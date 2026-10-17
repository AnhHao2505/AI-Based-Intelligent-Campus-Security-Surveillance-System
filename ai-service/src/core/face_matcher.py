import logging
import time
from typing import Optional, Tuple, Dict, List
import numpy as np
import psycopg2

from .face_embedder import FaceEmbedder
from ..config import settings, INTERNAL_SERVICE_KEY_HEADER

logger = logging.getLogger(__name__)

class FaceMatcher:
    """
    Module so khớp đặc trưng khuôn mặt (Face Recognition) trực tiếp với PostgreSQL pgvector.
    Có bộ đệm In-Memory Cache để tìm kiếm siêu tốc (Sub-millisecond).
    """
    def __init__(
        self,
        db_host: Optional[str] = None,
        db_port: Optional[int] = None,
        db_user: Optional[str] = None,
        db_pass: Optional[str] = None,
        db_name: Optional[str] = None
    ):
        self.db_params = {
            "host": db_host or settings.DB_HOST,
            "port": db_port or settings.DB_PORT,
            "user": db_user or settings.POSTGRES_USER,
            "password": db_pass or settings.POSTGRES_PASSWORD,
            "dbname": db_name or settings.POSTGRES_DB
        }
        self.embedder = FaceEmbedder(embedding_dim=512)
        self.cached_faces: List[Dict] = []
        self.public_camera_codes: set = set()
        self.last_cache_time = 0
        self.cache_ttl = 30.0 # Tự động làm mới cache mỗi 30 giây
        self.access_cache: Dict[str, Tuple[bool, str, Optional[str], float]] = {}
        self.access_cache_ttl = getattr(settings, "ACCESS_DECISION_CACHE_TTL", 30.0)
        self.refresh_cache()

    def _get_connection(self):
        return psycopg2.connect(**self.db_params)

    def refresh_cache(self):
        """Tải toàn bộ vector khuôn mặt từ PostgreSQL vào bộ nhớ RAM để tìm kiếm cực nhanh"""
        try:
            conn = self._get_connection()
            cur = conn.cursor()
            cur.execute("""
                SELECT fd.code, 
                       COALESCE(u.full_name, fd.code) AS full_name, 
                       fd.embedding_front::text,
                       COALESCE(u.is_active, true) AS is_active,
                       COALESCE(u.role, 'NORMAL_USER') AS role
                FROM face_data fd 
                JOIN users u ON (fd.user_id = u.id OR fd.code = u.user_code)
                WHERE u.deleted_at IS NULL
                UNION ALL
                SELECT g.id::text AS code,
                       (g.full_name || ' (Khách)') AS full_name,
                       gfe.embedding::text,
                       true AS is_active,
                       'GUEST' AS role
                FROM guest_face_embeddings gfe
                JOIN guests g ON g.id = gfe.guest_id
                JOIN guest_visits gv ON gv.id = g.visit_id
                WHERE gv.status = 'APPROVED'
                  AND g.biometric_status = 'PHOTO_READY'
                  AND gfe.expires_at > NOW();
            """)
            rows = cur.fetchall()
            
            new_cache = []
            for code, full_name, emb_str, is_active, role in rows:
                if emb_str:
                    clean_str = emb_str.strip("[]")
                    vec = np.array([float(x) for x in clean_str.split(",")], dtype=np.float32)
                    norm = np.linalg.norm(vec)
                    if norm > 1e-6:
                        vec = vec / norm
                    new_cache.append({
                        "code": code,
                        "full_name": full_name,
                        "vector": vec,
                        "is_active": bool(is_active),
                        "role": str(role)
                    })
            
            self.cached_faces = new_cache

            # Nạp danh sách mã camera thuộc khu vực Công cộng (Cấp 1 - PUBLIC)
            try:
                cur.execute("""
                    SELECT UPPER(c.camera_code)
                    FROM cameras c
                    JOIN areas a ON c.area_id = a.id
                    WHERE c.deleted_at IS NULL
                      AND (a.area_level = 'PUBLIC' OR a.area_access_level <= 1);
                """)
                self.public_camera_codes = {r[0] for r in cur.fetchall()}
            except Exception as e_cam:
                logger.debug(f"Không thể nạp danh sách camera công cộng: {e_cam}")

            self.last_cache_time = time.time()
            logger.info(f"✅ [FaceMatcher] Đã nạp {len(self.cached_faces)} hồ sơ khuôn mặt và {len(self.public_camera_codes)} camera công cộng vào RAM cache.")
            cur.close()
            conn.close()
        except Exception as e:
            logger.warning(f"⚠️ [FaceMatcher] Không thể kết nối Database để nạp face cache: {e}")
            self.last_cache_time = time.time()

    def is_camera_public(self, camera_code: str) -> bool:
        """Kiểm tra camera có thuộc khu vực Cấp 1 (Công cộng - PUBLIC) hay không"""
        if not camera_code:
            return False
        if time.time() - self.last_cache_time > self.cache_ttl:
            self.refresh_cache()
        return camera_code.strip().upper() in getattr(self, "public_camera_codes", set())

    def match_face(
        self,
        face_crop: np.ndarray,
        threshold: Optional[float] = None,
        landmarks: Optional[List[Tuple[float, float]]] = None
    ) -> Optional[Tuple[str, str, float, bool]]:
        """
        So khớp khuôn mặt cắt ra từ camera với Database:
        Trả về: (Mã SV/CB, Họ và Tên, Độ tương đồng Cosine Score, Trạng thái hoạt động is_active) nếu score >= threshold.
        """
        if face_crop is None or face_crop.size == 0:
            return None

        if threshold is None:
            threshold = settings.FACE_MATCH_THRESHOLD

        # Trích xuất vector 512 chiều từ ảnh mặt camera qua ArcFace ONNX (kèm 5 điểm mốc căn chỉnh)
        vec_list = self.embedder.extract_embedding(face_crop, landmarks=landmarks)
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
            is_active = bool(best_match.get("is_active", True))
            role = str(best_match.get("role", "NORMAL_USER"))
            logger.info(f"🎯 [FaceMatcher] NHẬN DIỆN KHUÔN MẶT: {best_match['code']} - {best_match['full_name']} (Khớp: {best_score:.1%}, Hoạt động: {is_active}, Role: {role})")
            return (best_match["code"], best_match["full_name"], best_score, is_active, role)

        return None

    def check_camera_access(self, camera_code: str, user_code: str) -> Tuple[bool, str, Optional[str]]:
        """
        Kiểm tra quyền ra vào của user_code tại vị trí camera_code:
        Gọi API POST /api/access-control/check-entry của Spring Boot Backend (checkEntry).
        Có bộ đệm in-memory RAM (TTL 30s) để đảm bảo tốc độ Real-time 30+ FPS cho luồng video.
        Trả về: (is_allowed: bool, reason: str, area_name: Optional[str])
        """
        if not camera_code or not user_code:
            return False, "Thiếu thông tin camera hoặc người dùng", None

        cache_key = f"{camera_code}:{user_code}"
        now = time.time()

        # 1. Kiểm tra cache RAM
        if cache_key in self.access_cache:
            allowed, reason, area_name, expire_at = self.access_cache[cache_key]
            if now < expire_at:
                return allowed, reason, area_name

        # 2. Gọi API Backend Spring Boot
        backend_url = getattr(settings, "BACKEND_API_URL", "http://localhost:8080")
        endpoint = f"{backend_url.rstrip('/')}/api/access-control/check-entry"

        try:
            import requests
            resp = requests.post(
                endpoint,
                json={"cameraCode": camera_code, "userCode": user_code},
                headers={INTERNAL_SERVICE_KEY_HEADER: settings.INTERNAL_SERVICE_KEY},
                timeout=1.5
            )
            if resp.status_code == 200:
                res_json = resp.json()
                data = res_json.get("data", {})
                allowed = bool(data.get("allowed", False))
                reason = data.get("reason", "Xét duyệt từ Backend")
                area_name = data.get("areaName")
                ttl = self.access_cache_ttl if allowed else min(self.access_cache_ttl, 5.0)
                self.access_cache[cache_key] = (allowed, reason, area_name, now + ttl)
                logger.info(f"🔑 [FaceMatcher] Quyền ra vào [{user_code}] tại [{camera_code}] ({area_name or 'N/A'}): {'HỢP LỆ' if allowed else 'TỪ CHỐI'} ({reason})")
                return allowed, reason, area_name
            else:
                logger.warning(f"⚠️ [FaceMatcher] Backend trả về status {resp.status_code} khi checkEntry cho {user_code}")
        except Exception as e:
            logger.warning(f"⚠️ [FaceMatcher] Không thể kết nối Backend để xét checkEntry cho {user_code}: {e}")

        # 3. Fallback Offline: Luôn Fail-closed để đảm bảo an ninh (không tự ý cho phép vào khu vực bảo mật)
        return False, "Không thể kết nối máy chủ xét quyền (Fail-closed)", None
