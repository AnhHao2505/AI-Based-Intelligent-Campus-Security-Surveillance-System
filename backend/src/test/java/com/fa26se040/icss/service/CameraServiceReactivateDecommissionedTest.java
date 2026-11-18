package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraDetailResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import com.fa26se040.icss.util.AesEncryptionUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Camera DECOMMISSIONED chưa xoá (deletedAt = null) kích hoạt lại như cũ: ACTIVE, giữ khu, lưu,
 * rồi đồng bộ lại path MediaMTX từ cấu hình luồng (sub stream ở path chính, main stream ở path "_main").
 */
@ExtendWith(MockitoExtension.class)
class CameraServiceReactivateDecommissionedTest {

    @Mock private CameraRepository cameraRepository;
    @Mock private CameraStreamConfigurationRepository cameraStreamConfigurationRepository;
    @Mock private MediaMtxService mediaMtxService;
    @Mock private AesEncryptionUtil aesEncryptionUtil;

    @InjectMocks private CameraService cameraService;

    @Test
    @DisplayName("ReactivateCamera: a decommissioned (not deleted) camera becomes ACTIVE, keeps its area and resyncs both MediaMTX paths")
    void reactivateCamera_decommissionedCamera_reactivatesAndResyncsPaths() {
        UUID cameraId = UUID.randomUUID();
        Area area = Area.builder().id(UUID.randomUUID()).name("Phòng Lab IoT").build();
        Camera camera = Camera.builder()
                .id(cameraId)
                .cameraCode("CAM-REA-001")
                .name("Camera Lab IoT")
                .status(CameraStatus.DECOMMISSIONED)
                .operationalStatus(OperationalStatus.OFFLINE)
                .area(area)
                .build();
        CameraStreamConfiguration cfg = CameraStreamConfiguration.builder()
                .camera(camera)
                .host("192.168.1.50")
                .port(554)
                .username("viewer")
                .credentialRef("cipher-text")
                .mainStreamPath("/stream1")
                .subStreamPath("stream2")
                .build();
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cameraStreamConfigurationRepository.findByCameraId(cameraId)).thenReturn(Optional.of(cfg));
        when(aesEncryptionUtil.decrypt("cipher-text")).thenReturn("p4ss");

        CameraDetailResponse response = cameraService.reactivateCamera(cameraId);

        ArgumentCaptor<Camera> saved = ArgumentCaptor.forClass(Camera.class);
        verify(cameraRepository).save(saved.capture());
        assertEquals(CameraStatus.ACTIVE, saved.getValue().getStatus());
        assertNull(saved.getValue().getDeletedAt());
        assertSame(area, saved.getValue().getArea(), "Kích hoạt lại phải giữ nguyên khu");
        assertEquals(CameraStatus.ACTIVE, response.getStatus());
        assertEquals(area.getId(), response.getAssignedArea().id());

        InOrder order = inOrder(cameraRepository, mediaMtxService);
        order.verify(cameraRepository).save(camera);
        order.verify(mediaMtxService).syncCameraPath("CAM-REA-001", "rtsp://viewer:p4ss@192.168.1.50:554/stream2");
        order.verify(mediaMtxService).syncCameraPath("CAM-REA-001_main", "rtsp://viewer:p4ss@192.168.1.50:554/stream1");
        verifyNoMoreInteractions(mediaMtxService);
    }

    @Test
    @DisplayName("F-08 (Cách B): ReactivateCamera tự gỡ cấu hình RTSP cũ nếu đã bị camera ACTIVE khác chiếm dụng")
    void reactivateCamera_whenRtspClaimedByAnotherActiveCamera_clearsStaleStreamConfigWithoutSyncingMediaMtx() {
        UUID cameraId = UUID.randomUUID();
        Area area = Area.builder().id(UUID.randomUUID()).name("Phòng Lab IoT").isActive(true).build();
        Camera camera = Camera.builder()
                .id(cameraId)
                .cameraCode("CAM-REA-002")
                .name("Camera Lab IoT 2")
                .status(CameraStatus.DECOMMISSIONED)
                .operationalStatus(OperationalStatus.OFFLINE)
                .area(area)
                .build();
        CameraStreamConfiguration cfg = CameraStreamConfiguration.builder()
                .camera(camera)
                .host("192.168.1.8")
                .port(554)
                .mainStreamPath("/stream")
                .build();
        camera.setStreamConfiguration(cfg);

        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(cameraRepository.save(any(Camera.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cameraStreamConfigurationRepository.findByCameraId(cameraId)).thenReturn(Optional.of(cfg));
        when(cameraStreamConfigurationRepository.existsActiveRtspConflict(
                cameraId,
                "192.168.1.8",
                554,
                "/stream",
                CameraStatus.DECOMMISSIONED
        )).thenReturn(true);

        CameraDetailResponse response = cameraService.reactivateCamera(cameraId);

        assertEquals(CameraStatus.ACTIVE, response.getStatus());
        assertNull(response.getStreamConfig(), "Cấu hình RTSP cũ bị chiếm phải được gỡ bỏ khi kích hoạt lại");
        verify(cameraStreamConfigurationRepository).delete(cfg);
        verifyNoInteractions(mediaMtxService);
    }
}
