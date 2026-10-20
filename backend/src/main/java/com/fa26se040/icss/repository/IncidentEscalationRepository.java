package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.enums.EscalationCloseReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentEscalationRepository extends JpaRepository<IncidentEscalation, UUID> {

    /** Bản ghi còn mở, cũ nhất trước (dùng index idx_incident_escalations_open). */
    @Query("SELECT e.id FROM IncidentEscalation e WHERE e.closedAt IS NULL ORDER BY e.createdAt ASC")
    List<UUID> findOpenIds();

    /** Bản ghi kèm sự cố + khu vực, đọc trong transaction của job (open-in-view: false). */
    @Query("SELECT e FROM IncidentEscalation e " +
           "JOIN FETCH e.incident i " +
           "LEFT JOIN FETCH i.area " +
           "WHERE e.id = :id")
    Optional<IncidentEscalation> findByIdWithIncident(@Param("id") UUID id);

    Optional<IncidentEscalation> findByIncidentId(UUID incidentId);

    /**
     * BR-IE-04 / BR-IE-07: chỉ một lần chạy giành được quyền báo FM. 1 dòng -> được gửi; 0 dòng -> đã đóng trước đó.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE IncidentEscalation e " +
           "SET e.fmEscalatedAt = :now, " +
           "    e.closedAt = :now, " +
           "    e.closeReason = com.fa26se040.icss.enums.EscalationCloseReason.ESCALATED " +
           "WHERE e.id = :id AND e.closedAt IS NULL")
    int markEscalatedIfOpen(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    /** BR-IE-06: sự cố rời NEW trước hạn -> đóng, không báo FM. */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE IncidentEscalation e " +
           "SET e.closedAt = :now, " +
           "    e.closeReason = :reason " +
           "WHERE e.id = :id AND e.closedAt IS NULL")
    int closeIfOpen(@Param("id") UUID id,
                    @Param("reason") EscalationCloseReason reason,
                    @Param("now") OffsetDateTime now);

    /**
     * S5 (V84): không có FM active -> chỉ một lần chạy giành được quyền báo ADMIN; bản ghi vẫn mở.
     * 1 dòng -> được gửi; 0 dòng -> đã báo ADMIN trước đó (hoặc bản ghi đã đóng).
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE IncidentEscalation e " +
           "SET e.adminNoFmNotifiedAt = :now " +
           "WHERE e.id = :id AND e.closedAt IS NULL AND e.adminNoFmNotifiedAt IS NULL")
    int markAdminNoFmNotifiedIfNot(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE IncidentEscalation e SET e.fmRecipientCount = :count WHERE e.id = :id")
    int updateFmRecipientCount(@Param("id") UUID id, @Param("count") int count);
}
