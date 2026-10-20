package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.guest.GuestTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Checklist 6.1 (V82): độ dài lý do đọc từ REASON_MIN_LENGTH / REASON_MAX_LENGTH mỗi lần kiểm. ADMIN sửa là có hiệu lực
 * ngay (không restart) ở cả 3 cách kiểm: {@code @ReasonLength} trên DTO, kiểm chéo ở DTO (từ chối đơn) và kiểm trong service
 * (vô hiệu hoá khu vực). Sửa cấu hình sai (min ≥ max, max > độ dài cột) -> 400, giá trị giữ nguyên.
 */
class ReasonLengthConfigIntegrationTest extends GuestTestSupport {

    @Autowired private JdbcTemplate jdbc;

    /** Đúng 15 / 20 ký tự (String.length), có dấu tiếng Việt như lý do thật. */
    private static final String REASON_15 = "Lý do mười lăm.";
    private static final String REASON_20 = "Lý do đủ hai mươi kt";

    /** Đặt lại mặc định: max trước rồi min để luôn thoả min < max, kể cả khi lần chạy trước dừng giữa chừng. */
    @BeforeEach
    @AfterEach
    void resetReasonLimits() {
        setConfig(ConfigKey.REASON_MAX_LENGTH, ConfigKey.REASON_MAX_LENGTH.getDefaultValue());
        setConfig(ConfigKey.REASON_MIN_LENGTH, ConfigKey.REASON_MIN_LENGTH.getDefaultValue());
    }

    private MvcResult patchConfig(User actor, ConfigKey key, String value, String reason) throws Exception {
        return send(patch("/api/system-configurations/{key}", key.getKey()), actor,
                Map.of("configValue", value, "reason", reason)).andReturn();
    }

    private MvcResult reasonLimits(User actor) throws Exception {
        return send(get("/api/system-configurations/reason-limits"), actor, null).andReturn();
    }

    private void assertLimits(User actor, int min, int max) throws Exception {
        MvcResult r = reasonLimits(actor);
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        JsonNode data = json(r).path("data");
        assertEquals(min, data.path("minLength").asInt(), data.toString());
        assertEquals(max, data.path("maxLength").asInt(), data.toString());
    }

    private AccessRequest pendingRequest(Area area, User requester) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS);
        return accessRequestRepository.save(AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn kiểm thử độ dài lý do " + suffix)
                .startTime(start)
                .endTime(start.plusHours(2))
                .status(RequestStatus.PENDING)
                .build());
    }

    private Long areaVersion(Area area) {
        return jdbc.queryForObject("SELECT version FROM areas WHERE id = ?", Long.class, area.getId());
    }

    @Test
    @DisplayName("GET /reason-limits: mọi role đăng nhập đọc được, chỉ 2 giá trị; chưa đăng nhập -> 401")
    void reasonLimits_readableByEveryRole() throws Exception {
        assertEquals(15, REASON_15.length());
        assertEquals(20, REASON_20.length());
        for (User u : List.of(admin, fm, guard, hostL1)) {
            assertLimits(u, 10, 500);
            List<String> fields = new java.util.ArrayList<>();
            json(reasonLimits(u)).path("data").fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("minLength", "maxLength"), fields, u.getRole().name());
        }
        MvcResult anonymous = mockMvc.perform(get("/api/system-configurations/reason-limits")).andReturn();
        assertEquals(401, status(anonymous));
    }

    @Test
    @DisplayName("ADMIN đổi REASON_MIN_LENGTH 10 -> 20: lý do 15 ký tự bị 400, 20 ký tự qua, không restart (DTO, kiểm chéo DTO, service)")
    void raiseMinimum_appliesImmediately() throws Exception {
        MvcResult raise = patchConfig(admin, ConfigKey.REASON_MIN_LENGTH, "20", "Nâng độ dài tối thiểu của lý do");
        assertEquals(200, status(raise), raise.getResponse().getContentAsString());
        assertLimits(hostL1, 20, 500);

        // 1. @ReasonLength trên DTO (SystemConfigUpdateRequest)
        MvcResult dtoShort = patchConfig(admin, ConfigKey.REASON_MAX_LENGTH, "500", REASON_15);
        assertEquals(400, status(dtoShort));
        assertEquals("VALIDATION_ERROR", errorCode(dtoShort));
        assertEquals("Lý do phải có từ 20 đến 500 ký tự", json(dtoShort).path("data").path("reason").asText());
        assertEquals(200, status(patchConfig(admin, ConfigKey.REASON_MAX_LENGTH, "500", REASON_20)));

        // 2. Kiểm chéo ở DTO: FM từ chối đơn (AccessRequestReviewRequest)
        AccessRequest request = pendingRequest(contactArea, hostL2);
        MvcResult rejectShort = send(patch("/api/access-requests/{id}/review", request.getId()), fm,
                Map.of("status", "REJECTED", "rejectionReason", REASON_15)).andReturn();
        assertEquals(400, status(rejectShort));
        assertEquals("VALIDATION_ERROR", errorCode(rejectShort));
        assertEquals("Lý do từ chối phải có từ 20 đến 500 ký tự khi từ chối yêu cầu",
                json(rejectShort).path("data").path("rejectionReason").asText());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, request.getId()));
        MvcResult rejectOk = send(patch("/api/access-requests/{id}/review", request.getId()), fm,
                Map.of("status", "REJECTED", "rejectionReason", REASON_20)).andReturn();
        assertEquals(200, status(rejectOk), rejectOk.getResponse().getContentAsString());
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, request.getId()));

        // 3. Kiểm trong service: ADMIN vô hiệu hoá khu vực (ERR_AREA_050)
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        MvcResult deactivateShort = send(post("/api/areas/{id}/deactivate", area.getId()), admin,
                Map.of("reason", REASON_15, "version", areaVersion(area))).andReturn();
        assertEquals(400, status(deactivateShort));
        assertEquals("ERR_AREA_050", errorCode(deactivateShort));
        assertEquals("Lý do phải có từ 20 đến 500 ký tự", message(deactivateShort));
        MvcResult deactivateOk = send(post("/api/areas/{id}/deactivate", area.getId()), admin,
                Map.of("reason", REASON_20, "version", areaVersion(area))).andReturn();
        assertEquals(200, status(deactivateOk), deactivateOk.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Sửa cấu hình độ dài lý do sai -> 400 kèm thông báo rõ, giá trị giữ 10 / 500")
    void invalidLimits_rejected() throws Exception {
        String reason = "Thử giá trị độ dài lý do không hợp lệ";

        MvcResult minNotBelowMax = patchConfig(admin, ConfigKey.REASON_MIN_LENGTH, "500", reason);
        assertEquals(400, status(minNotBelowMax));
        assertEquals("Độ dài tối thiểu của lý do (500) phải nhỏ hơn độ dài tối đa đang cấu hình (500)", message(minNotBelowMax));

        MvcResult minZero = patchConfig(admin, ConfigKey.REASON_MIN_LENGTH, "0", reason);
        assertEquals(400, status(minZero));
        assertEquals("Độ dài tối thiểu của lý do phải từ 1 ký tự trở lên", message(minZero));

        MvcResult maxOverColumn = patchConfig(admin, ConfigKey.REASON_MAX_LENGTH, "501", reason);
        assertEquals(400, status(maxOverColumn));
        assertEquals("Độ dài tối đa của lý do không được vượt quá 500 ký tự (độ dài cột lưu lý do trong cơ sở dữ liệu)",
                message(maxOverColumn));

        MvcResult maxNotAboveMin = patchConfig(admin, ConfigKey.REASON_MAX_LENGTH, "10", reason);
        assertEquals(400, status(maxNotAboveMin));
        assertEquals("Độ dài tối đa của lý do (10) phải lớn hơn độ dài tối thiểu đang cấu hình (10)", message(maxNotAboveMin));

        assertLimits(admin, 10, 500);
        assertTrue(systemConfigService.getInt(ConfigKey.REASON_MIN_LENGTH) == 10
                && systemConfigService.getInt(ConfigKey.REASON_MAX_LENGTH) == 500);
    }
}
