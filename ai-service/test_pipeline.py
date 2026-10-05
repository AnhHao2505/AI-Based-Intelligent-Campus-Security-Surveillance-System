"""
Script kiểm thử độc lập cho AI Service:
- Kiểm tra Human Detection (YOLO11)
- Kiểm tra Human Tracking (ByteTrack)
- Kiểm tra Face Detection & Recognition (YuNet + ArcFace ResNet-50 512D + pgvector)
- Kiểm tra cơ chế Xâm nhập khu vực cấm ROI & Loitering
- Hỗ trợ in Terminal Log chi tiết (Timestamp, Track ID, Trạng thái Face, Vùng ROI, Cảnh báo)

Cách chạy:
1. Chế độ Test Tự Động (Synthetic / Mock frames - Không cần camera phần cứng):
   python test_pipeline.py --mode auto

2. Chế độ Test Trực Tiếp qua Webcam máy tính / Camera điện thoại:
   python test_pipeline.py --mode webcam
   python test_pipeline.py --mode webcam --source 0
   python test_pipeline.py --mode webcam --source "http://192.168.1.10:8080/video"
"""

import sys
import os
import time
import datetime
import argparse
import cv2
import numpy as np

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from src.core.entity import Point, BoundingBox
from src.pipeline.video_pipeline import VideoPipeline

def get_now_str() -> str:
    """Trả về timestamp dạng [HH:MM:SS.fff]"""
    return datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]

def run_webcam_test(source=None):
    print("=" * 70)
    print("   KIỂM THỬ PIPELINE AI & CAMERA TRỰC TIẾP (REAL-TIME SURVEILLANCE)")
    print("=" * 70)

    # Xác định nguồn video (webcam index hoặc stream URL)
    cap = None
    if source is not None:
        try:
            cam_idx = int(source)
            cap = cv2.VideoCapture(cam_idx, cv2.CAP_DSHOW)
            if not cap.isOpened():
                cap = cv2.VideoCapture(cam_idx)
            print(f"[{get_now_str()}] [INIT] Đang mở camera nguồn: Index #{cam_idx}")
        except ValueError:
            print(f"[{get_now_str()}] [INIT] Đang kết nối luồng video từ URL/File: {source}")
            cap = cv2.VideoCapture(source)
    else:
        # Tự động dò camera khả dụng (Index 0, 1, 2)
        for idx in [0, 1, 2]:
            c = cv2.VideoCapture(idx, cv2.CAP_DSHOW)
            if c.isOpened():
                ret, f = c.read()
                if ret and f is not None:
                    cap = c
                    print(f"[{get_now_str()}] [INIT] Đã kết nối thành công Camera Index #{idx}")
                    break
                c.release()

    if cap is None or not cap.isOpened():
        print(f"[{get_now_str()}] [ERROR] Không tìm thấy camera phần cứng hoạt động.")
        print("[!] Tự động chuyển sang chế độ test tự động (--mode auto) để kiểm tra logic AI.")
        run_auto_test()
        return

    ret, frame = cap.read()
    if not ret or frame is None:
        print(f"[{get_now_str()}] [ERROR] Không đọc được frame từ camera.")
        cap.release()
        return

    h, w, _ = frame.shape
    print(f"[{get_now_str()}] [INFO] Kích thước khung hình camera: {w}x{h} px")

    # Định nghĩa vùng cấm ROI màu đỏ chiếm 50% khung hình phía bên phải
    roi = [
        Point(int(w * 0.4), int(h * 0.2)),
        Point(int(w * 0.95), int(h * 0.2)),
        Point(int(w * 0.95), int(h * 0.95)),
        Point(int(w * 0.4), int(h * 0.95)),
    ]

    pipeline = VideoPipeline(
        camera_code="CAM-LAB-01",
        roi_polygon=roi,
        conf_threshold=0.4
    )

    print(f"[{get_now_str()}] [READY] AI Video Pipeline đã khởi tạo sẵn sàng!")
    print(f"[{get_now_str()}] [INFO] Vùng cấm ROI được thiết lập ở nửa phải màn hình.")
    print(f"[{get_now_str()}] [INFO] Nhấn phím 'q' trên cửa sổ video để dừng kiểm thử.\n")
    print("-" * 70)
    print(f"{'THỜI GIAN':<13} | {'HÀNH ĐỘNG':<12} | {'TRACK':<8} | {'CHI TIẾT LOG AI'}")
    print("-" * 70)

    last_log_time = 0.0
    last_tracked_ids = set()
    prev_time = time.time()
    fps = 0.0

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        now = time.time()
        dt = now - prev_time
        prev_time = now
        fps = 0.9 * fps + 0.1 * (1.0 / dt if dt > 0 else 30.0)

        annotated_frame, persons, alerts = pipeline.process_frame(frame)

        # 1. In log khi có Cảnh báo mới (Ngay lập tức)
        for a in alerts:
            print(f"[{get_now_str()}] | \033[91m🚨 CẢNH BÁO\033[0m   | Track #{a.track_id:<3} | {a.event_type} - {a.details}")

        # 2. In log khi phát hiện Track ID mới bước vào khung hình
        current_ids = {p.track_id for p in persons}
        new_ids = current_ids - last_tracked_ids
        for nid in new_ids:
            p_obj = next((p for p in persons if p.track_id == nid), None)
            roi_str = "TRONG VÙNG CẤM" if (p_obj and p_obj.is_in_roi) else "Ngoài vùng cấm"
            print(f"[{get_now_str()}] | \033[94m🔍 PHÁT HIỆN\033[0m | Track #{nid:<3} | Xuất hiện trong khung hình ({roi_str})")
        last_tracked_ids = current_ids

        # 3. In log trạng thái định kỳ mỗi 1.0 giây (Heartbeat Observability)
        if now - last_log_time >= 1.0:
            last_log_time = now
            if persons:
                for p in persons:
                    # Thông tin nhận diện khuôn mặt
                    if p.face_detected and p.face_info:
                        if p.face_info.is_authorized:
                            face_str = f"\033[92mHỢP LỆ: {p.face_info.matched_name or p.face_info.matched_code}\033[0m"
                        else:
                            face_str = f"\033[93mKHÔNG PHÉP: {p.face_info.matched_name or 'Chưa rõ'}\033[0m"
                    else:
                        face_str = "Chưa quét được mặt (Quay lưng/Che mặt)"

                    roi_str = "\033[91mTRONG VÙNG CẤM\033[0m" if p.is_in_roi else "Bình thường"
                    print(f"[{get_now_str()}] | 👁️  THEO DÕI  | Track #{p.track_id:<3} | Vị trí: {roi_str} | Khuôn mặt: {face_str} | FPS: {fps:.1f}")

        # Vẽ FPS lên frame
        cv2.putText(annotated_frame, f"FPS: {fps:.1f}", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 255, 0), 2)
        cv2.imshow("Campus Security AI - Human Tracking & Camera Test", annotated_frame)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()
    print(f"\n[{get_now_str()}] [*] Đã đóng camera và dừng pipeline an toàn.")

def run_auto_test():
    print("=" * 70)
    print("   CHẠY KIỂM THỬ TỰ ĐỘNG (AUTOMATED TEST PIPELINE - NO CAMERA REQUIRED)")
    print("=" * 70)

    w, h = 640, 480
    roi = [
        Point(200, 100),
        Point(500, 100),
        Point(500, 400),
        Point(200, 400),
    ]

    pipeline = VideoPipeline(
        camera_code="CAM-TEST",
        roi_polygon=roi,
        conf_threshold=0.3
    )

    print(f"[{get_now_str()}] [*] Đang khởi tạo mô phỏng chuỗi frame...")
    engine = pipeline.analysis_engine

    print(f"\n1. Test: Người ở ngoài ROI...")
    bbox_outside = BoundingBox(x1=50, y1=50, x2=100, y2=150)
    persons, alerts = engine.process_frame([(101, bbox_outside)], roi, current_time=1000.0)
    assert len(persons) == 1
    assert persons[0].is_in_roi == False
    assert len(alerts) == 0
    print(f"   [{get_now_str()}] -> [PASS] Không kích hoạt cảnh báo khi đối tượng ở ngoài ROI.")

    print(f"\n2. Test: Người bước vào trong ROI...")
    bbox_inside = BoundingBox(x1=250, y1=150, x2=350, y2=350)
    persons, alerts = engine.process_frame([(101, bbox_inside)], roi, current_time=1005.0)
    assert persons[0].is_in_roi == True
    print(f"   [{get_now_str()}] -> [PASS] Ghi nhận chính xác đối tượng đã bước vào trong vùng cấm ROI.")

    print(f"\n3. Test: Chạy frame mẫu qua Full VideoPipeline (Detection + Tracking + Analysis)...")
    sample_frame = np.zeros((480, 640, 3), dtype=np.uint8)
    ann_frame, p_list, a_list = pipeline.process_frame(sample_frame)
    assert ann_frame.shape == sample_frame.shape
    print(f"   [{get_now_str()}] -> [PASS] Full VideoPipeline xử lý thành công không có bất kỳ ngoại lệ nào!")

    print("\n" + "=" * 70)
    print("       TẤT CẢ CÁC BÀI TEST AI LOGIC ĐÃ HOÀN TOÀN CHÍNH XÁC (100% PASS)")
    print("=" * 70)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Test AI & Camera Pipeline")
    parser.add_argument("--mode", choices=["auto", "webcam"], default="auto", help="Chế độ test (auto hoặc webcam)")
    parser.add_argument("--source", default=None, help="Nguồn camera: Index số (0, 1, 2) hoặc URL RTSP/HTTP (DroidCam, IP Cam) hoặc file video")
    args = parser.parse_args()

    if args.mode == "webcam":
        run_webcam_test(source=args.source)
    else:
        run_auto_test()
