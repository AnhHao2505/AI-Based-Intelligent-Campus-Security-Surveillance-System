import os
from pydantic_settings import BaseSettings
from pydantic import Field

class Settings(BaseSettings):
    APP_NAME: str = "Campus Security AI Service"
    DEBUG: bool = True
    
    # Kafka Configuration
    KAFKA_BOOTSTRAP_SERVERS: str = Field(default="localhost:9092", alias="KAFKA_BOOTSTRAP_SERVERS")
    KAFKA_TOPIC_ALERTS: str = "security-incidents"
    KAFKA_TOPIC_HEALTH: str = "campus.camera.health"
    KAFKA_ENABLED: bool = True
    
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
        env_file = (".env", "../.env")
        extra = "ignore"

settings = Settings()
