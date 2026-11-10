import sys
import os
import time
import torch
import numpy as np
import cv2
from ultralytics import YOLO

def test_gtx1650_fp16():
    print("=" * 70)
    print("TEST OPT-02: ĐO THỰC TẾ FP32 VS FP16 TRÊN GPU GTX 1650")
    print("=" * 70)
    
    device_name = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU"
    print(f"Device: {device_name}")
    print(f"CUDA Available: {torch.cuda.is_available()}")
    
    model_path = os.path.abspath("yolo26n.pt")
    if not os.path.exists(model_path):
        model_path = os.path.abspath("../../yolo26n.pt")
        
    model = YOLO(model_path)
    
    # Sử dụng video thật từ evidence nếu có
    sample_video = "evidence/CAM-002/2026/10/31/Incident 31-10-2026 - 16-29-29.mp4"
    if not os.path.exists(sample_video):
        sample_video = os.path.join("..", sample_video)
        
    frames = []
    if os.path.exists(sample_video):
        cap = cv2.VideoCapture(sample_video)
        while len(frames) < 60:
            ret, f = cap.read()
            if not ret: break
            frames.append(f)
        cap.release()
        print(f"Đã nạp {len(frames)} frames thực tế từ: {sample_video} ({frames[0].shape})")
    else:
        print("Không tìm thấy video mẫu, sinh 60 frames 720p ngẫu nhiên...")
        frames = [np.random.randint(0, 255, (720, 1280, 3), dtype=np.uint8) for _ in range(60)]

    # Warmup
    for _ in range(5):
        model(frames[0], device=0, verbose=False)

    # 1. Đo FP32 (Mặc định)
    latencies_fp32 = []
    detections_fp32 = []
    torch.cuda.synchronize()
    for f in frames:
        t0 = time.perf_counter()
        res = model(f, device=0, half=False, classes=[0], verbose=False)
        torch.cuda.synchronize()
        latencies_fp32.append((time.perf_counter() - t0) * 1000.0)
        boxes = res[0].boxes
        b_list = []
        if boxes is not None:
            for b in boxes:
                xyxy = b.xyxy[0].cpu().numpy().tolist()
                conf = float(b.conf[0].cpu().numpy())
                b_list.append((xyxy, conf))
        detections_fp32.append(b_list)

    # Warmup FP16
    for _ in range(5):
        model(frames[0], device=0, half=True, verbose=False)

    # 2. Đo FP16 (half=True)
    latencies_fp16 = []
    detections_fp16 = []
    torch.cuda.synchronize()
    for f in frames:
        t0 = time.perf_counter()
        res = model(f, device=0, half=True, classes=[0], verbose=False)
        torch.cuda.synchronize()
        latencies_fp16.append((time.perf_counter() - t0) * 1000.0)
        boxes = res[0].boxes
        b_list = []
        if boxes is not None:
            for b in boxes:
                xyxy = b.xyxy[0].cpu().numpy().tolist()
                conf = float(b.conf[0].cpu().numpy())
                b_list.append((xyxy, conf))
        detections_fp16.append(b_list)

    # Thống kê độ trễ
    p50_fp32 = np.percentile(latencies_fp32, 50)
    p95_fp32 = np.percentile(latencies_fp32, 95)
    mean_fp32 = np.mean(latencies_fp32)

    p50_fp16 = np.percentile(latencies_fp16, 50)
    p95_fp16 = np.percentile(latencies_fp16, 95)
    mean_fp16 = np.mean(latencies_fp16)

    print("\n--- KẾT QUẢ ĐO ĐỘ TRỄ SUY LUẬN (INFERENCE LATENCY) ---")
    print(f"FP32: Mean = {mean_fp32:.2f} ms | P50 = {p50_fp32:.2f} ms | P95 = {p95_fp32:.2f} ms (~{1000/mean_fp32:.1f} FPS)")
    print(f"FP16: Mean = {mean_fp16:.2f} ms | P50 = {p50_fp16:.2f} ms | P95 = {p95_fp16:.2f} ms (~{1000/mean_fp16:.1f} FPS)")
    speedup = (mean_fp32 / mean_fp16 - 1) * 100
    print(f"===> Tốc độ thay đổi: {speedup:+.1f}%")

    # So sánh độ chính xác detection giữa FP32 và FP16
    diff_count = 0
    total_det = 0
    iou_diffs = []
    conf_diffs = []

    for d32, d16 in zip(detections_fp32, detections_fp16):
        if len(d32) != len(d16):
            diff_count += abs(len(d32) - len(d16))
        total_det += len(d32)
        for (box32, conf32), (box16, conf16) in zip(d32, d16):
            conf_diffs.append(abs(conf32 - conf16))
            # Tính IoU
            xA = max(box32[0], box16[0])
            yA = max(box32[1], box16[1])
            xB = min(box32[2], box16[2])
            yB = min(box32[3], box16[3])
            inter = max(0, xB - xA) * max(0, yB - yA)
            area32 = (box32[2] - box32[0]) * (box32[3] - box32[1])
            area16 = (box16[2] - box16[0]) * (box16[3] - box16[1])
            iou = inter / max(1.0, area32 + area16 - inter)
            iou_diffs.append(1.0 - iou)

    print("\n--- SO SÁNH ĐỘ CHÍNH XÁC PHÁT HIỆN (DETECTION ACCURACY MATCH) ---")
    print(f"Tổng số detection FP32: {total_det}")
    print(f"Số lượng detection lệch số box: {diff_count}")
    if conf_diffs:
        print(f"Độ lệch điểm tin cậy trung bình (|conf32 - conf16|): {np.mean(conf_diffs):.4f}")
        print(f"Độ lệch IoU trung bình (1 - IoU): {np.mean(iou_diffs):.4f}")

if __name__ == "__main__":
    test_gtx1650_fp16()
