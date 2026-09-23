package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.Floor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FloorRepository extends JpaRepository<Floor, UUID> {

    List<Floor> findByBuildingIdAndIsActiveTrueOrderByFloorOrderAsc(UUID buildingId);

    Optional<Floor> findByBuildingIdAndFloorCodeIgnoreCase(UUID buildingId, String floorCode);

    @Query("SELECT f FROM Floor f JOIN f.building b WHERE LOWER(b.code) = LOWER(:buildingCode) AND LOWER(f.floorCode) = LOWER(:floorCode)")
    Optional<Floor> findByBuildingCodeIgnoreCaseAndFloorCodeIgnoreCase(
            @Param("buildingCode") String buildingCode,
            @Param("floorCode") String floorCode
    );

    @Query("SELECT f FROM Floor f JOIN FETCH f.building b WHERE f.isActive = true AND b.isActive = true ORDER BY b.code ASC, f.floorOrder ASC")
    List<Floor> findAllActiveFloors();
}
