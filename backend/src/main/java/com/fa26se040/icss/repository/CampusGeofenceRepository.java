package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.CampusGeofence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampusGeofenceRepository extends JpaRepository<CampusGeofence, UUID> {

    Optional<CampusGeofence> findFirstByOrderByUpdatedAtDesc();
}
