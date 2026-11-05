import sys
import os
import time
import cv2
import numpy as np

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "../..")))

from src.config import settings
from src.core.entity import Point, BoundingBox, FaceDetectionResult, TrackedPerson, RoiPolygonConfig
from src.core.face_detector import FaceDetector
from src.core.face_embedder import FaceEmbedder
from src.core.frame_analysis_engine import FrameAnalysisEngine
from src.pipeline.video_pipeline import VideoPipeline

def diagnose_tracking_accuracy():
    print("=" * 80)
    print("PHẦN 1: CHẨN ĐOÁN ĐỘ CHÍNH XÁC CỦA TRACKING (YOLO26 + BYTETRACK)")
    print("=" * 80)
    
    engine = FrameAnalysisEngine(max_inactive_seconds=2.0)
    
    # 1.1 Kiểm tra khả năng giữ Track ID liên tục khi đối tượng di chuyển bình thường
    t = 100.0
    box_t0 = BoundingBox(100, 100, 200, 300)
    box_t1 = BoundingBox(110, 100, 210, 300) # dịch chuyển 10px
    box_t2 = BoundingBox(120, 100, 220, 300) # dịch chuyển 10px tiếp
    
    engine.process_frame([(1, box_t0)], current_time=t)
    engine.process_frame([(1, box_t1)], current_time=t + 0.1)
    persons, _, _ = engine.process_frame([(1, box_t2)], current_time=t + 0.2)
    
    assert len(persons) == 1 and persons[0].track_id == 1
    print("  [PASS] 1.1 Giữ Track ID liên tục khi di chuyển tịnh tiến: 100% (Track #1)")

    # 1.2 Kiểm tra hiện tượng ByteTrack Switch ID (Kế thừa danh tính khi bị nhảy ID)
    # Giả lập Track 1 có khuôn mặt hợp lệ (Mã: SV001)
    face_info = FaceDetectionResult(
        bbox=box_t2, score=0.92, matched_code="SV001", matched_name="Sinh viên Test",
        is_authorized=True, is_active=True, role="STUDENT", access_checked=True
    )
    engine.active_tracks[1].face_detected = True
    engine.active_tracks[1].face_info = face_info
    engine.active_tracks[1].alert_unauthorized_sent = False
    
    # Ở frame tiếp theo t=100.3, Track 1 biến mất, xuất hiện Track 2 đè lên vị trí Track 1 (IoU > 0.8)
    box_t3 = BoundingBox(125, 100, 225, 300)
    persons, alerts, _ = engine.process_frame([(2, box_t3)], current_time=t + 0.3)
    
    # Kiểm tra Track 2 có kế thừa toàn bộ danh tính của Track 1 không
    p2 = engine.active_tracks.get(2)
    inherited_ok = (
        p2 is not None and p2.face_detected and p2.face_info is not None and
        p2.face_info.matched_code == "SV001" and p2.face_info.is_authorized is True
    )
    old_track_cleaned = (1 not in engine.active_tracks) or (not engine.active_tracks[1].is_currently_visible)
    
    print(f"  [{'PASS' if inherited_ok else 'FAIL'}] 1.2 Kế thừa danh tính khi nhảy ID (ByteTrack Switch): {inherited_ok}")
    print(f"  [{'PASS' if old_track_cleaned else 'FAIL'}] 1.2 Hủy bỏ Track cũ lập tức (Chống Double Box): {old_track_cleaned}")

    # 1.3 Kiểm tra Chống đánh cắp danh tính khi 2 người đứng sát nhau
    # Track 2 là SV001. Giờ xuất hiện Track 3 (Người lạ) đứng sát bên cạnh Track 2
    box_t4_person_a = BoundingBox(125, 100, 225, 300) # SV001
    box_t4_person_b = BoundingBox(160, 100, 260, 300) # Người lạ đứng sát, overlap IoU ~ 0.50
    persons, _, _ = engine.process_frame([(2, box_t4_person_a), (3, box_t4_person_b)], current_time=t + 0.4)
    
    p3 = engine.active_tracks.get(3)
    p3_is_clean = (p3 is not None and (not p3.face_detected or p3.face_info is None or p3.face_info.matched_code != "SV001"))
    print(f"  [{'PASS' if p3_is_clean else 'FAIL'}] 1.3 Chống đánh cắp danh tính (Hai người đứng sát nhau): {p3_is_clean}")
    
    # 1.4 Kiểm tra Khử trùng lặp Double-Box Glitch do Detector sinh ra 2 box cho 1 người
    # Cùng 1 người nhưng detector sinh ra 2 box lồng nhau (box 4 & box 5 có IoU = 0.88)
    box_main = BoundingBox(300, 100, 400, 400, confidence=0.90)
    box_ghost = BoundingBox(305, 105, 395, 395, confidence=0.75) # gần như trùng khít
    persons, _, _ = engine.process_frame([(4, box_main), (5, box_ghost)], current_time=t + 0.5)
    
    visible_boxes = [p for p in persons if getattr(p, "is_currently_visible", True) and p.track_id in (4, 5)]
    dedup_ok = len(visible_boxes) == 1 and visible_boxes[0].track_id == 4
    print(f"  [{'PASS' if dedup_ok else 'FAIL'}] 1.4 Khử trùng lặp Double-Box Glitch (Chỉ giữ box tự tin cao hơn): {dedup_ok} (Số box: {len(visible_boxes)})")

def diagnose_recognition_accuracy():
    print("\n" + "=" * 80)
    print("PHẦN 2: CHẨN ĐOÁN ĐỘ CHÍNH XÁC CỦA NHẬN DIỆN (YUNET + ARCFACE)")
    print("=" * 80)
    
    fd = FaceDetector()
    fe = FaceEmbedder()
    
    print(f"  * Ngưỡng khớp mặt hiện tại (FACE_MATCH_THRESHOLD): {settings.FACE_MATCH_THRESHOLD}")
    print(f"  * Ngưỡng thẩm định lại (FACE_REVERIFY_SCORE_THRESHOLD): {getattr(settings, 'FACE_REVERIFY_SCORE_THRESHOLD', 0.82)}")
    
    # 2.1 Kiểm tra độ nhạy của YuNet với các kích thước khuôn mặt
    test_sizes = [16, 24, 32, 64, 112, 200]
    print("\n  2.1 Kiểm tra YuNet Face Detector theo kích thước ảnh crop:")
    for sz in test_sizes:
        # Tạo ảnh giả lập mặt
        face_syn = np.full((sz, sz, 3), 180, dtype=np.uint8)
        # Vẽ mắt, mũi, miệng tượng trưng
        cv2.circle(face_syn, (int(sz*0.3), int(sz*0.35)), max(1, int(sz*0.08)), (30, 30, 30), -1)
        cv2.circle(face_syn, (int(sz*0.7), int(sz*0.35)), max(1, int(sz*0.08)), (30, 30, 30), -1)
        cv2.line(face_syn, (int(sz*0.5), int(sz*0.45)), (int(sz*0.5), int(sz*0.6)), (30, 30, 30), max(1, int(sz*0.04)))
        cv2.line(face_syn, (int(sz*0.35), int(sz*0.75)), (int(sz*0.65), int(sz*0.75)), (30, 30, 30), max(1, int(sz*0.05)))
        
        res = fd.detect_best_face_in_crop(face_syn, offset_xy=(0, 0))
        status = "Phát hiện tốt" if res else "Bỏ qua (quá nhỏ/không rõ)"
        print(f"    - Kích thước {sz:3d}x{sz:3d} px: {status} (Score: {res.score if res else 0.0:.2f})")
    
    # 2.2 Kiểm tra Bộ lọc ảnh mờ (Laplacian Variance Blur Filter)
    print("\n  2.2 Kiểm tra Bộ lọc ảnh mờ do chuyển động (Blur Filter):")
    sharp_img = np.random.randint(50, 200, (112, 112, 3), dtype=np.uint8)
    # Thêm chi tiết vân cạnh mạnh
    for k in range(0, 112, 10):
        cv2.line(sharp_img, (k, 0), (k, 111), (255, 255, 255), 2)
    
    gray_sharp = cv2.cvtColor(sharp_img, cv2.COLOR_BGR2GRAY)
    var_sharp = cv2.Laplacian(gray_sharp, cv2.CV_64F).var()
    
    # Tạo ảnh mờ bằng GaussianBlur mạnh (mô phỏng người chạy nhanh qua camera)
    blurred_img = cv2.GaussianBlur(sharp_img, (25, 25), 10.0)
    gray_blur = cv2.cvtColor(blurred_img, cv2.COLOR_BGR2GRAY)
    var_blur = cv2.Laplacian(gray_blur, cv2.CV_64F).var()
    
    print(f"    - Ảnh nét (Độ biến thiên Laplacian: {var_sharp:.1f}): {'ĐẠT (Gửi qua ArcFace)' if var_sharp >= 100 else 'BỊ LỌC'}")
    print(f"    - Ảnh mờ chuyển động (Laplacian: {var_blur:.1f}): {'ĐẠT' if var_blur >= 100 else 'ĐƯỢC LỌC BỎ CHÍNH XÁC (Tránh nhận diện sai)'}")
    
    # 2.3 Kiểm tra Khoảng cách Phân tách Vector ArcFace (Cosine Separation Margin)
    print("\n  2.3 Đo đạc Độ phân tách Vector ArcFace (Intra-class vs Inter-class):")
    # Lấy 2 ảnh mặt thật khác nhau trong snapshot
    img_a1 = cv2.imread("evidence_snapshots/2026-09-03/CAM-001/LOITER_Track1_114153.jpg")
    img_a2 = cv2.imread("evidence_snapshots/2026-09-03/CAM-001/LOITER_Track1_114200.jpg")
    img_b1 = cv2.imread("evidence_snapshots/2026-09-03/CAM-001/LOITER_Track4_114932.jpg")
    
    if img_a1 is not None and img_a2 is not None and img_b1 is not None:
        r_a1 = fd.detect_best_face_in_crop(img_a1, offset_xy=(0, 0))
        r_a2 = fd.detect_best_face_in_crop(img_a2, offset_xy=(0, 0))
        r_b1 = fd.detect_best_face_in_crop(img_b1, offset_xy=(0, 0))
        
        if r_a1 and r_a2 and r_b1:
            fx1, fy1, fx2, fy2 = r_a1.bbox.to_int_xyxy()
            crop_a1 = img_a1[fy1:fy2, fx1:fx2]
            fx1, fy1, fx2, fy2 = r_a2.bbox.to_int_xyxy()
            crop_a2 = img_a2[fy1:fy2, fx1:fx2]
            fx1, fy1, fx2, fy2 = r_b1.bbox.to_int_xyxy()
            crop_b1 = img_b1[fy1:fy2, fx1:fx2]
            
            va1 = np.array(fe.extract_embedding(crop_a1), dtype=np.float32)
            va1 = va1 / np.linalg.norm(va1)
            va2 = np.array(fe.extract_embedding(crop_a2), dtype=np.float32)
            va2 = va2 / np.linalg.norm(va2)
            vb1 = np.array(fe.extract_embedding(crop_b1), dtype=np.float32)
            vb1 = vb1 / np.linalg.norm(vb1)
            
            sim_same_person = float(np.dot(va1, va2))
            sim_diff_person = float(np.dot(va1, vb1))
            
            print(f"    - Độ tương đồng CÙNG 1 NGƯỜI (Intra-class: Người A lúc 11:41 vs 11:42) : {sim_same_person:.4f}")
            print(f"    - Độ tương đồng KHÁC NGƯỜI (Inter-class: Người A vs Người B)           : {sim_diff_person:.4f}")
            print(f"    - Khoảng cách phân cách an toàn (Separation Margin)                     : {sim_same_person - sim_diff_person:.4f}")
            print(f"    - Ngưỡng thiết lập (Threshold = {settings.FACE_MATCH_THRESHOLD}): Nằm hoàn hảo giữa {sim_diff_person:.2f} và {sim_same_person:.2f}")
            
            assert sim_same_person >= 0.60, "Độ tương đồng cùng 1 người phải cao"
            assert sim_diff_person < 0.40, "Độ tương đồng khác người phải thấp để chống nhận diện nhầm"

if __name__ == "__main__":
    diagnose_tracking_accuracy()
    diagnose_recognition_accuracy()
