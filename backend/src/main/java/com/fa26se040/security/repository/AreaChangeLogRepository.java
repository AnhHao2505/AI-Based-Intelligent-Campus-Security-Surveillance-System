package com.fa26se040.security.repository;

import com.fa26se040.security.entity.AreaChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AreaChangeLogRepository extends JpaRepository<AreaChangeLog, Integer> {
    List<AreaChangeLog> findByAreaIdOrderByCreatedAtDesc(Integer areaId);
}
