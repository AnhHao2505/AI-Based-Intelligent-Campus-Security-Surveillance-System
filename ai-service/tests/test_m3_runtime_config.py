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
    """Kiểm tra EdgeAgent nhận và kiểm tra kiểu/khoảng giá trị của 3 khóa mới, bỏ qua giá trị sai."""
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

    # Payload sai khoảng giá trị: timeout < 0.5 hoặc > 10.0 -> bỏ qua, giữ nguyên 2.5
    agent._handle_system_config({
        "access_check_timeout_seconds": 0.1,
        "access_decision_cache_ttl": 9999.0,
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

    # Không hợp lệ: timeout > 10.0 -> HTTPException 400
    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(update_ai_runtime_config(AIRuntimeConfigRequest(access_check_timeout_seconds=15.0)))
    assert exc_info.value.status_code == 400

    # Không hợp lệ: decision TTL < 1.0 -> HTTPException 400
    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(update_ai_runtime_config(AIRuntimeConfigRequest(access_decision_cache_ttl=0.2)))
    assert exc_info.value.status_code == 400

    # Reset
    settings.ACCESS_CHECK_TIMEOUT_SECONDS = 1.5
    settings.ACCESS_DECISION_CACHE_TTL = 30.0
    settings.ACCESS_DENY_CACHE_TTL = 5.0
