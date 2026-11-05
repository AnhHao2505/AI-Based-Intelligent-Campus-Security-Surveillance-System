import threading
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
        self.cached_matrix: Optional[np.ndarray] = None
        self.public_camera_codes: set = set()
        self.last_cache_time = 0
        self.cache_ttl = 30.0 # Tự động làm mới cache mỗi 30 giây
        self.access_cache: Dict[str, Tuple[bool, str, Optional[str], float]] = {}
        self.access_cache_ttl = getattr(settings, "ACCESS_DECISION_CACHE_TTL", 30.0)
        self._access_cache_lock = threading.Lock()
        self.refresh_cache()

    def _get_connection(self):
        return psycopg2.connect(**self.db_params)

    def refresh_cache(self):
        """Tải toàn bộ vector khuôn mặt từ PostgreSQL vào bộ nhớ RAM để tìm kiếm cực nhanh"""
        with self._access_cache_lock:
            self.access_cache.clear()
        conn = None
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
            if new_cache:
                self.cached_matrix = np.vstack([item["vector"] for item in new_cache]).astype(np.float32)
            else:
                self.cached_matrix = None

            # Nạp danh sách mã camera thuộc khu vực Công cộng (Cấp 1 - PUBLIC) và tên khu vực tương ứng
            try:
                cur.execute("""
                    SELECT UPPER(c.camera_code), a.name
                    FROM cameras c
                    JOIN areas a ON c.area_id = a.id
                    WHERE c.deleted_at IS NULL;
                """)
                area_rows = cur.fetchall()
                self.camera_area_map = {r[0]: r[1] for r in area_rows}
            except Exception as e_area:
                logger.debug(f"Không thể nạp danh sách tên khu vực của camera: {e_area}")
                self.camera_area_map = {}

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
                self.public_camera_codes = set()

            self.last_cache_time = time.time()
            logger.info(f"✅ [FaceMatcher] Đã nạp {len(self.cached_faces)} hồ sơ khuôn mặt và {len(self.public_camera_codes)} camera công cộng vào RAM cache.")
            cur.close()
        except Exception as e:
            logger.warning(f"⚠️ [FaceMatcher] Không thể kết nối Database để nạp face cache: {e}")
            # BUG-03: KHÔNG cập nhật last_cache_time khi lỗi để tránh kẹt cache rỗng suốt 30s
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass

    def get_camera_area_name(self, camera_code: str):
        """Lấy tên khu vực thực tế (Physical Area Name) của Camera từ cơ sở dữ liệu"""
        if not camera_code:
            return None
        if time.time() - self.last_cache_time > self.cache_ttl:
            self.refresh_cache()
        return getattr(self, "camera_area_map", {}).get(camera_code.strip().upper())

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

        # Tối ưu hóa Vectorized Matrix Multiplication (BLAS GEMV: (N, 512) @ (512,) -> (N,))
        # Giảm độ phức tạp từ O(N) Python loop xuống 1 phép tính C/BLAS đa luồng siêu tốc (< 0.2ms)
        if self.cached_matrix is None or len(self.cached_matrix) != len(self.cached_faces):
            self.cached_matrix = np.vstack([item["vector"] for item in self.cached_faces]).astype(np.float32)

        scores = np.dot(self.cached_matrix, cam_vec)
        best_idx = int(np.argmax(scores))
        best_score = float(scores[best_idx])
        best_match = self.cached_faces[best_idx]

        if best_match and best_score >= threshold:
            is_active = bool(best_match.get("is_active", True))
            role = str(best_match.get("role", "NORMAL_USER"))
            logger.info(f"🎯 [FaceMatcher] NHẬN DIỆN KHUÔN MẶT: {best_match['code']} - {best_match['full_name']} (Khớp: {best_score:.1%}, Hoạt động: {is_active}, Role: {role})")
            return (best_match["code"], best_match["full_name"], best_score, is_active, role)

        return None

    def get_cached_access(
        self,
        camera_code: str,
        user_code: str
    ) -> Optional[Tuple[bool, str, Optional[str], Optional[str]]]:
        """Truy xuất nhanh quyền ra vào từ RAM cache, trả về None nếu cache miss hoặc hết hạn"""
        if not camera_code or not user_code:
            return None
        cache_key = f"{camera_code}:{user_code}"
        now = time.time()
        with self._access_cache_lock:
            if cache_key in self.access_cache:
                entry = self.access_cache[cache_key]
                if len(entry) >= 5:
                    allowed, reason, area_name, expire_at, source = entry[:5]
                else:
                    allowed, reason, area_name, expire_at = entry[:4]
                    source = None
                if now < expire_at:
                    return allowed, reason, area_name, source
        return None

    def check_camera_access(
        self,
        camera_code: str,
        user_code: str,
        roi_label: Optional[str] = None,
        event_direction: Optional[str] = None,
        duration_seconds: Optional[float] = None,
        skip_cache: bool = False
    ) -> Tuple[bool, str, Optional[str], Optional[str]]:
        """
        Kiểm tra quyền ra vào của user_code tại vị trí camera_code và ghi log chuyển động qua vùng ROI (VÀO / RA).
        Gọi API POST /api/access-control/check-entry của Spring Boot Backend (checkEntry).
        Có bộ đệm in-memory RAM (TTL 30s) khi đối soát nhanh.
        Trả về: (is_allowed: bool, reason: str, area_name: Optional[str])
        """
        if not camera_code or not user_code:
            return False, "Thiếu thông tin camera hoặc người dùng", None, None

        cache_key = f"{camera_code}:{user_code}"
        now = time.time()

        # 1. Kiểm tra cache RAM (chỉ dùng cache khi không yêu cầu skip_cache)
        if not skip_cache:
            with self._access_cache_lock:
                if cache_key in self.access_cache:
                    entry = self.access_cache[cache_key]
                    if len(entry) >= 5:
                        allowed, reason, area_name, expire_at, source = entry[:5]
                    else:
                        allowed, reason, area_name, expire_at = entry[:4]
                        source = None
                    if now < expire_at:
                        return allowed, reason, area_name, source

        # 2. Gọi API Backend Spring Boot (có retry 1 lần khi timeout/lỗi mạng)
        backend_url = getattr(settings, "BACKEND_API_URL", "http://localhost:8080")
        endpoint = f"{backend_url.rstrip('/')}/api/access-control/check-entry"
        headers = {INTERNAL_SERVICE_KEY_HEADER: settings.INTERNAL_SERVICE_KEY}
        payload = {
            "cameraCode": camera_code,
            "userCode": user_code
        }
        if roi_label:
            payload["roiLabel"] = roi_label
        if event_direction:
            payload["eventDirection"] = event_direction
        if duration_seconds is not None:
            payload["durationSeconds"] = round(duration_seconds, 1)

        import requests
        last_error = None
        for attempt in range(2):  # Thử lần đầu + retry đúng 1 lần
            try:
                resp = requests.post(
                    endpoint,
                    json=payload,
                    headers=headers,
                    timeout=1.5
                )
                if resp.status_code == 200:
                    res_json = resp.json()
                    data = res_json.get("data", {})
                    allowed = bool(data.get("allowed", False))
                    reason = data.get("reason", "Xét duyệt từ Backend")
                    area_name = data.get("areaName")
                    raw_source = data.get("source")
                    source = str(raw_source).strip().upper() if raw_source else None

                    # Tự động loại bỏ khỏi Face RAM Cache nếu Backend phản hồi người dùng không tồn tại / đã bị xoá
                    reason_lower = (reason or "").lower()
                    if "không tồn tại" in reason_lower or "đã bị xoá" in reason_lower:
                        logger.info(f"🗑️ [FaceMatcher] Tài khoản [{user_code}] đã bị xoá hoặc không tồn tại trên hệ thống -> Loại bỏ ngay khỏi Face RAM Cache.")
                        self.cached_faces = [f for f in self.cached_faces if f.get("code") != user_code]
                        if self.cached_faces:
                            self.cached_matrix = np.vstack([item["vector"] for item in self.cached_faces]).astype(np.float32)
                        else:
                            self.cached_matrix = None
                        with self._access_cache_lock:
                            self.access_cache.pop(cache_key, None)
                    else:
                        ttl = self.access_cache_ttl if allowed else min(self.access_cache_ttl, 5.0)
                        with self._access_cache_lock:
                            self.access_cache[cache_key] = (allowed, reason, area_name, now + ttl, source)

                    logger.info(f"🔑 [FaceMatcher] Quyền ra vào [{user_code}] tại [{camera_code}] ({area_name or 'N/A'}, source={source}): {'HỢP LỆ' if allowed else 'TỪ CHỐI'} ({reason})")
                    return allowed, reason, area_name, source
                else:
                    last_error = f"HTTP {resp.status_code}"
                    logger.warning(f"⚠️ [FaceMatcher] Backend trả về status {resp.status_code} khi checkEntry cho {user_code} (lần {attempt + 1})")
            except Exception as e:
                last_error = str(e)
                logger.warning(f"⚠️ [FaceMatcher] Không thể kết nối Backend để xét checkEntry cho {user_code} (lần {attempt + 1}): {e}")

            if attempt == 0:
                time.sleep(0.1)

        # 3. Fallback Chính sách BUG-02 khi Backend lỗi / timeout:
        # (ii) Stale-if-error: nếu có quyết định cũ trong access_cache (kể cả hết TTL) thì dùng tạm nếu chưa quá ACCESS_STALE_MAX_SECONDS
        with self._access_cache_lock:
            if cache_key in self.access_cache:
                entry = self.access_cache[cache_key]
                if len(entry) >= 5:
                    c_allowed, c_reason, c_area, c_expire_at, c_source = entry[:5]
                else:
                    c_allowed, c_reason, c_area, c_expire_at = entry[:4]
                    c_source = None

                max_stale = getattr(settings, "ACCESS_STALE_MAX_SECONDS", 600.0)
                if (now - c_expire_at) <= max_stale:
                    logger.warning(
                        f"⚠️ [FaceMatcher] Backend không phản hồi ({last_error}). Dùng tạm cache cũ (stale-if-error) "
                        f"cho [{user_code}] tại [{camera_code}]: allowed={c_allowed}, reason={c_reason}"
                    )
                    return c_allowed, f"[Backend không phản hồi - Dùng tạm cache] {c_reason}", c_area, c_source
                else:
                    logger.warning(
                        f"⚠️ [FaceMatcher] Backend không phản hồi ({last_error}). Cache cũ cho [{user_code}] "
                        f"đã hết hạn quá {max_stale}s ({now - c_expire_at:.1f}s) -> Bỏ qua stale cache."
                    )

        # (iii) Nếu hoàn toàn không có cache hoặc cache quá hạn stale:
        is_public = self.is_camera_public(camera_code)
        if is_public:
            # Khu vực công cộng: chỉ ghi log cảnh báo, không chặn vi phạm
            area_name = self.get_camera_area_name(camera_code) or "Khu vực công cộng"
            logger.warning(
                f"⚠️ [FaceMatcher] Backend không phản hồi khi xét quyền cho [{user_code}] "
                f"tại camera công cộng [{camera_code}] ({last_error}). Cho phép lưu thông và chỉ ghi log."
            )
            return True, f"[Backend không phản hồi] Khu vực công cộng - Cho phép lưu thông ({last_error})", area_name, None
        else:
            # Khu vực hạn chế: duy trì Fail-Closed (allowed=False), ghi rõ 'Backend không phản hồi'
            area_name = self.get_camera_area_name(camera_code) or "Khu vực hạn chế"
            logger.error(
                f"🛑 [FaceMatcher] Backend không phản hồi khi xét quyền cho [{user_code}] "
                f"tại khu vực hạn chế [{camera_code}] ({last_error}). Áp dụng Fail-Closed."
            )
            return False, f"[Backend không phản hồi] Không thể xác thực quyền truy cập ({last_error})", area_name, None
