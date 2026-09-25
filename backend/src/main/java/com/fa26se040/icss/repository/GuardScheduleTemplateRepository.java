package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuardScheduleTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface GuardScheduleTemplateRepository extends JpaRepository<GuardScheduleTemplate, UUID> {

    List<GuardScheduleTemplate> findByIsActiveTrue();

    List<GuardScheduleTemplate> findByGuardIdAndIsActiveTrue(UUID guardId);

    List<GuardScheduleTemplate> findByDayOfWeekAndIsActiveTrue(Integer dayOfWeek);

    @Query("SELECT t FROM GuardScheduleTemplate t LEFT JOIN t.area a LEFT JOIN t.guard g LEFT JOIN g.team team WHERE t.isActive = true " +
            "AND (CAST(:building AS string) IS NULL OR LOWER(COALESCE(a.building, team.description)) = LOWER(CAST(:building AS string)))")
    List<GuardScheduleTemplate> findActiveTemplatesByBuilding(@Param("building") String building);

    boolean existsByGuardIdAndDayOfWeekAndStartTimeAndIsActiveTrue(
            UUID guardId, Integer dayOfWeek, LocalTime startTime
    );
}
