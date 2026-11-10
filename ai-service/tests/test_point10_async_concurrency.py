import asyncio
from unittest.mock import MagicMock, patch
import cv2
import numpy as np
import pytest
from src.config import settings


def test_min_motion_area_in_settings():
    """Điểm 10: MIN_MOTION_AREA được khai báo trong Settings với giá trị mặc định 3000"""
    assert hasattr(settings, "MIN_MOTION_AREA")
    assert settings.MIN_MOTION_AREA == 3000


@pytest.mark.anyio
async def test_sync_ai_config_from_backend_non_blocking():
    """
    Điểm 10: sync_ai_config_from_backend không được gọi requests.get đồng bộ trực tiếp
    trên event loop mà phải chạy qua asyncio.to_thread (hoặc httpx).
    """
    from src.main import sync_ai_config_from_backend

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "data": {
            "face_match_threshold": 0.8,
            "after_hour_start": "23:00",
            "after_hour_end": "05:00",
            "max_inactive_seconds": 3.0,
            "unknown_alert_cooldown": 45.0
        }
    }

    with patch("asyncio.to_thread", wraps=asyncio.to_thread) as mock_to_thread, \
         patch("requests.get", return_value=mock_resp) as mock_get:

        res = await sync_ai_config_from_backend(max_retries=1, retry_delay=0.01)
        assert res is True
        # asyncio.to_thread phải được gọi để chạy requests.get ngoài event loop
        assert mock_to_thread.called, "requests.get phải được chạy qua asyncio.to_thread để không chặn event loop"


@pytest.mark.anyio
async def test_analyze_frame_offloads_heavy_inference_to_thread():
    """
    Điểm 10: analyze_frame phải offload xử lý CPU nặng (process_single_frame) sang worker thread.
    """
    from fastapi import UploadFile
    import io
    from src.main import analyze_frame
    import src.main as main_mod

    mock_pipeline = MagicMock()
    mock_pipeline.process_single_frame.return_value = (np.zeros((10, 10, 3)), [], [])
    main_mod.default_pipeline = mock_pipeline

    dummy_img = np.zeros((50, 50, 3), dtype=np.uint8)
    _, buf = cv2.imencode(".jpg", dummy_img)
    upload_file = UploadFile(filename="test.jpg", file=io.BytesIO(buf.tobytes()))

    with patch("asyncio.to_thread", wraps=asyncio.to_thread) as mock_to_thread:
        res = await analyze_frame(file=upload_file)
        assert res["total_persons"] == 0
        assert mock_to_thread.called, "Inference nặng phải được offload qua asyncio.to_thread"
