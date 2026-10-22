package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.EventScheduleResponse;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Màn Quản lý sự kiện (FM): liệt kê lịch sự kiện của mọi khu vực, có lọc và phân trang.
 * Một câu đếm + một câu lấy trang (JOIN FETCH khu vực và người tạo / sửa / huỷ) -> không N+1.
 * Chỉ ghép điều kiện cho tham số có giá trị (không dùng ":p IS NULL OR ..." vì PostgreSQL không suy ra kiểu tham số null).
 */
@Service
@RequiredArgsConstructor
public class EventScheduleQueryService {

    /** Ngày lọc (from / to) tính theo giờ Việt Nam, giống cách hiển thị trên giao diện. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_PAGE_SIZE = 100;

    private final EntityManager entityManager;

    /**
     * @param statuses trạng thái lưu (BR-ES-L1), rỗng = mọi trạng thái; giá trị lạ -> 400
     * @param from     lấy lịch giao với khoảng [from 00:00, to + 1 ngày 00:00); from sau to -> 400
     * @param q        tìm trong tên sự kiện và tên khu, không phân biệt hoa thường
     */
    @Transactional(readOnly = true)
    public Page<EventScheduleResponse> search(List<String> statuses, UUID areaId, LocalDate from, LocalDate to,
                                              String q, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Ngày bắt đầu lọc phải trước hoặc bằng ngày kết thúc");
        }
        int pageNo = Math.max(0, page);
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();

        List<AreaEventScheduleStatus> statusList = parseStatuses(statuses);
        if (!statusList.isEmpty()) {
            where.append(" AND s.status IN :statuses");
            params.put("statuses", statusList);
        }
        if (areaId != null) {
            where.append(" AND a.id = :areaId");
            params.put("areaId", areaId);
        }
        if (from != null) {
            where.append(" AND s.endAt > :fromTs");
            params.put("fromTs", from.atStartOfDay(ZONE).toOffsetDateTime());
        }
        if (to != null) {
            where.append(" AND s.startAt < :toTs");
            params.put("toTs", to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
        }
        String keyword = q != null ? q.trim() : "";
        if (!keyword.isEmpty()) {
            where.append(" AND (LOWER(s.title) LIKE :q ESCAPE '\\' OR LOWER(a.name) LIKE :q ESCAPE '\\')");
            params.put("q", "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%");
        }

        TypedQuery<Long> countQuery = entityManager.createQuery(
                "SELECT COUNT(s) FROM AreaEventSchedule s JOIN s.area a" + where, Long.class);
        params.forEach(countQuery::setParameter);
        long total = countQuery.getSingleResult();

        List<EventScheduleResponse> content = List.of();
        if (total > 0) {
            TypedQuery<AreaEventSchedule> pageQuery = entityManager.createQuery(
                    "SELECT s FROM AreaEventSchedule s"
                            + " JOIN FETCH s.area a"
                            + " JOIN FETCH s.createdBy"
                            + " LEFT JOIN FETCH s.updatedBy"
                            + " LEFT JOIN FETCH s.cancelledBy"
                            + where
                            + " ORDER BY s.startAt DESC, s.id DESC",
                    AreaEventSchedule.class);
            params.forEach(pageQuery::setParameter);
            pageQuery.setFirstResult(pageNo * pageSize);
            pageQuery.setMaxResults(pageSize);
            content = pageQuery.getResultList().stream().map(AreaService::mapToEventScheduleResponse).toList();
        }
        return new PageImpl<>(content, PageRequest.of(pageNo, pageSize), total);
    }

    private static List<AreaEventScheduleStatus> parseStatuses(List<String> raw) {
        List<AreaEventScheduleStatus> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String token : raw) {
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            try {
                AreaEventScheduleStatus st = AreaEventScheduleStatus.valueOf(token.trim().toUpperCase(Locale.ROOT));
                if (!result.contains(st)) {
                    result.add(st);
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Trạng thái lịch sự kiện không hợp lệ: " + token.trim());
            }
        }
        return result;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
