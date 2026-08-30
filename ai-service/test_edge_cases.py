"""
Kiểm thử chuyên sâu các ca biên (Edge Cases) cho LoiteringEngine và VideoPipeline:
1. Trường hợp nhiều người cùng xuất hiện (Multi-person scenario):
   - Người 1: Được ủy quyền (Authorized) -> Không báo động.
   - Người 2: Không thấy mặt nhưng lảng vảng quá thời gian -> Báo động LOITERING_UNIDENTIFIED_PERSON.
   - Người 3: Người lạ bị phát hiện không có quyền -> Báo động UNAUTHORIZED_ACCESS tức thì.
2. Trường hợp người bước ra khỏi ROI rồi quay trở lại (Exit & Re-enter ROI).
3. Trường hợp người biến mất khỏi khung hình (Track Pruning).
4. Khung hình rỗng / lỗi kích thước.
"""

import sys
import os
import time
import numpy as np

# Tự động set UTF-8 cho console Windows
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from src.core.entity import Point, BoundingBox, FaceDetectionResult
from src.core.loitering_engine import LoiteringEngine
from src.pipeline.video_pipeline import VideoPipeline

def test_edge_cases():
    print("=" * 65)
    print("       KIỂM THỬ CHUYÊN SÂU CÁC CA BIÊN (EDGE CASES AUDIT)")
    print("=" * 65)

    roi = [Point(100, 100), Point(400, 100), Point(400, 400), Point(100, 400)]
    engine = LoiteringEngine(loitering_threshold_seconds=5, max_inactive_seconds=2.0)

    # -------------------------------------------------------------
    # CASE 1: Đa đối tượng đồng thời trong khung hình
    # -------------------------------------------------------------
    print("\n[Case 1] Kiểm tra 3 người đồng thời trong vùng cấm ROI:")
    # Người 1 (Track 1): Hợp lệ (Authorized Guard)
    p1_box = BoundingBox(120, 120, 180, 250) # In ROI
    # Người 2 (Track 2): Che mặt / Quay lưng (Unknown)
    p2_box = BoundingBox(200, 150, 260, 300) # In ROI
    # Người 3 (Track 3): Nhận diện mặt nhưng là Kẻ đột nhập (Unauthorized)
    p3_box = BoundingBox(300, 150, 360, 320) # In ROI

    # Tại t = 1000s: 3 người cùng bước vào
    tracks_t0 = [(1, p1_box), (2, p2_box), (3, p3_box)]
    persons, alerts = engine.process_frame(tracks_t0, roi, current_time=1000.0)
    
    # Gán thông tin khuôn mặt cho Người 1 (Authorized) và Người 3 (Unauthorized)
    engine.associate_face(1, FaceDetectionResult(bbox=p1_box, score=0.92, matched_name="Nguyen Van An (Guard)", is_authorized=True))
    engine.associate_face(3, FaceDetectionResult(bbox=p3_box, score=0.88, matched_name="Unknown Intruder", is_authorized=False))
    # Người 2 KHÔNG gán mặt (face_detected = False)

    # Tại t = 1001s: Kiểm tra ngay lập tức
    persons, alerts = engine.process_frame(tracks_t0, roi, current_time=1001.0)
    # Người 3 phải bị báo động UNAUTHORIZED_ACCESS ngay lập tức (không cần chờ loiter time)
    assert any(a.event_type == "UNAUTHORIZED_ACCESS" and a.track_id == 3 for a in alerts), "Lỗi: Người 3 chưa bị báo động UNAUTHORIZED_ACCESS"
    print("   -> [PASS] Người 3 (Không phép) bị báo động UNAUTHORIZED_ACCESS ngay lập tức!")

    # Tại t = 1006s (Sau 6 giây > ngưỡng 5s):
    persons, alerts = engine.process_frame(tracks_t0, roi, current_time=1006.0)
    # Người 1: Hợp lệ -> KHÔNG báo động
    p1 = next(p for p in persons if p.track_id == 1)
    assert p1.alert_loitering_sent == False, "Lỗi: Người hợp lệ bị báo động nhầm"
    print("   -> [PASS] Người 1 (Bảo vệ hợp lệ) KHÔNG bị báo động nhầm dù ở trong vùng cấm lâu.")

    # Người 2: Không rõ mặt lảng vảng quá 5s -> Báo động LOITERING_UNIDENTIFIED_PERSON
    assert any(a.event_type == "LOITERING_UNIDENTIFIED_PERSON" and a.track_id == 2 for a in alerts), "Lỗi: Người 2 chưa bị báo động Loitering"
    print("   -> [PASS] Người 2 (Không thấy mặt) bị kích hoạt LOITERING_UNIDENTIFIED_PERSON chuẩn xác sau 6.0s!")

    # -------------------------------------------------------------
    # CASE 2: Người bước ra ngoài ROI rồi bước lại vào trong ROI
    # -------------------------------------------------------------
    print("\n[Case 2] Kiểm tra người bước ra khỏi ROI rồi quay lại (Reset Timer):")
    # Người 4 bước vào ROI lúc t=2000s, ở đến 2003s (chưa đủ 5s)
    p4_in = BoundingBox(150, 150, 220, 280)
    engine.process_frame([(4, p4_in)], roi, current_time=2000.0)
    engine.process_frame([(4, p4_in)], roi, current_time=2003.0)
    
    # Bước ra ngoài ROI lúc t=2004s
    p4_out = BoundingBox(20, 20, 80, 120)
    persons, _ = engine.process_frame([(4, p4_out)], roi, current_time=2004.0)
    p4 = next(p for p in persons if p.track_id == 4)
    assert p4.is_in_roi == False
    assert p4.loiter_duration == 0.0
    print("   -> [PASS] Khi bước ra ngoài ROI, bộ đếm thời gian được reset về 0.0s.")

    # Quay lại ROI lúc t=2006s và ở tới 2008s (2s < 5s)
    engine.process_frame([(4, p4_in)], roi, current_time=2006.0)
    persons, alerts = engine.process_frame([(4, p4_in)], roi, current_time=2008.0)
    p4 = next(p for p in persons if p.track_id == 4)
    assert p4.loiter_duration == 2.0
    assert len(alerts) == 0
    print("   -> [PASS] Khi quay lại ROI, thời gian được tính lại từ đầu (2.0s), không bị cộng dồn sai.")

    # -------------------------------------------------------------
    # CASE 3: Dọn dẹp Track khi đối tượng rời khỏi Camera (Pruning)
    # -------------------------------------------------------------
    print("\n[Case 3] Kiểm tra dọn dẹp bộ nhớ Track cũ (Track Pruning):")
    # Người 5 xuất hiện lần cuối lúc t=3000s
    engine.process_frame([(5, p4_in)], roi, current_time=3000.0)
    assert 5 in engine.active_tracks
    
    # Không xuất hiện trong 3s tiếp theo (t=3004s > max_inactive_seconds=2.0)
    engine.process_frame([], roi, current_time=3004.0)
    assert 5 not in engine.active_tracks
    print("   -> [PASS] Track #5 đã được tự động dọn dẹp sạch sẽ khỏi RAM sau khi rời camera.")

    # -------------------------------------------------------------
    # CASE 4: Khung hình bất thường / Rỗng
    # -------------------------------------------------------------
    print("\n[Case 4] Kiểm tra xử lý an toàn khi frame rỗng hoặc None:")
    pipeline = VideoPipeline(camera_code="CAM-SAFE-TEST")
    f_none, p_none, a_none = pipeline.process_frame(None)
    assert f_none is None and len(p_none) == 0 and len(a_none) == 0
    
    f_empty, p_empty, a_empty = pipeline.process_frame(np.array([]))
    assert len(p_empty) == 0 and len(a_empty) == 0
    print("   -> [PASS] Pipeline xử lý an toàn tuyệt đối với frame rỗng, không crash ứng dụng.")

    print("\n" + "=" * 65)
    print("      TẤT CẢ 4 NHÓM CA BIÊN ĐỀU ĐẠT CHUẨN XÁC 100% (ALL PASS)")
    print("=" * 65)

if __name__ == "__main__":
    test_edge_cases()
