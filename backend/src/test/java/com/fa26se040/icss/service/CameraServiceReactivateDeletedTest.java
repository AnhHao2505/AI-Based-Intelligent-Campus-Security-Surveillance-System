package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Camera đã xoá mềm không kích hoạt lại được ("xoá là mất"): reactivateCamera coi như không tìm thấy (ERR_CAM_002).
 */
@ExtendWith(MockitoExtension.class)
class CameraServiceReactivateDeletedTest {

    @Mock private CameraRepository cameraRepository;
    @Mock private CameraStreamConfigurationRepository cameraStreamConfigurationRepository;
    @Mock private MediaMtxService mediaMtxService;

    @InjectMocks private CameraService cameraService;

    @Test
    @DisplayName("ReactivateCamera: a soft-deleted camera throws ERR_CAM_002 and stays deleted")
    void reactivateCamera_deletedCamera_throwsNotFoundAndStaysDeleted() {
        UUID cameraId = UUID.randomUUID();
        OffsetDateTime deletedAt = OffsetDateTime.now().minusDays(1);
        Camera camera = Camera.builder()
                .id(cameraId)
                .cameraCode("CAM-DEL-002")
                .name("Camera đã xoá")
                .status(CameraStatus.DECOMMISSIONED)
                .operationalStatus(OperationalStatus.OFFLINE)
                .area(Area.builder().id(UUID.randomUUID()).name("Phòng Lab IoT").build())
                .deletedAt(deletedAt)
                .build();
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));

        CameraException ex = assertThrows(CameraException.class, () -> cameraService.reactivateCamera(cameraId));

        assertEquals(CameraErrorCode.ERR_CAM_002, ex.getErrorCode());
        assertEquals(CameraStatus.DECOMMISSIONED, camera.getStatus());
        assertEquals(deletedAt, camera.getDeletedAt());
        verify(cameraRepository, never()).save(any());
        verifyNoInteractions(cameraStreamConfigurationRepository, mediaMtxService);
    }
}
