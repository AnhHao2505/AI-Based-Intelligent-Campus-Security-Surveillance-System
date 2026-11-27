package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraStatusAlertPayload;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.util.StringNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V151: Theo dõi trạng thái kết nối luồng livestream của từng camera trong bộ nhớ và phát cảnh báo
 * Video Loss (CAMERA_OFFLINE) / Recovery (CAMERA_ONLINE) khi mất kết nối đột ngột liên tục đạt ngưỡng
 * cấu hình động {@link ConfigKey#NOTIFICATION_CAMERA_OFFLINE_SECONDS} (mặc định 45 giây).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraHealthAlertService {

    public static final String REF_TYPE_CAMERA = "CAMERA";
    public static final String WEB_CAMERA_ALERTS_TOPIC = "/topic/cameras/status-alerts";
    public static final String EVENT_CAMERA_OFFLINE = "CAMERA_OFFLINE";
    public static final String EVENT_CAMERA_ONLINE = "CAMERA_ONLINE";

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final InAppNotificationService inAppNotificationService;
    private final UserRepository userRepository;
    private final IncidentRecipientResolver incidentRecipientResolver;
    private final SystemConfigService systemConfigService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private FloorRepository floorRepository;

    @Value("${icss.camera.boot-grace-seconds:60}")
    private long bootGraceSeconds = 60L;

    private volatile OffsetDateTime bootedAt = OffsetDateTime.now();

    private final ConcurrentHashMap<UUID, CameraOutageState> states = new ConcurrentHashMap<>();

    private static final class CameraOutageState {
        boolean everSeenOnline;
        boolean plannedStop;
        OffsetDateTime offlineSince;
        boolean alertSent;
        final Set<UUID> notifiedGuardIds = new LinkedHashSet<>();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        this.bootedAt = OffsetDateTime.now();
    }

    void setBootedAt(OffsetDateTime bootedAt) {
        this.bootedAt = bootedAt;
    }

    void setBootGraceSeconds(long bootGraceSeconds) {
        this.bootGraceSeconds = bootGraceSeconds;
    }

    void setMessagingTemplate(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    void setFloorRepository(FloorRepository floorRepository) {
        this.floorRepository = floorRepository;
    }

    /**
     * Đánh dấu camera bị ngắt chủ động (ngắt stream, tắt/xoá camera) để không sinh cảnh báo mất kết nối.
     * Nếu trước đó camera đang ở trạng thái đã báo lỗi (alertSent = true), gửi tin thu hồi để đóng cảnh báo cũ.
     */
    public void markPlannedStop(Camera camera) {
        if (camera == null || camera.getId() == null) {
            return;
        }
        CameraOutageState state = states.computeIfAbsent(camera.getId(), k -> new CameraOutageState());
        boolean shouldDismissExistingAlert;
        OffsetDateTime prevOfflineSince;
        Set<UUID> prevNotifiedGuards;
        synchronized (state) {
            state.plannedStop = true;
            prevOfflineSince = state.offlineSince;
            state.offlineSince = null;
            shouldDismissExistingAlert = state.alertSent;
            prevNotifiedGuards = shouldDismissExistingAlert
                    ? new LinkedHashSet<>(state.notifiedGuardIds)
                    : Set.of();
            state.alertSent = false;
            state.notifiedGuardIds.clear();
        }
        if (shouldDismissExistingAlert) {
            dispatchRecoveryAlert(camera, prevOfflineSince, OffsetDateTime.now(), prevNotifiedGuards);
        }
    }

    /**
     * Xoá cờ ngắt chủ động khi camera được bật stream lại hoặc kích hoạt lại.
     */
    public void clearPlannedStop(UUID cameraId) {
        if (cameraId == null) {
            return;
        }
        CameraOutageState state = states.computeIfAbsent(cameraId, k -> new CameraOutageState());
        synchronized (state) {
            state.plannedStop = false;
            state.everSeenOnline = true;
            state.offlineSince = null;
            state.alertSent = false;
            state.notifiedGuardIds.clear();
        }
    }

    /**
     * Ghi nhận trạng thái sau mỗi nhịp đồng bộ của CameraService.
     */
    public void recordStatus(Camera camera, OperationalStatus previousDbStatus, OperationalStatus currentStatus, OffsetDateTime now) {
        if (camera == null || camera.getId() == null || currentStatus == null || now == null) {
            return;
        }

        CameraOutageState state = states.computeIfAbsent(camera.getId(), k -> new CameraOutageState());

        if (currentStatus == OperationalStatus.ONLINE) {
            boolean shouldSendRecovery;
            OffsetDateTime prevOfflineSince;
            Set<UUID> prevNotifiedGuards;
            synchronized (state) {
                state.everSeenOnline = true;
                state.plannedStop = false;
                prevOfflineSince = state.offlineSince;
                state.offlineSince = null;
                shouldSendRecovery = state.alertSent;
                prevNotifiedGuards = shouldSendRecovery
                        ? new LinkedHashSet<>(state.notifiedGuardIds)
                        : Set.of();
                state.alertSent = false;
                state.notifiedGuardIds.clear();
            }
            if (shouldSendRecovery) {
                dispatchRecoveryAlert(camera, prevOfflineSince, now, prevNotifiedGuards);
            }
            return;
        }

        // currentStatus == OFFLINE
        int thresholdSeconds = resolveThresholdSeconds();
        OffsetDateTime offlineAt;
        synchronized (state) {
            if (state.plannedStop) {
                return;
            }
            if (previousDbStatus == OperationalStatus.ONLINE) {
                state.everSeenOnline = true;
            }
            if (!state.everSeenOnline) {
                // Camera vốn đã OFFLINE từ trước khi server chạy và chưa từng ONLINE -> không báo giả
                return;
            }
            if (isWithinBootGrace(now)) {
                // Chờ hết 60s khởi động để MediaMTX / Edge đồng bộ nhịp đầu; everSeenOnline đã được chốt ở trên
                return;
            }
            if (state.offlineSince == null) {
                state.offlineSince = now;
            }
            long elapsedSeconds = Duration.between(state.offlineSince, now).getSeconds();
            if (state.alertSent || elapsedSeconds < thresholdSeconds) {
                return;
            }
            state.alertSent = true;
            offlineAt = state.offlineSince;
        }

        Set<UUID> notifiedMobileGuards = dispatchOfflineAlert(camera, offlineAt, now);
        if (!notifiedMobileGuards.isEmpty()) {
            synchronized (state) {
                if (state.alertSent) {
                    state.notifiedGuardIds.addAll(notifiedMobileGuards);
                }
            }
        }
    }

    private boolean isWithinBootGrace(OffsetDateTime now) {
        if (bootGraceSeconds <= 0 || bootedAt == null) {
            return false;
        }
        long sinceBoot = Duration.between(bootedAt, now).getSeconds();
        return sinceBoot >= 0 && sinceBoot < bootGraceSeconds;
    }

    private int resolveThresholdSeconds() {
        try {
            int configured = systemConfigService.getInt(ConfigKey.NOTIFICATION_CAMERA_OFFLINE_SECONDS);
            if (configured > 0) {
                return configured;
            }
        } catch (Exception e) {
            log.warn("[CAMERA-ALERT] Không đọc được NOTIFICATION_CAMERA_OFFLINE_SECONDS, dùng mặc định 45s: {}", e.getMessage());
        }
        return Integer.parseInt(ConfigKey.NOTIFICATION_CAMERA_OFFLINE_SECONDS.getDefaultValue());
    }

    private Set<UUID> dispatchOfflineAlert(Camera camera, OffsetDateTime offlineSince, OffsetDateTime now) {
        LocationInfo loc = resolveLocation(camera);
        String camName = (camera.getName() != null && !camera.getName().isBlank())
                ? camera.getName().trim()
                : camera.getCameraCode();
        String camCode = camera.getCameraCode() != null ? camera.getCameraCode().trim() : "";
        String offlineClock = offlineSince.atZoneSameInstant(VN_ZONE).format(TIME_FORMATTER);

        String title = "Camera mất kết nối đột ngột";
        String locationSuffix = buildLocationParentheses(loc.building(), loc.floor());
        String message = "Camera " + camName
                + (camCode.isEmpty() || camCode.equalsIgnoreCase(camName) ? "" : " (" + camCode + ")")
                + " tại " + loc.areaLabel() + locationSuffix
                + " đã mất kết nối lúc " + offlineClock + ".";

        // 1. Lưu In-App Notification trên DB cho FM + ADMIN + GUARD đang hoạt động (phục vụ chuông Web)
        try {
            List<User> webRecipients = new ArrayList<>();
            webRecipients.addAll(userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER));
            webRecipients.addAll(userRepository.findActiveUsersByRole(Role.ADMIN));
            webRecipients.addAll(userRepository.findActiveUsersByRole(Role.GUARD));
            inAppNotificationService.createForUsers(
                    webRecipients,
                    NotificationType.CAMERA_OFFLINE,
                    title,
                    message,
                    camera.getId(),
                    REF_TYPE_CAMERA
            );
        } catch (Exception e) {
            log.error("[CAMERA-ALERT] Lỗi tạo thông báo DB CAMERA_OFFLINE cho camera [{}]: {}", camCode, e.getMessage(), e);
        }

        CameraStatusAlertPayload payload = new CameraStatusAlertPayload(
                EVENT_CAMERA_OFFLINE,
                camera.getId(),
                camCode,
                camName,
                loc.areaLabel(),
                loc.building(),
                loc.floor(),
                offlineSince,
                now
        );

        // 2. Broadcast STOMP lên Web (/topic/cameras/status-alerts)
        sendStompSafely(WEB_CAMERA_ALERTS_TOPIC, payload);

        // 3. Gửi STOMP riêng cho bảo vệ đang CHECKED_IN thuộc toà nhà (fallback toàn khuôn viên) trên Mobile
        Set<UUID> notifiedGuards = new LinkedHashSet<>();
        try {
            List<User> mobileGuards = incidentRecipientResolver.findOnDutyGuardsForCameraBuilding(
                    now, loc.buildingId(), loc.building());
            for (User guard : mobileGuards) {
                if (guard == null || guard.getId() == null) {
                    continue;
                }
                sendStompSafely(guardCameraAlertTopic(guard.getId()), payload);
                notifiedGuards.add(guard.getId());
            }
            log.info("🚨 [CAMERA-ALERT] Đã phát cảnh báo mất kết nối camera [{}] ({}{}) tới Web và {} bảo vệ đang trực",
                    camCode, loc.areaLabel(), locationSuffix, notifiedGuards.size());
        } catch (Exception e) {
            log.error("[CAMERA-ALERT] Lỗi gửi STOMP mobile cho camera [{}]: {}", camCode, e.getMessage(), e);
        }
        return notifiedGuards;
    }

    private void dispatchRecoveryAlert(Camera camera, OffsetDateTime offlineSince, OffsetDateTime now, Set<UUID> prevNotifiedGuards) {
        LocationInfo loc = resolveLocation(camera);
        String camName = (camera.getName() != null && !camera.getName().isBlank())
                ? camera.getName().trim()
                : camera.getCameraCode();
        String camCode = camera.getCameraCode() != null ? camera.getCameraCode().trim() : "";

        CameraStatusAlertPayload payload = new CameraStatusAlertPayload(
                EVENT_CAMERA_ONLINE,
                camera.getId(),
                camCode,
                camName,
                loc.areaLabel(),
                loc.building(),
                loc.floor(),
                offlineSince,
                now
        );

        sendStompSafely(WEB_CAMERA_ALERTS_TOPIC, payload);

        Set<UUID> targetGuardIds = new LinkedHashSet<>(prevNotifiedGuards);
        try {
            List<User> currentOnDuty = incidentRecipientResolver.findOnDutyGuardsForCameraBuilding(
                    now, loc.buildingId(), loc.building());
            for (User guard : currentOnDuty) {
                if (guard != null && guard.getId() != null) {
                    targetGuardIds.add(guard.getId());
                }
            }
        } catch (Exception e) {
            log.warn("[CAMERA-ALERT] Không đọc được danh sách bảo vệ hiện tại khi thu hồi cảnh báo [{}]: {}", camCode, e.getMessage());
        }

        for (UUID guardId : targetGuardIds) {
            sendStompSafely(guardCameraAlertTopic(guardId), payload);
        }
        log.info("✅ [CAMERA-ALERT] Đã phát tin thu hồi cảnh báo (CAMERA_ONLINE) cho camera [{}]", camCode);
    }

    public static String guardCameraAlertTopic(UUID guardId) {
        return "/topic/guards/" + guardId + "/camera-alerts";
    }

    private void sendStompSafely(String destination, Object payload) {
        if (messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.error("[CAMERA-ALERT] Lỗi gửi STOMP tới [{}]: {}", destination, e.getMessage(), e);
        }
    }

    static String buildLocationParentheses(String building, String floor) {
        boolean hasBuilding = building != null && !building.isBlank();
        boolean hasFloor = floor != null && !floor.isBlank();
        if (hasBuilding && hasFloor) {
            return " (" + building.trim() + " · " + floor.trim() + ")";
        }
        if (hasBuilding) {
            return " (" + building.trim() + ")";
        }
        if (hasFloor) {
            return " (" + floor.trim() + ")";
        }
        return "";
    }

    private record LocationInfo(String areaLabel, UUID buildingId, String building, String floor) {
    }

    private LocationInfo resolveLocation(Camera camera) {
        Area area = camera.getArea();
        boolean hasActiveArea = area != null && area.getDeletedAt() == null;

        String areaLabel = hasActiveArea && area.getName() != null && !area.getName().isBlank()
                ? area.getName().trim()
                : "Hành lang";

        UUID buildingId = null;
        String building = null;
        String floor = null;

        if (hasActiveArea) {
            Floor floorEntity = area.getFloorEntity();
            if (floorEntity != null) {
                if (floorEntity.getBuilding() != null) {
                    Building b = floorEntity.getBuilding();
                    buildingId = b.getId();
                    if (b.getName() != null && !b.getName().isBlank()) {
                        building = b.getName().trim();
                    }
                }
                if (floorEntity.getName() != null && !floorEntity.getName().isBlank()) {
                    floor = floorEntity.getName().trim();
                }
            }
            if ((building == null || building.isBlank()) && area.getBuilding() != null && !area.getBuilding().isBlank()) {
                building = area.getBuilding().trim();
            }
            if ((floor == null || floor.isBlank()) && area.getFloor() != null && !area.getFloor().isBlank()) {
                floor = area.getFloor().trim();
            }
        }

        if ((building == null || building.isBlank()) && camera.getMapBuilding() != null && !camera.getMapBuilding().isBlank()) {
            building = camera.getMapBuilding().trim();
        }
        if ((floor == null || floor.isBlank()) && camera.getMapFloorOrder() != null) {
            floor = resolveFloorFromMap(building, camera.getMapFloorOrder());
        }

        return new LocationInfo(areaLabel, buildingId, building, floor);
    }

    private String resolveFloorFromMap(String mapBuilding, Integer mapFloorOrder) {
        if (mapFloorOrder == null) {
            return null;
        }
        if (floorRepository != null && mapBuilding != null && !mapBuilding.isBlank()) {
            try {
                for (Floor f : floorRepository.findAllActiveFloors()) {
                    if (f.getBuilding() != null
                            && StringNormalizer.matchesBuildingName(mapBuilding, f.getBuilding().getName())
                            && Objects.equals(f.getFloorOrder(), mapFloorOrder)) {
                        if (buildingHasName(f.getName())) {
                            return f.getName().trim();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return mapFloorOrder == 0 ? "Tầng Trệt" : "Tầng " + mapFloorOrder;
    }

    private static boolean buildingHasName(String s) {
        return s != null && !s.isBlank();
    }
}
