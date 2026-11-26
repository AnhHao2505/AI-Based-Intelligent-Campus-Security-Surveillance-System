package com.fa26se040.icss.service;

import com.fa26se040.icss.controller.SystemConfigController;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.dto.systemconfig.SystemConfigResponse;
import com.fa26se040.icss.entity.SystemConfiguration;
import com.fa26se040.icss.entity.SystemConfigurationChangeLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.repository.SystemConfigurationChangeLogRepository;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

            // Trong transaction: Cache đã được cập nhật giá trị mới (50) để các lời gọi cùng transaction đọc được
            assertEquals(50, systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS));

            // Giả lập rollback transaction: Cache phải được khôi phục về giá trị cũ (30)
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertEquals(30, systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("getDouble returns parsed double value from cache or default fallback")
    void testGetDouble_ValidAndDefault() {
        SystemConfiguration config = SystemConfiguration.builder()
                .configKey(ConfigKey.RECORDING_RETENTION_HOURS.getKey())
                .configValue("12.5")
                .dataType("DECIMAL")
                .build();
        when(systemConfigurationRepository.findAll()).thenReturn(List.of(config));
        systemConfigService.initCache();

        assertEquals(12.5, systemConfigService.getDouble(ConfigKey.RECORDING_RETENTION_HOURS));
        assertEquals(30, systemConfigService.getInt(ConfigKey.AI_UNKNOWN_ALERT_COOLDOWN_SECONDS));
    }

    @Test
    @DisplayName("V150: getAiRuntimeConfigMap gửi 11 khoá admin chỉnh, gồm night_allowed_access_sources mặc định 4 nguồn")
    void testGetAiRuntimeConfigMap_AdminKeysWithDefaults() {
        when(systemConfigurationRepository.findAll()).thenReturn(List.of());
        systemConfigService.initCache();

        var map = systemConfigService.getAiRuntimeConfigMap();
        assertNotNull(map);
        assertEquals(11, map.size());
        assertEquals(0.50, map.get("face_match_threshold"));
        assertEquals(0.35, map.get("detection_confidence"));
        assertEquals(0.45, map.get("face_confidence"));
        assertEquals("22:00", map.get("after_hour_start"));
        assertEquals("06:00", map.get("after_hour_end"));
        assertEquals(
                List.of("ASSIGNED_PERSONNEL", "ACCESS_REQUEST", "GUEST_VISIT", "INCIDENT_RESPONSE"),
                map.get("night_allowed_access_sources")
        );
        assertEquals(30, map.get("unknown_alert_cooldown"));
        assertEquals(30, map.get("unauthorized_alert_cooldown"));
        assertEquals(5, map.get("suspicious_motion_cooldown"));
        assertEquals(0.5, map.get("recording_retention_hours"));
        assertEquals(0.0, map.get("incident_no_detection_timeout_seconds"));
    }

    @Test
    @DisplayName("V150: cập nhật AI_NIGHT_ALLOWED_ACCESS_SOURCES hợp lệ -> chuẩn hoá CSV, ghi audit, cập nhật runtime-config và sync MQTT")
    void updateNightAllowedAccessSources_ValidValue_NormalizesAuditsAndSyncs() {
        String key = ConfigKey.AI_NIGHT_ALLOWED_ACCESS_SOURCES.getKey();
        SystemConfiguration nightConfig = SystemConfiguration.builder()
                .configKey(key)
                .configValue(ConfigKey.AI_NIGHT_ALLOWED_ACCESS_SOURCES.getDefaultValue())
                .dataType("STRING")
                .configGroup("AI_CONFIG")
                .displayOrder(12)
                .description("Nguồn quyền miễn giờ giới nghiêm")
                .editable(true)
                .build();

        AuditService mockAudit = mock(AuditService.class);
        EdgeMqttControlService mockMqtt = mock(EdgeMqttControlService.class);
        when(mockMqtt.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(systemConfigService, "auditService", mockAudit);
        ReflectionTestUtils.setField(systemConfigService, "edgeMqttControlService", mockMqtt);

        when(systemConfigurationRepository.findById(key)).thenReturn(Optional.of(nightConfig));
        when(userRepository.findByEmail(adminUser.getEmail())).thenReturn(Optional.of(adminUser));
        when(systemConfigurationRepository.save(any(SystemConfiguration.class))).thenAnswer(i -> i.getArgument(0));

        SystemConfigResponse response = systemConfigService.update(
                key,
                " guest_visit , ASSIGNED_PERSONNEL , GUEST_VISIT , open_event ",
                "Mở thử nghiệm sự kiện tối và bỏ đơn tạm thời",
                adminUser.getEmail()
        );

        assertEquals("ASSIGNED_PERSONNEL,GUEST_VISIT,OPEN_EVENT", response.configValue());
        assertNotNull(response.options());
        assertEquals(6, response.options().size());
        assertEquals(
                List.of("ASSIGNED_PERSONNEL", "GUEST_VISIT", "OPEN_EVENT"),
                systemConfigService.getAiRuntimeConfigMap().get("night_allowed_access_sources")
        );

        ArgumentCaptor<SystemConfigurationChangeLog> logCaptor = ArgumentCaptor.forClass(SystemConfigurationChangeLog.class);
        verify(changeLogRepository).save(logCaptor.capture());
        assertEquals("ASSIGNED_PERSONNEL,GUEST_VISIT,OPEN_EVENT", logCaptor.getValue().getNewValue());
        verify(mockAudit).record(eq(AuditTargetType.SYSTEM_CONFIG), eq(AuditAction.UPDATE), eq(key),
                any(), any(), any(), any(), eq("Mở thử nghiệm sự kiện tối và bỏ đơn tạm thời"), any(AuditActor.class));
        verify(mockMqtt).publishSystemConfig(anyMap());
    }

    @Test
    @DisplayName("V150: từ chối giá trị lạ, NONE, phần tử rỗng hoặc danh sách rỗng cho AI_NIGHT_ALLOWED_ACCESS_SOURCES")
    void updateNightAllowedAccessSources_InvalidOrEmpty_ThrowsIllegalArgumentException() {
        String key = ConfigKey.AI_NIGHT_ALLOWED_ACCESS_SOURCES.getKey();
        SystemConfiguration nightConfig = SystemConfiguration.builder()
                .configKey(key)
                .configValue(ConfigKey.AI_NIGHT_ALLOWED_ACCESS_SOURCES.getDefaultValue())
                .dataType("STRING")
                .configGroup("AI_CONFIG")
                .editable(true)
                .build();

        // Danh sách rỗng bị chặn ngay trước findById
        for (String empty : List.of("", "   ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> systemConfigService.update(key, empty, "Lý do kiểm thử", adminUser.getEmail()));
        }

        when(systemConfigurationRepository.findById(key)).thenReturn(Optional.of(nightConfig));
        for (String invalid : List.of(
                "UNKNOWN_SOURCE",
                "NONE",
                "ASSIGNED_PERSONNEL,INVALID_VAL",
                "ASSIGNED_PERSONNEL,",
                ",ACCESS_REQUEST",
                "ASSIGNED_PERSONNEL,,GUEST_VISIT"
        )) {
            assertThrows(IllegalArgumentException.class,
                    () -> systemConfigService.update(key, invalid, "Lý do kiểm thử", adminUser.getEmail()),
                    "Phải từ chối giá trị không hợp lệ: " + invalid);
        }
        verify(systemConfigurationRepository, never()).save(any());
    }

    @Test
    @DisplayName("V150: getAll chỉ gắn options cho AI_NIGHT_ALLOWED_ACCESS_SOURCES, khoá khác giữ options = null")
    void getAll_OnlyNightAllowedSourcesHasOptions() {
        SystemConfiguration nightConfig = SystemConfiguration.builder()
                .configKey(ConfigKey.AI_NIGHT_ALLOWED_ACCESS_SOURCES.getKey())
                .configValue(ConfigKey.AI_NIGHT_ALLOWED_ACCESS_SOURCES.getDefaultValue())
                .dataType("STRING")
                .configGroup("AI_CONFIG")
                .displayOrder(12)
                .description("Nguồn quyền miễn giờ giới nghiêm")
                .editable(true)
                .build();
        when(systemConfigurationRepository.findAllByOrderByConfigGroupAscDisplayOrderAsc())
                .thenReturn(List.of(maxMembersConfig, nightConfig));

        List<SystemConfigResponse> responses = systemConfigService.getAll();
        assertEquals(2, responses.size());
        assertNull(responses.get(0).options());
        assertNotNull(responses.get(1).options());
        assertEquals(6, responses.get(1).options().size());
    }

    @Test
    @DisplayName("V150: PATCH /api/system-configurations/AI_NIGHT_ALLOWED_ACCESS_SOURCES với giá trị lạ -> HTTP 400")
    void patchNightAllowedSources_InvalidValue_Returns400() throws Exception {
        String key = ConfigKey.AI_NIGHT_ALLOWED_ACCESS_SOURCES.getKey();
        SystemConfiguration nightConfig = SystemConfiguration.builder()
                .configKey(key)
                .configValue(ConfigKey.AI_NIGHT_ALLOWED_ACCESS_SOURCES.getDefaultValue())
                .dataType("STRING")
                .configGroup("AI_CONFIG")
                .editable(true)
                .build();
        when(systemConfigurationRepository.findById(key)).thenReturn(Optional.of(nightConfig));

        Validator noValidation = new Validator() {
            @Override
            public boolean supports(Class<?> clazz) {
                return true;
            }

            @Override
            public void validate(Object target, Errors errors) {
            }
        };
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new SystemConfigController(systemConfigService, mock(ReasonValidator.class), mock(GuestRules.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(noValidation)
                .build();

        mockMvc.perform(patch("/api/system-configurations/{configKey}", key)
                        .principal(new UsernamePasswordAuthenticationToken(adminUser.getEmail(), null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configValue\":\"ASSIGNED_PERSONNEL,STRANGE_SOURCE\",\"reason\":\"Kiểm thử từ chối giá trị lạ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.httpCode").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("STRANGE_SOURCE")));
    }

    @Test
    @DisplayName("V142: không gửi tham số kỹ thuật, để AI dùng giá trị .env")
    void testGetAiRuntimeConfigMap_OmitsTechnicalKeys() {
        when(systemConfigurationRepository.findAll()).thenReturn(List.of());
        systemConfigService.initCache();

        var map = systemConfigService.getAiRuntimeConfigMap();
        for (String removed : List.of("max_inactive_seconds", "access_check_timeout_seconds",
                "access_decision_cache_ttl", "access_deny_cache_ttl", "identity_grace_seconds",
                "identity_hold_seconds", "camera_unknown_min_interval", "recording_segment_seconds",
                "incident_start_delay_seconds")) {
            assertFalse(map.containsKey(removed), removed);
        }
    }

    @Test
    @DisplayName("V126+: mọi khoá AI runtime (trừ giờ ngoài giờ) đều kích hoạt đồng bộ xuống AI")
    void testAiRuntimeKeys_CoverAllRuntimeConfigKeys() {
        for (ConfigKey k : ConfigKey.values()) {
            String key = k.getKey();
            boolean isAiRuntime = key.startsWith("AI_")
                    || key.equals(ConfigKey.RECORDING_RETENTION_HOURS.getKey())
                    || key.equals(ConfigKey.INCIDENT_RECORDING_NO_DETECTION_TIMEOUT_SECONDS.getKey());
            boolean isAfterHour = key.equals("AI_AFTER_HOUR_START") || key.equals("AI_AFTER_HOUR_END");
            assertEquals(isAiRuntime && !isAfterHour, SystemConfigService.AI_RUNTIME_KEYS.contains(key), key);
        }
    }
}
