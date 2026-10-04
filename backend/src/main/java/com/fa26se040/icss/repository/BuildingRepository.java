package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.Building;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BuildingRepository extends JpaRepository<Building, UUID> {

    Optional<Building> findByNameIgnoreCase(String name);

    @Query("SELECT b FROM Building b LEFT JOIN FETCH b.floors f WHERE b.isActive = true ORDER BY b.name ASC")
    List<Building> findAllActiveWithFloors();

    List<Building> findByIsActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
