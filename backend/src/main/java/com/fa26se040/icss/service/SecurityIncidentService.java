package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.dto.incident.IncidentResolveRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

    @org.springframework.beans.factory.annotation.Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    private final org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();

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
                return mapToDetailResponse(existing.get());
            }
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
        if (area == null) {
            log.warn("Không tìm thấy khu vực hoạt động nào liên kết với camera [{}] để ghi nhận sự cố", eventDto.getCameraCode());
            throw new IllegalArgumentException("Camera " + eventDto.getCameraCode() + " chưa được gán vào khu vực hoạt động nào!");
        }

        String building = (area.getBuilding() != null && !area.getBuilding().isBlank())
                ? area.getBuilding()
                : "FPT_CAMPUS";

        // Normalize MinIO image URL for web clients
        String imageUrl = eventDto.getImageUrl();
        if (imageUrl != null && imageUrl.contains("minio:9000")) {
            imageUrl = imageUrl.replace("minio:9000", "localhost:9000");
        }

        OffsetDateTime detectedAt = OffsetDateTime.now();

        String rawEventType = eventDto.getEventType() != null ? eventDto.getEventType() : "SECURITY_ALERT";
        String normalizedEventType = switch (rawEventType) {
            case "AFTER_HOURS", "AFTER_HOURS_PRESENCE", "AFTER_HOURS_ACCESS" -> "INTRUSION";
            case "UNAUTHORIZED" -> "UNAUTHORIZED_ACCESS";
            case "UNKNOWN" -> "UNKNOWN_PERSON";
            case "OUT_OF_TIME" -> "ACCESS_OUT_OF_TIME";
            default -> rawEventType;
        };

        // Quy chuẩn an ninh khuôn viên: Khu vực Cấp 1 (PUBLIC) cho phép tự do ra vào trong giờ hoạt động
        // -> Không tạo cảnh báo sự cố đối với người lạ (UNKNOWN_PERSON) và không cảnh báo truy cập trái phép (UNAUTHORIZED_ACCESS) trong giờ
        // (Ngoại lệ: Sự kiện Test do Bảo vệ kích hoạt để kiểm thử tính năng thì luôn được ghi nhận)
        boolean isTestEvent = eventDto.getEventId() != null
                && (eventDto.getEventId().startsWith("EVT-") || eventDto.getEventId().startsWith("TEST-"));

        boolean isPublicArea = !isTestEvent && (area.getAreaLevel() == AreaLevel.PUBLIC
                || (area.getAreaAccessLevel() != null && area.getAreaAccessLevel() <= 1));

        if (isPublicArea && ("UNKNOWN_PERSON".equals(normalizedEventType) || "UNAUTHORIZED_ACCESS".equals(normalizedEventType))) {
            log.info("Khu vực [{}] thuộc Cấp 1 (PUBLIC) - Bỏ qua cảnh báo [{}] trong giờ hoạt động bình thường.",
                    area.getName(), normalizedEventType);
            return null;
        }

        String cleanDetails = eventDto.getDetails() != null ? eventDto.getDetails().replaceAll("\\[[A-Z0-9_]+\\]\\s*", "").trim() : "";

        // Ngoại lệ cho cảnh báo ban đêm (INTRUSION): Trừ bảo vệ trong ca trực
        if ("INTRUSION".equals(normalizedEventType)) {
            ExtractedPersonInfo personInfo = extractPersonInfo(cleanDetails);
            if (personInfo.user != null && personInfo.user.getRole() == Role.GUARD) {
                if (isGuardOnShift(personInfo.user.getId(), detectedAt, area)) {
                    log.info("Bỏ qua cảnh báo INTRUSION ngoài giờ vì [{}] ({}) là bảo vệ đang trong ca trực.",
                            personInfo.user.getFullName(), personInfo.user.getUserCode());
                    return null;
                }
            }
        }

        String incidentResolutionNotes = cleanDetails;
        if (incidentResolutionNotes.isBlank()) {
            incidentResolutionNotes = switch (normalizedEventType) {
                case "INTRUSION" -> "Phát hiện đối tượng xuất hiện ngoài giờ hoạt động tại " + area.getName() + ".";
                case "UNAUTHORIZED_ACCESS" -> "Phát hiện đối tượng không có quyền truy cập tại " + area.getName() + ".";
                case "UNKNOWN_PERSON" -> "Phát hiện người chưa đăng ký tại " + area.getName() + ".";
                case "ACCESS_OUT_OF_TIME" -> "Phát hiện ra vào ngoài khung giờ quy định tại " + area.getName() + ".";
                default -> "Phát hiện sự cố an ninh tại " + area.getName() + ".";
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
                .build();
        incident.setSeverity(resolveSeverity(normalizedEventType, area, detectedAt));

        SecurityIncident saved = incidentRepository.save(incident);
        // Ghi xuống DB ngay: tin trùng đến cùng lúc vấp unique index event_id tại đây, trước mọi tác dụng phụ bên dưới
        incidentRepository.flush();
        IncidentDetailResponse response = mapToDetailResponse(saved);

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
                        .areaId(area.getId())
                        .areaName(area.getName())
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

        // 2. Real-time WebSocket dispatching
        // A. BR-IE-01 / BR-IE-02: gửi cho bảo vệ đang trực (ca CHECKED_IN, trong khung giờ).
        //    BR-IE-03: tạo 1 bản ghi leo thang cùng transaction, đếm số bảo vệ đã gửi được.
        //    Resolver lỗi -> vẫn tạo bản ghi với 0 người nhận để job báo FM ở lần chạy kế (BR-IE-05).
        //    Lỗi SQL trong transaction này làm cả transaction rollback (kể cả sự cố) — try/catch chỉ chặn lỗi tầng Java.
        List<User> onDutyGuards = List.of();
        try {
            onDutyGuards = incidentRecipientResolver.findOnDutyGuards(detectedAt);
        } catch (Exception e) {
            log.error("Lỗi khi tìm bảo vệ đang trực cho sự cố [{}]: {}", saved.getId(), e.getMessage(), e);
        }
        log.info("Incident [{}] in area [{}] (Building: [{}]): Found [{}] on-duty guards to notify",
                saved.getId(), area.getName(), building, onDutyGuards.size());

        int guardRecipientCount = 0;
        for (User guard : onDutyGuards) {
            String guardTopic = "/topic/guards/" + guard.getId() + "/alerts";
            try {
                messagingTemplate.convertAndSend(guardTopic, response);
                guardRecipientCount++;
                log.info("Dispatched incident [{}] to guard [{}] ({}) at topic [{}]",
                        saved.getId(), guard.getFullName(), guard.getEmail(), guardTopic);
            } catch (Exception e) {
                log.error("Lỗi khi gửi cảnh báo sự cố [{}] tới [{}]: {}", saved.getId(), guardTopic, e.getMessage(), e);
            }
        }

        try {
            incidentEscalationRepository.save(IncidentEscalation.builder()
                    .incident(saved)
                    .guardsNotifiedAt(OffsetDateTime.now())
                    .guardRecipientCount(guardRecipientCount)
                    .build());
        } catch (Exception e) {
            log.error("Không tạo được bản ghi leo thang cho sự cố [{}]: {}", saved.getId(), e.getMessage(), e);
        }

        // B. Building-scoped topic (for web dashboard & monitoring)
        String buildingTopic = "/topic/buildings/" + building.toUpperCase() + "/alerts";
        messagingTemplate.convertAndSend(buildingTopic, response);

        // C. Global topic (for system admins & overview)
        messagingTemplate.convertAndSend("/topic/security-alerts", response);

        log.info("Broadcasted new incident [{}] to [{}] and [/topic/security-alerts]", saved.getId(), buildingTopic);
        return response;
    }

    @Transactional(readOnly = true)
    public List<IncidentDetailResponse> getActiveIncidents(String building) {
        List<SecurityIncident> list = incidentRepository.findActiveIncidents(building);
        return list.stream().map(this::mapToDetailResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IncidentDetailResponse> getIncidents(String building, IncidentStatus status) {
        List<SecurityIncident> list = incidentRepository.findIncidents(building, status);
        return list.stream().map(this::mapToDetailResponse).collect(Collectors.toList());
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

        try {
            incident.setStatus(IncidentStatus.CLAIMED);
            incident.setClaimedBy(guard);
            incident.setClaimedAt(OffsetDateTime.now());

            SecurityIncident saved = incidentRepository.saveAndFlush(incident);

            // Sync with all Web & Mobile clients
            Map<String, Object> updatePayload = new HashMap<>();
            updatePayload.put("incidentId", saved.getId());
            updatePayload.put("status", saved.getStatus().name());
            updatePayload.put("claimedById", guard.getId());
            updatePayload.put("claimedByName", guard.getFullName());
            updatePayload.put("claimedAt", saved.getClaimedAt());
            updatePayload.put("severity", severityName(saved));

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
            SecurityIncident fresh = incidentRepository.findById(incidentId).orElse(incident);
            String claimantName = fresh.getClaimedBy() != null ? fresh.getClaimedBy().getFullName() : "đồng đội khác";
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

        messagingTemplate.convertAndSend("/topic/incidents/updates", updatePayload);
        log.info("Incident [{}] resolved with status [{}] by [{}]", incidentId, newStatus, guard.getFullName());

        return mapToDetailResponse(saved);
    }

    private IncidentDetailResponse mapToDetailResponse(SecurityIncident i) {
        return IncidentDetailResponse.builder()
                .id(i.getId())
                .eventId(i.getEventId())
                .cameraCode(i.getCameraCode())
                .areaId(i.getArea() != null ? i.getArea().getId() : null)
                .areaName(i.getArea() != null ? i.getArea().getName() : null)
                .building(i.getBuilding())
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
                .build();
    }

    /**
     * V90 (BR-SEV-01): mức độ của sự cố mới. Resolver chưa được inject (test dựng service thủ công) hoặc lỗi tầng Java
     * -> NULL, ingest chạy tiếp như trước V90.
     */
    private IncidentSeverity resolveSeverity(String eventType, Area area, OffsetDateTime at) {
        if (incidentSeverityResolver == null) {
            return null;
        }
        try {
            return incidentSeverityResolver.resolve(eventType, area.getAreaLevel(), at);
        } catch (Exception e) {
            log.error("Không tính được severity cho sự cố loại [{}] tại khu [{}]: {}", eventType, area.getName(), e.getMessage(), e);
            return null;
        }
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

    public void triggerIncidentRecordingStart(SecurityIncident incident) {
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
