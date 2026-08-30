package com.fa26se040.icss.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fa26se040.icss.entity.CameraSpecification;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraSpecificationRepository extends JpaRepository<CameraSpecification, UUID> {
    Optional<CameraSpecification> findByCameraId(UUID cameraId);
}
