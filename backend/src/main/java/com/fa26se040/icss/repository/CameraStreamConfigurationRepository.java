package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CameraStreamConfigurationRepository extends JpaRepository<CameraStreamConfiguration, UUID> {
    Optional<CameraStreamConfiguration> findByCameraId(UUID cameraId);

    @Query("""
            SELECT COUNT(cfg) > 0
            FROM CameraStreamConfiguration cfg
            JOIN cfg.camera c
            WHERE c.id <> :cameraId
              AND c.deletedAt IS NULL
              AND c.status <> :decommissionedStatus
              AND LOWER(TRIM(cfg.host)) = LOWER(TRIM(:host))
              AND cfg.port = :port
              AND (
                  CASE
                      WHEN LOWER(TRIM(cfg.mainStreamPath)) LIKE 'rtsp://%'
                        OR LOWER(TRIM(cfg.mainStreamPath)) LIKE 'rtsps://%'
                        OR TRIM(cfg.mainStreamPath) LIKE '/%'
                          THEN TRIM(cfg.mainStreamPath)
                      ELSE CONCAT('/', TRIM(cfg.mainStreamPath))
                  END
              ) = :normalizedMainStreamPath
            """)
    boolean existsActiveRtspConflict(
            @Param("cameraId") UUID cameraId,
            @Param("host") String host,
            @Param("port") Integer port,
            @Param("normalizedMainStreamPath") String normalizedMainStreamPath,
            @Param("decommissionedStatus") CameraStatus decommissionedStatus
    );
}
