import 'package:latlong2/latlong.dart';

import 'campus_graph.dart';
import 'campus_map_data.dart';

/// GPS cách nút ngoài trời gần nhất của đồ thị quá ngưỡng này (m) thì coi là ở xa khuôn viên (hoặc GPS sai):
/// không gắn vị trí GPS vào đồ thị mà xuất phát từ toà / tầng người dùng đang chọn.
/// HẰNG KỸ THUẬT (về độ tin cậy GPS, không phải quy tắc nghiệp vụ): sai số GPS điện thoại ngoài trời thường dưới
/// 20 m; 150 m vẫn đủ rộng cho mọi điểm trong sân trường (các nút ngoài trời cách nhau dưới 100 m).
const double kGpsFarFromCampusMeters = 150;

/// Bỏ đoạn nối toạ độ → nút khi hai điểm gần như trùng (m) — tránh đoạn thẳng 0 m trên bản đồ.
const double _attachEpsilonMeters = 0.5;

/// Tốc độ đi bộ / thời gian đổi tầng dùng để ước tính thời gian (cấu hình hệ thống WALKING_SPEED_MPS,
/// FLOOR_CHANGE_SECONDS — V123). Mặc định = giá trị seed, dùng khi chưa đọc được cấu hình.
class RouteEtaSettings {
  static const double defaultWalkingSpeedMps = 1.45;
  static const int defaultFloorChangeSeconds = 14;

  final double walkingSpeedMps;
  final int floorChangeSeconds;

  const RouteEtaSettings({
    this.walkingSpeedMps = defaultWalkingSpeedMps,
    this.floorChangeSeconds = defaultFloorChangeSeconds,
  });

  static const RouteEtaSettings defaults = RouteEtaSettings();

  /// Từ phản hồi route-settings; giá trị thiếu / không hợp lệ → mặc định của từng trường.
  factory RouteEtaSettings.fromJson(Map<String, dynamic> json) {
    final speed = (json['walkingSpeedMps'] as num?)?.toDouble();
    final seconds = (json['floorChangeSeconds'] as num?)?.toInt();
    return RouteEtaSettings(
      walkingSpeedMps: speed != null && speed.isFinite && speed > 0 ? speed : defaultWalkingSpeedMps,
      floorChangeSeconds: seconds != null && seconds >= 0 ? seconds : defaultFloorChangeSeconds,
    );
  }

  /// Thời gian (giây) = quãng đường / tốc độ + số tầng đổi × thời gian mỗi tầng.
  double secondsFor(double meters, int floorsClimbed) => meters / walkingSpeedMps + floorsClimbed * floorChangeSeconds;

  /// Làm tròn LÊN phút.
  int minutesFor(double meters, int floorsClimbed) {
    final s = secondsFor(meters, floorsClimbed);
    return s <= 0 ? 0 : (s / 60).ceil();
  }
}

/// Nhãn tầng giống chip tầng trên màn bản đồ (tầng 0 = G).
String campusFloorLabel(int floorOrder) => floorOrder == 0 ? 'Tầng G' : 'Tầng $floorOrder';

/// Một điểm của tuyến, kèm toà / tầng để vẽ theo tầng đang xem.
class CampusRoutePoint {
  final LatLng latLng;
  final String building;
  final int floorOrder;

  const CampusRoutePoint(this.latLng, {required this.building, required this.floorOrder});
}

/// Chỗ đổi tầng dọc tuyến (gộp các bậc liên tiếp của cùng một cầu thang / thang máy).
class CampusFloorChange {
  final LatLng latLng;
  final String building;
  final int fromFloor;
  final int toFloor;

  /// STAIRS hoặc ELEVATOR.
  final String via;

  const CampusFloorChange({
    required this.latLng,
    required this.building,
    required this.fromFloor,
    required this.toFloor,
    required this.via,
  });

  bool get isUp => toFloor > fromFloor;

  /// "Lên tầng 2" / "Xuống tầng G".
  String get label => '${isUp ? 'Lên' : 'Xuống'} tầng ${toFloor == 0 ? 'G' : toFloor}';
}

/// Điểm xuất phát được chọn thế nào.
enum CampusRouteStartMode {
  /// GPS ngoài trời trong khuôn viên → nút ngoài trời gần nhất.
  outdoorGps,

  /// Trong nhà / không có GPS / GPS xa khuôn viên → cầu thang hoặc thang máy của toà + tầng đang chọn.
  selectedFloor,
}

/// Tuyến dẫn đường trên đồ thị khuôn viên (hoặc hướng thẳng khi đồ thị không có đường).
class CampusRoute {
  static const String directFallbackMessage = 'Chưa có đường đi trên bản đồ, hiển thị hướng thẳng';

  final List<CampusRoutePoint> points;

  /// Id các nút đồ thị tuyến đi qua (rỗng khi hướng thẳng).
  final List<String> nodeIds;
  final double distanceMeters;
  final int floorsClimbed;
  final List<CampusFloorChange> floorChanges;
  final bool isDirectFallback;
  final CampusRouteStartMode startMode;
  final RouteEtaSettings eta;

  /// Toà / tầng xuất phát (chế độ [CampusRouteStartMode.selectedFloor]).
  final String? startBuilding;
  final int startFloorOrder;

  const CampusRoute({
    required this.points,
    this.nodeIds = const [],
    required this.distanceMeters,
    required this.floorsClimbed,
    required this.floorChanges,
    required this.isDirectFallback,
    required this.startMode,
    required this.eta,
    this.startBuilding,
    this.startFloorOrder = 0,
  });

  List<LatLng> get latLngs => [for (final p in points) p.latLng];

  /// Thời gian dự kiến, làm tròn lên phút.
  int get etaMinutes => eta.minutesFor(distanceMeters, floorsClimbed);

  String get formattedDistance {
    if (distanceMeters >= 1000) return '${(distanceMeters / 1000).toStringAsFixed(1)} km';
    return '${distanceMeters.round()} m';
  }

  String get formattedDuration => '${etaMinutes < 1 ? 1 : etaMinutes} phút đi bộ';

  String? get message => isDirectFallback ? directFallbackMessage : null;
}

/// Không dựng được tuyến (thiếu toạ độ, chưa biết điểm xuất phát).
class CampusRouteUnavailable implements Exception {
  /// INVALID_COORDS (thiếu toạ độ điểm đến) hoặc NO_START (chưa xác định được điểm xuất phát).
  final String reasonCode;
  final String message;

  const CampusRouteUnavailable(this.reasonCode, this.message);

  @override
  String toString() => 'CampusRouteUnavailable($reasonCode): $message';
}

bool _isOutdoorBuilding(String? b) => b == null || b.trim().isEmpty || b == 'OUTDOOR';

double _dist(double lat, double lon, CampusGraphNode n) => haversineMeters(lat, lon, n.latitude, n.longitude);

CampusGraphNode? _nearest(Iterable<CampusGraphNode> pool, double lat, double lon) {
  CampusGraphNode? best;
  var bestD = double.infinity;
  for (final n in pool) {
    final d = _dist(lat, lon, n);
    if (d < bestD) {
      bestD = d;
      best = n;
    }
  }
  return best;
}

/// Nút đích cho khu có tâm (lat, lon) ở toà [building] (tên chuẩn) / tầng [floorOrder]:
/// nút gần tâm nhất cùng toà + cùng tầng; không có thì nút gần nhất cùng toà; khu ngoài trời (hoặc toà không có
/// trong đồ thị) → nút ngoài trời gần nhất.
CampusGraphNode? resolveDestinationNode(
  CampusGraph graph, {
  required double latitude,
  required double longitude,
  String? building,
  int? floorOrder,
}) {
  if (!_isOutdoorBuilding(building)) {
    final inBuilding = graph.nodes.where((n) => !n.isOutdoor && n.building == building).toList();
    if (inBuilding.isNotEmpty) {
      final f = floorOrder ?? 0;
      return _nearest(inBuilding.where((n) => n.floorOrder == f), latitude, longitude) ??
          _nearest(inBuilding, latitude, longitude);
    }
  }
  return _nearest(graph.nodes.where((n) => n.isOutdoor), latitude, longitude) ?? _nearest(graph.nodes, latitude, longitude);
}

/// Dựng tuyến từ vị trí bảo vệ tới khu.
///
/// Điểm xuất phát:
/// - (a) [gps] có, nằm ngoài mọi toà và cách nút ngoài trời gần nhất không quá [kGpsFarFromCampusMeters]
///   → nút ngoài trời gần nhất; tuyến bắt đầu bằng đoạn thẳng GPS → nút.
/// - (b) còn lại (GPS trong nhà, không có GPS, GPS xa khuôn viên) → toà [selectedBuilding] (hoặc toà chứa GPS) +
///   tầng [selectedFloorOrder]: chọn cầu thang / thang máy của tầng đó có đường tới đích ngắn nhất.
///
/// Đích: [resolveDestinationNode]; tuyến kết thúc bằng đoạn thẳng nút → tâm khu.
/// Đồ thị không có đường (hoặc chưa có đồ thị) → đoạn thẳng xuất phát → đích, [CampusRoute.isDirectFallback].
///
/// Ném [CampusRouteUnavailable] khi thiếu toạ độ đích hoặc không xác định được điểm xuất phát.
CampusRoute planCampusRoute({
  required CampusGraph? graph,
  CampusMapData? campus,
  LatLng? gps,
  String? selectedBuilding,
  int selectedFloorOrder = 0,
  required double? destinationLatitude,
  required double? destinationLongitude,
  String? destinationBuilding,
  int? destinationFloorOrder,
  RouteEtaSettings eta = RouteEtaSettings.defaults,
}) {
  final dLat = destinationLatitude;
  final dLon = destinationLongitude;
  if (dLat == null || dLon == null || !dLat.isFinite || !dLon.isFinite || (dLat == 0 && dLon == 0)) {
    throw const CampusRouteUnavailable('INVALID_COORDS', 'Thiếu tọa độ điểm đến.');
  }
  String? canonical(String? b) => _isOutdoorBuilding(b) ? null : (campus?.buildingFor(b)?.name ?? b!.trim());
  final destBuilding = canonical(destinationBuilding);
  final destFloor = destBuilding == null ? 0 : (destinationFloorOrder ?? 0);
  final destPoint = CampusRoutePoint(LatLng(dLat, dLon), building: destBuilding ?? 'OUTDOOR', floorOrder: destFloor);

  // ---- điểm xuất phát
  final gpsBuilding = gps == null ? null : campus?.buildingAt(latitude: gps.latitude, longitude: gps.longitude);
  CampusGraphNode? outdoorStart;
  if (graph != null && gps != null && gpsBuilding == null) {
    final n = _nearest(graph.nodes.where((x) => x.isOutdoor), gps.latitude, gps.longitude);
    if (n != null && _dist(gps.latitude, gps.longitude, n) <= kGpsFarFromCampusMeters) outdoorStart = n;
  }

  final CampusRouteStartMode mode;
  final CampusRoutePoint startPoint;
  String? startBuilding;
  var startFloor = 0;
  List<CampusGraphNode> startCandidates = const [];
  if (outdoorStart != null) {
    mode = CampusRouteStartMode.outdoorGps;
    startPoint = CampusRoutePoint(gps!, building: 'OUTDOOR', floorOrder: 0);
    startCandidates = [outdoorStart];
  } else {
    mode = CampusRouteStartMode.selectedFloor;
    startBuilding = gpsBuilding ?? canonical(selectedBuilding);
    if (startBuilding == null) {
      throw const CampusRouteUnavailable(
          'NO_START', 'Chưa xác định được vị trí của bạn. Chọn toà và tầng bạn đang đứng rồi dẫn đường lại.');
    }
    startFloor = selectedFloorOrder;
    if (graph != null) {
      final inBuilding = graph.nodes.where((n) => !n.isOutdoor && n.building == startBuilding).toList();
      var onFloor = inBuilding.where((n) => n.floorOrder == startFloor).toList();
      if (onFloor.isEmpty && inBuilding.isNotEmpty) {
        startFloor = 0;
        onFloor = inBuilding.where((n) => n.floorOrder == 0).toList();
      }
      final vertical = onFloor.where((n) => n.isVerticalAccess).toList();
      startCandidates = vertical.isNotEmpty ? vertical : onFloor.where((n) => !n.isVirtual).toList();
    }
    final anchor = startCandidates.isNotEmpty ? startCandidates.first : null;
    startPoint = anchor != null
        ? CampusRoutePoint(LatLng(anchor.latitude, anchor.longitude), building: startBuilding, floorOrder: startFloor)
        : (gps != null
            ? CampusRoutePoint(gps, building: startBuilding, floorOrder: startFloor)
            : throw const CampusRouteUnavailable(
                'NO_START', 'Chưa xác định được vị trí của bạn. Chọn toà và tầng bạn đang đứng rồi dẫn đường lại.'));
  }

  CampusRoute direct(CampusRoutePoint from) {
    final meters = haversineMeters(from.latLng.latitude, from.latLng.longitude, dLat, dLon);
    return CampusRoute(
      points: [from, destPoint],
      distanceMeters: meters,
      floorsClimbed: (from.floorOrder - destPoint.floorOrder).abs(),
      floorChanges: const [],
      isDirectFallback: true,
      startMode: mode,
      eta: eta,
      startBuilding: startBuilding,
      startFloorOrder: startFloor,
    );
  }

  if (graph == null || startCandidates.isEmpty) return direct(startPoint);
  final endNode = resolveDestinationNode(graph,
      latitude: dLat, longitude: dLon, building: destBuilding, floorOrder: destinationFloorOrder);
  if (endNode == null) return direct(startPoint);

  CampusGraphPath? best;
  for (final c in startCandidates) {
    final p = graph.shortestPath(c.id, endNode.id);
    if (p != null && (best == null || p.cost < best.cost)) best = p;
  }
  if (best == null) return direct(startPoint);

  final points = <CampusRoutePoint>[];
  var meters = best.lengthMeters;
  final first = best.nodes.first;
  if (mode == CampusRouteStartMode.outdoorGps) {
    final leg = _dist(gps!.latitude, gps.longitude, first);
    if (leg > _attachEpsilonMeters) {
      points.add(startPoint);
      meters += leg;
    }
  }
  for (final n in best.nodes) {
    points.add(CampusRoutePoint(LatLng(n.latitude, n.longitude), building: n.building, floorOrder: n.floorOrder));
  }
  final endLeg = _dist(dLat, dLon, best.nodes.last);
  if (endLeg > _attachEpsilonMeters) {
    points.add(CampusRoutePoint(destPoint.latLng, building: best.nodes.last.building, floorOrder: best.nodes.last.floorOrder));
    meters += endLeg;
  }

  return CampusRoute(
    points: points,
    nodeIds: best.nodeIds,
    distanceMeters: meters,
    floorsClimbed: best.floorsClimbed,
    floorChanges: floorChangesOf(best.nodes),
    isDirectFallback: false,
    startMode: mode,
    eta: eta,
    startBuilding: mode == CampusRouteStartMode.selectedFloor ? startBuilding : null,
    startFloorOrder: mode == CampusRouteStartMode.selectedFloor ? best.nodes.first.floorOrder : 0,
  );
}

/// Các chỗ đổi tầng dọc [path]; chuỗi cạnh đổi tầng liên tiếp (cầu thang qua nhiều tầng) gộp thành một.
List<CampusFloorChange> floorChangesOf(List<CampusGraphNode> path) {
  final out = <CampusFloorChange>[];
  var i = 0;
  while (i < path.length - 1) {
    if (path[i].floorOrder == path[i + 1].floorOrder) {
      i++;
      continue;
    }
    final start = path[i];
    var j = i + 1;
    final up = path[j].floorOrder > start.floorOrder;
    while (j < path.length - 1 &&
        path[j + 1].floorOrder != path[j].floorOrder &&
        (path[j + 1].floorOrder > path[j].floorOrder) == up) {
      j++;
    }
    out.add(CampusFloorChange(
      latLng: LatLng(start.latitude, start.longitude),
      building: start.building,
      fromFloor: start.floorOrder,
      toFloor: path[j].floorOrder,
      via: start.isVerticalAccess ? start.type : path[j].type,
    ));
    i = j;
  }
  return out;
}

/// GeoJSON tuyến chia theo tầng: đoạn ở [currentFloor] có `current = true` (vẽ đậm), đoạn ở tầng khác hoặc đoạn
/// đổi tầng `current = false` (vẽ mờ). Tuyến hướng thẳng có `direct = true` (vẽ nét đứt).
Map<String, dynamic> routeGeoJson(CampusRoute? route, {required int currentFloor}) {
  final features = <Map<String, dynamic>>[];
  if (route != null && route.points.length >= 2) {
    var run = <CampusRoutePoint>[route.points.first];
    bool? runCurrent;
    void flush() {
      if (run.length >= 2) {
        features.add({
          'type': 'Feature',
          'properties': {'current': runCurrent ?? false, 'direct': route.isDirectFallback},
          'geometry': {
            'type': 'LineString',
            'coordinates': [
              for (final p in run) [p.latLng.longitude, p.latLng.latitude],
            ],
          },
        });
      }
    }

    for (var i = 1; i < route.points.length; i++) {
      final a = route.points[i - 1];
      final b = route.points[i];
      final current = route.isDirectFallback || (a.floorOrder == b.floorOrder && a.floorOrder == currentFloor);
      if (runCurrent == null || runCurrent == current) {
        runCurrent = current;
        run.add(b);
      } else {
        flush();
        run = [a, b];
        runCurrent = current;
      }
    }
    flush();
  }
  return {'type': 'FeatureCollection', 'features': features};
}

/// GeoJSON các chỗ đổi tầng (điểm), `current = true` khi chỗ đổi tầng bắt đầu hoặc kết thúc ở [currentFloor].
Map<String, dynamic> floorChangesGeoJson(CampusRoute? route, {required int currentFloor}) => {
      'type': 'FeatureCollection',
      'features': [
        for (final c in route?.floorChanges ?? const <CampusFloorChange>[])
          {
            'type': 'Feature',
            'properties': {
              'label': c.label,
              'current': c.fromFloor == currentFloor || c.toFloor == currentFloor,
            },
            'geometry': {
              'type': 'Point',
              'coordinates': [c.latLng.longitude, c.latLng.latitude],
            },
          },
      ],
    };
