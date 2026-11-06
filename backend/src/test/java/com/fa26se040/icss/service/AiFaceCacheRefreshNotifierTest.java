package com.fa26se040.icss.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** L3: AiFaceCacheRefreshNotifier gửi refresh-cache sau commit, một lần mỗi transaction, lỗi mạng không ném ra ngoài. */
class AiFaceCacheRefreshNotifierTest {

    private AiFaceCacheRefreshNotifier notifier;
    private List<String> calledUrls;

    @BeforeEach
    void setUp() {
        notifier = new AiFaceCacheRefreshNotifier();
        calledUrls = new CopyOnWriteArrayList<>();
        notifier.setRefreshHttpCaller(calledUrls::add);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Trong transaction: chưa gửi trước commit, gửi đúng 1 lần sau commit dù gọi nhiều lần")
    void inTransaction_sendsOnceAfterCommit() throws InterruptedException {
        TransactionSynchronizationManager.initSynchronization();

        notifier.notifyAfterCommit();
        notifier.notifyAfterCommit();
        notifier.notifyAfterCommit();

        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size(), "Chỉ đăng ký 1 synchronization");
        Thread.sleep(100);
        assertTrue(calledUrls.isEmpty(), "Không được gọi AI trước commit");

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }

        assertTrue(waitFor(() -> !calledUrls.isEmpty(), 1500), "Phải gọi refresh-cache sau commit");
        Thread.sleep(100);
        assertEquals(List.of("http://localhost:8000/api/v1/faces/refresh-cache"), calledUrls);
    }

    @Test
    @DisplayName("Transaction rollback: không gửi")
    void rollback_doesNotSend() throws InterruptedException {
        TransactionSynchronizationManager.initSynchronization();
        notifier.notifyAfterCommit();

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        Thread.sleep(200);
        assertTrue(calledUrls.isEmpty());
    }

    @Test
    @DisplayName("Ngoài transaction: gửi ngay, URL chuẩn hoá dấu gạch chéo cuối")
    void noTransaction_sendsImmediately() {
        notifier.setAiServiceUrl("http://custom-ai-host:9090/");

        notifier.notifyAfterCommit();

        assertTrue(waitFor(() -> !calledUrls.isEmpty(), 1500));
        assertEquals("http://custom-ai-host:9090/api/v1/faces/refresh-cache", calledUrls.get(0));
    }

    @Test
    @DisplayName("AI service lỗi: không ném exception ra nghiệp vụ")
    void callerThrows_doesNotPropagate() {
        notifier.setRefreshHttpCaller(url -> {
            throw new RuntimeException("connection refused (simulated)");
        });

        assertDoesNotThrow(() -> notifier.notifyAfterCommit());

        TransactionSynchronizationManager.initSynchronization();
        notifier.notifyAfterCommit();
        assertDoesNotThrow(() -> {
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        });
    }

    private static boolean waitFor(BooleanSupplier condition, long maxWaitMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
