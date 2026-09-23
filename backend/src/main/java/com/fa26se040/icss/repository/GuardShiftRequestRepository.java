package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuardShiftRequest;
import com.fa26se040.icss.enums.GuardShiftRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface GuardShiftRequestRepository extends JpaRepository<GuardShiftRequest, UUID> {

    List<GuardShiftRequest> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId);

    List<GuardShiftRequest> findBySubstituteGuardIdOrderByCreatedAtDesc(UUID substituteGuardId);

    boolean existsByShiftIdAndStatus(UUID shiftId, GuardShiftRequestStatus status);

    boolean existsByShiftId(UUID shiftId);

    @Query("SELECT r FROM GuardShiftRequest r " +
            "JOIN r.shift s " +
            "JOIN r.requester req " +
            "WHERE (:status IS NULL OR r.status = :status) " +
            "AND (:teamId IS NULL OR req.team.id = :teamId) " +
            "AND (CAST(:startDate AS date) IS NULL OR s.shiftDate >= :startDate) " +
            "AND (CAST(:endDate AS date) IS NULL OR s.shiftDate <= :endDate) " +
            "ORDER BY r.createdAt DESC")
    List<GuardShiftRequest> findRequests(
            @Param("status") GuardShiftRequestStatus status,
            @Param("teamId") UUID teamId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
