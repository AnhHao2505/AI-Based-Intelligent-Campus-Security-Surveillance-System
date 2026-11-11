import os
import sys
from unittest.mock import MagicMock, patch
import numpy as np
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.config import settings
from src.utils.ffmpeg_options import get_ffmpeg_capture_options
from src.utils.system_threads import configure_system_threads
from src.pipeline.model_warmup import warmup_pipeline_models


def test_ffmpeg_capture_options_builder_with_low_latency_flags():
    """
    P1-06: get_ffmpeg_capture_options phải tạo chuỗi tham số cho OpenCV FFMPEG
    chứa rtsp_transport=tcp, fflags=nobuffer, flags=low_delay theo settings.
    """
    opts = get_ffmpeg_capture_options(timeout_us=3000000)
    assert "rtsp_transport;tcp" in opts, "Phải có cấu hình transport TCP"
    assert "fflags;nobuffer" in opts, "Phải có cờ nobuffer để triệt tiêu độ trễ đệm"
    assert "flags;low_delay" in opts, "Phải có cờ low_delay cho RTSP stream"
    assert "stimeout;3000000" in opts, "Phải chứa stimeout đúng tham số truyền vào"

    # Khi tắt cờ trong settings
    with patch.object(settings, "FFMPEG_NOBUFFER", False), \
         patch.object(settings, "FFMPEG_LOW_DELAY", False), \
         patch.object(settings, "RTSP_TRANSPORT", "udp"):
        custom_opts = get_ffmpeg_capture_options(timeout_us=2000000)
        assert "rtsp_transport;udp" in custom_opts
        assert "nobuffer" not in custom_opts
        assert "low_delay" not in custom_opts
        assert "stimeout;2000000" in custom_opts


def test_configure_system_threads_reads_env_and_configures_libraries():
    """
    P1-06: configure_system_threads đọc số luồng từ env/settings (không ghi cứng 4),
    và cấu hình cho OpenCV, PyTorch, OMP và ONNX intra_op.
    """
    with patch.dict(os.environ, {"AI_NUM_THREADS": "6"}), \
         patch("cv2.setNumThreads") as mock_cv2, \
         patch("torch.set_num_threads") as mock_torch:
        
        threads = configure_system_threads()
        assert threads == 6
        mock_cv2.assert_called_with(6)
        mock_torch.assert_called_with(6)
        assert os.environ.get("OMP_NUM_THREADS") == "6"

    # Kiểm tra FaceEmbedder sử dụng đúng số luồng cấu hình thay vì ghi cứng 4
    with patch("onnxruntime.InferenceSession") as mock_session, \
         patch("os.path.exists", return_value=True), \
         patch.dict(os.environ, {"AI_NUM_THREADS": "8"}):
        from src.core.face_embedder import FaceEmbedder
        embedder = FaceEmbedder(model_path="dummy_path.onnx")
        # sess_options được truyền vào InferenceSession
        assert mock_session.call_count > 0
        _, kwargs = mock_session.call_args
        sess_opts = kwargs.get("sess_options") or mock_session.call_args[0][1]
        assert sess_opts.intra_op_num_threads == 8, (
            f"Kỳ vọng intra_op_num_threads=8, thực tế: {getattr(sess_opts, 'intra_op_num_threads', None)}"
        )


def test_model_warmup_and_health_readiness():
    """
    P1-06: warmup_pipeline_models chạy dummy inference qua YOLO, YuNet, ArcFace.
    Endpoint /health phản ánh models_warmed_up và chỉ UP khi đã warmup.
    """
    mock_pipeline = MagicMock()
    mock_pipeline.human_detector = MagicMock()
    mock_pipeline.face_detector = MagicMock()
    mock_pipeline.face_embedder = MagicMock()

    success = warmup_pipeline_models(mock_pipeline)
    assert success is True
    # Kiểm tra cả 3 mô hình đều được gọi với dummy input
    assert mock_pipeline.human_detector.detect_and_track.call_count >= 1
    assert mock_pipeline.face_detector.detect_in_image.call_count >= 1
    assert mock_pipeline.face_embedder.extract_embedding.call_count >= 1

    # Kiểm tra logic endpoint /health
    from fastapi.testclient import TestClient
    from src.main import app
    import src.main as main_mod

    client = TestClient(app)

    with patch("os.path.exists", return_value=True), \
         patch.object(main_mod, "default_pipeline", mock_pipeline), \
         patch.object(main_mod, "models_warmed_up", False):
        
        # Khi chưa warm up xong: /health phải báo status DOWN
        res = client.get("/health")
        assert res.status_code == 200
        data = res.json()
        assert data["models_warmed_up"] is False
        assert data["status"] == "DOWN", "Chưa warm-up thì /health chưa được báo UP"

        # Khi đã warm up xong: /health báo status UP
        main_mod.models_warmed_up = True
        res2 = client.get("/health")
        assert res2.status_code == 200
        data2 = res2.json()
        assert data2["models_warmed_up"] is True
        assert data2["status"] == "UP"
