package com.fa26se040.security.service;

import com.fa26se040.security.exception.AreaErrorCode;
import com.fa26se040.security.exception.AreaException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Pattern;

@Component
public class AreaValidator {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,48}[A-Z0-9]$");
    private static final BigDecimal MAX_MAP_COORD = new BigDecimal("99999.99");

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

    public void validateMapCoordinates(BigDecimal mapX, BigDecimal mapY) {
        if ((mapX == null) != (mapY == null)) {
            throw new AreaException(AreaErrorCode.ERR_AREA_006);
        }
        if (mapX != null) {
            if (mapX.compareTo(BigDecimal.ZERO) < 0 || mapX.compareTo(MAX_MAP_COORD) > 0 ||
                mapY.compareTo(BigDecimal.ZERO) < 0 || mapY.compareTo(MAX_MAP_COORD) > 0) {
                throw new AreaException(AreaErrorCode.ERR_AREA_006);
            }
        }
    }

    public void validateDowngradeReason(Short currentLevel, Short newLevel, String reason) {
        if (newLevel < currentLevel) {
            if (reason == null || reason.trim().length() < 10 || reason.trim().length() > 255) {
                throw new AreaException(AreaErrorCode.ERR_AREA_008);
            }
        } else {
            if (reason != null && reason.trim().length() > 255) {
                throw new AreaException(AreaErrorCode.ERR_AREA_008);
            }
        }
    }
}
