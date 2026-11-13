import 'dart:convert';
import 'dart:math' as math;

import 'campus_graph.dart';

/// Lỗi nạp / phân tích asset bản đồ khuôn viên. [message] hiển thị được cho người dùng.
class CampusMapLoadException implements Exception {
  final String message;
  final Object? cause;

  const CampusMapLoadException(this.message, [this.cause]);

  @override
  String toString() => 'CampusMapLoadException: $message${cause != null ? ' ($cause)' : ''}';
}

/// Phiên bản cấu trúc asset mà app đọc được (manifest.json → schemaVersion, do frontend/scripts/export-campus-map.mjs sinh).
const int supportedCampusSchemaVersion = 1;

/// Một tầng của một toà trong manifest.
class CampusFloorRef {
  final int floorOrder;
  final String file;
  final int features;

  const CampusFloorRef({required this.floorOrder, required this.file, required this.features});
}

/// Một toà trong manifest: tên chuẩn, các tên gọi khác (alias) và danh sách tầng.
class CampusBuilding {
  final String name;
  final List<String> aliases;
  final List<CampusFloorRef> floors;

  const CampusBuilding({required this.name, required this.aliases, required this.floors});

  /// Tên [other] có chỉ cùng toà này không (so không phân biệt hoa thường, theo tên chuẩn và alias).
  bool matches(String? other) {
    if (other == null) return false;
    final o = other.trim().toLowerCase();
    if (o.isEmpty) return false;
    if (name.trim().toLowerCase() == o) return true;
    return aliases.any((a) => a.trim().toLowerCase() == o);
  }

  List<int> get floorOrders => floors.map((f) => f.floorOrder).toList()..sort();
}

/// Khung bao (kinh / vĩ độ) của toàn bộ khối 3D khuôn viên — dùng làm tâm và mức zoom mặc định.
class CampusBounds {
  final double west;
  final double south;
  final double east;
  final double north;

  const CampusBounds({required this.west, required this.south, required this.east, required this.north});

  double get centerLatitude => (south + north) / 2;
  double get centerLongitude => (west + east) / 2;

  bool contains(double lat, double lon) => lat >= south && lat <= north && lon >= west && lon <= east;
}

/// Khối 3D của một tầng (FeatureCollection GeoJSON đã phân tích).
class CampusFloorLayer {
  final String building;
  final int floorOrder;
  final List<Map<String, dynamic>> features;

  const CampusFloorLayer({required this.building, required this.floorOrder, required this.features});

  int get featureCount => features.length;

  /// Số khối theo loại (`layer`: slab, wall, room, door, portal).
  Map<String, int> get countsByLayer {
    final out = <String, int>{};
    for (final f in features) {
      final layer = (f['properties'] as Map?)?['layer']?.toString() ?? '?';
      out[layer] = (out[layer] ?? 0) + 1;
    }
    return out;
  }
}

/// Toàn bộ dữ liệu bản đồ khuôn viên đã nạp từ mobile/assets/campus/.
class CampusMapData {
  final int schemaVersion;
  final String seedVersion;
  final List<CampusBuilding> buildings;
  final Map<String, CampusFloorLayer> floors;
  final CampusBounds bounds;

  /// Đồ thị đường đi (graph.json); null khi asset không có đồ thị → dẫn đường chỉ vẽ hướng thẳng.
  final CampusGraph? graph;

  const CampusMapData({
    required this.schemaVersion,
    required this.seedVersion,
    required this.buildings,
    required this.floors,
    required this.bounds,
    this.graph,
  });

  static String floorKey(String building, int floorOrder) => '$building::$floorOrder';

  /// Toà (tên chuẩn) ứng với tên [name] bất kỳ (tên chuẩn hoặc alias); không có thì null.
  CampusBuilding? buildingFor(String? name) {
    for (final b in buildings) {
      if (b.matches(name)) return b;
    }
    return null;
  }

  /// Hai tên có chỉ cùng một toà không (dựa vào alias trong manifest; không có trong manifest thì so chuỗi).
  bool sameBuilding(String? a, String? b) {
    if (a == null || b == null) return false;
    final ba = buildingFor(a);
    if (ba != null) return ba.matches(b);
    return a.trim().toLowerCase() == b.trim().toLowerCase();
  }

  /// Các tầng của toà [building]; null = hợp các tầng của mọi toà.
  List<int> floorOrdersFor(String? building) {
    if (building != null) {
      return buildingFor(building)?.floorOrders ?? const [];
    }
    final all = <int>{};
    for (final b in buildings) {
      all.addAll(b.floorOrders);
    }
    return all.toList()..sort();
  }

  /// GeoJSON các khối của tầng [floorOrder] thuộc toà [building] (null = mọi toà), chỉ giữ các loại [layers].
  Map<String, dynamic> featureCollectionFor({String? building, required int floorOrder, Set<String>? layers}) {
    final features = <Map<String, dynamic>>[];
    for (final b in buildings) {
      if (building != null && !b.matches(building)) continue;
      final floor = floors[floorKey(b.name, floorOrder)];
      if (floor == null) continue;
      for (final f in floor.features) {
        final layer = (f['properties'] as Map?)?['layer']?.toString();
        if (layers != null && !layers.contains(layer)) continue;
        features.add(f);
      }
    }
    return {'type': 'FeatureCollection', 'features': features};
  }

  /// Id khối phòng (layer = room) chứa điểm (lat, lon) ở toà / tầng cho trước; không có thì null.
  /// Cùng cách web gán khu vào phòng: điểm tâm khu nằm trong polygon phòng (campusIndoor3dData.js:2806-2816).
  String? roomIdAt({required double latitude, required double longitude, String? building, required int floorOrder}) {
    for (final b in buildings) {
      if (building != null && !b.matches(building)) continue;
      final floor = floors[floorKey(b.name, floorOrder)];
      if (floor == null) continue;
      for (final f in floor.features) {
        final props = f['properties'] as Map?;
        if (props?['layer'] != 'room') continue;
        final geom = f['geometry'] as Map?;
        if (geom?['type'] != 'Polygon') continue;
        final rings = (geom!['coordinates'] as List).cast<List>();
        if (rings.isEmpty) continue;
        if (_pointInRing(longitude, latitude, rings.first) &&
            !rings.skip(1).any((hole) => _pointInRing(longitude, latitude, hole))) {
          return props?['id']?.toString();
        }
      }
    }
    return null;
  }
}

extension CampusFootprint on CampusMapData {
  /// Toà (tên chuẩn) có mặt sàn tầng trệt (layer = slab: phòng + hành lang) chứa điểm (lat, lon); ngoài mọi toà → null.
  /// Dùng để biết vị trí GPS đang ở trong nhà (GPS trong nhà không đủ tin cậy để gắn vào nút gần nhất).
  String? buildingAt({required double latitude, required double longitude}) {
    for (final b in buildings) {
      final floor = floors[CampusMapData.floorKey(b.name, 0)];
      if (floor == null) continue;
      for (final f in floor.features) {
        if ((f['properties'] as Map?)?['layer'] != 'slab') continue;
        final geom = f['geometry'] as Map?;
        if (geom?['type'] != 'Polygon') continue;
        final rings = (geom!['coordinates'] as List).cast<List>();
        if (rings.isNotEmpty && _pointInRing(longitude, latitude, rings.first)) return b.name;
      }
    }
    return null;
  }
}

bool _pointInRing(double x, double y, List ring) {
  var inside = false;
  for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    final xi = (ring[i][0] as num).toDouble();
    final yi = (ring[i][1] as num).toDouble();
    final xj = (ring[j][0] as num).toDouble();
    final yj = (ring[j][1] as num).toDouble();
    final intersects = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
    if (intersects) inside = !inside;
  }
  return inside;
}

/// Mức zoom (Web Mercator, ô 512 px của MapLibre) để khung [bounds] vừa vùng nhìn [widthPx] × [heightPx]
/// trừ lề [paddingPx] mỗi bên. Dùng cho camera ban đầu khi chưa có bản đồ để gọi newLatLngBounds.
double zoomToFitBounds(CampusBounds bounds, double widthPx, double heightPx, {double paddingPx = 0}) {
  final w = math.max(1.0, widthPx - 2 * paddingPx);
  final h = math.max(1.0, heightPx - 2 * paddingPx);
  double mercY(double lat) {
    final s = math.sin(lat * math.pi / 180);
    return math.log((1 + s) / (1 - s)) / 2;
  }

  final lonSpan = math.max(1e-9, (bounds.east - bounds.west).abs()) / 360;
  final latSpan = math.max(1e-9, (mercY(bounds.north) - mercY(bounds.south)).abs()) / (2 * math.pi);
  final zoomX = math.log(w / 512 / lonSpan) / math.ln2;
  final zoomY = math.log(h / 512 / latSpan) / math.ln2;
  return math.min(zoomX, zoomY);
}

/// Phân tích asset (chạy ngoài luồng giao diện qua compute). [files]: đường dẫn tương đối (vd `manifest.json`,
/// `layers/toa-alpha__f0.geojson`) → nội dung chuỗi.
CampusMapData parseCampusAssets(Map<String, String> files) {
  final manifestRaw = files['manifest.json'];
  if (manifestRaw == null) {
    throw const CampusMapLoadException('Thiếu manifest.json trong dữ liệu bản đồ khuôn viên');
  }
  final Map<String, dynamic> manifest;
  try {
    manifest = jsonDecode(manifestRaw) as Map<String, dynamic>;
  } catch (e) {
    throw CampusMapLoadException('manifest.json không đúng định dạng JSON', e);
  }
  final schema = manifest['schemaVersion'];
  if (schema != supportedCampusSchemaVersion) {
    throw CampusMapLoadException('Phiên bản dữ liệu bản đồ ($schema) không được hỗ trợ');
  }
  final seed = manifest['seedVersion'];
  if (seed is! String || seed.trim().isEmpty) {
    throw const CampusMapLoadException('manifest.json thiếu seedVersion');
  }

  final buildings = <CampusBuilding>[];
  final floors = <String, CampusFloorLayer>{};
  double west = double.infinity, south = double.infinity, east = -double.infinity, north = -double.infinity;

  void extend(dynamic coords) {
    if (coords is List && coords.isNotEmpty && coords.first is num) {
      final lon = (coords[0] as num).toDouble();
      final lat = (coords[1] as num).toDouble();
      west = math.min(west, lon);
      east = math.max(east, lon);
      south = math.min(south, lat);
      north = math.max(north, lat);
    } else if (coords is List) {
      for (final c in coords) {
        extend(c);
      }
    }
  }

  for (final rawB in (manifest['buildings'] as List? ?? const [])) {
    final b = rawB as Map<String, dynamic>;
    final name = b['name']?.toString() ?? '';
    final refs = <CampusFloorRef>[];
    for (final rawF in (b['floors'] as List? ?? const [])) {
      final f = rawF as Map<String, dynamic>;
      final ref = CampusFloorRef(
        floorOrder: (f['floorOrder'] as num).toInt(),
        file: f['file'].toString(),
        features: (f['features'] as num?)?.toInt() ?? -1,
      );
      refs.add(ref);
      final raw = files[ref.file];
      if (raw == null) {
        throw CampusMapLoadException('Thiếu file ${ref.file} trong dữ liệu bản đồ khuôn viên');
      }
      final Map<String, dynamic> fc;
      try {
        fc = jsonDecode(raw) as Map<String, dynamic>;
      } catch (e) {
        throw CampusMapLoadException('${ref.file} không đúng định dạng GeoJSON', e);
      }
      final features = [
        for (final e in (fc['features'] as List? ?? const [])) e as Map<String, dynamic>,
      ];
      if (ref.features >= 0 && features.length != ref.features) {
        throw CampusMapLoadException(
            '${ref.file} có ${features.length} khối, manifest ghi ${ref.features} — dữ liệu không đồng bộ');
      }
      for (final feat in features) {
        extend((feat['geometry'] as Map?)?['coordinates']);
      }
      floors[CampusMapData.floorKey(name, ref.floorOrder)] =
          CampusFloorLayer(building: name, floorOrder: ref.floorOrder, features: features);
    }
    buildings.add(CampusBuilding(
      name: name,
      aliases: (b['aliases'] as List? ?? const []).map((e) => e.toString()).toList(),
      floors: refs,
    ));
  }

  if (buildings.isEmpty || floors.isEmpty || !west.isFinite) {
    throw const CampusMapLoadException('Dữ liệu bản đồ khuôn viên rỗng');
  }

  // Đồ thị đường đi (P-MAP-2). Thiếu / hỏng thì vẫn mở được bản đồ 3D; dẫn đường rơi về hướng thẳng.
  CampusGraph? graph;
  final graphFile = (manifest['graph'] as Map?)?['file']?.toString();
  final graphRaw = graphFile == null ? null : files[graphFile];
  if (graphRaw != null) {
    try {
      final g = CampusGraph.fromJson(jsonDecode(graphRaw) as Map<String, dynamic>);
      graph = g.nodes.isEmpty ? null : g;
    } catch (_) {
      graph = null;
    }
  }

  return CampusMapData(
    schemaVersion: schema as int,
    seedVersion: seed,
    buildings: buildings,
    floors: floors,
    bounds: CampusBounds(west: west, south: south, east: east, north: north),
    graph: graph,
  );
}
