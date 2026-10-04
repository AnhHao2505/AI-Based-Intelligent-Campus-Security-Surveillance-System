package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.GuardLocation;
import com.fa26se040.icss.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuardLocationRepository extends JpaRepository<GuardLocation, UUID> {

    Optional<GuardLocation> findByGuardId(UUID guardId);

    Optional<GuardLocation> findByGuard(User guard);

    @Query("SELECT gl FROM GuardLocation gl JOIN FETCH gl.guard g WHERE gl.isInsideGeofence = true AND gl.updatedAt >= :threshold AND g.isActive = true")
    List<GuardLocation> findGuardsInsideGeofenceSince(@Param("threshold") OffsetDateTime threshold);

    @Query("SELECT gl FROM GuardLocation gl JOIN FETCH gl.guard g WHERE gl.updatedAt >= :threshold AND g.isActive = true ORDER BY gl.updatedAt DESC")
    List<GuardLocation> findActiveLocationsSince(@Param("threshold") OffsetDateTime threshold);

    @Query("SELECT gl FROM GuardLocation gl JOIN FETCH gl.guard g WHERE g.isActive = true ORDER BY gl.updatedAt DESC")
    List<GuardLocation> findAllGuardLocations();
}
