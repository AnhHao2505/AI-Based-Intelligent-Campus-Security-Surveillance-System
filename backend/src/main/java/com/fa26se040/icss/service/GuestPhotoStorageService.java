package com.fa26se040.icss.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    /** Tạo bucket nếu chưa có. Cố ý KHÔNG gọi setBucketPolicy: bucket giữ mặc định private. */
    public void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("[GUEST-PHOTO] Created private bucket {}", bucket);
        }
    }

    /** Xoá object; S3/MinIO coi xoá object không tồn tại là thành công (idempotent). Lỗi kết nối -> ném lỗi. */
    public void removeObject(String objectKey) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }
}
