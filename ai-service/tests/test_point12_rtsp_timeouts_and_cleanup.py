import os
import threading
import time
from unittest.mock import MagicMock, patch
import cv2
import pytest
from src.pipeline.stream_worker import CameraStreamWorker
from src.pipeline.stream_recorder import StreamRecorder


@pytest.mark.requires_model
def test_rtsp_capture_configures_timeouts():
    """
    Điểm 12: CameraStreamWorker cấu hình stimeout (5s) và OPEN/READ timeouts cho VideoCapture.
    """
    worker = CameraStreamWorker(camera_code="CAM-TIMEOUT", rtsp_url="rtsp://192.168.1.100:554/live")

    mock_cap = MagicMock()
    mock_cap.read.return_value = (False, None)

    with patch("cv2.VideoCapture", return_value=mock_cap) as mock_vc_ctor:
        # Chạy _capture_loop trong 1 vòng ngắn rồi stop
        def _stop_soon():
            time.sleep(0.05)
            worker.is_running = False

        worker.is_running = True
        threading.Thread(target=_stop_soon).start()
        worker._capture_loop()

        # Kiểm tra OPENCV_FFMPEG_CAPTURE_OPTIONS chứa stimeout
        opts = os.environ.get("OPENCV_FFMPEG_CAPTURE_OPTIONS", "")
        assert "stimeout;5000000" in opts, f"OPENCV_FFMPEG_CAPTURE_OPTIONS phải có stimeout;5000000, thực tế: {opts}"
        # Kiểm tra set OPEN_TIMEOUT_MSEC và READ_TIMEOUT_MSEC
        mock_cap.set.assert_any_call(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 5000)
        mock_cap.set.assert_any_call(cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000)
        # cap.release() phải được gọi khi kết thúc loop
        mock_cap.release.assert_called()


@pytest.mark.requires_model
def test_stream_worker_stop_cleanly_releases_even_on_exception():
    """
    Điểm 12: stop() giải phóng VideoCapture và join threads sạch sẽ ngay cả khi capture gặp lỗi.
    """
    worker = CameraStreamWorker(camera_code="CAM-LEAK-TEST", rtsp_url="rtsp://192.168.1.100:554/live")
    worker.recorder = MagicMock()

    mock_cap = MagicMock()
    # Giả lập read() ném exception bất ngờ
    mock_cap.read.side_effect = RuntimeError("OpenCV hardware decode failure")

    with patch("cv2.VideoCapture", return_value=mock_cap):
        worker.start()
        time.sleep(0.1)
        worker.stop()

        # Threads phải được dọn dẹp không còn sống
        assert worker.capture_thread is None or not worker.capture_thread.is_alive()
        assert worker.analysis_thread is None or not worker.analysis_thread.is_alive()
        assert worker.is_running is False
        mock_cap.release.assert_called()


def test_stream_recorder_stop_idempotent_and_thread_safe():
    """
    Điểm 12: StreamRecorder.stop() có thể được gọi nhiều lần và đồng thời từ nhiều luồng
    mà không bị double-close, không raise exception, và đóng writer an toàn.
    """
    recorder = StreamRecorder(camera_code="CAM-DOUBLE-STOP", base_dir="dummy_dir")
    recorder.is_running = True
    recorder._close_writer = MagicMock()

    # Gọi đồng thời từ 5 luồng
    threads = []
    for _ in range(5):
        t = threading.Thread(target=recorder.stop)
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    # _close_writer chỉ được gọi đúng 1 lần duy nhất dù gọi stop 5 lần đồng thời
    assert recorder._close_writer.call_count == 1, (
        f"_close_writer chỉ được gọi 1 lần, thực tế gọi: {recorder._close_writer.call_count}"
    )
