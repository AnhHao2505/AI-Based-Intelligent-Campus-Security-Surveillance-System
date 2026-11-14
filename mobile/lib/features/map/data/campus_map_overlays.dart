import 'campus_map_data.dart';

// Dữ liệu cho lớp biểu tượng cầu thang / thang máy trên bản đồ 3D (P-MAP-3).
// Không phụ thuộc MapLibre: chỉ dựng GeoJSON từ asset, để test được không cần bản đồ.
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
