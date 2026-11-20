import copy
import re
import time
import uuid
import logging
from datetime import datetime
from zoneinfo import ZoneInfo
from typing import Dict, List, Optional, Tuple, Any, Set
from ..config import settings
from .entity import (
    TrackedPerson, BoundingBox, Point, SecurityAlertEvent,
    FaceDetectionResult, RoiPolygonConfig
)
from ..utils.geometry import (
    is_point_in_polygon, scale_polygon_to_frame
)

logger = logging.getLogger(__name__)

# Hằng số KỸ THUẬT (chống rung / bù độ trễ xử lý), không phải tham số nghiệp vụ nên không đưa lên màn Cấu hình:
# - ROI_MIN_DWELL_SECONDS: phải ở trong ROI tối thiểu chừng này mới tính là đã vào (chống rung 1 frame ở mép ROI).
# - TRACK_COASTING_SECONDS: track không còn được detector trả về quá chừng này thì coi là đã khuất khỏi khung hình.
# - ACCESS_DECISION_EXTRA_WAIT_SECONDS: chờ thêm sau timeout check-entry để kết quả fallback kịp về tới engine.
ROI_MIN_DWELL_SECONDS = 0.2
TRACK_COASTING_SECONDS = 1.5
ACCESS_DECISION_EXTRA_WAIT_SECONDS = 0.2
# - OCCLUSION_CONTEST_IOU / IOM: hai người đang thấy chồng lấn tới mức này thì ByteTrack có thể tráo ID giữa họ
#   -> danh tính đã nhận diện của cả hai bị coi là chưa chắc, phải quét lại mặt để xác nhận.
OCCLUSION_CONTEST_IOU = 0.20
OCCLUSION_CONTEST_IOM = 0.35

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

# L4: mã lý do từ chối khách (GuestEntryDenyReason, backend trả ở denyReason) -> lý do ngắn trên cảnh báo
GUEST_DENY_REASON_LABELS = {
    "VISIT_NOT_ACTIVE": "Lượt khách không còn hiệu lực",
    "OUTSIDE_WINDOW": "Ngoài giờ tiếp khách",
    "AREA_NOT_IN_VISIT": "Khu vực không thuộc lượt khách",
    "AREA_INACTIVE": "Khu vực tạm dừng hoạt động",
    "AREA_TYPE_NOT_ALLOWED": "Loại khu vực không nhận khách",
    "HOST_LOST_ACCESS": "Người tiếp đón mất quyền",
    "BIOMETRIC_DELETED": "Dữ liệu khuôn mặt đã bị xoá",
    "BIOMETRIC_NOT_READY": "Dữ liệu khuôn mặt chưa sẵn sàng",
}

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
        camera_unknown_min_interval: Optional[float] = None,
        access_grace_period: Optional[float] = None,
        unauthorized_alert_cooldown: Optional[float] = None
    ):
        self.max_inactive_seconds = max_inactive_seconds if max_inactive_seconds is not None else settings.DEFAULT_TRACK_MAX_INACTIVE_SECONDS
        self.after_hour_start = after_hour_start or settings.DEFAULT_AFTER_HOUR_START
        self.after_hour_end = after_hour_end or settings.DEFAULT_AFTER_HOUR_END
        self.unknown_alert_cooldown = unknown_alert_cooldown if unknown_alert_cooldown is not None else settings.DEFAULT_UNKNOWN_ALERT_COOLDOWN_SECONDS
        self.camera_unknown_min_interval = camera_unknown_min_interval if camera_unknown_min_interval is not None else getattr(settings, 'CAMERA_UNKNOWN_ALERT_MIN_INTERVAL_SECONDS', 3.0)
        self.access_grace_period = access_grace_period if access_grace_period is not None else getattr(settings, 'ACCESS_RESOLUTION_GRACE_PERIOD_SECONDS', 1.5)
        self.unauthorized_alert_cooldown = unauthorized_alert_cooldown if unauthorized_alert_cooldown is not None else getattr(settings, 'DEFAULT_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS', 30.0)
        # Hold buffer: hết access_grace_period mà vẫn đang thấy mặt đủ chất lượng nhưng chưa khớp -> chờ thêm tối đa
        # identity_hold_seconds trước khi chốt UNKNOWN (cấu hình AI_IDENTITY_HOLD_SECONDS trên màn Cấu hình hệ thống).
        self.identity_hold_seconds = getattr(settings, 'ACCESS_RESOLUTION_HOLD_SECONDS', 1.0)
        self.active_tracks: Dict[int, TrackedPerson] = {}
        self.pending_faces: Dict[int, FaceDetectionResult] = {}
        self.last_unknown_alert_time: Dict[str, float] = {}  # camera_code -> timestamp
        self.track_last_unknown_alert_time: Dict[int, float] = {}  # track_id -> timestamp
        self.track_last_unauthorized_alert_time: Dict[int, float] = {}  # track_id -> timestamp
        self.user_last_unauthorized_alert_time: Dict[str, float] = {}  # matched_code -> timestamp
        self._last_parse_warning_time: Dict[str, float] = {}  # error_key -> timestamp
        self._missing_source_warn_times: Dict[str, float] = {}

    def find_matching_parent_track(
        self,
        bbox: BoundingBox,
        current_time: float,
        exclude_tids: Optional[Set[int]] = None,
        user_code: Optional[str] = None,
        frame_size: Optional[Tuple[int, int]] = None
    ) -> Optional[Tuple[int, TrackedPerson]]:
        """
        Tìm kiếm track cha gần đây để kế thừa danh tính và vòng đời trạng thái (Sticky Security State):
        1. Khớp theo user_code nếu cả hai đã định danh cùng một người.
        2. Khớp theo độ trùng lấn không gian (IoU > 0.30 hoặc IoM > 0.50).
        3. Khớp ngữ cảnh phòng kín (Single Person Scene Continuity): Nếu chỉ có duy nhất 1 người
           vừa xuất hiện gần đây trong vòng 2.5s và không có ai khác trong frame hiện tại.
        """
        exclude = exclude_tids or set()
        best_tid = None
        best_score = 0.0

        active_recent = [
            (tid, p) for tid, p in self.active_tracks.items()
            if tid not in exclude and (current_time - p.last_seen_time) <= min(2.5, self.max_inactive_seconds)
        ]

        # Ưu tiên 1: Cùng user_code
        if user_code:
            for tid, p in active_recent:
                if p.face_detected and p.face_info and getattr(p.face_info, "matched_code", None) == user_code:
                    return tid, p

        # Ưu tiên 2: Không gian (IoU / IoM)
        for tid, p in active_recent:
            iou_val = bbox.iou(p.bbox)
            iom_val = bbox.intersection_ratio(p.bbox)
            score = max(iou_val, iom_val)
            if (iou_val > 0.30 or iom_val > 0.50) and score > best_score:
                best_score = score
                best_tid = tid

        if best_tid is not None:
            return best_tid, self.active_tracks[best_tid]

        # Ưu tiên 3: Single Person Scene Continuity / Fast Movement Continuity
        if active_recent:
            c1_x, c1_y = (bbox.x1 + bbox.x2) / 2.0, (bbox.y1 + bbox.y2) / 2.0
            best_candidate = None
            min_dist = float('inf')
            for tid, p in active_recent:
                c2_x, c2_y = (p.bbox.x1 + p.bbox.x2) / 2.0, (p.bbox.y1 + p.bbox.y2) / 2.0
                dist = ((c1_x - c2_x)**2 + (c1_y - c2_y)**2)**0.5
                if dist < min_dist:
                    min_dist = dist
                    best_candidate = (tid, p)

            if best_candidate is not None:
                tid, p = best_candidate
                # G3.4: Chuẩn hoá ngưỡng khoảng cách theo đường chéo khung hình (chuẩn base 1080p: đường chéo ~2202.9px)
                scale_factor = 1.0
                if frame_size and frame_size[0] > 0 and frame_size[1] > 0:
                    diag = (frame_size[0] ** 2 + frame_size[1] ** 2) ** 0.5
                    scale_factor = diag / 2202.9

                thresh_single = 1000.0 * scale_factor
                thresh_normal = 500.0 * scale_factor
                thresh_alert = 800.0 * scale_factor

                # Nếu chỉ có 1 người duy nhất gần đây: Cho phép khoảng cách nhảy vọt lên tới 1000px
                # (đặc biệt khi người lao nhanh, nhảy qua ROI vào phòng trong khung hình 1080p/2K)
                if len(active_recent) == 1 and min_dist < thresh_single:
                    return tid, p
                elif min_dist < thresh_normal:
                    return tid, p
                elif getattr(p, 'security_display_state', 'NEUTRAL') in ('VERIFYING', 'UNKNOWN', 'UNAUTHORIZED', 'INTRUSION') and min_dist < thresh_alert:
                    return tid, p

        return None

    @staticmethod
    def parent_identity_is_safe(
        bbox: BoundingBox,
        parent: TrackedPerson,
        visible_count: int,
        user_code: Optional[str] = None
    ) -> bool:
        """
        Track cha tìm được có đủ tin cậy để chuyển DANH TÍNH KHUÔN MẶT sang track mới không.
        Khớp chỉ theo khoảng cách (Ưu tiên 3, tới 500-1000px) chỉ an toàn khi trong frame có đúng 1 người;
        có người khác đứng gần thì người mới xuất hiện rất dễ nhận nhầm danh tính của người vừa bị che khuất.
        """
        if user_code and parent.face_info and getattr(parent.face_info, "matched_code", None) == user_code:
            return True
        if bbox.iou(parent.bbox) > 0.30 or bbox.intersection_ratio(parent.bbox) > 0.50:
            return True
        return visible_count <= 1

    @staticmethod
    def _copy_identity_as_inherited(receiver: TrackedPerson, donor: TrackedPerson) -> None:
        """
        Chuyển danh tính từ track donor sang receiver dưới dạng KẾ THỪA (chưa xác thực trên chính receiver):
        receiver sẽ được quét lại mặt mỗi frame và bị gỡ / đổi danh tính nếu mặt thật không khớp.
        Sao chép face_info thành object riêng để cờ kế thừa không ghi lên track donor.
        """
        receiver.face_detected = True
        receiver.face_info = copy.copy(donor.face_info)
        receiver.expires_at = getattr(donor, "expires_at", None)
        receiver.starts_at = getattr(donor, "starts_at", None)
        receiver.is_inherited_identity = True
        receiver.identity_contested = False
        receiver.inherited_mismatch_count = getattr(donor, "inherited_mismatch_count", 0)
        receiver.identity_verified_at = None
        if hasattr(receiver.face_info, "is_inherited"):
            receiver.face_info.is_inherited = True

    @staticmethod
    def _is_double_box_glitch(b1: BoundingBox, b2: BoundingBox) -> bool:
        """Hai box gần như trùng nhau là box kép detector sinh cho CÙNG một người, không phải hai người."""
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

    def _release_identity(self, person: TrackedPerson, current_time: float, camera_code: str, reason: str) -> None:
        """
        Gỡ danh tính khỏi track (danh tính đó đang được xác nhận bằng khuôn mặt ở một người khác trong frame).
        Track trở thành người chưa xác thực: được quét mặt lại từ đầu, có lại khoảng chờ xác thực tính từ bây giờ,
        rồi bị cảnh báo theo danh tính thật (hoặc UNKNOWN) như một lượt vào ROI bình thường.
        """
        old_code = person.face_info.matched_code if person.face_info else None
        person.face_detected = False
        person.face_info = None
        person.expires_at = None
        person.starts_at = None
        person.is_inherited_identity = False
        person.identity_contested = False
        person.inherited_mismatch_count = 0
        person.identity_verified_at = None
        person.pending_reverify_code = None
        person.pending_reverify_count = 0
        person.alert_unknown_sent = False
        person.alert_unauthorized_sent = False
        person.alert_after_hours_sent = False
        person.unknown_verdict_time = None
        person.unknown_verdict_session_id = None
        person.identity_update_sent = False
        person.entry_logged_as_unknown = False
        person.roi_entry_logged = False
        self.track_last_unknown_alert_time.pop(person.track_id, None)
        self.track_last_unauthorized_alert_time.pop(person.track_id, None)
        if person.is_in_roi:
            person.roi_enter_time = current_time
        new_state = "VERIFYING" if (person.is_in_roi or getattr(person, "has_entered_roi", False)) else "NEUTRAL"
        logger.warning(
            f"🧭 [FrameAnalysisEngine:{camera_code}] Track #{person.track_id}: gỡ danh tính [{old_code}] ({reason})"
        )
        self._set_security_display_state(person, new_state, camera_code, reason)

    def _enforce_unique_identities(
        self, detected_tracks: List[Tuple[int, BoundingBox]], current_time: float, camera_code: str
    ) -> None:
        """
        Một người chỉ đứng ở một chỗ: trong cùng frame không được có hai box đang thấy mang cùng một mã người.
        Xảy ra khi người A bị người B che -> ByteTrack đẩy track của A sang B (hoặc B kế thừa danh tính của A),
        rồi A hiện ra lại và được nhận diện đúng trên track mới. Track được xác nhận bằng mặt GẦN NHẤT giữ danh tính
        (cùng frame thì điểm khớp cao hơn giữ); track kia bị gỡ danh tính. Hai box chỉ là box kép của cùng một người
        thì để khối khử trùng lặp xử lý.
        """
        boxes = {tid: b for tid, b in detected_tracks if tid >= 0}
        claims: Dict[str, List[Tuple[int, float, float, bool]]] = {}
        for tid in boxes:
            pending = tid not in self.active_tracks
            if pending:
                face = self.pending_faces.get(tid)
                verified_at = getattr(face, "matched_at", None) if face else None
            else:
                p = self.active_tracks[tid]
                face = p.face_info if p.face_detected else None
                verified_at = getattr(p, "identity_verified_at", None)
            code = getattr(face, "matched_code", None) if face else None
            if not code:
                continue
            score = float(getattr(face, "score", 0.0) or 0.0)
            claims.setdefault(code, []).append((tid, verified_at, score, pending))

        for code, holders in claims.items():
            if len(holders) < 2:
                continue
            winner = max(holders, key=lambda h: (h[1] if h[1] is not None else float("-inf"), h[2]))
            if winner[1] is None:
                continue  # Không track nào tự xác nhận bằng mặt: chờ quét lại mặt phân xử
            for tid, _verified_at, _score, pending in holders:
                if tid == winner[0] or self._is_double_box_glitch(boxes[tid], boxes[winner[0]]):
                    continue
                if pending:
                    self.pending_faces.pop(tid, None)
                    continue
                self._release_identity(
                    self.active_tracks[tid], current_time, camera_code,
                    f"{code} đang được nhận diện bằng khuôn mặt ở Track #{winner[0]}"
                )

    def _contest_identities_on_overlap(self, visible_tracks: List[Tuple[int, BoundingBox]], camera_code: str) -> None:
        """
        Hai người đang thấy chồng lấn nhau: ByteTrack có thể tráo ID, khiến danh tính của người bị che dính sang người
        đang che. Danh tính đã nhận diện của cả hai được đánh dấu chưa xác nhận (như danh tính kế thừa): pipeline quét
        lại mặt mỗi frame, mặt đúng người -> xác nhận lại; mặt người khác -> đổi hoặc gỡ danh tính.
        Đánh dấu identity_contested: điểm mặt thấp chỉ được tính để gỡ danh tính sau khi hai box đã tách nhau
        (đi ngang người khác không phải bằng chứng tráo ID).
        """
        persons = [self.active_tracks[tid] for tid, _ in visible_tracks if tid >= 0 and tid in self.active_tracks]
        contested: Set[int] = set()
        for i in range(len(persons)):
            for j in range(i + 1, len(persons)):
                a, b = persons[i].bbox, persons[j].bbox
                if a.iou(b) > OCCLUSION_CONTEST_IOU or a.intersection_ratio(b) > OCCLUSION_CONTEST_IOM:
                    contested.add(persons[i].track_id)
                    contested.add(persons[j].track_id)
        for p in persons:
            if p.track_id not in contested or getattr(p, "is_inherited_identity", False):
                continue
            if not (p.face_detected and p.face_info and p.face_info.matched_code):
                continue
            p.is_inherited_identity = True
            p.identity_contested = True
            logger.info(
                f"🫂 [FrameAnalysisEngine:{camera_code}] Track #{p.track_id} ({p.face_info.matched_code}) chồng lấn người khác "
                f"-> quét lại mặt để xác nhận danh tính"
            )

    def _within_identity_grace(self, person: TrackedPerson, current_time: float) -> bool:
        """
        Người VỪA được khớp mặt mà quyết định quyền chưa về: chờ thêm tối đa ACCESS_CHECK_TIMEOUT_SECONDS + 0.2s
        tính từ lúc khớp mặt (check-entry gọi 1 lần, hết timeout thì có kết quả fallback). Tránh cảnh báo
        UNAUTHORIZED/INTRUSION sai cho người có quyền chỉ vì được nhận ra sau khi grace 1.5s từ lúc vào ROI đã hết.
        """
        matched_at = getattr(person.face_info, "matched_at", None) if person.face_info else None
        if matched_at is None:
            return False
        wait = float(getattr(settings, "ACCESS_CHECK_TIMEOUT_SECONDS", 1.5)) + ACCESS_DECISION_EXTRA_WAIT_SECONDS
        return (current_time - matched_at) < wait

    def _has_usable_face_since(self, person: TrackedPerson, since: float) -> bool:
        """Từ mốc `since` (lúc vào ROI) đã có khuôn mặt qua bộ lọc chất lượng gắn vào track này chưa."""
        if not (person.face_detected and person.face_info):
            return False
        seen_at = getattr(person.face_info, "seen_at", None)
        return seen_at is not None and seen_at >= since

    def _unknown_verdict_active(self, person: TrackedPerson, current_time: float) -> bool:
        """Người này đã bị chốt cảnh báo không danh tính và còn trong unknown_alert_cooldown."""
        t = getattr(person, "unknown_verdict_time", None)
        return t is not None and (current_time - t) < self.unknown_alert_cooldown

    def _absorb_late_identity(self, person: TrackedPerson, matched_code: Optional[str], camera_code: str) -> None:
        """
        Một lượt vào ROI chỉ có một cảnh báo: đã chốt UNKNOWN / PERSON_INTRUSION (người lạ) mà sau đó mới nhận ra mặt
        -> KHÔNG phát cảnh báo thứ hai. Đánh dấu đã cảnh báo, cooldown tính từ lúc chốt cảnh báo đầu
        (hết cooldown thì người vẫn còn đó có thể bị cảnh báo lại như bình thường).
        """
        verdict_t = person.unknown_verdict_time
        person.alert_unauthorized_sent = True
        self.track_last_unauthorized_alert_time[person.track_id] = verdict_t
        if matched_code:
            prev = self.user_last_unauthorized_alert_time.get(matched_code)
            self.user_last_unauthorized_alert_time[matched_code] = verdict_t if prev is None else max(prev, verdict_t)
        logger.info(
            f"🔒 [FrameAnalysisEngine:{camera_code}] Track #{person.track_id}: đã chốt cảnh báo người lạ trước đó, "
            f"nhận ra {matched_code} muộn -> không phát thêm cảnh báo"
        )

    @staticmethod
    def _vanished_unverified_in_roi(person: TrackedPerson, visible_bboxes: List[BoundingBox]) -> bool:
        """
        Track mất dấu khi đang ở trong ROI, chưa xác thực (VERIFYING, chưa có danh tính), chưa bị cảnh báo,
        đã ở trong ROI tối thiểu ROI_MIN_DWELL_SECONDS. Vị trí cuối còn trùng một người đang thấy trong frame
        -> là box kép của detector / ByteTrack đổi ID, KHÔNG phải người biến mất -> bỏ qua.
        """
        if not (person.is_in_roi and getattr(person, "security_display_state", "NEUTRAL") == "VERIFYING"):
            return False
        if person.face_detected and person.face_info and person.face_info.matched_code:
            return False
        if person.alert_unknown_sent or person.roi_enter_time is None:
            return False
        if (person.last_seen_time - person.roi_enter_time) < ROI_MIN_DWELL_SECONDS:
            return False
        for b in visible_bboxes:
            if person.bbox.iou(b) > 0.30 or person.bbox.intersection_ratio(b) > 0.50:
                return False
        return True

    def _emit_identityless_alert(
        self,
        person: TrackedPerson,
        camera_code: str,
        area_name: Optional[str],
        roi_label: str,
        after_hours: bool,
        current_time: float,
        generated_alerts: List[SecurityAlertEvent],
        state_reason: str,
    ) -> None:
        """Chốt cảnh báo KHÔNG danh tính cho người chưa xác thực: UNKNOWN_PERSON (trong giờ) / PERSON_INTRUSION (ngoài giờ)."""
        target_area_name = getattr(person.face_info, "area_name", None) if person.face_info else None
        raw_loc = target_area_name or area_name or roi_label or "Khu vực giám sát"
        if not isinstance(raw_loc, str):
            raw_loc = "Khu vực giám sát"
        location_display = re.sub(r"\s*\([^)]*(?:vùng|roi)[^)]*\)", "", raw_loc, flags=re.IGNORECASE).strip() or raw_loc

        if after_hours:
            self._set_security_display_state(person, "INTRUSION", camera_code, "Đột nhập ngoài giờ")
            evt_type = "PERSON_INTRUSION"
            det_msg = f"[PERSON_INTRUSION] Vị trí: {location_display}"
        else:
            self._set_security_display_state(person, "UNKNOWN", camera_code, state_reason)
            evt_type = "UNKNOWN_PERSON"
            det_msg = f"[UNKNOWN_PERSON] Vị trí: {location_display}"

        generated_alerts.append(SecurityAlertEvent(
            camera_code=camera_code,
            event_type=evt_type,
            track_id=person.track_id,
            confidence=person.bbox.confidence,
            details=det_msg,
            location={"label": roi_label},
            roi_session_id=self._start_unknown_verdict(person, current_time)
        ))
        logger.warning(
            f"🚨 [FrameAnalysisEngine:{camera_code}] VI PHẠM AN NINH: [{evt_type}] "
            f"Track #{person.track_id} ({evt_type}), Vị trí: {location_display}"
        )
        person.alert_unknown_sent = True
        self.last_unknown_alert_time[camera_code] = current_time
        self.track_last_unknown_alert_time[person.track_id] = current_time

    @staticmethod
    def _start_unknown_verdict(person: TrackedPerson, current_time: float) -> str:
        """Ghi nhận vừa chốt cảnh báo không danh tính; trả mã phiên gửi kèm cảnh báo."""
        person.unknown_verdict_time = current_time
        person.unknown_verdict_session_id = str(uuid.uuid4())
        person.identity_update_sent = False
        return person.unknown_verdict_session_id

    def _emit_identity_update(
        self,
        person: TrackedPerson,
        outcome: str,
        camera_code: str,
        generated_alerts: List[SecurityAlertEvent],
        details: str,
        location: Optional[Dict[str, Any]],
    ) -> None:
        """
        Đã chốt cảnh báo không danh tính cho người này, nay mới nhận ra mặt -> gửi IDENTITY_UPDATE (1 lần) để Backend
        cập nhật sự cố cũ (UNAUTHORIZED: thêm tên, lý do) hoặc tự đóng (AUTHORIZED). Không phải cảnh báo mới.
        """
        session_id = getattr(person, "unknown_verdict_session_id", None)
        if not session_id or getattr(person, "identity_update_sent", False):
            return
        face = person.face_info
        user_code = getattr(face, "matched_code", None) if face else None
        if not user_code:
            return
        user_name = getattr(face, "matched_name", None) or user_code
        generated_alerts.append(SecurityAlertEvent(
            camera_code=camera_code,
            event_type="IDENTITY_UPDATE",
            track_id=person.track_id,
            confidence=person.bbox.confidence,
            details=details,
            location=location,
            roi_session_id=session_id,
            identity_outcome=outcome,
            user_code=user_code,
            user_name=user_name,
        ))
        person.identity_update_sent = True
        logger.info(
            f"🪪 [FrameAnalysisEngine:{camera_code}] Track #{person.track_id}: nhận ra {user_name} ({user_code}) sau cảnh báo "
            f"người lạ -> gửi IDENTITY_UPDATE [{outcome}] cho phiên {session_id}"
        )

    def _emit_authorized_identity_update(
        self, person: TrackedPerson, camera_code: str, generated_alerts: List[SecurityAlertEvent], location: Optional[Dict[str, Any]]
    ) -> None:
        face = person.face_info
        code = getattr(face, "matched_code", None) if face else None
        name = (getattr(face, "matched_name", None) if face else None) or code
        self._emit_identity_update(
            person, "AUTHORIZED", camera_code, generated_alerts,
            f"[IDENTITY_UPDATE] Tên: {name} ({code})\nKết quả: Có quyền ra vào", location
        )

    def _check_access_window_timers(
        self,
        person: TrackedPerson,
        current_time: float,
        roi_label: str,
        roi_access_events: List[Dict[str, Any]],
    ) -> None:
        """
        Tới mốc starts_at (bắt đầu hiệu lực) hoặc expires_at (hết hiệu lực) backend trả về: KHÔNG tự đổi quyền,
        chỉ gửi lượt ENTRY hỏi lại backend (backend ghi nhật ký ra vào). Màu, cảnh báo, IDENTITY_UPDATE do kết quả
        backend quyết định (VideoPipeline đặt cờ window_recheck_result_ready khi có kết quả).
        Lý do: đơn có thể bị huỷ sát giờ, hoặc người còn nguồn quyền khác phủ tiếp, AI không biết được.
        """
        face = person.face_info
        if not (person.face_detected and face and getattr(face, "matched_code", None)):
            return
        starts_at = person.starts_at or getattr(face, "starts_at", None)
        expires_at = person.expires_at or getattr(face, "expires_at", None)
        starts_due = starts_at is not None and current_time >= starts_at
        expires_due = expires_at is not None and current_time >= expires_at
        if not (starts_due or expires_due):
            return
        if starts_due:
            person.starts_at = None
            face.starts_at = None
        if expires_due:
            person.expires_at = None
            face.expires_at = None
        logger.info(
            f"⏱️ [FrameAnalysisEngine] Track #{person.track_id} ({face.matched_code}) chạm mốc "
            f"{'bắt đầu' if starts_due else 'hết'} hiệu lực quyền -> hỏi lại Backend"
        )
        roi_access_events.append({
            "user_code": face.matched_code,
            "roi_label": roi_label,
            "direction": "ENTRY",
            "duration": 0.0,
            "track_id": person.track_id,
            "force_recheck": True,
        })

    def _consume_window_recheck_result(
        self,
        person: TrackedPerson,
        after_hours: bool,
        camera_code: str,
        current_time: float,
    ) -> bool:
        """
        Xử lý kết quả backend của lượt hỏi lại do chạm mốc thời gian. Trả True khi kết quả khác trạng thái đang hiển thị
        (xanh -> không còn quyền, hoặc đỏ/vàng -> có quyền), để nơi gọi xét lại trạng thái và cảnh báo.
        Khi mất quyền: xoá cooldown cảnh báo vi phạm để cảnh báo hết hạn được phát ngay.
        """
        if not getattr(person, "window_recheck_result_ready", False):
            return False
        person.window_recheck_result_ready = False
        face = person.face_info
        if not (person.face_detected and face and getattr(face, "matched_code", None)
                and getattr(face, "access_checked", False)):
            return False
        authorized_now = self._check_is_night_authorized(person, after_hours, camera_code, current_time)
        showing_authorized = getattr(person, "security_display_state", "NEUTRAL") == "AUTHORIZED"
        if authorized_now == showing_authorized:
            return False
        if not authorized_now:
            person.alert_unauthorized_sent = False
            self.track_last_unauthorized_alert_time.pop(person.track_id, None)
            self.user_last_unauthorized_alert_time.pop(face.matched_code, None)
        return True

    def _check_is_night_authorized(
        self,
        person: TrackedPerson,
        after_hours: bool,
        camera_code: str,
        current_time: float,
    ) -> bool:
        """
        Luật ban đêm (BR-294, Lucas chốt P4-a): chỉ xét miễn trừ khi Backend ĐÃ CHO PHÉP (is_authorized=True).
        - Backend từ chối -> luôn vi phạm (ban đêm PERSON_INTRUSION), kể cả role GUARD hay source nằm trong danh sách miễn.
        - Backend cho phép + ngoài giờ: GUARD được miễn không cần source; người khác phải có source thuộc
          NIGHT_ALLOWED_ACCESS_SOURCES (ACCESS_LEVEL, OPEN_EVENT hay thiếu source đều bị PERSON_INTRUSION).
        """
        if not person.face_info or not person.face_info.is_authorized:
            return False
        if not after_hours:
            return True

        role = getattr(person.face_info, "role", "NORMAL_USER")
        if role == "GUARD":
            return True

        access_source = getattr(person.face_info, "access_source", None)
        if access_source:
            access_source = str(access_source).strip().upper()

        if not access_source:
            matched_code = getattr(person.face_info, "matched_code", None)
            matched_name = getattr(person.face_info, "matched_name", matched_code)
            warn_key = f"{camera_code}:{matched_code}"
            last_warn = self._missing_source_warn_times.get(warn_key, 0.0)
            if (current_time - last_warn) >= 60.0:
                self._missing_source_warn_times[warn_key] = current_time
                logger.warning(
                    f"[CAM: {camera_code}] Người dùng {matched_name} ({matched_code}) "
                    f"được Backend cho phép nhưng thiếu trường access_source ngoài giờ. "
                    f"Xử lý như ACCESS_LEVEL (từ chối ngoài giờ, phát cảnh báo PERSON_INTRUSION)."
                )
            return False

        allowed_night_sources = getattr(settings, "NIGHT_ALLOWED_ACCESS_SOURCES", [
            "ASSIGNED_PERSONNEL", "ACCESS_REQUEST", "GUEST_VISIT", "INCIDENT_RESPONSE"
        ])
        return access_source in allowed_night_sources

    def _set_security_display_state(
        self,
        person: TrackedPerson,
        new_state: str,
        camera_code: str,
        reason: Optional[str] = None
    ):
        old_state = getattr(person, "security_display_state", "NEUTRAL")
        if old_state != new_state:
            person.security_display_state = new_state
            name_part = f" ({person.face_info.matched_name})" if (person.face_detected and person.face_info and person.face_info.matched_name) else (
                f" ({person.face_info.matched_code})" if (person.face_detected and person.face_info and person.face_info.matched_code) else ""
            )
            reason_part = f" [{reason}]" if reason else ""
            logger.info(
                f"🔄 [{camera_code}] Track #{person.track_id}{name_part} đổi trạng thái: {old_state} ➔ {new_state}{reason_part}"
            )
        else:
            person.security_display_state = new_state

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

        if start is None or end is None or start == end:
            # Không cho phép start == end (tránh vô hiệu hoá bảo vệ ngoài giờ). Fallback về DEFAULT_AFTER_HOUR_START / END
            fb_start = parse_time_str(settings.DEFAULT_AFTER_HOUR_START)
            fb_end = parse_time_str(settings.DEFAULT_AFTER_HOUR_END)

            error_key = f"{self.after_hour_start}:{self.after_hour_end}"
            last_warn = self._last_parse_warning_time.get(error_key, 0.0)
            should_warn = (current_time - last_warn) >= 60.0

            if fb_start is not None and fb_end is not None and fb_start != fb_end:
                start = fb_start
                end = fb_end
                if should_warn:
                    logger.warning(
                        f"⚠️ [FrameAnalysisEngine] Cấu hình giờ giới nghiêm không hợp lệ (start='{self.after_hour_start}', end='{self.after_hour_end}'). "
                        f"Không cho phép 2 giờ trùng nhau. Fallback về cấu hình mặc định: start='{settings.DEFAULT_AFTER_HOUR_START}', end='{settings.DEFAULT_AFTER_HOUR_END}'."
                    )
                    self._last_parse_warning_time[error_key] = current_time
            else:
                if should_warn:
                    logger.warning(
                        f"⚠️ [FrameAnalysisEngine] Cấu hình giờ giới nghiêm không hợp lệ và cấu hình mặc định cũng không khả dụng "
                        f"(start='{self.after_hour_start}', end='{self.after_hour_end}'). Kích hoạt chế độ an ninh Fail-Safe: after_hours = True."
                    )
                    self._last_parse_warning_time[error_key] = current_time
                after_hours = True

        if not after_hours and start is not None and end is not None:
            if start < end:
                after_hours = (start <= now < end)
            else:
                after_hours = (now >= start or now < end)

        generated_alerts: List[SecurityAlertEvent] = []
        roi_access_events: List[Dict[str, Any]] = []
        current_frame_track_ids = set()

        # G2.4: alert_rules là tính năng thừa/dead-code (Backend và Frontend không hỗ trợ),
        # ROI surveillance chỉ cần lưu toạ độ đỉnh và tên nhãn.
        active_pixel_polys: List[Tuple[List[Point], str]] = []

        fw = frame_size[0] if frame_size else 0
        fh = frame_size[1] if frame_size else 0

        # Chuẩn bị danh sách Polygon giám sát
        if roi_polygons:
            for poly in roi_polygons:
                if isinstance(poly, RoiPolygonConfig):
                    pts = poly.to_pixel_points(fw, fh) if (fw > 0 and fh > 0) else poly.vertices
                    lbl = poly.label or ""
                elif isinstance(poly, dict):
                    raw_pts = poly.get("vertices", [])
                    pts = scale_polygon_to_frame(raw_pts, fw, fh) if (fw > 0 and fh > 0) else [Point(p["x"], p["y"]) for p in raw_pts if "x" in p and "y" in p]
                    lbl = poly.get("label", "")
                elif isinstance(poly, (list, tuple)):
                    pts = scale_polygon_to_frame(poly, fw, fh) if (fw > 0 and fh > 0) else poly
                    lbl = ""
                else:
                    continue

                if len(pts) >= 3:
                    active_pixel_polys.append((pts, lbl))

        elif roi_polygon and len(roi_polygon) >= 3:
            pts = scale_polygon_to_frame(roi_polygon, fw, fh) if (fw > 0 and fh > 0) else roi_polygon
            active_pixel_polys.append((pts, "Vùng ROI"))

        # Một mã người chỉ được mang bởi một box đang thấy (gỡ danh tính bị dính sang người che khuất)
        self._enforce_unique_identities(detected_tracks, current_time, camera_code)

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
            kept_tracks = []
            for tid, bbox in sorted_tracks:
                if tid < 0:
                    continue
                is_duplicate = any(self._is_double_box_glitch(bbox, kb) for _, kb in kept_tracks)
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
                    person.expires_at = getattr(person.face_info, "expires_at", None)
                    person.starts_at = getattr(person.face_info, "starts_at", None)
                    if getattr(person.face_info, "matched_code", None):
                        person.identity_verified_at = getattr(person.face_info, "matched_at", None) or current_time

                # Kế thừa danh tính và trạng thái cảnh báo nếu track mới đè lên vị trí track cũ (ByteTrack switch ID)
                detected_tids = {tid for tid, _ in detected_tracks}
                parent_match = self.find_matching_parent_track(
                    bbox=bbox,
                    current_time=current_time,
                    exclude_tids=detected_tids,
                    user_code=person.face_info.matched_code if (person.face_detected and person.face_info and getattr(person.face_info, "matched_code", None)) else None,
                    frame_size=frame_size
                )
                best_match_tid = parent_match[0] if parent_match else None

                if best_match_tid is not None:
                    old_p = self.active_tracks[best_match_tid]
                    both_identified = (
                        person.face_detected and person.face_info and person.face_info.matched_code and
                        old_p.face_detected and old_p.face_info and old_p.face_info.matched_code
                    )
                    different_persons = bool(both_identified and (person.face_info.matched_code != old_p.face_info.matched_code))
                    identity_safe = self.parent_identity_is_safe(
                        bbox, old_p,
                        visible_count=sum(1 for tid, _ in detected_tracks if tid >= 0),
                        user_code=person.face_info.matched_code if (person.face_detected and person.face_info) else None
                    )

                    if not different_persons:
                        # Kế thừa nhận diện khuôn mặt (chỉ khi track cha đủ tin cậy về mặt không gian)
                        if identity_safe and old_p.face_detected and old_p.face_info and not person.face_detected:
                            self._copy_identity_as_inherited(person, old_p)
                        if identity_safe:
                            # Kế thừa thông tin ROI và lịch sử cảnh báo (tránh spam sự cố lặp lại khi cùng một người)
                            person.is_in_roi = old_p.is_in_roi
                            person.was_in_roi = getattr(old_p, 'was_in_roi', False) or getattr(old_p, 'is_in_roi', False)
                            person.roi_enter_time = old_p.roi_enter_time
                            person.entered_roi_label = old_p.entered_roi_label
                            person.roi_entry_logged = old_p.roi_entry_logged
                            person.alert_unauthorized_sent = old_p.alert_unauthorized_sent
                            person.alert_unknown_sent = old_p.alert_unknown_sent
                            person.alert_after_hours_sent = old_p.alert_after_hours_sent
                            person.unknown_verdict_time = getattr(old_p, "unknown_verdict_time", None)
                            person.unknown_verdict_session_id = getattr(old_p, "unknown_verdict_session_id", None)
                            person.identity_update_sent = getattr(old_p, "identity_update_sent", False)
                            person.entry_logged_as_unknown = getattr(old_p, "entry_logged_as_unknown", False)
                            person.first_seen_time = old_p.first_seen_time
                            person.has_entered_roi = getattr(old_p, "has_entered_roi", False)
                            person.roi_first_entered_time = getattr(old_p, "roi_first_entered_time", None)
                            old_state = getattr(old_p, "security_display_state", "NEUTRAL")
                            # Không kế thừa danh tính thì cũng không kế thừa trạng thái "có quyền" của người kia
                            if old_state != "AUTHORIZED" or (person.face_detected and person.face_info and person.face_info.is_authorized):
                                person.security_display_state = old_state
                            if best_match_tid in self.track_last_unknown_alert_time:
                                self.track_last_unknown_alert_time[track_id] = self.track_last_unknown_alert_time.pop(best_match_tid)
                            if best_match_tid in self.track_last_unauthorized_alert_time:
                                self.track_last_unauthorized_alert_time[track_id] = self.track_last_unauthorized_alert_time.pop(best_match_tid)
                        else:
                            # G3.1: Khi không an toàn về danh tính (identity_safe = False), track mới là đối tượng riêng biệt:
                            # Bắt buộc reset toàn bộ cờ cảnh báo và cooldown để không làm tắt cảnh báo của đối tượng mới
                            person.alert_unknown_sent = False
                            person.alert_unauthorized_sent = False
                            person.alert_after_hours_sent = False
                            person.unknown_verdict_time = None
                            person.unknown_verdict_session_id = None
                            person.identity_update_sent = False
                            person.entry_logged_as_unknown = False
                            person.roi_entry_logged = False
                            self.track_last_unknown_alert_time.pop(track_id, None)
                            self.track_last_unauthorized_alert_time.pop(track_id, None)
                            self.track_last_unknown_alert_time.pop(best_match_tid, None)
                            self.track_last_unauthorized_alert_time.pop(best_match_tid, None)

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
                    person.expires_at = getattr(person.face_info, "expires_at", None)
                    person.starts_at = getattr(person.face_info, "starts_at", None)
                    if getattr(person.face_info, "matched_code", None):
                        person.identity_verified_at = getattr(person.face_info, "matched_at", None) or current_time

            # Kiểm tra nằm trong Surveillance Polygons
            in_roi = False
            matched_label = ""

            if active_pixel_polys:
                bc = person.bbox.bottom_center
                for poly_pts, lbl in active_pixel_polys:
                    if is_point_in_polygon(bc, poly_pts):
                        in_roi = True
                        if not matched_label:
                            matched_label = lbl
            else:
                in_roi = True

            # Theo dõi chuyển trạng thái ra vào vùng ROI
            if in_roi:
                person.has_entered_roi = True
                if person.roi_first_entered_time is None:
                    person.roi_first_entered_time = current_time

            if in_roi and not person.was_in_roi:
                person.roi_enter_time = current_time
                person.entered_roi_label = matched_label or "Vùng giám sát"
                person.roi_entry_logged = False
                # Nếu đã hết cooldown thì cho phép cảnh báo lại khi tái xâm nhập
                last_unauth_t = self.track_last_unauthorized_alert_time.get(person.track_id, 0.0)
                if (current_time - last_unauth_t) >= self.unauthorized_alert_cooldown:
                    person.alert_unauthorized_sent = False
                logger.info(
                    f"📍 [FrameAnalysisEngine:{camera_code}] Track #{person.track_id} BƯỚC VÀO VÙNG ROI: '{person.entered_roi_label}'"
                )
            elif not in_roi and person.was_in_roi:
                roi_enter_t = person.roi_enter_time if person.roi_enter_time is not None else current_time
                duration = max(0.0, current_time - roi_enter_t)
                exit_label = person.entered_roi_label or "Vùng giám sát"
                exit_code = person.face_info.matched_code if (person.face_detected and person.face_info and person.face_info.matched_code) else "UNKNOWN"
                # Không ghi log và không tạo sự kiện ra vào khi rời khỏi ROI
                # Early Exit Fail-Secure: Kẻ gian chạy vội qua ROI vào phòng khi chưa kịp xác thực danh tính
                # Chống flapping mép ROI: Phải ở trong ROI tối thiểu 0.2s để tránh rung giật 1 frame khi vừa chạm chân
                if getattr(person, "security_display_state", "NEUTRAL") == "VERIFYING" and exit_code == "UNKNOWN" and duration >= ROI_MIN_DWELL_SECONDS:
                    should_alert = (not is_public_area) or after_hours
                    if should_alert:
                        self._emit_identityless_alert(
                            person, camera_code, area_name, exit_label, after_hours, current_time,
                            generated_alerts, "Người lạ trong khu vực"
                        )

                person.roi_enter_time = None
                person.entered_roi_label = None
                person.roi_entry_logged = False
                person.entry_logged_as_unknown = False
                # Chống flapping mép ROI: Không reset cờ alert_unauthorized_sent ngay khi vừa rời ROI
                # Cờ này được duy trì và chỉ reset khi hết cooldown thời gian hoặc track bị hủy.
                if getattr(person, "security_display_state", "NEUTRAL") not in ("UNKNOWN", "INTRUSION", "UNAUTHORIZED"):
                    person.alert_unknown_sent = False

            person.is_in_roi = in_roi
            person.was_in_roi = in_roi

            if person.is_in_roi:
                if not person.roi_entry_logged:
                    entry_code = person.face_info.matched_code if (person.face_detected and person.face_info and person.face_info.matched_code) else None
                    roi_start = person.roi_enter_time if person.roi_enter_time is not None else (person.roi_first_entered_time if getattr(person, "roi_first_entered_time", None) is not None else current_time)
                    if entry_code:
                        roi_access_events.append({
                            "user_code": entry_code,
                            "roi_label": person.entered_roi_label or matched_label or "Vùng giám sát",
                            "direction": "ENTRY",
                            "duration": 0.0
                        })
                        person.roi_entry_logged = True
                    elif (current_time - roi_start) >= self.access_grace_period and not (
                        # Đang giữ (hold) chờ khớp mặt: chưa ghi ENTRY "UNKNOWN", khớp được thì ghi đúng mã người
                        (current_time - roi_start) < self.access_grace_period + self.identity_hold_seconds
                        and self._has_usable_face_since(person, roi_start)
                    ):
                        roi_access_events.append({
                            "user_code": "UNKNOWN",
                            "roi_label": person.entered_roi_label or matched_label or "Vùng giám sát",
                            "direction": "ENTRY",
                            "duration": 0.0
                        })
                        person.roi_entry_logged = True
                        person.entry_logged_as_unknown = True
                elif person.entry_logged_as_unknown and person.face_detected and person.face_info and person.face_info.matched_code:
                    # Đã ghi ENTRY "UNKNOWN" rồi mới nhận ra mặt: ghi bổ sung ENTRY mang mã người thật (EXIT sau này cũng
                    # ghi mã người thật). Bản ghi UNKNOWN cũ giữ nguyên trong nhật ký ra vào.
                    roi_access_events.append({
                        "user_code": person.face_info.matched_code,
                        "roi_label": person.entered_roi_label or matched_label or "Vùng giám sát",
                        "direction": "ENTRY",
                        "duration": 0.0
                    })
                    person.entry_logged_as_unknown = False

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

                    deny_reason_code = getattr(person.face_info, "access_deny_reason", None)

                    # Mốc starts_at / expires_at: chỉ hỏi lại backend; kết quả về thì các nhánh bên dưới tự xét màu và
                    # cảnh báo theo face_info.is_authorized (mất quyền -> cooldown đã được xoá để cảnh báo phát ngay).
                    self._check_access_window_timers(
                        person, current_time, person.entered_roi_label or matched_label or "Vùng giám sát", roi_access_events
                    )
                    self._consume_window_recheck_result(person, after_hours, camera_code, current_time)

                    # Luật ban đêm (BR-294, Lucas chốt P4-a): chỉ xét miễn trừ khi Backend ĐÃ CHO PHÉP (is_authorized=True).
                    is_night_authorized = self._check_is_night_authorized(person, after_hours, camera_code, current_time)

                    if is_night_authorized:
                        # Người có thẩm quyền hợp lệ: Không sinh sự cố vi phạm (AUTHORIZED_PERSON chỉ ghi lịch sử ra vào)
                        self._set_security_display_state(person, "AUTHORIZED", camera_code, "Có thẩm quyền hợp lệ")
                        # Đã lỡ chốt cảnh báo người lạ trước khi nhận ra mặt -> báo Backend tự đóng sự cố đó
                        self._emit_authorized_identity_update(person, camera_code, generated_alerts, loc_info)
                    elif is_public_area and not after_hours:
                        # Khu vực Cấp 1 (Công khai - PUBLIC): Ban ngày tất cả mọi người vẫn vào bình thường -> KHÔNG cảnh báo UNAUTHORIZED_PERSON!
                        self._set_security_display_state(person, "AUTHORIZED", camera_code, "Có thẩm quyền hợp lệ")
                    elif not getattr(person.face_info, "access_checked", False) and (
                        (current_time - (person.roi_enter_time if person.roi_enter_time is not None else current_time)) < self.access_grace_period
                        or self._within_identity_grace(person, current_time)
                    ):
                        # BUG-02: Grace period cho người đã xác định danh tính nhưng access_checked == False
                        # để cho Backend phản hồi kiểm tra quyền truy cập, tránh bắn cảnh báo vi phạm giả.
                        self._set_security_display_state(person, "VERIFYING", camera_code, "Đang xác thực trong ROI")
                    else:
                        if after_hours:
                            self._set_security_display_state(person, "INTRUSION", camera_code, "Đột nhập ngoài giờ")
                        else:
                            self._set_security_display_state(person, "UNAUTHORIZED", camera_code, "Từ chối truy cập / Không có quyền")

                        # Vi phạm: Phân loại chuẩn theo 5 nhóm (PERSON_INTRUSION, UNAUTHORIZED_PERSON)
                        last_unauth_track_ts = self.track_last_unauthorized_alert_time.get(person.track_id)
                        last_unauth_user_ts = self.user_last_unauthorized_alert_time.get(matched_code) if matched_code else None

                        track_unauth_cooldown_ok = (last_unauth_track_ts is None) or ((current_time - last_unauth_track_ts) >= self.unauthorized_alert_cooldown)
                        user_unauth_cooldown_ok = (last_unauth_user_ts is None) or ((current_time - last_unauth_user_ts) >= self.unauthorized_alert_cooldown)

                        should_alert_unauth = (not person.alert_unauthorized_sent) if (last_unauth_track_ts is None and last_unauth_user_ts is None) else (track_unauth_cooldown_ok and user_unauth_cooldown_ok)
                        # Đã chốt cảnh báo người lạ cho người này -> không phát cảnh báo thứ hai, gửi IDENTITY_UPDATE
                        absorb_now = should_alert_unauth and self._unknown_verdict_active(person, current_time)
                        if should_alert_unauth:
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
                                if deny_reason_code:
                                    # L4: khách bị từ chối -> lý do theo mã backend trả
                                    minimal_reason = GUEST_DENY_REASON_LABELS.get(deny_reason_code, "Không có quyền")
                                elif not is_active:
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

                            if absorb_now:
                                self._absorb_late_identity(person, matched_code, camera_code)
                                self._emit_identity_update(person, "UNAUTHORIZED", camera_code, generated_alerts, detail_msg, loc_info)
                        if should_alert_unauth and not absorb_now:
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
                            self.track_last_unauthorized_alert_time[person.track_id] = current_time
                            if matched_code:
                                self.user_last_unauthorized_alert_time[matched_code] = current_time
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
                        # Quy định an ninh:
                        # - Khu vực thông thường (is_public_area=False): Bất kỳ người lạ nào cũng bị cảnh báo UNKNOWN_PERSON (hoặc PERSON_INTRUSION ngoài giờ).
                        # - Ngoài giờ (after_hours=True): Mọi đối tượng lạ hoặc không được duyệt đều là PERSON_INTRUSION.
                        # - Khu vực công cộng (is_public_area=True) ban ngày: Cho phép người lạ tự do đi lại (0 cảnh báo UNKNOWN_PERSON).
                        should_alert_stranger = (not is_public_area) or after_hours
                        if should_alert_stranger:
                            roi_start = person.roi_enter_time if person.roi_enter_time is not None else (person.roi_first_entered_time if getattr(person, "roi_first_entered_time", None) is not None else current_time)
                            elapsed_in_roi = current_time - roi_start
                            is_identity_resolved = person.face_detected and person.face_info and bool(getattr(person.face_info, "matched_code", None))
                            is_access_checked = person.face_detected and person.face_info and getattr(person.face_info, "access_checked", False)

                            # Trong thời gian đệm (access_grace_period), nếu danh tính chưa xác thực (chưa có matched_code),
                            # hệ thống PHẢI giữ trạng thái VERIFYING (Vàng) để cho Face Detector & ArcFace quét và so khớp khuôn mặt!
                            # Tuyệt đối KHÔNG được bắn cảnh báo UNKNOWN_PERSON sớm khi người vừa bước chân chạm vào ROI!
                            if not is_identity_resolved and elapsed_in_roi < self.access_grace_period:
                                if not is_access_checked:
                                    self._set_security_display_state(person, "VERIFYING", camera_code, "Đang xác thực trong ROI")
                                    continue

                            # Hold buffer: hết grace mà ĐANG thấy mặt đủ chất lượng (chưa khớp) -> chờ thêm tối đa
                            # identity_hold_seconds để mặt rõ hơn, tránh chốt UNKNOWN rồi 1s sau lại cảnh báo theo danh tính.
                            # Không thấy mặt nào dùng được (quay lưng, che mặt) -> chốt UNKNOWN ngay khi hết grace.
                            # Rời ROI trong lúc giữ -> nhánh Early Exit phát UNKNOWN ngay (trạng thái vẫn là VERIFYING).
                            if (
                                not is_identity_resolved
                                and not is_access_checked
                                and elapsed_in_roi < self.access_grace_period + self.identity_hold_seconds
                                and self._has_usable_face_since(person, roi_start)
                            ):
                                self._set_security_display_state(person, "VERIFYING", camera_code, "Đang chờ khớp khuôn mặt")
                                continue

                            last_camera_ts = self.last_unknown_alert_time.get(camera_code)
                            camera_interval_ok = (last_camera_ts is None) or ((current_time - last_camera_ts) >= self.camera_unknown_min_interval)
                            if camera_interval_ok:
                                is_identity_resolved = person.face_detected and person.face_info and bool(getattr(person.face_info, "matched_code", None))
                                if after_hours:
                                    self._set_security_display_state(person, "INTRUSION", camera_code, "Đột nhập ngoài giờ")
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
                                alert.roi_session_id = self._start_unknown_verdict(person, current_time)
                                self.last_unknown_alert_time[camera_code] = current_time
                                self.track_last_unknown_alert_time[person.track_id] = current_time
                                if after_hours:
                                    person.alert_after_hours_sent = True
                                else:
                                    self._set_security_display_state(person, "UNKNOWN", camera_code, "Người lạ trong vùng ROI")

            if not person.is_in_roi:
                if not getattr(person, "has_entered_roi", False):
                    self._set_security_display_state(person, "NEUTRAL", camera_code)
                else:
                    # Đã từng vào ROI và nay bước ra ngoài vào phòng (Sticky Security State):
                    # Mốc starts_at / expires_at khi người đang ở trong phòng sau khi qua ROI: chỉ hỏi lại backend.
                    # Kết quả backend khác trạng thái đang hiển thị -> đưa về VERIFYING để khối Late Identity Upgrade
                    # bên dưới xét lại ngay trong frame này (có quyền -> AUTHORIZED; mất quyền -> UNAUTHORIZED/INTRUSION + cảnh báo
                    # với lý do backend trả).
                    self._check_access_window_timers(
                        person, current_time, person.entered_roi_label or "Vùng giám sát", roi_access_events
                    )
                    if self._consume_window_recheck_result(person, after_hours, camera_code, current_time):
                        self._set_security_display_state(person, "VERIFYING", camera_code, "Kết quả xét lại quyền theo mốc thời gian")

                    # 1. Late Identity Upgrade: CHỈ nâng cấp nếu trước đó là UNKNOWN, VERIFYING, hoặc INTRUSION (người lạ ngoài giờ),
                    # VÀ danh tính mới được xác thực CÓ QUYỀN (is_night_authorized == True và access_checked == True)
                    if getattr(person, "security_display_state", "NEUTRAL") in ("UNKNOWN", "VERIFYING", "INTRUSION"):
                        if person.face_detected and person.face_info and getattr(person.face_info, "matched_code", None):
                            if getattr(person.face_info, "access_checked", False):
                                is_night_authorized = self._check_is_night_authorized(person, after_hours, camera_code, current_time)
                                if is_night_authorized:
                                    self._set_security_display_state(person, "AUTHORIZED", camera_code, "Có thẩm quyền hợp lệ")
                                    person.alert_unauthorized_sent = False
                                    person.alert_unknown_sent = False
                                    self._emit_authorized_identity_update(
                                        person, camera_code, generated_alerts, {"label": person.entered_roi_label or "Vùng giám sát"}
                                    )
                                else:
                                    matched_code = person.face_info.matched_code
                                    matched_name = getattr(person.face_info, "matched_name", matched_code)
                                    reason_str = getattr(person.face_info, "access_reason", None) or "Không có quyền ra vào khu vực này."
                                    clean_reason = re.sub(r"\[[A-Z0-9_:\s]+\]\s*", "", reason_str or "").strip()
                                    target_area_name = getattr(person.face_info, "area_name", None)
                                    raw_loc = target_area_name or area_name or "Khu vực giám sát"
                                    location_display = re.sub(r"\s*\([^)]*(?:vùng|roi)[^)]*\)", "", raw_loc, flags=re.IGNORECASE).strip() or raw_loc

                                    if after_hours:
                                        self._set_security_display_state(person, "INTRUSION", camera_code, "Đột nhập ngoài giờ")
                                        event_type = "PERSON_INTRUSION"
                                        detail_msg = f"[PERSON_INTRUSION] Tên: {matched_name} ({matched_code})\nLý do: Ngoài giờ hoạt động\nVị trí: {location_display}"
                                    else:
                                        self._set_security_display_state(person, "UNAUTHORIZED", camera_code, "Từ chối truy cập / Không có quyền")
                                        event_type = "UNAUTHORIZED_PERSON"
                                        r_low = (clean_reason or "").lower()
                                        deny_reason_code = getattr(person.face_info, "access_deny_reason", None)
                                        is_active = getattr(person.face_info, "is_active", True)
                                        if deny_reason_code:
                                            minimal_reason = GUEST_DENY_REASON_LABELS.get(deny_reason_code, "Không có quyền")
                                        elif not is_active:
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
                                            minimal_reason = clean_reason or "Không có quyền"
                                        detail_msg = f"[UNAUTHORIZED_PERSON] Tên: {matched_name} ({matched_code})\nLý do: {minimal_reason}\nVị trí: {location_display}"

                                    last_unauth_track_ts = self.track_last_unauthorized_alert_time.get(person.track_id)
                                    last_unauth_user_ts = self.user_last_unauthorized_alert_time.get(matched_code) if matched_code else None
                                    track_unauth_cooldown_ok = (last_unauth_track_ts is None) or ((current_time - last_unauth_track_ts) >= self.unauthorized_alert_cooldown)
                                    user_unauth_cooldown_ok = (last_unauth_user_ts is None) or ((current_time - last_unauth_user_ts) >= self.unauthorized_alert_cooldown)
                                    should_alert_unauth = (not person.alert_unauthorized_sent) if (last_unauth_track_ts is None and last_unauth_user_ts is None) else (track_unauth_cooldown_ok and user_unauth_cooldown_ok)

                                    late_absorb = should_alert_unauth and self._unknown_verdict_active(person, current_time)
                                    if should_alert_unauth:
                                        if late_absorb:
                                            # Đã chốt cảnh báo người lạ -> không phát cảnh báo thứ hai, gửi IDENTITY_UPDATE
                                            self._absorb_late_identity(person, matched_code, camera_code)
                                            self._emit_identity_update(
                                                person, "UNAUTHORIZED", camera_code, generated_alerts, detail_msg,
                                                {"label": person.entered_roi_label or "Vùng giám sát"}
                                            )
                                        else:
                                            alert = SecurityAlertEvent(
                                                camera_code=camera_code,
                                                event_type=event_type,
                                                track_id=person.track_id,
                                                confidence=person.bbox.confidence,
                                                details=detail_msg,
                                                location={"label": person.entered_roi_label or "Vùng giám sát"}
                                            )
                                            generated_alerts.append(alert)
                                            logger.warning(
                                                f"🚨 [FrameAnalysisEngine:{camera_code}] VI PHẠM AN NINH (Cập nhật danh tính): [{event_type}] "
                                                f"Người: {matched_name} ({matched_code}), Lý do: {'Ngoài giờ hoạt động' if after_hours else minimal_reason}, Vị trí: {location_display}"
                                            )
                                        person.alert_unauthorized_sent = True
                                        self.track_last_unauthorized_alert_time[person.track_id] = current_time
                                        if matched_code:
                                            self.user_last_unauthorized_alert_time[matched_code] = current_time
                                        if after_hours:
                                            person.alert_after_hours_sent = True
                    # 2. Nếu đã là UNAUTHORIZED hoặc INTRUSION: Chốt khóa giữ nguyên trạng thái ĐỎ (Sticky State),
                    # tuyệt đối KHÔNG bao giờ tự động chuyển thành AUTHORIZED!

        self._contest_identities_on_overlap(filtered_detected_tracks, camera_code)

        inactive_ids = []
        detected_tids_all = {tid for tid, _ in detected_tracks}
        visible_bboxes = [b for vis_tid, b in filtered_detected_tracks if vis_tid >= 0]
        for tid, p in self.active_tracks.items():
            if tid not in current_frame_track_ids:
                inactive_sec = current_time - p.last_seen_time
                # Cho phép duy trì hiển thị bounding box (coasting 1.5s) khi người tạm thời cúi người,
                # ngồi xuống hoặc bị khuất tầm nhìn, tránh việc biến mất và mất Track ID đột ngột
                if inactive_sec > TRACK_COASTING_SECONDS:
                    p.is_currently_visible = False
                    # Fail-secure: người chưa xác thực biến mất NGAY TRONG ROI (đi thẳng qua cửa nằm trong ROI, điểm chân
                    # không bao giờ ra khỏi ROI nên nhánh Early Exit không chạy) -> chốt cảnh báo người lạ.
                    if tid not in detected_tids_all and self._vanished_unverified_in_roi(p, visible_bboxes) and ((not is_public_area) or after_hours):
                        logger.info(
                            f"🫥 [FrameAnalysisEngine:{camera_code}] Track #{tid} biến mất trong ROI khi chưa xác thực "
                            f"({inactive_sec:.1f}s không thấy) -> chốt cảnh báo người lạ"
                        )
                        self._emit_identityless_alert(
                            p, camera_code, area_name, p.entered_roi_label or "Vùng giám sát", after_hours, current_time,
                            generated_alerts, "Người lạ biến mất trong khu vực"
                        )
                if inactive_sec > self.max_inactive_seconds:
                    inactive_ids.append(tid)
                    logger.info(f"👋 [FrameAnalysisEngine:{camera_code}] Track #{tid} rời khỏi tầm nhìn camera")

        for tid in inactive_ids:
            del self.active_tracks[tid]
            if tid in self.pending_faces:
                del self.pending_faces[tid]
            self.track_last_unknown_alert_time.pop(tid, None)
            self.track_last_unauthorized_alert_time.pop(tid, None)

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
                            # Track đang thấy có thể là NGƯỜI KHÁC đứng trước người vừa bị che (pa) -> chỉ kế thừa,
                            # để pb còn được quét lại mặt và gỡ danh tính nếu không khớp.
                            self._copy_identity_as_inherited(pb, pa)
                        pb.has_entered_roi = pb.has_entered_roi or getattr(pa, "has_entered_roi", False)
                        pb.roi_first_entered_time = pb.roi_first_entered_time if pb.roi_first_entered_time is not None else getattr(pa, "roi_first_entered_time", None)
                        if getattr(pa, "security_display_state", "NEUTRAL") != "NEUTRAL":
                            if pa.security_display_state == "AUTHORIZED":
                                # G3.3: Tuyệt đối không cho track mới mượn cờ xanh AUTHORIZED qua Coasting NMS
                                # trừ khi chính track mới đã được đối soát khuôn mặt độc lập và có quyền hợp lệ.
                                if pb.face_detected and pb.face_info and pb.face_info.is_authorized and not getattr(pb, "is_inherited_identity", False):
                                    pb.security_display_state = "AUTHORIZED"
                                elif pb.security_display_state != "AUTHORIZED":
                                    if pb.is_in_roi or getattr(pa, "is_in_roi", False):
                                        pb.security_display_state = "VERIFYING"
                            else:
                                pb.security_display_state = pa.security_display_state
                        pb.alert_unauthorized_sent = pb.alert_unauthorized_sent or getattr(pa, "alert_unauthorized_sent", False)
                        pb.alert_unknown_sent = pb.alert_unknown_sent or getattr(pa, "alert_unknown_sent", False)
                        pb.alert_after_hours_sent = pb.alert_after_hours_sent or getattr(pa, "alert_after_hours_sent", False)
                        pa.is_currently_visible = False
                        suppressed_tids.add(tid_a)
                        break
                    elif a_in_curr and not b_in_curr:
                        if b_has_face and not a_has_face:
                            self._copy_identity_as_inherited(pa, pb)
                        pa.has_entered_roi = pa.has_entered_roi or getattr(pb, "has_entered_roi", False)
                        pa.roi_first_entered_time = pa.roi_first_entered_time if pa.roi_first_entered_time is not None else getattr(pb, "roi_first_entered_time", None)
                        if getattr(pb, "security_display_state", "NEUTRAL") != "NEUTRAL":
                            if pb.security_display_state == "AUTHORIZED":
                                # G3.3: Tuyệt đối không cho track mới mượn cờ xanh AUTHORIZED qua Coasting NMS
                                # trừ khi chính track mới đã được đối soát khuôn mặt độc lập và có quyền hợp lệ.
                                if pa.face_detected and pa.face_info and pa.face_info.is_authorized and not getattr(pa, "is_inherited_identity", False):
                                    pa.security_display_state = "AUTHORIZED"
                                elif pa.security_display_state != "AUTHORIZED":
                                    if pa.is_in_roi or getattr(pb, "is_in_roi", False):
                                        pa.security_display_state = "VERIFYING"
                            else:
                                pa.security_display_state = pb.security_display_state
                        pa.alert_unauthorized_sent = pa.alert_unauthorized_sent or getattr(pb, "alert_unauthorized_sent", False)
                        pa.alert_unknown_sent = pa.alert_unknown_sent or getattr(pb, "alert_unknown_sent", False)
                        pa.alert_after_hours_sent = pa.alert_after_hours_sent or getattr(pb, "alert_after_hours_sent", False)
                        pb.is_currently_visible = False
                        suppressed_tids.add(tid_b)

        return list(self.active_tracks.values()), generated_alerts, roi_access_events

    def associate_face(self, track_id: int, face_result: FaceDetectionResult):
        if face_result is None:
            return
        if track_id in self.active_tracks:
            curr_p = self.active_tracks[track_id]
            # Nếu track đã có danh tính rõ ràng (matched_code) mà face_result mới không có matched_code (mặt mờ hoặc trượt),
            # tuyệt đối không ghi đè xóa mất danh tính đã nhận diện của họ!
            if curr_p.face_detected and curr_p.face_info and curr_p.face_info.matched_code and not getattr(face_result, "matched_code", None):
                return
            self.active_tracks[track_id].face_detected = True
            self.active_tracks[track_id].face_info = face_result
            self.active_tracks[track_id].expires_at = getattr(face_result, "expires_at", None)
            self.active_tracks[track_id].starts_at = getattr(face_result, "starts_at", None)
            # Mặt khớp mã người quét trên chính track này: danh tính đã được xác nhận tại thời điểm khớp
            curr_p.identity_verified_at = (
                (getattr(face_result, "matched_at", None) or time.time())
                if getattr(face_result, "matched_code", None) else None
            )
        else:
            self.pending_faces[track_id] = face_result
