package com.fa26se040.icss.service;

import io.minio.*;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioStorageServiceTest {

    private MinioClient minioClient;
    private MinioStorageService minioStorageService;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        minioStorageService = new MinioStorageService(minioClient);
        ReflectionTestUtils.setField(minioStorageService, "minioEndpoint", "http://localhost:9000");
        ReflectionTestUtils.setField(minioStorageService, "bucketFaces", "face-profiles");
        ReflectionTestUtils.setField(minioStorageService, "bucketEvidence", "security-evidence");
        ReflectionTestUtils.setField(minioStorageService, "bucketSnapshots", "camera-snapshots");
    }

    @Test
    @DisplayName("Tất cả bucket phải Private: initBuckets tạo bucket nhưng tuyệt đối KHÔNG gọi setBucketPolicy")
    void initBuckets_createsBuckets_neverSetsPublicPolicy() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        minioStorageService.initBuckets();

        // 3 bucket: face-profiles, security-evidence, camera-snapshots
        verify(minioClient, times(3)).makeBucket(any(MakeBucketArgs.class));
        verify(minioClient, never()).setBucketPolicy(any(SetBucketPolicyArgs.class));
    }

    @Test
    @DisplayName("presignedGetUrl trả về URL có chữ ký bảo mật với TTL xác định")
    void presignedGetUrl_returnsValidUrl() throws Exception {
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/camera-snapshots/CAM-1/roi.jpg?sig=xyz");

        String url = minioStorageService.presignedGetUrl("camera-snapshots", "CAM-1/roi.jpg", 3600);

        assertNotNull(url);
        assertTrue(url.contains("sig=xyz"));

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(captor.capture());
        assertEquals("camera-snapshots", captor.getValue().bucket());
        assertEquals("CAM-1/roi.jpg", captor.getValue().object());
        assertEquals(Method.GET, captor.getValue().method());
        assertEquals(3600, captor.getValue().expiry());
    }

    @Test
    @DisplayName("getObject lấy dữ liệu stream từ MinIO thành công")
    void getObject_returnsStream() throws Exception {
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        InputStream stream = minioStorageService.getObject("camera-snapshots", "CAM-1/roi.jpg");
        assertNotNull(stream);

        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        assertEquals("camera-snapshots", captor.getValue().bucket());
        assertEquals("CAM-1/roi.jpg", captor.getValue().object());
    }
}
