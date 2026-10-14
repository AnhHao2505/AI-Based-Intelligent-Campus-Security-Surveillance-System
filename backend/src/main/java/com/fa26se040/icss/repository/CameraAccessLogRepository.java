package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.enums.AccessResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface CameraAccessLogRepository extends JpaRepository<CameraAccessLog, UUID> {

    @Query("SELECT l FROM CameraAccessLog l " +
           "WHERE l.cameraCode = :cameraCode " +
           "AND (:accessResult IS NULL OR l.accessResult = :accessResult) " +
           "AND (:search IS NULL OR LOWER(l.userCode) LIKE :search OR LOWER(l.fullName) LIKE :search) " +
           "AND (cast(:from as timestamp) IS NULL OR l.loggedAt >= :from) " +
           "AND (cast(:to as timestamp) IS NULL OR l.loggedAt <= :to) " +
           "ORDER BY l.loggedAt DESC")
    Page<CameraAccessLog> findFiltered(
            @Param("cameraCode") String cameraCode,
            @Param("accessResult") AccessResult accessResult,
            @Param("search") String search,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable
    );

    long countByCameraCodeAndLoggedAtBetween(String cameraCode, OffsetDateTime from, OffsetDateTime to);

    long countByCameraCodeAndLoggedAtBetweenAndAccessResult(
            String cameraCode,
            OffsetDateTime from,
            OffsetDateTime to,
            AccessResult accessResult
    );
}
