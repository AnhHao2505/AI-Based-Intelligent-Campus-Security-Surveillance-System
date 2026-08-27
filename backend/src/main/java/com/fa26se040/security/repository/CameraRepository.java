package com.fa26se040.security.repository;

import com.fa26se040.security.entity.Camera;
import com.fa26se040.security.entity.CameraStatus;
import com.fa26se040.security.entity.OperationalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraRepository extends JpaRepository<Camera, UUID> {
    boolean existsByCameraCode(String cameraCode);

    Optional<Camera> findTopByCameraCodeStartingWithOrderByCameraCodeDesc(String prefix);

    @Query("SELECT c FROM Camera c WHERE c.deletedAt IS NULL " +
           "AND (:search IS NULL OR LOWER(c.cameraCode) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(c.zoneName) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "AND (:status IS NULL OR c.status = :status) " +
           "AND (:opStatus IS NULL OR c.operationalStatus = :opStatus)")
    Page<Camera> findFiltered(@Param("search") String search,
                              @Param("status") CameraStatus status,
                              @Param("opStatus") OperationalStatus opStatus,
                              Pageable pageable);
}
