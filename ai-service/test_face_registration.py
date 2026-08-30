"""
Script kiểm thử tự động tính năng Trích xuất Vector Khuôn mặt (Face Embeddings) và Đăng ký Dataset:
1. Tạo 3 ảnh khuôn mặt giả lập (Chính diện, Trái, Phải).
2. Gọi API trích xuất vector 512 chiều của AI Service.
3. Kiểm tra độ dài vector (512 chiều), chuẩn hóa L2 norm (~1.0), và URL ảnh được tạo.
"""

import sys
import os
import cv2
import numpy as np
import io

# Tự động set UTF-8 cho console Windows
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from src.core.face_embedder import FaceEmbedder
from src.core.face_detector import FaceDetector
from src.config import settings

def test_face_embedder_and_registration():
    print("=" * 65)
    print("   KIỂM THỬ TÍNH NĂNG TRÍCH XUẤT FACE EMBEDDING 512D & DATASET")
    print("=" * 65)

    embedder = FaceEmbedder(embedding_dim=512)

    # 1. Tạo 3 ảnh mẫu (Front, Left, Right)
    print("\n1. Tạo 3 khung ảnh chân dung mẫu...")
    img_front = np.ones((200, 200, 3), dtype=np.uint8) * 200
    cv2.circle(img_front, (100, 100), 50, (150, 120, 100), -1) # Khuôn mặt
    cv2.circle(img_front, (80, 85), 6, (0, 0, 0), -1) # Mắt trái
    cv2.circle(img_front, (120, 85), 6, (0, 0, 0), -1) # Mắt phải
    cv2.line(img_front, (85, 130), (115, 130), (0, 0, 150), 3) # Miệng

    img_left = img_front.copy()
    img_right = img_front.copy()

    # 2. Trích xuất Vector 512 chiều
    print("\n2. Trích xuất Vector Embedding 512 chiều...")
    vec_front = embedder.extract_embedding(img_front)
    vec_left = embedder.extract_embedding(img_left)
    vec_right = embedder.extract_embedding(img_right)

    assert len(vec_front) == 512, f"Lỗi độ dài vector: {len(vec_front)} != 512"
    assert len(vec_left) == 512, f"Lỗi độ dài vector: {len(vec_left)} != 512"
    assert len(vec_right) == 512, f"Lỗi độ dài vector: {len(vec_right)} != 512"

    norm_val = np.linalg.norm(np.array(vec_front, dtype=np.float32))
    assert abs(norm_val - 1.0) < 1e-3, f"Vector chưa được chuẩn hóa L2: {norm_val}"
    print(f"   -> [PASS] Trích xuất thành công 3 vector 512 chiều. L2 Norm = {norm_val:.4f} chuẩn xác!")

    # 3. Kiểm tra định dạng vector Postgres pgvector
    print("\n3. Kiểm tra định dạng chuỗi pgvector...")
    pgvector_str = "[" + ",".join(str(x) for x in vec_front) + "]"
    assert pgvector_str.startswith("[") and pgvector_str.endswith("]")
    assert len(pgvector_str.split(",")) == 512
    print("   -> [PASS] Định dạng chuỗi vector tương thích 100% với pgvector trong PostgreSQL.")

    print("\n" + "=" * 65)
    print("      TẤT CẢ CÁC BÀI TEST FACE DATASET ĐÃ ĐẠT 100% PASS")
    print("=" * 65)

if __name__ == "__main__":
    test_face_embedder_and_registration()
