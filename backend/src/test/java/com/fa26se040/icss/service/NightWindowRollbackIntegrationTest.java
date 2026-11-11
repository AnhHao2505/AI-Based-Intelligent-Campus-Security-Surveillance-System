package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.EventScheduleRequest;
import com.fa26se040.icss.dto.area.EventScheduleResponse;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * g) EV-N8 (V115): lỗi giữa chừng khi hệ thống xử lý lịch theo giờ ban đêm mới -> rollback cả việc lưu cấu hình: giá trị
 * trong DB và cache giữ nguyên, lịch đã cắt trước đó trở lại như cũ, không còn audit của lần xử lý dở.
 * Lỗi được gây ra bằng spy AuditService ném ở lần ghi audit huỷ lịch (lịch thứ 2 trong cùng khu). Lớp riêng vì @SpyBean
 * tạo Spring context riêng.
 */
class NightWindowRollbackIntegrationTest extends Step5bTestSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @SpyBean private AuditService auditServiceSpy;
    @Autowired private SystemConfigurationRepository systemConfigurationRepository;

    private static OffsetDateTime at(int plusDays, int hour, int minute) {
        return LocalDate.now(VN).plusDays(plusDays).atTime(hour, minute).atZone(VN).toOffsetDateTime();
    }

    private EventScheduleResponse create(Area area, OffsetDateTime start, OffsetDateTime end) {
        return areaService.createSchedule(area.getId(),
                new EventScheduleRequest("Sự kiện rollback", start, end, "SEMINAR", "Ghi chú kiểm thử rollback"), fm.getEmail());
    }

    @Test
    @DisplayName("g) START 22:00 -> 21:00, lịch 1 được cắt rồi lịch 2 lỗi khi ghi audit huỷ -> exception, cấu hình vẫn 22:00 (DB + cache), lịch 1 vẫn tới 21:30, không audit nào của lần xử lý")
    void failureMidway_rollsBackConfigAndSchedules() {
        systemConfigService.update(ConfigKey.AI_AFTER_HOUR_START.getKey(), "22:00", admin.getEmail());
        assertEquals("06:00", systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END));
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        EventScheduleResponse first = create(area, at(2, 18, 0), at(2, 21, 30));
        EventScheduleResponse second = create(area, at(3, 21, 10), at(3, 21, 40));

        doThrow(new IllegalStateException("audit down")).when(auditServiceSpy).record(
                eq(AuditTargetType.AREA_EVENT_SCHEDULE), eq(AuditAction.CANCEL), anyString(), any(), any(), any(), any(),
                anyString(), any(AuditActor.class));
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    systemConfigService.update(ConfigKey.AI_AFTER_HOUR_START.getKey(), "21:00", admin.getEmail()));
            assertEquals("audit down", ex.getMessage());
        } finally {
            reset(auditServiceSpy);
        }

        assertEquals("22:00", systemConfigurationRepository.findById(ConfigKey.AI_AFTER_HOUR_START.getKey()).orElseThrow().getConfigValue());
        assertEquals("22:00", systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START), "cache phải về giá trị cũ");

        AreaEventSchedule firstAfter = eventScheduleRepository.findById(first.id()).orElseThrow();
        assertEquals(at(2, 21, 30).toInstant(), firstAfter.getEndAt().toInstant(), "lịch đã cắt trong transaction lỗi phải rollback");
        assertEquals(AreaEventScheduleStatus.SCHEDULED, eventScheduleRepository.findById(second.id()).orElseThrow().getStatus());
        assertTrue(auditsForTarget(first.id().toString()).stream()
                .noneMatch(a -> AreaService.AFTER_HOURS_CHANGE_SOURCE.equals(a.getActorSource())));
    }
}
