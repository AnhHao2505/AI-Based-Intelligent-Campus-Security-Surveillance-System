package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.enums.AreaLevel;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Màn Quản lý sự kiện (V87): tên sự kiện bắt buộc (ERR_AREA_057), ghi chú khi tạo không bắt buộc (BR-ES-20 sửa lại),
 * audit có title, API GET /api/event-schedules (lọc status / areaId / from-to / q, phân trang, phân quyền),
 * và câu SQL của V87 (điền bù title từ reason_label).
 */
class EventScheduleTitleAndListIntegrationTest extends Step5bTestSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String MIGRATION = "db/migration/V87__event_schedule_title.sql";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Map<String, Object> body(String title, OffsetDateTime start, OffsetDateTime end, String note) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (title != null) {
            b.put("title", title);
        }
        b.put("startAt", start.toString());
        b.put("endAt", end.toString());
        b.put("reasonCode", "OTHER");
        if (note != null) {
            b.put("note", note);
        }
        return b;
    }

    private OffsetDateTime future(int days) {
        return OffsetDateTime.now().plusDays(days).truncatedTo(ChronoUnit.HOURS);
    }

    private MvcResult create(Area area, Map<String, Object> body) throws Exception {
        return send(post("/api/areas/{id}/event-schedules", area.getId()), fm, body).andReturn();
    }

    private AreaEventSchedule schedule(Area area, String title, AreaEventScheduleStatus status, OffsetDateTime start, OffsetDateTime end) {
        AreaEventSchedule s = newSchedule(area, start, end, "OTHER", "Khác");
        s.setTitle(title);
        s.setStatus(status);
        return eventScheduleRepository.save(s);
    }

    private List<String> titles(MvcResult r) throws Exception {
        List<String> out = new ArrayList<>();
        json(r).path("data").path("content").forEach(n -> out.add(n.path("title").asText()));
        return out;
    }

    private MvcResult list(String query) throws Exception {
        return send(get("/api/event-schedules" + query), fm, null).andReturn();
    }

    // ================================================================== tên sự kiện

    @Test
    @DisplayName("Tạo lịch thiếu tên / tên toàn khoảng trắng / tên 151 ký tự -> 400 ERR_AREA_057; tên 150 ký tự (sau trim) -> 201")
    void titleRequiredAndMax150() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);

        MvcResult missing = create(area, body(null, future(1), future(1).plusHours(2), EVENT_NOTE));
        assertEquals(400, status(missing), describe(missing));
        assertEquals("ERR_AREA_057", errorCode(missing));
        assertEquals("Tên sự kiện bắt buộc, tối đa 150 ký tự", message(missing));

        MvcResult blank = create(area, body("   ", future(1), future(1).plusHours(2), EVENT_NOTE));
        assertEquals(400, status(blank), describe(blank));
        assertEquals("ERR_AREA_057", errorCode(blank));

        MvcResult tooLong = create(area, body("A".repeat(151), future(1), future(1).plusHours(2), EVENT_NOTE));
        assertEquals(400, status(tooLong), describe(tooLong));
        assertEquals("ERR_AREA_057", errorCode(tooLong));

        assertEquals(0, eventScheduleRepository.findByAreaIdOrderByStartAtAsc(area.getId()).size(), "bị chặn thì không tạo lịch");

        String max = "B".repeat(150);
        MvcResult ok = create(area, body("  " + max + "  ", future(1), future(1).plusHours(2), EVENT_NOTE));
        assertEquals(201, status(ok), describe(ok));
        assertEquals(max, json(ok).path("data").path("title").asText(), "tên được trim");
    }

    // ================================================================== ghi chú không bắt buộc (BR-ES-20 sửa lại)

    @Test
    @DisplayName("BR-ES-20 (sửa 21/10): ghi chú rỗng hoặc không gửi -> 201, note null; ghi chú 5 ký tự -> 400 ERR_AREA_024")
    void noteOptionalOnCreate() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);

        MvcResult noNote = create(area, body("Hội thảo không ghi chú", future(1), future(1).plusHours(2), null));
        assertEquals(201, status(noNote), describe(noNote));
        JsonNode data = json(noNote).path("data");
        assertTrue(data.path("note").isNull(), "không gửi ghi chú -> note null");
        assertEquals(area.getId().toString(), data.path("areaId").asText());
        assertEquals(area.getName(), data.path("areaName").asText(), "response có tên khu");
        assertEquals(fm.getFullName(), data.path("createdByName").asText());

        MvcResult emptyNote = create(area, body("Hội thảo ghi chú rỗng", future(2), future(2).plusHours(2), "   "));
        assertEquals(201, status(emptyNote), describe(emptyNote));
        assertTrue(json(emptyNote).path("data").path("note").isNull(), "ghi chú chỉ có khoảng trắng -> null");

        MvcResult shortNote = create(area, body("Hội thảo ghi chú ngắn", future(3), future(3).plusHours(2), "ngắn"));
        assertEquals(400, status(shortNote), describe(shortNote));
        assertEquals("ERR_AREA_024", errorCode(shortNote));
    }

    // ================================================================== sửa tên + audit

    @Test
    @DisplayName("B5/B6: sửa lịch đổi được tên; audit CREATE có title, audit UPDATE ghi title cũ và mới")
    void updateTitleAudited() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        OffsetDateTime start = future(2);
        MvcResult created = create(area, body("Tên ban đầu", start, start.plusHours(2), null));
        assertEquals(201, status(created), describe(created));
        String scheduleId = json(created).path("data").path("id").asText();

        Map<String, Object> upd = body("Tên đã sửa", start.plusHours(1), start.plusHours(3), EVENT_NOTE);
        MvcResult updated = send(patch("/api/areas/{id}/event-schedules/{sid}", area.getId(), scheduleId), fm, upd).andReturn();
        assertEquals(200, status(updated), describe(updated));
        assertEquals("Tên đã sửa", json(updated).path("data").path("title").asText());
        assertEquals("Tên đã sửa", jdbcTemplate.queryForObject(
                "SELECT title FROM area_event_schedules WHERE id = ?::uuid", String.class, scheduleId));

        List<AuditLog> audits = auditsForTarget(scheduleId);
        AuditLog createAudit = audits.stream().filter(a -> a.getAction().name().equals("CREATE")).findFirst().orElseThrow();
        assertEquals("Tên ban đầu", objectMapper.readTree(createAudit.getNewValue()).path("title").asText());
        assertNull(createAudit.getReason(), "không có ghi chú -> lý do audit null");

        AuditLog updateAudit = audits.stream().filter(a -> a.getAction().name().equals("UPDATE")).findFirst().orElseThrow();
        assertEquals("Tên ban đầu", objectMapper.readTree(updateAudit.getOldValue()).path("title").asText());
        assertEquals("Tên đã sửa", objectMapper.readTree(updateAudit.getNewValue()).path("title").asText());

        Map<String, Object> noTitle = body(null, start.plusHours(1), start.plusHours(3), EVENT_NOTE);
        MvcResult rejected = send(patch("/api/areas/{id}/event-schedules/{sid}", area.getId(), scheduleId), fm, noTitle).andReturn();
        assertEquals(400, status(rejected), describe(rejected));
        assertEquals("ERR_AREA_057", errorCode(rejected));
    }

    // ================================================================== API liệt kê

    @Test
    @DisplayName("B4: GET /api/event-schedules lọc đúng theo status (nhiều giá trị), areaId, from/to (giao khoảng), q (tên sự kiện + tên khu, không phân biệt hoa thường), sắp startAt giảm dần, phân trang")
    void listAllFilters() throws Exception {
        String tag = "EVQ" + suffix.toUpperCase();
        Area a = newArea(AreaLevel.CONFIDENTIAL);
        a.setName("Hội trường Sao Mai " + tag);
        a = areaRepository.save(a);
        Area b = newArea(AreaLevel.CONFIDENTIAL);

        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        AreaEventSchedule s1 = schedule(a, "Hội Thảo AI " + tag, AreaEventScheduleStatus.SCHEDULED, now.plusDays(3), now.plusDays(3).plusHours(2));
        AreaEventSchedule s2 = schedule(a, "Ngày hội việc làm " + tag, AreaEventScheduleStatus.STARTED, now.minusHours(1), now.plusHours(1));
        AreaEventSchedule s3 = schedule(b, "Workshop " + tag, AreaEventScheduleStatus.CANCELLED, now.plusDays(5), now.plusDays(5).plusHours(2));
        AreaEventSchedule s4 = schedule(b, "Lễ tốt nghiệp " + tag, AreaEventScheduleStatus.COMPLETED, now.minusDays(3), now.minusDays(3).plusHours(2));

        // q theo tên sự kiện (mọi lịch có tag), sắp startAt giảm dần
        MvcResult all = list("?q=" + tag.toLowerCase());
        assertEquals(200, status(all), describe(all));
        assertEquals(List.of(s3.getTitle(), s1.getTitle(), s2.getTitle(), s4.getTitle()), titles(all));
        JsonNode first = json(all).path("data").path("content").get(0);
        assertEquals(b.getName(), first.path("areaName").asText());
        assertEquals(fm.getFullName(), first.path("createdByName").asText());
        assertEquals("CANCELLED", first.path("status").asText());

        // status một giá trị + areaId
        assertEquals(List.of(s1.getTitle()), titles(list("?status=SCHEDULED&areaId=" + a.getId())));
        // status nhiều giá trị (lặp tham số và dạng phẩy)
        assertEquals(List.of(s3.getTitle(), s2.getTitle()), titles(list("?status=STARTED&status=CANCELLED&q=" + tag)));
        assertEquals(List.of(s3.getTitle(), s2.getTitle()), titles(list("?status=STARTED,CANCELLED&q=" + tag)));
        // areaId
        assertEquals(List.of(s3.getTitle(), s4.getTitle()), titles(list("?areaId=" + b.getId())));
        // q theo tên khu, không phân biệt hoa thường (gồm chữ có dấu)
        assertEquals(List.of(s1.getTitle(), s2.getTitle()), titles(list("?q=HỘI TRƯỜNG SAO MAI " + tag)));
        // q theo tên sự kiện có dấu, khác hoa thường
        assertEquals(List.of(s1.getTitle()), titles(list("?q=hội thảo ai " + tag.toLowerCase())));

        // from / to: lịch giao với khoảng ngày (giờ Việt Nam)
        LocalDate d4 = s4.getStartAt().atZoneSameInstant(VN).toLocalDate();
        assertEquals(List.of(s4.getTitle()), titles(list("?from=" + d4 + "&to=" + d4 + "&q=" + tag)));
        LocalDate d3 = s3.getStartAt().atZoneSameInstant(VN).toLocalDate();
        assertEquals(List.of(s3.getTitle()), titles(list("?from=" + d3 + "&to=" + d3 + "&q=" + tag)));
        LocalDate today = now.atZoneSameInstant(VN).toLocalDate();
        assertTrue(titles(list("?from=" + today + "&q=" + tag)).containsAll(List.of(s1.getTitle(), s2.getTitle(), s3.getTitle())),
                "from = hôm nay: lấy lịch kết thúc sau 00:00 hôm nay");
        assertFalse(titles(list("?from=" + today + "&q=" + tag)).contains(s4.getTitle()), "lịch đã kết thúc trước from bị loại");

        // phân trang
        MvcResult page0 = list("?areaId=" + b.getId() + "&size=1&page=0");
        assertEquals(List.of(s3.getTitle()), titles(page0));
        assertEquals(2, json(page0).path("data").path("totalElements").asInt());
        assertEquals(2, json(page0).path("data").path("totalPages").asInt());
        assertEquals(List.of(s4.getTitle()), titles(list("?areaId=" + b.getId() + "&size=1&page=1")));

        // tham số sai -> 400
        MvcResult badStatus = list("?status=RUNNING");
        assertEquals(400, status(badStatus), describe(badStatus));
        MvcResult badRange = list("?from=" + today + "&to=" + today.minusDays(1));
        assertEquals(400, status(badRange), describe(badRange));
    }

    @Test
    @DisplayName("B4: GET /api/event-schedules — NORMAL_USER, GUARD -> 403; FACILITY_MANAGER, ADMIN -> 200")
    void listAllPermissions() throws Exception {
        assertEquals(403, status(send(get("/api/event-schedules"), userL1, null).andReturn()));
        assertEquals(403, status(send(get("/api/event-schedules"), guard, null).andReturn()));
        assertEquals(200, status(send(get("/api/event-schedules"), fm, null).andReturn()));
        assertEquals(200, status(send(get("/api/event-schedules"), admin, null).andReturn()));
    }

    // ================================================================== Dừng sự kiện (nút Dừng, Q7 / F6)

    @Test
    @DisplayName("Q7/F6: DISABLE ghi chú không bắt buộc (có nhập thì 10–500, sai -> 400 ERR_AREA_024); dừng -> phiên đóng, lịch ENDED_EARLY, khu hết mở. ENABLE thủ công -> 400 ERR_AREA_058 (X-01a)")
    void stopEventNoteOptional() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        var session = openEvent(area, now.minusHours(1), now.plusHours(2));
        AreaEventSchedule running = schedule(area, "Sự kiện đang diễn ra", AreaEventScheduleStatus.STARTED, now.minusHours(1), now.plusHours(2));
        running.setSession(session);
        eventScheduleRepository.save(running);
        session.setScheduleId(running.getId()); // phiên do lịch sinh ra (BR-ES-S1)
        sessionRepository.save(session);

        MvcResult shortNote = patchEventMode(fm, area, eventBody("DISABLE", null, "OTHER", "ngắn", dbVersion(area)));
        assertEquals(400, status(shortNote), describe(shortNote));
        assertEquals("ERR_AREA_024", errorCode(shortNote));
        assertTrue(Boolean.TRUE.equals(reload(area).getOpenToMembers()), "bị chặn thì khu vẫn mở");

        MvcResult stopped = patchEventMode(fm, area, eventBody("DISABLE", null, "OTHER", null, dbVersion(area)));
        assertEquals(200, status(stopped), describe(stopped));
        assertFalse(Boolean.TRUE.equals(reload(area).getOpenToMembers()), "dừng -> khu hết mở");
        assertNotNull(sessionRepository.findById(session.getId()).orElseThrow().getActualEnd(), "dừng -> phiên đóng");
        assertEquals(AreaEventScheduleStatus.ENDED_EARLY, eventScheduleRepository.findById(running.getId()).orElseThrow().getStatus());

        Area other = newArea(AreaLevel.CONFIDENTIAL);
        MvcResult enableNoNote = patchEventMode(fm, other, eventBody("ENABLE", now.plusHours(2), "OTHER", null, dbVersion(other)));
        assertEquals(400, status(enableNoNote), describe(enableNoNote));
        assertEquals("ERR_AREA_058", errorCode(enableNoNote), "X-01a: không còn bật sự kiện thủ công");
    }

    // ================================================================== V87

    @Test
    @DisplayName("V87: chạy nguyên văn SQL của file migration trên bảng tạm cùng tên (chưa có cột title) -> title = reason_label (trim, cắt 150), thiếu thì \"Sự kiện\", cột NOT NULL")
    void v87Backfill() throws Exception {
        String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(st -> {
            try {
                // Bảng tạm trùng tên che bảng thật trong transaction này (pg_temp đứng trước public), mất khi rollback
                jdbcTemplate.execute("CREATE TEMP TABLE area_event_schedules (id INT PRIMARY KEY, reason_label VARCHAR(255)) ON COMMIT DROP");
                jdbcTemplate.update("INSERT INTO area_event_schedules VALUES (1, 'Hội thảo khoa học'), (2, NULL), (3, '   '), (4, ?), (5, '  Ngày hội  ')",
                        "X".repeat(200));

                jdbcTemplate.execute(sql);

                Map<Integer, String> titles = new LinkedHashMap<>();
                jdbcTemplate.query("SELECT id, title FROM area_event_schedules ORDER BY id",
                        rs -> { titles.put(rs.getInt(1), rs.getString(2)); });
                assertEquals("Hội thảo khoa học", titles.get(1));
                assertEquals("Sự kiện", titles.get(2), "reason_label NULL -> Sự kiện");
                assertEquals("Sự kiện", titles.get(3), "reason_label rỗng -> Sự kiện");
                assertEquals("X".repeat(150), titles.get(4), "cắt còn 150 ký tự");
                assertEquals("Ngày hội", titles.get(5), "bỏ khoảng trắng hai đầu");
                assertEquals("NO", jdbcTemplate.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns WHERE table_schema LIKE 'pg_temp%' "
                                + "AND table_name = 'area_event_schedules' AND column_name = 'title'", String.class));

                jdbcTemplate.execute(sql); // chạy lại không lỗi
            } finally {
                st.setRollbackOnly();
            }
        });
        assertEquals("NO", jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'area_event_schedules' AND column_name = 'title'", String.class),
                "bảng thật đã có cột title NOT NULL (Flyway áp V87)");
    }
}
