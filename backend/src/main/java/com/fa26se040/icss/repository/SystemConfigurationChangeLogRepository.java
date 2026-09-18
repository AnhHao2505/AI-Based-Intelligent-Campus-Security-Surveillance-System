package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.SystemConfigurationChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SystemConfigurationChangeLogRepository extends JpaRepository<SystemConfigurationChangeLog, UUID> {

    Page<SystemConfigurationChangeLog> findByConfigKeyOrderByChangedAtDesc(String configKey, Pageable pageable);
}
