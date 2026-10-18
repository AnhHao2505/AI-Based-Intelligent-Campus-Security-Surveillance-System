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

    # Kafka Configuration
    KAFKA_BOOTSTRAP_SERVERS: str = Field(default="localhost:9092", validation_alias="KAFKA_BOOTSTRAP_SERVERS")
    KAFKA_TOPIC_ALERTS: str = "security-incidents"
    KAFKA_TOPIC_HEALTH: str = "campus.camera.health"
    KAFKA_ENABLED: bool = True
    
    # MinIO Storage Configuration (Bắt buộc đọc credentials từ biến môi trường, không để default trong source)
    MINIO_ENDPOINT: str = Field(default="localhost:9000", validation_alias="MINIO_ENDPOINT")
    MINIO_ACCESS_KEY: str = Field(..., validation_alias=AliasChoices("MINIO_ROOT_USER", "MINIO_ACCESS_KEY"))
    MINIO_SECRET_KEY: str = Field(..., min_length=8, validation_alias=AliasChoices("MINIO_ROOT_PASSWORD", "MINIO_SECRET_KEY"))
    MINIO_BUCKET_FACES: str = "face-profiles"
    MINIO_BUCKET_EVIDENCE: str = "security-evidence"
    MINIO_SECURE: bool = False
    MINIO_ENABLED: bool = True
    
    # AI Models Paths (Đường dẫn nhất quán dựa trên thư mục gốc ai-service)
    MODEL_YOLO_PATH: str = Field(
        default=os.path.join(AI_SERVICE_DIR, "models", "yolo11n.pt") if os.path.exists(os.path.join(AI_SERVICE_DIR, "models", "yolo11n.pt")) else "yolo11n.pt",
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
    
    # AI Thresholds & Business Rules
    DEFAULT_DETECTION_CONFIDENCE: float = 0.50
    DEFAULT_FACE_CONFIDENCE: float = 0.60
    DEFAULT_FACE_NMS_THRESHOLD: float = 0.30
    FACE_MATCH_THRESHOLD: float = 0.75
    UPPER_BODY_CROP_RATIO: float = 0.60
    DEFAULT_AFTER_HOUR_START: str = "22:00"
    DEFAULT_AFTER_HOUR_END: str = "06:00"
    DEFAULT_TRACK_MAX_INACTIVE_SECONDS: float = 10.0
    DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS: float = 30.0
    TIMEZONE: str = "Asia/Ho_Chi_Minh"
    
    # Backend Access Decision Service Integration
    BACKEND_API_URL: str = Field(default="http://localhost:8080", validation_alias="BACKEND_API_URL")
    ACCESS_DECISION_CACHE_TTL: float = 30.0
    # Khoá dùng chung với Backend (cùng giá trị INTERNAL_SERVICE_KEY của Backend). Bắt buộc, không có mặc định:
    # thiếu hoặc ngắn hơn 32 ký tự thì service không khởi động.
    INTERNAL_SERVICE_KEY: str = Field(..., min_length=32, validation_alias="INTERNAL_SERVICE_KEY")
    
    class Config:
        env_file = (".env", "../.env")
        extra = "ignore"
        # Không in giá trị đầu vào (khoá, mật khẩu) trong thông báo lỗi validate
        hide_input_in_errors = True

settings = Settings()
