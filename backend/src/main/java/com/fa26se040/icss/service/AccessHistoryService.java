package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesslog.AccessHistoryItem;
import com.fa26se040.icss.dto.accesslog.MyAccessHistoryItem;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import com.fa26se040.icss.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lịch sử ra vào đọc từ camera_access_logs (ghi bởi CameraAccessLogService, gồm cả lượt được và không được vào).
 * - getMyHistory: chỉ log có user_id = người đang đăng nhập; không nhận userId từ request.
 * - search: toàn hệ thống cho FACILITY_MANAGER / ADMIN, lọc theo khu vực, camera, mã người, kết quả, thời gian.
 * Không truyền from/to thì lấy 7 ngày gần nhất; from > to → 400 ERR_AC_010. Luôn sắp xếp loggedAt giảm dần.
 */
@Service
@RequiredArgsConstructor
public class AccessHistoryService {

    /** Khoảng thời gian mặc định khi không truyền from / to (đã chốt: 7 ngày gần nhất). */
    static final int DEFAULT_RANGE_DAYS = 7;
    /** Trần kích thước trang (giới hạn kỹ thuật chống tải quá lớn, không phải tham số nghiệp vụ). */
    static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VN_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final CameraAccessLogRepository cameraAccessLogRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<MyAccessHistoryItem> getMyHistory(String actorEmail, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        User me = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng"));
        OffsetDateTime[] range = resolveRange(from, to, OffsetDateTime.now());
        return cameraAccessLogRepository
                .findAll(filter(me.getId(), null, null, null, null, range[0], range[1]), pageRequest(page, size))
                .map(MyAccessHistoryItem::from);
    }

    @Transactional(readOnly = true)
    public Page<AccessHistoryItem> search(
            UUID areaId,
            String cameraCode,
            String userCode,
            AccessResult accessResult,
            OffsetDateTime from,
            OffsetDateTime to,
            int page,
            int size
    ) {
        OffsetDateTime[] range = resolveRange(from, to, OffsetDateTime.now());
        return cameraAccessLogRepository
                .findAll(filter(null, areaId, blankToNull(cameraCode), blankToNull(userCode), accessResult, range[0], range[1]),
                        pageRequest(page, size))
                .map(AccessHistoryItem::from);
    }

    /**
     * Không truyền gì → [now − 7 ngày, now]; chỉ có to → [to − 7 ngày, to]; chỉ có from → [from, now].
     */
    OffsetDateTime[] resolveRange(OffsetDateTime from, OffsetDateTime to, OffsetDateTime now) {
        OffsetDateTime end = to != null ? to : now;
        OffsetDateTime start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS);
        if (start.isAfter(end)) {
            throw new AccessControlException(AccessControlErrorCode.ERR_AC_010, format(start), format(end));
        }
        return new OffsetDateTime[]{start, end};
    }

    private static Specification<CameraAccessLog> filter(
            UUID userId,
            UUID areaId,
            String cameraCode,
            String userCode,
            AccessResult accessResult,
            OffsetDateTime from,
            OffsetDateTime to
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("user").get("id"), userId));
            }
            if (areaId != null) {
                predicates.add(cb.equal(root.get("area").get("id"), areaId));
            }
            if (cameraCode != null) {
                predicates.add(cb.equal(cb.upper(root.get("cameraCode")), cameraCode.toUpperCase()));
            }
            if (userCode != null) {
                predicates.add(cb.equal(cb.upper(root.get("userCode")), userCode.toUpperCase()));
            }
            if (accessResult != null) {
                predicates.add(cb.equal(root.get("accessResult"), accessResult));
            }
            predicates.add(cb.greaterThanOrEqualTo(root.get("loggedAt"), from));
            predicates.add(cb.lessThanOrEqualTo(root.get("loggedAt"), to));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Pageable pageRequest(int page, int size) {
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "loggedAt"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String format(OffsetDateTime value) {
        return value.atZoneSameInstant(VN_ZONE).format(VN_DATE_TIME_FORMATTER);
    }
}
