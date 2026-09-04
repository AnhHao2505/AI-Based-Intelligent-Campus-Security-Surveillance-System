package com.fa26se040.icss.service;

import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class AreaValidator {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,48}[A-Z0-9]$");

    public String validateAndNormalizeCode(String code) {
        if (code == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_004);
        }
        String normalized = code.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new AreaException(AreaErrorCode.ERR_AREA_004);
        }
        return normalized;
    }

    public void validateCodeUpdate(String requestCode, String currentCode) {
        if (requestCode != null && !requestCode.trim().equalsIgnoreCase(currentCode.trim())) {
            throw new AreaException(AreaErrorCode.ERR_AREA_007);
        }
    }

    public String validateAndNormalizeName(String name) {
        if (name == null) {
            throw new AreaException(AreaErrorCode.ERR_AREA_005);
        }
        String normalized = name.trim();
        if (normalized.isEmpty() || normalized.length() > 150) {
            throw new AreaException(AreaErrorCode.ERR_AREA_005);
        }
        return normalized;
    }
}
