package com.fa26se040.security.repository;

import com.fa26se040.security.entity.Area;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AreaRepository extends JpaRepository<Area, Integer> {

    boolean existsByCodeAndDeletedAtIsNull(String code);

    Optional<Area> findByIdAndDeletedAtIsNull(Integer id);

    @Query(
        value = """
            SELECT a FROM Area a
            JOIN FETCH a.areaLevel al
            WHERE a.deletedAt IS NULL
              AND (:isActive IS NULL OR a.isActive = :isActive)
              AND (:areaLevel IS NULL OR al.level = :areaLevel)
              AND (:building IS NULL OR LOWER(a.building) = LOWER(:building))
              AND (:keyword IS NULL OR (
                    LOWER(a.code) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                    LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  ))
            """,
        countQuery = """
            SELECT COUNT(a) FROM Area a
            WHERE a.deletedAt IS NULL
              AND (:isActive IS NULL OR a.isActive = :isActive)
              AND (:areaLevel IS NULL OR a.areaLevel.level = :areaLevel)
              AND (:building IS NULL OR LOWER(a.building) = LOWER(:building))
              AND (:keyword IS NULL OR (
                    LOWER(a.code) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                    LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  ))
            """
    )
    Page<Area> searchAreas(
        @Param("keyword") String keyword,
        @Param("areaLevel") Short areaLevel,
        @Param("building") String building,
        @Param("isActive") Boolean isActive,
        Pageable pageable
    );
}
