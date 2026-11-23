package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.dto.incident.IncidentResolveRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.IncidentDispatchOffer;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.CameraType;
import com.fa26se040.icss.enums.IncidentDispatchStage;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.exception.IncidentNotOfferedException;
import com.fa26se040.icss.exception.IncidentOfferNotPendingException;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.IncidentDispatchQueryRepository;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityIncidentService {

    private final SecurityIncidentRepository incidentRepository;
    private final CameraRepository cameraRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final CameraAccessLogService cameraAccessLogService;
    private final GuardShiftRepository guardShiftRepository;
    private final IncidentRecipientResolver incidentRecipientResolver;
    private final IncidentEscalationRepository incidentEscalationRepository;
    private final IncidentSeverityResolver incidentSeverityResolver;

    // V93 (K6): phân phối theo đợt. Inject qua field để KHÔNG đổi constructor: test dựng service thủ công / @InjectMocks
    // để hai field này null, khi đó ingest và claim chạy đúng luồng cũ.
    @Autowired(required = false)
    private IncidentDispatchService incidentDispatchService;

    @Autowired(required = false)
    private IncidentDispatchQueryRepository incidentDispatchQueryRepository;

    // G2 (DEF-16): /topic/guards/{id}/alerts chỉ app mobile subscribe -> chỉ bảo vệ có phiên mobile mới thực sự nhận được
    // cảnh báo. Inject qua field như hai field trên; null (test dựng thủ công) -> đếm như cũ.
    @Autowired(required = false)
    private com.fa26se040.icss.config.WebSocketSessionRegistry webSocketSessionRegistry;

    /** V129: loại sự kiện AI gửi khi nhận ra mặt sau khi đã chốt cảnh báo người lạ (không phải sự cố mới). */
    public static final String IDENTITY_UPDATE_EVENT = "IDENTITY_UPDATE";
    public static final String IDENTITY_AUTHORIZED = "AUTHORIZED";
    public static final String IDENTITY_UNAUTHORIZED = "UNAUTHORIZED";

    @org.springframework.beans.factory.annotation.Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    private final com.fa26se040.icss.config.AiServiceRestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @Autowired(required = false)
    private com.fa26se040.icss.repository.FloorRepository floorRepository;

    @Transactional
    public IncidentDetailResponse ingestIncident(IncidentEventDto eventDto) {
        log.info("Processing incoming incident for camera [{}] type [{}]",
                eventDto.getCameraCode(), eventDto.getEventType());

        // 0. Chống tạo trùng theo event_id (AI gửi lại cùng sự kiện qua MQTT hoặc REST): đã có thì trả sự cố cũ,
        //    không lưu, không gửi WebSocket, không tạo bản ghi leo thang, không bật ghi hình. event_id rỗng -> chạy như cũ.
        //    Hai tin đến cùng lúc cùng lọt qua bước này thì unique index uq_security_incidents_event_id (V92) chặn bản thứ hai.
        if (eventDto.getEventId() != null && !eventDto.getEventId().isBlank()) {
            Optional<SecurityIncident> existing = incidentRepository.findByEventId(eventDto.getEventId());
            if (existing.isPresent()) {
                log.info("Bỏ qua sự cố trùng event_id [{}]: đã có sự cố [{}]", eventDto.getEventId(), existing.get().getId());
                return mapToDetailResponse(existing.get(), null);
            }
        }

        // V129: AI nhận ra mặt sau khi đã chốt cảnh báo người lạ -> cập nhật / tự đóng sự cố cũ, KHÔNG tạo sự cố mới
        if (IDENTITY_UPDATE_EVENT.equalsIgnoreCase(eventDto.getEventType())) {
            return applyIdentityUpdate(eventDto);
        }

        // 1. Resolve camera and its assigned area
        Camera camera = cameraRepository.findByCameraCodeAndDeletedAtIsNull(eventDto.getCameraCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy camera với mã: " + eventDto.getCameraCode()));

        // Kiểm tra trạng thái camera: Nếu camera đã ngừng hoạt động (DECOMMISSIONED) hoặc đã bị xoá mềm -> bỏ qua
        if (camera.getStatus() == CameraStatus.DECOMMISSIONED || camera.getDeletedAt() != null) {
            log.warn("Bỏ qua sự cố vì camera [{}] đã ngừng hoạt động (status={}, deletedAt={})",
                    camera.getCameraCode(), camera.getStatus(), camera.getDeletedAt());
            return null;
        }

        Area area = camera.getArea();
        CameraType effectiveType = camera.getCameraType() != null
                ? camera.getCameraType()
                : CameraType.RESTRICTED_AREA;
        if (area == null && effectiveType != CameraType.CORRIDOR) {
            log.warn("Không tìm thấy khu vực hoạt động nào liên kết với camera [{}] để ghi nhận sự cố", eventDto.getCameraCode());
            throw new IllegalArgumentException("Camera " + eventDto.getCameraCode() + " chưa được gán vào khu vực hoạt động nào!");
        }

        String building = resolveBuildingName(area, camera);
        if (building == null) {
            log.warn("Camera [{}] (type={}) chưa có thông tin tòa nhà để ghi nhận sự cố", camera.getCameraCode(), effectiveType);
            throw new IllegalStateException("Camera " + camera.getCameraCode() + " chưa có thông tin tòa nhà để ghi nhận sự cố!");
        }
        String locationName = area != null ? area.getName()
                : (camera.getName() != null && !camera.getName().isBlank() ? camera.getName() : camera.getCameraCode());

        // Normalize MinIO image URL for web clients
        String imageUrl = normalizeImageUrl(eventDto.getImageUrl());

        // M5: Sử dụng detected_at chuẩn từ payload AI (chính xác đến mili giây), fallback về now() nếu thiếu/lệch
        OffsetDateTime detectedAt = parseDetectedAt(eventDto.getDetectedAt());

        String rawEventType = eventDto.getEventType() != null ? eventDto.getEventType() : "SECURITY_ALERT";
        String normalizedEventType = normalizeEventType(rawEventType);

        // Quyết định 03/11: AUTHORIZED_PERSON chỉ dùng cho lịch sử ra vào, không tạo sự cố
        if ("AUTHORIZED_PERSON".equals(normalizedEventType)) {
            log.info("Bỏ qua sự kiện [{}] (người hợp lệ) tại [{}]: không tạo sự cố.", rawEventType, locationName);
            return null;
        }

        // Quy chuẩn an ninh khuôn viên: Khu vực Cấp 1 (PUBLIC) hoặc Hành lang (CORRIDOR, dùng chuẩn PUBLIC) cho phép tự do
        // ra vào trong giờ hoạt động -> Không tạo cảnh báo UNKNOWN_PERSON và UNAUTHORIZED_PERSON trong giờ
        // (Ngoại lệ: Sự kiện Test do Bảo vệ kích hoạt để kiểm thử tính năng thì luôn được ghi nhận)
        boolean isTestEvent = eventDto.getEventId() != null
                && (eventDto.getEventId().startsWith("EVT-") || eventDto.getEventId().startsWith("TEST-"));

        AreaLevel effectiveAreaLevel = area != null ? area.getAreaLevel() : AreaLevel.PUBLIC;
        Integer effectiveAccessLevel = area != null ? area.getAreaAccessLevel() : 1;
        boolean isPublicArea = !isTestEvent && (effectiveAreaLevel == AreaLevel.PUBLIC
                || (effectiveAccessLevel != null && effectiveAccessLevel <= 1));

        if (isPublicArea && ("UNKNOWN_PERSON".equals(normalizedEventType) || "UNAUTHORIZED_PERSON".equals(normalizedEventType))) {
            log.info("Vị trí [{}] thuộc chuẩn PUBLIC - Bỏ qua cảnh báo [{}] trong giờ hoạt động bình thường.",
                    locationName, normalizedEventType);
            return null;
        }

        String cleanDetails = eventDto.getDetails() != null ? eventDto.getDetails().replaceAll("\\[[A-Z0-9_]+\\]\\s*", "").trim() : "";

        // Ngoại lệ cho cảnh báo ban đêm (PERSON_INTRUSION): Trừ bảo vệ trong ca trực
        if ("PERSON_INTRUSION".equals(normalizedEventType)) {
            ExtractedPersonInfo personInfo = extractPersonInfo(cleanDetails);
            if (personInfo.user != null && personInfo.user.getRole() == Role.GUARD) {
                if (isGuardOnShift(personInfo.user.getId(), detectedAt, area)) {
                    log.info("Bỏ qua cảnh báo PERSON_INTRUSION ngoài giờ vì [{}] ({}) là bảo vệ đang trong ca trực.",
                            personInfo.user.getFullName(), personInfo.user.getUserCode());
                    return null;
                }
            }
        }

        String incidentResolutionNotes = cleanDetails;
        if (incidentResolutionNotes.isBlank()) {
            incidentResolutionNotes = switch (normalizedEventType) {
                case "PERSON_INTRUSION" -> "Phát hiện đối tượng xuất hiện ngoài giờ hoạt động tại " + locationName + ".";
                case "UNAUTHORIZED_PERSON" -> "Phát hiện đối tượng không có quyền truy cập tại " + locationName + ".";
                case "UNKNOWN_PERSON" -> "Phát hiện người chưa đăng ký tại " + locationName + ".";
                case "SUSPICIOUS_MOVING_OBJECT" -> "Phát hiện vật thể chuyển động bất thường tại " + locationName + ".";
                default -> "Phát hiện sự cố an ninh tại " + locationName + ".";
            };
        }

        SecurityIncident incident = SecurityIncident.builder()
                .eventId(eventDto.getEventId())
                .cameraCode(eventDto.getCameraCode() != null ? eventDto.getCameraCode() : "CAM-UNKNOWN")
                .area(area)
                .building(building)
                .eventType(normalizedEventType)
                .imageUrl(imageUrl)
                .detectedAt(detectedAt)
                .status(IncidentStatus.NEW)
                .resolutionNotes(incidentResolutionNotes)
                .version(0)
                .broadcastAt(detectedAt)
                .aiSessionId(truncate(eventDto.getRoiSessionId(), 64))
                .build();
        incident.setSeverity(resolveSeverity(normalizedEventType, effectiveAreaLevel, locationName, detectedAt));
        // V93 (BR-DSP-02): mã người bị nhận diện, để loại người đó khỏi danh sách mời. Gọi parser có sẵn, không sửa regex.
        if (incidentDispatchService != null) {
            incident.setSubjectUserCode(subjectUserCodeOf(cleanDetails));
        }

        SecurityIncident saved = incidentRepository.save(incident);
        // Ghi xuống DB ngay: tin trùng đến cùng lúc vấp unique index event_id tại đây, trước mọi tác dụng phụ bên dưới
        incidentRepository.flush();
        IncidentDetailResponse response = mapToDetailResponse(saved, camera);

        // 1b. Tự động đồng bộ sự cố người lạ / vi phạm / sai giờ / ngoài giờ sang Nhật ký ra vào của Camera (Tab 4 Camera Detail)
        if (cameraAccessLogService != null && eventDto.getCameraCode() != null) {
            try {
                ExtractedPersonInfo personInfo = extractPersonInfo(cleanDetails);
                String logReason = cleanDetails;
                if (cleanDetails.contains("Lý do:")) {
                    logReason = cleanDetails.substring(cleanDetails.indexOf("Lý do:") + 6).trim();
                } else if (!personInfo.isExistingUser || logReason.isBlank()) {
                    logReason = "Phát hiện người chưa đăng ký tại khu vực giám sát.";
                }

                CheckCameraEntryResponse logEntry = CheckCameraEntryResponse.builder()
                        .cameraCode(eventDto.getCameraCode())
                        .areaId(area != null ? area.getId() : null)
                        .areaName(locationName)
                        .userCode(personInfo.userCode)
                        .fullName(personInfo.fullName)
                        .allowed(false)
                        .source(AccessSource.NONE)
                        .reason(logReason)
                        .build();
                cameraAccessLogService.recordAccessLog(logEntry, detectedAt);
            } catch (Exception ex) {
                log.warn("Không thể đồng bộ sự cố sang nhật ký camera: {}", ex.getMessage());
            }
        }

        // 1c. Tự động kích hoạt ghi hình sự cố an ninh (Incident Recording) sang AI Service
        // Bắt đầu ghi hình ngay lập tức từ khi phát hiện tới khi Bảo vệ click "Xác nhận đã xem"
        try {
            triggerIncidentRecordingStart(saved);
        } catch (Exception ex) {
            log.warn("Không thể kích hoạt ghi hình sự cố sang AI service: {}", ex.getMessage());
        }

        // 2'. V93 (K6, BR-DSP-01): phân phối theo đợt chỉ chạy khi DISPATCH_ENABLED = true. Tắt (hoặc service chưa inject)
        //     -> đi đúng luồng cũ ở mục 2 bên dưới. Bước 1 có người được mời -> KHÔNG gửi 3 kênh broadcast (X3).
        boolean dispatchEnabled = incidentDispatchService != null && incidentDispatchService.isEnabled();
        if (dispatchEnabled) {
            IncidentDetailResponse step1Response = startStep1IfPossible(saved, camera);
            if (step1Response != null) {
                return step1Response;
            }
        }

        // 2. Real-time WebSocket dispatching
        // A. BR-IE-01 / BR-IE-02: gửi cho bảo vệ đang trực (ca CHECKED_IN, trong khung giờ).
        //    BR-IE-03: tạo 1 bản ghi leo thang cùng transaction, đếm số bảo vệ đã gửi được.
        //    Resolver lỗi -> vẫn tạo bản ghi với 0 người nhận để job báo FM ở lần chạy kế (BR-IE-05).
        //    Lỗi SQL trong transaction này làm cả transaction rollback (kể cả sự cố) — try/catch chỉ chặn lỗi tầng Java.
        //    V93: phần gửi tách thành sendToOnDutyGuards / sendToBuildingAndGlobalTopics (giữ nguyên nội dung, thứ tự)
        //    để job dùng lại khi sự cố chuyển STEP1 -> BROADCAST.
        int guardRecipientCount = sendToOnDutyGuards(saved, response, locationName, building, detectedAt);

        try {
            incidentEscalationRepository.save(IncidentEscalation.builder()
                    .incident(saved)
                    .guardsNotifiedAt(OffsetDateTime.now())
                    .guardRecipientCount(guardRecipientCount)
                    .build());
        } catch (Exception e) {
            log.error("Không tạo được bản ghi leo thang cho sự cố [{}]: {}", saved.getId(), e.getMessage(), e);
        }

        sendToBuildingAndGlobalTopics(saved, response, building);

        // V93 (BR-DSP-09): mức có notify_fm_immediately -> báo FM ngay qua kênh riêng (sau commit). Chỉ khi DISPATCH_ENABLED.
        if (dispatchEnabled) {
            incidentDispatchService.sendCriticalNoticeIfConfigured(saved);
        }
        return response;
    }

    /**
     * Mục 2.A của ingest (tách nguyên văn ở V93): gửi sự cố tới kênh riêng của từng bảo vệ đang trực, trong transaction
     * hiện tại. Trả về số bảo vệ đã gửi được.
     */
    private int sendToOnDutyGuards(SecurityIncident saved, IncidentDetailResponse response, String areaName,
                                   String building, OffsetDateTime detectedAt) {
        List<User> onDutyGuards = List.of();
        try {
            onDutyGuards = incidentRecipientResolver.findOnDutyGuards(detectedAt);
        } catch (Exception e) {
            log.error("Lỗi khi tìm bảo vệ đang trực cho sự cố [{}]: {}", saved.getId(), e.getMessage(), e);
        }
        log.info("Incident [{}] in area [{}] (Building: [{}]): Found [{}] on-duty guards to notify",
                saved.getId(), areaName, building, onDutyGuards.size());

        int guardRecipientCount = 0;
        for (User guard : onDutyGuards) {
            String guardTopic = "/topic/guards/" + guard.getId() + "/alerts";
            try {
                messagingTemplate.convertAndSend(guardTopic, response);
                if (canReceiveGuardAlerts(guard)) {
                    guardRecipientCount++;
                }
                log.info("Dispatched incident [{}] to guard [{}] ({}) at topic [{}]",
                        saved.getId(), guard.getFullName(), guard.getEmail(), guardTopic);
            } catch (Exception e) {
                log.error("Lỗi khi gửi cảnh báo sự cố [{}] tới [{}]: {}", saved.getId(), guardTopic, e.getMessage(), e);
            }
        }
        return guardRecipientCount;
    }

    /**
     * G2 (BR-IE-05): bảo vệ được tính là "đã nhận" cảnh báo khi có phiên STOMP từ mobile (kênh riêng chỉ app mobile nghe).
     * Mọi bảo vệ đang trực đều đã đăng xuất app -> guard_recipient_count = 0 -> job leo thang báo FM ngay.
     */
    private boolean canReceiveGuardAlerts(User guard) {
        return webSocketSessionRegistry == null || webSocketSessionRegistry.hasMobileSession(guard.getId());
    }

    /** Mục 2.B và 2.C của ingest (tách nguyên văn ở V93): kênh theo toà và kênh toàn cục. */
    private void sendToBuildingAndGlobalTopics(SecurityIncident saved, IncidentDetailResponse response, String building) {
        // B. Building-scoped topic (for web dashboard & monitoring)
        String buildingTopic = "/topic/buildings/" + building.toUpperCase() + "/alerts";
        messagingTemplate.convertAndSend(buildingTopic, response);

        // C. Global topic (for system admins & overview)
        messagingTemplate.convertAndSend("/topic/security-alerts", response);

        log.info("Broadcasted new incident [{}] to [{}] and [/topic/security-alerts]", saved.getId(), buildingTopic);
    }

    /**
     * V93 (K6 bước 3, BR-DSP-02..04): mời tối đa step1_size bảo vệ. Trả về response (dispatchStage = STEP1) nếu đã mời;
     * null nếu không vào bước 1 (step1_size = 0, không có ứng viên E1, hoặc lỗi chọn ứng viên) -> ingest phát như cũ.
     */
    private IncidentDetailResponse startStep1IfPossible(SecurityIncident saved, Camera camera) {
        OffsetDateTime now = OffsetDateTime.now();
        IncidentDispatchPolicy policy;
        List<User> invited;
        try {
            policy = incidentDispatchService.policyFor(saved.getSeverity()).orElse(null);
            if (policy == null || policy.getStep1Size() == null || policy.getStep1Size() <= 0) {
                return null;
            }
            invited = incidentDispatchService.selectStep1Guards(saved, policy, now);
        } catch (Exception e) {
            log.error("[DISPATCH] incident={}: lỗi khi chọn bảo vệ bước 1, phát cảnh báo như cũ: {}",
                    saved.getId(), e.getMessage(), e);
            return null;
        }
        if (invited.isEmpty()) {
            log.info("[DISPATCH] incident={}: không có bảo vệ đủ điều kiện ở bước 1 (E1), phát cảnh báo toàn bộ", saved.getId());
            return null;
        }

        saved.setDispatchStage(IncidentDispatchStage.STEP1);
        saved.setBroadcastAt(null);
        List<IncidentDispatchOffer> offers = incidentDispatchService.createOffers(saved, invited, policy, now);
        IncidentDetailResponse response = mapToDetailResponse(saved, camera);

        // BR-IE-03: vẫn tạo bản ghi leo thang. Số bảo vệ được nhắm tới = số người được mời (khác 0), để job leo thang
        // không báo FM ngay; hạn leo thang vẫn tính từ lúc tạo sự cố (BR-DSP-09)
        try {
            incidentEscalationRepository.save(IncidentEscalation.builder()
                    .incident(saved)
                    .guardsNotifiedAt(now)
                    .guardRecipientCount(offers.size())
                    .build());
        } catch (Exception e) {
            log.error("Không tạo được bản ghi leo thang cho sự cố [{}]: {}", saved.getId(), e.getMessage(), e);
        }

        incidentDispatchService.sendOffersAfterCommit(offers, response);
        incidentDispatchService.sendCriticalNoticeIfConfigured(saved);
        log.info("[DISPATCH] incident={} severity={}: mời {} bảo vệ ở bước 1, hạn {} giây",
                saved.getId(), saved.getSeverity(), offers.size(), policy.getStep1TimeoutSeconds());
        return response;
    }

    /**
     * V93 (K4 bước 4, BR-DSP-05): sự cố ở bước 1, còn NEW và không còn lời mời chờ -> chuyển BROADCAST (conditional
     * UPDATE) và phát cảnh báo toàn bộ sau commit bằng chính hai method gửi của ingest. Job hết hạn gọi qua proxy, mỗi sự
     * cố một transaction.
     *
     * @return true nếu lần gọi này đã chuyển sự cố sang BROADCAST
     */
    @Transactional
    public boolean evaluateDispatch(UUID incidentId, OffsetDateTime now) {
        return promoteToBroadcastIfNoPendingOffer(incidentId, now);
    }

    /**
     * V93 (K5): bảo vệ từ chối lời mời của mình. Không còn lời mời chờ, chưa hết hạn -> IncidentOfferNotPendingException.
     * E6: người cuối cùng ở bước 1 từ chối -> phát cảnh báo toàn bộ ngay, không chờ job.
     */
    @Transactional
    public void declineOffer(UUID incidentId, String guardEmail) {
        User guard = userRepository.findByEmail(guardEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản bảo vệ"));
        OffsetDateTime now = OffsetDateTime.now();
        if (incidentDispatchService == null || !incidentDispatchService.decline(incidentId, guard.getId(), now)) {
            throw new IncidentOfferNotPendingException("Không còn lời mời xử lý sự cố này để từ chối");
        }
        log.info("[DISPATCH] incident={}: bảo vệ [{}] từ chối lời mời", incidentId, guard.getFullName());
        promoteToBroadcastIfNoPendingOffer(incidentId, now);
    }

    private boolean promoteToBroadcastIfNoPendingOffer(UUID incidentId, OffsetDateTime now) {
        if (incidentDispatchService == null || incidentDispatchQueryRepository == null) {
            return false;
        }
        if (incidentDispatchService.hasPendingOffers(incidentId)) {
            return false;
        }
        SecurityIncident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null || incident.getStatus() != IncidentStatus.NEW
                || incident.getDispatchStage() != IncidentDispatchStage.STEP1) {
            return false;
        }
        if (incidentDispatchQueryRepository.promoteToBroadcast(incidentId, now) != 1) {
            return false;
        }

        // promoteToBroadcast không làm mới entity trong persistence context. Không sửa entity (tránh UPDATE thừa và tăng
        // version, làm claim song song bị 409 oan), chỉ sửa DTO gửi đi.
        Camera cam = findCameraByCode(incident.getCameraCode());
        IncidentDetailResponse response = mapToDetailResponse(incident, cam);
        response.setDispatchStage(IncidentDispatchStage.BROADCAST);
        String areaName = incident.getArea() != null ? incident.getArea().getName()
                : (cam != null && cam.getName() != null ? cam.getName() : null);
        String candidateBuilding = resolveBuildingName(incident.getArea(), cam);
        final String building = (candidateBuilding != null && !candidateBuilding.isBlank())
                ? candidateBuilding
                : incident.getBuilding();
        if (building == null || building.isBlank()) {
            log.warn("[DISPATCH] incident={}: thiếu thông tin tòa nhà khi chuyển BROADCAST", incidentId);
            return false;
        }

        // Số bảo vệ được nhắm tới đổi thành số người nhận broadcast (0 -> job leo thang báo FM ở lần chạy kế)
        int targeted = 0;
        try {
            targeted = (int) incidentRecipientResolver.findOnDutyGuards(now).stream()
                    .filter(this::canReceiveGuardAlerts)
                    .count();
        } catch (Exception e) {
            log.error("Lỗi khi tìm bảo vệ đang trực cho sự cố [{}]: {}", incidentId, e.getMessage(), e);
        }
        incidentEscalationRepository.updateGuardRecipientCountForIncident(incidentId, targeted);

        IncidentDispatchService.runAfterCommit(() -> {
            sendToOnDutyGuards(incident, response, areaName, building, now);
            sendToBuildingAndGlobalTopics(incident, response, building);
        });
        log.info("[DISPATCH] incident={}: hết lời mời ở bước 1, chuyển BROADCAST ({} bảo vệ đang trực)", incidentId, targeted);
        return true;
    }

    /**
     * V129: AI đã chốt cảnh báo người lạ (UNKNOWN_PERSON / PERSON_INTRUSION) rồi mới nhận ra mặt. Tìm sự cố theo
     * (roi_session_id, camera) và:
     * - UNAUTHORIZED: ghi tên, lý do vào sự cố; UNKNOWN_PERSON -> UNAUTHORIZED_PERSON (PERSON_INTRUSION giữ nguyên);
     *   mức độ chỉ nâng, không hạ. Không mời / phát lại cho bảo vệ.
     * - AUTHORIZED, sự cố còn NEW: tự đóng (RESOLVED_DISMISSED, FALSE_ALARM, không người xử lý), huỷ lời mời còn chờ,
     *   dừng ghi hình sự cố.
     * - AUTHORIZED, sự cố đã CLAIMED: giữ nguyên, chỉ báo cho bảo vệ (bảo vệ tự đóng).
     * Mọi trường hợp gửi /topic/incidents/updates sau commit, kèm identityNotice để web / mobile hiện thông báo không còi.
     * Không tìm thấy sự cố (vd. khu PUBLIC bị bỏ qua lúc ingest) hoặc sự cố đã đóng -> bỏ qua.
     */
    IncidentDetailResponse applyIdentityUpdate(IncidentEventDto dto) {
        String sessionId = dto.getRoiSessionId();
        if (sessionId == null || sessionId.isBlank() || dto.getCameraCode() == null) {
            log.warn("[IDENTITY_UPDATE] Thiếu roi_session_id hoặc camera_code, bỏ qua (event_id={})", dto.getEventId());
            return null;
        }
        Optional<SecurityIncident> found = incidentRepository
                .findFirstByAiSessionIdAndCameraCodeOrderByDetectedAtDesc(sessionId, dto.getCameraCode());
        if (found.isEmpty()) {
            log.info("[IDENTITY_UPDATE] Không có sự cố của phiên [{}] camera [{}], bỏ qua", sessionId, dto.getCameraCode());
            return null;
        }
        SecurityIncident incident = found.get();
        if (incident.getStatus() == IncidentStatus.RESOLVED_VERIFIED || incident.getStatus() == IncidentStatus.RESOLVED_DISMISSED) {
            log.info("[IDENTITY_UPDATE] Sự cố [{}] đã đóng ({}), bỏ qua", incident.getId(), incident.getStatus());
            return mapToDetailResponse(incident, null);
        }

        String outcome = dto.getIdentityOutcome() != null ? dto.getIdentityOutcome().trim().toUpperCase() : "";
        String code = dto.getUserCode() != null && !dto.getUserCode().isBlank() ? dto.getUserCode().trim() : null;
        String name = dto.getUserName() != null && !dto.getUserName().isBlank() ? dto.getUserName().trim() : code;
        String who = code != null ? name + " (" + code + ")" : "người trong khu vực";
        Camera cam = incident.getArea() == null ? findCameraByCode(incident.getCameraCode()) : null;
        String areaName = incident.getArea() != null ? incident.getArea().getName()
                : (cam != null && cam.getName() != null && !cam.getName().isBlank() ? cam.getName() : incident.getCameraCode());

        if (IDENTITY_AUTHORIZED.equals(outcome)) {
            return applyAuthorizedIdentity(incident, dto, who, areaName, cam);
        }
        if (IDENTITY_UNAUTHORIZED.equals(outcome)) {
            return applyUnauthorizedIdentity(incident, dto, code, who, areaName, cam);
        }
        log.warn("[IDENTITY_UPDATE] identity_outcome không hợp lệ [{}] cho sự cố [{}], bỏ qua", dto.getIdentityOutcome(), incident.getId());
        return null;
    }

    private IncidentDetailResponse applyAuthorizedIdentity(SecurityIncident incident, IncidentEventDto dto, String who,
                                                           String areaName, Camera cam) {
        String newImageUrl = normalizeImageUrl(dto.getImageUrl());
        if (newImageUrl != null && !newImageUrl.isBlank()) {
            incident.setImageUrl(newImageUrl);
        }

        if (incident.getStatus() != IncidentStatus.NEW) {
            // Đã có bảo vệ nhận: không tự đóng, chỉ báo để bảo vệ tự quyết định
            if (newImageUrl != null && !newImageUrl.isBlank()) {
                incidentRepository.saveAndFlush(incident);
            }
            Map<String, Object> payload = baseIdentityPayload(incident);
            if (incident.getImageUrl() != null) {
                payload.put("imageUrl", incident.getImageUrl());
            }
            payload.put("identityNotice", "Sự cố tại " + areaName + ": AI xác minh " + who
                    + " có quyền ra vào. Bạn có thể đóng sự cố.");
            sendIncidentUpdateAfterCommit(payload);
            log.info("[IDENTITY_UPDATE] Sự cố [{}] đã được nhận ({}): {} có quyền, chỉ gửi thông báo", incident.getId(),
                    incident.getStatus(), who);
            return mapToDetailResponse(incident, cam);
        }

        OffsetDateTime now = OffsetDateTime.now();
        String autoNote = "Tự đóng: AI xác minh danh tính muộn, " + who + " có quyền ra vào.";
        String oldNotes = incident.getResolutionNotes();
        incident.setStatus(IncidentStatus.RESOLVED_DISMISSED);
        incident.setOutcome(IncidentOutcome.DISMISSED);
        incident.setResolutionCategory(IncidentResolutionCategory.FALSE_ALARM);
        incident.setResolutionNotes(oldNotes == null || oldNotes.isBlank() ? autoNote : oldNotes + "\n" + autoNote);
        incident.setResolvedBy(null);
        incident.setResolvedAt(now);
        SecurityIncident saved = incidentRepository.saveAndFlush(incident);

        if (incidentDispatchService != null) {
            incidentDispatchService.cancelPendingOffersForClosedIncident(saved.getId(), now);
        }
        UUID incidentId = saved.getId();
        IncidentDispatchService.runAfterCommit(() -> triggerIncidentRecordingStop(incidentId));

        Map<String, Object> payload = baseIdentityPayload(saved);
        payload.put("status", saved.getStatus().name());
        payload.put("outcome", saved.getOutcome().name());
        payload.put("resolutionCategory", saved.getResolutionCategory().name());
        payload.put("resolutionNotes", saved.getResolutionNotes());
        payload.put("resolvedById", null);
        payload.put("resolvedByName", null);
        payload.put("resolvedAt", saved.getResolvedAt());
        payload.put("autoResolved", true);
        if (saved.getImageUrl() != null) {
            payload.put("imageUrl", saved.getImageUrl());
        }
        payload.put("identityNotice", "Sự cố người lạ tại " + areaName + " đã tự đóng: AI xác minh " + who
                + " có quyền ra vào.");
        sendIncidentUpdateAfterCommit(payload);
        log.info("[IDENTITY_UPDATE] Tự đóng sự cố [{}]: {} có quyền ra vào", saved.getId(), who);
        return mapToDetailResponse(saved, cam);
    }

    private IncidentDetailResponse applyUnauthorizedIdentity(SecurityIncident incident, IncidentEventDto dto, String code,
                                                             String who, String areaName, Camera cam) {
        String cleanDetails = dto.getDetails() != null ? dto.getDetails().replaceAll("\\[[A-Z0-9_]+\\]\\s*", "").trim() : "";
        if ("UNKNOWN_PERSON".equals(incident.getEventType())) {
            incident.setEventType("UNAUTHORIZED_PERSON");
        }
        if (!cleanDetails.isBlank()) {
            incident.setResolutionNotes(cleanDetails);
        }
        if (code != null) {
            incident.setSubjectUserCode(truncate(code, 50));
        }
        String newImageUrl = normalizeImageUrl(dto.getImageUrl());
        if (newImageUrl != null && !newImageUrl.isBlank()) {
            incident.setImageUrl(newImageUrl);
        }
        AreaLevel effectiveLevel = incident.getArea() != null ? incident.getArea().getAreaLevel() : AreaLevel.PUBLIC;
        IncidentSeverity raised = resolveSeverity(incident.getEventType(), effectiveLevel, areaName, incident.getDetectedAt());
        if (raised != null && (incident.getSeverity() == null || raised.ordinal() > incident.getSeverity().ordinal())) {
            incident.setSeverity(raised);
        }
        SecurityIncident saved = incidentRepository.saveAndFlush(incident);

        Map<String, Object> payload = baseIdentityPayload(saved);
        payload.put("eventType", saved.getEventType());
        payload.put("resolutionNotes", saved.getResolutionNotes());
        payload.put("subjectUserCode", saved.getSubjectUserCode());
        if (saved.getImageUrl() != null) {
            payload.put("imageUrl", saved.getImageUrl());
        }
        payload.put("identityNotice", "Sự cố tại " + areaName + ": AI nhận diện được " + who + ", không có quyền ra vào.");
        sendIncidentUpdateAfterCommit(payload);
        log.info("[IDENTITY_UPDATE] Cập nhật sự cố [{}] theo danh tính: {} không có quyền", saved.getId(), who);
        return mapToDetailResponse(saved, cam);
    }

    private Map<String, Object> baseIdentityPayload(SecurityIncident incident) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("incidentId", incident.getId());
        payload.put("severity", severityName(incident));
        payload.put("dispatchStage", dispatchStageName(incident));
        payload.put("identityUpdate", true);
        return payload;
    }

    private void sendIncidentUpdateAfterCommit(Map<String, Object> payload) {
        IncidentDispatchService.runAfterCommit(() -> {
            try {
                messagingTemplate.convertAndSend("/topic/incidents/updates", payload);
            } catch (Exception e) {
                log.error("[IDENTITY_UPDATE] Gửi cập nhật sự cố [{}] lỗi: {}", payload.get("incidentId"), e.getMessage(), e);
            }
        });
    }

    private static String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String trimmed = imageUrl.trim();
        if (trimmed.contains("minio:9000")) {
            return trimmed.replace("minio:9000", "localhost:9000");
        }
        return trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** V93 (BR-DSP-02): mã người bị nhận diện trong details (parser có sẵn); người lạ / không xác định -> null. */
    private String subjectUserCodeOf(String cleanDetails) {
        ExtractedPersonInfo info = extractPersonInfo(cleanDetails);
        if (info.userCode == null || info.userCode.isBlank() || "UNKNOWN".equalsIgnoreCase(info.userCode)) {
            return null;
        }
        return info.userCode.length() > 50 ? info.userCode.substring(0, 50) : info.userCode;
    }

    /** V93 (E2): tên người đã nhận sự cố, đọc thẳng từ DB; không đọc được -> null. */
    private String currentClaimerName(UUID incidentId) {
        if (incidentDispatchQueryRepository == null) {
            return null;
        }
        try {
            return incidentDispatchQueryRepository.findClaimerFullName(incidentId).orElse(null);
        } catch (Exception e) {
            log.warn("Không đọc được người đã nhận sự cố [{}]: {}", incidentId, e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<IncidentDetailResponse> getActiveIncidents(String building) {
        List<SecurityIncident> list = incidentRepository.findActiveIncidents(building);
        return mapToDetailResponses(list);
    }

    @Transactional(readOnly = true)
    public List<IncidentDetailResponse> getIncidents(String building, IncidentStatus status) {
        List<SecurityIncident> list = incidentRepository.findIncidents(building, status);
        return mapToDetailResponses(list);
    }

    @Transactional
    public IncidentClaimResponse claimIncident(UUID incidentId, String guardEmail) {
        SecurityIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sự cố an ninh"));

        if (incident.getStatus() != IncidentStatus.NEW) {
            String claimantName = incident.getClaimedBy() != null ? incident.getClaimedBy().getFullName() : "đồng đội khác";
            throw new IllegalStateException("Sự việc đã được tiếp nhận bởi " + claimantName);
        }

        User guard = userRepository.findByEmail(guardEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản bảo vệ"));

        // V93 (BR-DSP-06): sự cố đang ở bước 1 -> chỉ người có lời mời còn chờ, chưa hết hạn mới nhận được.
        // Đặt SAU kiểm status (giữ 409 khi đã có người nhận). Bước BROADCAST giữ nguyên luật cũ.
        if (incident.getDispatchStage() == IncidentDispatchStage.STEP1 && incidentDispatchService != null
                && !incidentDispatchService.hasActiveOffer(incidentId, guard.getId(), OffsetDateTime.now())) {
            throw new IncidentNotOfferedException("Sự cố đang được mời bảo vệ khác xử lý");
        }

        try {
            incident.setStatus(IncidentStatus.CLAIMED);
            incident.setClaimedBy(guard);
            incident.setClaimedAt(OffsetDateTime.now());

            SecurityIncident saved = incidentRepository.saveAndFlush(incident);

            // V93 (K5, BR-DSP-07): lời mời của người nhận -> ACCEPTED, lời mời khác -> CANCELLED + báo sau commit
            if (incidentDispatchService != null) {
                incidentDispatchService.onClaimed(saved.getId(), guard.getId(), saved.getClaimedAt());
            }

            // Sync with all Web & Mobile clients
            Map<String, Object> updatePayload = new HashMap<>();
            updatePayload.put("incidentId", saved.getId());
            updatePayload.put("status", saved.getStatus().name());
            updatePayload.put("claimedById", guard.getId());
            updatePayload.put("claimedByName", guard.getFullName());
            updatePayload.put("claimedAt", saved.getClaimedAt());
            updatePayload.put("severity", severityName(saved));
            updatePayload.put("dispatchStage", dispatchStageName(saved));

            messagingTemplate.convertAndSend("/topic/incidents/updates", updatePayload);
            log.info("Incident [{}] claimed successfully by [{}]", incidentId, guard.getFullName());

            return IncidentClaimResponse.builder()
                    .incidentId(saved.getId())
                    .status(saved.getStatus().name())
                    .message("Tiếp nhận sự vụ thành công")
                    .claimedById(guard.getId())
                    .claimantName(guard.getFullName())
                    .claimedAt(saved.getClaimedAt())
                    .build();

        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic locking conflict on claiming incident [{}]: {}", incidentId, e.getMessage());
            // V93 (E2): entity trong persistence context đã bị gán người claim của chính request này -> đọc tên người
            // đã nhận thật từ DB trước; không đọc được thì dùng cách cũ.
            String claimantName = currentClaimerName(incidentId);
            if (claimantName == null) {
                SecurityIncident fresh = incidentRepository.findById(incidentId).orElse(incident);
                claimantName = fresh.getClaimedBy() != null ? fresh.getClaimedBy().getFullName() : "đồng đội khác";
            }
            throw new IllegalStateException("Sự việc vừa được tiếp nhận bởi " + claimantName);
        }
    }

    @Transactional
    public IncidentDetailResponse resolveIncident(UUID incidentId, IncidentResolveRequest request, String guardEmail) {
        SecurityIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sự cố an ninh"));

        User guard = userRepository.findByEmail(guardEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản bảo vệ"));

        // Validation 1: Incident must not be in NEW status
        if (incident.getStatus() == IncidentStatus.NEW) {
            throw new IllegalStateException("Cần tiếp nhận sự cố trước khi xử lý");
        }

        // Validation 2: Incident must not already be resolved
        if (incident.getStatus().name().startsWith("RESOLVED")) {
            throw new IllegalStateException("Sự cố này đã được xử lý");
        }

        // Validation 3: Only the claiming guard can resolve the incident
        if (incident.getClaimedBy() == null || !incident.getClaimedBy().getEmail().equalsIgnoreCase(guardEmail)) {
            throw new AccessDeniedException("Bạn không phải người tiếp nhận sự cố này");
        }

        IncidentStatus newStatus = request.getOutcome() == IncidentOutcome.VERIFIED
                ? IncidentStatus.RESOLVED_VERIFIED
                : IncidentStatus.RESOLVED_DISMISSED;

        incident.setStatus(newStatus);
        incident.setOutcome(request.getOutcome());
        incident.setResolutionCategory(request.getResolutionCategory());
        incident.setResolutionNotes(request.getResolutionNotes());
        incident.setEvidenceImageUrl(request.getEvidenceImageUrl());
        incident.setResolvedBy(guard);
        incident.setResolvedAt(OffsetDateTime.now());

        SecurityIncident saved = incidentRepository.saveAndFlush(incident);

        // Sync with all Web & Mobile clients
        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("incidentId", saved.getId());
        updatePayload.put("status", saved.getStatus().name());
        updatePayload.put("outcome", saved.getOutcome().name());
        updatePayload.put("resolutionCategory", saved.getResolutionCategory().name());
        updatePayload.put("resolutionNotes", saved.getResolutionNotes());
        updatePayload.put("resolvedById", guard.getId());
        updatePayload.put("resolvedByName", guard.getFullName());
        updatePayload.put("resolvedAt", saved.getResolvedAt());
        updatePayload.put("severity", severityName(saved));
        updatePayload.put("dispatchStage", dispatchStageName(saved));

        messagingTemplate.convertAndSend("/topic/incidents/updates", updatePayload);
        log.info("Incident [{}] resolved with status [{}] by [{}]", incidentId, newStatus, guard.getFullName());

        return mapToDetailResponse(saved);
    }

    private List<IncidentDetailResponse> mapToDetailResponses(List<SecurityIncident> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Map<String, Camera> camerasByCode = new HashMap<>();
        java.util.Set<String> codes = list.stream()
                .map(SecurityIncident::getCameraCode)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toSet());
        if (!codes.isEmpty() && cameraRepository != null) {
            List<Camera> found = cameraRepository.findByCameraCodeIn(codes);
            if (found != null) {
                for (Camera c : found) {
                    if (c.getCameraCode() != null) {
                        camerasByCode.put(c.getCameraCode().toUpperCase(), c);
                    }
                }
            }
        }
        return list.stream()
                .map(i -> {
                    Camera cam = i.getCameraCode() != null ? camerasByCode.get(i.getCameraCode().toUpperCase()) : null;
                    if (cam == null) {
                        cam = findCameraByCode(i.getCameraCode());
                    }
                    return mapToDetailResponse(i, cam);
                })
                .collect(Collectors.toList());
    }

    private Camera findCameraByCode(String cameraCode) {
        if (cameraRepository == null || cameraCode == null || cameraCode.isBlank()) {
            return null;
        }
        return cameraRepository.findByCameraCode(cameraCode).orElse(null);
    }

    private IncidentDetailResponse mapToDetailResponse(SecurityIncident i) {
        return mapToDetailResponse(i, findCameraByCode(i.getCameraCode()));
    }

    private IncidentDetailResponse mapToDetailResponse(SecurityIncident i, Camera cam) {
        Integer effectiveFloorOrder = resolveEffectiveFloorOrder(i.getArea(), cam);
        return IncidentDetailResponse.builder()
                .id(i.getId())
                .eventId(i.getEventId())
                .cameraCode(i.getCameraCode())
                .cameraName(cam != null ? cam.getName() : null)
                .cameraType(cam != null ? cam.getCameraType() : null)
                .areaId(i.getArea() != null ? i.getArea().getId() : null)
                .areaName(i.getArea() != null ? i.getArea().getName() : null)
                .building(i.getBuilding())
                .floor(resolveFloorDisplay(i.getArea(), cam, i.getBuilding(), effectiveFloorOrder))
                .mapLatitude(cam != null ? cam.getMapLatitude() : null)
                .mapLongitude(cam != null ? cam.getMapLongitude() : null)
                .mapFloorOrder(effectiveFloorOrder)
                .eventType(i.getEventType())
                .imageUrl(i.getImageUrl())
                .detectedAt(i.getDetectedAt())
                .status(i.getStatus())
                .claimedById(i.getClaimedBy() != null ? i.getClaimedBy().getId() : null)
                .claimedByName(i.getClaimedBy() != null ? i.getClaimedBy().getFullName() : null)
                .claimedAt(i.getClaimedAt())
                .resolvedById(i.getResolvedBy() != null ? i.getResolvedBy().getId() : null)
                .resolvedByName(i.getResolvedBy() != null ? i.getResolvedBy().getFullName() : null)
                .resolvedAt(i.getResolvedAt())
                .outcome(i.getOutcome())
                .resolutionCategory(i.getResolutionCategory())
                .resolutionNotes(i.getResolutionNotes())
                .evidenceImageUrl(i.getEvidenceImageUrl())
                .radioChannel("Kênh " + (i.getBuilding() != null ? i.getBuilding() : "Chính"))
                .severity(i.getSeverity())
                .dispatchStage(i.getDispatchStage())
                .build();
    }

    private static String resolveBuildingName(Area area, Camera camera) {
        if (area != null && area.getBuilding() != null && !area.getBuilding().isBlank()) {
            return area.getBuilding().trim();
        }
        if (area != null && area.getFloorEntity() != null && area.getFloorEntity().getBuilding() != null
                && area.getFloorEntity().getBuilding().getName() != null
                && !area.getFloorEntity().getBuilding().getName().isBlank()) {
            return area.getFloorEntity().getBuilding().getName().trim();
        }
        if (camera != null && camera.getMapBuilding() != null && !camera.getMapBuilding().isBlank()) {
            return camera.getMapBuilding().trim();
        }
        return null;
    }

    private static Integer resolveEffectiveFloorOrder(Area area, Camera cam) {
        if (cam != null && cam.getMapFloorOrder() != null) {
            return cam.getMapFloorOrder();
        }
        if (area != null && area.getFloorEntity() != null) {
            return area.getFloorEntity().getFloorOrder();
        }
        return null;
    }

    private String resolveFloorDisplay(Area area, Camera cam, String building, Integer floorOrder) {
        if (area != null) {
            if (area.getFloor() != null && !area.getFloor().isBlank()) {
                return area.getFloor().trim();
            }
            if (area.getFloorEntity() != null && area.getFloorEntity().getName() != null
                    && !area.getFloorEntity().getName().isBlank()) {
                return area.getFloorEntity().getName().trim();
            }
        }
        if (floorOrder == null) {
            return null;
        }
        String lookupBuilding = (cam != null && cam.getMapBuilding() != null && !cam.getMapBuilding().isBlank())
                ? cam.getMapBuilding()
                : building;
        if (floorRepository != null && lookupBuilding != null && !lookupBuilding.isBlank()) {
            try {
                for (Floor f : floorRepository.findAllActiveFloors()) {
                    if (f.getBuilding() != null
                            && com.fa26se040.icss.util.StringNormalizer.matchesBuildingName(lookupBuilding, f.getBuilding().getName())
                            && java.util.Objects.equals(f.getFloorOrder(), floorOrder)) {
                        return f.getName();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return floorOrder == 0 ? "Tầng Trệt" : "Tầng " + floorOrder;
    }

    /**
     * V90 (BR-SEV-01): mức độ của sự cố mới. Resolver chưa được inject (test dựng service thủ công) hoặc lỗi tầng Java
     * -> NULL, ingest chạy tiếp như trước V90.
     */
    private IncidentSeverity resolveSeverity(String eventType, AreaLevel areaLevel, String locationName, OffsetDateTime at) {
        if (incidentSeverityResolver == null) {
            return null;
        }
        try {
            return incidentSeverityResolver.resolve(eventType, areaLevel, at);
        } catch (Exception e) {
            log.error("Không tính được severity cho sự cố loại [{}] tại [{}]: {}", eventType, locationName, e.getMessage(), e);
            return null;
        }
    }

    /** V93: tên dispatch stage trong map gửi /topic/incidents/updates. */
    private static String dispatchStageName(SecurityIncident incident) {
        return incident.getDispatchStage() != null ? incident.getDispatchStage().name() : null;
    }

    /** V90: tên severity trong map gửi /topic/incidents/updates; sự cố cũ -> null. */
    private static String severityName(SecurityIncident incident) {
        return incident.getSeverity() != null ? incident.getSeverity().name() : null;
    }

    private boolean isGuardOnShift(UUID guardId, OffsetDateTime at, Area area) {
        if (guardShiftRepository == null || guardId == null || at == null) {
            return false;
        }
        LocalDate date = at.atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        LocalTime time = at.atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalTime();

        List<GuardShift> candidateShifts = guardShiftRepository
                .findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                        guardId, date.minusDays(1), date);

        for (GuardShift shift : candidateShifts) {
            if (shift.getStatus() == ShiftStatus.SCHEDULED || shift.getStatus() == ShiftStatus.CHECKED_IN) {
                if (shift.getArea() != null && area != null && !shift.getArea().getId().equals(area.getId())) {
                    boolean isPublic = area.getAreaLevel() == AreaLevel.PUBLIC
                            || (area.getAreaAccessLevel() != null && area.getAreaAccessLevel() <= 1);
                    if (!isPublic) {
                        continue;
                    }
                }

                LocalTime start = shift.getStartTime();
                LocalTime end = shift.getEndTime();
                LocalDate shiftDate = shift.getShiftDate();

                if (start != null && end != null) {
                    if (!start.isAfter(end)) {
                        if (shiftDate.equals(date) && !time.isBefore(start) && !time.isAfter(end)) {
                            return true;
                        }
                    } else {
                        if (shiftDate.equals(date) && !time.isBefore(start)) {
                            return true;
                        } else if (shiftDate.equals(date.minusDays(1)) && !time.isAfter(end)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

@lombok.AllArgsConstructor
    private static class ExtractedPersonInfo {
        final String userCode;
        final String fullName;
        final boolean isExistingUser;
        final User user;
    }

    /**
     * Trích xuất thông tin người dùng linh hoạt từ chuỗi chi tiết sự cố do AI gửi về.
     * Hỗ trợ định dạng chuẩn: ": Họ Tên (MÃ_SỐ) tại..." hoặc mọi mã trong ngoặc đơn (NV001, SE194249, GUEST-xxx,...)
     */
    private ExtractedPersonInfo extractPersonInfo(String cleanDetails) {
        if (cleanDetails == null || cleanDetails.isBlank()) {
            return new ExtractedPersonInfo("UNKNOWN", "Người lạ / Chưa rõ mặt", false, null);
        }

        // 1. Bắt cấu trúc chuẩn do AI Engine sinh ra: ": [Tên Người] ([MÃ_NGƯỜI_DÙNG])"
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile(":\\s*([^():\\n]+?)\\s*\\(([A-Za-z0-9_.-]+)\\)").matcher(cleanDetails);
        if (m1.find()) {
            String candidateName = m1.group(1).trim();
            String candidateCode = m1.group(2).trim();
            Optional<User> uOpt = userRepository.findByUserCodeAndDeletedAtIsNull(candidateCode);
            if (uOpt.isPresent()) {
                return new ExtractedPersonInfo(uOpt.get().getUserCode(), uOpt.get().getFullName(), true, uOpt.get());
            }
            // Không tìm thấy trong User DB (ví dụ: đã xóa mềm hoặc mã khách / mã ngoài)
            return new ExtractedPersonInfo(candidateCode, candidateName.isBlank() ? "Người lạ / Chưa rõ mặt" : candidateName, false, null);
        }

        // 2. Fallback: Tìm mã trong ngoặc đơn bất kỳ nếu khớp với mã User trong hệ thống
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\\(([A-Za-z0-9_.-]+)\\)").matcher(cleanDetails);
        while (m2.find()) {
            String candidate = m2.group(1).trim();
            Optional<User> uOpt = userRepository.findByUserCodeAndDeletedAtIsNull(candidate);
            if (uOpt.isPresent()) {
                return new ExtractedPersonInfo(uOpt.get().getUserCode(), uOpt.get().getFullName(), true, uOpt.get());
            }
        }

        return new ExtractedPersonInfo("UNKNOWN", "Người lạ / Chưa rõ mặt", false, null);
    }

    /**
     * M5: Parse thời điểm phát hiện sự cố (detected_at) từ payload AI.
     * Chấp nhận định dạng ISO-8601 có hoặc không có mili giây, 'Z' hoặc offset (+07:00).
     * Nếu thiếu, sai định dạng, hoặc lệch quá xa server time (tương lai > 5 phút hoặc quá khứ > 24 giờ),
     * hệ thống sẽ fallback về now và log WARN.
     */
    public OffsetDateTime parseDetectedAt(String rawDetectedAt) {
        return parseDetectedAt(rawDetectedAt, OffsetDateTime.now());
    }

    public OffsetDateTime parseDetectedAt(String rawDetectedAt, OffsetDateTime now) {
        if (rawDetectedAt == null || rawDetectedAt.isBlank()) {
            log.warn("detected_at trong cảnh báo bị rỗng/null, dùng thời gian hiện tại của server: {}", now);
            return now;
        }

        OffsetDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(rawDetectedAt.trim());
        } catch (DateTimeParseException e) {
            try {
                parsed = LocalDateTime.parse(rawDetectedAt.trim()).atZone(ZoneOffset.UTC).toOffsetDateTime();
            } catch (Exception ex) {
                log.warn("detected_at [{}] sai định dạng ISO-8601, dùng thời gian hiện tại của server {}: {}",
                        rawDetectedAt, now, e.getMessage());
                return now;
            }
        }

        if (parsed.isAfter(now.plusMinutes(5))) {
            log.warn("detected_at [{}] lệch về tương lai > 5 phút so với server time [{}], dùng thời gian hiện tại của server",
                    parsed, now);
            return now;
        }

        if (parsed.isBefore(now.minusHours(24))) {
            log.warn("detected_at [{}] quá cũ (> 24 giờ) so với server time [{}], dùng thời gian hiện tại của server",
                    parsed, now);
            return now;
        }

        return parsed;
    }

    /**
     * Chuẩn hoá loại sự kiện về 5 nhãn (quyết định 03/11). Vẫn nhận tên cũ để AI bản cũ không làm hỏng luồng sự cố;
     * sai khung giờ (ACCESS_OUT_OF_TIME / OUT_OF_TIME) tính như vào trái phép. Loại khác (vd CAMERA_TAMPERING) giữ nguyên.
     */
    public static String normalizeEventType(String rawEventType) {
        if (rawEventType == null) {
            return null;
        }
        return switch (rawEventType) {
            case "AFTER_HOURS", "AFTER_HOURS_PRESENCE", "AFTER_HOURS_ACCESS", "INTRUSION", "PERSON_INTRUSION" -> "PERSON_INTRUSION";
            case "UNAUTHORIZED", "UNAUTHORIZED_ACCESS", "ACCESS_OUT_OF_TIME", "OUT_OF_TIME", "UNAUTHORIZED_PERSON" -> "UNAUTHORIZED_PERSON";
            case "UNKNOWN", "UNKNOWN_IDENTITY", "UNKNOWN_PERSON" -> "UNKNOWN_PERSON";
            case "SUSPICIOUS_MOTION", "CAMOUFLAGE", "MOVING_OBJECT", "SUSPICIOUS_MOVING_OBJECT" -> "SUSPICIOUS_MOVING_OBJECT";
            case "AUTHORIZED_ACCESS", "AUTHORIZED_PERSON" -> "AUTHORIZED_PERSON";
            default -> rawEventType;
        };
    }

    public void triggerIncidentRecordingStart(SecurityIncident incident) {
        if (incident == null) return;
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            // Trên kiến trúc Cloud-Edge, Edge AI đã chủ động bật ghi hình sự cố ngay tại 0ms khi phát hiện (theo event_id)
            return;
        }
        if (incident == null || aiServiceUrl == null || aiServiceUrl.isBlank()) return;
        String incidentIdStr = incident.getId() != null ? incident.getId().toString() : "";
        String cameraCode = incident.getCameraCode() != null ? incident.getCameraCode() : "CAM-001";
        String eventType = incident.getEventType() != null ? incident.getEventType() : "SECURITY_INCIDENT";
        String details = incident.getResolutionNotes() != null ? incident.getResolutionNotes() : "";
        String url = aiServiceUrl + "/api/v1/incident-recording/start";

        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> req = Map.of(
                        "incident_id", incidentIdStr,
                        "camera_code", cameraCode,
                        "event_type", eventType,
                        "details", details
                );
                restTemplate.postForObject(url, req, Map.class);
                log.info("🚨 [IncidentService] Đã kích hoạt ghi hình sự cố [{}] camera [{}] sang AI Service",
                        incidentIdStr, cameraCode);
            } catch (Exception ex) {
                log.warn("⚠️ Không thể gửi tín hiệu ghi hình sự cố sang AI service: {}", ex.getMessage());
            }
        });
    }

    public void triggerIncidentRecordingStop(UUID incidentId) {
        triggerIncidentRecordingStop(incidentId != null ? incidentId.toString() : null);
    }

    public void triggerIncidentRecordingStop(String incidentId) {
        String effectiveIncidentKey = incidentId;
        if (incidentId != null && !incidentId.isBlank()) {
            try {
                UUID parsedId = UUID.fromString(incidentId.trim());
                Optional<SecurityIncident> found = incidentRepository.findById(parsedId);
                if (found.isPresent() && found.get().getEventId() != null && !found.get().getEventId().isBlank()) {
                    effectiveIncidentKey = found.get().getEventId();
                }
            } catch (IllegalArgumentException ignored) {
                // incidentId không phải UUID DB mà là event_id trực tiếp -> giữ nguyên
            }
        }

        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            edgeMqttControlService.publishIncidentStopCommand(effectiveIncidentKey, false);
            if (effectiveIncidentKey != null && !effectiveIncidentKey.equals(incidentId) && incidentId != null && !incidentId.isBlank()) {
                edgeMqttControlService.publishIncidentStopCommand(incidentId, false);
            }
            if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
                return;
            }
        }
        if (aiServiceUrl == null || aiServiceUrl.isBlank()) return;
        if (aiServiceUrl == null || aiServiceUrl.isBlank()) return;
        String url = aiServiceUrl + "/api/v1/incident-recording/stop";
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> req = (incidentId != null && !incidentId.isBlank())
                        ? Map.of("incident_id", incidentId)
                        : Map.of("stop_all", true);
                restTemplate.postForObject(url, req, Map.class);
                log.info("🛑 [IncidentService] Đã gửi tín hiệu dừng ghi hình sự cố [{}] sang AI Service", incidentId);
            } catch (Exception ex) {
                log.warn("⚠️ Không thể gửi tín hiệu dừng ghi hình sự cố sang AI service: {}", ex.getMessage());
            }
        });
    }

    public void triggerAllIncidentRecordingsStop() {
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            edgeMqttControlService.publishIncidentStopCommand(null, true);
            if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
                return;
            }
        }
        if (aiServiceUrl == null || aiServiceUrl.isBlank()) return;
        String url = aiServiceUrl + "/api/v1/incident-recording/stop";
        CompletableFuture.runAsync(() -> {
            try {
                restTemplate.postForObject(url, Map.of("stop_all", true), Map.class);
                log.info("🛑 [IncidentService] Đã gửi tín hiệu dừng tất cả phiên ghi hình sự cố sang AI Service");
            } catch (Exception ex) {
                log.warn("⚠️ Không thể gửi tín hiệu dừng toàn bộ ghi hình sự cố sang AI service: {}", ex.getMessage());
            }
        });
    }
}
