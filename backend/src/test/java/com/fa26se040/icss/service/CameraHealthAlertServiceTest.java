package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraStatusAlertPayload;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.CameraType;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import com.fa26se040.icss.repository.OnDutyGuardQueryRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CameraHealthAlertServiceTest {

    @Mock
    private InAppNotificationService inAppNotificationService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OnDutyGuardQueryRepository onDutyGuardQueryRepository;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private IncidentRecipientResolver incidentRecipientResolver;
    private CameraHealthAlertService alertService;

    private User fmUser;
    private User adminUser;
    private User guardAlpha;
    private User guardBeta;
    private Building alphaBuilding;
    private Building betaBuilding;
    private Camera alphaCamera;

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 27, 10, 0, 0, 0, ZoneOffset.ofHours(7));

    @BeforeEach
    void setUp() {
        incidentRecipientResolver = new IncidentRecipientResolver(onDutyGuardQueryRepository);
        alertService = new CameraHealthAlertService(
                inAppNotificationService,
                userRepository,
                incidentRecipientResolver,
                systemConfigService
        );
        alertService.setMessagingTemplate(messagingTemplate);
        alertService.setBootedAt(T0.minusMinutes(10));
        alertService.setBootGraceSeconds(60L);

        fmUser = User.builder().id(UUID.randomUUID()).email("fm@fpt.edu.vn").role(Role.FACILITY_MANAGER).isActive(true).build();
        adminUser = User.builder().id(UUID.randomUUID()).email("admin@fpt.edu.vn").role(Role.ADMIN).isActive(true).build();
        guardAlpha = User.builder().id(UUID.randomUUID()).email("guard.alpha@fpt.edu.vn").role(Role.GUARD).isActive(true).build();
        guardBeta = User.builder().id(UUID.randomUUID()).email("guard.beta@fpt.edu.vn").role(Role.GUARD).isActive(true).build();

        alphaBuilding = Building.builder().id(UUID.randomUUID()).name("Tòa Alpha").build();
        betaBuilding = Building.builder().id(UUID.randomUUID()).name("Tòa Beta").build();

        Floor floor2 = Floor.builder().id(UUID.randomUUID()).name("Tầng 2").floorOrder(2).building(alphaBuilding).build();
        Area serverRoom = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Server")
                .building("Tòa Alpha")
                .floor("Tầng 2")
                .floorEntity(floor2)
                .isActive(true)
                .build();

        alphaCamera = Camera.builder()
                .id(UUID.randomUUID())
                .cameraCode("CAM-001")
                .name("Góc cửa chính")
                .cameraType(CameraType.RESTRICTED_AREA)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.OFFLINE)
                .area(serverRoom)
                .build();

        lenient().when(systemConfigService.getInt(ConfigKey.NOTIFICATION_CAMERA_OFFLINE_SECONDS)).thenReturn(45);
        lenient().when(userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER)).thenReturn(List.of(fmUser));
        lenient().when(userRepository.findActiveUsersByRole(Role.ADMIN)).thenReturn(List.of(adminUser));
        lenient().when(userRepository.findActiveUsersByRole(Role.GUARD)).thenReturn(List.of(guardAlpha, guardBeta));
    }

    private GuardShift activeShift(User guard, Building building) {
        return GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard)
                .building(building)
                .shiftDate(LocalDate.of(2026, 11, 27))
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .status(ShiftStatus.CHECKED_IN)
                .build();
    }

    @Test
    @DisplayName("V151: Đạt ngưỡng cấu hình động (45s) -> gửi đúng 1 thông báo kèm Tòa · Tầng, chỉ đẩy mobile cho bảo vệ toà Alpha, và tự gỡ khi ONLINE lại")
    void offlineReachesConfiguredThreshold_sendsOnceWithFloorAndRecoversCleanly() {
        when(onDutyGuardQueryRepository.findByStatusAndShiftDateBetween(eq(ShiftStatus.CHECKED_IN), any(), any()))
                .thenReturn(List.of(activeShift(guardAlpha, alphaBuilding), activeShift(guardBeta, betaBuilding)));

        // Nhịp 0s: bắt đầu rớt ONLINE -> OFFLINE (chưa đủ 45s)
        alertService.recordStatus(alphaCamera, OperationalStatus.ONLINE, OperationalStatus.OFFLINE, T0);
        // Nhịp 30s: vẫn chưa đủ 45s
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(30));
        verify(inAppNotificationService, never()).createForUsers(any(), any(), any(), any(), any(), any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));

        // Nhịp 45s: chạm ngưỡng -> gửi đúng 1 lần
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(45));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<User>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(inAppNotificationService, times(1)).createForUsers(
                recipientsCaptor.capture(),
                eq(NotificationType.CAMERA_OFFLINE),
                eq("Camera mất kết nối đột ngột"),
                msgCaptor.capture(),
                eq(alphaCamera.getId()),
                eq("CAMERA")
        );
        assertEquals(4, recipientsCaptor.getValue().size());
        assertEquals(
                "Camera Góc cửa chính (CAM-001) tại Phòng Server (Tòa Alpha · Tầng 2) đã mất kết nối lúc 10:00.",
                msgCaptor.getValue()
        );

        ArgumentCaptor<CameraStatusAlertPayload> webPayloadCaptor = ArgumentCaptor.forClass(CameraStatusAlertPayload.class);
        verify(messagingTemplate, times(1)).convertAndSend(
                eq(CameraHealthAlertService.WEB_CAMERA_ALERTS_TOPIC),
                webPayloadCaptor.capture()
        );
        assertEquals("CAMERA_OFFLINE", webPayloadCaptor.getValue().event());
        assertEquals("Tòa Alpha", webPayloadCaptor.getValue().building());
        assertEquals("Tầng 2", webPayloadCaptor.getValue().floor());

        // Mobile chỉ gửi cho guardAlpha (cùng Tòa Alpha), không gửi cho guardBeta (Tòa Beta)
        verify(messagingTemplate, times(1)).convertAndSend(
                eq(CameraHealthAlertService.guardCameraAlertTopic(guardAlpha.getId())),
                any(CameraStatusAlertPayload.class)
        );
        verify(messagingTemplate, never()).convertAndSend(
                eq(CameraHealthAlertService.guardCameraAlertTopic(guardBeta.getId())),
                any(CameraStatusAlertPayload.class)
        );

        // Nhịp 60s: vẫn OFFLINE -> không spam lặp lại
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(60));
        verify(inAppNotificationService, times(1)).createForUsers(any(), any(), any(), any(), any(), any());

        // Nhịp 75s: khôi phục ONLINE -> phát CAMERA_ONLINE để tự đóng thông báo
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.ONLINE, T0.plusSeconds(75));
        ArgumentCaptor<CameraStatusAlertPayload> recoveryCaptor = ArgumentCaptor.forClass(CameraStatusAlertPayload.class);
        verify(messagingTemplate, times(2)).convertAndSend(
                eq(CameraHealthAlertService.WEB_CAMERA_ALERTS_TOPIC),
                recoveryCaptor.capture()
        );
        assertEquals("CAMERA_ONLINE", recoveryCaptor.getAllValues().get(1).event());
        verify(messagingTemplate, times(2)).convertAndSend(
                eq(CameraHealthAlertService.guardCameraAlertTopic(guardAlpha.getId())),
                any(CameraStatusAlertPayload.class)
        );
    }

    @Test
    @DisplayName("V151: Đổi động NOTIFICATION_CAMERA_OFFLINE_SECONDS = 90s -> mốc 45s chưa báo, đủ 90s mới báo; fallback toàn trường khi toà Alpha không có ca trực")
    void dynamicConfigThresholdAndCampusWideGuardFallback() {
        when(systemConfigService.getInt(ConfigKey.NOTIFICATION_CAMERA_OFFLINE_SECONDS)).thenReturn(90);
        // Chỉ có guardBeta trực ở Tòa Beta, Tòa Alpha không có ai trực -> fallback gửi cho guardBeta
        when(onDutyGuardQueryRepository.findByStatusAndShiftDateBetween(eq(ShiftStatus.CHECKED_IN), any(), any()))
                .thenReturn(List.of(activeShift(guardBeta, betaBuilding)));

        alertService.recordStatus(alphaCamera, OperationalStatus.ONLINE, OperationalStatus.OFFLINE, T0);
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(45));
        verify(inAppNotificationService, never()).createForUsers(any(), any(), any(), any(), any(), any());

        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(90));
        verify(inAppNotificationService, times(1)).createForUsers(any(), eq(NotificationType.CAMERA_OFFLINE), any(), any(), any(), any());
        verify(messagingTemplate, times(1)).convertAndSend(
                eq(CameraHealthAlertService.guardCameraAlertTopic(guardBeta.getId())),
                any(CameraStatusAlertPayload.class)
        );
    }

    @Test
    @DisplayName("V151: Ngắt stream chủ động (markPlannedStop) không phát cảnh báo; bật lại (clearPlannedStop) giám sát bình thường")
    void plannedStopSuppressesAlertUntilReconnected() {
        alertService.markPlannedStop(alphaCamera);
        alertService.recordStatus(alphaCamera, OperationalStatus.ONLINE, OperationalStatus.OFFLINE, T0);
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(120));

        verify(inAppNotificationService, never()).createForUsers(any(), any(), any(), any(), any(), any());

        // Bật stream lại rồi rớt đột ngột
        alertService.clearPlannedStop(alphaCamera.getId());
        alertService.recordStatus(alphaCamera, OperationalStatus.ONLINE, OperationalStatus.OFFLINE, T0.plusSeconds(150));
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(195));

        verify(inAppNotificationService, times(1)).createForUsers(any(), eq(NotificationType.CAMERA_OFFLINE), any(), any(), any(), any());
    }

    @Test
    @DisplayName("V151: Camera vốn đã OFFLINE từ trước khi server bật không báo giả; trong 60s boot grace chờ hết ân hạn mới tính giờ")
    void alreadyOfflineAtBootIgnored_andBootGraceDelaysTimer() {
        // 1. Camera vốn đã OFFLINE từ trước -> không bao giờ báo
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0);
        alertService.recordStatus(alphaCamera, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(120));
        verify(inAppNotificationService, never()).createForUsers(any(), any(), any(), any(), any(), any());

        // 2. Trong cửa sổ 60s boot grace: ghi nhận từng ONLINE nhưng chờ hết boot grace mới bắt đầu đếm 45s
        alertService.setBootedAt(T0);
        Camera cam2 = Camera.builder()
                .id(UUID.randomUUID())
                .cameraCode("CAM-002")
                .name("Hành lang Đông")
                .cameraType(CameraType.CORRIDOR)
                .mapBuilding("Alpha")
                .mapFloorOrder(1)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.OFFLINE)
                .build();

        // t = 15s (còn trong 60s boot grace)
        alertService.recordStatus(cam2, OperationalStatus.ONLINE, OperationalStatus.OFFLINE, T0.plusSeconds(15));
        // t = 60s (vừa hết boot grace -> bắt đầu đếm offlineSince từ t=60s)
        alertService.recordStatus(cam2, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(60));
        verify(inAppNotificationService, never()).createForUsers(any(), any(), any(), any(), any(), any());

        // t = 105s (60 + 45s) -> phát cảnh báo với "Hành lang (Alpha · Tầng 1)"
        alertService.recordStatus(cam2, OperationalStatus.OFFLINE, OperationalStatus.OFFLINE, T0.plusSeconds(105));
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(inAppNotificationService, times(1)).createForUsers(
                any(), eq(NotificationType.CAMERA_OFFLINE), any(), msgCaptor.capture(), eq(cam2.getId()), eq("CAMERA"));
        assertTrue(msgCaptor.getValue().contains("Hành lang (Alpha · Tầng 1)"));
    }

    @Test
    @DisplayName("V151: MediaMTX Gateway ngừng phản hồi (getLivePathStatuses == null) chuyển camera ONLINE sang OFFLINE và ghi nhận vào CameraHealthAlertService")
    void syncLiveOperationalStatuses_gatewayUnreachable_marksOnlineCameraOfflineAndNotifiesTracker() {
        CameraRepository camRepo = mock(CameraRepository.class);
        CameraStreamConfigurationRepository cfgRepo = mock(CameraStreamConfigurationRepository.class);
        MediaMtxService mediaMtx = mock(MediaMtxService.class);
        CameraHealthAlertService mockAlert = mock(CameraHealthAlertService.class);

        CameraService cameraService = new CameraService(
                camRepo, cfgRepo, mediaMtx, null, null, null, systemConfigService, null, null, null
        );
        ReflectionTestUtils.setField(cameraService, "cameraHealthAlertService", mockAlert);

        alphaCamera.setOperationalStatus(OperationalStatus.ONLINE);
        alphaCamera.setUpdatedAt(T0.minusMinutes(5));
        when(mediaMtx.getLivePathStatuses()).thenReturn(null);
        when(camRepo.findActiveWithStreamConfigByStatus(CameraStatus.ACTIVE)).thenReturn(List.of(alphaCamera));

        cameraService.syncLiveOperationalStatuses();

        assertEquals(OperationalStatus.OFFLINE, alphaCamera.getOperationalStatus());
        verify(camRepo).save(alphaCamera);
        verify(mockAlert).recordStatus(eq(alphaCamera), eq(OperationalStatus.ONLINE), eq(OperationalStatus.OFFLINE), any());
    }
}
