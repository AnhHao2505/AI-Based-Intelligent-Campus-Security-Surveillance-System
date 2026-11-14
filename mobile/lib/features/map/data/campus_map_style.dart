import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:latlong2/latlong.dart' as ll;

import '../models/area_pin_model.dart';

/// Dòng ghi công bắt buộc của OpenStreetMap (giống `attribution` của nguồn tile web, CampusMapView.jsx:104).
const String osmAttributionText = '© OpenStreetMap contributors';

/// Nền bản đồ giống web: raster OpenStreetMap, không cần key (frontend/src/components/area/CampusMapView.jsx:91-125).
/// Chế độ tối: giảm độ sáng ô bản đồ (thay cho ColorFilter của flutter_map trước đây).
String campusMapStyleJson({required bool dark}) {
  return jsonEncode({
    'version': 8,
    'sources': {
      'osm-tiles': {
        'type': 'raster',
        'tiles': [
          'https://a.tile.openstreetmap.org/{z}/{x}/{y}.png',
          'https://b.tile.openstreetmap.org/{z}/{x}/{y}.png',
          'https://c.tile.openstreetmap.org/{z}/{x}/{y}.png',
        ],
        'tileSize': 256,
        'attribution': osmAttributionText,
        'maxzoom': 19,
      },
    },
    'layers': [
      {
        'id': 'osm-background',
        'type': 'background',
        'paint': {'background-color': dark ? '#0f172a' : '#f1f5f9'},
      },
      {
        'id': 'osm-tiles-layer',
        'type': 'raster',
        'source': 'osm-tiles',
        'minzoom': 0,
        'maxzoom': 24,
        if (dark) 'paint': {'raster-brightness-max': 0.3, 'raster-saturation': -0.3},
      },
    ],
  });
}

/// Góc nghiêng / xoay mặc định giống web (CAMPUS_CENTER.pitch / bearing, CampusMapView.jsx:67-73).
/// Chỉ là tham số trình bày; tâm và zoom mặc định lấy từ khung bao asset.
const double campusDefaultTilt = 52;
const double campusDefaultBearing = -12;

/// Màu phòng đang chọn ở chế độ quản lý của web (campusIndoor3dData.js:2838-2843).
const String selectedRoomColor = '#f97316';

String colorHex(Color c) {
  final argb = c.toARGB32();
  return '#${(argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';
}

/// Thứ tự các lớp bản đồ do app thêm, từ dưới lên (guard_map_screen.dart _onStyleLoaded thêm đúng thứ tự này và ghi
/// log đối chiếu): khối 3D → cầu thang → tuyến đường → ghim khu → TÊN PHÒNG → vị trí bảo vệ. Tên phòng nằm ngay trên
/// ghim khu để ghim không che chữ; vị trí bảo vệ luôn trên cùng.
const List<String> campusLayerOrder = [
  'campus-slab-layer',
  'campus-room-layer',
  'campus-wall-layer',
  'campus-portals-layer',
  'campus-route-outline',
  'campus-route-line',
  'campus-route-direct',
  'campus-route-floors-layer',
  'campus-pins-layer',
  'campus-room-labels-layer',
  'campus-guard-halo',
  'campus-guard-dot',
];

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
