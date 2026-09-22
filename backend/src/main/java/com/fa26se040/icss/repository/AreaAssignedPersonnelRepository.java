package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.User;
import jakarta.persistence.LockModeType;
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
public interface AreaAssignedPersonnelRepository extends JpaRepository<AreaAssignedPersonnel, UUID> {

    @Query("SELECT a FROM AreaAssignedPersonnel a " +
           "JOIN FETCH a.user " +
           "LEFT JOIN FETCH a.createdBy " +
           "LEFT JOIN FETCH a.revokedBy " +
           "WHERE a.area.id = :areaId " +
           "ORDER BY a.createdAt DESC")
    List<AreaAssignedPersonnel> findByAreaIdWithDetails(@Param("areaId") UUID areaId);

    @Query("SELECT a FROM AreaAssignedPersonnel a " +
           "JOIN FETCH a.area " +
           "JOIN FETCH a.user " +
           "LEFT JOIN FETCH a.createdBy " +
           "LEFT JOIN FETCH a.revokedBy " +
           "WHERE a.id = :id AND a.area.id = :areaId")
    Optional<AreaAssignedPersonnel> findByIdAndAreaIdWithDetails(
            @Param("id") UUID id,
            @Param("areaId") UUID areaId
    );

    /**
     * BR-AP-03: bản ghi CHƯA thu hồi của cùng area + user có khoảng [validFrom, validTo) chồng với
     * khoảng mới [:validFrom, :validTo). Bản ghi cũ validTo NULL = vô hạn.
     * Hai khoảng nối tiếp (validTo cũ == validFrom mới) KHÔNG tính là chồng lấn.
     * Tách riêng bản có validTo mới = NULL để tránh bind tham số NULL vào điều kiện "IS NULL".
     */
    @Query("SELECT a FROM AreaAssignedPersonnel a " +
           "WHERE a.area.id = :areaId " +
           "AND a.user.id = :userId " +
           "AND a.revokedAt IS NULL " +
           "AND a.validFrom < :validTo " +
           "AND (a.validTo IS NULL OR a.validTo > :validFrom)")
    List<AreaAssignedPersonnel> findOverlappingNotRevoked(
            @Param("areaId") UUID areaId,
            @Param("userId") UUID userId,
            @Param("validFrom") OffsetDateTime validFrom,
            @Param("validTo") OffsetDateTime validTo
    );

    /**
     * BR-AP-03: như findOverlappingNotRevoked nhưng khoảng mới không có validTo (vô hạn).
     */
    @Query("SELECT a FROM AreaAssignedPersonnel a " +
           "WHERE a.area.id = :areaId " +
           "AND a.user.id = :userId " +
           "AND a.revokedAt IS NULL " +
           "AND (a.validTo IS NULL OR a.validTo > :validFrom)")
    List<AreaAssignedPersonnel> findOverlappingNotRevokedOpenEnded(
            @Param("areaId") UUID areaId,
            @Param("userId") UUID userId,
            @Param("validFrom") OffsetDateTime validFrom
    );

    /**
     * Bản ghi gán còn hiệu lực tại thời điểm :at (dùng cho AccessDecisionService#checkEntry).
     */
    @Query("SELECT a FROM AreaAssignedPersonnel a " +
           "WHERE a.area.id = :areaId " +
           "AND a.user.id = :userId " +
           "AND a.revokedAt IS NULL " +
           "AND a.validFrom <= :at " +
           "AND (a.validTo IS NULL OR a.validTo > :at) " +
           "ORDER BY a.validFrom DESC")
    List<AreaAssignedPersonnel> findEffectiveAt(
            @Param("userId") UUID userId,
            @Param("areaId") UUID areaId,
            @Param("at") OffsetDateTime at
    );

    /**
     * Khoá dòng users (SELECT ... FOR UPDATE) trong transaction hiện tại để tuần tự hoá các thao tác
     * gán/sửa cho cùng một user, tránh hai FM cùng lúc tạo bản ghi chồng lấn lọt BR-AP-03.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findUserByIdForUpdate(@Param("userId") UUID userId);
}
