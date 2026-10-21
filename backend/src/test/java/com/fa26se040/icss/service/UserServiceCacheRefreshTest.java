package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceCacheRefreshTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FaceDataService faceDataService;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserAccessLevelHelper userAccessLevelHelper;

    @Mock
    private AuditService auditService;

    @Mock
    private UserBulkImportService userBulkImportService;

    @Mock
    private AccessRequestService accessRequestService;

    @Mock
    private AreaAssignedPersonnelService assignedPersonnelService;

    @InjectMocks
    private UserService userService;

    private UUID userId;
    private User testUser;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .userCode("SV19001")
                .fullName("Nguyễn Văn A")
                .email("nva@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .isActive(true)
                .accessLevel(1)
                .deletedAt(null)
                .build();
        // BR-AP-24/25: vô hiệu hoá / xoá cần ADMIN đang thao tác (revoked_by của AP)
        lenient().when(userRepository.findByEmail("admin@fpt.edu.vn")).thenReturn(Optional.of(User.builder()
                .id(UUID.randomUUID()).email("admin@fpt.edu.vn").role(Role.ADMIN).isActive(true).build()));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("toggleActive trong transaction: Chỉ gọi refresh-cache SAU KHI commit DB thành công")
    void toggleActive_TransactionActive_RefreshesCacheOnlyAfterCommit() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicBoolean refreshCalled = new AtomicBoolean(false);
        AtomicReference<String> calledUrl = new AtomicReference<>();
        userService.setRefreshHttpCaller(url -> {
            refreshCalled.set(true);
            calledUrl.set(url);
        });

        TransactionSynchronizationManager.initSynchronization();

        // 1. Thực hiện toggleActive trong transaction
        UserListResponse response = userService.toggleActive(userId, "admin@fpt.edu.vn");
        assertNotNull(response);
        assertFalse(response.isActive());

        // 2. Trước khi commit: refreshHttpCaller chưa được gọi
        assertFalse(refreshCalled.get(), "Không được gọi AI refresh trước khi transaction commit");

        // 3. Giả lập Spring commit transaction
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        // Chờ tác vụ async sau commit hoàn thành
        assertTrue(waitForCondition(refreshCalled::get, 1500), "Phải gọi refresh-cache sau khi commit");
        assertEquals("http://localhost:8000/api/v1/faces/refresh-cache", calledUrl.get());
    }

    @Test
    @DisplayName("softDelete trong transaction: Chỉ gọi refresh-cache SAU KHI commit DB thành công")
    void softDelete_TransactionActive_RefreshesCacheOnlyAfterCommit() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicBoolean refreshCalled = new AtomicBoolean(false);
        userService.setRefreshHttpCaller(url -> refreshCalled.set(true));

        TransactionSynchronizationManager.initSynchronization();

        // 1. Thực hiện softDelete
        userService.softDelete(userId, "admin@fpt.edu.vn");

        // 2. Trước khi commit: chưa gọi AI
        assertFalse(refreshCalled.get(), "Không được gọi AI refresh trước khi transaction commit");

        // 3. Commit transaction
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        assertTrue(waitForCondition(refreshCalled::get, 1500), "Phải gọi refresh-cache sau khi commit");
    }

    @Test
    @DisplayName("AI Service bị lỗi hoặc sập: Không làm fail thao tác của người dùng")
    void aiServiceOffline_DoesNotFailUserOperation() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        // Caller ném RuntimeException giả lập mạng lỗi / AI sập
        userService.setRefreshHttpCaller(url -> {
            throw new RuntimeException("AI service connection refused (Simulated offline)");
        });

        // Gọi toggleActive ngoài transaction -> không ném exception
        assertDoesNotThrow(() -> {
            UserListResponse response = userService.toggleActive(userId, "admin@fpt.edu.vn");
            assertNotNull(response);
        });

        // Gọi softDelete ngoài transaction -> không ném exception
        assertDoesNotThrow(() -> {
            userService.softDelete(userId, "admin@fpt.edu.vn");
        });
    }

    @Test
    @DisplayName("Cấu hình ai.service.url tùy biến: Chuẩn hóa URL không bị trùng dấu gạch chéo")
    void customAiServiceUrl_FormatsTargetUrlProperly() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicReference<String> invokedUrl = new AtomicReference<>();
        userService.setRefreshHttpCaller(invokedUrl::set);
        userService.setAiServiceUrl("http://custom-ai-host:9090/");

        userService.toggleActive(userId, "admin@fpt.edu.vn");

        assertTrue(waitForCondition(() -> invokedUrl.get() != null, 1500));
        assertEquals("http://custom-ai-host:9090/api/v1/faces/refresh-cache", invokedUrl.get());
    }

    private boolean waitForCondition(java.util.function.BooleanSupplier condition, long maxWaitMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
