"""
Đăng ký CameraStreamWorker: bảo đảm mỗi camera chỉ có ĐÚNG MỘT worker chạy.

Trước đây 4 nơi (EdgeAgent MQTT, /stream/start, /stream/live-annotated, vòng Auto-Record) tự "kiểm tra rồi tạo"
worker: kiểm tra active_workers trong lock, tạo + start() ngoài lock, rồi ghi đè active_workers[camera] mà không
dừng worker cũ. Hai nơi chạy chồng nhau -> cả hai cùng tạo worker, worker bị ghi đè thành "mồ côi" (không còn trong
active_workers, /stream/stop không tắt được) nhưng vẫn đọc RTSP và phát cảnh báo -> mỗi sự cố bị ghi 2 lần.

Mọi nơi tạo worker phải đi qua get_or_start_worker(): một khoá riêng theo từng camera tuần tự hoá cả vòng
kiểm tra -> tạo -> dừng worker cũ -> start worker mới -> đăng ký. start()/stop() vẫn chạy NGOÀI active_workers_lock
(Điểm 7: không giữ lock registry khi mở/đóng RTSP).
"""
import logging
import threading
from typing import Any, Callable, Dict, Optional, Tuple

logger = logging.getLogger(__name__)

_lifecycle_locks: Dict[str, threading.Lock] = {}
_lifecycle_locks_guard = threading.Lock()


def _camera_lifecycle_lock(camera_code: str) -> threading.Lock:
    norm = camera_code.strip().upper()
    with _lifecycle_locks_guard:
        lock = _lifecycle_locks.get(norm)
        if lock is None:
            lock = threading.Lock()
            _lifecycle_locks[norm] = lock
        return lock


def find_worker_key(active_workers: Dict[str, Any], camera_code: str) -> Optional[str]:
    """Khoá trong active_workers ứng với camera_code (không phân biệt hoa thường), None nếu chưa có."""
    norm = (camera_code or "").strip().upper()
    if camera_code in active_workers:
        return camera_code
    for key in list(active_workers.keys()):
        if str(key).strip().upper() == norm:
            return key
    return None


def _is_running(worker: Any) -> bool:
    return bool(worker is not None and getattr(worker, "is_running", False))


def get_or_start_worker(
    active_workers: Dict[str, Any],
    registry_lock: Any,
    camera_code: str,
    factory: Callable[[], Any],
    keep_existing: Optional[Callable[[Any], bool]] = None,
) -> Tuple[Any, bool]:
    """
    Trả (worker, started).

    - Camera đã có worker đang chạy và keep_existing(worker) đúng (mặc định: luôn giữ) -> trả (worker, False).
    - Ngược lại: tạo worker mới bằng factory(), dừng worker cũ đang chạy (nếu có), start worker mới, đăng ký vào
      active_workers -> trả (worker_mới, True). factory() lỗi thì worker cũ giữ nguyên.
    """
    camera_code = camera_code.strip()
    keep = keep_existing or (lambda _w: True)

    with _camera_lifecycle_lock(camera_code):
        with registry_lock:
            key = find_worker_key(active_workers, camera_code)
            existing = active_workers.get(key) if key is not None else None

        if _is_running(existing) and keep(existing):
            return existing, False

        new_worker = factory()

        if _is_running(existing):
            try:
                existing.stop()
            except Exception as e:
                logger.warning(f"Lỗi khi dừng worker cũ [{camera_code}]: {e}")

        new_worker.start()

        with registry_lock:
            current_key = find_worker_key(active_workers, camera_code)
            displaced = active_workers.pop(current_key, None) if current_key is not None else None
            active_workers[camera_code] = new_worker

        # Phòng hờ: worker do code ngoài helper chen vào trong lúc tạo -> dừng để không thành mồ côi
        if displaced is not None and displaced is not existing and displaced is not new_worker and _is_running(displaced):
            logger.warning(f"⚠️ [WorkerRegistry] Dừng worker trùng cho camera [{camera_code}] (đăng ký ngoài luồng chuẩn).")
            try:
                displaced.stop()
            except Exception as e:
                logger.warning(f"Lỗi khi dừng worker trùng [{camera_code}]: {e}")

        return new_worker, True
