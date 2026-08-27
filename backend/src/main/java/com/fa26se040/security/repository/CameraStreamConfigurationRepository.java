package com.fa26se040.security.repository;

import com.fa26se040.security.entity.CameraStreamConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraStreamConfigurationRepository extends JpaRepository<CameraStreamConfiguration, UUID> {
    Optional<CameraStreamConfiguration> findByCameraId(UUID cameraId);
}
