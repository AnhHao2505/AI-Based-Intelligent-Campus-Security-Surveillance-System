#!/usr/bin/env python3
"""
NFR-01 End-to-End Alert Chain & Check-Entry Latency Measurement Script.

Measures >= 30 samples across the 4 stages of the security alert chain plus the
access-control check-entry hop:
  (a) frame capture (`frame_captured_at`)
  (b) AI inference & MQTT publish (`mqtt_published_at`)
  (c) Backend MQTT receive (`mqtt_received_at`)
  (d) WebSocket STOMP push to client (`ws_pushed_at` / `ws_client_received_at`)
  (+) `POST /api/access-control/check-entry` round-trip

Usage examples (from repo root, using ai-service/.venv):
  # Measure against an already-running local/cloud Backend + local/configured MQTT:
  ai-service\\.venv\\Scripts\\python.exe scripts\\measure_nfr_e2e_latency.py --samples 30 --backend-url http://localhost:8080

  # Auto-start an isolated Backend on port 18088 using Spring profile `test` (campus_security_test) + local Mosquitto:
  ai-service\\.venv\\Scripts\\python.exe scripts\\measure_nfr_e2e_latency.py --samples 30 --start-test-backend

  # Measure AI pipeline + real local Mosquitto broker hop only (no Backend required):
  ai-service\\.venv\\Scripts\\python.exe scripts\\measure_nfr_e2e_latency.py --samples 30 --mode ai-broker
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np

# Ensure UTF-8 console output on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

REPO_ROOT = Path(__file__).resolve().parent.parent
AI_SERVICE_DIR = REPO_ROOT / "ai-service"
BACKEND_DIR = REPO_ROOT / "backend"


def _load_env_quietly() -> None:
    """Load .env into os.environ (without overwriting existing vars or printing secrets)."""
    env_path = REPO_ROOT / ".env"
    if not env_path.exists():
        return
    try:
        from dotenv import load_dotenv
        load_dotenv(dotenv_path=env_path, override=False)
    except Exception:
        pass


def _parse_iso_to_epoch_ms(raw: Any) -> Optional[float]:
    if raw is None:
        return None
    if isinstance(raw, (int, float)):
        val = float(raw)
        return val * 1000.0 if val < 1e11 else val
    if not isinstance(raw, str):
        return None
    s = raw.strip()
    if not s:
        return None
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp() * 1000.0
    except Exception:
        return None


def _epoch_ms_to_iso_utc(epoch_ms: float) -> str:
    dt = datetime.fromtimestamp(epoch_ms / 1000.0, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{int(epoch_ms % 1000):03d}Z"


def _stats_ms(values: List[float]) -> Dict[str, Any]:
    if not values:
        return {"count": 0, "min": None, "median": None, "p95": None, "mean": None, "max": None}
    arr = np.array(values, dtype=np.float64)
    return {
        "count": int(len(arr)),
        "min": round(float(np.min(arr)), 2),
        "median": round(float(np.percentile(arr, 50)), 2),
        "p95": round(float(np.percentile(arr, 95)), 2),
        "mean": round(float(np.mean(arr)), 2),
        "max": round(float(np.max(arr)), 2),
    }


def _is_port_open(host: str, port: int, timeout: float = 0.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _backend_healthy(backend_url: str, timeout: float = 1.5) -> bool:
    import requests
    try:
        resp = requests.get(f"{backend_url.rstrip('/')}/actuator/health", timeout=timeout)
        return resp.status_code == 200
    except Exception:
        return False


class StompWsCollector:
    """Minimal STOMP 1.2 client over WebSocket (websockets) subscribing to /topic/security-alerts."""

    def __init__(self, ws_url: str, jwt_token: str, topic: str = "/topic/security-alerts"):
        self.ws_url = ws_url
        self.jwt_token = jwt_token
        self.topic = topic
        self.connected = threading.Event()
        self.stop_event = threading.Event()
        self.error: Optional[str] = None
        self.received_by_event_id: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.Lock()
        self._thread: Optional[threading.Thread] = None

    def start(self, timeout: float = 10.0) -> None:
        self._thread = threading.Thread(target=self._run_thread, daemon=True)
        self._thread.start()
        if not self.connected.wait(timeout=timeout):
            raise RuntimeError(f"WebSocket STOMP connection timed out ({self.error or 'no error detail'})")
        if self.error:
            raise RuntimeError(f"WebSocket STOMP error: {self.error}")

    def stop(self) -> None:
        self.stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=3.0)

    def _run_thread(self) -> None:
        asyncio.run(self._async_loop())

    @staticmethod
    def _build_frame(command: str, headers: Dict[str, str], body: str = "") -> str:
        lines = [command]
        for k, v in headers.items():
            lines.append(f"{k}:{v}")
        lines.append("")
        lines.append(body)
        return "\n".join(lines) + "\x00"

    @staticmethod
    def _parse_frame(raw: str) -> Tuple[str, Dict[str, str], str]:
        if raw == "\n" or raw == "\r\n":
            return "HEARTBEAT", {}, ""
        raw = raw.rstrip("\x00")
        parts = raw.split("\n\n", 1)
        head = parts[0]
        body = parts[1] if len(parts) > 1 else ""
        head_lines = [line.rstrip("\r") for line in head.split("\n")]
        cmd = head_lines[0] if head_lines else ""
        headers: Dict[str, str] = {}
        for line in head_lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k] = v
        return cmd, headers, body

    async def _async_loop(self) -> None:
        import websockets
        try:
            async with websockets.connect(self.ws_url, ping_interval=20, ping_timeout=20) as ws:
                connect_frame = self._build_frame(
                    "CONNECT",
                    {
                        "accept-version": "1.2,1.1,1.0",
                        "heart-beat": "10000,10000",
                        "Authorization": f"Bearer {self.jwt_token}",
                    },
                )
                await ws.send(connect_frame)
                connected_raw = await asyncio.wait_for(ws.recv(), timeout=5.0)
                cmd, _, body = self._parse_frame(
                    connected_raw.decode("utf-8") if isinstance(connected_raw, bytes) else connected_raw
                )
                if cmd != "CONNECTED":
                    self.error = f"Expected CONNECTED, got {cmd}: {body}"
                    self.connected.set()
                    return

                sub_frame = self._build_frame(
                    "SUBSCRIBE",
                    {"id": "nfr-sub-0", "destination": self.topic, "ack": "auto"},
                )
                await ws.send(sub_frame)
                # Brief yield so SimpleBroker registers the subscription
                await asyncio.sleep(0.25)
                self.connected.set()

                while not self.stop_event.is_set():
                    try:
                        msg_raw = await asyncio.wait_for(ws.recv(), timeout=0.25)
                    except asyncio.TimeoutError:
                        continue
                    recv_ms = time.time() * 1000.0
                    text = msg_raw.decode("utf-8") if isinstance(msg_raw, bytes) else msg_raw
                    for chunk in text.split("\x00"):
                        if not chunk.strip():
                            continue
                        f_cmd, _, f_body = self._parse_frame(chunk)
                        if f_cmd == "MESSAGE" and f_body:
                            try:
                                payload = json.loads(f_body)
                                ev_id = payload.get("eventId") or payload.get("event_id")
                                if ev_id:
                                    with self._lock:
                                        self.received_by_event_id[ev_id] = {
                                            "ws_client_received_ms": recv_ms,
                                            "payload": payload,
                                        }
                            except Exception:
                                pass
        except Exception as exc:
            self.error = str(exc)
            self.connected.set()


class ManagedTestBackend:
    """Starts an isolated Spring Boot backend against campus_security_test for local E2E measurement."""

    def __init__(self, port: int = 18088, mqtt_port: int = 1883):
        self.port = port
        self.mqtt_port = mqtt_port
        self.base_url = f"http://127.0.0.1:{port}"
        self.proc: Optional[subprocess.Popen] = None
        self.log_path = Path(tempfile.gettempdir()) / f"nfr_backend_{port}_{os.getpid()}.log"
        self._log_file = None

    def start(self, timeout: float = 90.0) -> None:
        if _is_port_open("127.0.0.1", self.port):
            raise RuntimeError(f"Port {self.port} is already in use; pass --backend-url or choose another --test-backend-port.")

        mvnw = BACKEND_DIR / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        env = os.environ.copy()
        # OS env vars + additional location ensure application-test.yml and campus_security_test are strictly used
        env["SPRING_PROFILES_ACTIVE"] = "test"
        env["SPRING_CONFIG_ADDITIONAL_LOCATION"] = "file:src/test/resources/"
        env["POSTGRES_DB"] = "campus_security_test"
        env["SPRING_DATASOURCE_URL"] = "jdbc:postgresql://127.0.0.1:5432/campus_security_test?options=-c%20timezone=Asia/Ho_Chi_Minh"
        env["PORT"] = str(self.port)
        env["SERVER_PORT"] = str(self.port)
        env["APP_MQTT_ENABLED"] = "true"
        env["APP_MQTT_BROKER_URL"] = f"tcp://127.0.0.1:{self.mqtt_port}"
        env["APP_MQTT_CLIENT_ID"] = f"nfr-test-backend-{os.getpid()}"
        env["AI_SERVICE_URL"] = ""
        env["ICSS_SCHEDULER_INCIDENT_ESCALATION_ENABLED"] = "false"
        env["ICSS_SCHEDULER_INCIDENT_DISPATCH_ENABLED"] = "false"
        cmd = [
            str(mvnw),
            "spring-boot:run",
            "-Dspring-boot.run.profiles=test",
        ]
        self._log_file = open(self.log_path, "w", encoding="utf-8", errors="replace")
        self.proc = subprocess.Popen(
            cmd,
            cwd=str(BACKEND_DIR),
            env=env,
            stdout=self._log_file,
            stderr=subprocess.STDOUT,
        )

        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"Test backend exited early with code {self.proc.returncode}. Log: {self.log_path}")
            if _backend_healthy(self.base_url, timeout=1.0):
                # Give MQTT subscriber a moment to connect & subscribe
                time.sleep(1.5)
                return
            time.sleep(1.0)
        raise RuntimeError(f"Test backend did not become healthy within {timeout}s. Log: {self.log_path}")

    def stop(self) -> None:
        if self.proc is not None:
            try:
                if os.name == "nt":
                    subprocess.run(
                        ["taskkill", "/PID", str(self.proc.pid), "/T", "/F"],
                        capture_output=True,
                        check=False,
                    )
                else:
                    self.proc.terminate()
                    self.proc.wait(timeout=5)
            except Exception:
                pass
            self.proc = None
        if self._log_file is not None:
            try:
                self._log_file.close()
            except Exception:
                pass
            self._log_file = None


def _parse_backend_trace_log(log_path: Optional[Path]) -> Dict[str, Dict[str, float]]:
    """Parse optional [NFR-TRACE] structured log lines from backend log if present."""
    traces: Dict[str, Dict[str, float]] = {}
    if not log_path or not log_path.exists():
        return traces
    pattern = re.compile(r"\[NFR-TRACE\]\s+(.*)")
    kv_pattern = re.compile(r"(\w+)=([^\s,;]+)")
    with open(log_path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m = pattern.search(line)
            if not m:
                continue
            kv = dict(kv_pattern.findall(m.group(1)))
            ev_id = kv.get("eventId") or kv.get("event_id")
            if not ev_id:
                continue
            entry: Dict[str, float] = {}
            for k in ("frameCapturedAt", "detectedAt", "mqttPublishedAt", "mqttReceivedAt", "dbSavedAt", "wsPushedAt"):
                ts_ms = _parse_iso_to_epoch_ms(kv.get(k))
                if ts_ms is not None:
                    entry[k] = ts_ms
            if entry:
                traces[ev_id] = entry
    return traces


def _login_and_prepare_test_camera(
    backend_url: str,
    email: str,
    password: str,
    preferred_camera_code: Optional[str] = None,
) -> Tuple[str, str, Optional[str]]:
    """Authenticate via /api/auth/login and locate/create a RESTRICTED camera + user code via REST API."""
    import requests
    login_resp = requests.post(
        f"{backend_url.rstrip('/')}/api/auth/login",
        json={"email": email, "password": password},
        timeout=5.0,
    )
    if login_resp.status_code != 200:
        raise RuntimeError(f"Login failed ({login_resp.status_code}) for {email}")
    data_obj = login_resp.json()["data"]
    token = data_obj.get("accessToken") or data_obj.get("token")
    if not token:
        raise RuntimeError(f"Login response missing accessToken: keys={list(data_obj.keys())}")
    headers = {"Authorization": f"Bearer {token}"}

    # Find an existing camera (prefer non-PUBLIC/non-CORRIDOR so incidents persist & broadcast)
    cams_resp = requests.get(f"{backend_url.rstrip('/')}/api/cameras?size=50", headers=headers, timeout=5.0)
    cams = cams_resp.json().get("data", {}).get("content", []) if cams_resp.status_code == 200 else []
    selected_cam: Optional[str] = None
    if preferred_camera_code:
        for c in cams:
            if c.get("cameraCode") == preferred_camera_code and c.get("status") != "DECOMMISSIONED":
                selected_cam = c["cameraCode"]
                break
    if not selected_cam:
        for c in cams:
            if c.get("status") != "DECOMMISSIONED" and c.get("cameraType") not in ("PUBLIC_AREA", "CORRIDOR"):
                selected_cam = c.get("cameraCode")
                break
    if not selected_cam:
        # Look for an area or create one + camera via API
        areas_resp = requests.get(f"{backend_url.rstrip('/')}/api/areas?size=50", headers=headers, timeout=5.0)
        areas = areas_resp.json().get("data", {}).get("content", []) if areas_resp.status_code == 200 else []
        area_id = None
        for a in areas:
            if a.get("areaLevel") in ("CONFIDENTIAL", "HIGH_SECURITY") and a.get("isActive", True):
                area_id = a["id"]
                break
        if not area_id and areas:
            area_id = areas[0]["id"]
        building_name = "Alpha"
        if not area_id:
            bld_resp = requests.get(f"{backend_url.rstrip('/')}/api/buildings", headers=headers, timeout=5.0)
            buildings = bld_resp.json().get("data", []) if bld_resp.status_code == 200 else []
            floor_id = None
            if buildings:
                bld_id = buildings[0]["id"]
                building_name = buildings[0].get("name") or building_name
                fl_resp = requests.get(f"{backend_url.rstrip('/')}/api/buildings/{bld_id}/floors", headers=headers, timeout=5.0)
                floors = fl_resp.json().get("data", []) if fl_resp.status_code == 200 else []
                if floors:
                    floor_id = floors[0]["id"]
            ar_payload: Dict[str, Any] = {
                "name": f"NFR Bench Area {uuid.uuid4().hex[:6].upper()}",
                "areaLevel": "CONFIDENTIAL",
                "centerLatitude": 10.8411,
                "centerLongitude": 106.8099,
            }
            if floor_id:
                ar_payload["floorId"] = floor_id
            else:
                ar_payload["building"] = building_name
                ar_payload["floor"] = "Tầng 1"
            ar_resp = requests.post(
                f"{backend_url.rstrip('/')}/api/areas",
                headers=headers,
                json=ar_payload,
                timeout=5.0,
            )
            if ar_resp.status_code in (200, 201):
                area_id = ar_resp.json()["data"]["id"]

        cam_code = preferred_camera_code or "NFR-BENCH-CAM"
        cam_payload: Dict[str, Any] = {
            "cameraCode": cam_code,
            "name": "NFR Latency Bench Camera",
            "cameraType": "RESTRICTED_AREA" if area_id else "CORRIDOR",
            "areaId": area_id,
            "mapBuilding": building_name,
            "mapFloorOrder": 1,
        }
        cam_resp = requests.post(
            f"{backend_url.rstrip('/')}/api/cameras",
            headers=headers,
            json=cam_payload,
            timeout=5.0,
        )
        if cam_resp.status_code not in (200, 201):
            raise RuntimeError(f"Could not create test camera via API: {cam_resp.status_code} {cam_resp.text}")
        selected_cam = cam_resp.json()["data"]["cameraCode"]

    # Find a userCode for check-entry benchmark
    user_code: Optional[str] = None
    users_resp = requests.get(f"{backend_url.rstrip('/')}/api/users?size=10", headers=headers, timeout=5.0)
    if users_resp.status_code == 200:
        users = users_resp.json().get("data", {}).get("content", [])
        if users:
            user_code = users[0].get("userCode")
    return token, selected_cam, user_code


def _measure_ai_inference_phase(samples: int) -> Dict[str, Any]:
    """Measure real 1080p VideoPipeline inference latency (YOLO26 + ByteTrack + SCRFD + Engine + Overlay)."""
    sys.path.insert(0, str(AI_SERVICE_DIR))
    from src.core.entity import Point
    from src.pipeline.video_pipeline import VideoPipeline

    roi = [Point(100, 100), Point(1820, 100), Point(1820, 980), Point(100, 980)]
    pipeline = VideoPipeline(camera_code="NFR-INFER-CAM", roi_polygon=roi, enable_motion_gating=False)
    # Disable external network calls inside pure inference measurement
    pipeline.storage_service.client = None
    pipeline.storage_service.is_connected = False
    pipeline.mqtt_client = None
    pipeline.io_executor = None
    pipeline.face_matcher.last_cache_time = time.time() + 999999.0

    base_frame = np.full((1080, 1920, 3), 40, dtype=np.uint8)
    for _ in range(3):
        pipeline.process_frame(base_frame, need_annotated=True)

    capture_to_infer_ms: List[float] = []
    for i in range(samples):
        frame = base_frame.copy()
        cv2.rectangle(frame, (300 + (i % 10) * 8, 220), (460 + (i % 10) * 8, 660), (200, 200, 200), -1)
        t_cap = time.time()
        pipeline.process_frame(frame, current_time=t_cap, need_annotated=True)
        t_done = time.time()
        capture_to_infer_ms.append((t_done - t_cap) * 1000.0)

    pipeline.stop()
    return _stats_ms(capture_to_infer_ms)


def _measure_check_entry_phase(
    backend_url: str,
    camera_code: str,
    user_code: str,
    service_key: str,
    samples: int,
) -> Dict[str, Any]:
    """Measure real HTTP POST /api/access-control/check-entry round-trip via FaceMatcher."""
    sys.path.insert(0, str(AI_SERVICE_DIR))
    from unittest.mock import patch
    from src.config import settings
    from src.core.face_matcher import FaceMatcher

    old_url = settings.BACKEND_API_URL
    old_key = settings.INTERNAL_SERVICE_KEY
    settings.BACKEND_API_URL = backend_url
    settings.INTERNAL_SERVICE_KEY = service_key

    try:
        with patch.object(FaceMatcher, "_get_connection", return_value=None):
            matcher = FaceMatcher(db_host="localhost", db_port=5432, db_user="sep", db_pass="test", db_name="campus_security_test")

        # Warm up connection pool
        matcher.check_camera_access(camera_code, user_code, skip_cache=True)

        uncached_ms: List[float] = []
        for _ in range(samples):
            t0 = time.perf_counter()
            matcher.check_camera_access(camera_code, user_code, skip_cache=True)
            uncached_ms.append((time.perf_counter() - t0) * 1000.0)

        # Cached hits
        matcher.check_camera_access(camera_code, user_code, skip_cache=False)
        cached_ms: List[float] = []
        for _ in range(samples):
            t0 = time.perf_counter()
            matcher.check_camera_access(camera_code, user_code, skip_cache=False)
            cached_ms.append((time.perf_counter() - t0) * 1000.0)

        return {
            "uncached_http_ms": _stats_ms(uncached_ms),
            "cached_ram_ms": _stats_ms(cached_ms),
        }
    finally:
        settings.BACKEND_API_URL = old_url
        settings.INTERNAL_SERVICE_KEY = old_key


def _measure_e2e_chain(
    samples: int,
    mqtt_host: str,
    mqtt_port: int,
    backend_url: Optional[str],
    jwt_token: Optional[str],
    camera_code: str,
    use_minio: bool,
    backend_log_path: Optional[Path],
) -> Dict[str, Any]:
    """
    Measure the 4-stage alert chain across >=30 samples:
      (a) frame_captured_at -> engine detected_at -> (b) mqtt_published_at -> (c) mqtt_received_at -> (d) ws_pushed_at / ws_client_received_at.
    """
    sys.path.insert(0, str(AI_SERVICE_DIR))
    import paho.mqtt.client as mqtt
    from src.config import settings
    from src.core.entity import BoundingBox, FaceDetectionResult, Point, SecurityAlertEvent
    from src.core.frame_analysis_engine import FrameAnalysisEngine
    from src.integration.alert_outbox import AlertOutbox
    from src.integration.mqtt_client import SecurityMqttClient
    from src.integration.storage_service import StorageService

    import shutil
    outbox_dir = tempfile.mkdtemp(prefix="nfr-outbox-")
    outbox = AlertOutbox(path=os.path.join(outbox_dir, "nfr_outbox.sqlite3"))

    old_client_id = settings.MQTT_CLIENT_ID
    settings.MQTT_CLIENT_ID = f"nfr-ai-bench-{os.getpid()}"
    settings.MQTT_ENABLED = True
    mqtt_client = SecurityMqttClient(host=mqtt_host, port=mqtt_port, outbox=outbox, start_flusher=True)

    # Wait for MQTT connect
    wait_deadline = time.time() + 8.0
    while not mqtt_client.is_connected and time.time() < wait_deadline:
        time.sleep(0.05)
    if not mqtt_client.is_connected:
        settings.MQTT_CLIENT_ID = old_client_id
        mqtt_client.close()
        outbox.close()
        raise RuntimeError(f"Could not connect AI SecurityMqttClient to {mqtt_host}:{mqtt_port}")

    # Broker-echo subscriber to measure raw MQTT broker hop (b -> broker subscriber) in all modes
    broker_recv_ms: Dict[str, float] = {}
    broker_lock = threading.Lock()
    sub_client = None
    try:
        sub_client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"nfr-broker-probe-{os.getpid()}",
        )
        backend_mqtt_pw = os.environ.get("MQTT_BACKEND_PASSWORD") or os.environ.get("MQTT_AI_PASSWORD") or ""
        backend_mqtt_user = os.environ.get("MQTT_BACKEND_USERNAME", "icss-backend")
        if backend_mqtt_pw:
            sub_client.username_pw_set(backend_mqtt_user, backend_mqtt_pw)
        sub_ready = threading.Event()

        def _on_sub_connect(client, userdata, flags, rc, properties=None):
            client.subscribe(settings.MQTT_TOPIC_ALERTS, qos=1)
            sub_ready.set()

        def _on_sub_msg(client, userdata, msg):
            ts_ms = time.time() * 1000.0
            try:
                data = json.loads(msg.payload.decode("utf-8"))
                ev_id = data.get("event_id")
                if ev_id:
                    with broker_lock:
                        broker_recv_ms[ev_id] = {
                            "recv_ms": ts_ms,
                            "mqtt_published_ms": _parse_iso_to_epoch_ms(data.get("mqtt_published_at")),
                            "frame_captured_ms": _parse_iso_to_epoch_ms(data.get("frame_captured_at")),
                        }
            except Exception:
                pass

        sub_client.on_connect = _on_sub_connect
        sub_client.on_message = _on_sub_msg
        sub_client.connect(mqtt_host, mqtt_port, keepalive=30)
        sub_client.loop_start()
        sub_ready.wait(timeout=5.0)
    except Exception:
        sub_client = None

    ws_collector: Optional[StompWsCollector] = None
    if backend_url and jwt_token:
        ws_scheme = "wss" if backend_url.startswith("https://") else "ws"
        host_part = backend_url.split("://", 1)[1].rstrip("/")
        ws_url = f"{ws_scheme}://{host_part}/ws-security"
        ws_collector = StompWsCollector(ws_url=ws_url, jwt_token=jwt_token, topic="/topic/security-alerts")
        ws_collector.start(timeout=10.0)

    storage = StorageService() if use_minio else None
    roi = [Point(50, 50), Point(600, 50), Point(600, 450), Point(50, 450)]
    bbox = BoundingBox(150, 100, 280, 380, confidence=0.92)
    snapshot_frame = np.full((720, 1280, 3), 64, dtype=np.uint8)

    records: List[Dict[str, Any]] = []
    engine = FrameAnalysisEngine(after_hour_start="23:59", after_hour_end="00:00")

    for i in range(samples):
        track_id = 5000 + i
        # Distinct event per sample (unique track + unique user code so backend dedup doesn't swallow it)
        face = FaceDetectionResult(
            bbox=bbox,
            score=0.94,
            matched_code=f"NFRUSER{i:03d}",
            matched_name=f"NFR Bench User {i}",
            is_authorized=False,
            is_active=True,
            access_checked=True,
            access_reason="Cấp độ truy cập không đủ",
        )
        engine.associate_face(track_id=track_id, face_result=face)

        # Stage (a): Frame capture timestamp
        t_capture_ms = time.time() * 1000.0
        _, alerts, _ = engine.process_frame(
            detected_tracks=[(track_id, bbox)],
            roi_polygon=roi,
            camera_code=camera_code,
            current_time=t_capture_ms / 1000.0,
        )
        assert alerts, f"Sample {i} failed to emit alert"
        alert: SecurityAlertEvent = alerts[0]
        # Prefix with EVT- so backend accepts even if camera is CORRIDOR/PUBLIC during daytime
        alert.event_id = f"EVT-NFR-{uuid.uuid4().hex[:12]}"
        # Populate frame_captured_at if SecurityAlertEvent supports it (post-coordination)
        if hasattr(alert, "frame_captured_at"):
            setattr(alert, "frame_captured_at", _epoch_ms_to_iso_utc(t_capture_ms))

        # Optional MinIO upload (matches VideoPipeline._dispatch_alert_async)
        if storage is not None and storage.is_connected:
            try:
                res = storage.precompute_evidence_key(camera_code, alert.track_id, event_type=alert.event_type)
                if isinstance(res, (tuple, list)) and len(res) >= 4:
                    obj_name, obj_key, file_name, date_str = res[:4]
                    up_res = storage.upload_frame_evidence_with_key(
                        snapshot_frame, camera_code, obj_name, obj_key, file_name, date_str
                    )
                    if up_res == obj_key:
                        alert.image_url = obj_key
            except Exception:
                pass

        # Stage (b): AI MQTT Publish
        t_pub_call_ms = time.time() * 1000.0
        mqtt_client.send_alert(alert)
        # Prefer stamped mqtt_published_at on event if @ai-fixer added it
        pub_iso = getattr(alert, "mqtt_published_at", None)
        t_pub_ms = _parse_iso_to_epoch_ms(pub_iso) or t_pub_call_ms
        t_det_ms = _parse_iso_to_epoch_ms(alert.detected_at) or t_pub_ms

        records.append({
            "event_id": alert.event_id,
            "a_frame_captured_ms": t_capture_ms,
            "engine_detected_ms": t_det_ms,
            "b_mqtt_published_ms": t_pub_ms,
        })
        # Small spacing so broker/backend don't batch into a single frame
        time.sleep(0.04)

    # Wait up to 8s for all WebSocket & broker deliveries
    if ws_collector is not None:
        wait_end = time.time() + 8.0
        while time.time() < wait_end:
            with ws_collector._lock:
                got = sum(1 for r in records if r["event_id"] in ws_collector.received_by_event_id)
            if got >= len(records):
                break
            time.sleep(0.05)

    wait_broker_end = time.time() + 3.0
    if sub_client is not None:
        while time.time() < wait_broker_end:
            with broker_lock:
                got_b = sum(1 for r in records if r["event_id"] in broker_recv_ms)
            if got_b >= len(records):
                break
            time.sleep(0.05)

    backend_traces = _parse_backend_trace_log(backend_log_path)

    hop_a_to_det: List[float] = []
    hop_det_to_b: List[float] = []
    hop_a_to_b: List[float] = []
    hop_b_to_broker: List[float] = []
    hop_b_to_c: List[float] = []
    hop_c_to_d: List[float] = []
    hop_b_to_ws_client: List[float] = []
    e2e_a_to_d: List[float] = []

    ws_received_count = 0
    for r in records:
        ev_id = r["event_id"]
        a_ms = r["a_frame_captured_ms"]
        det_ms = r["engine_detected_ms"]
        b_ms = r["b_mqtt_published_ms"]

        with broker_lock:
            br_info = broker_recv_ms.get(ev_id)
        if br_info is not None:
            br_ms = br_info["recv_ms"]
            if br_info.get("mqtt_published_ms") is not None:
                b_ms = br_info["mqtt_published_ms"]
            if br_info.get("frame_captured_ms") is not None:
                a_ms = br_info["frame_captured_ms"]
            hop_b_to_broker.append(max(0.0, br_ms - b_ms))

        hop_a_to_det.append(max(0.0, det_ms - a_ms))
        hop_det_to_b.append(max(0.0, b_ms - det_ms))
        hop_a_to_b.append(max(0.0, b_ms - a_ms))

        trace = backend_traces.get(ev_id, {})
        if ws_collector is not None:
            with ws_collector._lock:
                ws_item = ws_collector.received_by_event_id.get(ev_id)
            if ws_item is not None:
                ws_received_count += 1
                ws_client_ms = ws_item["ws_client_received_ms"]
                payload = ws_item["payload"]
                c_ms = trace.get("mqttReceivedAt") or _parse_iso_to_epoch_ms(payload.get("mqttReceivedAt"))
                d_ms = trace.get("wsPushedAt") or _parse_iso_to_epoch_ms(payload.get("wsPushedAt")) or ws_client_ms

                if c_ms is not None:
                    hop_b_to_c.append(max(0.0, c_ms - b_ms))
                    hop_c_to_d.append(max(0.0, d_ms - c_ms))
                hop_b_to_ws_client.append(max(0.0, ws_client_ms - b_ms))
                e2e_a_to_d.append(max(0.0, ws_client_ms - a_ms))

    if ws_collector is not None:
        ws_collector.stop()
    if sub_client is not None:
        sub_client.loop_stop()
        sub_client.disconnect()
    settings.MQTT_CLIENT_ID = old_client_id
    mqtt_client.close()
    outbox.close()
    shutil.rmtree(outbox_dir, ignore_errors=True)

    return {
        "samples_sent": len(records),
        "ws_received_count": ws_received_count,
        "hop_a_capture_to_engine_detected_ms": _stats_ms(hop_a_to_det),
        "hop_engine_to_b_mqtt_publish_ms": _stats_ms(hop_det_to_b),
        "hop_a_to_b_ai_total_ms": _stats_ms(hop_a_to_b),
        "hop_b_to_mosquitto_sub_ms": _stats_ms(hop_b_to_broker),
        "hop_b_to_c_backend_mqtt_receive_ms": _stats_ms(hop_b_to_c),
        "hop_c_to_d_backend_db_and_ws_push_ms": _stats_ms(hop_c_to_d),
        "hop_b_to_ws_client_ms": _stats_ms(hop_b_to_ws_client),
        "e2e_a_to_d_ms": _stats_ms(e2e_a_to_d),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Measure NFR-01 3s end-to-end latency (>=30 samples)")
    parser.add_argument("--samples", type=int, default=30, help="Number of samples per phase (default: 30)")
    parser.add_argument("--mode", choices=("auto", "full", "ai-broker"), default="auto")
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    parser.add_argument("--start-test-backend", action="store_true", help="Start isolated Spring Boot test backend on port 18088")
    parser.add_argument("--test-backend-port", type=int, default=18088)
    parser.add_argument("--mqtt-host", default=None)
    parser.add_argument("--mqtt-port", type=int, default=None)
    parser.add_argument("--camera-code", default=None)
    parser.add_argument("--user-code", default=None)
    parser.add_argument("--email", default=os.environ.get("NFR_ADMIN_EMAIL", "admin@fpt.edu.vn"))
    parser.add_argument("--password", default=os.environ.get("NFR_ADMIN_PASSWORD", "123456"))
    parser.add_argument("--use-minio", action="store_true", help="Include real local MinIO snapshot upload in E2E chain")
    parser.add_argument("--skip-yolo", action="store_true", help="Skip 1080p full VideoPipeline inference phase")
    parser.add_argument("--backend-log", default=None, help="Optional backend log path for [NFR-TRACE] parsing")
    parser.add_argument("--output-json", default=None, help="Optional output path for JSON summary")
    args = parser.parse_args()

    if args.samples < 30:
        print(f"[WARN] samples={args.samples} < 30; NFR verification requires >= 30 samples.")

    _load_env_quietly()
    mqtt_host = args.mqtt_host or os.environ.get("MQTT_BROKER_HOST", "127.0.0.1")
    mqtt_port = args.mqtt_port or int(os.environ.get("MQTT_BROKER_PORT", "1883"))

    managed_backend: Optional[ManagedTestBackend] = None
    backend_url: Optional[str] = None
    backend_log_path = Path(args.backend_log) if args.backend_log else None

    try:
        if args.mode != "ai-broker":
            if args.start_test_backend:
                managed_backend = ManagedTestBackend(port=args.test_backend_port, mqtt_port=mqtt_port)
                print(f"[INFO] Starting isolated Spring Boot test backend on {managed_backend.base_url} (profile=test, DB=campus_security_test)...")
                managed_backend.start(timeout=120.0)
                backend_url = managed_backend.base_url
                backend_log_path = managed_backend.log_path
            elif _backend_healthy(args.backend_url):
                backend_url = args.backend_url
            elif args.mode == "full":
                raise RuntimeError(f"Backend not reachable at {args.backend_url}; pass --start-test-backend or start backend first.")

        jwt_token: Optional[str] = None
        camera_code = args.camera_code or "CAM-NFR-BENCH"
        user_code = args.user_code or "AD-001"

        if backend_url:
            jwt_token, camera_code, detected_user = _login_and_prepare_test_camera(
                backend_url, args.email, args.password, preferred_camera_code=args.camera_code
            )
            if not args.user_code and detected_user:
                user_code = detected_user

        print("=" * 80)
        print("NFR-01 END-TO-END LATENCY MEASUREMENT")
        print(f"Environment   : LOCAL SERVICES (Mosquitto={mqtt_host}:{mqtt_port}, Backend={backend_url or 'NONE'}, MinIO={'ON' if args.use_minio else 'OFF'})")
        print(f"Samples       : {args.samples} | Camera: {camera_code}")
        print("=" * 80)

        infer_stats = None
        if not args.skip_yolo:
            print(f"[1/3] Measuring full 1080p AI VideoPipeline inference ({args.samples} frames)...")
            infer_stats = _measure_ai_inference_phase(args.samples)
            print(f"      1080p AI Inference: median={infer_stats['median']} ms, p95={infer_stats['p95']} ms, max={infer_stats['max']} ms")

        print(f"[2/3] Measuring 4-stage alert chain ({args.samples} alerts)...")
        chain_stats = _measure_e2e_chain(
            samples=args.samples,
            mqtt_host=mqtt_host,
            mqtt_port=mqtt_port,
            backend_url=backend_url,
            jwt_token=jwt_token,
            camera_code=camera_code,
            use_minio=args.use_minio,
            backend_log_path=backend_log_path,
        )

        check_entry_stats = None
        if backend_url:
            if managed_backend is not None:
                service_key = "test-only-internal-service-key-0123456789"
            else:
                service_key = os.environ.get("INTERNAL_SERVICE_KEY") or "test-only-internal-service-key-0123456789"
            print(f"[3/3] Measuring check-entry HTTP hop ({args.samples} calls)...")
            check_entry_stats = _measure_check_entry_phase(
                backend_url=backend_url,
                camera_code=camera_code,
                user_code=user_code,
                service_key=service_key,
                samples=args.samples,
            )

        summary = {
            "timestamp_utc": _epoch_ms_to_iso_utc(time.time() * 1000.0),
            "environment": {
                "mode": "local-e2e" if backend_url else "local-ai-broker-only",
                "mqtt_broker": f"{mqtt_host}:{mqtt_port}",
                "backend_url": backend_url,
                "minio_upload_enabled": args.use_minio,
                "cloud_railway_measured": False,
                "live_mac_camera_measured": False,
                "note": "Measured against local services/synthetic frames; Railway WAN RTT & live Mac RTSP capture lag not included.",
            },
            "samples": args.samples,
            "ai_1080p_inference_ms": infer_stats,
            "alert_chain": chain_stats,
            "check_entry": check_entry_stats,
        }

        print("\n" + "-" * 80)
        print(f"{'METRIC / HOP':<44} | {'N':>4} | {'MEDIAN(ms)':>10} | {'P95(ms)':>10} | {'MAX(ms)':>10}")
        print("-" * 80)
        rows = []
        if infer_stats:
            rows.append(("1080p VideoPipeline inference (a->det)", infer_stats))
        rows.extend([
            ("Alert Engine (a->detected_at)", chain_stats["hop_a_capture_to_engine_detected_ms"]),
            ("AI Pre-Publish (detected_at->b)", chain_stats["hop_engine_to_b_mqtt_publish_ms"]),
            ("MQTT Broker Echo (b->mosquitto sub)", chain_stats["hop_b_to_mosquitto_sub_ms"]),
            ("Backend MQTT Receive (b->c)", chain_stats["hop_b_to_c_backend_mqtt_receive_ms"]),
            ("Backend Ingest+WS Push (c->d)", chain_stats["hop_c_to_d_backend_db_and_ws_push_ms"]),
            ("MQTT Pub -> WS Client Recv (b->d)", chain_stats["hop_b_to_ws_client_ms"]),
            ("END-TO-END Chain (a->d)", chain_stats["e2e_a_to_d_ms"]),
        ])
        if check_entry_stats:
            rows.append(("check-entry HTTP uncached", check_entry_stats["uncached_http_ms"]))
            rows.append(("check-entry RAM cache hit", check_entry_stats["cached_ram_ms"]))

        for label, st in rows:
            n = st.get("count", 0)
            med = f"{st['median']:.2f}" if st.get("median") is not None else "N/A"
            p95 = f"{st['p95']:.2f}" if st.get("p95") is not None else "N/A"
            mx = f"{st['max']:.2f}" if st.get("max") is not None else "N/A"
            print(f"{label:<44} | {n:>4} | {med:>10} | {p95:>10} | {mx:>10}")
        print("-" * 80)

        if infer_stats and chain_stats["e2e_a_to_d_ms"]["p95"] is not None:
            combined_p95 = infer_stats["p95"] + chain_stats["hop_b_to_ws_client_ms"]["p95"]
            print(f"Combined 1080p AI Inference P95 + MQTT->WS P95 = {combined_p95:.2f} ms (Target <= 3000.00 ms)")

        if args.output_json:
            out_path = Path(args.output_json)
            out_path.parent.mkdir(parents=True, exist_ok=True)
            out_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
            print(f"[INFO] Wrote JSON report to {out_path}")
        return 0
    finally:
        if managed_backend is not None:
            managed_backend.stop()


if __name__ == "__main__":
    raise SystemExit(main())
