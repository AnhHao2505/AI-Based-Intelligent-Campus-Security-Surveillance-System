"""
Benchmark NFR-01: Alert Latency & End-to-End Pipeline Latency Measurement Script
Đo đạc độ trễ xử lý cảnh báo từ lúc phát hiện vi phạm qua Pipeline đến khi sự kiện sẵn sàng phát đi.
Tiêu chuẩn NFR-01: Thời gian xử lý cảnh báo <= 3.0 giây (3000ms).
"""

import sys
import os
import time
import cv2
import numpy as np
from typing import List

# Setup path
sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from src.core.entity import Point, BoundingBox, FaceDetectionResult
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.pipeline.video_pipeline import VideoPipeline

def run_nfr01_benchmark(num_iterations: int = 30, max_allowed_latency_sec: float = 3.0):
    print("=" * 75)
    print("      BENCHMARK NFR-01: ALERT DETECTION & PROCESSING LATENCY")
    print(f"      Standard: Latency <= {max_allowed_latency_sec*1000:.0f} ms | Iterations: {num_iterations}")
    print("=" * 75)

    # 1. Khởi tạo Engine và cấu hình ROI vùng cấm
    roi = [Point(100, 100), Point(500, 100), Point(500, 500), Point(100, 500)]
    engine = FrameAnalysisEngine()
    
    # Bbox nằm bên trong ROI
    bbox_inside = BoundingBox(x1=250, y1=150, x2=350, y2=350)
    
    latencies_ms: List[float] = []

    print(f"\n[PHASE 1] Đo đạc FrameAnalysisEngine với 30 trường hợp vi phạm:")
    for i in range(1, num_iterations + 1):
        # Reset cooldown hoặc gán track_id mới để mô phỏng sự kiện vi phạm mới mỗi lần
        track_id = 1000 + i
        face_unauth = FaceDetectionResult(
            bbox=bbox_inside,
            score=0.95,
            matched_code=f"UNAUTH_{i:03d}",
            matched_name="User Without Access",
            is_authorized=False,
            is_active=True,
            access_reason="Sinh viên không có quyền truy cập khu vực này"
        )
        engine.associate_face(track_id=track_id, face_result=face_unauth)

        # Đo đạc thời gian xử lý vi phạm
        start_time = time.perf_counter()
        persons, alerts, _ = engine.process_frame([(track_id, bbox_inside)], roi, current_time=time.time())
        elapsed_ms = (time.perf_counter() - start_time) * 1000.0
        latencies_ms.append(elapsed_ms)

        assert len(alerts) > 0, f"Lỗi: Không phát hiện cảnh báo ở lần lặp #{i}"
        alert = alerts[0]
        assert alert.event_type == "UNAUTHORIZED_ACCESS"

        if i <= 5 or i % 10 == 0 or i == num_iterations:
            print(f"  Iteration #{i:02d}: Latency = {elapsed_ms:.3f} ms | Alert: {alert.event_type} (Track #{track_id})")

    # Tính toán các chỉ số thống kê Phase 1
    avg_latency = float(np.mean(latencies_ms))
    p50_latency = float(np.percentile(latencies_ms, 50))
    p95_latency = float(np.percentile(latencies_ms, 95))
    max_latency = float(np.max(latencies_ms))
    min_latency = float(np.min(latencies_ms))

    print("\n" + "-" * 75)
    print("                 KẾT QUẢ ĐO ĐẠC NFR-01 (PHASE 1: LOGIC ENGINE)")
    print("-" * 75)
    print(f"  - Tổng số lần thử nghiệm     : {num_iterations}")
    print(f"  - Độ trễ tối thiểu (Min)     : {min_latency:.3f} ms")
    print(f"  - Độ trễ trung bình (Mean)   : {avg_latency:.3f} ms")
    print(f"  - Độ trễ Median (P50)        : {p50_latency:.3f} ms")
    print(f"  - Độ trễ phân vị 95 (P95)    : {p95_latency:.3f} ms")
    print(f"  - Độ trễ tối đa (Max)        : {max_latency:.3f} ms")
    print(f"  - Ngưỡng cam kết NFR-01      : <= {max_allowed_latency_sec*1000:.0f} ms")

    target_ms = max_allowed_latency_sec * 1000.0
    passed_p1 = p95_latency <= target_ms

    if passed_p1:
        print(f"\n  ===> [PASSED] NFR-01 PHASE 1 ĐẠT TIÊU CHUẨN! (P95 {p95_latency:.2f} ms <= {target_ms:.0f} ms)")
    else:
        print(f"\n  ===> [FAILED] NFR-01 PHASE 1 VƯỢT NGƯỠNG CHO PHÉP! (P95 {p95_latency:.2f} ms > {target_ms:.0f} ms)")

    # -------------------------------------------------------------------------
    # PHASE 2: True End-to-End Pipeline (1080p MOG2 + YOLO26 + Tracking + Draw)
    # -------------------------------------------------------------------------
    print(f"\n[PHASE 2] Đo đạc End-to-End Full VideoPipeline (1080p MOG2 + YOLO26 + Tracking + Overlay):")
    pipeline = VideoPipeline(camera_code="CAM-BENCH-01", roi_polygon=roi)

    # Khung hình Full HD 1080p (1920x1080)
    test_frame_1080p = np.full((1080, 1920, 3), 40, dtype=np.uint8)

    # Warm up 2 frames
    for _ in range(2):
        pipeline.process_frame(test_frame_1080p)

    pipeline_latencies_ms: List[float] = []
    phase2_iters = 15
    for j in range(1, phase2_iters + 1):
        frame_j = test_frame_1080p.copy()
        # Giả lập vật thể chuyển động qua các frame
        cv2.rectangle(frame_j, (200 + j * 5, 200), (300 + j * 5, 380), (200, 200, 200), -1)

        t_start = time.perf_counter()
        ann_frame, persons, alerts = pipeline.process_frame(frame_j)
        t_elapsed = (time.perf_counter() - t_start) * 1000.0
        pipeline_latencies_ms.append(t_elapsed)

        if j <= 3 or j % 5 == 0 or j == phase2_iters:
            print(f"  Frame #{j:02d} (1080p): Latency = {t_elapsed:.2f} ms | Active Persons = {len(persons)}")

    p2_avg = float(np.mean(pipeline_latencies_ms))
    p2_p50 = float(np.percentile(pipeline_latencies_ms, 50))
    p2_p95 = float(np.percentile(pipeline_latencies_ms, 95))
    p2_max = float(np.max(pipeline_latencies_ms))

    print("\n" + "-" * 75)
    print("            KẾT QUẢ ĐO ĐẠC NFR-01 END-TO-END PIPELINE (1080p)")
    print("-" * 75)
    print(f"  - Độ trễ trung bình (Mean)   : {p2_avg:.2f} ms (~{1000.0/max(1.0, p2_avg):.1f} FPS)")
    print(f"  - Độ trễ Median (P50)        : {p2_p50:.2f} ms")
    print(f"  - Độ trễ phân vị 95 (P95)    : {p2_p95:.2f} ms")
    print(f"  - Độ trễ tối đa (Max)        : {p2_max:.2f} ms")
    print(f"  - Ngưỡng cam kết NFR-01      : <= {target_ms:.0f} ms")

    passed_p2 = p2_p95 <= target_ms
    if passed_p2:
        print(f"\n  ===> [PASSED] NFR-01 END-TO-END ĐẠT TIÊU CHUẨN! (P95 {p2_p95:.2f} ms <= {target_ms:.0f} ms)")
    else:
        print(f"\n  ===> [FAILED] NFR-01 END-TO-END VƯỢT TIÊU CHUẨN! (P95 {p2_p95:.2f} ms > {target_ms:.0f} ms)")
    print("=" * 75)

    return passed_p1 and passed_p2

if __name__ == "__main__":
    success = run_nfr01_benchmark(num_iterations=30, max_allowed_latency_sec=3.0)
    sys.exit(0 if success else 1)
