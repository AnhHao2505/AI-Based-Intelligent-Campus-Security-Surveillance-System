package com.fa26se040.icss.service;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

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

    // ===== M2 / B6: gỡ policy public của bucket tạo trước đây =====

    private static ErrorResponseException minioError(String code) {
        return new ErrorResponseException(new ErrorResponse(code, "minio message", null, null, null, null, null), null, null);
    }

    private void givenBucketsExist() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    }

    @Test
    @DisplayName("B6: bucket có policy -> deleteBucketPolicy đúng bucket đó; bucket policy rỗng -> không gỡ")
    void initBuckets_deletesPolicyWhenPresent() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenAnswer(inv -> {
            GetBucketPolicyArgs args = inv.getArgument(0);
            return "face-profiles".equals(args.bucket()) ? "{\"Statement\":[{\"Effect\":\"Allow\"}]}" : "";
        });

        minioStorageService.initBuckets();

        ArgumentCaptor<DeleteBucketPolicyArgs> captor = ArgumentCaptor.forClass(DeleteBucketPolicyArgs.class);
        verify(minioClient, times(1)).deleteBucketPolicy(captor.capture());
        assertEquals("face-profiles", captor.getValue().bucket());
        verify(minioClient, times(3)).getBucketPolicy(any(GetBucketPolicyArgs.class));
    }

    @Test
    @DisplayName("B6: cả 3 bucket có policy -> gỡ cả 3")
    void initBuckets_deletesPolicyOfAllThreeBuckets() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("{\"Statement\":[]}");

        minioStorageService.initBuckets();

        ArgumentCaptor<DeleteBucketPolicyArgs> captor = ArgumentCaptor.forClass(DeleteBucketPolicyArgs.class);
        verify(minioClient, times(3)).deleteBucketPolicy(captor.capture());
        assertEquals(List.of("face-profiles", "security-evidence", "camera-snapshots"),
                captor.getAllValues().stream().map(DeleteBucketPolicyArgs::bucket).toList());
    }

    @Test
    @DisplayName("B6: không có policy kiểu chuỗi rỗng -> không gọi deleteBucketPolicy")
    void initBuckets_emptyPolicy_noDelete() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("");

        minioStorageService.initBuckets();

        verify(minioClient, never()).deleteBucketPolicy(any(DeleteBucketPolicyArgs.class));
    }

    @Test
    @DisplayName("B6: không có policy kiểu NoSuchBucketPolicy -> không gọi deleteBucketPolicy, không ném ra ngoài")
    void initBuckets_noSuchBucketPolicy_noDelete() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenThrow(minioError("NoSuchBucketPolicy"));

        assertDoesNotThrow(() -> minioStorageService.initBuckets());

        verify(minioClient, never()).deleteBucketPolicy(any(DeleteBucketPolicyArgs.class));
        verify(minioClient, times(3)).getBucketPolicy(any(GetBucketPolicyArgs.class));
    }

    @Test
    @DisplayName("B6: lỗi khác khi đọc / gỡ policy -> chỉ WARN, không ném ra ngoài, vẫn xử lý bucket còn lại")
    void initBuckets_otherErrors_doNotPropagate() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketPolicy(any(GetBucketPolicyArgs.class)))
                .thenThrow(minioError("AccessDenied"))
                .thenThrow(new IOException("connection reset"))
                .thenReturn("{\"Statement\":[]}");
        doThrow(minioError("AccessDenied")).when(minioClient).deleteBucketPolicy(any(DeleteBucketPolicyArgs.class));

        assertDoesNotThrow(() -> minioStorageService.initBuckets());

        verify(minioClient, times(3)).getBucketPolicy(any(GetBucketPolicyArgs.class));
        verify(minioClient, times(1)).deleteBucketPolicy(any(DeleteBucketPolicyArgs.class));
    }

    @Test
    @DisplayName("Không kết nối được MinIO lúc tạo bucket -> không ném ra ngoài, bỏ qua bước gỡ policy")
    void initBuckets_minioUnreachable_skipsPolicyStep() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(new IOException("connection refused"));

        assertDoesNotThrow(() -> minioStorageService.initBuckets());

        verify(minioClient, never()).getBucketPolicy(any(GetBucketPolicyArgs.class));
        verify(minioClient, never()).deleteBucketPolicy(any(DeleteBucketPolicyArgs.class));
    }

    // ===== B3 / B4: statObject =====

    @Test
    @DisplayName("statObject: có object -> Optional chứa metadata")
    void statObject_found() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        Optional<StatObjectResponse> result = minioStorageService.statObject("security-evidence", "2026-10-18/CAM-1/a.jpg");

        assertTrue(result.isPresent());
        ArgumentCaptor<StatObjectArgs> captor = ArgumentCaptor.forClass(StatObjectArgs.class);
        verify(minioClient).statObject(captor.capture());
        assertEquals("security-evidence", captor.getValue().bucket());
        assertEquals("2026-10-18/CAM-1/a.jpg", captor.getValue().object());
    }

    @Test
    @DisplayName("statObject: NoSuchKey / NoSuchObject -> Optional.empty()")
    void statObject_notFound_empty() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(minioError("NoSuchKey"))
                .thenThrow(minioError("NoSuchObject"));

        assertTrue(minioStorageService.statObject("face-profiles", "SV-1/a.jpg").isEmpty());
        assertTrue(minioStorageService.statObject("face-profiles", "SV-1/a.jpg").isEmpty());
    }

    @Test
    @DisplayName("statObject: lỗi khác -> ném ra cho controller trả 500")
    void statObject_otherError_throws() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(minioError("AccessDenied"));

        assertThrows(ErrorResponseException.class, () -> minioStorageService.statObject("face-profiles", "SV-1/a.jpg"));
    }
}
