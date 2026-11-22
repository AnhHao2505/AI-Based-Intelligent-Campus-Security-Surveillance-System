package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Kiểm trùng khuôn mặt khi đăng ký (người dùng: tạo tài khoản, import ZIP; khách: tạo lượt, thay ảnh).
 * <p>
 * Cách tính giống bên nhận diện camera: cosine giữa hai vector 512 chiều (pgvector {@code 1 - (a <=> b)}, tương đương
 * tích vô hướng của hai vector đã chuẩn hoá), trùng khi cosine ≥ {@link #threshold()}. Ngưỡng đọc một chỗ:
 * {@code AI_FACE_MATCH_THRESHOLD} trong System Configuration, cùng getter mà {@code SystemConfigService#getAiRuntimeConfigMap}
 * gửi sang dịch vụ nhận diện ({@code face_match_threshold}).
 * <p>
 * Kho so sánh:
 * <ul>
 *   <li>Đăng ký người dùng ({@link #findDuplicateForUser}): mọi người dùng chưa xoá mềm, kể cả đang bị vô hiệu hoá
 *   (face-gate 1A), và khách thuộc lượt còn hiệu lực hoặc chưa bắt đầu (APPROVED chưa hết giờ, PENDING chưa tới giờ
 *   bắt đầu).</li>
 *   <li>Ảnh khách ({@link #findDuplicateForGuest}): mọi người dùng chưa xoá mềm (kể cả đang bị vô hiệu hoá, face-gate 2),
 *   các khách khác trong cùng lượt, và khách của lượt khác chỉ khi lượt đó đang PENDING / APPROVED và khung giờ giao
 *   nhau (start_A &lt; end_B và start_B &lt; end_A; kết thúc đúng lúc bắt đầu không tính là giao). Cùng một khách được
 *   mời nhiều lượt không chồng giờ vẫn hợp lệ.</li>
 *   <li>Kích hoạt lại tài khoản ({@link #findDuplicateForReactivation}): người dùng đang hoạt động khác và khách của lượt
 *   còn hiệu lực.</li>
 *   <li>Khôi phục tài khoản đã xoá mềm ({@link #findDuplicateForRestore}): như đăng ký người dùng.</li>
 * </ul>
 * Chống hai yêu cầu cùng lúc (face-gate 4): mọi lần kiểm gọi {@code pg_advisory_xact_lock} với một khoá cố định trước
 * khi đọc, khoá giữ tới khi transaction của người gọi kết thúc (người gọi ghi embedding / đổi trạng thái trong cùng
 * transaction đó). Các lượt đăng ký khuôn mặt vì vậy chạy nối tiếp nhau; không khoá dòng nào.
 * Người gọi phải đang ở trong transaction ghi, nếu không khoá nhả ngay khi hàm trả về.
 * <p>
 * Kết quả chỉ có mã / tên / lượt, không có ảnh hay vector của người kia.
 */
@Service
@RequiredArgsConstructor
public class FaceDuplicateCheckService {

    public static final int EMBEDDING_DIM = 512;
    private static final int PURPOSE_PREVIEW_LENGTH = 60;
    /** Khoá advisory dùng chung cho mọi lần kiểm trùng + ghi khuôn mặt (người dùng và khách). Hằng tuỳ ý, chỉ cần cố định. */
    static final long FACE_REGISTRATION_LOCK_KEY = 0x1C55_FACE_0001L;

    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigService systemConfigService;

    /** USER: người dùng; GUEST: khách của lượt khác; GUEST_SAME_VISIT: khách khác trong cùng lượt. */
    public enum Kind { USER, GUEST, GUEST_SAME_VISIT }

    /** Người đã đăng ký khuôn mặt giống ảnh mới. {@code userActive}: chỉ có nghĩa khi kind = USER. */
    public record Match(Kind kind, String userCode, boolean userActive, String guestName, String visitPurpose,
                        OffsetDateTime visitStart, OffsetDateTime visitEnd, double score) {

        /**
         * "tài khoản SV-001", "tài khoản SV-001 (đang bị vô hiệu hoá)" hoặc "khách Nguyễn Văn A của lượt "…"
         * (05/12/2026, 09:00-11:00)". Câu chi tiết, chỉ dùng cho luồng ADMIN (tạo / import / mở khoá / khôi phục).
         */
        public String describe() {
            if (kind == Kind.USER) {
                return "tài khoản " + userCode + (userActive ? "" : " (đang bị vô hiệu hoá)");
            }
            if (kind == Kind.GUEST_SAME_VISIT) {
                return "khách " + guestName + " trong cùng lượt này";
            }
            return "khách " + guestName + " của lượt \"" + shorten(visitPurpose) + "\" ("
                    + InAppNotificationService.formatTimeRange(visitStart, visitEnd) + ")";
        }

        /** Câu lỗi chi tiết cho luồng ADMIN: "Khuôn mặt này đã được đăng ký cho " + {@link #describe()}. */
        public String adminMessage() {
            return "Khuôn mặt này đã được đăng ký cho " + describe();
        }
    }

    /** Ngưỡng cosine dùng chung với bên nhận diện. */
    public double threshold() {
        return systemConfigService.getDouble(ConfigKey.AI_FACE_MATCH_THRESHOLD);
    }

    /**
     * Đăng ký khuôn mặt người dùng (tạo tài khoản, import ZIP): tìm người dùng chưa xoá mềm (kể cả đang bị vô hiệu hoá)
     * hoặc khách của lượt còn hiệu lực / chưa bắt đầu có cosine ≥ ngưỡng, giống nhất trước. Giữ khoá advisory tới hết
     * transaction của người gọi.
     *
     * @param excludeUserCode mã đang được đăng ký / thay ảnh (không so với chính mình), có thể null
     * @return rỗng nếu không trùng
     * @throws IllegalArgumentException vector không đủ 512 chiều (không so được thì không cho qua)
     */
    @Transactional
    public Optional<Match> findDuplicateForUser(List<Float> embedding, String excludeUserCode) {
        String vector = toVector(requireEmbedding(embedding));
        lockRegistrations();
        return pick(bestUser(vector, excludeUserCode, true), liveGuest(vector));
    }

    /**
     * Kích hoạt lại tài khoản đang bị vô hiệu hoá (face-gate 3): khuôn mặt đã lưu của mã này có trùng người dùng đang
     * hoạt động khác, hay khách của lượt còn hiệu lực không. Mã chưa có khuôn mặt -> rỗng.
     */
    @Transactional
    public Optional<Match> findDuplicateForReactivation(String userCode) {
        return storedVector(userCode).flatMap(vector -> {
            lockRegistrations();
            return pick(bestUser(vector, userCode, false), liveGuest(vector));
        });
    }

    /**
     * Khôi phục tài khoản đã xoá mềm (face-gate 3): khuôn mặt đã lưu của mã này có trùng người dùng chưa xoá mềm khác
     * (kể cả đang bị vô hiệu hoá và các tài khoản vừa khôi phục trong cùng transaction, người gọi phải flush trước),
     * hay khách của lượt còn hiệu lực không. Mã chưa có khuôn mặt -> rỗng.
     */
    @Transactional
    public Optional<Match> findDuplicateForRestore(String userCode) {
        return storedVector(userCode).flatMap(vector -> {
            lockRegistrations();
            return pick(bestUser(vector, userCode, true), liveGuest(vector));
        });
    }

    /**
     * Ảnh khách: tìm người dùng đang hoạt động, khách khác trong cùng lượt, hoặc khách của lượt khác đang PENDING / APPROVED
     * có khung giờ giao với [start, end) của lượt này, có cosine ≥ ngưỡng.
     *
     * @param visitId        lượt của khách (khi tạo lượt: lượt vừa dựng, khách cùng đơn chưa có trong DB nên người gọi
     *                       tự so với nhau bằng {@link #cosine})
     * @param start          giờ bắt đầu của lượt này
     * @param end            giờ kết thúc của lượt này
     * @param excludeGuestId khách đang được thay ảnh (không so với chính mình), có thể null
     */
    @Transactional
    public Optional<Match> findDuplicateForGuest(List<Float> embedding, UUID visitId, OffsetDateTime start,
                                                 OffsetDateTime end, UUID excludeGuestId) {
        String vector = toVector(requireEmbedding(embedding));
        lockRegistrations();
        Match sameVisit = null;
        if (visitId != null) {
            List<Object> args = new ArrayList<>(List.of(visitId));
            String where = " AND gv.id = ?";
            if (excludeGuestId != null) {
                where += " AND g.id <> ?";
                args.add(excludeGuestId);
            }
            sameVisit = bestGuest(vector, Kind.GUEST_SAME_VISIT, where, args);
        }
        // lượt khác: PENDING / APPROVED và giao khung giờ (mốc chạm nhau không tính)
        List<Object> args = new ArrayList<>(List.of(end, start));
        String where = " AND gv.status IN ('PENDING', 'APPROVED') AND gv.start_time < ? AND ? < gv.end_time";
        if (visitId != null) {
            where += " AND gv.id <> ?";
            args.add(visitId);
        }
        Match overlapping = bestGuest(vector, Kind.GUEST, where, args);
        return pick(bestUser(vector, null, true), sameVisit, overlapping);
    }

    /** Vector đủ 512 chiều, nếu không thì chặn (thiếu dữ liệu không được coi là "không trùng"). */
    private static List<Float> requireEmbedding(List<Float> embedding) {
        if (embedding == null || embedding.size() != EMBEDDING_DIM) {
            throw new IllegalArgumentException("Vector khuôn mặt không hợp lệ, không kiểm trùng được.");
        }
        return embedding;
    }

    /** Khoá advisory theo transaction: các lần kiểm trùng + ghi khuôn mặt chạy nối tiếp (face-gate 4). */
    private void lockRegistrations() {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)", rs -> null, FACE_REGISTRATION_LOCK_KEY);
    }

    /** Vector khuôn mặt đã lưu của mã (dạng chuỗi pgvector), rỗng nếu mã chưa đăng ký khuôn mặt. */
    private Optional<String> storedVector(String userCode) {
        if (userCode == null || userCode.isBlank()) {
            return Optional.empty();
        }
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT embedding_front::text FROM face_data WHERE UPPER(code) = UPPER(?) LIMIT 1", String.class, userCode);
        return rows.isEmpty() || rows.get(0) == null ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Khách của lượt còn hiệu lực hoặc chưa bắt đầu (APPROVED chưa hết giờ, PENDING chưa tới giờ bắt đầu). */
    private Match liveGuest(String vector) {
        OffsetDateTime now = OffsetDateTime.now();
        return bestGuest(vector, Kind.GUEST,
                " AND ((gv.status = 'APPROVED' AND gv.end_time > ?) OR (gv.status = 'PENDING' AND gv.start_time > ?))",
                List.of(now, now));
    }

    /** Người giống nhất trong các ứng viên đạt ngưỡng. */
    private Optional<Match> pick(Match... candidates) {
        double threshold = threshold();
        Match best = null;
        for (Match m : candidates) {
            if (m != null && m.score() >= threshold && (best == null || m.score() > best.score())) {
                best = m;
            }
        }
        return Optional.ofNullable(best);
    }

    // Sắp theo khoảng cách cosine tăng dần: vector lỗi (NaN) xếp cuối, không che người trùng thật.
    // includeInactive: true = mọi tài khoản chưa xoá mềm (đăng ký, ảnh khách, khôi phục); false = chỉ đang hoạt động (mở khoá)
    private Match bestUser(String vector, String excludeUserCode, boolean includeInactive) {
        // Cùng cách nối face_data -> users với AiSyncService (kho khuôn mặt gửi cho bên nhận diện)
        StringBuilder sql = new StringBuilder("""
                SELECT u.user_code, u.is_active, (fd.embedding_front <=> CAST(? AS vector)) AS distance
                FROM face_data fd
                JOIN users u ON (fd.user_id = u.id OR fd.code = u.user_code)
                WHERE u.deleted_at IS NULL
                """);
        if (!includeInactive) {
            sql.append(" AND u.is_active = true");
        }
        List<Object> args = new ArrayList<>();
        args.add(vector);
        if (excludeUserCode != null && !excludeUserCode.isBlank()) {
            sql.append(" AND UPPER(u.user_code) <> UPPER(?) AND UPPER(fd.code) <> UPPER(?)");
            args.add(excludeUserCode);
            args.add(excludeUserCode);
        }
        sql.append(" ORDER BY distance ASC LIMIT 1");
        List<Match> rows = jdbcTemplate.query(sql.toString(), (rs, i) -> new Match(Kind.USER, rs.getString("user_code"),
                rs.getBoolean("is_active"), null, null, null, null, 1 - rs.getDouble("distance")), args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Khách có ảnh (PHOTO_READY + embedding) có khuôn mặt gần nhất trong phạm vi {@code where} (bắt đầu bằng " AND"). */
    private Match bestGuest(String vector, Kind kind, String where, List<Object> whereArgs) {
        StringBuilder sql = new StringBuilder("""
                SELECT g.full_name, gv.purpose, gv.start_time, gv.end_time,
                       (gfe.embedding <=> CAST(? AS vector)) AS distance
                FROM guest_face_embeddings gfe
                JOIN guests g ON g.id = gfe.guest_id
                JOIN guest_visits gv ON gv.id = g.visit_id
                WHERE g.biometric_status = 'PHOTO_READY'
                """);
        List<Object> args = new ArrayList<>();
        args.add(vector);
        sql.append(where);
        args.addAll(whereArgs);
        sql.append(" ORDER BY distance ASC LIMIT 1");
        List<Match> rows = jdbcTemplate.query(sql.toString(), (rs, i) -> new Match(kind, null, false,
                rs.getString("full_name"), rs.getString("purpose"),
                rs.getObject("start_time", OffsetDateTime.class), rs.getObject("end_time", OffsetDateTime.class),
                1 - rs.getDouble("distance")), args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Cosine giữa hai vector (dùng cho các ảnh trong cùng một lần gửi, chưa có trong DB). 0 nếu không so được. */
    public static double cosine(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    static String toVector(List<Float> values) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (Float v : values) {
            sj.add(Float.toString(v));
        }
        return sj.toString();
    }

    private static String shorten(String purpose) {
        String p = purpose == null ? "" : purpose.trim();
        return p.length() <= PURPOSE_PREVIEW_LENGTH ? p : p.substring(0, PURPOSE_PREVIEW_LENGTH - 1) + "…";
    }
}
