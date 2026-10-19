package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * V83 (loại ⇔ cấp): cấp khu luôn bằng cấp của loại, không đọc preset, không còn sửa cấp riêng từng khu.
 * Trước V83 lớp này có 8 test: lấy cấp từ preset / fail-closed khi thiếu preset / 4 test PATCH access-rules (sửa, audit,
 * no-op, khu inactive). Các chức năng đó đã bỏ nên thay bằng test cho quy tắc mới.
 */
@ExtendWith(MockitoExtension.class)
class AreaServiceAccessLevelTest {

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AreaValidator areaValidator;

    @Mock
    private AreaDependencyChecker dependencyChecker;

    @Mock
    private com.fa26se040.icss.service.AuditService auditService;

    @Mock
    private FloorRepository floorRepository;

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

        Floor defaultFloor = Floor.builder()
                .id(UUID.randomUUID())
                .name("Tầng 1")
                .build();
        org.mockito.Mockito.lenient().when(floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase(any(), any()))
                .thenReturn(Optional.of(defaultFloor));
    }

    @ParameterizedTest
    @EnumSource(AreaLevel.class)
    @DisplayName("V83 (BR-PR-23): tạo khu -> cấp = cấp của loại (Công khai 1, Bảo mật 2, Tuyệt mật 3), cờ chỉ định false")
    void createArea_LevelFollowsType(AreaLevel level) {
        AreaCreateRequest req = AreaCreateRequest.builder()
                .name("Khu " + level.label())
                .areaLevel(level)
                .building("Tòa A")
                .floor("Tầng 1")
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .build();
        when(areaValidator.validateAndNormalizeName(req.getName())).thenReturn(req.getName());
        when(userRepository.findByEmail(adminEmail)).thenReturn(Optional.of(admin));
        when(areaRepository.saveAndFlush(any(Area.class))).thenAnswer(inv -> {
            Area a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        AreaResponse resp = areaService.create(req, adminEmail);

        assertEquals(level, resp.areaLevel());
        assertEquals(level.level(), resp.areaAccessLevel());
        assertFalse(resp.explicitAuthorizationRequired());
        assertFalse(resp.differsFromPreset());
        ArgumentCaptor<Area> saved = ArgumentCaptor.forClass(Area.class);
        verify(areaRepository).saveAndFlush(saved.capture());
        assertEquals(level.level(), saved.getValue().getAreaAccessLevel());
    }

    @Test
    @DisplayName("V83 (BR-TC-04): đổi loại Công khai -> Tuyệt mật khi update -> cấp theo loại mới (3), cờ false")
    void updateArea_TypeChange_LevelFollowsNewType() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .name("Phòng Học 101")
                .areaLevel(AreaLevel.PUBLIC)
                .building("Tòa A")
                .floor("Tầng 1")
                .isActive(true)
                .build();

        AreaUpdateRequest updateReq = AreaUpdateRequest.builder()
                .name("Phòng Học 101 Đổi Cấp")
                .areaLevel(AreaLevel.HIGHLY_CONFIDENTIAL)
                .building("Tòa A")
                .floor("Tầng 1")
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .reason("Đổi sang Tuyệt mật theo yêu cầu an ninh")
                .version(0L)
                .build();

        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(existing));
        when(areaValidator.validateAndNormalizeName(updateReq.getName())).thenReturn(updateReq.getName());
        when(userRepository.findByEmail(adminEmail)).thenReturn(Optional.of(admin));
        when(areaRepository.saveAndFlush(any(Area.class))).thenAnswer(inv -> inv.getArgument(0));

        AreaResponse resp = areaService.update(areaId, updateReq, adminEmail);

        assertNotNull(resp);
        assertEquals(AreaLevel.HIGHLY_CONFIDENTIAL, resp.areaLevel());
        assertEquals(3, resp.areaAccessLevel());
        assertFalse(resp.explicitAuthorizationRequired());
    }

    @Test
    @DisplayName("V83 (D4): PATCH access-rules luôn 400 ERR_AREA_056 \"Cấp khu vực cố định theo loại\" (đổi cấp, giữ nguyên, khu inactive), không khoá, không lưu, không audit")
    void updateAccessRules_AlwaysRejected() {
        UUID areaId = UUID.randomUUID();
        for (AreaAccessRulesUpdateRequest req : List.of(
                new AreaAccessRulesUpdateRequest(1, false, "Hạ cấp khu Bảo mật xuống 1", 0L),
                new AreaAccessRulesUpdateRequest(2, false, "Giữ nguyên cấp của loại", 0L),
                new AreaAccessRulesUpdateRequest(2, true, "Bật lại cờ chỉ định đích danh", 0L))) {
            AreaException ex = assertThrows(AreaException.class, () -> areaService.updateAccessRules(areaId, req, adminEmail));
            assertEquals(AreaErrorCode.ERR_AREA_056, ex.getErrorCode());
            assertEquals("Cấp khu vực cố định theo loại (Công khai: cấp 1, Bảo mật: cấp 2, Tuyệt mật: cấp 3). Muốn đổi cấp, hãy đổi loại khu vực",
                    ex.getMessage());
        }
        verifyNoInteractions(areaRepository, auditService);
    }

    @Test
    @DisplayName("getAvailableAreasForRequest: khu thiếu loại (dữ liệu hỏng) bị loại khỏi danh sách (fail-closed)")
    void getAvailableAreasForRequest_WhenAreaTypeNull_ExcludesArea() {
        User caller = User.builder()
                .id(UUID.randomUUID())
                .email("user@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(2)
                .isActive(true)
                .build();
        when(userRepository.findByEmail("user@fpt.edu.vn")).thenReturn(Optional.of(caller));

        Area validArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Khu vực hợp lệ")
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .isActive(true)
                .build();

        Area nullTypeArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Khu vực thiếu loại")
                .areaLevel(null)
                .isActive(true)
                .build();

        when(areaRepository.findAvailableForRequest()).thenReturn(List.of(validArea, nullTypeArea));

        var result = areaService.getAvailableAreasForRequest("user@fpt.edu.vn");

        assertEquals(1, result.size());
        assertEquals(validArea.getId(), result.get(0).id());
    }
}
