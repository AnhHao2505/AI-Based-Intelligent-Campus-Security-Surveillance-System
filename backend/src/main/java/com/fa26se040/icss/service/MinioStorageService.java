package com.fa26se040.icss.service;

import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketPolicyArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.ServerSideEncryptionS3;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    @Value("${minio.bucket.faces:face-profiles}")
    private String bucketFaces;

    @Value("${minio.bucket.evidence:security-evidence}")
    private String bucketEvidence;

    @Value("${minio.bucket.recordings:camera-recordings}")
    private String bucketRecordings;

    @Value("${minio.bucket.chat:chat-media}")
    private String bucketChat;

    /** Mã lỗi MinIO khi object không tồn tại. */
    private static final Set<String> OBJECT_NOT_FOUND_CODES = Set.of("NoSuchKey", "NoSuchObject");

    /** NFR-05: tiền tố object ghi thử lúc khởi động (xoá ngay sau khi ghi). */
    static final String SSE_PROBE_PREFIX = "_nfr05-probe/";

    /** NFR-05: bucket đã xác nhận có mã hoá mặc định trong vòng đời instance này (không kiểm lại mỗi lần upload). */
    private final Set<String> defaultEncryptionVerified = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void initBuckets() {
        List<String> buckets = new ArrayList<>(List.of(bucketFaces, bucketEvidence));
        if (bucketRecordings != null && !bucketRecordings.isBlank()) {
            buckets.add(bucketRecordings);
        }
        if (bucketChat != null && !bucketChat.isBlank()) {
            buckets.add(bucketChat);
        }
        try {
            for (String b : buckets) {
                ensureBucketExists(b);
            }
        } catch (Exception e) {
            log.warn("Could not verify/create MinIO buckets at startup (Endpoint: {}): {}", minioEndpoint, e.getMessage());
            log.error("[NFR-05] Không kiểm được mã hoá phía server lúc khởi động: MinIO chưa tới được ({}). "
                    + "Backend vẫn chạy; upload sẽ thất bại nếu MinIO không có KMS.", MinioBucketEncryption.describe(e));
            // Không kết nối được MinIO -> bỏ qua bước gỡ policy và kiểm SSE (mỗi lệnh sẽ lại chờ timeout)
            return;
        }
        // M2: bucket tạo trước đây có thể còn policy public-read -> gỡ để mọi truy cập phải qua /api/media
        for (String bucket : buckets) {
            removeBucketPolicyIfPresent(bucket);
        }
        // NFR-05: ghi + xoá thử một object có SSE; lỗi chỉ log ERROR
        verifyEncryptedWriteAtStartup();
    }

    /**
     * Gỡ policy của bucket nếu có. Không có policy (minio 8.5.12 trả "" hoặc ném NoSuchBucketPolicy) -> bỏ qua.
     * Lỗi khác chỉ WARN, không chặn khởi động.
     */
    void removeBucketPolicyIfPresent(String bucket) {
        try {
            String policy = minioClient.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
            if (policy == null || policy.isEmpty()) {
                return;
            }
            minioClient.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket(bucket).build());
            log.info("[MINIO] removed bucket policy: {}", bucket);
        } catch (ErrorResponseException e) {
            if (e.errorResponse() != null && "NoSuchBucketPolicy".equals(e.errorResponse().code())) {
                return;
            }
            log.warn("[MINIO] could not check/remove bucket policy of {}: {}", bucket, e.getMessage());
        } catch (Exception e) {
            log.warn("[MINIO] could not check/remove bucket policy of {}: {}", bucket, e.getMessage());
        }
    }

    public String getFacesBucket() {
        return bucketFaces;
    }

    public String getEvidenceBucket() {
        return bucketEvidence;
    }

    public String getRecordingsBucket() { return bucketRecordings; }

    public String getChatBucket() {
        return (bucketChat != null && !bucketChat.isBlank()) ? bucketChat : "chat-media";
    }

    /**
     * Upload picture sent inside Guard Chat into private 'chat-media' bucket.
     * Returns internal proxy URL '/api/media/{bucket}/{senderId}/{uuid}.{ext}'.
     */
    public String uploadChatImage(byte[] imageBytes, UUID senderId, String extension, String contentType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Hình ảnh không được để trống");
        }
        String bucket = getChatBucket();
        String ext = (extension != null && !extension.isBlank()) ? extension.toLowerCase() : "jpg";
        String objectName = senderId + "/" + UUID.randomUUID() + "." + ext;
        String mimeType = (contentType != null && !contentType.isBlank()) ? contentType : "image/jpeg";

        try {
            ensureBucketExists(bucket);
            putObjectEncrypted(bucket, objectName, imageBytes, mimeType);
            String mediaUrl = "/api/media/" + bucket + "/" + objectName;
            log.info("[MINIO] Uploaded guard chat image: {}", mediaUrl);
            return mediaUrl;
        } catch (Exception e) {
            log.error("[MINIO ERROR] Failed to upload guard chat image for sender {}: {}", senderId, e.getMessage(), e);
            throw new RuntimeException("Không thể tải ảnh tin nhắn lên hệ thống lưu trữ", e);
        }
    }

    /**
     * Tải tạm thời (staging) dữ liệu nhị phân lên MinIO phục vụ xử lý bất đồng bộ/RPC giữa Cloud và Edge.
     */
    public void uploadStagingObject(String bucketName, String objectName, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Staging bytes cannot be empty");
        }
        try {
            ensureBucketExists(bucketName);
            putObjectEncrypted(bucketName, objectName, bytes, contentType != null ? contentType : "application/octet-stream");
        } catch (Exception e) {
            log.error("[MINIO ERROR] Failed to upload staging object {}/{}: {}", bucketName, objectName, e.getMessage());
            throw new RuntimeException("Failed to upload staging object to MinIO: " + e.getMessage(), e);
        }
    }

    /**
     * Xóa an toàn object trên MinIO (dùng để dọn dẹp file staging trong khối finally).
     */
    public void removeObjectSafely(String bucketName, String objectName) {
        if (bucketName == null || bucketName.isBlank() || objectName == null || objectName.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
        } catch (Exception e) {
            log.debug("[MINIO] Could not remove temporary object {}/{}: {}", bucketName, objectName, e.getMessage());
        }
    }

    private String getRecordingsBucketUnusedPlaceholder() {
        return bucketRecordings;
    }

    public Iterable<Result<Item>> listObjects(String bucketName, String prefix, boolean recursive) {
        return minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(prefix != null ? prefix : "")
                        .recursive(recursive)
                        .build()
        );
    }

    /**
     * Tạo bucket nếu chưa tồn tại.
     * Cố ý giữ mặc định Private cho toàn bộ bucket (không gán public read policy).
     * NFR-05: bật mã hoá mặc định SSE-S3 cho bucket (idempotent, lỗi chỉ log ERROR).
     */
    private void ensureBucketExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            log.info("Created private MinIO bucket: {}", bucketName);
        }
        if (!defaultEncryptionVerified.contains(bucketName)
                && MinioBucketEncryption.ensureDefaultEncryption(minioClient, bucketName)) {
            defaultEncryptionVerified.add(bucketName);
        }
    }

    /**
     * Sinh Presigned GET URL cho object với thời hạn (TTL tính theo giây).
     */
    public String presignedGetUrl(String bucketName, String objectName, int ttlSeconds) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(ttlSeconds)
                        .build()
        );
    }

    /**
     * Metadata của object (content-type, kích thước). Object không tồn tại -> Optional.empty(); lỗi khác ném ra.
     */
    public Optional<StatObjectResponse> statObject(String bucketName, String objectName) throws Exception {
        try {
            return Optional.of(minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            ));
        } catch (ErrorResponseException e) {
            if (e.errorResponse() != null && OBJECT_NOT_FOUND_CODES.contains(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Đọc trực tiếp InputStream của object từ MinIO để backend phục vụ media streaming an toàn.
     */
    public InputStream getObject(String bucketName, String objectName) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        );
    }

    /**
     * Đọc một phần (byte range) của object từ MinIO phục vụ HTTP 206 Partial Content video streaming.
     */
    public InputStream getObject(String bucketName, String objectName, long offset, long length) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .offset(offset)
                        .length(length)
                        .build()
        );
    }

    /**
     * Upload face profile image into 'face-profiles' bucket:
     * Path: face-profiles/{code}/{code}_front.jpg
     */
    public String uploadFaceImage(byte[] imageBytes, String userCode) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image bytes cannot be empty");
        }

        String fileName = userCode + "_front.jpg";
        String objectName = userCode + "/" + fileName;

        try {
            ensureBucketExists(bucketFaces);
            putObjectEncrypted(bucketFaces, objectName, imageBytes, "image/jpeg");

            String fileUrl = minioEndpoint + "/" + bucketFaces + "/" + objectName;
            log.info("[MINIO] Uploaded face profile image: {}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("[MINIO ERROR] Failed to upload face profile image for code {}: {}", userCode, e.getMessage(), e);
            throw new RuntimeException("Failed to upload image to MinIO: " + e.getMessage(), e);
        }
    }

    /**
     * Delete all profile face images for a user code (Compensating action on DB failure)
     */
    public boolean deleteFaceImage(String userCode) {
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketFaces)
                            .prefix(userCode + "/")
                            .recursive(true)
                            .build()
            );

            for (Result<Item> result : results) {
                Item item = result.get();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketFaces)
                                .object(item.objectName())
                                .build()
                );
                log.info("[MINIO] Deleted face profile object: {}", item.objectName());
            }
            return true;
        } catch (Exception e) {
            log.error("[MINIO ERROR] Failed to delete face profile images for code {}: {}", userCode, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Upload security evidence snapshot frame into 'security-evidence' bucket:
     * Path: security-evidence/{YYYY-MM-DD}/{cameraCode}/{eventType}_Track{trackId}_{HHmmss}.jpg
     */
    public String uploadSecurityEvidence(byte[] imageBytes, String cameraCode, String eventType, Long trackId) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }

        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        String fileName = String.format("%s_Track%d_%s.jpg", eventType, trackId, timeStr);
        String objectName = String.format("%s/%s/%s", dateStr, cameraCode, fileName);

        try {
            ensureBucketExists(bucketEvidence);
            putObjectEncrypted(bucketEvidence, objectName, imageBytes, "image/jpeg");

            String fileUrl = minioEndpoint + "/" + bucketEvidence + "/" + objectName;
            log.info("[MINIO] Uploaded security evidence image: {}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("[MINIO ERROR] Failed to upload security evidence: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * NFR-05: lưu object với ServerSideEncryptionS3 (SSE-S3, khoá KMS mặc định của MinIO). Không có đường lưu dạng rõ:
     * MinIO từ chối SSE (chưa cấu hình KMS / lỗi KMS) -> log ERROR [NFR-05] + {@link com.fa26se040.icss.exception.StorageEncryptionException}.
     */
    private void putObjectEncrypted(String bucketName, String objectName, byte[] bytes, String contentType) throws Exception {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, bytes.length, -1)
                            .contentType(contentType)
                            .sse(new ServerSideEncryptionS3())
                            .build()
            );
        } catch (ErrorResponseException e) {
            throw MinioBucketEncryption.onEncryptedPutFailure(bucketName, objectName, e);
        }
    }

    /**
     * NFR-05: kiểm lúc khởi động — ghi rồi xoá một object nhỏ có SSE vào bucket evidence.
     * Lỗi chỉ log ERROR [NFR-05], không làm sập backend.
     */
    void verifyEncryptedWriteAtStartup() {
        String objectName = SSE_PROBE_PREFIX + UUID.randomUUID() + ".txt";
        byte[] payload = "NFR-05 startup probe".getBytes(StandardCharsets.UTF_8);
        try {
            putObjectEncrypted(bucketEvidence, objectName, payload, "text/plain");
        } catch (Exception e) {
            log.error("[NFR-05] Kiểm tra lúc khởi động THẤT BẠI: không ghi được object có SSE vào bucket {} ({}). "
                            + "Upload ảnh khuôn mặt / bằng chứng sẽ bị từ chối cho tới khi MinIO có KMS.",
                    bucketEvidence, MinioBucketEncryption.describe(e));
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketEvidence).object(objectName).build());
            log.info("[NFR-05] Kiểm tra lúc khởi động: ghi + xoá object có SSE ở bucket {} thành công", bucketEvidence);
        } catch (Exception e) {
            log.error("[NFR-05] Kiểm tra lúc khởi động: ghi object có SSE được nhưng không xoá được {}/{} ({})",
                    bucketEvidence, objectName, MinioBucketEncryption.describe(e));
        }
    }
}
