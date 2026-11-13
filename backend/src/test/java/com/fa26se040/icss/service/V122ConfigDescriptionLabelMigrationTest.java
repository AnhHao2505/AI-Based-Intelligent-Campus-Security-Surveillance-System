package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V122: mô tả 2 cấu hình V83 ghi nhãn cấp 3 cũ, đổi sang "Bảo mật cao". DB test đã chạy V122 khi dựng schema;
 * test kiểm kết quả, rồi chạy lại đúng file V122 trong một giao dịch ROLLBACK để kiểm chạy lại không đổi gì.
 */
class V122ConfigDescriptionLabelMigrationTest extends AbstractIntegrationTest {

    private static final String V122_FILE = "db/migration/V122__rename_highly_confidential_label_in_config_descriptions.sql";
    private static final String GAP_DESCRIPTION = "Đơn cá nhân chỉ xin được khu vực có cấp cao hơn cấp của người tạo đơn tối đa bấy nhiêu cấp "
            + "(1: cấp 1 xin Bảo mật, cấp 2 xin Bảo mật cao; 2: cấp 1 xin được cả Bảo mật cao)";
    private static final String GROUP_DESCRIPTION = "Cho phép tạo yêu cầu truy cập nhóm tại khu vực Bảo mật cao (HIGHLY_CONFIDENTIAL)";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;

    private String description(String key) {
        return jdbc.queryForObject("SELECT description FROM system_configurations WHERE config_key = ?", String.class, key);
    }

    @Test
    @DisplayName("V122: mô tả ACCESS_REQUEST_MAX_LEVEL_GAP và ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE dùng nhãn Bảo mật cao; chạy lại không đổi")
    void descriptionsUseNewLabel() throws Exception {
        assertEquals(GAP_DESCRIPTION, description("ACCESS_REQUEST_MAX_LEVEL_GAP"));
        assertEquals(GROUP_DESCRIPTION, description("ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE"));

        String script = new String(new ClassPathResource(V122_FILE).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.execute(script);
            assertEquals(GAP_DESCRIPTION, description("ACCESS_REQUEST_MAX_LEVEL_GAP"));
            assertEquals(GROUP_DESCRIPTION, description("ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE"));
            status.setRollbackOnly();
        });
    }
}
