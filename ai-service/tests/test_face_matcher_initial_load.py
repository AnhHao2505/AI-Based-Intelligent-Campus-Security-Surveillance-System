"""FaceMatcher phải nạp face cache ngay khi khởi tạo nếu cache còn rỗng.
Background refresh chỉ chạy sau cache_ttl, nên thiếu bước này mọi người là UNKNOWN lúc service mới khởi động."""
from unittest.mock import MagicMock

from src.core.face_matcher import FaceMatcher


def test_face_matcher_loads_cache_on_init_when_empty():
    cache = MagicMock(cached_faces=[], last_cache_time=0, db_params={})
    FaceMatcher(cache=cache)
    assert cache.refresh_cache.call_count == 1


def test_face_matcher_skips_initial_load_when_cache_ready():
    cache = MagicMock(cached_faces=[{"code": "SV001"}], last_cache_time=123.0, db_params={})
    FaceMatcher(cache=cache)
    assert cache.refresh_cache.call_count == 0
