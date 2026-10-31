import os
import json
import re
import time
import socket
import logging
import threading
import subprocess
from urllib.parse import urlparse
from concurrent.futures import ThreadPoolExecutor
from typing import Optional, Dict, Any, Callable

import cv2
import numpy as np

from ..config import settings
from ..pipeline.incident_recorder import incident_recording_manager

logger = logging.getLogger(__name__)


def _format_mediamtx_path(camera_code: str) -> str:
    if not camera_code:
        return ""
    return re.sub(r"[^a-z0-9_-]", "", camera_code.lower()).strip()


def _read_rtsp_frame_once(rtsp_url: str, timeout_ms: int = 4000) -> Optional[np.ndarray]:
    timeout_us = max(1000, timeout_ms) * 1000
    os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = f"rtsp_transport;tcp|stimeout;{timeout_us}"
    cap = cv2.VideoCapture(rtsp_url, cv2.CAP_FFMPEG)
    cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, timeout_ms)
    cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, timeout_ms)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    try:
        if cap.isOpened():
            for _ in range(5):
                ret, frame = cap.read()
                if ret and frame is not None and frame.size > 0:
                    return frame
                time.sleep(0.04)
    finally:
        cap.release()
    return None


class EdgeMqttAgent:
    """
    Edge Control Plane Agent chạy tại mạng nội bộ (LAN):
    - Duy trì 100% kết nối Outbound tới Cloud MQTT Broker & MinIO S3 (không cần mở port NAT/Router).
    - Nhận cấu hình Retained (Camera Streams, ROI Polygons, System Config) để tự khôi phục trạng thái khi khởi động.
    - Xử lý lệnh điều khiển (Incident Stop, On-Demand Cloud Stream Relay qua FFmpeg -c copy).
    - Xử lý các yêu cầu RPC đồng bộ (TEST_CONNECTION, CAPTURE_SNAPSHOT, EXTRACT_FACE_EMBEDDING) có correlationId.
    """

    TOPICS = [
        "campus/edge/config/cameras/+",
        "campus/edge/config/roi/+",
        "campus/edge/config/system",
        "campus/edge/cmd/incident-stop",
        "campus/edge/cmd/cloud-relay",
        "campus/edge/events/faces",
        "campus/edge/rpc/request",
    ]

    def __init__(
        self,
        mqtt_client_wrapper: Any,
        storage: Any,
        active_workers: Dict[str, Any],
        default_pipeline_getter: Optional[Callable[[], Any]] = None,
        worker_factory: Optional[Callable[[str, str, Optional[Dict[str, Any]]], Any]] = None,
        face_detector_instance: Optional[Any] = None,
        face_embedder_instance: Optional[Any] = None,
    ):
        self.mqtt = mqtt_client_wrapper
        self.storage = storage
        self.active_workers = active_workers
        self.default_pipeline_getter = default_pipeline_getter
        self.worker_factory = worker_factory
        self.face_detector = face_detector_instance
        self.face_embedder = face_embedder_instance
        self._lock = threading.Lock()
        self._relay_processes: Dict[str, subprocess.Popen] = {}
        self._executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="edge-mqtt-rpc")

    def start(self):
        if self.mqtt and hasattr(self.mqtt, "register_edge_subscriptions"):
            self.mqtt.register_edge_subscriptions(self.TOPICS, self._on_mqtt_message_async)
            logger.info("🚀 [EdgeAgent] Đã kích hoạt Cloud-Edge MQTT Control Plane (Outbound-Only).")

    def stop(self):
        with self._lock:
            for cam, proc in list(self._relay_processes.items()):
                self._terminate_process(proc)
            self._relay_processes.clear()
        self._executor.shutdown(wait=False)

    def _on_mqtt_message_async(self, topic: str, payload: bytes):
        self._executor.submit(self.handle_message, topic, payload)

    def handle_message(self, topic: str, payload: bytes):
        if not payload:
            return
        try:
            data = json.loads(payload.decode("utf-8"))
        except Exception as e:
            logger.warning(f"⚠️ [EdgeAgent] Payload JSON không hợp lệ trên {topic}: {e}")
            return

        try:
            if topic.startswith("campus/edge/config/cameras/"):
                self._handle_camera_stream_config(data)
            elif topic.startswith("campus/edge/config/roi/"):
                self._handle_roi_config(data)
            elif topic == "campus/edge/config/system":
                self._handle_system_config(data)
            elif topic == "campus/edge/cmd/incident-stop":
                self._handle_incident_stop(data)
            elif topic == "campus/edge/cmd/cloud-relay":
                self._handle_cloud_relay(data)
            elif topic == "campus/edge/events/faces":
                self._handle_face_cache_refresh()
            elif topic == "campus/edge/rpc/request":
                self._handle_rpc_request(data)
        except Exception as e:
            logger.error(f"❌ [EdgeAgent] Lỗi xử lý sự kiện tại topic {topic}: {e}", exc_info=True)

    # ------------------------------------------------------------------
    # 1. RETAINED CONFIG HANDLERS
    # ------------------------------------------------------------------

    def _sync_local_mediamtx_path(self, camera_code: str, rtsp_source_url: str) -> str:
        api_base = getattr(settings, "MEDIAMTX_API_URL", "http://localhost:9997").rstrip("/")
        rtsp_base = getattr(settings, "MEDIAMTX_RTSP_URL", "rtsp://localhost:8554").rstrip("/")
        path_name = _format_mediamtx_path(camera_code)
        if not api_base or not path_name:
            return rtsp_source_url

        payload = {
            "source": rtsp_source_url,
            "sourceProtocol": "tcp",
            "sourceOnDemand": False,
        }
        try:
            import requests
            add_resp = requests.post(f"{api_base}/v3/config/paths/add/{path_name}", json=payload, timeout=2.0)
            if add_resp.status_code >= 400:
                requests.patch(f"{api_base}/v3/config/paths/patch/{path_name}", json=payload, timeout=2.0)
            return f"{rtsp_base}/{path_name}"
        except Exception as e:
            logger.debug(f"[EdgeAgent] Không kết nối được Local MediaMTX ({api_base}), dùng trực tiếp RTSP camera: {e}")
            return rtsp_source_url

    def _delete_local_mediamtx_path(self, camera_code: str):
        api_base = getattr(settings, "MEDIAMTX_API_URL", "http://localhost:9997").rstrip("/")
        path_name = _format_mediamtx_path(camera_code)
        if not api_base or not path_name:
            return
        try:
            import requests
            requests.delete(f"{api_base}/v3/config/paths/delete/{path_name}", timeout=2.0)
        except Exception:
            pass

    def _handle_camera_stream_config(self, data: Dict[str, Any]):
        camera_code = str(data.get("camera_code") or "").strip().upper()
        rtsp_url = str(data.get("rtsp_url") or "").strip()
        enabled = bool(data.get("enabled", False))
        roi_geometry = data.get("roi_geometry")

        if not camera_code:
            return

        with self._lock:
            existing = self.active_workers.get(camera_code)
            if not enabled or not rtsp_url:
                if existing is not None:
                    try:
                        existing.stop()
                    except Exception as e:
                        logger.warning(f"[EdgeAgent] Lỗi dừng worker {camera_code}: {e}")
                    self.active_workers.pop(camera_code, None)
                self._delete_local_mediamtx_path(camera_code)
                self._stop_relay_unlocked(camera_code)
                logger.info(f"🛑 [EdgeAgent] Đã tắt luồng camera [{camera_code}] theo cấu hình MQTT.")
                return

            effective_rtsp = self._sync_local_mediamtx_path(camera_code, rtsp_url)
            if existing is not None and getattr(existing, "is_running", False):
                if getattr(existing, "rtsp_url", None) in (effective_rtsp, rtsp_url):
                    if roi_geometry and isinstance(roi_geometry, dict) and hasattr(existing, "update_roi"):
                        existing.update_roi(polygons=roi_geometry.get("polygons", []))
                    return
                try:
                    existing.stop()
                except Exception:
                    pass
                self.active_workers.pop(camera_code, None)

            factory = self.worker_factory
            if factory is None:
                from ..pipeline.stream_worker import CameraStreamWorker
                factory = lambda c, u, r: CameraStreamWorker(camera_code=c, rtsp_url=u, roi_geometry=r)

            worker = factory(camera_code, effective_rtsp, roi_geometry)
            worker.start()
            self.active_workers[camera_code] = worker
            logger.info(f"✅ [EdgeAgent] Đã kích hoạt CameraStreamWorker [{camera_code}] -> {effective_rtsp}")

    def _handle_roi_config(self, data: Dict[str, Any]):
        camera_code = str(data.get("camera_code") or "").strip().upper()
        polygons = data.get("polygons", [])
        ah_start = data.get("after_hour_start") or settings.DEFAULT_AFTER_HOUR_START
        ah_end = data.get("after_hour_end") or settings.DEFAULT_AFTER_HOUR_END

        default_pipe = self.default_pipeline_getter() if self.default_pipeline_getter else None
        if default_pipe is not None:
            default_pipe.camera_code = camera_code or default_pipe.camera_code
            default_pipe.analysis_engine.after_hour_start = ah_start
            default_pipe.analysis_engine.after_hour_end = ah_end
            default_pipe.set_roi_config(polygons=polygons)

        worker = self.active_workers.get(camera_code)
        if worker and getattr(worker, "is_running", False):
            worker.update_roi(polygons=polygons, after_hour_start=ah_start, after_hour_end=ah_end)
            logger.info(f"✅ [EdgeAgent] Đã cập nhật {len(polygons)} ROI polygon(s) cho worker [{camera_code}] qua MQTT.")

    def _handle_system_config(self, data: Dict[str, Any]):
        threshold = data.get("face_match_threshold")
        ah_start = data.get("after_hour_start")
        ah_end = data.get("after_hour_end")
        max_inactive = data.get("max_inactive_seconds")
        cooldown = data.get("unknown_alert_cooldown")

        if threshold is not None:
            settings.FACE_MATCH_THRESHOLD = float(threshold)
        if ah_start:
            settings.DEFAULT_AFTER_HOUR_START = str(ah_start)
        if ah_end:
            settings.DEFAULT_AFTER_HOUR_END = str(ah_end)
        if max_inactive is not None:
            settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS = float(max_inactive)
        if cooldown is not None:
            settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS = float(cooldown)

        default_pipe = self.default_pipeline_getter() if self.default_pipeline_getter else None
        if default_pipe is not None:
            default_pipe.analysis_engine.after_hour_start = settings.DEFAULT_AFTER_HOUR_START
            default_pipe.analysis_engine.after_hour_end = settings.DEFAULT_AFTER_HOUR_END
            default_pipe.analysis_engine.max_inactive_seconds = settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS
            default_pipe.analysis_engine.unknown_alert_cooldown = settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS

        for worker in list(self.active_workers.values()):
            if hasattr(worker, "update_runtime_config"):
                worker.update_runtime_config(
                    max_inactive_seconds=settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS,
                    unknown_alert_cooldown=settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS,
                )
            if (ah_start or ah_end) and getattr(worker, "pipeline", None):
                worker.pipeline.analysis_engine.after_hour_start = settings.DEFAULT_AFTER_HOUR_START
                worker.pipeline.analysis_engine.after_hour_end = settings.DEFAULT_AFTER_HOUR_END

        logger.info("✅ [EdgeAgent] Đã đồng bộ cấu hình hệ thống AI qua MQTT Retained.")

    # ------------------------------------------------------------------
    # 2. COMMAND & EVENT HANDLERS
    # ------------------------------------------------------------------

    def _handle_incident_stop(self, data: Dict[str, Any]):
        stop_all = bool(data.get("stop_all", False))
        incident_id = data.get("incident_id")
        if stop_all:
            incident_recording_manager.stop_all_incident_recordings()
        elif incident_id:
            incident_recording_manager.stop_incident_recording(str(incident_id))

    def _handle_cloud_relay(self, data: Dict[str, Any]):
        camera_code = str(data.get("camera_code") or "").strip().upper()
        active = bool(data.get("active", False))
        cloud_rtsp_base = getattr(settings, "CLOUD_MEDIAMTX_RTSP_URL", "").strip().rstrip("/")
        if not camera_code:
            return

        with self._lock:
            if not active or not cloud_rtsp_base:
                self._stop_relay_unlocked(camera_code)
                return

            existing_proc = self._relay_processes.get(camera_code)
            if existing_proc is not None and existing_proc.poll() is None:
                return

            path_name = _format_mediamtx_path(camera_code)
            local_rtsp = f"{getattr(settings, 'MEDIAMTX_RTSP_URL', 'rtsp://localhost:8554').rstrip('/')}/{path_name}"
            cloud_target = f"{cloud_rtsp_base}/{path_name}"
            cmd = [
                "ffmpeg",
                "-nostdin",
                "-loglevel", "warning",
                "-rtsp_transport", "tcp",
                "-i", local_rtsp,
                "-c", "copy",
                "-f", "rtsp",
                "-rtsp_transport", "tcp",
                cloud_target,
            ]
            try:
                proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                self._relay_processes[camera_code] = proc
                logger.info(f"🌐 [EdgeAgent] Đã bật On-Demand Cloud Stream Relay (-c copy) cho [{camera_code}] -> {cloud_target}")
            except Exception as e:
                logger.warning(f"⚠️ [EdgeAgent] Không thể khởi chạy FFmpeg Cloud Relay cho [{camera_code}]: {e}")

    def _stop_relay_unlocked(self, camera_code: str):
        proc = self._relay_processes.pop(camera_code, None)
        if proc is not None:
            self._terminate_process(proc)
            logger.info(f"🛑 [EdgeAgent] Đã ngắt On-Demand Cloud Stream Relay cho [{camera_code}].")

    @staticmethod
    def _terminate_process(proc: subprocess.Popen):
        try:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=2.0)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass

    def _handle_face_cache_refresh(self):
        count = 0
        default_pipe = self.default_pipeline_getter() if self.default_pipeline_getter else None
        if default_pipe and getattr(default_pipe, "face_matcher", None):
            default_pipe.face_matcher.refresh_cache()
            count += 1
        for worker in list(self.active_workers.values()):
            if getattr(worker, "pipeline", None) and getattr(worker.pipeline, "face_matcher", None):
                worker.pipeline.face_matcher.refresh_cache()
                count += 1
        logger.info(f"🔄 [EdgeAgent] Đã làm mới Face Cache cho {count} pipeline(s) qua MQTT.")

    # ------------------------------------------------------------------
    # 3. SYNCHRONOUS RPC REQUEST -> REPLY HANDLERS
    # ------------------------------------------------------------------

    def _handle_rpc_request(self, req: Dict[str, Any]):
        corr_id = req.get("correlationId")
        action = str(req.get("action") or "").strip().upper()
        camera_code = req.get("cameraCode")
        payload = req.get("payload") or {}

        if not corr_id:
            return

        reply_topic = f"campus/edge/rpc/reply/{corr_id}"
        t0 = time.time()

        try:
            if action == "TEST_CONNECTION":
                reply = self._rpc_test_connection(corr_id, camera_code, payload, t0)
            elif action == "CAPTURE_SNAPSHOT":
                reply = self._rpc_capture_snapshot(corr_id, camera_code, payload, t0)
            elif action == "EXTRACT_FACE_EMBEDDING":
                reply = self._rpc_extract_face_embedding(corr_id, payload, t0)
            else:
                reply = {
                    "correlationId": corr_id,
                    "success": False,
                    "errorCode": "ERR_RPC_UNKNOWN_ACTION",
                    "errorMessage": f"Không hỗ trợ lệnh RPC: {action}",
                    "latencyMs": int((time.time() - t0) * 1000),
                }
        except Exception as e:
            reply = {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_EDGE_INTERNAL",
                "errorMessage": str(e),
                "latencyMs": int((time.time() - t0) * 1000),
            }

        self.mqtt.publish_json(reply_topic, reply, qos=1, retain=False)

    def _rpc_test_connection(self, corr_id: str, camera_code: Optional[str], payload: Dict[str, Any], t0: float) -> Dict[str, Any]:
        rtsp_url = str(payload.get("rtsp_url") or "").strip()
        timeout_ms = int(payload.get("timeout_ms") or 4000)

        if not rtsp_url:
            host = str(payload.get("host") or "").strip()
            port = int(payload.get("port") or 554)
            path = str(payload.get("path") or "/").strip()
            if not path.startswith("/"):
                path = "/" + path
            rtsp_url = f"rtsp://{host}:{port}{path}" if host else ""

        parsed = urlparse(rtsp_url)
        host = parsed.hostname
        port = parsed.port or 554
        if not host:
            return {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_CONN_INVALID_URL",
                "errorMessage": "Địa chỉ RTSP không hợp lệ",
                "latencyMs": int((time.time() - t0) * 1000),
            }

        # Kiểm tra nhanh TCP socket trước để báo lỗi chính xác trong LAN
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(min(max(timeout_ms / 1000.0, 1.0), 3.5))
        try:
            sock.connect((host, port))
        except socket.timeout:
            return {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_CONN_TIMEOUT",
                "errorMessage": f"Quá thời gian kết nối tới camera nội bộ {host}:{port}",
                "latencyMs": int((time.time() - t0) * 1000),
            }
        except Exception as sock_err:
            return {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_CONN_REFUSED",
                "errorMessage": f"Không thể kết nối TCP tới {host}:{port} ({sock_err})",
                "latencyMs": int((time.time() - t0) * 1000),
            }
        finally:
            try:
                sock.close()
            except Exception:
                pass

        frame = _read_rtsp_frame_once(rtsp_url, timeout_ms=timeout_ms)
        latency_ms = int((time.time() - t0) * 1000)
        if frame is None:
            return {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_STREAM_UNREADABLE",
                "errorMessage": "Kết nối TCP thành công nhưng không đọc được khung hình RTSP (kiểm tra tài khoản/đường dẫn stream)",
                "latencyMs": latency_ms,
            }

        return {
            "correlationId": corr_id,
            "success": True,
            "latencyMs": latency_ms,
            "data": {"fps": 15.0},
        }

    def _rpc_capture_snapshot(self, corr_id: str, camera_code: Optional[str], payload: Dict[str, Any], t0: float) -> Dict[str, Any]:
        cam = (camera_code or "CAM-UNKNOWN").strip().upper()
        rtsp_url = str(payload.get("rtsp_url") or "").strip()
        timeout_ms = int(payload.get("timeout_ms") or 4000)

        frame = None
        worker = self.active_workers.get(cam)
        if worker and getattr(worker, "is_running", False):
            frame = worker.get_current_raw_frame()

        if frame is None and rtsp_url:
            frame = _read_rtsp_frame_once(rtsp_url, timeout_ms=timeout_ms)

        if frame is None or frame.size == 0:
            return {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_SNAPSHOT_001",
                "errorMessage": f"Không thể trích xuất khung hình từ camera {cam}",
                "latencyMs": int((time.time() - t0) * 1000),
            }

        h, w = frame.shape[:2]
        ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
        if not ok:
            return {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_SNAPSHOT_ENCODE",
                "errorMessage": "Không thể nén JPEG khung hình snapshot",
                "latencyMs": int((time.time() - t0) * 1000),
            }

        bucket = getattr(settings, "MINIO_BUCKET_SNAPSHOTS", "camera-snapshots")
        object_key = f"{cam}/snap_{int(time.time() * 1000)}.jpg"
        uploaded = self.storage.upload_bytes(bucket, object_key, buf.tobytes(), "image/jpeg")
        if not uploaded:
            return {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_SNAPSHOT_UPLOAD",
                "errorMessage": "Không thể tải ảnh snapshot lên MinIO S3",
                "latencyMs": int((time.time() - t0) * 1000),
            }

        return {
            "correlationId": corr_id,
            "success": True,
            "latencyMs": int((time.time() - t0) * 1000),
            "data": {
                "bucket": bucket,
                "object_key": object_key,
                "width": int(w),
                "height": int(h),
            },
        }

    def _rpc_extract_face_embedding(self, corr_id: str, payload: Dict[str, Any], t0: float) -> Dict[str, Any]:
        bucket = str(payload.get("bucket") or getattr(settings, "MINIO_BUCKET_FACES", "face-profiles"))
        object_key = str(payload.get("object_key") or "").strip()
        code = str(payload.get("code") or "")

        raw_bytes = self.storage.download_bytes(bucket, object_key)
        if not raw_bytes:
            return {
                "correlationId": corr_id,
                "success": False,
                "errorCode": "ERR_FACE_STAGING_NOT_FOUND",
                "errorMessage": f"Không tải được ảnh staging từ MinIO ({bucket}/{object_key})",
                "latencyMs": int((time.time() - t0) * 1000),
            }

        from ..utils.image_utils import decode_image_safely
        img, _ = decode_image_safely(raw_bytes)

        detector = self.face_detector
        if detector is None:
            default_pipe = self.default_pipeline_getter() if self.default_pipeline_getter else None
            if default_pipe and getattr(default_pipe, "face_detector", None):
                detector = default_pipe.face_detector
            else:
                from ..core.face_detector import FaceDetector
                detector = FaceDetector()
                self.face_detector = detector

        embedder = self.face_embedder
        if embedder is None:
            from ..core.face_embedder import FaceEmbedder
            embedder = FaceEmbedder(embedding_dim=512)
            self.face_embedder = embedder

        faces = detector.detect_in_image(img)
        face_count = len(faces)
        landmarks = None
        if not faces:
            face_crop = img
        else:
            faces.sort(key=lambda f: f.score, reverse=True)
            fx1, fy1, fx2, fy2 = faces[0].bbox.to_int_xyxy()
            ih, iw, _ = img.shape
            fx1, fy1 = max(0, fx1), max(0, fy1)
            fx2, fy2 = min(iw, fx2), min(ih, fy2)
            face_crop = img[fy1:fy2, fx1:fx2] if (fx2 > fx1 and fy2 > fy1) else img
            if faces[0].landmarks:
                landmarks = [(pt[0] - fx1, pt[1] - fy1) for pt in faces[0].landmarks]

        vector_512 = embedder.extract_embedding(face_crop, landmarks=landmarks)
        return {
            "correlationId": corr_id,
            "success": True,
            "latencyMs": int((time.time() - t0) * 1000),
            "data": {
                "code": code,
                "face_count": face_count,
                "embedding_front": vector_512,
            },
        }
