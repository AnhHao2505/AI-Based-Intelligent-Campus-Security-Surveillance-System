package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.enums.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecurityIncidentRepository extends JpaRepository<SecurityIncident, UUID> {

    @Query("SELECT i FROM SecurityIncident i WHERE i.status IN ('NEW', 'CLAIMED') " +
            "AND (:building IS NULL OR LOWER(i.building) = LOWER(:building)) " +
            "ORDER BY i.detectedAt DESC")
    List<SecurityIncident> findActiveIncidents(@Param("building") String building);

    @Query("SELECT i FROM SecurityIncident i WHERE " +
            "(:building IS NULL OR LOWER(i.building) = LOWER(:building)) AND " +
            "(:status IS NULL OR i.status = :status) " +
            "ORDER BY i.detectedAt DESC")
    List<SecurityIncident> findIncidents(
            @Param("building") String building,
            @Param("status") IncidentStatus status
    );

    Optional<SecurityIncident> findByEventId(String eventId);
}
