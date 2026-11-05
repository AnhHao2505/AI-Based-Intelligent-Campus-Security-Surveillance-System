import sys
import os
import time
import psutil
import torch
import numpy as np
from typing import List

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "../..")))

from src.pipeline.video_pipeline import VideoPipeline

def get_process_resources():
    p = psutil.Process()
    mem_info = p.memory_info()
    ram_mb = mem_info.rss / (1024 * 1024)
    cpu_pct = p.cpu_percent(interval=None)
    
    vram_mb = 0.0
    if torch.cuda.is_available():
        vram_mb = torch.cuda.memory_allocated(0) / (1024 * 1024)
        
    return ram_mb, cpu_pct, vram_mb

def measure_resources_by_camera_count():
    print("=" * 80)
    print("MEASURE SYSTEM RESOURCES: ĐO TIÊU THỤ CPU / RAM / VRAM THEO SỐ CAMERA (1, 2, 3)")
    print("=" * 80)

    # Đo baseline ban đầu
    psutil.cpu_percent(interval=None) # reset cpu tracker
    time.sleep(0.5)
    ram_base, cpu_base, vram_base = get_process_resources()
    print(f"Baseline Process (Chưa nạp pipeline): RAM = {ram_base:.1f} MB | VRAM = {vram_base:.1f} MB")

    pipelines: List[VideoPipeline] = []
    dummy_frame = np.full((720, 1280, 3), 100, dtype=np.uint8)

    print("\n" + "-" * 80)
    print(f"{'SỐ CAMERA':<12} | {'RAM PROCESS (MB)':<18} | {'VRAM GPU (MB)':<16} | {'SỐ THREADS':<12} | {'DELTA RAM (MB)':<15}")
    print("-" * 80)

    prev_ram = ram_base
    for num_cams in [1, 2, 3]:
        cam_code = f"CAM-BENCH-{num_cams:02d}"
        p = VideoPipeline(camera_code=cam_code)
        # Chạy 3 frames để warm up và cấp phát bộ nhớ
        for _ in range(3):
            p.process_frame(dummy_frame)
        pipelines.append(p)

        time.sleep(1.0)
        p_proc = psutil.Process()
        ram_mb, cpu_pct, vram_mb = get_process_resources()
        num_threads = p_proc.num_threads()
        delta_ram = ram_mb - prev_ram
        prev_ram = ram_mb

        print(f"{num_cams:<12} | {ram_mb:18.1f} | {vram_mb:16.1f} | {num_threads:12d} | {delta_ram:+15.1f}")

    print("-" * 80)
    print("\nĐo mức tải CPU khi 1, 2, 3 cameras đồng thời xử lý 30 frames:")
    for active_cams in [1, 2, 3]:
        t0 = time.time()
        psutil.cpu_percent(interval=None)
        frames_run = 30
        for _ in range(frames_run):
            for i in range(active_cams):
                pipelines[i].process_frame(dummy_frame)
        dur = time.time() - t0
        cpu_load = psutil.Process().cpu_percent(interval=None)
        print(f"  * {active_cams} camera(s) xử lý {frames_run} frames: Thời gian = {dur:.2f}s | CPU Process Load = {cpu_load:.1f}%")

    # Dọn dẹp
    for p in pipelines:
        p.stop()

if __name__ == "__main__":
    measure_resources_by_camera_count()
