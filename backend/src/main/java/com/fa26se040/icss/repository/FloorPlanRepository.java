package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.FloorPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FloorPlanRepository extends JpaRepository<FloorPlan, UUID> {
    List<FloorPlan> findByIsActiveTrueOrderByBuildingAscFloorAsc();
}
