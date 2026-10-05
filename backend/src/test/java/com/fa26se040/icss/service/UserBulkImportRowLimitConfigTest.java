package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.MaxRecordsExceededException;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UI-19: giới hạn số dòng nạp tài khoản theo lô đọc từ USER_BULK_IMPORT_MAX_ROWS lúc chạy (sửa trên màn
 * System Configuration có hiệu lực ngay). Không @Transactional: SystemConfigService chỉ cập nhật cache sau commit.
 * Dòng thử dùng role bị luồng từ chối ngay (normal: ADMIN, staff: để trống) -> không ghi user / MinIO nào.
 */
class UserBulkImportRowLimitConfigTest extends AbstractIntegrationTest {

    @Autowired private UserBulkImportService bulkImportService;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private UserRepository userRepository;

    private User admin;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(User.builder()
                .userCode("BIL-ADM-" + suffix)
                .fullName("Bulk import limit admin " + suffix)
                .email("bil-adm-" + suffix + "@fpt.edu.vn")
                .role(Role.ADMIN)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        // DB test dùng chung: trả giới hạn về mặc định và vô hiệu hoá user fixture
        if (systemConfigService.getInt(ConfigKey.USER_BULK_IMPORT_MAX_ROWS) != 200) {
            systemConfigService.update(ConfigKey.USER_BULK_IMPORT_MAX_ROWS.getKey(), "200", admin.getEmail());
        }
        admin.setIsActive(false);
        userRepository.save(admin);
    }

    /** ZIP chỉ có metadata.csv: header + n dòng với role cho trước. */
    private MockMultipartFile zip(int rows, String role) throws Exception {
        StringBuilder csv = new StringBuilder("user_code,full_name,email,role\n");
        for (int i = 1; i <= rows; i++) {
            csv.append("BIL-").append(suffix).append("-").append(i).append(",")
                    .append("Bulk Row ").append(i).append(",")
                    .append("bil-").append(suffix).append("-").append(i).append("@fpt.edu.vn,")
                    .append(role).append("\n");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("metadata.csv"));
            zos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return new MockMultipartFile("file", "import.zip", "application/zip", bytes.toByteArray());
    }

    @Test
    @DisplayName("Đổi USER_BULK_IMPORT_MAX_ROWS 200 -> 5: file 6 dòng bị từ chối (message nêu 5), 5 dòng qua giới hạn — cả 2 luồng")
    void rowLimitReadFromConfigAtRuntime() throws Exception {
        systemConfigService.update(ConfigKey.USER_BULK_IMPORT_MAX_ROWS.getKey(), "5", admin.getEmail());
        assertEquals(5, systemConfigService.getInt(ConfigKey.USER_BULK_IMPORT_MAX_ROWS), "cache cập nhật ngay sau commit");

        // Luồng người dùng thường
        MaxRecordsExceededException normal6 = assertThrows(MaxRecordsExceededException.class,
                () -> bulkImportService.bulkImportNormalUsers(zip(6, "ADMIN")));
        assertTrue(normal6.getMessage().contains("tối đa 5 "), normal6.getMessage());
        assertTrue(normal6.getMessage().contains("6"), normal6.getMessage());
        assertFalse(normal6.getMessage().contains("200"), normal6.getMessage());

        BulkImportResponse normal5 = bulkImportService.bulkImportNormalUsers(zip(5, "ADMIN"));
        assertEquals(5, normal5.getTotalRows(), "5 dòng vượt qua kiểm tra giới hạn");
        assertEquals(0, normal5.getSuccessCount());

        // Luồng cán bộ / bảo vệ
        MaxRecordsExceededException staff6 = assertThrows(MaxRecordsExceededException.class,
                () -> bulkImportService.bulkImportStaffUsers(zip(6, "")));
        assertTrue(staff6.getMessage().contains("tối đa 5 "), staff6.getMessage());

        BulkImportResponse staff5 = bulkImportService.bulkImportStaffUsers(zip(5, ""));
        assertEquals(5, staff5.getTotalRows());
        assertEquals(0, staff5.getSuccessCount());
    }

    @Test
    @DisplayName("Mặc định (200): 201 dòng bị từ chối, message nêu 200")
    void defaultLimitIs200() throws Exception {
        assertEquals(200, systemConfigService.getInt(ConfigKey.USER_BULK_IMPORT_MAX_ROWS));
        MaxRecordsExceededException ex = assertThrows(MaxRecordsExceededException.class,
                () -> bulkImportService.bulkImportNormalUsers(zip(201, "ADMIN")));
        assertTrue(ex.getMessage().contains("tối đa 200 "), ex.getMessage());
    }
}
