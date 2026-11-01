package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.SecurityIncident;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Truy vấn trên security_incidents phục vụ phân phối theo đợt (V93, K4–K6).
 *
 * <p>Kế thừa {@link Repository} (không phải JpaRepository) nên không có save/delete và không thay thế
 * {@link SecurityIncidentRepository}. Chỉ ghi duy nhất cột dispatch_stage / broadcast_at qua conditional UPDATE.</p>
 */
public interface IncidentDispatchQueryRepository extends Repository<SecurityIncident, UUID> {

    /** BR-DSP-03: số sự cố mỗi bảo vệ đã nhận từ {@code since}. Kết quả: [guardId, count]. */
    @Query("SELECT i.claimedBy.id, COUNT(i) FROM SecurityIncident i " +
           "WHERE i.claimedBy.id IN :guardIds AND i.claimedAt >= :since " +
           "GROUP BY i.claimedBy.id")
    List<Object[]> countClaimsSince(@Param("guardIds") Collection<UUID> guardIds,
                                    @Param("since") OffsetDateTime since);

    /** BR-DSP-03: lần nhận gần nhất của mỗi bảo vệ. Kết quả: [guardId, maxClaimedAt]. */
    @Query("SELECT i.claimedBy.id, MAX(i.claimedAt) FROM SecurityIncident i " +
           "WHERE i.claimedBy.id IN :guardIds " +
           "GROUP BY i.claimedBy.id")
    List<Object[]> findLastClaimTimes(@Param("guardIds") Collection<UUID> guardIds);

    /** BR-DSP-02: bảo vệ đang giữ sự cố CLAIMED (chưa xử lý xong). */
    @Query("SELECT DISTINCT i.claimedBy.id FROM SecurityIncident i " +
           "WHERE i.status = com.fa26se040.icss.enums.IncidentStatus.CLAIMED AND i.claimedBy IS NOT NULL")
    List<UUID> findGuardIdsHoldingClaimed();

    /** K4 bước 4: sự cố còn NEW đang ở bước 1, cũ nhất trước. */
    @Query("SELECT i.id FROM SecurityIncident i " +
           "WHERE i.dispatchStage = com.fa26se040.icss.enums.IncidentDispatchStage.STEP1 " +
           "AND i.status = com.fa26se040.icss.enums.IncidentStatus.NEW " +
           "ORDER BY i.createdAt ASC")
    List<UUID> findStep1NewIncidentIds();

    /**
     * K4 bước 4: STEP1 -> BROADCAST. 1 dòng = lần gọi này giành được việc phát broadcast; 0 dòng = đã chuyển trước đó.
     * Không tăng version của sự cố, nên không xung đột với optimistic lock khi claim.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE SecurityIncident i " +
           "SET i.dispatchStage = com.fa26se040.icss.enums.IncidentDispatchStage.BROADCAST, i.broadcastAt = :now " +
           "WHERE i.id = :id AND i.dispatchStage = com.fa26se040.icss.enums.IncidentDispatchStage.STEP1")
    int promoteToBroadcast(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    /**
     * K5 / E2: tên người đã nhận sự cố, đọc thẳng từ DB. Dùng sau khi claim thua optimistic lock: entity trong
     * persistence context đã bị gán người claim của transaction hiện tại nên không dùng được. Flush mode COMMIT để truy
     * vấn không flush lại entity đang lỗi.
     */
    @Query("SELECT u.fullName FROM SecurityIncident i JOIN i.claimedBy u WHERE i.id = :id")
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    Optional<String> findClaimerFullName(@Param("id") UUID id);
}
