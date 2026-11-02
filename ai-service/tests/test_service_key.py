import time
import pytest
from fastapi import FastAPI, Depends, HTTPException, Query
from fastapi.testclient import TestClient
from src.config import settings
from src.security import require_service_key, make_stream_token, verify_stream_token

# T1 Vector chung (Java / Python)
TEST_SERVICE_KEY = "0123456789abcdef0123456789abcdef"
TEST_CAMERA = "CAM-001"
TEST_EXP = 1893456000
EXPECTED_TOKEN = "1893456000.83208ab377034e26a5a0ba3f022dae5dcc22f673d99c2de3ab46f8d39f93fcde"

def test_t1_common_vector():
    orig_key = settings.INTERNAL_SERVICE_KEY
    try:
        settings.INTERNAL_SERVICE_KEY = TEST_SERVICE_KEY
        token = make_stream_token(TEST_CAMERA, TEST_EXP)
        assert token == EXPECTED_TOKEN
        assert verify_stream_token(TEST_CAMERA, EXPECTED_TOKEN, now=1893455000) is True
    finally:
        settings.INTERNAL_SERVICE_KEY = orig_key

def test_verify_stream_token_cases():
    orig_key = settings.INTERNAL_SERVICE_KEY
    try:
        settings.INTERNAL_SERVICE_KEY = TEST_SERVICE_KEY
        # Hợp lệ
        assert verify_stream_token("CAM-001", EXPECTED_TOKEN, now=TEST_EXP - 10) is True
        # Không phân biệt chữ hoa/thường mã camera
        assert verify_stream_token("cam-001", EXPECTED_TOKEN, now=TEST_EXP - 10) is True
        # Hết hạn
        assert verify_stream_token("CAM-001", EXPECTED_TOKEN, now=TEST_EXP + 1) is False
        # Sai camera
        assert verify_stream_token("CAM-002", EXPECTED_TOKEN, now=TEST_EXP - 10) is False
        # Sửa 1 ký tự cuối
        tampered_char = "0" if EXPECTED_TOKEN[-1] != "0" else "1"
        tampered_token = EXPECTED_TOKEN[:-1] + tampered_char
        assert verify_stream_token("CAM-001", tampered_token, now=TEST_EXP - 10) is False
        # Token dị dạng / None
        assert verify_stream_token("CAM-001", "", now=TEST_EXP - 10) is False
        assert verify_stream_token("CAM-001", None, now=TEST_EXP - 10) is False
        assert verify_stream_token("CAM-001", "invalid_token_format", now=TEST_EXP - 10) is False
        assert verify_stream_token("CAM-001", "abc.def", now=TEST_EXP - 10) is False
    finally:
        settings.INTERNAL_SERVICE_KEY = orig_key

def test_require_service_key_http_cases():
    orig_key = settings.INTERNAL_SERVICE_KEY
    try:
        settings.INTERNAL_SERVICE_KEY = TEST_SERVICE_KEY
        app = FastAPI()

        @app.get("/health")
        def health():
            return {"status": "UP"}

        @app.get("/api/v1/protected", dependencies=[Depends(require_service_key)])
        def protected():
            return {"success": True}

        @app.get("/api/v1/stream/live-annotated")
        def live_annotated(camera_code: str = Query(...), token: str = Query(...)):
            if not verify_stream_token(camera_code, token, time.time()):
                raise HTTPException(status_code=401, detail="Token luồng không hợp lệ hoặc đã hết hạn")
            return {"status": "STREAMING", "camera": camera_code}

        client = TestClient(app)

        # 1. /health không cần khoá
        res = client.get("/health")
        assert res.status_code == 200
        assert res.json() == {"status": "UP"}

        # 2. Thiếu header -> 401
        res = client.get("/api/v1/protected")
        assert res.status_code == 401
        assert res.json()["detail"] == "Invalid service key"

        # 3. Sai header -> 401
        res = client.get("/api/v1/protected", headers={"X-Service-Key": "wrong-service-key-wrong-service-key"})
        assert res.status_code == 401
        assert res.json()["detail"] == "Invalid service key"

        # 4. Đúng header -> 200 (không 401)
        res = client.get("/api/v1/protected", headers={"X-Service-Key": TEST_SERVICE_KEY})
        assert res.status_code == 200
        assert res.json() == {"success": True}

        # 5. live-annotated token sai / thiếu -> 401
        res = client.get("/api/v1/stream/live-annotated?camera_code=CAM-001")
        assert res.status_code == 422  # Thiếu query param token
        res = client.get("/api/v1/stream/live-annotated?camera_code=CAM-001&token=badtoken")
        assert res.status_code == 401
        # live-annotated token đúng
        valid_now_token = make_stream_token("CAM-001", int(time.time()) + 60)
        res = client.get(f"/api/v1/stream/live-annotated?camera_code=CAM-001&token={valid_now_token}")
        assert res.status_code == 200
    finally:
        settings.INTERNAL_SERVICE_KEY = orig_key

def test_main_app_routes_have_require_service_key():
    from src.main import app

    found_api_v1_routes = 0
    for r in app.routes:
        path = getattr(r, "path", "")
        if not path.startswith("/api/v1/"):
            continue

        found_api_v1_routes += 1
        if path == "/api/v1/stream/live-annotated":
            # Không yêu cầu require_service_key vì dùng token URL
            dep_functions = [d.dependency for d in getattr(r, "dependencies", [])]
            assert require_service_key not in dep_functions, (
                f"Route {path} không được có require_service_key dependency"
            )
        else:
            dep_functions = [d.dependency for d in getattr(r, "dependencies", [])]
            assert require_service_key in dep_functions, (
                f"Sót dependency require_service_key tại route: {path}"
            )

    assert found_api_v1_routes >= 14, f"Số lượng route /api/v1/* kiểm tra được: {found_api_v1_routes}"
