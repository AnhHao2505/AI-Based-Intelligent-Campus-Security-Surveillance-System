package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccessRequestRepository extends JpaRepository<AccessRequest, UUID> {

    @Query("SELECT ar FROM AccessRequest ar " +
           "JOIN FETCH ar.area " +
           "JOIN FETCH ar.requester " +
           "LEFT JOIN FETCH ar.reviewer " +
           "WHERE ar.id = :id")
    Optional<AccessRequest> findByIdWithDetails(@Param("id") UUID id);

    @Query(value = "SELECT ar FROM AccessRequest ar " +
                   "JOIN FETCH ar.area " +
                   "WHERE ar.requester.id = :requesterId " +
                   "AND (:status IS NULL OR ar.status = :status)",
           countQuery = "SELECT COUNT(ar) FROM AccessRequest ar " +
                        "WHERE ar.requester.id = :requesterId " +
                        "AND (:status IS NULL OR ar.status = :status)")
    Page<AccessRequest> findMyRequests(
            @Param("requesterId") UUID requesterId,
            @Param("status") RequestStatus status,
            Pageable pageable
    );

    @Query(value = "SELECT ar FROM AccessRequest ar " +
                   "JOIN FETCH ar.area " +
                   "JOIN FETCH ar.requester " +
                   "WHERE (:status IS NULL OR ar.status = :status)",
           countQuery = "SELECT COUNT(ar) FROM AccessRequest ar " +
                        "WHERE (:status IS NULL OR ar.status = :status)")
    Page<AccessRequest> findAllRequests(
            @Param("status") RequestStatus status,
            Pageable pageable
    );

    @Query("SELECT DISTINCT ar FROM AccessRequest ar " +
           "JOIN FETCH ar.requester " +
           "LEFT JOIN FETCH ar.members m " +
           "LEFT JOIN FETCH m.user " +
           "WHERE ar.area.id = :areaId " +
           "AND ar.status IN :statuses " +
           "AND ar.startTime < :endTime " +
           "AND ar.endTime > :startTime " +
           "AND (ar.requester.id IN :userIds OR EXISTS (" +
           "    SELECT 1 FROM AccessRequestMember arm " +
           "    WHERE arm.accessRequest.id = ar.id AND arm.user.id IN :userIds" +
           "))")
    List<AccessRequest> findOverlappingRequests(
            @Param("userIds") Collection<UUID> userIds,
            @Param("areaId") UUID areaId,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime,
            @Param("statuses") Collection<RequestStatus> statuses
    );

    @Query("SELECT ar FROM AccessRequest ar " +
           "JOIN FETCH ar.area " +
           "JOIN FETCH ar.requester " +
           "WHERE ar.status = :status AND ar.startTime < :now")
    List<AccessRequest> findPendingOverdueRequests(
            @Param("status") RequestStatus status,
            @Param("now") OffsetDateTime now
    );

    @Query("SELECT DISTINCT ar FROM AccessRequest ar " +
           "JOIN FETCH ar.area " +
           "JOIN FETCH ar.requester " +
           "LEFT JOIN FETCH ar.members m " +
           "LEFT JOIN FETCH m.user " +
           "WHERE ar.status = :status AND ar.startTime >= :windowStart AND ar.startTime <= :windowEnd")
    List<AccessRequest> findApprovedRequestsStartingBetween(
            @Param("status") RequestStatus status,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd
    );

    @Query("SELECT ar FROM AccessRequest ar " +
           "JOIN FETCH ar.area " +
           "JOIN FETCH ar.requester " +
           "WHERE ar.status = :status AND ar.createdAt < :threshold")
    List<AccessRequest> findPendingRequestsCreatedBefore(
            @Param("status") RequestStatus status,
            @Param("threshold") OffsetDateTime threshold
    );

    @Modifying
    @Query("UPDATE AccessRequest ar SET ar.status = :newStatus, ar.updatedAt = :now " +
           "WHERE ar.status = :currentStatus AND ar.startTime < :now")
    int expireOverdueRequests(
            @Param("currentStatus") RequestStatus currentStatus,
            @Param("newStatus") RequestStatus newStatus,
            @Param("now") OffsetDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AccessRequest r " +
           "SET r.status = :newStatus, " +
           "    r.reviewer = :reviewer, " +
           "    r.reviewedAt = :reviewedAt, " +
           "    r.rejectionReason = :rejectionReason, " +
           "    r.updatedAt = :reviewedAt " +
           "WHERE r.id = :id AND r.status = :expectedStatus")
    int reviewIfPending(
            @Param("id") UUID id,
            @Param("newStatus") RequestStatus newStatus,
            @Param("reviewer") User reviewer,
            @Param("reviewedAt") OffsetDateTime reviewedAt,
            @Param("rejectionReason") String rejectionReason,
            @Param("expectedStatus") RequestStatus expectedStatus
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AccessRequest r " +
           "SET r.status = :newStatus, " +
           "    r.updatedAt = :now " +
           "WHERE r.id = :id AND r.status = :expectedStatus")
    int cancelIfPending(
            @Param("id") UUID id,
            @Param("newStatus") RequestStatus newStatus,
            @Param("now") OffsetDateTime now,
            @Param("expectedStatus") RequestStatus expectedStatus
    );
}
