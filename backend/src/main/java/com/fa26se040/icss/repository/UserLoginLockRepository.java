package com.fa26se040.icss.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V114 (L3): trạng thái khoá đăng nhập theo tài khoản trong bảng users (failed_login_count, failed_login_window_start,
 * login_locked_until). Mọi lần ghi là MỘT câu UPDATE có điều kiện (không đọc rồi ghi), nên 2 request sai cùng lúc được
 * PostgreSQL xếp hàng theo khoá dòng: request sau đánh giá lại WHERE trên dòng đã cập nhật. Dùng JDBC vì cần
 * {@code UPDATE ... RETURNING} để biết giá trị sau cập nhật trong cùng câu lệnh.
 */
@Repository
@RequiredArgsConstructor
public class UserLoginLockRepository {

    /** Giá trị sau cập nhật của một lần sai. {@code lockedUntil != null} nghĩa là chính lần sai này vừa mở đợt khoá. */
    public record FailureResult(int failedCount, OffsetDateTime lockedUntil) {
    }

    /**
     * Cửa sổ đếm bắt đầu lại khi: chưa có cửa sổ, cửa sổ đã quá {@code windowStartedBefore}, hoặc còn dấu khoá cũ đã hết hạn
     * (WHERE đã loại dòng đang khoá nên login_locked_until khác NULL ở đây chắc chắn đã hết hạn).
     */
    private static final String WINDOW_RESTARTS = "(failed_login_window_start IS NULL OR login_locked_until IS NOT NULL "
            + "OR failed_login_window_start < :windowStartedBefore)";

    private static final String NEW_COUNT = "(CASE WHEN " + WINDOW_RESTARTS + " THEN 1 ELSE failed_login_count + 1 END)";

    private static final String RECORD_FAILURE_SQL = "UPDATE users SET "
            + "failed_login_count = " + NEW_COUNT + ", "
            + "failed_login_window_start = CASE WHEN " + WINDOW_RESTARTS + " THEN :now ELSE failed_login_window_start END, "
            + "login_locked_until = CASE WHEN " + NEW_COUNT + " >= :maxAttempts THEN :lockUntil ELSE NULL END "
            + "WHERE id = :id AND (login_locked_until IS NULL OR login_locked_until <= :now) "
            + "RETURNING failed_login_count, login_locked_until";

    private static final String RESET_SQL = "UPDATE users SET failed_login_count = 0, failed_login_window_start = NULL, "
            + "login_locked_until = NULL WHERE id = :id "
            + "AND (failed_login_count <> 0 OR failed_login_window_start IS NOT NULL OR login_locked_until IS NOT NULL)";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Ghi một lần sai. Rỗng khi tài khoản đang trong đợt khoá (không đếm thêm, không kéo dài khoá) hoặc không còn dòng.
     *
     * @param windowStartedBefore now − LOGIN_FAILED_WINDOW_MINUTES
     * @param lockUntil           now + LOGIN_LOCK_MINUTES, đặt khi số lần sai sau cập nhật ≥ {@code maxAttempts}
     */
    public Optional<FailureResult> recordFailure(UUID userId, OffsetDateTime now, OffsetDateTime windowStartedBefore,
                                                 int maxAttempts, OffsetDateTime lockUntil) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("now", now)
                .addValue("windowStartedBefore", windowStartedBefore)
                .addValue("maxAttempts", maxAttempts)
                .addValue("lockUntil", lockUntil);
        List<FailureResult> rows = jdbc.query(RECORD_FAILURE_SQL, params, (rs, i) -> new FailureResult(
                rs.getInt("failed_login_count"), rs.getObject("login_locked_until", OffsetDateTime.class)));
        return rows.stream().findFirst();
    }

    /** Đăng nhập thành công: 3 cột về mặc định (không ghi nếu đã ở mặc định). */
    public void reset(UUID userId) {
        jdbc.update(RESET_SQL, new MapSqlParameterSource("id", userId));
    }
}
