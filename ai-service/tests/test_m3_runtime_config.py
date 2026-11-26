import pytest
from unittest.mock import MagicMock, patch
from src.config import settings
from src.core.face_matcher import FaceMatcher
from src.integration.edge_agent import EdgeMqttAgent
from src.main import AIRuntimeConfigRequest, update_ai_runtime_config
from fastapi import HTTPException

def test_face_matcher_reads_settings_at_invocation_time():
    """Kiểm tra FaceMatcher đọc timeout và TTL động từ settings tại thời điểm gọi, không bị dính cứng."""
    # Reset/cấu hình ban đầu
    settings.ACCESS_CHECK_TIMEOUT_SECONDS = 1.5
    settings.ACCESS_DECISION_CACHE_TTL = 30.0
    settings.ACCESS_DENY_CACHE_TTL = 5.0

    mock_cache = MagicMock()
    mock_cache.db_params = {}
    mock_cache.cached_faces = []
    mock_cache.last_cache_time = 1.0

    with patch("src.core.face_matcher.get_shared_face_cache", return_value=mock_cache), \
         patch("src.core.face_matcher.get_shared_face_embedder", return_value=MagicMock()):
        matcher = FaceMatcher(cache=mock_cache)

    assert matcher.access_cache_ttl == 30.0

    # Đổi settings tại runtime
    settings.ACCESS_CHECK_TIMEOUT_SECONDS = 4.2
    settings.ACCESS_DECISION_CACHE_TTL = 50.0
    settings.ACCESS_DENY_CACHE_TTL = 12.0

    assert matcher.access_cache_ttl == 50.0

    # Mock response từ backend khi gọi checkEntry (allowed=True)
    mock_resp_allowed = MagicMock()
    mock_resp_allowed.status_code = 200
    mock_resp_allowed.json.return_value = {
        "data": {
            "allowed": True,
            "reason": "Thẻ hợp lệ",
            "areaName": "Sảnh Chính",
            "source": "FACE"
        }
    }

    mock_session = MagicMock()
    mock_session.post.return_value = mock_resp_allowed
    matcher.session = mock_session

    allowed, reason, area_name, source, deny_reason = matcher.check_camera_access("CAM-01", "NV01")
    assert allowed is True
    # Kiểm tra timeout truyền vào post là 4.2 (giá trị mới)
    mock_session.post.assert_called_with(
        "http://localhost:8080/api/access-control/check-entry",
        json={"userCode": "NV01", "cameraCode": "CAM-01"},
        headers={"X-Service-Key": settings.INTERNAL_SERVICE_KEY},
        timeout=4.2
    )
    # Kiểm tra TTL trong cache được tính theo 50.0s (ACCESS_DECISION_CACHE_TTL)
    cache_entry = matcher.access_cache.get("CAM-01:NV01")
    assert cache_entry is not None
    # expire_time ~ now + 50.0
    import time
    assert cache_entry[3] > time.time() + 40.0

    # Trường hợp allowed=False -> dùng ACCESS_DENY_CACHE_TTL (12.0)
    mock_resp_deny = MagicMock()
    mock_resp_deny.status_code = 200
    mock_resp_deny.json.return_value = {
        "data": {
            "allowed": False,
            "reason": "Không có quyền",
            "areaName": "Phòng Server",
            "source": "NONE"
        }
    }
    mock_session.post.return_value = mock_resp_deny
    matcher.access_cache.clear()

    allowed2, reason2, _, _, _ = matcher.check_camera_access("CAM-01", "NV02")
    assert allowed2 is False
    cache_entry2 = matcher.access_cache.get("CAM-01:NV02")
    assert cache_entry2 is not None
    # expire_time ~ now + 12.0
    now = time.time()
    assert now < cache_entry2[3] <= now + 15.0

    # Trả về giá trị mặc định
    settings.ACCESS_CHECK_TIMEOUT_SECONDS = 1.5
    settings.ACCESS_DECISION_CACHE_TTL = 30.0
    settings.ACCESS_DENY_CACHE_TTL = 5.0

def test_edge_agent_handle_system_config_validation():
    """Kiểm tra EdgeAgent nhận 3 khóa mới và bỏ qua giá trị vô nghĩa (khoảng min/max do Backend kiểm khi lưu)."""
    agent = EdgeMqttAgent(mqtt_client_wrapper=MagicMock(), storage=MagicMock(), active_workers={})
    settings.ACCESS_CHECK_TIMEOUT_SECONDS = 1.5
    settings.ACCESS_DECISION_CACHE_TTL = 30.0
    settings.ACCESS_DENY_CACHE_TTL = 5.0

    # Payload hợp lệ
    agent._handle_system_config({
        "access_check_timeout_seconds": 2.5,
        "access_decision_cache_ttl": 60.0,
        "access_deny_cache_ttl": 10.0
    })
    assert settings.ACCESS_CHECK_TIMEOUT_SECONDS == 2.5
    assert settings.ACCESS_DECISION_CACHE_TTL == 60.0
    assert settings.ACCESS_DENY_CACHE_TTL == 10.0

    # Giá trị không dương -> bỏ qua, giữ nguyên
    agent._handle_system_config({
        "access_check_timeout_seconds": 0,
        "access_decision_cache_ttl": -1.0,
        "access_deny_cache_ttl": -5.0
    })
    assert settings.ACCESS_CHECK_TIMEOUT_SECONDS == 2.5
    assert settings.ACCESS_DECISION_CACHE_TTL == 60.0
    assert settings.ACCESS_DENY_CACHE_TTL == 10.0

    # Payload sai kiểu dữ liệu -> bỏ qua
    agent._handle_system_config({
        "access_check_timeout_seconds": "not-a-number",
        "access_decision_cache_ttl": None,
        "access_deny_cache_ttl": [1, 2]
    })
    assert settings.ACCESS_CHECK_TIMEOUT_SECONDS == 2.5
    assert settings.ACCESS_DECISION_CACHE_TTL == 60.0
    assert settings.ACCESS_DENY_CACHE_TTL == 10.0

    # Reset
    settings.ACCESS_CHECK_TIMEOUT_SECONDS = 1.5
    settings.ACCESS_DECISION_CACHE_TTL = 30.0
    settings.ACCESS_DENY_CACHE_TTL = 5.0



def test_main_update_ai_runtime_config_endpoint():
    """Kiểm tra endpoint /api/v1/system-config/ai-runtime nhận diện các khóa mới và validate."""
    import asyncio
    # Hợp lệ
    req = AIRuntimeConfigRequest(
        access_check_timeout_seconds=3.0,
        access_decision_cache_ttl=120.0,
        access_deny_cache_ttl=8.0
    )
    res = asyncio.run(update_ai_runtime_config(req))
    assert res["success"] is True
    assert res["access_check_timeout_seconds"] == 3.0
    assert res["access_decision_cache_ttl"] == 120.0
    assert res["access_deny_cache_ttl"] == 8.0
    assert settings.ACCESS_CHECK_TIMEOUT_SECONDS == 3.0

    # Không hợp lệ: timeout không dương -> HTTPException 400
    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(update_ai_runtime_config(AIRuntimeConfigRequest(access_check_timeout_seconds=-1.0)))
    assert exc_info.value.status_code == 400

    # Không hợp lệ: ngưỡng khớp mặt > 1 -> HTTPException 400, và không áp dở dang khoá hợp lệ đi kèm
    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(update_ai_runtime_config(AIRuntimeConfigRequest(
            access_decision_cache_ttl=45.0, face_match_threshold=1.5)))
    assert exc_info.value.status_code == 400
    assert settings.ACCESS_DECISION_CACHE_TTL == 120.0

    # Reset
    settings.ACCESS_CHECK_TIMEOUT_SECONDS = 1.5
    settings.ACCESS_DECISION_CACHE_TTL = 30.0
    settings.ACCESS_DENY_CACHE_TTL = 5.0


def test_recording_retention_config_and_time_based_deletion():
    """V126: Kiểm tra cập nhật thời hạn lưu trữ video và xoá MinIO dựa theo thời gian (giờ)."""
    import asyncio
    from datetime import datetime, timezone, timedelta
    from src.integration.storage_service import StorageService

    # 1. Cập nhật qua AIRuntimeConfigRequest
    req = AIRuntimeConfigRequest(recording_retention_hours=2.0)
    res = asyncio.run(update_ai_runtime_config(req))
    assert res["success"] is True
    assert res["recording_retention_hours"] == 2.0
    assert settings.RECORDING_RETENTION_HOURS == 2.0

    # 2. Cập nhật qua EdgeMqttAgent (_handle_system_config)
    agent = EdgeMqttAgent(mqtt_client_wrapper=MagicMock(), storage=MagicMock(), active_workers={})
    agent._handle_system_config({"recording_retention_hours": 1.5})
    assert settings.RECORDING_RETENTION_HOURS == 1.5

    # 3. Test StorageService.enforce_recording_retention xoá theo thời gian
    storage = StorageService()
    storage.is_connected = True
    storage.client = MagicMock()

    now = datetime.now(timezone.utc)
    # Giả lập 3 object: 1 cái 3 giờ trước (quá 1.5h -> xoá), 1 cái 2 giờ trước (quá 1.5h -> xoá), 1 cái 30 phút trước (giữ)
    obj_old1 = MagicMock()
    obj_old1.object_name = "CAM-01/old_1.mp4"
    obj_old1.last_modified = now - timedelta(hours=3)

    obj_old2 = MagicMock()
    obj_old2.object_name = "CAM-01/old_2.mp4"
    obj_old2.last_modified = now - timedelta(hours=2)

    obj_recent = MagicMock()
    obj_recent.object_name = "CAM-01/recent.mp4"
    obj_recent.last_modified = now - timedelta(minutes=30)

    storage.client.list_objects.return_value = [obj_old1, obj_old2, obj_recent]

    deleted = storage.enforce_recording_retention("CAM-01")
    assert "CAM-01/old_1.mp4" in deleted
    assert "CAM-01/old_2.mp4" in deleted
    assert "CAM-01/recent.mp4" not in deleted
    assert storage.client.remove_object.call_count == 2

    # Reset
    settings.RECORDING_RETENTION_HOURS = 0.5


def test_suspicious_motion_min_duration_runtime_config():
    """Kiểm tra cập nhật SUSPICIOUS_MOTION_MIN_DURATION qua AIRuntimeConfigRequest."""
    import asyncio
    old_val = settings.SUSPICIOUS_MOTION_MIN_DURATION
    try:
        req = AIRuntimeConfigRequest(suspicious_motion_min_duration=0.8)
        res = asyncio.run(update_ai_runtime_config(req))
        assert res["success"] is True
        assert res["suspicious_motion_min_duration"] == 0.8
        assert settings.SUSPICIOUS_MOTION_MIN_DURATION == 0.8
    finally:
        settings.SUSPICIOUS_MOTION_MIN_DURATION = old_val


def test_main_update_ai_runtime_config_night_allowed_sources():
    """V150: Kiểm tra endpoint /api/v1/system-config/ai-runtime nhận và validate night_allowed_access_sources."""
    import asyncio
    old_sources = list(settings.NIGHT_ALLOWED_ACCESS_SOURCES)
    try:
        req = AIRuntimeConfigRequest(night_allowed_access_sources=["ASSIGNED_PERSONNEL", "INCIDENT_RESPONSE"])
        res = asyncio.run(update_ai_runtime_config(req))
        assert res["success"] is True
        assert res["night_allowed_access_sources"] == ["ASSIGNED_PERSONNEL", "INCIDENT_RESPONSE"]
        assert settings.NIGHT_ALLOWED_ACCESS_SOURCES == ["ASSIGNED_PERSONNEL", "INCIDENT_RESPONSE"]

        # Danh sách rỗng -> 400 và giữ nguyên giá trị cũ
        with pytest.raises(HTTPException) as exc_empty:
            asyncio.run(update_ai_runtime_config(AIRuntimeConfigRequest(night_allowed_access_sources=[])))
        assert exc_empty.value.status_code == 400
        assert settings.NIGHT_ALLOWED_ACCESS_SOURCES == ["ASSIGNED_PERSONNEL", "INCIDENT_RESPONSE"]

        # Giá trị lạ -> 400 và giữ nguyên giá trị cũ
        with pytest.raises(HTTPException) as exc_bad:
            asyncio.run(update_ai_runtime_config(AIRuntimeConfigRequest(night_allowed_access_sources=["INVALID"])))
        assert exc_bad.value.status_code == 400
        assert settings.NIGHT_ALLOWED_ACCESS_SOURCES == ["ASSIGNED_PERSONNEL", "INCIDENT_RESPONSE"]
    finally:
        settings.NIGHT_ALLOWED_ACCESS_SOURCES = old_sources



