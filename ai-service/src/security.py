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
