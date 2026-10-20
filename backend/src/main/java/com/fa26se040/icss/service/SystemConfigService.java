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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    public double getDouble(ConfigKey configKey) {
        String raw = cache.get(configKey.getKey());
        if (raw == null) {
            log.warn("Config key [{}] not found in cache. Using default value: {}",
                    configKey.getKey(), configKey.getDefaultValue());
            return Double.parseDouble(configKey.getDefaultValue());
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid double value [{}] for config key [{}]. Using default: {}",
                    raw, configKey.getKey(), configKey.getDefaultValue());
            return Double.parseDouble(configKey.getDefaultValue());
        }
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
        return update(key, rawValue, null, actorEmail);
    }

    @Transactional
    public SystemConfigResponse update(String key, String rawValue, String reason, String actorEmail) {
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
                .reason(reason)
                .build();
        changeLogRepository.save(logEntry);

        // Update in-memory cache immediately so in-transaction reads see the new value
        cache.put(key, value);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if ("AI_AFTER_HOUR_START".equals(key) || "AI_AFTER_HOUR_END".equals(key)) {
                        syncAfterHourToAi();
                    } else if ("AI_TRACK_MAX_INACTIVE_SECONDS".equals(key) || "AI_UNKNOWN_ALERT_COOLDOWN_SECONDS".equals(key) || "AI_FACE_MATCH_THRESHOLD".equals(key)) {
                        syncAiRuntimeConfig();
                    }
                    checkEventModeLimitsAndNotifyFm(key);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        if (oldValue != null) {
                            cache.put(key, oldValue);
                        } else {
                            cache.remove(key);
                        }
                    }
                }
            });
        } else {
            if ("AI_AFTER_HOUR_START".equals(key) || "AI_AFTER_HOUR_END".equals(key)) {
                syncAfterHourToAi();
            } else if ("AI_TRACK_MAX_INACTIVE_SECONDS".equals(key) || "AI_UNKNOWN_ALERT_COOLDOWN_SECONDS".equals(key) || "AI_FACE_MATCH_THRESHOLD".equals(key)) {
                syncAiRuntimeConfig();
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
        StringBuilder sb = new StringBuilder("Giới hạn chế độ sự kiện đã thay đổi. Các khu vực vượt giới hạn mới: ");
        for (int i = 0; i < violating.size(); i++) {
            com.fa26se040.icss.entity.Area a = violating.get(i);
            if (i > 0) sb.append("; ");
            sb.append(a.getName());
            boolean hasDetail = false;
            if (a.isEventActive(java.time.OffsetDateTime.now()) && a.getOpenUntil() != null) {
                sb.append(" (kết thúc: ").append(dtf.format(a.getOpenUntil())).append(")");
                hasDetail = true;
            }
            List<com.fa26se040.icss.entity.AreaEventSchedule> vScheds = areaService.getViolatingSchedules(a.getId(), maxHours, windowDays, budgetHours);
            if (!vScheds.isEmpty()) {
                sb.append(" [Lịch vi phạm: ");
                for (int j = 0; j < vScheds.size(); j++) {
                    if (j > 0) sb.append(", ");
                    com.fa26se040.icss.entity.AreaEventSchedule sc = vScheds.get(j);
                    sb.append(dtf.format(sc.getStartAt())).append(" - ").append(dtf.format(sc.getEndAt()));
                }
                sb.append("]");
            } else if (!hasDetail && a.getOpenUntil() != null) {
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

    private void syncAiRuntimeConfig() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of(
                    "max_inactive_seconds", getDouble(ConfigKey.AI_TRACK_MAX_INACTIVE_SECONDS),
                    "unknown_alert_cooldown", getInt(ConfigKey.AI_UNKNOWN_ALERT_COOLDOWN_SECONDS),
                    "face_match_threshold", getDouble(ConfigKey.AI_FACE_MATCH_THRESHOLD)
            );
            aiRestTemplate.postForObject(aiServiceUrl + "/api/v1/system-config/ai-runtime",
                    new HttpEntity<>(body, headers), Map.class);
            log.info("Synced AI runtime config to AI service: {}", body);
        } catch (Exception e) {
            log.warn("Could not sync AI runtime configuration to AI service: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application ready - synchronizing initial AI configurations to AI service...");
        syncAfterHourToAi();
        syncAiRuntimeConfig();
    }

    public Map<String, Object> getAiRuntimeConfigMap() {
        return Map.of(
                "face_match_threshold", getDouble(ConfigKey.AI_FACE_MATCH_THRESHOLD),
                "after_hour_start", getString(ConfigKey.AI_AFTER_HOUR_START),
                "after_hour_end", getString(ConfigKey.AI_AFTER_HOUR_END),
                "max_inactive_seconds", getDouble(ConfigKey.AI_TRACK_MAX_INACTIVE_SECONDS),
                "unknown_alert_cooldown", getInt(ConfigKey.AI_UNKNOWN_ALERT_COOLDOWN_SECONDS)
        );
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
                validateReasonLengthConfig(config.getConfigKey(), intVal);
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
                if (ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getKey().equals(config.getConfigKey())) {
                    validateSponsorAllowedAreaTypes(value);
                }
            }
            default -> log.warn("Unknown dataType [{}] for config [{}]", dataType, config.getConfigKey());
        }
    }

    /**
     * Checklist 6.1 (V82): độ dài lý do phải thoả 1 ≤ REASON_MIN_LENGTH < REASON_MAX_LENGTH ≤ độ dài cột lưu lý do
     * ({@link ReasonValidator#STORAGE_MAX_LENGTH}). So với giá trị hiện hành của khoá còn lại. Kiểm trước khoảng min/max
     * trong metadata để câu báo lỗi nói rõ lý do.
     */
    private void validateReasonLengthConfig(String key, int value) {
        if (ConfigKey.REASON_MIN_LENGTH.getKey().equals(key)) {
            if (value < 1) {
                throw new IllegalArgumentException("Độ dài tối thiểu của lý do phải từ 1 ký tự trở lên");
            }
            int currentMax = getInt(ConfigKey.REASON_MAX_LENGTH);
            if (value >= currentMax) {
                throw new IllegalArgumentException("Độ dài tối thiểu của lý do (" + value
                        + ") phải nhỏ hơn độ dài tối đa đang cấu hình (" + currentMax + ")");
            }
        } else if (ConfigKey.REASON_MAX_LENGTH.getKey().equals(key)) {
            if (value > ReasonValidator.STORAGE_MAX_LENGTH) {
                throw new IllegalArgumentException("Độ dài tối đa của lý do không được vượt quá "
                        + ReasonValidator.STORAGE_MAX_LENGTH + " ký tự (độ dài cột lưu lý do trong cơ sở dữ liệu)");
            }
            int currentMin = getInt(ConfigKey.REASON_MIN_LENGTH);
            if (value <= currentMin) {
                throw new IllegalArgumentException("Độ dài tối đa của lý do (" + value
                        + ") phải lớn hơn độ dài tối thiểu đang cấu hình (" + currentMin + ")");
            }
        }
    }

    private void validateSponsorAllowedAreaTypes(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Danh sách loại khu vực cho phép bảo lãnh không được để trống");
        }
        String[] parts = value.split(",");
        boolean hasValid = false;
        for (String raw : parts) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            com.fa26se040.icss.enums.AreaLevel level;
            try {
                level = com.fa26se040.icss.enums.AreaLevel.valueOf(token);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Loại khu vực không hợp lệ: " + token);
            }
            if (level == com.fa26se040.icss.enums.AreaLevel.PUBLIC) {
                throw new IllegalArgumentException("Không cho phép bảo lãnh đối với khu vực Công khai (PUBLIC)");
            }
            if (level == com.fa26se040.icss.enums.AreaLevel.HIGHLY_CONFIDENTIAL) {
                throw new IllegalArgumentException("Không cho phép bảo lãnh đối với khu vực Tuyệt mật (HIGHLY_CONFIDENTIAL)");
            }
            hasValid = true;
        }
        if (!hasValid) {
            throw new IllegalArgumentException("Danh sách loại khu vực cho phép bảo lãnh không được để trống");
        }
    }

    public java.util.Set<com.fa26se040.icss.enums.AreaLevel> getSponsorAllowedAreaLevels() {
        String raw = getString(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES);
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of(com.fa26se040.icss.enums.AreaLevel.CONFIDENTIAL);
        }
        java.util.Set<com.fa26se040.icss.enums.AreaLevel> levels = new java.util.HashSet<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                try {
                    com.fa26se040.icss.enums.AreaLevel al = com.fa26se040.icss.enums.AreaLevel.valueOf(token);
                    if (al != com.fa26se040.icss.enums.AreaLevel.PUBLIC && al != com.fa26se040.icss.enums.AreaLevel.HIGHLY_CONFIDENTIAL) {
                        levels.add(al);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return levels.isEmpty() ? java.util.Set.of(com.fa26se040.icss.enums.AreaLevel.CONFIDENTIAL) : levels;
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
                changedByName,
                changeLog.getReason()
        );
    }
}
