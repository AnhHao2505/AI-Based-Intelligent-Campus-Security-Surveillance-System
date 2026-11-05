import threading
import time
import logging
import cv2
import numpy as np
from typing import List, Optional, Tuple, Any

from ..config import settings
from ..core.entity import Point, TrackedPerson, SecurityAlertEvent, RoiPolygonConfig, BoundingBox, MotionResult
from ..core.motion_detector import MotionDetector
from ..core.zone_state_machine import ZoneStateMachine, ZoneState
from ..core.human_detector import HumanDetector
from ..core.face_detector import FaceDetector
from ..core.face_matcher import FaceMatcher
from ..core.frame_analysis_engine import FrameAnalysisEngine
from ..integration.mqtt_client import get_security_mqtt_client
from ..integration.storage_service import StorageService
from ..utils.visualizer import FrameVisualizer
from ..utils.geometry import is_point_in_polygon, scale_polygon_to_frame, normalize_polygon_to_unit
from .incident_recorder import incident_recording_manager

logger = logging.getLogger(__name__)

class VideoPipeline:
    """
    Pipeline tích hợp toàn diện quy trình phân tích luồng Video:
    1. Tiền xử lý chuyển động & Chống phá hoại (OpenCV MOG2 MotionDetector)
    2. Cổng lọc tải & Máy trạng thái chốt giữ (ZoneStateMachine Latch)
    3. Human Detection & Tracking (YOLO26 + ByteTrack)
    4. Đối soát ngụy trang / Xâm nhập bất thường (SUSPICIOUS_MOVING_OBJECT)
    5. Crop vùng người & Face Detection (YuNet)
    6. Face Recognition Matching (pgvector Cosine Search)
    7. Violation Analysis (ROI Polygon Check)
    8. Cảnh báo tự động (MTQQ Event + MinIO Snapshot Upload)
    9. Visualization & Overlay Rendering
    """
    def __init__(
        self,
        camera_code: str,
        roi_polygon: Optional[List[Point]] = None,
        conf_threshold: Optional[float] = None,
        model_yolo_path: Optional[str] = None,
        model_yunet_path: Optional[str] = None,
        roi_polygons: Optional[List[Any]] = None,
        after_hour_start: Optional[str] = None,
        after_hour_end: Optional[str] = None,
        enable_motion_gating: bool = True,
        max_motion_track_hold_seconds: Optional[float] = None,
        analysis_max_fps: Optional[float] = None
    ):
        self.analysis_max_fps = analysis_max_fps if analysis_max_fps is not None else getattr(settings, "ANALYSIS_MAX_FPS", 10.0)
        self.camera_code = camera_code
        self.roi_polygon = roi_polygon or []
        self.roi_polygons: List[RoiPolygonConfig] = []

        if roi_polygons:
            self.set_roi_config(roi_polygons)
        elif self.roi_polygon:
            self.set_roi_polygon(self.roi_polygon)

        # Khởi tạo các module core
        yolo_path = model_yolo_path or settings.MODEL_YOLO_PATH
        yunet_path = model_yunet_path or settings.MODEL_YUNET_PATH
        detector_conf = conf_threshold if conf_threshold is not None else settings.DEFAULT_DETECTION_CONFIDENCE
        ah_start = after_hour_start or settings.DEFAULT_AFTER_HOUR_START
        ah_end = after_hour_end or settings.DEFAULT_AFTER_HOUR_END

        logger.info(f"Khởi tạo VideoPipeline cho Camera [{camera_code}]...")
        self.human_detector = HumanDetector(model_path=yolo_path, conf_threshold=detector_conf, analysis_fps=self.analysis_max_fps)
        self.face_detector = FaceDetector(model_path=yunet_path, score_threshold=settings.DEFAULT_FACE_CONFIDENCE)
        self.face_matcher = FaceMatcher()
        self.analysis_engine = FrameAnalysisEngine(after_hour_start=ah_start, after_hour_end=ah_end)

        # Khởi tạo module Motion Detector & State Machine
        self.enable_motion_gating = enable_motion_gating
        self.max_motion_track_hold_seconds = max_motion_track_hold_seconds if max_motion_track_hold_seconds is not None else getattr(settings, 'MAX_MOTION_TRACK_HOLD_SECONDS', 3.0)
        self.motion_detector = MotionDetector(
            history=500,
            var_threshold=25.0,
            min_motion_area=getattr(settings, "MIN_MOTION_AREA", 3000),
            lighting_spike_ratio=0.70,
            tampering_pixel_val=245,
            tampering_area_ratio=0.05,
            tampering_duration_threshold=1.5
        )
        self.state_machine = ZoneStateMachine(
            cooldown_seconds=5.0,
            motion_to_occupied_seconds=1.0
        )

        # Cấu hình tối ưu nhận diện khuôn mặt cho track chưa định danh (P0-02)
        self.face_scan_throttling_enabled = getattr(settings, "FACE_SCAN_THROTTLING_ENABLED", True)
        self.face_scan_initial_interval = getattr(settings, "FACE_SCAN_INITIAL_INTERVAL", 0.4)
        self.face_scan_initial_window = getattr(settings, "FACE_SCAN_INITIAL_WINDOW_SECONDS", 2.0)
        self.face_scan_backoff_interval = getattr(settings, "FACE_SCAN_BACKOFF_INTERVAL", 1.0)
        self.face_min_crop_size = getattr(settings, "FACE_MIN_CROP_SIZE", 30)
        self.face_min_laplacian_variance = getattr(settings, "FACE_MIN_LAPLACIAN_VARIANCE", 50.0)
        self._track_face_scan_times = {}
        self._last_suspicious_motion_time: float = 0.0
        self._suspicious_motion_cooldown: float = 5.0
        self._last_tampering_time: float = 0.0
        self._tampering_cooldown: float = 10.0
        self._pending_access_checks = set()
        self._pending_checks_lock = threading.Lock()
        self.heartbeat_interval_seconds: float = float(getattr(settings, "MOTION_GATING_HEARTBEAT_SECONDS", 4.0))

        # Khởi tạo các module tích hợp
        from concurrent.futures import ThreadPoolExecutor
        self.access_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix=f"access-{camera_code}")
        self.io_executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix=f"io-{camera_code}")
        self.executor = self.io_executor  # Backward compatibility
        self.mqtt_client = get_security_mqtt_client()
        self.kafka_producer = self.mqtt_client  # Backward compatibility
        self.storage_service = StorageService()
        self.visualizer = FrameVisualizer()
        self._last_heartbeat_time: Optional[float] = None

    def _apply_access_decision(self, face_info, is_allowed: bool, access_reason: str, area_name: Optional[str], access_source: Optional[str] = None):
        reason_lower = (access_reason or "").lower()
        if "không tồn tại" in reason_lower or "đã bị xoá" in reason_lower:
            face_info.matched_code = None
            face_info.matched_name = None
            face_info.is_authorized = False
            face_info.access_reason = "Đối tượng không xác định"
            face_info.access_source = None
        else:
            face_info.is_authorized = is_allowed
            face_info.access_reason = access_reason
            face_info.area_name = area_name
            face_info.access_source = access_source
        face_info.access_checked = True
        target_name = face_info.matched_name or face_info.matched_code or "Đối tượng"
        logger.info(
            f"🚪 [VideoPipeline:{self.camera_code}] [AccessDecision] Thẩm quyền của [{target_name}] tại [{area_name or self.camera_code}]: "
            f"{'✅ HỢP LỆ' if face_info.is_authorized else '⛔ TỪ CHỐI'} (Lý do: {face_info.access_reason}, Nguồn: {face_info.access_source or 'N/A'})"
        )

    def _handle_identity_switch(
        self,
        track_id: int,
        old_code: str,
        new_code: str,
        roi_label: str,
        need_exit: bool,
        need_entry: bool
    ):
        """
        Xử lý đổi danh tính từ old_code sang new_code tuần tự trong MỘT task duy nhất (POINT-08):
        1. Ghi EXIT(old_code) nếu need_exit=True
        2. Sau khi EXIT hoàn tất, ghi ENTRY(new_code) nếu need_entry=True
        Đảm bảo không bao giờ có 2 task chạy song song làm đảo lộn thứ tự EXIT/ENTRY.
        """
        if need_exit and self.face_matcher and old_code:
            try:
                logger.info(f"🔄 [Identity Switch] Ghi nhận EXIT tuần tự cho mã cũ: {old_code}")
                self.face_matcher.check_camera_access(
                    self.camera_code,
                    old_code,
                    roi_label=roi_label,
                    event_direction="EXIT",
                    skip_cache=True,
                )
            except Exception as e:
                logger.warning(f"[VideoPipeline] Lỗi khi ghi EXIT cho mã cũ {old_code}: {e}")

        if need_entry and self.face_matcher and new_code:
            try:
                logger.info(f"🔄 [Identity Switch] Ghi nhận ENTRY tuần tự cho mã mới: {new_code}")
                res = self.face_matcher.check_camera_access(
                    self.camera_code,
                    new_code,
                    roi_label=roi_label,
                    event_direction="ENTRY",
                    skip_cache=True,
                )
                is_allowed = res[0]
                access_reason = res[1]
                area_name = res[2]
                access_source = res[3] if len(res) >= 4 else None
                existing = self.analysis_engine.active_tracks.get(track_id)
                if existing and existing.face_info and existing.face_info.matched_code == new_code:
                    self._apply_access_decision(existing.face_info, is_allowed, access_reason, area_name, access_source)
                    existing.roi_entry_logged = True
            except Exception as e:
                logger.warning(f"[VideoPipeline] Lỗi khi ghi ENTRY cho mã mới {new_code}: {e}")

    def _async_resolve_camera_access(self, track_id: int, user_code: str, roi_label: str):
        try:
            res = self.face_matcher.check_camera_access(
                self.camera_code,
                user_code,
                roi_label=roi_label,
                event_direction=None
            )
            is_allowed = res[0]
            access_reason = res[1]
            area_name = res[2]
            access_source = res[3] if len(res) >= 4 else None
            existing = self.analysis_engine.active_tracks.get(track_id)
            if existing and existing.face_info and existing.face_info.matched_code == user_code:
                self._apply_access_decision(existing.face_info, is_allowed, access_reason, area_name, access_source)
                existing.roi_entry_logged = True
        except Exception as e:
            logger.warning(f"[VideoPipeline] Lỗi kiểm tra quyền bất đồng bộ cho user {user_code}: {e}")
        finally:
            with self._pending_checks_lock:
                self._pending_access_checks.discard(f"{self.camera_code}:{user_code}")

    def _resolve_access_for_person(self, track_id: int, face_info, user_code: str, roi_label: str):
        if not self.face_matcher:
            face_info.is_authorized = True
            face_info.access_checked = True
            return

        cached = self.face_matcher.get_cached_access(self.camera_code, user_code)
        if cached is not None:
            is_allowed = cached[0]
            access_reason = cached[1]
            area_name = cached[2]
            access_source = cached[3] if len(cached) >= 4 else None
            self._apply_access_decision(face_info, is_allowed, access_reason, area_name, access_source)
            existing = self.analysis_engine.active_tracks.get(track_id)
            if existing:
                existing.roi_entry_logged = True
            return

        cache_key = f"{self.camera_code}:{user_code}"
        should_submit = False
        with self._pending_checks_lock:
            if cache_key not in self._pending_access_checks:
                self._pending_access_checks.add(cache_key)
                should_submit = True
        if should_submit:
            if self.access_executor and not getattr(self.access_executor, "_shutdown", False):
                self.access_executor.submit(self._async_resolve_camera_access, track_id, user_code, roi_label)
        face_info.access_checked = False

    def set_roi_polygon(self, polygon: List[Point]):
        """Cập nhật tọa độ vùng cấm ROI (đơn lẻ, tương thích ngược)"""
        self.roi_polygon = polygon
        self.roi_polygons = [
            RoiPolygonConfig(
                label="Vùng ROI",
                alert_rules=["ENTRY_EXIT_TRACKING"],
                vertices=polygon
            )
        ]

    def set_analysis_max_fps(self, fps: float):
        """Cập nhật giới hạn FPS phân tích cho VideoPipeline (P0-03)"""
        self.analysis_max_fps = fps
        if hasattr(self, "human_detector") and self.human_detector:
            self.human_detector.set_analysis_fps(fps)

    def set_roi_config(self, polygons: Optional[List[Any]] = None):
        """Cập nhật danh sách đa polygon ROI đầy đủ thuộc tính chuẩn hóa"""
        configs: List[RoiPolygonConfig] = []
        if polygons:
            for p in polygons:
                if isinstance(p, RoiPolygonConfig):
                    configs.append(p)
                elif isinstance(p, dict):
                    raw_vertices = p.get("vertices", [])
                    ref_w = p.get("reference_width", 1920)
                    ref_h = p.get("reference_height", 1080)
                    pts = normalize_polygon_to_unit(raw_vertices, ref_w, ref_h)
                    configs.append(
                        RoiPolygonConfig(
                            label=p.get("label", ""),
                            alert_rules=p.get("alert_rules", ["ENTRY_EXIT_TRACKING"]),
                            target_area_id=p.get("target_area_id"),
                            vertices=pts
                        )
                    )
        self.roi_polygons = configs
        if configs:
            self.roi_polygon = configs[0].vertices
        else:
            self.roi_polygon = []

    @staticmethod
    def _is_box_overlapping_any(
        box: BoundingBox,
        targets: List[BoundingBox],
        overlap_threshold: float = 0.08,
        margin: float = 35.0
    ) -> bool:
        """
        Kiểm tra xem 1 box (ví dụ motion box) có nằm trong, trùng lặp hoặc sát cơ thể (margin) với bất kỳ target box (human box) nào không.
        Mở rộng margin 35-50px để bao quát bàn chân đang bước, cánh tay vung và bóng đổ của người.
        """
        if not targets:
            return False

        box_area = max(1.0, (box.x2 - box.x1) * (box.y2 - box.y1))
        cx = (box.x1 + box.x2) / 2.0
        cy = (box.y1 + box.y2) / 2.0

        for tgt in targets:
            exp_x1 = tgt.x1 - margin
            exp_y1 = tgt.y1 - margin
            exp_x2 = tgt.x2 + margin
            exp_y2 = tgt.y2 + margin + 15.0  # Thêm margin hướng xuống cho bước chân & bóng đổ sàn

            # 1. Nếu tâm của motion box nằm trong expanded human box
            if (exp_x1 <= cx <= exp_x2) and (exp_y1 <= cy <= exp_y2):
                return True

            # 2. Hoặc diện tích phần giao cắt chiếm >= overlap_threshold diện tích của motion box
            ix1 = max(box.x1, exp_x1)
            iy1 = max(box.y1, exp_y1)
            ix2 = min(box.x2, exp_x2)
            iy2 = min(box.y2, exp_y2)
            inter_w = max(0.0, ix2 - ix1)
            inter_h = max(0.0, iy2 - iy1)
            inter_area = inter_w * inter_h

            if (inter_area / box_area) >= overlap_threshold:
                return True

        return False

    def process_single_frame(
        self,
        frame: np.ndarray,
        current_time: Optional[float] = None
    ) -> Tuple[np.ndarray, List[TrackedPerson], List[SecurityAlertEvent]]:
        """
        Phân tích 1 ảnh tĩnh đơn lẻ (dùng cho API analyze-frame / snapshot) mà KHÔNG làm bẩn
        tracker state (ByteTrack) hay active_tracks của pipeline luồng video dùng chung.
        """
        if frame is None or frame.size == 0:
            return frame, [], []

        if current_time is None:
            current_time = time.time()

        h, w = frame.shape[:2]
        if hasattr(self.human_detector, "detect"):
            detected_tracks = self.human_detector.detect(frame)
        else:
            detected_tracks = self.human_detector.detect_and_track(frame, persist=False)

        temp_engine = FrameAnalysisEngine(
            after_hour_start=self.analysis_engine.after_hour_start,
            after_hour_end=self.analysis_engine.after_hour_end,
            max_inactive_seconds=self.analysis_engine.max_inactive_seconds,
            unknown_alert_cooldown=self.analysis_engine.unknown_alert_cooldown
        )

        for track_id, bbox in detected_tracks:
            x1, y1, x2, y2 = bbox.to_int_xyxy()
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(w, x2), min(h, y2)
            if (x2 - x1) > 20 and (y2 - y1) > 20:
                crop_h = int((y2 - y1) * settings.UPPER_BODY_CROP_RATIO)
                upper_crop = frame[y1:y1 + crop_h, x1:x2]
                if upper_crop.size > 0:
                    face_res = self.face_detector.detect_best_face_in_crop(upper_crop, offset_xy=(x1, y1))
                    if face_res:
                        fx1, fy1, fx2, fy2 = face_res.bbox.to_int_xyxy()
                        fx1, fy1 = max(0, fx1), max(0, fy1)
                        fx2, fy2 = min(w, fx2), min(h, fy2)
                        f_crop = frame[fy1:fy2, fx1:fx2]
                        if f_crop.size > 0:
                            lm = face_res.landmarks if getattr(face_res, "landmarks", None) else None
                            m = self.face_matcher.match_face(f_crop, landmarks=lm)
                            if m:
                                code, name, score, is_active, role = m
                                face_res.matched_code = code
                                face_res.matched_name = name
                                face_res.is_active = is_active
                                face_res.role = role
                                face_res.score = score
                                is_allowed, reason, area_name = self.face_matcher.check_camera_access(self.camera_code, code)
                                self._apply_access_decision(face_res, is_allowed, reason, area_name)
                            else:
                                is_allowed, reason, area_name = self.face_matcher.check_camera_access(self.camera_code, "UNKNOWN")
                                face_res.is_authorized = is_allowed
                                face_res.access_reason = reason
                                face_res.area_name = area_name
                                face_res.access_checked = True
                        temp_engine.associate_face(track_id, face_res)

        active_persons, alerts, _ = temp_engine.process_frame(
            detected_tracks=detected_tracks,
            roi_polygons=self.roi_polygons if self.roi_polygons else None,
            frame_size=(w, h),
            camera_code=self.camera_code,
            current_time=current_time
        )

        has_roi = bool(self.roi_polygons or self.roi_polygon)
        annotated_frame = FrameVisualizer.draw_all_rois(
            frame.copy(),
            polygons=self.roi_polygons if self.roi_polygons else (self.roi_polygon if self.roi_polygon else None)
        )
        annotated_frame = self.visualizer.draw_tracked_persons(
            annotated_frame,
            active_persons,
            only_in_roi=has_roi
        )
        return annotated_frame, active_persons, alerts

    def _is_face_blurry(self, face_crop: np.ndarray) -> bool:
        """
        Kiểm tra ảnh mặt có bị mờ (motion blur / defocus) qua phương sai Laplacian (P0-02).
        Trả về True nếu ảnh mờ và cần bỏ qua ArcFace.
        Bỏ qua kiểm tra nếu min_variance <= 0 hoặc nếu là ảnh synthetic mock (pixels đồng nhất).
        """
        if self.face_min_laplacian_variance <= 0.0 or face_crop is None or face_crop.size == 0:
            return False
        if np.max(face_crop) == np.min(face_crop):
            return False
        if len(face_crop.shape) == 3:
            if face_crop.shape[2] == 3:
                gray = cv2.cvtColor(face_crop, cv2.COLOR_BGR2GRAY)
            elif face_crop.shape[2] == 4:
                gray = cv2.cvtColor(face_crop, cv2.COLOR_BGRA2GRAY)
            else:
                gray = face_crop[:, :, 0]
        else:
            gray = face_crop
        var = float(cv2.Laplacian(gray, cv2.CV_64F).var())
        return var < self.face_min_laplacian_variance

    def is_bbox_in_roi(self, bbox: BoundingBox, frame_w: int, frame_h: int) -> Tuple[bool, str]:
        """
        Kiểm tra xem vị trí chân người (bottom_center) có nằm trong bất kỳ polygon ROI nào không.
        Trả về: (in_roi: bool, matched_label: str)
        """
        if not self.roi_polygons and not self.roi_polygon:
            return True, "Toàn khung hình"

        bc = bbox.bottom_center
        if self.roi_polygons:
            for poly in self.roi_polygons:
                pts = poly.to_pixel_points(frame_w, frame_h) if (frame_w > 0 and frame_h > 0) else poly.vertices
                if len(pts) >= 3 and is_point_in_polygon(bc, pts):
                    return True, poly.label or "Vùng ROI"
        elif self.roi_polygon and len(self.roi_polygon) >= 3:
            pts = scale_polygon_to_frame(self.roi_polygon, frame_w, frame_h) if (frame_w > 0 and frame_h > 0) else self.roi_polygon
            if is_point_in_polygon(bc, pts):
                return True, "Vùng ROI"

        return False, ""

    def is_bbox_near_or_in_roi(
        self,
        bbox: BoundingBox,
        frame_w: int,
        frame_h: int,
        margin: float = 40.0
    ) -> bool:
        """
        Kiểm tra xem bounding box của người có chạm, giao cắt hoặc nằm sát biên ROI không
        (dùng để nhận biết người đang bước vào mép ROI, triệt tiêu cảnh báo ngụy trang giả).
        """
        if not self.roi_polygons and not self.roi_polygon:
            return True

        cx = (bbox.x1 + bbox.x2) / 2.0
        cy = (bbox.y1 + bbox.y2) / 2.0
        test_points = [
            bbox.bottom_center,
            Point(bbox.x1, bbox.y2),
            Point(bbox.x2, bbox.y2),
            Point(cx, cy),
            Point(bbox.x1, (bbox.y1 + bbox.y2) / 2.0),
            Point(bbox.x2, (bbox.y1 + bbox.y2) / 2.0),
        ]

        polygon_list = []
        if self.roi_polygons:
            for poly in self.roi_polygons:
                pts = poly.to_pixel_points(frame_w, frame_h) if (frame_w > 0 and frame_h > 0) else poly.vertices
                if len(pts) >= 3:
                    polygon_list.append(pts)
        elif self.roi_polygon and len(self.roi_polygon) >= 3:
            pts = scale_polygon_to_frame(self.roi_polygon, frame_w, frame_h) if (frame_w > 0 and frame_h > 0) else self.roi_polygon
            polygon_list.append(pts)

        for pts in polygon_list:
            for pt in test_points:
                if is_point_in_polygon(pt, pts):
                    return True

            cv_pts = np.array([[int(round(p.x)), int(round(p.y))] for p in pts], dtype=np.int32)
            for pt in (bbox.bottom_center, Point(bbox.x1, bbox.y2), Point(bbox.x2, bbox.y2)):
                dist = cv2.pointPolygonTest(cv_pts, (float(pt.x), float(pt.y)), measureDist=True)
                if dist >= -margin:
                    return True

        return False

    def _render_overlay(
        self,
        base_frame: np.ndarray,
        active_persons: List[TrackedPerson],
        alerts: List[SecurityAlertEvent],
        motion_boxes: Optional[List[BoundingBox]] = None,
        only_in_roi: Optional[bool] = None
    ) -> np.ndarray:
        """Render các lớp đồ họa lên khung hình (P1-05 Lazy Overlay)."""
        vis_frame = base_frame.copy()
        vis_frame = FrameVisualizer.draw_all_rois(
            vis_frame,
            polygons=self.roi_polygons if self.roi_polygons else (self.roi_polygon if self.roi_polygon else None)
        )
        has_suspicious_motion_alert = any(a.event_type in ("SUSPICIOUS_MOVING_OBJECT", "SUSPICIOUS_MOTION") for a in alerts)
        if has_suspicious_motion_alert and motion_boxes:
            vis_frame = FrameVisualizer.draw_motion_boxes(vis_frame, motion_boxes)

        if active_persons:
            has_roi = bool(self.roi_polygons or self.roi_polygon)
            roi_filter = only_in_roi if only_in_roi is not None else (getattr(settings, "DRAW_ONLY_IN_ROI", False) and has_roi)
            vis_frame = self.visualizer.draw_tracked_persons(
                vis_frame,
                active_persons,
                only_in_roi=roi_filter
            )
        return vis_frame

    def process_frame(
        self,
        frame: np.ndarray,
        current_time: Optional[float] = None,
        need_annotated: Optional[bool] = None
    ) -> Tuple[np.ndarray, List[TrackedPerson], List[SecurityAlertEvent]]:
        """
        Xử lý 1 khung hình qua toàn bộ pipeline.
        Trả về: (annotated_frame, danh sách người đang được track, danh sách cảnh báo phát sinh)
        """
        if frame is None or frame.size == 0:
            return frame, [], []

        if current_time is None:
            current_time = time.time()

        early_alerts: List[SecurityAlertEvent] = []
        h, w = frame.shape[:2]

        # ------------------------------------------------------------------
        # 1. TIỀN XỬ LÝ CHUYỂN ĐỘNG BẰNG OPENCV MOTION DETECTOR (~1ms)
        # ------------------------------------------------------------------
        motion_res = self.motion_detector.detect(
            frame,
            self.roi_polygons if self.roi_polygons else None
        )
        has_full_attr = getattr(motion_res, "has_motion_full", None)
        has_any_motion = has_full_attr if isinstance(has_full_attr, bool) else bool(getattr(motion_res, "has_motion", False))
        if has_any_motion:
            logger.info(
                f"⚡ [VideoPipeline:{self.camera_code}] [MotionDetector] Phát hiện chuyển động (Full-Frame={has_any_motion}, ROI={getattr(motion_res, 'has_motion_roi', motion_res.has_motion)}): "
                f"diện tích={motion_res.motion_area}px, số box ROI={len(motion_res.motion_boxes)}"
            )

        # 1a. Kiểm tra hành vi phá hoại chiếu đèn (CAMERA_TAMPERING)
        if motion_res.is_tampering:
            if (current_time - self._last_tampering_time) >= self._tampering_cooldown:
                self._last_tampering_time = current_time
                logger.warning(
                    f"🚨 [VideoPipeline:{self.camera_code}] [Tampering] PHÁT HIỆN HÀNH VI CHIẾU ĐÈN / LÀM MÙ CẢM BIẾN CAMERA!"
                )
                tamper_alert = SecurityAlertEvent(
                    camera_code=self.camera_code,
                    event_type="CAMERA_TAMPERING",
                    details="Phát hiện hành vi chiếu đèn laser hoặc làm mù cảm biến camera",
                    confidence=1.0,
                    track_id=-1
                )
                early_alerts.append(tamper_alert)

        # 1b. Cổng Motion-Gating: Nếu ở IDLE và hoàn toàn không có chuyển động trên TOÀN BỘ KHUNG HÌNH
        #     Bỏ qua 100% suy luận Deep Learning nặng (YOLO26, YuNet, ArcFace) để giải phóng CPU/GPU.
        #     Tuy nhiên, cứ mỗi heartbeat_interval_seconds (mặc định 4s), chạy 1 frame YOLO thăm dò
        #     để phát hiện đối tượng ngồi yên trong ROI (tránh lọt lưới do MOG2 hấp thụ vào background).
        if (
            self.enable_motion_gating
            and self.state_machine.state == ZoneState.IDLE
            and not has_any_motion
            and not motion_res.is_lighting_spike
        ):
            if self._last_heartbeat_time is None:
                self._last_heartbeat_time = current_time
            is_heartbeat = (current_time - self._last_heartbeat_time) >= self.heartbeat_interval_seconds
            if not is_heartbeat:
                if not hasattr(self, "_last_idle_log_time"):
                    self._last_idle_log_time = 0.0
                if (current_time - self._last_idle_log_time) >= 5.0:
                    self._last_idle_log_time = current_time
                    logger.info(
                        f"💤 [VideoPipeline:{self.camera_code}] [Motion-Gating] Trạng thái IDLE (phòng trống) -> Bỏ qua Deep Learning để tiết kiệm CPU/GPU"
                    )
                if early_alerts:
                    annotated_frame = self._render_overlay(frame, [], early_alerts, motion_boxes=[])
                    for alert in early_alerts:
                        if self.io_executor and not getattr(self.io_executor, "_shutdown", False):
                            self.io_executor.submit(self._dispatch_alert_async, alert, annotated_frame)
                    return annotated_frame, [], early_alerts
                elif need_annotated is True:
                    annotated_frame = FrameVisualizer.draw_all_rois(
                        frame.copy(),
                        polygons=self.roi_polygons if self.roi_polygons else (self.roi_polygon if self.roi_polygon else None)
                    )
                    return annotated_frame, [], []
                else:
                    return frame, [], []
            else:
                self._last_heartbeat_time = current_time
                logger.info(
                    f"💓 [VideoPipeline:{self.camera_code}] [Heartbeat] Chạy thăm dò YOLO26 định kỳ ({self.heartbeat_interval_seconds}s) để rà soát người ngồi yên trong ROI"
                )

        # ------------------------------------------------------------------
        # 2. ĐÁNH THỨC PHÁT HIỆN & THEO DÕI NGƯỜI (YOLO26 + BYTETRACK)
        # ------------------------------------------------------------------
        self._last_heartbeat_time = current_time
        detected_tracks = self.human_detector.detect_and_track(frame, persist=True)
        current_frame_tids = {tid for tid, _ in detected_tracks if tid >= 0}
        if current_frame_tids or getattr(settings, "AI_LOG_VERBOSE_FRAMES", False):
            logger.info(
                f"👁️ [VideoPipeline:{self.camera_code}] [HumanDetector] YOLO26 + ByteTrack: {len(detected_tracks)} người | Track IDs: {sorted(list(current_frame_tids))}"
            )

        # 2a. TRIỆT TIÊU MOTION DETECTION TRONG BOUNDING BOX NGƯỜI (HUMAN MASKING)
        # Loại bỏ 100% các bounding box vàng của motion detection nằm bên trong hoặc trùng lên người.
        # Bao gồm cả người vừa phát hiện trong frame và người đang được duy trì track (active tracks).
        # Khi người cúi xuống / ngồi xuống khiến YOLO tạm thời trượt detection, chuyển động của họ
        # vẫn được nhận diện là của người đó -> duy trì track ID và KHÔNG cảnh báo ngụy trang giả!
        current_frame_tids = {tid for tid, _ in detected_tracks if tid >= 0}
        detected_boxes = [bbox for _, bbox in detected_tracks]
        active_tracked_boxes: List[BoundingBox] = []

        for tid, p in list(self.analysis_engine.active_tracks.items()):
            inactive_dur = current_time - p.last_seen_time
            if inactive_dur <= self.analysis_engine.max_inactive_seconds:
                # Kiểm tra xem vị trí của track cũ có đang trùng lấn với detection mới nào từ YOLO không
                has_active_yolo_det = any(
                    p.bbox.iou(d_box) > 0.25 or p.bbox.intersection_ratio(d_box) > 0.45
                    for d_box in detected_boxes
                )
                if has_active_yolo_det and tid not in current_frame_tids:
                    # Đã có detection mới của YOLO tại vị trí này -> Track cũ không còn visible, nhường quyền cho track mới
                    p.is_currently_visible = False
                elif tid not in current_frame_tids and motion_res.motion_boxes:
                    # Người thật sự cúi người/ngồi khuất và YOLO trượt detection:
                    # Giới hạn thời gian tối đa motion box được phép gia hạn track (tránh track ma do chuyển động nền)
                    yolo_last = getattr(p, "yolo_last_seen_time", p.first_seen_time) or p.first_seen_time
                    if (current_time - yolo_last) <= self.max_motion_track_hold_seconds:
                        overlapping_mb = [
                            mb for mb in motion_res.motion_boxes
                            if self._is_box_overlapping_any(mb, [p.bbox], overlap_threshold=0.08)
                        ]
                        if overlapping_mb:
                            # Duy trì sự tồn tại của track nhưng KHÔNG phình to box vô tận
                            p.last_seen_time = current_time
                            p.is_currently_visible = True
                    else:
                        p.is_currently_visible = False
                active_tracked_boxes.append(p.bbox)

        all_human_boxes = [bbox for _, bbox in detected_tracks] + active_tracked_boxes
        if all_human_boxes and motion_res.motion_boxes:
            non_human_motion_boxes = [
                mb for mb in motion_res.motion_boxes
                if not self._is_box_overlapping_any(mb, all_human_boxes, overlap_threshold=0.08)
            ]
            motion_res.motion_boxes = non_human_motion_boxes
            if not motion_res.motion_boxes:
                motion_res.motion_area = 0

        # 2b. Kiểm tra người trong ROI và cập nhật Máy trạng thái chốt giữ
        person_in_roi = False
        for track_id, bbox in detected_tracks:
            in_roi, _ = self.is_bbox_in_roi(bbox, w, h)
            if in_roi:
                person_in_roi = True
                break

        # Nếu frame hiện tại YOLO trượt phát hiện nhưng người đang được track đang ở trong ROI
        if not person_in_roi:
            for p in self.analysis_engine.active_tracks.values():
                if (current_time - p.last_seen_time) <= self.analysis_engine.max_inactive_seconds:
                    in_roi, _ = self.is_bbox_in_roi(p.bbox, w, h)
                    if in_roi:
                        person_in_roi = True
                        break

        self.state_machine.update(
            has_motion=motion_res.has_motion,
            person_in_roi=person_in_roi,
            current_time=current_time
        )

        # 2c. Phát hiện Đột nhập Ngụy trang (SUSPICIOUS_MOVING_OBJECT):
        # Có chuyển động đáng kể trong ROI nhưng YOLO không nhận diện được dáng người (và không phải do người tạo ra).
        # Nếu có bất kỳ người nào đang ở gần mép ROI hoặc đang bước vào ROI, tuyệt đối KHÔNG kích hoạt cảnh báo ngụy trang!
        has_roi_attr = getattr(motion_res, "has_motion_roi", None)
        has_roi_motion = has_roi_attr if isinstance(has_roi_attr, bool) else bool(getattr(motion_res, "has_motion", False))
        has_nearby_human = any(
            self.is_bbox_near_or_in_roi(bbox, w, h, margin=40.0)
            for _, bbox in detected_tracks
        ) or any(
            self.is_bbox_near_or_in_roi(p.bbox, w, h, margin=40.0)
            for p in self.analysis_engine.active_tracks.values()
            if (current_time - p.last_seen_time) <= self.analysis_engine.max_inactive_seconds
        )

        if has_roi_motion and not person_in_roi and not has_nearby_human and len(motion_res.motion_boxes) > 0:
            if (current_time - self._last_suspicious_motion_time) >= self._suspicious_motion_cooldown:
                self._last_suspicious_motion_time = current_time
                cam_area_name = self.face_matcher.get_camera_area_name(self.camera_code) if self.face_matcher else None
                loc_name = cam_area_name or "Khu vực giám sát"
                logger.warning(
                    f"🕵️ [VideoPipeline:{self.camera_code}] [SuspiciousMotion] CẢNH BÁO: Chuyển động nghi vấn ngụy trang trong ROI (Vị trí: {loc_name})"
                )

                anomaly_alert = SecurityAlertEvent(
                    camera_code=self.camera_code,
                    event_type="SUSPICIOUS_MOVING_OBJECT",
                    details=f"[SUSPICIOUS_MOVING_OBJECT] Vị trí: {loc_name}",
                    confidence=0.9,
                    track_id=-1
                )
                early_alerts.append(anomaly_alert)

        # ------------------------------------------------------------------
        # 3. QUÉT KHUÔN MẶT TRÊN TỪNG NGƯỜI (YUNET + ARCFACE)
        # ------------------------------------------------------------------
        for track_id, bbox in detected_tracks:
            if track_id < 0:
                continue

            in_roi, roi_label = self.is_bbox_in_roi(bbox, w, h)

            # Tối ưu hiệu năng: Nếu Track ID này đã nhận diện được danh tính trong active_tracks,
            # ByteTrack tiếp tục bám theo mượt mà mà không cần chạy lại YuNet + ArcFace + pgvector mỗi frame!
            existing = self.analysis_engine.active_tracks.get(track_id)
            is_reverifying = False
            if existing and existing.face_detected and existing.face_info and existing.face_info.matched_code:
                # Nếu người này trước đó ở ngoài ROI, nay bước chân vào ROI:
                if in_roi and not getattr(existing.face_info, "access_checked", False):
                    self._resolve_access_for_person(
                        track_id,
                        existing.face_info,
                        existing.face_info.matched_code,
                        roi_label or "Vùng giám sát"
                    )

                # Điểm 8: Kiểm tra điều kiện Re-verification / Face Quality Upgrade
                prev_score = getattr(existing.face_info, "score", 0.0) or 0.0
                last_match_t = getattr(existing, "last_face_match_time", 0.0) or existing.first_seen_time
                reverify_threshold = getattr(settings, "FACE_REVERIFY_SCORE_THRESHOLD", 0.82)
                reverify_interval = getattr(settings, "FACE_REVERIFY_MIN_INTERVAL_SECONDS", 3.0)

                is_inherited = getattr(existing, "is_inherited_identity", False) or (existing.face_info and getattr(existing.face_info, "is_inherited", False))

                # Chỉ re-verify nếu là track kế thừa (chưa xác thực độc lập trên track này), hoặc score ban đầu mấp mé (< threshold) và đã qua chu kỳ interval
                if is_inherited:
                    is_reverifying = True
                elif prev_score < reverify_threshold and (current_time - last_match_t) >= reverify_interval:
                    is_reverifying = True
                else:
                    continue

            if not is_reverifying:
                # Track CHƯA định danh: Throttling theo thời gian (P0-02)
                if self.face_scan_throttling_enabled:
                    if track_id in self._track_face_scan_times:
                        last_scan_t = self._track_face_scan_times[track_id]
                        first_seen_t = existing.first_seen_time if existing else last_scan_t
                        track_age = current_time - first_seen_t

                        if track_age < self.face_scan_initial_window:
                            scan_interval = self.face_scan_initial_interval
                        else:
                            scan_interval = self.face_scan_backoff_interval

                        # Thử lại nếu chưa đủ khoảng cách scan_interval
                        if (current_time - last_scan_t) < (scan_interval - 1e-4):
                            continue

            x1, y1, x2, y2 = bbox.to_int_xyxy()
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(w, x2), min(h, y2)

            if (x2 - x1) > 20 and (y2 - y1) > 20:
                crop_h = int((y2 - y1) * settings.UPPER_BODY_CROP_RATIO)
                person_upper_crop = frame[y1:y1 + crop_h, x1:x2]
                
                if person_upper_crop.size > 0:
                    if not is_reverifying:
                        self._track_face_scan_times[track_id] = current_time

                    face_result = self.face_detector.detect_best_face_in_crop(
                        person_upper_crop,
                        offset_xy=(x1, y1)
                    )
                    if face_result:
                        fx1, fy1, fx2, fy2 = face_result.bbox.to_int_xyxy()
                        fx1, fy1 = max(0, fx1), max(0, fy1)
                        fx2, fy2 = min(w, fx2), min(h, fy2)
                        face_w = fx2 - fx1
                        face_h = fy2 - fy1

                        # Lọc chất lượng mặt (P0-02):
                        # 1. Kích thước tối thiểu
                        if face_w < self.face_min_crop_size or face_h < self.face_min_crop_size:
                            # Mặt quá nhỏ, chất lượng kém -> Bỏ qua, không xóa danh tính cũ và không phá vỡ grace period
                            continue

                        face_crop = frame[fy1:fy2, fx1:fx2]
                        if face_crop.size > 0:
                            # 2. Lọc độ mờ (Laplacian variance)
                            if self._is_face_blurry(face_crop):
                                logger.info(
                                    f"⚠️ [VideoPipeline:{self.camera_code}] [FaceFilter] Track #{track_id}: Mặt bị mờ do chuyển động -> Bỏ qua ArcFace"
                                )
                                # Mặt bị mờ do chuyển động -> Bỏ qua, không xóa danh tính cũ và không phá vỡ grace period
                                continue
                            crop_landmarks = None
                            if face_result.landmarks:
                                crop_landmarks = [(pt[0] - fx1, pt[1] - fy1) for pt in face_result.landmarks]
                            match = self.face_matcher.match_face(
                                face_crop,
                                threshold=settings.FACE_MATCH_THRESHOLD,
                                landmarks=crop_landmarks
                            )
                            if match:
                                if len(match) >= 5:
                                    code, name, score, is_active, role = match
                                else:
                                    code, name, score, is_active = match[:4]
                                    role = "NORMAL_USER"
                                logger.info(
                                    f"✅ [VideoPipeline:{self.camera_code}] [FaceMatcher] Track #{track_id} -> Nhận diện: {code} ({name}) "
                                    f"[Khớp: {score:.1%}, Vai trò: {role}, Hoạt động: {is_active}]"
                                )
                                # Điểm 8: Nếu đang re-verify cho 1 track đã có danh tính:
                                identity_switched = False
                                if is_reverifying and existing and existing.face_info and existing.face_info.matched_code:
                                    prev_code = existing.face_info.matched_code
                                    prev_score = getattr(existing.face_info, "score", 0.0) or 0.0
                                    existing.last_face_match_time = current_time

                                    if code == prev_code:
                                        # Cùng mã: xóa candidate pending
                                        existing.pending_reverify_code = None
                                        existing.pending_reverify_count = 0
                                        # Đã xác thực khuôn mặt độc lập trên track này -> Xóa cờ kế thừa!
                                        existing.is_inherited_identity = False
                                        if existing.face_info:
                                            existing.face_info.is_inherited = False
                                        # Chỉ cập nhật điểm nếu điểm mới cao hơn điểm cũ
                                        if score > prev_score:
                                            existing.face_info.score = score
                                            face_result.score = score
                                        continue

                                    # Mã mới KHÁC danh tính hiện tại:
                                    # Điểm mới phải >= prev_score + 0.05
                                    if score < (prev_score + 0.05):
                                        continue

                                    # Yêu cầu 2 lần match liên tiếp cùng mã mới trước khi đổi (POINT-08a)
                                    if getattr(existing, "pending_reverify_code", None) == code:
                                        existing.pending_reverify_count = getattr(existing, "pending_reverify_count", 0) + 1
                                    else:
                                        existing.pending_reverify_code = code
                                        existing.pending_reverify_count = 1

                                    if existing.pending_reverify_count < 2:
                                        # Lần 1: chưa đủ 2 lần liên tiếp -> BỎ QUA, không đổi
                                        continue

                                    # Lần 2 liên tiếp cùng mã mới -> Thực hiện đổi danh tính:
                                    logger.info(
                                        f"🔄 [Face Re-verify] Track #{track_id} đổi danh tính từ {prev_code} "
                                        f"sang {code} (score mới: {score:.3f}, score cũ: {prev_score:.3f})"
                                    )
                                    existing.pending_reverify_code = None
                                    existing.pending_reverify_count = 0
                                    existing.is_inherited_identity = False
                                    if existing.face_info:
                                        existing.face_info.is_inherited = False

                                    # POINT-08b: Reset cảnh báo vi phạm
                                    existing.alert_unauthorized_sent = False
                                    existing.alert_unknown_sent = False
                                    existing.track_unauthorized_alert_time = 0.0

                                    # POINT-08c: Đổi danh tính A -> B: Ghi EXIT(A) rồi ENTRY(B) tuần tự trong MỘT task
                                    need_exit = getattr(existing, "roi_entry_logged", False)
                                    need_entry = in_roi

                                    if (need_exit or need_entry) and self.face_matcher:
                                        if self.access_executor and not getattr(self.access_executor, "_shutdown", False):
                                            self.access_executor.submit(
                                                self._handle_identity_switch,
                                                track_id,
                                                prev_code,
                                                code,
                                                roi_label or "Vùng giám sát",
                                                need_exit,
                                                need_entry
                                            )
                                        else:
                                            self._handle_identity_switch(
                                                track_id,
                                                prev_code,
                                                code,
                                                roi_label or "Vùng giám sát",
                                                need_exit,
                                                need_entry
                                            )
                                        existing.roi_entry_logged = False
                                        identity_switched = True

                                face_result.matched_code = code
                                face_result.matched_name = name
                                face_result.is_active = is_active
                                face_result.role = role
                                face_result.score = score

                                if existing:
                                    existing.last_face_match_time = current_time

                                # CHỈ kiểm tra thẩm quyền và ghi nhận truy cập khi đã thực sự bước chân vào vùng ROI!
                                if in_roi:
                                    if not identity_switched:
                                        self._resolve_access_for_person(
                                            track_id,
                                            face_result,
                                            code,
                                            roi_label or "Vùng giám sát"
                                        )
                                else:
                                    # Người dùng đang ở ngoài vùng ROI: Trạng thái bình thường, không ghi log vi phạm
                                    face_result.is_authorized = True
                                    face_result.access_reason = "Đang ở ngoài vùng ROI"
                                    face_result.access_checked = False
                            else:
                                logger.info(
                                    f"❓ [VideoPipeline:{self.camera_code}] [FaceMatcher] Track #{track_id} -> Không khớp khuôn mặt trong CSDL (UNKNOWN)"
                                )

                        self.analysis_engine.associate_face(track_id, face_result)

        # ------------------------------------------------------------------
        # 4. PHÂN TÍCH XÂM NHẬP VÙNG CẤM (FRAME ANALYSIS ENGINE)
        # ------------------------------------------------------------------
        is_public = self.face_matcher.is_camera_public(self.camera_code) if self.face_matcher else False
        cam_area_name = self.face_matcher.get_camera_area_name(self.camera_code) if self.face_matcher else None
        active_persons, alerts, roi_events = self.analysis_engine.process_frame(
            detected_tracks=detected_tracks,
            roi_polygon=self.roi_polygon,
            camera_code=self.camera_code,
            current_time=current_time,
            roi_polygons=self.roi_polygons,
            frame_size=(w, h),
            is_public_area=is_public,
            area_name=cam_area_name
        )

        # Dọn dẹp track cũ trong bộ nhớ đệm throttling (P0-02)
        active_tids = set(self.analysis_engine.active_tracks.keys())
        for tid in list(self._track_face_scan_times.keys()):
            if tid not in active_tids:
                del self._track_face_scan_times[tid]

        if early_alerts:
            alerts = early_alerts + alerts

        # 4b. Xử lý ghi log sự kiện RA / VÀO vùng ROI ngầm (Async qua ThreadPoolExecutor)
        if roi_events and self.face_matcher:
            for ev in roi_events:
                if self.access_executor and not getattr(self.access_executor, "_shutdown", False):
                    self.access_executor.submit(
                        self.face_matcher.check_camera_access,
                        self.camera_code,
                        ev["user_code"],
                        ev["roi_label"],
                        ev["direction"],
                        ev.get("duration"),
                        True
                    )

        has_roi = bool(self.roi_polygons or self.roi_polygon)

        # ------------------------------------------------------------------
        # 5. XỬ LÝ LƯU BẰNG CHỨNG & GỬI CẢNH BÁO TỰ ĐỘNG + LAZY OVERLAY (P1-05)
        # ------------------------------------------------------------------
        annotated_frame = None
        has_alerts = bool(alerts)
        is_client_active = getattr(self, "has_active_client", False)
        render_required = (need_annotated is True) or has_alerts or is_client_active

        if render_required:
            annotated_frame = self._render_overlay(
                frame,
                active_persons,
                alerts,
                motion_boxes=motion_res.motion_boxes if motion_res else []
            )

        if alerts:
            # Tái sử dụng kết quả vẽ giữa snapshot và annotated (P1-05)
            snapshot_frame = annotated_frame if annotated_frame is not None else frame
            for alert in alerts:
                logger.warning(
                    f"🚨 [VideoPipeline:{self.camera_code}] [SecurityAlert] PHÁT HIỆN SỰ CỐ AN NINH: "
                    f"Loại=[{alert.event_type}], Track=#{alert.track_id}, Vị trí=[{alert.camera_code}], Chi tiết: {alert.details}"
                )
                ev_type = str(alert.event_type or "").upper()
                if any(k in ev_type for k in ["UNAUTHORIZED", "INTRUSION", "AFTER_HOUR", "OUT_OF_TIME", "UNKNOWN", "SUSPICIOUS_MOTION", "SUSPICIOUS_MOVING_OBJECT", "TAMPERING"]):
                    try:
                        incident_recording_manager.start_incident_recording(
                            incident_id=alert.event_id or f"EVT-{int(time.time()*1000)}",
                            camera_code=self.camera_code,
                            event_type=alert.event_type,
                            details=alert.details or "Phát hiện sự cố an ninh",
                        )
                    except Exception as e:
                        logger.error(f"Lỗi kích hoạt ghi hình sự cố cho camera [{self.camera_code}]: {e}")

                if self.io_executor and not getattr(self.io_executor, "_shutdown", False):
                    self.io_executor.submit(self._dispatch_alert_async, alert, snapshot_frame)

        result_frame = annotated_frame if annotated_frame is not None else frame
        return result_frame, active_persons, alerts

    def _dispatch_alert_async(self, alert: SecurityAlertEvent, snapshot_frame: np.ndarray):
        # 1. Tính trước object key của ảnh bằng chứng và gán alert.image_url ngay lập tức (POINT-13)
        object_name, object_key, file_name, date_str = (None, None, None, None)
        if self.storage_service and hasattr(self.storage_service, "precompute_evidence_key"):
            try:
                res_key = self.storage_service.precompute_evidence_key(
                    self.camera_code,
                    alert.track_id,
                    event_type=alert.event_type
                )
                if isinstance(res_key, (tuple, list)) and len(res_key) >= 4:
                    object_name, object_key, file_name, date_str = res_key[:4]
                    alert.image_url = object_key
            except Exception as e:
                logger.warning(f"⚠️ [VideoPipeline] Không thể tính trước object key cho ảnh bằng chứng: {e}")
        elif not alert.image_url:
            date_str = time.strftime("%Y-%m-%d")
            time_str = time.strftime("%H%M%S")
            file_name = f"{alert.event_type}_Track{alert.track_id}_{time_str}.jpg"
            object_name = f"{date_str}/{self.camera_code}/{file_name}"
            object_key = f"security-evidence/{object_name}"
            alert.image_url = object_key

        # 2. Upload chạy nền qua io_executor (không tạo threading.Thread mới, không vượt giới hạn pool)
        upload_future = None
        if self.storage_service and snapshot_frame is not None and snapshot_frame.size > 0:
            frame_copy = snapshot_frame.copy()

            def _upload_task():
                try:
                    if hasattr(self.storage_service, "upload_frame_evidence_with_key") and object_name:
                        self.storage_service.upload_frame_evidence_with_key(
                            frame_copy,
                            self.camera_code,
                            object_name,
                            object_key,
                            file_name,
                            date_str
                        )
                        logger.info(
                            f"☁️ [VideoPipeline:{self.camera_code}] [MinIO] Đã upload ảnh bằng chứng vi phạm: {object_key}"
                        )
                    else:
                        self.storage_service.upload_frame_evidence(
                            frame_copy,
                            self.camera_code,
                            alert.track_id,
                            event_type=alert.event_type
                        )
                except Exception as e:
                    logger.error(f"🚨 [VideoPipeline] Background upload ảnh bằng chứng thất bại cho {object_key}: {e}")
                    try:
                        if hasattr(self.storage_service, "save_local_snapshot") and file_name and date_str:
                            self.storage_service.save_local_snapshot(frame_copy, self.camera_code, file_name, date_str)
                    except Exception as err:
                        logger.error(f"🚨 [VideoPipeline] Lưu local fallback cũng thất bại: {err}")

            if self.io_executor and not getattr(self.io_executor, "_shutdown", False):
                try:
                    upload_future = self.io_executor.submit(_upload_task)
                except Exception as e:
                    logger.error(f"🚨 [VideoPipeline] Không thể submit task upload vào io_executor: {e}")
            else:
                _upload_task()

        # 3. Chờ kết quả upload tối đa ALERT_IMAGE_WAIT_SECONDS (mặc định 0.8s) trước khi gửi alert (POINT-13)
        wait_seconds = float(getattr(settings, "ALERT_IMAGE_WAIT_SECONDS", 0.8))
        if upload_future is not None:
            try:
                upload_future.result(timeout=wait_seconds)
            except TimeoutError:
                logger.warning(
                    f"⚠️ [VideoPipeline] Upload ảnh bằng chứng vượt quá {wait_seconds}s! "
                    f"Gửi cảnh báo ngay với key tính trước; upload tiếp tục chạy nền."
                )
            except Exception as e:
                logger.warning(f"⚠️ [VideoPipeline] Upload ảnh bằng chứng gặp lỗi trong thời gian chờ: {e}")

        # 4. Gửi cảnh báo qua MQTT / Durable Outbox (image_url luôn là key tính trước)
        try:
            self.mqtt_client.send_alert(alert)
            logger.info(
                f"📡 [VideoPipeline:{self.camera_code}] [MQTT] Đã gửi cảnh báo [{alert.event_type}] "
                f"(Track #{alert.track_id}) lên broker MQTT thành công"
            )
        except Exception as e:
            logger.error(f"🚨 [VideoPipeline] Lỗi gửi cảnh báo an ninh sang MQTT/Outbox: {e}")



    def stop(self):
        """Dừng an toàn cả hai executor khi worker/pipeline giải phóng (cancel_futures=True)"""
        if hasattr(self, "access_executor") and self.access_executor:
            try:
                self.access_executor.shutdown(wait=False, cancel_futures=True)
            except TypeError:
                self.access_executor.shutdown(wait=False)
            self.access_executor = None
        if hasattr(self, "io_executor") and self.io_executor:
            try:
                self.io_executor.shutdown(wait=False, cancel_futures=True)
            except TypeError:
                self.io_executor.shutdown(wait=False)
            self.io_executor = None
        self.executor = None
