package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AreaEventScheduleRepository extends JpaRepository<AreaEventSchedule, UUID> {

    @Query("""
        SELECT s FROM AreaEventSchedule s
        LEFT JOIN FETCH s.createdBy
        LEFT JOIN FETCH s.updatedBy
        LEFT JOIN FETCH s.cancelledBy
        WHERE s.area.id = :areaId
        ORDER BY s.startAt ASC
        """)
    List<AreaEventSchedule> findByAreaIdOrderByStartAtAsc(@Param("areaId") UUID areaId);

    @Query("""
        SELECT s FROM AreaEventSchedule s
        LEFT JOIN FETCH s.createdBy
        LEFT JOIN FETCH s.updatedBy
        LEFT JOIN FETCH s.cancelledBy
        WHERE s.area.id = :areaId AND s.status = :status
        ORDER BY s.startAt ASC
        """)
    List<AreaEventSchedule> findByAreaIdAndStatusOrderByStartAtAsc(
            @Param("areaId") UUID areaId,
            @Param("status") AreaEventScheduleStatus status
    );

    List<AreaEventSchedule> findByAreaIdAndStatus(UUID areaId, AreaEventScheduleStatus status);

    long countByAreaIdAndStatus(UUID areaId, AreaEventScheduleStatus status);

    boolean existsByAreaIdAndStatusAndStartAtLessThanEqualAndEndAtGreaterThan(
            UUID areaId,
            AreaEventScheduleStatus status,
            OffsetDateTime at1,
            OffsetDateTime at2
    );

    @Query("""
        SELECT s FROM AreaEventSchedule s
        JOIN FETCH s.area
        JOIN FETCH s.createdBy
        WHERE s.status = :status
          AND s.startAt <= :now
        ORDER BY s.startAt ASC
        """)
    List<AreaEventSchedule> findSchedulesReadyToStart(
            @Param("status") AreaEventScheduleStatus status,
            @Param("now") OffsetDateTime now
    );

    @Query("""
        SELECT s FROM AreaEventSchedule s
        JOIN FETCH s.area
        JOIN FETCH s.createdBy
        WHERE s.status = :status
          AND s.remindedAt IS NULL
          AND s.startAt <= :threshold
          AND s.startAt > :now
        ORDER BY s.startAt ASC
        """)
    List<AreaEventSchedule> findSchedulesToRemind(
            @Param("status") AreaEventScheduleStatus status,
            @Param("now") OffsetDateTime now,
            @Param("threshold") OffsetDateTime threshold
    );
}
