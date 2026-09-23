package com.fa26se040.icss.service;

import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // =========================================================================
    // BR-AR-01: Chuẩn hoá Unicode NFC trước trim/gộp khoảng trắng
    // =========================================================================

    @Test
    @DisplayName("BR-AR-01: Chuỗi tiếng Việt dạng NFD phải được chấp nhận và trả về dạng NFC")
    void br_ar_01_vietnameseNfd_shouldBeAcceptedAndNormalizedToNfc() {
        // "Khu Vực Kỹ Thuật" ở dạng NFD (decomposed)
        String original = "Khu Vực Kỹ Thuật";
        String nfdString = Normalizer.normalize(original, Normalizer.Form.NFD);

        // Kiểm tra chắc chắn chuỗi ban đầu đang ở dạng NFD chứ không phải NFC
        assertTrue(Normalizer.isNormalized(nfdString, Normalizer.Form.NFD));

        String result = areaValidator.validateAndNormalizeName(nfdString);

        assertNotNull(result);
        assertEquals(original, result);
        assertTrue(Normalizer.isNormalized(result, Normalizer.Form.NFC), "Kết quả phải ở chuẩn Unicode NFC");
    }

    @Test
    @DisplayName("BR-AR-01: Trim và gộp nhiều khoảng trắng liên tiếp thành 1")
    void br_ar_01_multipleSpacesAndTrim_shouldCollapseToOneSpace() {
        String input = "   Phòng    Họp     Lớn   ";
        String result = areaValidator.validateAndNormalizeName(input);
        assertEquals("Phòng Họp Lớn", result);
    }

    // =========================================================================
    // BR-AR-02: Độ dài 3–100 ký tự sau chuẩn hoá
    // =========================================================================

    @Test
    @DisplayName("BR-AR-02: Tên null hoặc rỗng -> ERR_AREA_005")
    void br_ar_02_nameNullOrBlank_shouldThrowErrArea005() {
        AreaException ex1 = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName(null));
        assertEquals(AreaErrorCode.ERR_AREA_005, ex1.getErrorCode());

        AreaException ex2 = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("   "));
        assertEquals(AreaErrorCode.ERR_AREA_005, ex2.getErrorCode());
    }

    @Test
    @DisplayName("BR-AR-02: Tên 2 ký tự sau chuẩn hoá -> ERR_AREA_005")
    void br_ar_02_name2Chars_shouldThrowErrArea005() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("AB"));
        assertEquals(AreaErrorCode.ERR_AREA_005, ex.getErrorCode());
    }

    @Test
    @DisplayName("BR-AR-02: Tên đúng 3 ký tự -> Hợp lệ")
    void br_ar_02_name3Chars_shouldBeValid() {
        String result = areaValidator.validateAndNormalizeName("Lab");
        assertEquals("Lab", result);
    }

    @Test
    @DisplayName("BR-AR-02: Tên đúng 100 ký tự -> Hợp lệ")
    void br_ar_02_name100Chars_shouldBeValid() {
        String name100 = "Khu " + "a".repeat(96);
        assertEquals(100, name100.length());
        String result = areaValidator.validateAndNormalizeName(name100);
        assertEquals(name100, result);
    }

    @Test
    @DisplayName("BR-AR-02: Tên 101 ký tự -> ERR_AREA_005")
    void br_ar_02_name101Chars_shouldThrowErrArea005() {
        String name101 = "Khu " + "a".repeat(97);
        assertEquals(101, name101.length());
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName(name101));
        assertEquals(AreaErrorCode.ERR_AREA_005, ex.getErrorCode());
    }

    // =========================================================================
    // BR-AR-03: Phải chứa ít nhất một chữ cái Unicode
    // =========================================================================

    @Test
    @DisplayName("BR-AR-03: Chỉ số -> ERR_AREA_018")
    void br_ar_03_onlyNumbers_shouldThrowErrArea018() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("123456"));
        assertEquals(AreaErrorCode.ERR_AREA_018, ex.getErrorCode());
    }

    @Test
    @DisplayName("BR-AR-03: Chỉ ký tự đặc biệt -> ERR_AREA_018")
    void br_ar_03_onlySpecialChars_shouldThrowErrArea018() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("--- / ."));
        assertEquals(AreaErrorCode.ERR_AREA_018, ex.getErrorCode());
    }

    @Test
    @DisplayName("BR-AR-03: Chứa cả chữ và số -> Hợp lệ")
    void br_ar_03_lettersAndNumbers_shouldBeValid() {
        String result = areaValidator.validateAndNormalizeName("Phòng 101-A");
        assertEquals("Phòng 101-A", result);
    }

    // =========================================================================
    // BR-AR-04: Ký tự cho phép: chữ (Unicode), số, khoảng trắng thường ' ', - _ ( ) . , /
    // =========================================================================

    @Test
    @DisplayName("BR-AR-04: Chứa ký tự hợp lệ: - _ ( ) . , / -> Hợp lệ")
    void br_ar_04_allowedSpecialChars_shouldBeValid() {
        String input = "Khu Vực A-1_B (Tầng 2) - P.101, Phân khu/Zone";
        String result = areaValidator.validateAndNormalizeName(input);
        assertEquals(input, result);
    }

    @Test
    @DisplayName("BR-AR-04: Chứa Emoji -> ERR_AREA_019")
    void br_ar_04_emoji_shouldThrowErrArea019() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("Phòng Server 🏢"));
        assertEquals(AreaErrorCode.ERR_AREA_019, ex.getErrorCode());
    }

    @Test
    @DisplayName("BR-AR-04: Chứa thẻ script/HTML -> ERR_AREA_019")
    void br_ar_04_htmlScript_shouldThrowErrArea019() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("<script>alert(1)</script>"));
        assertEquals(AreaErrorCode.ERR_AREA_019, ex.getErrorCode());
    }

    @Test
    @DisplayName("BR-AR-04: Chứa ký tự cấm (@, #, $) -> ERR_AREA_019")
    void br_ar_04_disallowedChars_shouldThrowErrArea019() {
        AreaException ex = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("Phòng VIP @#$"));
        assertEquals(AreaErrorCode.ERR_AREA_019, ex.getErrorCode());
    }

    @Test
    @DisplayName("BR-AR-04: Tab hoặc xuống dòng không được phép (chỉ khoảng trắng thường) -> ERR_AREA_019")
    void br_ar_04_tabOrNewline_shouldThrowErrArea019() {
        AreaException ex1 = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("Phòng\tServer"));
        assertEquals(AreaErrorCode.ERR_AREA_019, ex1.getErrorCode());

        AreaException ex2 = assertThrows(AreaException.class, () -> areaValidator.validateAndNormalizeName("Phòng\nServer"));
        assertEquals(AreaErrorCode.ERR_AREA_019, ex2.getErrorCode());
    }
}
