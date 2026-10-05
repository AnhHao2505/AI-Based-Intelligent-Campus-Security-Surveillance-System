package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.entity.SystemConfiguration;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.MaxRecordsExceededException;
import com.fa26se040.icss.repository.SystemConfigurationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UI-19: thiếu key USER_BULK_IMPORT_MAX_ROWS trong system_configurations -> dùng mặc định 200 (ConfigKey),
 * không làm hỏng luồng nạp. Không cần DB: SystemConfigService dựng trên repository giả trả danh sách không có key.
 */
class UserBulkImportRowLimitDefaultTest {

    private SystemConfigurationRepository configRepository;
    private UserBulkImportHelper helper;
    private UserBulkImportService service;

    @BeforeEach
    void setUp() {
        configRepository = mock(SystemConfigurationRepository.class);
        // Có key khác nhưng KHÔNG có USER_BULK_IMPORT_MAX_ROWS
        when(configRepository.findAll()).thenReturn(List.of(SystemConfiguration.builder()
                .configKey(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS.getKey())
                .configValue("30")
                .build()));
        SystemConfigService systemConfigService = new SystemConfigService(configRepository, null, null, null, null);
        systemConfigService.initCache();
        helper = mock(UserBulkImportHelper.class);
        service = new UserBulkImportService(helper, systemConfigService);
    }

    private MockMultipartFile zip(int rows) throws Exception {
        StringBuilder csv = new StringBuilder("user_code,full_name,email,role\n");
        for (int i = 1; i <= rows; i++) {
            csv.append("DEF-").append(i).append(",Default Row ").append(i)
                    .append(",def-").append(i).append("@fpt.edu.vn,ADMIN\n");
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
    @DisplayName("Key thiếu -> mặc định 200: 201 dòng bị từ chối (message nêu 200), 200 dòng qua giới hạn")
    void missingKey_fallsBackTo200() throws Exception {
        MaxRecordsExceededException ex = assertThrows(MaxRecordsExceededException.class,
                () -> service.bulkImportNormalUsers(zip(201)));
        assertTrue(ex.getMessage().contains("tối đa 200 "), ex.getMessage());

        // 200 dòng role ADMIN: qua giới hạn, từng dòng bị luồng người dùng thường từ chối trước khi gọi helper
        BulkImportResponse ok = service.bulkImportNormalUsers(zip(200));
        assertEquals(200, ok.getTotalRows());
        assertEquals(200, ok.getFailureCount());
        verifyNoInteractions(helper);
    }
}
