package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.enums.AreaLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AreaRepository extends JpaRepository<Area, UUID> {

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    @Query("SELECT COUNT(a) > 0 FROM Area a " +
            "WHERE a.floorEntity.id = :floorId " +
            "AND LOWER(a.name) = LOWER(:name) " +
            "AND a.deletedAt IS NULL")
    boolean existsByFloorIdAndNameIgnoreCase(
            @Param("floorId") UUID floorId,
            @Param("name") String name
    );

    @Query("SELECT COUNT(a) > 0 FROM Area a " +
            "WHERE a.id != :id " +
            "AND a.floorEntity.id = :floorId " +
            "AND LOWER(a.name) = LOWER(:name) " +
            "AND a.deletedAt IS NULL")
    boolean existsByFloorIdAndNameIgnoreCaseExcludingId(
            @Param("id") UUID id,
            @Param("floorId") UUID floorId,
            @Param("name") String name
    );

    Optional<Area> findByIdAndDeletedAtIsNull(UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Area a WHERE a.id = :id")
    Optional<Area> findByIdWithLock(@Param("id") UUID id);

    java.util.List<Area> findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(String building, String floor);

    @Query("SELECT a FROM Area a WHERE a.deletedAt IS NULL AND a.areaLevel IN :levels ORDER BY a.building ASC, a.floor ASC, a.name ASC")
    java.util.List<Area> findAvailableForRequest(@Param("levels") Collection<AreaLevel> levels);

    java.util.List<Area> findByOpenToMembersTrueAndOpenUntilAfterAndDeletedAtIsNull(java.time.OffsetDateTime now);

    @Query(
        value = """
            SELECT a FROM Area a
            WHERE (:isActive IS NULL
                   OR (:isActive = true  AND a.deletedAt IS NULL)
                   OR (:isActive = false AND a.deletedAt IS NOT NULL))
              AND (:areaLevel IS NULL OR a.areaLevel = :areaLevel)
              AND (CAST(:building AS string) IS NULL OR LOWER(a.building) = LOWER(CAST(:building AS string)))
              AND (CAST(:keyword AS string) IS NULL OR (
                    LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                  ))
            """,
        countQuery = """
            SELECT COUNT(a) FROM Area a
            WHERE (:isActive IS NULL
                   OR (:isActive = true  AND a.deletedAt IS NULL)
                   OR (:isActive = false AND a.deletedAt IS NOT NULL))
              AND (:areaLevel IS NULL OR a.areaLevel = :areaLevel)
              AND (CAST(:building AS string) IS NULL OR LOWER(a.building) = LOWER(CAST(:building AS string)))
              AND (CAST(:keyword AS string) IS NULL OR (
                    LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                  ))
            """
    )
    Page<Area> searchAreas(
        @Param("keyword") String keyword,
        @Param("areaLevel") AreaLevel areaLevel,
        @Param("building") String building,
        @Param("isActive") Boolean isActive,
        Pageable pageable
    );

    @Query("SELECT a FROM Area a JOIN a.cameras c WHERE c.cameraCode = :cameraCode AND a.deletedAt IS NULL")
    java.util.List<Area> findAreasByCameraCode(@Param("cameraCode") String cameraCode);
}
