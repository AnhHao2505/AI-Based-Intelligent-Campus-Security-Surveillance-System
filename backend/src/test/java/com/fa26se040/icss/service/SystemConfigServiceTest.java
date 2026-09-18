package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.systemconfig.SystemConfigResponse;
import com.fa26se040.icss.entity.SystemConfiguration;
import com.fa26se040.icss.entity.SystemConfigurationChangeLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.SystemConfigurationChangeLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemConfigServiceTest {

    @Mock
    private SystemConfigurationRepository systemConfigurationRepository;

    @Mock
    private SystemConfigurationChangeLogRepository changeLogRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SystemConfigService systemConfigService;

    private User adminUser;
    private SystemConfiguration maxMembersConfig;
    private SystemConfiguration readOnlyConfig;

    @BeforeEach
    void setUp() {
        adminUser = User.builder()
                .id(UUID.randomUUID())
                .email("admin@fpt.edu.vn")
                .fullName("Quản trị viên")
                .build();

        maxMembersConfig = SystemConfiguration.builder()
                .configKey(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey())
                .configValue("30")
                .dataType("INTEGER")
                .minValue(BigDecimal.valueOf(1))
                .maxValue(BigDecimal.valueOf(100))
                .unit("người")
                .configGroup("ACCESS_REQUEST")
                .displayOrder(1)
                .description("Số thành viên tối đa")
                .editable(true)
                .updatedAt(OffsetDateTime.now())
                .build();

        readOnlyConfig = SystemConfiguration.builder()
                .configKey("READ_ONLY_KEY")
                .configValue("system_fixed")
                .dataType("STRING")
                .editable(false)
                .build();
    }

    @Test
    @DisplayName("1. getInt trả đúng giá trị từ cache")
    void getInt_ReturnsValueFromCache() {
        when(systemConfigurationRepository.findAll()).thenReturn(List.of(maxMembersConfig));
        systemConfigService.initCache();

        int result = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS);

        assertEquals(30, result);
    }

    @Test
    @DisplayName("2. getInt khi thiếu key trong cache thì trả defaultValue, không ném exception")
    void getInt_MissingKey_ReturnsDefaultValue() {
        when(systemConfigurationRepository.findAll()).thenReturn(List.of());
        systemConfigService.initCache();

        int result = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS);

        assertEquals(30, result);
    }

    @Test
    @DisplayName("3. update vượt maxValue ném IllegalArgumentException")
    void update_ExceedsMaxValue_ThrowsIllegalArgumentException() {
        when(systemConfigurationRepository.findById(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey()))
                .thenReturn(Optional.of(maxMembersConfig));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> systemConfigService.update(
                        ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey(),
                        "101",
                        adminUser.getEmail()
                )
        );

        assertTrue(ex.getMessage().contains("không được lớn hơn"));
    }

    @Test
    @DisplayName("4. update sai kiểu dữ liệu (chữ cho INTEGER) ném IllegalArgumentException")
    void update_InvalidType_ThrowsIllegalArgumentException() {
        when(systemConfigurationRepository.findById(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey()))
                .thenReturn(Optional.of(maxMembersConfig));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> systemConfigService.update(
                        ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey(),
                        "not_a_number",
                        adminUser.getEmail()
                )
        );

        assertTrue(ex.getMessage().contains("phải là số nguyên"));
    }

    @Test
    @DisplayName("5. update khi editable = false ném IllegalArgumentException")
    void update_NotEditable_ThrowsIllegalArgumentException() {
        when(systemConfigurationRepository.findById("READ_ONLY_KEY"))
                .thenReturn(Optional.of(readOnlyConfig));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> systemConfigService.update(
                        "READ_ONLY_KEY",
                        "new_val",
                        adminUser.getEmail()
                )
        );

        assertTrue(ex.getMessage().contains("không được phép chỉnh sửa"));
    }

    @Test
    @DisplayName("6. update thành công thì cache đổi ngay và ghi đúng 1 change log")
    void update_Success_UpdatesCacheAndWritesChangeLog() {
        when(systemConfigurationRepository.findAll()).thenReturn(List.of(maxMembersConfig));
        systemConfigService.initCache();
        assertEquals(30, systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS));

        when(systemConfigurationRepository.findById(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey()))
                .thenReturn(Optional.of(maxMembersConfig));
        when(userRepository.findByEmail(adminUser.getEmail())).thenReturn(Optional.of(adminUser));
        when(systemConfigurationRepository.save(any(SystemConfiguration.class))).thenAnswer(i -> i.getArgument(0));

        SystemConfigResponse response = systemConfigService.update(
                ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey(),
                "50",
                adminUser.getEmail()
        );

        assertNotNull(response);
        assertEquals("50", response.configValue());
        assertEquals("admin@fpt.edu.vn", response.updatedByEmail());

        // Cache đổi ngay lập tức
        assertEquals(50, systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS));

        // Ghi đúng 1 change log
        ArgumentCaptor<SystemConfigurationChangeLog> logCaptor = ArgumentCaptor.forClass(SystemConfigurationChangeLog.class);
        verify(changeLogRepository).save(logCaptor.capture());
        SystemConfigurationChangeLog savedLog = logCaptor.getValue();
        assertEquals("ACCESS_REQUEST_MAX_GROUP_MEMBERS", savedLog.getConfigKey());
        assertEquals("30", savedLog.getOldValue());
        assertEquals("50", savedLog.getNewValue());
        assertEquals(adminUser, savedLog.getChangedBy());
    }

    @Test
    @DisplayName("7. getBoolean với giá trị rác trả default không ném exception")
    void getBoolean_InvalidValue_ReturnsDefaultWithoutException() {
        SystemConfiguration invalidBoolConfig = SystemConfiguration.builder()
                .configKey(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.getKey())
                .configValue("invalid_boolean_string")
                .dataType("BOOLEAN")
                .editable(true)
                .build();

        when(systemConfigurationRepository.findAll()).thenReturn(List.of(invalidBoolConfig));
        systemConfigService.initCache();

        boolean result = systemConfigService.getBoolean(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE);

        // Default value của ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE là false
        assertFalse(result);
    }

    @Test
    @DisplayName("8. update thành công thì cache chỉ đổi SAU khi commit")
    void update_Success_CacheUpdatesOnlyAfterCommit() {
        when(systemConfigurationRepository.findAll()).thenReturn(List.of(maxMembersConfig));
        systemConfigService.initCache();
        assertEquals(30, systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS));

        when(systemConfigurationRepository.findById(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey()))
                .thenReturn(Optional.of(maxMembersConfig));
        when(userRepository.findByEmail(adminUser.getEmail())).thenReturn(Optional.of(adminUser));
        when(systemConfigurationRepository.save(any(SystemConfiguration.class))).thenAnswer(i -> i.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            systemConfigService.update(
                    ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey(),
                    "50",
                    adminUser.getEmail()
            );

            // Trước khi commit: Cache vẫn giữ giá trị cũ (30)
            assertEquals(30, systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS));

            // Giả lập commit transaction thành công
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            // Sau khi commit: Cache đã đổi sang giá trị mới (50)
            assertEquals(50, systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
