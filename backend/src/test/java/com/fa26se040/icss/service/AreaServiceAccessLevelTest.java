package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AreaServiceAccessLevelTest {

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AreaLevelPresetRepository areaLevelPresetRepository;

    @Mock
    private AreaValidator areaValidator;

    @Mock
    private AreaDependencyChecker dependencyChecker;

    @Mock
    private AreaGeometryValidator geometryValidator;

    @InjectMocks
    private AreaService areaService;

    private User admin;
    private final String adminEmail = "admin@fpt.edu.vn";

    @BeforeEach
    void setUp() {
        admin = User.builder()
                .id(UUID.randomUUID())
                .userCode("ADMIN001")
                .fullName("Quản Trị Viên")
                .email(adminEmail)
                .role(Role.ADMIN)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("Tạo area luôn lấy access rules từ preset (PUBLIC: 1, false; PRIVATE: 3, true)")
    void createArea_AlwaysTakesAccessRulesFromPreset() {
        AreaCreateRequest reqPublic = new AreaCreateRequest(
                "PUBLIC-HALL",
                "Sảnh Chính",
                AreaLevel.PUBLIC,
                "Tòa A",
                "Tầng 1",
                "Khu vực sảnh chung"
        );

        when(areaValidator.validateAndNormalizeCode(reqPublic.code())).thenReturn(reqPublic.code());
        when(areaValidator.validateAndNormalizeName(reqPublic.name())).thenReturn(reqPublic.name());
        when(areaRepository.existsByCodeAndDeletedAtIsNull(reqPublic.code())).thenReturn(false);
        when(userRepository.findByEmail(adminEmail)).thenReturn(Optional.of(admin));

        AreaLevelPreset publicPreset = AreaLevelPreset.builder()
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .build();
        when(areaLevelPresetRepository.findById(AreaLevel.PUBLIC)).thenReturn(Optional.of(publicPreset));

        when(areaRepository.save(any(Area.class))).thenAnswer(inv -> {
            Area a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        AreaResponse respPublic = areaService.create(reqPublic, adminEmail);
        assertNotNull(respPublic);
        assertEquals(1, respPublic.areaAccessLevel());
        assertFalse(respPublic.explicitAuthorizationRequired());

        // Test tạo khu vực HIGHLY_CONFIDENTIAL
        AreaCreateRequest reqPrivate = new AreaCreateRequest(
                "SERVER-ROOM",
                "Phòng Máy Chủ",
                AreaLevel.HIGHLY_CONFIDENTIAL,
                "Tòa A",
                "Tầng 2",
                "Phòng kỹ thuật"
        );
        when(areaValidator.validateAndNormalizeCode(reqPrivate.code())).thenReturn(reqPrivate.code());
        when(areaValidator.validateAndNormalizeName(reqPrivate.name())).thenReturn(reqPrivate.name());
        when(areaRepository.existsByCodeAndDeletedAtIsNull(reqPrivate.code())).thenReturn(false);

        AreaLevelPreset privatePreset = AreaLevelPreset.builder()
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .areaAccessLevel(3)
                .explicitAuthorizationRequired(true)
                .build();
        when(areaLevelPresetRepository.findById(AreaLevel.HIGHLY_CONFIDENTIAL)).thenReturn(Optional.of(privatePreset));

        AreaResponse respPrivate = areaService.create(reqPrivate, adminEmail);
        assertNotNull(respPrivate);
        assertEquals(3, respPrivate.areaAccessLevel());
        assertTrue(respPrivate.explicitAuthorizationRequired());
    }

    @Test
    @DisplayName("Tạo area khi thiếu preset -> fail-closed (accessLevel = 3, explicitAuthorizationRequired = true)")
    void createArea_WhenPresetMissing_FailsClosedWithLevel3AndExplicitAuthTrue() {
        AreaCreateRequest reqMissing = new AreaCreateRequest(
                "UNKNOWN-ROOM",
                "Phòng Mới",
                AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED,
                "Tòa B",
                "Tầng 1",
                "Khu vực chưa có preset"
        );

        when(areaValidator.validateAndNormalizeCode(reqMissing.code())).thenReturn(reqMissing.code());
        when(areaValidator.validateAndNormalizeName(reqMissing.name())).thenReturn(reqMissing.name());
        when(areaRepository.existsByCodeAndDeletedAtIsNull(reqMissing.code())).thenReturn(false);
        when(userRepository.findByEmail(adminEmail)).thenReturn(Optional.of(admin));
        when(areaLevelPresetRepository.findById(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)).thenReturn(Optional.empty());

        when(areaRepository.save(any(Area.class))).thenAnswer(inv -> {
            Area a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        AreaResponse resp = areaService.create(reqMissing, adminEmail);
        assertNotNull(resp);
        assertEquals(3, resp.areaAccessLevel());
        assertTrue(resp.explicitAuthorizationRequired());
    }

    @Test
    @DisplayName("Cập nhật area_level khi update -> KHÔNG tự động thay đổi access-rules")
    void updateArea_AreaLevelChange_DoesNotTouchAccessRules() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .code("ROOM-101")
                .name("Phòng Học 101")
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .building("Tòa A")
                .floor("Tầng 1")
                .isActive(true)
                .build();

        AreaUpdateRequest updateReq = new AreaUpdateRequest(
                "ROOM-101",
                "Phòng Học 101 Đổi Cấp",
                AreaLevel.HIGHLY_CONFIDENTIAL,
                "Tòa A",
                "Tầng 1",
                "Mô tả mới"
        );

        when(areaRepository.findByIdAndDeletedAtIsNull(areaId)).thenReturn(Optional.of(existing));
        when(areaValidator.validateAndNormalizeName(updateReq.name())).thenReturn(updateReq.name());
        when(userRepository.findByEmail(adminEmail)).thenReturn(Optional.of(admin));
        when(areaRepository.save(any(Area.class))).thenAnswer(inv -> inv.getArgument(0));

        AreaResponse resp = areaService.update(areaId, updateReq, adminEmail);

        assertNotNull(resp);
        assertEquals(AreaLevel.HIGHLY_CONFIDENTIAL, resp.areaLevel());
        assertEquals(2, resp.areaAccessLevel(), "areaAccessLevel phải giữ nguyên không tự đổi");
        assertTrue(resp.explicitAuthorizationRequired(), "explicitAuthorizationRequired phải giữ nguyên");
    }

    @Test
    @DisplayName("PATCH access-rules thành công -> cập nhật areaAccessLevel và cờ explicit")
    void updateAccessRules_Valid_Success() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .code("ROOM-202")
                .name("Phòng Nghiên Cứu")
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(existing));
        when(areaRepository.save(any(Area.class))).thenAnswer(inv -> inv.getArgument(0));

        AreaAccessRulesUpdateRequest req = new AreaAccessRulesUpdateRequest(1, true);
        AreaResponse resp = areaService.updateAccessRules(areaId, req);

        assertNotNull(resp);
        assertEquals(1, resp.areaAccessLevel());
        assertTrue(resp.explicitAuthorizationRequired());
    }

    @Test
    @DisplayName("PATCH access-rules cho area inactive hoặc đã xóa -> báo lỗi ERR_AREA_017")
    void updateAccessRules_InactiveOrDeletedArea_ThrowsAreaException() {
        UUID areaId = UUID.randomUUID();
        Area inactiveArea = Area.builder()
                .id(areaId)
                .code("ROOM-OLD")
                .name("Phòng Cũ")
                .isActive(false)
                .build();

        when(areaRepository.findById(areaId)).thenReturn(Optional.of(inactiveArea));

        AreaAccessRulesUpdateRequest req = new AreaAccessRulesUpdateRequest(2, false);
        AreaException ex = assertThrows(AreaException.class, () -> areaService.updateAccessRules(areaId, req));
        assertEquals(AreaErrorCode.ERR_AREA_017, ex.getErrorCode());

        // Test deleted area
        Area deletedArea = Area.builder()
                .id(areaId)
                .code("ROOM-DEL")
                .name("Phòng Đã Xóa")
                .isActive(true)
                .deletedAt(OffsetDateTime.now())
                .build();
        when(areaRepository.findById(areaId)).thenReturn(Optional.of(deletedArea));
        AreaException exDel = assertThrows(AreaException.class, () -> areaService.updateAccessRules(areaId, req));
        assertEquals(AreaErrorCode.ERR_AREA_017, exDel.getErrorCode());
    }
}
