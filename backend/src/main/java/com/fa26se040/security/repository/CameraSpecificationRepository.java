package com.fa26se040.security.repository;

import com.fa26se040.security.entity.CameraSpecification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraSpecificationRepository extends JpaRepository<CameraSpecification, UUID> {
    Optional<CameraSpecification> findByCameraId(UUID cameraId);
}
