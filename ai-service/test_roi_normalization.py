"""
Unit test kiểm thử cơ chế chuẩn hóa và đồng bộ pixel ROI đa khung hình
(Cross-frame ROI Pixel Normalization & Multi-Polygon Synchronization)
"""
from src.core.entity import Point, BoundingBox, RoiPolygonConfig, TrackedPerson
from src.utils.geometry import scale_polygon_to_frame, is_point_in_polygon, normalize_to_unit
from src.core.loitering_engine import LoiteringEngine

def test_roi_polygon_config_scaling():
    # 1. Định nghĩa polygon chuẩn hóa [0.0..1.0] (tương ứng vùng giữa khung hình)
    norm_poly = RoiPolygonConfig(
        label="Vùng Cấm Trung Tâm",
        alert_rules=["ENTRY_EXIT_TRACKING", "LOITERING"],
        vertices=[
            Point(0.2, 0.2),
            Point(0.8, 0.2),
            Point(0.8, 0.8),
            Point(0.2, 0.8)
        ]
    )

    # 2. Scale trên khung hình 1080p (1920x1080)
    pts_1080p = norm_poly.to_pixel_points(1920, 1080)
    assert len(pts_1080p) == 4
    assert pts_1080p[0] == Point(384.0, 216.0)
    assert pts_1080p[1] == Point(1536.0, 216.0)
    assert pts_1080p[2] == Point(1536.0, 864.0)
    assert pts_1080p[3] == Point(384.0, 864.0)

    # 3. Scale trên khung hình 720p (1280x720)
    pts_720p = norm_poly.to_pixel_points(1280, 720)
    assert pts_720p[0] == Point(256.0, 144.0)
    assert pts_720p[1] == Point(1024.0, 144.0)
    assert pts_720p[2] == Point(1024.0, 576.0)
    assert pts_720p[3] == Point(256.0, 576.0)

    # 4. Scale trên khung hình 4K (3840x2160)
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
    # Điểm pixel tuyệt đối (> 1.0) không được scale gấp đôi
    raw_pixels = [
        Point(100.0, 100.0),
        Point(400.0, 100.0),
        Point(400.0, 400.0),
        Point(100.0, 400.0)
    ]
    scaled = scale_polygon_to_frame(raw_pixels, 1920, 1080)
    assert scaled[0] == Point(100.0, 100.0)
    assert scaled[2] == Point(400.0, 400.0)

def test_loitering_engine_with_normalized_roi():
    engine = LoiteringEngine(loitering_threshold_seconds=3)

    # Đa giác chuẩn hóa chiếm 50% bên phải màn hình: x in [0.5, 1.0], y in [0.0, 1.0]
    norm_polys = [
        RoiPolygonConfig(
            label="Khu vực hạn chế",
            alert_rules=["ENTRY_EXIT_TRACKING", "LOITERING"],
            vertices=[Point(0.5, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.5, 1.0)]
        )
    ]

    # Khung hình 1920x1080
    frame_size = (1920, 1080)

    # Người 1 ở nửa bên trái: x=400, y=500 (ngoài ROI)
    box_outside = BoundingBox(x1=350, y1=400, x2=450, y2=600) # bottom_center = (400, 600)
    persons, alerts = engine.process_frame(
        detected_tracks=[(1, box_outside)],
        camera_code="CAM-TEST",
        current_time=1000.0,
        roi_polygons=norm_polys,
        frame_size=frame_size
    )
    assert persons[0].is_in_roi == False
    assert len(alerts) == 0

    # Người 2 ở nửa bên phải: x=1200, y=700 (trong ROI chuẩn hóa x >= 0.5 * 1920 = 960)
    box_inside = BoundingBox(x1=1150, y1=500, x2=1250, y2=700) # bottom_center = (1200, 700)
    persons, alerts = engine.process_frame(
        detected_tracks=[(2, box_inside)],
        camera_code="CAM-TEST",
        current_time=1000.0,
        roi_polygons=norm_polys,
        frame_size=frame_size
    )
    p2 = [p for p in persons if p.track_id == 2][0]
    assert p2.is_in_roi == True
    assert p2.loiter_duration == 0.0

    # Sau 4 giây (vượt ngưỡng 3s) và không thấy mặt -> Báo động LOITERING
    persons, alerts = engine.process_frame(
        detected_tracks=[(2, box_inside)],
        camera_code="CAM-TEST",
        current_time=1004.0,
        roi_polygons=norm_polys,
        frame_size=frame_size
    )
    p2 = [p for p in persons if p.track_id == 2][0]
    assert p2.is_in_roi == True
    assert len(alerts) == 1
    assert alerts[0].event_type == "LOITERING_UNIDENTIFIED_PERSON"
    assert alerts[0].track_id == 2
    assert alerts[0].location == {"label": "Khu vực hạn chế"}

if __name__ == "__main__":
    print("Running test_roi_polygon_config_scaling...")
    test_roi_polygon_config_scaling()
    print("Running test_scale_polygon_to_frame_dicts...")
    test_scale_polygon_to_frame_dicts()
    print("Running test_backward_compatibility_with_pixel_coords...")
    test_backward_compatibility_with_pixel_coords()
    print("Running test_loitering_engine_with_normalized_roi...")
    test_loitering_engine_with_normalized_roi()
    print("\nALL ROI NORMALIZATION TESTS PASSED SUCCESSFULLY!")
