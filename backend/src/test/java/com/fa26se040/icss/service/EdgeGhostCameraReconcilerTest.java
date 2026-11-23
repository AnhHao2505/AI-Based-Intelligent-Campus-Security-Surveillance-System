package com.fa26se040.icss.service;

import com.fa26se040.icss.repository.CameraRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EdgeGhostCameraReconcilerTest {

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private EdgeMqttControlService edgeMqttControlService;

    private EdgeGhostCameraReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new EdgeGhostCameraReconciler(cameraRepository, edgeMqttControlService, new ObjectMapper());
    }

    private static String heartbeat(String status, String... codes) {
        StringBuilder cams = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) {
                cams.append(',');
            }
            cams.append("{\"camera_code\":\"").append(codes[i]).append("\",\"fps\":15.0,\"connected\":true}");
        }
        return "{\"edge_id\":\"edge-01\",\"status\":\"" + status + "\",\"cameras\":[" + cams + "]}";
    }

    @Test
    @DisplayName("Camera edge đang chạy nhưng DB không còn -> gửi enabled=false (retained) cho đúng camera đó")
    void ghostCamera_isDisabled() {
        when(cameraRepository.findExistingUpperCameraCodes(Set.of("CAM-002", "CAM-007"))).thenReturn(List.of("CAM-007"));

        reconciler.reconcile(heartbeat("ONLINE", "CAM-002", "CAM-007"));

        verify(edgeMqttControlService).publishCameraStreamConfig(eq("CAM-002"), eq(""), eq(false), isNull());
        verify(edgeMqttControlService, never()).publishCameraStreamConfig(eq("CAM-007"), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Mã camera so không phân biệt hoa thường: cam-007 trong heartbeat khớp CAM-007 trong DB")
    void lowerCaseCode_matchesExistingCamera() {
        when(cameraRepository.findExistingUpperCameraCodes(Set.of("CAM-007"))).thenReturn(List.of("CAM-007"));

        reconciler.reconcile(heartbeat("ONLINE", "cam-007"));

        verifyNoInteractions(edgeMqttControlService);
    }

    @Test
    @DisplayName("Mọi camera đều còn trong DB -> không gửi gì")
    void allCamerasExist_noPublish() {
        when(cameraRepository.findExistingUpperCameraCodes(anyCollection())).thenReturn(List.of("CAM-001", "CAM-007"));

        reconciler.reconcile(heartbeat("ONLINE", "CAM-001", "CAM-007"));

        verifyNoInteractions(edgeMqttControlService);
    }

    @Test
    @DisplayName("Heartbeat OFFLINE (LWT) hoặc không có camera -> không truy vấn DB, không gửi gì")
    void offlineOrEmpty_isIgnored() {
        reconciler.reconcile(heartbeat("OFFLINE", "CAM-002"));
        reconciler.reconcile(heartbeat("ONLINE"));
        reconciler.reconcile("");
        reconciler.reconcile(null);

        verifyNoInteractions(cameraRepository);
        verifyNoInteractions(edgeMqttControlService);
    }

    @Test
    @DisplayName("Heartbeat dồn dập: chỉ gửi lệnh tắt 1 lần trong RESEND_INTERVAL_MS")
    void repeatedHeartbeats_publishOnceWithinInterval() {
        when(cameraRepository.findExistingUpperCameraCodes(anyCollection())).thenReturn(List.of());

        reconciler.reconcile(heartbeat("ONLINE", "CAM-002"));
        reconciler.reconcile(heartbeat("ONLINE", "CAM-002"));
        reconciler.reconcile(heartbeat("ONLINE", "CAM-002"));

        verify(edgeMqttControlService, times(1)).publishCameraStreamConfig(eq("CAM-002"), eq(""), eq(false), isNull());
    }

    @Test
    @DisplayName("Lỗi DB -> không gửi lệnh tắt (không tắt nhầm camera đang có), không ném lỗi ra consumer MQTT")
    void dbError_doesNotDisableAnything() {
        when(cameraRepository.findExistingUpperCameraCodes(anyCollection())).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> reconciler.reconcile(heartbeat("ONLINE", "CAM-002")));

        verifyNoInteractions(edgeMqttControlService);
    }

    @Test
    @DisplayName("Payload hỏng -> bỏ qua, không ném lỗi")
    void malformedPayload_isIgnored() {
        assertDoesNotThrow(() -> reconciler.reconcile("{not json"));

        verifyNoInteractions(cameraRepository);
        verifyNoInteractions(edgeMqttControlService);
    }
}
