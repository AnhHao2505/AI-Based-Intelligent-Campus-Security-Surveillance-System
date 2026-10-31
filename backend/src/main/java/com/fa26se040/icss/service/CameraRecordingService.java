package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RecordingVideoResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.repository.CameraRepository;
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
     * Quét cả MinIO Object Storage và thư mục đĩa cục bộ:
     * 1. MinIO bucket camera-recordings (Ghi hình định kỳ)
     * 2. MinIO bucket security-evidence (Ghi hình sự cố an ninh)
     * 3. Thư mục đĩa recording & evidence (Backward compatibility)
     */
    public List<RecordingVideoResponse> listRecordings(
            String cameraCodeFilter,
            String keyword,
            String fromDateStr,
            String toDateStr
    ) {
        // Cache camera info from DB
        Map<String, Camera> cameraMap = cameraRepository.findAll().stream()
                .filter(c -> c.getCameraCode() != null)
                .collect(Collectors.toMap(
                        c -> c.getCameraCode().trim().toUpperCase(),
                        c -> c,
                        (existing, replacement) -> existing
                ));

        List<RecordingVideoResponse> results = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        // 1. Quét MinIO bucket camera-recordings & security-evidence
        if (minioStorageService != null) {
            scanMinioBucket(minioStorageService.getRecordingsBucket(), false, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results, seenKeys);
            scanMinioBucket(minioStorageService.getEvidenceBucket(), true, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results, seenKeys);
        }

        // 2. Quét thư mục đĩa (để tương thích ngược với video cũ đã hoàn tất)
        scanDirectory(recordingStoragePath, false, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results, seenKeys);
        scanDirectory(evidenceStoragePath, true, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results, seenKeys);

        // Sắp xếp mới nhất lên đầu
        results.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return results;
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
                            durationSeconds = Math.max(5, fileSizeBytes / (900 * 1024));
                        }
                    }
                    OffsetDateTime endTime = startTime.plusSeconds(durationSeconds);

                    if (fromDateStr != null && !fromDateStr.isBlank()) {
                        OffsetDateTime fromDate = OffsetDateTime.parse(fromDateStr + "T00:00:00+07:00");
                        if (startTime.isBefore(fromDate)) continue;
                    }
                    if (toDateStr != null && !toDateStr.isBlank()) {
                        OffsetDateTime toDate = OffsetDateTime.parse(toDateStr + "T23:59:59+07:00");
                        if (startTime.isAfter(toDate)) continue;
                    }

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

                    String dedupKey = cameraCode.toUpperCase() + ":" + filename.toLowerCase();
                    if (!seenKeys.add(dedupKey)) continue;

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
                            .resolution("1280x720 (HD 25fps)")
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
                            durationSeconds = Math.max(5, fileSizeBytes / (250 * 1024));
                        }
                    }
                    OffsetDateTime endTime = startTime.plusSeconds(durationSeconds);

                    if (fromDateStr != null && !fromDateStr.isBlank()) {
                        OffsetDateTime fromDate = OffsetDateTime.parse(fromDateStr + "T00:00:00+07:00");
                        if (startTime.isBefore(fromDate)) continue;
                    }
                    if (toDateStr != null && !toDateStr.isBlank()) {
                        OffsetDateTime toDate = OffsetDateTime.parse(toDateStr + "T23:59:59+07:00");
                        if (startTime.isAfter(toDate)) continue;
                    }

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
                            .resolution("1280x720 (HD 25fps)")
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
        // Kiểm tra an ninh nghiêm ngặt chống Path Traversal và rò rỉ file hệ thống (CWE-22)
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

                    // Hỗ trợ HTTP 206 Partial Content (Range Header) để trình duyệt đọc moov atom cuối file tức thì
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
