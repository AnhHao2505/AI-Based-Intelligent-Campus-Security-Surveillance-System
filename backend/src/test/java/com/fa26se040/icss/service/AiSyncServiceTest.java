package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.ai.AiSyncDataDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiSyncServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AiSyncService aiSyncService;

    @Test
    @DisplayName("Phase 3: AiSyncService dùng LEFT JOIN areas và đưa cả PUBLIC_AREA lẫn CORRIDOR vào publicCameraCodes")
    void getAiSyncData_includesPublicAndCorridorCamerasInPublicSet() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(Collections.emptyList());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);

            when(rs.getString("camera_code")).thenReturn("CAM-RESTRICT", "CAM-PUBLIC", "CAM-CORR");
            when(rs.getString("area_name")).thenReturn("Phòng Server", "Sảnh Chính", "Hành lang Cánh Đông");
            when(rs.getBoolean("is_public")).thenReturn(false, true, true);

            handler.processRow(rs);
            handler.processRow(rs);
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(sqlCaptor.capture(), any(RowCallbackHandler.class));

        AiSyncDataDto dto = aiSyncService.getAiSyncData();

        String cameraSql = sqlCaptor.getValue();
        assertTrue(cameraSql.contains("LEFT JOIN areas"), "Phải dùng LEFT JOIN areas để không làm rớt camera CORRIDOR (area_id IS NULL)");
        assertTrue(cameraSql.contains("PUBLIC_AREA") && cameraSql.contains("CORRIDOR"),
                "SQL phải gom cả camera PUBLIC_AREA lẫn CORRIDOR vào is_public");
        assertTrue(cameraSql.contains("COALESCE(a.name, c.name)"),
                "Camera CORRIDOR phải fallback area_name sang tên camera");

        assertEquals(3, dto.getCameraAreaMap().size());
        assertEquals("Hành lang Cánh Đông", dto.getCameraAreaMap().get("CAM-CORR"));

        assertFalse(dto.getPublicCameraCodes().contains("CAM-RESTRICT"));
        assertTrue(dto.getPublicCameraCodes().contains("CAM-PUBLIC"));
        assertTrue(dto.getPublicCameraCodes().contains("CAM-CORR"));
    }
}
