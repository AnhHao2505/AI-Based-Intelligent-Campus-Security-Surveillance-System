"""
Script kiểm thử độc lập cho AI Service:
- Kiểm tra Human Detection (YOLOv8)
- Kiểm tra Human Tracking (ByteTrack)
- Kiểm tra cơ chế Xâm nhập khu vực cấm ROI

Cách chạy:
1. Chế độ Test Tự Động (Synthetic / Mock frames - Không cần camera phần cứng):
   python test_pipeline.py --mode auto

2. Chế độ Test Trực Tiếp qua Webcam / Camera cắm ngoài:
   python test_pipeline.py --mode webcam
"""

import sys
import os
import time
import argparse
import cv2
import numpy as np

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from src.core.entity import Point
from src.pipeline.video_pipeline import VideoPipeline

def run_webcam_test():
    print("=" * 60)
    print("   KIỂM THỬ PIPELINE AI TRỰC TIẾP QUA WEBCAM / CAMERA")
    print("=" * 60)
    
    cap = None
    for idx in [1, 2, 0]:
        c = cv2.VideoCapture(idx, cv2.CAP_DSHOW)
        if c.isOpened():
            ret, f = c.read()
            if ret and f is not None:
                cap = c
                print(f"[+] Đã mở camera Index #{idx}")
                break
            c.release()

    if cap is None:
        print("[!] Không tìm thấy camera. Chuyển sang chế độ test tự động (--mode auto).")
        run_auto_test()
        return

    ret, frame = cap.read()
    h, w, _ = frame.shape

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

    print("\n[OK] Pipeline sẵn sàng!")
    print("[*] Vùng cấm (ROI màu đỏ) được vẽ ở nửa phải màn hình.")
    print("[*] Nhấn phím 'q' trên cửa sổ để thoát.\n")

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        annotated_frame, persons, alerts = pipeline.process_frame(frame)

        for a in alerts:
            print(f">> [CẢNH BÁO MỚI]: {a.event_type} | Track #{a.track_id} | {a.details}")

        cv2.imshow("Campus Security AI - Human Tracking Test", annotated_frame)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()
    print("[*] Đã đóng camera.")

def run_auto_test():
    print("=" * 60)
    print("   CHẠY KIỂM THỬ TỰ ĐỘNG (AUTOMATED TEST PIPELINE)")
    print("=" * 60)

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

    print("[*] Đang khởi tạo mô phỏng chuỗi frame...")
    engine = pipeline.analysis_engine
    
    print("\n1. Test: Người ở ngoài ROI...")
    from src.core.entity import BoundingBox
    bbox_outside = BoundingBox(x1=50, y1=50, x2=100, y2=150)
    persons, alerts = engine.process_frame([(101, bbox_outside)], roi, current_time=1000.0)
    assert len(persons) == 1
    assert persons[0].is_in_roi == False
    assert len(alerts) == 0
    print("   -> [PASS] Không kích hoạt cảnh báo khi ở ngoài ROI.")

    print("\n2. Test: Người bước vào trong ROI...")
    bbox_inside = BoundingBox(x1=250, y1=150, x2=350, y2=350)
    persons, alerts = engine.process_frame([(101, bbox_inside)], roi, current_time=1005.0)
    assert persons[0].is_in_roi == True
    print("   -> [PASS] Ghi nhận người trong ROI.")

    print("\n3. Test: Chạy frame mẫu qua Full VideoPipeline...")
    sample_frame = np.zeros((480, 640, 3), dtype=np.uint8)
    ann_frame, p_list, a_list = pipeline.process_frame(sample_frame)
    assert ann_frame.shape == sample_frame.shape
    print("   -> [PASS] Full VideoPipeline chạy thành công không có ngoại lệ!")

    print("\n" + "=" * 60)
    print("       TẤT CẢ CÁC BÀI TEST ĐÃ HOÀN TOÀN CHÍNH XÁC (100% PASS)")
    print("=" * 60)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Test AI Pipeline")
    parser.add_argument("--mode", choices=["auto", "webcam"], default="auto", help="Chế độ test (auto hoặc webcam)")
    args = parser.parse_args()

    if args.mode == "webcam":
        run_webcam_test()
    else:
        run_auto_test()
