package com.fa26se040.icss.service;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

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

    @PostConstruct
    public void initBuckets() {
        try {
            ensureBucketExists(bucketFaces);
            ensureBucketExists(bucketEvidence);
        } catch (Exception e) {
            log.warn("Could not verify/create MinIO buckets at startup (Endpoint: {}): {}", minioEndpoint, e.getMessage());
        }
    }

    private void ensureBucketExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            log.info("Created MinIO bucket: {}", bucketName);
        }
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
}
