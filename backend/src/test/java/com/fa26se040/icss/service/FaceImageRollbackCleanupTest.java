package com.fa26se040.icss.service;

import com.fa26se040.icss.TestFaces;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.AiFaceRegistrationResponseDto;
import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.entity.FaceData;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AuditWriteException;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Việc 6 (phương án b): ảnh khuôn mặt tải lên MinIO rồi transaction rollback -> FaceDataService xoá object vừa tải trong
 * afterCompletion(ROLLED_BACK); commit thì giữ. Chạy FaceDataService / UserService / UserBulkImportHelper THẬT với
 * {@link InMemoryTransactionManager} (proxy @Transactional thật, không cần DB): REQUIRED tham gia transaction ngoài,
 * REQUIRES_NEW của từng dòng import, rollback-only, afterCompletion đúng như Spring. AI, MinIO, repository, audit là mock.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FaceImageRollbackCleanupTest {

    private static final String BUCKET = "face-profiles";

    // phụ thuộc của FaceDataService
    @Mock private FaceDataRepository faceDataRepository;
    // tạo trong setUp (sau @InjectMocks) để Mockito không inject nhầm vào UserService
    private UserRepository faceUserRepository;
    @Mock private MinioStorageService minioStorageService;
    @Mock private AiServiceRestTemplate restTemplate;
    @Mock private FaceDuplicateCheckService faceDuplicateCheckService;
    @Mock private SystemConfigService systemConfigService;
    private AuditService faceAuditService;

    // phụ thuộc của UserService (InjectMocks theo kiểu; faceDataService được thay bằng proxy thật ở setUp)
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private UserAccessLevelHelper userAccessLevelHelper;
    @Mock private NotificationService notificationService;
    @Mock private AiFaceCacheRefreshNotifier aiFaceCacheRefreshNotifier;
    @InjectMocks private UserService userServiceTarget;

    private InMemoryTransactionManager txManager;
    private FaceDataService faceDataTarget;
    private FaceDataService faceDataService;
    private UserService userService;

    @BeforeEach
    void setUp() {
        txManager = new InMemoryTransactionManager();
        faceUserRepository = mock(UserRepository.class);
        faceAuditService = mock(AuditService.class);

        when(systemConfigService.getIntWithinBounds(ConfigKey.USER_FACE_IMAGE_MAX_KB)).thenReturn(350);
        when(systemConfigService.getInt(ConfigKey.USER_BULK_IMPORT_MAX_ROWS)).thenReturn(200);
        when(restTemplate.postForEntity(anyString(), any(), eq(AiFaceRegistrationResponseDto.class)))
                .thenAnswer(i -> ResponseEntity.ok(AiFaceRegistrationResponseDto.builder()
                        .success(true).faceCount(1).embeddingFront(TestFaces.random()).build()));
        when(minioStorageService.getFacesBucket()).thenReturn(BUCKET);
        when(minioStorageService.uploadFaceImage(any(), anyString()))
                .thenAnswer(i -> "http://minio:9000/" + BUCKET + "/" + i.getArgument(1) + "/" + i.getArgument(1) + "_front.jpg");
        when(minioStorageService.deleteFaceImage(anyString())).thenReturn(true);
        when(faceDataRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(faceDataRepository.save(any(FaceData.class))).thenAnswer(i -> i.getArgument(0));
        when(faceAuditService.resolveCurrentActor(anyString())).thenReturn(AuditActor.system("TEST"));
        when(auditService.resolveCurrentActor(anyString())).thenReturn(AuditActor.system("TEST"));
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });

        faceDataTarget = new FaceDataService(faceDataRepository, faceUserRepository, minioStorageService, restTemplate,
                faceDuplicateCheckService, systemConfigService);
        ReflectionTestUtils.setField(faceDataTarget, "auditService", faceAuditService);
        ReflectionTestUtils.setField(faceDataTarget, "aiServiceUrl", "http://ai.test");
        faceDataService = txManager.transactional(faceDataTarget);

        ReflectionTestUtils.setField(userServiceTarget, "faceDataService", faceDataService);
        ReflectionTestUtils.setField(userServiceTarget, "systemConfigService", systemConfigService);
        userService = txManager.transactional(userServiceTarget);
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private static StaffAccountCreateRequest request(String code) {
        return StaffAccountCreateRequest.builder()
                .fullName("Rollback " + code)
                .userCode(code)
                .email(code.toLowerCase() + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .faceImage(image())
                .build();
    }

    // ------------------------------------------------------------------ FaceDataService.registerFace

    @Test
    @DisplayName("OI-01: lỗi khi lưu face_data sau khi ảnh đã lên MinIO -> rollback, object vừa tải bị xoá")
    void faceDataSaveFails_uploadedObjectDeleted() {
        when(faceDataRepository.save(any(FaceData.class))).thenThrow(new DataIntegrityViolationException("face_data lỗi"));

        assertThatThrownBy(() -> faceDataService.registerFace("OI01", image()))
                .hasMessageContaining("face_data lỗi");

        verify(minioStorageService).uploadFaceImage(any(), eq("OI01"));
        verify(minioStorageService, times(1)).deleteFaceImage("OI01");
        assertThat(txManager.rollbacks.get()).isEqualTo(1);
        assertThat(txManager.commits.get()).isZero();
    }

    @Test
    @DisplayName("OI-02: lỗi ghi audit REGISTER_FACE -> rollback, object vừa tải bị xoá, lỗi gốc AuditWriteException giữ nguyên")
    void faceAuditFails_uploadedObjectDeleted() {
        when(faceAuditService.record(eq(AuditTargetType.FACE_REGISTRATION), eq(AuditAction.REGISTER_FACE), anyString(),
                any(), any(), any(), any(), any(), nullable(AuditActor.class)))
                .thenThrow(new AuditWriteException("audit lỗi"));

        assertThatThrownBy(() -> faceDataService.registerFace("OI02", image()))
                .isInstanceOf(AuditWriteException.class).hasMessage("audit lỗi");

        verify(minioStorageService, times(1)).deleteFaceImage("OI02");
    }

    @Test
    @DisplayName("OI-03: registerFace thành công, commit -> object còn (không xoá)")
    void registerFaceCommits_objectKept() {
        faceDataService.registerFace("OI03", image());

        verify(minioStorageService).uploadFaceImage(any(), eq("OI03"));
        verify(minioStorageService, never()).deleteFaceImage(anyString());
        assertThat(txManager.commits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("OI-04: xoá object lỗi (MinIO không phản hồi, ném lỗi) -> lỗi gốc vẫn trả về đúng, không bị đè")
    void deleteThrows_originalErrorPropagates() {
        when(faceDataRepository.save(any(FaceData.class))).thenThrow(new DataIntegrityViolationException("face_data lỗi"));
        when(minioStorageService.deleteFaceImage("OI04")).thenThrow(new RuntimeException("MinIO không phản hồi"));

        assertThatThrownBy(() -> faceDataService.registerFace("OI04", image()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Lỗi xử lý khuôn mặt: face_data lỗi")
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);

        verify(minioStorageService, times(1)).deleteFaceImage("OI04");
    }

    @Test
    @DisplayName("OI-05: xoá object thất bại (deleteFaceImage trả false) -> lỗi gốc AuditWriteException vẫn trả về")
    void deleteReturnsFalse_originalErrorPropagates() {
        when(faceAuditService.record(eq(AuditTargetType.FACE_REGISTRATION), eq(AuditAction.REGISTER_FACE), anyString(),
                any(), any(), any(), any(), any(), nullable(AuditActor.class)))
                .thenThrow(new AuditWriteException("audit lỗi"));
        when(minioStorageService.deleteFaceImage("OI05")).thenReturn(false);

        assertThatThrownBy(() -> faceDataService.registerFace("OI05", image()))
                .isInstanceOf(AuditWriteException.class).hasMessage("audit lỗi");
    }

    @Test
    @DisplayName("OI-06: face_data của mã đã có (ảnh cũ bị ghi đè) rồi rollback -> KHÔNG xoá object đang được face_data tham chiếu")
    void existingFaceData_rollback_objectNotDeleted() {
        when(faceDataRepository.findByCode("OI06")).thenReturn(Optional.of(FaceData.builder().code("OI06")
                .imageFrontUrl("http://minio:9000/" + BUCKET + "/OI06/OI06_front.jpg").build()));
        when(faceAuditService.record(eq(AuditTargetType.FACE_REGISTRATION), eq(AuditAction.REGISTER_FACE), anyString(),
                any(), any(), any(), any(), any(), nullable(AuditActor.class)))
                .thenThrow(new AuditWriteException("audit lỗi"));

        assertThatThrownBy(() -> faceDataService.registerFace("OI06", image())).isInstanceOf(AuditWriteException.class);

        verify(minioStorageService, never()).deleteFaceImage(anyString());
    }

    @Test
    @DisplayName("OI-07: gọi registerFace không có transaction -> giữ hành vi cũ (không đăng ký xoá)")
    void noTransaction_keepsPreviousBehaviour() {
        when(faceDataRepository.save(any(FaceData.class))).thenThrow(new DataIntegrityViolationException("face_data lỗi"));

        assertThatThrownBy(() -> faceDataTarget.registerFace("OI07", image())).hasMessageContaining("face_data lỗi");

        verify(minioStorageService, never()).deleteFaceImage(anyString());
    }

    @Test
    @DisplayName("OI-08: lỗi AI trước khi tải ảnh -> không có object, không xoá gì")
    void failureBeforeUpload_nothingToDelete() {
        when(restTemplate.postForEntity(anyString(), any(), eq(AiFaceRegistrationResponseDto.class)))
                .thenReturn(ResponseEntity.ok(AiFaceRegistrationResponseDto.builder()
                        .success(true).faceCount(2).embeddingFront(TestFaces.random()).build()));

        assertThatThrownBy(() -> faceDataService.registerFace("OI08", image()))
                .hasMessage("Ảnh chứa nhiều hơn 1 khuôn mặt");

        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
        verify(minioStorageService, never()).deleteFaceImage(anyString());
    }

    // ------------------------------------------------------------------ UserService.createStaffAccount

    @Test
    @DisplayName("OI-09: lỗi ở UserService sau registerFace (ghi audit USER_ACCOUNT) -> rollback, object bị xoá đúng 1 lần")
    void userServiceFailsAfterRegisterFace_objectDeletedOnce() {
        when(auditService.record(eq(AuditTargetType.USER_ACCOUNT), eq(AuditAction.CREATE), anyString(), any(), any(), any(),
                any(), any(), nullable(AuditActor.class)))
                .thenThrow(new AuditWriteException("audit tài khoản lỗi"));

        assertThatThrownBy(() -> userService.createStaffAccount(request("OI09")))
                .isInstanceOf(AuditWriteException.class).hasMessage("audit tài khoản lỗi");

        verify(minioStorageService, times(1)).deleteFaceImage("OI09");
        assertThat(txManager.rollbacks.get()).isEqualTo(1);
        verify(aiFaceCacheRefreshNotifier, never()).notifyAfterCommit();
    }

    @Test
    @DisplayName("OI-10: tạo tài khoản thành công -> commit, object còn")
    void createStaffAccountCommits_objectKept() {
        userService.createStaffAccount(request("OI10"));

        verify(minioStorageService).uploadFaceImage(any(), eq("OI10"));
        verify(minioStorageService, never()).deleteFaceImage(anyString());
        assertThat(txManager.commits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("OI-11: transaction bao ngoài rollback sau createStaffAccount -> object bị xoá")
    void outerRollback_objectDeleted() {
        TransactionTemplate outer = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> outer.executeWithoutResult(tx -> {
            userService.createStaffAccount(request("OI11"));
            throw new IllegalStateException("ép rollback");
        })).isInstanceOf(IllegalStateException.class);

        verify(minioStorageService, times(1)).deleteFaceImage("OI11");
    }

    // ------------------------------------------------------------------ import ZIP

    @Test
    @DisplayName("OI-12: import 2 dòng, dòng 2 lỗi sau registerFace -> chỉ xoá ảnh dòng 2, ảnh dòng 1 còn")
    void bulkImport_onlyFailedRowObjectDeleted() throws Exception {
        UserBulkImportHelper helperTarget = new UserBulkImportHelper(userRepository, passwordEncoder, faceDataService,
                minioStorageService, notificationService, userAccessLevelHelper, systemConfigService);
        AuditService importAudit = mock(AuditService.class);
        when(importAudit.resolveCurrentActor(anyString())).thenReturn(AuditActor.system("TEST"));
        when(importAudit.record(eq(AuditTargetType.USER_ACCOUNT), eq(AuditAction.CREATE), anyString(), any(),
                argThat((User u) -> u != null && "OI12B".equals(u.getUserCode())), any(), any(), any(), nullable(AuditActor.class)))
                .thenThrow(new AuditWriteException("audit dòng 2 lỗi"));
        ReflectionTestUtils.setField(helperTarget, "auditService", importAudit);
        UserBulkImportService importService = new UserBulkImportService(txManager.transactional(helperTarget), systemConfigService);
        ReflectionTestUtils.setField(importService, "aiFaceCacheRefreshNotifier", aiFaceCacheRefreshNotifier);

        BulkImportResponse response = importService.bulkImportNormalUsers(zipOf(List.of("OI12A", "OI12B")));

        assertThat(response.getSuccessCount()).isEqualTo(1);
        assertThat(response.getFailureCount()).isEqualTo(1);
        // B1: lỗi ghi audit giữ câu của AuditWriteException và mang mã ERR_AUDIT_001 ở kết quả dòng
        assertThat(response.getResults().get(1).getErrorMessage()).isEqualTo("audit dòng 2 lỗi");
        assertThat(response.getResults().get(1).getErrorCode()).isEqualTo("ERR_AUDIT_001");
        verify(minioStorageService).uploadFaceImage(any(), eq("OI12A"));
        verify(minioStorageService).uploadFaceImage(any(), eq("OI12B"));
        verify(minioStorageService, never()).deleteFaceImage("OI12A");
        verify(minioStorageService, times(1)).deleteFaceImage("OI12B");
        assertThat(txManager.commits.get()).as("dòng 1 commit").isEqualTo(1);
        assertThat(txManager.rollbacks.get()).as("dòng 2 rollback").isEqualTo(1);
        // việc 4: báo làm mới kho khuôn mặt một lần cho cả lô
        verify(aiFaceCacheRefreshNotifier, times(1)).notifyAfterCommit();
    }

    /** ZIP nạp người dùng thường: metadata.csv (user_code, full_name, email) + images/<mã>.jpg. */
    private static MockMultipartFile zipOf(List<String> codes) throws Exception {
        StringBuilder csv = new StringBuilder("user_code,full_name,email\n");
        for (String code : codes) {
            csv.append(code).append(",Người dùng ").append(code).append(",").append(code.toLowerCase()).append("@fpt.edu.vn\n");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("metadata.csv"));
            zos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (String code : codes) {
                zos.putNextEntry(new ZipEntry("images/" + code + ".jpg"));
                zos.write(new byte[]{1, 2, 3});
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", "import.zip", "application/zip", bytes.toByteArray());
    }
}
