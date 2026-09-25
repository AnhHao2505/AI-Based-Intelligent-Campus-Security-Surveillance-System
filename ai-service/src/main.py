import os
import cv2
import time
import base64
import numpy as np
from fastapi import FastAPI, File, UploadFile, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional, Dict, Any

from .config import settings
from .core.entity import Point
from .pipeline.video_pipeline import VideoPipeline
from .integration.storage_service import StorageService

storage_service = StorageService()

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
            "yunet_model": os.path.exists(settings.MODEL_YUNET_PATH)
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
    default_pipeline.set_roi_config(polygons_to_set)

    worker_updated = False
    if req.camera_code in active_workers:
        active_workers[req.camera_code].update_roi(polygons_to_set, ah_start, ah_end)
        worker_updated = True

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
from .core.face_embedder import FaceEmbedder

face_embedder = FaceEmbedder(embedding_dim=512)

@app.post("/api/v1/faces/process-registration")
async def process_face_registration(
    code: str = Form(...),
    full_name: Optional[str] = Form(None),
    front_image: UploadFile = File(...)
):
    global default_pipeline
    if not default_pipeline:
        raise HTTPException(status_code=500, detail="AI Service chưa sẵn sàng.")

    raw_bytes = await front_image.read()
    if not raw_bytes:
        raise HTTPException(status_code=400, detail="Ảnh chính diện bị rỗng.")

    from .utils.image_utils import decode_image_safely

    try:
        img, jpeg_bytes = decode_image_safely(raw_bytes)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Không thể đọc file ảnh chính diện: {str(e)}")

    faces = default_pipeline.face_detector.detect_in_image(img)
    face_count = len(faces)

    if not faces:
        face_crop = img
    else:
        faces.sort(key=lambda f: f.score, reverse=True)
        fx1, fy1, fx2, fy2 = faces[0].bbox.to_int_xyxy()
        ih, iw, _ = img.shape
        fx1, fy1 = max(0, fx1), max(0, fy1)
        fx2, fy2 = min(iw, fx2), min(ih, fy2)
        face_crop = img[fy1:fy2, fx1:fx2] if (fx2 > fx1 and fy2 > fy1) else img

    vector_512 = face_embedder.extract_embedding(face_crop)

    return {
        "success": True,
        "code": code,
        "full_name": full_name,
        "face_count": face_count,
        "embedding_front": vector_512
    }

from .pipeline.stream_worker import CameraStreamWorker
active_workers: Dict[str, CameraStreamWorker] = {}

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

class SnapshotRequest(BaseModel):
    rtsp_url: str
    timeout_ms: int = 5000

@app.post("/api/v1/cameras/snapshot")
async def capture_rtsp_snapshot(req: SnapshotRequest):
    start_time = time.time()
    
    os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "rtsp_transport;tcp"
    cap = cv2.VideoCapture(req.rtsp_url, cv2.CAP_FFMPEG)
    cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, req.timeout_ms)
    cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, req.timeout_ms)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    
    try:
        if not cap.isOpened():
            raise HTTPException(status_code=502, detail="Không thể kết nối RTSP stream")
        
        ret, frame = cap.read()
        if not ret or frame is None:
            raise HTTPException(status_code=502, detail="Không thể đọc frame từ RTSP")
        
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
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Lỗi trích xuất snapshot: {str(e)}")
    finally:
        cap.release()
