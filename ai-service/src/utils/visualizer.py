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
        """
        Vẽ đa giác vùng ROI lên khung hình với cơ chế tự động co giãn pixel theo frame:
        - Tự động scale nếu đỉnh đa giác ở dạng chuẩn hóa [0.0, 1.0]
        - Hỗ trợ danh sách Point, Dict hoặc đối tượng RoiPolygonConfig
        """
        if not roi_polygon:
            return frame

        h, w = frame.shape[:2]

        # Trích xuất danh sách các Point
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

        # Chuyển đổi chuẩn hóa [0.0..1.0] -> pixel frame thực tế
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

        # Vẽ viền polygon
        cv2.polylines(frame, [pts], isClosed=True, color=color, thickness=thickness)

        # Tạo hiệu ứng nền mờ bán trong suốt (semi-transparent overlay)
        overlay = frame.copy()
        cv2.fillPoly(overlay, [pts], color)
        cv2.addWeighted(overlay, 0.15, frame, 0.85, 0, frame)

        # Ghi nhãn vùng ROI
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
        """Vẽ toàn bộ các vùng ROI với nhãn và màu sắc nhận diện trực quan"""
        if not polygons:
            return frame

        palette = [
            (0, 0, 255),    # Đỏ: Khu vực cấm / Loitering
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
        persons: List[TrackedPerson],
        loitering_threshold: int = 10
    ) -> np.ndarray:
        """Vẽ thông tin theo dõi của từng người lên khung hình"""
        for p in persons:
            x1, y1, x2, y2 = p.bbox.to_int_xyxy()

            # Xác định màu sắc dựa trên trạng thái
            if p.alert_loitering_sent or p.alert_unauthorized_sent:
                box_color = (0, 0, 255) # Đỏ: Báo động vi phạm
                status_text = "VI PHAM LOITERING!" if p.alert_loitering_sent else "XAM NHAP TRAI PHEP!"
            elif p.is_in_roi:
                box_color = (0, 165, 255) # Cam: Đang trong vùng cấm
                status_text = f"Trong ROI ({p.loiter_duration:.1f}s/{loitering_threshold}s)"
            else:
                box_color = (0, 255, 0) # Xanh lá: Bình thường
                status_text = "Binh thuong"

            # Vẽ bounding box người
            cv2.rectangle(frame, (x1, y1), (x2, y2), box_color, 2)

            # Vẽ quỹ đạo di chuyển (Trajectory)
            if len(p.trajectory) > 1:
                for i in range(1, len(p.trajectory)):
                    pt1 = (int(p.trajectory[i-1].x), int(p.trajectory[i-1].y))
                    pt2 = (int(p.trajectory[i].x), int(p.trajectory[i].y))
                    cv2.line(frame, pt1, pt2, box_color, 2)

            # Nhãn thông tin khuôn mặt
            face_str = "Chua ro mat"
            if p.face_detected and p.face_info:
                if p.face_info.matched_name:
                    face_str = f"Mat: {p.face_info.matched_name}"
                else:
                    face_str = f"Mat ({int(p.face_info.score * 100)}%)"

            # Tag thông tin trên đầu người
            label = f"ID:#{p.track_id} | {status_text} | {face_str}"
            (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
            
            label_y = max(y1 - 6, th + 6)
            cv2.rectangle(frame, (x1, label_y - th - 4), (x1 + tw + 6, label_y + 2), box_color, -1)
            cv2.putText(frame, label, (x1 + 3, label_y - 2), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)

            # Vẽ khuôn mặt (nếu có)
            if p.face_detected and p.face_info:
                fx1, fy1, fx2, fy2 = p.face_info.bbox.to_int_xyxy()
                cv2.rectangle(frame, (fx1, fy1), (fx2, fy2), (255, 255, 0), 1)
                if p.face_info.landmarks:
                    for lx, ly in p.face_info.landmarks:
                        cv2.circle(frame, (int(lx), int(ly)), 2, (0, 255, 255), -1)

        return frame

    @staticmethod
    def draw_dashboard_overlay(
        frame: np.ndarray,
        camera_code: str,
        fps: float,
        total_persons: int,
        loitering_count: int
    ) -> np.ndarray:
        """Vẽ banner thống kê trên đầu khung hình"""
        h, w, _ = frame.shape
        cv2.rectangle(frame, (0, 0), (w, 40), (20, 20, 20), -1)
        
        info_text = f"CAM: {camera_code} | FPS: {fps:.1f} | Nguoi: {total_persons} | Dang lang vang: {loitering_count}"
        cv2.putText(frame, info_text, (15, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
        return frame
