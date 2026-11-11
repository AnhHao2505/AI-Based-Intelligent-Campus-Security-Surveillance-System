"""
Unit test kiểm thử cơ chế chuẩn hóa và đồng bộ pixel ROI đa khung hình
(Cross-frame ROI Pixel Normalization & Multi-Polygon Synchronization)
"""
from src.core.entity import Point, BoundingBox, RoiPolygonConfig, TrackedPerson
from src.utils.geometry import scale_polygon_to_frame, is_point_in_polygon, normalize_to_unit
from src.core.frame_analysis_engine import FrameAnalysisEngine

def test_roi_polygon_config_scaling():
    norm_poly = RoiPolygonConfig(
        label="Vùng Cấm Trung Tâm",
        alert_rules=["ENTRY_EXIT_TRACKING"],
        vertices=[
            Point(0.2, 0.2),
            Point(0.8, 0.2),
            Point(0.8, 0.8),
            Point(0.2, 0.8)
        ]
    )

    pts_1080p = norm_poly.to_pixel_points(1920, 1080)
    assert len(pts_1080p) == 4
    assert pts_1080p[0] == Point(384.0, 216.0)
    assert pts_1080p[1] == Point(1536.0, 216.0)
    assert pts_1080p[2] == Point(1536.0, 864.0)
    assert pts_1080p[3] == Point(384.0, 864.0)

    pts_720p = norm_poly.to_pixel_points(1280, 720)
    assert pts_720p[0] == Point(256.0, 144.0)
    assert pts_720p[1] == Point(1024.0, 144.0)
    assert pts_720p[2] == Point(1024.0, 576.0)
    assert pts_720p[3] == Point(256.0, 576.0)

    pts_4k = norm_poly.to_pixel_points(3840, 2160)
    assert pts_4k[0] == Point(768.0, 432.0)
    assert pts_4k[2] == Point(3072.0, 1728.0)

def test_scale_polygon_to_frame_dicts():
    dict_pts = [
        {"x": 0.1, "y": 0.1},
        {"x": 0.5, "y": 0.1},
        {"x": 0.5, "y": 0.5},
        {"x": 0.1, "y": 0.5}
    ]
    scaled = scale_polygon_to_frame(dict_pts, 1000, 1000)
    assert len(scaled) == 4
    assert scaled[0] == Point(100.0, 100.0)
    assert scaled[2] == Point(500.0, 500.0)

def test_backward_compatibility_with_pixel_coords():
    raw_pixels = [
        Point(100.0, 100.0),
        Point(400.0, 100.0),
        Point(400.0, 400.0),
        Point(100.0, 400.0)
    ]
    scaled = scale_polygon_to_frame(raw_pixels, 1920, 1080)
    assert scaled[0] == Point(100.0, 100.0)
    assert scaled[2] == Point(400.0, 400.0)

if __name__ == "__main__":
    print("Running test_roi_polygon_config_scaling...")
    test_roi_polygon_config_scaling()
    print("Running test_scale_polygon_to_frame_dicts...")
    test_scale_polygon_to_frame_dicts()
    print("Running test_backward_compatibility_with_pixel_coords...")
    test_backward_compatibility_with_pixel_coords()
    print("\nALL ROI NORMALIZATION TESTS PASSED SUCCESSFULLY!")
