package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.IncidentDispatchOffer;
import com.fa26se040.icss.enums.DispatchOfferCancelReason;
import com.fa26se040.icss.enums.DispatchOfferResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lời mời bước 1 (V93, K3). Lời mời còn chờ = {@code response IS NULL}. Mọi chuyển trạng thái đi qua conditional UPDATE
 * {@code WHERE response IS NULL}: chỉ một bên (claim, decline, job) giành được một lời mời.
 */
@Repository
public interface IncidentDispatchOfferRepository extends JpaRepository<IncidentDispatchOffer, UUID> {

    /** Lời mời còn chờ của một sự cố, kèm bảo vệ. */
    @Query("SELECT o FROM IncidentDispatchOffer o JOIN FETCH o.guard " +
           "WHERE o.incident.id = :incidentId AND o.response IS NULL")
    List<IncidentDispatchOffer> findPendingByIncidentId(@Param("incidentId") UUID incidentId);

    /** Số lời mời còn chờ của một sự cố (BR-DSP-05). */
    @Query("SELECT COUNT(o) FROM IncidentDispatchOffer o WHERE o.incident.id = :incidentId AND o.response IS NULL")
    long countPendingByIncidentId(@Param("incidentId") UUID incidentId);

    /** Bảo vệ đang có lời mời chờ ở bất kỳ sự cố nào (E5: loại khỏi bước 1 của sự cố khác). */
    @Query("SELECT DISTINCT o.guard.id FROM IncidentDispatchOffer o WHERE o.response IS NULL")
    List<UUID> findGuardIdsWithPendingOffer();

    /** BR-DSP-06: bảo vệ có lời mời còn chờ và chưa hết hạn cho sự cố này. */
    @Query("SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END FROM IncidentDispatchOffer o " +
           "WHERE o.incident.id = :incidentId AND o.guard.id = :guardId " +
           "AND o.response IS NULL AND o.expiresAt > :now")
    boolean existsActivePendingOffer(@Param("incidentId") UUID incidentId,
                                     @Param("guardId") UUID guardId,
                                     @Param("now") OffsetDateTime now);

    /** Lời mời còn chờ đã tới hạn (K4 bước 1). */
    @Query("SELECT o FROM IncidentDispatchOffer o JOIN FETCH o.guard JOIN FETCH o.incident " +
           "WHERE o.response IS NULL AND o.expiresAt <= :now")
    List<IncidentDispatchOffer> findDuePending(@Param("now") OffsetDateTime now);

    /** Mọi lời mời còn chờ (K4 bước 2–3). */
    @Query("SELECT o FROM IncidentDispatchOffer o JOIN FETCH o.guard JOIN FETCH o.incident " +
           "WHERE o.response IS NULL")
    List<IncidentDispatchOffer> findAllPending();

    /** Đóng một lời mời còn chờ. 1 dòng = giành được; 0 dòng = đã đóng trước đó. */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE IncidentDispatchOffer o " +
           "SET o.response = :response, o.respondedAt = :now, o.cancelReason = :reason " +
           "WHERE o.id = :id AND o.response IS NULL")
    int respondIfPending(@Param("id") UUID id,
                         @Param("response") DispatchOfferResponse response,
                         @Param("reason") DispatchOfferCancelReason reason,
                         @Param("now") OffsetDateTime now);

    /** Bảo vệ nhận sự cố: lời mời còn chờ của chính người đó -> ACCEPTED (K5). */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE IncidentDispatchOffer o " +
           "SET o.response = com.fa26se040.icss.enums.DispatchOfferResponse.ACCEPTED, o.respondedAt = :now " +
           "WHERE o.incident.id = :incidentId AND o.guard.id = :guardId AND o.response IS NULL")
    int acceptIfPending(@Param("incidentId") UUID incidentId,
                        @Param("guardId") UUID guardId,
                        @Param("now") OffsetDateTime now);

    /** Bảo vệ từ chối: lời mời còn chờ, chưa hết hạn của chính người đó -> DECLINED (K5). */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE IncidentDispatchOffer o " +
           "SET o.response = com.fa26se040.icss.enums.DispatchOfferResponse.DECLINED, o.respondedAt = :now " +
           "WHERE o.incident.id = :incidentId AND o.guard.id = :guardId " +
           "AND o.response IS NULL AND o.expiresAt > :now")
    int declineIfPending(@Param("incidentId") UUID incidentId,
                         @Param("guardId") UUID guardId,
                         @Param("now") OffsetDateTime now);
}
