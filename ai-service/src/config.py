import os
from typing import List, Optional
from pydantic_settings import BaseSettings
from pydantic import Field, AliasChoices

AI_SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_DIR = os.path.dirname(AI_SERVICE_DIR)

# Header mang khoá dùng chung khi gọi endpoint nội bộ của Backend (check-entry, /api/internal/**)
INTERNAL_SERVICE_KEY_HEADER = "X-Service-Key"

class Settings(BaseSettings):
    APP_NAME: str = "Campus Security AI Service"
    DEBUG: bool = True
    
    # PostgreSQL Configuration
    DB_HOST: str = Field(default="localhost", validation_alias="DB_HOST")
    DB_PORT: int = Field(default=5432, validation_alias="DB_PORT")
    POSTGRES_USER: str = Field(default="sep", validation_alias="POSTGRES_USER")
    POSTGRES_PASSWORD: str = Field(..., validation_alias="POSTGRES_PASSWORD")
    POSTGRES_DB: str = Field(default="campus_security", validation_alias="POSTGRES_DB")

    # MQTT Configuration (Edge-Cloud Control Plane & Real-time Telemetry)
    MQTT_BROKER_HOST: str = Field(default="localhost", validation_alias="MQTT_BROKER_HOST")
    MQTT_BROKER_PORT: int = Field(default=1883, validation_alias="MQTT_BROKER_PORT")
    MQTT_CLIENT_ID: str = Field(default="campus-security-ai-service", validation_alias="MQTT_CLIENT_ID")
    MQTT_TOPIC_ALERTS: str = "campus/alerts/incidents"
    MQTT_TOPIC_RECORDINGS: str = "campus/recordings/completed"
    MQTT_TOPIC_HEALTH: str = "campus/cameras/{camera_id}/health"
    MQTT_ENABLED: bool = Field(default=True, validation_alias="MQTT_ENABLED")
    MQTT_USERNAME: str = Field(default="icss-ai", validation_alias="MQTT_AI_USERNAME")
    MQTT_PASSWORD: str = Field(default="", validation_alias="MQTT_AI_PASSWORD")
    # Outbox SQLite: cảnh báo được lưu bền trước khi gửi, gửi lại tới khi broker xác nhận (PUBACK)
    MQTT_OUTBOX_PATH: str = Field(default="data/mqtt_outbox.sqlite3", validation_alias="MQTT_OUTBOX_PATH")
    MQTT_OUTBOX_FLUSH_SECONDS: float = Field(default=5.0, validation_alias="MQTT_OUTBOX_FLUSH_SECONDS")
    MQTT_OUTBOX_INFLIGHT_TIMEOUT_SECONDS: float = Field(default=30.0, validation_alias="MQTT_OUTBOX_INFLIGHT_TIMEOUT_SECONDS")
    MQTT_OUTBOX_MAX_ROWS: int = Field(default=10000, validation_alias="MQTT_OUTBOX_MAX_ROWS")
    MQTT_OUTBOX_MAX_AGE_HOURS: float = Field(default=24.0, validation_alias="MQTT_OUTBOX_MAX_AGE_HOURS")

    # MediaMTX Edge & Cloud Relay Configuration
    MEDIAMTX_API_URL: str = Field(default="http://localhost:9997", validation_alias="MEDIAMTX_API_URL")
    MEDIAMTX_RTSP_URL: str = Field(default="rtsp://localhost:8554", validation_alias="MEDIAMTX_RTSP_URL")
    CLOUD_MEDIAMTX_RTSP_URL: str = Field(default="", validation_alias="CLOUD_MEDIAMTX_RTSP_URL")
    
    # MinIO Storage Configuration (Bắt buộc đọc credentials từ biến môi trường, không để default trong source)
    MINIO_ENDPOINT: str = Field(default="localhost:9000", validation_alias="MINIO_ENDPOINT")
    MINIO_ACCESS_KEY: str = Field(..., validation_alias=AliasChoices("MINIO_ROOT_USER", "MINIO_ACCESS_KEY"))
    MINIO_SECRET_KEY: str = Field(..., min_length=8, validation_alias=AliasChoices("MINIO_ROOT_PASSWORD", "MINIO_SECRET_KEY"))
    MINIO_BUCKET_FACES: str = "face-profiles"
    MINIO_BUCKET_EVIDENCE: str = "security-evidence"
    MINIO_BUCKET_RECORDINGS: str = "camera-recordings"
    MINIO_BUCKET_SNAPSHOTS: str = "camera-snapshots"
    MINIO_SECURE: bool = Field(default=False, validation_alias="MINIO_SECURE")
    MINIO_ENABLED: bool = Field(default=True, validation_alias="MINIO_ENABLED")
    
    # AI Models Paths (Đường dẫn nhất quán dựa trên thư mục gốc ai-service)
    MODEL_YOLO_PATH: str = Field(
        default=os.path.join(AI_SERVICE_DIR, "models", "yolo26n.pt") if os.path.exists(os.path.join(AI_SERVICE_DIR, "models", "yolo26n.pt")) else "yolo26n.pt",
        validation_alias="MODEL_YOLO_PATH"
    )
    MODEL_YUNET_PATH: str = Field(
        default=os.path.join(AI_SERVICE_DIR, "models", "face_detection_yunet.onnx"),
        validation_alias="MODEL_YUNET_PATH"
    )
    MODEL_ARCFACE_PATH: str = Field(
        default=os.path.join(AI_SERVICE_DIR, "models", "w600k_r50.onnx"),
        validation_alias="MODEL_ARCFACE_PATH"
    )
    AI_DEVICE: str = Field(default="auto", validation_alias="AI_DEVICE")
    
    # AI Thresholds & Business Rules
    DEFAULT_DETECTION_CONFIDENCE: float = 0.35
    DRAW_ONLY_IN_ROI: bool = Field(default=False, validation_alias="DRAW_ONLY_IN_ROI")
    DEFAULT_FACE_CONFIDENCE: float = 0.60
    DEFAULT_FACE_NMS_THRESHOLD: float = 0.30
    FACE_MATCH_THRESHOLD: float = Field(default=0.65, validation_alias="FACE_MATCH_THRESHOLD")
    FACE_REVERIFY_SCORE_THRESHOLD: float = Field(default=0.82, validation_alias="FACE_REVERIFY_SCORE_THRESHOLD")
    FACE_REVERIFY_MIN_INTERVAL_SECONDS: float = Field(default=3.0, validation_alias="FACE_REVERIFY_MIN_INTERVAL_SECONDS")
    FACE_SCAN_THROTTLING_ENABLED: bool = Field(default=True, validation_alias="FACE_SCAN_THROTTLING_ENABLED")
    FACE_SCAN_INITIAL_INTERVAL: float = Field(default=0.4, validation_alias="FACE_SCAN_INITIAL_INTERVAL")
    FACE_SCAN_INITIAL_WINDOW_SECONDS: float = Field(default=2.0, validation_alias="FACE_SCAN_INITIAL_WINDOW_SECONDS")
    FACE_SCAN_BACKOFF_INTERVAL: float = Field(default=1.0, validation_alias="FACE_SCAN_BACKOFF_INTERVAL")
    FACE_MIN_CROP_SIZE: int = Field(default=30, validation_alias="FACE_MIN_CROP_SIZE")
    FACE_MIN_LAPLACIAN_VARIANCE: float = Field(default=50.0, validation_alias="FACE_MIN_LAPLACIAN_VARIANCE")
    ANALYSIS_MAX_FPS: float = Field(default=10.0, validation_alias="ANALYSIS_MAX_FPS")
    UPPER_BODY_CROP_RATIO: float = 0.60
    DEFAULT_AFTER_HOUR_START: str = "22:00"
    DEFAULT_AFTER_HOUR_END: str = "06:00"
    NIGHT_ALLOWED_ACCESS_SOURCES: List[str] = Field(
        default=["ASSIGNED_PERSONNEL", "ACCESS_REQUEST", "INCIDENT_RESPONSE", "OPEN_EVENT", "GUEST_VISIT"],
        validation_alias="NIGHT_ALLOWED_ACCESS_SOURCES"
    )
    DEFAULT_TRACK_MAX_INACTIVE_SECONDS: float = 2.0
    DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS: float = 30.0
    ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS: float = Field(default=1.8, validation_alias="ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS")
    CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS: float = 3.0
    MAX_MOTION_TRACK_HOLD_SECONDS: float = 3.0
    MOTION_GATING_HEARTBEAT_SECONDS: float = Field(default=4.0, validation_alias="MOTION_GATING_HEARTBEAT_SECONDS")
    MIN_MOTION_AREA: int = Field(default=3000, validation_alias="MIN_MOTION_AREA")
    TIMEZONE: str = "Asia/Ho_Chi_Minh"
    AI_LOG_LEVEL: str = Field(default="INFO", validation_alias="AI_LOG_LEVEL")
    AI_LOG_TERMINAL_COLORS: bool = Field(default=True, validation_alias="AI_LOG_TERMINAL_COLORS")
    AI_LOG_VERBOSE_FRAMES: bool = Field(default=False, validation_alias="AI_LOG_VERBOSE_FRAMES")

    # RTSP / FFmpeg Low-Latency Configuration (P1-06)
    RTSP_TRANSPORT: str = Field(default="tcp", validation_alias="RTSP_TRANSPORT")
    FFMPEG_LOW_DELAY: bool = Field(default=True, validation_alias="FFMPEG_LOW_DELAY")
    FFMPEG_NOBUFFER: bool = Field(default=True, validation_alias="FFMPEG_NOBUFFER")
    FFMPEG_STIMEOUT_US: int = Field(default=5000000, validation_alias="FFMPEG_STIMEOUT_US")

    # CPU Threading Configuration (P1-06)
    AI_NUM_THREADS: Optional[int] = Field(default=None, validation_alias="AI_NUM_THREADS")

    # Hardware Acceleration / FP16 Inference Configuration (P2-07)
    AI_HALF: bool = Field(default=False, validation_alias="AI_HALF")
    
    # Continuous Video Recording Configuration
    RECORDING_BASE_DIR: str = Field(
        default=os.path.join(REPO_DIR, "recording"),
        validation_alias="RECORDING_BASE_DIR"
    )
    RECORDING_SEGMENT_SECONDS: int = Field(default=60, validation_alias="RECORDING_SEGMENT_SECONDS")
    LOCAL_RECORDING_MAX_RETAINED_FILES: int = Field(default=30, validation_alias="LOCAL_RECORDING_MAX_RETAINED_FILES")
    LOCAL_RECORDING_MAX_MB: int = Field(default=2048, validation_alias="LOCAL_RECORDING_MAX_MB")
    LOCAL_EVIDENCE_MAX_MB: int = Field(default=5120, validation_alias="LOCAL_EVIDENCE_MAX_MB")
    EMERGENCY_DISK_FREE_MIN_MB: int = Field(default=500, validation_alias="EMERGENCY_DISK_FREE_MIN_MB")
    RETRY_UPLOAD_INTERVAL_SECONDS: float = Field(default=30.0, validation_alias="RETRY_UPLOAD_INTERVAL_SECONDS")
    RETRY_UPLOAD_MAX_BATCH_FILES: int = Field(default=5, validation_alias="RETRY_UPLOAD_MAX_BATCH_FILES")
    ALERT_IMAGE_WAIT_SECONDS: float = Field(default=0.8, validation_alias="ALERT_IMAGE_WAIT_SECONDS")
    ALERT_IMAGE_UPLOAD_TIMEOUT_SECONDS: float = Field(default=1.0, validation_alias="ALERT_IMAGE_UPLOAD_TIMEOUT_SECONDS")

    # Incident Video Evidence Recording Configuration
    EVIDENCE_BASE_DIR: str = Field(
        default=os.path.join(REPO_DIR, "evidence"),
        validation_alias="EVIDENCE_BASE_DIR"
    )

    # Backend Access Decision Service Integration
    BACKEND_API_URL: str = Field(default="http://localhost:8080", validation_alias="BACKEND_API_URL")
    ACCESS_DECISION_CACHE_TTL: float = 30.0
    ACCESS_STALE_MAX_SECONDS: float = Field(default=600.0, validation_alias="ACCESS_STALE_MAX_SECONDS")
    # Khoá dùng chung với Backend (cùng giá trị INTERNAL_SERVICE_KEY của Backend). Bắt buộc, không có mặc định:
    # thiếu hoặc ngắn hơn 32 ký tự thì service không khởi động.
    INTERNAL_SERVICE_KEY: str = Field(..., min_length=32, validation_alias="INTERNAL_SERVICE_KEY")
    AI_CORS_ALLOWED_ORIGINS: str = Field(default="http://localhost:5173,http://localhost:5174,http://localhost:3000", validation_alias="AI_CORS_ALLOWED_ORIGINS")
    
    class Config:
        env_file = (
            ".env",
            "../.env",
            os.path.join(AI_SERVICE_DIR, ".env"),
            os.path.join(os.path.dirname(AI_SERVICE_DIR), ".env"),
            os.path.join(os.path.dirname(os.path.dirname(AI_SERVICE_DIR)), ".env"),
        )
        extra = "ignore"
        # Không in giá trị đầu vào (khoá, mật khẩu) trong thông báo lỗi validate
        hide_input_in_errors = True

settings = Settings()
