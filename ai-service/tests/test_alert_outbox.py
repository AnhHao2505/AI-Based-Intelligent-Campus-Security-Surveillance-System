import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from src.integration.alert_outbox import AlertOutbox


class FakeClock:
    def __init__(self, now: float = 1_000_000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now


def test_enqueue_same_event_id_twice_keeps_one_row(tmp_path):
    outbox = AlertOutbox(str(tmp_path / "outbox.sqlite3"))

    assert outbox.enqueue("evt-1", "campus/alerts/incidents", '{"event_id":"evt-1"}') is True
    assert outbox.enqueue("evt-1", "campus/alerts/incidents", '{"event_id":"evt-1"}') is False
    assert outbox.count() == 1


def test_mark_sent_removes_row(tmp_path):
    outbox = AlertOutbox(str(tmp_path / "outbox.sqlite3"))
    outbox.enqueue("evt-1", "t", "p")

    outbox.mark_sent("evt-1")

    assert outbox.count() == 0


def test_rows_survive_reopen(tmp_path):
    path = str(tmp_path / "nested" / "outbox.sqlite3")
    first = AlertOutbox(path)
    first.enqueue("evt-1", "campus/alerts/incidents", '{"event_id":"evt-1"}')
    first.close()

    reopened = AlertOutbox(path)

    assert reopened.count() == 1
    assert reopened.due_rows(10, 30.0) == [("evt-1", "campus/alerts/incidents", '{"event_id":"evt-1"}')]


def test_due_rows_skips_inflight_until_timeout(tmp_path):
    clock = FakeClock()
    outbox = AlertOutbox(str(tmp_path / "outbox.sqlite3"), clock=clock)
    outbox.enqueue("evt-1", "t", "p")
    assert [r[0] for r in outbox.due_rows(10, 30.0)] == ["evt-1"]

    outbox.mark_attempt("evt-1")
    clock.now += 29
    assert outbox.due_rows(10, 30.0) == []

    clock.now += 2
    assert [r[0] for r in outbox.due_rows(10, 30.0)] == ["evt-1"]


def test_purge_drops_rows_older_than_max_age(tmp_path):
    clock = FakeClock()
    outbox = AlertOutbox(str(tmp_path / "outbox.sqlite3"), clock=clock)
    outbox.enqueue("old", "t", "p")
    clock.now += 2 * 3600
    outbox.enqueue("new", "t", "p")

    dropped = outbox.purge(max_rows=100, max_age_hours=1.0)

    assert dropped == 1
    assert [r[0] for r in outbox.due_rows(10, 30.0)] == ["new"]


def test_purge_keeps_newest_rows_within_max_rows(tmp_path):
    clock = FakeClock()
    outbox = AlertOutbox(str(tmp_path / "outbox.sqlite3"), clock=clock)
    for i in range(5):
        outbox.enqueue(f"evt-{i}", "t", "p")
        clock.now += 1

    dropped = outbox.purge(max_rows=2, max_age_hours=24.0)

    assert dropped == 3
    assert [r[0] for r in outbox.due_rows(10, 30.0)] == ["evt-3", "evt-4"]
