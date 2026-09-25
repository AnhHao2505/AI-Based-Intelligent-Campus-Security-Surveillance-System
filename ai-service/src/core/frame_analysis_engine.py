import time
from datetime import datetime
from zoneinfo import ZoneInfo
from typing import Dict, List, Optional, Tuple, Any, Union
from ..config import settings
from .entity import TrackedPerson, BoundingBox, Point, SecurityAlertEvent, FaceDetectionResult, RoiPolygonConfig
from ..utils.geometry import is_point_in_polygon, scale_polygon_to_frame

class FrameAnalysisEngine:
    """
    Engine phân tích an ninh.
    Kết hợp giữa Human Track ID và Face Detection:
    - Kiểm tra xâm nhập ngoài giờ hoạt động (AFTER_HOURS).
    - Hỗ trợ đa polygon ROI và tự động chuẩn hóa/scale pixel theo khung hình.
    """
    def __init__(
        self,
        max_inactive_seconds: float = 3.0,
        after_hour_start: Optional[str] = None,
        after_hour_end: Optional[str] = None
    ):
        self.max_inactive_seconds = max_inactive_seconds
        self.after_hour_start = after_hour_start or settings.DEFAULT_AFTER_HOUR_START
        self.after_hour_end = after_hour_end or settings.DEFAULT_AFTER_HOUR_END
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
            after_hours = (start <= now < end) if start < end else (now >= start or now < end)
        except ValueError:
            after_hours = False

        generated_alerts: List[SecurityAlertEvent] = []
        current_frame_track_ids = set()

        active_pixel_polys: List[Tuple[List[Point], List[str], str]] = []

        fw = frame_size[0] if frame_size else 0
        fh = frame_size[1] if frame_size else 0

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
                    rules = ["ENTRY_EXIT_TRACKING"]
                    lbl = ""
                else:
                    continue

                if len(pts) >= 3:
                    active_pixel_polys.append((pts, rules, lbl))

        elif roi_polygon and len(roi_polygon) >= 3:
            pts = scale_polygon_to_frame(roi_polygon, fw, fh) if (fw > 0 and fh > 0) else roi_polygon
            active_pixel_polys.append((pts, ["ENTRY_EXIT_TRACKING"], "Vùng ROI"))

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
                matched_rules = ["ENTRY_EXIT_TRACKING"]

            person.is_in_roi = in_roi

            if person.is_in_roi:
                loc_info = {"label": matched_label} if matched_label else None

                if after_hours and "AFTER_HOURS" in matched_rules and not person.alert_after_hours_sent:
                    generated_alerts.append(SecurityAlertEvent(
                        camera_code=camera_code,
                        event_type="AFTER_HOURS_PRESENCE",
                        track_id=person.track_id,
                        confidence=person.bbox.confidence,
                        details=f"Phát hiện người trong khu vực {matched_label or 'giám sát'} ngoài giờ hoạt động.",
                        location=loc_info
                    ))
                    person.alert_after_hours_sent = True

                if "ENTRY_EXIT_TRACKING" in matched_rules:
                    if person.face_detected and person.face_info and not person.face_info.is_authorized:
                        if not person.alert_unauthorized_sent:
                            alert = SecurityAlertEvent(
                                camera_code=camera_code,
                                event_type="UNAUTHORIZED",
                                track_id=person.track_id,
                                confidence=person.bbox.confidence,
                                details=f"Phát hiện đối tượng không được phép {person.face_info.matched_name or 'Chưa rõ'} tại vùng {matched_label or 'giám sát'}.",
                                location=loc_info
                            )
                            generated_alerts.append(alert)
                            person.alert_unauthorized_sent = True

        inactive_ids = []
        for tid, p in self.active_tracks.items():
            if tid not in current_frame_track_ids:
                if (current_time - p.last_seen_time) > self.max_inactive_seconds:
                    inactive_ids.append(tid)

        for tid in inactive_ids:
            del self.active_tracks[tid]

        return list(self.active_tracks.values()), generated_alerts

    def associate_face(self, track_id: int, face_result: FaceDetectionResult):
        if track_id in self.active_tracks and face_result is not None:
            self.active_tracks[track_id].face_detected = True
            self.active_tracks[track_id].face_info = face_result
