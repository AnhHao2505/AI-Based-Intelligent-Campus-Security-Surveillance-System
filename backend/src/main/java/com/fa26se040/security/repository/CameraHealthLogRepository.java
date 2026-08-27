package com.fa26se040.security.repository;

import com.fa26se040.security.entity.CameraHealthLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CameraHealthLogRepository extends JpaRepository<CameraHealthLog, UUID> {
    Page<CameraHealthLog> findByCameraIdOrderByCheckedAtDesc(UUID cameraId, Pageable pageable);
}
