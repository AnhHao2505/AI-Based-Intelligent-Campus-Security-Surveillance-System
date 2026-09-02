package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AreaLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AreaLevelRepository extends JpaRepository<AreaLevel, Short> {
    List<AreaLevel> findByIsActiveTrueOrderByLevelAsc();
    Optional<AreaLevel> findByLevelAndIsActiveTrue(Short level);
}
