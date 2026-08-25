package com.fa26se040.security.service;

import com.fa26se040.security.exception.AreaErrorCode;
import com.fa26se040.security.exception.AreaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AreaValidatorTest {

    private AreaValidator areaValidator;

    @BeforeEach
    void setUp() {
        areaValidator = new AreaValidator();
    }

    @Test
    @DisplayName("U1: code = 'server-b01' -> Chuẩn hoá SERVER-B01, hợp lệ")
    void u1_codeLower_shouldNormalizeToUppercase() {
        String result = areaValidator.validateAndNormalizeCode("server-b01");
        assertEquals("SERVER-B01", result);
    }

    @Test
    @DisplayName("U2: code = 'AB' -> ERR_AREA_004")
    void u2_codeTooShort_shouldThrowErrArea004() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeCode("AB"));
        assertEquals(AreaErrorCode.ERR_AREA_004, ex.getErrorCode());
    }

    @Test
    @DisplayName("U3: code = 'SERVER_B01' -> ERR_AREA_004")
    void u3_codeWithUnderscore_shouldThrowErrArea004() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeCode("SERVER_B01"));
        assertEquals(AreaErrorCode.ERR_AREA_004, ex.getErrorCode());
    }

    @Test
    @DisplayName("U4: code = '-SERVER' -> ERR_AREA_004")
    void u4_codeLeadingHyphen_shouldThrowErrArea004() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeCode("-SERVER"));
        assertEquals(AreaErrorCode.ERR_AREA_004, ex.getErrorCode());
    }

    @Test
    @DisplayName("U5: name rỗng -> ERR_AREA_005")
    void u5_nameEmpty_shouldThrowErrArea005() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("  "));
        assertEquals(AreaErrorCode.ERR_AREA_005, ex.getErrorCode());
    }

    @Test
    @DisplayName("U6: Hạ 3->1, reason rỗng -> ERR_AREA_008")
    void u6_downgradeEmptyReason_shouldThrowErrArea008() {
        AreaException ex = assertThrows(AreaException.class,
                () -> areaValidator.validateDowngradeReason((short) 3, (short) 1, ""));
        assertEquals(AreaErrorCode.ERR_AREA_008, ex.getErrorCode());
    }

    @Test
    @DisplayName("U7: Hạ cấp, reason 5 ký tự -> ERR_AREA_008")
    void u7_downgradeShortReason_shouldThrowErrArea008() {
        AreaException ex = assertThrows(AreaException.class,
                () -> areaValidator.validateDowngradeReason((short) 3, (short) 1, "12345"));
        assertEquals(AreaErrorCode.ERR_AREA_008, ex.getErrorCode());
    }

    @Test
    @DisplayName("U8: Nâng 1->3, không có reason -> Hợp lệ")
    void u8_upgradeNoReason_shouldBeValid() {
        assertDoesNotThrow(() -> areaValidator.validateDowngradeReason((short) 1, (short) 3, null));
    }

    @Test
    @DisplayName("U9: Chỉ có mapX, thiếu mapY -> ERR_AREA_006")
    void u9_onlyMapX_shouldThrowErrArea006() {
        AreaException ex = assertThrows(AreaException.class,
                () -> areaValidator.validateMapCoordinates(new BigDecimal("100.5"), null));
        assertEquals(AreaErrorCode.ERR_AREA_006, ex.getErrorCode());
    }
}
