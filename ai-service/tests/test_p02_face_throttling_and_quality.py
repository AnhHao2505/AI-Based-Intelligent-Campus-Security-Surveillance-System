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
    Xác nhận P0.2:
    - Track chưa định danh được quét ngay lần đầu (t=0.0s).
    - Trong 2s đầu: chỉ quét lại sau mỗi ~0.4s. Các frame ở giữa (0.1s, 0.2s, 0.3s) bị bỏ qua.
    - Sau 2s (t > 2.0s): áp dụng backoff, chỉ quét lại sau mỗi 1.0s.
    """
    pipeline = VideoPipeline(camera_code="CAM-THROTTLE-TEST", enable_motion_gating=False)
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

    # Frame 1: t = 0.0s -> Phải quét ngay lần đầu
    pipeline.process_frame(frame, current_time=0.0)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 1

    # Frame 2, 3, 4: t = 0.1s, 0.2s, 0.3s -> Phải bỏ qua (chưa đủ 0.4s)
    pipeline.process_frame(frame, current_time=0.1)
    pipeline.process_frame(frame, current_time=0.2)
    pipeline.process_frame(frame, current_time=0.3)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 1, (
        f"Kỳ vọng 1 lần quét nhưng thực tế quét {pipeline.face_detector.detect_best_face_in_crop.call_count} lần (chưa throttle)"
    )

    # Frame 5: t = 0.4s -> Đã đủ 0.4s -> Quét lần 2
    pipeline.process_frame(frame, current_time=0.4)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 2

    # Frame 6: t = 0.5s -> Bỏ qua
    pipeline.process_frame(frame, current_time=0.5)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 2

    # Frame 7: t = 0.8s -> Quét lần 3
    pipeline.process_frame(frame, current_time=0.8)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 3

    # Frame 8: t = 2.0s -> Quét lần 4 (kết thúc window 2.0s)
    pipeline.process_frame(frame, current_time=2.0)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 4

    # Frame 9: t = 2.5s (sau 2.0s, interval là 1.0s, mới trôi qua 0.5s) -> Bỏ qua
    pipeline.process_frame(frame, current_time=2.5)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 4

    # Frame 10: t = 3.0s (đã trôi qua 1.0s) -> Quét lần 5
    pipeline.process_frame(frame, current_time=3.0)
    assert pipeline.face_detector.detect_best_face_in_crop.call_count == 5


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
