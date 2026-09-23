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

    boolean existsByCodeAndDeletedAtIsNull(String code);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    @Query("SELECT COUNT(a) > 0 FROM Area a " +
            "WHERE LOWER(COALESCE(TRIM(a.name), '')) = LOWER(COALESCE(TRIM(:name), '')) " +
            "AND LOWER(COALESCE(TRIM(a.building), '')) = LOWER(COALESCE(TRIM(:building), '')) " +
            "AND LOWER(COALESCE(TRIM(a.floor), '')) = LOWER(COALESCE(TRIM(:floor), '')) " +
            "AND a.deletedAt IS NULL")
    boolean existsByNameAndBuildingAndFloor(
            @Param("name") String name,
            @Param("building") String building,
            @Param("floor") String floor
    );

    @Query("SELECT COUNT(a) > 0 FROM Area a " +
            "WHERE a.id != :id " +
            "AND LOWER(COALESCE(TRIM(a.name), '')) = LOWER(COALESCE(TRIM(:name), '')) " +
            "AND LOWER(COALESCE(TRIM(a.building), '')) = LOWER(COALESCE(TRIM(:building), '')) " +
            "AND LOWER(COALESCE(TRIM(a.floor), '')) = LOWER(COALESCE(TRIM(:floor), '')) " +
            "AND a.deletedAt IS NULL")
    boolean existsByNameAndBuildingAndFloorExcludingId(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("building") String building,
            @Param("floor") String floor
    );

    Optional<Area> findByIdAndDeletedAtIsNull(UUID id);

    java.util.List<Area> findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(String building, String floor);

    @Query("SELECT a FROM Area a WHERE a.deletedAt IS NULL AND a.areaLevel IN :levels ORDER BY a.building ASC, a.floor ASC, a.name ASC")
    java.util.List<Area> findAvailableForRequest(@Param("levels") Collection<AreaLevel> levels);

    @Query(
        value = """
            SELECT a FROM Area a
            WHERE (:isActive IS NULL
                   OR (:isActive = true  AND a.deletedAt IS NULL)
                   OR (:isActive = false AND a.deletedAt IS NOT NULL))
              AND (:areaLevel IS NULL OR a.areaLevel = :areaLevel)
              AND (CAST(:building AS string) IS NULL OR LOWER(a.building) = LOWER(CAST(:building AS string)))
              AND (CAST(:keyword AS string) IS NULL OR (
                    LOWER(a.code) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) OR
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
                    LOWER(a.code) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')) OR
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
