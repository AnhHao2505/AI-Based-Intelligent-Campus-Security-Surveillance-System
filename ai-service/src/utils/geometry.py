from typing import List, Tuple, Any
from ..core.entity import Point

def is_point_in_polygon(point: Point, polygon: List[Point]) -> bool:
    """
    Thuật toán Ray-Casting kiểm tra 1 điểm có nằm trong Polygon tùy ý (kể cả lồi hoặc lõm) hay không.
    """
    if len(polygon) < 3:
        return False

    inside = False
    n = len(polygon)
    x, y = point.x, point.y

    p1 = polygon[0]
    for i in range(1, n + 1):
        p2 = polygon[i % n]
        if y > min(p1.y, p2.y):
            if y <= max(p1.y, p2.y):
                if x <= max(p1.x, p2.x):
                    if p1.y != p2.y:
                        x_inters = (y - p1.y) * (p2.x - p1.x) / (p2.y - p1.y) + p1.x
                    else:
                        x_inters = p1.x
                    if p1.x == p2.x or x <= x_inters:
                        inside = not inside
        p1 = p2

    return inside

def normalize_polygon(polygon_pts: List[Tuple[float, float]], frame_width: int, frame_height: int) -> List[Point]:
    """Chuyển đổi danh sách tọa độ (có thể là tỉ lệ 0..1 hoặc pixel) thành Point pixel chuẩn"""
    result = []
    for x, y in polygon_pts:
        if 0.0 <= x <= 1.0 and 0.0 <= y <= 1.0:
            result.append(Point(x * frame_width, y * frame_height))
        else:
            result.append(Point(float(x), float(y)))
    return result

def scale_polygon_to_frame(points: List[Any], frame_width: int, frame_height: int) -> List[Point]:
    """
    Chuẩn hóa và co giãn danh sách đỉnh đa giác (Point, Dict, Tuple)
    về đúng tọa độ pixel trên kích thước khung hình (frame_width, frame_height).
    """
    if not points:
        return []

    result: List[Point] = []
    for pt in points:
        if isinstance(pt, Point):
            x, y = pt.x, pt.y
        elif isinstance(pt, dict):
            x = float(pt.get("x", 0.0))
            y = float(pt.get("y", 0.0))
        elif isinstance(pt, (tuple, list)) and len(pt) >= 2:
            x, y = float(pt[0]), float(pt[1])
        else:
            continue

        if 0.0 <= x <= 1.0 and 0.0 <= y <= 1.0 and frame_width > 1 and frame_height > 1:
            px = max(0.0, min(float(frame_width), x * frame_width))
            py = max(0.0, min(float(frame_height), y * frame_height))
            result.append(Point(px, py))
        else:
            result.append(Point(float(x), float(y)))

    return result

def normalize_to_unit(x: float, y: float, ref_width: int, ref_height: int) -> Tuple[float, float]:
    """Chuyển đổi tọa độ pixel về khoảng [0.0, 1.0] dựa trên kích thước tham chiếu"""
    if ref_width <= 0 or ref_height <= 0:
        return (x, y)
    nx = max(0.0, min(1.0, x / ref_width))
    ny = max(0.0, min(1.0, y / ref_height))
    return (round(nx, 4), round(ny, 4))
