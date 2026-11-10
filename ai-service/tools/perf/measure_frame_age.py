import sys
import os
import time
import cv2
import numpy as np
import threading

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

def measure_frame_age_simulation(duration_seconds: int = 60, camera_fps: float = 25.0, analysis_cost_ms: float = 80.0):
    print("=" * 80)
    print("MEASURE FRAME AGE: ĐO ĐỘ TUỔI FRAME (INGEST TO ANALYSIS LATENCY)")
    print(f"Cấu hình: Stream source @ {camera_fps} fps (mỗi frame cách {1000/camera_fps:.1f}ms)")
    print(f"Thời gian phân tích AI mỗi frame giả lập: {analysis_cost_ms:.1f}ms")
    print(f"Thời gian chạy kiểm thử: {duration_seconds}s")
    print("=" * 80)

    # Biến chia sẻ giữa Capture và Analysis
    raw_frame_buffer = {"frame": None, "capture_ts": 0.0, "frame_id": 0}
    lock = threading.Lock()
    is_running = True

    ages = []
    dropped_frames = 0
    total_captured = 0

    # Luồng giả lập Camera Ingest (Capture Thread)
    def capture_sim():
        nonlocal total_captured
        interval = 1.0 / camera_fps
        next_t = time.perf_counter()
        while is_running:
            now = time.perf_counter()
            total_captured += 1
            # Giả lập 1 frame
            dummy_f = np.zeros((720, 1280, 3), dtype=np.uint8)
            with lock:
                raw_frame_buffer["frame"] = dummy_f
                raw_frame_buffer["capture_ts"] = now
                raw_frame_buffer["frame_id"] += 1

            next_t += interval
            sleep_t = next_t - time.perf_counter()
            if sleep_t > 0:
                time.sleep(sleep_t)
            else:
                next_t = time.perf_counter()

    # Luồng phân tích (Analysis Thread)
    def analysis_sim():
        nonlocal dropped_frames
        last_id = -1
        while is_running:
            with lock:
                if raw_frame_buffer["frame"] is not None and raw_frame_buffer["frame_id"] != last_id:
                    cap_ts = raw_frame_buffer["capture_ts"]
                    cur_id = raw_frame_buffer["frame_id"]
                    if last_id != -1 and cur_id > last_id + 1:
                        dropped_frames += (cur_id - last_id - 1)
                    last_id = cur_id
                else:
                    cap_ts = None

            if cap_ts is not None:
                # Độ tuổi của frame tại thời điểm bắt đầu đưa vào phân tích
                age_ms = (time.perf_counter() - cap_ts) * 1000.0
                ages.append(age_ms)
                # Giả lập thời gian suy luận AI chiếm CPU/GIL
                time.sleep(analysis_cost_ms / 1000.0)
            else:
                time.sleep(0.005)

    t_cap = threading.Thread(target=capture_sim, daemon=True)
    t_ana = threading.Thread(target=analysis_sim, daemon=True)
    t_cap.start()
    t_ana.start()

    start_t = time.time()
    checkpoint_interval = 15 # In checkpoint mỗi 15s
    next_cp = start_t + checkpoint_interval

    while time.time() - start_t < duration_seconds:
        time.sleep(1.0)
        if time.time() >= next_cp:
            elapsed = int(time.time() - start_t)
            recent_ages = ages[-20:] if ages else [0]
            print(f"  [T={elapsed:02d}s] Frames captured: {total_captured} | Frames dropped: {dropped_frames} | Recent Frame Age P50: {np.percentile(recent_ages, 50):.2f}ms, P95: {np.percentile(recent_ages, 95):.2f}ms")
            next_cp += checkpoint_interval

    is_running = False
    t_cap.join(timeout=1.0)
    t_ana.join(timeout=1.0)

    print("\n" + "-" * 80)
    print("KẾT QUẢ ĐO ĐỘ TUỔI FRAME (FRAME AGE METRICS):")
    print("-" * 80)
    print(f"Tổng số frame camera phát sinh : {total_captured}")
    print(f"Tổng số frame AI phân tích    : {len(ages)}")
    print(f"Tổng số frame bị drop (bỏ qua) : {dropped_frames} ({dropped_frames/max(1, total_captured)*100:.1f}%)")
    print(f"Độ tuổi frame P50 (Median)    : {np.percentile(ages, 50):.2f} ms")
    print(f"Độ tuổi frame P95             : {np.percentile(ages, 95):.2f} ms")
    print(f"Độ tuổi frame P99             : {np.percentile(ages, 99):.2f} ms")
    print(f"Độ tuổi frame Max             : {np.max(ages):.2f} ms")
    print("-" * 80)

if __name__ == "__main__":
    # Đo 30 giây mô phỏng ingest
    measure_frame_age_simulation(duration_seconds=30, camera_fps=25.0, analysis_cost_ms=60.0)
