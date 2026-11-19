from unittest.mock import patch
import pytest
from src.config import settings
from src.pipeline.stream_worker import select_stream_path, resolve_camera_rtsp_url


def test_select_stream_path_prefers_main_stream_when_available():
    available = ["CAM-001", "CAM-001_main", "CAM-002"]
    selected = select_stream_path("CAM-001", available)
    assert selected == "CAM-001_main"


def test_select_stream_path_falls_back_to_sub_when_main_absent():
    available = ["CAM-001", "CAM-002"]
    selected = select_stream_path("CAM-001", available)
    assert selected == "CAM-001"


def test_select_stream_path_without_available_list_returns_camera_code():
    assert select_stream_path("CAM-005") == "cam-005"
    assert select_stream_path("  CAM-006  ") == "cam-006"


def test_select_stream_path_empty_input():
    assert select_stream_path("") == ""
    assert select_stream_path("   ") == ""


def test_resolve_camera_rtsp_url_dual_stream():
    available = ["cam_gate", "cam_gate_main"]
    with patch.object(settings, "INTERNAL_SERVICE_KEY", ""):
        url = resolve_camera_rtsp_url("cam_gate", base_rtsp_gateway="rtsp://mediamtx:8554", available_paths=available)
        assert url == "rtsp://mediamtx:8554/cam_gate_main"

    with patch.object(settings, "INTERNAL_SERVICE_KEY", "unit-test-secret"):
        auth_url = resolve_camera_rtsp_url("cam_gate", base_rtsp_gateway="rtsp://mediamtx:8554", available_paths=available)
        assert auth_url.startswith("rtsp://icss-internal:")
        assert auth_url.endswith("@mediamtx:8554/cam_gate_main")


def test_resolve_camera_rtsp_url_single_stream():
    available = ["cam_gate"]
    with patch.object(settings, "INTERNAL_SERVICE_KEY", ""):
        url = resolve_camera_rtsp_url("cam_gate", base_rtsp_gateway="rtsp://mediamtx:8554", available_paths=available)
        assert url == "rtsp://mediamtx:8554/cam_gate"

    with patch.object(settings, "INTERNAL_SERVICE_KEY", "unit-test-secret"):
        auth_url = resolve_camera_rtsp_url("cam_gate", base_rtsp_gateway="rtsp://mediamtx:8554", available_paths=available)
        assert auth_url.startswith("rtsp://icss-internal:")
        assert auth_url.endswith("@mediamtx:8554/cam_gate")
