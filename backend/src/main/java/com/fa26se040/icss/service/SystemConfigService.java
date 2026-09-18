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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
                }
            });
        } else {
            cache.put(key, value);
        }

        log.info("Cập nhật SystemConfiguration thành công: key={}, old={}, new={}, actor={}",
                key, oldValue, value, actorEmail);

        return mapToResponse(saved);
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
