package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.enums.IncidentSeverity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IncidentDispatchPolicyRepository extends JpaRepository<IncidentDispatchPolicy, IncidentSeverity> {
}
