package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository
        extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

    /** Lọc theo lô (màn Cấp truy cập): đọc audit IMPORT của nhiều lô một lần để lấy tên file và luồng. */
    List<AuditLog> findByTargetTypeAndActionAndTargetIdIn(AuditTargetType targetType, AuditAction action,
                                                         Collection<String> targetIds);
}
