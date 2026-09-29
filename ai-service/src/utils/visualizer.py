import cv2
import numpy as np
from typing import List, Optional, Any, Union, Dict
from ..core.entity import TrackedPerson, Point, RoiPolygonConfig

class FrameVisualizer:
    """Tiện ích vẽ bounding box, track ID, polygon ROI, thông tin khuôn mặt và cảnh báo lên khung hình"""

    @staticmethod
    def draw_roi(
        frame: np.ndarray,
        roi_polygon: Optional[Union[List[Point], List[Any], RoiPolygonConfig]] = None,
        color=(0, 0, 255),
        thickness=2,
        label: Optional[str] = None
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

        cv2.polylines(frame, [pts], isClosed=True, color=color, thickness=thickness)

        first_pt = pixel_pts[0]
        text_label = poly_label if poly_label else "[!] KHU VUC HAN CHE (ROI)"
        cv2.putText(
            frame,
            text_label,
            (first_pt[0], max(20, first_pt[1] - 8)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            color,
            2
        )
        return frame

    @staticmethod
    def draw_multiple_rois(
        frame: np.ndarray,
        polygons: Optional[List[Any]] = None,
        thickness=2
    ) -> np.ndarray:
        if not polygons:
            return frame

        palette = [
            (0, 0, 255),    # Đỏ: Khu vực cấm
            (255, 140, 0),  # Cam: Khu vực hạn chế
            (255, 0, 128),  # Tím hồng: Cảnh báo đám đông
            (0, 215, 255),  # Vàng: Khu vực quan sát
            (50, 205, 50),  # Xanh lá: Ra vào
        ]

        for idx, poly in enumerate(polygons):
            c = palette[idx % len(palette)]
            label_text = None
            if hasattr(poly, "label") and poly.label:
                label_text = f"ROI {idx+1}: {poly.label}"
            elif isinstance(poly, dict) and poly.get("label"):
                label_text = f"ROI {idx+1}: {poly.get('label')}"

            FrameVisualizer.draw_roi(frame, poly, color=c, thickness=thickness, label=label_text)

        return frame

    @staticmethod
    def draw_tracked_persons(
        frame: np.ndarray,
        persons: List[TrackedPerson]
    ) -> np.ndarray:
        for p in persons:
            x1, y1, x2, y2 = p.bbox.to_int_xyxy()

            if p.alert_unauthorized_sent:
                box_color = (0, 0, 255) # Đỏ: Báo động vi phạm
                status_text = "XAM NHAP TRAI PHEP!"
            elif p.is_in_roi:
                box_color = (0, 165, 255) # Cam: Đang trong vùng cấm
                status_text = "Trong ROI"
            else:
                box_color = (0, 255, 0) # Xanh lá: Bình thường
                status_text = "Binh thuong"

            cv2.rectangle(frame, (x1, y1), (x2, y2), box_color, 2)

            face_str = "Chua ro mat"
            if p.face_detected and p.face_info:
                if p.face_info.matched_name:
                    face_str = f"Mat: {p.face_info.matched_name}"
                else:
                    face_str = f"Mat ({int(p.face_info.score * 100)}%)"

            label = f"ID:#{p.track_id} | {status_text} | {face_str}"
            (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
            
            label_y = max(y1 - 6, th + 6)
            cv2.rectangle(frame, (x1, label_y - th - 4), (x1 + tw + 6, label_y + 2), box_color, -1)
            cv2.putText(frame, label, (x1 + 3, label_y - 2), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)

        return frame

    @staticmethod
    def draw_entry_line(
        frame: np.ndarray,
        entry_line: Any,
        color=(255, 255, 0),
        thickness=2
    ) -> np.ndarray:
        if not entry_line:
            return frame

        h, w = frame.shape[:2]
        pa = None
        pb = None
        label = ""
        direction = "AB_IS_IN"

        if hasattr(entry_line, "point_a") and hasattr(entry_line, "point_b"):
            pa = entry_line.point_a
            pb = entry_line.point_b
            label = getattr(entry_line, "label", "")
            direction = getattr(entry_line, "direction", "AB_IS_IN")
        elif isinstance(entry_line, dict):
            pa_dict = entry_line.get("point_a", {})
            pb_dict = entry_line.get("point_b", {})
            pa = Point(float(pa_dict.get("x", 0.0)), float(pa_dict.get("y", 0.0)))
            pb = Point(float(pb_dict.get("x", 0.0)), float(pb_dict.get("y", 0.0)))
            label = entry_line.get("label", "")
            direction = entry_line.get("direction", "AB_IS_IN")

        if not pa or not pb:
            return frame

        ax = int(pa.x * w) if 0.0 <= pa.x <= 1.0 else int(pa.x)
        ay = int(pa.y * h) if 0.0 <= pa.y <= 1.0 else int(pa.y)
        bx = int(pb.x * w) if 0.0 <= pb.x <= 1.0 else int(pb.x)
        by = int(pb.y * h) if 0.0 <= pb.y <= 1.0 else int(pb.y)

        cv2.line(frame, (ax, ay), (bx, by), color, thickness)
        cv2.circle(frame, (ax, ay), 5, (0, 200, 255), -1)
        cv2.circle(frame, (bx, by), 5, (0, 100, 255), -1)

        mx, my = (ax + bx) // 2, (ay + by) // 2
        dx, dy = bx - ax, by - ay
        length = float(np.hypot(dx, dy))
        if length > 5:
            nx, ny = dy / length, -dx / length
            if direction == "AB_IS_OUT":
                nx, ny = -nx, -ny
            arrow_len = 25
            ex, ey = int(mx + nx * arrow_len), int(my + ny * arrow_len)
            cv2.arrowedLine(frame, (mx, my), (ex, ey), (0, 255, 255), 2, tipLength=0.35)

        lbl_text = f"{label} [IN]" if label else "[IN]"
        cv2.putText(frame, lbl_text, (mx + 5, my - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)
        return frame

    @staticmethod
    def draw_all_rois(
        frame: np.ndarray,
        polygons: Optional[List[Any]] = None,
        entry_lines: Optional[List[Any]] = None,
        thickness=2
    ) -> np.ndarray:
        if polygons:
            frame = FrameVisualizer.draw_multiple_rois(frame, polygons, thickness=thickness)
        if entry_lines:
            for line in entry_lines:
                frame = FrameVisualizer.draw_entry_line(frame, line, thickness=thickness)
        return frame
