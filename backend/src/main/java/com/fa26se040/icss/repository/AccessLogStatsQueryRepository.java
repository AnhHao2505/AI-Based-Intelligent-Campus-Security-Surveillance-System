package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.CameraAccessLog;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Truy vấn CHỈ ĐỌC trên camera_access_logs phục vụ Dashboard thống kê an ninh (FM, ADMIN).
 *
 * <p>Kế thừa {@link Repository} nên không có save/delete. Không thay thế {@link CameraAccessLogRepository}
 * — nhật ký ra vào vẫn ghi và tra cứu qua repository đó.</p>
 */
public interface AccessLogStatsQueryRepository extends Repository<CameraAccessLog, UUID> {

    interface DayResultCount {
        /** Ngày theo múi giờ {@code zone}, dạng yyyy-MM-dd. */
        String getDay();

        String getAccessResult();

        Long getTotal();
    }

    /**
     * Số lượt ra vào theo ngày và kết quả, {@code loggedAt} trong [from, to]. Ngày cắt theo múi giờ {@code zone}
     * ngay trong SQL (native vì JPQL không có AT TIME ZONE), không phụ thuộc múi giờ phiên DB.
     */
    @Query(value = "SELECT to_char(l.logged_at AT TIME ZONE :zone, 'YYYY-MM-DD') AS day, " +
                   "l.access_result AS accessResult, COUNT(*) AS total " +
                   "FROM camera_access_logs l " +
                   "WHERE l.logged_at >= :from AND l.logged_at <= :to " +
                   "GROUP BY 1, 2", nativeQuery = true)
    List<DayResultCount> countByDayAndResult(@Param("from") OffsetDateTime from,
                                             @Param("to") OffsetDateTime to,
                                             @Param("zone") String zone);
}
