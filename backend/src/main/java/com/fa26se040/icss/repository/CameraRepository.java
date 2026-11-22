package com.fa26se040.icss.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraRepository extends JpaRepository<Camera, UUID> {
    boolean existsByCameraCode(String cameraCode);
    Optional<Camera> findByCameraCode(String cameraCode);
    @Query("SELECT c FROM Camera c LEFT JOIN FETCH c.area WHERE c.cameraCode = :cameraCode AND c.deletedAt IS NULL")
    Optional<Camera> findByCameraCodeAndDeletedAtIsNull(@Param("cameraCode") String cameraCode);

    @Query("SELECT c FROM Camera c LEFT JOIN FETCH c.area WHERE c.cameraCode IN :cameraCodes")
    List<Camera> findByCameraCodeIn(@Param("cameraCodes") java.util.Collection<String> cameraCodes);

    Optional<Camera> findTopByCameraCodeStartingWithOrderByCameraCodeDesc(String prefix);

    @Query(value = "SELECT c FROM Camera c LEFT JOIN FETCH c.area WHERE " +
           "c.deletedAt IS NULL " +
           "AND (LOWER(c.cameraCode) LIKE :search OR LOWER(c.name) LIKE :search) " +
           "AND (:status IS NULL OR c.status = :status) " +
           "AND (:opStatus IS NULL OR c.operationalStatus = :opStatus)",
           countQuery = "SELECT COUNT(c) FROM Camera c WHERE " +
           "c.deletedAt IS NULL " +
           "AND (LOWER(c.cameraCode) LIKE :search OR LOWER(c.name) LIKE :search) " +
           "AND (:status IS NULL OR c.status = :status) " +
           "AND (:opStatus IS NULL OR c.operationalStatus = :opStatus)")
    Page<Camera> findFiltered(@Param("search") String search,
                              @Param("status") CameraStatus status,
                              @Param("opStatus") OperationalStatus opStatus,
                              Pageable pageable);

    List<Camera> findByStatusAndDeletedAtIsNullOrderByCameraCodeAsc(CameraStatus status);

    @Query("SELECT DISTINCT c FROM Camera c LEFT JOIN FETCH c.streamConfiguration WHERE c.status = :status AND c.deletedAt IS NULL ORDER BY c.cameraCode ASC")
    List<Camera> findActiveWithStreamConfigByStatus(@Param("status") CameraStatus status);

    List<Camera> findByStatus(CameraStatus status);

    @Query(value = "SELECT nextval('camera_code_seq')", nativeQuery = true)
    Long getNextCameraCodeSequence();

    List<Camera> findByAreaIdAndDeletedAtIsNull(UUID areaId);

    int countByAreaIdAndDeletedAtIsNull(UUID areaId);

}
