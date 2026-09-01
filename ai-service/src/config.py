import os
from pydantic_settings import BaseSettings
from pydantic import Field

class Settings(BaseSettings):
    APP_NAME: str = "Campus Security AI Service"
    DEBUG: bool = True
    
    # Kafka Configuration
    KAFKA_BOOTSTRAP_SERVERS: str = Field(default="localhost:9092", alias="KAFKA_BOOTSTRAP_SERVERS")
    KAFKA_TOPIC_ALERTS: str = "campus.security.alerts"
    KAFKA_TOPIC_HEALTH: str = "campus.camera.health"
    KAFKA_ENABLED: bool = True
    
    # MinIO Storage Configuration
    MINIO_ENDPOINT: str = Field(default="localhost:9000", alias="MINIO_ENDPOINT")
    MINIO_ACCESS_KEY: str = Field(default="minioadmin", alias="MINIO_ROOT_USER")
    MINIO_SECRET_KEY: str = Field(default="minioadmin", alias="MINIO_ROOT_PASSWORD")
    MINIO_BUCKET_NAME: str = "security-evidence"
    MINIO_SECURE: bool = False
    MINIO_ENABLED: bool = True
    
    # Backend Integration
    BACKEND_URL: str = Field(default="http://localhost:8080", alias="BACKEND_URL")
    
    # AI Models & Default Thresholds
    MODEL_YOLO_PATH: str = "yolov8n.pt"
    MODEL_YUNET_PATH: str = os.path.join(os.path.dirname(os.path.dirname(__file__)), "models", "face_detection_yunet.onnx")
    
    DEFAULT_DETECTION_CONFIDENCE: float = 0.50
    DEFAULT_FACE_CONFIDENCE: float = 0.60
    DEFAULT_LOITERING_THRESHOLD_SECONDS: int = 10
    DEFAULT_INFERENCE_FPS: int = 10
    
    class Config:
        env_file = ".env"
        extra = "ignore"

settings = Settings()
