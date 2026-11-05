import os
import sys
import time
import threading
from unittest.mock import MagicMock, patch
import numpy as np
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.core.face_matcher import FaceMatcher
from src.core.shared_face_cache import SharedFaceCache, get_shared_face_cache, reset_shared_face_cache


def test_shared_face_cache_singleton_across_cameras():
    """
    Xác nhận N FaceMatcher / VideoPipeline dùng chung 1 instance SharedFaceCache trong RAM.
    Khi cache cập nhật, tất cả matcher đều nhìn thấy dữ liệu mới tức thì.
    """
    reset_shared_face_cache()
    cache = get_shared_face_cache()

    matcher1 = FaceMatcher()
    matcher2 = FaceMatcher()

    assert matcher1.cache is cache
    assert matcher2.cache is cache

    # Cập nhật cache từ matcher1
    mock_vec = np.ones(512, dtype=np.float32) / np.sqrt(512)
    new_faces = [{"code": "SHARED_01", "full_name": "Shared User", "vector": mock_vec, "is_active": True, "role": "NORMAL_USER"}]
    matcher1.cached_faces = new_faces

    # matcher2 phải thấy ngay lập tức vì cùng dùng chung RAM cache
    assert len(matcher2.cached_faces) == 1
    assert matcher2.cached_faces[0]["code"] == "SHARED_01"
    assert matcher2.cached_matrix is not None
    assert np.allclose(matcher2.cached_matrix[0], mock_vec)


def test_db_down_does_not_block_hot_path():
    """
    Xác nhận match_face, get_camera_area_name, is_camera_public KHÔNG BAO GIỜ
    gọi refresh_cache đồng bộ trên đường nóng, kể cả khi quá hạn cache_ttl và DB bị lỗi / timeout.
    """
    reset_shared_face_cache()
    matcher = FaceMatcher()

    # Nạp dữ liệu ban đầu
    mock_vec = np.zeros(512, dtype=np.float32)
    mock_vec[0] = 1.0
    matcher.cached_faces = [{"code": "U01", "full_name": "User 1", "vector": mock_vec, "is_active": True, "role": "NORMAL_USER"}]
    matcher.public_camera_codes = {"CAM-PUB-01"}
    matcher.camera_area_map = {"CAM-PUB-01": "Cong chinh"}

    # Giả lập thời gian cache đã hết hạn từ lâu (> 30s)
    matcher.last_cache_time = time.time() - 9999.0

    # Mock DB connection timeout 5 giây
    def slow_failing_db(*args, **kwargs):
        time.sleep(5.0)
        raise RuntimeError("Database connection timed out")

    with patch("psycopg2.connect", side_effect=slow_failing_db):
        t0 = time.perf_counter()
        # Gọi get_camera_area_name
        area = matcher.get_camera_area_name("CAM-PUB-01")
        # Gọi is_camera_public
        is_pub = matcher.is_camera_public("CAM-PUB-01")
        # Gọi match_face
        dummy_face = np.zeros((112, 112, 3), dtype=np.uint8)
        with patch.object(matcher.embedder, "extract_embedding", return_value=mock_vec.tolist()):
            match_res = matcher.match_face(dummy_face)

        elapsed = time.perf_counter() - t0

        # Đường nóng PHẢI trả lời trong < 100ms (hoàn toàn không bị block bởi 5s DB timeout)
        assert elapsed < 0.1, f"Đường nóng bị block {elapsed:.3f}s do gọi DB đồng bộ!"
        assert area == "Cong chinh"
        assert is_pub is True
        assert match_res is not None
        assert match_res[0] == "U01"


def test_atomic_swap_and_exponential_backoff():
    """
    Kiểm tra:
    1. Khi DB lỗi, giữ nguyên cache cũ trong RAM (không làm rỗng cache).
    2. Exponential backoff tăng dần từ 2s -> 4s -> 8s -> tối đa 60s.
    3. Khi DB phục hồi, atomic swap dữ liệu mới và reset backoff về 2s.
    """
    reset_shared_face_cache()
    cache = SharedFaceCache(auto_refresh=False)

    # 1. Nạp cache thành công ban đầu
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    # 1 user trong DB
    mock_cur.fetchall.side_effect = [
        [("U01", "User 1", "[" + ",".join(["1.0"] + ["0.0"]*511) + "]", True, "NORMAL_USER")], # users
        [("CAM-01", "Area 1")], # camera_area_map
        [("CAM-01",)] # public_camera_codes
    ]
    mock_conn.cursor.return_value = mock_cur

    with patch.object(cache, "_get_connection", return_value=mock_conn):
        assert cache.refresh_cache() is True
        assert len(cache.cached_faces) == 1
        assert cache.cached_faces[0]["code"] == "U01"
        assert cache.cached_matrix is not None
        assert cache._consecutive_failures == 0
        assert cache._current_backoff == 2.0

    # 2. DB gặp sự cố kết nối
    with patch.object(cache, "_get_connection", side_effect=RuntimeError("DB Connection Lost")):
        # Lần fail 1
        assert cache.refresh_cache() is False
        # Vẫn giữ nguyên cache cũ (không mất dữ liệu)
        assert len(cache.cached_faces) == 1
        assert cache.cached_faces[0]["code"] == "U01"
        assert cache._current_backoff == 4.0

        # Lần fail 2
        assert cache.refresh_cache() is False
        assert cache._current_backoff == 8.0

        # Lần fail 3, 4, 5
        cache.refresh_cache() # 16.0
        cache.refresh_cache() # 32.0
        cache.refresh_cache() # 60.0 (max)
        assert cache._current_backoff == 60.0
        cache.refresh_cache() # vẫn là 60.0
        assert cache._current_backoff == 60.0
        # Dữ liệu cũ vẫn nguyên vẹn
        assert len(cache.cached_faces) == 1

    # 3. DB phục hồi với dữ liệu mới (2 users)
    mock_cur2 = MagicMock()
    mock_cur2.fetchall.side_effect = [
        [
            ("U01", "User 1", "[" + ",".join(["1.0"] + ["0.0"]*511) + "]", True, "NORMAL_USER"),
            ("U02", "User 2", "[" + ",".join(["0.0", "1.0"] + ["0.0"]*510) + "]", True, "NORMAL_USER")
        ],
        [("CAM-01", "Area 1")],
        [("CAM-01",)]
    ]
    mock_conn2 = MagicMock()
    mock_conn2.cursor.return_value = mock_cur2

    with patch.object(cache, "_get_connection", return_value=mock_conn2):
        assert cache.refresh_cache() is True
        # Dữ liệu được hoán đổi nguyên tử sang 2 users
        assert len(cache.cached_faces) == 2
        assert cache.cached_matrix.shape == (2, 512)
        # Backoff được reset về 2.0s
        assert cache._consecutive_failures == 0
        assert cache._current_backoff == 2.0


def test_np_fromstring_fast_parsing():
    """
    Xác nhận parse vector chuỗi bằng np.fromstring cho kết quả chuẩn xác tuyệt đối,
    chuẩn hóa L2 norm đúng bằng 1.0.
    """
    reset_shared_face_cache()
    cache = SharedFaceCache(auto_refresh=False)

    raw_vec = np.random.randn(512).astype(np.float32)
    emb_str = "[" + ",".join(f"{x:.6f}" for x in raw_vec) + "]"

    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_cur.fetchall.side_effect = [
        [("PARSE_TEST", "Parse Test User", emb_str, True, "NORMAL_USER")],
        [],
        []
    ]
    mock_conn.cursor.return_value = mock_cur

    with patch.object(cache, "_get_connection", return_value=mock_conn):
        assert cache.refresh_cache() is True
        assert len(cache.cached_faces) == 1
        parsed_vec = cache.cached_faces[0]["vector"]
        assert isinstance(parsed_vec, np.ndarray)
        assert parsed_vec.shape == (512,)
        assert parsed_vec.dtype == np.float32
        # Kiểm tra chuẩn hóa L2 norm ~ 1.0
        assert np.isclose(np.linalg.norm(parsed_vec), 1.0, atol=1e-5)


def test_event_driven_refresh():
    """
    Xác nhận trigger_immediate_refresh đánh thức background thread cập nhật cache ngay lập tức.
    """
    reset_shared_face_cache()
    cache = SharedFaceCache(auto_refresh=False)
    assert not cache._refresh_event.is_set()

    cache.trigger_immediate_refresh()
    assert cache._refresh_event.is_set()


def test_refresh_faces_cache_endpoint():
    """
    Xác nhận endpoint /api/v1/faces/refresh-cache gọi shared cache refresh và xóa access cache.
    """
    from fastapi.testclient import TestClient
    from src.main import app
    from src.config import settings

    reset_shared_face_cache()
    cache = get_shared_face_cache()

    with patch.object(cache, "refresh_cache", return_value=True) as mock_refresh:
        client = TestClient(app)
        headers = {"X-Service-Key": settings.INTERNAL_SERVICE_KEY}
        resp = client.post("/api/v1/faces/refresh-cache", headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is True
        assert "pipelines_refreshed" in data
        mock_refresh.assert_called_once()
