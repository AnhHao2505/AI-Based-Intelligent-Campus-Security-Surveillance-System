package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RecordingVideoResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.repository.CameraRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
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

    @Value("${recording.storage.path:C:/DATN/AI-Based-Intelligent-Campus-Security-Surveillance-System/recording}")
    private String recordingStoragePath;

    @Value("${recording.evidence.path:C:/DATN/AI-Based-Intelligent-Campus-Security-Surveillance-System/evidence}")
    private String evidenceStoragePath;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yyyy - HH-mm-ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss"),
            DateTimeFormatter.ofPattern("dd_MM_yyyy_HH_mm_ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd - HH-mm-ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"),
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    );

    /**
     * Quét đệ quy cả 2 thư mục:
     * 1. C:\DATN\...\recording (Ghi hình định kỳ 1 phút/session)
     * 2. C:\DATN\...\evidence  (Ghi hình sự cố an ninh từ khi phát hiện tới khi Bảo vệ click Xác nhận đã xem)
     * Cấu trúc: {camera_id}/{year}/{month}/{day}/{filename}.mp4
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

        // Quét thư mục Continuous Recording
        scanDirectory(recordingStoragePath, false, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results);

        // Quét thư mục Incident Evidence Recording
        scanDirectory(evidenceStoragePath, true, cameraCodeFilter, keyword, fromDateStr, toDateStr, cameraMap, results);

        // Sắp xếp mới nhất lên đầu
        results.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return results;
    }

    private void scanDirectory(
            String dirPathStr,
            boolean isEvidenceFolder,
            String cameraCodeFilter,
            String keyword,
            String fromDateStr,
            String toDateStr,
            Map<String, Camera> cameraMap,
            List<RecordingVideoResponse> results
    ) {
        Path baseDirPath = Paths.get(dirPathStr).toAbsolutePath().normalize();
        log.info("🔍 [RecordingService] Quét thư mục (isEvidence={}): {}", isEvidenceFolder, baseDirPath);
        if (!Files.exists(baseDirPath)) {
            try {
                Files.createDirectories(baseDirPath);
                log.info("📁 Đã tự động tạo thư mục: {}", baseDirPath);
            } catch (IOException e) {
                log.error("❌ Không thể tạo thư mục {}: {}", baseDirPath, e.getMessage());
                return;
            }
        }

        try (Stream<Path> stream = Files.walk(baseDirPath)) {
            List<Path> videoFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".mp4"))
                    .toList();
            log.info("🔍 [RecordingService] Tìm thấy {} tệp mp4 trong {}", videoFiles.size(), baseDirPath);

            for (Path filePath : videoFiles) {
                try {
                    Path relativePath = baseDirPath.relativize(filePath);
                    String relPathStr = relativePath.toString().replace('\\', '/');

                    // Lấy camera_id từ thư mục đầu tiên
                    String cameraCode = "CAM-UNKNOWN";
                    if (relativePath.getNameCount() > 1) {
                        cameraCode = relativePath.getName(0).toString();
                    }

                    if (cameraCodeFilter != null && !cameraCodeFilter.isBlank()
                            && !cameraCode.equalsIgnoreCase(cameraCodeFilter.trim())) {
                        continue;
                    }

                    String filename = filePath.getFileName().toString();
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
                            durationSeconds = Math.max(5, fileSizeBytes / (250 * 1024)); // Ước lượng ~250KB/s
                        }
                    }
                    OffsetDateTime endTime = startTime.plusSeconds(durationSeconds);

                    // Lọc theo ngày nếu có
                    if (fromDateStr != null && !fromDateStr.isBlank()) {
                        OffsetDateTime fromDate = OffsetDateTime.parse(fromDateStr + "T00:00:00+07:00");
                        if (startTime.isBefore(fromDate)) continue;
                    }
                    if (toDateStr != null && !toDateStr.isBlank()) {
                        OffsetDateTime toDate = OffsetDateTime.parse(toDateStr + "T23:59:59+07:00");
                        if (startTime.isAfter(toDate)) continue;
                    }

                    // Tìm thông tin camera liên quan từ DB
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
                    } catch (Exception ex) {
                        log.debug("Không thể đọc Area proxy của camera [{}]: {}", cameraCode, ex.getMessage());
                    }

                    // Tìm theo keyword
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
     * Trả về Stream Video hỗ trợ HTTP Range chuẩn cho thẻ <video> HTML5.
     * Hỗ trợ tìm kiếm tệp ở cả recordingStoragePath và evidenceStoragePath.
     */
    public ResponseEntity<Resource> streamVideo(String relativePathStr, String source, HttpHeaders headers, boolean download) throws IOException {
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

        // Kiểm tra an ninh chống Directory Traversal Attack
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
        String baseName = filename.replace(".mp4", "").replaceFirst("^(?i)Incident[ _-]*", "").trim();

        // Thử parse qua các định dạng ngày giờ chuẩn
        for (DateTimeFormatter fmt : DATE_TIME_FORMATTERS) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(baseName, fmt);
                return ldt.atZone(VIETNAM_ZONE).toOffsetDateTime();
            } catch (Exception ignored) {
            }
        }

        // Thử regex tìm chuỗi ngày tháng giờ phút giây dạng dd-MM-yyyy...
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

        // Fallback: Lấy thời gian sửa đổi gần nhất của file trừ đi 60s
        long modifiedMillis = attrs.lastModifiedTime().toMillis();
        return OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(modifiedMillis),
                VIETNAM_ZONE
        ).minusSeconds(60);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
