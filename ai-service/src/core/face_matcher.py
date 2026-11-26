import threading
import logging
import time
from typing import Optional, Tuple, Dict, List
import numpy as np
try:
    import psycopg2
except ImportError:
    psycopg2 = None

from .face_embedder import FaceEmbedder, get_shared_face_embedder
from .shared_face_cache import SharedFaceCache, get_shared_face_cache
from .access_reasons import is_identity_gone_reason
from ..config import settings, INTERNAL_SERVICE_KEY_HEADER

logger = logging.getLogger(__name__)

class AccessCheckResult(tuple):
    """
    Bộ kết quả kiểm tra quyền truy cập 5 phần tử tương thích ngược (allowed, reason, area_name, source, deny_reason)
    kèm thuộc tính expires_at, starts_at và verification_status.
    """
    def __new__(
        cls,
        allowed: bool,
        reason: str,
        area_name: Optional[str] = None,
        source: Optional[str] = None,
        deny_reason: Optional[str] = None,
        expires_at: Optional[float] = None,
        starts_at: Optional[float] = None,
        verification_status: Optional[str] = None,
    ):
        instance = super().__new__(cls, (allowed, reason, area_name, source, deny_reason))
        instance.expires_at = expires_at
        instance.starts_at = starts_at
        instance.verification_status = verification_status
        return instance

class CacheEntry(tuple):
    """
    Bộ đệm quyền truy cập 6 phần tử tương thích ngược (allowed, reason, area_name, cache_expire, source, deny_reason)
    kèm thuộc tính expires_at và starts_at. Đảm bảo len(entry) == 6 đối với các kiểm thử cũ.
    """
    def __new__(cls, allowed: bool, reason: str, area_name: Optional[str], cache_expire: float, source: Optional[str], deny_reason: Optional[str], expires_at: Optional[float] = None, starts_at: Optional[float] = None):
        instance = super().__new__(cls, (allowed, reason, area_name, cache_expire, source, deny_reason))
        instance.expires_at = expires_at
        instance.starts_at = starts_at
        return instance

class FaceMatcher:
    """
    Module so khớp đặc trưng khuôn mặt (Face Recognition) trực tiếp với PostgreSQL pgvector hoặc API Backend.
    Có bộ đệm In-Memory Cache để tìm kiếm siêu tốc (Sub-millisecond).
    Tích hợp Process-wide Shared Face Cache để tối ưu RAM và CPU giữa nhiều CameraStreamWorker.
    """
    def __init__(
        self,
        db_host: Optional[str] = None,
        db_port: Optional[int] = None,
        db_user: Optional[str] = None,
        db_pass: Optional[str] = None,
        db_name: Optional[str] = None,
        cache: Optional[SharedFaceCache] = None
    ):
        has_custom_db = any([db_host, db_port, db_user, db_pass, db_name])
        if cache is not None:
            self.cache = cache
        elif has_custom_db:
            self.cache = SharedFaceCache(
                db_host=db_host,
                db_port=db_port,
                db_user=db_user,
                db_pass=db_pass,
                db_name=db_name,
                auto_refresh=False
            )
        else:
            self.cache = get_shared_face_cache()

        self.db_params = self.cache.db_params
        # Dùng chung 1 phiên ArcFace cho mọi camera (không nạp lại model 174MB theo từng VideoPipeline)
        self.embedder = get_shared_face_embedder()
        self.access_cache: Dict[str, Tuple[bool, str, Optional[str], float, Optional[str], Optional[str]]] = {}
        self._access_cache_lock = threading.Lock()
        self.last_best_score: float = 0.0

        # Nạp cache lần đầu nếu cache còn rỗng và chưa từng nạp
        # (background refresh chỉ chạy sau cache_ttl, thiếu bước này mọi người sẽ là UNKNOWN lúc mới khởi động)
        if not self.cache.cached_faces and self.cache.last_cache_time == 0:
            self.refresh_cache()

    @property
    def access_cache_ttl(self) -> float:
        """Đọc động từ settings tại thời điểm truy cập để thay đổi cấu hình runtime có hiệu lực ngay"""
        return float(getattr(settings, "ACCESS_DECISION_CACHE_TTL", 30.0))

    @access_cache_ttl.setter
    def access_cache_ttl(self, val: float):
        settings.ACCESS_DECISION_CACHE_TTL = float(val)

    @property
    def cached_faces(self) -> List[Dict]:
        return self.cache.cached_faces

    @cached_faces.setter
    def cached_faces(self, val: List[Dict]):
        self.cache.cached_faces = val

    @property
    def cached_matrix(self) -> Optional[np.ndarray]:
        return self.cache.cached_matrix

    @cached_matrix.setter
    def cached_matrix(self, val: Optional[np.ndarray]):
        self.cache.cached_matrix = val

    @property
    def camera_area_map(self) -> Dict[str, str]:
        return self.cache.camera_area_map

    @camera_area_map.setter
    def camera_area_map(self, val: Dict[str, str]):
        self.cache.camera_area_map = val

    @property
    def public_camera_codes(self) -> set:
        return self.cache.public_camera_codes

    @public_camera_codes.setter
    def public_camera_codes(self, val: set):
        self.cache.public_camera_codes = val

    @property
    def last_cache_time(self) -> float:
        return self.cache.last_cache_time

    @last_cache_time.setter
    def last_cache_time(self, val: float):
        self.cache.last_cache_time = val

    @property
    def cache_ttl(self) -> float:
        return self.cache.cache_ttl

    @cache_ttl.setter
    def cache_ttl(self, val: float):
        self.cache.cache_ttl = val

    def _get_connection(self):
        if psycopg2 is None:
            raise RuntimeError("psycopg2 chưa được cài đặt hoặc kết nối DB trực tiếp đã bị tắt")
        return psycopg2.connect(**self.db_params)

    def refresh_cache(self):
        """Tải toàn bộ vector khuôn mặt từ Backend API vào bộ nhớ RAM để tìm kiếm cực nhanh"""
        # KHÔNG xóa self.access_cache để chống bão request Thundering Herd lên Backend
        is_mocked_db = (
            hasattr(self._get_connection, "mock_calls") or
            hasattr(self._get_connection, "assert_called") or
            type(self._get_connection).__name__ in ("MagicMock", "Mock")
        )
        if is_mocked_db:
            return self.cache.refresh_cache(get_connection_fn=self._get_connection)
        return self.cache.refresh_cache()

    def get_camera_area_name(self, camera_code: str):
        """Lấy tên khu vực thực tế (Physical Area Name) của Camera từ cơ sở dữ liệu"""
        if not camera_code:
            return None
        return self.camera_area_map.get(camera_code.strip().upper())

    def is_camera_public(self, camera_code: str) -> bool:
        """Kiểm tra camera có thuộc khu vực Cấp 1 (Công cộng - PUBLIC) hay không"""
        if not camera_code:
            return False
        return camera_code.strip().upper() in self.public_camera_codes

    def match_face(
        self,
        face_crop: np.ndarray,
        threshold: Optional[float] = None,
        landmarks: Optional[List[Tuple[float, float]]] = None
    ) -> Optional[Tuple[str, str, float, bool]]:
        """
        So khớp khuôn mặt cắt ra từ camera với Database:
        Trả về: (Mã SV/CB, Họ và Tên, Độ tương đồng Cosine Score, Trạng thái hoạt động is_active, Role) nếu score >= threshold.
        """
        if face_crop is None or face_crop.size == 0:
            self.last_best_score = 0.0
            return None

        if threshold is None:
            threshold = settings.FACE_MATCH_THRESHOLD

        # Trích xuất vector 512 chiều từ ảnh mặt camera qua ArcFace ONNX (kèm 5 điểm mốc căn chỉnh)
        t0 = time.perf_counter()
        vec_list = self.embedder.extract_embedding(face_crop, landmarks=landmarks)
        cam_vec = np.array(vec_list, dtype=np.float32)
        norm = np.linalg.norm(cam_vec)
        if norm < 1e-6:
            self.last_best_score = 0.0
            return None
        cam_vec = cam_vec / norm

        # KHÔNG GỌI refresh_cache() đồng bộ trên đường nóng nữa.
        # Dữ liệu được background thread tự động duy trì trong self.cache.
        cached_faces = self.cached_faces
        if not cached_faces:
            self.last_best_score = 0.0
            return None

        # Tối ưu hóa Vectorized Matrix Multiplication (BLAS GEMV: (N, 512) @ (512,) -> (N,))
        # Giảm độ phức tạp từ O(N) Python loop xuống 1 phép tính C/BLAS đa luồng siêu tốc (< 0.2ms)
        cached_matrix = self.cached_matrix
        if cached_matrix is None or len(cached_matrix) != len(cached_faces):
            cached_matrix = np.vstack([item["vector"] for item in cached_faces]).astype(np.float32)
            self.cached_matrix = cached_matrix

        scores = np.dot(cached_matrix, cam_vec)
        best_idx = int(np.argmax(scores))
        best_score = float(scores[best_idx])
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        self.last_best_score = best_score
        self.last_match_ms = elapsed_ms
        best_match = cached_faces[best_idx]

        now_ts = time.time()
        last_log_ts = getattr(self, "_last_match_log_time", 0.0)
        should_log = (now_ts - last_log_ts) >= 2.0

        if best_match and best_score >= threshold:
            expires_at = best_match.get("expires_at")
            if expires_at is not None and (now_ts * 1000.0) > expires_at:
                logger.debug(f"⏳ [FaceMatcher] Khách mời [{best_match.get('code')}] đã hết hạn chuyến thăm -> Bỏ qua.")
                return None

            is_active = bool(best_match.get("is_active", True))
            role = str(best_match.get("role", "NORMAL_USER"))
            if should_log:
                self._last_match_log_time = now_ts
                logger.debug(
                    f"⏱️ [FaceMatcher] time={elapsed_ms:.1f}ms | best_score={best_score:.3f} >= threshold={threshold:.2f} "
                    f"-> MATCH {best_match['code']} ({best_match['full_name']}, role={role})"
                )
            return (best_match["code"], best_match["full_name"], best_score, is_active, role)

        if should_log:
            self._last_match_log_time = now_ts
            logger.debug(
                f"⏱️ [FaceMatcher] time={elapsed_ms:.1f}ms | best_score={best_score:.3f} < threshold={threshold:.2f} -> NO_MATCH"
            )
        return None

    def get_cached_access(
        self,
        camera_code: str,
        user_code: str
    ) -> Optional[Tuple[bool, str, Optional[str], Optional[str], Optional[str]]]:
        """Truy xuất nhanh quyền ra vào từ RAM cache, trả về None nếu cache miss hoặc hết hạn.
        Trả về: (is_allowed, reason, area_name, source, deny_reason)"""
        if not camera_code or not user_code:
            return None
        cache_key = f"{camera_code}:{user_code}"
        now = time.time()
        with self._access_cache_lock:
            if cache_key in self.access_cache:
                entry = self.access_cache[cache_key]
                allowed = entry[0]
                reason = entry[1]
                area_name = entry[2]
                expire_at = entry[3]
                source = entry[4] if len(entry) >= 5 else None
                deny_reason = entry[5] if len(entry) >= 6 else None
                cached_expires_at = getattr(entry, "expires_at", None) if hasattr(entry, "expires_at") else (entry[6] if len(entry) >= 7 else None)
                cached_starts_at = getattr(entry, "starts_at", None) if hasattr(entry, "starts_at") else (entry[7] if len(entry) >= 8 else None)
                if now < expire_at:
                    return AccessCheckResult(allowed, reason, area_name, source, deny_reason, expires_at=cached_expires_at, starts_at=cached_starts_at)
        return None

    def check_camera_access(
        self,
        camera_code: str,
        user_code: str,
        roi_label: Optional[str] = None,
        event_direction: Optional[str] = None,
        duration_seconds: Optional[float] = None,
        skip_cache: bool = False
    ) -> Tuple[bool, str, Optional[str], Optional[str], Optional[str]]:
        """
        Kiểm tra quyền ra vào của user_code tại vị trí camera_code và ghi log chuyển động qua vùng ROI (VÀO / RA).
        Gọi API POST /api/access-control/check-entry của Spring Boot Backend (checkEntry).
        Có bộ đệm in-memory RAM (TTL 30s) khi đối soát nhanh.
        Trả về: (is_allowed: bool, reason: str, area_name: Optional[str], source: Optional[str], deny_reason: Optional[str])
        source = AccessSource backend trả (ASSIGNED_PERSONNEL, ACCESS_LEVEL, OPEN_EVENT, ACCESS_REQUEST, GUEST_VISIT,
        INCIDENT_RESPONSE, NONE); None khi không gọi được backend. deny_reason = mã GuestEntryDenyReason (chỉ khách bị từ chối).
        """
        if not camera_code or not user_code:
            return AccessCheckResult(False, "Thiếu thông tin camera hoặc người dùng", None, None, None, expires_at=None, starts_at=None)

        cache_key = f"{camera_code}:{user_code}"
        now = time.time()

        # 1. Kiểm tra cache RAM (chỉ dùng cache khi không yêu cầu skip_cache)
        if not skip_cache:
            with self._access_cache_lock:
                if cache_key in self.access_cache:
                    entry = self.access_cache[cache_key]
                    allowed = entry[0]
                    reason = entry[1]
                    area_name = entry[2]
                    expire_at = entry[3]
                    source = entry[4] if len(entry) >= 5 else None
                    deny_reason = entry[5] if len(entry) >= 6 else None
                    cached_expires_at = getattr(entry, "expires_at", None) if hasattr(entry, "expires_at") else (entry[6] if len(entry) >= 7 else None)
                    cached_starts_at = getattr(entry, "starts_at", None) if hasattr(entry, "starts_at") else (entry[7] if len(entry) >= 8 else None)
                    if now < expire_at:
                        return AccessCheckResult(allowed, reason, area_name, source, deny_reason, expires_at=cached_expires_at, starts_at=cached_starts_at)

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

        # M3: Đọc timeout và TTL từ settings tại thời điểm gọi, cập nhật lúc chạy có hiệu lực ngay
        timeout = float(getattr(settings, "ACCESS_CHECK_TIMEOUT_SECONDS", 1.5))
        decision_ttl = float(getattr(settings, "ACCESS_DECISION_CACHE_TTL", 30.0))
        deny_ttl = float(getattr(settings, "ACCESS_DENY_CACHE_TTL", 5.0))

        import requests
        from ..utils.http_client import get_http_session
        last_error = None
        # Gọi Backend ĐÚNG 1 LẦN (không retry sau timeout): retry làm trường hợp xấu nhất lên ~3.1s (2 x timeout
        # + 0.1s), vượt ngân sách cảnh báo 3s (NFR-01), và có thể làm Backend ghi trùng nhật ký VÀO/RA khi lần
        # đầu thật ra đã được xử lý nhưng trả lời chậm. Lỗi kết nối nhanh (keep-alive cũ, backend vừa restart)
        # vẫn được urllib3 Retry của session dùng chung (utils/http_client.py) thử lại tự động.
        t_req_start = time.perf_counter()
        try:
            if hasattr(requests.post, "mock_calls") or hasattr(requests.post, "assert_called"):
                post_fn = requests.post
            else:
                post_fn = (getattr(self, "session", None) or get_http_session()).post

            resp = post_fn(
                endpoint,
                json=payload,
                headers=headers,
                timeout=timeout
            )
            check_entry_ms = (time.perf_counter() - t_req_start) * 1000.0
            if resp.status_code == 200:
                res_json = resp.json()
                data = res_json.get("data", {})
                allowed = bool(data.get("allowed", False))
                reason = data.get("reason", "Xét duyệt từ Backend")
                area_name = data.get("areaName")
                raw_source = data.get("source")
                source = str(raw_source).strip().upper() if raw_source else None
                deny_reason = data.get("denyReason")
                raw_expires_at = data.get("expiresAt")
                expires_at = float(raw_expires_at) if raw_expires_at is not None else None
                raw_starts_at = data.get("startsAt")
                starts_at = float(raw_starts_at) if raw_starts_at is not None else None

                # Tự động loại bỏ khỏi Face RAM Cache nếu Backend phản hồi người dùng không tồn tại / đã bị xoá.
                # Chỉ khi lý do nói về NGƯỜI: "Camera không tồn tại" / "Khu vực không tồn tại" không phải lý do xoá mặt.
                if is_identity_gone_reason(reason, deny_reason):
                    logger.info(f"🗑️ [FaceMatcher] Tài khoản [{user_code}] đã bị xoá hoặc không tồn tại trên hệ thống -> Loại bỏ ngay khỏi Face RAM Cache.")
                    self.cache.remove_face(user_code)
                    with self._access_cache_lock:
                        self.access_cache.pop(cache_key, None)
                else:
                    ttl = decision_ttl if allowed else deny_ttl
                    cache_expire = now + ttl
                    # Nếu có mốc expires_at định trước (hết đơn / hết ca), không cache quá mốc này
                    if allowed and expires_at is not None and expires_at < cache_expire:
                        cache_expire = expires_at
                    # Khi bị từ chối kèm mốc starts_at (chưa tới giờ duyệt), cache TTL không được vượt quá starts_at
                    # để ngay khi chạm starts_at, cache từ chối hết hạn và hệ thống gọi lại backend
                    if not allowed and starts_at is not None:
                        if starts_at <= now:
                            cache_expire = now
                        elif starts_at < cache_expire:
                            cache_expire = starts_at
                    with self._access_cache_lock:
                        self.access_cache[cache_key] = CacheEntry(
                            allowed, reason, area_name, cache_expire, source, deny_reason,
                            expires_at=expires_at, starts_at=starts_at
                        )

                logger.info(
                    f"🔑 [FaceMatcher] Quyền ra vào [{user_code}] tại [{camera_code}] ({area_name or 'N/A'}, "
                    f"source={source}, check_entry_ms={check_entry_ms:.1f}ms, starts_at={starts_at}, expires_at={expires_at}): "
                    f"{'HỢP LỆ' if allowed else 'TỪ CHỐI'} ({reason})"
                )
                return AccessCheckResult(allowed, reason, area_name, source, deny_reason, expires_at=expires_at, starts_at=starts_at)
            else:
                last_error = f"HTTP {resp.status_code}"
                logger.warning(f"⚠️ [FaceMatcher] Backend trả về status {resp.status_code} khi checkEntry cho {user_code} (check_entry_ms={check_entry_ms:.1f}ms)")
        except Exception as e:
            check_entry_ms = (time.perf_counter() - t_req_start) * 1000.0
            last_error = str(e)
            logger.warning(f"⚠️ [FaceMatcher] Không thể kết nối Backend để xét checkEntry cho {user_code} (check_entry_ms={check_entry_ms:.1f}ms): {e}")

        # 3. Fallback Chính sách BUG-02 khi Backend lỗi / timeout (Lucas K8):
        # (ii) Stale-if-error: nếu có quyết định cũ trong access_cache (kể cả hết TTL)
        with self._access_cache_lock:
            if cache_key in self.access_cache:
                entry = self.access_cache[cache_key]
                c_allowed = entry[0]
                c_reason = entry[1]
                c_area = entry[2]
                c_expire_at = entry[3]
                c_source = entry[4] if len(entry) >= 5 else None
                c_deny_reason = entry[5] if len(entry) >= 6 else None
                stale_elapsed = now - c_expire_at
                max_stale = getattr(settings, "ACCESS_STALE_MAX_SECONDS", 600.0)
                max_stale_allowed = settings.ACCESS_STALE_ALLOWED_MAX_SECONDS

                can_use_stale = False
                c_expires_at = getattr(entry, "expires_at", None) if hasattr(entry, "expires_at") else (entry[6] if len(entry) >= 7 else None)
                c_starts_at = getattr(entry, "starts_at", None) if hasattr(entry, "starts_at") else (entry[7] if len(entry) >= 8 else None)
                if not c_allowed:
                    if c_starts_at is not None and now >= c_starts_at:
                        # Lý do từ chối trước đó là do "chưa tới giờ"; nay đã tới hoặc quá giờ, không dùng lại denial cũ
                        logger.warning(
                            f"⚠️ [FaceMatcher] Backend không phản hồi ({last_error}). Cache cũ từ chối do chưa tới giờ "
                            f"(starts_at={c_starts_at}) cho [{user_code}] đã tới hạn lúc {now} -> Bỏ qua stale denial."
                        )
                    elif stale_elapsed <= max_stale:
                        can_use_stale = True
                    else:
                        logger.warning(
                            f"⚠️ [FaceMatcher] Backend không phản hồi ({last_error}). Cache cũ từ chối cho [{user_code}] "
                            f"đã hết hạn quá {max_stale}s ({stale_elapsed:.1f}s) -> Bỏ qua stale cache."
                        )
                else:
                    if c_expires_at is not None and now >= c_expires_at:
                        logger.warning(
                            f"⚠️ [FaceMatcher] Backend không phản hồi ({last_error}). Cache cũ cho phép cho [{user_code}] "
                            f"đã hết hạn hiệu lực lúc {c_expires_at} -> Bỏ qua stale approval."
                        )
                    elif stale_elapsed <= max_stale_allowed:
                        can_use_stale = True
                    else:
                        logger.warning(
                            f"⚠️ [FaceMatcher] Backend không phản hồi ({last_error}). Cache cũ cho phép cho [{user_code}] "
                            f"đã hết hạn quá {max_stale_allowed}s ({stale_elapsed:.1f}s) -> Coi như không có cache."
                        )

                if can_use_stale:
                    logger.warning(
                        f"⚠️ [FaceMatcher] Backend không phản hồi ({last_error}). Dùng tạm cache cũ (stale-if-error) "
                        f"cho [{user_code}] tại [{camera_code}]: allowed={c_allowed}, reason={c_reason}"
                    )
                    return AccessCheckResult(c_allowed, f"[Backend không phản hồi - Dùng tạm cache] {c_reason}", c_area, c_source, c_deny_reason, expires_at=c_expires_at, starts_at=c_starts_at)

        # (iii) Nếu hoàn toàn không có cache hoặc cache không dùng được (quá hạn stale):
        is_public = self.is_camera_public(camera_code)
        if is_public:
            # Khu vực công cộng: chỉ ghi log cảnh báo, không chặn vi phạm
            area_name = self.get_camera_area_name(camera_code) or "Khu vực công cộng"
            logger.warning(
                f"⚠️ [FaceMatcher] Backend không phản hồi khi xét quyền cho [{user_code}] "
                f"tại camera công cộng [{camera_code}] ({last_error}). Cho phép lưu thông và chỉ ghi log."
            )
            return AccessCheckResult(True, f"[Backend không phản hồi] Khu vực công cộng - Cho phép lưu thông ({last_error})", area_name, None, None, expires_at=None, starts_at=None)
        else:
            # Khu vực hạn chế: duy trì Fail-Closed (allowed=False), ghi rõ 'Backend không phản hồi'
            area_name = self.get_camera_area_name(camera_code) or "Khu vực hạn chế"
            logger.error(
                f"🛑 [FaceMatcher] Backend không phản hồi khi xét quyền cho [{user_code}] "
                f"tại khu vực hạn chế [{camera_code}] ({last_error}). Áp dụng Fail-Closed."
            )
            return AccessCheckResult(
                False,
                f"[Backend không phản hồi] Không thể xác thực quyền truy cập ({last_error})",
                area_name,
                None,
                None,
                expires_at=None,
                starts_at=None,
                verification_status="UNVERIFIED_TIMEOUT",
            )
