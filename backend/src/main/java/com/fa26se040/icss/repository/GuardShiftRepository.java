package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.enums.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuardShiftRepository extends JpaRepository<GuardShift, UUID> {

    @Query("SELECT s FROM GuardShift s LEFT JOIN s.area a LEFT JOIN s.guard g LEFT JOIN g.team t WHERE s.shiftDate BETWEEN :startDate AND :endDate " +
            "AND (:guardId IS NULL OR g.id = :guardId) " +
            "AND (CAST(:building AS string) IS NULL OR LOWER(COALESCE(a.building, t.description)) = LOWER(CAST(:building AS string))) " +
            "AND (:status IS NULL OR s.status = :status) " +
            "ORDER BY s.shiftDate ASC, s.startTime ASC")
    List<GuardShift> findShifts(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("guardId") UUID guardId,
            @Param("building") String building,
            @Param("status") ShiftStatus status
    );

    List<GuardShift> findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
            UUID guardId, LocalDate startDate, LocalDate endDate
    );

    Optional<GuardShift> findByGuardIdAndShiftDateAndStatus(
            UUID guardId, LocalDate shiftDate, ShiftStatus status
    );

    boolean existsByGuardIdAndShiftDateAndStartTime(
            UUID guardId, LocalDate shiftDate, LocalTime startTime
    );

    @Query("SELECT s FROM GuardShift s LEFT JOIN s.area a LEFT JOIN s.guard g LEFT JOIN g.team t WHERE s.shiftDate = :date " +
            "AND LOWER(COALESCE(a.building, t.description)) = LOWER(:building) " +
            "AND s.status IN ('SCHEDULED', 'CHECKED_IN')")
    List<GuardShift> findActiveShiftsInBuilding(
            @Param("date") LocalDate date,
            @Param("building") String building
    );

    List<GuardShift> findByGuardIdAndShiftDateAndStartTimeLessThanOrderByStartTimeAsc(
            UUID guardId, LocalDate shiftDate, LocalTime startTime
    );

    List<GuardShift> findByStatusAndShiftDateLessThanEqual(
            ShiftStatus status, LocalDate shiftDate
    );

    List<GuardShift> findByGuardIdAndShiftDateAndStatusNot(UUID guardId, LocalDate shiftDate, ShiftStatus status);

    boolean existsByGuardIdAndShiftDateAndStatusNot(UUID guardId, LocalDate shiftDate, ShiftStatus status);

    List<GuardShift> findByShiftDate(LocalDate shiftDate);

    List<GuardShift> findByShiftDateBetween(LocalDate startDate, LocalDate endDate);
}
