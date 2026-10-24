import os
import cv2
import time
import base64
import asyncio
import numpy as np
from fastapi import FastAPI, File, UploadFile, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional, Dict, Any

import logging
from .config import settings, INTERNAL_SERVICE_KEY_HEADER
from .pipeline.video_pipeline import VideoPipeline
from .pipeline.stream_worker import CameraStreamWorker
from .integration.storage_service import StorageService

logger = logging.getLogger("ai_service")
storage_service = StorageService()
active_workers: Dict[str, CameraStreamWorker] = {}

app = FastAPI(
    title=settings.APP_NAME,
    version="1.0.0",
    description="Microservice xử lý AI: Human Detection, Tracking (ByteTrack), Face Detection & Violation Analysis"
)

# Cho phép CORS cho frontend và các service khác
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Pipeline mặc định cho Camera 1
default_pipeline: Optional[VideoPipeline] = None

async def sync_ai_config_from_backend(max_retries: int = 10, retry_delay: float = 3.0):
    """
    Tự động kéo cấu hình an ninh và runtime mới nhất từ Backend CSDL khi khởi động.
    Tránh lệch ngưỡng giữa AI service và Database khi restart hoặc demo sau khi bật máy.
    """
    backend_url = getattr(settings, "BACKEND_API_URL", "http://localhost:8080").rstrip("/")
    endpoint = f"{backend_url}/api/internal/ai/runtime-config"
    for attempt in range(1, max_retries + 1):
        try:
            import requests
            resp = requests.get(endpoint, headers={INTERNAL_SERVICE_KEY_HEADER: settings.INTERNAL_SERVICE_KEY}, timeout=3.0)
            if resp.status_code == 401:
                logger.warning("⚠️ Backend từ chối X-Service-Key khi kéo cấu hình AI: kiểm tra INTERNAL_SERVICE_KEY hai bên có trùng nhau")
            if resp.status_code == 200:
                body = resp.json()
                data = body.get("data", {})
                if data:
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

                    if default_pipeline:
                        if ah_start:
                            default_pipeline.analysis_engine.after_hour_start = settings.DEFAULT_AFTER_HOUR_START
                        if ah_end:
                            default_pipeline.analysis_engine.after_hour_end = settings.DEFAULT_AFTER_HOUR_END
                        if max_inactive is not None:
                            default_pipeline.analysis_engine.max_inactive_seconds = settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS
                        if cooldown is not None:
                            default_pipeline.analysis_engine.unknown_alert_cooldown = settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS

                    for worker in active_workers.values():
                        worker.update_runtime_config(
                            max_inactive_seconds=settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS,
                            unknown_alert_cooldown=settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS
                        )
                        if ah_start or ah_end:
                            worker.update_roi(
                                after_hour_start=settings.DEFAULT_AFTER_HOUR_START,
                                after_hour_end=settings.DEFAULT_AFTER_HOUR_END
                            )

                    logger.info(
                        f"✅ Đồng bộ cấu hình AI thành công từ Backend: threshold={settings.FACE_MATCH_THRESHOLD}, "
                        f"after_hours={settings.DEFAULT_AFTER_HOUR_START}-{settings.DEFAULT_AFTER_HOUR_END}, "
                        f"max_inactive={settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS}s, cooldown={settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS}s"
                    )
                    return True
        except Exception as e:
            logger.debug(f"Thử kéo cấu hình từ Backend (lần {attempt}/{max_retries}) chưa thành công: {e}")
        await asyncio.sleep(retry_delay)
    logger.warning("⚠️ Không thể kết nối Backend để lấy cấu hình runtime, sử dụng cấu hình mặc định cục bộ.")
    return False

@app.on_event("startup")
async def startup_event():
    global default_pipeline
    try:
        default_pipeline = VideoPipeline(
            camera_code="CAM-001",
            conf_threshold=settings.DEFAULT_DETECTION_CONFIDENCE
        )
    except Exception as e:
        print(f"[!] Cảnh báo khi khởi tạo pipeline: {e}")
    asyncio.create_task(sync_ai_config_from_backend())

@app.get("/")
async def root():
    return {
        "service": settings.APP_NAME,
        "status": "ONLINE",
        "timestamp": time.time(),
        "version": "1.0.0"
    }

@app.get("/health")
async def health_check():
    return {
        "status": "UP",
        "models": {
            "yolo_model": os.path.exists(settings.MODEL_YOLO_PATH) or True,
            "yunet_model": os.path.exists(settings.MODEL_YUNET_PATH),
            "arcface_model": os.path.exists(settings.MODEL_ARCFACE_PATH)
        },
        "kafka_connected": default_pipeline.kafka_producer.is_connected if default_pipeline else False,
        "minio_connected": default_pipeline.storage_service.is_connected if default_pipeline else False
    }

class ROIConfigRequest(BaseModel):
    camera_code: str
    roi_polygon: Optional[List[Dict[str, float]]] = None
    polygons: Optional[List[Dict[str, Any]]] = None
    reference_width: Optional[int] = 1920
    reference_height: Optional[int] = 1080
    after_hour_start: Optional[str] = None
    after_hour_end: Optional[str] = None

class AfterHourConfigRequest(BaseModel):
    start: str
    end: str

@app.post("/api/v1/system-config/after-hour")
async def update_after_hour_config(req: AfterHourConfigRequest):
    import datetime
    try:
        datetime.time.fromisoformat(req.start)
        datetime.time.fromisoformat(req.end)
    except ValueError:
        raise HTTPException(status_code=400, detail="after-hour times must use HH:mm")
    if default_pipeline:
        default_pipeline.analysis_engine.after_hour_start = req.start
        default_pipeline.analysis_engine.after_hour_end = req.end
    for worker in active_workers.values():
        worker.pipeline.analysis_engine.after_hour_start = req.start
        worker.pipeline.analysis_engine.after_hour_end = req.end
    return {"success": True, "start": req.start, "end": req.end}

class AIRuntimeConfigRequest(BaseModel):
    max_inactive_seconds: Optional[float] = None
    unknown_alert_cooldown: Optional[float] = None
    face_match_threshold: Optional[float] = None

@app.post("/api/v1/system-config/ai-runtime")
async def update_ai_runtime_config(req: AIRuntimeConfigRequest):
    if req.max_inactive_seconds is not None:
        if req.max_inactive_seconds < 1.0 or req.max_inactive_seconds > 60.0:
            raise HTTPException(status_code=400, detail="max_inactive_seconds must be between 1.0 and 60.0")
        settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS = req.max_inactive_seconds
        if default_pipeline:
            default_pipeline.analysis_engine.max_inactive_seconds = req.max_inactive_seconds
        for worker in active_workers.values():
            worker.update_runtime_config(max_inactive_seconds=req.max_inactive_seconds)

    if req.unknown_alert_cooldown is not None:
        if req.unknown_alert_cooldown < 5.0 or req.unknown_alert_cooldown > 300.0:
            raise HTTPException(status_code=400, detail="unknown_alert_cooldown must be between 5.0 and 300.0")
        settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS = req.unknown_alert_cooldown
        if default_pipeline:
            default_pipeline.analysis_engine.unknown_alert_cooldown = req.unknown_alert_cooldown
        for worker in active_workers.values():
            worker.update_runtime_config(unknown_alert_cooldown=req.unknown_alert_cooldown)

    if req.face_match_threshold is not None:
        if req.face_match_threshold < 0.1 or req.face_match_threshold > 1.0:
            raise HTTPException(status_code=400, detail="face_match_threshold must be between 0.1 and 1.0")
        settings.FACE_MATCH_THRESHOLD = req.face_match_threshold

    logger.info(
        f"✅ Cập nhật cấu hình runtime AI: max_inactive_seconds={settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS}s, "
        f"cooldown={settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS}s, threshold={settings.FACE_MATCH_THRESHOLD}"
    )
    return {
        "success": True,
        "max_inactive_seconds": settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS,
        "unknown_alert_cooldown": settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS,
        "face_match_threshold": settings.FACE_MATCH_THRESHOLD
    }

@app.post("/api/v1/cameras/configure")
async def configure_camera(req: ROIConfigRequest):
    global default_pipeline
    if not default_pipeline:
        raise HTTPException(status_code=500, detail="Pipeline chưa sẵn sàng.")

    polygons_to_set = []
    if req.polygons:
        polygons_to_set = req.polygons
    elif req.roi_polygon:
        polygons_to_set = [{
            "label": "Vùng ROI",
            "alert_rules": ["ENTRY_EXIT_TRACKING"],
            "vertices": req.roi_polygon
        }]

    ah_start = req.after_hour_start or settings.DEFAULT_AFTER_HOUR_START
    ah_end = req.after_hour_end or settings.DEFAULT_AFTER_HOUR_END

    default_pipeline.camera_code = req.camera_code
    default_pipeline.analysis_engine.after_hour_start = ah_start
    default_pipeline.analysis_engine.after_hour_end = ah_end
    default_pipeline.set_roi_config(polygons=polygons_to_set)

    worker_updated = False
    if req.camera_code in active_workers and active_workers[req.camera_code].is_running:
        active_workers[req.camera_code].update_roi(
            polygons=polygons_to_set,
            after_hour_start=ah_start,
            after_hour_end=ah_end
        )
        worker_updated = True
    else:
        logger.info(f"ℹ️ Camera [{req.camera_code}] chưa có Stream Worker đang chạy. Cấu hình ROI đã được áp dụng vào pipeline mặc định.")

    return {
        "success": True,
        "camera_code": req.camera_code,
        "polygons_count": len(polygons_to_set),
        "worker_updated": worker_updated
    }

@app.post("/api/v1/analyze-frame")
async def analyze_frame(file: UploadFile = File(...)):
    global default_pipeline
    if not default_pipeline:
        raise HTTPException(status_code=500, detail="Pipeline chưa sẵn sàng.")

    contents = await file.read()
    nparr = np.frombuffer(contents, np.uint8)
    frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    if frame is None:
        raise HTTPException(status_code=400, detail="Ảnh không hợp lệ hoặc bị hỏng.")

    annotated_frame, active_persons, alerts = default_pipeline.process_frame(frame)

    return {
        "total_persons": len(active_persons),
        "persons": [
            {
                "track_id": p.track_id,
                "bbox": p.bbox.to_int_xyxy(),
                "is_in_roi": p.is_in_roi,
                "face_detected": p.face_detected,
                "face_score": p.face_info.score if p.face_info else None,
                "alert_unauthorized_sent": p.alert_unauthorized_sent
            }
            for p in active_persons
        ],
        "alerts_generated": [a.to_dict() for a in alerts]
    }

from fastapi import Form
from .core.face_detector import FaceDetector
from .core.face_embedder import FaceEmbedder

face_detector = FaceDetector()
face_embedder = FaceEmbedder(embedding_dim=512)

@app.post("/api/v1/faces/process-registration")
async def process_face_registration(
    code: str = Form(...),
    full_name: Optional[str] = Form(None),
    front_image: UploadFile = File(...)
):
    raw_bytes = await front_image.read()
    if not raw_bytes:
        raise HTTPException(status_code=400, detail="Ảnh chính diện bị rỗng.")

    from .utils.image_utils import decode_image_safely

    try:
        img, jpeg_bytes = decode_image_safely(raw_bytes)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Không thể đọc file ảnh chính diện: {str(e)}")

    try:
        detector = default_pipeline.face_detector if default_pipeline else face_detector
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

        vector_512 = face_embedder.extract_embedding(face_crop, landmarks=landmarks)
    except Exception as e:
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Registration error: {type(e).__name__}: {str(e)}")

    return {
        "success": True,
        "code": code,
        "full_name": full_name,
        "face_count": face_count,
        "embedding_front": vector_512
    }

@app.post("/api/v1/faces/refresh-cache")
async def refresh_faces_cache():
    """
    Xóa và làm mới cache khuôn mặt cùng quyền truy cập ngay lập tức trong RAM AI.
    Được gọi khi tài khoản bị vô hiệu hóa, kích hoạt lại hoặc xóa mềm.
    """
    count = 0
    if default_pipeline and getattr(default_pipeline, "face_matcher", None):
        default_pipeline.face_matcher.refresh_cache()
        count += 1
    for worker in active_workers.values():
        if getattr(worker, "pipeline", None) and getattr(worker.pipeline, "face_matcher", None):
            worker.pipeline.face_matcher.refresh_cache()
            count += 1
    logger.info(f"🔄 [FaceCache] Đã làm mới face cache tức thì cho {count} pipeline(s).")
    return {"success": True, "pipelines_refreshed": count}

class StreamStartRequest(BaseModel):
    camera_code: str
    rtsp_url: str
    roi_geometry: Optional[Dict[str, Any]] = None

@app.post("/api/v1/stream/start")
async def start_camera_stream(req: StreamStartRequest):
    if not req.camera_code or not req.camera_code.strip():
        raise HTTPException(status_code=400, detail="Mã camera (camera_code) là bắt buộc.")
    if not req.rtsp_url or not req.rtsp_url.strip():
        raise HTTPException(status_code=400, detail="RTSP URL (rtsp_url) là bắt buộc.")

    camera_code = req.camera_code.strip()
    rtsp_url = req.rtsp_url.strip()

    if camera_code in active_workers and active_workers[camera_code].is_running:
        return {"status": "ALREADY_RUNNING", "camera_code": camera_code}

    try:
        worker = CameraStreamWorker(
            camera_code=camera_code,
            rtsp_url=rtsp_url,
            roi_geometry=req.roi_geometry
        )
        worker.start()
        active_workers[camera_code] = worker
        return {"status": "STARTED", "camera_code": camera_code, "rtsp_url": rtsp_url}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Không thể khởi động worker camera: {e}")

@app.post("/api/v1/stream/stop")
async def stop_camera_stream(camera_code: str = Query(..., description="Mã camera cần dừng, ví dụ: CAM-001")):
    if camera_code not in active_workers:
        raise HTTPException(status_code=404, detail="Camera worker không tồn tại.")
    
    active_workers[camera_code].stop()
    del active_workers[camera_code]
    return {"status": "STOPPED", "camera_code": camera_code}

@app.get("/api/v1/stream/status")
async def get_stream_status(camera_code: str = Query(..., description="Mã camera cần xem trạng thái, ví dụ: CAM-001")):
    if camera_code not in active_workers:
        return {"status": "STOPPED", "camera_code": camera_code, "is_running": False}
    
    return active_workers[camera_code].get_status()

from fastapi.responses import StreamingResponse

@app.get("/api/v1/stream/live-annotated")
async def get_live_annotated_feed(camera_code: str = Query(..., description="Mã camera, ví dụ: CAM-002")):
    """
    Luồng video trực tiếp đã được AI vẽ Bounding Box, Track ID, Khuôn mặt và Vùng ROI theo thời gian thực.
    Mở trực tiếp trên trình duyệt Web để xem AI đang tracking thế nào:
    http://localhost:8000/api/v1/stream/live-annotated?camera_code=CAM-002
    """
    if camera_code not in active_workers or not active_workers[camera_code].is_running:
        raise HTTPException(status_code=404, detail="Camera worker hiện chưa chạy. Hãy Kết nối Stream trước.")
        
    async def frame_generator():
        worker = active_workers[camera_code]
        while worker.is_running:
            with worker.lock:
                frame = worker.latest_frame
            if frame is not None:
                _, buffer = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
                yield (b'--frame\r\n'
                       b'Content-Type: image/jpeg\r\n\r\n' + buffer.tobytes() + b'\r\n')
            await asyncio.sleep(0.08) # ~12 FPS preview
            
    return StreamingResponse(frame_generator(), media_type="multipart/x-mixed-replace; boundary=frame")

class SnapshotRequest(BaseModel):
    rtsp_url: str
    timeout_ms: int = 5000

def _read_rtsp_frame_sync(rtsp_url: str, timeout_ms: int):
    timeout_us = max(1000, timeout_ms) * 1000
    os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = f"rtsp_transport;tcp|stimeout;{timeout_us}"
    
    # Thử tối đa 2 lần để tránh xung đột socket khi người dùng vừa bấm Lưu luồng rồi bấm Thử kết nối liên tiếp
    for attempt in range(2):
        cap = cv2.VideoCapture(rtsp_url, cv2.CAP_FFMPEG)
        cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, timeout_ms)
        cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, timeout_ms)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        try:
            if cap.isOpened():
                for _ in range(5):
                    ret, frame = cap.read()
                    if ret and frame is not None:
                        return frame
                    time.sleep(0.05)
        finally:
            cap.release()
        if attempt == 0:
            time.sleep(0.25)
    return None

@app.post("/api/v1/cameras/snapshot")
async def capture_rtsp_snapshot(req: SnapshotRequest):
    start_time = time.time()
    timeout_sec = min(max(req.timeout_ms / 1000.0, 1.0), 4.0)
    
    logger.info(f"📸 [Snapshot] Bắt đầu trích xuất khung hình: url={req.rtsp_url}, timeout={req.timeout_ms}ms")
    try:
        loop = asyncio.get_running_loop()
        frame = await asyncio.wait_for(
            loop.run_in_executor(None, _read_rtsp_frame_sync, req.rtsp_url, req.timeout_ms),
            timeout=timeout_sec + 0.5
        )
    except asyncio.TimeoutError:
        logger.warning(f"⚠️ [Snapshot] Timeout khi trích xuất khung hình từ {req.rtsp_url}")
        raise HTTPException(status_code=504, detail="Quá thời gian kết nối RTSP stream (Timeout)")
    except Exception as e:
        logger.warning(f"⚠️ [Snapshot] Lỗi ngoại lệ khi trích xuất khung hình từ {req.rtsp_url}: {e}")
        raise HTTPException(status_code=502, detail=f"Lỗi trích xuất snapshot: {str(e)}")
        
    if frame is None:
        logger.warning(f"⚠️ [Snapshot] Không đọc được frame từ {req.rtsp_url}")
        raise HTTPException(status_code=502, detail="Không thể kết nối hoặc đọc frame từ RTSP")
        
    logger.info(f"✅ [Snapshot] Trích xuất thành công: shape={frame.shape} từ {req.rtsp_url}")

    latency_ms = int((time.time() - start_time) * 1000)
    height, width = frame.shape[:2]
    
    _, buffer = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 85])
    base64_str = base64.b64encode(buffer).decode('utf-8')
    
    return {
        "success": True,
        "snapshot_base64": f"data:image/jpeg;base64,{base64_str}",
        "width": width,
        "height": height,
        "latency_ms": latency_ms
    }
