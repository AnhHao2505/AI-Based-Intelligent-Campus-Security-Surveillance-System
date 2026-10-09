package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.enums.AreaLevel;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BR-GV-04: nguồn khu vực cho form lượt khách (GET /api/guest-visits/selectable-areas) — khu vực INTERNAL / CONTACT
 * đang hoạt động, GỒM khu vực INTERNAL (cờ explicit = false, không có trong /api/areas/available-for-request).
 */
class GuestSelectableAreasTest extends GuestTestSupport {

    private static final String URL = "/api/guest-visits/selectable-areas";

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
}
