import logging
import threading
from typing import Optional
import requests
from requests.adapters import HTTPAdapter
from urllib3.util import Retry

logger = logging.getLogger(__name__)

_session: Optional[requests.Session] = None
_session_lock = threading.Lock()


def get_http_session(
    pool_connections: int = 10,
    pool_maxsize: int = 20,
    max_retries: int = 1
) -> requests.Session:
    """
    Trả về requests.Session dùng chung cho toàn bộ tiến trình ai-service (P1-04).
    Tối ưu HTTP connection pooling, keep-alive connections, tránh handshake TCP/TLS lặp lại.
    """
    global _session
    if _session is None:
        with _session_lock:
            if _session is None:
                session = requests.Session()
                retry_strategy = Retry(
                    total=max_retries,
                    backoff_factor=0.2,
                    status_forcelist=[502, 503, 504],
                    raise_on_status=False
                )
                adapter = HTTPAdapter(
                    pool_connections=pool_connections,
                    pool_maxsize=pool_maxsize,
                    max_retries=retry_strategy
                )
                session.mount("http://", adapter)
                session.mount("https://", adapter)
                _session = session
                logger.debug("🌐 [HTTPClient] Đã khởi tạo shared requests.Session với connection pooling.")
    return _session


def close_http_session():
    """Đóng HTTP session khi service dừng (shutdown)."""
    global _session
    with _session_lock:
        if _session is not None:
            try:
                _session.close()
                logger.debug("🌐 [HTTPClient] Đã đóng shared requests.Session.")
            except Exception as e:
                logger.warning(f"Lỗi khi đóng shared requests.Session: {e}")
            finally:
                _session = None
