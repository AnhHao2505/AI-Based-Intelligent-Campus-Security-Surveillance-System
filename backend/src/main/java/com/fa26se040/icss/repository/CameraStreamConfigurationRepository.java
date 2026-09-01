package com.fa26se040.icss.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fa26se040.icss.entity.CameraStreamConfiguration;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraStreamConfigurationRepository extends JpaRepository<CameraStreamConfiguration, UUID> {
    Optional<CameraStreamConfiguration> findByCameraId(UUID cameraId);
}
