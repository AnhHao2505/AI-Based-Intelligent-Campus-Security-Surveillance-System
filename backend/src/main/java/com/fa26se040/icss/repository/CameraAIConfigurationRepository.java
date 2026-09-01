package com.fa26se040.icss.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fa26se040.icss.entity.CameraAIConfiguration;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraAIConfigurationRepository extends JpaRepository<CameraAIConfiguration, UUID> {
    Optional<CameraAIConfiguration> findByCameraId(UUID cameraId);
}
