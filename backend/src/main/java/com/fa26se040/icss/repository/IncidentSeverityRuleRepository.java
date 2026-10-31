package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.IncidentSeverityRule;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.IncidentTimeWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentSeverityRuleRepository extends JpaRepository<IncidentSeverityRule, UUID> {

    Optional<IncidentSeverityRule> findByEventTypeAndAreaLevelCodeAndTimeWindow(
            String eventType, AreaLevel areaLevelCode, IncidentTimeWindow timeWindow);
}
