import os
import time
import sqlite3
import logging
import threading
from typing import Callable, List, Optional, Tuple

from ..config import settings, AI_SERVICE_DIR

logger = logging.getLogger(__name__)


class AlertOutbox:
    """
    Hàng đợi bền (SQLite) cho cảnh báo an ninh gửi qua MQTT.
    Cảnh báo được ghi xuống đĩa TRƯỚC khi publish và chỉ bị xoá khi broker xác nhận (PUBACK),
    nên broker tắt hoặc AI service khởi động lại cũng không làm mất cảnh báo.
    Gửi lại an toàn vì backend chống trùng theo event_id.
    """

    def __init__(self, path: str, clock: Callable[[], float] = time.time):
        self.path = path
        self._clock = clock
        parent = os.path.dirname(os.path.abspath(path))
        os.makedirs(parent, exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(path, check_same_thread=False)
        with self._lock:
            self._conn.execute(
                """
                CREATE TABLE IF NOT EXISTS outbox (
                    event_id TEXT PRIMARY KEY,
                    topic TEXT,
                    payload TEXT,
                    created_at REAL,
                    attempts INTEGER,
                    last_attempt_at REAL,
                    last_error TEXT
                )
                """
            )
            self._conn.commit()

    def enqueue(self, event_id: str, topic: str, payload: str) -> bool:
        """Lưu cảnh báo; event_id đã có thì bỏ qua. Trả True nếu là dòng mới."""
        with self._lock:
            cur = self._conn.execute(
                "INSERT OR IGNORE INTO outbox (event_id, topic, payload, created_at, attempts, last_attempt_at, last_error) "
                "VALUES (?, ?, ?, ?, 0, NULL, NULL)",
                (event_id, topic, payload, self._clock()),
            )
            self._conn.commit()
            return cur.rowcount == 1

    def due_rows(self, limit: int = 100, inflight_timeout: float = 30.0) -> List[Tuple[str, str, str]]:
        """Các dòng chưa từng gửi, hoặc đã gửi quá inflight_timeout giây mà chưa có PUBACK."""
        cutoff = self._clock() - inflight_timeout
        with self._lock:
            cur = self._conn.execute(
                "SELECT event_id, topic, payload FROM outbox "
                "WHERE last_attempt_at IS NULL OR last_attempt_at <= ? "
                "ORDER BY created_at, rowid LIMIT ?",
                (cutoff, limit),
            )
            return cur.fetchall()

    def mark_attempt(self, event_id: str, error: Optional[str] = None):
        with self._lock:
            self._conn.execute(
                "UPDATE outbox SET attempts = attempts + 1, last_attempt_at = ?, last_error = ? WHERE event_id = ?",
                (self._clock(), error, event_id),
            )
            self._conn.commit()

    def mark_sent(self, event_id: str):
        with self._lock:
            self._conn.execute("DELETE FROM outbox WHERE event_id = ?", (event_id,))
            self._conn.commit()

    def purge(self, max_rows: int, max_age_hours: float) -> int:
        """Xoá dòng quá tuổi và dòng cũ nhất vượt max_rows. Mỗi dòng bị xoá là một cảnh báo bị bỏ."""
        cutoff = self._clock() - max_age_hours * 3600
        with self._lock:
            expired = self._conn.execute("DELETE FROM outbox WHERE created_at < ?", (cutoff,)).rowcount
            total = self._conn.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]
            overflow = 0
            if total > max_rows:
                overflow = self._conn.execute(
                    "DELETE FROM outbox WHERE event_id IN "
                    "(SELECT event_id FROM outbox ORDER BY created_at, rowid LIMIT ?)",
                    (total - max_rows,),
                ).rowcount
            self._conn.commit()

        dropped = expired + overflow
        if dropped:
            logger.error(
                f"❌ [MQTT Outbox] Đã BỎ {dropped} cảnh báo chưa gửi được "
                f"({expired} quá {max_age_hours} giờ, {overflow} vượt giới hạn {max_rows} dòng)"
            )
        return dropped

    def count(self) -> int:
        with self._lock:
            return self._conn.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]

    def close(self):
        with self._lock:
            self._conn.close()


_outbox: Optional[AlertOutbox] = None
_outbox_lock = threading.Lock()


def get_outbox() -> AlertOutbox:
    """Outbox dùng chung cho cả tiến trình (mọi pipeline/camera ghi vào cùng một file)."""
    global _outbox
    if _outbox is None:
        with _outbox_lock:
            if _outbox is None:
                path = settings.MQTT_OUTBOX_PATH
                if not os.path.isabs(path):
                    path = os.path.join(AI_SERVICE_DIR, path)
                _outbox = AlertOutbox(path)
    return _outbox
