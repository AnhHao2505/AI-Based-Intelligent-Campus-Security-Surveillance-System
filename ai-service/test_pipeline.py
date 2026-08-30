"""
Script kiểm thử độc lập cho AI Service:
- Kiểm tra Human Detection (YOLOv8)
- Kiểm tra Human Tracking (ByteTrack)
- Kiểm tra cơ chế Loitering khi không thấy mặt người trong vùng cấm ROI

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

# Tự động set UTF-8 cho console Windows
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

# Thêm đường dẫn ai-service vào sys.path để import
sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from src.core.entity import Point
from src.pipeline.video_pipeline import VideoPipeline

def run_webcam_test():
    print("=" * 60)
    print("   KIỂM THỬ PIPELINE AI TRỰC TIẾP QUA WEBCAM / CAMERA")
    print("=" * 60)
    
    # Tìm camera hoạt động
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

    # Định nghĩa vùng cấm ROI chiếm 50% khung hình phía bên phải
    roi = [
        Point(int(w * 0.4), int(h * 0.2)),
        Point(int(w * 0.95), int(h * 0.2)),
        Point(int(w * 0.95), int(h * 0.95)),
        Point(int(w * 0.4), int(h * 0.95)),
    ]

    pipeline = VideoPipeline(
        camera_code="CAM-LAB-01",
        roi_polygon=roi,
        loitering_threshold_seconds=5, # Đặt ngưỡng 5 giây để test nhanh
        conf_threshold=0.4
    )

    print("\n[OK] Pipeline sẵn sàng!")
    print("[*] Vùng cấm (ROI màu đỏ) được vẽ ở nửa phải màn hình.")
    print("[*] Hãy thử bước vào vùng cấm:")
    print("    - Nếu quay mặt đi / che mặt và đứng quá 5s -> Báo động LOITERING_UNIDENTIFIED_PERSON!")
    print("[*] Nhấn phím 'q' trên cửa sổ để thoát.\n")

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        annotated_frame, persons, alerts = pipeline.process_frame(frame)

        for a in alerts:
            print(f">> [CẢNH BÁO MỚI]: {a.event_type} | Track #{a.track_id} | Thời gian: {a.duration_seconds:.1f}s | {a.details}")

        cv2.imshow("Campus Security AI - Human Tracking & Loitering Test", annotated_frame)
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
        loitering_threshold_seconds=3, # 3s để test nhanh
        conf_threshold=0.3
    )

    print("[*] Đang khởi tạo mô phỏng chuỗi frame...")
    # Tạo frame trắng
    blank_frame = np.ones((h, w, 3), dtype=np.uint8) * 128

    # Test logic Loitering Engine trực tiếp
    engine = pipeline.loitering_engine
    
    # 1. Giả lập một người (Track #101) xuất hiện bên ngoài ROI
    print("\n1. Test: Người ở ngoài ROI...")
    tracks_outside = [(101, pipeline.human_detector.model_path and None)] # Tạo dummy bbox
    from src.core.entity import BoundingBox
    bbox_outside = BoundingBox(x1=50, y1=50, x2=100, y2=150) # bottom_center = (75, 150) -> Ngoài ROI
    persons, alerts = engine.process_frame([(101, bbox_outside)], roi, current_time=1000.0)
    assert len(persons) == 1
    assert persons[0].is_in_roi == False
    assert len(alerts) == 0
    print("   -> [PASS] Không kích hoạt cảnh báo khi ở ngoài ROI.")

    # 2. Giả lập người (Track #101) bước vào trong ROI tại t = 1005s
    print("\n2. Test: Người bước vào trong ROI...")
    bbox_inside = BoundingBox(x1=250, y1=150, x2=350, y2=350) # bottom_center = (300, 350) -> Trong ROI
    persons, alerts = engine.process_frame([(101, bbox_inside)], roi, current_time=1005.0)
    assert persons[0].is_in_roi == True
    assert persons[0].loiter_duration == 0.0
    assert len(alerts) == 0
    print("   -> [PASS] Ghi nhận bắt đầu tính thời gian lưu trú trong ROI.")

    # 3. Giả lập người lưu lại trong ROI 2 giây (t = 1007s < ngưỡng 3s)
    print("\n3. Test: Người ở trong ROI 2 giây (< ngưỡng 3s)...")
    persons, alerts = engine.process_frame([(101, bbox_inside)], roi, current_time=1007.0)
    assert persons[0].loiter_duration == 2.0
    assert len(alerts) == 0
    print("   -> [PASS] Chưa vượt ngưỡng, không báo động giả.")

    # 4. Giả lập người ở trong ROI 4 giây (t = 1009s > ngưỡng 3s) mà không có khuôn mặt hợp lệ
    print("\n4. Test: Người ở trong ROI 4 giây mà không nhận diện được mặt...")
    persons, alerts = engine.process_frame([(101, bbox_inside)], roi, current_time=1009.0)
    assert persons[0].loiter_duration == 4.0
    assert len(alerts) == 1
    assert alerts[0].event_type == "LOITERING_UNIDENTIFIED_PERSON"
    assert alerts[0].track_id == 101
    print(f"   -> [PASS] ĐÃ KÍCH HOẠT CẢNH BÁO: {alerts[0].event_type} cho Track #{alerts[0].track_id} sau {alerts[0].duration_seconds}s!")

    # 5. Giả lập frame tiếp theo (t = 1010s) -> Không spam lại cảnh báo
    print("\n5. Test: Chống spam cảnh báo lặp lại...")
    persons, alerts = engine.process_frame([(101, bbox_inside)], roi, current_time=1010.0)
    assert len(alerts) == 0
    print("   -> [PASS] Không gửi trùng lặp cảnh báo cho cùng một track_id.")

    # 6. Test xử lý 1 frame thật qua full pipeline
    print("\n6. Test: Chạy frame mẫu qua Full VideoPipeline...")
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
