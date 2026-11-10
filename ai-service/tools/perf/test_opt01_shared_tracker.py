import sys
import os
import cv2
import numpy as np
import threading
from ultralytics import YOLO

# Test xem Ultralytics model.track() có dùng chung tracker giữa các camera không
def test_shared_tracker_state():
    print("=" * 60)
    print("TEST OPT-01: KIỂM CHỨNG TRẠNG THÁI BYTETRACK TRONG ULTRALYTICS")
    print("=" * 60)
    
    model_path = os.path.abspath("yolo26n.pt")
    if not os.path.exists(model_path):
        model_path = os.path.abspath("../../yolo26n.pt")
        
    model = YOLO(model_path)
    
    # Tạo 2 frame giả lập: 
    # Camera A: người ở góc trái (x=50..100)
    # Camera B: người ở góc phải (x=500..550)
    frame_a1 = np.zeros((480, 640, 3), dtype=np.uint8)
    cv2.rectangle(frame_a1, (50, 100), (120, 300), (200, 200, 200), -1)

    frame_b1 = np.zeros((480, 640, 3), dtype=np.uint8)
    cv2.rectangle(frame_b1, (500, 100), (570, 300), (200, 200, 200), -1)

    print("\n1. Kiểm tra thuộc tính model.predictor:")
    # Chạy 1 lần trên Camera A
    res_a = model.track(frame_a1, persist=True, classes=[0], verbose=False)
    predictor = getattr(model, "predictor", None)
    print(f"  - Predictor tồn tại: {predictor is not None}")
    if predictor:
        trackers = getattr(predictor, "trackers", None)
        print(f"  - Predictor.trackers: {trackers} (type: {type(trackers)}, len: {len(trackers) if trackers else 0})")
        if trackers:
            print(f"  - Tracker class: {type(trackers[0])}")

    # Chạy tiếp trên Camera B với CÙNG model
    res_b = model.track(frame_b1, persist=True, classes=[0], verbose=False)
    trackers_after_b = getattr(predictor, "trackers", None)
    print(f"  - Sau khi Camera B gọi model.track: Predictor.trackers len = {len(trackers_after_b) if trackers_after_b else 0}")
    print(f"  - Camera A và Camera B có chung 1 tracker list không: {trackers is trackers_after_b}")
    
    # Kiểm tra thread-safety khi 2 camera gọi đồng thời
    errors = []
    def cam_worker(name, frame, num_frames):
        for i in range(num_frames):
            try:
                res = model.track(frame, persist=True, classes=[0], verbose=False)
            except Exception as e:
                errors.append(f"{name} frame {i}: {e}")

    threads = [
        threading.Thread(target=cam_worker, args=("Cam_A", frame_a1, 10)),
        threading.Thread(target=cam_worker, args=("Cam_B", frame_b1, 10)),
    ]
    for t in threads: t.start()
    for t in threads: t.join()

    print(f"\n2. Kiểm tra xung đột đa luồng đồng thời (Race Condition):")
    print(f"  - Số lỗi phát sinh khi gọi đồng thời không khóa: {len(errors)}")
    for err in errors[:3]:
        print(f"    * {err}")

    return predictor is not None and trackers is trackers_after_b

if __name__ == "__main__":
    test_shared_tracker_state()
