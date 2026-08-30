package com.fa26se040.icss.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fa26se040.icss.entity.CameraHealthLog;

import java.util.UUID;

@Repository
public interface CameraHealthLogRepository extends JpaRepository<CameraHealthLog, UUID> {
    Page<CameraHealthLog> findByCameraIdOrderByCheckedAtDesc(UUID cameraId, Pageable pageable);
}
