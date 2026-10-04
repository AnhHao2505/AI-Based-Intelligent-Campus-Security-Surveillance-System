package com.fa26se040.icss.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fa26se040.icss.dto.geofence.CampusGeofenceDto;
import com.fa26se040.icss.dto.geofence.CampusGeofenceUpdateRequest;
import com.fa26se040.icss.dto.geofence.PointDto;
import com.fa26se040.icss.entity.CampusGeofence;
import com.fa26se040.icss.entity.GuardLocation;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.repository.CampusGeofenceRepository;
import com.fa26se040.icss.repository.GuardLocationRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class CampusGeofenceService {

    public static final String DEFAULT_CAMPUS_NAME = "FPT University HCMC Campus";
    public static final String DEFAULT_CAMPUS_DESCRIPTION = "Khuôn viên Đại học FPT TP.HCM (Khu Công nghệ cao, TP. Thủ Đức)";

    // Tọa độ trung tâm khuôn viên mặc định
    public static final PointDto DEFAULT_CAMPUS_CENTER = new PointDto(10.84113, 106.80988);

    // Đa giác ranh giới Geofence mặc định ban đầu
    public static final List<PointDto> DEFAULT_CAMPUS_POLYGON = List.of(
            new PointDto(10.84350, 106.80800), // P1: Tây Bắc (Góc đường D1 - Nguyễn Xiển)
            new PointDto(10.84380, 106.81050), // P2: Bắc (Mặt tiền đường D1, cổng chính)
            new PointDto(10.84250, 106.81280), // P3: Đông Bắc (Giáp đường D2)
            new PointDto(10.83920, 106.81220), // P4: Đông Nam (Khu tiếp giáp phía Nam)
            new PointDto(10.83880, 106.80950), // P5: Nam (Vành đai sân tập, thể thao)
            new PointDto(10.84020, 106.80720)  // P6: Tây Nam (Khu ký túc xá / dịch vụ)
    );

    public static final double DEFAULT_RADIUS_METERS = 400.0;

    private final CampusGeofenceRepository campusGeofenceRepository;
    private final UserRepository userRepository;
    private final GuardLocationRepository guardLocationRepository;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    private volatile CampusGeofenceDto cachedGeofence;

    public CampusGeofenceService(
            CampusGeofenceRepository campusGeofenceRepository,
            UserRepository userRepository,
            @Lazy GuardLocationRepository guardLocationRepository,
            ObjectMapper objectMapper,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.campusGeofenceRepository = campusGeofenceRepository;
        this.userRepository = userRepository;
        this.guardLocationRepository = guardLocationRepository;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Lấy thông tin Geofence động duy nhất của toàn khuôn viên trường từ CSDL (hoặc cache).
     */
    public CampusGeofenceDto getCampusGeofence() {
        if (cachedGeofence != null) {
            return cachedGeofence;
        }

        Optional<CampusGeofence> entityOpt = campusGeofenceRepository.findFirstByOrderByUpdatedAtDesc();
        if (entityOpt.isPresent()) {
            CampusGeofence entity = entityOpt.get();
            try {
                List<PointDto> polygon = objectMapper.readValue(
                        entity.getPolygonJson(),
                        new TypeReference<List<PointDto>>() {}
                );
                CampusGeofenceDto dto = CampusGeofenceDto.builder()
                        .campusName(entity.getCampusName())
                        .description(entity.getDescription())
                        .center(new PointDto(entity.getCenterLatitude(), entity.getCenterLongitude()))
                        .polygon(polygon)
                        .radiusMeters(DEFAULT_RADIUS_METERS)
                        .build();
                this.cachedGeofence = dto;
                return dto;
            } catch (Exception e) {
                log.error("Lỗi parse polygon_json từ DB: {}", e.getMessage());
            }
        }

        // Fallback default
        CampusGeofenceDto defaultDto = CampusGeofenceDto.builder()
                .campusName(DEFAULT_CAMPUS_NAME)
                .description(DEFAULT_CAMPUS_DESCRIPTION)
                .center(DEFAULT_CAMPUS_CENTER)
                .polygon(DEFAULT_CAMPUS_POLYGON)
                .radiusMeters(DEFAULT_RADIUS_METERS)
                .build();
        this.cachedGeofence = defaultDto;
        return defaultDto;
    }

    /**
     * Cập nhật ranh giới Geofence do ADMIN vẽ trực tiếp trên bản đồ.
     */
    @Transactional
    public CampusGeofenceDto updateCampusGeofence(CampusGeofenceUpdateRequest request, String adminEmail) {
        if (request.getPolygon() == null || request.getPolygon().size() < 3) {
            throw new IllegalArgumentException("Ranh giới Geofence phải có ít nhất 3 điểm tạo thành đa giác khép kín.");
        }

        // Validate coordinates
        for (PointDto pt : request.getPolygon()) {
            if (pt.getLatitude() == null || pt.getLongitude() == null ||
                    pt.getLatitude() < -90 || pt.getLatitude() > 90 ||
                    pt.getLongitude() < -180 || pt.getLongitude() > 180) {
                throw new IllegalArgumentException("Tọa độ đỉnh Geofence không hợp lệ: " + pt);
            }
        }

        User admin = userRepository.findByEmail(adminEmail)
                .orElse(null);

        // Calculate center if missing
        PointDto center = request.getCenter();
        if (center == null || center.getLatitude() == null || center.getLongitude() == null) {
            double avgLat = request.getPolygon().stream().mapToDouble(PointDto::getLatitude).average().orElse(DEFAULT_CAMPUS_CENTER.getLatitude());
            double avgLng = request.getPolygon().stream().mapToDouble(PointDto::getLongitude).average().orElse(DEFAULT_CAMPUS_CENTER.getLongitude());
            center = new PointDto(avgLat, avgLng);
        }

        String polygonJson;
        try {
            polygonJson = objectMapper.writeValueAsString(request.getPolygon());
        } catch (Exception e) {
            throw new IllegalArgumentException("Lỗi chuyển đổi đa giác Geofence sang JSON: " + e.getMessage());
        }

        CampusGeofence geofence = campusGeofenceRepository.findFirstByOrderByUpdatedAtDesc()
                .orElseGet(() -> CampusGeofence.builder().build());

        geofence.setCampusName(request.getCampusName() != null ? request.getCampusName() : DEFAULT_CAMPUS_NAME);
        geofence.setDescription(request.getDescription());
        geofence.setCenterLatitude(center.getLatitude());
        geofence.setCenterLongitude(center.getLongitude());
        geofence.setPolygonJson(polygonJson);
        geofence.setUpdatedBy(admin);
        geofence.setUpdatedAt(OffsetDateTime.now());

        campusGeofenceRepository.save(geofence);

        CampusGeofenceDto updatedDto = CampusGeofenceDto.builder()
                .campusName(geofence.getCampusName())
                .description(geofence.getDescription())
                .center(center)
                .polygon(request.getPolygon())
                .radiusMeters(DEFAULT_RADIUS_METERS)
                .build();

        this.cachedGeofence = updatedDto;

        // Tái đánh giá Geofence cho toàn bộ nhân viên bảo vệ theo ranh giới mới
        try {
            List<GuardLocation> allGuardLocations = guardLocationRepository.findAll();
            for (GuardLocation loc : allGuardLocations) {
                boolean isInside = isPointInPolygon(loc.getLatitude(), loc.getLongitude(), request.getPolygon());
                if (loc.getIsInsideGeofence() != isInside) {
                    loc.setIsInsideGeofence(isInside);
                    guardLocationRepository.save(loc);
                }
            }
            log.info("Đã tái đánh giá trạng thái Geofence cho [{}] vị trí bảo vệ theo ranh giới mới", allGuardLocations.size());
        } catch (Exception e) {
            log.warn("Lỗi khi tái đánh giá vị trí bảo vệ theo geofence mới: {}", e.getMessage());
        }

        // Phát WebSocket thông báo ranh giới Geofence mới cho Web và Mobile
        try {
            messagingTemplate.convertAndSend("/topic/campus/geofence", updatedDto);
        } catch (Exception e) {
            log.warn("Lỗi phát WebSocket ranh giới geofence: {}", e.getMessage());
        }

        log.info("Admin [{}] cập nhật thành công ranh giới Geofence với [{}] đỉnh",
                adminEmail, request.getPolygon().size());

        return updatedDto;
    }

    /**
     * Kiểm tra một tọa độ GPS (vĩ độ, kinh độ) có nằm trong Geofence khuôn viên trường hay không.
     * Sử dụng thuật toán Ray-Casting (bắn tia ngang) với độ chính xác cao O(N).
     */
    public boolean isInsideGeofence(double latitude, double longitude) {
        CampusGeofenceDto current = getCampusGeofence();
        List<PointDto> polygon = (current != null && current.getPolygon() != null && current.getPolygon().size() >= 3)
                ? current.getPolygon()
                : DEFAULT_CAMPUS_POLYGON;
        return isPointInPolygon(latitude, longitude, polygon);
    }

    /**
     * Thuật toán Ray-Casting kiểm tra điểm nằm trong đa giác phẳng (Polygon).
     */
    public boolean isPointInPolygon(double lat, double lng, List<PointDto> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }

        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).getLongitude();
            double yi = polygon.get(i).getLatitude();
            double xj = polygon.get(j).getLongitude();
            double yj = polygon.get(j).getLatitude();

            boolean intersect = ((yi > lat) != (yj > lat))
                    && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Tính khoảng cách đường chim bay giữa 2 điểm tọa độ theo công thức Haversine (đơn vị: Mét).
     */
    public double calculateDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final int EARTH_RADIUS = 6371000; // mét
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS * c;
    }
}
