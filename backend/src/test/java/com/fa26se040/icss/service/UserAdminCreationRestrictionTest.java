package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.dto.BulkImportRowResult;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.dto.user.StaffAccountCreateResponse;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAdminCreationRestrictionTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FaceDataService faceDataService;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserAccessLevelHelper userAccessLevelHelper;

    @Mock
    private AuditService auditService;

    @Mock
    private UserBulkImportService userBulkImportService;

    @Mock
    private AccessRequestService accessRequestService;

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private UserBulkImportHelper userBulkImportHelper;

    @InjectMocks
    private UserService userService;

    private UserBulkImportService realBulkImportService;

    private MockMultipartFile mockFaceImage;

    @BeforeEach
    void setUp() {
        mockFaceImage = new MockMultipartFile(
                "faceImage", "face.jpg", "image/jpeg", new byte[]{1, 2, 3, 4}
        );
        realBulkImportService = new UserBulkImportService(userBulkImportHelper, systemConfigService);
    }

    @Test
    @DisplayName("Cấm tạo tài khoản mới với vai trò ADMIN qua createStaffAccount")
    void createStaffAccount_WhenRoleIsAdmin_ThrowsIllegalArgumentException() {
        StaffAccountCreateRequest req = new StaffAccountCreateRequest();
        req.setUserCode("AD002");
        req.setFullName("Admin Thứ Hai");
        req.setEmail("admin2@fpt.edu.vn");
        req.setRole(Role.ADMIN);
        req.setFaceImage(mockFaceImage);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> userService.createStaffAccount(req)
        );

        assertTrue(
                ex.getMessage().contains("ADMIN") || ex.getMessage().contains("Quản trị viên"),
                "Thông báo lỗi phải đề cập đến việc không được phép tạo vai trò ADMIN"
        );
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Tạo tài khoản cán bộ với vai trò GUARD vẫn hợp lệ")
    void createStaffAccount_WhenRoleIsGuard_Success() {
        StaffAccountCreateRequest req = new StaffAccountCreateRequest();
        req.setUserCode("GUARD002");
        req.setFullName("Bảo Vệ Hai");
        req.setEmail("guard002@fpt.edu.vn");
        req.setRole(Role.GUARD);
        req.setFaceImage(mockFaceImage);

        when(userRepository.findExistingUserCodes(any())).thenReturn(Set.of());
        when(userRepository.findExistingEmails(any())).thenReturn(Set.of());
        when(passwordEncoder.encode(any())).thenReturn("hashed_pass");
        when(faceDataService.registerFace(any(), any())).thenReturn(FaceDataResponseDto.builder().build());
        when(userAccessLevelHelper.resolveDefaultAccessLevel(Role.GUARD)).thenReturn(2);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        StaffAccountCreateResponse resp = userService.createStaffAccount(req);

        assertNotNull(resp);
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Bulk import luồng cán bộ từ chối dòng có role ADMIN")
    void bulkImportStaffUsers_WhenRoleIsAdmin_RejectsWithFailedStatus() throws Exception {
        when(systemConfigService.getInt(ConfigKey.USER_BULK_IMPORT_MAX_ROWS)).thenReturn(200);

        String csvContent = "user_code,full_name,email,role\n" +
                "AD999,Admin Mau,ad999@fpt.edu.vn,ADMIN\n";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("metadata.csv"));
            zos.write(csvContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        MockMultipartFile zipFile = new MockMultipartFile(
                "file", "staff.zip", "application/zip", baos.toByteArray()
        );

        BulkImportResponse resp = realBulkImportService.bulkImportStaffUsers(zipFile);

        assertNotNull(resp);
        assertEquals(1, resp.getTotalRows());
        assertEquals(0, resp.getSuccessCount());
        assertEquals(1, resp.getFailureCount());

        BulkImportRowResult row = resp.getResults().get(0);
        assertEquals("FAILED", row.getStatus());
        assertTrue(
                row.getErrorMessage().contains("ADMIN") || row.getErrorMessage().contains("Quản trị viên"),
                "Lỗi của dòng phải nêu rõ không chấp nhận vai trò ADMIN: " + row.getErrorMessage()
        );
    }
}
