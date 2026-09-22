import time
from typing import Dict, List, Optional, Tuple, Any, Union
from .entity import TrackedPerson, BoundingBox, Point, SecurityAlertEvent, FaceDetectionResult, RoiPolygonConfig
from ..utils.geometry import is_point_in_polygon, scale_polygon_to_frame

class LoiteringEngine:
    """
    Engine phân tích hành vi lảng vảng (Loitering) và xâm nhập khu vực hạn chế (Restricted Area ROI).
    Kết hợp giữa Human Track ID và Face Detection:
    - Nếu có nhận diện khuôn mặt: Kiểm tra quyền hợp lệ.
    - Nếu KHÔNG phát hiện được khuôn mặt (quay lưng, che mặt...): Vẫn duy trì theo dõi bằng Human Track ID
      và kích hoạt báo động nếu thời gian lưu trú trong ROI vượt quá ngưỡng quy định.
    - Hỗ trợ đa polygon ROI và tự động chuẩn hóa/scale pixel theo khung hình.
    """
    def __init__(self, loitering_threshold_seconds: int = 10, max_inactive_seconds: float = 3.0):
        self.loitering_threshold_seconds = loitering_threshold_seconds
        self.max_inactive_seconds = max_inactive_seconds
        self.active_tracks: Dict[int, TrackedPerson] = {}

    def process_frame(
        self,
        detected_tracks: List[Tuple[int, BoundingBox]],
        roi_polygon: Optional[List[Point]] = None,
        camera_code: str = "CAM-001",
        current_time: Optional[float] = None,
        roi_polygons: Optional[List[Any]] = None,
        frame_size: Optional[Tuple[int, int]] = None
    ) -> Tuple[List[TrackedPerson], List[SecurityAlertEvent]]:
        """
        Xử lý trạng thái theo dõi và phát hiện vi phạm cho 1 frame.
        Trả về danh sách TrackedPerson hiện tại và danh sách các sự kiện cảnh báo mới phát sinh (nếu có).
        """
        if current_time is None:
            current_time = time.time()

        generated_alerts: List[SecurityAlertEvent] = []
        current_frame_track_ids = set()

        # Chuẩn bị danh sách các đa giác pixel đã được scale theo kích thước frame
        active_pixel_polys: List[Tuple[List[Point], List[str], str]] = []

        fw = frame_size[0] if frame_size else 0
        fh = frame_size[1] if frame_size else 0

        # Nếu có danh sách đa polygon
        if roi_polygons:
            for poly in roi_polygons:
                if isinstance(poly, RoiPolygonConfig):
                    pts = poly.to_pixel_points(fw, fh) if (fw > 0 and fh > 0) else poly.vertices
                    rules = poly.alert_rules or ["ENTRY_EXIT_TRACKING"]
                    lbl = poly.label or ""
                elif isinstance(poly, dict):
                    raw_pts = poly.get("vertices", [])
                    pts = scale_polygon_to_frame(raw_pts, fw, fh) if (fw > 0 and fh > 0) else [Point(p["x"], p["y"]) for p in raw_pts if "x" in p and "y" in p]
                    rules = poly.get("alert_rules", ["ENTRY_EXIT_TRACKING"])
                    lbl = poly.get("label", "")
                elif isinstance(poly, (list, tuple)):
                    pts = scale_polygon_to_frame(poly, fw, fh) if (fw > 0 and fh > 0) else poly
                    rules = ["ENTRY_EXIT_TRACKING", "LOITERING"]
                    lbl = ""
                else:
                    continue

                if len(pts) >= 3:
                    active_pixel_polys.append((pts, rules, lbl))

        # Nếu truyền roi_polygon đơn lẻ (tương thích ngược)
        elif roi_polygon and len(roi_polygon) >= 3:
            pts = scale_polygon_to_frame(roi_polygon, fw, fh) if (fw > 0 and fh > 0) else roi_polygon
            active_pixel_polys.append((pts, ["ENTRY_EXIT_TRACKING", "LOITERING"], "Vùng ROI"))

        for track_id, bbox in detected_tracks:
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

            # Kiểm tra xem chân người (bottom-center) có nằm trong bất kỳ polygon nào không
            in_roi = False
            matched_rules: List[str] = []
            matched_label = ""

            if active_pixel_polys:
                bc = person.bbox.bottom_center
                for poly_pts, rules, lbl in active_pixel_polys:
                    if is_point_in_polygon(bc, poly_pts):
                        in_roi = True
                        matched_rules.extend(rules)
                        if not matched_label:
                            matched_label = lbl
            else:
                # Nếu không thiết lập ROI nào, mặc định toàn bộ frame là ROI
                in_roi = True
                matched_rules = ["ENTRY_EXIT_TRACKING", "LOITERING"]

            person.is_in_roi = in_roi

            if in_roi:
                if person.roi_entry_time is None:
                    person.roi_entry_time = current_time
                    person.loiter_duration = 0.0
                else:
                    person.loiter_duration = current_time - person.roi_entry_time
            else:
                person.roi_entry_time = None
                person.loiter_duration = 0.0

            # QUY TẮC PHÁT HIỆN CẢNH BÁO THEO 3 RULE CHUẨN: ENTRY_EXIT_TRACKING, LOITERING, CROWD_OVERCROWDING
            if person.is_in_roi:
                loc_info = {"label": matched_label} if matched_label else None

                # 1. Ghi nhận ra vào / Xâm nhập không hợp lệ (ENTRY_EXIT_TRACKING)
                if "ENTRY_EXIT_TRACKING" in matched_rules:
                    if person.face_detected and person.face_info and not person.face_info.is_authorized:
                        if not person.alert_unauthorized_sent:
                            alert = SecurityAlertEvent(
                                camera_code=camera_code,
                                event_type="UNAUTHORIZED_ACCESS",
                                track_id=person.track_id,
                                duration_seconds=person.loiter_duration,
                                confidence=person.bbox.confidence,
                                details=f"Phát hiện đối tượng không được phép {person.face_info.matched_name or 'Chưa rõ'} tại vùng {matched_label or 'giám sát'}.",
                                location=loc_info
                            )
                            generated_alerts.append(alert)
                            person.alert_unauthorized_sent = True

                # 2. Phát hiện lảng vảng (LOITERING)
                if "LOITERING" in matched_rules:
                    if not person.face_detected or (person.face_info and not person.face_info.is_authorized):
                        if person.loiter_duration >= self.loitering_threshold_seconds:
                            if not person.alert_loitering_sent:
                                alert = SecurityAlertEvent(
                                    camera_code=camera_code,
                                    event_type="LOITERING_UNIDENTIFIED_PERSON",
                                    track_id=person.track_id,
                                    duration_seconds=person.loiter_duration,
                                    confidence=person.bbox.confidence,
                                    details=f"Phát hiện người không rõ danh tính lảng vảng trong khu vực {matched_label or 'hạn chế'} suốt {int(person.loiter_duration)} giây mà không xác định được khuôn mặt.",
                                    location=loc_info
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
