import os
import cv2
import time
import numpy as np
from fastapi import FastAPI, File, UploadFile, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional, Dict, Any

from .config import settings
from .core.entity import Point
from .pipeline.video_pipeline import VideoPipeline

app = FastAPI(
    title=settings.APP_NAME,
    version="1.0.0",
    description="Microservice xử lý AI: Human Detection, Tracking (ByteTrack), Face Detection & Loitering Analysis"
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
            loitering_threshold_seconds=settings.DEFAULT_LOITERING_THRESHOLD_SECONDS,
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
            "yolo_model": os.path.exists(settings.MODEL_YOLO_PATH) or True, # Ultralytics tải tự động
            "yunet_model": os.path.exists(settings.MODEL_YUNET_PATH)
        },
        "kafka_connected": default_pipeline.kafka_producer.is_connected if default_pipeline else False,
        "minio_connected": default_pipeline.storage_service.is_connected if default_pipeline else False
    }

class ROIConfigRequest(BaseModel):
    camera_code: str
    loitering_threshold_seconds: int = 10
    roi_polygon: List[Dict[str, float]] # [{"x": 100, "y": 200}, ...]

@app.post("/api/v1/cameras/configure")
async def configure_camera(req: ROIConfigRequest):
    global default_pipeline
    if not default_pipeline:
        raise HTTPException(status_code=500, detail="Pipeline chưa sẵn sàng.")
    
    polygon_points = [Point(p["x"], p["y"]) for p in req.roi_polygon]
    default_pipeline.camera_code = req.camera_code
    default_pipeline.loitering_threshold_seconds = req.loitering_threshold_seconds
    default_pipeline.set_roi_polygon(polygon_points)
    
    return {
        "success": True,
        "camera_code": req.camera_code,
        "loitering_threshold_seconds": req.loitering_threshold_seconds,
        "roi_points_count": len(polygon_points)
    }

@app.post("/api/v1/analyze-frame")
async def analyze_frame(file: UploadFile = File(...)):
    """API phân tích 1 frame ảnh độc lập"""
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
                "loiter_duration_seconds": round(p.loiter_duration, 2),
                "face_detected": p.face_detected,
                "face_score": p.face_info.score if p.face_info else None,
                "alert_loitering_sent": p.alert_loitering_sent,
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
    full_name: str = Form(...),
    front_image: UploadFile = File(...)
):
    """
    API nhận diện & trích xuất Vector Embedding 512 chiều từ ảnh khuôn mặt (Front)
    và lưu trữ vào MinIO cho Dataset quản trị.
    """
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

    # Phát hiện khuôn mặt
    faces = default_pipeline.face_detector.detect_in_image(img)
    if not faces:
        # Nếu không tìm thấy mặt với ngưỡng cao, thử trích xuất trực tiếp trên toàn bộ ảnh chân dung
        face_crop = img
    else:
        # Cắt lấy vùng khuôn mặt có score cao nhất
        faces.sort(key=lambda f: f.score, reverse=True)
        fx1, fy1, fx2, fy2 = faces[0].bbox.to_int_xyxy()
        ih, iw, _ = img.shape
        fx1, fy1 = max(0, fx1), max(0, fy1)
        fx2, fy2 = min(iw, fx2), min(ih, fy2)
        face_crop = img[fy1:fy2, fx1:fx2] if (fx2 > fx1 and fy2 > fy1) else img

    # Trích xuất vector 512 chiều
    vector_512 = face_embedder.extract_embedding(face_crop)

    # Upload ảnh JPEG đã được chuẩn hóa lên MinIO
    uploaded_url = default_pipeline.storage_service.upload_face_image(jpeg_bytes, code=code, angle="front")
    image_front_url = uploaded_url or f"/storage/faces/{code}/{code}_front.jpg"

    return {
        "success": True,
        "code": code,
        "full_name": full_name,
        "image_front_url": image_front_url,
        "embedding_front": vector_512
    }

# Quản lý các Stream Worker đang chạy
from .pipeline.stream_worker import CameraStreamWorker
active_workers: Dict[str, CameraStreamWorker] = {}

class StreamStartRequest(BaseModel):
    camera_code: str = "CAM-001"
    rtsp_url: str = "rtsp://localhost:8554/cam01"

@app.post("/api/v1/stream/start")
async def start_camera_stream(req: StreamStartRequest):
    """Bắt đầu worker đọc luồng RTSP từ MediaMTX và phân tích AI liên tục"""
    if req.camera_code in active_workers and active_workers[req.camera_code].is_running:
        return {"status": "ALREADY_RUNNING", "camera_code": req.camera_code}

    worker = CameraStreamWorker(camera_code=req.camera_code, rtsp_url=req.rtsp_url)
    worker.start()
    active_workers[req.camera_code] = worker
    return {"status": "STARTED", "camera_code": req.camera_code, "rtsp_url": req.rtsp_url}

@app.post("/api/v1/stream/stop")
async def stop_camera_stream(camera_code: str = Query("CAM-001")):
    """Dừng worker phân tích AI luồng camera"""
    if camera_code not in active_workers:
        raise HTTPException(status_code=404, detail="Camera worker không tồn tại.")
    
    active_workers[camera_code].stop()
    del active_workers[camera_code]
    return {"status": "STOPPED", "camera_code": camera_code}

@app.get("/api/v1/stream/status")
async def get_stream_status(camera_code: str = Query("CAM-001")):
    """Xem trạng thái FPS và đối tượng đang track trên luồng"""
    if camera_code not in active_workers:
        return {"status": "STOPPED", "camera_code": camera_code, "is_running": False}
    
    return active_workers[camera_code].get_status()


