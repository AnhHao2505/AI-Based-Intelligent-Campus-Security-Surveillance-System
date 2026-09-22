package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.enums.AreaLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AreaLevelPresetRepository extends JpaRepository<AreaLevelPreset, AreaLevel> {
}
