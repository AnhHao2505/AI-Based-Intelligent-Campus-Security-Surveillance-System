import 'dart:async';
import 'dart:math' show Point, max;

import 'package:flutter/foundation.dart' show kDebugMode, listEquals;
import 'package:flutter/material.dart';
import 'package:latlong2/latlong.dart';
import 'package:maplibre_gl/maplibre_gl.dart' as ml;
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../data/campus_floor_resolver.dart';
import '../data/campus_graph.dart' show haversineMeters;
import '../data/campus_map_data.dart';
import '../data/campus_map_overlays.dart';
import '../data/campus_map_sprites.dart';
import '../data/campus_map_style.dart';
import '../data/campus_route_planner.dart';
import '../models/area_pin_model.dart';
import '../providers/map_provider.dart';
import '../widgets/area_detail_card.dart';
import '../widgets/campus_map_legend.dart';
import '../widgets/osm_attribution_bar.dart';
import '../../chat/widgets/floating_guard_chat_button.dart';
import '../../incident/widgets/peer_claim_notice.dart';

/// Các lớp mặt bằng 2D trong asset (thuộc tính `layer`), vẽ theo thứ tự này trên mặt phẳng z = 0.
/// Lớp `door` (mặt bằng cửa) không vẽ riêng vì khung cửa đã nằm trong lớp `wall`.
const List<({String layer, double opacity})> _campusLayers = [
  (layer: 'slab', opacity: 0.96),
  (layer: 'room', opacity: 0.9),
  (layer: 'wall', opacity: 1.0),
];

const String _pinsSource = 'campus-pins';
const String _pinsLayer = campusPinsLayerId;
const String _roomLabelsLayer = campusRoomLabelsLayerId;
const String _incidentPinSource = 'campus-incident-pin';
const String _incidentPinLayer = campusIncidentPinLayerId;
const String _guardSource = 'campus-guard';
const String _routeSource = 'campus-route';
const String _portalsSource = 'campus-portals';
const String _roomLabelsSource = 'campus-room-labels';

/// Ảnh symbol đã vẽ (PNG), dùng lại khi style nạp lại / mở lại màn; chỉ vẽ một lần cho mỗi id.
final Map<String, CampusSprite> _spriteCache = {};

/// Tuyến trên đồ thị: CHỈ vẽ đoạn thuộc đúng tầng đang xem (theo chuẩn Google/Apple Maps Indoor, không lộ đường mờ tầng khác).
const List<Object> _routeGraphFilter = [
  'all',
  ['!=', ['get', 'direct'], true],
  ['==', ['get', 'current'], true],
];
const List<Object> _routeDirectFilter = ['==', ['get', 'direct'], true];

/// Lề khi đưa khung bao (khuôn viên / tuyến đường) vào màn hình: chừa thanh tiêu đề + chip toà ở trên, thanh tầng + nút phải và dock ở dưới.
const EdgeInsets _fitPadding = EdgeInsets.fromLTRB(32, 132, 72, 110);
const EdgeInsets _routeFitPadding = EdgeInsets.fromLTRB(36, 136, 76, 210);

/// Màu lộ trình xanh lam sáng (tương phản rõ với đường nền OSM tối và khối phòng màu cam).
const String _routeActiveColor = '#0EA5E9';

/// Nút chat bảo vệ góc dưới trái: lề đáy (tính từ mép trên dải ghi công).
const double _chatButtonBottom = 20;

class GuardMapScreen extends StatefulWidget {
  final AreaPinModel? initialDestination;

  const GuardMapScreen({super.key, this.initialDestination});

  @override
  State<GuardMapScreen> createState() => _GuardMapScreenState();
}

class _GuardMapScreenState extends State<GuardMapScreen> with PeerClaimNoticeMixin {
  late final MapProvider _provider;
  ml.MapLibreMapController? _map;
  bool _styleReady = false;
  ml.CameraPosition? _initialCamera;
  ({LatLng target, double zoom})? _pendingFocus;

  // Chữ ký dữ liệu đã đẩy lên bản đồ (tránh gửi lại GeoJSON lớn khi không đổi)
  String? _campusKey;
  String? _highlightKey;
  String? _pinsKey;
  String? _portalsKey;
  String? _labelsKey;
  bool _syncing = false;

  /// Ảnh đã addImage vào style hiện tại (maplibre_gl không có hasImage); style nạp lại thì xoá.
  final Set<String> _styleImages = {};
  bool _syncAgain = false;

  /// Chế độ camera tự động bám theo vị trí GPS real-time khi bảo vệ di chuyển.
  bool _isFollowingGps = false;
  LatLng? _lastFollowedGps;

  /// Hiện/ẩn bảng chú giải màu cấp khu khi bấm nút (i) trên thanh tiêu đề.
  bool _showLegend = false;

  /// Góc xoay 2D hiện tại của camera (độ, 0 = hướng Bắc).
  double _cameraBearing = 0;

  /// Bộ đếm mô phỏng đi bộ (chỉ dùng ở chế độ Debug trên Emulator).
  Timer? _simTimer;
  bool _isWalkingSim = false;

  @override
  void initState() {
    super.initState();
    _provider = context.read<MapProvider>();
    _provider.addListener(_onProviderChanged);

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await _provider.loadCampusMap();
      await _provider.initLocation();
      await _provider.fetchAreaPins();
      if (!mounted) return;

      if (_provider.selectedBuilding == null && _provider.buildings.length == 1) {
        _provider.selectBuilding(_provider.buildings.first);
      }

      final dest = widget.initialDestination;
      if (dest != null) {
        _provider.focusIncident(dest);
        final area = _provider.selectedArea;
        if (area != null && area.centerLatitude != 0.0) {
          _focusOn(area.latLng, 18.2);
        }
      } else if (_provider.guardLocation != null && _provider.campus == null) {
        _focusOn(_provider.guardLocation!, 17.0);
      }
    });
  }

  @override
  void dispose() {
    _simTimer?.cancel();
    _provider.removeListener(_onProviderChanged);
    super.dispose();
  }

  void _onProviderChanged() {
    _scheduleSync();
    final gps = _provider.guardLocation;
    if (_isFollowingGps && gps != null) {
      final moved = _lastFollowedGps == null ||
          haversineMeters(
                _lastFollowedGps!.latitude,
                _lastFollowedGps!.longitude,
                gps.latitude,
                gps.longitude,
              ) >=
              1.0;
      if (moved) {
        _lastFollowedGps = gps;
        _focusOn(gps, 18.3, bearing: _provider.guardHeading);
      }
    }
  }

  // ------------------------------------------------------------------ camera

  void _focusOn(LatLng target, double minZoom, {double? bearing, bool preserveCamera = true}) {
    if (_map == null || !_styleReady) {
      _pendingFocus = (target: target, zoom: minZoom);
      return;
    }
    final cur = _map!.cameraPosition;
    final targetZoom = (preserveCamera && cur != null) ? max(cur.zoom, minZoom) : minZoom;
    final targetBearing = (bearing != null && bearing.isFinite && bearing >= 0)
        ? bearing
        : (preserveCamera && cur != null ? cur.bearing : campusDefaultBearing);
    _map!.animateCamera(
      ml.CameraUpdate.newCameraPosition(ml.CameraPosition(
        target: ml.LatLng(target.latitude, target.longitude),
        zoom: targetZoom,
        tilt: campusDefaultTilt,
        bearing: targetBearing,
      )),
      duration: const Duration(milliseconds: 550),
    );
  }

  void _fitBounds(double south, double west, double north, double east, {EdgeInsets padding = _fitPadding}) {
    _map?.animateCamera(
      ml.CameraUpdate.newLatLngBounds(
        ml.LatLngBounds(southwest: ml.LatLng(south, west), northeast: ml.LatLng(north, east)),
        left: padding.left,
        top: padding.top,
        right: padding.right,
        bottom: padding.bottom,
      ),
      duration: const Duration(milliseconds: 700),
    );
  }

  void _syncCameraBearing() {
    final b = _map?.cameraPosition?.bearing;
    if (b != null && b.isFinite && (b - _cameraBearing).abs() > 0.5 && mounted) {
      setState(() => _cameraBearing = b);
    }
  }

  /// Xoay góc nhìn 2D theo chiều kim đồng hồ 45° (giữ nguyên mặt phẳng tilt = 0°).
  void _rotate2dStep() {
    final map = _map;
    if (map == null || !_styleReady) return;
    if (_isFollowingGps && mounted) setState(() => _isFollowingGps = false);
    final cur = map.cameraPosition;
    final currentBearing = cur?.bearing ?? _cameraBearing;
    final nextBearing = ((currentBearing + 45) % 360);
    setState(() => _cameraBearing = nextBearing);
    map.animateCamera(
      ml.CameraUpdate.newCameraPosition(ml.CameraPosition(
        target: cur?.target ??
            ml.LatLng(
              _provider.visibleCampusCenter?.latitude ?? 10.8405,
              _provider.visibleCampusCenter?.longitude ?? 106.8112,
            ),
        zoom: cur?.zoom ?? 18.0,
        tilt: campusDefaultTilt,
        bearing: nextBearing,
      )),
      duration: const Duration(milliseconds: 280),
    );
  }

  /// Đưa góc nhìn 2D về hướng Bắc (bearing = 0°, tilt = 0°).
  void _resetNorth2d() {
    final map = _map;
    if (map == null || !_styleReady) return;
    final cur = map.cameraPosition;
    setState(() => _cameraBearing = 0);
    map.animateCamera(
      ml.CameraUpdate.newCameraPosition(ml.CameraPosition(
        target: cur?.target ??
            ml.LatLng(
              _provider.visibleCampusCenter?.latitude ?? 10.8405,
              _provider.visibleCampusCenter?.longitude ?? 106.8112,
            ),
        zoom: cur?.zoom ?? 18.0,
        tilt: campusDefaultTilt,
        bearing: 0,
      )),
      duration: const Duration(milliseconds: 300),
    );
  }

  /// Hộp thoại 1 chạm hỏi tầng bảo vệ đang đứng khi GPS ở trong tòa nhà hoặc chưa xác định vị trí.
  Future<({int floorOrder, bool forceOutdoor})?> _promptStartFloor(List<int> floors) {
    final sorted = (floors.isEmpty ? [0, 1, 2, 3, 4] : [...floors])..sort();
    return showModalBottomSheet<({int floorOrder, bool forceOutdoor})>(
      context: context,
      backgroundColor: AppColors.crd(context),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 18),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Bạn đang đứng ở tầng nào?',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                  color: AppColors.txtPrimary(ctx),
                ),
              ),
              const SizedBox(height: 4),
              Text(
                'Hệ thống dùng tọa độ GPS (x, y) của bạn tại tầng này để dẫn đường chính xác.',
                style: TextStyle(fontSize: 12.5, color: AppColors.txtSecondary(ctx)),
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  for (var i = 0; i < sorted.length; i++) ...[
                    if (i > 0) const SizedBox(width: 8),
                    Expanded(
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: AppColors.surf(ctx),
                          foregroundColor: AppColors.txtPrimary(ctx),
                          elevation: 0,
                          minimumSize: const Size(0, 48),
                          padding: EdgeInsets.zero,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(10),
                            side: BorderSide(color: AppColors.crdBorder(ctx)),
                          ),
                        ),
                        onPressed: () => Navigator.pop(ctx, (floorOrder: sorted[i], forceOutdoor: false)),
                        child: Text(
                          _shortFloorLabel(sorted[i]),
                          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.primaryLight,
                    side: BorderSide(color: AppColors.primaryLight.withAlpha(110)),
                    minimumSize: const Size(0, 42),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  ),
                  onPressed: () => Navigator.pop(ctx, (floorOrder: 0, forceOutdoor: true)),
                  icon: const Icon(Icons.park_outlined, size: 18),
                  label: const Text('Đang ở ngoài sân (Tầng G)', style: TextStyle(fontWeight: FontWeight.w600)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _startNavigationTo(AreaPinModel area) async {
    int? startFloorOrder;
    var forceOutdoor = false;

    if (_provider.needsStartFloorPrompt) {
      final picked = await _promptStartFloor(_provider.availableFloorOrders);
      if (picked == null || !mounted) return;
      startFloorOrder = picked.floorOrder;
      forceOutdoor = picked.forceOutdoor;
    }

    await _provider.calculateRouteTo(
      area,
      startFloorOrder: startFloorOrder,
      forceOutdoorStart: forceOutdoor,
    );
    if (!mounted) return;
    final route = _provider.currentRoute;
    if (route != null) {
      final startFloor = route.startMode == CampusRouteStartMode.outdoorGps ? 0 : route.startFloorOrder;
      if (_provider.selectedFloorOrder != startFloor) {
        _provider.selectFloor(startFloor);
      }
      _fitRoute(route.latLngs);
      if (route.message != null) _showSnack(route.message!);
    } else if (_provider.errorMessage != null) {
      _showSnack(_provider.errorMessage!);
    }
  }

  void _stopWalkSimulation({bool resumeGps = false}) {
    _simTimer?.cancel();
    _simTimer = null;
    if (_isWalkingSim && mounted) {
      setState(() => _isWalkingSim = false);
    } else {
      _isWalkingSim = false;
    }
    if (resumeGps) {
      _provider.setSimulatedGpsOverride(false);
    }
  }

  void _startWalkSimulation() {
    final route = _provider.currentRoute;
    if (route == null || route.points.length < 2) {
      _showSnack('Hãy chọn phòng và bấm Dẫn đường trước khi mô phỏng đi bộ');
      return;
    }
    _simTimer?.cancel();

    const dist = Distance();
    const stepMeters = 2.5;
    final steps = <({LatLng point, int floorOrder, double heading})>[];
    for (var i = 1; i < route.points.length; i++) {
      final a = route.points[i - 1];
      final b = route.points[i];
      final segMeters = dist.as(LengthUnit.Meter, a.latLng, b.latLng);
      final bearing = segMeters > 0.2 ? dist.bearing(a.latLng, b.latLng) : 0.0;
      final n = (segMeters / stepMeters).ceil().clamp(1, 40);
      for (var k = 0; k < n; k++) {
        final t = k / n;
        final lat = a.latLng.latitude + (b.latLng.latitude - a.latLng.latitude) * t;
        final lon = a.latLng.longitude + (b.latLng.longitude - a.latLng.longitude) * t;
        steps.add((point: LatLng(lat, lon), floorOrder: a.floorOrder, heading: bearing));
      }
    }
    final last = route.points.last;
    steps.add((point: last.latLng, floorOrder: last.floorOrder, heading: steps.isNotEmpty ? steps.last.heading : 0.0));

    var idx = 0;
    var lastFloor = steps.first.floorOrder;
    if (_provider.selectedFloorOrder != lastFloor) {
      _provider.selectFloor(lastFloor);
    }
    setState(() => _isWalkingSim = true);
    _provider.setSimulatedGpsOverride(true, location: steps.first.point, heading: steps.first.heading);

    _simTimer = Timer.periodic(const Duration(milliseconds: 700), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      idx++;
      if (idx >= steps.length) {
        _stopWalkSimulation();
        _showSnack('Đã tới điểm đến (mô phỏng)');
        return;
      }
      final s = steps[idx];
      if (s.floorOrder != lastFloor) {
        lastFloor = s.floorOrder;
        _provider.selectFloor(s.floorOrder);
      }
      _provider.updateGuardLocation(s.point, heading: s.heading);
    });
  }

  void _teleportIndoorForTest() {
    _stopWalkSimulation();
    final pt = _provider.findIndoorTestPoint();
    if (pt == null) {
      _showSnack('Chưa tải xong bản đồ tòa nhà');
      return;
    }
    _provider.setSimulatedGpsOverride(true, location: pt);
    _focusOn(pt, 18.2);
    _showSnack('Đã đặt GPS vào trong Tòa Epsilon. Bấm Dẫn đường để chọn tầng!');
  }

  Future<void> _toggleFollowGps() async {
    if (_isFollowingGps) {
      if (mounted) setState(() => _isFollowingGps = false);
      return;
    }
    if (_provider.guardLocation == null) {
      await _provider.initLocation();
    }
    final gps = _provider.guardLocation;
    if (gps != null) {
      if (mounted) {
        setState(() {
          _isFollowingGps = true;
          _lastFollowedGps = gps;
        });
      }
      _focusOn(gps, 18.3, bearing: _provider.guardHeading);
      return;
    }
    final target = _provider.visibleCampusCenter ?? _provider.campusCenter;
    if (target != null) _focusOn(target, 18.0);
  }

  void _fitRoute(List<LatLng> points) {
    if (points.isEmpty) return;
    if (_isFollowingGps && mounted) {
      setState(() => _isFollowingGps = false);
    }
    var s = points.first.latitude, n = s, w = points.first.longitude, e = w;
    for (final p in points) {
      if (p.latitude < s) s = p.latitude;
      if (p.latitude > n) n = p.latitude;
      if (p.longitude < w) w = p.longitude;
      if (p.longitude > e) e = p.longitude;
    }
    _fitBounds(s, w, n, e, padding: _routeFitPadding);
  }

  void _focusRouteDestination(AreaPinModel area) {
    if (_isFollowingGps) {
      setState(() => _isFollowingGps = false);
    }
    final destFloor = floorOrderOfPin(area, _provider.visibleCampus ?? _provider.campus) ?? 0;
    if (_provider.selectedFloorOrder != destFloor) {
      _provider.selectFloor(destFloor);
    }
    if (area.centerLatitude != 0.0) {
      _focusOn(area.latLng, 18.6);
    }
  }

  void _focusFloorChange(CampusFloorChange change) {
    if (_isFollowingGps) {
      setState(() => _isFollowingGps = false);
    }
    if (_provider.selectedFloorOrder != change.toFloor) {
      _provider.selectFloor(change.toFloor);
    }
    _focusOn(change.latLng, 18.6);
  }

  void _showSnack(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Row(
          children: [
            const Icon(Icons.school_rounded, color: Colors.white, size: 20),
            const SizedBox(width: 8),
            Expanded(child: Text(text)),
          ],
        ),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 80),
      ),
    );
  }

  /// Camera ban đầu: tâm + zoom vừa khung bao khối 3D của các tòa hiển thị (Tòa Epsilon); chưa có asset thì theo vị trí GPS / khu đầu tiên.
  ml.CameraPosition? _computeInitialCamera(Size size) {
    final b = _provider.visibleCampusBounds ?? _provider.campus?.bounds;
    if (b != null) {
      return ml.CameraPosition(
        target: ml.LatLng(b.centerLatitude, b.centerLongitude),
        zoom: zoomToFitBounds(b, size.width, size.height, paddingPx: _fitPadding.left),
        tilt: campusDefaultTilt,
        bearing: campusDefaultBearing,
      );
    }
    final fallback = _provider.guardLocation ??
        (_provider.areaPins.isNotEmpty ? _provider.areaPins.first.latLng : null);
    if (fallback == null) return null;
    // Chỉ dùng khi không đọc được asset: zoom mặc định cũ của màn bản đồ (flutter_map initialZoom 16.8)
    return ml.CameraPosition(target: ml.LatLng(fallback.latitude, fallback.longitude), zoom: 16.8);
  }

  // ------------------------------------------------------------------ layers

  Future<void> _onStyleLoaded() async {
    final map = _map;
    if (map == null) return;
    _campusKey = null;
    _highlightKey = null;
    _pinsKey = null;
    _portalsKey = null;
    _labelsKey = null;
    _styleImages.clear();
    const empty = {'type': 'FeatureCollection', 'features': <dynamic>[]};
    try {
      // Ảnh phải có trong style trước khi lớp symbol dùng tới (style mới không giữ ảnh cũ)
      for (final id in const [portalStairsImageId, portalElevatorImageId]) {
        await _ensureImage(map, id);
      }
      for (final l in _campusLayers) {
        await map.addGeoJsonSource('campus-${l.layer}', empty);
        await map.addFillLayer(
          'campus-${l.layer}',
          'campus-${l.layer}-layer',
          ml.FillLayerProperties(
            fillColor: const ['get', 'color'],
            fillOpacity: l.opacity,
          ),
          enableInteraction: false,
        );
      }
      // Viền ranh giới phòng 2D sắc nét để các phòng liền kề tách bạch rõ ràng
      await map.addLineLayer(
        'campus-room',
        'campus-room-border-layer',
        const ml.LineLayerProperties(
          lineColor: '#334155',
          lineWidth: 1.6,
          lineOpacity: 0.9,
        ),
        enableInteraction: false,
      );
      // Cầu thang / thang máy: trên khối 3D, dưới tên phòng / tuyến đường / ghim (CampusMapView.jsx:1447-1485).
      // Không đặt symbol-height-offset: MapLibre Android chưa hỗ trợ (maplibre_gl 0.28, layer_properties.dart:925-937).
      await map.addGeoJsonSource(_portalsSource, empty);
      await map.addSymbolLayer(
        _portalsSource,
        'campus-portals-layer',
        const ml.SymbolLayerProperties(
          iconImage: ['get', 'iconImage'],
          iconSize: ['interpolate', ['linear'], ['zoom'], 15.5, 0.52, 19.5, 0.88],
          iconAllowOverlap: true,
          iconIgnorePlacement: false,
          iconOpacity: 0.96,
        ),
        minzoom: 15.5,
        enableInteraction: false,
      );
      await map.addGeoJsonSource(_routeSource, empty);
      await map.addLineLayer(
        _routeSource,
        'campus-route-outline',
        const ml.LineLayerProperties(
          lineColor: '#ffffff',
          lineWidth: 8.5,
          lineOpacity: 1.0,
          lineCap: 'round',
          lineJoin: 'round',
        ),
        filter: _routeGraphFilter,
        enableInteraction: false,
      );
      await map.addLineLayer(
        _routeSource,
        'campus-route-line',
        const ml.LineLayerProperties(
          lineColor: _routeActiveColor,
          lineWidth: 5.5,
          lineOpacity: 1.0,
          lineCap: 'round',
          lineJoin: 'round',
        ),
        filter: _routeGraphFilter,
        enableInteraction: false,
      );
      // Không có đường trên đồ thị: đoạn thẳng nét đứt xuất phát → đích
      await map.addLineLayer(
        _routeSource,
        'campus-route-direct',
        const ml.LineLayerProperties(
          lineColor: _routeActiveColor,
          lineWidth: 4.5,
          lineDasharray: [1.5, 1.5],
        ),
        filter: _routeDirectFilter,
        enableInteraction: false,
      );
      await map.addGeoJsonSource(_pinsSource, empty);
      await map.addCircleLayer(
        _pinsSource,
        _pinsLayer,
        _areaPinPaint,
      );
      // Quầng đỏ đứng yên dưới ghim sự cố (cùng nguồn với lớp ghim sự cố), không nhận chạm. Nằm DƯỚI tên phòng
      // (campusLayerOrder) để vòng quầng không cắt chữ của nhãn khu sự cố; ghim sự cố vẫn ở trên tên phòng.
      await map.addGeoJsonSource(_incidentPinSource, empty);
      await map.addCircleLayer(
        _incidentPinSource,
        campusIncidentHaloLayerId,
        const ml.CircleLayerProperties(
          circleColor: incidentHaloColor,
          circleRadius: incidentHaloRadius,
          circleOpacity: incidentHaloOpacity,
          circleStrokeColor: incidentHaloColor,
          circleStrokeWidth: incidentHaloStrokeWidth,
          circleStrokeOpacity: incidentHaloStrokeOpacity,
          circlePitchAlignment: 'viewport',
        ),
        enableInteraction: false,
      );
      // Tên phòng: ảnh nhãn theo khu, khu đang chọn đặt trước (CampusMapView.jsx:1487-1528).
      // Nằm trên lớp ghim khu và quầng sự cố (campusLayerOrder): ghim không che được chữ; neo cạnh dưới + dịch lên
      // roomLabelLiftAbovePin để nhãn không che ghim của chính khu đó.
      await map.addGeoJsonSource(_roomLabelsSource, empty);
      await map.addSymbolLayer(
        _roomLabelsSource,
        _roomLabelsLayer,
        ml.SymbolLayerProperties(
          iconImage: const ['get', 'imageId'],
          iconSize: roomLabelIconSizeExpression(),
          iconAnchor: roomLabelIconAnchor,
          iconOffset: roomLabelIconOffsetExpression(),
          iconAllowOverlap: false,
          iconIgnorePlacement: false,
          iconPadding: 3,
          symbolSortKey: ['get', 'sortKey'],
          iconOpacity: 0.98,
        ),
        minzoom: roomLabelMinZoom,
        // Bấm nhãn mở thẻ khu như bấm ghim (_onFeatureTapped); chạm trúng cả nhãn lẫn ghim → nhãn (lớp trên)
      );
      // Ghim sự cố đang mở (kể cả ghim tạm): trên tên phòng để nhãn không che, dưới vị trí bảo vệ. Cùng hình với ghim khu;
      // quầng đỏ của nó (lớp campusIncidentHaloLayerId, dưới tên phòng) phân biệt với ghim khu đang chọn.
      await map.addCircleLayer(_incidentPinSource, _incidentPinLayer, _areaPinPaint);
      await map.addGeoJsonSource(_guardSource, empty);
      await map.addCircleLayer(
        _guardSource,
        'campus-guard-halo',
        ml.CircleLayerProperties(
            circleColor: colorHex(AppColors.primaryLight), circleRadius: 16, circleOpacity: 0.25),
        enableInteraction: false,
      );
      await map.addCircleLayer(
        _guardSource,
        'campus-guard-dot',
        ml.CircleLayerProperties(
          circleColor: colorHex(AppColors.primary),
          circleRadius: 7,
          circleStrokeColor: '#ffffff',
          circleStrokeWidth: 2,
        ),
        enableInteraction: false,
      );
      // Đối chiếu thứ tự lớp thật với campusLayerOrder (chỉ ghi log, không chặn bản đồ)
      final actual = (await map.getLayerIds()).map((e) => e.toString()).where(campusLayerOrder.contains).toList();
      debugPrint(listEquals(actual, campusLayerOrder)
          ? '[MAP] thứ tự lớp đúng: ${actual.join(' → ')}'
          : '[MAP] thứ tự lớp LỆCH: ${actual.join(' → ')}');
    } catch (e) {
      debugPrint('[GuardMapScreen] add layers: $e');
    }
    _styleReady = true;
    await _syncLayers();
    final pending = _pendingFocus;
    _pendingFocus = null;
    if (pending != null) _focusOn(pending.target, pending.zoom);
  }

  /// Hình ghim khu (màu theo cấp khu; khu đang chọn to hơn), dùng chung cho lớp ghim khu và lớp ghim sự cố.
  static final ml.CircleLayerProperties _areaPinPaint = ml.CircleLayerProperties(
    circleColor: const ['get', 'color'],
    circleRadius: const ['case', ['get', 'selected'], areaPinMaxRadius, 9],
    circleStrokeColor: '#ffffff',
    circleStrokeWidth: const ['case', ['get', 'selected'], areaPinMaxStrokeWidth, 2],
    circlePitchAlignment: 'viewport',
  );

  /// Đăng ký ảnh [id] vào style hiện tại nếu chưa có. Ảnh vẽ một lần rồi giữ trong [_spriteCache].
  Future<void> _ensureImage(ml.MapLibreMapController map, String id) async {
    if (_styleImages.contains(id)) return;
    var sprite = _spriteCache[id];
    if (sprite == null) {
      sprite = id.startsWith(roomLabelImagePrefix) ? await renderRoomLabelForImageId(id) : await renderPortalIcon(id);
      if (sprite == null) return;
      _spriteCache[id] = sprite;
    }
    try {
      await map.addImage(id, sprite.png, false, campusSpritePixelRatio);
      _styleImages.add(id);
    } catch (e) {
      debugPrint('[GuardMapScreen] add image $id: $e');
    }
  }

  void _scheduleSync() {
    if (!_styleReady) return;
    if (_syncing) {
      _syncAgain = true;
      return;
    }
    _syncLayers();
  }

  /// Đẩy dữ liệu hiện tại của provider lên các nguồn GeoJSON (chỉ phần đã đổi, trừ vị trí GPS / tuyến luôn cập nhật).
  Future<void> _syncLayers() async {
    final map = _map;
    if (map == null || !_styleReady) return;
    _syncing = true;
    try {
      final p = _provider;
      final campus = p.visibleCampus ?? p.campus;
      final effectiveBldg = p.effectiveBuilding;

      final campusKey = '${campus?.seedVersion}|$effectiveBldg|${p.selectedFloorOrder}';
      if (campusKey != _campusKey) {
        _campusKey = campusKey;
        for (final l in _campusLayers) {
          await map.setGeoJsonSource(
            'campus-${l.layer}',
            campus?.featureCollectionFor(
                  building: effectiveBldg,
                  floorOrder: p.selectedFloorOrder,
                  layers: {l.layer},
                ) ??
                const {'type': 'FeatureCollection', 'features': <dynamic>[]},
          );
        }
      }

      // Cầu thang / thang máy: dựng lại khi đổi toà / tầng
      final portalsKey = campusKey;
      if (portalsKey != _portalsKey) {
        _portalsKey = portalsKey;
        final sw = Stopwatch()..start();
        final portals = portalSymbolsGeoJson(campus, building: effectiveBldg, floorOrder: p.selectedFloorOrder);
        await map.setGeoJsonSource(_portalsSource, portals);
        debugPrint('[MAP] portals ${effectiveBldg ?? 'ALL'}/${p.selectedFloorOrder}: '
            '${(portals['features'] as List).length} điểm, ${sw.elapsedMilliseconds} ms');
      }

      // Tên phòng: dựng lại khi đổi toà / tầng / khu đang chọn / danh sách khu (tải lại từ API)
      final labelsKey = '$campusKey|${p.selectedArea?.id}|${identityHashCode(p.areaPins)}';
      if (labelsKey != _labelsKey) {
        _labelsKey = labelsKey;
        final sw = Stopwatch()..start();
        final labels = roomLabelsGeoJson(
          campus,
          p.areaPins,
          building: effectiveBldg,
          floorOrder: p.selectedFloorOrder,
          selectedAreaId: p.selectedArea?.id,
        );
        final built = sw.elapsedMilliseconds;
        for (final id in roomLabelImageIds(labels)) {
          await _ensureImage(map, id);
        }
        await map.setGeoJsonSource(_roomLabelsSource, labels);
        debugPrint('[MAP] room labels ${effectiveBldg ?? 'ALL'}/${p.selectedFloorOrder}: '
            '${(labels['features'] as List).length} nhãn, dựng $built ms, tổng ${sw.elapsedMilliseconds} ms');
      }

      final highlight = p.highlightRoomId;
      if (highlight != _highlightKey) {
        _highlightKey = highlight;
        await map.setLayerProperties(
          'campus-room-layer',
          ml.FillLayerProperties(
            fillColor: highlight == null
                ? const ['get', 'color']
                : [
                    'case',
                    ['==', ['get', 'id'], highlight],
                    selectedRoomColor,
                    ['get', 'color'],
                  ],
          ),
        );
      }

      // Ghim sự cố đang mở tách sang lớp riêng; lớp ghim khu không vẽ lại nó
      final selected = p.selectedArea;
      final split = splitIncidentPin(p.filteredPins, selected: selected, incidentAreaId: p.incidentAreaId);
      final incident = split.incident;
      final pinsKey = '${split.areas.map((x) => x.id).join(',')}|${selected?.id}|${incident?.id}';
      if (pinsKey != _pinsKey) {
        _pinsKey = pinsKey;
        await map.setGeoJsonSource(_pinsSource, pinsGeoJson(split.areas, selectedId: selected?.id));
        await map.setGeoJsonSource(
            _incidentPinSource, pinsGeoJson([?incident], selectedId: incident?.id));
      }

      await map.setGeoJsonSource(_guardSource, pointGeoJson(p.guardLocation));
      await map.setGeoJsonSource(_routeSource, routeGeoJson(p.currentRoute, currentFloor: p.selectedFloorOrder));
    } catch (e) {
      debugPrint('[GuardMapScreen] sync layers: $e');
    } finally {
      _syncing = false;
      if (_syncAgain) {
        _syncAgain = false;
        _syncLayers();
      }
    }
  }

  void _onFeatureTapped(dynamic point, ml.LatLng coordinates, String id, String layerId, dynamic annotation) {
    // Ghim khu, nhãn tên phòng và ghim sự cố đều mang id khu ở feature id
    if (layerId != _pinsLayer && layerId != _roomLabelsLayer && layerId != _incidentPinLayer) return;
    _selectTappedArea(point, id, layerId);
  }

  /// MapLibre Android đã ưu tiên lớp nằm trên khi một lần chạm trúng nhiều lớp
  /// (MapLibreMapController.firstFeatureOnLayers): ghim sự cố > nhãn > ghim khu. Chạm rơi vào ghim khu thì vẫn hỏi lại
  /// lớp nhãn tại đúng điểm chạm: có nhãn ở đó → mở khu của NHÃN; chạm vào ghim sự cố luôn là sự cố (tappedAreaId).
  Future<void> _selectTappedArea(dynamic point, String id, String layerId) async {
    var areaId = id;
    final map = _map;
    if (layerId == _pinsLayer && map != null && point is Point<double>) {
      try {
        final labels = await map.queryRenderedFeatures(point, const [_roomLabelsLayer], null);
        areaId = tappedAreaId(
            layerId: layerId, featureId: id, labelIdsAtPoint: [for (final f in labels) (f as Map)['id']]);
      } catch (e) {
        debugPrint('[GuardMapScreen] query labels at tap: $e');
      }
    }
    if (!mounted) return;
    final pin = findAreaPinById(_provider.areaPins, areaId, selected: _provider.selectedArea);
    if (pin == null) return;
    if (_isFollowingGps) setState(() => _isFollowingGps = false);
    _provider.selectArea(pin);
    _focusOn(pin.latLng, 17.8);
  }

  // ------------------------------------------------------------------ UI

  /// Nhãn ngắn trên thanh chọn tầng dọc: 0 → G, còn lại → số tầng.
  static String _shortFloorLabel(int floorOrder) => floorOrder == 0 ? 'G' : '$floorOrder';

  @override
  Widget build(BuildContext context) {
    final mapState = context.select((MapProvider provider) => (
          buildings: provider.buildings,
          selectedBuilding: provider.selectedBuilding,
          selectedFloor: provider.selectedFloorOrder,
          floors: provider.availableFloorOrders,
          selectedArea: provider.selectedArea,
          currentRoute: provider.currentRoute,
          isLoadingPins: provider.isLoadingPins,
          isLoadingRoute: provider.isLoadingRoute,
          isLoadingCampus: provider.isLoadingCampus,
          campusError: provider.campusError,
          hasCampus: provider.campus != null,
          visibleCampus: provider.visibleCampus ?? provider.campus,
          isSimulatingGps: provider.isSimulatingGps,
        ));
    final mapProvider = _provider;
    final isDark = Theme.of(context).brightness == Brightness.dark;
    // Mọi thứ nổi ở đáy màn đặt trên dải ghi công OSM để không che chữ
    final bottomInset = osmAttributionBarHeight + MediaQuery.viewPaddingOf(context).bottom;
    final destFloorOrder = mapState.selectedArea == null
        ? null
        : floorOrderOfPin(mapState.selectedArea!, mapState.visibleCampus);
    final floorsDesc = [...mapState.floors]..sort((a, b) => b.compareTo(a));

    return Scaffold(
      body: Stack(
        children: [
          // 1. Bản đồ MapLibre 2D phẳng (tilt = 0°, bearing = 0°) — khớp tuyệt đối từng phòng và icon ở mọi tầng
          SizedBox.expand(
            child: LayoutBuilder(
              builder: (context, constraints) {
                final ready = mapState.hasCampus || mapState.campusError != null;
                if (!ready) {
                  return const Center(child: CircularProgressIndicator(color: AppColors.primaryLight));
                }
                _initialCamera ??= _computeInitialCamera(constraints.biggest);
                final initial = _initialCamera;
                if (initial == null) {
                  return Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        mapState.campusError ?? 'Chưa có dữ liệu để hiển thị bản đồ',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: AppColors.txtSecondary(context)),
                      ),
                    ),
                  );
                }
                return ml.MapLibreMap(
                  key: ValueKey(isDark),
                  styleString: campusMapStyleJson(dark: isDark),
                  initialCameraPosition: initial,
                  trackCameraPosition: true,
                  rotateGesturesEnabled: true,
                  tiltGesturesEnabled: false,
                  compassEnabled: true,
                  compassViewPosition: ml.CompassViewPosition.topRight,
                  compassViewMargins: const Point(14, 146),
                  // Đã có OsmAttributionBar riêng ở mép đáy; tắt nút (i) mặc định của MapLibre Android để không mở
                  // dialog trắng trống khi chạm nhầm.
                  attributionEnabled: false,
                  onMapCreated: (controller) {
                    _map = controller;
                    _styleReady = false;
                    controller.onFeatureTapped.add(_onFeatureTapped);
                  },
                  onCameraIdle: _syncCameraBearing,
                  onStyleLoadedCallback: _onStyleLoaded,
                );
              },
            ),
          ),

          // 2. Thanh tiêu đề gọn + nút chú giải (i) + làm mới (chỉ hiện chọn tòa khi có nhiều hơn 1 tòa)
          SafeArea(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  margin: const EdgeInsets.fromLTRB(14, 8, 14, 6),
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                  decoration: BoxDecoration(
                    color: AppColors.crd(context).withAlpha(238),
                    borderRadius: BorderRadius.circular(12),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withAlpha(35),
                        blurRadius: 12,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: Row(
                    children: [
                      IconButton(
                        icon: const Icon(Icons.arrow_back_rounded, size: 22),
                        color: AppColors.txtPrimary(context),
                        onPressed: () => Navigator.pop(context),
                        tooltip: 'Quay lại',
                        padding: const EdgeInsets.all(6),
                        constraints: const BoxConstraints(),
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          'Bản Đồ Tuần Tra',
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w700,
                            color: AppColors.txtPrimary(context),
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      IconButton(
                        key: const ValueKey('map-legend-toggle'),
                        icon: Icon(
                          _showLegend ? Icons.info_rounded : Icons.info_outline_rounded,
                          size: 20,
                        ),
                        color: _showLegend ? AppColors.primary : AppColors.txtSecondary(context),
                        onPressed: () => setState(() => _showLegend = !_showLegend),
                        tooltip: 'Chú giải màu khu vực',
                        padding: const EdgeInsets.all(6),
                        constraints: const BoxConstraints(),
                      ),
                      if (kDebugMode) ...[
                        const SizedBox(width: 2),
                        PopupMenuButton<String>(
                          key: const ValueKey('map-debug-sim-menu'),
                          tooltip: 'Mô phỏng GPS (Debug)',
                          padding: const EdgeInsets.all(6),
                          constraints: const BoxConstraints(),
                          icon: Icon(
                            _isWalkingSim ? Icons.directions_walk_rounded : Icons.directions_run_rounded,
                            size: 20,
                            color: (_isWalkingSim || mapState.isSimulatingGps)
                                ? AppColors.primary
                                : AppColors.txtSecondary(context),
                          ),
                          onSelected: (value) {
                            if (value == 'walk') {
                              if (_isWalkingSim) {
                                _stopWalkSimulation();
                              } else {
                                _startWalkSimulation();
                              }
                            } else if (value == 'indoor') {
                              _teleportIndoorForTest();
                            } else if (value == 'reset') {
                              _stopWalkSimulation(resumeGps: true);
                              _showSnack('Đã khôi phục GPS thiết bị');
                            }
                          },
                          itemBuilder: (ctx) => [
                            PopupMenuItem(
                              value: 'walk',
                              child: Text(_isWalkingSim ? 'Dừng mô phỏng đi bộ' : 'Mô phỏng đi bộ theo tuyến'),
                            ),
                            const PopupMenuItem(
                              value: 'indoor',
                              child: Text('Đặt GPS vào trong Tòa Epsilon'),
                            ),
                            if (mapState.isSimulatingGps)
                              const PopupMenuItem(
                                value: 'reset',
                                child: Text('Khôi phục GPS thiết bị'),
                              ),
                          ],
                        ),
                      ],
                      const SizedBox(width: 2),
                      if (mapState.isLoadingPins || mapState.isLoadingCampus)
                        const Padding(
                          padding: EdgeInsets.all(6),
                          child: SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.primaryLight),
                          ),
                        )
                      else
                        IconButton(
                          icon: const Icon(Icons.refresh_rounded, size: 21),
                          color: AppColors.txtPrimary(context),
                          onPressed: () => mapProvider.fetchAreaPins(),
                          tooltip: 'Làm mới',
                          padding: const EdgeInsets.all(6),
                          constraints: const BoxConstraints(),
                        ),
                    ],
                  ),
                ),

                if (_showLegend)
                  const Padding(
                    padding: EdgeInsets.fromLTRB(14, 0, 72, 6),
                    child: CampusMapLegend(),
                  ),

                if (mapState.campusError != null)
                  Container(
                    margin: const EdgeInsets.fromLTRB(14, 0, 14, 6),
                    padding: const EdgeInsets.fromLTRB(12, 6, 4, 6),
                    decoration: BoxDecoration(
                      color: Colors.orange.withAlpha(40),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: Colors.orange.shade300),
                    ),
                    child: Row(
                      children: [
                        Icon(Icons.warning_amber_rounded, size: 18, color: Colors.orange.shade700),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            '${mapState.campusError}. Bản đồ chỉ hiện nền và ghim khu.',
                            style: TextStyle(fontSize: 12, color: AppColors.txtPrimary(context)),
                          ),
                        ),
                        TextButton(
                          onPressed: () => mapProvider.loadCampusMap(force: true),
                          child: const Text('Thử lại'),
                        ),
                      ],
                    ),
                  ),

                if (mapState.buildings.isNotEmpty)
                  SizedBox(
                    height: 38,
                    child: ListView.builder(
                      scrollDirection: Axis.horizontal,
                      padding: const EdgeInsets.symmetric(horizontal: 14),
                      itemCount: mapState.buildings.length > 1
                          ? mapState.buildings.length + 1
                          : mapState.buildings.length,
                      itemBuilder: (context, index) {
                        final hasAllChip = mapState.buildings.length > 1;
                        final isAll = hasAllChip && index == 0;
                        final bldName = isAll
                            ? null
                            : mapState.buildings[hasAllChip ? index - 1 : index];
                        final isSelected = hasAllChip
                            ? mapState.selectedBuilding == bldName
                            : (mapState.selectedBuilding == bldName || mapState.selectedBuilding == null);
                        final chipLabel = isAll
                            ? 'Tất cả'
                            : (bldName != null &&
                                    (bldName.trim().toLowerCase().startsWith('tòa ') ||
                                        bldName.trim().toLowerCase().startsWith('toa ')))
                                ? bldName.trim()
                                : 'Tòa ${bldName ?? ""}';

                        return Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: _chip(
                            context,
                            label: chipLabel,
                            selected: isSelected,
                            onSelected: () => mapProvider.selectBuilding(bldName),
                          ),
                        );
                      },
                    ),
                  ),
              ],
            ),
          ),

          // 3. Cột điều khiển bên phải: Thanh chọn tầng dọc (4..G) + Toàn tuyến (khi dẫn đường) + Bám GPS + Phóng to/Thu nhỏ
          Positioned(
            right: 14,
            bottom: bottomInset + (mapState.selectedArea != null ? 182 : 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (mapState.hasCampus && floorsDesc.isNotEmpty)
                  Container(
                    key: const ValueKey('vertical-floor-rail'),
                    width: 40,
                    padding: const EdgeInsets.symmetric(vertical: 3),
                    decoration: BoxDecoration(
                      color: AppColors.crd(context).withAlpha(238),
                      borderRadius: BorderRadius.circular(12),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withAlpha(45),
                          blurRadius: 12,
                          offset: const Offset(0, 4),
                        ),
                      ],
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        for (final f in floorsDesc)
                          _floorRailItem(
                            context,
                            label: _shortFloorLabel(f),
                            selected: mapState.selectedFloor == f,
                            hasTargetBadge: destFloorOrder == f && mapState.selectedFloor != f,
                            onTap: () => mapProvider.selectFloor(f),
                          ),
                      ],
                    ),
                  ),
                if (mapState.currentRoute != null) ...[
                  const SizedBox(height: 8),
                  FloatingActionButton.small(
                    heroTag: 'fab_fit_route',
                    backgroundColor: AppColors.crd(context),
                    foregroundColor: const Color(0xFF0EA5E9),
                    elevation: 4,
                    onPressed: () => _fitRoute(mapState.currentRoute!.latLngs),
                    tooltip: 'Toàn tuyến',
                    child: const Icon(Icons.route_rounded, size: 20),
                  ),
                ],
                const SizedBox(height: 8),
                FloatingActionButton.small(
                  heroTag: 'fab_my_loc',
                  backgroundColor: _isFollowingGps ? AppColors.primary : AppColors.crd(context),
                  foregroundColor: _isFollowingGps ? Colors.white : AppColors.primaryLight,
                  elevation: 4,
                  onPressed: _toggleFollowGps,
                  tooltip: _isFollowingGps ? 'Đang bám GPS' : 'Bám GPS',
                  child: const Icon(Icons.my_location_rounded, size: 20),
                ),
                const SizedBox(height: 8),
                GestureDetector(
                  onLongPress: _resetNorth2d,
                  child: FloatingActionButton.small(
                    heroTag: 'fab_rotate_2d',
                    backgroundColor: _cameraBearing.abs() > 1
                        ? AppColors.primary.withAlpha(235)
                        : AppColors.crd(context),
                    foregroundColor: _cameraBearing.abs() > 1 ? Colors.white : AppColors.txtPrimary(context),
                    elevation: 3,
                    onPressed: _rotate2dStep,
                    tooltip: 'Xoay góc 2D (giữ để về Bắc)',
                    child: Transform.rotate(
                      angle: -_cameraBearing * pi / 180,
                      child: const Icon(Icons.explore_rounded, size: 20),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                FloatingActionButton.small(
                  heroTag: 'fab_zoom_in',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: AppColors.txtPrimary(context),
                  elevation: 3,
                  onPressed: () {
                    if (_isFollowingGps) setState(() => _isFollowingGps = false);
                    _map?.animateCamera(ml.CameraUpdate.zoomIn());
                  },
                  tooltip: 'Phóng to',
                  child: const Icon(Icons.add_rounded, size: 20),
                ),
                const SizedBox(height: 6),
                FloatingActionButton.small(
                  heroTag: 'fab_zoom_out',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: AppColors.txtPrimary(context),
                  elevation: 3,
                  onPressed: () {
                    if (_isFollowingGps) setState(() => _isFollowingGps = false);
                    _map?.animateCamera(ml.CameraUpdate.zoomOut());
                  },
                  tooltip: 'Thu nhỏ',
                  child: const Icon(Icons.remove_rounded, size: 20),
                ),
              ],
            ),
          ),

          // 3b. Nút chat bảo vệ — ẩn khi thẻ khu đang mở
          if (mapState.selectedArea == null)
            Positioned(
              left: 14,
              bottom: bottomInset + _chatButtonBottom,
              child: const FloatingGuardChatButton(heroTag: 'fab_chat_map'),
            ),

          // 3c. Dòng ghi công OpenStreetMap — luôn hiện ở mép đáy
          const Positioned(left: 0, right: 0, bottom: 0, child: OsmAttributionBar()),

          // 4. Compact Navigation / Area Dock khi đang chọn khu
          if (mapState.selectedArea != null)
            Positioned(
              left: 0,
              right: 0,
              bottom: bottomInset,
              child: AreaDetailCard(
                area: mapState.selectedArea!,
                currentRoute: mapState.currentRoute,
                isLoadingRoute: mapState.isLoadingRoute,
                onNavigateTap: () => _startNavigationTo(mapState.selectedArea!),
                onFocusDestination: mapState.currentRoute == null
                    ? null
                    : () => _focusRouteDestination(mapState.selectedArea!),
                onSelectFloorStep: mapState.currentRoute == null
                    ? null
                    : _focusFloorChange,
                onCancelRoute: () {
                  _stopWalkSimulation();
                  if (_isFollowingGps) setState(() => _isFollowingGps = false);
                  mapProvider.clearRoute();
                },
                onClose: () {
                  _stopWalkSimulation();
                  if (_isFollowingGps) setState(() => _isFollowingGps = false);
                  mapProvider.selectArea(null);
                  mapProvider.clearRoute();
                },
              ),
            ),
        ],
      ),
    );
  }

  Widget _floorRailItem(
    BuildContext context, {
    required String label,
    required bool selected,
    required bool hasTargetBadge,
    required VoidCallback onTap,
  }) {
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: onTap,
      child: Container(
        width: 32,
        height: 28,
        margin: const EdgeInsets.symmetric(vertical: 1.5),
        decoration: BoxDecoration(
          color: selected ? AppColors.primary : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Stack(
          alignment: Alignment.center,
          children: [
            Text(
              label,
              style: TextStyle(
                fontSize: 13,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w600,
                color: selected ? Colors.white : AppColors.txtSecondary(context),
              ),
            ),
            if (hasTargetBadge)
              Positioned(
                top: 4,
                right: 4,
                child: Container(
                  width: 6,
                  height: 6,
                  decoration: const BoxDecoration(
                    color: Color(0xFF0EA5E9),
                    shape: BoxShape.circle,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _chip(
    BuildContext context, {
    required String label,
    required bool selected,
    required VoidCallback onSelected,
  }) {
    return FilterChip(
      label: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          fontWeight: selected ? FontWeight.bold : FontWeight.normal,
          color: selected ? Colors.white : AppColors.txtSecondary(context),
        ),
      ),
      selected: selected,
      onSelected: (_) => onSelected(),
      backgroundColor: AppColors.crd(context).withAlpha(220),
      selectedColor: AppColors.primary,
      checkmarkColor: Colors.white,
      side: BorderSide(color: selected ? AppColors.primary : AppColors.crdBorder(context)),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 0),
    );
  }
}
