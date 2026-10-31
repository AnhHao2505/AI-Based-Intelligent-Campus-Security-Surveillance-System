package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RecordingCompletedEventDto;
import com.fa26se040.icss.dto.camera.RecordingVideoResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraRecording;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.RecordingStatus;
import com.fa26se040.icss.enums.RecordingStorageLocation;
import com.fa26se040.icss.enums.RecordingType;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraRecordingRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CameraRecordingIngestionAndLifecycleTest {

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private CameraRecordingRepository cameraRecordingRepository;

    @Mock
    private SecurityIncidentRepository securityIncidentRepository;

    @Mock
    private MinioStorageService minioStorageService;

    @InjectMocks
    private CameraRecordingService cameraRecordingService;

    private Camera sampleCamera;
    private SecurityIncident sampleIncident;
    private UUID incidentUuid;

    @BeforeEach
    void setUp() {
        Area area = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Lab AI")
                .building("Tòa Alpha")
                .floor("2")
                .build();

        sampleCamera = Camera.builder()
                .id(UUID.randomUUID())
                .cameraCode("CAM-001")
                .name("Camera Cổng Chính")
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build();

        incidentUuid = UUID.randomUUID();
        sampleIncident = SecurityIncident.builder()
                .id(incidentUuid)
                .eventId("EVT-20261031-001")
                .cameraCode("CAM-001")
                .eventType("UNAUTHORIZED_ACCESS")
                .building("Tòa Alpha")
                .area(area)
                .build();
    }

    @Test
    @DisplayName("1. Happy Path: Ingest video bằng chứng hoàn tất vào CSDL với thời lượng thật (23s) và hiển thị đúng trên listRecordings")
    void ingestCompletedRecording_happyPath_persistsEntityAndReturnsExactDuration(@TempDir Path tempDir) {
        String storageKey = "CAM-001/2026/10/31/Incident 31-10-2026 - 14-30-00.mp4";
        RecordingCompletedEventDto dto = RecordingCompletedEventDto.builder()
                .cameraCode("CAM-001")
                .incidentId(incidentUuid.toString())
                .recordingType("INCIDENT_EVIDENCE")
                .storageLocation("CLOUD_MINIO")
                .storageBucket("security-evidence")
                .storageKey(storageKey)
                .filename("Incident 31-10-2026 - 14-30-00.mp4")
                .startTime("2026-10-31T14:30:00+07:00")
                .endTime("2026-10-31T14:30:23+07:00")
                .durationSeconds(23)
                .frameCount(345)
                .fps(15.0)
                .resolution("854x480")
                .fileSizeBytes(2_621_440L)
                .checksumSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .build();

        when(cameraRecordingRepository.findByStorageKey(storageKey)).thenReturn(Optional.empty());
        when(cameraRepository.findByCameraCode("CAM-001")).thenReturn(Optional.of(sampleCamera));
        when(securityIncidentRepository.findById(incidentUuid)).thenReturn(Optional.of(sampleIncident));
        when(cameraRecordingRepository.save(any(CameraRecording.class))).thenAnswer(inv -> {
            CameraRecording saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });

        CameraRecording saved = cameraRecordingService.ingestCompletedRecording(dto);

        assertNotNull(saved);
        assertEquals("CAM-001", saved.getCameraCode());
        assertEquals(sampleCamera, saved.getCamera());
        assertEquals(sampleIncident, saved.getIncident());
        assertEquals(RecordingType.INCIDENT_EVIDENCE, saved.getRecordingType());
        assertEquals(RecordingStorageLocation.CLOUD_MINIO, saved.getStorageLocation());
        assertEquals(RecordingStatus.READY, saved.getStatus());
        assertEquals(23, saved.getDurationSeconds());
        assertEquals("854x480", saved.getResolution());

        // Kiểm chứng listRecordings đọc từ DB và trả đúng 00:23 (23s) chứ không bị fake 60s
        when(cameraRepository.findAll()).thenReturn(List.of(sampleCamera));
        when(cameraRecordingRepository.findAllByStatusOrderByStartTimeDesc(RecordingStatus.READY))
                .thenReturn(List.of(saved));
        ReflectionTestUtils.setField(cameraRecordingService, "recordingStoragePath", tempDir.resolve("empty_rec").toString());
        ReflectionTestUtils.setField(cameraRecordingService, "evidenceStoragePath", tempDir.resolve("empty_evd").toString());

        List<RecordingVideoResponse> responses = cameraRecordingService.listRecordings("CAM-001", null, null, null);
        assertEquals(1, responses.size());
        RecordingVideoResponse resp = responses.get(0);
        assertEquals("00:23 (23s)", resp.getDuration());
        assertEquals("SECURITY_EVENT", resp.getEventType());
        assertTrue(resp.getVideoUrl().contains("source=minio_evidence"));
    }

    @Test
    @DisplayName("2. Idempotency & Retention Sync: Gọi lặp cùng storageKey chỉ cập nhật bản ghi hiện có và đánh dấu DELETED cho phân đoạn hết hạn")
    void ingestCompletedRecording_idempotentUpsertAndMarksDeletedKeys() {
        String storageKey = "CAM-001/2026/10/31/31-10-2026 - 15-00-00.mp4";
        String expiredKey = "CAM-001/2026/10/31/31-10-2026 - 14-00-00.mp4";

        CameraRecording existing = CameraRecording.builder()
                .id(UUID.randomUUID())
                .cameraCode("CAM-001")
                .storageKey(storageKey)
                .filename("31-10-2026 - 15-00-00.mp4")
                .recordingType(RecordingType.PERIODIC_SEGMENT)
                .storageLocation(RecordingStorageLocation.CLOUD_MINIO)
                .status(RecordingStatus.RECORDING)
                .durationSeconds(0)
                .fileSizeBytes(0L)
                .startTime(OffsetDateTime.parse("2026-10-31T15:00:00+07:00"))
                .endTime(OffsetDateTime.parse("2026-10-31T15:00:00+07:00"))
                .build();

        CameraRecording expiredRecord = CameraRecording.builder()
                .id(UUID.randomUUID())
                .cameraCode("CAM-001")
                .storageKey(expiredKey)
                .filename("31-10-2026 - 14-00-00.mp4")
                .recordingType(RecordingType.PERIODIC_SEGMENT)
                .storageLocation(RecordingStorageLocation.CLOUD_MINIO)
                .status(RecordingStatus.READY)
                .durationSeconds(60)
                .fileSizeBytes(5_000_000L)
                .startTime(OffsetDateTime.parse("2026-10-31T14:00:00+07:00"))
                .endTime(OffsetDateTime.parse("2026-10-31T14:01:00+07:00"))
                .build();

        when(cameraRecordingRepository.findByStorageKey(storageKey)).thenReturn(Optional.of(existing));
        when(cameraRecordingRepository.findByStorageKey(expiredKey)).thenReturn(Optional.of(expiredRecord));
        when(cameraRepository.findByCameraCode("CAM-001")).thenReturn(Optional.of(sampleCamera));
        when(cameraRecordingRepository.save(any(CameraRecording.class))).thenAnswer(inv -> inv.getArgument(0));

        RecordingCompletedEventDto duplicateDto = RecordingCompletedEventDto.builder()
                .cameraCode("CAM-001")
                .recordingType("PERIODIC_SEGMENT")
                .storageLocation("CLOUD_MINIO")
                .storageBucket("camera-recordings")
                .storageKey(storageKey)
                .filename("31-10-2026 - 15-00-00.mp4")
                .startTime("2026-10-31T15:00:00+07:00")
                .endTime("2026-10-31T15:01:00+07:00")
                .durationSeconds(60)
                .fileSizeBytes(6_120_000L)
                .deletedStorageKeys(List.of(expiredKey))
                .build();

        CameraRecording updated = cameraRecordingService.ingestCompletedRecording(duplicateDto);

        assertEquals(existing.getId(), updated.getId(), "Phải giữ nguyên UUID hiện có (Idempotent UPSERT)");
        assertEquals(RecordingStatus.READY, updated.getStatus());
        assertEquals(60, updated.getDurationSeconds());
        assertEquals(6_120_000L, updated.getFileSizeBytes());
        assertEquals(RecordingStatus.DELETED, expiredRecord.getStatus(), "Phân đoạn bị xóa trên MinIO phải chuyển sang DELETED");
    }

    @Test
    @DisplayName("3. Exception / Domain Error: Từ chối bản tin thiếu storageKey hoặc duration không hợp lệ với mã lỗi ERR_RECORDING_001")
    void ingestCompletedRecording_invalidPayload_throwsCameraException() {
        RecordingCompletedEventDto invalidDto = RecordingCompletedEventDto.builder()
                .cameraCode("CAM-001")
                .storageKey("   ")
                .durationSeconds(-5)
                .fileSizeBytes(0L)
                .build();

        CameraException ex = assertThrows(CameraException.class,
                () -> cameraRecordingService.ingestCompletedRecording(invalidDto));
        assertEquals(CameraErrorCode.ERR_RECORDING_001, ex.getErrorCode());
        verify(cameraRecordingRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. Valid State Transition: Chuyển trạng thái RECORDING -> READY -> DELETED thành công")
    void transitionRecordingStatus_validTransitions_succeeds() {
        UUID recId = UUID.randomUUID();
        CameraRecording recording = CameraRecording.builder()
                .id(recId)
                .cameraCode("CAM-001")
                .storageKey("CAM-001/seg1.mp4")
                .filename("seg1.mp4")
                .recordingType(RecordingType.PERIODIC_SEGMENT)
                .storageLocation(RecordingStorageLocation.CLOUD_MINIO)
                .status(RecordingStatus.RECORDING)
                .durationSeconds(60)
                .fileSizeBytes(1024L)
                .startTime(OffsetDateTime.now().minusMinutes(1))
                .endTime(OffsetDateTime.now())
                .build();

        when(cameraRecordingRepository.findById(recId)).thenReturn(Optional.of(recording));
        when(cameraRecordingRepository.save(any(CameraRecording.class))).thenAnswer(inv -> inv.getArgument(0));

        CameraRecording ready = cameraRecordingService.transitionRecordingStatus(recId, RecordingStatus.READY);
        assertEquals(RecordingStatus.READY, ready.getStatus());

        CameraRecording deleted = cameraRecordingService.transitionRecordingStatus(recId, RecordingStatus.DELETED);
        assertEquals(RecordingStatus.DELETED, deleted.getStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "READY, RECORDING",
            "DELETED, READY",
            "DELETED, RECORDING"
    })
    @DisplayName("5. Invalid State Transition Matrix: Từ chối mọi chuyển trạng thái ngược vòng đời với ERR_RECORDING_002")
    void transitionRecordingStatus_invalidTransitions_throwsErrRecording002(
            RecordingStatus currentStatus,
            RecordingStatus forbiddenTarget
    ) {
        UUID recId = UUID.randomUUID();
        CameraRecording recording = CameraRecording.builder()
                .id(recId)
                .cameraCode("CAM-001")
                .storageKey("CAM-001/seg1.mp4")
                .filename("seg1.mp4")
                .recordingType(RecordingType.PERIODIC_SEGMENT)
                .storageLocation(RecordingStorageLocation.CLOUD_MINIO)
                .status(currentStatus)
                .durationSeconds(60)
                .fileSizeBytes(1024L)
                .startTime(OffsetDateTime.now().minusMinutes(1))
                .endTime(OffsetDateTime.now())
                .build();

        when(cameraRecordingRepository.findById(recId)).thenReturn(Optional.of(recording));

        CameraException ex = assertThrows(CameraException.class,
                () -> cameraRecordingService.transitionRecordingStatus(recId, forbiddenTarget));
        assertEquals(CameraErrorCode.ERR_RECORDING_002, ex.getErrorCode());
        verify(cameraRecordingRepository, never()).save(any());
    }
}
