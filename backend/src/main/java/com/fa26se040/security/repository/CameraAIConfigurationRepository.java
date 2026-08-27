package com.fa26se040.security.repository;

import com.fa26se040.security.entity.CameraAIConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraAIConfigurationRepository extends JpaRepository<CameraAIConfiguration, UUID> {
    Optional<CameraAIConfiguration> findByCameraId(UUID cameraId);
}
