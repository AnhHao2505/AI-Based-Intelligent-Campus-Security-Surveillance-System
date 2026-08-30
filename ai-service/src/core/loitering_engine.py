import time
from typing import Dict, List, Optional, Tuple
from .entity import TrackedPerson, BoundingBox, Point, SecurityAlertEvent, FaceDetectionResult
from ..utils.geometry import is_point_in_polygon

class LoiteringEngine:
    """
    Engine phân tích hành vi lảng vảng (Loitering) và xâm nhập khu vực hạn chế (Restricted Area ROI).
    Kết hợp giữa Human Track ID và Face Detection:
    - Nếu có nhận diện khuôn mặt: Kiểm tra quyền hợp lệ.
    - Nếu KHÔNG phát hiện được khuôn mặt (quay lưng, che mặt...): Vẫn duy trì theo dõi bằng Human Track ID
      và kích hoạt báo động nếu thời gian lưu trú trong ROI vượt quá ngưỡng quy định.
    """
    def __init__(self, loitering_threshold_seconds: int = 10, max_inactive_seconds: float = 3.0):
        self.loitering_threshold_seconds = loitering_threshold_seconds
        self.max_inactive_seconds = max_inactive_seconds
        self.active_tracks: Dict[int, TrackedPerson] = {}

    def process_frame(
        self,
        detected_tracks: List[Tuple[int, BoundingBox]],
        roi_polygon: Optional[List[Point]],
        camera_code: str = "CAM-001",
        current_time: Optional[float] = None
    ) -> Tuple[List[TrackedPerson], List[SecurityAlertEvent]]:
        """
        Xử lý trạng thái theo dõi và phát hiện vi phạm cho 1 frame.
        Trả về danh sách TrackedPerson hiện tại và danh sách các sự kiện cảnh báo mới phát sinh (nếu có).
        """
        if current_time is None:
            current_time = time.time()

        generated_alerts: List[SecurityAlertEvent] = []
        current_frame_track_ids = set()

        for track_id, bbox in detected_tracks:
            # Bỏ qua các track id không hợp lệ (-1 khi tracker chưa khóa đối tượng)
            if track_id < 0:
                continue

            current_frame_track_ids.add(track_id)

            if track_id not in self.active_tracks:
                person = TrackedPerson(
                    track_id=track_id,
                    bbox=bbox,
                    first_seen_time=current_time,
                    last_seen_time=current_time
                )
                self.active_tracks[track_id] = person
            else:
                person = self.active_tracks[track_id]
                person.update_position(bbox, current_time)

            # Kiểm tra xem người có đang nằm trong Polygon ROI vùng cấm hay không
            # Dùng vị trí chân đứng (bottom-center)
            in_roi = True
            if roi_polygon and len(roi_polygon) >= 3:
                in_roi = is_point_in_polygon(person.bbox.bottom_center, roi_polygon)

            person.is_in_roi = in_roi

            if in_roi:
                if person.roi_entry_time is None:
                    person.roi_entry_time = current_time
                    person.loiter_duration = 0.0
                else:
                    person.loiter_duration = current_time - person.roi_entry_time
            else:
                # Nếu đã bước ra khỏi ROI, reset lại timer vùng cấm
                person.roi_entry_time = None
                person.loiter_duration = 0.0

            # QUY TẮC PHÁT HIỆN CẢNH BÁO
            if person.is_in_roi:
                # 1. Nếu khuôn mặt được nhận diện và KHÔNG có quyền truy cập
                if person.face_detected and person.face_info and not person.face_info.is_authorized:
                    if not person.alert_unauthorized_sent:
                        alert = SecurityAlertEvent(
                            camera_code=camera_code,
                            event_type="UNAUTHORIZED_ACCESS",
                            track_id=person.track_id,
                            duration_seconds=person.loiter_duration,
                            confidence=person.bbox.confidence,
                            details=f"Phát hiện đối tượng không được phép {person.face_info.matched_name or 'Chưa rõ'} tại vùng cấm."
                        )
                        generated_alerts.append(alert)
                        person.alert_unauthorized_sent = True

                # 2. Nếu KHÔNG nhận diện được mặt (quay lưng, che mặt...) nhưng LẢNG VẢNG quá thời gian ngưỡng
                if (not person.face_detected or (person.face_info and not person.face_info.is_authorized)):
                    if person.loiter_duration >= self.loitering_threshold_seconds:
                        if not person.alert_loitering_sent:
                            alert = SecurityAlertEvent(
                                camera_code=camera_code,
                                event_type="LOITERING_UNIDENTIFIED_PERSON",
                                track_id=person.track_id,
                                duration_seconds=person.loiter_duration,
                                confidence=person.bbox.confidence,
                                details=f"Phát hiện người không rõ danh tính lảng vảng trong khu vực hạn chế suốt {int(person.loiter_duration)} giây mà không xác định được khuôn mặt."
                            )
                            generated_alerts.append(alert)
                            person.alert_loitering_sent = True

        # Dọn dẹp các track đã biến mất khỏi khung hình quá max_inactive_seconds
        inactive_ids = []
        for tid, p in self.active_tracks.items():
            if tid not in current_frame_track_ids:
                if (current_time - p.last_seen_time) > self.max_inactive_seconds:
                    inactive_ids.append(tid)

        for tid in inactive_ids:
            del self.active_tracks[tid]

        return list(self.active_tracks.values()), generated_alerts

    def associate_face(self, track_id: int, face_result: FaceDetectionResult):
        """Gán thông tin nhận diện khuôn mặt vào TrackedPerson tương ứng"""
        if track_id in self.active_tracks and face_result is not None:
            self.active_tracks[track_id].face_detected = True
            self.active_tracks[track_id].face_info = face_result
