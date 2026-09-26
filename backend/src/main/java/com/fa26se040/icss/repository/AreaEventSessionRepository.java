package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AreaEventSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AreaEventSessionRepository extends JpaRepository<AreaEventSession, UUID> {

    Optional<AreaEventSession> findByAreaIdAndActualEndIsNull(UUID areaId);

    Optional<AreaEventSession> findTopByAreaIdOrderByStartedAtDesc(UUID areaId);

    List<AreaEventSession> findByAreaIdAndActualEndIsNullAndPlannedEndLessThanEqual(UUID areaId, OffsetDateTime now);

    @Query("""
        SELECT s FROM AreaEventSession s
        WHERE s.actualEnd IS NULL
          AND s.plannedEnd > :now
          AND s.plannedEnd <= :threshold
          AND s.expiryRemindedAt IS NULL
        """)
    List<AreaEventSession> findSessionsNearingExpiry(
            @Param("now") OffsetDateTime now,
            @Param("threshold") OffsetDateTime threshold
    );

    @Query("""
        SELECT s FROM AreaEventSession s
        WHERE s.area.id = :areaId
          AND s.startedAt < :windowEnd
          AND (
            (s.actualEnd IS NOT NULL AND s.actualEnd > :windowStart)
            OR (s.actualEnd IS NULL AND s.plannedEnd > :windowStart)
          )
        ORDER BY s.startedAt ASC
        """)
    List<AreaEventSession> findSessionsInWindow(
            @Param("areaId") UUID areaId,
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd
    );
}
