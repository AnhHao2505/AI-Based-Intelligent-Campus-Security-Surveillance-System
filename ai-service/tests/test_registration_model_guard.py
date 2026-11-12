"""
Đăng ký khuôn mặt phải từ chối khi ArcFace chưa nạp (thay vì lưu vector dự phòng DCT "giống mọi người"),
và lỗi "không thấy khuôn mặt" phải giữ mã 400 thay vì bị đổi thành 500.
"""
from unittest.mock import MagicMock, patch

import cv2
import numpy as np
from fastapi.testclient import TestClient

import src.main as main_mod
from src.config import settings, INTERNAL_SERVICE_KEY_HEADER


def _jpeg() -> bytes:
    ok, buf = cv2.imencode(".jpg", np.full((120, 120, 3), 128, np.uint8))
    assert ok
    return buf.tobytes()


def _post(client):
    return client.post(
        "/api/v1/faces/process-registration",
        data={"code": "SE000001"},
        files={"front_image": ("f.jpg", _jpeg(), "image/jpeg")},
        headers={INTERNAL_SERVICE_KEY_HEADER: settings.INTERNAL_SERVICE_KEY},
    )


def test_registration_rejected_when_arcface_not_loaded():
    client = TestClient(main_mod.app)
    no_model = MagicMock(session=None)
    with patch.object(main_mod, "face_embedder", no_model):
        resp = _post(client)
    assert resp.status_code == 503
    no_model.extract_embedding.assert_not_called()


def test_registration_no_face_keeps_400():
    client = TestClient(main_mod.app)
    detector = MagicMock()
    detector.detect_in_image.return_value = []
    with patch.object(main_mod, "face_embedder", MagicMock(session=object())), \
         patch.object(main_mod, "default_pipeline", None), \
         patch.object(main_mod, "face_detector", detector):
        resp = _post(client)
    assert resp.status_code == 400
