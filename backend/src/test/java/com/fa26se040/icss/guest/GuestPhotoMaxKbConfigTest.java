package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.service.GuestPhotoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * V141: dung lượng tối đa ảnh khách đọc từ GUEST_PHOTO_MAX_KB (mặc định 2048, khoảng 100–10240), thay property
 * icss.guest.photo-max-kb. Sửa qua PATCH /api/system-configurations/GUEST_PHOTO_MAX_KB có hiệu lực ngay, không restart,
 * cho mọi chỗ dùng giới hạn: host gửi ảnh khi tạo lượt (POST /api/guest-visits), host gắn ảnh cho khách chưa có ảnh và
 * thay ảnh (POST /api/guest-visits/{visitId}/guests/{guestId}/photo), và /eligibility (giới hạn cho form).
 * ADMIN không còn gắn ảnh khách (403) nên không có đường gắn ảnh thứ ba.
 */
class GuestPhotoMaxKbConfigTest extends GuestTestSupport {

    private static final String KEY = ConfigKey.GUEST_PHOTO_MAX_KB.getKey();

    @Autowired private GuestPhotoService photoService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restoreDefault() {
        // GuestTestSupport cũng đưa về mặc định trước mỗi test; trả lại ngay để lớp test khác (vd GuestSchemaTest) thấy 2048
        setConfig(ConfigKey.GUEST_PHOTO_MAX_KB, ConfigKey.GUEST_PHOTO_MAX_KB.getDefaultValue());
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult patchLimit(String value) throws Exception {
        return send(patch("/api/system-configurations/" + KEY), admin,
                Map.of("configValue", value, "reason", "Kiểm thử giới hạn dung lượng ảnh khách")).andReturn();
    }

    private int eligibilityLimit() throws Exception {
        MvcResult r = send(get("/api/guest-visits/eligibility"), hostL2, null).andReturn();
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        return json(r).path("data").path("photoMaxKb").asInt();
    }

    /** Host tạo lượt 1 khách, ảnh JPG đúng {@code bytes} byte. */
    private MvcResult createWithPhoto(int bytes) throws Exception {
        Map<String, Object> body = createBody(future(60), future(180), List.of(internalArea),
                List.of(guestInput("Khách Giới Hạn", null)));
        return createVisitRaw(hostL2, withConsent(body),
                List.of(photo("khach.jpg", "image/jpeg", new byte[bytes]))).andReturn();
    }

    /** Host gắn / thay ảnh cho một khách của lượt mình. */
    private MvcResult attach(User actor, GuestVisit v, Guest g, int bytes) throws Exception {
        return mockMvc.perform(multipart("/api/guest-visits/" + v.getId() + "/guests/" + g.getId() + "/photo")
                .file(new MockMultipartFile("file", "khach.jpg", "image/jpeg", new byte[bytes]))
                .param("consentConfirmed", "true")
                .param("consentNoticeVersion", systemConfigService.getString(ConfigKey.GUEST_CONSENT_NOTICE_VERSION))
                .header("Authorization", bearer(actor))).andReturn();
    }

    private void assertTooBig(MvcResult r, int expectedKb) throws Exception {
        assertEquals(400, status(r), r.getResponse().getContentAsString());
        assertEquals("ERR_GUEST_033", errorCode(r));
        assertTrue(message(r).contains("tối đa " + expectedKb + " KB"), message(r));
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("V141: khoá GUEST_PHOTO_MAX_KB seed 2048 KB, INTEGER, khoảng 100–10240, nhóm GUEST; service và /eligibility đọc đúng giá trị")
    void seededConfig() throws Exception {
        Map<String, Object> row = jdbc.queryForMap("SELECT config_value, data_type, min_value, max_value, unit, config_group, editable "
                + "FROM system_configurations WHERE config_key = ?", KEY);
        assertEquals("2048", row.get("config_value"));
        assertEquals("INTEGER", row.get("data_type"));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) row.get("min_value")));
        assertEquals(0, new BigDecimal("10240").compareTo((BigDecimal) row.get("max_value")));
        assertEquals("KB", row.get("unit"));
        assertEquals("GUEST", row.get("config_group"));
        assertEquals(Boolean.TRUE, row.get("editable"));
        assertEquals(2048, photoService.getPhotoMaxKb());
        assertEquals(2048, eligibilityLimit());
    }

    @Test
    @DisplayName("V141: đổi GUEST_PHOTO_MAX_KB qua System Configuration có hiệu lực ngay cho tạo lượt, gắn ảnh, thay ảnh và /eligibility")
    void updatedLimit_appliesImmediatelyToAllPhotoPaths() throws Exception {
        assertEquals(200, status(patchLimit("100")));
        assertEquals(100, eligibilityLimit());

        // Tạo lượt: ảnh 100 KB + 1 byte bị từ chối, câu nêu 100 KB; đúng 100 KB thì tạo được
        assertTooBig(createWithPhoto(100 * 1024 + 1), 100);
        MvcResult created = createWithPhoto(100 * 1024);
        assertEquals(201, status(created), created.getResponse().getContentAsString());

        // Gắn ảnh cho khách chưa có ảnh
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.PENDING, future(300), future(420), List.of(internalArea), "Khách Gắn Ảnh");
        Guest g = guestsOf(v.getId()).get(0);
        assertTooBig(attach(hostL2, v, g, 100 * 1024 + 1), 100);
        MvcResult attached = attach(hostL2, v, g, 100 * 1024);
        assertEquals(200, status(attached), attached.getResponse().getContentAsString());

        // Thay ảnh (khách đã có ảnh): vẫn theo giới hạn 100 KB
        assertTooBig(attach(hostL2, v, g, 150 * 1024), 100);

        // Nâng lên 300 KB: cùng ảnh 150 KB thay được ngay, /eligibility trả 300
        assertEquals(200, status(patchLimit("300")));
        assertEquals(300, eligibilityLimit());
        MvcResult replaced = attach(hostL2, v, g, 150 * 1024);
        assertEquals(200, status(replaced), replaced.getResponse().getContentAsString());
        assertTooBig(attach(hostL2, v, g, 300 * 1024 + 1), 300);
    }

    @Test
    @DisplayName("V141: lưu GUEST_PHOTO_MAX_KB ngoài 100–10240 hoặc không phải số nguyên -> 400, giá trị và lịch sử thay đổi giữ nguyên")
    void outOfBoundsValue_rejectedOnSave() throws Exception {
        Integer logsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM system_configuration_change_logs WHERE config_key = ?", Integer.class, KEY);

        MvcResult low = patchLimit("99");
        assertEquals(400, status(low));
        assertEquals("Giá trị không được nhỏ hơn 100", message(low));
        MvcResult high = patchLimit("10241");
        assertEquals(400, status(high));
        assertEquals("Giá trị không được lớn hơn 10240", message(high));
        MvcResult decimal = patchLimit("2048.5");
        assertEquals(400, status(decimal));
        assertEquals("Giá trị cấu hình phải là số nguyên", message(decimal));

        assertEquals("2048", jdbc.queryForObject("SELECT config_value FROM system_configurations WHERE config_key = ?", String.class, KEY));
        assertEquals(logsBefore, jdbc.queryForObject(
                "SELECT count(*) FROM system_configuration_change_logs WHERE config_key = ?", Integer.class, KEY));
        assertEquals(2048, photoService.getPhotoMaxKb());
    }

    @Test
    @DisplayName("V141: giá trị cũ ngoài khoảng còn trong DB (sửa tay) -> dùng mặc định 2048 của ConfigKey (getIntWithinBounds), không chặn nhầm ảnh hợp lệ")
    void legacyOutOfRangeValue_fallsBackToDefault() throws Exception {
        jdbc.update("UPDATE system_configurations SET config_value = '50' WHERE config_key = ?", KEY);
        systemConfigService.reloadCache();

        assertEquals(Integer.parseInt(ConfigKey.GUEST_PHOTO_MAX_KB.getDefaultValue()), photoService.getPhotoMaxKb());
        assertEquals(2048, eligibilityLimit());
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.PENDING, future(300), future(420), List.of(internalArea), "Khách Dữ Liệu Cũ");
        MvcResult r = attach(hostL2, v, guestsOf(v.getId()).get(0), 1000 * 1024);
        assertEquals(200, status(r), r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("ADMIN không còn gắn ảnh khách (403): đường nhận ảnh chỉ còn tạo lượt và gắn / thay ảnh của host")
    void adminCannotAttachPhoto() throws Exception {
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.PENDING, future(300), future(420), List.of(internalArea), "Khách Admin");
        MvcResult r = attach(admin, v, guestsOf(v.getId()).get(0), 1024);
        assertEquals(403, status(r));
    }
}
