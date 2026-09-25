package com.fa26se040.icss.service;

import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class AreaValidator {

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
