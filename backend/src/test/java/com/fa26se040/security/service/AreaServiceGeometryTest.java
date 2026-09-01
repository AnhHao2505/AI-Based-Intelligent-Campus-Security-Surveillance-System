package com.fa26se040.security.service;

import com.fa26se040.security.dto.area.AreaGeometry;
import com.fa26se040.security.dto.area.AreaGeometryResponse;
import com.fa26se040.security.entity.Area;
import com.fa26se040.security.entity.AreaLevel;
import com.fa26se040.security.entity.User;
import com.fa26se040.security.repository.AreaChangeLogRepository;
import com.fa26se040.security.repository.AreaLevelRepository;
import com.fa26se040.security.repository.AreaRepository;
import com.fa26se040.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AreaServiceGeometryTest {

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private AreaLevelRepository areaLevelRepository;

    @Mock
    private AreaChangeLogRepository areaChangeLogRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AreaValidator areaValidator;

    @Mock
    private AreaDependencyChecker dependencyChecker;

    @Mock
    private AreaGeometryValidator geometryValidator;

    @InjectMocks
    private AreaService areaService;

    private UUID areaId;
    private Area area;
    private User user;

    @BeforeEach
    void setUp() {
        areaId = UUID.randomUUID();
        AreaLevel level = AreaLevel.builder().level((short) 1).build();
        area = Area.builder()
                .id(areaId)
                .code("ZONE-01")
                .name("Zone 1")
                .areaLevel(level)
                .building("FPT_AROUND")
                .floor("G")
                .isActive(true)
                .build();

        user = User.builder()
                .id(UUID.randomUUID())
                .email("admin@campus.com")
                .build();
    }

    @Test
    @DisplayName("FIX-5: Backend always overrides type='polygon' and version=1 regardless of client input")
    void testSaveGeometryNormalizesTypeAndVersion() {
        AreaGeometry inputGeometry = AreaGeometry.builder()
                .type("INVALID_TYPE")
                .version(999)
                .vertices(List.of(
                        new AreaGeometry.Vertex(new BigDecimal("0.1"), new BigDecimal("0.1")),
                        new AreaGeometry.Vertex(new BigDecimal("0.5"), new BigDecimal("0.1")),
                        new AreaGeometry.Vertex(new BigDecimal("0.3"), new BigDecimal("0.5"))
                ))
                .build();

        when(areaRepository.findByIdAndDeletedAtIsNull(areaId)).thenReturn(Optional.of(area));
        when(areaRepository.findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull("FPT_AROUND", "G"))
                .thenReturn(Collections.emptyList());
        when(userRepository.findByEmail("admin@campus.com")).thenReturn(Optional.of(user));
        when(areaRepository.save(any(Area.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AreaGeometryResponse response = areaService.saveGeometry(areaId, inputGeometry, "admin@campus.com");

        assertEquals("polygon", response.geometry().getType());
        assertEquals(1, response.geometry().getVersion());

        ArgumentCaptor<Area> areaCaptor = ArgumentCaptor.forClass(Area.class);
        verify(areaRepository).save(areaCaptor.capture());
        assertEquals("polygon", areaCaptor.getValue().getGeometry().getType());
        assertEquals(1, areaCaptor.getValue().getGeometry().getVersion());
    }
}
