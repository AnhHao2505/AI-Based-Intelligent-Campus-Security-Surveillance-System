import 'dart:math' as math;

import '../models/area_pin_model.dart';
import 'campus_floor_resolver.dart';
import 'campus_map_data.dart';
import 'campus_map_style.dart';

// Dữ liệu cho lớp biểu tượng cầu thang / thang máy và lớp tên phòng trên bản đồ 3D (P-MAP-3).
// Không phụ thuộc MapLibre: chỉ dựng GeoJSON từ asset + danh sách khu, để test được không cần bản đồ.
// Chép quy tắc web buildVector3dLayers (frontend/src/data/campusIndoor3dData.js).

/// Id ảnh của cầu thang / thang máy, trùng tên ảnh web (frontend/src/utils/mapPortalIcons.js:114-117).
const String portalStairsImageId = 'portal-stairs';
const String portalElevatorImageId = 'portal-elevator';

/// Ảnh cho [nodeType] của điểm `layer = portal`: ELEVATOR → thang máy, còn lại → cầu thang (campusIndoor3dData.js:3167).
String portalIconImageId(String? nodeType) =>
    nodeType == 'ELEVATOR' ? portalElevatorImageId : portalStairsImageId;

const Map<String, dynamic> _emptyCollection = {'type': 'FeatureCollection', 'features': <dynamic>[]};

/// Điểm cầu thang / thang máy cần vẽ cho toà [building] (null = "Tất cả") ở tầng [floorOrder].
/// - Chọn một toà: chỉ điểm của đúng toà + tầng đó (web bỏ tầng dưới / toà khác, campusIndoor3dData.js:3151-3157).
/// - "Tất cả" toà: web (toàn cảnh, không giãn tầng) chỉ vẽ tầng 0 của mọi toà (campusIndoor3dData.js:3158-3160).
///   App không có chế độ giãn tầng nên luôn theo nhánh này, bất kể tầng đang xem.
Map<String, dynamic> portalSymbolsGeoJson(CampusMapData? campus, {String? building, required int floorOrder}) {
  if (campus == null) return _emptyCollection;
  final targetFloor = building == null ? 0 : floorOrder;
  final features = <Map<String, dynamic>>[];
  for (final b in campus.buildings) {
    if (building != null && !b.matches(building)) continue;
    final floor = campus.floors[CampusMapData.floorKey(b.name, targetFloor)];
    if (floor == null) continue;
    for (final f in floor.features) {
      final props = f['properties'] as Map?;
      if (props?['layer'] != 'portal') continue;
      if ((f['geometry'] as Map?)?['type'] != 'Point') continue;
      final nodeType = props?['nodeType']?.toString();
      features.add({
        'type': 'Feature',
        'properties': {
          'id': props?['id'],
          'nodeType': nodeType,
          'iconImage': portalIconImageId(nodeType),
          'portalGroupId': props?['portalGroupId'],
          'building': b.name,
          'floorOrder': targetFloor,
          'heightOffset': props?['heightOffset'],
        },
        'geometry': f['geometry'],
      });
    }
  }
  return {'type': 'FeatureCollection', 'features': features};
}

/// Mã cấp khu dùng cho nhãn: viết hoa, bỏ khoảng trắng; rỗng → PUBLIC (web extractAreaLevelCode + `|| "PUBLIC"`,
/// campusIndoor3dData.js:2897-2898).
String roomLabelLevelCode(String? areaLevel) {
  final code = (areaLevel ?? '').trim().toUpperCase();
  return code.isEmpty ? 'PUBLIC' : code;
}

const String roomLabelImagePrefix = 'room-label::';

/// Id ảnh nhãn: một id cho mỗi tổ hợp (đang chọn, cấp, tên) — cùng dạng id web
/// (buildRoomLabelSpriteId, frontend/src/utils/mapRoomLabelSprites.js:31-43).
String roomLabelImageId({required String name, required String levelCode, required bool selected}) =>
    '$roomLabelImagePrefix${selected ? '1' : '0'}:${roomLabelLevelCode(levelCode)}:${Uri.encodeComponent(name.trim())}';

/// Tách id ảnh nhãn thành (tên, cấp, đang chọn); không phải id nhãn → null.
({String name, String levelCode, bool selected})? parseRoomLabelImageId(String id) {
  if (!id.startsWith(roomLabelImagePrefix)) return null;
  final payload = id.substring(roomLabelImagePrefix.length);
  final first = payload.indexOf(':');
  if (first < 0) return null;
  final second = payload.indexOf(':', first + 1);
  if (second < 0) return null;
  final level = payload.substring(first + 1, second);
  final encoded = payload.substring(second + 1);
  String name;
  try {
    name = Uri.decodeComponent(encoded);
  } catch (_) {
    name = encoded;
  }
  return (name: name, levelCode: level.isEmpty ? 'PUBLIC' : level, selected: payload.substring(0, first) == '1');
}

/// Nhãn tên phòng cho toà [building] ở tầng [floorOrder]: mỗi phòng (layer = room) khớp một khu trong [pins]
/// có một điểm nhãn mang tên khu, đặt ở điểm trong phòng giống web.
/// - "Tất cả" toà ([building] null) → không có nhãn (web chỉ vẽ nhãn khi không ở toàn cảnh, campusIndoor3dData.js:2900-2905).
/// - Ghép phòng ↔ khu như web (campusIndoor3dData.js:2811-2820): khu có toạ độ, cùng toà (khu không ghi toà thì
///   khớp mọi toà), cùng tầng, tâm khu nằm trong phòng; lấy khu đầu tiên khớp.
/// - [selectedAreaId]: khu đang chọn → nhãn nổi (ảnh riêng) và được ưu tiên đặt trước (`sortKey` 0, còn lại 10).
Map<String, dynamic> roomLabelsGeoJson(
  CampusMapData? campus,
  List<AreaPinModel> pins, {
  String? building,
  required int floorOrder,
  String? selectedAreaId,
}) {
  if (campus == null || building == null || pins.isEmpty) return _emptyCollection;
  final features = <Map<String, dynamic>>[];
  for (final b in campus.buildings) {
    if (!b.matches(building)) continue;
    final floor = campus.floors[CampusMapData.floorKey(b.name, floorOrder)];
    if (floor == null) continue;
    final candidates = [
      for (final p in pins)
        if (pinHasCoordinates(p) &&
            ((p.building ?? '').trim().isEmpty || campus.sameBuilding(p.building, b.name)) &&
            (floorOrderOfPin(p, campus) ?? 0) == floorOrder)
          p,
    ];
    if (candidates.isEmpty) continue;
    for (final f in floor.features) {
      final props = f['properties'] as Map?;
      if (props?['layer'] != 'room') continue;
      final geom = f['geometry'] as Map?;
      if (geom?['type'] != 'Polygon') continue;
      final rings = [for (final r in (geom!['coordinates'] as List)) _toRing(r as List)];
      if (rings.isEmpty || rings.first.length < 3) continue;
      final outer = rings.first;
      final holes = rings.skip(1).toList();
      AreaPinModel? matched;
      for (final p in candidates) {
        if (pointInPolygonWithHoles([p.centerLongitude, p.centerLatitude], outer, holes)) {
          matched = p;
          break;
        }
      }
      final name = matched?.name.trim() ?? '';
      if (matched == null || name.isEmpty) continue;
      final level = roomLabelLevelCode(matched.areaLevel);
      final selected = matched.id == selectedAreaId;
      features.add({
        'type': 'Feature',
        // Id của feature = id khu: MapLibre Android trả feature.id() khi bấm (onFeatureTapped), giống lớp ghim
        'id': matched.id,
        'properties': {
          'id': props?['id'],
          'areaId': matched.id,
          'name': name,
          'areaLevelCode': level,
          'imageId': roomLabelImageId(name: name, levelCode: level, selected: selected),
          'isSelected': selected,
          'sortKey': selected ? 0 : 10,
          'building': b.name,
          'floorOrder': floorOrder,
        },
        'geometry': {'type': 'Point', 'coordinates': polygonInteriorPoint(outer, holes)},
      });
    }
  }
  return {'type': 'FeatureCollection', 'features': features};
}

/// Dựng GeoJSON cho lớp phòng (`layer == 'room'`) có tô màu theo cấp độ khu từ [pins], tách riêng polygon của
/// phòng đang chọn ([selectedAreaId]) và phòng đang có sự cố ([incidentAreaId]) để vẽ viền halo 2 lớp, đồng thời
/// trả về tập [matchedAreaIds] để ẩn chấm tròn ghim thừa bên trong các phòng đã tô màu.
({
  Map<String, dynamic> rooms,
  Map<String, dynamic> selectedRoom,
  Map<String, dynamic> incidentRoom,
  Set<String> matchedAreaIds,
}) coloredRoomsGeoJson(
  CampusMapData? campus,
  List<AreaPinModel> pins, {
  String? building,
  required int floorOrder,
  String? selectedAreaId,
  String? incidentAreaId,
}) {
  if (campus == null) {
    return (
      rooms: _emptyCollection,
      selectedRoom: _emptyCollection,
      incidentRoom: _emptyCollection,
      matchedAreaIds: const <String>{},
    );
  }
  final roomFeatures = <Map<String, dynamic>>[];
  final selectedFeatures = <Map<String, dynamic>>[];
  final incidentFeatures = <Map<String, dynamic>>[];
  final matchedAreaIds = <String>{};

  for (final b in campus.buildings) {
    if (building != null && !b.matches(building)) continue;
    final floor = campus.floors[CampusMapData.floorKey(b.name, floorOrder)];
    if (floor == null) continue;
    final candidates = [
      for (final p in pins)
        if (pinHasCoordinates(p) &&
            ((p.building ?? '').trim().isEmpty || campus.sameBuilding(p.building, b.name)) &&
            (floorOrderOfPin(p, campus) ?? 0) == floorOrder)
          p,
    ];
    for (final f in floor.features) {
      final props = f['properties'] as Map?;
      if (props?['layer'] != 'room') continue;
      final geom = f['geometry'] as Map?;
      AreaPinModel? matched;
      if (geom?['type'] == 'Polygon' && candidates.isNotEmpty) {
        final rings = [for (final r in (geom!['coordinates'] as List)) _toRing(r as List)];
        if (rings.isNotEmpty && rings.first.length >= 3) {
          final outer = rings.first;
          final holes = rings.skip(1).toList();
          for (final p in candidates) {
            if (pointInPolygonWithHoles([p.centerLongitude, p.centerLatitude], outer, holes)) {
              matched = p;
              break;
            }
          }
        }
      }
      if (matched != null) matchedAreaIds.add(matched.id);
      final isIncident = matched != null && incidentAreaId != null && matched.id == incidentAreaId;
      final isSelected = matched != null && selectedAreaId != null && matched.id == selectedAreaId;
      final fillColor = matched != null ? colorHex(matched.levelColor) : (props?['color']?.toString() ?? '#94a3b8');
      final feature = <String, dynamic>{
        'type': 'Feature',
        if (matched != null) 'id': matched.id,
        'properties': {
          ...?props?.cast<String, dynamic>(),
          'color': fillColor,
          if (matched != null) 'areaId': matched.id,
          'isSelected': isSelected,
          'isIncident': isIncident,
        },
        'geometry': geom,
      };
      roomFeatures.add(feature);
      if (isIncident) {
        incidentFeatures.add(feature);
      } else if (isSelected) {
        selectedFeatures.add(feature);
      }
    }
  }
  return (
    rooms: {'type': 'FeatureCollection', 'features': roomFeatures},
    selectedRoom: {'type': 'FeatureCollection', 'features': selectedFeatures},
    incidentRoom: {'type': 'FeatureCollection', 'features': incidentFeatures},
    matchedAreaIds: matchedAreaIds,
  );
}

/// Chỉ giữ lại các ghim khu chưa khớp vào polygon phòng nào (khu vực ngoài trời / sân / cổng).
List<AreaPinModel> filterUnmatchedAreaPins(List<AreaPinModel> pins, Set<String> matchedAreaIds) {
  if (matchedAreaIds.isEmpty) return pins;
  return [
    for (final p in pins)
      if (!matchedAreaIds.contains(p.id)) p,
  ];
}

/// GeoJSON lớp cửa (`layer == 'door'`) trên tầng đang xem.
Map<String, dynamic> doorsGeoJson(CampusMapData? campus, {String? building, required int floorOrder}) {
  if (campus == null) return _emptyCollection;
  return campus.featureCollectionFor(building: building, floorOrder: floorOrder, layers: const {'door'});
}

/// Id khu cần mở cho một lần chạm: [featureId] là feature MapLibre trả về ở lớp [layerId]; [labelIdsAtPoint] là id các
/// nhãn tên phòng nằm ngay tại điểm chạm. Chỉ khi chạm rơi vào lớp GHIM KHU ([campusPinsLayerId]) mà có nhãn tại đó
/// → khu của nhãn (nhãn nằm trên ghim khu). Lớp ghim sự cố nằm trên nhãn nên chạm vào nó luôn là sự cố.
String tappedAreaId({
  required String layerId,
  required String featureId,
  Iterable<Object?> labelIdsAtPoint = const [],
}) {
  if (layerId != campusPinsLayerId) return featureId;
  for (final id in labelIdsAtPoint) {
    if (id != null && id.toString().isNotEmpty) return id.toString();
  }
  return featureId;
}

/// Khu ứng với id của feature vừa bấm (ghim hoặc nhãn; nhãn mang id khu ở feature id và `areaId`): tìm trong [pins],
/// không có thì là [selected] nếu trùng id (ghim tạm của sự cố chưa có trong danh sách); không thấy → null.
AreaPinModel? findAreaPinById(Iterable<AreaPinModel> pins, String id, {AreaPinModel? selected}) {
  for (final p in pins) {
    if (p.id == id) return p;
  }
  return selected?.id == id ? selected : null;
}

/// Các id ảnh nhãn có trong [labels] (để đăng ký ảnh trước khi đẩy GeoJSON lên bản đồ).
Set<String> roomLabelImageIds(Map<String, dynamic> labels) => {
      for (final f in (labels['features'] as List? ?? const []))
        if ((f as Map)['properties']?['imageId'] is String) f['properties']['imageId'] as String,
    };

List<List<double>> _toRing(List raw) => [
      for (final p in raw) [((p as List)[0] as num).toDouble(), (p[1] as num).toDouble()],
    ];

// ------------------------------------------------------------------ hình học (chép web)

bool _pointOnSegment(double px, double py, double ax, double ay, double bx, double by, double eps) {
  final dx = bx - ax;
  final dy = by - ay;
  final lenSq = dx * dx + dy * dy;
  if (lenSq < 1e-14) return _hypot(px - ax, py - ay) <= eps;
  final t = (((px - ax) * dx + (py - ay) * dy) / lenSq).clamp(0.0, 1.0);
  return _hypot(px - (ax + t * dx), py - (ay + t * dy)) <= eps;
}

double _hypot(double x, double y) => math.sqrt(x * x + y * y);

/// Điểm nằm trong vòng; điểm trên cạnh (sai số 1e-6 độ) tính là trong (web isPointInRing, campusIndoor3dData.js:1938-1955).
bool _pointInRing(List<double> pt, List<List<double>> ring) {
  if (ring.length < 3) return false;
  final x = pt[0], y = pt[1];
  var inside = false;
  for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    final xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
    if (_pointOnSegment(x, y, xi, yi, xj, yj, 1e-6)) return true;
    if ((yi > y) != (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12) + xi) inside = !inside;
  }
  return inside;
}

/// Điểm nằm hẳn trong vòng; điểm trên cạnh (sai số 1e-7) tính là ngoài (web isPointInRingStrict, :754-771).
bool _pointInRingStrict(List<double> pt, List<List<double>> ring) {
  if (ring.length < 3) return false;
  final x = pt[0], y = pt[1];
  var inside = false;
  for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    final xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
    if (_pointOnSegment(x, y, xi, yi, xj, yj, 1e-7)) return false;
    if ((yi > y) != (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12) + xi) inside = !inside;
  }
  return inside;
}

/// Trong vòng ngoài và không nằm hẳn trong lỗ nào (web isPointInPolygonWithHoles, :776-780).
bool pointInPolygonWithHoles(List<double> pt, List<List<double>> outer, List<List<List<double>>> holes) {
  if (!_pointInRing(pt, outer)) return false;
  return !holes.any((h) => _pointInRingStrict(pt, h));
}

double _round(double v, int digits) => double.parse(v.toStringAsFixed(digits));

/// Trung bình các đỉnh (bỏ đỉnh đóng vòng), làm tròn 6 chữ số (web getRingCentroid, :1957-1970).
List<double> _ringCentroid(List<List<double>> ring) {
  final n = ring.length > 1 && ring.first[0] == ring.last[0] ? ring.length - 1 : ring.length;
  var sx = 0.0, sy = 0.0;
  for (var i = 0; i < n; i++) {
    sx += ring[i][0];
    sy += ring[i][1];
  }
  return [_round(sx / n, 6), _round(sy / n, 6)];
}

/// Điểm chắc chắn nằm trong phòng (ngoài lỗ) để đặt nhãn: tâm nếu tâm nằm hẳn trong vòng và không có lỗ;
/// không thì trung điểm của dây cung ngang dài nhất (web getPolygonInteriorPoint, campusIndoor3dData.js:786-855;
/// web đặt nhãn tại face.seedCoord = điểm này, :1376 và :2855-2856).
List<double> polygonInteriorPoint(List<List<double>> outer, [List<List<List<double>>> holes = const []]) {
  final centroid = _ringCentroid(outer);
  if (holes.isEmpty && _pointInRingStrict(centroid, outer)) return centroid;

  var minLat = double.infinity, maxLat = -double.infinity;
  final lats = <double>{};
  for (final p in outer) {
    if (p[1] < minLat) minLat = p[1];
    if (p[1] > maxLat) maxLat = p[1];
    lats.add(p[1]);
  }
  for (final h in holes) {
    for (final p in h) {
      lats.add(p[1]);
    }
  }
  final sorted = lats.toList()..sort();
  final samples = <double>[];
  for (var i = 0; i < sorted.length - 1; i++) {
    if (sorted[i + 1] - sorted[i] > 1e-9) samples.add((sorted[i] + sorted[i + 1]) / 2);
  }
  for (var s = 1; s <= 12; s++) {
    samples.add(minLat + (maxLat - minLat) * s / 13);
  }

  List<double>? best = pointInPolygonWithHoles(centroid, outer, holes) ? centroid : null;
  var bestSpan = -1.0;
  final allRings = [outer, ...holes];
  for (final y in samples) {
    final xs = <double>[];
    for (final ring in allRings) {
      for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        final yi = ring[i][1], yj = ring[j][1];
        if ((yi > y) != (yj > y)) {
          final xi = ring[i][0], xj = ring[j][0];
          xs.add(xi + ((y - yi) * (xj - xi)) / (yj - yi));
        }
      }
    }
    xs.sort();
    for (var k = 0; k + 1 < xs.length; k += 2) {
      final span = xs[k + 1] - xs[k];
      if (span <= bestSpan) continue;
      final cand = [_round((xs[k] + xs[k + 1]) / 2, 7), _round(y, 7)];
      if (pointInPolygonWithHoles(cand, outer, holes)) {
        bestSpan = span;
        best = cand;
      }
    }
  }
  return best ?? centroid;
}
