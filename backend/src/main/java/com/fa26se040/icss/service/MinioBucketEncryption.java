package com.fa26se040.icss.service;

import com.fa26se040.icss.exception.StorageEncryptionException;
import io.minio.GetBucketEncryptionArgs;
import io.minio.MinioClient;
import io.minio.SetBucketEncryptionArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.SseConfiguration;
import lombok.extern.slf4j.Slf4j;

/**
 * NFR-05 (DEF-01): mã hoá mặc định SSE-S3 cho bucket ứng dụng (MinioStorageService, GuestPhotoStorageService), và
 * cách xử lý chung khi MinIO từ chối SSE lúc ghi object.
 *
 * <p>Lớp bảo vệ thứ hai bên cạnh header SSE của từng lệnh putObject: object nào ghi vào bucket mà không gửi header vẫn
 * được MinIO mã hoá bằng khoá KMS mặc định của server ({@code MINIO_KMS_SECRET_KEY}). Backend không giữ tên khoá.</p>
 */
@Slf4j
final class MinioBucketEncryption {

    /** Mã lỗi MinIO khi bucket chưa có cấu hình mã hoá. */
    static final String NO_ENCRYPTION_CONFIG = "ServerSideEncryptionConfigurationNotFoundError";

    private MinioBucketEncryption() {
    }

    /**
     * Idempotent: bucket đã có mã hoá mặc định (SSE-S3 hoặc SSE-KMS) -> không ghi gì; chưa có -> đặt SSE-S3.
     * Lỗi chỉ log ERROR [NFR-05] và trả false (không chặn khởi động / upload: lệnh put vẫn tự gửi header SSE).
     *
     * @return true khi bucket chắc chắn đã có mã hoá mặc định
     */
    static boolean ensureDefaultEncryption(MinioClient client, String bucket) {
        try {
            if (hasDefaultEncryption(client, bucket)) {
                return true;
            }
            client.setBucketEncryption(SetBucketEncryptionArgs.builder()
                    .bucket(bucket)
                    .config(SseConfiguration.newConfigWithSseS3Rule())
                    .build());
            log.info("[NFR-05] Đã bật mã hoá mặc định SSE-S3 cho bucket {}", bucket);
            return true;
        } catch (Exception e) {
            log.error("[NFR-05] Không bật được mã hoá mặc định SSE-S3 cho bucket {}: {}", bucket, describe(e));
            return false;
        }
    }

    static boolean hasDefaultEncryption(MinioClient client, String bucket) throws Exception {
        try {
            SseConfiguration current = client.getBucketEncryption(GetBucketEncryptionArgs.builder().bucket(bucket).build());
            return current != null && current.rule() != null && current.rule().sseAlgorithm() != null;
        } catch (ErrorResponseException e) {
            if (NO_ENCRYPTION_CONFIG.equals(errorCode(e))) {
                return false;
            }
            throw e;
        }
    }

    /**
     * MinIO từ chối SSE: {@code NotImplemented} (server chưa cấu hình KMS) hoặc mã lỗi KMS ({@code KMS.*}).
     * Các lỗi khác (AccessDenied, NoSuchBucket…) không phải lỗi mã hoá.
     */
    static boolean isSseRejected(ErrorResponseException e) {
        String code = errorCode(e);
        return "NotImplemented".equals(code) || (code != null && code.startsWith("KMS"));
    }

    /**
     * Ghi object có SSE bị lỗi: lỗi mã hoá -> log ERROR [NFR-05] + {@link StorageEncryptionException} (không có đường
     * lưu dạng rõ); lỗi khác -> trả lại nguyên lỗi để caller xử lý như trước.
     */
    static Exception onEncryptedPutFailure(String bucket, String object, ErrorResponseException e) {
        if (!isSseRejected(e)) {
            return e;
        }
        log.error("[NFR-05] MinIO từ chối mã hoá phía server SSE-S3 cho {}/{} ({}): KHÔNG lưu dạng rõ, upload thất bại. "
                + "Kiểm tra KMS của MinIO (MINIO_KMS_SECRET_KEY).", bucket, object, describe(e));
        return new StorageEncryptionException(bucket, object, errorCode(e), e);
    }

    static String errorCode(ErrorResponseException e) {
        return e.errorResponse() != null ? e.errorResponse().code() : null;
    }

    /**
     * Mô tả lỗi để log, không chứa secret: lỗi từ MinIO -> loại lỗi + mã lỗi (bỏ message / resource / request id);
     * lỗi khác (mạng, I/O) -> loại lỗi + message (chỉ có host:port, không có credential hay URL ký sẵn).
     */
    static String describe(Exception e) {
        if (e instanceof ErrorResponseException ere) {
            return "ErrorResponseException code=" + errorCode(ere);
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
