package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RecordingVideoResponse;
import com.fa26se040.icss.repository.CameraRepository;
import io.minio.Result;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CameraRecordingServiceTest {

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private MinioStorageService minioStorageService;

    @InjectMocks
    private CameraRecordingService cameraRecordingService;

    @Test
    @DisplayName("1. Quét cả thư mục recording và evidence trên đĩa, nhận diện SECURITY_EVENT")
    void testListRecordingsFromBothFolders(@TempDir Path tempDir) throws IOException {
        when(cameraRepository.findAll()).thenReturn(Collections.emptyList());

        Path recDir = tempDir.resolve("recording");
        Path evdDir = tempDir.resolve("evidence");

        Path recCamDir = recDir.resolve("CAM-006/2026/10/26");
        Path evdCamDir = evdDir.resolve("CAM-006/2026/10/26");
        Files.createDirectories(recCamDir);
        Files.createDirectories(evdCamDir);

        Files.createFile(recCamDir.resolve("26-10-2026 - 10-00-00.mp4"));
        Files.createFile(evdCamDir.resolve("Incident 26-10-2026 - 10-05-00.mp4"));

        ReflectionTestUtils.setField(cameraRecordingService, "recordingStoragePath", recDir.toString());
        ReflectionTestUtils.setField(cameraRecordingService, "evidenceStoragePath", evdDir.toString());

        List<RecordingVideoResponse> list = cameraRecordingService.listRecordings(null, null, null, null);

        assertNotNull(list);
        assertEquals(2, list.size(), "Phải tìm thấy đủ 2 video từ cả 2 thư mục");

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

    @Test
    @DisplayName("2. Quét video từ MinIO bucket, chuyển đổi chuẩn xác sang RecordingVideoResponse")
    void testListRecordingsFromMinIO(@TempDir Path tempDir) throws Exception {
        when(cameraRepository.findAll()).thenReturn(Collections.emptyList());
        when(minioStorageService.getRecordingsBucket()).thenReturn("camera-recordings");
        when(minioStorageService.getEvidenceBucket()).thenReturn("security-evidence");

        Item mockItem = mock(Item.class);
        when(mockItem.isDir()).thenReturn(false);
        when(mockItem.objectName()).thenReturn("CAM-001/2026/10/31/31-10-2026 - 11-00-00.mp4");
        when(mockItem.size()).thenReturn(1048576L);
        when(mockItem.lastModified()).thenReturn(ZonedDateTime.now());

        Result<Item> mockResult = new Result<>(mockItem);
        when(minioStorageService.listObjects(eq("camera-recordings"), anyString(), eq(true)))
                .thenReturn(List.of(mockResult));
        when(minioStorageService.listObjects(eq("security-evidence"), anyString(), eq(true)))
                .thenReturn(Collections.emptyList());

        ReflectionTestUtils.setField(cameraRecordingService, "recordingStoragePath", tempDir.resolve("empty_rec").toString());
        ReflectionTestUtils.setField(cameraRecordingService, "evidenceStoragePath", tempDir.resolve("empty_evd").toString());

        List<RecordingVideoResponse> list = cameraRecordingService.listRecordings("CAM-001", null, null, null);

        assertNotNull(list);
        assertEquals(1, list.size());
        RecordingVideoResponse video = list.get(0);
        assertEquals("CAM-001", video.getCameraId());
        assertTrue(video.getVideoUrl().contains("source=minio_recording"));
        assertTrue(video.getStoragePath().startsWith("minio://camera-recordings/"));
    }

    @Test
    @DisplayName("3. Security: streamVideo từ chối đường dẫn chứa path traversal hoặc file không phải mp4")
    void testStreamVideo_securityRejection() throws IOException {
        var res1 = cameraRecordingService.streamVideo("../../etc/passwd", "recording", null, false);
        assertEquals(HttpStatus.BAD_REQUEST, res1.getStatusCode());

        var res2 = cameraRecordingService.streamVideo("CAM-001/2026/10/31/script.sh", "recording", null, false);
        assertEquals(HttpStatus.BAD_REQUEST, res2.getStatusCode());

        var res3 = cameraRecordingService.streamVideo("   ", "recording", null, false);
        assertEquals(HttpStatus.BAD_REQUEST, res3.getStatusCode());
    }

    @Test
    @DisplayName("4. Bỏ qua các file .recording.mp4 đang ghi hình dở (chưa đóng moov atom)")
    void testListRecordings_skipsInProgressRecordingFiles(@TempDir Path tempDir) throws IOException {
        when(cameraRepository.findAll()).thenReturn(Collections.emptyList());

        Path recDir = tempDir.resolve("recording");
        Path evdDir = tempDir.resolve("evidence");
        Path camDir = evdDir.resolve("CAM-001/2026/10/31");
        Files.createDirectories(recDir);
        Files.createDirectories(camDir);

        Files.createFile(camDir.resolve("Incident 31-10-2026 - 13-39-54.recording.mp4"));
        Files.createFile(camDir.resolve("Incident 31-10-2026 - 13-40-00.mp4"));

        ReflectionTestUtils.setField(cameraRecordingService, "recordingStoragePath", recDir.toString());
        ReflectionTestUtils.setField(cameraRecordingService, "evidenceStoragePath", evdDir.toString());

        List<RecordingVideoResponse> list = cameraRecordingService.listRecordings(null, null, null, null);
        assertEquals(1, list.size(), "Chỉ được liệt kê file .mp4 đã hoàn tất, bỏ qua file .recording.mp4");
        assertEquals("Incident 31-10-2026 - 13-40-00.mp4", list.get(0).getFilename());
    }

    @Test
    @DisplayName("5. Stream video từ MinIO hỗ trợ HTTP Range (206 Partial Content) để đọc moov atom cuối file")
    void testStreamVideoFromMinIO_supportsHttpRange() throws Exception {
        when(minioStorageService.getRecordingsBucket()).thenReturn("camera-recordings");
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(10000L);
        when(minioStorageService.statObject("camera-recordings", "CAM-001/2026/10/31/video.mp4"))
                .thenReturn(Optional.of(stat));

        byte[] partialBytes = new byte[1000];
        when(minioStorageService.getObject("camera-recordings", "CAM-001/2026/10/31/video.mp4", 9000L, 1000L))
                .thenReturn(new ByteArrayInputStream(partialBytes));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=9000-9999");

        var response = cameraRecordingService.streamVideo(
                "CAM-001/2026/10/31/video.mp4", "minio_recording", headers, false);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 9000-9999/10000", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(1000L, response.getHeaders().getContentLength());
    }
}
