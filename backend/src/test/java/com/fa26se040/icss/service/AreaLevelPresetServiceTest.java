package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.LevelPresetResponse;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * V83 (D2): bảng cấp theo loại chỉ để xem. Trước V83 lớp này có 7 test cho chức năng sửa preset (version, audit, no-op,
 * BR-PR-02); chức năng đã bỏ nên thay bằng 2 test cho quy tắc mới.
 */
@ExtendWith(MockitoExtension.class)
class AreaLevelPresetServiceTest {

    @Mock
    private AreaLevelPresetRepository presetRepository;

    @InjectMocks
    private AreaLevelPresetService presetService;

    private static AreaLevelPreset preset(AreaLevel level, User updatedBy) {
        return AreaLevelPreset.builder()
                .areaLevel(level)
                .areaAccessLevel(level.level())
                .explicitAuthorizationRequired(false)
                .version(1L)
                .updatedBy(updatedBy)
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("V83: danh sách preset đúng 3 loại theo thứ tự Công khai, Bảo mật, Tuyệt mật, cấp 1/2/3, cờ false")
    void getAllPresets_ReturnsThreeTypesInOrder() {
        User admin = User.builder().id(UUID.randomUUID()).userCode("AD-001").fullName("Quản trị").role(Role.ADMIN).build();
        when(presetRepository.findAll()).thenReturn(List.of(
                preset(AreaLevel.HIGHLY_CONFIDENTIAL, null),
                preset(AreaLevel.PUBLIC, admin),
                preset(AreaLevel.CONFIDENTIAL, null)));

        List<LevelPresetResponse> result = presetService.getAllPresets();

        assertEquals(List.of(AreaLevel.PUBLIC, AreaLevel.CONFIDENTIAL, AreaLevel.HIGHLY_CONFIDENTIAL),
                result.stream().map(LevelPresetResponse::areaLevel).toList());
        assertEquals(List.of(1, 2, 3), result.stream().map(LevelPresetResponse::areaAccessLevel).toList());
        result.forEach(r -> assertFalse(r.explicitAuthorizationRequired()));
        assertEquals("Quản trị", result.get(0).updatedByName());
    }

    @ParameterizedTest
    @EnumSource(AreaLevel.class)
    @DisplayName("V83 (BR-PR-23): sửa preset luôn 400 ERR_AC_005 \"Cấp khu vực cố định theo loại\", không đọc / ghi DB, không audit")
    void updatePreset_AlwaysRejected(AreaLevel level) {
        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(level.level(), false, "Thử sửa cấp của loại khu vực", 0L);

        AccessControlException ex = assertThrows(AccessControlException.class,
                () -> presetService.updatePreset(level, req, "admin@fpt.edu.vn"));

        assertEquals(AccessControlErrorCode.ERR_AC_005, ex.getErrorCode());
        assertEquals("Cấp khu vực cố định theo loại (Công khai: cấp 1, Bảo mật: cấp 2, Tuyệt mật: cấp 3), không sửa được",
                ex.getMessage());
        verifyNoInteractions(presetRepository);
    }
}
