import cv2
import numpy as np
from typing import List, Optional
from ..core.entity import TrackedPerson, Point

class FrameVisualizer:
    """Tiện ích vẽ bounding box, track ID, polygon ROI, thông tin khuôn mặt và cảnh báo lên khung hình"""

    @staticmethod
    def draw_roi(frame: np.ndarray, roi_polygon: Optional[List[Point]], color=(0, 0, 255), thickness=2) -> np.ndarray:
        """Vẽ đa giác vùng cấm (Restricted Area ROI)"""
        if not roi_polygon or len(roi_polygon) < 3:
            return frame

        pts = np.array([[int(p.x), int(p.y)] for p in roi_polygon], np.int32)
        pts = pts.reshape((-1, 1, 2))
        
        # Vẽ viền polygon
        cv2.polylines(frame, [pts], isClosed=True, color=color, thickness=thickness)
        
        # Tạo hiệu ứng nền mờ bán trong suốt (semi-transparent overlay)
        overlay = frame.copy()
        cv2.fillPoly(overlay, [pts], color)
        cv2.addWeighted(overlay, 0.15, frame, 0.85, 0, frame)

        # Ghi nhãn RESTRICTED ZONE
        first_pt = roi_polygon[0]
        cv2.putText(
            frame,
            "[!] KHU VUC HAN CHE (ROI)",
            (int(first_pt.x), max(20, int(first_pt.y) - 8)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            color,
            2
        )
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
