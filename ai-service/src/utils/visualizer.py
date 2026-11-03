import cv2
import numpy as np
from typing import List, Optional, Any, Union
from ..core.entity import TrackedPerson, Point, RoiPolygonConfig

class FrameVisualizer:
    """Tiện ích vẽ bounding box, track ID, polygon ROI, thông tin khuôn mặt và cảnh báo lên khung hình"""

    @staticmethod
    def draw_roi(
        frame: np.ndarray,
        roi_polygon: Optional[Union[List[Point], List[Any], RoiPolygonConfig]] = None,
        color=(0, 0, 255),
        thickness=2,
        label: Optional[str] = None,
        draw_label: bool = False
    ) -> np.ndarray:
        if not roi_polygon:
            return frame

        h, w = frame.shape[:2]

        pts_list: List[Point] = []
        poly_label = label

        if isinstance(roi_polygon, RoiPolygonConfig):
            pts_list = roi_polygon.vertices
            if not poly_label:
                poly_label = roi_polygon.label
        elif isinstance(roi_polygon, dict):
            raw_vertices = roi_polygon.get("vertices", [])
            for item in raw_vertices:
                if isinstance(item, Point):
                    pts_list.append(item)
                elif isinstance(item, dict):
                    pts_list.append(Point(float(item.get("x", 0.0)), float(item.get("y", 0.0))))
                elif isinstance(item, (tuple, list)) and len(item) >= 2:
                    pts_list.append(Point(float(item[0]), float(item[1])))
            if not poly_label:
                poly_label = roi_polygon.get("label", "")
        elif isinstance(roi_polygon, (list, tuple)):
            for item in roi_polygon:
                if isinstance(item, Point):
                    pts_list.append(item)
                elif isinstance(item, dict):
                    pts_list.append(Point(float(item.get("x", 0.0)), float(item.get("y", 0.0))))
                elif isinstance(item, (tuple, list)) and len(item) >= 2:
                    pts_list.append(Point(float(item[0]), float(item[1])))

        if len(pts_list) < 3:
            return frame

        pixel_pts = []
        for p in pts_list:
            if 0.0 <= p.x <= 1.0 and 0.0 <= p.y <= 1.0 and w > 1 and h > 1:
                px = int(round(p.x * w))
                py = int(round(p.y * h))
            else:
                px = int(round(p.x))
                py = int(round(p.y))
            pixel_pts.append([px, py])

        pts = np.array(pixel_pts, np.int32).reshape((-1, 1, 2))

        # Chỉ vẽ đường viền đa giác đỏ (tối giản, sắc nét, không che khuất khung hình)
        roi_thickness = max(2, int(round(w / 600.0)))
        cv2.polylines(frame, [pts], isClosed=True, color=color, thickness=roi_thickness)

        if not draw_label or not poly_label:
            return frame

        first_pt = pixel_pts[0]
        text_label = poly_label
        font_scale = max(0.60, min(w / 1400.0, 1.2))
        font_thickness = max(2, int(round(font_scale * 1.6)))
        (tw, th), baseline = cv2.getTextSize(text_label, cv2.FONT_HERSHEY_SIMPLEX, font_scale, font_thickness)

        rx = max(6, min(w - tw - 16, first_pt[0]))
        ry = max(th + 12, first_pt[1] - 8)

        cv2.rectangle(frame, (rx - 5, ry - th - 6), (rx + tw + 7, ry + 4), (20, 20, 20), -1)
        cv2.rectangle(frame, (rx - 5, ry - th - 6), (rx + tw + 7, ry + 4), color, 2)
        cv2.putText(
            frame,
            text_label,
            (rx + 2, ry - 2),
            cv2.FONT_HERSHEY_SIMPLEX,
            font_scale,
            (255, 255, 255),
            font_thickness,
            cv2.LINE_AA
        )
        return frame

    @staticmethod
    def draw_multiple_rois(
        frame: np.ndarray,
        polygons: Optional[List[Any]] = None,
        thickness=2,
        draw_label: bool = False
    ) -> np.ndarray:
        if not polygons:
            return frame

        for poly in polygons:
            # Đường viền đa giác màu đỏ (0, 0, 255), ẩn hoàn toàn nhãn chữ
            FrameVisualizer.draw_roi(frame, poly, color=(0, 0, 255), thickness=thickness, draw_label=draw_label)

        return frame

    @staticmethod
    def draw_tracked_persons(
        frame: np.ndarray,
        persons: List[TrackedPerson],
        only_in_roi: bool = False
    ) -> np.ndarray:
        h, w = frame.shape[:2]
        # Kích thước chữ và nét vẽ thích ứng theo độ phân giải khung hình (to, rõ, dễ nhìn)
        font_scale = max(0.65, min(w / 1400.0, 1.4))
        font_thickness = max(2, int(round(font_scale * 1.8)))
        box_thickness = max(2, int(round(w / 500.0)))

        # Khử trùng lặp NMS trước khi vẽ: đảm bảo 1 người chỉ vẽ đúng 1 bounding box
        visible_candidates = [
            p for p in persons
            if getattr(p, "is_currently_visible", True)
        ]
        visible_candidates.sort(
            key=lambda p: (
                1 if (p.face_detected and p.face_info and p.face_info.matched_code) else 0,
                1 if (p.alert_unauthorized_sent or getattr(p, "alert_unknown_sent", False)) else 0,
                getattr(p.bbox, "confidence", 1.0)
            ),
            reverse=True
        )

        deduped_persons = []
        for cand in visible_candidates:
            is_dup = False
            for chosen in deduped_persons:
                if cand.bbox.iou(chosen.bbox) > 0.35 or cand.bbox.intersection_ratio(chosen.bbox) > 0.55:
                    is_dup = True
                    break
            if not is_dup:
                deduped_persons.append(cand)

        for p in deduped_persons:

            # Nếu bật chế độ only_in_roi: Chỉ vẽ khung cho người đang trong ROI (hoặc đang có cảnh báo vi phạm)
            if only_in_roi and not p.is_in_roi:
                if not (p.alert_unauthorized_sent or getattr(p, "alert_unknown_sent", False) or getattr(p, "alert_after_hours_sent", False)):
                    continue

            x1, y1, x2, y2 = p.bbox.to_int_xyxy()

            # Xác định các trạng thái nhận diện & phân quyền
            is_identified = bool(p.face_detected and p.face_info and p.face_info.matched_code)
            matched_name = p.face_info.matched_name if (is_identified and p.face_info.matched_name) else None
            is_authorized = bool(is_identified and p.face_info.is_authorized)
            is_after_hours = getattr(p, "alert_after_hours_sent", False)
            is_unauthorized = bool(p.alert_unauthorized_sent or (is_identified and not p.face_info.is_authorized))

            # Quy tắc màu và nhãn: Tối đa 1 dòng, tối đa 3 cụm thông tin ngăn cách bởi "|"
            # 1. Ngoài vùng ROI: Màu default (Xanh lá)
            if not p.is_in_roi:
                box_color = (0, 255, 0)  # Xanh lá
                label = f"ID:#{p.track_id} | {matched_name}" if matched_name else f"ID:#{p.track_id}"
            # 2. Trong vùng ROI
            else:
                # Đột nhập ngoài giờ
                if is_after_hours:
                    box_color = (0, 0, 255)  # Đỏ
                    label = f"ID:#{p.track_id} | {matched_name} | PERSON_INTRUSION" if matched_name else f"ID:#{p.track_id} | PERSON_INTRUSION"
                # Hợp lệ
                elif is_authorized:
                    box_color = (0, 255, 0)  # Xanh lá: Hợp lệ
                    label = f"ID:#{p.track_id} | {matched_name}" if matched_name else f"ID:#{p.track_id} | AUTHORIZED_PERSON"
                # Không có quyền / Sai giờ
                elif is_unauthorized:
                    box_color = (0, 0, 255)  # Đỏ: Vi phạm
                    label = f"ID:#{p.track_id} | {matched_name} | UNAUTHORIZED_PERSON" if matched_name else f"ID:#{p.track_id} | UNAUTHORIZED_PERSON"
                # Người lạ trong ROI
                else:
                    box_color = (0, 0, 255)  # Đỏ: Người lạ trong ROI
                    label = f"ID:#{p.track_id} | UNKNOWN_PERSON"

            # Vẽ bounding box chính
            cv2.rectangle(frame, (x1, y1), (x2, y2), box_color, box_thickness)

            # Vẽ 4 góc định vị (Corner Target Accents) tạo hiệu ứng OSD chuyên nghiệp
            corner_len = max(14, int(min(x2 - x1, y2 - y1) * 0.15))
            accent_thickness = box_thickness + 2
            cv2.line(frame, (x1, y1), (x1 + corner_len, y1), box_color, accent_thickness)
            cv2.line(frame, (x1, y1), (x1, y1 + corner_len), box_color, accent_thickness)
            cv2.line(frame, (x2, y1), (x2 - corner_len, y1), box_color, accent_thickness)
            cv2.line(frame, (x2, y1), (x2, y1 + corner_len), box_color, accent_thickness)
            cv2.line(frame, (x1, y2), (x1 + corner_len, y2), box_color, accent_thickness)
            cv2.line(frame, (x1, y2), (x1, y2 - corner_len), box_color, accent_thickness)
            cv2.line(frame, (x2, y2), (x2 - corner_len, y2), box_color, accent_thickness)
            cv2.line(frame, (x2, y2), (x2, y2 - corner_len), box_color, accent_thickness)

            (tw, th), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, font_scale, font_thickness)
            badge_h = th + baseline + 12
            badge_w = tw + 16

            badge_y1 = max(0, y1 - badge_h) if (y1 - badge_h >= 0) else y1
            badge_y2 = badge_y1 + badge_h
            badge_x2 = min(w, x1 + badge_w)

            # Nền badge chữ nhật đặc với viền trắng tương phản cao
            cv2.rectangle(frame, (x1, badge_y1), (badge_x2, badge_y2), box_color, -1)
            cv2.rectangle(frame, (x1, badge_y1), (badge_x2, badge_y2), (255, 255, 255), 1)

            # Màu chữ: Chữ đen trên nền xanh lá, chữ trắng trên nền đỏ/cam
            text_color = (0, 0, 0) if box_color == (0, 255, 0) else (255, 255, 255)
            cv2.putText(
                frame,
                label,
                (x1 + 8, badge_y1 + th + 4),
                cv2.FONT_HERSHEY_SIMPLEX,
                font_scale,
                text_color,
                font_thickness,
                cv2.LINE_AA
            )

        return frame

    @staticmethod
    def draw_motion_boxes(
        frame: np.ndarray,
        motion_boxes: List[Any],
        color=(0, 140, 255),
        thickness=2,
        label="SUSPICIOUS_MOVING_OBJECT"
    ) -> np.ndarray:
        if not motion_boxes:
            return frame

        h, w = frame.shape[:2]
        font_scale = max(0.60, min(w / 1400.0, 1.3))
        font_thickness = max(2, int(round(font_scale * 1.8)))
        box_thickness = max(2, int(round(w / 550.0)))

        for box in motion_boxes:
            x1, y1, x2, y2 = box.to_int_xyxy()
            cv2.rectangle(frame, (x1, y1), (x2, y2), color, box_thickness)

            (tw, th), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, font_scale, font_thickness)
            badge_h = th + baseline + 10
            badge_w = tw + 14

            badge_y1 = max(0, y1 - badge_h) if (y1 - badge_h >= 0) else y1
            badge_y2 = badge_y1 + badge_h
            badge_x2 = min(w, x1 + badge_w)

            cv2.rectangle(frame, (x1, badge_y1), (badge_x2, badge_y2), color, -1)
            cv2.rectangle(frame, (x1, badge_y1), (badge_x2, badge_y2), (255, 255, 255), 1)
            cv2.putText(
                frame,
                label,
                (x1 + 7, badge_y1 + th + 3),
                cv2.FONT_HERSHEY_SIMPLEX,
                font_scale,
                (255, 255, 255),
                font_thickness,
                cv2.LINE_AA
            )
        return frame

    @staticmethod
    def draw_all_rois(
        frame: np.ndarray,
        polygons: Optional[List[Any]] = None,
        thickness=2
    ) -> np.ndarray:
        if polygons:
            frame = FrameVisualizer.draw_multiple_rois(frame, polygons, thickness=thickness)
        return frame
