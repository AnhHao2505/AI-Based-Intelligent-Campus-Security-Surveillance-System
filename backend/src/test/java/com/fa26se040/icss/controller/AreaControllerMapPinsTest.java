package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.area.AreaMapPinResponse;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.AreaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AreaControllerMapPinsTest {

    @Mock
    private AreaService areaService;

    @InjectMocks
    private AreaController areaController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(areaController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/areas/map-pins - Trả về danh sách pin khu vực thành công")
    void getMapPins_Success() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        List<AreaMapPinResponse> pins = List.of(
                AreaMapPinResponse.builder()
                        .id(id1)
                        .name("Cổng chính")
                        .areaLevel(AreaLevel.PUBLIC)
                        .building("FPT_AROUND")
                        .floor("G")
                        .centerLatitude(10.84175)
                        .centerLongitude(106.80922)
                        .isActive(true)
                        .build(),
                AreaMapPinResponse.builder()
                        .id(id2)
                        .name("Thư viện tầng G")
                        .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                        .building("FPT_AROUND")
                        .floor("G")
                        .centerLatitude(10.84148)
                        .centerLongitude(106.81008)
                        .isActive(true)
                        .build()
        );

        when(areaService.getMapPins(isNull())).thenReturn(pins);

        mockMvc.perform(get("/api/areas/map-pins")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(id1.toString()))
                .andExpect(jsonPath("$.data[0].name").value("Cổng chính"))
                .andExpect(jsonPath("$.data[0].centerLatitude").value(10.84175))
                .andExpect(jsonPath("$.data[0].centerLongitude").value(106.80922))
                .andExpect(jsonPath("$.data[1].id").value(id2.toString()))
                .andExpect(jsonPath("$.data[1].name").value("Thư viện tầng G"));
    }
}
