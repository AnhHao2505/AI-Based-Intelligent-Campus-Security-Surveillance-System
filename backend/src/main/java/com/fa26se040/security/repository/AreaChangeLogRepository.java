package com.fa26se040.security.repository;

import com.fa26se040.security.entity.AreaChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AreaChangeLogRepository extends JpaRepository<AreaChangeLog, UUID> {
    List<AreaChangeLog> findByAreaIdOrderByCreatedAtDesc(UUID areaId);
}
