package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.enums.IncidentStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Truy vấn CHỈ ĐỌC trên security_incidents phục vụ Dashboard thống kê an ninh (FM, ADMIN).
 *
 * <p>Kế thừa {@link Repository} (không phải JpaRepository) nên không có save/delete: Dashboard không ghi
 * dữ liệu sự cố. Không thay thế {@link SecurityIncidentRepository} — nghiệp vụ sự cố vẫn đi qua repository đó.</p>
 *
 * <p>Khoảng thời gian lọc theo {@code detectedAt} trong [from, to].</p>
 */
public interface SecurityStatsQueryRepository extends Repository<SecurityIncident, UUID> {

    interface StatusCount {
        IncidentStatus getStatus();

        Long getTotal();
    }

    interface AreaCount {
        UUID getAreaId();

        String getAreaName();

        Long getTotal();
    }

    /** Số sự cố theo trạng thái; trạng thái không có sự cố nào thì không có dòng. */
    @Query("SELECT i.status AS status, COUNT(i) AS total FROM SecurityIncident i " +
           "WHERE i.detectedAt >= :from AND i.detectedAt <= :to " +
           "GROUP BY i.status")
    List<StatusCount> countByStatus(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /**
     * Số sự cố theo khu vực, nhiều nhất trước, hoà thì theo tên khu. LEFT JOIN để sự cố không gắn khu
     * (areaId null) vẫn được đếm thành một nhóm riêng.
     */
    @Query("SELECT a.id AS areaId, a.name AS areaName, COUNT(i) AS total FROM SecurityIncident i " +
           "LEFT JOIN i.area a " +
           "WHERE i.detectedAt >= :from AND i.detectedAt <= :to " +
           "GROUP BY a.id, a.name " +
           "ORDER BY COUNT(i) DESC, a.name ASC, a.id ASC")
    List<AreaCount> countByArea(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
