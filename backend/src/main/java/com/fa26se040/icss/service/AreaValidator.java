package com.fa26se040.icss.service;

import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

@Component
public class AreaValidator {

    private static final Pattern HAS_LETTER_PATTERN = Pattern.compile("\\p{L}");
    private static final Pattern ALLOWED_CHARS_PATTERN = Pattern.compile("^[\\p{L}0-9 \\-_().,/]+$");

    /**
     * BR-AR-01: Chuẩn hoá Unicode NFC trước, sau đó trim và gộp khoảng trắng liên tiếp thành 1.
     */
    public String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String nfc = Normalizer.normalize(name, Normalizer.Form.NFC);
        return nfc.trim().replaceAll(" +", " ");
    }

    /**
     * BR-AR-01..04: Validate và chuẩn hoá tên khu vực.
     */
    public String validateAndNormalizeName(String name) {
        if (name == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_005);
        }
        String normalized = normalizeName(name);

        // BR-AR-02: Độ dài 3–100 ký tự sau chuẩn hoá
        if (normalized.length() < 3 || normalized.length() > 100) {
            throw new AreaException(AreaErrorCode.ERR_AREA_005);
        }

        // BR-AR-03: Phải chứa ít nhất một chữ cái (Unicode, gồm tiếng Việt có dấu)
        if (!HAS_LETTER_PATTERN.matcher(normalized).find()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_018);
        }

        // BR-AR-04: Ký tự cho phép: chữ (Unicode), số, khoảng trắng thường ' ', - _ ( ) . , /
        if (!ALLOWED_CHARS_PATTERN.matcher(normalized).matches()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_019);
        }

        return normalized;
    }
}
