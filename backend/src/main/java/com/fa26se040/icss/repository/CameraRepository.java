package com.fa26se040.icss.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStatus;
import com.fa26se040.icss.entity.OperationalStatus;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraRepository extends JpaRepository<Camera, UUID> {
    boolean existsByCameraCode(String cameraCode);

    Optional<Camera> findTopByCameraCodeStartingWithOrderByCameraCodeDesc(String prefix);

    @Query("SELECT c FROM Camera c WHERE c.deletedAt IS NULL " +
           "AND (LOWER(c.cameraCode) LIKE :search OR LOWER(c.name) LIKE :search OR LOWER(c.zoneName) LIKE :search) " +
           "AND (:status IS NULL OR c.status = :status) " +
           "AND (:opStatus IS NULL OR c.operationalStatus = :opStatus)")
    Page<Camera> findFiltered(@Param("search") String search,
                              @Param("status") CameraStatus status,
                              @Param("opStatus") OperationalStatus opStatus,
                              Pageable pageable);
}
