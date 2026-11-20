package com.fa26se040.icss.service;

import com.fa26se040.icss.exception.StorageEncryptionException;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.ErrorResponse;
import io.minio.messages.SseAlgorithm;
import io.minio.messages.SseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
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
    }

    @Test
    @DisplayName("Tất cả bucket phải Private: initBuckets tạo bucket nhưng tuyệt đối KHÔNG gọi setBucketPolicy")
    void initBuckets_createsBuckets_neverSetsPublicPolicy() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        minioStorageService.initBuckets();

        // 3 bucket: face-profiles, security-evidence, camera-snapshots
        verify(minioClient, times(2)).makeBucket(any(MakeBucketArgs.class));
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

    @Test
    @DisplayName("getObject với offset và length lấy đúng byte range phục vụ HTTP 206 video streaming")
    void getObject_withRange_returnsPartialStream() throws Exception {
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        InputStream stream = minioStorageService.getObject("camera-recordings", "CAM-001/video.mp4", 1024L, 4096L);
        assertNotNull(stream);

        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        assertEquals("camera-recordings", captor.getValue().bucket());
        assertEquals("CAM-001/video.mp4", captor.getValue().object());
        assertEquals(1024L, captor.getValue().offset());
        assertEquals(4096L, captor.getValue().length());
    }

    @Test
    @DisplayName("uploadSecurityEvidence bắt buộc đính kèm mã hóa ServerSideEncryptionS3 (SSE-S3)")
    void uploadSecurityEvidence_usesSseS3() throws Exception {
        givenBucketsExist();
        byte[] dummyImage = new byte[]{1, 2, 3, 4};

        String url = minioStorageService.uploadSecurityEvidence(dummyImage, "CAM-001", "UNAUTHORIZED_ACCESS", 1L);
        assertNotNull(url);

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        assertNotNull(captor.getValue().sse(), "Phải cấu hình SSE-S3 khi tải bằng chứng lên MinIO");
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
        verify(minioClient, times(2)).getBucketPolicy(any(GetBucketPolicyArgs.class));
    }

    @Test
    @DisplayName("B6: cả 3 bucket có policy -> gỡ cả 3")
    void initBuckets_deletesPolicyOfAllThreeBuckets() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("{\"Statement\":[]}");

        minioStorageService.initBuckets();

        ArgumentCaptor<DeleteBucketPolicyArgs> captor = ArgumentCaptor.forClass(DeleteBucketPolicyArgs.class);
        verify(minioClient, times(2)).deleteBucketPolicy(captor.capture());
        assertEquals(List.of("face-profiles", "security-evidence"),
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
        verify(minioClient, times(2)).getBucketPolicy(any(GetBucketPolicyArgs.class));
    }

    @Test
    @DisplayName("B6: lỗi khác khi đọc / gỡ policy -> chỉ WARN, không ném ra ngoài, vẫn xử lý bucket còn lại")
    void initBuckets_otherErrors_doNotPropagate() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketPolicy(any(GetBucketPolicyArgs.class)))
                .thenThrow(minioError("AccessDenied"))
                .thenReturn("{\"Statement\":[]}");
        doThrow(minioError("AccessDenied")).when(minioClient).deleteBucketPolicy(any(DeleteBucketPolicyArgs.class));

        assertDoesNotThrow(() -> minioStorageService.initBuckets());

        verify(minioClient, times(2)).getBucketPolicy(any(GetBucketPolicyArgs.class));
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

    // ===== NFR-05 (N1): mã hoá mặc định của bucket =====

    @Test
    @DisplayName("NFR-05: initBuckets bật mã hoá mặc định SSE-S3 cho mọi bucket ứng dụng (faces, evidence, snapshots, recordings)")
    void initBuckets_enablesDefaultEncryptionOnEveryBucket() throws Exception {
        ReflectionTestUtils.setField(minioStorageService, "bucketRecordings", "camera-recordings");
        givenBucketsExist();
        when(minioClient.getBucketEncryption(any(GetBucketEncryptionArgs.class)))
                .thenThrow(minioError("ServerSideEncryptionConfigurationNotFoundError"));

        minioStorageService.initBuckets();

        ArgumentCaptor<SetBucketEncryptionArgs> captor = ArgumentCaptor.forClass(SetBucketEncryptionArgs.class);
        verify(minioClient, times(3)).setBucketEncryption(captor.capture());
        assertEquals(List.of("face-profiles", "security-evidence", "camera-recordings"),
                captor.getAllValues().stream().map(SetBucketEncryptionArgs::bucket).toList());
        captor.getAllValues().forEach(args ->
                assertEquals(SseAlgorithm.AES256, args.config().rule().sseAlgorithm(), args.bucket()));
    }

    @Test
    @DisplayName("NFR-05: bucket đã có mã hoá mặc định -> không ghi đè (idempotent); upload sau đó không kiểm lại")
    void initBuckets_alreadyEncrypted_noWrite_andCached() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketEncryption(any(GetBucketEncryptionArgs.class)))
                .thenReturn(SseConfiguration.newConfigWithSseS3Rule());

        minioStorageService.initBuckets();
        minioStorageService.uploadFaceImage(new byte[]{1}, "SE1");
        minioStorageService.uploadSecurityEvidence(new byte[]{1}, "CAM-1", "UNAUTHORIZED_ACCESS", 1L);

        verify(minioClient, never()).setBucketEncryption(any(SetBucketEncryptionArgs.class));
        verify(minioClient, times(2)).getBucketEncryption(any(GetBucketEncryptionArgs.class));
    }

    @Test
    @DisplayName("NFR-05: bật mã hoá bucket lỗi -> không chặn khởi động, vẫn gỡ policy các bucket")
    void initBuckets_encryptionError_doesNotBlockStartup() throws Exception {
        givenBucketsExist();
        when(minioClient.getBucketEncryption(any(GetBucketEncryptionArgs.class))).thenThrow(minioError("AccessDenied"));

        assertDoesNotThrow(() -> minioStorageService.initBuckets());

        verify(minioClient, never()).setBucketEncryption(any(SetBucketEncryptionArgs.class));
        verify(minioClient, times(2)).getBucketPolicy(any(GetBucketPolicyArgs.class));
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

    @Test
    @DisplayName("uploadFaceImage: SSE-S3 thành công khi MinIO có hỗ trợ KMS")
    void uploadFaceImage_success_withSse() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);

        byte[] bytes = new byte[]{1, 2, 3};
        String url = minioStorageService.uploadFaceImage(bytes, "SE123456");

        assertEquals("http://localhost:9000/face-profiles/SE123456/SE123456_front.jpg", url);
        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
    }

    // ===== NFR-05 (N2): không có đường lưu dạng rõ =====

    @Test
    @DisplayName("NFR-05: SSE bị từ chối (NotImplemented = MinIO chưa có KMS) -> upload ảnh khuôn mặt thất bại, KHÔNG thử lại không mã hoá")
    void uploadFaceImage_sseNotImplemented_failsWithoutPlaintextRetry(CapturedOutput output) throws Exception {
        givenBucketsExist();
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(minioError("NotImplemented"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> minioStorageService.uploadFaceImage(new byte[]{1, 2, 3}, "SE123456"));

        assertInstanceOf(StorageEncryptionException.class, ex.getCause());
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(1)).putObject(captor.capture());
        assertNotNull(captor.getValue().sse(), "lệnh ghi duy nhất phải có SSE");
        String nfr05Line = output.getOut().lines()
                .filter(l -> l.contains("ERROR") && l.contains("[NFR-05] MinIO từ chối mã hoá phía server SSE-S3"))
                .findFirst().orElse(null);
        assertNotNull(nfr05Line, "phải log ERROR có tiền tố [NFR-05]");
        assertTrue(nfr05Line.contains("code=NotImplemented"));
        assertFalse(nfr05Line.contains("minio message"), "dòng [NFR-05] chỉ có mã lỗi, không chép chi tiết trả về từ MinIO");
    }

    @Test
    @DisplayName("NFR-05: lỗi KMS (KMS.*) khi ghi bằng chứng -> trả null như mọi lỗi upload, không ghi dạng rõ")
    void uploadSecurityEvidence_kmsError_noPlaintextRetry() throws Exception {
        givenBucketsExist();
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(minioError("KMS.NotFoundException"));

        assertNull(minioStorageService.uploadSecurityEvidence(new byte[]{1}, "CAM-1", "UNAUTHORIZED_ACCESS", 1L));

        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("NFR-05: lỗi không liên quan mã hoá (AccessDenied) -> ném lại nguyên lỗi, không đổi thành lỗi mã hoá")
    void uploadFaceImage_otherError_rethrownAsIs() throws Exception {
        givenBucketsExist();
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(minioError("AccessDenied"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> minioStorageService.uploadFaceImage(new byte[]{1}, "SE1"));

        assertInstanceOf(ErrorResponseException.class, ex.getCause());
        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("NFR-05: khởi động -> ghi 1 object nhỏ có SSE vào security-evidence rồi xoá đúng object đó")
    void initBuckets_startupProbe_writesEncryptedObjectThenRemovesIt() throws Exception {
        givenBucketsExist();

        minioStorageService.initBuckets();

        ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(1)).putObject(put.capture());
        assertEquals("security-evidence", put.getValue().bucket());
        assertTrue(put.getValue().object().startsWith(MinioStorageService.SSE_PROBE_PREFIX));
        assertNotNull(put.getValue().sse());
        ArgumentCaptor<RemoveObjectArgs> remove = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient, times(1)).removeObject(remove.capture());
        assertEquals("security-evidence", remove.getValue().bucket());
        assertEquals(put.getValue().object(), remove.getValue().object());
    }

    @Test
    @DisplayName("NFR-05: khởi động, MinIO không có KMS -> log ERROR [NFR-05], không ném ra, không ghi lại dạng rõ")
    void initBuckets_startupProbe_sseRejected_logsErrorOnly(CapturedOutput output) throws Exception {
        givenBucketsExist();
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(minioError("NotImplemented"));

        assertDoesNotThrow(() -> minioStorageService.initBuckets());

        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        assertTrue(output.getOut().contains("[NFR-05] Kiểm tra lúc khởi động THẤT BẠI"));
    }

    @Test
    @DisplayName("NFR-05: khởi động, MinIO chưa tới được -> log ERROR [NFR-05], backend không sập, không ghi thử")
    void initBuckets_minioUnreachable_logsNfr05Error_noProbe(CapturedOutput output) throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(new IOException("connection refused"));

        assertDoesNotThrow(() -> minioStorageService.initBuckets());

        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        assertTrue(output.getOut().contains("[NFR-05] Không kiểm được mã hoá phía server lúc khởi động"));
    }
}
