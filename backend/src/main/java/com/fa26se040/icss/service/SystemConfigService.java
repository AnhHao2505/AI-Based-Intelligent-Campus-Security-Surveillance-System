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
import com.fa26se040.icss.config.AiServiceRestTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.HashMap;
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
    private final AiServiceRestTemplate aiRestTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    /** PR-AUDIT: field injection để giữ constructor (test dựng service bằng tay); null thì không ghi audit. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditService auditService;

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

        // PR-AUDIT (B3): UPDATE / SYSTEM_CONFIG cùng transaction (giữ change log riêng ở trên). Lỗi ghi audit -> rollback,
        // cache bên dưới chưa bị ghi vì exception ném trước dòng cache.put.
        if (auditService != null) {
            auditService.record(com.fa26se040.icss.enums.AuditTargetType.SYSTEM_CONFIG,
                    com.fa26se040.icss.enums.AuditAction.UPDATE, key, null, null,
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.SystemConfigAuditSnapshot(key, oldValue),
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.SystemConfigAuditSnapshot(key, value),
                    reason, com.fa26se040.icss.dto.audit.AuditActor.user(actor));
        }

        // Update in-memory cache immediately so in-transaction reads see the new value
        cache.put(key, value);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if ("AI_AFTER_HOUR_START".equals(key) || "AI_AFTER_HOUR_END".equals(key)) {
                        syncAfterHourToAi();
                    } else if ("AI_TRACK_MAX_INACTIVE_SECONDS".equals(key) || "AI_UNKNOWN_ALERT_COOLDOWN_SECONDS".equals(key) || "AI_FACE_MATCH_THRESHOLD".equals(key)
                            || "AI_ACCESS_CHECK_TIMEOUT_SECONDS".equals(key) || "AI_ACCESS_DECISION_CACHE_TTL".equals(key) || "AI_ACCESS_DENY_CACHE_TTL".equals(key)) {
                        syncAiRuntimeConfig();
                    }
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
            } else if ("AI_TRACK_MAX_INACTIVE_SECONDS".equals(key) || "AI_UNKNOWN_ALERT_COOLDOWN_SECONDS".equals(key) || "AI_FACE_MATCH_THRESHOLD".equals(key)
                    || "AI_ACCESS_CHECK_TIMEOUT_SECONDS".equals(key) || "AI_ACCESS_DECISION_CACHE_TTL".equals(key) || "AI_ACCESS_DENY_CACHE_TTL".equals(key)) {
                syncAiRuntimeConfig();
            }
        }

        // EV-N8 (V115): đổi giờ ban đêm -> hệ thống cắt / huỷ lịch, cắt / tắt sự kiện đang mở trong CÙNG transaction.
        // Gọi SAU khi đã đăng ký afterCompletion ở trên: lỗi ở đây rollback cả việc lưu cấu hình và cache về giá trị cũ.
        // EV-N1: không còn thông báo "khu vực vượt giới hạn mới" khi đổi cấu hình sự kiện (BR-EV-29 bỏ).
        List<com.fa26se040.icss.dto.area.EventNightAdjustment> eventAdjustments = null;
        if (isAfterHoursKey(key)) {
            AreaService areaService = areaServiceProvider.getIfAvailable();
            if (areaService != null) {
                eventAdjustments = areaService.applyAfterHoursChange(now);
            }
        }

        log.info("Cập nhật SystemConfiguration thành công: key={}, old={}, new={}, actor={}",
                key, oldValue, value, actorEmail);

        return mapToResponse(saved).withEventAdjustments(eventAdjustments);
    }

    private static boolean isAfterHoursKey(String key) {
        return ConfigKey.AI_AFTER_HOUR_START.getKey().equals(key) || ConfigKey.AI_AFTER_HOUR_END.getKey().equals(key);
    }

    private void syncAfterHourToAi() {
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            edgeMqttControlService.publishSystemConfig(getAiRuntimeConfigMap());
            if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
                return;
            }
        }
        if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
            return;
        }
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
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            edgeMqttControlService.publishSystemConfig(getAiRuntimeConfigMap());
            if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
                return;
            }
        }
        if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("max_inactive_seconds", getDouble(ConfigKey.AI_TRACK_MAX_INACTIVE_SECONDS));
            body.put("unknown_alert_cooldown", getInt(ConfigKey.AI_UNKNOWN_ALERT_COOLDOWN_SECONDS));
            body.put("face_match_threshold", getDouble(ConfigKey.AI_FACE_MATCH_THRESHOLD));
            body.put("access_check_timeout_seconds", getDouble(ConfigKey.AI_ACCESS_CHECK_TIMEOUT_SECONDS));
            body.put("access_decision_cache_ttl", getDouble(ConfigKey.AI_ACCESS_DECISION_CACHE_TTL));
            body.put("access_deny_cache_ttl", getDouble(ConfigKey.AI_ACCESS_DENY_CACHE_TTL));
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
        Map<String, Object> map = new HashMap<>();
        map.put("face_match_threshold", getDouble(ConfigKey.AI_FACE_MATCH_THRESHOLD));
        map.put("after_hour_start", getString(ConfigKey.AI_AFTER_HOUR_START));
        map.put("after_hour_end", getString(ConfigKey.AI_AFTER_HOUR_END));
        map.put("max_inactive_seconds", getDouble(ConfigKey.AI_TRACK_MAX_INACTIVE_SECONDS));
        map.put("unknown_alert_cooldown", getInt(ConfigKey.AI_UNKNOWN_ALERT_COOLDOWN_SECONDS));
        map.put("access_check_timeout_seconds", getDouble(ConfigKey.AI_ACCESS_CHECK_TIMEOUT_SECONDS));
        map.put("access_decision_cache_ttl", getDouble(ConfigKey.AI_ACCESS_DECISION_CACHE_TTL));
        map.put("access_deny_cache_ttl", getDouble(ConfigKey.AI_ACCESS_DENY_CACHE_TTL));
        return map;
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
                // V115 (EV-N1): bỏ ràng buộc chéo EVENT_MODE_MIN_MINUTES ≤ EVENT_MODE_MAX_HOURS × 60 cùng với EVENT_MODE_MAX_HOURS
                validateNotificationScanConfig(config.getConfigKey(), intVal);
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
                if (ConfigKey.INCIDENT_SEVERITY_DEFAULT.getKey().equals(config.getConfigKey())) {
                    validateIncidentSeverityDefault(value);
                }
            }
            default -> log.warn("Unknown dataType [{}] for config [{}]", dataType, config.getConfigKey());
        }
    }

    /**
     * V113: ràng buộc chéo chu kỳ quét của job thông báo, kiểm khi sửa khoá nào trong cặp cũng được (so với giá trị hiện hành
     * của khoá còn lại):
     * <ul>
     *   <li>NOTIFICATION_STARTING_SOON_SCAN_MINUTES &lt; NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES: quét thưa hơn thời gian báo
     *   trước thì đơn có thể lọt, không được nhắc trước giờ bắt đầu</li>
     *   <li>NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES ≤ NOTIFICATION_PENDING_OVERDUE_HOURS × 60</li>
     * </ul>
     */
    private void validateNotificationScanConfig(String key, int value) {
        String scanSoon = ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES.getKey();
        String lead = ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES.getKey();
        String scanOverdue = ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES.getKey();
        String overdueHours = ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS.getKey();

        if (scanSoon.equals(key) || lead.equals(key)) {
            int currentScan = getInt(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES);
            int currentLead = getInt(ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES);
            int newScan = scanSoon.equals(key) ? value : currentScan;
            int newLead = lead.equals(key) ? value : currentLead;
            if (newScan >= newLead) {
                throw new IllegalArgumentException("Chu kỳ quét nhắc sắp đến giờ (" + scanSoon + ") phải nhỏ hơn thời gian báo trước ("
                        + lead + "). Giá trị hiện hành: " + scanSoon + " = " + currentScan + " phút, " + lead + " = " + currentLead
                        + " phút; giá trị vừa nhập: " + key + " = " + value + " phút.");
            }
        }
        if (scanOverdue.equals(key) || overdueHours.equals(key)) {
            int currentScan = getInt(ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES);
            int currentHours = getInt(ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS);
            int newScan = scanOverdue.equals(key) ? value : currentScan;
            int newHours = overdueHours.equals(key) ? value : currentHours;
            if (newScan > newHours * 60) {
                String unit = overdueHours.equals(key) ? " giờ" : " phút";
                throw new IllegalArgumentException("Chu kỳ quét đơn tồn đọng (" + scanOverdue + ") không được lớn hơn ngưỡng tồn đọng ("
                        + overdueHours + " × 60 phút). Giá trị hiện hành: " + scanOverdue + " = " + currentScan + " phút, "
                        + overdueHours + " = " + currentHours + " giờ; giá trị vừa nhập: " + key + " = " + value + unit + ".");
            }
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

    /** V90 (BR-SEV-03): INCIDENT_SEVERITY_DEFAULT phải là đúng tên một mức của IncidentSeverity. */
    private void validateIncidentSeverityDefault(String value) {
        try {
            com.fa26se040.icss.enums.IncidentSeverity.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mức độ mặc định của sự cố phải là một trong: "
                    + java.util.Arrays.stream(com.fa26se040.icss.enums.IncidentSeverity.values())
                            .map(Enum::name)
                            .collect(java.util.stream.Collectors.joining(", ")));
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
