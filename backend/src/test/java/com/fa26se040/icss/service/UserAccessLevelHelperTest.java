package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccessLevelHelperTest {

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private UserAccessLevelHelper userAccessLevelHelper;

    @Test
    @DisplayName("NORMAL_USER mapping tới ACCESS_LEVEL_DEFAULT_NORMAL_USER và lấy giá trị từ config")
    void resolveDefaultAccessLevel_NormalUser_Success() {
        when(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_NORMAL_USER)).thenReturn(1);

        int level = userAccessLevelHelper.resolveDefaultAccessLevel(Role.NORMAL_USER);

        assertEquals(1, level);
        verify(systemConfigService).getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_NORMAL_USER);
    }

    @Test
    @DisplayName("GUARD mapping tới ACCESS_LEVEL_DEFAULT_GUARD và lấy giá trị từ config")
    void resolveDefaultAccessLevel_Guard_Success() {
        when(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_GUARD)).thenReturn(2);

        int level = userAccessLevelHelper.resolveDefaultAccessLevel(Role.GUARD);

        assertEquals(2, level);
        verify(systemConfigService).getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_GUARD);
    }

    @Test
    @DisplayName("FACILITY_MANAGER mapping tới ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER và lấy giá trị từ config")
    void resolveDefaultAccessLevel_FacilityManager_Success() {
        when(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER)).thenReturn(2);

        int level = userAccessLevelHelper.resolveDefaultAccessLevel(Role.FACILITY_MANAGER);

        assertEquals(2, level);
        verify(systemConfigService).getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER);
    }

    @Test
    @DisplayName("ADMIN mapping tới ACCESS_LEVEL_DEFAULT_ADMIN và lấy giá trị từ config")
    void resolveDefaultAccessLevel_Admin_Success() {
        when(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_ADMIN)).thenReturn(1);

        int level = userAccessLevelHelper.resolveDefaultAccessLevel(Role.ADMIN);

        assertEquals(1, level);
        verify(systemConfigService).getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_ADMIN);
    }

    @Test
    @DisplayName("Nhánh default: role null trả về 1 và không gọi systemConfigService")
    void resolveDefaultAccessLevel_NullRole_Returns1() {
        int level = userAccessLevelHelper.resolveDefaultAccessLevel(null);

        assertEquals(1, level);
        verifyNoInteractions(systemConfigService);
    }

    @Test
    @DisplayName("resolveConfigKey trả về null cho role null")
    void resolveConfigKey_NullRole_ReturnsNull() {
        assertNull(userAccessLevelHelper.resolveConfigKey(null));
    }
}
