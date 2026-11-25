package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AuditWriteException;
import com.fa26se040.icss.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fix/face-cache-refresh (việc 4): tạo tài khoản tay và import ZIP làm tập khuôn mặt hợp lệ thay đổi -> báo AI làm mới
 * kho khuôn mặt (AiFaceCacheRefreshNotifier) NGAY SAU commit; rollback thì không báo; AI lỗi / quá hạn không làm hỏng
 * nghiệp vụ đã commit.
 *
 * <p>Transaction thật trên DB test. FaceDataService (AI + MinIO) và NotificationService (mail) là mock, tráo vào bean thật
 * trong lúc chạy rồi trả lại ở @AfterEach — không dùng @MockBean để không tạo thêm context (giới hạn kết nối DB test,
 * xem AccountMailAfterCommitIntegrationTest). Lời gọi HTTP refresh-cache được thay bằng bộ ghi; bộ ghi đọc DB bằng kết nối
 * riêng nên chỉ thấy dữ liệu đã commit.</p>
 */
class FaceCacheRefreshAfterCommitIntegrationTest extends AbstractIntegrationTest {

    /** Thời gian chờ để khẳng định "không có lời gọi nào nữa" (notifier gửi trên luồng nền). */
    private static final long QUIET_MS = 800;

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserBulkImportHelper userBulkImportHelper;
    @Autowired private AiFaceCacheRefreshNotifier notifier;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private FaceDataService faceDataService;
    /** Bean đích -> {tên field, giá trị thật} để trả lại sau mỗi test. */
    private final List<Object[]> swapped = new ArrayList<>();
    private final List<String> refreshUrls = new CopyOnWriteArrayList<>();
    /** Với mỗi lời gọi refresh: các mã cần kiểm đã commit hay chưa tại thời điểm gọi. */
    private final List<Boolean> committedAtRefresh = new CopyOnWriteArrayList<>();
    private final List<String> watchCodes = new CopyOnWriteArrayList<>();
    private final List<String> createdCodes = new ArrayList<>();
    private String suffix;
    private HttpServer aiServer;

    @BeforeEach
    void setUp() {
        faceDataService = mock(FaceDataService.class);
        swap(userService, "faceDataService", faceDataService);
        swap(userBulkImportHelper, "faceDataService", faceDataService);
        swap(userService, "notificationService", mock(NotificationService.class));
        swap(userBulkImportHelper, "notificationService", mock(NotificationService.class));
        // Không phụ thuộc broker MQTT của máy chạy test: chỉ đi nhánh HTTP
        swap(notifier, "edgeMqttControlService", null);
        swap(notifier, "aiServiceUrl", "http://localhost:8000");
        Consumer<String> recorder = url -> {
            committedAtRefresh.add(watchCodes.stream().allMatch(this::userCommitted));
            refreshUrls.add(url);
        };
        swap(notifier, "refreshHttpCaller", recorder);

        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        when(faceDataService.registerFace(anyString(), any())).thenAnswer(i -> FaceDataResponseDto.builder()
                .id(UUID.randomUUID()).code(i.getArgument(0)).imageFrontUrl("face-profiles/" + i.getArgument(0) + ".jpg").build());
    }

    private void swap(Object bean, String field, Object replacement) {
        Object target = AopTestUtils.getUltimateTargetObject(bean);
        swapped.add(new Object[]{target, field, ReflectionTestUtils.getField(target, field)});
        ReflectionTestUtils.setField(target, field, replacement);
    }

    @AfterEach
    void cleanUp() {
        if (aiServer != null) {
            aiServer.stop(0);
            aiServer = null;
        }
        // trả theo thứ tự ngược để field bị tráo hai lần về đúng giá trị gốc
        for (int i = swapped.size() - 1; i >= 0; i--) {
            Object[] s = swapped.get(i);
            ReflectionTestUtils.setField(s[0], (String) s[1], s[2]);
        }
        swapped.clear();
        refreshUrls.clear();
        committedAtRefresh.clear();
        watchCodes.clear();
        // PR-AUDIT: audit append-only có FK tới users -> không xoá được user, chỉ khoá
        createdCodes.forEach(code -> userRepository.findByUserCode(code).ifPresent(u -> {
            u.setIsActive(false);
            userRepository.save(u);
        }));
        createdCodes.clear();
    }

    private StaffAccountCreateRequest request(String code) {
        createdCodes.add(code);
        return StaffAccountCreateRequest.builder()
                .fullName("Face cache " + code)
                .userCode(code)
                .email(code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .faceImage(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                .build();
    }

    private boolean userCommitted(String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_code = ?", Integer.class, code);
        return n != null && n == 1;
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /** Đợi đủ {@code expected} lời gọi, rồi đợi thêm QUIET_MS để chắc không có lời gọi thừa. */
    private void assertRefreshCount(int expected) throws InterruptedException {
        if (expected > 0) {
            assertThat(waitFor(() -> refreshUrls.size() >= expected, 5000))
                    .as("phải có %d lời gọi refresh-cache", expected).isTrue();
        }
        Thread.sleep(QUIET_MS);
        assertThat(refreshUrls).as("số lời gọi refresh-cache").hasSize(expected);
    }

    // ------------------------------------------------------------------ tạo tài khoản tay

    @Test
    @DisplayName("FCR-01: tạo tài khoản tay commit -> báo refresh-cache đúng 1 lần, lúc báo tài khoản đã commit")
    void createStaffAccount_commit_refreshOnceAfterCommit() throws Exception {
        String code = "FCR1-" + suffix;
        watchCodes.add(code);

        userService.createStaffAccount(request(code));

        assertRefreshCount(1);
        assertThat(refreshUrls.get(0)).isEqualTo("http://localhost:8000/api/v1/faces/refresh-cache");
        assertThat(committedAtRefresh).containsExactly(true);
    }

    @Test
    @DisplayName("FCR-02: transaction bao ngoài rollback sau khi tạo tài khoản -> không báo refresh-cache")
    void createStaffAccount_outerRollback_noRefresh() throws Exception {
        String code = "FCR2-" + suffix;

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            userService.createStaffAccount(request(code));
            throw new IllegalStateException("ép rollback sau khi tạo tài khoản");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(userCommitted(code)).isFalse();
        assertRefreshCount(0);
    }

    @Test
    @DisplayName("FCR-03: lỗi ghi audit trong createStaffAccount -> rollback, không báo refresh-cache")
    void createStaffAccount_auditFails_noRefresh() throws Exception {
        String code = "FCR3-" + suffix;
        AuditService failingAudit = mock(AuditService.class);
        when(failingAudit.resolveCurrentActor(anyString())).thenReturn(com.fa26se040.icss.dto.audit.AuditActor.system("TEST"));
        when(failingAudit.record(eq(AuditTargetType.USER_ACCESS_LEVEL), any(), any(), any(), any(), any(), any(), any(),
                any(com.fa26se040.icss.dto.audit.AuditActor.class)))
                .thenThrow(new AuditWriteException("ép lỗi ghi audit"));
        swap(userService, "auditService", failingAudit);

        assertThatThrownBy(() -> userService.createStaffAccount(request(code))).isInstanceOf(AuditWriteException.class);

        assertThat(userCommitted(code)).isFalse();
        assertRefreshCount(0);
    }

    @Test
    @DisplayName("FCR-04: AI trả HTTP 500 cho refresh-cache -> tài khoản vẫn commit, request không lỗi")
    void createStaffAccount_aiReturns500_accountCommitted() throws Exception {
        CountDownLatch hit = startAiServer(500, 0);
        String code = "FCR4-" + suffix;

        userService.createStaffAccount(request(code));

        assertThat(hit.await(5, TimeUnit.SECONDS)).as("AI phải nhận được POST refresh-cache").isTrue();
        assertThat(userCommitted(code)).isTrue();
    }

    @Test
    @DisplayName("FCR-05: AI treo quá timeout 2 s -> tài khoản vẫn commit, request trả về ngay (không chờ AI)")
    void createStaffAccount_aiTimeout_accountCommittedWithoutWaiting() throws Exception {
        CountDownLatch hit = startAiServer(200, 4000);
        String code = "FCR5-" + suffix;

        long started = System.nanoTime();
        userService.createStaffAccount(request(code));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(userCommitted(code)).isTrue();
        assertThat(elapsedMs).as("tạo tài khoản không chờ AI (timeout 2 s)").isLessThan(2000);
        assertThat(hit.await(5, TimeUnit.SECONDS)).as("AI phải nhận được POST refresh-cache").isTrue();
    }

    @Test
    @DisplayName("FCR-06: AI không chạy (connection refused) -> tài khoản vẫn commit")
    void createStaffAccount_aiDown_accountCommitted() throws Exception {
        // trả lại bộ gọi HTTP thật, trỏ tới cổng không có ai nghe
        restoreRealHttpCaller("http://127.0.0.1:" + freePort());
        String code = "FCR6-" + suffix;

        userService.createStaffAccount(request(code));

        assertThat(userCommitted(code)).isTrue();
    }

    // ------------------------------------------------------------------ import ZIP

    @Test
    @DisplayName("FCR-07: import ZIP 3 dòng (2 thành công, 1 lỗi) -> báo refresh-cache đúng 1 lần cho cả lô, sau khi 2 dòng commit")
    void bulkImport_mixedRows_refreshOnceForBatch() throws Exception {
        List<String> codes = List.of("FCR7A" + suffix, "FCR7B" + suffix, "FCR7C" + suffix);
        createdCodes.addAll(codes);
        watchCodes.addAll(List.of(codes.get(0), codes.get(2)));
        when(faceDataService.registerFace(eq(codes.get(1)), any())).thenThrow(new IllegalStateException("ép lỗi dòng 2"));

        BulkImportResponse response = userService.bulkImportNormalUsers(zipOf(codes));

        assertThat(response.getSuccessCount()).isEqualTo(2);
        assertThat(response.getFailureCount()).isEqualTo(1);
        assertThat(userCommitted(codes.get(1))).as("dòng lỗi rollback").isFalse();
        assertRefreshCount(1);
        assertThat(committedAtRefresh).containsExactly(true);
    }

    @Test
    @DisplayName("FCR-08: import ZIP mọi dòng lỗi (rollback từng dòng) -> không báo refresh-cache")
    void bulkImport_allRowsFail_noRefresh() throws Exception {
        List<String> codes = List.of("FCR8A" + suffix, "FCR8B" + suffix);
        createdCodes.addAll(codes);
        when(faceDataService.registerFace(anyString(), any())).thenThrow(new IllegalStateException("ép lỗi mọi dòng"));

        BulkImportResponse response = userService.bulkImportNormalUsers(zipOf(codes));

        assertThat(response.getSuccessCount()).isZero();
        assertRefreshCount(0);
    }

    // ------------------------------------------------------------------ helpers

    /** AI giả: POST /api/v1/faces/refresh-cache trả {@code status} sau {@code delayMs}. */
    private CountDownLatch startAiServer(int status, long delayMs) throws Exception {
        CountDownLatch hit = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        aiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        aiServer.createContext(AiFaceCacheRefreshNotifier.REFRESH_PATH, exchange -> {
            calls.incrementAndGet();
            hit.countDown();
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        aiServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        aiServer.start();
        restoreRealHttpCaller("http://127.0.0.1:" + aiServer.getAddress().getPort());
        return hit;
    }

    /** Bỏ bộ ghi, dùng lại bộ gọi HTTP thật của notifier (giá trị gốc đã lưu khi tráo). */
    private void restoreRealHttpCaller(String aiUrl) {
        Object realCaller = swapped.stream()
                .filter(s -> "refreshHttpCaller".equals(s[1]))
                .findFirst().orElseThrow()[2];
        swap(notifier, "refreshHttpCaller", realCaller);
        swap(notifier, "aiServiceUrl", aiUrl);
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** ZIP nạp người dùng thường: metadata.csv (user_code, full_name, email) + images/<mã>.jpg. */
    private static MockMultipartFile zipOf(List<String> codes) throws Exception {
        StringBuilder csv = new StringBuilder("user_code,full_name,email\n");
        for (String code : codes) {
            csv.append(code).append(",Người dùng ").append(code).append(",").append(code.toLowerCase(Locale.ROOT)).append("@fpt.edu.vn\n");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("metadata.csv"));
            zos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (String code : codes) {
                zos.putNextEntry(new ZipEntry("images/" + code + ".jpg"));
                zos.write(new byte[]{1, 2, 3});
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", "import.zip", "application/zip", bytes.toByteArray());
    }
}
