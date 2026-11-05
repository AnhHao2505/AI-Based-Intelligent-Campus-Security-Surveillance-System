from unittest.mock import MagicMock, patch
import time
from datetime import datetime, timezone, timedelta
import pytest
import requests
from src.core.face_matcher import FaceMatcher
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.core.entity import BoundingBox, FaceDetectionResult


def create_matcher():
    with patch("src.core.face_matcher.FaceMatcher._get_connection"):
        matcher = FaceMatcher(
            db_host="localhost",
            db_port=5432,
            db_user="sep",
            db_pass="secret",
            db_name="campus_security"
        )
    matcher.public_camera_codes = {"CAM-GATE-01"}
    matcher.camera_area_map = {
        "CAM-GATE-01": "Cổng chính (Công cộng)",
        "CAM-SERVER-01": "Phòng máy chủ (Khu vực hạn chế)"
    }
    return matcher


def test_check_camera_access_retries_once_on_timeout():
    """Nhánh 1: check_camera_access retry 1 lần khi timeout; lần 2 thành công thì trả về kết quả"""
    matcher = create_matcher()

    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "data": {
            "allowed": True,
            "reason": "Quyền hợp lệ từ Backend",
            "areaName": "Cổng chính"
        }
    }

    with patch("requests.post", side_effect=[requests.exceptions.Timeout("Timeout attempt 1"), mock_resp]) as mock_post:
        allowed, reason, area, *rest = matcher.check_camera_access("CAM-GATE-01", "SE123456", skip_cache=True)
        assert mock_post.call_count == 2, f"Phải retry đúng 1 lần (tổng 2 lần gọi), thực tế gọi: {mock_post.call_count}"
        assert allowed is True
        assert "Quyền hợp lệ từ Backend" in reason
        assert area == "Cổng chính"


def test_check_camera_access_falls_back_to_stale_cache_on_persistent_failure():
    """Nhánh 2: Backend lỗi cả 2 lần nhưng có cache cũ (kể cả quá hạn TTL) -> stale-if-error"""
    matcher = create_matcher()
    # Nạp cache cũ đã quá hạn (expire_at < now)
    old_time = time.time() - 100.0
    cache_key = "CAM-SERVER-01:SE123456"
    matcher.access_cache[cache_key] = (True, "Đơn phê duyệt hợp lệ", "Phòng máy chủ", old_time)

    with patch("requests.post", side_effect=requests.exceptions.ConnectionError("Connection refused")):
        allowed, reason, area, *rest = matcher.check_camera_access("CAM-SERVER-01", "SE123456", skip_cache=False)
        assert allowed is True
        assert "Đơn phê duyệt hợp lệ" in reason
        assert area == "Phòng máy chủ"


def test_check_camera_access_public_area_no_cache_allows_and_logs():
    """Nhánh 3: Không có cache + Backend sập + Camera công cộng -> cho phép lưu thông, không cảnh báo vi phạm"""
    matcher = create_matcher()

    with patch("requests.post", side_effect=requests.exceptions.Timeout("Backend down")):
        allowed, reason, area, *rest = matcher.check_camera_access("CAM-GATE-01", "SE999999", skip_cache=True)
        assert allowed is True, "Tại khu vực công cộng khi Backend sập và không có cache, phải cho phép lưu thông"
        assert "Backend không phản hồi" in reason


def test_check_camera_access_restricted_area_no_cache_fail_closed_with_backend_unresponsive():
    """Nhánh 4: Không có cache + Backend sập + Khu vực hạn chế -> Fail-Closed và ghi rõ 'Backend không phản hồi'"""
    matcher = create_matcher()

    with patch("requests.post", side_effect=requests.exceptions.Timeout("Backend down")):
        allowed, reason, area, *rest = matcher.check_camera_access("CAM-SERVER-01", "SE999999", skip_cache=True)
        assert allowed is False, "Tại khu vực hạn chế khi Backend sập, bắt buộc Fail-Closed (allowed=False)"
        assert "Backend không phản hồi" in reason

    # Đưa kết quả này vào FrameAnalysisEngine để kiểm tra format alert
    engine = FrameAnalysisEngine(after_hour_start="22:00", after_hour_end="06:00")
    bbox = BoundingBox(10, 10, 50, 100)
    face_info = FaceDetectionResult(
        bbox=bbox,
        score=0.95,
        matched_code="SE999999",
        matched_name="Kẻ Đột Nhập",
        is_authorized=allowed,
        access_checked=True,
        access_reason=reason,
        area_name=area
    )
    engine.associate_face(track_id=1, face_result=face_info)

    # Ban ngày (14:00) tại khu vực hạn chế
    t_day = datetime(2026, 11, 4, 14, 0, 0, tzinfo=timezone(timedelta(hours=7))).timestamp()

    _, alerts, _ = engine.process_frame(
        detected_tracks=[(1, bbox)],
        camera_code="CAM-SERVER-01",
        current_time=t_day
    )
    assert len(alerts) == 1
    alert = alerts[0]
    # Giữ nguyên contract: event_type phải là UNAUTHORIZED_PERSON
    assert alert.event_type == "UNAUTHORIZED_PERSON"
    # Details phải chứa "Backend không phản hồi"
    assert "Backend không phản hồi" in alert.details, f"Details phải chứa 'Backend không phản hồi', thực tế: {alert.details}"


def test_check_camera_access_stale_cache_older_than_max_is_rejected():
    """
    BUG-02: Stale cache quá hạn ACCESS_STALE_MAX_SECONDS (mặc định 600s)
    thì KHÔNG được dùng lại. Phải chuyển tiếp sang chính sách Fail-Closed.
    """
    matcher = create_matcher()
    # Cache đã hết hạn 650 giây trước (> 600s)
    old_expired_time = time.time() - 650.0
    cache_key = "CAM-SERVER-01:SE123456"
    matcher.access_cache[cache_key] = (True, "Đơn phê duyệt cũ", "Phòng máy chủ", old_expired_time, "ACCESS_REQUEST")

    with patch("requests.post", side_effect=requests.exceptions.ConnectionError("Connection refused")):
        allowed, reason, area, *rest = matcher.check_camera_access("CAM-SERVER-01", "SE123456", skip_cache=False)
        # Vì cache quá cũ (> 600s), không được dùng stale cache -> Fail-Closed
        assert allowed is False, f"Stale cache quá hạn 600s không được dùng, nhưng allowed lại là {allowed}"
        assert "Backend không phản hồi" in reason
        assert "Không thể xác thực quyền truy cập" in reason
