import os
import json
import hashlib
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock, patch
import numpy as np

# Đảm bảo biến môi trường tối thiểu cho Settings khi chạy unit test độc lập
os.environ.setdefault("MINIO_ROOT_USER", "minioadmin")
os.environ.setdefault("MINIO_ROOT_PASSWORD", "minioadmin123")
os.environ.setdefault("INTERNAL_SERVICE_KEY", "test-internal-service-key-at-least-32-chars")
os.environ.setdefault("MQTT_ENABLED", "False")
os.environ.setdefault("MINIO_ENABLED", "False")


class TestEdgeAgentAndRecorders(unittest.TestCase):

    def test_1_happy_path_rpc_capture_snapshot_uploads_to_minio_and_replies(self):
        from src.integration.edge_agent import EdgeMqttAgent

        mock_mqtt = MagicMock()
        mock_storage = MagicMock()
        mock_storage.upload_bytes.return_value = True

        fake_worker = MagicMock()
        fake_worker.is_running = True
        fake_worker.get_current_raw_frame.return_value = np.zeros((480, 854, 3), dtype=np.uint8)

        agent = EdgeMqttAgent(
            mqtt_client_wrapper=mock_mqtt,
            storage=mock_storage,
            active_workers={"CAM-001": fake_worker},
        )

        req_payload = {
            "correlationId": "corr-snap-001",
            "action": "CAPTURE_SNAPSHOT",
            "cameraCode": "CAM-001",
            "payload": {"rtsp_url": "rtsp://192.168.1.105:554/live", "timeout_ms": 3000},
        }
        agent.handle_message("campus/edge/rpc/request", json.dumps(req_payload).encode("utf-8"))

        mock_storage.upload_bytes.assert_called_once()
        mock_mqtt.publish_json.assert_called_once()
        reply_topic, reply_body = mock_mqtt.publish_json.call_args[0][:2]
        self.assertEqual(reply_topic, "campus/edge/rpc/reply/corr-snap-001")
        self.assertTrue(reply_body["success"])
        self.assertEqual(reply_body["data"]["bucket"], "camera-snapshots")
        self.assertEqual(reply_body["data"]["width"], 854)
        self.assertEqual(reply_body["data"]["height"], 480)

    def test_2_idempotency_camera_stream_config_does_not_recreate_running_worker(self):
        from src.integration.edge_agent import EdgeMqttAgent

        mock_mqtt = MagicMock()
        mock_storage = MagicMock()
        active_workers = {}
        created_workers = []

        def fake_worker_factory(camera_code, rtsp_url, roi_geometry=None):
            w = MagicMock()
            w.camera_code = camera_code
            w.rtsp_url = rtsp_url
            w.is_running = True
            created_workers.append(w)
            return w

        agent = EdgeMqttAgent(
            mqtt_client_wrapper=mock_mqtt,
            storage=mock_storage,
            active_workers=active_workers,
            worker_factory=fake_worker_factory,
        )

        with patch.object(agent, "_sync_local_mediamtx_path") as mock_sync, \
             patch.object(agent, "_delete_local_mediamtx_path") as mock_del:
            cfg = {
                "camera_code": "CAM-001",
                "rtsp_url": "rtsp://192.168.1.105:554/live",
                "enabled": True,
            }
            # Gọi lặp lại 2 lần liên tiếp (Idempotency)
            agent.handle_message("campus/edge/config/cameras/CAM-001", json.dumps(cfg).encode("utf-8"))
            agent.handle_message("campus/edge/config/cameras/CAM-001", json.dumps(cfg).encode("utf-8"))

            self.assertEqual(len(created_workers), 1)
            self.assertEqual(mock_sync.call_count, 2)

            # Tắt luồng -> dừng worker và gỡ path trên Edge MediaMTX
            cfg_off = {"camera_code": "CAM-001", "rtsp_url": "", "enabled": False}
            agent.handle_message("campus/edge/config/cameras/CAM-001", json.dumps(cfg_off).encode("utf-8"))
            created_workers[0].stop.assert_called_once()
            mock_del.assert_called_once_with("CAM-001")
            self.assertNotIn("CAM-001", active_workers)

    def test_3_domain_error_extract_face_embedding_when_minio_download_fails(self):
        from src.integration.edge_agent import EdgeMqttAgent

        mock_mqtt = MagicMock()
        mock_storage = MagicMock()
        mock_storage.download_bytes.return_value = None

        agent = EdgeMqttAgent(
            mqtt_client_wrapper=mock_mqtt,
            storage=mock_storage,
            active_workers={},
        )

        req_payload = {
            "correlationId": "corr-face-err",
            "action": "EXTRACT_FACE_EMBEDDING",
            "cameraCode": None,
            "payload": {"bucket": "face-profiles", "object_key": "staging/missing.jpg", "code": "NV001"},
        }
        agent.handle_message("campus/edge/rpc/request", json.dumps(req_payload).encode("utf-8"))

        mock_mqtt.publish_json.assert_called_once()
        reply_topic, reply_body = mock_mqtt.publish_json.call_args[0][:2]
        self.assertEqual(reply_topic, "campus/edge/rpc/reply/corr-face-err")
        self.assertFalse(reply_body["success"])
        self.assertEqual(reply_body["errorCode"], "ERR_FACE_STAGING_NOT_FOUND")

    def test_4_stream_recorder_osd_timestamp_and_completed_telemetry_payload(self):
        from src.pipeline.stream_recorder import StreamRecorder, build_recording_completed_payload

        vn_tz = timezone(timedelta(hours=7))
        start_dt = datetime(2026, 10, 31, 17, 0, 0, tzinfo=vn_tz)
        end_dt = datetime(2026, 10, 31, 17, 1, 0, tzinfo=vn_tz)

        with tempfile.NamedTemporaryFile(delete=False, suffix=".mp4") as tmp:
            tmp.write(b"fake-mp4-video-content-with-moov")
            tmp_path = tmp.name

        try:
            expected_sha = hashlib.sha256(b"fake-mp4-video-content-with-moov").hexdigest()
            payload = build_recording_completed_payload(
                camera_code="CAM-001",
                recording_type="PERIODIC_SEGMENT",
                bucket_name="camera-recordings",
                object_key="CAM-001/2026/10/31/31-10-2026 - 17-00-00.mp4",
                file_path=tmp_path,
                start_dt=start_dt,
                end_dt=end_dt,
                frames_count=900,
                fps=15.0,
                incident_event_id=None,
            )
            self.assertEqual(payload["event_type"], "RECORDING_COMPLETED")
            self.assertEqual(payload["camera_code"], "CAM-001")
            self.assertEqual(payload["duration_seconds"], 60)
            self.assertEqual(payload["checksum_sha256"], expected_sha)
            self.assertEqual(payload["resolution"], "854x480")

            # Kiểm tra đóng dấu OSD đồng hồ thời gian thực trên frame 854x480
            rec = StreamRecorder(camera_code="CAM-001")
            blank = np.zeros((480, 854, 3), dtype=np.uint8)
            stamped = rec._draw_osd_timestamp(blank, start_dt)
            self.assertEqual(stamped.shape, (480, 854, 3))
            self.assertGreater(int(stamped.sum()), 0)
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)


if __name__ == "__main__":
    unittest.main()
