package com.fa26se040.icss.service;

import com.fa26se040.icss.context.AuditContext;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

/**
 * Step 5b — TC-11b (BR-TC-11): lỗi xảy ra giữa chừng khi hệ thống huỷ đơn -> rollback toàn bộ, không thông báo.
 * Tách class riêng vì @SpyBean AuditService tạo Spring context riêng.
 * Cách ép lỗi: audit thứ 2 có actor SYSTEM nguồn AREA_TYPE_CHANGE (tức lúc huỷ đơn thứ 2) ném RuntimeException.
 */
public class Step5bAreaTypeChangeRollbackTest extends Step5bTestSupport {

    @SpyBean
    private AuditService auditService;

    @AfterEach
    void resetSpy() {
        Mockito.reset(auditService);
    }

    @Test
    @DisplayName("TC-11b (BR-TC-11): lỗi khi huỷ đơn thứ 2 -> rollback toàn bộ (loại, cấp, đơn thứ 1), không audit, không thông báo")
    void tc11b_BR_TC_11_failureMidway_rollsBackEverything() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AccessRequest r1 = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(1), now.plusHours(2));
        AccessRequest r2 = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(3), now.plusHours(4));
        long auditBefore = auditCountForArea(area);

        AtomicInteger systemCancelAudits = new AtomicInteger();
        doAnswer(inv -> {
            AuditActor actor = inv.getArgument(8);
            AuditActor effective = actor != null ? actor : AuditContext.getCurrentActor();
            if (effective != null && AREA_TYPE_CHANGE_SOURCE.equals(effective.getActorSource())
                    && systemCancelAudits.incrementAndGet() == 2) {
                throw new RuntimeException("TC-11b: ép lỗi khi hệ thống huỷ đơn thứ 2");
            }
            return inv.callRealMethod();
        }).when(auditService).record(any(), any(), any(), any(), any(), any(), any(), any(), nullable(AuditActor.class));

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, TYPE_CHANGE_REASON, apiVersion(area));

        assertTrue(status(r) >= 500, "Lỗi giữa chừng phải trả 5xx: " + describe(r));
        assertEquals(2, systemCancelAudits.get(), "Tiền đề: luồng đổi loại phải chạy tới lần huỷ đơn thứ 2");

        Area after = reload(area);
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, after.getAreaLevel(), "Loại khu vực phải rollback");
        assertEquals(2, after.getAreaAccessLevel(), "Cấp phải rollback");
        assertFalse(after.getExplicitAuthorizationRequired(), "Cờ cần đơn phải rollback");
        assertEquals(RequestStatus.APPROVED, requestStatus(r1), "Đơn thứ 1 đã huỷ trong transaction phải rollback");
        assertEquals(RequestStatus.APPROVED, requestStatus(r2));
        assertEquals(auditBefore, auditCountForArea(area), "Rollback thì không còn audit nào");
        assertTrue(auditsForTarget(r1.getId().toString()).isEmpty());
        assertEquals(0, countNotifications(userL2, fm, fm2), "Rollback thì không thông báo nào được gửi");
        assertTrue(notificationsOf(userL2, NotificationType.REQUEST_SYSTEM_CANCELLED).isEmpty());

        for (AccessRequest req : List.of(r1, r2)) {
            Map<String, Object> row = cancelColumns(req);
            assertNull(row.get("cancel_source"));
            assertNull(row.get("cancel_reason"));
        }
    }
}
