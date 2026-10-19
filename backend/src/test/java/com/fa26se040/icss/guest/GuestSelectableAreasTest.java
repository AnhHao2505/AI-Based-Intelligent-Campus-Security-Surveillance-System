package com.fa26se040.icss.guest;

import com.fa26se040.icss.dto.guest.GuestSelectableAreaResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.service.GuestVisitService;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BR-GV-04 (V83): nguồn khu vực cho form lượt khách (GET /api/guest-visits/selectable-areas) — khu vực Bảo mật
 * (CONFIDENTIAL, gộp Nội bộ + Liên hệ trước) đang hoạt động.
 */
class GuestSelectableAreasTest extends GuestTestSupport {

    private static final String URL = "/api/guest-visits/selectable-areas";

    @Autowired private GuestVisitService guestVisitService;

    private GuestSelectableAreaResponse find(List<GuestSelectableAreaResponse> list, Area area) {
        UUID id = area.getId();
        return list.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private Set<String> ids(MvcResult r) throws Exception {
        Set<String> ids = new HashSet<>();
        for (JsonNode a : json(r).path("data")) {
            ids.add(a.path("id").asText());
        }
        return ids;
    }

    @Test
    @DisplayName("GV-04 (V83): danh sách có mọi khu Bảo mật đang hoạt động; không có HIGHLY, PUBLIC, khu vực tắt, khu vực đã xoá mềm")
    void selectableAreas_followBrGv04() throws Exception {
        Area inactiveInternal = newArea(AreaLevel.CONFIDENTIAL);
        inactiveInternal.setIsActive(false);
        areaRepository.save(inactiveInternal);
        Area deletedContact = newArea(AreaLevel.CONFIDENTIAL);
        deletedContact.setIsActive(false);
        deletedContact.setDeletedAt(OffsetDateTime.now());
        areaRepository.save(deletedContact);

        MvcResult r = send(get(URL), hostL2, null).andReturn();

        assertEquals(200, status(r), r.getResponse().getContentAsString());
        Set<String> ids = ids(r);
        assertTrue(ids.contains(internalArea.getId().toString()), "khu Bảo mật (Nội bộ cũ) phải xuất hiện");
        assertTrue(ids.contains(contactArea.getId().toString()), "khu Bảo mật (Liên hệ trước cũ) phải xuất hiện");
        assertFalse(ids.contains(highlyArea.getId().toString()), "HIGHLY không nhận khách");
        assertFalse(ids.contains(publicArea.getId().toString()), "PUBLIC không nhận khách");
        assertFalse(ids.contains(inactiveInternal.getId().toString()), "khu vực tắt không xuất hiện");
        assertFalse(ids.contains(deletedContact.getId().toString()), "khu vực đã xoá mềm không xuất hiện");
        for (JsonNode a : json(r).path("data")) {
            assertEquals("CONFIDENTIAL", a.path("areaLevel").asText());
        }
    }

    @Test
    @DisplayName("GV-04: khu vực Bảo mật lấy từ danh sách tạo được lượt khách (201); cùng quyền với tạo lượt — GUARD 403")
    void internalAreaFromList_canCreateVisit_guardForbidden() throws Exception {
        assertTrue(ids(send(get(URL), hostL2, null).andReturn()).contains(internalArea.getId().toString()));

        MvcResult created = send(post("/api/guest-visits"), hostL2, createBody(future(0), future(120), List.of(internalArea),
                List.of(guestInput("Khách form", null)))).andReturn();
        assertEquals(201, status(created), created.getResponse().getContentAsString());

        assertEquals(403, status(send(get(URL), guard, null).andReturn()));
    }

    // ------------------------------------------------------------------ BR-GV-06 theo khung giờ (hostCanInvite)

    @Test
    @DisplayName("GV-06: khu Bảo mật cấp 2, host cấp 2 -> hostCanInvite true, không reasonCode")
    void window_internalEnoughLevel_canInvite() {
        var list = guestVisitService.listSelectableAreas(hostL2.getEmail(), future(0), future(120));

        GuestSelectableAreaResponse a = find(list, internalArea);
        assertEquals(Boolean.TRUE, a.hostCanInvite());
        assertNull(a.reasonCode());
    }

    /** V83: chủ nhà cấp 1 (thiếu cấp với khu Bảo mật) chỉ có khi hạ GUEST_HOST_MIN_LEVEL xuống 1; dùng để kiểm đường AP. */
    private void allowLevel1Hosts() {
        setConfig(com.fa26se040.icss.enums.ConfigKey.GUEST_HOST_MIN_LEVEL, "1");
    }

    @Test
    @DisplayName("GV-06 (V83): khu Bảo mật (Liên hệ trước cũ): host cấp 2 không AP -> true (đủ cấp là đủ); host cấp 1 không AP -> false + ERR_GUEST_014")
    void window_contactWithoutAp_levelDecides() {
        var level2 = guestVisitService.listSelectableAreas(hostL2.getEmail(), future(0), future(120));
        assertEquals(Boolean.TRUE, find(level2, contactArea).hostCanInvite());
        assertNull(find(level2, contactArea).reasonCode());

        allowLevel1Hosts();
        var level1 = guestVisitService.listSelectableAreas(hostL1.getEmail(), future(0), future(120));
        assertEquals(Boolean.FALSE, find(level1, contactArea).hostCanInvite());
        assertEquals("ERR_GUEST_014", find(level1, contactArea).reasonCode());
    }

    @Test
    @DisplayName("GV-06: host thiếu cấp, AP chỉ phủ một phần khung giờ -> false; AP phủ trọn khung giờ -> true; đổi khung giờ thì kết quả đổi theo")
    void window_apCoverage_followsWindow() {
        allowLevel1Hosts();
        newAp(contactArea, hostL1, future(0), future(60));

        var partial = guestVisitService.listSelectableAreas(hostL1.getEmail(), future(0), future(120));
        assertEquals(Boolean.FALSE, find(partial, contactArea).hostCanInvite());
        assertEquals("ERR_GUEST_014", find(partial, contactArea).reasonCode());

        var covered = guestVisitService.listSelectableAreas(hostL1.getEmail(), future(0), future(60));
        assertEquals(Boolean.TRUE, find(covered, contactArea).hostCanInvite());
        assertNull(find(covered, contactArea).reasonCode());
    }

    @Test
    @DisplayName("GV-01: host dưới cấp tối thiểu -> mọi khu false + ERR_GUEST_002")
    void window_hostNotEligible_allBlocked() {
        var list = guestVisitService.listSelectableAreas(hostL1.getEmail(), future(0), future(120));

        assertFalse(list.isEmpty());
        for (GuestSelectableAreaResponse a : list) {
            assertEquals(Boolean.FALSE, a.hostCanInvite(), a.name());
            assertEquals("ERR_GUEST_002", a.reasonCode(), a.name());
        }
    }

    @Test
    @DisplayName("API: không gửi giờ / gửi thiếu một mốc -> không có hostCanInvite, reasonCode (tương thích); đủ hai mốc -> có; kết thúc ≤ bắt đầu -> 400 ERR_GUEST_011")
    void api_windowParams_compatibleAndValidated() throws Exception {
        String start = future(0).toString();
        String end = future(120).toString();

        MvcResult none = send(get(URL), hostL2, null).andReturn();
        MvcResult onlyStart = send(get(URL).param("startTime", start), hostL2, null).andReturn();
        for (MvcResult r : List.of(none, onlyStart)) {
            assertEquals(200, status(r), r.getResponse().getContentAsString());
            for (JsonNode a : json(r).path("data")) {
                assertFalse(a.has("hostCanInvite"), a.toString());
                assertFalse(a.has("reasonCode"), a.toString());
            }
        }

        // host thiếu cấp (V83: chỉ có khi GUEST_HOST_MIN_LEVEL = 1) để thấy reasonCode
        allowLevel1Hosts();
        MvcResult withWindow = send(get(URL).param("startTime", start).param("endTime", end), hostL1, null).andReturn();
        assertEquals(200, status(withWindow), withWindow.getResponse().getContentAsString());
        boolean sawContact = false;
        for (JsonNode a : json(withWindow).path("data")) {
            assertTrue(a.has("hostCanInvite"), a.toString());
            if (contactArea.getId().toString().equals(a.path("id").asText())) {
                sawContact = true;
                assertFalse(a.path("hostCanInvite").asBoolean());
                assertEquals("ERR_GUEST_014", a.path("reasonCode").asText());
            }
        }
        assertTrue(sawContact);

        MvcResult reversed = send(get(URL).param("startTime", end).param("endTime", start), hostL2, null).andReturn();
        assertEquals(400, status(reversed), reversed.getResponse().getContentAsString());
        assertTrue(reversed.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("ERR_GUEST_011"));
    }
}
