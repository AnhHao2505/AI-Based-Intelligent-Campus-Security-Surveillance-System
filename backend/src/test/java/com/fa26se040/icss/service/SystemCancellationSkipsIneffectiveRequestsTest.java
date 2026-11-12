package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T1b bổ sung: mọi luồng hệ thống tự huỷ đơn truy cập chỉ chọn đơn còn HIỆU LỰC (PENDING có startTime &gt; now, APPROVED có
 * endTime &gt; now). Đơn PENDING đã quá giờ bắt đầu (hiệu lực EXPIRED, job chưa chạy) giữ nguyên PENDING trong DB, không audit
 * huỷ, không thông báo REQUEST_SYSTEM_CANCELLED, và không được tính trong màn xem trước. Mỗi test kèm 1 đơn còn hiệu lực để
 * chứng minh luồng vẫn huỷ bình thường.
 */
class SystemCancellationSkipsIneffectiveRequestsTest extends Step5bTestSupport {

    private static final String REASON = "Kiểm thử đơn đã hết hiệu lực không bị hệ thống huỷ";

    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;

    private Area area;
    private User requester;

    @BeforeEach
    void setUpRequests() {
        // Tầng của Step5bTestSupport ("Tầng test 5b <suffix>", 21 ký tự) vượt giới hạn 20 ký tự khi PUT khu vực (đổi loại)
        floor = floorRepository.save(com.fa26se040.icss.entity.Floor.builder()
                .name("SC " + suffix)
                .floorOrder(1)
                .building(building)
                .isActive(true)
                .build());
        area = newArea(AreaLevel.CONFIDENTIAL);
        requester = newUser("rq", Role.NORMAL_USER, 3, true);
    }

    /** PENDING đã qua giờ bắt đầu 1 phút (job EXPIRE chưa chạy). */
    private AccessRequest elapsedPending() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        return newRequest(area, requester, RequestType.INDIVIDUAL, RequestStatus.PENDING, now.minusMinutes(1), now.plusHours(2));
    }

    /** PENDING còn hiệu lực (bắt đầu ngày mai). */
    private AccessRequest validPending() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS);
        return newRequest(area, requester, RequestType.INDIVIDUAL, RequestStatus.PENDING, start, start.plusHours(2));
    }

    private String dbStatus(AccessRequest r) {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, r.getId());
    }

    private long cancelAudits(AccessRequest r) {
        return auditsForTarget(r.getId().toString()).stream()
                .filter(a -> a.getAction() != null && "CANCEL".equals(a.getAction().name())).count();
    }

    private int systemCancelNotices(AccessRequest r) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'REQUEST_SYSTEM_CANCELLED' AND reference_id = ?",
                Integer.class, r.getId());
    }

    /** Đơn đã hết hiệu lực: vẫn PENDING trong DB, hiệu lực EXPIRED, 0 audit huỷ, 0 thông báo. */
    private void assertUntouched(AccessRequest r) {
        assertEquals("PENDING", dbStatus(r));
        AccessRequest fresh = accessRequestRepository.findById(r.getId()).orElseThrow();
        assertEquals(RequestStatus.EXPIRED, fresh.effectiveStatus(OffsetDateTime.now()));
        assertEquals(0, cancelAudits(r));
        assertEquals(0, systemCancelNotices(r));
    }

    /** Đơn còn hiệu lực: luồng vẫn huỷ như cũ. */
    private void assertCancelledBySystem(AccessRequest r) {
        assertEquals("CANCELLED", dbStatus(r));
        assertEquals(1, cancelAudits(r));
        assertEquals("SYSTEM", jdbc.queryForObject("SELECT cancel_source FROM access_requests WHERE id = ?", String.class, r.getId()));
    }

    @Test
    @DisplayName("Đổi loại khu sang Công khai: xem trước không có đơn quá giờ; sau đổi đơn quá giờ vẫn PENDING (EXPIRED), 0 audit, 0 thông báo; đơn còn hiệu lực bị huỷ")
    void areaTypeChangeToPublic() throws Exception {
        AccessRequest elapsed = elapsedPending();
        AccessRequest valid = validPending();

        MvcResult preview = send(get("/api/areas/{id}/type-change-preview", area.getId()).param("newAreaLevel", "PUBLIC"), admin, null).andReturn();
        assertEquals(200, status(preview), preview.getResponse().getContentAsString());
        JsonNode pending = json(preview).path("data").path("pendingRequestsToCancel");
        assertEquals(1, pending.size(), pending.toString());
        assertTrue(pending.toString().contains(valid.getId().toString()) && !pending.toString().contains(elapsed.getId().toString()), pending.toString());

        MvcResult change = changeType(admin, area, AreaLevel.PUBLIC, REASON, apiVersion(area));
        assertEquals(200, status(change), change.getResponse().getContentAsString());

        assertUntouched(elapsed);
        assertCancelledBySystem(valid);
    }

    @Test
    @DisplayName("Vô hiệu hoá khu: xem trước requestsToCancel không đếm đơn quá giờ; sau vô hiệu hoá đơn đó vẫn PENDING (EXPIRED), 0 audit, 0 thông báo")
    void areaDeactivation() throws Exception {
        AccessRequest elapsed = elapsedPending();
        AccessRequest valid = validPending();

        MvcResult deps = send(get("/api/areas/{id}/dependencies", area.getId()), admin, null).andReturn();
        assertEquals(200, status(deps), deps.getResponse().getContentAsString());
        assertEquals(1, json(deps).path("data").path("requestsToCancel").asInt(), "số xem trước = số bị huỷ thật");

        MvcResult deactivate = send(post("/api/areas/{id}/deactivate", area.getId()), admin,
                Map.of("reason", REASON, "version", apiVersion(area))).andReturn();
        assertEquals(200, status(deactivate), deactivate.getResponse().getContentAsString());

        assertUntouched(elapsed);
        assertCancelledBySystem(valid);
    }

    @Test
    @DisplayName("Khoá tài khoản người tạo đơn (toggleActive): đơn quá giờ vẫn PENDING (EXPIRED), 0 audit, 0 thông báo; đơn còn hiệu lực bị huỷ")
    void requesterDeactivated() {
        AccessRequest elapsed = elapsedPending();
        AccessRequest valid = validPending();

        userService.toggleActive(requester.getId(), admin.getEmail());

        assertUntouched(elapsed);
        assertCancelledBySystem(valid);
    }

    @Test
    @DisplayName("Xoá tài khoản người tạo đơn (softDelete): đơn quá giờ vẫn PENDING (EXPIRED), 0 audit, 0 thông báo; đơn còn hiệu lực bị huỷ")
    void requesterSoftDeleted() {
        AccessRequest elapsed = elapsedPending();
        AccessRequest valid = validPending();

        userService.softDelete(requester.getId(), admin.getEmail());

        assertUntouched(elapsed);
        assertCancelledBySystem(valid);
    }

    @Test
    @DisplayName("Gỡ lô import chứa người tạo đơn (deleteBatch): đơn quá giờ vẫn PENDING (EXPIRED), 0 audit, 0 thông báo; đơn còn hiệu lực bị huỷ")
    void importBatchDeleted() {
        UUID batchId = UUID.randomUUID();
        requester.setImportBatchId(batchId);
        requester = userRepository.save(requester);
        AccessRequest elapsed = elapsedPending();
        AccessRequest valid = validPending();

        userService.deleteBatch(batchId, admin.getEmail());

        assertUntouched(elapsed);
        assertCancelledBySystem(valid);
    }
}
