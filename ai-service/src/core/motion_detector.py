import time
import logging
from typing import List, Optional, Tuple
import numpy as np
import cv2

from .entity import Point, BoundingBox, RoiPolygonConfig, MotionResult

logger = logging.getLogger(__name__)

class MotionDetector:
    """
    Module phát hiện chuyển động siêu nhẹ dựa trên OpenCV Background Subtractor MOG2:
    - Áp dụng mặt nạ đa giác ROI chuẩn hóa (0.0..1.0)
    - Lọc nhiễu hạt và côn trùng bằng phép toán hình thái học Morphological Opening
    - Lọc kích thước đối tượng chuyển động tối thiểu (Contour Area)
    - Phân biệt biến động ánh sáng phòng (Lighting Spike) và Chiếu đèn làm mù camera (Tampering)
    """

    def __init__(
        self,
        history: int = 500,
        var_threshold: float = 25.0,
        min_motion_area: int = 3000,
        lighting_spike_ratio: float = 0.70,
        tampering_pixel_val: int = 245,
        tampering_area_ratio: float = 0.05,
        tampering_duration_threshold: float = 1.5
    ):
        self.history = history
        self.var_threshold = var_threshold
        self.min_motion_area = min_motion_area
        self.lighting_spike_ratio = lighting_spike_ratio
        self.tampering_pixel_val = tampering_pixel_val
        self.tampering_area_ratio = tampering_area_ratio
        self.tampering_duration_threshold = tampering_duration_threshold

        # Khởi tạo thuật toán trừ nền MOG2
        self.subtractor = cv2.createBackgroundSubtractorMOG2(
            history=self.history,
            varThreshold=self.var_threshold,
            detectShadows=False
        )

        # Cấu trúc phần tử hình thái học (Morphological Kernel) 5x5
        self.morph_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))

        # Theo dõi sự kiện phá hoại chiếu đèn (Tampering State)
        self._tampering_start_time: Optional[float] = None

    def reset_background(self):
        """Khởi tạo lại trạng thái nền (Background model)"""
        self.subtractor = cv2.createBackgroundSubtractorMOG2(
            history=self.history,
            varThreshold=self.var_threshold,
            detectShadows=False
        )
        self._tampering_start_time = None

    def adapt_instant(self, frame: np.ndarray):
        """Học ngay lập tức frame hiện tại làm nền chuẩn mới (learningRate=1.0)"""
        if frame is not None and frame.size > 0:
            self.subtractor.apply(frame, learningRate=1.0)

    def _build_roi_mask(self, height: int, width: int, roi_polygons: Optional[List[RoiPolygonConfig]]) -> np.ndarray:
        """Tạo mặt nạ nhị phân (Binary Mask) từ danh sách ROI đa giác"""
        mask = np.zeros((height, width), dtype=np.uint8)

        if not roi_polygons:
            # Nếu không cấu hình ROI, áp dụng toàn bộ khung hình
            mask.fill(255)
            return mask

        has_valid_poly = False
        for roi in roi_polygons:
            if not roi.vertices or len(roi.vertices) < 3:
                continue

            pixel_pts = roi.to_pixel_points(width, height)
            pts_array = np.array([[int(round(p.x)), int(round(p.y))] for p in pixel_pts], dtype=np.int32)
            cv2.fillPoly(mask, [pts_array], 255)
            has_valid_poly = True

        if not has_valid_poly:
            mask.fill(255)

        return mask

    def detect(self, frame: np.ndarray, roi_polygons: Optional[List[RoiPolygonConfig]] = None) -> MotionResult:
        """
        Xử lý 1 khung hình video:
        Trả về MotionResult chứa trạng thái chuyển động, diện tích, và các cảnh báo bất thường.
        """
        if frame is None or frame.size == 0:
            return MotionResult()

        height, width = frame.shape[:2]
        roi_mask = self._build_roi_mask(height, width, roi_polygons)
        roi_pixel_count = cv2.countNonZero(roi_mask)
        if roi_pixel_count <= 0:
            roi_pixel_count = height * width

        # ------------------------------------------------------------------
        # 1. KIỂM TRA PHÁ HOẠI LÀM MÙ CẢM BIẾN (CAMERA TAMPERING / LASER BLINDING)
        # ------------------------------------------------------------------
        # Chuyển sang ảnh xám để đo cường độ điểm ảnh bị bão hòa trắng
        if len(frame.shape) == 3:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        else:
            gray = frame

        # Đếm các pixel cháy sáng tuyệt đối trong vùng ROI
        saturated_mask = (gray >= self.tampering_pixel_val).astype(np.uint8) * 255
        saturated_in_roi = cv2.bitwise_and(saturated_mask, saturated_mask, mask=roi_mask)
        saturated_count = cv2.countNonZero(saturated_in_roi)
        saturated_ratio = saturated_count / float(roi_pixel_count)

        is_tampering = False
        now = time.time()
        if saturated_ratio >= self.tampering_area_ratio:
            if self._tampering_start_time is None:
                self._tampering_start_time = now
            elif (now - self._tampering_start_time) >= self.tampering_duration_threshold:
                is_tampering = True
        else:
            self._tampering_start_time = None

        # ------------------------------------------------------------------
        # 2. TRỪ NỀN MOG2 & ÁP DỤNG MẶT NẠ ROI
        # ------------------------------------------------------------------
        raw_fg = self.subtractor.apply(frame)
        fg_roi = cv2.bitwise_and(raw_fg, raw_fg, mask=roi_mask)

        # ------------------------------------------------------------------
        # 3. KIỂM TRA CHỚP SÁNG ĐỘT NGỘT (LIGHTING SPIKE)
        # ------------------------------------------------------------------
        raw_motion_pixels = cv2.countNonZero(fg_roi)
        motion_ratio = raw_motion_pixels / float(roi_pixel_count)
        is_lighting_spike = motion_ratio >= self.lighting_spike_ratio

        # ------------------------------------------------------------------
        # 4. LỌC NHIỄU HẠT (MORPHOLOGICAL OPENING) & CONTOUR FILTERING
        # ------------------------------------------------------------------
        fg_cleaned = cv2.morphologyEx(fg_roi, cv2.MORPH_OPEN, self.morph_kernel)

        contours, _ = cv2.findContours(fg_cleaned, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        motion_boxes: List[BoundingBox] = []
        total_motion_area = 0

        for cnt in contours:
            area = cv2.contourArea(cnt)
            if area >= self.min_motion_area:
                x, y, w, h = cv2.boundingRect(cnt)
                motion_boxes.append(BoundingBox(
                    x1=float(x),
                    y1=float(y),
                    x2=float(x + w),
                    y2=float(y + h),
                    confidence=min(1.0, float(area) / float(self.min_motion_area * 2))
                ))
                total_motion_area += int(area)

        has_motion = len(motion_boxes) > 0 and not is_lighting_spike

        return MotionResult(
            has_motion=has_motion,
            motion_area=total_motion_area,
            motion_boxes=motion_boxes,
            is_lighting_spike=is_lighting_spike,
            is_tampering=is_tampering,
            details=f"motion_pixels={raw_motion_pixels}, area={total_motion_area}, boxes={len(motion_boxes)}"
        )
