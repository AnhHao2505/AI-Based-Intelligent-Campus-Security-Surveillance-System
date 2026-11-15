"""
Đo A/B cách căn mặt trước ArcFace (CHỈ ĐỌC, không ghi DB):
  A = cách cũ: cắt sát khung mặt rồi căn bằng cv2.estimateAffinePartial2D (RANSAC), chuẩn hoá /128
  F = cách mới (giống InsightFace): căn trên ảnh gốc, biến đổi tương tự bình phương tối thiểu trên 5 điểm, /127.5

Mẫu của mỗi người được tạo LẠI từ chính ảnh đăng ký (image_front_url trên MinIO) theo từng cách, để so công bằng.
Đo 2 thứ:
  1. Người khác nhau (ảnh đăng ký với nhau): độ giống cao nhất / phân vị 99 -> càng thấp càng ít nhận nhầm
  2. Trên luồng camera: điểm của người thật (--code) và điểm của người khác giống nhất -> khoảng cách càng lớn càng tốt

Chạy từ thư mục ai-service (đứng trước camera trong lúc đo):
  .venv\\Scripts\\python tools\\eval_face_alignment.py --code SE000000 --seconds 60
"""
import argparse
import json
import os
import sys
import time
from urllib.parse import unquote, urlparse

import numpy as np

AI_SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, AI_SERVICE_DIR)

import cv2  # noqa: E402
import psycopg2  # noqa: E402

from src.config import settings  # noqa: E402
from src.core.face_detector import FaceDetector  # noqa: E402
from src.core.face_embedder import ARCFACE_CANONICAL_5PTS, get_shared_face_embedder  # noqa: E402
from src.integration.storage_service import StorageService  # noqa: E402
from src.utils.image_utils import decode_image_safely  # noqa: E402


def _run(embedder, aligned: np.ndarray, std: float) -> np.ndarray:
    rgb = cv2.cvtColor(aligned, cv2.COLOR_BGR2RGB)
    blob = ((rgb.astype(np.float32) - 127.5) / std).transpose(2, 0, 1)[None]
    v = embedder.session.run([embedder.output_name], {embedder.input_name: blob})[0][0]
    return v / (np.linalg.norm(v) + 1e-12)


def embed_a(embedder, img, face):
    """Cách cũ: crop sát bbox, RANSAC, /128."""
    h, w = img.shape[:2]
    x1, y1, x2, y2 = face.bbox.to_int_xyxy()
    x1, y1, x2, y2 = max(0, x1), max(0, y1), min(w, x2), min(h, y2)
    crop = img[y1:y2, x1:x2]
    if crop.size == 0 or not face.landmarks:
        return None
    pts = np.array([(px - x1, py - y1) for px, py in face.landmarks], np.float32)
    M, _ = cv2.estimateAffinePartial2D(pts, ARCFACE_CANONICAL_5PTS)
    if M is None:
        return None
    return _run(embedder, cv2.warpAffine(crop, M, (112, 112), borderValue=0.0), 128.0)


def embed_f(embedder, img, face):
    """Cách mới: ảnh gốc + điểm mốc tuyệt đối, bình phương tối thiểu, /127.5 (dùng đúng code của FaceEmbedder)."""
    if not face.landmarks:
        return None
    return _run(embedder, embedder.align_face(img, landmarks=face.landmarks), 127.5)


def best_face(detector, img):
    faces = detector.detect_in_image(img)
    return max(faces, key=lambda f: f.score) if faces else None


def load_templates(detector, embedder):
    conn = psycopg2.connect(host=settings.DB_HOST, port=settings.DB_PORT, user=settings.POSTGRES_USER,
                            password=settings.POSTGRES_PASSWORD, dbname=settings.POSTGRES_DB)
    with conn.cursor() as cur:
        # Giống sync-data: bỏ khuôn mặt của tài khoản đã xoá
        cur.execute("SELECT f.code, f.image_front_url FROM face_data f LEFT JOIN users u ON u.id = f.user_id "
                    "WHERE f.image_front_url IS NOT NULL AND (u.id IS NULL OR u.deleted_at IS NULL) ORDER BY f.code")
        rows = cur.fetchall()
    conn.close()
    storage = StorageService()
    codes, ta, tf = [], [], []
    for code, url in rows:
        path = unquote(urlparse(url).path).lstrip("/")
        bucket, _, obj = path.partition("/")
        raw = storage.download_bytes(bucket, obj)
        if not raw:
            continue
        img, _ = decode_image_safely(raw)
        face = best_face(detector, img)
        if face is None:
            continue
        va, vf = embed_a(embedder, img, face), embed_f(embedder, img, face)
        if va is None or vf is None:
            continue
        codes.append(code); ta.append(va); tf.append(vf)
    return codes, np.stack(ta), np.stack(tf)


def impostor_stats(T):
    sims = T @ T.T
    iu = np.triu_indices(len(T), k=1)
    v = sims[iu]
    return float(v.max()), float(np.percentile(v, 99)), float(np.median(v))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--code", required=True, help="Mã người đứng trước camera (đã đăng ký khuôn mặt)")
    ap.add_argument("--seconds", type=float, default=60.0)
    ap.add_argument("--rtsp", default="rtsp://localhost:8554/cam-002")
    a = ap.parse_args()

    embedder = get_shared_face_embedder()
    if getattr(embedder, "session", None) is None:
        print("❌ ArcFace chưa nạp được"); return 2
    detector = FaceDetector()
    codes, TA, TF = load_templates(detector, embedder)
    print(f"Tạo lại mẫu cho {len(codes)} người từ ảnh đăng ký")
    if a.code not in codes:
        print(f"❌ Không có mẫu cho {a.code}"); return 2
    me = codes.index(a.code)
    others = [i for i in range(len(codes)) if i != me]

    print("\n1) Người khác nhau (ảnh đăng ký với nhau) — càng thấp càng ít nhận nhầm")
    for name, T in (("A cũ", TA), ("F mới", TF)):
        mx, p99, med = impostor_stats(T)
        print(f"   {name:6}: cao nhất {mx:.3f} | phân vị 99 {p99:.3f} | trung vị {med:.3f}")

    print(f"\n2) Đo trên camera {a.seconds:.0f}s — hãy đứng trước camera (nhìn thẳng, quay trái/phải, đi lại)")
    os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
    cap = cv2.VideoCapture(a.rtsp, cv2.CAP_FFMPEG)
    gen = {"A": [], "F": []}; imp = {"A": [], "F": []}
    t_end = time.time() + a.seconds
    while time.time() < t_end:
        ok, frame = cap.read()
        if not ok:
            break
        face = best_face(detector, frame)
        if face is None or min(face.bbox.x2 - face.bbox.x1, face.bbox.y2 - face.bbox.y1) < settings.FACE_MIN_CROP_SIZE:
            continue
        for key, fn, T in (("A", embed_a, TA), ("F", embed_f, TF)):
            v = fn(embedder, frame, face)
            if v is None:
                continue
            s = T @ v
            gen[key].append(float(s[me])); imp[key].append(float(s[others].max()))
    cap.release()
    if not gen["F"]:
        print("   Không thấy khuôn mặt nào"); return 1

    print(f"   Số khung có mặt: {len(gen['F'])}")
    print(f"   {'':6}  {'người thật: trung vị':>21} {'max':>6} {'≥0.50':>6} {'≥0.55':>6} {'≥0.60':>6} | {'người khác giống nhất: max':>27}")
    for key, label in (("A", "A cũ"), ("F", "F mới")):
        g, i = np.array(gen[key]), np.array(imp[key])
        print(f"   {label:6}  {np.median(g):>21.3f} {g.max():>6.3f} {100*np.mean(g>=0.5):>5.0f}% {100*np.mean(g>=0.55):>5.0f}% "
              f"{100*np.mean(g>=0.6):>5.0f}% | {i.max():>27.3f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
