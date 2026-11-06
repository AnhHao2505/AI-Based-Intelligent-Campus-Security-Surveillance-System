package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.repository.CameraRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Xoá camera (xoá mềm) giữ nguyên khu vực cũ: cột cameras.area_id là NOT NULL (V76), camera đã xoá
 * vẫn trỏ về khu để tra lịch sử; danh sách camera đang dùng của khu lọc theo deleted_at.
 */
@ExtendWith(MockitoExtension.class)
class CameraServiceDeleteKeepsAreaTest {

    @Mock private CameraRepository cameraRepository;
    @Mock private MediaMtxService mediaMtxService;

    @InjectMocks private CameraService cameraService;

    @Test
    @DisplayName("DeleteCamera: soft delete keeps the original area, marks DECOMMISSIONED/OFFLINE and cleans the MediaMTX path")
    void deleteCamera_keepsOriginalArea() {
        UUID cameraId = UUID.randomUUID();
        Area area = Area.builder().id(UUID.randomUUID()).name("Phòng Lab IoT").build();
        Camera camera = Camera.builder()
                .id(cameraId)
                .cameraCode("CAM-DEL-001")
                .name("Camera Lab IoT")
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build();
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));

        assertDoesNotThrow(() -> cameraService.deleteCamera(cameraId));

        ArgumentCaptor<Camera> saved = ArgumentCaptor.forClass(Camera.class);
        verify(cameraRepository).save(saved.capture());
        assertSame(area, saved.getValue().getArea(), "Camera đã xoá phải giữ khu vực cũ");
        assertEquals(CameraStatus.DECOMMISSIONED, saved.getValue().getStatus());
        assertEquals(OperationalStatus.OFFLINE, saved.getValue().getOperationalStatus());
        assertNotNull(saved.getValue().getDeletedAt());
        verify(mediaMtxService).deleteCameraPath("CAM-DEL-001");
    }
}
