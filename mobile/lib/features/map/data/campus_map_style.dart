import 'package:flutter/material.dart';
import 'package:latlong2/latlong.dart' as ll;

import '../models/area_pin_model.dart';

/// Dòng ghi công bắt buộc của OpenStreetMap / OpenFreeMap (giống `attribution` trên web).
const String osmAttributionText = '© OpenStreetMap contributors';

/// URL style OpenFreeMap giống bản đồ Web (`OPENFREEMAP_STYLE_URL`), tránh bị rate-limit từ raster `tile.openstreetmap.org`.
const String openFreeMapBrightStyleUrl = 'https://tiles.openfreemap.org/styles/bright';

String campusMapStyleJson({required bool dark}) {
  return openFreeMapBrightStyleUrl;
}

/// Góc nhìn 2D phẳng từ trên xuống (khóa Bắc) cho màn hình dẫn đường bảo vệ — mọi tầng nằm cùng mặt phẳng z = 0
/// để ghim khu, nhãn tên phòng, cầu thang và tuyến đường khớp khít chính xác tuyệt đối, không lệch thị sai.
const double campusDefaultTilt = 0;
const double campusDefaultBearing = 0;

/// Màu phòng đang chọn ở chế độ quản lý của web (campusIndoor3dData.js:2838-2843).
const String selectedRoomColor = '#f97316';

String colorHex(Color c) {
  final argb = c.toARGB32();
  return '#${(argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';
}

/// Lớp ghim khu, lớp tên phòng, lớp ghim sự cố — ba lớp nhận chạm để mở thẻ khu (guard_map_screen.dart).
const String campusPinsLayerId = 'campus-pins-layer';
const String campusRoomLabelsLayerId = 'campus-room-labels-layer';
const String campusIncidentPinLayerId = 'campus-incident-pin-layer';

/// Quầng đỏ đứng yên dưới ghim sự cố (cùng nguồn với lớp ghim sự cố), không nhận chạm: chạm vẫn rơi vào ghim sự cố.
const String campusIncidentHaloLayerId = 'campus-incident-halo-layer';

/// Thứ tự các lớp bản đồ do app thêm, từ dưới lên (guard_map_screen.dart _onStyleLoaded thêm đúng thứ tự này và ghi
/// log đối chiếu): khối 3D → cầu thang → tuyến đường → ghim khu → QUẦNG SỰ CỐ → TÊN PHÒNG → GHIM SỰ CỐ → vị trí bảo
/// vệ. Quầng nằm dưới tên phòng để vòng quầng không cắt chữ của nhãn; tên phòng trên ghim khu để ghim không che chữ;
/// ghim sự cố trên tên phòng để nhãn không che ghim; vị trí bảo vệ trên cùng.
const List<String> campusLayerOrder = [
  'campus-slab-layer',
  'campus-room-layer',
  'campus-wall-layer',
  'campus-room-border-layer',
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

/// Chia ghim cần vẽ: [incident] = khu đang chọn nếu nó là khu của sự cố đang mở ([incidentAreaId], kể cả ghim tạm
/// chưa có trong danh sách khu) → vẽ ở lớp ghim sự cố; [areas] = ghim khu theo toà / tầng ([filtered]), bỏ ghim sự cố
/// (không vẽ 2 lần), thêm khu đang chọn khác nếu nó không nằm trong [filtered] (như trước). Khu không có toạ độ bị bỏ.
({List<AreaPinModel> areas, AreaPinModel? incident}) splitIncidentPin(
  List<AreaPinModel> filtered, {
  AreaPinModel? selected,
  String? incidentAreaId,
}) {
  final hasCoords = selected != null && selected.centerLatitude != 0.0;
  final incident = hasCoords && incidentAreaId != null && selected.id == incidentAreaId ? selected : null;
  final areas = [
    for (final p in filtered)
      if (p.id != incident?.id) p,
  ];
  if (incident == null && hasCoords && !areas.any((x) => x.id == selected.id)) areas.add(selected);
  return (areas: areas, incident: incident);
}

/// GeoJSON ghim khu: mỗi khu một điểm, thuộc tính màu theo cấp khu (AreaPinModel.levelColor) và cờ đang chọn.
Map<String, dynamic> pinsGeoJson(List<AreaPinModel> pins, {String? selectedId}) {
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
