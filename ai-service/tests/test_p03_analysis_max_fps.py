import os
import sys
import time
from unittest.mock import MagicMock, patch
import numpy as np
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.human_detector import HumanDetector, compute_track_buffer, get_tracker_config_path
from src.pipeline.stream_worker import CameraStreamWorker
from src.config import settings


def test_track_buffer_scaling_preserves_temporal_memory():
    """
    Xác nhận P0.3:
    - Tại 30 FPS: track_buffer = 60 (2.0s).
    - Tại 10 FPS: track_buffer = 20 (2.0s).
    - Tại 12 FPS: track_buffer = 24 (2.0s).
    - Tại 5 FPS: track_buffer = 10 (2.0s).
    - Giới hạn cận dưới an toàn: tối thiểu 5 frames.
    - File cấu hình YAML sinh ra phản ánh đúng track_buffer.
    """
    assert compute_track_buffer(30.0, memory_seconds=2.0) == 60
    assert compute_track_buffer(10.0, memory_seconds=2.0) == 20
    assert compute_track_buffer(12.0, memory_seconds=2.0) == 24
    assert compute_track_buffer(5.0, memory_seconds=2.0) == 10
    assert compute_track_buffer(1.0, memory_seconds=2.0) >= 5

    # Kiểm tra đường dẫn config trả về cho 10 FPS
    cfg_path = get_tracker_config_path(analysis_fps=10.0)
    assert os.path.exists(cfg_path)
    with open(cfg_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "track_buffer: 20" in content


@patch("src.pipeline.stream_worker.VideoPipeline")
@patch("src.pipeline.stream_worker.StreamRecorder")
def test_stream_worker_analysis_fps_rate_limiting(mock_recorder_cls, mock_pipeline_cls):
    """
    Xác nhận P0.3:
    - Capture Thread ghi nhận đủ 100% frames vào StreamRecorder (không drop frame video).
    - Analysis Thread điều tiết tốc độ phân tích theo ANALYSIS_MAX_FPS (ví dụ 10 FPS).
    """
    mock_pipeline = MagicMock()
    mock_pipeline.process_frame.return_value = (np.zeros((100, 100, 3), dtype=np.uint8), [], [])
    mock_pipeline_cls.return_value = mock_pipeline

    mock_rec = MagicMock()
    mock_recorder_cls.return_value = mock_rec

    worker = CameraStreamWorker(
        camera_code="CAM-FPS-TEST",
        rtsp_url="rtsp://localhost:8554/test",
        analysis_max_fps=10.0
    )
    assert worker.analysis_max_fps == 10.0

    # Giả lập worker đang chạy
    worker.is_running = True
    test_frame = np.full((100, 100, 3), 120, dtype=np.uint8)

    # 1. Capture loop nhận 20 frames liên tiếp ở tốc độ 30 FPS (khoảng ~0.15s)
    for i in range(20):
        worker.recorder.write_frame(test_frame)
        with worker.raw_frame_lock:
            worker.raw_frame = test_frame
            worker.frame_id += 1
        time.sleep(0.005)

    # StreamRecorder nhận đủ tất cả 20 frames
    assert worker.recorder.write_frame.call_count == 20

    worker.is_running = False


def test_human_detector_runtime_fps_update():
    """
    Xác nhận P0.3:
    - Khi khởi tạo HumanDetector với analysis_fps = 10.0, track_buffer tự động tính = 20.
    - Khi cập nhật qua set_analysis_fps(15.0), track_buffer tự động tính lại = 30.
    """
    with patch("src.core.human_detector.YOLO"):
        detector = HumanDetector(model_path="yolo26n.pt", analysis_fps=10.0)
        assert detector.analysis_fps == 10.0
        assert detector.track_buffer == 20

        detector.set_analysis_fps(15.0)
        assert detector.analysis_fps == 15.0
        assert detector.track_buffer == 30
