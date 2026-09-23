package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuardTeamDispatch;
import com.fa26se040.icss.enums.GuardDispatchStatus;
import com.fa26se040.icss.enums.ShiftType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuardTeamDispatchRepository extends JpaRepository<GuardTeamDispatch, UUID> {

    @Query("SELECT d FROM GuardTeamDispatch d JOIN FETCH d.guard g JOIN FETCH d.toTeam t LEFT JOIN FETCH d.fromTeam f " +
           "WHERE d.status = :status AND d.startDate <= :date AND d.endDate >= :date")
    List<GuardTeamDispatch> findDispatchesOnDate(
            @Param("date") LocalDate date,
            @Param("status") GuardDispatchStatus status
    );

    @Query("SELECT d FROM GuardTeamDispatch d JOIN FETCH d.guard g JOIN FETCH d.toTeam t LEFT JOIN FETCH d.fromTeam f " +
           "WHERE d.status = :status AND d.startDate <= :endDate AND d.endDate >= :startDate")
    List<GuardTeamDispatch> findDispatchesInDateRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("status") GuardDispatchStatus status
    );

    @Query("SELECT d FROM GuardTeamDispatch d JOIN FETCH d.guard g JOIN FETCH d.toTeam t LEFT JOIN FETCH d.fromTeam f " +
           "WHERE d.guard.id = :guardId AND d.status = 'ACTIVE' AND d.startDate <= :date AND d.endDate >= :date " +
           "AND (:shiftType IS NULL OR d.shiftType IS NULL OR d.shiftType = :shiftType)")
    Optional<GuardTeamDispatch> findActiveDispatchForGuardOnDateAndShift(
            @Param("guardId") UUID guardId,
            @Param("date") LocalDate date,
            @Param("shiftType") ShiftType shiftType
    );

    @Query("SELECT d FROM GuardTeamDispatch d JOIN FETCH d.guard g JOIN FETCH d.toTeam t LEFT JOIN FETCH d.fromTeam f " +
           "WHERE d.toTeam.id = :teamId AND d.status = 'ACTIVE' AND d.endDate >= :today")
    List<GuardTeamDispatch> findActiveDispatchesByToTeamId(
            @Param("teamId") UUID teamId,
            @Param("today") LocalDate today
    );

    List<GuardTeamDispatch> findAllByStatusAndEndDateBefore(GuardDispatchStatus status, LocalDate date);

    List<GuardTeamDispatch> findByToTeamIdAndStatus(UUID toTeamId, GuardDispatchStatus status);

    @Query("SELECT d FROM GuardTeamDispatch d " +
           "WHERE d.guard.id = :guardId AND d.status = 'ACTIVE' " +
           "AND d.startDate <= :endDate AND d.endDate >= :startDate " +
           "AND (:shiftType IS NULL OR d.shiftType IS NULL OR d.shiftType = :shiftType)")
    List<GuardTeamDispatch> findOverlappingDispatches(
            @Param("guardId") UUID guardId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("shiftType") ShiftType shiftType
    );
}
