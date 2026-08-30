import cv2
import numpy as np
from typing import List, Optional

class FaceEmbedder:
    """
    Module trích xuất đặc trưng khuôn mặt (Face Embedding) dạng Vector 512 chiều.
    Vector được chuẩn hóa L2 (L2-norm = 1.0) để phục vụ so khớp độ tương đồng Cosine Similarity / pgvector.
    """
    def __init__(self, embedding_dim: int = 512):
        self.embedding_dim = embedding_dim

    def extract_embedding(self, face_image: np.ndarray) -> List[float]:
        """
        Trích xuất vector 512 chiều từ ảnh khuôn mặt (Face Crop).
        """
        if face_image is None or face_image.size == 0:
            # Trả về zero vector nếu ảnh lỗi
            return [0.0] * self.embedding_dim

        # 1. Tiền xử lý: Resize về kích thước chuẩn 112x112 và chuyển Gray/RGB
        resized = cv2.resize(face_image, (112, 112))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY) if len(resized.shape) == 3 else resized
        
        # 2. Trích xuất đặc trưng đa phân giải (Spatial Multiscale Representation)
        # Sử dụng biến đổi DCT và Histogram Gradient cục bộ
        h_blocks = 8
        w_blocks = 8
        block_h = 112 // h_blocks
        block_w = 112 // w_blocks
        
        features = []
        for i in range(h_blocks):
            for j in range(w_blocks):
                block = gray[i * block_h : (i + 1) * block_h, j * block_w : (j + 1) * block_w]
                mean_val = float(np.mean(block))
                std_val = float(np.std(block))
                gx = cv2.Sobel(block, cv2.CV_32F, 1, 0, ksize=3)
                gy = cv2.Sobel(block, cv2.CV_32F, 0, 1, ksize=3)
                mag = np.mean(np.sqrt(gx**2 + gy**2))
                features.extend([mean_val, std_val, float(mag)])

        # Bổ sung các hệ số Fourier / DCT tần số thấp cho đủ 512 chiều
        float_gray = np.float32(gray) / 255.0
        dct = cv2.dct(float_gray)
        dct_flat = dct[:18, :18].flatten() # 324 giá trị
        features.extend([float(v) for v in dct_flat])
        
        # Đảm bảo đúng chính xác 512 chiều
        if len(features) < self.embedding_dim:
            features.extend([0.0] * (self.embedding_dim - len(features)))
        else:
            features = features[:self.embedding_dim]

        # 3. Chuẩn hóa L2 (Unit Vector)
        feat_arr = np.array(features, dtype=np.float32)
        norm = np.linalg.norm(feat_arr)
        if norm > 1e-6:
            feat_arr = feat_arr / norm
        else:
            feat_arr = np.zeros(self.embedding_dim, dtype=np.float32)

        return [round(float(x), 6) for x in feat_arr]
