package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserAccessLevelHelper {

    private final SystemConfigService systemConfigService;

    public int resolveDefaultAccessLevel(Role role) {
        if (role == null) {
            log.warn("Vai trò là null khi xác định cấp độ truy cập mặc định, sử dụng giá trị mặc định 1");
            return 1;
        }

        ConfigKey key = resolveConfigKey(role);
        if (key == null) {
            log.warn("Vai trò [{}] không có cấu hình cấp độ truy cập mặc định, sử dụng giá trị mặc định 1", role);
            return 1;
        }

        return systemConfigService.getInt(key);
    }

    public ConfigKey resolveConfigKey(Role role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case NORMAL_USER -> ConfigKey.ACCESS_LEVEL_DEFAULT_NORMAL_USER;
            case GUARD -> ConfigKey.ACCESS_LEVEL_DEFAULT_GUARD;
            case FACILITY_MANAGER -> ConfigKey.ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER;
            case ADMIN -> ConfigKey.ACCESS_LEVEL_DEFAULT_ADMIN;
            default -> null;
        };
    }
}
