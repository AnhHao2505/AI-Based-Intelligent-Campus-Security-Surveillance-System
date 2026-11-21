package com.fa26se040.icss;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Vector khuôn mặt giả cho test (512 chiều). Đăng ký khuôn mặt có kiểm trùng (cosine ≥ AI_FACE_MATCH_THRESHOLD):
 * vector hằng số (0.01, 0.02…) luôn cùng hướng nên bị coi là cùng một người; mỗi lần cần "người khác" phải dùng
 * {@link #random()} (vector Gauss ngẫu nhiên, cosine giữa hai vector ≈ 0 ± 0.05). DB test không rollback nên vector
 * của các lượt chạy trước vẫn còn, chỉ vector ngẫu nhiên mới không đụng nhau.
 */
public final class TestFaces {

    public static final int DIM = 512;

    private TestFaces() {
    }

    /** Một khuôn mặt "mới", khác mọi khuôn mặt đã có. */
    public static List<Float> random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Float> v = new ArrayList<>(DIM);
        for (int i = 0; i < DIM; i++) {
            v.add((float) r.nextGaussian());
        }
        return v;
    }

    /** Cùng người với {@code base} (ảnh khác): cộng nhiễu nhỏ, cosine với base ≈ 0.99. */
    public static List<Float> sameAs(List<Float> base) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Float> v = new ArrayList<>(DIM);
        for (Float x : base) {
            v.add((float) (x + 0.1 * r.nextGaussian()));
        }
        return v;
    }

    /** "[a,b,...]" — dạng chuỗi pgvector / JSON. */
    public static String text(List<Float> v) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        v.forEach(x -> sj.add(Float.toString(x)));
        return sj.toString();
    }

    /** Phần tử ngăn cách bởi dấu phẩy, không ngoặc (ghép vào JSON giả của dịch vụ trích khuôn mặt). */
    public static String csv(List<Float> v) {
        String t = text(v);
        return t.substring(1, t.length() - 1);
    }

    /** JSON giả của POST /api/v1/faces/process-registration với một khuôn mặt ngẫu nhiên. */
    public static String registrationBody(String code, int faceCount) {
        return registrationBody(code, faceCount, random());
    }

    public static String registrationBody(String code, int faceCount, List<Float> face) {
        return "{\"success\":true,\"code\":\"" + code + "\",\"face_count\":" + faceCount
                + ",\"embedding_front\":[" + csv(face) + "]}";
    }
}
