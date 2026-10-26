package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RecordingVideoResponse;
import com.fa26se040.icss.repository.CameraRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void testListRecordingsFromBothFolders(@TempDir Path tempDir) throws IOException {
        when(cameraRepository.findAll()).thenReturn(Collections.emptyList());

        Path recDir = tempDir.resolve("recording");
        Path evdDir = tempDir.resolve("evidence");

        // Tạo thư mục giả lập CAM-006 trong recording và evidence
        Path recCamDir = recDir.resolve("CAM-006/2026/10/26");
        Path evdCamDir = evdDir.resolve("CAM-006/2026/10/26");
        Files.createDirectories(recCamDir);
        Files.createDirectories(evdCamDir);

        // Tạo dummy file video
        Files.createFile(recCamDir.resolve("26-10-2026 - 10-00-00.mp4"));
        Files.createFile(evdCamDir.resolve("Incident 26-10-2026 - 10-05-00.mp4"));

        ReflectionTestUtils.setField(cameraRecordingService, "recordingStoragePath", recDir.toString());
        ReflectionTestUtils.setField(cameraRecordingService, "evidenceStoragePath", evdDir.toString());

        List<RecordingVideoResponse> list = cameraRecordingService.listRecordings(null, null, null, null);

        assertNotNull(list);
        assertEquals(2, list.size(), "Phải tìm thấy đủ 2 video từ cả 2 thư mục");

        // Kiểm tra xem có video sự cố an ninh SECURITY_EVENT nào được tìm thấy từ evidence/ không
        boolean hasSecurityEvent = list.stream().anyMatch(r -> "SECURITY_EVENT".equals(r.getEventType()));
        assertTrue(hasSecurityEvent, "Phải tìm thấy video sự cố an ninh trong thư mục evidence/");

        RecordingVideoResponse incidentVideo = list.stream()
                .filter(r -> "SECURITY_EVENT".equals(r.getEventType()))
                .findFirst()
                .orElse(null);

        assertNotNull(incidentVideo);
        assertEquals("CAM-006", incidentVideo.getCameraId());
        assertTrue(incidentVideo.getFilename().startsWith("Incident"), "Tên file phải bắt đầu bằng Incident");
        assertEquals("CRITICAL", incidentVideo.getSeverity());
        assertTrue(incidentVideo.getVideoUrl().contains("source=evidence"));
    }
}
