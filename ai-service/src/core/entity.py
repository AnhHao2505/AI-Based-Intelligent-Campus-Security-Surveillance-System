from dataclasses import dataclass, field
from typing import List, Optional, Tuple, Dict, Any
import time
import uuid

@dataclass
class Point:
    x: float
    y: float

    def to_tuple(self) -> Tuple[float, float]:
        return (self.x, self.y)

@dataclass
class BoundingBox:
    x1: float
    y1: float
    x2: float
    y2: float
    confidence: float = 1.0

    @property
    def width(self) -> float:
        return max(0.0, self.x2 - self.x1)

    @property
    def height(self) -> float:
        return max(0.0, self.y2 - self.y1)

    @property
    def center(self) -> Point:
        return Point((self.x1 + self.x2) / 2.0, (self.y1 + self.y2) / 2.0)

    @property
    def bottom_center(self) -> Point:
        """Tâm đáy của Bounding Box - đại diện vị trí chân đứng trên mặt sàn"""
        return Point((self.x1 + self.x2) / 2.0, self.y2)

    def to_int_xyxy(self) -> Tuple[int, int, int, int]:
        return (int(self.x1), int(self.y1), int(self.x2), int(self.y2))

@dataclass
class FaceDetectionResult:
    bbox: BoundingBox
    score: float
    landmarks: Optional[List[Tuple[float, float]]] = None
    embedding: Optional[List[float]] = None
    matched_code: Optional[str] = None
    matched_name: Optional[str] = None
    is_authorized: bool = False

@dataclass
class TrackedPerson:
    track_id: int
    bbox: BoundingBox
    first_seen_time: float = field(default_factory=time.time)
    last_seen_time: float = field(default_factory=time.time)
    trajectory: List[Point] = field(default_factory=list)
    
    # Trạng thái trong vùng hạn chế (ROI)
    is_in_roi: bool = False
    roi_entry_time: Optional[float] = None
    loiter_duration: float = 0.0
    
    # Thông tin khuôn mặt
    face_detected: bool = False
    face_info: Optional[FaceDetectionResult] = None
    
    # Cờ trạng thái đã bắn thông báo (tránh spam cảnh báo liên tục)
    alert_loitering_sent: bool = False
    alert_unauthorized_sent: bool = False

    def update_position(self, new_bbox: BoundingBox, current_time: float):
        self.bbox = new_bbox
        self.last_seen_time = current_time
        bc = new_bbox.bottom_center
        self.trajectory.append(bc)
        # Giữ tối đa 50 điểm lịch sử di chuyển
        if len(self.trajectory) > 50:
            self.trajectory.pop(0)

@dataclass
class SecurityAlertEvent:
    event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    camera_code: str = "CAM-001"
    event_type: str = "LOITERING_UNIDENTIFIED_PERSON" # LOITERING_UNIDENTIFIED_PERSON | UNAUTHORIZED_ACCESS | STRANGER_DETECTED
    track_id: int = 0
    duration_seconds: float = 0.0
    confidence: float = 1.0
    image_url: Optional[str] = None
    detected_at: str = field(default_factory=lambda: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    details: str = ""
    location: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_id": self.event_id,
            "camera_code": self.camera_code,
            "event_type": self.event_type,
            "track_id": self.track_id,
            "duration_seconds": round(self.duration_seconds, 2),
            "confidence": round(self.confidence, 3),
            "image_url": self.image_url,
            "detected_at": self.detected_at,
            "details": self.details,
            "location": self.location or {}
        }
