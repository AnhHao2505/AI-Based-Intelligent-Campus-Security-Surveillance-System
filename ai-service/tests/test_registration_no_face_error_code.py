"""
Ảnh đăng ký không có khuôn mặt -> mã cố định ERR_NO_FACE_DETECTED, cùng mã ở HTTP và RPC edge:
- POST /api/v1/faces/process-registration: HTTP 400, body {"detail": <câu cũ>, "errorCode": "ERR_NO_FACE_DETECTED"};
  detail giữ nguyên tiền tố "Không phát hiện thấy khuôn mặt" để Backend bản cũ (so khớp câu chữ) vẫn chạy.
- RPC EXTRACT_FACE_EMBEDDING: reply errorCode = "ERR_NO_FACE_DETECTED" (không đổi).
"""
import json
from unittest.mock import MagicMock, patch

import cv2
import numpy as np
from fastapi.testclient import TestClient

import src.main as main_mod
from src.config import settings, INTERNAL_SERVICE_KEY_HEADER
from src.core.face_detector import ERR_NO_FACE_DETECTED


def _jpeg() -> bytes:
    ok, buf = cv2.imencode(".jpg", np.full((120, 120, 3), 128, np.uint8))
    assert ok
    return buf.tobytes()


def test_http_no_face_returns_fixed_error_code_and_legacy_detail():
    detector = MagicMock()
    detector.detect_in_image.return_value = []
    with patch.object(main_mod, "face_embedder", MagicMock(session=object())), \
         patch.object(main_mod, "default_pipeline", None), \
         patch.object(main_mod, "face_detector", detector):
        resp = TestClient(main_mod.app).post(
            "/api/v1/faces/process-registration",
            data={"code": "SE000001"},
            files={"front_image": ("f.jpg", _jpeg(), "image/jpeg")},
            headers={INTERNAL_SERVICE_KEY_HEADER: settings.INTERNAL_SERVICE_KEY},
        )
    assert resp.status_code == 400
    body = resp.json()
    assert ERR_NO_FACE_DETECTED == "ERR_NO_FACE_DETECTED"
    assert body["errorCode"] == "ERR_NO_FACE_DETECTED"
    assert body["detail"].startswith("Không phát hiện thấy khuôn mặt")


def test_http_other_400_has_no_error_code():
    """Ảnh hỏng vẫn là HTTPException cũ ({"detail": ...}), không mang mã "không thấy mặt".
    Giả lập lỗi giải mã (giải mã bytes rác thật bằng Pillow mất hàng chục giây trên máy dev)."""
    with patch.object(main_mod, "face_embedder", MagicMock(session=object())), \
         patch("src.utils.image_utils.decode_image_safely", side_effect=ValueError("cannot identify image file")):
        resp = TestClient(main_mod.app).post(
            "/api/v1/faces/process-registration",
            data={"code": "SE000001"},
            files={"front_image": ("f.jpg", b"not-an-image", "image/jpeg")},
            headers={INTERNAL_SERVICE_KEY_HEADER: settings.INTERNAL_SERVICE_KEY},
        )
    assert resp.status_code == 400
    assert "errorCode" not in resp.json()


def test_edge_rpc_no_face_uses_same_error_code():
    from src.integration.edge_agent import EdgeMqttAgent

    detector = MagicMock()
    detector.detect_in_image.return_value = []
    mock_mqtt = MagicMock()
    storage = MagicMock()
    storage.download_bytes.return_value = _jpeg()
    agent = EdgeMqttAgent(mqtt_client_wrapper=mock_mqtt, storage=storage, active_workers={},
                          face_detector_instance=detector,
                          face_embedder_instance=MagicMock(session=object()))
    req = {"correlationId": "corr-no-face", "action": "EXTRACT_FACE_EMBEDDING", "cameraCode": None,
           "payload": {"bucket": "face-profiles", "object_key": "staging/x.jpg", "code": "NV001"}}
    agent.handle_message("campus/edge/rpc/request", json.dumps(req).encode("utf-8"))

    reply = mock_mqtt.publish_json.call_args[0][1]
    assert reply["success"] is False
    assert reply["errorCode"] == ERR_NO_FACE_DETECTED
