from unittest.mock import MagicMock, patch
import pytest

from src.core.entity import BoundingBox, FaceDetectionResult
from src.core.face_matcher import FaceMatcher
from src.pipeline.video_pipeline import VideoPipeline


def test_check_camera_access_reads_and_caches_source():
    """
    Điểm 4, bước B: check_camera_access đọc trường 'source' từ Backend response,
    trả về source và lưu vào access_cache.
    """
    matcher = FaceMatcher()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "code": 200,
        "data": {
            "allowed": True,
            "reason": "Người dùng được gán vào khu vực",
            "areaName": "Phòng Nghiên Cứu",
            "source": "ASSIGNED_PERSONNEL"
        }
    }

    with patch("requests.post", return_value=mock_resp):
        res = matcher.check_camera_access("CAM-001", "SE123456", skip_cache=True)
        # (allowed, reason, area_name, source, deny_reason)
        assert len(res) == 5
        allowed, reason, area_name, source, deny_reason = res
        assert allowed is True
        assert source == "ASSIGNED_PERSONNEL"
        assert deny_reason is None

        # get_cached_access trả 5 phần như trên; cache nội bộ lưu 6 phần (thêm expire_at)
        cached = matcher.get_cached_access("CAM-001", "SE123456")
        assert cached is not None
        assert len(cached) == 5
        assert cached[3] == "ASSIGNED_PERSONNEL"
        assert len(matcher.access_cache["CAM-001:SE123456"]) == 6


def test_check_camera_access_missing_source_is_none():
    """
    Điểm 4, bước B: Backend response không có trường 'source' -> source là None.
    """
    matcher = FaceMatcher()
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "code": 200,
        "data": {
            "allowed": True,
            "reason": "Hợp lệ",
            "areaName": "Sảnh Chính"
            # thiếu source
        }
    }

    with patch("requests.post", return_value=mock_resp):
        res = matcher.check_camera_access("CAM-001", "SE999999", skip_cache=True)
        assert len(res) == 5
        allowed, reason, area_name, source, deny_reason = res
        assert allowed is True
        assert source is None

        cached = matcher.get_cached_access("CAM-001", "SE999999")
        assert cached is not None
        assert len(cached) == 5
        assert cached[3] is None


@pytest.mark.requires_model
def test_apply_access_decision_populates_face_info_access_source():
    """
    Điểm 4, bước B: VideoPipeline._apply_access_decision ghi nhận access_source vào face_info.
    """
    pipeline = VideoPipeline(camera_code="CAM-TEST")
    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.9,
        matched_code="SE123456",
        matched_name="Nguyen Van A"
    )

    pipeline._apply_access_decision(
        face_info,
        is_allowed=True,
        access_reason="Có đơn được duyệt",
        area_name="Phòng Lab",
        access_source="ACCESS_REQUEST"
    )

    assert face_info.is_authorized is True
    assert face_info.access_source == "ACCESS_REQUEST"
    assert face_info.access_checked is True
