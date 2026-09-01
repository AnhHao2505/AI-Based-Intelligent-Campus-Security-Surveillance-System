package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AreaTemporaryUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AreaTemporaryUsageRepository extends JpaRepository<AreaTemporaryUsage, UUID> {

    @Query("""
        SELECT count(u) > 0 FROM AreaTemporaryUsage u
        WHERE u.area.id = :areaId
          AND u.startTime < :endTime
          AND u.endTime > :startTime
    """)
    boolean existsOverlappingUsage(
            @Param("areaId") UUID areaId,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime
    );

    @Query("""
        SELECT count(u) > 0 FROM AreaTemporaryUsage u
        WHERE u.area.id = :areaId
          AND u.id <> :excludeId
          AND u.startTime < :endTime
          AND u.endTime > :startTime
    """)
    boolean existsOverlappingUsageExcludingId(
            @Param("areaId") UUID areaId,
            @Param("excludeId") UUID excludeId,
            @Param("startTime") OffsetDateTime startTime,
            @Param("endTime") OffsetDateTime endTime
    );

    List<AreaTemporaryUsage> findByAreaIdOrderByStartTimeAsc(UUID areaId);
}
