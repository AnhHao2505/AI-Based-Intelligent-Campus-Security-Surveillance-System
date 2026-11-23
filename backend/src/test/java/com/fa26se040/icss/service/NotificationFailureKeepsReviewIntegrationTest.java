package com.fa26se040.icss.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.guest.GuestTestSupport;
import com.fa26se040.icss.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Việc 8 — BR-115: thông báo in-app lưu trong transaction riêng (InAppNotificationService.createForUsers, REQUIRES_NEW);
 * lỗi ghi thông báo KHÔNG được huỷ nghiệp vụ. Kịch bản: FM duyệt / từ chối đơn truy cập qua HTTP thật, ghi thông báo ném
 * lỗi DB bên trong transaction REQUIRES_NEW của InAppNotificationService (bean thật, proxy thật; chỉ notificationRepository
 * trong bean đích được thay bằng mock ném lỗi ở saveAll).
 * Kỳ vọng: 200, đơn đã commit đúng trạng thái, không có thông báo nào được lưu, lỗi được ghi log ERROR.
 * Chỉ thêm test, không sửa code nghiệp vụ.
 */
class NotificationFailureKeepsReviewIntegrationTest extends GuestTestSupport {

    @Autowired private InAppNotificationService inAppNotificationService;
    @Autowired private JdbcTemplate jdbc;

    private Object notificationTarget;
    private Object realNotificationRepository;
    private NotificationRepository failingRepository;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void breakNotificationWrites() {
        notificationTarget = AopTestUtils.getUltimateTargetObject(inAppNotificationService);
        realNotificationRepository = ReflectionTestUtils.getField(notificationTarget, "notificationRepository");
        failingRepository = mock(NotificationRepository.class);
        when(failingRepository.saveAll(anyIterable()))
                .thenThrow(new DataAccessResourceFailureException("giả lập lỗi ghi bảng notifications"));
        ReflectionTestUtils.setField(notificationTarget, "notificationRepository", failingRepository);

        serviceLogger = (Logger) LoggerFactory.getLogger(AccessRequestService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(notificationTarget, "notificationRepository", realNotificationRepository);
        serviceLogger.detachAppender(logs);
    }

    private AccessRequest pendingRequest() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS);
        return accessRequestRepository.save(AccessRequest.builder()
                .area(internalArea)
                .requester(hostL1)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn kiểm thử BR-115 " + suffix)
                .startTime(start)
                .endTime(start.plusHours(2))
                .status(RequestStatus.PENDING)
                .build());
    }

    private MvcResult review(UUID id, String status, String rejectionReason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        if (rejectionReason != null) {
            body.put("rejectionReason", rejectionReason);
        }
        return send(patch("/api/access-requests/{id}/review", id), fm, body).andReturn();
    }

    /** Đọc bằng JdbcTemplate (kết nối riêng): chỉ thấy dữ liệu đã commit. */
    private Map<String, Object> committedRow(UUID id) {
        return jdbc.queryForMap("SELECT status, rejection_reason, reviewer_id FROM access_requests WHERE id = ?", id);
    }

    private int notificationsFor(UUID id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE reference_id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    private boolean errorLogged(UUID id) {
        return logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().equals("Failed to send review notification for request " + id)
                && e.getThrowableProxy() != null);
    }

    @Test
    @DisplayName("BR-115-01: FM duyệt đơn, ghi thông báo lỗi -> 200, đơn APPROVED đã commit, không có thông báo, lỗi ghi log ERROR")
    void approve_notificationFails_approvalCommitted() throws Exception {
        AccessRequest request = pendingRequest();

        MvcResult r = review(request.getId(), "APPROVED", null);

        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(r).path("data").path("status").asText()).isEqualTo("APPROVED");
        Map<String, Object> row = committedRow(request.getId());
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("reviewer_id")).isEqualTo(fm.getId());
        verify(failingRepository).saveAll(anyIterable());
        assertThat(notificationsFor(request.getId())).as("không có thông báo nào được lưu").isZero();
        assertThat(errorLogged(request.getId())).as("lỗi gửi thông báo phải được ghi log ERROR").isTrue();
    }

    @Test
    @DisplayName("BR-115-02: FM từ chối đơn, ghi thông báo lỗi -> 200, đơn REJECTED kèm lý do đã commit, không có thông báo")
    void reject_notificationFails_rejectionCommitted() throws Exception {
        AccessRequest request = pendingRequest();
        String reason = "Khu vực đang bảo trì hệ thống điện";

        MvcResult r = review(request.getId(), "REJECTED", reason);

        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        Map<String, Object> row = committedRow(request.getId());
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("rejection_reason")).isEqualTo(reason);
        assertThat(notificationsFor(request.getId())).isZero();
        assertThat(errorLogged(request.getId())).isTrue();
    }
}
