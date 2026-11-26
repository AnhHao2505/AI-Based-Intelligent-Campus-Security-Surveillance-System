package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogStatsResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import com.fa26se040.icss.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
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

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    // Bộ nhớ đệm in-memory Debounce (Key: cameraCode:userCode -> Expire Epoch Seconds)
    private final Map<String, Long> debounceCache = new ConcurrentHashMap<>();

    /**
     * Ghi nhận lượt quét ra vào từ camera (có cơ chế debounce chống spam, số giây đọc từ ENTRY_LOG_DEBOUNCE_SECONDS).
     * Kiểm tra debounce TRƯỚC khi mở giao dịch REQUIRES_NEW để lượt bị bỏ qua không chiếm kết nối DB.
     */
    public CameraAccessLogResponse recordAccessLog(CheckCameraEntryResponse entryResponse, OffsetDateTime at) {
        if (entryResponse == null || entryResponse.getCameraCode() == null) {
            return null;
        }

        final OffsetDateTime effectiveAt = at != null ? at : OffsetDateTime.now();

        String cameraCode = entryResponse.getCameraCode();
        String userCode = (entryResponse.getUserCode() != null && !entryResponse.getUserCode().isBlank())
                ? entryResponse.getUserCode()
                : "UNKNOWN";

        // Bỏ qua ghi log khi ra khỏi ROI (chức năng log out ROI đã được xoá)
        if (entryResponse.getReason() != null && (
                entryResponse.getReason().startsWith("[RA KHỎI ROI") ||
                entryResponse.getReason().toLowerCase().contains("rời khu vực") ||
                entryResponse.getReason().toLowerCase().contains("rời khỏi")
        )) {
            log.info("Bỏ qua ghi nhật ký ra khỏi ROI: camera=[{}], user=[{}], reason=[{}]", cameraCode, userCode, entryResponse.getReason());
            return null;
        }

        // 1. Kiểm tra Debounce chống spam, khoá theo kết quả ALLOWED/DENIED để log chuyển trạng thái không bị nuốt.
        // Lượt nhận diện thường bị chặn bởi mọi log cùng kết quả (thường hoặc vào ROI) trong cửa sổ debounce;
        // log vào ROI chỉ bị chặn bởi log vào ROI trước đó, nên nhãn [VÀO ROI] không bị lượt nhận diện thường nuốt.
        boolean isRoiEntry = entryResponse.getReason() != null && entryResponse.getReason().startsWith("[VÀO ROI");
        String outcome = entryResponse.isAllowed() ? "ALLOWED" : "DENIED";
        String oppositeOutcome = entryResponse.isAllowed() ? "DENIED" : "ALLOWED";
        String baseKey = cameraCode + ":" + userCode + ":" + outcome;
        String cacheKey = isRoiEntry ? baseKey + ":ENTRY" : baseKey;
        long nowEpoch = effectiveAt.toEpochSecond();
        Long lastSeen = debounceCache.get(cacheKey);
        int debounceSeconds = systemConfigService.getInt(ConfigKey.ENTRY_LOG_DEBOUNCE_SECONDS);
        if (lastSeen != null && (nowEpoch - lastSeen) < debounceSeconds) {
            log.info("Debounce: Bỏ qua ghi log trùng lặp cho [{}] tại [{}] ({}) trong vòng {}s", userCode, cameraCode, outcome, debounceSeconds);
            return null;
        }
        debounceCache.put(baseKey, nowEpoch);
        if (isRoiEntry) {
            debounceCache.put(baseKey + ":ENTRY", nowEpoch);
        }
        String oppositeKey = cameraCode + ":" + userCode + ":" + oppositeOutcome;
        debounceCache.remove(oppositeKey);
        debounceCache.remove(oppositeKey + ":ENTRY");
        log.info("Recording access log for camera [{}], user [{}]", cameraCode, userCode);

        if (transactionManager != null) {
            TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return requiresNew.execute(status -> persistAndBroadcastAccessLog(entryResponse, effectiveAt, cameraCode, userCode));
        }
        return persistAndBroadcastAccessLog(entryResponse, effectiveAt, cameraCode, userCode);
    }

    private CameraAccessLogResponse persistAndBroadcastAccessLog(
            CheckCameraEntryResponse entryResponse,
            OffsetDateTime at,
            String cameraCode,
            String userCode
    ) {
        // 2. Tìm Area và User (dùng getReference + metadata đã tải ở AccessDecisionService để tránh 2 query thừa)
        Area area = null;
        if (entryResponse.getAreaId() != null) {
            if (entityManager != null) {
                area = entityManager.getReference(Area.class, entryResponse.getAreaId());
            } else {
                area = areaRepository.findById(entryResponse.getAreaId()).orElse(null);
            }
        }

        User user = null;
        boolean userExists;
        boolean isGuard;
        Integer userAccessLevel;
        String fullName = entryResponse.getFullName();

        if (entryResponse.getUserId() != null && entryResponse.getUserRole() != null && entityManager != null) {
            user = entityManager.getReference(User.class, entryResponse.getUserId());
            userExists = true;
            isGuard = entryResponse.getUserRole() == Role.GUARD;
            userAccessLevel = entryResponse.getUserAccessLevel();
        } else if (Boolean.TRUE.equals(entryResponse.getUserKnownAbsent())) {
            user = null;
            userExists = false;
            isGuard = false;
            userAccessLevel = null;
        } else {
            if (entryResponse.getUserCode() != null) {
                user = userRepository.findByUserCodeAndDeletedAtIsNull(entryResponse.getUserCode()).orElse(null);
            }
            userExists = user != null;
            isGuard = user != null && user.getRole() == Role.GUARD;
            userAccessLevel = user != null ? user.getAccessLevel() : null;
            if (fullName == null && user != null) {
                fullName = user.getFullName();
            }
        }

        // 3. Phân loại kết quả theo 5 nhóm chuẩn Ma trận
        AccessResult result = classifyResult(entryResponse, at, userExists, isGuard);

        String reasonDetails = entryResponse.getReason();
        if (reasonDetails != null) {
            reasonDetails = reasonDetails.replaceAll("\\[[A-Z0-9_]+\\]\\s*", "").trim();
        }

        // 3b. Chuẩn hóa danh tính khi là Người lạ (UNKNOWN_PERSON) hoặc Đột nhập ngoài giờ của người lạ
        // Giữ nguyên mã AI quét được (userCode) để phục vụ truy vết điều tra; chỉ ẩn thông tin user entity và đặt tên chuẩn hoá
        boolean isGuest = userCode.startsWith("GUEST-");
        if (result == AccessResult.UNKNOWN_PERSON || ((result == AccessResult.PERSON_INTRUSION || result == AccessResult.INTRUSION) && !userExists && !isGuest)) {
            user = null;
            fullName = "Người lạ / Chưa rõ mặt";
            userAccessLevel = null;
            if (result == AccessResult.UNKNOWN_PERSON) {
                if (reasonDetails == null || reasonDetails.isBlank()
                        || reasonDetails.contains("người dùng không tồn tại")
                        || reasonDetails.contains("không tồn tại")
                        || reasonDetails.contains("đã bị xoá")) {
                    reasonDetails = "Phát hiện người chưa đăng ký tại khu vực giám sát.";
                }
            }
        } else if (result == AccessResult.UNAUTHORIZED_PERSON || result == AccessResult.UNAUTHORIZED_ACCESS) {
            if (reasonDetails != null && reasonDetails.contains("Lý do:")) {
                reasonDetails = reasonDetails.substring(reasonDetails.indexOf("Lý do:") + 6).trim();
            }
        }

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
                .reasonDetails(reasonDetails)
                .loggedAt(at)
                .build();

        CameraAccessLog saved = cameraAccessLogRepository.save(logEntity);
        CameraAccessLogResponse responseDto = CameraAccessLogResponse.from(
                saved,
                entryResponse.getAreaId(),
                entryResponse.getAreaName()
        );

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
        return classifyResult(entryResponse, at, userExists, false);
    }

    /**
     * @param isGuard người dùng có role GUARD: ngoài giờ được miễn theo nguồn, khớp luật ban đêm bên AI
     *                (FrameAnalysisEngine._check_is_night_authorized).
     */
    public AccessResult classifyResult(CheckCameraEntryResponse entryResponse, OffsetDateTime at, boolean userExists, boolean isGuard) {
        String reason = entryResponse.getReason() != null ? entryResponse.getReason().toLowerCase() : "";
        String userCode = entryResponse.getUserCode() != null ? entryResponse.getUserCode() : "";

        // 1. Kiểm tra Người lạ (UNKNOWN_PERSON) TRƯỚC TIÊN:
        // Chưa rõ mặt, không có tài khoản, đã bị xoá, người lạ rời đi, hoặc gắn cờ UNKNOWN
        boolean isGuest = userCode.startsWith("GUEST-");
        boolean isExplicitUnknownCode = userCode.isBlank() || userCode.startsWith("UNKNOWN") || userCode.equals("VIOLATION");
        if ((!userExists && !isGuest && isExplicitUnknownCode)
                || reason.contains("người dùng không tồn tại")
                || reason.contains("đã bị xoá")
                || userCode.startsWith("UNKNOWN")
                || reason.contains("chưa rõ mặt")
                || reason.contains("chưa rõ danh tính")
                || reason.contains("chưa đăng ký")
                || reason.contains("người lạ")) {
            return AccessResult.UNKNOWN_PERSON;
        }

        // 2. Nếu được phép -> kiểm tra khung giờ ngoài giờ (Luật ban đêm: cấp độ chung không có hiệu lực ban đêm)
        if (entryResponse.isAllowed()) {
            if (!isGuard && isAfterHours(at)) {
                com.fa26se040.icss.enums.AccessSource src = entryResponse.getSource();
                boolean isNightExempt = src == com.fa26se040.icss.enums.AccessSource.ASSIGNED_PERSONNEL
                        || src == com.fa26se040.icss.enums.AccessSource.ACCESS_REQUEST
                        || src == com.fa26se040.icss.enums.AccessSource.GUEST_VISIT
                        || src == com.fa26se040.icss.enums.AccessSource.INCIDENT_RESPONSE;
                if (!isNightExempt) {
                    return AccessResult.PERSON_INTRUSION;
                }
            }
            return AccessResult.AUTHORIZED_PERSON;
        }

        // 3. Kiểm tra Ngoài giờ (PERSON_INTRUSION)
        if (isAfterHours(at) || reason.contains("ngoài giờ") || reason.contains("after_hours") || reason.contains("đột nhập") || reason.contains("intrusion")) {
            return AccessResult.PERSON_INTRUSION;
        }

        // 4. Kiểm tra Chuyển động nghi vấn (SUSPICIOUS_MOVING_OBJECT)
        if (reason.contains("chuyển động nghi vấn") || reason.contains("suspicious_moving_object") || reason.contains("nguy trang")) {
            return AccessResult.SUSPICIOUS_MOVING_OBJECT;
        }

        // 5. Mặc định: Sai quyền / Sai giờ (UNAUTHORIZED_PERSON)
        return AccessResult.UNAUTHORIZED_PERSON;
    }

    /**
     * at (quy về giờ Asia/Ho_Chi_Minh) có nằm trong khung ngoài giờ AI_AFTER_HOUR_START–AI_AFTER_HOUR_END không.
     * Đọc cấu hình qua SystemConfigService mỗi lần gọi nên sửa có hiệu lực ngay.
     */
    private boolean isAfterHours(OffsetDateTime at) {
        LocalTime time = at.atZoneSameInstant(VN_ZONE).toLocalTime();
        return isWithin(time, readTime(ConfigKey.AI_AFTER_HOUR_START), readTime(ConfigKey.AI_AFTER_HOUR_END));
    }

    /**
     * t thuộc khung [start, end): start inclusive, end exclusive.
     * start &lt; end: khung trong ngày; start &gt; end: khung qua nửa đêm; start == end: khung rỗng (chỉ còn để chịu
     * dữ liệu cũ: BE đã từ chối lưu START = END, #262).
     */
    static boolean isWithin(LocalTime t, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        if (start.isAfter(end)) {
            return !t.isBefore(start) || t.isBefore(end);
        }
        return false;
    }

    private LocalTime readTime(ConfigKey key) {
        String raw = systemConfigService.getString(key);
        try {
            return LocalTime.parse(raw != null ? raw.trim() : key.getDefaultValue());
        } catch (DateTimeParseException e) {
            log.warn("Giá trị [{}] của cấu hình [{}] không đúng định dạng HH:mm. Dùng mặc định: {}",
                    raw, key.getKey(), key.getDefaultValue());
            return LocalTime.parse(key.getDefaultValue());
        }
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
        long authorized = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.AUTHORIZED_PERSON)
                + cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.AUTHORIZED_ACCESS);
        long unauthorized = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.UNAUTHORIZED_PERSON)
                + cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.UNAUTHORIZED_ACCESS)
                + cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.ACCESS_OUT_OF_TIME);
        long outOfTime = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.ACCESS_OUT_OF_TIME);
        long unknown = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.UNKNOWN_PERSON);
        long intrusion = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.PERSON_INTRUSION)
                + cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.INTRUSION);
        long suspicious = cameraAccessLogRepository.countByCameraCodeAndLoggedAtBetweenAndAccessResult(cameraCode, startOfDay, endOfDay, AccessResult.SUSPICIOUS_MOVING_OBJECT);

        return CameraAccessLogStatsResponse.builder()
                .cameraCode(cameraCode)
                .totalToday(total)
                .authorizedToday(authorized)
                .unauthorizedToday(unauthorized)
                .outOfTimeToday(outOfTime)
                .unknownToday(unknown)
                .intrusionToday(intrusion)
                .suspiciousToday(suspicious)
                .build();
    }

    public void clearDebounceCache() {
        debounceCache.clear();
    }
}
