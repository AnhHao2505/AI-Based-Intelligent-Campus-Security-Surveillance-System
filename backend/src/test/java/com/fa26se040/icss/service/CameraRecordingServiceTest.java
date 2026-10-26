package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RecordingVideoResponse;
import com.fa26se040.icss.repository.CameraRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CameraRecordingServiceTest {

    @Mock
    private CameraRepository cameraRepository;

    @InjectMocks
    private CameraRecordingService cameraRecordingService;

    @Test
    @DisplayName("Quét cả thư mục recording và evidence, nhận diện SECURITY_EVENT từ evidence/")
    void testListRecordingsFromBothFolders() {
        when(cameraRepository.findAll()).thenReturn(Collections.emptyList());

        ReflectionTestUtils.setField(cameraRecordingService, "recordingStoragePath", "C:/DATN/AI-Based-Intelligent-Campus-Security-Surveillance-System/recording");
        ReflectionTestUtils.setField(cameraRecordingService, "evidenceStoragePath", "C:/DATN/AI-Based-Intelligent-Campus-Security-Surveillance-System/evidence");

        List<RecordingVideoResponse> list = cameraRecordingService.listRecordings(null, null, null, null);

        assertNotNull(list);
        assertFalse(list.isEmpty(), "Danh sách không được rỗng khi đã có video trong thư mục");

        // Kiểm tra xem có video sự cố an ninh SECURITY_EVENT nào được tìm thấy từ evidence/ không
        boolean hasSecurityEvent = list.stream().anyMatch(r -> "SECURITY_EVENT".equals(r.getEventType()));
        assertTrue(hasSecurityEvent, "Phải tìm thấy video sự cố an ninh trong thư mục evidence/");

        RecordingVideoResponse incidentVideo = list.stream()
                .filter(r -> "SECURITY_EVENT".equals(r.getEventType()))
                .findFirst()
                .orElse(null);

        assertNotNull(incidentVideo);
        assertTrue(List.of("CAM-006", "CAM-007").contains(incidentVideo.getCameraId()), "CameraId phải là CAM-006 hoặc CAM-007");
        assertTrue(incidentVideo.getFilename().startsWith("Incident"), "Tên file phải bắt đầu bằng Incident");
        assertEquals("CRITICAL", incidentVideo.getSeverity());
        assertTrue(incidentVideo.getVideoUrl().contains("source=evidence"));
    }
}
