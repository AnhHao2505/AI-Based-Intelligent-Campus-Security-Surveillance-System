"""
Mỗi camera chỉ được có MỘT CameraStreamWorker chạy.

Lỗi cũ: EdgeAgent (MQTT), /stream/start, /stream/live-annotated và vòng Auto-Record cùng "kiểm tra rồi tạo" worker
ngoài lock -> chạy chồng nhau tạo 2 worker cho CAM-002, worker bị ghi đè thành mồ côi nhưng vẫn phát cảnh báo
-> mỗi sự cố bị ghi 2 lần cách nhau vài trăm ms.
"""
import threading
import time
from unittest.mock import MagicMock

from src.pipeline.worker_registry import find_worker_key, get_or_start_worker


class FakeWorker:
    def __init__(self, camera_code, rtsp_url="rtsp://cam/live", build_delay=0.0):
        time.sleep(build_delay)  # mô phỏng khởi tạo VideoPipeline / tải ROI chậm
        self.camera_code = camera_code
        self.rtsp_url = rtsp_url
        self.is_running = False
        self.start_calls = 0
        self.stop_calls = 0

    def start(self):
        self.start_calls += 1
        self.is_running = True

    def stop(self):
        self.stop_calls += 1
        self.is_running = False


def _running(workers):
    return [w for w in workers if w.is_running]


def test_concurrent_starts_leave_exactly_one_running_worker():
    active_workers = {}
    lock = threading.RLock()
    created = []
    created_lock = threading.Lock()

    def factory():
        w = FakeWorker("CAM-002", build_delay=0.05)
        with created_lock:
            created.append(w)
        return w

    barrier = threading.Barrier(4)

    def caller():
        barrier.wait()
        get_or_start_worker(active_workers, lock, "CAM-002", factory)

    threads = [threading.Thread(target=caller) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=5)

    assert len(_running(created)) == 1, "Chỉ được có đúng 1 worker đang chạy cho CAM-002"
    assert list(active_workers.keys()) == ["CAM-002"]
    assert active_workers["CAM-002"] is _running(created)[0]


def test_existing_running_worker_is_reused_without_building_new_one():
    active_workers = {}
    lock = threading.RLock()
    first, started = get_or_start_worker(active_workers, lock, "CAM-002", lambda: FakeWorker("CAM-002"))
    assert started is True

    factory = MagicMock(side_effect=lambda: FakeWorker("CAM-002"))
    again, started_again = get_or_start_worker(active_workers, lock, "CAM-002", factory)

    assert started_again is False
    assert again is first
    factory.assert_not_called()


def test_case_insensitive_code_does_not_create_second_worker():
    active_workers = {}
    lock = threading.RLock()
    first, _ = get_or_start_worker(active_workers, lock, "CAM-002", lambda: FakeWorker("CAM-002"))
    again, started = get_or_start_worker(active_workers, lock, "cam-002", lambda: FakeWorker("cam-002"))

    assert started is False
    assert again is first
    assert find_worker_key(active_workers, "Cam-002") == "CAM-002"


def test_replacing_worker_stops_old_one_before_starting_new_one():
    active_workers = {}
    lock = threading.RLock()
    old, _ = get_or_start_worker(active_workers, lock, "CAM-002", lambda: FakeWorker("CAM-002", "rtsp://old"))

    order = []
    old.stop = lambda: (order.append("stop_old"), setattr(old, "is_running", False))
    new = FakeWorker("CAM-002", "rtsp://new")
    new.start = lambda: (order.append("start_new"), setattr(new, "is_running", True))

    worker, started = get_or_start_worker(
        active_workers, lock, "CAM-002", lambda: new,
        keep_existing=lambda w: w.rtsp_url == "rtsp://new",
    )

    assert started is True
    assert worker is new
    assert order == ["stop_old", "start_new"]
    assert active_workers == {"CAM-002": new}


def test_factory_error_keeps_old_worker_running():
    active_workers = {}
    lock = threading.RLock()
    old, _ = get_or_start_worker(active_workers, lock, "CAM-002", lambda: FakeWorker("CAM-002", "rtsp://old"))

    def broken_factory():
        raise RuntimeError("không mở được pipeline")

    try:
        get_or_start_worker(active_workers, lock, "CAM-002", broken_factory, keep_existing=lambda w: False)
    except RuntimeError:
        pass

    assert old.is_running is True
    assert active_workers["CAM-002"] is old


def test_start_and_stop_run_outside_registry_lock():
    """Điểm 7: không giữ active_workers_lock khi start()/stop() worker."""
    active_workers = {}
    lock = threading.RLock()
    held = []

    old, _ = get_or_start_worker(active_workers, lock, "CAM-002", lambda: FakeWorker("CAM-002", "rtsp://old"))
    old.stop = lambda: held.append(("stop", lock._is_owned()))
    new = FakeWorker("CAM-002", "rtsp://new")
    new.start = lambda: held.append(("start", lock._is_owned()))

    get_or_start_worker(active_workers, lock, "CAM-002", lambda: new, keep_existing=lambda w: False)

    assert held == [("stop", False), ("start", False)]


def test_edge_agent_and_stream_start_race_yields_one_worker():
    """EdgeAgent nhận config MQTT cùng lúc một luồng khác (live-annotated / auto-record) khởi động cùng camera."""
    from src.integration.edge_agent import EdgeMqttAgent
    import json
    from unittest.mock import patch

    active_workers = {}
    lock = threading.RLock()
    created = []

    def edge_factory(camera_code, rtsp_url, roi_geometry=None):
        w = FakeWorker(camera_code, rtsp_url, build_delay=0.05)
        created.append(w)
        return w

    agent = EdgeMqttAgent(
        mqtt_client_wrapper=MagicMock(),
        storage=MagicMock(),
        active_workers=active_workers,
        worker_factory=edge_factory,
        active_workers_lock=lock,
    )
    rtsp = "rtsp://127.0.0.1:8554/cam-002"
    cfg = json.dumps({"camera_code": "CAM-002", "rtsp_url": rtsp, "enabled": True}).encode("utf-8")

    def other_path():
        def factory():
            w = FakeWorker("CAM-002", rtsp, build_delay=0.05)
            created.append(w)
            return w
        get_or_start_worker(active_workers, lock, "CAM-002", factory)

    with patch.object(agent, "_sync_local_mediamtx_path", return_value=rtsp):
        t1 = threading.Thread(target=agent.handle_message, args=("campus/edge/config/cameras/CAM-002", cfg))
        t2 = threading.Thread(target=other_path)
        t1.start(); t2.start()
        t1.join(timeout=5); t2.join(timeout=5)

    assert len(_running(created)) == 1
    assert list(active_workers.keys()) == ["CAM-002"]
