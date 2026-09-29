package com.fa26se040.icss;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * Base class cho tất cả integration tests yêu cầu nạp Spring Context.
 * Bắt buộc kích hoạt profile "test" để trỏ về database kiểm thử (campus_security_test).
 * Tích hợp chốt chặn an toàn: nếu database không kết thúc bằng "_test", fail ngay lập tức.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected DataSource dataSource;

    @BeforeAll
    public void verifyTestDatabaseSafety() {
        if (dataSource == null) {
            throw new IllegalStateException("CHỐT CHẶN AN TOÀN: DataSource là null trong AbstractIntegrationTest!");
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData.getURL();
            String catalog = connection.getCatalog();
            String dbName = (catalog != null && !catalog.isBlank()) ? catalog : extractDatabaseName(url);

            if (dbName == null || !dbName.endsWith("_test")) {
                throw new IllegalStateException(String.format(
                        "CHỐT CHẶN AN TOÀN KÍCH HOẠT: Integration tests chỉ được phép chạy trên database kết thúc bằng '_test'! " +
                        "Database hiện tại là: '%s'. " +
                        "Vui lòng kiểm tra lại cấu hình profile 'test' và đảm bảo @ActiveProfiles(\"test\") được áp dụng.",
                        dbName
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("CHỐT CHẶN AN TOÀN: Không thể xác minh database qua kết nối DataSource", e);
        }
    }

    private static String extractDatabaseName(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        String clean = jdbcUrl;
        int qIdx = clean.indexOf('?');
        if (qIdx != -1) {
            clean = clean.substring(0, qIdx);
        }
        int slashIdx = clean.lastIndexOf('/');
        if (slashIdx != -1 && slashIdx < clean.length() - 1) {
            return clean.substring(slashIdx + 1);
        }
        return null;
    }
}
