package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.InAppNotificationService;
import com.fa26se040.icss.service.IncidentDispatchService;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** V93 (BR-DSP-09): số phút leo thang theo mức, chỉ khi DISPATCH_ENABLED = true. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentEscalationJobSeverityTest {

    @Mock private IncidentEscalationRepository escalationRepository;
    @Mock private UserRepository userRepository;
    @Mock private SystemConfigService systemConfigService;
    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private IncidentDispatchService incidentDispatchService;

    private IncidentEscalationJob job;

    @BeforeEach
    void setUp() {
        job = new IncidentEscalationJob(escalationRepository, userRepository, systemConfigService,
                inAppNotificationService, messagingTemplate, transactionManager);
        when(systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATE_FM_MINUTES)).thenReturn(5);
        when(incidentDispatchService.policyFor(IncidentSeverity.CRITICAL)).thenReturn(Optional.of(
                IncidentDispatchPolicy.builder().severity(IncidentSeverity.CRITICAL).step1Size((short) 0)
                        .step1TimeoutSeconds(10).escalateFmMinutes(2).notifyFmImmediately(true).build()));
    }

    private static SecurityIncident incident(IncidentSeverity severity) {
        return SecurityIncident.builder().id(UUID.randomUUID()).severity(severity).build();
    }

    @Test
    @DisplayName("Chưa inject dispatch -> INCIDENT_ESCALATE_FM_MINUTES như trước")
    void notInjected_usesConfig() {
        assertEquals(5, job.escalationMinutesFor(incident(IncidentSeverity.CRITICAL)));
    }

    @Test
    @DisplayName("DISPATCH_ENABLED = false -> INCIDENT_ESCALATE_FM_MINUTES (bất biến hành vi cũ)")
    void dispatchDisabled_usesConfig() {
        ReflectionTestUtils.setField(job, "incidentDispatchService", incidentDispatchService);
        when(incidentDispatchService.isEnabled()).thenReturn(false);
        assertEquals(5, job.escalationMinutesFor(incident(IncidentSeverity.CRITICAL)));
    }

    @Test
    @DisplayName("DISPATCH_ENABLED = true -> escalate_fm_minutes của mức (CRITICAL 2 phút)")
    void dispatchEnabled_usesPolicy() {
        ReflectionTestUtils.setField(job, "incidentDispatchService", incidentDispatchService);
        when(incidentDispatchService.isEnabled()).thenReturn(true);
        assertEquals(2, job.escalationMinutesFor(incident(IncidentSeverity.CRITICAL)));
    }

    @Test
    @DisplayName("Sự cố không có severity hoặc mức thiếu chính sách -> INCIDENT_ESCALATE_FM_MINUTES")
    void missingSeverityOrPolicy_usesConfig() {
        ReflectionTestUtils.setField(job, "incidentDispatchService", incidentDispatchService);
        when(incidentDispatchService.isEnabled()).thenReturn(true);
        when(incidentDispatchService.policyFor(IncidentSeverity.MEDIUM)).thenReturn(Optional.empty());
        assertEquals(5, job.escalationMinutesFor(incident(null)));
        assertEquals(5, job.escalationMinutesFor(incident(IncidentSeverity.MEDIUM)));
    }
}
