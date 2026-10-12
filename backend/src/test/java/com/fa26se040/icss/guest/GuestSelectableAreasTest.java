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
 * BR-GV-04: nguồn khu vực cho form lượt khách (GET /api/guest-visits/selectable-areas) — khu vực INTERNAL / CONTACT
 * đang hoạt động, GỒM khu vực INTERNAL (cờ explicit = false, không có trong /api/areas/available-for-request).
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
    @DisplayName("GV-04: danh sách có INTERNAL + CONTACT đang hoạt động; không có HIGHLY, PUBLIC, khu vực tắt, khu vực đã xoá mềm")
    void selectableAreas_followBrGv04() throws Exception {
        Area inactiveInternal = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        inactiveInternal.setIsActive(false);
        areaRepository.save(inactiveInternal);
        Area deletedContact = newArea(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, 2, true);
        deletedContact.setIsActive(false);
        deletedContact.setDeletedAt(OffsetDateTime.now());
        areaRepository.save(deletedContact);

        MvcResult r = send(get(URL), hostL2, null).andReturn();

        assertEquals(200, status(r), r.getResponse().getContentAsString());
        Set<String> ids = ids(r);
        assertTrue(ids.contains(internalArea.getId().toString()), "INTERNAL phải xuất hiện");
        assertTrue(ids.contains(contactArea.getId().toString()), "CONTACT phải xuất hiện");
        assertFalse(ids.contains(highlyArea.getId().toString()), "HIGHLY không nhận khách");
        assertFalse(ids.contains(publicArea.getId().toString()), "PUBLIC không nhận khách");
        assertFalse(ids.contains(inactiveInternal.getId().toString()), "khu vực tắt không xuất hiện");
        assertFalse(ids.contains(deletedContact.getId().toString()), "khu vực đã xoá mềm không xuất hiện");
        for (JsonNode a : json(r).path("data")) {
            String level = a.path("areaLevel").asText();
            assertTrue(level.equals("INTERNAL_CONFIDENTIAL") || level.equals("CONFIDENTIAL_CONTACT_REQUIRED"), level);
        }

        // Đối chiếu: danh sách xin truy cập không có INTERNAL (lý do form khách trước đây thiếu khu vực này)
        MvcResult requestList = send(get("/api/areas/available-for-request"), hostL2, null).andReturn();
        assertEquals(200, status(requestList));
        assertFalse(ids(requestList).contains(internalArea.getId().toString()));
    }

    @Test
    @DisplayName("GV-04: khu vực INTERNAL lấy từ danh sách tạo được lượt khách (201); cùng quyền với tạo lượt — GUARD 403")
    void internalAreaFromList_canCreateVisit_guardForbidden() throws Exception {
        assertTrue(ids(send(get(URL), hostL2, null).andReturn()).contains(internalArea.getId().toString()));

        MvcResult created = send(post("/api/guest-visits"), hostL2, createBody(future(0), future(120), List.of(internalArea),
                List.of(guestInput("Khách form", null)))).andReturn();
        assertEquals(201, status(created), created.getResponse().getContentAsString());

        assertEquals(403, status(send(get(URL), guard, null).andReturn()));
    }

    // ------------------------------------------------------------------ BR-GV-06 theo khung giờ (hostCanInvite)

    @Test
    @DisplayName("GV-06: khu INTERNAL cấp 2, host cấp 2 -> hostCanInvite true, không reasonCode")
    void window_internalEnoughLevel_canInvite() {
        var list = guestVisitService.listSelectableAreas(hostL2.getEmail(), future(0), future(120));

        GuestSelectableAreaResponse a = find(list, internalArea);
        assertEquals(Boolean.TRUE, a.hostCanInvite());
        assertNull(a.reasonCode());
    }

    @Test
    @DisplayName("GV-06: khu CONTACT (bắt buộc chỉ định), host không có AP -> false + ERR_GUEST_014 (đủ cấp vẫn không tính)")
    void window_contactWithoutAp_cannotInvite() {
        var list = guestVisitService.listSelectableAreas(hostL2.getEmail(), future(0), future(120));

        GuestSelectableAreaResponse a = find(list, contactArea);
        assertEquals(Boolean.FALSE, a.hostCanInvite());
        assertEquals("ERR_GUEST_014", a.reasonCode());
    }

    @Test
    @DisplayName("GV-06: AP chỉ phủ một phần khung giờ -> false; AP phủ trọn khung giờ -> true; đổi khung giờ thì kết quả đổi theo")
    void window_apCoverage_followsWindow() {
        newAp(contactArea, hostL2, future(0), future(60));

        var partial = guestVisitService.listSelectableAreas(hostL2.getEmail(), future(0), future(120));
        assertEquals(Boolean.FALSE, find(partial, contactArea).hostCanInvite());
        assertEquals("ERR_GUEST_014", find(partial, contactArea).reasonCode());

        var covered = guestVisitService.listSelectableAreas(hostL2.getEmail(), future(0), future(60));
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

        MvcResult withWindow = send(get(URL).param("startTime", start).param("endTime", end), hostL2, null).andReturn();
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
