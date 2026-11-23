import os
import json
import unittest
from unittest.mock import MagicMock, patch

os.environ.setdefault("MINIO_ROOT_USER", "minioadmin")
os.environ.setdefault("MINIO_ROOT_PASSWORD", "minioadmin123")
os.environ.setdefault("INTERNAL_SERVICE_KEY", "test-internal-service-key-at-least-32-chars")
os.environ.setdefault("MQTT_ENABLED", "False")
os.environ.setdefault("MINIO_ENABLED", "False")

OLD_POLYS = [{"label": "Vùng cũ", "vertices": [{"x": 0.25, "y": 0.63}, {"x": 0.81, "y": 0.63}, {"x": 0.81, "y": 0.82}, {"x": 0.25, "y": 0.82}]}]
NEW_POLYS = [{"label": "Vùng mới", "vertices": [{"x": 0.46, "y": 0.71}, {"x": 0.71, "y": 0.73}, {"x": 0.75, "y": 0.95}, {"x": 0.42, "y": 0.95}]}]


def _stream_cfg(code="CAM-007", roi=None):
    cfg = {"camera_code": code, "rtsp_url": "rtsp://192.168.1.105:554/live", "enabled": True}
    if roi is not None:
        cfg["roi_geometry"] = {"polygons": roi}
    return json.dumps(cfg).encode("utf-8")


def _roi_cfg(polys, code="CAM-007"):
    return json.dumps({"camera_code": code, "polygons": polys}).encode("utf-8")


class TestEdgeAgentStaleRoi(unittest.TestCase):
    """Cấu hình stream retained kèm roi_geometry cũ không được đè ROI mới nhận qua config/roi hoặc HTTP configure."""

    def _make_agent(self):
        from src.integration.edge_agent import EdgeMqttAgent

        self.created = []

        def factory(camera_code, rtsp_url, roi_geometry=None):
            w = MagicMock()
            w.camera_code = camera_code
            w.rtsp_url = rtsp_url
            w.is_running = True
            w.init_roi = roi_geometry
            # Ghi lại ROI cuối cùng worker đang dùng
            w.current_polys = (roi_geometry or {}).get("polygons") if isinstance(roi_geometry, dict) else None

            def _update_roi(polygons=None, **_kw):
                if polygons is not None:
                    w.current_polys = polygons
            w.update_roi.side_effect = _update_roi
            self.created.append(w)
            return w

        agent = EdgeMqttAgent(
            mqtt_client_wrapper=MagicMock(),
            storage=MagicMock(),
            active_workers={},
            worker_factory=factory,
        )
        patch.object(agent, "_sync_local_mediamtx_path", side_effect=lambda c, u: u).start()
        patch.object(agent, "_delete_local_mediamtx_path").start()
        self.addCleanup(patch.stopall)
        return agent

    def test_replayed_stale_stream_config_does_not_overwrite_newer_roi(self):
        agent = self._make_agent()
        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=OLD_POLYS))
        agent.handle_message("campus/edge/config/roi/CAM-007", _roi_cfg(NEW_POLYS))
        self.assertEqual(self.created[0].current_polys, NEW_POLYS)

        # MQTT kết nối lại -> broker gửi lại cấu hình stream retained (vẫn kèm ROI cũ)
        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=OLD_POLYS))
        self.assertEqual(len(self.created), 1)
        self.assertEqual(self.created[0].current_polys, NEW_POLYS)

    def test_roi_received_before_stream_config_is_used_for_new_worker(self):
        agent = self._make_agent()
        agent.handle_message("campus/edge/config/roi/CAM-007", _roi_cfg(NEW_POLYS))
        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=OLD_POLYS))
        self.assertEqual(self.created[0].init_roi, {"polygons": NEW_POLYS})
        self.assertEqual(self.created[0].current_polys, NEW_POLYS)

    def test_http_configure_roi_is_respected_by_later_stream_config(self):
        agent = self._make_agent()
        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=OLD_POLYS))
        # POST /api/v1/cameras/configure gọi remember_roi (cập nhật worker trực tiếp, không qua MQTT)
        agent.remember_roi("cam-007", NEW_POLYS)
        self.created[0].update_roi(polygons=NEW_POLYS)

        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=OLD_POLYS))
        self.assertEqual(self.created[0].current_polys, NEW_POLYS)

    def test_cleared_roi_is_kept_cleared(self):
        agent = self._make_agent()
        agent.handle_message("campus/edge/config/roi/CAM-007", _roi_cfg([]))
        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=OLD_POLYS))
        self.assertEqual(self.created[0].current_polys, [])

    def test_without_roi_channel_stream_roi_still_applies(self):
        """AI vừa khởi động, chưa nhận config/roi: bản ROI kèm stream vẫn được dùng như trước."""
        agent = self._make_agent()
        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=OLD_POLYS))
        self.assertEqual(self.created[0].current_polys, OLD_POLYS)
        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=NEW_POLYS))
        self.assertEqual(len(self.created), 1)
        self.assertEqual(self.created[0].current_polys, NEW_POLYS)

    def test_roi_memory_is_per_camera(self):
        agent = self._make_agent()
        agent.handle_message("campus/edge/config/roi/CAM-001", _roi_cfg(NEW_POLYS, code="CAM-001"))
        agent.handle_message("campus/edge/config/cameras/CAM-007", _stream_cfg(roi=OLD_POLYS))
        self.assertEqual(self.created[0].current_polys, OLD_POLYS)


if __name__ == "__main__":
    unittest.main()
