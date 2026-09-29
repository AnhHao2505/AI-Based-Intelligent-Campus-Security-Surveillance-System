package com.fa26se040.icss.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaExceptionTest {

    @Test
    @DisplayName("DF-U1: Format message thay thế toàn bộ placeholder có tên và số, không để sót ký tự {")
    void testFormatMessage_ReplacesAllPlaceholders() {
        // ERR_AREA_009: {n}
        AreaException ex009 = new AreaException(AreaErrorCode.ERR_AREA_009, 3);
        assertTrue(ex009.getMessage().contains("3"));
        assertFalse(ex009.getMessage().contains("{"), "Không được chứa '{': " + ex009.getMessage());

        // ERR_AREA_010: {n}
        AreaException ex010 = new AreaException(AreaErrorCode.ERR_AREA_010, 5);
        assertTrue(ex010.getMessage().contains("5"));
        assertFalse(ex010.getMessage().contains("{"), "Không được chứa '{': " + ex010.getMessage());

        // ERR_AREA_027: {maxHours}
        AreaException ex027 = new AreaException(AreaErrorCode.ERR_AREA_027, 4);
        assertTrue(ex027.getMessage().contains("4"));
        assertFalse(ex027.getMessage().contains("{"), "Không được chứa '{': " + ex027.getMessage());

        // ERR_AREA_030: {status}
        AreaException ex030 = new AreaException(AreaErrorCode.ERR_AREA_030, "đang bật");
        assertTrue(ex030.getMessage().contains("đang bật"));
        assertFalse(ex030.getMessage().contains("{"), "Không được chứa '{': " + ex030.getMessage());

        // ERR_AREA_031: {minMinutes}
        AreaException ex031 = new AreaException(AreaErrorCode.ERR_AREA_031, 30);
        assertTrue(ex031.getMessage().contains("30"));
        assertFalse(ex031.getMessage().contains("{"), "Không được chứa '{': " + ex031.getMessage());

        // ERR_AREA_033: {maxLeadDays}
        AreaException ex033 = new AreaException(AreaErrorCode.ERR_AREA_033, 30);
        assertTrue(ex033.getMessage().contains("30"));
        assertFalse(ex033.getMessage().contains("{"), "Không được chứa '{': " + ex033.getMessage());

        // ERR_AREA_035: {minMinutes}
        AreaException ex035 = new AreaException(AreaErrorCode.ERR_AREA_035, 30);
        assertTrue(ex035.getMessage().contains("30"));
        assertFalse(ex035.getMessage().contains("{"), "Không được chứa '{': " + ex035.getMessage());

        // ERR_AREA_036: {maxHours}
        AreaException ex036 = new AreaException(AreaErrorCode.ERR_AREA_036, 12);
        assertTrue(ex036.getMessage().contains("12"));
        assertFalse(ex036.getMessage().contains("{"), "Không được chứa '{': " + ex036.getMessage());

        // ERR_AREA_039: {maxSchedules}
        AreaException ex039 = new AreaException(AreaErrorCode.ERR_AREA_039, 5);
        assertTrue(ex039.getMessage().contains("5"));
        assertFalse(ex039.getMessage().contains("{"), "Không được chứa '{': " + ex039.getMessage());

        // ERR_AREA_042: {count}
        AreaException ex042 = new AreaException(AreaErrorCode.ERR_AREA_042, 2);
        assertTrue(ex042.getMessage().contains("2"));
        assertFalse(ex042.getMessage().contains("{"), "Không được chứa '{': " + ex042.getMessage());
    }

    @Test
    @DisplayName("Custom message constructor giữ nguyên thông điệp tuỳ chỉnh")
    void testCustomMessageConstructor() {
        String customMsg = "Khu vực còn 2 lịch sự kiện chưa diễn ra: 28/09 08:00–12:00 (Trần Bình). Liên hệ quản lý cơ sở vật chất để huỷ lịch trước.";
        AreaException ex = new AreaException(AreaErrorCode.ERR_AREA_042, customMsg);
        assertEquals(customMsg, ex.getMessage());
        assertEquals(AreaErrorCode.ERR_AREA_042, ex.getErrorCode());
    }
}
