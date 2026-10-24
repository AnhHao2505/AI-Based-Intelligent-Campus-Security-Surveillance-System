package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.RequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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
public interface AccessRequestRepository extends JpaRepository<AccessRequest, UUID>, JpaSpecificationExecutor<AccessRequest> {

    @Query("SELECT ar FROM AccessRequest ar " +
           "JOIN FETCH ar.area " +
           "JOIN FETCH ar.requester " +
           "LEFT JOIN FETCH ar.reviewer " +
           "LEFT JOIN FETCH ar.finishedBy " +
           "WHERE ar.id = :id")
    Optional<AccessRequest> findByIdWithDetails(@Param("id") UUID id);

    @Query(value = "SELECT ar FROM AccessRequest ar " +
                   "JOIN FETCH ar.area " +
                   "WHERE ar.requester.id = :requesterId " +
                   "AND (:status IS NULL OR ar.status = :status) " +
                   "AND (:areaId IS NULL OR ar.area.id = :areaId)",
           countQuery = "SELECT COUNT(ar) FROM AccessRequest ar " +
                        "WHERE ar.requester.id = :requesterId " +
                        "AND (:status IS NULL OR ar.status = :status) " +
                        "AND (:areaId IS NULL OR ar.area.id = :areaId)")
    Page<AccessRequest> findMyRequests(
            @Param("requesterId") UUID requesterId,
            @Param("status") RequestStatus status,
            @Param("areaId") UUID areaId,
            Pageable pageable
    );

    default Page<AccessRequest> findMyRequests(UUID requesterId, RequestStatus status, Pageable pageable) {
        return findMyRequests(requesterId, status, null, pageable);
    }

    @Query(value = "SELECT ar FROM AccessRequest ar " +
                   "JOIN FETCH ar.area " +
                   "JOIN FETCH ar.requester " +
                   "WHERE (:status IS NULL OR ar.status = :status) " +
                   "AND (:areaId IS NULL OR ar.area.id = :areaId)",
           countQuery = "SELECT COUNT(ar) FROM AccessRequest ar " +
                        "WHERE (:status IS NULL OR ar.status = :status) " +
                        "AND (:areaId IS NULL OR ar.area.id = :areaId)")
    Page<AccessRequest> findAllRequests(
            @Param("status") RequestStatus status,
            @Param("areaId") UUID areaId,
            Pageable pageable
    );

    default Page<AccessRequest> findAllRequests(RequestStatus status, Pageable pageable) {
        return findAllRequests(status, null, pageable);
    }

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ar FROM AccessRequest ar " +
           "WHERE ar.status = :status AND ar.startTime < :now")
    List<AccessRequest> findPendingOverdueRequestsForUpdate(
            @Param("status") RequestStatus status,
            @Param("now") OffsetDateTime now
    );

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE AccessRequest ar SET ar.status = :newStatus, ar.updatedAt = :now " +
           "WHERE ar.id IN :ids AND ar.status = :currentStatus")
    int expireOverdueRequestsByIds(
            @Param("ids") Collection<UUID> ids,
            @Param("currentStatus") RequestStatus currentStatus,
            @Param("newStatus") RequestStatus newStatus,
            @Param("now") OffsetDateTime now
    );

    /** D-2.1: khoá các đơn APPROVED đã qua giờ kết thúc (end_time <= now) để job hệ thống chuyển sang FINISHED. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ar FROM AccessRequest ar " +
           "WHERE ar.status = :status AND ar.endTime <= :now")
    List<AccessRequest> findApprovedElapsedRequestsForUpdate(
            @Param("status") RequestStatus status,
            @Param("now") OffsetDateTime now
    );

    /** D-2.1: chỉ update theo danh sách id đã khoá, còn APPROVED; hệ thống kết thúc nên finished_by = NULL. */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE AccessRequest ar SET ar.status = :newStatus, ar.finishReason = :finishReason, " +
           "ar.finishedBy = NULL, ar.updatedAt = :now " +
           "WHERE ar.id IN :ids AND ar.status = :currentStatus")
    int finishElapsedRequestsByIds(
            @Param("ids") Collection<UUID> ids,
            @Param("currentStatus") RequestStatus currentStatus,
            @Param("newStatus") RequestStatus newStatus,
            @Param("finishReason") String finishReason,
            @Param("now") OffsetDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AccessRequest r " +
           "SET r.status = :newStatus, " +
           "    r.reviewer = :reviewer, " +
           "    r.reviewedAt = :reviewedAt, " +
           "    r.rejectionReason = :rejectionReason, " +
           "    r.updatedAt = :reviewedAt " +
           "WHERE r.id = :id AND r.status = :expectedStatus AND (r.startTime IS NULL OR r.startTime > :reviewedAt)")
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

    /**
     * BR-RQ-47: FM huỷ đơn APPROVED chưa bắt đầu — conditional UPDATE nguyên tử (cùng cách cancelIfPending):
     * 1 dòng = giành được quyền huỷ; 0 dòng = đơn đã đổi trạng thái hoặc đã tới giờ bắt đầu.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AccessRequest r " +
           "SET r.status = :newStatus, " +
           "    r.updatedAt = :now " +
           "WHERE r.id = :id AND r.status = :expectedStatus AND r.startTime > :now")
    int cancelIfApprovedNotStarted(
            @Param("id") UUID id,
            @Param("newStatus") RequestStatus newStatus,
            @Param("now") OffsetDateTime now,
            @Param("expectedStatus") RequestStatus expectedStatus
    );

    /**
     * BR-RQ-46: FM hoàn thành đơn APPROVED đã tới giờ bắt đầu — conditional UPDATE nguyên tử (cùng cách cancelIfApprovedNotStarted):
     * 1 dòng = giành được quyền hoàn thành; 0 dòng = đơn đã đổi trạng thái (FM khác hoàn thành, hệ thống huỷ...) hoặc chưa tới giờ.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AccessRequest r " +
           "SET r.status = :newStatus, " +
           "    r.updatedAt = :now, " +
           "    r.finishReason = :finishReason, " +
           "    r.finishedBy = :finishedBy " +
           "WHERE r.id = :id AND r.status = :expectedStatus AND (r.startTime IS NULL OR r.startTime <= :now)")
    int finishIfApprovedStarted(
            @Param("id") UUID id,
            @Param("newStatus") RequestStatus newStatus,
            @Param("now") OffsetDateTime now,
            @Param("expectedStatus") RequestStatus expectedStatus,
            @Param("finishReason") String finishReason,
            @Param("finishedBy") User finishedBy
    );

    /**
     * Step 5b (BR-TC-15): ghi nguồn huỷ sau khi cancelIfPending đã thành công (chỉ chạm đơn đã CANCELLED).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AccessRequest r " +
           "SET r.cancelSource = :source, " +
           "    r.cancelledBy = :cancelledBy, " +
           "    r.cancelReason = :reason " +
           "WHERE r.id = :id AND r.status = :cancelledStatus")
    int recordCancellation(
            @Param("id") UUID id,
            @Param("source") com.fa26se040.icss.enums.CancelSource source,
            @Param("cancelledBy") User cancelledBy,
            @Param("reason") String reason,
            @Param("cancelledStatus") RequestStatus cancelledStatus
    );

    /**
     * Step 5b (BR-TC-08, BR-TC-14): hệ thống huỷ đơn khi đổi loại khu vực — conditional UPDATE nguyên tử.
     * Không clear persistence context: transaction đổi loại còn giữ entity Area đã khoá.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE AccessRequest r " +
           "SET r.status = :cancelledStatus, " +
           "    r.updatedAt = :now, " +
           "    r.cancelSource = :source, " +
           "    r.cancelledBy = NULL, " +
           "    r.cancelReason = :reason " +
           "WHERE r.id = :id AND r.status = :expectedStatus")
    int cancelBySystemIfStatus(
            @Param("id") UUID id,
            @Param("expectedStatus") RequestStatus expectedStatus,
            @Param("cancelledStatus") RequestStatus cancelledStatus,
            @Param("source") com.fa26se040.icss.enums.CancelSource source,
            @Param("reason") String reason,
            @Param("now") OffsetDateTime now
    );

    /**
     * Step 5b (BR-TC-03, BR-TC-08): đơn còn hiệu lực của khu vực (status trong :statuses, chưa kết thúc),
     * kèm người gửi và thành viên để đánh giá quy tắc của loại mới.
     */
    @Query("SELECT DISTINCT r FROM AccessRequest r " +
           "JOIN FETCH r.requester " +
           "LEFT JOIN FETCH r.members m " +
           "LEFT JOIN FETCH m.user " +
           "WHERE r.area.id = :areaId AND r.status IN :statuses AND r.endTime > :now " +
           "ORDER BY r.startTime ASC")
    List<AccessRequest> findNotEndedByAreaWithParticipants(
            @Param("areaId") UUID areaId,
            @Param("statuses") Collection<RequestStatus> statuses,
            @Param("now") OffsetDateTime now
    );

    /**
     * BR-RQ-SP-05: đơn chưa kết thúc (status thuộc :statuses, end_time > :now) mà :requesterId là NGƯỜI TẠO,
     * kèm khu vực và thành viên để ghi audit / gửi thông báo khi hệ thống huỷ.
     */
    @Query("SELECT DISTINCT r FROM AccessRequest r " +
           "JOIN FETCH r.requester " +
           "JOIN FETCH r.area " +
           "LEFT JOIN FETCH r.members m " +
           "LEFT JOIN FETCH m.user " +
           "WHERE r.requester.id = :requesterId AND r.status IN :statuses AND r.endTime > :now " +
           "ORDER BY r.startTime ASC")
    List<AccessRequest> findNotEndedByRequesterWithParticipants(
            @Param("requesterId") UUID requesterId,
            @Param("statuses") Collection<RequestStatus> statuses,
            @Param("now") OffsetDateTime now
    );

    /**
     * Chỉ đọc, dùng cho AccessDecisionService#checkEntry: đơn có status = :status tại :areaId,
     * startTime <= :at < endTime, và user là requester hoặc là member của đơn nhóm.
     */
    @Query("SELECT ar FROM AccessRequest ar " +
           "WHERE ar.area.id = :areaId " +
           "AND ar.status = :status " +
           "AND ar.startTime <= :at " +
           "AND ar.endTime > :at " +
           "AND (ar.requester.id = :userId OR EXISTS (" +
           "    SELECT 1 FROM AccessRequestMember arm " +
           "    WHERE arm.accessRequest.id = ar.id AND arm.user.id = :userId" +
           ")) " +
           "ORDER BY ar.startTime ASC")
    List<AccessRequest> findCoveringRequestsForUser(
            @Param("userId") UUID userId,
            @Param("areaId") UUID areaId,
            @Param("at") OffsetDateTime at,
            @Param("status") RequestStatus status
    );

    /**
     * Dùng để phân biệt Sai giờ (ACCESS_OUT_OF_TIME) với Sai quyền (UNAUTHORIZED_ACCESS):
     * Tìm tất cả các đơn APPROVED của user tại khu vực này bất kể thời gian.
     */
    @Query("SELECT ar FROM AccessRequest ar " +
           "WHERE ar.area.id = :areaId " +
           "AND ar.status = :status " +
           "AND (ar.requester.id = :userId OR EXISTS (" +
           "    SELECT 1 FROM AccessRequestMember arm " +
           "    WHERE arm.accessRequest.id = ar.id AND arm.user.id = :userId" +
           ")) " +
           "ORDER BY ar.startTime DESC")
    List<AccessRequest> findAnyApprovedRequestsForUser(
            @Param("userId") UUID userId,
            @Param("areaId") UUID areaId,
            @Param("status") RequestStatus status
    );

    /**
     * BR-AL-30: tên khu vực của mọi đơn PENDING mà người dùng là người tạo hoặc thành viên (mỗi đơn một dòng, theo giờ bắt đầu).
     */
    @Query(value = "SELECT a.name FROM access_requests r JOIN areas a ON a.id = r.area_id "
            + "WHERE r.status = 'PENDING' AND (r.requester_id = :userId OR EXISTS ("
            + "SELECT 1 FROM access_request_members m WHERE m.access_request_id = r.id AND m.user_id = :userId)) "
            + "ORDER BY r.start_time, r.id", nativeQuery = true)
    List<String> findPendingAreaNamesForParticipant(@Param("userId") UUID userId);
}
