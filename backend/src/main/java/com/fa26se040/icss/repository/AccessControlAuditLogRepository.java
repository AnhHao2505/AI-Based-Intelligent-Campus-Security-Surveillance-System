package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessControlAuditLog;
import com.fa26se040.icss.enums.AccessControlTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface AccessControlAuditLogRepository extends JpaRepository<AccessControlAuditLog, UUID> {

    @Query(
        value = """
            SELECT l FROM AccessControlAuditLog l
            LEFT JOIN FETCH l.changedBy cb
            LEFT JOIN FETCH l.subjectUser su
            LEFT JOIN FETCH l.area a
            WHERE (:targetType IS NULL OR l.targetType = :targetType)
              AND (:areaId IS NULL OR l.area.id = :areaId)
              AND (:subjectUserId IS NULL OR l.subjectUser.id = :subjectUserId)
              AND (:changedBy IS NULL OR l.changedBy.id = :changedBy)
              AND (:fromDate IS NULL OR l.changedAt >= :fromDate)
              AND (:toDate IS NULL OR l.changedAt <= :toDate)
            ORDER BY l.changedAt DESC
        """,
        countQuery = """
            SELECT COUNT(l) FROM AccessControlAuditLog l
            WHERE (:targetType IS NULL OR l.targetType = :targetType)
              AND (:areaId IS NULL OR l.area.id = :areaId)
              AND (:subjectUserId IS NULL OR l.subjectUser.id = :subjectUserId)
              AND (:changedBy IS NULL OR l.changedBy.id = :changedBy)
              AND (:fromDate IS NULL OR l.changedAt >= :fromDate)
              AND (:toDate IS NULL OR l.changedAt <= :toDate)
        """
    )
    Page<AccessControlAuditLog> searchLogs(
            @Param("targetType") AccessControlTargetType targetType,
            @Param("areaId") UUID areaId,
            @Param("subjectUserId") UUID subjectUserId,
            @Param("changedBy") UUID changedBy,
            @Param("fromDate") OffsetDateTime fromDate,
            @Param("toDate") OffsetDateTime toDate,
            Pageable pageable
    );
}
