import os
import sys
import time
from unittest.mock import MagicMock, patch
import cv2
import numpy as np
import pytest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from src.pipeline.video_pipeline import VideoPipeline
from src.core.entity import FaceDetectionResult, BoundingBox, Point
from src.config import settings


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_unidentified_track_throttling_intervals(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Xác nhận Cascade Gate:
    - Trong ROI: YuNet chạy mỗi frame để bắt khoảnh khắc nét nhất.
    - ArcFace được điều tốc với chu kỳ tối thiểu 0.2s/lần (FACE_SCAN_INITIAL_INTERVAL).
    - Ngoài ROI: YuNet được throttle 1.0s/lần (FACE_SCAN_BACKOFF_INTERVAL).
    """
    pipeline = VideoPipeline(camera_code="CAM-THROTTLE-TEST", enable_motion_gating=False)
    pipeline.face_scan_initial_interval = 0.2
    pipeline.face_scan_backoff_interval = 1.0
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
    pipeline.face_matcher.get_camera_area_name.return_value = "Khu vực test"
    pipeline.face_matcher.is_camera_public.return_value = False
    pipeline.face_matcher.get_cached_access.return_value = None

    bbox = BoundingBox(100.0, 100.0, 300.0, 500.0)
    frame = np.full((600, 800, 3), 120, dtype=np.uint8)

    face_crop_res = FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 220.0, 210.0),
        score=0.90
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = face_crop_res
    pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]
    # Chưa nhận diện được danh tính (match_face trả về None)
    pipeline.face_matcher.match_face.return_value = None

    # --- 1. TRONG ROI: YuNet chạy mỗi frame, ArcFace throttle 0.2s/lần ---
    # Frame 1: t = 0.0s -> YuNet chạy, ArcFace chạy lần 1
    pipeline.process_frame(frame, current_time=0.0)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 1
    assert pipeline.face_matcher.match_face.call_count == 1

    # Frame 2: t = 0.1s -> YuNet chạy (frame 2), ArcFace bị throttle (chưa đủ 0.2s)
    pipeline.process_frame(frame, current_time=0.1)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 2
    assert pipeline.face_matcher.match_face.call_count == 1

    # Frame 3: t = 0.2s -> Đã đủ 0.2s -> ArcFace chạy lần 2
    pipeline.process_frame(frame, current_time=0.2)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 3
    assert pipeline.face_matcher.match_face.call_count == 2

    # Frame 4: t = 0.3s -> ArcFace bị throttle (mới qua 0.1s)
    pipeline.process_frame(frame, current_time=0.3)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 4
    assert pipeline.face_matcher.match_face.call_count == 2

    # Frame 5: t = 0.4s -> Đã đủ 0.2s -> ArcFace chạy lần 3
    pipeline.process_frame(frame, current_time=0.4)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 5
    assert pipeline.face_matcher.match_face.call_count == 3

    # --- 2. NGOÀI ROI: YuNet được throttle 1.0s/lần ---
    pipeline.roi_polygon = [Point(0, 0), Point(50, 0), Point(50, 50), Point(0, 50)] # bbox ở ngoài ROI
    # t = 0.5s: Vừa ra ngoài ROI -> YuNet bị throttle (chưa đủ 1.0s từ lần cuối ở 0.4s)
    pipeline.process_frame(frame, current_time=0.5)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 5

    # t = 1.4s: Đã đủ 1.0s -> YuNet chạy lần 6
    pipeline.process_frame(frame, current_time=1.4)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 6


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_face_min_crop_size_filters_small_crops(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Xác nhận P0.2:
    - Khi khuôn mặt phát hiện có kích thước nhỏ hơn FACE_MIN_CROP_SIZE (ví dụ 20x20 < 30x30),
      hệ thống bỏ qua không chạy ArcFace embedding (match_face không được gọi).
    - Khi khuôn mặt >= 30x30, ArcFace được gọi bình thường.
    """
    pipeline = VideoPipeline(camera_code="CAM-SIZE-TEST", enable_motion_gating=False)
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
    pipeline.face_matcher.get_camera_area_name.return_value = "Khu vực test"
    pipeline.face_matcher.is_camera_public.return_value = False
    pipeline.face_matcher.get_cached_access.return_value = None
    pipeline.face_matcher.check_camera_access.return_value = (True, "Khu vực test", "Hợp lệ")
    pipeline.face_matcher.match_face.return_value = ("USER_NORMAL", "User Normal", 0.9, True, "NORMAL_USER")

    bbox = BoundingBox(100.0, 100.0, 300.0, 500.0)
    frame = np.full((600, 800, 3), 120, dtype=np.uint8)
    pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]

    # 1. Mặt nhỏ 20x20 (< 30x30)
    small_face = FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 140.0, 130.0), # 20x20
        score=0.90
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = small_face
    pipeline.process_frame(frame, current_time=0.0)
    # match_face KHÔNG được gọi
    pipeline.face_matcher.match_face.assert_not_called()

    # 2. Mặt đủ lớn 60x60 (>= 30x30)
    normal_face = FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 180.0, 170.0), # 60x60
        score=0.90
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = normal_face
    # Chuyển thời gian sang 1.0s để không bị throttle
    pipeline.process_frame(frame, current_time=1.0)
    # match_face ĐƯỢC gọi
    pipeline.face_matcher.match_face.assert_called_once()


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_face_blur_filtering_with_laplacian_variance(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Xác nhận P0.2:
    - Khi ảnh mặt bị mờ (Laplacian variance < 50.0 do rung lắc / chuyển động nhanh),
      bỏ qua ArcFace embedding để tiết kiệm tài nguyên.
    - Khi ảnh sắc nét (Laplacian variance >= 50.0), chạy ArcFace bình thường.
    """
    pipeline = VideoPipeline(camera_code="CAM-BLUR-TEST", enable_motion_gating=False)
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
    pipeline.face_matcher.get_camera_area_name.return_value = "Khu vực test"
    pipeline.face_matcher.is_camera_public.return_value = False
    pipeline.face_matcher.get_cached_access.return_value = None
    pipeline.face_matcher.check_camera_access.return_value = (True, "Khu vực test", "Hợp lệ")
    pipeline.face_matcher.match_face.return_value = ("USER_SHARP", "User Sharp", 0.9, True, "NORMAL_USER")

    bbox = BoundingBox(100.0, 100.0, 400.0, 500.0)
    pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]

    # Tạo frame có vùng mặt (từ (120, 120) đến (220, 220)) với texture
    sharp_frame = np.random.randint(0, 255, (600, 800, 3), dtype=np.uint8)

    face_bbox = FaceDetectionResult(
        bbox=BoundingBox(120.0, 120.0, 220.0, 220.0), # 100x100
        score=0.90
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = face_bbox

    # 1. Ảnh mặt bị mờ nặng (Gaussian blur kernel 31x31)
    blurry_frame = sharp_frame.copy()
    blurry_crop = cv2.GaussianBlur(sharp_frame[120:220, 120:220], (31, 31), 0)
    blurry_frame[120:220, 120:220] = blurry_crop

    pipeline.process_frame(blurry_frame, current_time=0.0)
    # match_face không được gọi vì mặt mờ
    pipeline.face_matcher.match_face.assert_not_called()

    # 2. Ảnh mặt sắc nét (sharp_frame)
    pipeline.process_frame(sharp_frame, current_time=1.0) # t=1.0s để vượt qua throttle
    # match_face ĐƯỢC gọi
    pipeline.face_matcher.match_face.assert_called_once()


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_fast_face_identification_outside_roi(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Kiểm tra tính năng nhận diện nhanh cho người có khung xám (ngoài ROI):
    - Chu kỳ quét đã được tăng tốc lên 0.2s/lần (5 FPS) thay vì 1.0s rùa bò cũ.
    - Ngay khi ArcFace nhận diện được người (vd SE194249), tên lập tức được hiển thị trên khung xám.
    - Sau khi đã nhận diện thành công, hệ thống không quét lại liên tục để tiết kiệm tài nguyên.
    """
    pipeline = VideoPipeline(camera_code="CAM-FAST-TEST", enable_motion_gating=False)
    # ROI nằm xa (x=700..800, y=500..600)
    pipeline.roi_polygon = [Point(700, 500), Point(800, 500), Point(800, 600), Point(700, 600)]
    pipeline.face_matcher.get_camera_area_name.return_value = "Hành lang Lab 1"
    pipeline.face_matcher.is_camera_public.return_value = False
    pipeline.face_matcher.get_cached_access.return_value = None

    bbox_hallway = BoundingBox(100.0, 100.0, 300.0, 400.0)
    frame = np.random.randint(0, 255, (600, 800, 3), dtype=np.uint8)
    pipeline.human_detector.detect_and_track.return_value = [(10, bbox_hallway)]

    face_crop_res = FaceDetectionResult(
        bbox=BoundingBox(130.0, 110.0, 200.0, 180.0), # 70x70
        score=0.92
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = face_crop_res

    # Frame 1: t = 0.0s -> Chưa match được (ví dụ góc nghiêng)
    pipeline.face_matcher.match_face.return_value = None
    _, persons1, _ = pipeline.process_frame(frame, current_time=0.0)
    assert persons1[0].security_display_state == "NEUTRAL"
    assert persons1[0].face_info.matched_code is None

    # Frame 2: Chỉ 0.2s sau (t = 0.2s) -> Đã quét lại ngay lập tức (không phải chờ 1.0s)!
    # ArcFace match được SE194249
    pipeline.face_matcher.match_face.return_value = ("SE194249", "Dang Nguyen Gia Minh", 0.93, True, "STUDENT")
    res_frame2, persons2, _ = pipeline.process_frame(frame, current_time=0.2, need_annotated=True)

    p2 = persons2[0]
    assert p2.security_display_state == "NEUTRAL" # Vẫn là khung xám vì ở ngoài ROI
    assert p2.face_info.matched_code == "SE194249"
    assert p2.face_info.matched_name == "Dang Nguyen Gia Minh"

    # Frame 3: t = 0.4s -> Đã có danh tính với điểm cao (0.93 >= 0.82) -> Tự động dừng quét để tiết kiệm CPU/GPU!
    pipeline.process_frame(frame, current_time=0.4)
    # detect_best_face_in_crop chỉ được gọi 2 lần (ở t=0.0s và t=0.2s), không bị gọi lại ở t=0.4s
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 2


@patch("src.pipeline.video_pipeline.HumanDetector")
@patch("src.pipeline.video_pipeline.FaceDetector")
@patch("src.pipeline.video_pipeline.FaceMatcher")
@patch("src.pipeline.video_pipeline.StorageService")
@patch("src.pipeline.video_pipeline.get_security_mqtt_client")
def test_20fps_arcface_matching_every_frame(
    mock_mqtt, mock_storage, mock_matcher, mock_facedet, mock_humandet
):
    """
    Xác nhận cấu hình 20 FPS (0.05s / lần):
    - Cứ mỗi frame (50ms), ArcFace được chạy so khớp Database liên tục để chốt danh tính siêu tốc.
    """
    pipeline = VideoPipeline(camera_code="CAM-20FPS-TEST", enable_motion_gating=False)
    pipeline.roi_polygon = [Point(0, 0), Point(800, 0), Point(800, 600), Point(0, 600)]
    pipeline.face_matcher.get_camera_area_name.return_value = "Khu vực test"
    pipeline.face_matcher.is_camera_public.return_value = False
    pipeline.face_matcher.get_cached_access.return_value = None

    bbox = BoundingBox(100.0, 100.0, 300.0, 500.0)
    frame = np.full((600, 800, 3), 120, dtype=np.uint8)

    face_crop_res = FaceDetectionResult(
        bbox=BoundingBox(120.0, 110.0, 220.0, 210.0),
        score=0.90
    )
    pipeline.face_detector.detect_best_face_in_crop.return_value = face_crop_res
    pipeline.human_detector.detect_and_track.return_value = [(1, bbox)]
    pipeline.face_matcher.match_face.return_value = None

    # Frame 1: t = 0.00s -> ArcFace chạy lần 1
    pipeline.process_frame(frame, current_time=0.0)
    assert pipeline.face_matcher.match_face.call_count == 1

    # Frame 2: t = 0.05s (50ms sau, frame tiếp theo của IMOU 20FPS) -> ArcFace chạy lần 2!
    pipeline.process_frame(frame, current_time=0.05)
    assert pipeline.face_matcher.match_face.call_count == 2

    # Frame 3: t = 0.10s (100ms sau) -> ArcFace chạy lần 3!
    pipeline.process_frame(frame, current_time=0.10)
    assert pipeline.face_matcher.match_face.call_count == 3
