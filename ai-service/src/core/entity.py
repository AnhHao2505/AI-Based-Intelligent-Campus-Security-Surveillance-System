from dataclasses import dataclass, field
from typing import List, Tuple, Optional, Dict, Any
from datetime import datetime, timezone
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

    def to_int_xyxy(self) -> Tuple[int, int, int, int]:
        return int(round(self.x1)), int(round(self.y1)), int(round(self.x2)), int(round(self.y2))

    def to_xyxy(self) -> Tuple[float, float, float, float]:
        return self.x1, self.y1, self.x2, self.y2

    @property
    def area(self) -> float:
        """Diện tích hộp giới hạn (pixel)"""
        return max(0.0, self.x2 - self.x1) * max(0.0, self.y2 - self.y1)

    def iou(self, other: "BoundingBox") -> float:
        """Chỉ số giao thoa trên hợp nhất (Intersection over Union)"""
        ix1 = max(self.x1, other.x1)
        iy1 = max(self.y1, other.y1)
        ix2 = min(self.x2, other.x2)
        iy2 = min(self.y2, other.y2)
        iw = max(0.0, ix2 - ix1)
        ih = max(0.0, iy2 - iy1)
        inter = iw * ih
        if inter <= 0.0:
            return 0.0
        union = self.area + other.area - inter
        return (inter / union) if union > 0.0 else 0.0

    def intersection_ratio(self, other: "BoundingBox") -> float:
        """Tỷ lệ giao thoa trên hộp nhỏ hơn (Intersection over Minimum)"""
        ix1 = max(self.x1, other.x1)
        iy1 = max(self.y1, other.y1)
        ix2 = min(self.x2, other.x2)
        iy2 = min(self.y2, other.y2)
        iw = max(0.0, ix2 - ix1)
        ih = max(0.0, iy2 - iy1)
        inter = iw * ih
        min_a = min(self.area, other.area)
        return (inter / min_a) if min_a > 0.0 else 0.0

@dataclass
class FaceDetectionResult:
    bbox: BoundingBox
    score: float
    landmarks: Optional[List[Tuple[float, float]]] = None
    embedding: Optional[List[float]] = None
    matched_code: Optional[str] = None
    matched_name: Optional[str] = None
    is_authorized: bool = False
    is_active: bool = True
    access_reason: Optional[str] = None
    # Nguồn cấp quyền (AccessSource) và mã lý do từ chối khách (GuestEntryDenyReason) backend trả; None = không rõ
    access_source: Optional[str] = None
    access_deny_reason: Optional[str] = None
    area_name: Optional[str] = None
    role: Optional[str] = "NORMAL_USER"
    access_checked: bool = False
    access_source: Optional[str] = None
    is_inherited: bool = False
    # Thời điểm khuôn mặt được khớp ra danh tính này (cùng đồng hồ với current_time của pipeline).
    # Engine dùng để chờ quyết định quyền tính từ lúc nhận ra mặt, không chỉ từ lúc vào ROI.
    matched_at: Optional[float] = None
    # Thời điểm khuôn mặt này qua bộ lọc chất lượng (đủ lớn, không mờ) và được gắn vào track.
    # Engine dùng để biết có nên giữ thêm (hold) trước khi chốt UNKNOWN hay không.
    seen_at: Optional[float] = None
    # Mốc thời gian hết hạn hiệu lực quyền (Epoch seconds tính bằng float) từ Backend trả về
    expires_at: Optional[float] = None
    # Mốc thời gian bắt đầu có hiệu lực quyền (Epoch seconds tính bằng float) từ Backend trả về
    starts_at: Optional[float] = None

@dataclass
class TrackedPerson:
    track_id: int
    bbox: BoundingBox
    first_seen_time: float = field(default_factory=time.time)
    last_seen_time: float = field(default_factory=time.time)
    yolo_last_seen_time: Optional[float] = None
    
    # Trạng thái trong vùng hạn chế (ROI)
    is_in_roi: bool = False
    was_in_roi: bool = False
    roi_enter_time: Optional[float] = None
    entered_roi_label: Optional[str] = None
    roi_entry_logged: bool = False
    
    # Thông tin khuôn mặt
    face_detected: bool = False
    face_info: Optional[FaceDetectionResult] = None
    expires_at: Optional[float] = None
    starts_at: Optional[float] = None
    # Backend vừa trả kết quả cho lượt hỏi lại do chạm mốc starts_at / expires_at: engine xét lại trạng thái hiển thị
    window_recheck_result_ready: bool = False
    last_face_match_time: float = 0.0
    # Lần gần nhất bắt đầu quét lại mặt (có khớp hay không): giãn cách quét lại tính theo mốc này
    last_reverify_attempt_time: float = 0.0
    # Số lần quét lại danh tính kế thừa ra điểm rõ là người khác (< FACE_REVOKE_MAX_SCORE)
    inherited_mismatch_count: int = 0
    
    # Cờ trạng thái đã bắn thông báo
    alert_unauthorized_sent: bool = False
    alert_unknown_sent: bool = False
    alert_after_hours_sent: bool = False
    is_inherited_identity: bool = False
    # Thời điểm đã chốt cảnh báo KHÔNG danh tính (UNKNOWN_PERSON / PERSON_INTRUSION người lạ) cho người này.
    # Trong unknown_alert_cooldown kể từ mốc này, danh tính nhận ra muộn không sinh thêm cảnh báo thứ hai.
    unknown_verdict_time: Optional[float] = None
    # Mã phiên của cảnh báo không danh tính đã chốt (gửi kèm cảnh báo, Backend lưu vào ai_session_id của sự cố).
    # Nhận ra mặt muộn -> gửi IDENTITY_UPDATE cùng mã này để Backend cập nhật / tự đóng đúng sự cố cũ, đúng 1 lần.
    unknown_verdict_session_id: Optional[str] = None
    identity_update_sent: bool = False
    # Đã ghi ENTRY "UNKNOWN" cho lượt vào ROI này: nhận ra mặt sau đó thì ghi bổ sung ENTRY mang mã người thật
    entry_logged_as_unknown: bool = False
    
    # Vòng đời trạng thái an ninh (Sticky Security State)
    has_entered_roi: bool = False
    roi_first_entered_time: Optional[float] = None
    security_display_state: str = "NEUTRAL"  # NEUTRAL, VERIFYING, AUTHORIZED, UNAUTHORIZED, UNKNOWN, INTRUSION

    # Bộ đếm hiệu năng và độ trễ nhận diện (Task 1)
    first_matched_time: Optional[float] = None
    access_checked_time: Optional[float] = None

    def __post_init__(self):
        if self.yolo_last_seen_time is None:
            self.yolo_last_seen_time = self.first_seen_time

    def update_position(self, new_bbox: BoundingBox, current_time: float):
        self.bbox = new_bbox
        self.last_seen_time = current_time
        self.yolo_last_seen_time = current_time

@dataclass
class MotionResult:
    has_motion: bool = False
    has_motion_full: bool = False
    has_motion_roi: bool = False
    motion_area: int = 0
    motion_boxes: List[BoundingBox] = field(default_factory=list)
    is_lighting_spike: bool = False
    is_tampering: bool = False
    details: Optional[str] = None

@dataclass
class SecurityAlertEvent:
    event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    camera_code: str = "CAM-001"
    event_type: str = "UNKNOWN_PERSON"  # UNKNOWN_PERSON, AUTHORIZED_PERSON, UNAUTHORIZED_PERSON, PERSON_INTRUSION, SUSPICIOUS_MOVING_OBJECT, IDENTITY_UPDATE
    track_id: int = -1
    duration_seconds: float = 0.0
    confidence: float = 1.0
    image_url: Optional[str] = None
    detected_at: str = field(default_factory=lambda: datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z")
    details: str = ""
    location: Optional[Dict[str, Any]] = None
    # Cảnh báo không danh tính: mã phiên để Backend ghép với IDENTITY_UPDATE gửi sau
    roi_session_id: Optional[str] = None
    # Chỉ dùng cho event_type = IDENTITY_UPDATE: AUTHORIZED | UNAUTHORIZED, cùng mã và tên người vừa nhận ra
    identity_outcome: Optional[str] = None
    user_code: Optional[str] = None
    user_name: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        data = {
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
        for key in ("roi_session_id", "identity_outcome", "user_code", "user_name"):
            value = getattr(self, key)
            if value is not None:
                data[key] = value
        return data

@dataclass
class RoiPolygonConfig:
    label: str = ""
    alert_rules: Optional[List[str]] = None
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

