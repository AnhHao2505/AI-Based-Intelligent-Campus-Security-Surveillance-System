package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Cấp truy cập mặc định khi tạo tài khoản (UserAccessLevelHelper): đọc từ key ACCESS_LEVEL_DEFAULT_<ROLE> trong
 * system_configurations, không viết cứng. Viết lại từ bản cũ (ca36fdb^) theo code hiện tại:
 * - duyệt MỌI giá trị enum Role -> thêm role mới mà quên key cấu hình sẽ đỏ (code hiện rơi vào nhánh mặc định 1);
 * - mỗi role một giá trị cấu hình khác nhau -> chứng minh giá trị lấy đúng key của role, không trùng hợp.
 */
@ExtendWith(MockitoExtension.class)
class UserAccessLevelHelperTest {

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private UserAccessLevelHelper userAccessLevelHelper;

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("Mọi role đều có key ACCESS_LEVEL_DEFAULT_<ROLE> và cấp mặc định lấy từ đúng key đó")
    void everyRole_mapsToOwnConfigKey(Role role) {
        ConfigKey key = userAccessLevelHelper.resolveConfigKey(role);
        assertNotNull(key, "Role " + role + " chưa có key cấp truy cập mặc định");
        assertEquals("ACCESS_LEVEL_DEFAULT_" + role.name(), key.name());

        when(systemConfigService.getInt(key)).thenReturn(3);

        assertEquals(3, userAccessLevelHelper.resolveDefaultAccessLevel(role));
        verify(systemConfigService).getInt(key);
        verifyNoMoreInteractions(systemConfigService);
    }

    @Test
    @DisplayName("Mỗi role trả đúng giá trị cấu hình của mình (giá trị khác nhau, không viết cứng)")
    void distinctConfiguredValues_returnedPerRole() {
        when(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_NORMAL_USER)).thenReturn(1);
        when(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_GUARD)).thenReturn(2);
        when(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER)).thenReturn(3);
        when(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_ADMIN)).thenReturn(2);

        assertEquals(1, userAccessLevelHelper.resolveDefaultAccessLevel(Role.NORMAL_USER));
        assertEquals(2, userAccessLevelHelper.resolveDefaultAccessLevel(Role.GUARD));
        assertEquals(3, userAccessLevelHelper.resolveDefaultAccessLevel(Role.FACILITY_MANAGER));
        assertEquals(2, userAccessLevelHelper.resolveDefaultAccessLevel(Role.ADMIN));
    }

    @Test
    @DisplayName("Role null: cấp mặc định 1, không đọc cấu hình")
    void nullRole_returns1_withoutReadingConfig() {
        assertEquals(1, userAccessLevelHelper.resolveDefaultAccessLevel(null));
        verifyNoInteractions(systemConfigService);
    }

    @Test
    @DisplayName("resolveConfigKey(null) trả null")
    void resolveConfigKey_nullRole_returnsNull() {
        assertNull(userAccessLevelHelper.resolveConfigKey(null));
    }
}
