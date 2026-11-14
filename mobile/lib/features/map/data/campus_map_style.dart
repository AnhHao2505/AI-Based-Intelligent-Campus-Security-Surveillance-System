import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:latlong2/latlong.dart' as ll;

import '../models/area_pin_model.dart';
import 'campus_map_sprites.dart';

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

/// Giá trị paint của ghim khu (bán kính / viền): [value] như cũ dưới [roomLabelMinZoom]; từ mức đó trở lên ghim của khu
/// đang có nhãn trên tầng đang xem (`hasLabel`) và không phải khu đang chọn thì bằng 0 (không vẽ, không nhận chạm),
/// để nhãn không bị ghim che. Dùng biểu thức paint thay cho filter vì MapLibre chỉ tính `zoom` trong filter ở mức zoom
/// nguyên (16.4 sẽ thành 17); paint tính liên tục và không phải xoá / thêm lớp.
List<Object> areaPinHiddenWhenLabelled(Object value) => [
      'step',
      ['zoom'],
      value,
      roomLabelMinZoom,
      [
        'case',
        [
          'all',
          ['get', 'hasLabel'],
          ['!', ['get', 'selected']],
        ],
        0,
        value,
      ],
    ];

/// GeoJSON ghim khu: mỗi khu một điểm, thuộc tính màu theo cấp khu (AreaPinModel.levelColor), cờ đang chọn và cờ
/// `hasLabel` (khu có nhãn tên phòng trên tầng đang xem: id nằm trong [labelledAreaIds], xem roomLabelAreaIds).
Map<String, dynamic> pinsGeoJson(List<AreaPinModel> pins, {String? selectedId, Set<String> labelledAreaIds = const {}}) {
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
            'hasLabel': labelledAreaIds.contains(p.id),
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
