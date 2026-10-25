"""
Benchmark NFR-01: Alert Latency Measurement Script
Đo đạc độ trễ xử lý cảnh báo từ lúc phát hiện vi phạm qua Pipeline đến khi sự kiện sẵn sàng phát đi.
Tiêu chuẩn NFR-01: Thời gian xử lý cảnh báo <= 3.0 giây (3000ms).
"""

import sys
import os
import time
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
        persons, alerts = engine.process_frame([(track_id, bbox_inside)], roi, current_time=time.time())
        elapsed_ms = (time.perf_counter() - start_time) * 1000.0
        latencies_ms.append(elapsed_ms)

        assert len(alerts) > 0, f"Lỗi: Không phát hiện cảnh báo ở lần lặp #{i}"
        alert = alerts[0]
        assert alert.event_type == "UNAUTHORIZED_ACCESS"

        if i <= 5 or i % 10 == 0 or i == num_iterations:
            print(f"  Iteration #{i:02d}: Latency = {elapsed_ms:.3f} ms | Alert: {alert.event_type} (Track #{track_id})")

    # Tính toán các chỉ số thống kê
    avg_latency = float(np.mean(latencies_ms))
    p50_latency = float(np.percentile(latencies_ms, 50))
    p95_latency = float(np.percentile(latencies_ms, 95))
    max_latency = float(np.max(latencies_ms))
    min_latency = float(np.min(latencies_ms))

    print("\n" + "-" * 75)
    print("                       KẾT QUẢ ĐO ĐẠC NFR-01")
    print("-" * 75)
    print(f"  - Tổng số lần thử nghiệm     : {num_iterations}")
    print(f"  - Độ trễ tối thiểu (Min)     : {min_latency:.3f} ms")
    print(f"  - Độ trễ trung bình (Mean)   : {avg_latency:.3f} ms")
    print(f"  - Độ trễ Median (P50)        : {p50_latency:.3f} ms")
    print(f"  - Độ trễ phân vị 95 (P95)    : {p95_latency:.3f} ms")
    print(f"  - Độ trễ tối đa (Max)        : {max_latency:.3f} ms")
    print(f"  - Ngưỡng cam kết NFR-01      : <= {max_allowed_latency_sec*1000:.0f} ms")

    target_ms = max_allowed_latency_sec * 1000.0
    passed = p95_latency <= target_ms

    if passed:
        print(f"\n  ===> [PASSED] NFR-01 ĐẠT TIÊU CHUẨN! (P95 {p95_latency:.2f} ms <= {target_ms:.0f} ms)")
    else:
        print(f"\n  ===> [FAILED] NFR-01 VƯỢT NGƯỠNG CHO PHÉP! (P95 {p95_latency:.2f} ms > {target_ms:.0f} ms)")
    print("=" * 75)

    return passed

if __name__ == "__main__":
    success = run_nfr01_benchmark(num_iterations=30, max_allowed_latency_sec=3.0)
    sys.exit(0 if success else 1)
