package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessControlAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AccessControlAuditLogRepository
        extends JpaRepository<AccessControlAuditLog, UUID>, JpaSpecificationExecutor<AccessControlAuditLog> {
}
