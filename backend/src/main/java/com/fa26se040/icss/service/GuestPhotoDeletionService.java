package com.fa26se040.icss.service;

import com.fa26se040.icss.repository.GuestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * A9 (BR-GV-27): xoá object ảnh khách trên MinIO chỉ SAU khi transaction commit. Transaction rollback -> không xoá gì,
 * DB vẫn trỏ đúng object. Người gọi đã ghi pending_delete_object_key trong transaction (nếu đang trống) để job thử lại
 * khi xoá sau commit lỗi hoặc tiến trình dừng giữa chừng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestPhotoDeletionService {

    private final GuestPhotoStorageService photoStorage;
    private final GuestRepository guestRepository;
    private final PlatformTransactionManager transactionManager;

    public void deleteAfterCommit(UUID guestId, String objectKey) {
        Runnable task = () -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                photoStorage.removeObject(objectKey);
            } catch (Exception ex) {
                log.warn("[GUEST-PHOTO] remove after commit failed, job will retry: {}", ex.getMessage());
                Integer marked = tx.execute(s -> guestRepository.markPendingDeleteIfEmpty(guestId, objectKey));
                if (marked == null || marked == 0) {
                    log.error("[GUEST-PHOTO] guest {} already has another object pending deletion; object left for manual cleanup", guestId);
                }
                return;
            }
            tx.executeWithoutResult(s -> guestRepository.clearPendingDelete(guestId, objectKey));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
