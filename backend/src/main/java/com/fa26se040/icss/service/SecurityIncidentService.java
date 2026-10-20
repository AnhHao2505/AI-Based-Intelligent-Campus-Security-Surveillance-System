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

    @Transactional
    public IncidentDetailResponse ingestIncident(IncidentEventDto eventDto) {
        log.info("Processing incoming incident for camera [{}] type [{}]",
                eventDto.getCameraCode(), eventDto.getEventType());

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
        boolean isPublicArea = area.getAreaLevel() == AreaLevel.PUBLIC
                || (area.getAreaAccessLevel() != null && area.getAreaAccessLevel() <= 1);

        if (isPublicArea && ("UNKNOWN_PERSON".equals(normalizedEventType) || "UNAUTHORIZED_ACCESS".equals(normalizedEventType))) {
            log.info("Khu vực [{}] thuộc Cấp 1 (PUBLIC) - Bỏ qua cảnh báo [{}] trong giờ hoạt động bình thường.",
                    area.getName(), normalizedEventType);
            return null;
        }

        String cleanDetails = eventDto.getDetails() != null ? eventDto.getDetails() : "";
        cleanDetails = cleanDetails.replace("[TRONG_GIO] ", "").replace("[TRONG_GIO]", "")
                .replace("[AFTER_HOURS_PRESENCE] ", "[INTRUSION] ").replace("[AFTER_HOURS_PRESENCE]", "[INTRUSION]");

        // Ngoại lệ cho cảnh báo ban đêm (INTRUSION): Trừ bảo vệ trong ca trực
        if ("INTRUSION".equals(normalizedEventType)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((SE\\d+|GU\\d+|SEC-\\d+|[A-Z0-9_-]+)\\)").matcher(cleanDetails);
            if (m.find()) {
                String extractedCode = m.group(1);
                Optional<User> uOpt = userRepository.findByUserCodeAndDeletedAtIsNull(extractedCode);
                if (uOpt.isPresent() && uOpt.get().getRole() == Role.GUARD) {
                    if (isGuardOnShift(uOpt.get().getId(), detectedAt, area)) {
                        log.info("Bỏ qua cảnh báo INTRUSION ngoài giờ vì [{}] ({}) là bảo vệ đang trong ca trực.",
                                uOpt.get().getFullName(), uOpt.get().getUserCode());
                        return null;
                    }
                }
            }
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
                .resolutionNotes(cleanDetails)
                .version(0)
                .build();

        SecurityIncident saved = incidentRepository.save(incident);
        IncidentDetailResponse response = mapToDetailResponse(saved);

        // 1b. Tự động đồng bộ sự cố người lạ / vi phạm / sai giờ / ngoài giờ sang Nhật ký ra vào của Camera (Tab 4 Camera Detail)
        if (cameraAccessLogService != null && eventDto.getCameraCode() != null) {
            try {
                boolean isUnknown = normalizedEventType.contains("UNKNOWN") || cleanDetails.contains("UNKNOWN") || cleanDetails.contains("chưa rõ mặt");

                String userCode = isUnknown ? "UNKNOWN" : "VIOLATION";
                String fullName = isUnknown ? "Người lạ / Chưa rõ mặt" : "Đối tượng chưa cấp quyền";

                // Trích xuất mã người dùng nếu AI đã nhận diện được mặt (ví dụ: SE194249 hoặc GUEST-xxx)
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((SE\\d+|GUEST-[a-zA-Z0-9-]+|[A-Z]{2,}\\d{4,})\\)").matcher(cleanDetails);
                if (m.find()) {
                    String extractedCode = m.group(1);
                    Optional<User> uOpt = userRepository.findByUserCodeAndDeletedAtIsNull(extractedCode);
                    if (uOpt.isPresent()) {
                        userCode = uOpt.get().getUserCode();
                        fullName = uOpt.get().getFullName();
                    } else {
                        // Người dùng đã bị xoá mềm hoặc không tồn tại trong hệ thống -> Giữ nguyên mã AI quét được để phục vụ truy vết điều tra
                        userCode = extractedCode;
                        fullName = "Người lạ / Chưa rõ mặt";
                    }
                }

                CheckCameraEntryResponse logEntry = CheckCameraEntryResponse.builder()
                        .cameraCode(eventDto.getCameraCode())
                        .areaId(area.getId())
                        .areaName(area.getName())
                        .userCode(userCode)
                        .fullName(fullName)
                        .allowed(false)
                        .source(AccessSource.NONE)
                        .reason(cleanDetails.isBlank() ? "Phát hiện sự cố an ninh: " + normalizedEventType : cleanDetails)
                        .build();
                cameraAccessLogService.recordAccessLog(logEntry, detectedAt);
            } catch (Exception ex) {
                log.warn("Không thể đồng bộ sự cố sang nhật ký camera: {}", ex.getMessage());
            }
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
                .build();
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
}
