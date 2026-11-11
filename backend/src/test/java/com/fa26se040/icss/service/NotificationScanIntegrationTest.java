package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.guest.GuestTestSupport;
import com.fa26se040.icss.scheduler.NotificationScanScheduler;
import com.fa26se040.icss.scheduler.NotificationScanScheduler.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * PR-1 (V113).
 * <p>T2: nhắc "sắp đến giờ truy cập" (EXPIRING_SOON) gửi khi đơn APPROVED có now &lt; startTime ≤ now + L
 * (L = NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES), đúng 1 lần cho người tạo + từng thành viên, không bao giờ sau giờ bắt đầu.</p>
 * <p>T1: chu kỳ quét đọc từ cấu hình mỗi nhịp (sửa qua service là nhịp kế dùng ngay, không restart); ràng buộc chéo khi lưu.</p>
 *
 * <p>Thời gian điều khiển bằng {@code now} truyền vào (không sleep). Mốc đặt ở năm 2099 để không đụng đơn thật trên DB test
 * dùng chung. Các test quét chạy trong transaction rồi rollback (thông báo tạo cùng transaction của job nên cũng bị huỷ).</p>
 */
class NotificationScanIntegrationTest extends GuestTestSupport {

    @Autowired private InAppNotificationService inAppNotificationService;

    /** 12:00 ngày 10/03/2099 giờ Việt Nam. */
    private static final OffsetDateTime NOON = OffsetDateTime.of(2099, 3, 10, 12, 0, 0, 0, ZoneOffset.ofHours(7));

    private User requester;
    private User member1;
    private User member2;

    @BeforeEach
    void setUpScan() {
        requester = hostL2;
        member1 = hostL1;
        member2 = otherHost;
        resetScanConfigs();
    }

    @AfterEach
    void restoreScanConfigs() {
        resetScanConfigs();
    }

    /** Đưa 4 khoá về mặc định theo thứ tự luôn thoả ràng buộc chéo (nới ngưỡng lên max trước khi đặt lại chu kỳ). */
    private void resetScanConfigs() {
        if (!isDefault(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES) || !isDefault(ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES)) {
            setConfig(ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES, "240");
            setConfig(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES, ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES.getDefaultValue());
            setConfig(ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES, ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES.getDefaultValue());
        }
        if (!isDefault(ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES) || !isDefault(ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS)) {
            setConfig(ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS, "168");
            setConfig(ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES, ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES.getDefaultValue());
            setConfig(ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS, ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS.getDefaultValue());
        }
    }

    private boolean isDefault(ConfigKey key) {
        return key.getDefaultValue().equals(systemConfigService.getString(key));
    }

    private void inRolledBackTransaction(Runnable body) {
        transactionTemplate.executeWithoutResult(tx -> {
            body.run();
            tx.setRollbackOnly();
        });
    }

    /** Đơn nhóm: người tạo + 2 thành viên, bắt đầu {@code start}, dài 2 giờ. */
    private AccessRequest groupRequest(RequestStatus status, OffsetDateTime start) {
        AccessRequest req = AccessRequest.builder()
                .area(contactArea)
                .requester(requester)
                .requestType(RequestType.GROUP)
                .purpose("Đơn kiểm thử nhắc sắp đến giờ " + suffix)
                .startTime(start)
                .endTime(start.plusHours(2))
                .status(status)
                .reviewer(status == RequestStatus.PENDING ? null : fm)
                .reviewedAt(status == RequestStatus.PENDING ? null : start.minusDays(2))
                .rejectionReason(status == RequestStatus.REJECTED ? "Khu vực đang bảo trì định kỳ" : null)
                .build();
        req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(member1).build());
        req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(member2).build());
        return accessRequestRepository.save(req);
    }

    private List<com.fa26se040.icss.entity.Notification> startingSoon(AccessRequest req) {
        return entityManager.createQuery("SELECT n FROM Notification n JOIN FETCH n.recipient "
                        + "WHERE n.referenceId = :id AND n.type = :type", com.fa26se040.icss.entity.Notification.class)
                .setParameter("id", req.getId())
                .setParameter("type", NotificationType.EXPIRING_SOON)
                .getResultList();
    }

    private Set<UUID> recipientIds(AccessRequest req) {
        return startingSoon(req).stream().map(n -> n.getRecipient().getId()).collect(Collectors.toSet());
    }

    private Set<UUID> everyone() {
        return Set.of(requester.getId(), member1.getId(), member2.getId());
    }

    /**
     * Scheduler trong test: job nhắc sắp đến giờ chạy thật (bean Spring), job tồn đọng là no-op. Ở mốc 2099, job tồn đọng
     * thật sẽ coi mọi đơn PENDING trên DB test dùng chung là quá hạn và gửi cho mọi FM đang hoạt động (hàng trăm nghìn dòng).
     */
    private InAppNotificationService startingSoonOnly() {
        InAppNotificationService stub = Mockito.mock(InAppNotificationService.class);
        Mockito.doAnswer(inv -> {
            inAppNotificationService.scanAndNotifyExpiringSoonRequests(inv.getArgument(0));
            return null;
        }).when(stub).scanAndNotifyExpiringSoonRequests(Mockito.any());
        return stub;
    }

    private void scan(OffsetDateTime now) {
        inAppNotificationService.scanAndNotifyExpiringSoonRequests(now);
    }

    // ------------------------------------------------------------------ T2

    @Test
    @DisplayName("T2-a: đơn 12:00 duyệt từ trước, L=30, quét 11:29 -> 0; quét 11:30 -> đúng 1 thông báo cho người tạo + từng thành viên, nội dung nêu giờ bắt đầu")
    void approvedLongBefore_scanAtStartMinusLead_notifiesEveryone() {
        assertEquals(30, systemConfigService.getInt(ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES));
        inRolledBackTransaction(() -> {
            AccessRequest req = groupRequest(RequestStatus.APPROVED, NOON);

            scan(NOON.minusMinutes(31));
            assertEquals(0, startingSoon(req).size(), "12:00 chưa nằm trong (11:29, 11:59]");

            scan(NOON.minusMinutes(30));
            List<com.fa26se040.icss.entity.Notification> sent = startingSoon(req);
            assertEquals(3, sent.size(), sent.toString());
            assertEquals(everyone(), recipientIds(req));
            String expected = "Yêu cầu vào " + contactArea.getName() + " ("
                    + InAppNotificationService.formatTimeRange(req.getStartTime(), req.getEndTime())
                    + ") bắt đầu lúc 12:00 10/03. Vui lòng chuẩn bị.";
            for (var n : sent) {
                assertEquals(expected, n.getMessage());
                assertEquals("Sắp đến giờ truy cập khu vực", n.getTitle());
                assertEquals(InAppNotificationService.REF_TYPE_ACCESS_REQUEST, n.getReferenceType());
            }
        });
    }

    @Test
    @DisplayName("T2-b: đơn 12:00 còn PENDING lúc quét 11:30, 11:40; duyệt lúc 11:45 -> lần quét kế 11:50 có thông báo")
    void approvedLate_nextScanNotifies() {
        inRolledBackTransaction(() -> {
            AccessRequest req = groupRequest(RequestStatus.PENDING, NOON);
            scan(NOON.minusMinutes(30));
            scan(NOON.minusMinutes(20));
            assertEquals(0, startingSoon(req).size());

            req.setStatus(RequestStatus.APPROVED);
            req.setReviewer(fm);
            req.setReviewedAt(NOON.minusMinutes(15));
            accessRequestRepository.save(req);

            scan(NOON.minusMinutes(10));
            assertEquals(everyone(), recipientIds(req));
            assertEquals(3, startingSoon(req).size());
        });
    }

    @Test
    @DisplayName("T2-c: chu kỳ quét 15 phút, đơn 12:07 -> nhịp 11:25/11:40/... báo ở 11:40; nhịp 11:30/11:45/... báo ở 11:45; không lọt, không trùng")
    void fifteenMinuteCycle_neverMisses() {
        inRolledBackTransaction(() -> {
            systemConfigService.update(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES.getKey(), "15", admin.getEmail());
            OffsetDateTime dayA = NOON.plusMinutes(7);
            OffsetDateTime dayB = NOON.plusDays(1).plusMinutes(7);
            AccessRequest reqA = groupRequest(RequestStatus.APPROVED, dayA);
            AccessRequest reqB = groupRequest(RequestStatus.APPROVED, dayB);

            assertEquals(NOON.withHour(11).withMinute(40), firstNotifiedTick(reqA, NOON.withHour(11).withMinute(25), dayA));
            assertEquals(NOON.plusDays(1).withHour(11).withMinute(45),
                    firstNotifiedTick(reqB, NOON.plusDays(1).withHour(11).withMinute(30), dayB));
        });
    }

    /**
     * Chạy nhịp mỗi phút (scheduler mới, chu kỳ đọc từ cấu hình) từ {@code from} tới qua giờ bắt đầu 5 phút. Trả về nhịp đầu
     * tiên có thông báo; kiểm luôn số thông báo không vượt 3 và nhịp đó trước giờ bắt đầu.
     */
    private OffsetDateTime firstNotifiedTick(AccessRequest req, OffsetDateTime from, OffsetDateTime start) {
        NotificationScanScheduler scheduler = new NotificationScanScheduler(startingSoonOnly(), systemConfigService);
        OffsetDateTime first = null;
        for (OffsetDateTime t = from; !t.isAfter(start.plusMinutes(5)); t = t.plusMinutes(1)) {
            scheduler.tick(t);
            int count = startingSoon(req).size();
            assertTrue(count == 0 || count == 3, "tick " + t + ": " + count);
            if (count == 3 && first == null) {
                first = t;
            }
        }
        assertTrue(first != null && first.isBefore(start), "phải báo trước giờ bắt đầu " + start + ", thực tế: " + first);
        assertEquals(everyone(), recipientIds(req));
        return first;
    }

    @Test
    @DisplayName("T2-d: quét 2 lần liên tiếp (11:30, 11:31) -> vẫn 1 thông báo mỗi người")
    void scanTwice_stillOne() {
        inRolledBackTransaction(() -> {
            AccessRequest req = groupRequest(RequestStatus.APPROVED, NOON);
            scan(NOON.minusMinutes(30));
            scan(NOON.minusMinutes(29));
            assertEquals(3, startingSoon(req).size());
            assertEquals(everyone(), recipientIds(req));
        });
    }

    @Test
    @DisplayName("T2-e: đơn đã bắt đầu (startTime = now, startTime < now) -> không thông báo")
    void alreadyStarted_noNotification() {
        inRolledBackTransaction(() -> {
            AccessRequest startsNow = groupRequest(RequestStatus.APPROVED, NOON);
            AccessRequest startedBefore = groupRequest(RequestStatus.APPROVED, NOON.minusMinutes(10));
            scan(NOON);
            scan(NOON.plusMinutes(1));
            assertEquals(0, startingSoon(startsNow).size());
            assertEquals(0, startingSoon(startedBefore).size());
        });
    }

    @Test
    @DisplayName("T2-f: đơn PENDING / REJECTED / CANCELLED bắt đầu 12:00, quét 11:30 -> không thông báo")
    void notApproved_noNotification() {
        inRolledBackTransaction(() -> {
            List<AccessRequest> reqs = new ArrayList<>();
            for (RequestStatus s : List.of(RequestStatus.PENDING, RequestStatus.REJECTED, RequestStatus.CANCELLED)) {
                reqs.add(groupRequest(s, NOON));
            }
            scan(NOON.minusMinutes(30));
            for (AccessRequest r : reqs) {
                assertEquals(0, startingSoon(r).size(), r.getStatus().name());
            }
        });
    }

    // ------------------------------------------------------------------ T1: chu kỳ đọc từ cấu hình mỗi nhịp

    @Test
    @DisplayName("T1: đổi NOTIFICATION_STARTING_SOON_SCAN_MINUTES 5 -> 10 qua SystemConfigService (không restart) -> nhịp kế dùng giá trị mới")
    void scanIntervalChange_appliesAtNextTick() {
        inRolledBackTransaction(() -> {
            InAppNotificationService jobs = Mockito.mock(InAppNotificationService.class);
            NotificationScanScheduler scheduler = new NotificationScanScheduler(jobs, systemConfigService);
            OffsetDateTime t0 = NOON.minusHours(1);

            assertEquals(List.of(Job.STARTING_SOON, Job.PENDING_OVERDUE), scheduler.tick(t0), "khởi động: chạy ở nhịp đầu tiên");
            assertEquals(List.of(), scheduler.tick(t0.plusMinutes(4)));
            assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(t0.plusMinutes(5)));

            systemConfigService.update(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES.getKey(), "10", admin.getEmail());

            assertEquals(List.of(), scheduler.tick(t0.plusMinutes(10)), "mới 5 phút từ lần trước, chu kỳ mới 10");
            assertEquals(List.of(), scheduler.tick(t0.plusMinutes(14)));
            assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(t0.plusMinutes(15)));
            assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(t0.plusMinutes(25)));
            assertEquals(List.of(Job.STARTING_SOON), scheduler.tick(t0.plusMinutes(59)), "PENDING_OVERDUE chưa đủ chu kỳ 60");
            assertEquals(List.of(Job.PENDING_OVERDUE), scheduler.tick(t0.plusMinutes(60)), "PENDING_OVERDUE theo chu kỳ 60");
            Mockito.verify(jobs, Mockito.times(5)).scanAndNotifyExpiringSoonRequests(Mockito.any());
            Mockito.verify(jobs, Mockito.times(2)).scanAndNotifyPendingOverdueRequests(Mockito.any());
        });
    }

    // ------------------------------------------------------------------ T1: ràng buộc chéo khi lưu

    private MvcResult patchConfig(ConfigKey key, String value) throws Exception {
        return send(patch("/api/system-configurations/{key}", key.getKey()), admin,
                Map.of("configValue", value, "reason", REASON)).andReturn();
    }

    private String dbValue(ConfigKey key) {
        return transactionTemplate.execute(tx -> entityManager.createQuery(
                        "SELECT c.configValue FROM SystemConfiguration c WHERE c.configKey = :k", String.class)
                .setParameter("k", key.getKey()).getSingleResult());
    }

    @Test
    @DisplayName("T1: chu kỳ quét nhắc ≥ thời gian báo trước -> 400 ở cả hai chiều sửa, thông điệp nêu 2 khoá + giá trị, không lưu")
    void startingSoonScan_mustBeLessThanLead() throws Exception {
        MvcResult scanTooLong = patchConfig(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES, "30");
        assertEquals(400, status(scanTooLong), scanTooLong.getResponse().getContentAsString());
        assertEquals("Chu kỳ quét nhắc sắp đến giờ (NOTIFICATION_STARTING_SOON_SCAN_MINUTES) phải nhỏ hơn thời gian báo trước "
                + "(NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES). Giá trị hiện hành: NOTIFICATION_STARTING_SOON_SCAN_MINUTES = 5 phút, "
                + "NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES = 30 phút; giá trị vừa nhập: NOTIFICATION_STARTING_SOON_SCAN_MINUTES = 30 phút.",
                message(scanTooLong));
        assertEquals("5", dbValue(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES));
        assertEquals(5, systemConfigService.getInt(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES));

        MvcResult leadTooShort = patchConfig(ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES, "5");
        assertEquals(400, status(leadTooShort), leadTooShort.getResponse().getContentAsString());
        assertEquals("Chu kỳ quét nhắc sắp đến giờ (NOTIFICATION_STARTING_SOON_SCAN_MINUTES) phải nhỏ hơn thời gian báo trước "
                + "(NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES). Giá trị hiện hành: NOTIFICATION_STARTING_SOON_SCAN_MINUTES = 5 phút, "
                + "NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES = 30 phút; giá trị vừa nhập: NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES = 5 phút.",
                message(leadTooShort));
        assertEquals("30", dbValue(ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES));

        MvcResult ok = patchConfig(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES, "29");
        assertEquals(200, status(ok), ok.getResponse().getContentAsString());
        assertEquals(29, systemConfigService.getInt(ConfigKey.NOTIFICATION_STARTING_SOON_SCAN_MINUTES));
    }

    @Test
    @DisplayName("T1: chu kỳ quét tồn đọng > ngưỡng giờ × 60 -> 400 ở cả hai chiều sửa, thông điệp nêu 2 khoá + giá trị; = ngưỡng -> 200")
    void pendingOverdueScan_mustNotExceedThreshold() throws Exception {
        MvcResult scanUp = patchConfig(ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES, "120");
        assertEquals(200, status(scanUp), scanUp.getResponse().getContentAsString());

        MvcResult hoursTooShort = patchConfig(ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS, "1");
        assertEquals(400, status(hoursTooShort), hoursTooShort.getResponse().getContentAsString());
        assertEquals("Chu kỳ quét đơn tồn đọng (NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES) không được lớn hơn ngưỡng tồn đọng "
                + "(NOTIFICATION_PENDING_OVERDUE_HOURS × 60 phút). Giá trị hiện hành: NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES = 120 phút, "
                + "NOTIFICATION_PENDING_OVERDUE_HOURS = 24 giờ; giá trị vừa nhập: NOTIFICATION_PENDING_OVERDUE_HOURS = 1 giờ.",
                message(hoursTooShort));
        assertEquals("24", dbValue(ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS));

        assertEquals(200, status(patchConfig(ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS, "2")), "120 = 2 × 60: biên được phép");

        MvcResult scanTooLong = patchConfig(ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES, "121");
        assertEquals(400, status(scanTooLong), scanTooLong.getResponse().getContentAsString());
        assertEquals("Chu kỳ quét đơn tồn đọng (NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES) không được lớn hơn ngưỡng tồn đọng "
                + "(NOTIFICATION_PENDING_OVERDUE_HOURS × 60 phút). Giá trị hiện hành: NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES = 120 phút, "
                + "NOTIFICATION_PENDING_OVERDUE_HOURS = 2 giờ; giá trị vừa nhập: NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES = 121 phút.",
                message(scanTooLong));
        assertEquals("120", dbValue(ConfigKey.NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES));
    }
}
