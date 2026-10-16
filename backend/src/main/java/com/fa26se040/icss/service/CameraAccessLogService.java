package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogStatsResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class CameraAccessLogService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final CameraAccessLogRepository cameraAccessLogRepository;
    private final AreaRepository areaRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final SystemConfigService systemConfigService;

    // Bộ nhớ đệm in-memory Debounce (Key: cameraCode:userCode -> Expire Epoch Seconds)
    private final Map<String, Long> debounceCache = new ConcurrentHashMap<>();

    /**
     * Ghi nhận lượt quét ra vào từ camera (có cơ chế debounce chống spam, số giây đọc từ ENTRY_LOG_DEBOUNCE_SECONDS).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CameraAccessLogResponse recordAccessLog(CheckCameraEntryResponse entryResponse, OffsetDateTime at) {
        if (entryResponse == null || entryResponse.getCameraCode() == null) {
            return null;
        }

        if (at == null) {
            at = OffsetDateTime.now();
        }

        String cameraCode = entryResponse.getCameraCode();
        String userCode = entryResponse.getUserCode() != null ? entryResponse.getUserCode() : "UNKNOWN";

        // 1. Kiểm tra Debounce chống spam
        String cacheKey = cameraCode + ":" + userCode;
        long nowEpoch = at.toEpochSecond();
        Long lastSeen = debounceCache.get(cacheKey);
        int debounceSeconds = systemConfigService.getInt(ConfigKey.ENTRY_LOG_DEBOUNCE_SECONDS);
        if (lastSeen != null && (nowEpoch - lastSeen) < debounceSeconds) {
            log.info("Debounce: Bỏ qua ghi log trùng lặp cho [{}] tại [{}] trong vòng {}s", userCode, cameraCode, debounceSeconds);
            return null;
        }
        debounceCache.put(cacheKey, nowEpoch);
        log.info("Recording access log for camera [{}], user [{}]", cameraCode, userCode);

        // 2. Tìm Area và User
        Area area = null;
        if (entryResponse.getAreaId() != null) {
            area = areaRepository.findById(entryResponse.getAreaId()).orElse(null);
        }

        User user = null;
        if (entryResponse.getUserCode() != null) {
            user = userRepository.findByUserCodeAndDeletedAtIsNull(entryResponse.getUserCode()).orElse(null);
        }

        // 3. Phân loại kết quả theo 5 nhóm chuẩn Ma trận
        AccessResult result = classifyResult(entryResponse, at, user != null);

        String fullName = entryResponse.getFullName();
        if (fullName == null && user != null) {
            fullName = user.getFullName();
        }
        Integer userAccessLevel = user != null ? user.getAccessLevel() : null;

        // 4. Lưu Entity vào CSDL
        CameraAccessLog logEntity = CameraAccessLog.builder()
                .cameraCode(cameraCode)
                .area(area)
                .user(user)
                .userCode(userCode)
                .fullName(fullName)
                .userAccessLevel(userAccessLevel)
                .accessResult(result)
                .accessSource(entryResponse.getSource())
                .reasonDetails(entryResponse.getReason())
                .loggedAt(at)
                .build();

        CameraAccessLog saved = cameraAccessLogRepository.save(logEntity);
        CameraAccessLogResponse responseDto = CameraAccessLogResponse.from(saved);

        // 5. Phát WebSocket sự kiện thời gian thực cho Tab Timeline
        try {
            String topic = "/topic/cameras/" + cameraCode + "/access-logs";
            messagingTemplate.convertAndSend(topic, responseDto);
            log.info("Broadcasted access log [{}] to [{}] with result [{}]", userCode, topic, result);
        } catch (Exception e) {
            log.warn("Không thể gửi thông điệp WebSocket cho access log: {}", e.getMessage());
        }

        return responseDto;
    }

    /**
     * Phân loại kết quả chuẩn theo Ma trận kiểm soát an ninh.
     */
    public AccessResult classifyResult(CheckCameraEntryResponse entryResponse, OffsetDateTime at, boolean userExists) {
        if (entryResponse.isAllowed()) {
            return AccessResult.AUTHORIZED_ACCESS;
        }

        String reason = entryResponse.getReason() != null ? entryResponse.getReason().toLowerCase() : "";
        String userCode = entryResponse.getUserCode() != null ? entryResponse.getUserCode() : "";

        // 1. Kiểm tra Ngoài giờ (INTRUSION): Ban đêm (22:00 - 06:00) hoặc sự cố ngoài giờ / đột nhập
        int hour = at.atZoneSameInstant(VN_ZONE).getHour();
        if (hour >= 22 || hour < 6 || reason.contains("ngoài giờ") || reason.contains("after_hours") || reason.contains("đột nhập") || reason.contains("intrusion")) {
            return AccessResult.INTRUSION;
        }

        // 2. Kiểm tra Sai giờ (ACCESS_OUT_OF_TIME): Đến sớm, trễ, quá hạn, ngoài khung giờ quy định
        if (reason.contains("ngoài khung giờ") || reason.contains("hết hạn") || reason.contains("chưa tới giờ") || reason.contains("chưa đến giờ") || reason.contains("khung giờ") || reason.contains("thời gian") || reason.contains("out_of_time")) {
            return AccessResult.ACCESS_OUT_OF_TIME;
        }

        // 3. Kiểm tra Người lạ (UNKNOWN_PERSON): Chưa rõ mặt, không có tài khoản, đã bị xoá, hoặc gắn cờ UNKNOWN
        boolean isGuest = userCode.startsWith("GUEST-");
        if ((!userExists && !isGuest) || reason.contains("người dùng không tồn tại") || reason.contains("đã bị xoá") || userCode.startsWith("UNKNOWN") || reason.contains("chưa rõ mặt") || reason.contains("người lạ")) {
            return AccessResult.UNKNOWN_PERSON;
        }

        // 4. Mặc định: Sai quyền (UNAUTHORIZED_ACCESS) - Cấp độ không đủ, không có đơn, tài khoản bị khoá
        return AccessResult.UNAUTHORIZED_ACCESS;
    }

    @Transactional(readOnly = true)
    public Page<CameraAccessLogResponse> getLogs(
            String cameraCode,
            AccessResult accessResult,
            String search,
            OffsetDateTime from,
            OffsetDateTime to,
            Pageable pageable
    ) {
        String cleanSearch = (search != null && !search.trim().isEmpty()) ? "%" + search.trim().toLowerCase() + "%" : null;
        return cameraAccessLogRepository.findFiltered(cameraCode, accessResult, cleanSearch, from, to, pageable)
                .map(CameraAccessLogResponse::from);
    }

    @Transactional(readOnly = true)
    public CameraAccessLogStatsResponse getStatsToday(String cameraCode) {
        ZoneOffset offset = ZoneOffset.ofHours(7);
        LocalDate today = LocalDate.now(offset);
        OffsetDateTime startOfDay = today.atStartOfDay().atOffset(offset);
        OffsetDateTime endOfDay = today.atTime(LocalTime.MAX).atOffset(offset);

        long total = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetween(cameraCode, startOfDay, endOfDay);
        long authorized = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.AUTHORIZED_ACCESS);
        long unauthorized = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.UNAUTHORIZED_ACCESS);
        long outOfTime = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.ACCESS_OUT_OF_TIME);
        long unknown = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.UNKNOWN_PERSON);
        long intrusion = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.INTRUSION);

        return CameraAccessLogStatsResponse.builder()
                .cameraCode(cameraCode)
                .totalToday(total)
                .authorizedToday(authorized)
                .unauthorizedToday(unauthorized)
                .outOfTimeToday(outOfTime)
                .unknownToday(unknown)
                .intrusionToday(intrusion)
                .build();
    }

    public void clearDebounceCache() {
        debounceCache.clear();
    }
}
