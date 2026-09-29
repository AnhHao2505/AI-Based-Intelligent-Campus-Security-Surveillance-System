from dataclasses import dataclass, field
from typing import List, Tuple, Optional, Dict, Any
import time
import uuid

@dataclass
class Point:
    x: float
    y: float

@dataclass
class BoundingBox:
    x1: float
    y1: float
    x2: float
    y2: float
    confidence: float = 1.0

    @property
    def width(self) -> float:
        return self.x2 - self.x1

    @property
    def height(self) -> float:
        return self.y2 - self.y1

    @property
    def center(self) -> Point:
        return Point((self.x1 + self.x2) / 2.0, (self.y1 + self.y2) / 2.0)

    @property
    def bottom_center(self) -> Point:
        """Điểm chân người - dùng chuẩn xác nhất khi tính toán nằm trong Polygon/đường ranh ROI"""
        return Point((self.x1 + self.x2) / 2.0, self.y2)

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
    prev_bottom_center: Optional[Point] = None
    
    # Trạng thái trong vùng hạn chế (ROI)
    is_in_roi: bool = False
    
    # Thông tin khuôn mặt
    face_detected: bool = False
    face_info: Optional[FaceDetectionResult] = None
    
    # Cờ trạng thái đã bắn thông báo
    alert_unauthorized_sent: bool = False
    alert_after_hours_sent: bool = False

    def update_position(self, new_bbox: BoundingBox, current_time: float):
        self.prev_bottom_center = self.bbox.bottom_center
        self.bbox = new_bbox
        self.last_seen_time = current_time

@dataclass
class SecurityAlertEvent:
    event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    camera_code: str = "CAM-001"
    event_type: str = "UNKNOWN"  # AFTER_HOURS_PRESENCE, UNAUTHORIZED_ACCESS, UNKNOWN_PERSON
    track_id: int = -1
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

@dataclass
class AccessCrossEvent:
    """Sự kiện qua đường ranh ra/vào (chỉ ghi log, không sinh sự cố an ninh)"""
    event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    camera_code: str = "CAM-001"
    line_label: str = ""
    direction: str = "ENTER"  # ENTER hoặc EXIT
    track_id: int = -1
    crossed_at: str = field(default_factory=lambda: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_id": self.event_id,
            "camera_code": self.camera_code,
            "line_label": self.line_label,
            "direction": self.direction,
            "track_id": self.track_id,
            "crossed_at": self.crossed_at
        }

@dataclass
class RoiPolygonConfig:
    label: str = ""
    alert_rules: List[str] = field(default_factory=lambda: ["ENTRY_EXIT_TRACKING"])
    vertices: List[Point] = field(default_factory=list)
    target_area_id: Optional[str] = None

    def to_pixel_points(self, width: int, height: int) -> List[Point]:
        if width <= 0 or height <= 0:
            return self.vertices

        scaled: List[Point] = []
        for p in self.vertices:
            if 0.0 <= p.x <= 1.0 and 0.0 <= p.y <= 1.0:
                px = max(0.0, min(float(width), p.x * width))
                py = max(0.0, min(float(height), p.y * height))
                scaled.append(Point(px, py))
            else:
                scaled.append(Point(p.x, p.y))
        return scaled

@dataclass
class EntryLineConfig:
    label: str = ""
    point_a: Point = field(default_factory=lambda: Point(0.0, 0.0))
    point_b: Point = field(default_factory=lambda: Point(0.0, 0.0))
    direction: str = "AB_IS_IN"  # AB_IS_IN hoặc AB_IS_OUT

    def to_pixel_points(self, width: int, height: int) -> Tuple[Point, Point]:
        if width <= 0 or height <= 0:
            return self.point_a, self.point_b
        pa_x = self.point_a.x * width if 0.0 <= self.point_a.x <= 1.0 else self.point_a.x
        pa_y = self.point_a.y * height if 0.0 <= self.point_a.y <= 1.0 else self.point_a.y
        pb_x = self.point_b.x * width if 0.0 <= self.point_b.x <= 1.0 else self.point_b.x
        pb_y = self.point_b.y * height if 0.0 <= self.point_b.y <= 1.0 else self.point_b.y
        return Point(pa_x, pa_y), Point(pb_x, pb_y)
