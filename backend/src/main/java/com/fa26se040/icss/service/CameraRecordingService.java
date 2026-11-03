package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RecordingCompletedEventDto;
import com.fa26se040.icss.dto.camera.RecordingVideoResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraRecording;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.enums.RecordingStatus;
import com.fa26se040.icss.enums.RecordingStorageLocation;
import com.fa26se040.icss.enums.RecordingType;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraRecordingRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CameraRecordingService {

    private final CameraRepository cameraRepository;
    private final CameraRecordingRepository cameraRecordingRepository;
    private final SecurityIncidentRepository securityIncidentRepository;
    private final MinioStorageService minioStorageService;

    @Value("${recording.storage.path:C:/DATN/AI-Based-Intelligent-Campus-Security-Surveillance-System/recording}")
    private String recordingStoragePath;

    @Value("${recording.evidence.path:C:/DATN/AI-Based-Intelligent-Campus-Security-Surveillance-System/evidence}")
    private String evidenceStoragePath;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long MAX_RANGE_CHUNK_BYTES = 4L * 1024 * 1024; // 4MB chunk cho HTTP 206 Partial Content
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yyyy - HH-mm-ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss"),
            DateTimeFormatter.ofPattern("dd_MM_yyyy_HH_mm_ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd - HH-mm-ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"),
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    );

    /**
     * Tiếp nhận và lưu trữ (Idempotent UPSERT) metadata video ghi hình hoàn tất từ Edge AI qua MQTT.
     * Đồng thời đánh dấu DELETED các phân đoạn cũ đã bị dọn dẹp theo chính sách xoay vòng.
     */
    @Transactional
    public CameraRecording ingestCompletedRecording(RecordingCompletedEventDto dto) {
        if (dto == null
                || dto.getCameraCode() == null || dto.getCameraCode().isBlank()
                || dto.getStorageKey() == null || dto.getStorageKey().isBlank()
                || dto.getDurationSeconds() == null || dto.getDurationSeconds() < 1
                || dto.getFileSizeBytes() == null || dto.getFileSizeBytes() <= 0) {
            throw new CameraException(CameraErrorCode.ERR_RECORDING_001);
        }

        String normCamCode = dto.getCameraCode().trim().toUpperCase();
        String storageKey = dto.getStorageKey().trim();
        String filename = (dto.getFilename() != null && !dto.getFilename().isBlank())
                ? dto.getFilename().trim()
                : extractFilename(storageKey);

        RecordingType recType = "INCIDENT_EVIDENCE".equalsIgnoreCase(dto.getRecordingType())
                ? RecordingType.INCIDENT_EVIDENCE
                : RecordingType.PERIODIC_SEGMENT;

        RecordingStorageLocation storageLocation = "EDGE_LOCAL".equalsIgnoreCase(dto.getStorageLocation())
                ? RecordingStorageLocation.EDGE_LOCAL
                : RecordingStorageLocation.CLOUD_MINIO;

        OffsetDateTime startTime = parseIsoOffsetDateTime(dto.getStartTime(), OffsetDateTime.now(VIETNAM_ZONE).minusSeconds(dto.getDurationSeconds()));
        OffsetDateTime endTime = parseIsoOffsetDateTime(dto.getEndTime(), startTime.plusSeconds(dto.getDurationSeconds()));

        Camera camera = cameraRepository != null
                ? cameraRepository.findByCameraCode(normCamCode).orElse(null)
                : null;

        SecurityIncident incident = resolveSecurityIncident(dto.getIncidentId(), dto.getEventId());

        CameraRecording entity = cameraRecordingRepository.findByStorageKey(storageKey)
                .orElseGet(CameraRecording::new);

        entity.setCamera(camera);
        entity.setCameraCode(normCamCode);
        entity.setIncident(incident);
        entity.setRecordingType(recType);
        entity.setStorageLocation(storageLocation);
        entity.setStorageBucket(dto.getStorageBucket());
        entity.setStorageKey(storageKey);
        entity.setFilename(filename);
        entity.setStartTime(startTime);
        entity.setEndTime(endTime);
        entity.setDurationSeconds(dto.getDurationSeconds());
        entity.setFileSizeBytes(dto.getFileSizeBytes());
        entity.setResolution(dto.getResolution() != null && !dto.getResolution().isBlank() ? dto.getResolution() : "854x480");
        entity.setFps(dto.getFps() != null && dto.getFps() > 0 ? dto.getFps() : 15.0);
        entity.setChecksumSha256(dto.getChecksumSha256());
        entity.setStatus(RecordingStatus.READY);

        CameraRecording saved = cameraRecordingRepository.save(entity);

        if (dto.getDeletedStorageKeys() != null && !dto.getDeletedStorageKeys().isEmpty()) {
            for (String expiredKey : dto.getDeletedStorageKeys()) {
                if (expiredKey == null || expiredKey.isBlank()) continue;
                cameraRecordingRepository.findByStorageKey(expiredKey.trim()).ifPresent(oldRec -> {
                    if (oldRec.getStatus() != RecordingStatus.DELETED) {
                        oldRec.setStatus(RecordingStatus.DELETED);
                        cameraRecordingRepository.save(oldRec);
                    }
                });
            }
        }

        log.info("💾 [CameraRecording] Đã lưu metadata video [{}] camera={} duration={}s size={}B",
                storageKey, normCamCode, saved.getDurationSeconds(), saved.getFileSizeBytes());
        return saved;
    }

    /**
     * Quản lý chuyển trạng thái vòng đời bản ghi video:
     * Hợp lệ: RECORDING -> READY, RECORDING -> DELETED, READY -> DELETED.
     */
    @Transactional
    public CameraRecording transitionRecordingStatus(UUID recordingId, RecordingStatus targetStatus) {
        if (recordingId == null || targetStatus == null) {
            throw new CameraException(CameraErrorCode.ERR_RECORDING_002);
        }

        CameraRecording recording = cameraRecordingRepository.findById(recordingId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_RECORDING_001));

        RecordingStatus current = recording.getStatus();
        boolean valid = switch (current) {
            case RECORDING -> targetStatus == RecordingStatus.READY || targetStatus == RecordingStatus.DELETED;
            case READY -> targetStatus == RecordingStatus.DELETED;
            case DELETED -> false;
        };

        if (!valid) {
            throw new CameraException(CameraErrorCode.ERR_RECORDING_002);
        }

        recording.setStatus(targetStatus);
        return cameraRecordingRepository.save(recording);
    }

    /**
     * Liệt kê danh sách video:
     * 1. Ưu tiên đọc từ bảng CSDL camera_recordings (thời gian & thời lượng thực 100%).
     * 2. Tương thích ngược với các tệp cũ trên MinIO / ổ đĩa chưa được index vào CSDL.
     */
    public List<RecordingVideoResponse> listRecordings(
            String cameraCodeFilter,
            String keyword,
            String fromDateStr,
            String toDateStr
    ) {
        Map<String, Camera> cameraMap = cameraRepository.findAll().stream()
                .filter(c -> c.getCameraCode() != null)
                .collect(Collectors.toMap(
                        c -> c.getCameraCode().trim().toUpperCase(),
                        c -> c,
                        (existing, replacement) -> existing
                ));

        List<RecordingVideoResponse> results = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        // 1. Đọc từ CSDL camera_recordings (Primary Source of Truth)
        if (cameraRecordingRepository != null) {
            List<CameraRecording> dbRecordings = cameraRecordingRepository.findAllByStatusOrderByStartTimeDesc(RecordingStatus.READY);
            if (dbRecordings != null) {
                for (CameraRecording rec : dbRecordings) {
                    String dedupKey = rec.getCameraCode().toUpperCase() + ":" + rec.getFilename().toLowerCase();
                    seenKeys.add(dedupKey);
                    seenKeys.add(rec.getStorageKey());

                    if (cameraCodeFilter != null && !cameraCodeFilter.isBlank()
                            && !rec.getCameraCode().equalsIgnoreCase(cameraCodeFilter.trim())) {
                        continue;
                    }
                    if (!matchesDateRange(rec.getStartTime(), fromDateStr, toDateStr)) {
                        continue;
                    }

                    RecordingVideoResponse mapped = mapEntityToResponse(rec, cameraMap);
                    if (keyword != null && !keyword.isBlank() && !matchesKeyword(mapped, keyword)) {
                        continue;
                    }
                    results.add(mapped);
                }
            }
        }

        // 2. Tương thích ngược: Quét các file cũ chưa có trong CSDL trên MinIO & ổ đĩa
        if (minioStorageService != null) {
            scanMinioBucket(minioStorageService.getRecordingsBucket(), false, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results, seenKeys);
            scanMinioBucket(minioStorageService.getEvidenceBucket(), true, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results, seenKeys);
        }

        scanDirectory(recordingStoragePath, false, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results, seenKeys);
        scanDirectory(evidenceStoragePath, true, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results, seenKeys);

        results.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return results;
    }

    private RecordingVideoResponse mapEntityToResponse(CameraRecording rec, Map<String, Camera> cameraMap) {
        String cameraCode = rec.getCameraCode().toUpperCase();
        Camera cam = rec.getCamera() != null ? rec.getCamera() : cameraMap.get(cameraCode);
        String cameraName = cam != null ? cam.getName() : ("Camera " + cameraCode);
        String areaName = "Khuôn viên FPTU";
        String building = "Tòa nhà";
        String floor = "Tầng 1";
        try {
            if (cam != null && cam.getArea() != null) {
                if (cam.getArea().getName() != null) areaName = cam.getArea().getName();
                if (cam.getArea().getBuilding() != null) building = cam.getArea().getBuilding();
                if (cam.getArea().getFloor() != null) floor = "Tầng " + cam.getArea().getFloor();
            }
        } catch (Exception ignored) {
        }

        boolean isIncident = rec.getRecordingType() == RecordingType.INCIDENT_EVIDENCE;
        boolean isMinio = rec.getStorageLocation() == RecordingStorageLocation.CLOUD_MINIO;

        String encodedKey = URLEncoder.encode(rec.getStorageKey(), StandardCharsets.UTF_8);
        String sourceParam;
        String storageDisplay;
        if (isMinio) {
            sourceParam = isIncident ? "&source=minio_evidence" : "&source=minio_recording";
            String bucket = rec.getStorageBucket() != null ? rec.getStorageBucket()
                    : (isIncident ? "security-evidence" : "camera-recordings");
            storageDisplay = "minio://" + bucket + "/" + rec.getStorageKey();
        } else {
            sourceParam = isIncident ? "&source=evidence" : "&source=recording";
            storageDisplay = rec.getStorageKey();
        }

        String streamUrl = "/api/cameras/recordings/stream?path=" + encodedKey + sourceParam;
        String downloadUrl = streamUrl + "&download=true";

        int durSec = rec.getDurationSeconds() != null ? rec.getDurationSeconds() : 0;
        String durationDisplay = String.format("%02d:%02d (%ds)", durSec / 60, durSec % 60, durSec);
        long sizeBytes = rec.getFileSizeBytes() != null ? rec.getFileSizeBytes() : 0L;
        int fpsInt = rec.getFps() != null ? (int) Math.round(rec.getFps()) : 15;
        String resolutionDisplay = (rec.getResolution() != null ? rec.getResolution() : "854x480")
                + " (" + fpsInt + "fps)";

        return RecordingVideoResponse.builder()
                .id(rec.getId() != null ? rec.getId().toString() : ("REC-" + cameraCode + "-" + rec.getStartTime().toEpochSecond()))
                .cameraId(cameraCode)
                .cameraName(cameraName)
                .areaName(areaName)
                .building(building)
                .floor(floor)
                .eventType(isIncident ? "SECURITY_EVENT" : "NORMAL_RECORDING")
                .eventTypeName(isIncident ? "Sự cố an ninh (Incident Evidence)" : "Ghi hình định kỳ")
                .severity(isIncident ? "CRITICAL" : null)
                .startTime(rec.getStartTime())
                .endTime(rec.getEndTime())
                .duration(durationDisplay)
                .fileSize(formatFileSize(sizeBytes))
                .fileSizeBytes(sizeBytes)
                .resolution(resolutionDisplay)
                .format("MP4 (H.264 / AVC1)")
                .storagePath(storageDisplay)
                .relativePath(rec.getStorageKey())
                .videoUrl(streamUrl)
                .downloadUrl(downloadUrl)
                .filename(rec.getFilename())
                .notes(rec.getChecksumSha256() != null ? "SHA-256: " + rec.getChecksumSha256() : "Đã xác thực metadata từ Edge")
                .build();
    }

    private SecurityIncident resolveSecurityIncident(String incidentIdStr, String eventId) {
        if (securityIncidentRepository == null) return null;
        if (incidentIdStr != null && !incidentIdStr.isBlank()) {
            try {
                UUID uuid = UUID.fromString(incidentIdStr.trim());
                Optional<SecurityIncident> byUuid = securityIncidentRepository.findById(uuid);
                if (byUuid.isPresent()) return byUuid.get();
            } catch (IllegalArgumentException ignored) {
                Optional<SecurityIncident> byEvent = securityIncidentRepository.findByEventId(incidentIdStr.trim());
                if (byEvent.isPresent()) return byEvent.get();
            }
        }
        if (eventId != null && !eventId.isBlank()) {
            return securityIncidentRepository.findByEventId(eventId.trim()).orElse(null);
        }
        return null;
    }

    private OffsetDateTime parseIsoOffsetDateTime(String iso, OffsetDateTime fallback) {
        if (iso == null || iso.isBlank()) return fallback;
        try {
            return OffsetDateTime.parse(iso.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String extractFilename(String storageKey) {
        int idx = Math.max(storageKey.lastIndexOf('/'), storageKey.lastIndexOf('\\'));
        return idx >= 0 ? storageKey.substring(idx + 1) : storageKey;
    }

    private boolean matchesDateRange(OffsetDateTime startTime, String fromDateStr, String toDateStr) {
        if (startTime == null) return true;
        if (fromDateStr != null && !fromDateStr.isBlank()) {
            OffsetDateTime fromDate = OffsetDateTime.parse(fromDateStr + "T00:00:00+07:00");
            if (startTime.isBefore(fromDate)) return false;
        }
        if (toDateStr != null && !toDateStr.isBlank()) {
            OffsetDateTime toDate = OffsetDateTime.parse(toDateStr + "T23:59:59+07:00");
            if (startTime.isAfter(toDate)) return false;
        }
        return true;
    }

    private boolean matchesKeyword(RecordingVideoResponse r, String keyword) {
        String kw = keyword.toLowerCase().trim();
        return (r.getCameraId() != null && r.getCameraId().toLowerCase().contains(kw))
                || (r.getCameraName() != null && r.getCameraName().toLowerCase().contains(kw))
                || (r.getAreaName() != null && r.getAreaName().toLowerCase().contains(kw))
                || (r.getFilename() != null && r.getFilename().toLowerCase().contains(kw));
    }

    private static boolean isInProgressRecordingFilename(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase();
        return lower.endsWith(".recording.mp4") || lower.startsWith("_recording_");
    }

    private void scanMinioBucket(
            String bucketName,
            boolean isEvidenceBucket,
            String cameraCodeFilter,
            String keyword,
            String fromDateStr,
            String toDateStr,
            Map<String, Camera> cameraMap,
            List<RecordingVideoResponse> results,
            Set<String> seenKeys
    ) {
        if (minioStorageService == null || bucketName == null) return;
        try {
            Iterable<Result<Item>> objects = minioStorageService.listObjects(bucketName, "", true);
            if (objects == null) return;

            for (Result<Item> result : objects) {
                try {
                    Item item = result.get();
                    if (item.isDir()) continue;
                    String objectName = item.objectName();
                    if (objectName == null || !objectName.toLowerCase().endsWith(".mp4")) continue;
                    if (seenKeys.contains(objectName)) continue;

                    String[] parts = objectName.split("/");
                    String cameraCode = "CAM-UNKNOWN";
                    String filename = parts[parts.length - 1];
                    if (isInProgressRecordingFilename(filename)) continue;

                    if (parts.length > 1) {
                        cameraCode = (parts[0].equalsIgnoreCase("recordings") || parts[0].equalsIgnoreCase("evidence"))
                                ? (parts.length > 2 ? parts[1] : "CAM-UNKNOWN")
                                : parts[0];
                    }

                    if (cameraCodeFilter != null && !cameraCodeFilter.isBlank()
                            && !cameraCode.equalsIgnoreCase(cameraCodeFilter.trim())) {
                        continue;
                    }

                    String dedupKey = cameraCode.toUpperCase() + ":" + filename.toLowerCase();
                    if (!seenKeys.add(dedupKey)) continue;

                    long fileSizeBytes = item.size();
                    boolean isIncidentVideo = isEvidenceBucket || filename.toLowerCase().startsWith("incident");

                    OffsetDateTime lastModified = item.lastModified() != null ? item.lastModified().toOffsetDateTime() : OffsetDateTime.now(VIETNAM_ZONE);
                    OffsetDateTime startTime = parseStartTimeFromFilename(filename, lastModified);
                    long durationSeconds = 60;
                    if (isIncidentVideo) {
                        long diffSec = (lastModified.toInstant().toEpochMilli() - startTime.toInstant().toEpochMilli()) / 1000;
                        if (diffSec > 0 && diffSec <= 600) {
                            durationSeconds = diffSec;
                        } else {
                            durationSeconds = Math.max(5, fileSizeBytes / (120 * 1024));
                        }
                    }
                    OffsetDateTime endTime = startTime.plusSeconds(durationSeconds);

                    if (!matchesDateRange(startTime, fromDateStr, toDateStr)) continue;

                    Camera cam = cameraMap.get(cameraCode.toUpperCase());
                    String cameraName = cam != null ? cam.getName() : ("Camera " + cameraCode);
                    String areaName = "Khuôn viên FPTU";
                    String building = "Tòa nhà";
                    String floor = "Tầng 1";
                    try {
                        if (cam != null && cam.getArea() != null) {
                            if (cam.getArea().getName() != null) areaName = cam.getArea().getName();
                            if (cam.getArea().getBuilding() != null) building = cam.getArea().getBuilding();
                            if (cam.getArea().getFloor() != null) floor = "Tầng " + cam.getArea().getFloor();
                        }
                    } catch (Exception ignored) {}

                    if (keyword != null && !keyword.isBlank()) {
                        String kw = keyword.toLowerCase().trim();
                        boolean matches = cameraCode.toLowerCase().contains(kw)
                                || cameraName.toLowerCase().contains(kw)
                                || areaName.toLowerCase().contains(kw)
                                || filename.toLowerCase().contains(kw);
                        if (!matches) continue;
                    }

                    String encodedRelPath = URLEncoder.encode(objectName, StandardCharsets.UTF_8);
                    String sourceParam = isEvidenceBucket ? "&source=minio_evidence" : "&source=minio_recording";
                    String streamUrl = "/api/cameras/recordings/stream?path=" + encodedRelPath + sourceParam;
                    String downloadUrl = streamUrl + "&download=true";

                    String formattedSize = formatFileSize(fileSizeBytes);
                    String durationDisplay = String.format("%02d:%02d (%ds)", durationSeconds / 60, durationSeconds % 60, durationSeconds);
                    String eventType = isIncidentVideo ? "SECURITY_EVENT" : "NORMAL_RECORDING";
                    String eventTypeName = isIncidentVideo ? "Sự cố an ninh (Incident Evidence - MinIO)" : "Ghi hình định kỳ (1 phút/session - MinIO)";
                    String severity = isIncidentVideo ? "CRITICAL" : null;
                    String notes = "Video lưu tại MinIO Object Storage (bucket: " + bucketName + ").";
                    String idPrefix = isIncidentVideo ? "EVD-MINIO-" : "REC-MINIO-";

                    RecordingVideoResponse rec = RecordingVideoResponse.builder()
                            .id(idPrefix + cameraCode + "-" + startTime.toEpochSecond())
                            .cameraId(cameraCode)
                            .cameraName(cameraName)
                            .areaName(areaName)
                            .building(building)
                            .floor(floor)
                            .eventType(eventType)
                            .eventTypeName(eventTypeName)
                            .severity(severity)
                            .startTime(startTime)
                            .endTime(endTime)
                            .duration(durationDisplay)
                            .fileSize(formattedSize)
                            .fileSizeBytes(fileSizeBytes)
                            .resolution("854x480 (480p 15fps)")
                            .format("MP4 (H.264 / AVC1)")
                            .storagePath("minio://" + bucketName + "/" + objectName)
                            .relativePath(objectName)
                            .videoUrl(streamUrl)
                            .downloadUrl(downloadUrl)
                            .filename(filename)
                            .notes(notes)
                            .build();

                    results.add(rec);
                } catch (Exception ex) {
                    log.warn("⚠️ Bỏ qua object MinIO: {}", ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Không thể quét MinIO bucket {}: {}", bucketName, e.getMessage());
        }
    }

    private void scanDirectory(
            String dirPathStr,
            boolean isEvidenceFolder,
            String cameraCodeFilter,
            String keyword,
            String fromDateStr,
            String toDateStr,
            Map<String, Camera> cameraMap,
            List<RecordingVideoResponse> results,
            Set<String> seenKeys
    ) {
        Path baseDirPath = Paths.get(dirPathStr).toAbsolutePath().normalize();
        if (!Files.exists(baseDirPath)) {
            try {
                Files.createDirectories(baseDirPath);
            } catch (IOException e) {
                return;
            }
        }

        try (Stream<Path> stream = Files.walk(baseDirPath)) {
            List<Path> videoFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".mp4"))
                    .filter(p -> !isInProgressRecordingFilename(p.getFileName().toString()))
                    .toList();

            for (Path filePath : videoFiles) {
                try {
                    Path relativePath = baseDirPath.relativize(filePath);
                    String relPathStr = relativePath.toString().replace('\\', '/');
                    if (seenKeys.contains(relPathStr)) continue;

                    String cameraCode = "CAM-UNKNOWN";
                    if (relativePath.getNameCount() > 1) {
                        cameraCode = relativePath.getName(0).toString();
                    }

                    if (cameraCodeFilter != null && !cameraCodeFilter.isBlank()
                            && !cameraCode.equalsIgnoreCase(cameraCodeFilter.trim())) {
                        continue;
                    }

                    String filename = filePath.getFileName().toString();
                    String dedupKey = cameraCode.toUpperCase() + ":" + filename.toLowerCase();
                    if (!seenKeys.add(dedupKey)) continue;

                    long fileSizeBytes = Files.size(filePath);
                    BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);

                    boolean isIncidentVideo = isEvidenceFolder || filename.toLowerCase().startsWith("incident");

                    OffsetDateTime startTime = parseStartTime(filename, attrs);
                    long durationSeconds = 60;
                    if (isIncidentVideo) {
                        long diffSec = (attrs.lastModifiedTime().toMillis() - startTime.toInstant().toEpochMilli()) / 1000;
                        if (diffSec > 0 && diffSec < 86400) {
                            durationSeconds = diffSec;
                        } else {
                            durationSeconds = Math.max(5, fileSizeBytes / (120 * 1024));
                        }
                    }
                    OffsetDateTime endTime = startTime.plusSeconds(durationSeconds);

                    if (!matchesDateRange(startTime, fromDateStr, toDateStr)) continue;

                    Camera cam = cameraMap.get(cameraCode.toUpperCase());
                    String cameraName = cam != null ? cam.getName() : ("Camera " + cameraCode);
                    String areaName = "Khuôn viên FPTU";
                    String building = "Tòa nhà";
                    String floor = "Tầng 1";
                    try {
                        if (cam != null && cam.getArea() != null) {
                            if (cam.getArea().getName() != null) areaName = cam.getArea().getName();
                            if (cam.getArea().getBuilding() != null) building = cam.getArea().getBuilding();
                            if (cam.getArea().getFloor() != null) floor = "Tầng " + cam.getArea().getFloor();
                        }
                    } catch (Exception ignored) {}

                    if (keyword != null && !keyword.isBlank()) {
                        String kw = keyword.toLowerCase().trim();
                        boolean matches = cameraCode.toLowerCase().contains(kw)
                                || cameraName.toLowerCase().contains(kw)
                                || areaName.toLowerCase().contains(kw)
                                || filename.toLowerCase().contains(kw);
                        if (!matches) continue;
                    }

                    String encodedRelPath = URLEncoder.encode(relPathStr, StandardCharsets.UTF_8);
                    String sourceParam = isEvidenceFolder ? "&source=evidence" : "&source=recording";
                    String streamUrl = "/api/cameras/recordings/stream?path=" + encodedRelPath + sourceParam;
                    String downloadUrl = streamUrl + "&download=true";

                    String formattedSize = formatFileSize(fileSizeBytes);
                    String durationDisplay = String.format("%02d:%02d (%ds)", durationSeconds / 60, durationSeconds % 60, durationSeconds);

                    String eventType = isIncidentVideo ? "SECURITY_EVENT" : "NORMAL_RECORDING";
                    String eventTypeName = isIncidentVideo ? "Sự cố an ninh (Incident Evidence)" : "Ghi hình định kỳ (1 phút/session)";
                    String severity = isIncidentVideo ? "CRITICAL" : null;
                    String notes = isIncidentVideo
                            ? "Video ghi lại bằng chứng sự cố an ninh từ khi phát hiện tới khi Bảo vệ 'Xác nhận đã xem' (lưu tại evidence/)."
                            : "Video 1 phút chuẩn xác lưu tại ổ đĩa hệ thống (lưu tại recording/).";
                    String idPrefix = isIncidentVideo ? "EVD-" : "REC-";

                    RecordingVideoResponse rec = RecordingVideoResponse.builder()
                            .id(idPrefix + cameraCode + "-" + startTime.toEpochSecond())
                            .cameraId(cameraCode)
                            .cameraName(cameraName)
                            .areaName(areaName)
                            .building(building)
                            .floor(floor)
                            .eventType(eventType)
                            .eventTypeName(eventTypeName)
                            .severity(severity)
                            .startTime(startTime)
                            .endTime(endTime)
                            .duration(durationDisplay)
                            .fileSize(formattedSize)
                            .fileSizeBytes(fileSizeBytes)
                            .resolution("854x480 (480p 15fps)")
                            .format("MP4 (H.264 / AVC1)")
                            .storagePath(filePath.toAbsolutePath().toString())
                            .relativePath(relPathStr)
                            .videoUrl(streamUrl)
                            .downloadUrl(downloadUrl)
                            .filename(filename)
                            .notes(notes)
                            .build();

                    results.add(rec);
                } catch (Exception ex) {
                    log.warn("⚠️ Bỏ qua file không đọc được {}: {}", filePath, ex.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("❌ Lỗi khi duyệt thư mục {}: {}", dirPathStr, e.getMessage());
        }
    }

    /**
     * Trả về Stream Video hỗ trợ HTTP Range (206 Partial Content) chuẩn cho thẻ <video> HTML5.
     * Hỗ trợ tìm kiếm tệp ở MinIO và cả ổ đĩa cục bộ.
     */
    public ResponseEntity<Resource> streamVideo(String relativePathStr, String source, HttpHeaders headers, boolean download) throws IOException {
        if (relativePathStr == null || relativePathStr.isBlank()
                || relativePathStr.contains("..")
                || relativePathStr.startsWith("/")
                || relativePathStr.startsWith("\\")
                || !relativePathStr.toLowerCase().endsWith(".mp4")) {
            log.warn("🚨 Từ chối truy cập đường dẫn không hợp lệ hoặc chứa ký tự nguy hiểm: {}", relativePathStr);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // 1. Kiểm tra nếu video được lưu tại MinIO
        if (source != null && source.startsWith("minio") && minioStorageService != null) {
            String bucket = source.contains("evidence")
                    ? minioStorageService.getEvidenceBucket()
                    : minioStorageService.getRecordingsBucket();
            try {
                var statOpt = minioStorageService.statObject(bucket, relativePathStr);
                if (statOpt.isPresent()) {
                    var stat = statOpt.get();
                    long totalLength = stat.size();
                    String filename = relativePathStr.contains("/") ? relativePathStr.substring(relativePathStr.lastIndexOf("/") + 1) : relativePathStr;

                    if (download) {
                        InputStream inputStream = minioStorageService.getObject(bucket, relativePathStr);
                        Resource resource = new InputStreamResource(inputStream) {
                            @Override
                            public long contentLength() {
                                return totalLength;
                            }
                        };
                        return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                                .contentType(MediaType.valueOf("video/mp4"))
                                .contentLength(totalLength)
                                .body(resource);
                    }

                    List<HttpRange> ranges = headers != null ? headers.getRange() : Collections.emptyList();
                    if (!ranges.isEmpty() && totalLength > 0) {
                        HttpRange range = ranges.get(0);
                        long start = range.getRangeStart(totalLength);
                        long end = range.getRangeEnd(totalLength);
                        if (end - start + 1 > MAX_RANGE_CHUNK_BYTES) {
                            end = start + MAX_RANGE_CHUNK_BYTES - 1;
                        }
                        long rangeLength = end - start + 1;

                        byte[] data;
                        try (InputStream is = minioStorageService.getObject(bucket, relativePathStr, start, rangeLength)) {
                            data = is.readAllBytes();
                        }
                        long actualEnd = start + Math.max(0, data.length - 1);

                        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                                .contentType(MediaType.valueOf("video/mp4"))
                                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + actualEnd + "/" + totalLength)
                                .contentLength(data.length)
                                .body(new ByteArrayResource(data));
                    }

                    InputStream inputStream = minioStorageService.getObject(bucket, relativePathStr);
                    Resource resource = new InputStreamResource(inputStream) {
                        @Override
                        public long contentLength() {
                            return totalLength;
                        }
                    };
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf("video/mp4"))
                            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                            .contentLength(totalLength)
                            .body(resource);
                }
            } catch (Exception e) {
                log.error("❌ Lỗi stream video từ MinIO bucket {} object {}: {}", bucket, relativePathStr, e.getMessage());
            }
        }

        // 2. Fallback đọc từ đĩa cục bộ
        Path recBasePath = Paths.get(recordingStoragePath).toAbsolutePath().normalize();
        Path evdBasePath = Paths.get(evidenceStoragePath).toAbsolutePath().normalize();

        Path targetPath;
        if ("evidence".equalsIgnoreCase(source)) {
            targetPath = evdBasePath.resolve(relativePathStr).normalize();
            if (!Files.exists(targetPath) || !targetPath.startsWith(evdBasePath)) {
                targetPath = recBasePath.resolve(relativePathStr).normalize();
            }
        } else {
            targetPath = recBasePath.resolve(relativePathStr).normalize();
            if (!Files.exists(targetPath) || !targetPath.startsWith(recBasePath)) {
                targetPath = evdBasePath.resolve(relativePathStr).normalize();
            }
        }

        if (!targetPath.startsWith(recBasePath) && !targetPath.startsWith(evdBasePath)) {
            log.warn("🚨 Từ chối truy cập đường dẫn bất thường: {}", relativePathStr);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        File file = targetPath.toFile();
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Resource resource = new FileSystemResource(file);
        long contentLength = file.length();

        if (download) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                    .contentType(MediaType.valueOf("video/mp4"))
                    .contentLength(contentLength)
                    .body(resource);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(contentLength)
                .body(resource);
    }

    private OffsetDateTime parseStartTime(String filename, BasicFileAttributes attrs) {
        long modifiedMillis = attrs.lastModifiedTime().toMillis();
        OffsetDateTime fallback = OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(modifiedMillis),
                VIETNAM_ZONE
        ).minusSeconds(60);
        return parseStartTimeFromFilename(filename, fallback);
    }

    private OffsetDateTime parseStartTimeFromFilename(String filename, OffsetDateTime fallback) {
        String baseName = filename.replace(".mp4", "").replaceFirst("^(?i)Incident[ _-]*", "").trim();

        for (DateTimeFormatter fmt : DATE_TIME_FORMATTERS) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(baseName, fmt);
                return ldt.atZone(VIETNAM_ZONE).toOffsetDateTime();
            } catch (Exception ignored) {
            }
        }

        Pattern p = Pattern.compile("(\\d{2})[-_](\\d{2})[-_](\\d{4})[-_ ]*(\\d{2})[-_](\\d{2})[-_](\\d{2})");
        Matcher m = p.matcher(baseName);
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = Integer.parseInt(m.group(3));
                int hour = Integer.parseInt(m.group(4));
                int min = Integer.parseInt(m.group(5));
                int sec = Integer.parseInt(m.group(6));
                LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, min, sec);
                return ldt.atZone(VIETNAM_ZONE).toOffsetDateTime();
            } catch (Exception ignored) {
            }
        }

        return fallback;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
