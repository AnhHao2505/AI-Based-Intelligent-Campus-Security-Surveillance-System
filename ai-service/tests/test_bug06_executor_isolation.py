import time
import threading
from unittest.mock import MagicMock, patch
import pytest

from src.pipeline.video_pipeline import VideoPipeline
from src.core.entity import FaceDetectionResult, BoundingBox


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_access_checks_not_blocked_by_heavy_io(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    BUG-06: Phân tách ThreadPoolExecutor:
    Khi io_executor đang bận xử lý 2 tác vụ upload MinIO nặng (chiếm hết thread),
    tác vụ kiểm tra quyền truy cập (access check) qua access_executor KHÔNG bị nghẽn
    và phải được thực thi ngay lập tức (< 200ms).
    """
    pipeline = VideoPipeline(camera_code="CAM-BUG06-TEST")

    # Xác nhận pipeline có 2 executor riêng biệt
    assert hasattr(pipeline, "access_executor"), "Pipeline phải có access_executor riêng"
    assert hasattr(pipeline, "io_executor"), "Pipeline phải có io_executor riêng"
    assert pipeline.access_executor is not pipeline.io_executor, "access_executor và io_executor phải độc lập"

    # Chiếm dụng toàn bộ worker của io_executor bằng 2 task chậm (0.6s)
    io_started = threading.Event()
    io_count = 0
    io_lock = threading.Lock()

    def slow_io_task():
        nonlocal io_count
        with io_lock:
            io_count += 1
            if io_count >= 2:
                io_started.set()
        time.sleep(0.5)

    # Submit 2 task chậm vào io_executor
    pipeline.io_executor.submit(slow_io_task)
    pipeline.io_executor.submit(slow_io_task)

    # Chờ cả 2 task bắt đầu chạy
    assert io_started.wait(timeout=1.0), "2 task io chậm phải bắt đầu chạy"

    # Ngay lúc này, submit một access check
    access_completed = threading.Event()
    start_t = time.time()

    def quick_access_task():
        access_completed.set()

    pipeline.access_executor.submit(quick_access_task)

    # Task access check phải hoàn thành gần như tức thì (< 0.2s), không phải chờ 0.5s
    completed_in_time = access_completed.wait(timeout=0.2)
    elapsed = time.time() - start_t

    pipeline.stop()

    assert completed_in_time, f"Access check bị nghẽn bởi io_executor! Mất {elapsed:.3f}s"
    assert elapsed < 0.3, f"Access check phải thực thi ngay lập tức, thực tế mất: {elapsed:.3f}s"


@patch("src.pipeline.stream_worker.CameraStreamWorker._fetch_roi_geometry", return_value=None)
@patch("src.pipeline.stream_worker.cv2.VideoCapture")
@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_worker_create_and_stop_20_times_thread_count_does_not_increase(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet, mock_cap, mock_roi
):
    """
    BUG-06: Xác nhận CameraStreamWorker.stop() gọi shutdown cả access_executor và io_executor
    (cancel_futures=True). Khi tạo/dừng worker 20 lần, số thread không tăng.
    """
    from src.pipeline.stream_worker import CameraStreamWorker
    import gc

    gc.collect()
    time.sleep(0.1)
    initial_threads = threading.active_count()

    for i in range(20):
        worker = CameraStreamWorker(
            camera_code=f"CAM-LEAK-{i}",
            rtsp_url="rtsp://dummy/test",
        )
        worker.start()
        # Submit task nhỏ vào cả 2 executor để sinh thread trong pool
        worker.pipeline.access_executor.submit(lambda: None)
        worker.pipeline.io_executor.submit(lambda: None)
        worker.stop()

    gc.collect()
    time.sleep(0.3)
    final_threads = threading.active_count()

    # Cho phép dao động tối đa 1 thread do garbage collection hoặc pytest runner
    assert final_threads <= initial_threads + 1, (
        f"Số lượng thread bị rò rỉ sau 20 lần tạo/dừng worker! Ban đầu: {initial_threads}, sau cùng: {final_threads}"
    )


def test_fastapi_shutdown_event_cleans_default_pipeline():
    """
    BUG-06: Xác nhận sự kiện shutdown của FastAPI dọn dẹp default_pipeline.stop()
    """
    from src.main import shutdown_event
    import src.main as main_module
    import asyncio

    mock_pipeline = MagicMock()
    main_module.default_pipeline = mock_pipeline

    asyncio.run(shutdown_event())

    mock_pipeline.stop.assert_called_once()
    assert main_module.default_pipeline is None
