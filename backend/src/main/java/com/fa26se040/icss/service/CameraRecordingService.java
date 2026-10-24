package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.RecordingVideoResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.repository.CameraRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
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

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yyyy - HH-mm-ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy-HH-mm-ss"),
            DateTimeFormatter.ofPattern("dd_MM_yyyy_HH_mm_ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd - HH-mm-ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"),
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    );

    public Path getResolvedStoragePath() {
        Path configuredPath = Paths.get(recordingStoragePath).toAbsolutePath().normalize();
        if (Files.exists(configuredPath)) {
            return configuredPath;
        }

        // Thử tìm theo cấu trúc thư mục gốc dự án (../recording nếu đang ở thư mục backend)
        Path rootRecording = Paths.get("..", "recording").toAbsolutePath().normalize();
        if (Files.exists(rootRecording)) {
            return rootRecording;
        }

        Path localRecording = Paths.get("recording").toAbsolutePath().normalize();
        if (Files.exists(localRecording)) {
            return localRecording;
        }

        // Tự động tạo thư mục fallback
        try {
            Files.createDirectories(configuredPath);
            return configuredPath;
        } catch (Exception e) {
            try {
                Files.createDirectories(localRecording);
                return localRecording;
            } catch (Exception ignored) {
            }
        }
        return configuredPath;
    }

    /**
     * Quét đệ quy toàn bộ thư mục recording và tìm tất cả file video .mp4.
     * Cấu trúc: {camera_id}/{year}/{month}/{day}/{filename}.mp4
     */
    public List<RecordingVideoResponse> listRecordings(
            String cameraCodeFilter,
            String keyword,
            String fromDateStr,
            String toDateStr
    ) {
        Path baseDirPath = getResolvedStoragePath();
        log.info("🔍 [RecordingService] Quét thư mục lưu trữ video: {}", baseDirPath);

        // Cache camera info from DB
        Map<String, Camera> cameraMap = cameraRepository.findAll().stream()
                .filter(c -> c.getCameraCode() != null)
                .collect(Collectors.toMap(
                        c -> c.getCameraCode().trim().toUpperCase(),
                        c -> c,
                        (existing, replacement) -> existing
                ));

        List<RecordingVideoResponse> results = new ArrayList<>();

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

                    OffsetDateTime startTime = parseStartTime(filename, attrs);
                    OffsetDateTime endTime = startTime.plusSeconds(60);

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
                    String streamUrl = "/api/cameras/recordings/stream?path=" + encodedRelPath;
                    String downloadUrl = streamUrl + "&download=true";

                    String formattedSize = formatFileSize(fileSizeBytes);

                    RecordingVideoResponse rec = RecordingVideoResponse.builder()
                            .id("REC-" + cameraCode + "-" + startTime.toEpochSecond())
                            .cameraId(cameraCode)
                            .cameraName(cameraName)
                            .areaName(areaName)
                            .building(building)
                            .floor(floor)
                            .eventType("NORMAL_RECORDING")
                            .eventTypeName("Ghi hình định kỳ (1 phút/session)")
                            .severity(null)
                            .startTime(startTime)
                            .endTime(endTime)
                            .duration("01:00 (60s)")
                            .fileSize(formattedSize)
                            .fileSizeBytes(fileSizeBytes)
                            .resolution("1280x720 (HD 25fps)")
                            .format("MP4 (H.264 / AVC1)")
                            .storagePath(filePath.toAbsolutePath().toString())
                            .relativePath(relPathStr)
                            .videoUrl(streamUrl)
                            .downloadUrl(downloadUrl)
                            .filename(filename)
                            .notes("Video 1 phút chuẩn xác lưu tại ổ đĩa hệ thống.")
                            .build();

                    results.add(rec);
                } catch (Exception ex) {
                    log.warn("⚠️ Bỏ qua file không đọc được {}: {}", filePath, ex.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("❌ Lỗi khi duyệt thư mục recordings: {}", e.getMessage());
        }

        // Sắp xếp mới nhất lên đầu
        results.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        return results;
    }

    /**
     * Trả về Stream Video hỗ trợ HTTP Range chuẩn RFC 7233 cho thẻ <video> HTML5
     */
    public ResponseEntity<?> streamVideo(String relativePathStr, HttpHeaders headers, boolean download) throws IOException {
        Path baseDirPath = getResolvedStoragePath();
        Path targetPath = baseDirPath.resolve(relativePathStr).normalize();

        // Kiểm tra an ninh chống Directory Traversal Attack
        if (!targetPath.startsWith(baseDirPath)) {
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

        // Chuẩn hoá Streaming: Client gửi Range hoặc không gửi thì đều stream dạng Partial Content (206)
        // Nếu không có Range header, mặc định phát từ byte 0 (chunk đầu tiên tối đa 2MB)
        List<HttpRange> ranges = (headers != null) ? headers.getRange() : Collections.emptyList();
        long start = 0;
        long end = contentLength - 1;

        if (!ranges.isEmpty()) {
            HttpRange range = ranges.get(0);
            start = range.getRangeStart(contentLength);
            end = range.getRangeEnd(contentLength);

            // Kiểm tra tính hợp lệ của dải byte yêu cầu
            if (start >= contentLength) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength)
                        .build();
            }
        }

        // Giới hạn kích thước chunk tối đa 2MB mỗi lần request để tối ưu RAM và stream mượt mà
        long chunkLength = Math.min(2 * 1024 * 1024L, end - start + 1);
        if (start + chunkLength > contentLength) {
            chunkLength = contentLength - start;
        }

        ResourceRegion region = new ResourceRegion(resource, start, chunkLength);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.valueOf("video/mp4"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(region);
    }

    private OffsetDateTime parseStartTime(String filename, BasicFileAttributes attrs) {
        String baseName = filename.replace(".mp4", "").trim();

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
