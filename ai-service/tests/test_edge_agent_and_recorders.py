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

    def test_1_happy_path_rpc_test_connection_replies_with_latency(self):
        from src.integration.edge_agent import EdgeMqttAgent

        mock_mqtt = MagicMock()
        mock_storage = MagicMock()
        agent = EdgeMqttAgent(
            mqtt_client_wrapper=mock_mqtt,
            storage=mock_storage,
            active_workers={},
        )

        mock_sock = MagicMock()
        with patch("socket.socket", return_value=mock_sock), \
             patch("src.integration.edge_agent._read_rtsp_frame_once", return_value=np.zeros((480, 854, 3), dtype=np.uint8)):
            req_payload = {
                "correlationId": "corr-conn-001",
                "action": "TEST_CONNECTION",
                "cameraCode": "CAM-001",
                "payload": {"rtsp_url": "rtsp://192.168.1.105:554/live", "timeout_ms": 3000},
            }
            agent.handle_message("campus/edge/rpc/request", json.dumps(req_payload).encode("utf-8"))

        mock_mqtt.publish_json.assert_called_once()
        reply_topic, reply_body = mock_mqtt.publish_json.call_args[0][:2]
        self.assertEqual(reply_topic, "campus/edge/rpc/reply/corr-conn-001")
        self.assertTrue(reply_body["success"])
        self.assertEqual(reply_body["data"]["fps"], 15.0)

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

    def test_5_edge_agent_uses_main_rtsp_url_when_provided(self):
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

        with patch.object(agent, "_sync_local_mediamtx_path") as mock_sync:
            mock_sync.side_effect = lambda code, url: f"rtsp://localhost:8554/{code.lower()}"
            cfg = {
                "camera_code": "CAM-001",
                "rtsp_url": "rtsp://192.168.1.105:554/cam/sub",
                "main_rtsp_url": "rtsp://192.168.1.105:554/cam/main",
                "enabled": True,
            }
            agent.handle_message("campus/edge/config/cameras/CAM-001", json.dumps(cfg).encode("utf-8"))

            self.assertEqual(len(created_workers), 1)
            self.assertEqual(created_workers[0].rtsp_url, "rtsp://localhost:8554/cam-001_main")
            mock_sync.assert_any_call("CAM-001", "rtsp://192.168.1.105:554/cam/sub")
            mock_sync.assert_any_call("CAM-001_main", "rtsp://192.168.1.105:554/cam/main")

    def test_6_edge_agent_falls_back_to_rtsp_url_when_main_rtsp_url_absent(self):
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

        with patch.object(agent, "_sync_local_mediamtx_path") as mock_sync:
            mock_sync.side_effect = lambda code, url: f"rtsp://localhost:8554/{code.lower()}"
            cfg = {
                "camera_code": "CAM-002",
                "rtsp_url": "rtsp://192.168.1.105:554/cam/live",
                "enabled": True,
            }
            agent.handle_message("campus/edge/config/cameras/CAM-002", json.dumps(cfg).encode("utf-8"))

            self.assertEqual(len(created_workers), 1)
            self.assertEqual(created_workers[0].rtsp_url, "rtsp://localhost:8554/cam-002")
            mock_sync.assert_called_once_with("CAM-002", "rtsp://192.168.1.105:554/cam/live")

    def test_7_f02_edge_agent_decrypts_enc_etm_rtsp_config_and_rejects_tampered_mac(self):
        import base64
        import hmac as py_hmac
        from src.integration.edge_agent import EdgeMqttAgent
        from src.security import ENC_ETM_PREFIX, decrypt_mqtt_secret

        def _encrypt_etm(plaintext: str, key: str) -> str:
            nonce = b"\x01" * 16
            enc_key = hashlib.sha256(f"mqtt-enc|{key}".encode("utf-8")).digest()
            mac_key = hashlib.sha256(f"mqtt-mac|{key}".encode("utf-8")).digest()
            plain_bytes = plaintext.encode("utf-8")
            cipher = bytearray(len(plain_bytes))
            offset = 0
            counter = 0
            while offset < len(plain_bytes):
                block = hashlib.sha256(enc_key + nonce + counter.to_bytes(4, "big")).digest()
                counter += 1
                chunk = min(len(block), len(plain_bytes) - offset)
                for i in range(chunk):
                    cipher[offset + i] = plain_bytes[offset + i] ^ block[i]
                offset += chunk
            tag = py_hmac.new(mac_key, nonce + bytes(cipher), hashlib.sha256).digest()
            return ENC_ETM_PREFIX + base64.b64encode(nonce + bytes(cipher) + tag).decode("ascii")

        from src.config import settings

        orig_key = getattr(settings, "INTERNAL_SERVICE_KEY", "")
        key = os.environ.get("INTERNAL_SERVICE_KEY") or orig_key or "test-internal-service-key-32-chars!"
        settings.INTERNAL_SERVICE_KEY = key
        plain_sub = "rtsp://admin:SecretPass123@192.168.1.105:554/sub"
        enc_sub = _encrypt_etm(plain_sub, key)

        self.assertEqual(decrypt_mqtt_secret(enc_sub, key), plain_sub)
        # Tamper last byte of MAC
        tampered = enc_sub[:-2] + ("AA" if not enc_sub.endswith("AA") else "BB")
        with self.assertRaises(ValueError):
            decrypt_mqtt_secret(tampered, key)

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
        try:
            with patch.object(agent, "_sync_local_mediamtx_path") as mock_sync:
                mock_sync.side_effect = lambda code, url: f"rtsp://localhost:8554/{code.lower()}"
                cfg = {"camera_code": "CAM-003", "rtsp_url": enc_sub, "enabled": True}
                agent.handle_message("campus/edge/config/cameras/CAM-003", json.dumps(cfg).encode("utf-8"))
                mock_sync.assert_called_once_with("CAM-003", plain_sub)
                self.assertEqual(len(created_workers), 1)
        finally:
            settings.INTERNAL_SERVICE_KEY = orig_key


if __name__ == "__main__":
    unittest.main()
