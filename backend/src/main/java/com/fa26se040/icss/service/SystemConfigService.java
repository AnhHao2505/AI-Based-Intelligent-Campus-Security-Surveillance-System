package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.systemconfig.SystemConfigChangeLogResponse;
import com.fa26se040.icss.dto.systemconfig.SystemConfigResponse;
import com.fa26se040.icss.entity.SystemConfiguration;
import com.fa26se040.icss.entity.SystemConfigurationChangeLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.SystemConfigurationChangeLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemConfigService {

    private final SystemConfigurationRepository systemConfigurationRepository;
    private final SystemConfigurationChangeLogRepository changeLogRepository;
    private final UserRepository userRepository;
    private final org.springframework.beans.factory.ObjectProvider<AreaService> areaServiceProvider;
    private final org.springframework.beans.factory.ObjectProvider<InAppNotificationService> notificationServiceProvider;
    private final RestTemplate aiRestTemplate = new RestTemplate();

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    private volatile Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initCache() {
        refreshCache();
    }

    public synchronized void refreshCache() {
        try {
            List<SystemConfiguration> configs = systemConfigurationRepository.findAll();
            Map<String, String> newCache = new ConcurrentHashMap<>();
            for (SystemConfiguration config : configs) {
                if (config.getConfigKey() != null && config.getConfigValue() != null) {
                    newCache.put(config.getConfigKey(), config.getConfigValue());
                }
            }
            this.cache = newCache;
            log.info("SystemConfiguration cache loaded with {} entries", newCache.size());
        } catch (Exception e) {
            log.error("Failed to load SystemConfiguration cache from database", e);
        }
    }

    public int getInt(ConfigKey configKey) {
        String raw = cache.get(configKey.getKey());
        if (raw == null) {
            log.warn("Config key [{}] not found in cache. Using default value: {}",
                    configKey.getKey(), configKey.getDefaultValue());
            return Integer.parseInt(configKey.getDefaultValue());
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value [{}] for config key [{}]. Using default: {}",
                    raw, configKey.getKey(), configKey.getDefaultValue());
            return Integer.parseInt(configKey.getDefaultValue());
        }
    }

    public String getString(ConfigKey configKey) {
        String value = cache.get(configKey.getKey());
        return value == null || value.isBlank() ? configKey.getDefaultValue() : value.trim();
    }

    public boolean getBoolean(ConfigKey configKey) {
        String raw = cache.get(configKey.getKey());
        if (raw == null) {
            log.warn("Config key [{}] not found in cache. Using default value: {}",
                    configKey.getKey(), configKey.getDefaultValue());
            return Boolean.parseBoolean(configKey.getDefaultValue());
        }
        String trimmed = raw.trim();
        if (!trimmed.equalsIgnoreCase("true") && !trimmed.equalsIgnoreCase("false")) {
            log.warn("Invalid boolean value [{}] for config key [{}]. Using default: {}",
                    raw, configKey.getKey(), configKey.getDefaultValue());
            return Boolean.parseBoolean(configKey.getDefaultValue());
        }
        return Boolean.parseBoolean(trimmed);
    }

    @Transactional(readOnly = true)
    public List<SystemConfigResponse> getAll() {
        return systemConfigurationRepository.findAllByOrderByConfigGroupAscDisplayOrderAsc().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<SystemConfigChangeLogResponse> getHistory(String configKey, Pageable pageable) {
        if (!systemConfigurationRepository.existsById(configKey)) {
            throw new ResourceNotFoundException("Không tìm thấy cấu hình với mã: " + configKey);
        }
        return changeLogRepository.findByConfigKeyOrderByChangedAtDesc(configKey, pageable)
                .map(this::mapToChangeLogResponse);
    }

    @Transactional
    public SystemConfigResponse update(String key, String rawValue, String actorEmail) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            throw new IllegalArgumentException("Giá trị cấu hình không được để trống");
        }
        String value = rawValue.trim();

        SystemConfiguration config = systemConfigurationRepository.findById(key)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy cấu hình với mã: " + key));

        if (!Boolean.TRUE.equals(config.getEditable())) {
            throw new IllegalArgumentException("Cấu hình này không được phép chỉnh sửa");
        }

        validateValue(config, value);

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng: " + actorEmail));

        String oldValue = config.getConfigValue();
        OffsetDateTime now = OffsetDateTime.now();

        config.setConfigValue(value);
        config.setUpdatedBy(actor);
        config.setUpdatedAt(now);

        SystemConfiguration saved = systemConfigurationRepository.save(config);

        SystemConfigurationChangeLog logEntry = SystemConfigurationChangeLog.builder()
                .configKey(key)
                .oldValue(oldValue)
                .newValue(value)
                .changedBy(actor)
                .changedAt(now)
                .build();
        changeLogRepository.save(logEntry);

        // Update in-memory cache only after transaction commits successfully
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.put(key, value);
                    if ("AI_AFTER_HOUR_START".equals(key) || "AI_AFTER_HOUR_END".equals(key)) {
                        syncAfterHourToAi();
                    }
                    checkEventModeLimitsAndNotifyFm(key);
                }
            });
        } else {
            cache.put(key, value);
            if ("AI_AFTER_HOUR_START".equals(key) || "AI_AFTER_HOUR_END".equals(key)) {
                syncAfterHourToAi();
            }
            checkEventModeLimitsAndNotifyFm(key);
        }

        log.info("Cập nhật SystemConfiguration thành công: key={}, old={}, new={}, actor={}",
                key, oldValue, value, actorEmail);

        return mapToResponse(saved);
    }

    private void checkEventModeLimitsAndNotifyFm(String key) {
        if (!"EVENT_MODE_MAX_HOURS".equals(key) && !"EVENT_MODE_WINDOW_DAYS".equals(key) && !"EVENT_MODE_BUDGET_HOURS".equals(key)) {
            return;
        }

        AreaService areaService = areaServiceProvider.getIfAvailable();
        InAppNotificationService notificationService = notificationServiceProvider.getIfAvailable();
        if (areaService == null || notificationService == null) {
            return;
        }

        int maxHours = areaService.getEventModeMaxHours();
        int windowDays = areaService.getEventModeWindowDays();
        int budgetHours = areaService.getEventModeBudgetHours();

        List<com.fa26se040.icss.entity.Area> violating = areaService.findAreasViolatingNewEventLimits(maxHours, windowDays, budgetHours);
        if (violating.isEmpty()) {
            log.info("No active event areas violate new event limits after config key {} updated", key);
            return;
        }

        List<com.fa26se040.icss.entity.User> activeFms = userRepository.findActiveUsersByRole(com.fa26se040.icss.enums.Role.FACILITY_MANAGER);
        if (activeFms.isEmpty()) {
            return;
        }

        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        StringBuilder sb = new StringBuilder("Giới hạn chế độ sự kiện đã thay đổi. Các khu vực đang mở sự kiện vượt giới hạn mới: ");
        for (int i = 0; i < violating.size(); i++) {
            com.fa26se040.icss.entity.Area a = violating.get(i);
            if (i > 0) sb.append("; ");
            sb.append(a.getName());
            if (a.getOpenUntil() != null) {
                sb.append(" (kết thúc: ").append(dtf.format(a.getOpenUntil())).append(")");
            }
        }

        notificationService.createForUsers(
                activeFms,
                com.fa26se040.icss.enums.NotificationType.EVENT_MODE_LIMIT_CHANGED,
                "Giới hạn chế độ sự kiện đã thay đổi",
                sb.toString(),
                violating.get(0).getId(),
                "AREA"
        );
    }

    private void syncAfterHourToAi() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = Map.of(
                    "start", getString(ConfigKey.AI_AFTER_HOUR_START),
                    "end", getString(ConfigKey.AI_AFTER_HOUR_END));
            aiRestTemplate.postForObject(aiServiceUrl + "/api/v1/system-config/after-hour",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.warn("Could not sync after-hour configuration to AI service: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public int reloadCache() {
        refreshCache();
        return cache.size();
    }

    private void validateValue(SystemConfiguration config, String value) {
        String dataType = config.getDataType() != null ? config.getDataType().toUpperCase() : "STRING";
        switch (dataType) {
            case "INTEGER" -> {
                int intVal;
                try {
                    intVal = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Giá trị cấu hình phải là số nguyên");
                }
                if (config.getMinValue() != null && intVal < config.getMinValue().intValue()) {
                    throw new IllegalArgumentException("Giá trị không được nhỏ hơn " + config.getMinValue().intValue());
                }
                if (config.getMaxValue() != null && intVal > config.getMaxValue().intValue()) {
                    throw new IllegalArgumentException("Giá trị không được lớn hơn " + config.getMaxValue().intValue());
                }
                if ("EVENT_MODE_MIN_MINUTES".equals(config.getConfigKey())) {
                    int maxHours = getInt(ConfigKey.EVENT_MODE_MAX_HOURS);
                    if (intVal > maxHours * 60) {
                        throw new IllegalArgumentException("Thời lượng tối thiểu (EVENT_MODE_MIN_MINUTES) không được lớn hơn thời lượng tối đa (EVENT_MODE_MAX_HOURS * 60 phút)");
                    }
                }
                if ("EVENT_MODE_MAX_HOURS".equals(config.getConfigKey())) {
                    int minMinutes = getInt(ConfigKey.EVENT_MODE_MIN_MINUTES);
                    if (intVal * 60 < minMinutes) {
                        throw new IllegalArgumentException("Thời lượng tối đa (EVENT_MODE_MAX_HOURS * 60 phút) không được nhỏ hơn thời lượng tối thiểu (EVENT_MODE_MIN_MINUTES)");
                    }
                }
            }
            case "DECIMAL" -> {
                BigDecimal decVal;
                try {
                    decVal = new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Giá trị cấu hình phải là số thập phân");
                }
                if (config.getMinValue() != null && decVal.compareTo(config.getMinValue()) < 0) {
                    throw new IllegalArgumentException("Giá trị không được nhỏ hơn " + config.getMinValue());
                }
                if (config.getMaxValue() != null && decVal.compareTo(config.getMaxValue()) > 0) {
                    throw new IllegalArgumentException("Giá trị không được lớn hơn " + config.getMaxValue());
                }
            }
            case "BOOLEAN" -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("Giá trị cấu hình phải là 'true' hoặc 'false'");
                }
            }
            case "STRING" -> {
                if (value.length() > 255) {
                    throw new IllegalArgumentException("Độ dài giá trị cấu hình không được vượt quá 255 ký tự");
                }
                if ("AI_AFTER_HOUR_START".equals(config.getConfigKey()) || "AI_AFTER_HOUR_END".equals(config.getConfigKey())) {
                    try {
                        LocalTime.parse(value);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Giờ after-hour phải theo định dạng HH:mm");
                    }
                }
            }
            default -> log.warn("Unknown dataType [{}] for config [{}]", dataType, config.getConfigKey());
        }
    }

    private SystemConfigResponse mapToResponse(SystemConfiguration config) {
        String updatedByEmail = null;
        String updatedByName = null;
        if (config.getUpdatedBy() != null) {
            updatedByEmail = config.getUpdatedBy().getEmail();
            updatedByName = config.getUpdatedBy().getFullName();
        }
        return new SystemConfigResponse(
                config.getConfigKey(),
                config.getConfigValue(),
                config.getDataType(),
                config.getMinValue(),
                config.getMaxValue(),
                config.getUnit(),
                config.getConfigGroup(),
                config.getDisplayOrder(),
                config.getDescription(),
                config.getEditable(),
                config.getUpdatedAt(),
                updatedByEmail,
                updatedByName
        );
    }

    private SystemConfigChangeLogResponse mapToChangeLogResponse(SystemConfigurationChangeLog changeLog) {
        String changedByEmail = null;
        String changedByName = null;
        if (changeLog.getChangedBy() != null) {
            changedByEmail = changeLog.getChangedBy().getEmail();
            changedByName = changeLog.getChangedBy().getFullName();
        }
        return new SystemConfigChangeLogResponse(
                changeLog.getId(),
                changeLog.getConfigKey(),
                changeLog.getOldValue(),
                changeLog.getNewValue(),
                changeLog.getChangedAt(),
                changedByEmail,
                changedByName
        );
    }
}
