import base64
import hashlib
import hmac
import time
from typing import Optional
from fastapi import Header, HTTPException
from .config import settings

def require_service_key(x_service_key: Optional[str] = Header(default=None, alias="X-Service-Key")) -> str:
    if not x_service_key:
        raise HTTPException(status_code=401, detail="Invalid service key")
    
    expected_key = settings.INTERNAL_SERVICE_KEY
    if not expected_key or not hmac.compare_digest(x_service_key, expected_key):
        raise HTTPException(status_code=401, detail="Invalid service key")
    
    return x_service_key

def make_stream_token(camera_code: str, exp_unix: int) -> str:
    key_bytes = settings.INTERNAL_SERVICE_KEY.encode("utf-8")
    msg_bytes = f"{camera_code.upper()}|{exp_unix}".encode("utf-8")
    hexdigest = hmac.new(key_bytes, msg_bytes, hashlib.sha256).hexdigest()
    return f"{exp_unix}.{hexdigest}"

def verify_stream_token(camera_code: str, token: Optional[str], now: Optional[float] = None) -> bool:
    if not token or not camera_code:
        return False
    
    parts = token.split(".", 1)
    if len(parts) != 2:
        return False
    
    try:
        exp_unix = int(parts[0])
    except (ValueError, TypeError):
        return False
    
    if now is None:
        now = time.time()
    
    if now > exp_unix:
        return False
    
    key_bytes = settings.INTERNAL_SERVICE_KEY.encode("utf-8")
    msg_bytes = f"{camera_code.upper()}|{exp_unix}".encode("utf-8")
    expected_hexdigest = hmac.new(key_bytes, msg_bytes, hashlib.sha256).hexdigest()
    
    return hmac.compare_digest(parts[1], expected_hexdigest)


INTERNAL_RTSP_USER = "icss-internal"


def _normalize_rtsp_camera_code(camera_code: str) -> str:
    code = camera_code.strip().upper()
    if code.endswith("_MAIN"):
        code = code[:-5]
    return code


def make_internal_rtsp_secret(camera_code: str) -> str:
    key = getattr(settings, "INTERNAL_SERVICE_KEY", "") or ""
    if not camera_code or not key:
        return ""
    msg = f"rtsp-internal|{_normalize_rtsp_camera_code(camera_code)}".encode("utf-8")
    return hmac.new(key.encode("utf-8"), msg, hashlib.sha256).hexdigest()


def build_internal_rtsp_url(rtsp_base: str, path_name: str) -> str:
    clean_base = (rtsp_base or "rtsp://localhost:8554").rstrip("/")
    clean_path = (path_name or "").lstrip("/")
    secret = make_internal_rtsp_secret(clean_path)
    if secret and clean_base.startswith("rtsp://") and "@" not in clean_base:
        clean_base = f"rtsp://{INTERNAL_RTSP_USER}:{secret}@{clean_base[len('rtsp://'):]}"
    return f"{clean_base}/{clean_path}"


def make_internal_api_secret() -> str:
    """F-01: Sinh mật khẩu HMAC tĩnh cho MediaMTX Control API (/v3/*)."""
    key = getattr(settings, "INTERNAL_SERVICE_KEY", "") or ""
    if not key:
        return ""
    return hmac.new(key.encode("utf-8"), b"mediamtx-api", hashlib.sha256).hexdigest()


ENC_ETM_PREFIX = "ENC_ETM:"


def decrypt_mqtt_secret(value: Optional[str], service_key: Optional[str] = None) -> str:
    """
    F-02: Giải mã chuỗi RTSP/password đã được Backend mã hóa Encrypt-then-MAC (ENC_ETM:)
    trước khi gửi qua MQTT Broker.
    """
    if not value:
        return ""
    if not isinstance(value, str) or not value.startswith(ENC_ETM_PREFIX):
        return value

    key = service_key if service_key is not None else (getattr(settings, "INTERNAL_SERVICE_KEY", "") or "")
    if not key:
        raise ValueError("Thiếu INTERNAL_SERVICE_KEY để giải mã thông tin RTSP từ MQTT")

    raw = base64.b64decode(value[len(ENC_ETM_PREFIX):])
    if len(raw) < 48:
        raise ValueError("Dữ liệu ENC_ETM không hợp lệ")

    nonce = raw[:16]
    ciphertext = raw[16:-32]
    tag = raw[-32:]

    enc_key = hashlib.sha256(f"mqtt-enc|{key}".encode("utf-8")).digest()
    mac_key = hashlib.sha256(f"mqtt-mac|{key}".encode("utf-8")).digest()

    expected_tag = hmac.new(mac_key, nonce + ciphertext, hashlib.sha256).digest()
    if not hmac.compare_digest(tag, expected_tag):
        raise ValueError("Chữ ký xác thực ENC_ETM không hợp lệ")

    plain = bytearray(len(ciphertext))
    offset = 0
    counter = 0
    while offset < len(ciphertext):
        block = hashlib.sha256(enc_key + nonce + counter.to_bytes(4, "big")).digest()
        counter += 1
        chunk = min(len(block), len(ciphertext) - offset)
        for i in range(chunk):
            plain[offset + i] = ciphertext[offset + i] ^ block[i]
        offset += chunk

    return plain.decode("utf-8")
