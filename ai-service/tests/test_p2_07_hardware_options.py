import os
import sys
from unittest.mock import MagicMock, patch
import numpy as np
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.config import settings
from src.core.human_detector import HumanDetector
from src.core.face_embedder import FaceEmbedder


def test_ai_half_setting_default_and_yolo_parameter():
    """
    P2-07:
    - Setting AI_HALF mặc định là False (an toàn cho máy không có Tensor Core).
    - Khi AI_HALF=False: HumanDetector.detect và detect_and_track gọi model với half=False.
    - Khi AI_HALF=True và device là GPU (0): gọi model với half=True.
    - Khi AI_HALF=True nhưng device='cpu': tự động ép half=False để bảo vệ CPU.
    """
    assert settings.AI_HALF is False, "AI_HALF phải có giá trị mặc định là False"

    dummy_frame = np.zeros((100, 100, 3), dtype=np.uint8)

    # 1. Trường hợp mặc định AI_HALF=False
    with patch("src.core.human_detector.YOLO") as mock_yolo_cls, \
         patch("torch.cuda.is_available", return_value=True):
        mock_model = MagicMock()
        mock_yolo_cls.return_value = mock_model

        detector = HumanDetector(model_path="dummy.pt", device=0)
        assert detector.half is False

        detector.detect_and_track(dummy_frame)
        assert mock_model.track.call_count == 1
        _, track_kwargs = mock_model.track.call_args
        # FP32: không truyền half/quantize (truyền 'half' làm ultralytics >= 8.4 in cảnh báo deprecated mỗi khung hình)
        assert "half" not in track_kwargs and "quantize" not in track_kwargs, "FP32 không được truyền half/quantize"

        detector.detect(dummy_frame)
        assert mock_model.call_count == 1
        _, detect_kwargs = mock_model.call_args
        assert "half" not in detect_kwargs and "quantize" not in detect_kwargs

    # 2. Trường hợp bật AI_HALF=True trên GPU
    with patch("src.core.human_detector.YOLO") as mock_yolo_cls, \
         patch.object(settings, "AI_HALF", True), \
         patch("torch.cuda.is_available", return_value=True):
        mock_model = MagicMock()
        mock_yolo_cls.return_value = mock_model

        detector_gpu = HumanDetector(model_path="dummy.pt", device=0)
        assert detector_gpu.half is True

        detector_gpu.detect_and_track(dummy_frame)
        _, track_kwargs_gpu = mock_model.track.call_args
        # FP16: 'quantize=16' với ultralytics mới, 'half=True' với bản cũ chưa có 'quantize'
        assert track_kwargs_gpu.get("quantize") == 16 or track_kwargs_gpu.get("half") is True,             "Kỳ vọng bật FP16 (quantize=16 hoặc half=True) khi AI_HALF=True trên GPU"

    # 3. Trường hợp bật AI_HALF=True nhưng chạy trên CPU -> tự động ép False
    with patch("src.core.human_detector.YOLO") as mock_yolo_cls, \
         patch.object(settings, "AI_HALF", True), \
         patch("torch.cuda.is_available", return_value=False):
        mock_model = MagicMock()
        mock_yolo_cls.return_value = mock_model

        detector_cpu = HumanDetector(model_path="dummy.pt", device="cpu")
        assert detector_cpu.half is False, "Trên CPU phải tự ép half=False"


def test_onnx_provider_auto_selection_and_logging():
    """
    P2-07:
    - Khi có CUDAExecutionProvider: FaceEmbedder ưu tiên ['CUDAExecutionProvider', 'CPUExecutionProvider'].
    - Khi chỉ có CPU: FaceEmbedder chọn ['CPUExecutionProvider'].
    - Có phương thức get_active_provider() trả về provider thực tế.
    """
    # 1. Có CUDA
    with patch("os.path.exists", return_value=True), \
         patch("onnxruntime.get_available_providers", return_value=['CUDAExecutionProvider', 'CPUExecutionProvider']), \
         patch("onnxruntime.InferenceSession") as mock_session_cls:
        mock_session = MagicMock()
        mock_session.get_providers.return_value = ['CUDAExecutionProvider', 'CPUExecutionProvider']
        mock_session.get_inputs.return_value = [MagicMock(name="data")]
        mock_session.get_outputs.return_value = [MagicMock(name="output")]
        mock_session_cls.return_value = mock_session

        embedder = FaceEmbedder(model_path="dummy_arcface.onnx")
        assert mock_session_cls.call_count == 1
        call_providers = mock_session_cls.call_args[1].get("providers") or mock_session_cls.call_args[0][2]
        assert call_providers[0] == 'CUDAExecutionProvider'
        assert hasattr(embedder, "get_active_provider")
        assert embedder.get_active_provider() == 'CUDAExecutionProvider'

    # 2. Chỉ có CPU
    with patch("os.path.exists", return_value=True), \
         patch("onnxruntime.get_available_providers", return_value=['CPUExecutionProvider']), \
         patch("onnxruntime.InferenceSession") as mock_session_cls:
        mock_session = MagicMock()
        mock_session.get_providers.return_value = ['CPUExecutionProvider']
        mock_session.get_inputs.return_value = [MagicMock(name="data")]
        mock_session.get_outputs.return_value = [MagicMock(name="output")]
        mock_session_cls.return_value = mock_session

        embedder_cpu = FaceEmbedder(model_path="dummy_arcface.onnx")
        call_providers_cpu = mock_session_cls.call_args[1].get("providers") or mock_session_cls.call_args[0][2]
        assert call_providers_cpu == ['CPUExecutionProvider']
        assert embedder_cpu.get_active_provider() == 'CPUExecutionProvider'


def test_hardware_acceleration_documentation_exists():
    """
    P2-07: Tài liệu hướng dẫn build CPU vs GPU và tối ưu phần cứng phải tồn tại.
    """
    doc_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "docs",
        "HARDWARE_ACCELERATION_GUIDE.md"
    )
    assert os.path.exists(doc_path), f"File tài liệu {doc_path} phải tồn tại"
    with open(doc_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "AI_HALF" in content
    assert "Tensor Core" in content
    assert "onnxruntime-gpu" in content
