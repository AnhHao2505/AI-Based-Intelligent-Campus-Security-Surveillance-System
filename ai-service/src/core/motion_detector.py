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

        # Khởi tạo thuật toán trừ nền MOG2 (Bật detectShadows để loại bỏ bóng đổ trên sàn)
        self.subtractor = cv2.createBackgroundSubtractorMOG2(
            history=self.history,
            varThreshold=self.var_threshold,
            detectShadows=True
        )

        # Cấu trúc phần tử hình thái học:
        # - morph_kernel 5x5: Lọc nhiễu hạt
        # - dilate_kernel 15x15: Giãn nở kết nối các phần cơ thể (chân, tay, thân) thành 1 khối duy nhất
        self.morph_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
        self.dilate_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15))

        # Theo dõi sự kiện phá hoại chiếu đèn (Tampering State)
        self._tampering_start_time: Optional[float] = None
        self._cached_roi_mask: Optional[np.ndarray] = None
        self._mask_cache_key = None
        self.max_processing_dim: int = 640

    def reset_background(self):
        """Khởi tạo lại trạng thái nền (Background model)"""
        self.subtractor = cv2.createBackgroundSubtractorMOG2(
            history=self.history,
            varThreshold=self.var_threshold,
            detectShadows=True
        )
        self._tampering_start_time = None
        self._cached_roi_mask = None
        self._mask_cache_key = None

    def adapt_instant(self, frame: np.ndarray):
        """Học ngay lập tức frame hiện tại làm nền chuẩn mới (learningRate=1.0)"""
        if frame is not None and frame.size > 0:
            self.subtractor.apply(frame, learningRate=1.0)

    def _build_roi_mask(self, height: int, width: int, roi_polygons: Optional[List[RoiPolygonConfig]]) -> np.ndarray:
        """Tạo mặt nạ nhị phân (Binary Mask) từ danh sách ROI đa giác có Caching tối ưu hiệu năng"""
        if not roi_polygons:
            poly_signature = ()
        else:
            poly_signature = tuple(
                (roi.label, tuple((round(p.x, 4), round(p.y, 4)) for p in (roi.vertices or [])))
                for roi in roi_polygons
            )
        cache_key = (height, width, poly_signature)
        if self._cached_roi_mask is not None and self._mask_cache_key == cache_key:
            return self._cached_roi_mask

        mask = np.zeros((height, width), dtype=np.uint8)

        if not roi_polygons:
            mask.fill(255)
            self._cached_roi_mask = mask
            self._mask_cache_key = cache_key
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

        self._cached_roi_mask = mask
        self._mask_cache_key = cache_key
        return mask

    @staticmethod
    def _merge_close_boxes(boxes: List[BoundingBox], distance_thresh: float = 50.0) -> List[BoundingBox]:
        """
        Hợp nhất các bounding box chuyển động ở gần nhau hoặc chồng lấn nhau
        (ví dụ: 2 bàn chân, đầu gối, bóng đổ cùng 1 người) thành 1 khung duy nhất,
        tránh tình trạng vẽ quá nhiều bounding box lắt nhắt trên màn hình.
        """
        if not boxes or len(boxes) <= 1:
            return boxes

        rects = [[b.x1, b.y1, b.x2, b.y2, b.confidence] for b in boxes]
        merged = True

        while merged:
            merged = False
            new_rects = []
            skip_indices = set()

            for i in range(len(rects)):
                if i in skip_indices:
                    continue
                r1 = list(rects[i])
                for j in range(i + 1, len(rects)):
                    if j in skip_indices:
                        continue
                    r2 = rects[j]

                    # Khoảng cách giữa 2 box theo trục X và Y
                    x_dist = max(0.0, max(r1[0], r2[0]) - min(r1[2], r2[2]))
                    y_dist = max(0.0, max(r1[1], r2[1]) - min(r1[3], r2[3]))

                    # Nếu 2 box giao nhau hoặc cách nhau <= distance_thresh
                    if x_dist <= distance_thresh and y_dist <= distance_thresh:
                        r1[0] = min(r1[0], r2[0])
                        r1[1] = min(r1[1], r2[1])
                        r1[2] = max(r1[2], r2[2])
                        r1[3] = max(r1[3], r2[3])
                        r1[4] = max(r1[4], r2[4])
                        skip_indices.add(j)
                        merged = True

                new_rects.append(r1)

            rects = new_rects

        return [
            BoundingBox(x1=r[0], y1=r[1], x2=r[2], y2=r[3], confidence=r[4])
            for r in rects
        ]

    def detect(self, frame: np.ndarray, roi_polygons: Optional[List[RoiPolygonConfig]] = None) -> MotionResult:
        """
        Xử lý 1 khung hình video:
        Tối ưu downscaling trước khi MOG2 để tăng tốc độ gấp 4-8 lần trên độ phân giải 1080p,
        đồng thời áp dụng kiểm tra cấu trúc Laplacian nhằm triệt tiêu False Alarm của Tampering.
        """
        if frame is None or frame.size == 0:
            return MotionResult()

        orig_h, orig_w = frame.shape[:2]

        # Tối ưu: Downscale khung hình lớn (> 640px) trước khi qua MOG2
        scale = 1.0
        if max(orig_h, orig_w) > self.max_processing_dim:
            scale = float(self.max_processing_dim) / float(max(orig_h, orig_w))
            proc_w = int(orig_w * scale)
            proc_h = int(orig_h * scale)
            proc_frame = cv2.resize(frame, (proc_w, proc_h), interpolation=cv2.INTER_AREA)
        else:
            proc_w, proc_h = orig_w, orig_h
            proc_frame = frame

        roi_mask = self._build_roi_mask(proc_h, proc_w, roi_polygons)
        roi_pixel_count = cv2.countNonZero(roi_mask)
        if roi_pixel_count <= 0:
            roi_pixel_count = proc_h * proc_w

        # ------------------------------------------------------------------
        # 1. KIỂM TRA PHÁ HOẠI LÀM MÙ CẢM BIẾN (CAMERA TAMPERING / LASER BLINDING)
        # ------------------------------------------------------------------
        if len(proc_frame.shape) == 3:
            gray = cv2.cvtColor(proc_frame, cv2.COLOR_BGR2GRAY)
        else:
            gray = proc_frame

        saturated_mask = (gray >= self.tampering_pixel_val).astype(np.uint8) * 255
        saturated_in_roi = cv2.bitwise_and(saturated_mask, saturated_mask, mask=roi_mask)
        saturated_count = cv2.countNonZero(saturated_in_roi)
        saturated_ratio = saturated_count / float(roi_pixel_count)

        is_tampering = False
        now = time.time()
        # BUG-07: Phân biệt chớp sáng/tường trắng với hành vi chiếu đèn làm mù cảm biến:
        # Khi laser/đèn pin chiếu trực tiếp vào lens, cấu trúc texture bên trong vùng bão hòa bị xóa sổ (Laplacian variance rất thấp)
        if saturated_ratio >= self.tampering_area_ratio:
            # Erode vùng bão hòa để loại bỏ ảnh hưởng bước nhảy độ sáng tại đường biên
            sat_inner = cv2.erode(saturated_in_roi, self.morph_kernel)
            if cv2.countNonZero(sat_inner) > 0:
                lap = cv2.Laplacian(gray, cv2.CV_64F)
                lap_var = float(np.var(lap[sat_inner > 0]))
            else:
                lap_var = 0.0

            # Vùng tâm đèn pin/laser hoàn toàn phẳng, không có texture (lap_var < 25.0) hoặc tỷ lệ cháy sáng áp đảo (> 60%)
            if lap_var < 25.0 or saturated_ratio > 0.60:
                if self._tampering_start_time is None:
                    self._tampering_start_time = now
                elif (now - self._tampering_start_time) >= self.tampering_duration_threshold:
                    is_tampering = True
            else:
                self._tampering_start_time = None
        else:
            self._tampering_start_time = None

        # ------------------------------------------------------------------
        # 2. TRỪ NỀN MOG2 & LỌC BÓNG ĐỔ (SHADOW FILTERING)
        # ------------------------------------------------------------------
        raw_fg = self.subtractor.apply(proc_frame)
        _, fg_solid = cv2.threshold(raw_fg, 200, 255, cv2.THRESH_BINARY)
        fg_roi = cv2.bitwise_and(fg_solid, fg_solid, mask=roi_mask)

        # ------------------------------------------------------------------
        # 3. KIỂM TRA CHỚP SÁNG ĐỘT NGỘT (LIGHTING SPIKE)
        # ------------------------------------------------------------------
        raw_motion_pixels = cv2.countNonZero(fg_roi)
        motion_ratio = raw_motion_pixels / float(roi_pixel_count)
        is_lighting_spike = motion_ratio >= self.lighting_spike_ratio

        # ------------------------------------------------------------------
        # 4. LỌC NHIỄU HẠT & GIÃN NỞ HỢP NHẤT ĐỐI TƯỢNG (DILATION MERGE)
        # ------------------------------------------------------------------
        fg_cleaned = cv2.morphologyEx(fg_roi, cv2.MORPH_OPEN, self.morph_kernel)
        fg_dilated = cv2.dilate(fg_cleaned, self.dilate_kernel, iterations=2)

        contours, _ = cv2.findContours(fg_dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        raw_motion_boxes: List[BoundingBox] = []
        total_motion_area = 0

        # Ngưỡng diện tích tỷ lệ theo scale
        min_area_proc = self.min_motion_area * (scale * scale)

        for cnt in contours:
            area = cv2.contourArea(cnt)
            if area >= min_area_proc:
                x, y, w, h = cv2.boundingRect(cnt)
                # Phóng ngược về tọa độ gốc của frame
                ox1 = float(x / scale)
                oy1 = float(y / scale)
                ox2 = float((x + w) / scale)
                oy2 = float((y + h) / scale)
                orig_area = int(area / (scale * scale))
                raw_motion_boxes.append(BoundingBox(
                    x1=ox1,
                    y1=oy1,
                    x2=ox2,
                    y2=oy2,
                    confidence=min(1.0, float(orig_area) / float(self.min_motion_area * 2))
                ))
                total_motion_area += orig_area

        # Hợp nhất các bounding box ở gần nhau thành 1 khung duy nhất
        motion_boxes = self._merge_close_boxes(raw_motion_boxes, distance_thresh=50.0)

        has_motion = len(motion_boxes) > 0 and not is_lighting_spike

        return MotionResult(
            has_motion=has_motion,
            motion_area=total_motion_area,
            motion_boxes=motion_boxes,
            is_lighting_spike=is_lighting_spike,
            is_tampering=is_tampering,
            details=f"motion_pixels={raw_motion_pixels}, area={total_motion_area}, boxes={len(motion_boxes)}"
        )
