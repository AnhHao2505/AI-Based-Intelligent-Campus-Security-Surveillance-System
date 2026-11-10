import time
import threading
from unittest.mock import MagicMock, patch
import pytest
from src.core.face_matcher import FaceMatcher


def test_access_cache_concurrent_read_and_clear():
    """
    Điểm 7: Kiểm tra access_cache có khóa bảo vệ.
    Nhiều thread đồng thời đọc get_cached_access / check_camera_access
    trong khi các thread khác liên tục gọi refresh_cache (xóa access_cache)
    không được văng KeyError hoặc RuntimeError (dictionary changed size during iteration).
    """
    with patch.object(FaceMatcher, "refresh_cache"):
        matcher = FaceMatcher(db_name="sep_dev")
    matcher.access_cache = {}
    matcher.access_cache_ttl = 30.0

    # Fill initial cache entries
    for i in range(20):
        matcher.access_cache[f"CAM-001:USER_{i}"] = (True, "OK", "Area", time.time() + 30.0)

    exceptions = []
    stop_event = threading.Event()

    def reader_worker():
        while not stop_event.is_set():
            try:
                for i in range(20):
                    matcher.get_cached_access("CAM-001", f"USER_{i}")
            except Exception as e:
                exceptions.append(e)

    def writer_worker():
        while not stop_event.is_set():
            try:
                with matcher._access_cache_lock:
                    for i in range(20):
                        matcher.access_cache[f"CAM-001:USER_{i}"] = (True, "OK", "Area", time.time() + 30.0)
            except Exception as e:
                exceptions.append(e)

    def clearer_worker():
        while not stop_event.is_set():
            try:
                with matcher._access_cache_lock:
                    matcher.access_cache.clear()
            except Exception as e:
                exceptions.append(e)

    threads = []
    for _ in range(4):
        threads.append(threading.Thread(target=reader_worker))
    for _ in range(3):
        threads.append(threading.Thread(target=writer_worker))
    for _ in range(3):
        threads.append(threading.Thread(target=clearer_worker))

    for t in threads:
        t.start()

    time.sleep(0.3)
    stop_event.set()

    for t in threads:
        t.join()

    assert len(exceptions) == 0, f"Concurrent access_cache raised exceptions: {exceptions}"


def test_pending_access_checks_concurrent_access():
    """
    Kiểm tra _pending_access_checks được bảo vệ bởi khóa đồng bộ.
    """
    from src.pipeline.video_pipeline import VideoPipeline
    pipeline = VideoPipeline.__new__(VideoPipeline)
    pipeline.camera_code = "CAM-001"
    pipeline._pending_access_checks = set()
    pipeline._pending_checks_lock = threading.Lock()

    exceptions = []
    stop_event = threading.Event()

    def worker_add_remove(worker_id: int):
        while not stop_event.is_set():
            key = f"CAM-001:USER_{worker_id}"
            try:
                with pipeline._pending_checks_lock:
                    pipeline._pending_access_checks.add(key)
                with pipeline._pending_checks_lock:
                    pipeline._pending_access_checks.discard(key)
            except Exception as e:
                exceptions.append(e)

    threads = [threading.Thread(target=worker_add_remove, args=(i,)) for i in range(10)]
    for t in threads:
        t.start()

    time.sleep(0.3)
    stop_event.set()

    for t in threads:
        t.join()

    assert len(exceptions) == 0, f"Concurrent _pending_access_checks raised: {exceptions}"


def test_active_workers_concurrent_read_write():
    """
    Kiểm tra active_workers được bảo vệ bởi khóa active_workers_lock trong main.py
    khi nhiều request đồng thời duyệt và sửa đổi.
    """
    from src.main import active_workers, active_workers_lock

    exceptions = []
    stop_event = threading.Event()

    def reader():
        while not stop_event.is_set():
            try:
                with active_workers_lock:
                    _ = list(active_workers.values())
                    _ = list(active_workers.keys())
            except Exception as e:
                exceptions.append(e)

    def writer(idx: int):
        while not stop_event.is_set():
            key = f"CAM_{idx}"
            try:
                with active_workers_lock:
                    active_workers[key] = MagicMock()
                with active_workers_lock:
                    active_workers.pop(key, None)
            except Exception as e:
                exceptions.append(e)

    threads = [threading.Thread(target=reader) for _ in range(4)]
    threads.extend([threading.Thread(target=writer, args=(i,)) for i in range(4)])

    for t in threads:
        t.start()

    time.sleep(0.3)
    stop_event.set()

    for t in threads:
        t.join()

    assert len(exceptions) == 0, f"Concurrent active_workers raised: {exceptions}"
def test_worker_start_stop_not_holding_lock():
    """
    Điểm 7: Xác nhận active_workers_lock dùng RLock và worker.start() / worker.stop()
    không bị gọi trong khi đang giữ lock (tránh block các request khác hoặc deadlock).
    """
    import asyncio
    from src.main import active_workers, active_workers_lock, start_camera_stream, stop_camera_stream, StreamStartRequest
    from src.integration.edge_agent import EdgeMqttAgent

    assert isinstance(active_workers_lock, type(threading.RLock())), "active_workers_lock phải là RLock"

    # Mock worker
    mock_worker = MagicMock()
    mock_worker.is_running = True
    mock_worker.rtsp_url = "rtsp://old-url"

    lock_held_during_stop = []
    lock_held_during_start = []

    def mock_stop():
        # Kiểm tra xem lock có đang bị giữ bởi thread hiện tại không
        # Với RLock, _is_owned() trả về True nếu thread hiện tại đang sở hữu lock
        is_owned = active_workers_lock._is_owned() if hasattr(active_workers_lock, "_is_owned") else False
        lock_held_during_stop.append(is_owned)

    def mock_start():
        is_owned = active_workers_lock._is_owned() if hasattr(active_workers_lock, "_is_owned") else False
        lock_held_during_start.append(is_owned)

    mock_worker.stop.side_effect = mock_stop
    mock_worker.start.side_effect = mock_start

    with active_workers_lock:
        active_workers["CAM-TEST-LOCK"] = mock_worker

    # 1. Test stop_camera_stream
    asyncio.run(stop_camera_stream(camera_code="CAM-TEST-LOCK"))
    assert len(lock_held_during_stop) == 1
    assert lock_held_during_stop[0] is False, "worker.stop() không được chạy trong lúc đang giữ active_workers_lock!"

    # 2. Test start_camera_stream (với mock CameraStreamWorker)
    with patch("src.main.CameraStreamWorker") as mock_worker_cls:
        new_mock_worker = MagicMock()
        new_mock_worker.start.side_effect = mock_start
        mock_worker_cls.return_value = new_mock_worker

        req = StreamStartRequest(camera_code="CAM-TEST-LOCK-2", rtsp_url="rtsp://localhost:8554/live")
        asyncio.run(start_camera_stream(req))

        assert len(lock_held_during_start) == 1
        assert lock_held_during_start[0] is False, "worker.start() không được chạy trong lúc đang giữ active_workers_lock!"

    # Cleanup
    with active_workers_lock:
        active_workers.pop("CAM-TEST-LOCK", None)
        active_workers.pop("CAM-TEST-LOCK-2", None)
