"""
Tạo lại vector khuôn mặt (face_data.embedding_front) bị hỏng từ ảnh gốc đã lưu trên MinIO.

Bối cảnh: khi AI service chưa nạp được model ArcFace, FaceEmbedder âm thầm trả vector dự phòng DCT.
Vector đó "giống" mọi người khác (cos trung bình ~0.8, ArcFace thật chỉ ~0.0-0.2) nên mặt thật trên camera
không bao giờ khớp -> người đã đăng ký luôn bị báo UNKNOWN_PERSON.

Script:
  1. Phát hiện vector hỏng: cos trung bình với mọi người khác > --broken-threshold (mặc định 0.5).
  2. Tải ảnh gốc (image_front_url) từ MinIO, chạy ĐÚNG quy trình đăng ký hiện tại
     (YuNet detect_in_image -> crop + 5 điểm mốc -> ArcFace extract_embedding), giống /api/v1/faces/process-registration.
  3. Mặc định CHỈ IN KẾT QUẢ (chạy thử). Thêm --apply mới ghi DB: sao lưu bảng trước, cập nhật trong 1 transaction.

Chạy từ thư mục ai-service:
  .venv\\Scripts\\python tools\\reembed_broken_faces.py            # chạy thử
  .venv\\Scripts\\python tools\\reembed_broken_faces.py --apply    # ghi DB
Sau khi --apply: làm mới face cache của AI (POST /api/v1/faces/refresh-cache) hoặc khởi động lại AI service.
"""
import argparse
import json
import os
import sys
from datetime import datetime
from urllib.parse import urlparse, unquote

import numpy as np

AI_SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, AI_SERVICE_DIR)

import psycopg2  # noqa: E402

from src.config import settings  # noqa: E402
from src.core.face_detector import FaceDetector  # noqa: E402
from src.core.face_embedder import get_shared_face_embedder  # noqa: E402
from src.integration.storage_service import StorageService  # noqa: E402
from src.utils.image_utils import decode_image_safely  # noqa: E402


def _connect():
    return psycopg2.connect(
        host=settings.DB_HOST,
        port=settings.DB_PORT,
        user=settings.POSTGRES_USER,
        password=settings.POSTGRES_PASSWORD,
        dbname=settings.POSTGRES_DB,
    )


def _avg_cos_to_others(matrix: np.ndarray) -> np.ndarray:
    sims = matrix @ matrix.T
    n = len(matrix)
    if n < 2:
        return np.zeros(n)
    return (sims.sum(axis=1) - np.diag(sims)) / (n - 1)


def _split_minio_url(url: str):
    """http://host:9000/<bucket>/<object...> -> (bucket, object)"""
    path = unquote(urlparse(url).path).lstrip("/")
    bucket, _, obj = path.partition("/")
    return bucket, obj


def _embed_like_registration(img, detector, embedder):
    """Giống hệt /api/v1/faces/process-registration (main.py)."""
    faces = detector.detect_in_image(img)
    if not faces:
        return None, 0
    faces.sort(key=lambda f: f.score, reverse=True)
    fx1, fy1, fx2, fy2 = faces[0].bbox.to_int_xyxy()
    ih, iw = img.shape[:2]
    fx1, fy1 = max(0, fx1), max(0, fy1)
    fx2, fy2 = min(iw, fx2), min(ih, fy2)
    face_crop = img[fy1:fy2, fx1:fx2] if (fx2 > fx1 and fy2 > fy1) else img
    landmarks = None
    if faces[0].landmarks:
        landmarks = [(pt[0] - fx1, pt[1] - fy1) for pt in faces[0].landmarks]
    vec = np.array(embedder.extract_embedding(face_crop, landmarks=landmarks), dtype=np.float32)
    return vec, len(faces)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="Ghi vector mới vào DB (mặc định chỉ chạy thử)")
    ap.add_argument("--broken-threshold", type=float, default=0.5,
                    help="Cos trung bình với người khác vượt ngưỡng này thì coi là vector hỏng (mặc định 0.5)")
    ap.add_argument("--codes", nargs="*", help="Chỉ xử lý các mã này (bỏ qua bước tự phát hiện)")
    args = ap.parse_args()

    embedder = get_shared_face_embedder()
    if getattr(embedder, "session", None) is None:
        print("❌ ArcFace chưa nạp được (thiếu model hoặc onnxruntime) -> dừng, tránh tạo lại vector hỏng lần nữa.")
        return 2
    print(f"ArcFace provider: {embedder.get_active_provider()}")

    conn = _connect()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, code, image_front_url, embedding_front::text FROM face_data "
                "WHERE embedding_front IS NOT NULL ORDER BY code"
            )
            rows = cur.fetchall()
        if not rows:
            print("Không có dữ liệu face_data.")
            return 0

        ids = [r[0] for r in rows]
        codes = [r[1] for r in rows]
        urls = [r[2] for r in rows]
        old = np.array([json.loads(r[3]) for r in rows], dtype=np.float32)
        old /= np.linalg.norm(old, axis=1, keepdims=True) + 1e-12
        avg_cos = _avg_cos_to_others(old)

        if args.codes:
            wanted = {c.strip().upper() for c in args.codes}
            targets = [i for i, c in enumerate(codes) if c.upper() in wanted]
        else:
            targets = [i for i, m in enumerate(avg_cos) if m > args.broken_threshold]
        good = [i for i in range(len(rows)) if i not in set(targets)]

        print(f"Tổng {len(rows)} người | cần tạo lại: {len(targets)} | bình thường: {len(good)}")
        if not targets:
            return 0

        storage = StorageService()
        detector = FaceDetector()
        new_vecs = {}
        print(f"\n{'Mã':<12}{'cos TB cũ':>10}{'số mặt':>8}{'cos TB mới':>12}{'cũ↔mới':>9}  Ghi chú")
        for i in targets:
            code, url = codes[i], urls[i]
            note = ""
            if not url:
                print(f"{code:<12}{avg_cos[i]:>10.3f}{'-':>8}{'-':>12}{'-':>9}  BỎ QUA: không có ảnh gốc")
                continue
            bucket, obj = _split_minio_url(url)
            raw = storage.download_bytes(bucket, obj)
            if not raw:
                print(f"{code:<12}{avg_cos[i]:>10.3f}{'-':>8}{'-':>12}{'-':>9}  BỎ QUA: không tải được {bucket}/{obj}")
                continue
            try:
                img, _ = decode_image_safely(raw)
            except Exception as e:
                print(f"{code:<12}{avg_cos[i]:>10.3f}{'-':>8}{'-':>12}{'-':>9}  BỎ QUA: ảnh lỗi ({e})")
                continue
            vec, n_faces = _embed_like_registration(img, detector, embedder)
            if vec is None:
                print(f"{code:<12}{avg_cos[i]:>10.3f}{0:>8}{'-':>12}{'-':>9}  BỎ QUA: không thấy khuôn mặt trong ảnh")
                continue
            vec /= np.linalg.norm(vec) + 1e-12
            new_vecs[i] = vec
            if n_faces > 1:
                note = "ảnh có nhiều mặt, lấy mặt rõ nhất"
            ref = old[good] if good else np.zeros((0, vec.shape[0]), np.float32)
            new_avg = float((ref @ vec).mean()) if len(ref) else float("nan")
            print(f"{code:<12}{avg_cos[i]:>10.3f}{n_faces:>8}{new_avg:>12.3f}{float(old[i] @ vec):>9.3f}  {note}")

        # Kiểm tra sau khi thay: các vector mới phải "khác nhau" như ArcFace thật
        if len(new_vecs) >= 2:
            m = np.stack(list(new_vecs.values()))
            print(f"\nCos TB giữa các vector MỚI với nhau: {float(_avg_cos_to_others(m).mean()):.3f} (ArcFace thật ~0.0-0.2)")

        if not args.apply:
            print(f"\nCHẠY THỬ: chưa ghi DB. Thêm --apply để cập nhật {len(new_vecs)} người.")
            return 0
        if not new_vecs:
            print("Không có vector nào để ghi.")
            return 1

        backup_table = "face_data_embedding_backup_" + datetime.now().strftime("%Y%m%d_%H%M%S")
        with conn.cursor() as cur:
            cur.execute(
                f"CREATE TABLE {backup_table} AS "
                "SELECT id, code, embedding_front, now() AS backed_up_at FROM face_data"
            )
            for i, vec in new_vecs.items():
                literal = "[" + ",".join(f"{float(x):.6f}" for x in vec) + "]"
                cur.execute("UPDATE face_data SET embedding_front = %s::vector WHERE id = %s", (literal, ids[i]))
        conn.commit()
        print(f"\n✅ Đã cập nhật {len(new_vecs)} người. Bản sao lưu: bảng {backup_table}")
        print("Khôi phục nếu cần: UPDATE face_data f SET embedding_front = b.embedding_front "
              f"FROM {backup_table} b WHERE f.id = b.id;")
        print("Tiếp theo: làm mới face cache của AI (POST /api/v1/faces/refresh-cache) hoặc khởi động lại AI service.")
        return 0
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
