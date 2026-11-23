package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.service.GuestVisitService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * V137: guest_visits.cancelled_by. Host tự huỷ -> cancelled_by = host, cancelledBySystem = false; hệ thống huỷ khi ADMIN
 * vô hiệu hoá / đổi loại khu (GuestVisitService.closeForAreaDeactivation) -> cancelled_by = ADMIN, cancelledBySystem = true;
 * lượt chưa huỷ -> null. Đọc qua GET /api/guest-visits/{id} (host, FM, ADMIN như trước, không đổi quyền).
 */
class GuestVisitCancelledByTest extends GuestTestSupport {

    private static final String BASE = "/api/guest-visits";

    @Autowired private GuestVisitService guestVisitService;

    private JsonNode detail(com.fa26se040.icss.entity.User actor, UUID id) throws Exception {
        MvcResult r = send(get(BASE + "/" + id), actor, null).andReturn();
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        return json(r).path("data");
    }

    private UUID cancelledByHost() throws Exception {
        MvcResult created = createVisit(hostL2, validBody()).andReturn();
        assertEquals(201, status(created), created.getResponse().getContentAsString());
        UUID id = UUID.fromString(json(created).path("data").path("id").asText());
        MvcResult r = send(patch(BASE + "/" + id + "/cancel"), hostL2, Map.of("version", 0L)).andReturn();
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        return id;
    }

    @Test
    @DisplayName("V137: host tự huỷ -> cancelled_by = host; DTO cancelledByName = tên host, cancelledBySystem = false (host, FM, ADMIN đều đọc được)")
    void hostCancel_recordsHost() throws Exception {
        UUID id = cancelledByHost();

        GuestVisit v = transactionTemplate.execute(tx -> {
            GuestVisit loaded = guestVisitRepository.findById(id).orElseThrow();
            loaded.getCancelledBy().getId(); // nạp trong transaction
            return loaded;
        });
        assertEquals(GuestVisitStatus.CANCELLED, v.getStatus());
        assertEquals(hostL2.getId(), v.getCancelledBy().getId());

        for (com.fa26se040.icss.entity.User reader : List.of(hostL2, fm, admin)) {
            JsonNode d = detail(reader, id);
            assertEquals(hostL2.getFullName(), d.path("cancelledByName").asText(), "đọc bởi " + reader.getRole());
            assertFalse(d.path("cancelledBySystem").asBoolean(true), "đọc bởi " + reader.getRole());
        }
    }

    @Test
    @DisplayName("V137: hệ thống huỷ (ADMIN vô hiệu hoá / đổi loại khu) -> cancelled_by = ADMIN, cancelledBySystem = true")
    void systemCancel_recordsAdmin() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        GuestVisit pending = newVisit(hostL2, GuestVisitStatus.PENDING, future(60), future(180), List.of(area), "Khách A");

        guestVisitService.closeForAreaDeactivation(List.of(pending.getId()), admin,
                "Khu vực bị vô hiệu hoá (kiểm thử V137)", OffsetDateTime.now());

        UUID cancelledBy = transactionTemplate.execute(tx ->
                guestVisitRepository.findById(pending.getId()).orElseThrow().getCancelledBy().getId());
        assertEquals(admin.getId(), cancelledBy);

        JsonNode d = detail(admin, pending.getId());
        assertEquals("CANCELLED", d.path("status").asText());
        assertEquals(admin.getFullName(), d.path("cancelledByName").asText());
        assertTrue(d.path("cancelledBySystem").asBoolean(false));
    }

    @Test
    @DisplayName("V137: lượt chưa huỷ -> cancelledByName, cancelledBySystem đều null")
    void notCancelled_nullFields() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        GuestVisit pending = newVisit(hostL2, GuestVisitStatus.PENDING, future(60), future(180), List.of(area), "Khách B");

        JsonNode d = detail(admin, pending.getId());
        assertTrue(d.path("cancelledByName").isNull());
        assertTrue(d.path("cancelledBySystem").isNull());
        assertNull(transactionTemplate.execute(tx ->
                guestVisitRepository.findById(pending.getId()).orElseThrow().getCancelledBy()));
    }
}
