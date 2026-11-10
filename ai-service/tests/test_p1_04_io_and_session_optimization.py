import os
import sys
import tempfile
import threading
import sqlite3
from unittest.mock import MagicMock, patch
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.integration.storage_service import StorageService
from src.integration.alert_outbox import AlertOutbox
from src.utils.http_client import get_http_session


def test_minio_bucket_exists_caching_eliminates_redundant_checks():
    """
    Xác nhận P1-04:
    - Khi khởi tạo MinIO, các bucket được kiểm tra và ghi nhận vào cache (_verified_buckets).
    - Các lần upload_bytes hoặc upload_video_file tiếp theo KHÔNG gọi lại bucket_exists qua mạng.
    """
    with patch("minio.Minio") as mock_minio_cls:
        mock_client = MagicMock()
        mock_client.bucket_exists.return_value = True
        mock_minio_cls.return_value = mock_client

        storage = StorageService()
        assert storage.is_connected is True
        initial_check_count = mock_client.bucket_exists.call_count
        assert initial_check_count > 0, "Phải kiểm tra bucket khi khởi tạo"

        # Thực hiện 5 lần upload liên tiếp
        for i in range(5):
            success = storage.upload_bytes("security-evidence", f"test_{i}.jpg", b"fake_jpeg_bytes")
            assert success is True

        # bucket_exists KHÔNG được gọi thêm bất kỳ lần nào trong suốt 5 lần upload
        assert mock_client.bucket_exists.call_count == initial_check_count, (
            f"Kỳ vọng {initial_check_count} lần gọi nhưng thực tế gọi {mock_client.bucket_exists.call_count} lần "
            "(chưa cache bucket_exists)"
        )
        assert mock_client.put_object.call_count == 5


def test_alert_outbox_wal_mode_and_concurrency():
    """
    Xác nhận P1-04:
    - AlertOutbox tạo SQLite database trên đĩa với chế độ WAL (Write-Ahead Logging) và synchronous=NORMAL.
    - Đảm bảo đọc và ghi đồng thời không bị lỗi database locked.
    """
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tmp:
        db_path = tmp.name

    try:
        outbox = AlertOutbox(db_path)

        # Kiểm tra PRAGMA journal_mode
        cur = outbox._conn.execute("PRAGMA journal_mode;")
        mode = cur.fetchone()[0]
        assert str(mode).lower() == "wal", f"Kỳ vọng journal_mode='wal', thực tế: {mode}"

        # Kiểm tra PRAGMA synchronous (NORMAL = 1)
        cur = outbox._conn.execute("PRAGMA synchronous;")
        sync = cur.fetchone()[0]
        assert sync in (1, "1", "NORMAL", "normal"), f"Kỳ vọng synchronous=NORMAL (1), thực tế: {sync}"

        # Test đọc ghi song song từ 2 kết nối khác nhau
        errors = []
        def writer_job():
            try:
                conn2 = sqlite3.connect(db_path, timeout=5.0)
                for i in range(20):
                    conn2.execute(
                        "INSERT OR IGNORE INTO outbox (event_id, topic, payload, created_at, attempts) VALUES (?, ?, ?, ?, ?)",
                        (f"thread_evt_{i}", "topic", "{}", 1000.0 + i, 0)
                    )
                    conn2.commit()
                conn2.close()
            except Exception as e:
                errors.append(e)

        t = threading.Thread(target=writer_job)
        t.start()

        # Luồng chính đồng thời enqueue và query due_rows
        for i in range(20):
            outbox.enqueue(f"main_evt_{i}", "topic", "{}")
            outbox.due_rows(limit=10)

        t.join(timeout=3.0)
        assert len(errors) == 0, f"Đọc ghi song song gặp lỗi: {errors}"

    finally:
        outbox._conn.close()
        for ext in ["", "-wal", "-shm"]:
            f = db_path + ext
            if os.path.exists(f):
                try:
                    os.remove(f)
                except Exception:
                    pass


def test_shared_http_session_connection_pooling():
    """
    Xác nhận P1-04:
    - get_http_session trả về cùng một instance Session (Singleton / Reusable).
    - Adapter HTTP/HTTPS được cấu hình pool_connections >= 10 và pool_maxsize >= 20.
    """
    session1 = get_http_session()
    session2 = get_http_session()
    assert session1 is session2, "get_http_session phải trả về cùng một Session dùng chung"

    http_adapter = session1.adapters.get("http://")
    assert http_adapter is not None, "Phải có adapter cho http://"
    assert getattr(http_adapter, "_pool_connections", 0) >= 10
    assert getattr(http_adapter, "_pool_maxsize", 0) >= 20
