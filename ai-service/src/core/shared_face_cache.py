import logging
import threading
import time
from dataclasses import dataclass
from typing import Optional, List, Dict, Set, Callable, Any
import numpy as np
import psycopg2

from ..config import settings

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class FaceCacheSnapshot:
    cached_faces: List[Dict[str, Any]]
    cached_matrix: Optional[np.ndarray]
    camera_area_map: Dict[str, str]
    public_camera_codes: Set[str]
    last_cache_time: float


class SharedFaceCache:
    """
    Process-wide Shared Face Cache:
    Quản lý bộ nhớ đệm khuôn mặt và thông tin camera dùng chung cho toàn bộ tiến trình.
    - Duy trì cập nhật bất đồng bộ ở background thread (không block đường nóng).
    - Hoán đổi con trỏ nguyên tử (Atomic snapshot swap).
    - Giữ cache cũ khi DB gặp sự cố và áp dụng exponential backoff (2s -> 4s -> ... -> 60s).
    - Tối ưu hóa parse vector chuỗi từ PostgreSQL bằng np.fromstring.
    """
    def __init__(
        self,
        db_host: Optional[str] = None,
        db_port: Optional[int] = None,
        db_user: Optional[str] = None,
        db_pass: Optional[str] = None,
        db_name: Optional[str] = None,
        cache_ttl: float = 30.0,
        auto_refresh: bool = False
    ):
        self.db_params = {
            "host": db_host or settings.DB_HOST,
            "port": db_port or settings.DB_PORT,
            "user": db_user or settings.POSTGRES_USER,
            "password": db_pass or settings.POSTGRES_PASSWORD,
            "dbname": db_name or settings.POSTGRES_DB
        }
        self.cache_ttl = cache_ttl
        self._initial_backoff = 2.0
        self._max_backoff = 60.0
        self._current_backoff = 2.0
        self._consecutive_failures = 0

        self._snapshot_lock = threading.Lock()
        self._snapshot = FaceCacheSnapshot(
            cached_faces=[],
            cached_matrix=None,
            camera_area_map={},
            public_camera_codes=set(),
            last_cache_time=0.0
        )

        self._stop_event = threading.Event()
        self._refresh_event = threading.Event()
        self._bg_thread: Optional[threading.Thread] = None

        if auto_refresh:
            self.start_background_refresh()

    def _get_connection(self):
        return psycopg2.connect(**self.db_params)

    @property
    def snapshot(self) -> FaceCacheSnapshot:
        return self._snapshot

    @property
    def cached_faces(self) -> List[Dict[str, Any]]:
        return self._snapshot.cached_faces

    @cached_faces.setter
    def cached_faces(self, val: List[Dict[str, Any]]):
        with self._snapshot_lock:
            cur = self._snapshot
            matrix = None
            if val:
                try:
                    matrix = np.vstack([item["vector"] for item in val]).astype(np.float32)
                except Exception:
                    matrix = None
            self._snapshot = FaceCacheSnapshot(
                cached_faces=list(val),
                cached_matrix=matrix,
                camera_area_map=cur.camera_area_map,
                public_camera_codes=cur.public_camera_codes,
                last_cache_time=cur.last_cache_time
            )

    @property
    def cached_matrix(self) -> Optional[np.ndarray]:
        return self._snapshot.cached_matrix

    @cached_matrix.setter
    def cached_matrix(self, val: Optional[np.ndarray]):
        with self._snapshot_lock:
            cur = self._snapshot
            self._snapshot = FaceCacheSnapshot(
                cached_faces=cur.cached_faces,
                cached_matrix=val,
                camera_area_map=cur.camera_area_map,
                public_camera_codes=cur.public_camera_codes,
                last_cache_time=cur.last_cache_time
            )

    @property
    def camera_area_map(self) -> Dict[str, str]:
        return self._snapshot.camera_area_map

    @camera_area_map.setter
    def camera_area_map(self, val: Dict[str, str]):
        with self._snapshot_lock:
            cur = self._snapshot
            self._snapshot = FaceCacheSnapshot(
                cached_faces=cur.cached_faces,
                cached_matrix=cur.cached_matrix,
                camera_area_map=dict(val),
                public_camera_codes=cur.public_camera_codes,
                last_cache_time=cur.last_cache_time
            )

    @property
    def public_camera_codes(self) -> Set[str]:
        return self._snapshot.public_camera_codes

    @public_camera_codes.setter
    def public_camera_codes(self, val: Set[str]):
        with self._snapshot_lock:
            cur = self._snapshot
            self._snapshot = FaceCacheSnapshot(
                cached_faces=cur.cached_faces,
                cached_matrix=cur.cached_matrix,
                camera_area_map=cur.camera_area_map,
                public_camera_codes=set(val),
                last_cache_time=cur.last_cache_time
            )

    @property
    def last_cache_time(self) -> float:
        return self._snapshot.last_cache_time

    @last_cache_time.setter
    def last_cache_time(self, val: float):
        with self._snapshot_lock:
            cur = self._snapshot
            self._snapshot = FaceCacheSnapshot(
                cached_faces=cur.cached_faces,
                cached_matrix=cur.cached_matrix,
                camera_area_map=cur.camera_area_map,
                public_camera_codes=cur.public_camera_codes,
                last_cache_time=val
            )

    def remove_face(self, user_code: str):
        """Xoá một tài khoản khỏi Face Cache nguyên tử khi bị xoá mềm hoặc không tồn tại"""
        with self._snapshot_lock:
            cur = self._snapshot
            new_faces = [f for f in cur.cached_faces if f.get("code") != user_code]
            new_matrix = None
            if new_faces:
                try:
                    new_matrix = np.vstack([item["vector"] for item in new_faces]).astype(np.float32)
                except Exception:
                    new_matrix = None
            self._snapshot = FaceCacheSnapshot(
                cached_faces=new_faces,
                cached_matrix=new_matrix,
                camera_area_map=cur.camera_area_map,
                public_camera_codes=cur.public_camera_codes,
                last_cache_time=cur.last_cache_time
            )

    def refresh_cache(self, get_connection_fn: Optional[Callable[[], Any]] = None) -> bool:
        """
        Tải toàn bộ vector khuôn mặt từ PostgreSQL vào RAM cache và hoán đổi nguyên tử.
        Trả về True nếu nạp thành công, False nếu thất bại (giữ nguyên cache cũ).
        """
        conn = None
        try:
            conn = get_connection_fn() if get_connection_fn else self._get_connection()
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
                    # Tối ưu hóa parse vector chuỗi bằng np.fromstring trực tiếp từ C/NumPy
                    vec = np.fromstring(clean_str, dtype=np.float32, sep=",")
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

            if new_cache:
                new_matrix = np.vstack([item["vector"] for item in new_cache]).astype(np.float32)
            else:
                new_matrix = None

            # Nạp danh sách camera và khu vực
            new_camera_area_map = {}
            try:
                cur.execute("""
                    SELECT UPPER(c.camera_code), a.name
                    FROM cameras c
                    JOIN areas a ON c.area_id = a.id
                    WHERE c.deleted_at IS NULL;
                """)
                area_rows = cur.fetchall()
                new_camera_area_map = {r[0]: r[1] for r in area_rows}
            except Exception as e_area:
                logger.debug(f"Không thể nạp danh sách tên khu vực của camera: {e_area}")

            new_public_camera_codes = set()
            try:
                cur.execute("""
                    SELECT UPPER(c.camera_code)
                    FROM cameras c
                    JOIN areas a ON c.area_id = a.id
                    WHERE c.deleted_at IS NULL
                      AND (a.area_level = 'PUBLIC' OR a.area_access_level <= 1);
                """)
                new_public_camera_codes = {r[0] for r in cur.fetchall()}
            except Exception as e_cam:
                logger.debug(f"Không thể nạp danh sách camera công cộng: {e_cam}")

            cur.close()

            # HOÁN ĐỔI NGUYÊN TỬ (Atomic Snapshot Swap)
            now = time.time()
            with self._snapshot_lock:
                self._snapshot = FaceCacheSnapshot(
                    cached_faces=new_cache,
                    cached_matrix=new_matrix,
                    camera_area_map=new_camera_area_map,
                    public_camera_codes=new_public_camera_codes,
                    last_cache_time=now
                )

            self._consecutive_failures = 0
            self._current_backoff = self._initial_backoff
            logger.info(f"✅ [SharedFaceCache] Đã nạp {len(new_cache)} hồ sơ khuôn mặt và {len(new_public_camera_codes)} camera công cộng vào RAM cache.")
            return True
        except Exception as e:
            self._consecutive_failures += 1
            self._current_backoff = min(self._max_backoff, self._current_backoff * 2.0)
            logger.warning(
                f"⚠️ [SharedFaceCache] Không thể kết nối Database để nạp face cache (lỗi lần {self._consecutive_failures}): {e}. "
                f"Giữ cache cũ trong RAM, thử lại sau {self._current_backoff:.1f}s."
            )
            return False
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass

    def trigger_immediate_refresh(self):
        """Kích hoạt làm mới cache ngay lập tức theo sự kiện (Event-driven refresh hook)"""
        self._refresh_event.set()

    def start_background_refresh(self):
        """Khởi động worker thread chạy nền định kỳ làm mới cache"""
        if self._bg_thread is not None and self._bg_thread.is_alive():
            return
        self._stop_event.clear()
        self._bg_thread = threading.Thread(
            target=self._background_refresh_loop,
            daemon=True,
            name="shared-face-cache-refresher"
        )
        self._bg_thread.start()
        logger.info("🚀 [SharedFaceCache] Đã khởi động background thread cập nhật Face Cache.")

    def stop_background_refresh(self, timeout: float = 2.0):
        """Dừng sạch sẽ background worker thread"""
        self._stop_event.set()
        self._refresh_event.set()
        if self._bg_thread is not None and self._bg_thread.is_alive():
            self._bg_thread.join(timeout=timeout)
            self._bg_thread = None
            logger.info("🛑 [SharedFaceCache] Đã dừng background thread Face Cache.")

    def _background_refresh_loop(self):
        """Vòng lặp chạy nền định kỳ làm mới cache hoặc chờ sự kiện đánh thức"""
        while not self._stop_event.is_set():
            delay = self._current_backoff if self._consecutive_failures > 0 else self.cache_ttl
            signaled = self._refresh_event.wait(timeout=delay)
            if self._stop_event.is_set():
                break
            if signaled:
                self._refresh_event.clear()

            self.refresh_cache()


_GLOBAL_SHARED_FACE_CACHE: Optional[SharedFaceCache] = None
_GLOBAL_CACHE_LOCK = threading.Lock()


def get_shared_face_cache() -> SharedFaceCache:
    """Lấy singleton instance SharedFaceCache dùng chung cho toàn bộ process"""
    global _GLOBAL_SHARED_FACE_CACHE
    with _GLOBAL_CACHE_LOCK:
        if _GLOBAL_SHARED_FACE_CACHE is None:
            _GLOBAL_SHARED_FACE_CACHE = SharedFaceCache(auto_refresh=False)
        return _GLOBAL_SHARED_FACE_CACHE


def reset_shared_face_cache():
    """Reset shared face cache (chủ yếu dùng cho test isolation)"""
    global _GLOBAL_SHARED_FACE_CACHE
    with _GLOBAL_CACHE_LOCK:
        if _GLOBAL_SHARED_FACE_CACHE is not None:
            _GLOBAL_SHARED_FACE_CACHE.stop_background_refresh()
            _GLOBAL_SHARED_FACE_CACHE = None
