package com.fa26se040.icss.guest;

import com.fa26se040.icss.service.GuestPhotoStorageService;
import io.minio.*;
import io.minio.http.Method;
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
