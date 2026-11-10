import time
from unittest.mock import MagicMock, patch
import numpy as np
import pytest

from src.core.face_matcher import FaceMatcher
from src.core.shared_face_cache import SharedFaceCache, reset_shared_face_cache, get_shared_face_cache
from src.config import settings, INTERNAL_SERVICE_KEY_HEADER


def test_shared_face_cache_refresh_via_api():
    """
    Xác nhận SharedFaceCache nạp khuôn mặt, cameraAreaMap, publicCameraCodes qua API nội bộ của Backend.
    """
    reset_shared_face_cache()
    cache = SharedFaceCache(auto_refresh=False)

    now_ms = time.time() * 1000.0
    vec1 = [1.0] + [0.0] * 511
    vec2 = [0.0, 1.0] + [0.0] * 510

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "success": True,
        "data": {
            "faces": [
                {
                    "code": "SV01",
                    "fullName": "Sinh Vien 1",
                    "embedding": vec1,
                    "isActive": True,
                    "role": "NORMAL_USER",
                    "expiresAt": None
                },
                {
                    "code": "GUEST_EXPIRED",
                    "fullName": "Khach Het Han",
                    "embedding": vec2,
                    "isActive": True,
                    "role": "GUEST",
                    "expiresAt": now_ms - 5000 # Đã hết hạn cách đây 5 giây
                },
                {
                    "code": "GUEST_VALID",
                    "fullName": "Khach Hop Le",
                    "embedding": vec2,
                    "isActive": True,
                    "role": "GUEST",
                    "expiresAt": now_ms + 3600000 # Còn hạn 1 giờ
                }
            ],
            "cameraAreaMap": {
                "CAM-001": "Cong chinh"
            },
            "publicCameraCodes": ["CAM-001"],
            "timestamp": now_ms
        }
    }

    mock_session = MagicMock()
    mock_session.get.return_value = mock_resp

    with patch("src.utils.http_client.get_http_session", return_value=mock_session):
        success = cache.refresh_cache()
        assert success is True

        # Đảm bảo session.get gọi đúng header X-Service-Key
        mock_session.get.assert_called_once()
        args, kwargs = mock_session.get.call_args
        assert kwargs["headers"][INTERNAL_SERVICE_KEY_HEADER] == settings.INTERNAL_SERVICE_KEY

        # Khách hết hạn phải bị loại bỏ ngay trong RAM
        codes = [f["code"] for f in cache.cached_faces]
        assert "SV01" in codes
        assert "GUEST_VALID" in codes
        assert "GUEST_EXPIRED" not in codes
        assert len(cache.cached_faces) == 2

        # Kiểm tra cameraAreaMap và publicCameraCodes
        assert cache.camera_area_map.get("CAM-001") == "Cong chinh"
        assert "CAM-001" in cache.public_camera_codes
        assert cache.cached_matrix is not None
        assert len(cache.cached_matrix) == 2


def test_face_matcher_refresh_cache_does_not_clear_access_cache():
    """
    Xác nhận việc làm mới cache khuôn mặt (FaceMatcher.refresh_cache)
    KHÔNG xóa bộ đệm quyền truy cập (access_cache) để chống bão request Thundering Herd.
    """
    reset_shared_face_cache()
    matcher = FaceMatcher()
    matcher.access_cache = {
        "CAM-001:SV01": (True, "Hợp lệ", "Cong chinh", time.time() + 30.0, "ACCESS_LEVEL")
    }

    with patch.object(matcher.cache, "refresh_cache", return_value=True):
        matcher.refresh_cache()

        # Bộ đệm quyền truy cập PHẢI ĐƯỢC GIỮ NGUYÊN
        assert "CAM-001:SV01" in matcher.access_cache
        assert matcher.access_cache["CAM-001:SV01"][0] is True


def test_api_failure_keeps_old_cache_and_backoff():
    """
    Xác nhận khi Backend API lỗi 500 hoặc rớt mạng, cache cũ trong RAM được giữ nguyên và áp dụng backoff.
    """
    reset_shared_face_cache()
    cache = SharedFaceCache(auto_refresh=False)
    cache.cached_faces = [{"code": "PREV_USER", "full_name": "Old", "vector": np.ones(512), "is_active": True, "role": "NORMAL_USER"}]

    mock_session = MagicMock()
    mock_session.get.side_effect = RuntimeError("Connection timeout")

    with patch("src.utils.http_client.get_http_session", return_value=mock_session):
        success = cache.refresh_cache()
        assert success is False
        # Cache cũ còn nguyên
        assert len(cache.cached_faces) == 1
        assert cache.cached_faces[0]["code"] == "PREV_USER"
        assert cache._current_backoff == 4.0
