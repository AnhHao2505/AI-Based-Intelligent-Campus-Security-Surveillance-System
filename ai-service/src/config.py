import os
from pydantic_settings import BaseSettings
from pydantic import Field, AliasChoices

AI_SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Header mang khoá dùng chung khi gọi endpoint nội bộ của Backend (check-entry, /api/internal/**)
INTERNAL_SERVICE_KEY_HEADER = "X-Service-Key"

class Settings(BaseSettings):
    APP_NAME: str = "Campus Security AI Service"
    DEBUG: bool = True
    
    # PostgreSQL Configuration
    DB_HOST: str = Field(default="localhost", validation_alias="DB_HOST")
    DB_PORT: int = Field(default=5432, validation_alias="DB_PORT")
    POSTGRES_USER: str = Field(default="sep", validation_alias="POSTGRES_USER")
    POSTGRES_PASSWORD: str = Field(default="123456", validation_alias="POSTGRES_PASSWORD")
    POSTGRES_DB: str = Field(default="campus_security", validation_alias="POSTGRES_DB")

    # MQTT Configuration (Edge-Cloud Control Plane & Real-time Telemetry)
    MQTT_BROKER_HOST: str = Field(default="localhost", validation_alias="MQTT_BROKER_HOST")
    MQTT_BROKER_PORT: int = Field(default=1883, validation_alias="MQTT_BROKER_PORT")
    MQTT_CLIENT_ID: str = Field(default="campus-security-ai-service", validation_alias="MQTT_CLIENT_ID")
    MQTT_TOPIC_ALERTS: str = "campus/alerts/incidents"
    MQTT_TOPIC_RECORDINGS: str = "campus/recordings/completed"
    MQTT_TOPIC_HEALTH: str = "campus/cameras/{camera_id}/health"
    MQTT_ENABLED: bool = Field(default=True, validation_alias="MQTT_ENABLED")

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
    DEFAULT_DETECTION_CONFIDENCE: float = 0.50
    DEFAULT_FACE_CONFIDENCE: float = 0.60
    DEFAULT_FACE_NMS_THRESHOLD: float = 0.30
    FACE_MATCH_THRESHOLD: float = 0.75
    UPPER_BODY_CROP_RATIO: float = 0.60
    DEFAULT_AFTER_HOUR_START: str = "22:00"
    DEFAULT_AFTER_HOUR_END: str = "06:00"
    DEFAULT_TRACK_MAX_INACTIVE_SECONDS: float = 2.0
    DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS: float = 30.0
    TIMEZONE: str = "Asia/Ho_Chi_Minh"
    
    # Continuous Video Recording Configuration
    RECORDING_BASE_DIR: str = Field(
        default=r"C:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\recording",
        validation_alias="RECORDING_BASE_DIR"
    )
    RECORDING_SEGMENT_SECONDS: int = Field(default=60, validation_alias="RECORDING_SEGMENT_SECONDS")

    # Incident Video Evidence Recording Configuration
    EVIDENCE_BASE_DIR: str = Field(
        default=r"C:\DATN\AI-Based-Intelligent-Campus-Security-Surveillance-System\evidence",
        validation_alias="EVIDENCE_BASE_DIR"
    )

    # Backend Access Decision Service Integration
    BACKEND_API_URL: str = Field(default="http://localhost:8080", validation_alias="BACKEND_API_URL")
    ACCESS_DECISION_CACHE_TTL: float = 30.0
    INTERNAL_SERVICE_KEY: str = Field(..., min_length=32, validation_alias="INTERNAL_SERVICE_KEY")
    
    class Config:
        env_file = (".env", "../.env")
        extra = "ignore"
        hide_input_in_errors = True

settings = Settings()
