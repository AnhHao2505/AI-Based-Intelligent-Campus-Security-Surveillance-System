import 'package:material_ui/material_ui.dart';
import 'package:latlong2/latlong.dart' as ll;

import '../models/area_pin_model.dart';

/// Dòng ghi công bắt buộc của OpenStreetMap / OpenFreeMap (giống `attribution` trên web).
const String osmAttributionText = '© OpenStreetMap contributors';

/// URL style OpenFreeMap giống bản đồ Web (`OPENFREEMAP_STYLE_URL`), tránh bị rate-limit từ raster `tile.openstreetmap.org`.
const String openFreeMapBrightStyleUrl =
    'https://tiles.openfreemap.org/styles/bright';

String campusMapStyleJson({required bool dark}) {
  return openFreeMapBrightStyleUrl;
}

/// Góc nhìn 2D phẳng từ trên xuống — mọi tầng nằm cùng mặt phẳng z = 0 để ghim khu, nhãn tên phòng,
/// cầu thang và tuyến đường khớp khít chính xác tuyệt đối, không lệch thị sai.
/// Đặt bearing -12° để căn thẳng trục Tòa Epsilon trên màn hình dọc điện thoại (giống Web).
const double campusDefaultTilt = 0;
const double campusDefaultBearing = -12;

/// Màu phòng đang chọn / đường viền nổi 2 lớp.
const String selectedRoomColor = '#f97316';
const String selectedRoomOutlineColor = '#1d4ed8';
const String incidentRoomOutlineColor = '#dc2626';

String colorHex(Color c) {
  final argb = c.toARGB32();
  return '#${(argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';
}

/// Lớp mặt phòng, ghim khu, lớp tên phòng, lớp ghim sự cố — nhận chạm để mở thẻ khu (guard_map_screen.dart).
const String campusRoomLayerId = 'campus-room-layer';
const String campusPinsLayerId = 'campus-pins-layer';
const String campusRoomLabelsLayerId = 'campus-room-labels-layer';
const String campusIncidentPinLayerId = 'campus-incident-pin-layer';

/// Quầng đỏ đứng yên dưới ghim sự cố (cùng nguồn với lớp ghim sự cố), không nhận chạm: chạm vẫn rơi vào ghim sự cố.
const String campusIncidentHaloLayerId = 'campus-incident-halo-layer';

/// Thứ tự các lớp bản đồ do app thêm, từ dưới lên (guard_map_screen.dart _onStyleLoaded thêm đúng thứ tự này và ghi
/// log đối chiếu): khối 2D → cửa → viền phòng đang chọn / sự cố → cầu thang → tuyến đường → ghim ngoài trời →
/// QUẦNG SỰ CỐ → TÊN PHÒNG → GHIM SỰ CỐ → vị trí bảo vệ.
const List<String> campusLayerOrder = [
  'campus-slab-layer',
  campusRoomLayerId,
  'campus-wall-layer',
  'campus-room-border-layer',
  'campus-doors-fill',
  'campus-doors-outline',
  'campus-selected-room-halo',
  'campus-selected-room-outline',
  'campus-incident-room-halo',
  'campus-incident-room-outline',
  'campus-portals-layer',
  'campus-route-outline',
  'campus-route-line',
  'campus-route-direct',
  campusPinsLayerId,
  campusIncidentHaloLayerId,
  campusRoomLabelsLayerId,
  campusIncidentPinLayerId,
  'campus-guard-halo',
  'campus-guard-dot',
];

/// Chia ghim cần vẽ: [incident] = khu sự cố đang mở ([incidentAreaId], ưu tiên [incidentArea], sau đó trong [filtered]
/// hoặc [selected]) → luôn giữ cố định ở lớp ghim sự cố ngay cả khi người dùng bấm xem phòng khác;
/// [areas] = ghim khu ngoài trời / chưa khớp phòng theo toà / tầng ([filtered]), bỏ ghim sự cố (không vẽ 2 lần).
({List<AreaPinModel> areas, AreaPinModel? incident}) splitIncidentPin(
  List<AreaPinModel> filtered, {
  AreaPinModel? selected,
  String? incidentAreaId,
  AreaPinModel? incidentArea,
}) {
  AreaPinModel? incident;
  if (incidentAreaId != null) {
    if (incidentArea != null &&
        incidentArea.id == incidentAreaId &&
        incidentArea.centerLatitude != 0.0) {
      incident = incidentArea;
    } else {
      for (final p in filtered) {
        if (p.id == incidentAreaId && p.centerLatitude != 0.0) {
          incident = p;
          break;
        }
      }
      if (incident == null &&
          selected != null &&
          selected.id == incidentAreaId &&
          selected.centerLatitude != 0.0) {
        incident = selected;
      }
    }
  }
  final areas = [
    for (final p in filtered)
      if (p.id != incident?.id) p,
  ];
  final hasSelectedCoords = selected != null && selected.centerLatitude != 0.0;
  if (hasSelectedCoords &&
      selected.id != incident?.id &&
      !areas.any((x) => x.id == selected.id)) {
    areas.add(selected);
  }
  return (areas: areas, incident: incident);
}

/// GeoJSON ghim khu: mỗi khu một điểm, thuộc tính màu theo cấp khu (AreaPinModel.levelColor) và cờ đang chọn.
Map<String, dynamic> pinsGeoJson(
  List<AreaPinModel> pins, {
  String? selectedId,
}) {
  return {
    'type': 'FeatureCollection',
    'features': [
      for (final p in pins)
        {
          'type': 'Feature',
          'id': p.id,
          'properties': {
            'id': p.id,
            'name': p.name,
            'color': colorHex(p.levelColor),
            'selected': p.id == selectedId,
          },
          'geometry': {
            'type': 'Point',
            'coordinates': [p.centerLongitude, p.centerLatitude],
          },
        },
    ],
  };
}

Map<String, dynamic> pointGeoJson(ll.LatLng? point) => {
  'type': 'FeatureCollection',
  'features': [
    if (point != null)
      {
        'type': 'Feature',
        'properties': <String, dynamic>{},
        'geometry': {
          'type': 'Point',
          'coordinates': [point.longitude, point.latitude],
        },
      },
  ],
};
