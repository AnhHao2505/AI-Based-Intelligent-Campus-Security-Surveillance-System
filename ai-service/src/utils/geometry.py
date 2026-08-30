from typing import List, Tuple
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
