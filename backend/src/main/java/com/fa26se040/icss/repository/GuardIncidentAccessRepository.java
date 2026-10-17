package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.SecurityIncident;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Truy vấn CHỈ ĐỌC trên security_incidents phục vụ xét quyền ra vào của bảo vệ đang xử lý sự cố (BR-GD-02 / BR-GD-03).
 *
 * <p>Kế thừa {@link Repository} (không phải JpaRepository) nên không có save/delete: module access control
 * không ghi dữ liệu sự cố. Không thay thế {@link SecurityIncidentRepository} — mọi nghiệp vụ của module sự cố
 * vẫn đi qua repository đó.</p>
 */
public interface GuardIncidentAccessRepository extends Repository<SecurityIncident, UUID> {

    /**
     * Sự cố tại khu vực do bảo vệ này tiếp nhận, mới nhất theo claimedAt trước.
     * Gồm cả sự cố đã xử lý xong (RESOLVED_*) để tính thời gian ân hạn rời khu.
     * Gọi với {@code PageRequest.of(0, 1)} để chỉ lấy bản ghi mới nhất.
     */
    @Query("SELECT i FROM SecurityIncident i " +
            "WHERE i.area.id = :areaId " +
            "AND i.claimedBy.id = :userId " +
            "AND i.status IN ('CLAIMED', 'RESOLVED_VERIFIED', 'RESOLVED_DISMISSED') " +
            "AND i.claimedAt IS NOT NULL " +
            "ORDER BY i.claimedAt DESC")
    List<SecurityIncident> findLatestClaimedByUserInArea(@Param("userId") UUID userId,
                                                         @Param("areaId") UUID areaId,
                                                         Pageable pageable);
}
