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
 *   <li>Đăng ký người dùng ({@link #findDuplicateForUser}): người dùng đang hoạt động (chưa xoá mềm, is_active) và khách
 *   thuộc lượt còn hiệu lực hoặc chưa bắt đầu (APPROVED chưa hết giờ, PENDING chưa tới giờ bắt đầu).</li>
 *   <li>Ảnh khách ({@link #findDuplicateForGuest}): người dùng đang hoạt động, các khách khác trong cùng lượt, và khách
 *   của lượt khác chỉ khi lượt đó đang PENDING / APPROVED và khung giờ giao nhau (start_A &lt; end_B và start_B &lt; end_A;
 *   kết thúc đúng lúc bắt đầu không tính là giao). Cùng một khách được mời nhiều lượt không chồng giờ vẫn hợp lệ.</li>
 * </ul>
 * Kết quả chỉ có mã / tên / lượt, không có ảnh hay vector của người kia.
 */
@Service
@RequiredArgsConstructor
public class FaceDuplicateCheckService {

    public static final int EMBEDDING_DIM = 512;
    private static final int PURPOSE_PREVIEW_LENGTH = 60;

    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigService systemConfigService;

    /** USER: người dùng; GUEST: khách của lượt khác; GUEST_SAME_VISIT: khách khác trong cùng lượt. */
    public enum Kind { USER, GUEST, GUEST_SAME_VISIT }

    /** Người đã đăng ký khuôn mặt giống ảnh mới. */
    public record Match(Kind kind, String userCode, String guestName, String visitPurpose,
                        OffsetDateTime visitStart, OffsetDateTime visitEnd, double score) {

        /** "tài khoản SV-001" hoặc "khách Nguyễn Văn A của lượt "…" (05/12/2026, 09:00-11:00)". */
        public String describe() {
            if (kind == Kind.USER) {
                return "tài khoản " + userCode;
            }
            if (kind == Kind.GUEST_SAME_VISIT) {
                return "khách " + guestName + " trong cùng lượt này";
            }
            return "khách " + guestName + " của lượt \"" + shorten(visitPurpose) + "\" ("
                    + InAppNotificationService.formatTimeRange(visitStart, visitEnd) + ")";
        }
    }

    /** Ngưỡng cosine dùng chung với bên nhận diện. */
    public double threshold() {
        return systemConfigService.getDouble(ConfigKey.AI_FACE_MATCH_THRESHOLD);
    }

    /**
     * Đăng ký khuôn mặt người dùng (tạo tài khoản, import ZIP): tìm người dùng đang hoạt động hoặc khách của lượt còn
     * hiệu lực / chưa bắt đầu có cosine ≥ ngưỡng, giống nhất trước.
     *
     * @param excludeUserCode mã đang được đăng ký / thay ảnh (không so với chính mình), có thể null
     * @return rỗng nếu không trùng, hoặc vector không đủ 512 chiều (không so được)
     */
    @Transactional(readOnly = true)
    public Optional<Match> findDuplicateForUser(List<Float> embedding, String excludeUserCode) {
        if (embedding == null || embedding.size() != EMBEDDING_DIM) {
            return Optional.empty();
        }
        String vector = toVector(embedding);
        OffsetDateTime now = OffsetDateTime.now();
        Match liveGuest = bestGuest(vector, Kind.GUEST,
                " AND ((gv.status = 'APPROVED' AND gv.end_time > ?) OR (gv.status = 'PENDING' AND gv.start_time > ?))",
                List.of(now, now));
        return pick(bestUser(vector, excludeUserCode), liveGuest);
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
    @Transactional(readOnly = true)
    public Optional<Match> findDuplicateForGuest(List<Float> embedding, UUID visitId, OffsetDateTime start,
                                                 OffsetDateTime end, UUID excludeGuestId) {
        if (embedding == null || embedding.size() != EMBEDDING_DIM) {
            return Optional.empty();
        }
        String vector = toVector(embedding);
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
        return pick(bestUser(vector, null), sameVisit, overlapping);
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
    private Match bestUser(String vector, String excludeUserCode) {
        // Cùng cách nối face_data -> users với AiSyncService (kho khuôn mặt gửi cho bên nhận diện)
        StringBuilder sql = new StringBuilder("""
                SELECT u.user_code, (fd.embedding_front <=> CAST(? AS vector)) AS distance
                FROM face_data fd
                JOIN users u ON (fd.user_id = u.id OR fd.code = u.user_code)
                WHERE u.deleted_at IS NULL AND u.is_active = true
                """);
        List<Object> args = new ArrayList<>();
        args.add(vector);
        if (excludeUserCode != null && !excludeUserCode.isBlank()) {
            sql.append(" AND UPPER(u.user_code) <> UPPER(?) AND UPPER(fd.code) <> UPPER(?)");
            args.add(excludeUserCode);
            args.add(excludeUserCode);
        }
        sql.append(" ORDER BY distance ASC LIMIT 1");
        List<Match> rows = jdbcTemplate.query(sql.toString(), (rs, i) -> new Match(Kind.USER, rs.getString("user_code"),
                null, null, null, null, 1 - rs.getDouble("distance")), args.toArray());
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
        List<Match> rows = jdbcTemplate.query(sql.toString(), (rs, i) -> new Match(kind, null,
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
