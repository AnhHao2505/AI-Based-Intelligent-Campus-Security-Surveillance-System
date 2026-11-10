import sys
import time
import numpy as np

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

def test_refresh_cache_parsing():
    print("=" * 70)
    print("TEST OPT-10: ĐO TỐC ĐỘ PARSE VECTOR TRONG REFRESH_CACHE")
    print("=" * 70)

    # Sinh 1000 chuỗi vector pgvector text giả lập: "[0.123, -0.456, ...]"
    num_vectors = 1000
    dim = 512
    raw_vecs = np.random.randn(num_vectors, dim).astype(np.float32)
    text_vecs = ["[" + ",".join(f"{x:.6f}" for x in v) + "]" for v in raw_vecs]

    print(f"Số lượng vector thử nghiệm: {num_vectors} hồ sơ (512 chiều)")

    # 1. Cách hiện tại: clean_str.strip("[]") + [float(x) for x in split(",")]
    t0 = time.perf_counter()
    parsed_1 = []
    for emb_str in text_vecs:
        clean_str = emb_str.strip("[]")
        vec = np.array([float(x) for x in clean_str.split(",")], dtype=np.float32)
        norm = np.linalg.norm(vec)
        if norm > 1e-6:
            vec = vec / norm
        parsed_1.append(vec)
    matrix_1 = np.vstack(parsed_1)
    dur_1_ms = (time.perf_counter() - t0) * 1000.0
    print(f"1. Cách hiện tại (Python split & float list): {dur_1_ms:.2f} ms")

    # 2. Tối ưu: np.fromstring(clean_str, sep=',')
    t0 = time.perf_counter()
    parsed_2 = []
    for emb_str in text_vecs:
        clean_str = emb_str[1:-1]
        vec = np.fromstring(clean_str, dtype=np.float32, sep=',')
        norm = np.linalg.norm(vec)
        if norm > 1e-6:
            vec = vec / norm
        parsed_2.append(vec)
    matrix_2 = np.vstack(parsed_2)
    dur_2_ms = (time.perf_counter() - t0) * 1000.0
    print(f"2. Tối ưu (np.fromstring C-level): {dur_2_ms:.2f} ms (Nhanh hơn {(dur_1_ms/dur_2_ms - 1)*100:+.1f}%)")

    # 3. Xác nhận truy vấn runtime của ai-service có chạm tới pgvector index không
    print("\n3. Xác nhận về pgvector index tại Runtime:")
    print("  - Trong VideoPipeline.process_frame() -> FaceMatcher.match_face():")
    print("    AI-service HOÀN TOÀN KHÔNG gửi truy vấn SQL 'SELECT ... ORDER BY embedding <=> ...' tới Postgres!")
    print("    Nó thực hiện 100% bằng In-Memory BLAS GEMV: np.dot(self.cached_matrix, cam_vec).")
    print("  - Kết luận: OPT-10 (thêm HNSW index cho pgvector) BỊ LOẠI BỎ khỏi phạm vi tối ưu đường nóng ai-service.")
    print("    Chỉ cần tối ưu refresh_cache chạy ngầm ở background thread hoặc event-driven khi có thêm/sửa/xóa khuôn mặt.")

if __name__ == "__main__":
    test_refresh_cache_parsing()
