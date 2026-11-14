"""
Cấu hình runtime AI sửa trên màn Cấu hình (nhóm AI_CONFIG) phải áp ngay cho pipeline mặc định và worker đang chạy,
qua cả đường MQTT (EdgeMqttAgent) lẫn REST (/api/v1/system-config/ai-runtime), dùng chung một bảng khoá.
Khoảng min/max do Backend kiểm khi admin lưu; AI chỉ chặn giá trị vô nghĩa.
"""
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock

from src.config import settings
from src.pipeline.runtime_config import (
    RUNTIME_FIELDS,
    apply_runtime_config,
    current_runtime_config,
    parse_runtime_config,
)


def _fake_pipeline():
    engine = SimpleNamespace(access_grace_period=1.5, unauthorized_alert_cooldown=30.0,
                             max_inactive_seconds=2.0, unknown_alert_cooldown=30.0,
                             after_hour_start="22:00", after_hour_end="06:00")
    return SimpleNamespace(analysis_engine=engine, _suspicious_motion_cooldown=5.0)


class TestRuntimeConfig(unittest.TestCase):
    def setUp(self):
        self._saved = {attr: getattr(settings, attr) for attr, _ in RUNTIME_FIELDS.values()}

    def tearDown(self):
        for attr, val in self._saved.items():
            setattr(settings, attr, val)

    def test_parse_rejects_only_meaningless_values(self):
        values, errors = parse_runtime_config({
            "identity_grace_seconds": 2,
            "unauthorized_alert_cooldown": -5,      # không dương
            "suspicious_motion_cooldown": "abc",    # không phải số
            "face_match_threshold": 1.5,            # ngưỡng cosine > 1
            "access_deny_cache_ttl": True,          # bool không phải số
            "after_hour_start": "23:00",            # khoá khác, bỏ qua
        })
        self.assertEqual(values, {"identity_grace_seconds": 2.0})
        self.assertEqual(len(errors), 4)

    def test_parse_does_not_duplicate_backend_range(self):
        # Khoảng min/max là việc của Backend: AI nhận mọi số dương hợp lý
        values, errors = parse_runtime_config({"access_check_timeout_seconds": 0.1, "access_decision_cache_ttl": 9999})
        self.assertEqual(errors, [])
        self.assertEqual(values, {"access_check_timeout_seconds": 0.1, "access_decision_cache_ttl": 9999.0})

    def test_apply_updates_settings_default_pipeline_and_running_workers(self):
        default_pipe = _fake_pipeline()
        worker = SimpleNamespace(pipeline=_fake_pipeline())
        values, errors = apply_runtime_config(
            {"face_match_threshold": 0.5, "max_inactive_seconds": 8, "identity_grace_seconds": 1.0,
             "unauthorized_alert_cooldown": 60, "suspicious_motion_cooldown": 20},
            default_pipe, [worker],
        )
        self.assertEqual(errors, [])
        self.assertEqual(settings.FACE_MATCH_THRESHOLD, 0.5)
        self.assertEqual(settings.ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS, 1.0)
        self.assertEqual(settings.DEFAULT_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS, 60.0)
        self.assertEqual(settings.SUSPICIOUS_MOTION_COOLDOWN_SECONDS, 20.0)
        for pipe in (default_pipe, worker.pipeline):
            self.assertEqual(pipe.analysis_engine.max_inactive_seconds, 8.0)
            self.assertEqual(pipe.analysis_engine.access_grace_period, 1.0)
            self.assertEqual(pipe.analysis_engine.unauthorized_alert_cooldown, 60.0)
            self.assertEqual(pipe._suspicious_motion_cooldown, 20.0)
        self.assertEqual(current_runtime_config()["face_match_threshold"], 0.5)

    def test_apply_ignores_invalid_without_touching_pipelines(self):
        pipe = _fake_pipeline()
        apply_runtime_config({"identity_grace_seconds": 0}, pipe, [])
        self.assertEqual(pipe.analysis_engine.access_grace_period, 1.5)
        self.assertEqual(settings.ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS,
                         self._saved["ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS"])

    def test_edge_agent_mqtt_system_config_applies_to_running_worker(self):
        from src.integration.edge_agent import EdgeMqttAgent
        worker = MagicMock()
        worker.pipeline = _fake_pipeline()
        default_pipe = _fake_pipeline()
        agent = EdgeMqttAgent(
            mqtt_client_wrapper=MagicMock(), storage=MagicMock(),
            active_workers={"CAM-X": worker}, default_pipeline_getter=lambda: default_pipe,
        )
        agent._handle_system_config({"face_match_threshold": 0.55, "identity_grace_seconds": 2.5,
                                     "suspicious_motion_cooldown": 30, "after_hour_start": "23:30"})
        self.assertEqual(settings.FACE_MATCH_THRESHOLD, 0.55)
        self.assertEqual(worker.pipeline.analysis_engine.access_grace_period, 2.5)
        self.assertEqual(worker.pipeline._suspicious_motion_cooldown, 30.0)
        self.assertEqual(worker.pipeline.analysis_engine.after_hour_start, "23:30")
        self.assertEqual(default_pipe.analysis_engine.access_grace_period, 2.5)


if __name__ == "__main__":
    unittest.main()
