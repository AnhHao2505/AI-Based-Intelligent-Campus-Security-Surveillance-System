package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.dto.incident.IncidentResolveRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.GuardLocation;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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
    private final AreaRepository areaRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final GuardLocationService guardLocationService;
    private final CameraAccessLogService cameraAccessLogService;

    @Transactional
    public IncidentDetailResponse ingestIncident(IncidentEventDto eventDto) {
        log.info("Processing incoming incident for camera [{}] type [{}]",
                eventDto.getCameraCode(), eventDto.getEventType());

        // 1. Resolve area by camera code
        List<Area> areas = areaRepository.findAreasByCameraCode(eventDto.getCameraCode());
        Area area = areas.isEmpty() ? null : areas.get(0);

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

        SecurityIncident incident = SecurityIncident.builder()
                .eventId(eventDto.getEventId())
                .cameraCode(eventDto.getCameraCode() != null ? eventDto.getCameraCode() : "CAM-UNKNOWN")
                .area(area)
                .building(building)
                .eventType(eventDto.getEventType() != null ? eventDto.getEventType() : "SECURITY_ALERT")
                .imageUrl(imageUrl)
                .detectedAt(detectedAt)
                .status(IncidentStatus.NEW)
                .resolutionNotes(eventDto.getDetails())
                .version(0)
                .build();

        SecurityIncident saved = incidentRepository.save(incident);
        IncidentDetailResponse response = mapToDetailResponse(saved);

        // 1b. Tự động đồng bộ sự cố người lạ / vi phạm / sai giờ / ngoài giờ sang Nhật ký ra vào của Camera (Tab 4 Camera Detail)
        if (cameraAccessLogService != null && eventDto.getCameraCode() != null) {
            try {
                String eventType = eventDto.getEventType() != null ? eventDto.getEventType() : "";
                String details = eventDto.getDetails() != null ? eventDto.getDetails() : "";
                boolean isUnknown = eventType.contains("UNKNOWN") || details.contains("UNKNOWN") || details.contains("chưa rõ mặt");

                String userCode = isUnknown ? "UNKNOWN" : "VIOLATION";
                String fullName = isUnknown ? "Người lạ / Chưa rõ mặt" : "Đối tượng chưa cấp quyền";

                // Trích xuất mã người dùng nếu AI đã nhận diện được mặt (ví dụ: SE194249 hoặc GUEST-xxx)
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((SE\\d+|GUEST-[a-zA-Z0-9-]+|[A-Z]{2,}\\d{4,})\\)").matcher(details);
                if (m.find()) {
                    String extractedCode = m.group(1);
                    Optional<User> uOpt = userRepository.findByUserCodeAndDeletedAtIsNull(extractedCode);
                    if (uOpt.isPresent()) {
                        userCode = uOpt.get().getUserCode();
                        fullName = uOpt.get().getFullName();
                    } else {
                        userCode = extractedCode;
                        fullName = "Đối tượng nhận diện (" + extractedCode + ")";
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
                        .reason(details.isBlank() ? "Phát hiện sự cố an ninh: " + eventType : details)
                        .build();
                cameraAccessLogService.recordAccessLog(logEntry, detectedAt);
            } catch (Exception ex) {
                log.warn("Không thể đồng bộ sự cố sang nhật ký camera: {}", ex.getMessage());
            }
        }

        // 2. Real-time WebSocket dispatching
        // A. Dispatch targeted alerts only to guards currently inside the campus geofence
        if (guardLocationService != null) {
            try {
                List<GuardLocation> guardsInCampus = guardLocationService.findGuardsInsideCampus();
                log.info("Incident [{}] in area [{}] (Building: [{}]): Found [{}] guards inside campus geofence to notify",
                        saved.getId(), area.getName(), building, guardsInCampus.size());

                for (GuardLocation guardLoc : guardsInCampus) {
                    if (guardLoc.getGuard() != null && guardLoc.getGuard().getId() != null) {
                        String guardTopic = "/topic/guards/" + guardLoc.getGuard().getId() + "/alerts";
                        messagingTemplate.convertAndSend(guardTopic, response);
                        log.info("Dispatched incident [{}] to guard [{}] ({}) at topic [{}]",
                                saved.getId(), guardLoc.getGuard().getFullName(), guardLoc.getGuard().getEmail(), guardTopic);
                    }
                }
            } catch (Exception e) {
                log.error("Lỗi khi điều phối cảnh báo tới các bảo vệ trong geofence: {}", e.getMessage(), e);
            }
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
}
