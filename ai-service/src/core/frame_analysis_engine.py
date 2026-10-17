import time
from datetime import datetime
from zoneinfo import ZoneInfo
from typing import Dict, List, Optional, Tuple, Any
from ..config import settings
from .entity import (
    TrackedPerson, BoundingBox, Point, SecurityAlertEvent,
    FaceDetectionResult, RoiPolygonConfig
)
from ..utils.geometry import (
    is_point_in_polygon, scale_polygon_to_frame
)

class FrameAnalysisEngine:
    """
    Engine phân tích an ninh.
    Kết hợp giữa Human Track ID và Face Detection:
    - Kiểm tra xâm nhập ngoài giờ hoạt động (AFTER_HOURS).
    - Hỗ trợ đa polygon ROI (Surveillance Polygon) giám sát các sự cố: UNAUTHORIZED, UNKNOWN, AFTER_HOURS.
    """
    def __init__(
        self,
        max_inactive_seconds: Optional[float] = None,
        after_hour_start: Optional[str] = None,
        after_hour_end: Optional[str] = None,
        unknown_alert_cooldown: Optional[float] = None
    ):
        self.max_inactive_seconds = max_inactive_seconds if max_inactive_seconds is not None else settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS
        self.after_hour_start = after_hour_start or settings.DEFAULT_AFTER_HOUR_START
        self.after_hour_end = after_hour_end or settings.DEFAULT_AFTER_HOUR_END
        self.unknown_alert_cooldown = unknown_alert_cooldown if unknown_alert_cooldown is not None else settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS
        self.active_tracks: Dict[int, TrackedPerson] = {}
        self.pending_faces: Dict[int, FaceDetectionResult] = {}
        self.last_unknown_alert_time: Dict[str, float] = {}  # camera_code -> timestamp

    def process_frame(
        self,
        detected_tracks: List[Tuple[int, BoundingBox]],
        roi_polygon: Optional[List[Point]] = None,
        camera_code: str = "CAM-001",
        current_time: Optional[float] = None,
        roi_polygons: Optional[List[Any]] = None,
        frame_size: Optional[Tuple[int, int]] = None,
        is_public_area: bool = False
    ) -> Tuple[List[TrackedPerson], List[SecurityAlertEvent]]:
        if current_time is None:
            current_time = time.time()

        try:
            tz = ZoneInfo(settings.TIMEZONE)
            now = datetime.fromtimestamp(current_time, tz).time()
        except Exception:
            from datetime import timezone, timedelta
            now = datetime.fromtimestamp(current_time, timezone(timedelta(hours=7))).time()

        try:
            start = datetime.strptime(self.after_hour_start, "%H:%M").time()
            end = datetime.strptime(self.after_hour_end, "%H:%M").time()
            if start == end:
                after_hours = False
            elif start < end:
                after_hours = (start <= now < end)
            else:
                after_hours = (now >= start or now < end)
        except (ValueError, TypeError):
            after_hours = False

        generated_alerts: List[SecurityAlertEvent] = []
        current_frame_track_ids = set()

        active_pixel_polys: List[Tuple[List[Point], List[str], str]] = []

        fw = frame_size[0] if frame_size else 0
        fh = frame_size[1] if frame_size else 0

        # Chuẩn bị danh sách Polygon giám sát
        if roi_polygons:
            for poly in roi_polygons:
                if isinstance(poly, RoiPolygonConfig):
                    pts = poly.to_pixel_points(fw, fh) if (fw > 0 and fh > 0) else poly.vertices
                    rules = poly.alert_rules or ["ENTRY_EXIT_TRACKING", "AFTER_HOURS"]
                    lbl = poly.label or ""
                elif isinstance(poly, dict):
                    raw_pts = poly.get("vertices", [])
                    pts = scale_polygon_to_frame(raw_pts, fw, fh) if (fw > 0 and fh > 0) else [Point(p["x"], p["y"]) for p in raw_pts if "x" in p and "y" in p]
                    rules = poly.get("alert_rules", ["ENTRY_EXIT_TRACKING", "AFTER_HOURS"])
                    lbl = poly.get("label", "")
                elif isinstance(poly, (list, tuple)):
                    pts = scale_polygon_to_frame(poly, fw, fh) if (fw > 0 and fh > 0) else poly
                    rules = ["ENTRY_EXIT_TRACKING", "AFTER_HOURS"]
                    lbl = ""
                else:
                    continue

                if len(pts) >= 3:
                    active_pixel_polys.append((pts, rules, lbl))

        elif roi_polygon and len(roi_polygon) >= 3:
            pts = scale_polygon_to_frame(roi_polygon, fw, fh) if (fw > 0 and fh > 0) else roi_polygon
            active_pixel_polys.append((pts, ["ENTRY_EXIT_TRACKING", "AFTER_HOURS"], "Vùng ROI"))

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
                if track_id in self.pending_faces:
                    person.face_detected = True
                    person.face_info = self.pending_faces.pop(track_id)
                self.active_tracks[track_id] = person
            else:
                person = self.active_tracks[track_id]
                person.update_position(bbox, current_time)
                if track_id in self.pending_faces:
                    person.face_detected = True
                    person.face_info = self.pending_faces.pop(track_id)

            # Kiểm tra nằm trong Surveillance Polygons
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
                in_roi = True
                matched_rules = ["ENTRY_EXIT_TRACKING", "AFTER_HOURS"]

            person.is_in_roi = in_roi

            if person.is_in_roi:
                loc_info = {"label": matched_label} if matched_label else None
                target_area_name = getattr(person.face_info, "area_name", None) if person.face_info else None
                area_display = target_area_name or matched_label or "khu vực giám sát"

                # 1. TRƯỜNG HỢP CÓ TRONG DATABASE (Đã xác định được danh tính)
                if person.face_detected and person.face_info and person.face_info.matched_code:
                    matched_code = person.face_info.matched_code
                    matched_name = person.face_info.matched_name
                    is_active = getattr(person.face_info, "is_active", True)
                    is_authorized = person.face_info.is_authorized
                    reason_str = getattr(person.face_info, "access_reason", None) or "Không có quyền ra vào khu vực này."

                    role = getattr(person.face_info, "role", "NORMAL_USER")
                    reason_lower = (reason_str or "").lower()
                    is_guard_on_duty = (
                        role == "GUARD"
                        or "bảo vệ" in reason_lower
                        or "ca trực" in reason_lower
                        or "phân công" in reason_lower
                        or "gán" in reason_lower
                    )

                    # Kiểm tra ngoại lệ trực đêm / làm ngoài giờ:
                    is_night_authorized = False
                    if is_authorized:
                        # Ban đêm chỉ được phép nếu có phân công trực ca, đơn duyệt hoặc là bảo vệ trong ca
                        if after_hours:
                            if is_guard_on_duty or "đơn" in reason_lower or "lịch trực" in reason_lower:
                                is_night_authorized = True
                        else:
                            is_night_authorized = True
                    elif after_hours and is_guard_on_duty:
                        # Bảo vệ trong ca trực ban đêm luôn được phép
                        is_night_authorized = True

                    if is_night_authorized:
                        # Người có thẩm quyền hợp lệ: Không sinh sự cố vi phạm (AUTHORIZED_ACCESS)
                        pass
                    elif is_public_area and not after_hours:
                        # Khu vực Cấp 1 (Công khai - PUBLIC): Ban ngày tất cả mọi người vẫn vào bình thường -> KHÔNG cảnh báo UNAUTHORIZED_ACCESS!
                        pass
                    else:
                        # Vi phạm: Phân loại chuẩn theo 5 nhóm (INTRUSION, ACCESS_OUT_OF_TIME, UNAUTHORIZED_ACCESS)
                        if not person.alert_unauthorized_sent:
                            reason_lower = (reason_str or "").lower()
                            if after_hours:
                                event_type = "INTRUSION"
                                tag_prefix = "[INTRUSION]"
                                if not is_active:
                                    detail_msg = f"{tag_prefix} Phát hiện tài khoản bị vô hiệu hóa ngoài giờ quy định: {matched_name} ({matched_code}) tại {area_display}."
                                else:
                                    detail_msg = f"{tag_prefix} Phát hiện người trong trường không có quyền ngoài giờ: {matched_name} ({matched_code}) tại {area_display}. Lý do: {reason_str}"
                            elif any(k in reason_lower for k in ["khung giờ", "chưa tới giờ", "hết hạn", "thời gian", "ngoài khung giờ"]):
                                event_type = "ACCESS_OUT_OF_TIME"
                                tag_prefix = "[ACCESS_OUT_OF_TIME]"
                                detail_msg = f"{tag_prefix} Phát hiện vào sai khung giờ quy định: {matched_name} ({matched_code}) tại {area_display}. Lý do: {reason_str}"
                            else:
                                event_type = "UNAUTHORIZED_ACCESS"
                                tag_prefix = "[UNAUTHORIZED_ACCESS]"
                                if not is_active:
                                    detail_msg = f"{tag_prefix} Phát hiện tài khoản bị vô hiệu hóa: {matched_name} ({matched_code}) tại {area_display}."
                                else:
                                    detail_msg = f"{tag_prefix} Phát hiện đối tượng không có quyền truy cập: {matched_name} ({matched_code}) tại {area_display}. Lý do: {reason_str}"

                            alert = SecurityAlertEvent(
                                camera_code=camera_code,
                                event_type=event_type,
                                track_id=person.track_id,
                                confidence=person.bbox.confidence,
                                details=detail_msg,
                                location=loc_info
                            )
                            generated_alerts.append(alert)
                            person.alert_unauthorized_sent = True
                            if after_hours:
                                person.alert_after_hours_sent = True

                # 2. TẤT CẢ CÁC TRƯỜNG HỢP CÒN LẠI (Người lạ ngoài hệ thống HOẶC Chưa rõ mặt / Che mặt)
                # Phân loại là INTRUSION (nếu ngoài giờ) hoặc UNKNOWN_PERSON (nếu bình thường)
                else:
                    # Quy chuẩn khuôn viên: Khu vực công cộng (Cấp 1 - Public như Cổng chính) cho phép
                    # tự do lưu thông trong giờ hoạt động bình thường -> KHÔNG cảnh báo người lạ UNKNOWN_PERSON!
                    # Chỉ kích hoạt cảnh báo INTRUSION nếu xuất hiện ngoài giờ (after_hours = True).
                    if not getattr(person, "alert_unknown_sent", False):
                        if not is_public_area or after_hours:
                            last_alert_ts = self.last_unknown_alert_time.get(camera_code, 0.0)
                            if (current_time - last_alert_ts) >= self.unknown_alert_cooldown:
                                is_face_clear = person.face_detected and person.face_info
                                face_note = "người lạ ngoài hệ thống" if is_face_clear else "chưa rõ mặt"
                                if after_hours:
                                    event_type = "INTRUSION"
                                    tag_prefix = "[INTRUSION]"
                                    detail_msg = f"{tag_prefix} Phát hiện đối tượng chưa rõ danh tính / người lạ ({face_note}) ngoài giờ hoạt động tại {area_display}."
                                else:
                                    event_type = "UNKNOWN_PERSON"
                                    tag_prefix = "[UNKNOWN_PERSON]"
                                    detail_msg = f"{tag_prefix} Phát hiện đối tượng chưa đăng ký / người lạ ({face_note}) tại {area_display}."

                                alert = SecurityAlertEvent(
                                    camera_code=camera_code,
                                    event_type=event_type,
                                    track_id=person.track_id,
                                    confidence=person.bbox.confidence,
                                    details=detail_msg,
                                    location=loc_info
                                )
                                generated_alerts.append(alert)
                                person.alert_unknown_sent = True
                                self.last_unknown_alert_time[camera_code] = current_time
                                if after_hours:
                                    person.alert_after_hours_sent = True

        inactive_ids = []
        for tid, p in self.active_tracks.items():
            if tid not in current_frame_track_ids:
                if (current_time - p.last_seen_time) > self.max_inactive_seconds:
                    inactive_ids.append(tid)

        for tid in inactive_ids:
            del self.active_tracks[tid]
            if tid in self.pending_faces:
                del self.pending_faces[tid]

        return list(self.active_tracks.values()), generated_alerts

    def associate_face(self, track_id: int, face_result: FaceDetectionResult):
        if face_result is None:
            return
        if track_id in self.active_tracks:
            self.active_tracks[track_id].face_detected = True
            self.active_tracks[track_id].face_info = face_result
        else:
            self.pending_faces[track_id] = face_result
