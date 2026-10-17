package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.EscalationCloseReason;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.scheduler.IncidentEscalationJob.Outcome;
import com.fa26se040.icss.service.InAppNotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Job leo thang (BR-IE-04..10). Transaction manager là mock: TransactionTemplate chạy callback rồi commit(null).
 * Conditional UPDATE trên DB thật (rowcount 0 ở lần 2, 2 luồng đồng thời) kiểm ở IncidentEscalationIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class IncidentEscalationJobTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 17, 10, 0, 0, 0, ZoneOffset.ofHours(7));

    @Mock
    private IncidentEscalationRepository escalationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private InAppNotificationService inAppNotificationService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private IncidentEscalationJob job;

    private User fm;

    @BeforeEach
    void setUp() {
        fm = User.builder().id(UUID.randomUUID()).fullName("FM Bình").role(Role.FACILITY_MANAGER).build();
    }

    private static IncidentEscalation escalation(IncidentStatus status, int minutesAgo, int guardCount) {
        Area area = Area.builder().id(UUID.randomUUID()).name("Phòng server").building("TOA_ALPHA").build();
        SecurityIncident incident = SecurityIncident.builder()
                .id(UUID.randomUUID())
                .cameraCode("CAM-001")
                .area(area)
                .building("TOA_ALPHA")
                .eventType("INTRUSION")
                .status(status)
                .createdAt(NOW.minusMinutes(minutesAgo))
                .build();
        return IncidentEscalation.builder()
                .id(UUID.randomUUID())
                .incident(incident)
                .guardsNotifiedAt(incident.getCreatedAt())
                .guardRecipientCount(guardCount)
                .createdAt(incident.getCreatedAt())
                .build();
    }

    private void givenEscalation(IncidentEscalation e) {
        when(escalationRepository.findByIdWithIncident(e.getId())).thenReturn(Optional.of(e));
    }

    private void givenFmMinutes(int minutes) {
        when(systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATE_FM_MINUTES)).thenReturn(minutes);
    }

    private void givenEscalationWins(IncidentEscalation e) {
        when(escalationRepository.markEscalatedIfOpen(e.getId(), NOW)).thenReturn(1);
        when(userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER)).thenReturn(List.of(fm));
        when(inAppNotificationService.createForUsers(anyList(), any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(List.of(new Notification()));
    }

    private void assertNothingSent() {
        verify(inAppNotificationService, never()).createForUsers(anyList(), any(), anyString(), anyString(), any(), anyString());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("Chưa tới hạn (2 phút < 5 phút, có bảo vệ nhận) -> không gửi, không đóng")
    void notDue_nothingSent() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 2, 2);
        givenEscalation(e);
        givenFmMinutes(5);

        assertEquals(Outcome.NOT_DUE, job.processEscalation(e.getId(), NOW));

        verify(escalationRepository, never()).markEscalatedIfOpen(any(), any());
        assertNothingSent();
    }

    @Test
    @DisplayName("BR-IE-04: tới hạn -> gửi FM 1 lần (in-app INCIDENT_ESCALATED + real-time), lưu fm_recipient_count")
    void due_notifiesFmOnce() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 6, 2);
        givenEscalation(e);
        givenFmMinutes(5);
        givenEscalationWins(e);

        assertEquals(Outcome.ESCALATED, job.processEscalation(e.getId(), NOW));

        UUID incidentId = e.getIncident().getId();
        verify(inAppNotificationService, times(1)).createForUsers(eq(List.of(fm)), eq(NotificationType.INCIDENT_ESCALATED),
                anyString(), anyString(), eq(incidentId), eq(IncidentEscalationJob.REF_TYPE_SECURITY_INCIDENT));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, times(1)).convertAndSend(eq(IncidentEscalationJob.FM_TOPIC), payload.capture());
        assertEquals(incidentId, payload.getValue().get("incidentId"));
        assertEquals("Phòng server", payload.getValue().get("areaName"));
        assertEquals("TOA_ALPHA", payload.getValue().get("building"));
        assertEquals("INTRUSION", payload.getValue().get("eventType"));
        assertEquals(e.getIncident().getCreatedAt(), payload.getValue().get("createdAt"));
        assertEquals(6L, payload.getValue().get("minutesElapsed"));
        verify(escalationRepository).updateFmRecipientCount(e.getId(), 1);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "INTRUSION,Xâm nhập ngoài giờ",
            "AFTER_HOURS,Xâm nhập ngoài giờ",
            "AFTER_HOURS_PRESENCE,Xâm nhập ngoài giờ",
            "AFTER_HOURS_ACCESS,Xâm nhập ngoài giờ",
            "UNAUTHORIZED_ACCESS,Xâm nhập trái phép",
            "UNAUTHORIZED,Xâm nhập trái phép",
            "UNKNOWN_PERSON,Người lạ chưa xác minh",
            "UNKNOWN,Người lạ chưa xác minh",
            "ACCESS_OUT_OF_TIME,ACCESS_OUT_OF_TIME",
            "SECURITY_ALERT,SECURITY_ALERT"})
    @DisplayName("Nhãn tiếng Việt của loại sự cố; loại khác giữ mã")
    void eventTypeLabel_mapping(String eventType, String expected) {
        assertEquals(expected, IncidentEscalationJob.eventTypeLabel(eventType));
    }

    @Test
    @DisplayName("Nội dung INCIDENT_ESCALATED dùng nhãn tiếng Việt, không lộ mã INTRUSION")
    void notificationMessage_usesVietnameseLabel() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 6, 2);
        givenEscalation(e);
        givenFmMinutes(5);
        givenEscalationWins(e);

        job.processEscalation(e.getId(), NOW);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(inAppNotificationService).createForUsers(anyList(), eq(NotificationType.INCIDENT_ESCALATED),
                anyString(), message.capture(), any(), anyString());
        assertTrue(message.getValue().contains("Sự cố \"Xâm nhập ngoài giờ\" tại khu vực Phòng server (TOA_ALPHA)"), message.getValue());
        assertTrue(message.getValue().contains("đã chờ 6 phút"), message.getValue());
        assertFalse(message.getValue().contains("INTRUSION"), message.getValue());
    }

    @Test
    @DisplayName("Đúng mốc created_at + FM_MINUTES -> tới hạn (now ≥ hạn)")
    void exactlyAtDeadline_due() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 5, 1);
        givenEscalation(e);
        givenFmMinutes(5);
        givenEscalationWins(e);

        assertEquals(Outcome.ESCALATED, job.processEscalation(e.getId(), NOW));
    }

    @Test
    @DisplayName("BR-IE-05: guard_recipient_count = 0 -> gửi FM ngay lần chạy đầu, không chờ FM_MINUTES")
    void zeroGuards_escalatedAtFirstRun() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 0, 0);
        givenEscalation(e);
        givenFmMinutes(5);
        givenEscalationWins(e);

        assertEquals(Outcome.ESCALATED, job.processEscalation(e.getId(), NOW));

        verify(inAppNotificationService).createForUsers(anyList(), eq(NotificationType.INCIDENT_ESCALATED),
                anyString(), anyString(), any(), anyString());
    }

    @ParameterizedTest(name = "{0} -> đóng {1}, không gửi")
    @CsvSource({"CLAIMED,CLAIMED", "RESOLVED_VERIFIED,RESOLVED", "RESOLVED_DISMISSED,DISMISSED"})
    @DisplayName("BR-IE-06: sự cố rời NEW -> đóng với lý do tương ứng, không gửi FM")
    void leftNew_closedWithReason(IncidentStatus status, EscalationCloseReason reason) {
        IncidentEscalation e = escalation(status, 30, 2);
        givenEscalation(e);
        when(escalationRepository.closeIfOpen(e.getId(), reason, NOW)).thenReturn(1);

        assertEquals(Outcome.CLOSED, job.processEscalation(e.getId(), NOW));

        verify(escalationRepository).closeIfOpen(e.getId(), reason, NOW);
        verify(escalationRepository, never()).markEscalatedIfOpen(any(), any());
        assertNothingSent();
    }

    @Test
    @DisplayName("BR-IE-08: đổi FM_MINUTES 5 -> 1, sự cố mở 2 phút được leo thang ở lần chạy kế")
    void fmMinutesChanged_appliesAtNextRun() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 2, 1);
        givenEscalation(e);
        when(systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATE_FM_MINUTES)).thenReturn(5, 1);

        assertEquals(Outcome.NOT_DUE, job.processEscalation(e.getId(), NOW));

        givenEscalationWins(e);
        assertEquals(Outcome.ESCALATED, job.processEscalation(e.getId(), NOW));
        verify(systemConfigService, times(2)).getInt(ConfigKey.INCIDENT_ESCALATE_FM_MINUTES);
    }

    @Test
    @DisplayName("BR-IE-07: chạy 2 lần liên tiếp -> lần 2 UPDATE trả 0 dòng, chỉ gửi 1 lần")
    void twoRuns_notifyOnce() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 10, 1);
        givenEscalation(e);
        givenFmMinutes(5);
        givenEscalationWins(e);
        when(escalationRepository.markEscalatedIfOpen(e.getId(), NOW)).thenReturn(1, 0);

        assertEquals(Outcome.ESCALATED, job.processEscalation(e.getId(), NOW));
        assertEquals(Outcome.SKIPPED, job.processEscalation(e.getId(), NOW));

        verify(inAppNotificationService, times(1)).createForUsers(anyList(), any(), anyString(), anyString(), any(), anyString());
        verify(messagingTemplate, times(1)).convertAndSend(eq(IncidentEscalationJob.FM_TOPIC), any(Object.class));
        verify(escalationRepository, times(1)).updateFmRecipientCount(any(), anyInt());
    }

    @Test
    @DisplayName("Bản ghi đã đóng (đọc lại thấy closed_at) -> bỏ qua, không UPDATE")
    void alreadyClosed_skipped() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 10, 1);
        e.setClosedAt(NOW.minusMinutes(1));
        givenEscalation(e);

        assertEquals(Outcome.SKIPPED, job.processEscalation(e.getId(), NOW));

        verify(escalationRepository, never()).markEscalatedIfOpen(any(), any());
        assertNothingSent();
    }

    @Test
    @DisplayName("BR-IE-10: chỉ lấy FACILITY_MANAGER làm người nhận, ADMIN không nhận")
    void adminNeverRecipient() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 10, 1);
        givenEscalation(e);
        givenFmMinutes(5);
        givenEscalationWins(e);

        job.processEscalation(e.getId(), NOW);

        verify(userRepository).findActiveUsersByRole(Role.FACILITY_MANAGER);
        verify(userRepository, never()).findActiveUsersByRole(Role.ADMIN);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<User>> recipients = ArgumentCaptor.forClass(List.class);
        verify(inAppNotificationService).createForUsers(recipients.capture(), any(), anyString(), anyString(), any(), anyString());
        assertTrue(recipients.getValue().stream().allMatch(u -> u.getRole() == Role.FACILITY_MANAGER));
    }

    @Test
    @DisplayName("BR-IE-03: sự cố không có bản ghi leo thang -> job không thấy, không gửi gì")
    void incidentWithoutEscalationRecord_ignored() {
        when(escalationRepository.findOpenIds()).thenReturn(List.of());

        assertEquals(0, job.runOnce(NOW));

        verify(escalationRepository, never()).findByIdWithIncident(any());
        verifyNoInteractions(userRepository, inAppNotificationService, messagingTemplate);
    }

    @Test
    @DisplayName("Không tìm thấy bản ghi theo id -> SKIPPED")
    void missingEscalation_skipped() {
        UUID id = UUID.randomUUID();
        when(escalationRepository.findByIdWithIncident(id)).thenReturn(Optional.empty());

        assertEquals(Outcome.SKIPPED, job.processEscalation(id, NOW));
        assertNothingSent();
    }

    @Test
    @DisplayName("Lỗi ở 1 sự cố không chặn sự cố khác")
    void failureIsolatedPerIncident() {
        UUID broken = UUID.randomUUID();
        IncidentEscalation ok = escalation(IncidentStatus.NEW, 10, 1);
        when(escalationRepository.findOpenIds()).thenReturn(List.of(broken, ok.getId()));
        when(escalationRepository.findByIdWithIncident(broken)).thenThrow(new IllegalStateException("DB lỗi"));
        givenEscalation(ok);
        givenFmMinutes(5);
        givenEscalationWins(ok);

        assertEquals(1, job.runOnce(NOW));
        verify(transactionManager).rollback(any());
    }

    @Test
    @DisplayName("Tạo thông báo in-app lỗi -> transaction rollback (bản ghi còn mở để thử lại), không gửi real-time")
    void notificationFailure_rollsBack() {
        IncidentEscalation e = escalation(IncidentStatus.NEW, 10, 1);
        givenEscalation(e);
        givenFmMinutes(5);
        when(escalationRepository.markEscalatedIfOpen(e.getId(), NOW)).thenReturn(1);
        when(userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER)).thenReturn(List.of(fm));
        when(inAppNotificationService.createForUsers(anyList(), any(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("notification lỗi"));

        assertEquals(Outcome.FAILED, job.processEscalation(e.getId(), NOW));

        verify(transactionManager).rollback(any());
        verifyNoInteractions(messagingTemplate);
        verify(escalationRepository, never()).updateFmRecipientCount(any(), anyInt());
    }

    @Test
    @DisplayName("BR-IE-09: lần chạy kế = lần trước xong + INCIDENT_ESCALATION_CHECK_SECONDS đọc lại mỗi lần")
    void trigger_readsCheckSecondsEveryTime() {
        Instant last = Instant.parse("2026-10-17T03:00:00Z");
        TriggerContext context = mock(TriggerContext.class);
        when(context.lastCompletion()).thenReturn(last);
        when(systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATION_CHECK_SECONDS)).thenReturn(30, 10);

        assertEquals(last.plusSeconds(30), job.nextExecution(context));
        assertEquals(last.plusSeconds(10), job.nextExecution(context));
    }

    @Test
    @DisplayName("BR-IE-09: lần đầu (chưa chạy lần nào) tính từ đồng hồ hiện tại")
    void trigger_firstRunFromClock() {
        Instant start = Instant.parse("2026-10-17T03:00:00Z");
        TriggerContext context = mock(TriggerContext.class);
        when(context.lastCompletion()).thenReturn(null);
        when(context.getClock()).thenReturn(Clock.fixed(start, ZoneOffset.UTC));
        when(systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATION_CHECK_SECONDS)).thenReturn(30);

        assertEquals(start.plusSeconds(30), job.nextExecution(context));
    }

    @Test
    @DisplayName("Chu kỳ ≤ 0 (sửa thẳng DB) -> dùng mặc định trong ConfigKey")
    void trigger_invalidSecondsFallsBackToDefault() {
        when(systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATION_CHECK_SECONDS)).thenReturn(0);

        assertEquals(Integer.parseInt(ConfigKey.INCIDENT_ESCALATION_CHECK_SECONDS.getDefaultValue()), job.checkIntervalSeconds());
    }

    @Test
    @DisplayName("Cờ incident-escalation-enabled: bật -> đăng ký 1 trigger task, tắt -> không đăng ký")
    void configureTasks_respectsEnabledFlag() {
        ScheduledTaskRegistrar enabledRegistrar = new ScheduledTaskRegistrar();
        ReflectionTestUtils.setField(job, "enabled", true);
        job.configureTasks(enabledRegistrar);
        assertEquals(1, enabledRegistrar.getTriggerTaskList().size());

        ScheduledTaskRegistrar disabledRegistrar = new ScheduledTaskRegistrar();
        ReflectionTestUtils.setField(job, "enabled", false);
        job.configureTasks(disabledRegistrar);
        assertTrue(disabledRegistrar.getTriggerTaskList().isEmpty());
    }
}
