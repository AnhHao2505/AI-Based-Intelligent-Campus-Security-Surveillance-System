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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    @Value("${minio.bucket.snapshots:camera-snapshots}")
    private String bucketSnapshots;

    /** Mã lỗi MinIO khi object không tồn tại. */
    private static final Set<String> OBJECT_NOT_FOUND_CODES = Set.of("NoSuchKey", "NoSuchObject");

    @PostConstruct
    public void initBuckets() {
        try {
            ensureBucketExists(bucketFaces);
            ensureBucketExists(bucketEvidence);
            ensureBucketExists(bucketSnapshots);
        } catch (Exception e) {
            log.warn("Could not verify/create MinIO buckets at startup (Endpoint: {}): {}", minioEndpoint, e.getMessage());
            // Không kết nối được MinIO -> bỏ qua bước gỡ policy (mỗi lệnh sẽ lại chờ timeout)
            return;
        }
        // M2: bucket tạo trước đây có thể còn policy public-read -> gỡ để mọi truy cập phải qua /api/media
        for (String bucket : List.of(bucketFaces, bucketEvidence, bucketSnapshots)) {
            removeBucketPolicyIfPresent(bucket);
        }
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

    public String getSnapshotsBucket() {
        return bucketSnapshots;
    }

    /**
     * Tạo bucket nếu chưa tồn tại.
     * Cố ý giữ mặc định Private cho toàn bộ bucket (không gán public read policy).
     */
    private void ensureBucketExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            log.info("Created private MinIO bucket: {}", bucketName);
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
            ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketFaces)
                            .object(objectName)
                            .stream(inputStream, imageBytes.length, -1)
                            .contentType("image/jpeg")
                            .build()
            );

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
            ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketEvidence)
                            .object(objectName)
                            .stream(inputStream, imageBytes.length, -1)
                            .contentType("image/jpeg")
                            .build()
            );

            String fileUrl = minioEndpoint + "/" + bucketEvidence + "/" + objectName;
            log.info("[MINIO] Uploaded security evidence image: {}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("[MINIO ERROR] Failed to upload security evidence: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Upload camera ROI reference snapshot into 'camera-snapshots' bucket:
     * Path: camera-snapshots/{cameraCode}/roi_reference_{timestamp}.jpg
     */
    public String uploadRoiReferenceSnapshot(byte[] imageBytes, String cameraCode) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }

        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(OffsetDateTime.now());
        String fileName = String.format("roi_reference_%s.jpg", timestamp);
        String objectName = String.format("%s/%s", cameraCode, fileName);

        try {
            ensureBucketExists(bucketSnapshots);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketSnapshots)
                            .object(objectName)
                            .stream(inputStream, imageBytes.length, -1)
                            .contentType("image/jpeg")
                            .build()
            );

            String fileUrl = minioEndpoint + "/" + bucketSnapshots + "/" + objectName;
            log.info("[MINIO] Uploaded ROI reference snapshot: {}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("[MINIO ERROR] Failed to upload ROI reference snapshot for camera {}: {}", cameraCode, e.getMessage(), e);
            throw new RuntimeException("Failed to upload snapshot to MinIO: " + e.getMessage(), e);
        }
    }
}
