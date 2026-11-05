import re
import time
import logging
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

logger = logging.getLogger(__name__)

def parse_time_str(time_str: Optional[str]) -> Optional[Any]:
    if not time_str or not isinstance(time_str, str):
        return None
    time_str = time_str.strip()
    for fmt in ("%H:%M", "%H:%M:%S"):
        try:
            return datetime.strptime(time_str, fmt).time()
        except ValueError:
            continue
    return None


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
        unknown_alert_cooldown: Optional[float] = None,
        camera_unknown_min_interval: Optional[float] = None
    ):
        self.max_inactive_seconds = max_inactive_seconds if max_inactive_seconds is not None else settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS
        self.after_hour_start = after_hour_start or settings.DEFAULT_AFTER_HOUR_START
        self.after_hour_end = after_hour_end or settings.DEFAULT_AFTER_HOUR_END
        self.unknown_alert_cooldown = unknown_alert_cooldown if unknown_alert_cooldown is not None else settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS
        self.camera_unknown_min_interval = camera_unknown_min_interval if camera_unknown_min_interval is not None else getattr(settings, 'CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS', 3.0)
        self.active_tracks: Dict[int, TrackedPerson] = {}
        self.pending_faces: Dict[int, FaceDetectionResult] = {}
        self.last_unknown_alert_time: Dict[str, float] = {}  # camera_code -> timestamp
        self.track_last_unknown_alert_time: Dict[int, float] = {}  # track_id -> timestamp
        self._last_parse_warning_time: Dict[str, float] = {}  # error_key -> timestamp
        self._missing_source_warn_times: Dict[str, float] = {}

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

        start = parse_time_str(self.after_hour_start)
        end = parse_time_str(self.after_hour_end)
        after_hours = False

        if start is None or end is None:
            # Fallback về DEFAULT_AFTER_HOUR_START / END trong settings trước
            fb_start = start if start is not None else parse_time_str(settings.DEFAULT_AFTER_HOUR_START)
            fb_end = end if end is not None else parse_time_str(settings.DEFAULT_AFTER_HOUR_END)

            error_key = f"{self.after_hour_start}:{self.after_hour_end}"
            last_warn = self._last_parse_warning_time.get(error_key, 0.0)
            should_warn = (current_time - last_warn) >= 60.0

            if fb_start is not None and fb_end is not None:
                start = fb_start
                end = fb_end
                if should_warn:
                    logger.warning(
                        f"⚠️ [FrameAnalysisEngine] Lỗi định dạng giờ ngoài giờ: start='{self.after_hour_start}', end='{self.after_hour_end}'. "
                        f"Fallback về cấu hình mặc định: start='{settings.DEFAULT_AFTER_HOUR_START}', end='{settings.DEFAULT_AFTER_HOUR_END}'."
                    )
                    self._last_parse_warning_time[error_key] = current_time
            else:
                if should_warn:
                    logger.warning(
                        f"⚠️ [FrameAnalysisEngine] Lỗi định dạng giờ ngoài giờ (cả cấu hình mặc định cũng không hợp lệ): "
                        f"start='{self.after_hour_start}', end='{self.after_hour_end}'. Kích hoạt chế độ an ninh Fail-Safe: after_hours = True."
                    )
                    self._last_parse_warning_time[error_key] = current_time
                after_hours = True

        if start is not None and end is not None:
            if start == end:
                after_hours = False
            elif start < end:
                after_hours = (start <= now < end)
            else:
                after_hours = (now >= start or now < end)

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

        # Khử trùng lặp bounding box kép (double-box glitch) do detector sinh ra trong cùng frame
        filtered_detected_tracks: List[Tuple[int, BoundingBox]] = []
        if len(detected_tracks) <= 1:
            filtered_detected_tracks = detected_tracks
        else:
            def _track_prio(item: Tuple[int, BoundingBox]):
                tid, b = item
                p = self.active_tracks.get(tid)
                has_face = bool(p and p.face_detected and p.face_info and p.face_info.matched_code)
                return (1 if has_face else 0, b.confidence)

            sorted_tracks = sorted(detected_tracks, key=_track_prio, reverse=True)
            def _is_double_box_glitch(b1: BoundingBox, b2: BoundingBox) -> bool:
                iou = b1.iou(b2)
                if iou >= 0.85:
                    return True
                if iou < 0.50:
                    return False
                min_w = min(b1.width, b2.width)
                min_h = min(b1.height, b2.height)
                if min_w <= 0 or min_h <= 0:
                    return False
                dx = abs(b1.center.x - b2.center.x)
                dy = abs(b1.center.y - b2.center.y)
                # Box kép của 1 người: tâm hai box gần như trùng khớp (< 20% kích thước)
                # Hai người thật đứng sát nhau: tâm tách biệt (dx >= 20% min_w hoặc dy >= 20% min_h)
                return dx < 0.20 * min_w and dy < 0.20 * min_h

            kept_tracks = []
            for tid, bbox in sorted_tracks:
                if tid < 0:
                    continue
                is_duplicate = any(_is_double_box_glitch(bbox, kb) for _, kb in kept_tracks)
                if not is_duplicate:
                    kept_tracks.append((tid, bbox))
                else:
                    if tid in self.active_tracks:
                        self.active_tracks[tid].is_currently_visible = False
            filtered_detected_tracks = kept_tracks

        for track_id, bbox in filtered_detected_tracks:
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
                detected_tids = {tid for tid, _ in detected_tracks}
                for old_tid, old_p in list(self.active_tracks.items()):
                    if old_tid == track_id:
                        continue
                    # Tuyệt đối KHÔNG kế thừa từ track đang cùng hiện diện trong frame hiện tại!
                    # Nếu old_tid vẫn đang được phát hiện, đó là 2 người riêng biệt (kẻ lạ đứng cạnh người hợp lệ).
                    if old_tid in detected_tids:
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
                        if best_match_tid in self.track_last_unknown_alert_time:
                            self.track_last_unknown_alert_time[track_id] = self.track_last_unknown_alert_time.pop(best_match_tid)

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
                logger.info(
                    f"📍 [FrameAnalysisEngine:{camera_code}] Track #{person.track_id} BƯỚC VÀO VÙNG ROI: '{person.entered_roi_label}'"
                )
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
                logger.info(
                    f"🚶 [FrameAnalysisEngine:{camera_code}] Track #{person.track_id} ({exit_code}) RỜI KHỎI VÙNG ROI: '{exit_label}' (Thời gian ở trong vùng: {duration:.1f}s)"
                )
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
                if not isinstance(raw_loc, str):
                    raw_loc = "Khu vực giám sát"
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
                    access_source = getattr(person.face_info, "access_source", None)
                    if access_source:
                        access_source = str(access_source).strip().upper()

                    # Điểm 4, bước C: Rule ban đêm dựa trên trường `access_source` có cấu trúc từ Backend
                    allowed_night_sources = getattr(settings, "NIGHT_ALLOWED_ACCESS_SOURCES", [
                        "ASSIGNED_PERSONNEL", "ACCESS_REQUEST", "INCIDENT_RESPONSE", "OPEN_EVENT", "GUEST_VISIT"
                    ])

                    is_night_authorized = False
                    if is_authorized:
                        if after_hours:
                            if not access_source:
                                warn_key = f"{camera_code}:{matched_code}"
                                last_warn = self._missing_source_warn_times.get(warn_key, 0.0)
                                if (current_time - last_warn) >= 60.0:
                                    self._missing_source_warn_times[warn_key] = current_time
                                    logger.warning(
                                        f"[CAM: {camera_code}] Người dùng {matched_name} ({matched_code}) "
                                        f"được Backend cho phép nhưng thiếu trường access_source ngoài giờ. "
                                        f"Xử lý như ACCESS_LEVEL (từ chối ngoài giờ, phát cảnh báo PERSON_INTRUSION)."
                                    )
                                is_night_authorized = False
                            elif access_source in allowed_night_sources:
                                is_night_authorized = True
                            else:
                                is_night_authorized = False
                        else:
                            is_night_authorized = True

                    if is_night_authorized:
                        # Người có thẩm quyền hợp lệ: Không sinh sự cố vi phạm (AUTHORIZED_PERSON chỉ ghi lịch sử ra vào)
                        pass
                    elif is_public_area and not after_hours:
                        # Khu vực Cấp 1 (Công khai - PUBLIC): Ban ngày tất cả mọi người vẫn vào bình thường -> KHÔNG cảnh báo UNAUTHORIZED_PERSON!
                        pass
                    elif not getattr(person.face_info, "access_checked", False) and (current_time - (person.roi_enter_time or current_time)) < 1.5:
                        # BUG-02: Grace period 1.5s cho nguoi da xac dinh danh tinh nhung access_checked == False
                        # de cho Backend phan hoi kiem tra quyen truy cap, tranh ban canh bao vi pham gia.
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
                                elif "backend không phản hồi" in r_low or "không thể kết nối" in r_low:
                                    minimal_reason = "Backend không phản hồi"
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
                            logger.warning(
                                f"🚨 [FrameAnalysisEngine:{camera_code}] VI PHẠM AN NINH: [{event_type}] "
                                f"Người: {matched_name} ({matched_code}), Vị trí: {location_display}"
                            )
                            person.alert_unauthorized_sent = True
                            if after_hours:
                                person.alert_after_hours_sent = True

                # 2. TẤT CẢ CÁC TRƯỜNG HỢP CÒN LẠI (Người lạ ngoài hệ thống HOẶC Chưa rõ mặt / Che mặt)
                # Phân loại là PERSON_INTRUSION (nếu ngoài giờ) hoặc UNKNOWN_PERSON (nếu bình thường)
                else:
                    # Quy chuẩn khuôn viên: Khu vực công cộng (Cấp 1 - Public như Cổng chính) cho phép
                    # tự do lưu thông trong giờ hoạt động bình thường -> KHÔNG cảnh báo người lạ UNKNOWN_PERSON!
                    # Chỉ kích hoạt cảnh báo PERSON_INTRUSION nếu xuất hiện ngoài giờ (after_hours = True).
                    last_track_ts = self.track_last_unknown_alert_time.get(person.track_id)
                    track_cooldown_ok = (last_track_ts is None) or (not getattr(person, "alert_unknown_sent", False)) or ((current_time - last_track_ts) >= self.unknown_alert_cooldown)

                    if track_cooldown_ok:
                        if not is_public_area or after_hours or (active_pixel_polys and person.is_in_roi):
                            last_camera_ts = self.last_unknown_alert_time.get(camera_code)
                            camera_interval_ok = (last_camera_ts is None) or ((current_time - last_camera_ts) >= self.camera_unknown_min_interval)
                            if camera_interval_ok:
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
                                logger.warning(
                                    f"🚨 [FrameAnalysisEngine:{camera_code}] VI PHẠM AN NINH: [{event_type}] "
                                    f"Track #{person.track_id} ({event_type}), Vị trí: {location_display}"
                                )
                                person.alert_unknown_sent = True
                                self.last_unknown_alert_time[camera_code] = current_time
                                self.track_last_unknown_alert_time[person.track_id] = current_time
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
                    logger.info(f"👋 [FrameAnalysisEngine:{camera_code}] Hết thời gian lưu vết ({inactive_sec:.1f}s > {self.max_inactive_seconds}s) -> Hủy Track #{tid}")

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
            self.track_last_unknown_alert_time.pop(tid, None)

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
                    a_in_curr = tid_a in current_frame_track_ids
                    b_in_curr = tid_b in current_frame_track_ids
                    a_has_face = pa.face_detected and pa.face_info and pa.face_info.matched_code
                    b_has_face = pb.face_detected and pb.face_info and pb.face_info.matched_code

                    # Nếu cả hai track đều đang xuất hiện trong frame hiện tại:
                    if a_in_curr and b_in_curr:
                        # Hai người đứng sát nhau: Không sao chép face_info, không ẩn trừ phi trùng lặp gần như tuyệt đối (IoU > 0.85)
                        if iou_ab < 0.85:
                            continue
                        if a_has_face and not b_has_face:
                            pb.is_currently_visible = False
                            suppressed_tids.add(tid_b)
                        elif b_has_face and not a_has_face:
                            pa.is_currently_visible = False
                            suppressed_tids.add(tid_a)
                            break
                        elif pa.bbox.confidence >= pb.bbox.confidence:
                            pb.is_currently_visible = False
                            suppressed_tids.add(tid_b)
                        else:
                            pa.is_currently_visible = False
                            suppressed_tids.add(tid_a)
                            break
                        continue

                    # Nếu một track là coasting (không có trong frame hiện tại) và track kia đang có mặt:
                    if b_in_curr and not a_in_curr:
                        if a_has_face and not b_has_face:
                            pb.face_detected = True
                            pb.face_info = pa.face_info
                        pa.is_currently_visible = False
                        suppressed_tids.add(tid_a)
                        break
                    elif a_in_curr and not b_in_curr:
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
