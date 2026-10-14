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

    @Query("SELECT i FROM SecurityIncident i " +
            "LEFT JOIN FETCH i.area " +
            "LEFT JOIN FETCH i.claimedBy " +
            "LEFT JOIN FETCH i.resolvedBy " +
            "WHERE i.status IN ('NEW', 'CLAIMED') " +
            "ORDER BY i.detectedAt DESC")
    List<SecurityIncident> findActiveIncidents();

    @Query("SELECT i FROM SecurityIncident i " +
            "LEFT JOIN FETCH i.area " +
            "LEFT JOIN FETCH i.claimedBy " +
            "LEFT JOIN FETCH i.resolvedBy " +
            "WHERE i.status IN ('NEW', 'CLAIMED') " +
            "AND LOWER(i.building) = LOWER(:building) " +
            "ORDER BY i.detectedAt DESC")
    List<SecurityIncident> findActiveIncidentsByBuilding(@Param("building") String building);

    default List<SecurityIncident> findActiveIncidents(String building) {
        if (building == null || building.isBlank()) {
            return findActiveIncidents();
        }
        return findActiveIncidentsByBuilding(building.trim());
    }

    @Query("SELECT i FROM SecurityIncident i " +
            "LEFT JOIN FETCH i.area " +
            "LEFT JOIN FETCH i.claimedBy " +
            "LEFT JOIN FETCH i.resolvedBy " +
            "ORDER BY i.detectedAt DESC")
    List<SecurityIncident> findAllIncidents();

    @Query("SELECT i FROM SecurityIncident i " +
            "LEFT JOIN FETCH i.area " +
            "LEFT JOIN FETCH i.claimedBy " +
            "LEFT JOIN FETCH i.resolvedBy " +
            "WHERE LOWER(i.building) = LOWER(:building) " +
            "ORDER BY i.detectedAt DESC")
    List<SecurityIncident> findIncidentsByBuilding(@Param("building") String building);

    @Query("SELECT i FROM SecurityIncident i " +
            "LEFT JOIN FETCH i.area " +
            "LEFT JOIN FETCH i.claimedBy " +
            "LEFT JOIN FETCH i.resolvedBy " +
            "WHERE i.status = :status " +
            "ORDER BY i.detectedAt DESC")
    List<SecurityIncident> findIncidentsByStatus(@Param("status") IncidentStatus status);

    @Query("SELECT i FROM SecurityIncident i " +
            "LEFT JOIN FETCH i.area " +
            "LEFT JOIN FETCH i.claimedBy " +
            "LEFT JOIN FETCH i.resolvedBy " +
            "WHERE LOWER(i.building) = LOWER(:building) AND i.status = :status " +
            "ORDER BY i.detectedAt DESC")
    List<SecurityIncident> findIncidentsByBuildingAndStatus(
            @Param("building") String building,
            @Param("status") IncidentStatus status
    );

    default List<SecurityIncident> findIncidents(String building, IncidentStatus status) {
        boolean hasBuilding = building != null && !building.isBlank();
        if (hasBuilding && status != null) {
            return findIncidentsByBuildingAndStatus(building.trim(), status);
        } else if (hasBuilding) {
            return findIncidentsByBuilding(building.trim());
        } else if (status != null) {
            return findIncidentsByStatus(status);
        } else {
            return findAllIncidents();
        }
    }

    Optional<SecurityIncident> findByEventId(String eventId);
}
