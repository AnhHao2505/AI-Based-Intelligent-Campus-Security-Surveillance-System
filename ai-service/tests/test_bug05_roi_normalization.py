import pytest
from src.core.entity import Point, RoiPolygonConfig
from src.utils.geometry import normalize_polygon_to_unit


def test_normalize_roi_from_1920x1080_to_unit_and_scale_to_640x480():
    """
    BUG-05: Tọa độ pixel vẽ trên canvas 1920x1080 phải được chuẩn hóa về 0..1
    bằng reference_width=1920 và reference_height=1080.
    Khi áp dụng cho khung hình 640x480, tọa độ pixel co giãn tương ứng đúng tỷ lệ.
    """
    pixel_vertices_1080p = [
        {"x": 960.0, "y": 540.0},    # Center
        {"x": 1920.0, "y": 540.0},   # Right center
        {"x": 1920.0, "y": 1080.0},  # Bottom right
        {"x": 960.0, "y": 1080.0}    # Bottom center
    ]

    norm_pts = normalize_polygon_to_unit(
        pixel_vertices_1080p,
        reference_width=1920,
        reference_height=1080
    )

    # 1. Kiểm tra chuẩn hóa về khoảng [0.0, 1.0]
    assert len(norm_pts) == 4
    assert norm_pts[0] == Point(0.5, 0.5)
    assert norm_pts[1] == Point(1.0, 0.5)
    assert norm_pts[2] == Point(1.0, 1.0)
    assert norm_pts[3] == Point(0.5, 1.0)

    # 2. Khi gắn vào RoiPolygonConfig và scale sang frame 640x480:
    poly = RoiPolygonConfig(label="Test-1080p-to-480p", vertices=norm_pts)
    pts_480p = poly.to_pixel_points(640, 480)
    assert pts_480p[0] == Point(320.0, 240.0)
    assert pts_480p[1] == Point(640.0, 240.0)
    assert pts_480p[2] == Point(640.0, 480.0)
    assert pts_480p[3] == Point(320.0, 480.0)


def test_normalize_roi_from_640x480_to_unit_and_scale_to_1920x1080():
    """
    BUG-05: Tọa độ pixel vẽ trên canvas 640x480 phải được chuẩn hóa về 0..1
    bằng reference_width=640 và reference_height=480.
    Khi áp dụng cho khung hình 1920x1080, tọa độ pixel co giãn phóng to đúng tỷ lệ.
    """
    pixel_vertices_480p = [
        Point(160.0, 120.0),  # 25% w, 25% h
        Point(480.0, 120.0),  # 75% w, 25% h
        Point(480.0, 360.0),  # 75% w, 75% h
        Point(160.0, 360.0)   # 25% w, 75% h
    ]

    norm_pts = normalize_polygon_to_unit(
        pixel_vertices_480p,
        reference_width=640,
        reference_height=480
    )

    assert len(norm_pts) == 4
    assert norm_pts[0] == Point(0.25, 0.25)
    assert norm_pts[1] == Point(0.75, 0.25)
    assert norm_pts[2] == Point(0.75, 0.75)
    assert norm_pts[3] == Point(0.25, 0.75)

    # Co giãn lên frame 1920x1080:
    poly = RoiPolygonConfig(label="Test-480p-to-1080p", vertices=norm_pts)
    pts_1080p = poly.to_pixel_points(1920, 1080)
    assert pts_1080p[0] == Point(480.0, 270.0)
    assert pts_1080p[1] == Point(1440.0, 270.0)
    assert pts_1080p[2] == Point(1440.0, 810.0)
    assert pts_1080p[3] == Point(480.0, 810.0)


def test_already_normalized_unit_coordinates_remain_unchanged():
    """Tọa độ đã ở khoảng 0..1 không bị chia lại lần nữa"""
    unit_vertices = [Point(0.1, 0.1), Point(0.9, 0.1), Point(0.9, 0.9), Point(0.1, 0.9)]
    norm_pts = normalize_polygon_to_unit(unit_vertices, reference_width=1920, reference_height=1080)
    assert norm_pts == unit_vertices
