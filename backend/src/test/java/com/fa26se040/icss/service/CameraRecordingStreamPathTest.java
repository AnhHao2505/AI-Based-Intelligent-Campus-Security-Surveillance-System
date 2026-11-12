package com.fa26se040.icss.service;

import com.fa26se040.icss.repository.CameraRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BE-03: tham số {@code path} của GET /api/cameras/recordings/stream không đi ngược ra ngoài thư mục ghi hình
 * (ổ đĩa và MinIO). Bổ sung cho CameraRecordingServiceTest#testStreamVideo_securityRejection.
 */
@ExtendWith(MockitoExtension.class)
class CameraRecordingStreamPathTest {

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private MinioStorageService minioStorageService;

    @InjectMocks
    private CameraRecordingService cameraRecordingService;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("../, đường dẫn tuyệt đối, \\ đầu -> 400, không đọc MinIO")
    void traversalPaths_rejectedBeforeStorage() throws Exception {
        Path rec = Files.createDirectories(tempDir.resolve("recording"));
        Path evd = Files.createDirectories(tempDir.resolve("evidence"));
        Files.writeString(tempDir.resolve("secret.mp4"), "x");
        ReflectionTestUtils.setField(cameraRecordingService, "recordingStoragePath", rec.toString());
        ReflectionTestUtils.setField(cameraRecordingService, "evidenceStoragePath", evd.toString());

        for (String path : new String[]{
                "../secret.mp4",
                "CAM-001/../../secret.mp4",
                "CAM-001/..\\..\\secret.mp4",
                "/etc/secret.mp4",
                "\\secret.mp4",
                tempDir.resolve("secret.mp4").toString()
        }) {
            for (String source : new String[]{"recording", "evidence", "minio_recording", "minio_evidence"}) {
                var res = cameraRecordingService.streamVideo(path, source, null, false);
                assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode(), path + " / " + source);
            }
        }
        verify(minioStorageService, never()).statObject(anyString(), anyString());
    }
}
