package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AreaTemporaryUsageChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AreaTemporaryUsageChangeLogRepository extends JpaRepository<AreaTemporaryUsageChangeLog, UUID> {
    List<AreaTemporaryUsageChangeLog> findByTemporaryUsageIdOrderByCreatedAtDesc(UUID temporaryUsageId);
}
