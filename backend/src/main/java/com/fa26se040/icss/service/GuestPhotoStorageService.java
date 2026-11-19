package com.fa26se040.icss.service;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.ServerSideEncryptionS3;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * Lưu ảnh khách ở bucket MinIO RIÊNG, private (BR-GV-18). Class mới, dùng MinioClient sẵn có;
 * KHÔNG đi qua MinioStorageService (các bucket ở đó được đặt policy đọc công khai).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestPhotoStorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket.guests:guest-photos}")
    private String bucket;

    public String getBucket() {
        return bucket;
    }

    /** NFR-05: đã xác nhận bucket có mã hoá mặc định trong vòng đời instance này (không kiểm lại mỗi lần upload). */
    private volatile boolean defaultEncryptionVerified;

    /**
     * Tạo bucket nếu chưa có. Cố ý KHÔNG gọi setBucketPolicy: bucket giữ mặc định private.
     * NFR-05: bật mã hoá mặc định SSE-S3 cho bucket (idempotent, lỗi chỉ log ERROR).
     */
    public void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("[GUEST-PHOTO] Created private bucket {}", bucket);
        }
        if (!defaultEncryptionVerified) {
            defaultEncryptionVerified = MinioBucketEncryption.ensureDefaultEncryption(minioClient, bucket);
        }
    }

    /**
     * Tải ảnh lên bucket riêng, mã hoá phía server SSE-S3 bằng khoá KMS mặc định của MinIO (NFR-05).
     * MinIO từ chối SSE -> log ERROR [NFR-05] + StorageEncryptionException, không lưu dạng rõ.
     */
    public void upload(String objectKey, byte[] bytes, String contentType) throws Exception {
        ensureBucket();
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, bytes.length, -1)
                    .contentType(contentType)
                    .sse(new ServerSideEncryptionS3())
                    .build());
        } catch (ErrorResponseException e) {
            throw MinioBucketEncryption.onEncryptedPutFailure(bucket, objectKey, e);
        }
    }

    /** URL xem ảnh có hạn (presigned GET), sống ttlSeconds giây. */
    public String presignedGetUrl(String objectKey, int ttlSeconds) throws Exception {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry(ttlSeconds)
                .build());
    }

    /** Xoá object; S3/MinIO coi xoá object không tồn tại là thành công (idempotent). Lỗi kết nối -> ném lỗi. */
    public void removeObject(String objectKey) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }
}
