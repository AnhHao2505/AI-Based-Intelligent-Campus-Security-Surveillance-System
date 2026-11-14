import '../models/area_pin_model.dart';
import 'campus_map_data.dart';

/// Quy tên tầng (chuỗi trong DB, vd "Tầng Trệt", "Tầng 2", "LV3") về số thứ tự tầng của bản đồ.
/// Chép đúng quy tắc web `resolveFloorOrder` (frontend/src/data/campusIndoor3dData.js:182-235) để mobile và web
/// đặt khu vào cùng một tầng. [isAlpha] = toà là Tòa Alpha (web gọi matchesBuilding(buildingName, "Tòa Alpha")).
int resolveFloorOrder(String? floorName, {bool isAlpha = false}) {
  if (floorName == null || floorName == 'ALL') return 0;
  final fn = floorName.trim().toLowerCase();
  if (fn.isEmpty) return 0;
  if (fn.contains('trệt') || fn == 'g' || fn.contains('tầng g') || fn.contains('ground') || fn.contains('l1')) {
    return 0;
  }
  if (fn.contains('lv5') || fn.contains('tầng 4') || fn.contains('floor 4') || fn.contains('lầu 4') || fn == '4') {
    return 4;
  }
  if (fn.contains('lv4') ||
      fn.contains('floor 3') ||
      fn.contains('lầu 3') ||
      fn == '3' ||
      (fn.contains('tầng 3') && !isAlpha)) {
    return 3;
  }
  if (fn.contains('lv3') ||
      fn.contains('tầng 2') ||
      fn.contains('floor 2') ||
      fn.contains('lầu 2') ||
      fn == '2' ||
      fn.contains('tầng 3')) {
    return 2;
  }
  if (fn.contains('lv2') || fn.contains('tầng 1') || fn.contains('floor 1') || fn.contains('lầu 1') || fn == '1') {
    return 1;
  }
  return 0;
}

/// Tên chuẩn của Tòa Alpha trong web (DEFAULT_BUILDINGS_META, campusIndoor3dData.js:63-67).
const String _alphaCanonicalName = 'Tòa Alpha';

bool _isAlpha(String? building, CampusMapData? campus) {
  if (building == null) return false;
  if (campus != null) return campus.sameBuilding(building, _alphaCanonicalName);
  return building.trim().toLowerCase() == _alphaCanonicalName.toLowerCase();
}

/// Số thứ tự tầng của khu [pin]; null khi khu không có thông tin tầng (khu ngoài trời).
int? floorOrderOfPin(AreaPinModel pin, CampusMapData? campus) {
  final floor = pin.floor;
  if (floor == null || floor.trim().isEmpty) return null;
  return resolveFloorOrder(floor, isAlpha: _isAlpha(pin.building, campus));
}

/// Khu có toạ độ hợp lệ không (backend đã lọc NULL; mobile vẫn bỏ 0 / NaN như trước — map_provider.dart).
bool pinHasCoordinates(AreaPinModel pin) =>
    pin.centerLatitude.isFinite &&
    pin.centerLongitude.isFinite &&
    pin.centerLatitude != 0.0 &&
    pin.centerLongitude != 0.0;

/// Lọc ghim khu theo toà / tầng đang chọn:
/// - bỏ khu không có toạ độ;
/// - [building] khác null → khu phải cùng toà (so theo tên chuẩn + alias của manifest);
/// - [floorOrder] khác null → khu có thông tin tầng phải đúng tầng; khu không có tầng (ngoài trời) luôn hiện.
List<AreaPinModel> filterPinsForView({
  required List<AreaPinModel> pins,
  String? building,
  int? floorOrder,
  CampusMapData? campus,
}) {
  return pins.where((pin) {
    if (!pinHasCoordinates(pin)) return false;
    if (building != null && building.isNotEmpty) {
      final same = campus != null
          ? campus.sameBuilding(pin.building, building)
          : (pin.building ?? '').trim().toLowerCase() == building.trim().toLowerCase();
      if (!same) return false;
    }
    if (floorOrder != null) {
      final pinFloor = floorOrderOfPin(pin, campus);
      if (pinFloor != null && pinFloor != floorOrder) return false;
    }
    return true;
  }).toList();
}

/// Toà / tầng / phòng cần hiển thị khi mở bản đồ từ một sự cố.
class IncidentMapFocus {
  /// Tên toà chuẩn (theo manifest); null khi không xác định (khu ngoài trời hoặc toà không có bản đồ 3D).
  final String? building;

  /// Tầng của khu; khu không có tầng → tầng trệt (0).
  final int floorOrder;

  /// Id khối phòng chứa tâm khu (để tô nổi); null khi không tìm thấy.
  final String? roomId;

  const IncidentMapFocus({this.building, required this.floorOrder, this.roomId});
}

/// Tính toà / tầng / phòng cho khu [pin] của sự cố.
IncidentMapFocus resolveIncidentFocus(AreaPinModel pin, CampusMapData? campus) {
  final floorOrder = floorOrderOfPin(pin, campus) ?? 0;
  final building = campus?.buildingFor(pin.building)?.name;
  String? roomId;
  if (campus != null && pinHasCoordinates(pin)) {
    roomId = campus.roomIdAt(
      latitude: pin.centerLatitude,
      longitude: pin.centerLongitude,
      building: building,
      floorOrder: floorOrder,
    );
  }
  return IncidentMapFocus(building: building, floorOrder: floorOrder, roomId: roomId);
}
