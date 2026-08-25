package com.fa26se040.icss.security.service;

import com.fa26se040.icss.security.entity.AuditLog;
import com.fa26se040.icss.security.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAuditLog(UUID actorId, String actionType, String description, String ipAddress) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .actorId(actorId)
                    .actionType(actionType)
                    .description(description)
                    .ipAddress(ipAddress)
                    .build();
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save audit log for action: {}", actionType, e);
        }
    }
}
