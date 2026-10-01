package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.enums.GuestVisitStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuestVisitRepository extends JpaRepository<GuestVisit, UUID> {

    /** BR-GV-32: khoá dòng lượt khách trước mọi chuyển trạng thái. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM GuestVisit v WHERE v.id = :id")
    Optional<GuestVisit> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT DISTINCT v FROM GuestVisit v JOIN FETCH v.host LEFT JOIN FETCH v.areas WHERE v.id = :id")
    Optional<GuestVisit> findByIdWithDetails(@Param("id") UUID id);

    Page<GuestVisit> findByHostIdOrderByCreatedAtDesc(UUID hostId, Pageable pageable);

    Page<GuestVisit> findByStatusOrderByCreatedAtDesc(GuestVisitStatus status, Pageable pageable);

    Page<GuestVisit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * BR-GV-06: host có AP chưa thu hồi phủ trọn [from, to) tại khu vực (validTo loại trừ, NULL = không hạn).
     * Cùng điều kiện "còn hiệu lực" với AreaAssignedPersonnelRepository#findEffectiveAt, mở rộng cho cả khung giờ.
     */
    @Query("SELECT COUNT(a) > 0 FROM AreaAssignedPersonnel a "
            + "WHERE a.area.id = :areaId AND a.user.id = :userId AND a.revokedAt IS NULL "
            + "AND a.validFrom <= :from AND (a.validTo IS NULL OR a.validTo >= :to)")
    boolean existsAssignedPersonnelCovering(@Param("userId") UUID userId, @Param("areaId") UUID areaId,
                                            @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    // ------------------------------------------------------------------ job (BR-GV-12, 25, 29)

    @Query("SELECT v.id FROM GuestVisit v WHERE v.status = :status AND v.startTime <= :now ORDER BY v.startTime")
    List<UUID> findIdsByStatusAndStartTimeLessThanEqual(@Param("status") GuestVisitStatus status, @Param("now") OffsetDateTime now);

    @Query("SELECT v.id FROM GuestVisit v WHERE v.status = :status AND v.endTime <= :now ORDER BY v.endTime")
    List<UUID> findIdsByStatusAndEndTimeLessThanEqual(@Param("status") GuestVisitStatus status, @Param("now") OffsetDateTime now);

    /** Step 6 (BR-AD-09): lượt khách chứa khu vực, trạng thái thuộc :statuses, chưa kết thúc tại :now. */
    @Query("SELECT DISTINCT v FROM GuestVisit v JOIN v.areas a "
            + "WHERE a.id = :areaId AND v.status IN :statuses AND v.endTime > :now ORDER BY v.startTime")
    List<GuestVisit> findNotEndedByArea(@Param("areaId") UUID areaId,
                                        @Param("statuses") java.util.Collection<GuestVisitStatus> statuses,
                                        @Param("now") OffsetDateTime now);
}
