package com.fa26se040.icss.service;

import com.fa26se040.icss.config.AiServiceRestTemplate;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #262: BE từ chối AI_AFTER_HOUR_START = AI_AFTER_HOUR_END (so HH:mm, giá trị mới đã trim với giá trị hiện hành của khoá
 * còn lại) TRƯỚC mọi ghi. Service dựng bằng tay với repository / audit / MQTT / AreaService đều là mock nên chạy không cần DB;
 * test HTTP dùng MockMvc standalone với controller + GlobalExceptionHandler thật.
 */
@ExtendWith(MockitoExtension.class)
class SystemConfigAfterHoursDistinctTest {

    private static final String START = ConfigKey.AI_AFTER_HOUR_START.getKey();
    private static final String END = ConfigKey.AI_AFTER_HOUR_END.getKey();
    private static final String SAME_MESSAGE = "Giờ bắt đầu và kết thúc ngoài giờ không được trùng nhau!";
    private static final String ADMIN_EMAIL = "admin@fpt.edu.vn";
    private static final String REASON = "Điều chỉnh khung giờ ngoài giờ hành chính";

    @Mock private SystemConfigurationRepository configRepository;
    @Mock private SystemConfigurationChangeLogRepository changeLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private ObjectProvider<AreaService> areaServiceProvider;
    @Mock private ObjectProvider<InAppNotificationService> notificationServiceProvider;
    @Mock private AiServiceRestTemplate aiRestTemplate;
    @Mock private AuditService auditService;
    @Mock private EdgeMqttControlService edgeMqttControlService;
    @Mock private AreaService areaService;

    private SystemConfigService service;
    private SystemConfiguration startRow;
    private SystemConfiguration endRow;

    @BeforeEach
    void setUp() {
        service = new SystemConfigService(configRepository, changeLogRepository, userRepository,
                areaServiceProvider, notificationServiceProvider, aiRestTemplate);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "edgeMqttControlService", edgeMqttControlService);
        startRow = row(START, "22:00");
        endRow = row(END, "06:00");
        when(configRepository.findAll()).thenReturn(List.of(startRow, endRow));
        service.initCache();
    }

    private static SystemConfiguration row(String key, String value) {
        return SystemConfiguration.builder()
                .configKey(key).configValue(value).dataType("STRING")
                .configGroup("AI").displayOrder(1).description(key).editable(true)
                .updatedAt(OffsetDateTime.now().minusDays(1))
                .build();
    }

    /** Bị từ chối trước mọi ghi: không lưu, không change log, không audit, không tra user, không MQTT / AI, không EV-N8. */
    private void assertNothingWritten() {
        verify(configRepository, never()).save(any());
        verifyNoInteractions(changeLogRepository, auditService, userRepository, edgeMqttControlService,
                areaServiceProvider, aiRestTemplate);
    }

    private void assertWindowUnchanged() {
        assertEquals("22:00", startRow.getConfigValue(), "entity START không bị gán giá trị mới");
        assertEquals("06:00", endRow.getConfigValue(), "entity END không bị gán giá trị mới");
        assertEquals("22:00", service.getString(ConfigKey.AI_AFTER_HOUR_START), "cache START giữ nguyên");
        assertEquals("06:00", service.getString(ConfigKey.AI_AFTER_HOUR_END), "cache END giữ nguyên");
    }

    @Test
    @DisplayName("#262: đổi START thành đúng END -> IllegalArgumentException (400) đúng câu FE; DB + cache giữ nguyên, không change log, audit, MQTT, EV-N8")
    void startEqualToEnd_rejected_nothingWritten() {
        when(configRepository.findById(START)).thenReturn(Optional.of(startRow));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(START, "06:00", REASON, ADMIN_EMAIL));

        assertEquals(SAME_MESSAGE, ex.getMessage());
        assertNothingWritten();
        assertWindowUnchanged();
    }

    @Test
    @DisplayName("#262: đổi END thành đúng START có khoảng trắng thừa ('  22:00 ') -> bị từ chối, không ghi gì")
    void endEqualToStartWithSpaces_rejected() {
        when(configRepository.findById(END)).thenReturn(Optional.of(endRow));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(END, "  22:00 ", REASON, ADMIN_EMAIL));

        assertEquals(SAME_MESSAGE, ex.getMessage());
        assertNothingWritten();
        assertWindowUnchanged();
    }

    @Test
    @DisplayName("#262: so theo HH:mm — START '06:00:00' trùng END '06:00' -> bị từ chối")
    void startEqualToEndAfterNormalization_rejected() {
        when(configRepository.findById(START)).thenReturn(Optional.of(startRow));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(START, "06:00:00", REASON, ADMIN_EMAIL));

        assertEquals(SAME_MESSAGE, ex.getMessage());
        assertNothingWritten();
        assertWindowUnchanged();
    }

    @Test
    @DisplayName("#262: END hiện hành sai định dạng -> so với mặc định 06:00 (như AfterHoursWindow đọc cấu hình); START '06:00' bị từ chối")
    void otherKeyCorrupt_comparedWithDefault() {
        endRow.setConfigValue("sai-dinh-dang");
        service.initCache();
        when(configRepository.findById(START)).thenReturn(Optional.of(startRow));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(START, "06:00", REASON, ADMIN_EMAIL));

        assertEquals(SAME_MESSAGE, ex.getMessage());
        assertNothingWritten();
    }

    @Test
    @DisplayName("#262: giá trị khác (START 21:00, END 06:00) -> lưu, 1 change log, 1 audit, cache đổi, đồng bộ MQTT, chạy EV-N8")
    void distinctValue_saved() {
        User admin = User.builder().id(UUID.randomUUID()).email(ADMIN_EMAIL).fullName("Quản trị viên").build();
        when(configRepository.findById(START)).thenReturn(Optional.of(startRow));
        when(userRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(admin));
        when(configRepository.save(any(SystemConfiguration.class))).thenAnswer(i -> i.getArgument(0));
        when(edgeMqttControlService.isConnected()).thenReturn(true);
        when(areaServiceProvider.getIfAvailable()).thenReturn(areaService);
        when(areaService.applyAfterHoursChange(any(OffsetDateTime.class))).thenReturn(List.of());

        SystemConfigResponse response = service.update(START, " 21:00 ", REASON, ADMIN_EMAIL);

        assertEquals("21:00", response.configValue());
        assertEquals("21:00", service.getString(ConfigKey.AI_AFTER_HOUR_START));
        verify(configRepository).save(startRow);
        ArgumentCaptor<SystemConfigurationChangeLog> log = ArgumentCaptor.forClass(SystemConfigurationChangeLog.class);
        verify(changeLogRepository).save(log.capture());
        assertEquals("22:00", log.getValue().getOldValue());
        assertEquals("21:00", log.getValue().getNewValue());
        verify(auditService).record(eq(AuditTargetType.SYSTEM_CONFIG), eq(AuditAction.UPDATE), eq(START),
                any(), any(), any(), any(), eq(REASON), any(AuditActor.class));
        verify(edgeMqttControlService).publishSystemConfig(anyMap());
        verify(areaService).applyAfterHoursChange(any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("#262: PATCH /api/system-configurations/AI_AFTER_HOUR_START = END -> HTTP 400, message đúng câu FE, không ghi gì")
    void patchStartEqualToEnd_returns400() throws Exception {
        when(configRepository.findById(START)).thenReturn(Optional.of(startRow));
        // Chỉ kiểm luồng controller -> service -> GlobalExceptionHandler; @ReasonLength cần Spring inject nên bỏ qua @Valid ở đây
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
                .standaloneSetup(new SystemConfigController(service, mock(ReasonValidator.class), mock(GuestRules.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(noValidation)
                .build();

        mockMvc.perform(patch("/api/system-configurations/{configKey}", START)
                        .principal(new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configValue\":\"06:00\",\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.httpCode").value(400))
                .andExpect(jsonPath("$.message").value(SAME_MESSAGE));

        assertNothingWritten();
        assertWindowUnchanged();
    }
}
