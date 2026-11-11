package com.fa26se040.icss.audit;

import com.fa26se040.icss.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V114 (L19): hàm trigger append-only của audit_logs có search_path cố định (pg_catalog, public) và trigger vẫn gắn trên
 * bảng. Việc chặn UPDATE / DELETE kiểm ở AccessControlAuditLogIntegrationTest (test append-only có sẵn).
 */
class AuditTriggerSearchPathIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("L19: trg_prevent_audit_logs_modification() có proconfig search_path=pg_catalog, public; trigger append-only còn bật")
    void auditTriggerFunction_hasFixedSearchPath() {
        List<String> config = jdbc.queryForList("SELECT unnest(p.proconfig) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE p.proname = 'trg_prevent_audit_logs_modification' AND n.nspname = 'public'", String.class);
        assertThat(config).containsExactly("search_path=pg_catalog, public");

        String triggerFunction = jdbc.queryForObject("SELECT p.proname FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid "
                + "WHERE t.tgrelid = 'public.audit_logs'::regclass AND t.tgname = 'trg_audit_logs_append_only' AND t.tgenabled <> 'D'",
                String.class);
        assertThat(triggerFunction).isEqualTo("trg_prevent_audit_logs_modification");
    }
}
