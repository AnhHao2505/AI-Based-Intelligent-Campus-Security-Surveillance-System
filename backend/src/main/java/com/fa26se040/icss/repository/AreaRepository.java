package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.enums.AreaLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AreaRepository extends JpaRepository<Area, UUID> {

    boolean existsByCodeAndDeletedAtIsNull(String code);

    Optional<Area> findByIdAndDeletedAtIsNull(UUID id);

    java.util.List<Area> findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(String building, String floor);

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
}
