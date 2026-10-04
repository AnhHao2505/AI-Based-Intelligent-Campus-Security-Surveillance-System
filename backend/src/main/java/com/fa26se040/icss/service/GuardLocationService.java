package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.GuardLocationResponse;
import com.fa26se040.icss.dto.guard.GuardLocationUpdateRequest;
import com.fa26se040.icss.entity.GuardLocation;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.GuardLocationRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardLocationService {

    public static final int LOCATION_FRESHNESS_MINUTES = 10;

    private final GuardLocationRepository guardLocationRepository;
    private final UserRepository userRepository;
    private final CampusGeofenceService campusGeofenceService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Cập nhật vị trí GPS ngầm tự động của bảo vệ và đánh giá Geofence khuôn viên.
     */
    @Transactional
    public GuardLocationResponse updateGuardLocation(String userEmail, GuardLocationUpdateRequest request) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản người dùng: " + userEmail));

        // Kiểm tra xem vị trí có nằm trong ranh giới Geofence toàn khuôn viên hay không
        boolean isInside = campusGeofenceService.isInsideGeofence(request.getLatitude(), request.getLongitude());

        GuardLocation location = guardLocationRepository.findByGuard(user)
                .orElseGet(() -> GuardLocation.builder().guard(user).build());

        location.setLatitude(request.getLatitude());
        location.setLongitude(request.getLongitude());
        location.setAccuracy(request.getAccuracy());
        location.setBatteryLevel(request.getBatteryLevel());
        location.setHeading(request.getHeading());
        location.setSpeed(request.getSpeed());
        location.setIsInsideGeofence(isInside);
        location.setUpdatedAt(OffsetDateTime.now());

        GuardLocation saved = guardLocationRepository.save(location);
        GuardLocationResponse response = mapToResponse(saved);

        // Phát WebSocket cho Web Admin/Facility Manager theo dõi bảo vệ thời gian thực
        try {
            messagingTemplate.convertAndSend("/topic/guards/locations", response);
        } catch (Exception e) {
            log.warn("Lỗi khi gửi WebSocket vị trí bảo vệ: {}", e.getMessage());
        }

        log.debug("Cập nhật vị trí bảo vệ [{}] ({}, {}): trong khuôn viên = {}",
                user.getEmail(), request.getLatitude(), request.getLongitude(), isInside);

        return response;
    }

    /**
     * Lấy danh sách các bảo vệ hiện đang nằm trong Geofence khuôn viên trường và có dữ liệu định vị mới (trong vòng 10 phút).
     */
    @Transactional(readOnly = true)
    public List<GuardLocation> findGuardsInsideCampus() {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(LOCATION_FRESHNESS_MINUTES);
        return guardLocationRepository.findGuardsInsideGeofenceSince(threshold);
    }

    /**
     * Lấy danh sách vị trí của tất cả bảo vệ đang hoạt động gần đây (phục vụ hiển thị pin tròn trên bản đồ khuôn viên).
     */
    @Transactional(readOnly = true)
    public List<GuardLocationResponse> getActiveGuardLocations() {
        return guardLocationRepository.findAllGuardLocations().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public GuardLocationResponse mapToResponse(GuardLocation loc) {
        User guard = loc.getGuard();
        boolean isFresh = loc.getUpdatedAt() != null
                && loc.getUpdatedAt().isAfter(OffsetDateTime.now().minusMinutes(LOCATION_FRESHNESS_MINUTES));

        return GuardLocationResponse.builder()
                .guardId(guard.getId())
                .guardName(guard.getFullName())
                .guardEmail(guard.getEmail())
                .userCode(guard.getUserCode())
                .teamName(guard.getTeam() != null ? guard.getTeam().getTeamName() : null)
                .latitude(loc.getLatitude())
                .longitude(loc.getLongitude())
                .accuracy(loc.getAccuracy())
                .batteryLevel(loc.getBatteryLevel())
                .heading(loc.getHeading())
                .speed(loc.getSpeed())
                .isInsideGeofence(loc.getIsInsideGeofence())
                .updatedAt(loc.getUpdatedAt())
                .isFresh(isFresh)
                .build();
    }
}
