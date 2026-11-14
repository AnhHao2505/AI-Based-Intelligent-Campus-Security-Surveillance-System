"""
V125: chính sách cảnh báo AI sửa trên màn Cấu hình phải áp ngay cho pipeline mặc định và worker đang chạy,
qua cả đường MQTT (EdgeAgent) lẫn REST (/api/v1/system-config/ai-runtime).
"""
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock

from src.config import settings
from src.pipeline.runtime_config import apply_alert_policy, parse_alert_policy


def _fake_pipeline():
    engine = SimpleNamespace(access_grace_period=1.5, unauthorized_alert_cooldown=30.0)
    return SimpleNamespace(analysis_engine=engine, _suspicious_motion_cooldown=5.0)


class TestRuntimeAlertPolicy(unittest.TestCase):
    def setUp(self):
        self._saved = {
            k: getattr(settings, k) for k in (
                "ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS",
                "DEFAULT_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS",
                "SUSPICIOUS_MOTION_COOLDOWN_SECONDS",
            )
        }

    def tearDown(self):
        for k, v in self._saved.items():
            setattr(settings, k, v)

    def test_parse_accepts_valid_and_rejects_out_of_range(self):
        values, errors = parse_alert_policy({
            "identity_grace_seconds": 2,
            "unauthorized_alert_cooldown": 5,       # < 10 -> lỗi
            "suspicious_motion_cooldown": "abc",    # không phải số -> lỗi
            "face_match_threshold": 0.5,            # khoá khác, bỏ qua
        })
        self.assertEqual(values, {"identity_grace_seconds": 2.0})
        self.assertEqual(len(errors), 2)

    def test_apply_updates_settings_default_pipeline_and_running_workers(self):
        default_pipe = _fake_pipeline()
        worker = SimpleNamespace(pipeline=_fake_pipeline())
        values, errors = apply_alert_policy(
            {"identity_grace_seconds": 1.0, "unauthorized_alert_cooldown": 60, "suspicious_motion_cooldown": 20},
            default_pipe, [worker],
        )
        self.assertEqual(errors, [])
        self.assertEqual(settings.ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS, 1.0)
        self.assertEqual(settings.DEFAULT_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS, 60.0)
        self.assertEqual(settings.SUSPICIOUS_MOTION_COOLDOWN_SECONDS, 20.0)
        for pipe in (default_pipe, worker.pipeline):
            self.assertEqual(pipe.analysis_engine.access_grace_period, 1.0)
            self.assertEqual(pipe.analysis_engine.unauthorized_alert_cooldown, 60.0)
            self.assertEqual(pipe._suspicious_motion_cooldown, 20.0)

    def test_apply_ignores_invalid_without_touching_pipelines(self):
        pipe = _fake_pipeline()
        apply_alert_policy({"identity_grace_seconds": 99}, pipe, [])
        self.assertEqual(pipe.analysis_engine.access_grace_period, 1.5)
        self.assertEqual(settings.ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS,
                         self._saved["ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS"])

    def test_edge_agent_mqtt_system_config_applies_policy_to_running_worker(self):
        from src.integration.edge_agent import EdgeMqttAgent
        worker = MagicMock()
        worker.pipeline = _fake_pipeline()
        default_pipe = _fake_pipeline()
        agent = EdgeMqttAgent(
            mqtt_client_wrapper=MagicMock(), storage=MagicMock(),
            active_workers={"CAM-X": worker}, default_pipeline_getter=lambda: default_pipe,
        )
        agent._handle_system_config({"identity_grace_seconds": 2.5, "suspicious_motion_cooldown": 30})
        self.assertEqual(worker.pipeline.analysis_engine.access_grace_period, 2.5)
        self.assertEqual(worker.pipeline._suspicious_motion_cooldown, 30.0)
        self.assertEqual(default_pipe.analysis_engine.access_grace_period, 2.5)


if __name__ == "__main__":
    unittest.main()
