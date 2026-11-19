package com.fa26se040.icss.guest;

import com.fa26se040.icss.exception.StorageEncryptionException;
import com.fa26se040.icss.service.GuestPhotoStorageService;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.ErrorResponse;
import io.minio.messages.SseAlgorithm;
import io.minio.messages.SseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P4 — kho ảnh khách (BR-GV-18): bucket riêng, private (không đặt policy), URL xem có hạn. Unit test, MinioClient mock.
 */
class GuestPhotoStorageServiceTest {

    private MinioClient minio;
    private GuestPhotoStorageService storage;

    @BeforeEach
    void setUp() {
        minio = mock(MinioClient.class);
        storage = new GuestPhotoStorageService(minio);
        ReflectionTestUtils.setField(storage, "bucket", "guest-photos");
    }

    @Test
    @DisplayName("GV-18: bucket chưa có -> tạo bucket guest-photos, KHÔNG gọi setBucketPolicy (giữ private)")
    void ensureBucket_private() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        storage.ensureBucket();
        ArgumentCaptor<MakeBucketArgs> cap = ArgumentCaptor.forClass(MakeBucketArgs.class);
        verify(minio).makeBucket(cap.capture());
        assertEquals("guest-photos", cap.getValue().bucket());
        verify(minio, never()).setBucketPolicy(any(SetBucketPolicyArgs.class));
    }

    @Test
    @DisplayName("GV-18: upload vào bucket riêng (tạo bucket nếu thiếu), không đặt policy")
    void upload_toGuestBucket() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        storage.upload("visits/a/b/c.jpg", new byte[]{1, 2, 3}, "image/jpeg");
        ArgumentCaptor<PutObjectArgs> cap = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(cap.capture());
        assertEquals("guest-photos", cap.getValue().bucket());
        assertEquals("visits/a/b/c.jpg", cap.getValue().object());
        verify(minio, never()).setBucketPolicy(any(SetBucketPolicyArgs.class));
    }

    // ===== NFR-05 (N1) =====

    private static ErrorResponseException minioError(String code) {
        return new ErrorResponseException(new ErrorResponse(code, "minio message", null, null, null, null, null), null, null);
    }

    @Test
    @DisplayName("NFR-05: upload ảnh khách gửi SSE-S3 (header X-Amz-Server-Side-Encryption: AES256)")
    void upload_withServerSideEncryption() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        storage.upload("visits/a/b/c.jpg", new byte[]{1, 2, 3}, "image/jpeg");
        ArgumentCaptor<PutObjectArgs> cap = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(cap.capture());
        ServerSideEncryption sse = cap.getValue().sse();
        assertNotNull(sse, "ảnh khách phải được mã hoá phía server");
        assertInstanceOf(ServerSideEncryptionS3.class, sse);
        assertEquals("AES256", sse.headers().get("X-Amz-Server-Side-Encryption"));
    }

    @Test
    @DisplayName("NFR-05: bucket guest-photos chưa có mã hoá mặc định -> setBucketEncryption SSE-S3 một lần, upload sau không kiểm lại")
    void ensureBucket_enablesDefaultEncryptionOnce() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minio.getBucketEncryption(any(GetBucketEncryptionArgs.class)))
                .thenThrow(minioError("ServerSideEncryptionConfigurationNotFoundError"));

        storage.upload("visits/a/b/c.jpg", new byte[]{1}, "image/jpeg");
        storage.upload("visits/a/b/d.jpg", new byte[]{2}, "image/jpeg");

        ArgumentCaptor<SetBucketEncryptionArgs> cap = ArgumentCaptor.forClass(SetBucketEncryptionArgs.class);
        verify(minio, times(1)).setBucketEncryption(cap.capture());
        assertEquals("guest-photos", cap.getValue().bucket());
        assertEquals(SseAlgorithm.AES256, cap.getValue().config().rule().sseAlgorithm());
        verify(minio, times(1)).getBucketEncryption(any(GetBucketEncryptionArgs.class));
    }

    @Test
    @DisplayName("NFR-05: bucket đã có mã hoá mặc định -> không ghi đè cấu hình (idempotent)")
    void ensureBucket_alreadyEncrypted_noWrite() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minio.getBucketEncryption(any(GetBucketEncryptionArgs.class)))
                .thenReturn(SseConfiguration.newConfigWithSseS3Rule());

        storage.ensureBucket();

        verify(minio, never()).setBucketEncryption(any(SetBucketEncryptionArgs.class));
    }

    @Test
    @DisplayName("NFR-05: bật mã hoá bucket lỗi -> không ném ra (upload vẫn tự gửi SSE), lần sau thử lại")
    void ensureBucket_encryptionError_doesNotThrow_retriesNextTime() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minio.getBucketEncryption(any(GetBucketEncryptionArgs.class))).thenReturn(new SseConfiguration(null));
        doThrow(minioError("AccessDenied")).when(minio).setBucketEncryption(any(SetBucketEncryptionArgs.class));

        assertDoesNotThrow(() -> storage.ensureBucket());
        assertDoesNotThrow(() -> storage.ensureBucket());

        verify(minio, times(2)).setBucketEncryption(any(SetBucketEncryptionArgs.class));
    }

    @Test
    @DisplayName("NFR-05: MinIO từ chối SSE -> StorageEncryptionException, chỉ 1 lệnh ghi (có SSE), không ghi dạng rõ")
    void upload_sseRejected_failsWithoutPlaintextRetry() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minio.putObject(any(PutObjectArgs.class))).thenThrow(minioError("NotImplemented"));

        StorageEncryptionException ex = assertThrows(StorageEncryptionException.class,
                () -> storage.upload("visits/a/b/c.jpg", new byte[]{1, 2, 3}, "image/jpeg"));

        assertEquals("NotImplemented", ex.getErrorCode());
        assertTrue(ex.getMessage().startsWith("[NFR-05]"));
        ArgumentCaptor<PutObjectArgs> cap = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio, times(1)).putObject(cap.capture());
        assertNotNull(cap.getValue().sse());
    }

    @Test
    @DisplayName("GV-18: URL xem là presigned GET, hạn đúng TTL truyền vào")
    void presigned_ttl() throws Exception {
        when(minio.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://minio/x?sig");
        assertEquals("http://minio/x?sig", storage.presignedGetUrl("visits/a/b/c.jpg", 60));
        ArgumentCaptor<GetPresignedObjectUrlArgs> cap = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minio).getPresignedObjectUrl(cap.capture());
        assertEquals(Method.GET, cap.getValue().method());
        assertEquals(60, cap.getValue().expiry());
        assertEquals("guest-photos", cap.getValue().bucket());
    }
}
