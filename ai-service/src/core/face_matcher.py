import logging
import time
from typing import Optional, Tuple, Dict, List
import cv2
import numpy as np
import psycopg2

from .face_embedder import FaceEmbedder
from ..config import settings

logger = logging.getLogger(__name__)

class FaceMatcher:
    """
    Module so khớp đặc trưng khuôn mặt (Face Recognition) trực tiếp với PostgreSQL pgvector.
    Có bộ đệm In-Memory Cache để tìm kiếm siêu tốc (Sub-millisecond).
    """
    def __init__(self, db_host="localhost", db_port=5432, db_user="sep", db_pass="123456", db_name="campus_security"):
        self.db_params = {
            "host": db_host,
            "port": db_port,
            "user": db_user,
            "password": db_pass,
            "dbname": db_name
        }
        self.embedder = FaceEmbedder(embedding_dim=512)
        self.cached_faces: List[Dict] = []
        self.last_cache_time = 0
        self.cache_ttl = 30.0 # Tự động làm mới cache mỗi 30 giây
        self.refresh_cache()

    def _get_connection(self):
        return psycopg2.connect(**self.db_params)

    def refresh_cache(self):
        """Tải toàn bộ vector khuôn mặt từ PostgreSQL vào bộ nhớ RAM để tìm kiếm cực nhanh"""
        try:
            conn = self._get_connection()
            cur = conn.cursor()
            cur.execute("SELECT code, full_name, embedding_front::text FROM face_data;")
            rows = cur.fetchall()
            
            new_cache = []
            for code, full_name, emb_str in rows:
                if emb_str:
                    clean_str = emb_str.strip("[]")
                    vec = np.array([float(x) for x in clean_str.split(",")], dtype=np.float32)
                    norm = np.linalg.norm(vec)
                    if norm > 1e-6:
                        vec = vec / norm
                    new_cache.append({
                        "code": code,
                        "full_name": full_name,
                        "vector": vec
                    })
            
            self.cached_faces = new_cache
            self.last_cache_time = time.time()
            logger.info(f"✅ [FaceMatcher] Đã nạp {len(self.cached_faces)} hồ sơ khuôn mặt từ Database vào RAM cache.")
            cur.close()
            conn.close()
        except Exception as e:
            logger.warning(f"⚠️ [FaceMatcher] Không thể kết nối Database để nạp face cache: {e}")

    def match_face(self, face_crop: np.ndarray, threshold: float = 0.60) -> Optional[Tuple[str, str, float]]:
        """
        So khớp khuôn mặt cắt ra từ camera với Database:
        Trả về: (Mã SV/CB, Họ và Tên, Độ tương đồng Cosine Score) nếu score >= threshold.
        """
        if face_crop is None or face_crop.size == 0:
            return None

        # Trích xuất vector 512 chiều từ ảnh mặt camera
        vec_list = self.embedder.extract_embedding(face_crop)
        cam_vec = np.array(vec_list, dtype=np.float32)
        norm = np.linalg.norm(cam_vec)
        if norm < 1e-6:
            return None
        cam_vec = cam_vec / norm

        # Làm mới cache nếu quá hạn
        if time.time() - self.last_cache_time > self.cache_ttl:
            self.refresh_cache()

        if not self.cached_faces:
            return None

        best_score = -1.0
        best_match = None

        for item in self.cached_faces:
            # Tính Cosine Similarity: dot product giữa 2 unit vectors
            score = float(np.dot(cam_vec, item["vector"]))
            if score > best_score:
                best_score = score
                best_match = item

        if best_match and best_score >= threshold:
            logger.info(f"🎯 [FaceMatcher] NHẬN DIỆN KHUÔN MẶT: {best_match['code']} - {best_match['full_name']} (Khớp: {best_score:.1%})")
            return (best_match["code"], best_match["full_name"], best_score)

        return None
