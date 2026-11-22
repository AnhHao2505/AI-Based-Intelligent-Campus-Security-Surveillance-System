"""
Đếm khuôn mặt cho luồng đăng ký / gắn ảnh (detect_registration_faces):
- mặt chính vẫn phát hiện ở FACE_REGISTRATION_MAX_DIM (640) như trước;
- ảnh đã bị thu nhỏ thì thêm 1 lượt ở FACE_REGISTRATION_COUNT_MAX_DIM để đếm mặt phụ nhỏ bị mất khi thu nhỏ;
- luồng camera trực tiếp (detect_best_face_in_crop) không đi qua hàm này.
Phần lớn test dùng detector giả (không cần model). Test cuối dùng SCRFD thật (có trong git) và dựng ảnh trong bộ nhớ từ
ảnh mẫu public domain của matplotlib; thiếu matplotlib thì bỏ qua. Không lưu ảnh khuôn mặt vào repo.
"""
import json
from unittest.mock import MagicMock, patch

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from src.core.entity import BoundingBox, FaceDetectionResult
from src.core.face_detector import FaceDetector, detect_registration_faces


def _face(x1, y1, x2, y2, score=0.9):
    return FaceDetectionResult(
        bbox=BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2, confidence=score),
        score=score,
        landmarks=[(x1 + 5, y1 + 5)] * 5,
    )


class _FakeDetector:
    """Trả danh sách mặt theo max_dim được gọi, ghi lại các max_dim."""

    def __init__(self, by_dim):
        self.by_dim = by_dim
        self.calls = []

    def detect_in_image(self, image, offset_xy=(0, 0), max_dim=None):
        self.calls.append(max_dim)
        return list(self.by_dim.get(max_dim, []))


PRIMARY = _face(600, 300, 1000, 800, score=0.95)
SMALL_EXTRA = _face(1450, 60, 1480, 95, score=0.6)


def test_small_image_single_pass_like_before():
    det = _FakeDetector({640: [PRIMARY]})
    faces, count = detect_registration_faces(det, np.zeros((600, 512, 3), np.uint8), 640, 1280)
    assert det.calls == [640]
    assert count == 1 and faces == [PRIMARY]


def test_large_image_counts_small_extra_face_missed_at_640():
    det = _FakeDetector({640: [PRIMARY], 1280: [SMALL_EXTRA]})
    faces, count = detect_registration_faces(det, np.zeros((1200, 1600, 3), np.uint8), 640, 1280)
    assert det.calls == [640, 1280]
    assert count == 2
    # mặt chính (dùng trích vector) không đổi: chỉ lấy từ lượt 640
    assert faces == [PRIMARY]


def test_same_face_in_both_passes_not_double_counted():
    same_face_hi_res = _face(610, 310, 990, 790)
    inner_part = _face(700, 400, 760, 450)  # mảnh trong mặt to (tâm nằm trong mặt chính)
    det = _FakeDetector({640: [PRIMARY], 1280: [same_face_hi_res, inner_part]})
    _, count = detect_registration_faces(det, np.zeros((1200, 1600, 3), np.uint8), 640, 1280)
    assert count == 1


def test_bigger_box_containing_primary_not_counted():
    around = _face(500, 200, 1100, 900)
    det = _FakeDetector({640: [PRIMARY], 1280: [around]})
    _, count = detect_registration_faces(det, np.zeros((1200, 1600, 3), np.uint8), 640, 1280)
    assert count == 1


def test_no_face_at_640_stays_no_face():
    det = _FakeDetector({640: [], 1280: [SMALL_EXTRA]})
    faces, count = detect_registration_faces(det, np.zeros((1200, 1600, 3), np.uint8), 640, 1280)
    assert det.calls == [640]
    assert faces == [] and count == 0


@pytest.mark.parametrize("count_dim", [0, None, 640, 500])
def test_count_pass_disabled(count_dim):
    det = _FakeDetector({640: [PRIMARY], 1280: [SMALL_EXTRA]})
    _, count = detect_registration_faces(det, np.zeros((1200, 1600, 3), np.uint8), 640, count_dim)
    assert det.calls == [640]
    assert count == 1


def test_count_pass_never_upscales_beyond_original():
    det = _FakeDetector({640: [PRIMARY], 1000: [SMALL_EXTRA]})
    _, count = detect_registration_faces(det, np.zeros((750, 1000, 3), np.uint8), 640, 1280)
    assert det.calls == [640, 1000]
    assert count == 2


def test_faces_sorted_by_score_desc():
    low, high = _face(0, 0, 50, 50, score=0.5), _face(100, 100, 200, 200, score=0.9)
    det = _FakeDetector({640: [low, high]})
    faces, count = detect_registration_faces(det, np.zeros((600, 600, 3), np.uint8), 640, 1280)
    assert faces == [high, low] and count == 2


def _jpeg(h, w) -> bytes:
    ok, buf = cv2.imencode(".jpg", np.full((h, w, 3), 128, np.uint8))
    assert ok
    return buf.tobytes()


def test_http_registration_reports_extra_small_face():
    import src.main as main_mod
    from src.config import settings, INTERNAL_SERVICE_KEY_HEADER

    det = _FakeDetector({640: [PRIMARY], 1280: [SMALL_EXTRA]})
    det.registration_max_dim = 640
    embedder = MagicMock(session=object())
    embedder.extract_embedding.return_value = [0.1] * 512
    with patch.object(main_mod, "face_embedder", embedder), \
         patch.object(main_mod, "default_pipeline", None), \
         patch.object(main_mod, "face_detector", det), \
         patch.object(settings, "FACE_REGISTRATION_COUNT_MAX_DIM", 1280):
        resp = TestClient(main_mod.app).post(
            "/api/v1/faces/process-registration",
            data={"code": "SE000001"},
            files={"front_image": ("f.jpg", _jpeg(1200, 1600), "image/jpeg")},
            headers={INTERNAL_SERVICE_KEY_HEADER: settings.INTERNAL_SERVICE_KEY},
        )
    assert resp.status_code == 200
    assert resp.json()["face_count"] == 2
    assert det.calls == [640, 1280]


def test_edge_rpc_registration_reports_extra_small_face():
    from src.config import settings
    from src.integration.edge_agent import EdgeMqttAgent

    det = _FakeDetector({640: [PRIMARY], 1280: [SMALL_EXTRA]})
    det.registration_max_dim = 640
    embedder = MagicMock(session=object())
    embedder.extract_embedding.return_value = [0.1] * 512
    mock_mqtt = MagicMock()
    storage = MagicMock()
    storage.download_bytes.return_value = _jpeg(1200, 1600)
    agent = EdgeMqttAgent(mqtt_client_wrapper=mock_mqtt, storage=storage, active_workers={},
                          face_detector_instance=det, face_embedder_instance=embedder)
    req = {"correlationId": "corr-face-count", "action": "EXTRACT_FACE_EMBEDDING", "cameraCode": None,
           "payload": {"bucket": "face-profiles", "object_key": "staging/x.jpg", "code": "NV001"}}
    with patch.object(settings, "FACE_REGISTRATION_COUNT_MAX_DIM", 1280):
        agent.handle_message("campus/edge/rpc/request", json.dumps(req).encode("utf-8"))

    mock_mqtt.publish_json.assert_called_once()
    reply = mock_mqtt.publish_json.call_args[0][1]
    assert reply["success"] is True
    assert reply["data"]["face_count"] == 2


def _compose_with_small_second_face(secondary_face_px):
    """1600x1200: mặt chính ~480 px ở giữa + bản lật nhỏ (mặt ~secondary_face_px) ở góc trên phải."""
    cbook = pytest.importorskip("matplotlib.cbook")
    with cbook.get_sample_data("grace_hopper.jpg") as f:
        base = cv2.imdecode(np.frombuffer(f.read(), np.uint8), cv2.IMREAD_COLOR)
    det = FaceDetector(use_cuda=False)
    f0 = max(det.detect_in_image(base), key=lambda x: x.score)
    fx1, fy1, fx2, fy2 = f0.bbox.to_int_xyxy()
    fw = fx2 - fx1
    h, w = base.shape[:2]
    cw, ch = 1600, 1200
    canvas = cv2.GaussianBlur(cv2.resize(base, (cw, ch)), (0, 0), 25)
    s = 480 / fw
    prim = cv2.resize(base, (int(w * s), int(h * s)), interpolation=cv2.INTER_CUBIC)
    ph, pw = prim.shape[:2]
    # đáy ảnh chính sát đáy khung, giữa theo ngang; phần thừa phía trên bị cắt
    x0, y0 = (cw - pw) // 2, ch - ph
    src_y = max(0, -y0)
    canvas[max(0, y0):, x0:x0 + pw] = prim[src_y:src_y + ch - max(0, y0), :]
    if secondary_face_px:
        pad = int(fw * 0.6)
        head = cv2.flip(base[max(0, fy1 - pad):fy2 + pad, max(0, fx1 - pad):fx2 + pad], 1)
        hw = int(secondary_face_px * head.shape[1] / fw)
        sec = cv2.resize(head, (hw, int(head.shape[0] * hw / head.shape[1])), interpolation=cv2.INTER_AREA)
        sx, sy = cw - hw - 64, 60
        canvas[sy:sy + sec.shape[0], sx:sx + hw] = sec
    return det, canvas


def test_real_scrfd_small_second_face_now_counted():
    # Mặt phụ 32 px trên ảnh 1600 px còn 12,8 px sau khi thu về 640 -> code cũ đếm 1
    det, img = _compose_with_small_second_face(32)
    assert len(det.detect_in_image(img, max_dim=640)) == 1
    _, count = detect_registration_faces(det, img, 640, 1280)
    assert count == 2


def test_real_scrfd_single_face_still_one():
    det, img = _compose_with_small_second_face(0)
    faces, count = detect_registration_faces(det, img, 640, 1280)
    assert count == 1 and len(faces) == 1
