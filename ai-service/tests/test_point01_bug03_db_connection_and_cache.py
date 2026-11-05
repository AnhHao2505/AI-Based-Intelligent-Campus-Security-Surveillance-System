import time
from unittest.mock import MagicMock, patch
import pytest

from src.pipeline.stream_worker import CameraStreamWorker
from src.core.face_matcher import FaceMatcher


def test_fetch_roi_geometry_closes_connection_on_success():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_cur.fetchone.return_value = ['{"polygons": [{"label": "test"}]}']
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    with patch("psycopg2.connect", return_value=mock_conn):
        res = CameraStreamWorker._fetch_roi_geometry("CAM-001")
        assert res == {"polygons": [{"label": "test"}]}
        # conn.close() MUST be called even when row is found and returned early
        mock_conn.close.assert_called_once()


def test_fetch_roi_geometry_closes_connection_on_exception():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_cur.execute.side_effect = RuntimeError("DB execute failed")
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    with patch("psycopg2.connect", return_value=mock_conn):
        res = CameraStreamWorker._fetch_roi_geometry("CAM-001")
        assert res is None
        mock_conn.close.assert_called_once()


def test_refresh_cache_closes_conn_and_does_not_advance_last_cache_time_on_error():
    with patch.object(FaceMatcher, "refresh_cache"):
        matcher = FaceMatcher()
        matcher.last_cache_time = 0
        matcher.cached_faces = []

    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_cur.execute.side_effect = RuntimeError("Database offline during fetchall")
    mock_conn.cursor.return_value = mock_cur

    with patch.object(matcher, "_get_connection", return_value=mock_conn):
        matcher.refresh_cache()
        # Connection must be closed
        mock_conn.close.assert_called_once()
        # last_cache_time must NOT be advanced to now (must remain 0 so subsequent calls can retry)
        assert matcher.last_cache_time == 0
