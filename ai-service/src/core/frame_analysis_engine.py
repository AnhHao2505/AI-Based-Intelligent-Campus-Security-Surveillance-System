import re
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
        is_public_area: bool = False,
        area_name: Optional[str] = None
    ) -> Tuple[List[TrackedPerson], List[SecurityAlertEvent], List[Dict[str, Any]]]:
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
        roi_access_events: List[Dict[str, Any]] = []
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
                person.is_currently_visible = True
                if track_id in self.pending_faces:
                    person.face_detected = True
                    person.face_info = self.pending_faces.pop(track_id)

                # Kế thừa danh tính và trạng thái cảnh báo nếu track mới đè lên vị trí track cũ (ByteTrack switch ID)
                best_match_tid = None
                best_match_score = 0.0
                for old_tid, old_p in list(self.active_tracks.items()):
                    if old_tid == track_id:
                        continue
                    # Chỉ kế thừa từ track vừa mới xuất hiện gần đây trong vòng 2.0s (ByteTrack switch ID)
                    if (current_time - old_p.last_seen_time) > min(2.0, self.max_inactive_seconds):
                        continue
                    iou_val = bbox.iou(old_p.bbox)
                    iom_val = bbox.intersection_ratio(old_p.bbox)
                    match_score = max(iou_val, iom_val)
                    if (iou_val > 0.35 or iom_val > 0.55) and match_score > best_match_score:
                        best_match_score = match_score
                        best_match_tid = old_tid

                if best_match_tid is not None:
                    old_p = self.active_tracks[best_match_tid]
                    both_identified = (
                        person.face_detected and person.face_info and person.face_info.matched_code and
                        old_p.face_detected and old_p.face_info and old_p.face_info.matched_code
                    )
                    different_persons = bool(both_identified and (person.face_info.matched_code != old_p.face_info.matched_code))

                    if not different_persons:
                        # Kế thừa nhận diện khuôn mặt
                        if old_p.face_detected and old_p.face_info and not person.face_detected:
                            person.face_detected = True
                            person.face_info = old_p.face_info
                        # Kế thừa thông tin ROI và lịch sử cảnh báo (tránh spam sự cố lặp lại)
                        person.is_in_roi = old_p.is_in_roi
                        person.was_in_roi = old_p.was_in_roi
                        person.roi_enter_time = old_p.roi_enter_time
                        person.entered_roi_label = old_p.entered_roi_label
                        person.roi_entry_logged = old_p.roi_entry_logged
                        person.alert_unauthorized_sent = old_p.alert_unauthorized_sent
                        person.alert_unknown_sent = old_p.alert_unknown_sent
                        person.alert_after_hours_sent = old_p.alert_after_hours_sent
                        person.first_seen_time = old_p.first_seen_time
                        # Ẩn và xoá track cũ bị thay thế
                        old_p.is_currently_visible = False
                        del self.active_tracks[best_match_tid]

                self.active_tracks[track_id] = person
            else:
                person = self.active_tracks[track_id]
                person.update_position(bbox, current_time)
                person.is_currently_visible = True
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

            # Theo dõi chuyển trạng thái ra vào vùng ROI
            if in_roi and not person.was_in_roi:
                person.roi_enter_time = current_time
                person.entered_roi_label = matched_label or "Vùng giám sát"
                person.roi_entry_logged = False
            elif not in_roi and person.was_in_roi:
                duration = max(0.0, current_time - (person.roi_enter_time or current_time))
                exit_label = person.entered_roi_label or "Vùng giám sát"
                exit_code = person.face_info.matched_code if (person.face_detected and person.face_info and person.face_info.matched_code) else "UNKNOWN"
                roi_access_events.append({
                    "user_code": exit_code,
                    "roi_label": exit_label,
                    "direction": "EXIT",
                    "duration": duration
                })
                person.roi_enter_time = None
                person.entered_roi_label = None
                person.roi_entry_logged = False
                if person.face_info:
                    person.face_info.access_checked = False
                person.alert_unauthorized_sent = False
                person.alert_unknown_sent = False

            person.is_in_roi = in_roi
            person.was_in_roi = in_roi

            if person.is_in_roi:
                if not person.roi_entry_logged:
                    entry_code = person.face_info.matched_code if (person.face_detected and person.face_info and person.face_info.matched_code) else None
                    if entry_code:
                        roi_access_events.append({
                            "user_code": entry_code,
                            "roi_label": person.entered_roi_label or matched_label or "Vùng giám sát",
                            "direction": "ENTRY",
                            "duration": 0.0
                        })
                        person.roi_entry_logged = True
                    elif (current_time - person.first_seen_time) >= 1.5:
                        roi_access_events.append({
                            "user_code": "UNKNOWN",
                            "roi_label": person.entered_roi_label or matched_label or "Vùng giám sát",
                            "direction": "ENTRY",
                            "duration": 0.0
                        })
                        person.roi_entry_logged = True

                loc_info = {"label": matched_label} if matched_label else None
                target_area_name = getattr(person.face_info, "area_name", None) if person.face_info else None
                raw_loc = target_area_name or area_name or "Khu vực giám sát"
                location_display = re.sub(r"\s*\([^)]*(?:vùng|roi)[^)]*\)", "", raw_loc, flags=re.IGNORECASE).strip() or raw_loc

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
                        # Vi phạm: Phân loại chuẩn theo 5 nhóm (PERSON_INTRUSION, UNAUTHORIZED_PERSON)
                        if not person.alert_unauthorized_sent:
                            clean_reason = re.sub(r"\[[A-Z0-9_:\s]+\]\s*", "", reason_str or "").strip()
                            if "không tồn tại" in reason_lower or "đã bị xoá" in reason_lower:
                                if after_hours:
                                    event_type = "PERSON_INTRUSION"
                                    detail_msg = f"[PERSON_INTRUSION] Vị trí: {location_display}"
                                else:
                                    event_type = "UNKNOWN_PERSON"
                                    detail_msg = f"[UNKNOWN_PERSON] Vị trí: {location_display}"
                            elif after_hours:
                                event_type = "PERSON_INTRUSION"
                                detail_msg = f"[PERSON_INTRUSION] Tên: {matched_name} ({matched_code})\nLý do: Ngoài giờ hoạt động\nVị trí: {location_display}"
                            else:
                                event_type = "UNAUTHORIZED_PERSON"
                                r_low = (clean_reason or "").lower()
                                if not is_active:
                                    minimal_reason = "Tài khoản bị khóa"
                                elif "cấp độ" in r_low or "level" in r_low:
                                    minimal_reason = "Cấp độ không đủ"
                                elif "chưa tới" in r_low and any(k in r_low for k in ["giờ", "đơn", "khung"]):
                                    minimal_reason = "Chưa tới giờ duyệt"
                                elif "hết hạn" in r_low and any(k in r_low for k in ["đơn", "lượt", "sử dụng"]):
                                    minimal_reason = "Hết hạn đơn duyệt"
                                elif "phân công" in r_low:
                                    minimal_reason = "Chưa tới ngày trực" if ("chưa" in r_low) else "Hết hạn phân công"
                                elif "khách" in r_low:
                                    minimal_reason = "Ngoài giờ tiếp khách"
                                else:
                                    minimal_reason = "Không có quyền"
                                detail_msg = f"[UNAUTHORIZED_PERSON] Tên: {matched_name} ({matched_code})\nLý do: {minimal_reason}\nVị trí: {location_display}"

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
                        if not is_public_area or after_hours or (active_pixel_polys and person.is_in_roi):
                            last_alert_ts = self.last_unknown_alert_time.get(camera_code, 0.0)
                            if (current_time - last_alert_ts) >= self.unknown_alert_cooldown:
                                is_face_clear = person.face_detected and person.face_info
                                # Nếu chưa rõ mặt (chưa quét được face hoặc đang quay mặt đi), cho phép thời gian đệm 1.5s
                                # để Face Detector kịp phát hiện và định danh trước khi vội bắn cảnh báo người lạ
                                if not is_face_clear and (current_time - person.first_seen_time) < 1.5:
                                    continue
                                if after_hours:
                                    event_type = "PERSON_INTRUSION"
                                    detail_msg = f"[PERSON_INTRUSION] Vị trí: {location_display}"
                                else:
                                    event_type = "UNKNOWN_PERSON"
                                    detail_msg = f"[UNKNOWN_PERSON] Vị trí: {location_display}"

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
                inactive_sec = current_time - p.last_seen_time
                # Cho phép duy trì hiển thị bounding box (coasting 1.5s) khi người tạm thời cúi người,
                # ngồi xuống hoặc bị khuất tầm nhìn, tránh việc biến mất và mất Track ID đột ngột
                if inactive_sec > 1.5:
                    p.is_currently_visible = False
                if inactive_sec > self.max_inactive_seconds:
                    inactive_ids.append(tid)

        for tid in inactive_ids:
            p = self.active_tracks[tid]
            if getattr(p, "was_in_roi", False) and p.face_detected and p.face_info and p.face_info.matched_code:
                duration = max(0.0, current_time - (p.roi_enter_time or p.last_seen_time))
                exit_label = p.entered_roi_label or "Vùng giám sát"
                roi_access_events.append({
                    "user_code": p.face_info.matched_code,
                    "roi_label": exit_label,
                    "direction": "EXIT",
                    "duration": duration
                })
            del self.active_tracks[tid]
            if tid in self.pending_faces:
                del self.pending_faces[tid]

        # Khử trùng lặp giữa các Track đang hoạt động (Active Track-Level Deduplication / NMS)
        visible_tids = [tid for tid, p in self.active_tracks.items() if getattr(p, "is_currently_visible", True)]
        suppressed_tids = set()
        for i in range(len(visible_tids)):
            tid_a = visible_tids[i]
            if tid_a in suppressed_tids:
                continue
            pa = self.active_tracks[tid_a]
            for j in range(i + 1, len(visible_tids)):
                tid_b = visible_tids[j]
                if tid_b in suppressed_tids:
                    continue
                pb = self.active_tracks[tid_b]

                iou_ab = pa.bbox.iou(pb.bbox)
                iom_ab = pa.bbox.intersection_ratio(pb.bbox)
                if iou_ab > 0.35 or iom_ab > 0.55:
                    # Trùng lặp cùng 1 người! Giữ track tốt hơn và ẩn track phụ
                    a_in_curr = tid_a in current_frame_track_ids
                    b_in_curr = tid_b in current_frame_track_ids
                    a_has_face = pa.face_detected and pa.face_info and pa.face_info.matched_code
                    b_has_face = pb.face_detected and pb.face_info and pb.face_info.matched_code

                    if (b_in_curr and not a_in_curr) or (b_has_face and not a_has_face):
                        if a_has_face and not b_has_face:
                            pb.face_detected = True
                            pb.face_info = pa.face_info
                        pa.is_currently_visible = False
                        suppressed_tids.add(tid_a)
                        break
                    else:
                        if b_has_face and not a_has_face:
                            pa.face_detected = True
                            pa.face_info = pb.face_info
                        pb.is_currently_visible = False
                        suppressed_tids.add(tid_b)

        return list(self.active_tracks.values()), generated_alerts, roi_access_events

    def associate_face(self, track_id: int, face_result: FaceDetectionResult):
        if face_result is None:
            return
        if track_id in self.active_tracks:
            self.active_tracks[track_id].face_detected = True
            self.active_tracks[track_id].face_info = face_result
        else:
            self.pending_faces[track_id] = face_result
