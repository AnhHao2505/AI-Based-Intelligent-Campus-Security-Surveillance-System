package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaEventScheduleRepository;
import com.fa26se040.icss.repository.AreaEventSessionRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.ReasonCatalogRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.util.AfterHoursWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AreaServiceFloorIntegrityTest {

    @Mock private AreaRepository areaRepository;
    @Mock private CameraRepository cameraRepository;
    @Mock private UserRepository userRepository;
    @Mock private FloorRepository floorRepository;
    @Mock private AreaValidator areaValidator;
    @Mock private AreaDependencyChecker dependencyChecker;
    @Mock private AuditService auditService;
    @Mock private ReasonCatalogRepository reasonCatalogRepository;
    @Mock private AreaEventSessionRepository eventSessionRepository;
    @Mock private AreaEventScheduleRepository eventScheduleRepository;
    @Mock private SystemConfigService systemConfigService;
    @Mock private ReasonValidator reasonValidator;
    @Mock private ObjectProvider<InAppNotificationService> inAppNotificationServiceProvider;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AccessRequestRepository accessRequestRepository;
    @Mock private AreaAssignedPersonnelRepository assignedPersonnelRepository;
    @Mock private ObjectProvider<GuestVisitService> guestVisitServiceProvider;
    @Mock private AfterHoursWindow afterHoursWindow;

    @InjectMocks
    private AreaService areaService;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "admin@fpt.edu.vn";
    private Building alphaBuilding;
    private Floor alphaFloor2;
    private Building epsilonBuilding;
    private Floor epsilonFloor4;

    @BeforeEach
    void setUp() {
        User actor = User.builder().id(actorId).email(actorEmail).fullName("Admin").build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(areaValidator.validateAndNormalizeName(anyString())).thenAnswer(inv -> inv.getArgument(0));

        alphaBuilding = Building.builder()
                .id(UUID.randomUUID())
                .name("Tòa Alpha")
                .totalFloors(3)
                .isActive(true)
                .build();
        alphaFloor2 = Floor.builder()
                .id(UUID.randomUUID())
                .building(alphaBuilding)
                .name("Tầng 2")
                .floorOrder(2)
                .isActive(true)
                .build();

        epsilonBuilding = Building.builder()
                .id(UUID.randomUUID())
                .name("Tòa Epsilon")
                .totalFloors(5)
                .isActive(true)
                .build();
        epsilonFloor4 = Floor.builder()
                .id(UUID.randomUUID())
                .building(epsilonBuilding)
                .name("Tầng 4")
                .floorOrder(4)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("Happy Path Create: active Building + active Floor with trimmed matching metadata succeeds")
    void create_activeFloorAndMatchingBuildingFloor_succeeds() {
        AreaCreateRequest req = AreaCreateRequest.builder()
                .name("Phòng Lab 201")
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .floorId(alphaFloor2.getId())
                .building("  tòa alpha  ")
                .floor(" tầng 2 ")
                .centerLatitude(10.8412)
                .centerLongitude(106.8099)
                .build();

        when(floorRepository.findById(alphaFloor2.getId())).thenReturn(Optional.of(alphaFloor2));
        when(areaRepository.existsByFloorIdAndNameIgnoreCase(alphaFloor2.getId(), "Phòng Lab 201")).thenReturn(false);
        when(areaRepository.saveAndFlush(any(Area.class))).thenAnswer(inv -> {
            Area a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        AreaResponse resp = areaService.create(req, actorEmail);

        assertEquals("Tòa Alpha", resp.building());
        assertEquals("Tầng 2", resp.floor());
        verify(areaRepository).saveAndFlush(any(Area.class));
    }

    @Test
    @DisplayName("Domain Error Create: mismatched floorId vs building/floor throws ERR_AREA_021")
    void create_mismatchedFloorIdAndBuilding_throwsErrArea021() {
        AreaCreateRequest req = AreaCreateRequest.builder()
                .name("Phòng Lab Mismatch")
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(alphaFloor2.getId())
                .building("Tòa Epsilon")
                .floor("Tầng 2")
                .centerLatitude(10.8412)
                .centerLongitude(106.8099)
                .build();

        when(floorRepository.findById(alphaFloor2.getId())).thenReturn(Optional.of(alphaFloor2));

        AreaException ex = assertThrows(AreaException.class, () -> areaService.create(req, actorEmail));
        assertEquals(AreaErrorCode.ERR_AREA_021, ex.getErrorCode());
        verify(areaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Domain Error Create: inactive Floor or inactive Building throws ERR_AREA_021")
    void create_inactiveFloorOrBuilding_throwsErrArea021() {
        Floor inactiveFloor = Floor.builder()
                .id(UUID.randomUUID())
                .building(alphaBuilding)
                .name("Tầng 2")
                .isActive(false)
                .build();
        AreaCreateRequest floorInactiveReq = AreaCreateRequest.builder()
                .name("Phòng Tầng Khóa")
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(inactiveFloor.getId())
                .centerLatitude(10.8412)
                .centerLongitude(106.8099)
                .build();
        when(floorRepository.findById(inactiveFloor.getId())).thenReturn(Optional.of(inactiveFloor));

        AreaException exFloor = assertThrows(AreaException.class, () -> areaService.create(floorInactiveReq, actorEmail));
        assertEquals(AreaErrorCode.ERR_AREA_021, exFloor.getErrorCode());

        Building inactiveBuilding = Building.builder()
                .id(UUID.randomUUID())
                .name("Tòa Ngưng")
                .isActive(false)
                .build();
        Floor floorUnderInactiveBuilding = Floor.builder()
                .id(UUID.randomUUID())
                .building(inactiveBuilding)
                .name("Tầng Trệt")
                .isActive(true)
                .build();
        AreaCreateRequest buildingInactiveReq = AreaCreateRequest.builder()
                .name("Phòng Tòa Khóa")
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(floorUnderInactiveBuilding.getId())
                .centerLatitude(10.8412)
                .centerLongitude(106.8099)
                .build();
        when(floorRepository.findById(floorUnderInactiveBuilding.getId())).thenReturn(Optional.of(floorUnderInactiveBuilding));

        AreaException exBuilding = assertThrows(AreaException.class, () -> areaService.create(buildingInactiveReq, actorEmail));
        assertEquals(AreaErrorCode.ERR_AREA_021, exBuilding.getErrorCode());
        verify(areaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Happy Path Update: moving Area to active Floor with matching building/floor succeeds")
    void update_activeFloorAndMatchingMetadata_succeeds() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .name("Phòng Hợp Lệ")
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .floorEntity(alphaFloor2)
                .building("Tòa Alpha")
                .floor("Tầng 2")
                .centerLatitude(10.8412)
                .centerLongitude(106.8099)
                .version(1L)
                .isActive(true)
                .build();

        AreaUpdateRequest req = AreaUpdateRequest.builder()
                .name("Phòng Hợp Lệ")
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(epsilonFloor4.getId())
                .building("Tòa Epsilon")
                .floor("Tầng 4")
                .centerLatitude(10.8407)
                .centerLongitude(106.8114)
                .version(1L)
                .build();

        when(areaRepository.findAreaLevelById(areaId)).thenReturn(Optional.of(AreaLevel.PUBLIC));
        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(existing));
        when(floorRepository.findById(epsilonFloor4.getId())).thenReturn(Optional.of(epsilonFloor4));
        when(areaRepository.existsByFloorIdAndNameIgnoreCaseExcludingId(areaId, epsilonFloor4.getId(), "Phòng Hợp Lệ"))
                .thenReturn(false);
        when(areaRepository.saveAndFlush(any(Area.class))).thenAnswer(inv -> inv.getArgument(0));

        AreaResponse resp = areaService.update(areaId, req, actorEmail);

        assertEquals("Tòa Epsilon", resp.building());
        assertEquals("Tầng 4", resp.floor());
        verify(areaRepository).saveAndFlush(existing);
    }

    @Test
    @DisplayName("Domain Error Update: mismatched floorId vs floor name throws ERR_AREA_021 without saving")
    void update_mismatchedFloorName_throwsErrArea021() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .name("Phòng Hiện Tại")
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .floorEntity(alphaFloor2)
                .building("Tòa Alpha")
                .floor("Tầng 2")
                .centerLatitude(10.8412)
                .centerLongitude(106.8099)
                .version(1L)
                .isActive(true)
                .build();

        AreaUpdateRequest req = AreaUpdateRequest.builder()
                .name("Phòng Hiện Tại")
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(epsilonFloor4.getId())
                .building("Tòa Epsilon")
                .floor("Tầng Trệt") // mismatches epsilonFloor4 ("Tầng 4")
                .centerLatitude(10.8407)
                .centerLongitude(106.8114)
                .version(1L)
                .build();

        when(areaRepository.findAreaLevelById(areaId)).thenReturn(Optional.of(AreaLevel.PUBLIC));
        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(existing));
        when(floorRepository.findById(epsilonFloor4.getId())).thenReturn(Optional.of(epsilonFloor4));

        AreaException ex = assertThrows(AreaException.class, () -> areaService.update(areaId, req, actorEmail));
        assertEquals(AreaErrorCode.ERR_AREA_021, ex.getErrorCode());
        verify(areaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Domain Error Update: inactive Building or inactive Floor throws ERR_AREA_021 without saving")
    void update_inactiveTargetFloorOrBuilding_throwsErrArea021() {
        UUID areaId = UUID.randomUUID();
        Area existing = Area.builder()
                .id(areaId)
                .name("Phòng Hiện Tại")
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .floorEntity(alphaFloor2)
                .building("Tòa Alpha")
                .floor("Tầng 2")
                .centerLatitude(10.8412)
                .centerLongitude(106.8099)
                .version(1L)
                .isActive(true)
                .build();

        epsilonBuilding.setIsActive(false);

        AreaUpdateRequest req = AreaUpdateRequest.builder()
                .name("Phòng Hiện Tại")
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(epsilonFloor4.getId())
                .building("Tòa Epsilon")
                .floor("Tầng 4")
                .centerLatitude(10.8407)
                .centerLongitude(106.8114)
                .version(1L)
                .build();

        when(areaRepository.findAreaLevelById(areaId)).thenReturn(Optional.of(AreaLevel.PUBLIC));
        when(areaRepository.findByIdWithLock(areaId)).thenReturn(Optional.of(existing));
        when(floorRepository.findById(epsilonFloor4.getId())).thenReturn(Optional.of(epsilonFloor4));

        AreaException ex = assertThrows(AreaException.class, () -> areaService.update(areaId, req, actorEmail));
        assertEquals(AreaErrorCode.ERR_AREA_021, ex.getErrorCode());
        verify(areaRepository, never()).saveAndFlush(any());
    }
}
