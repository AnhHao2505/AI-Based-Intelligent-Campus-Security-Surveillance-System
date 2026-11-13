import 'dart:math' show Point;

import 'package:flutter/material.dart';
import 'package:latlong2/latlong.dart';
import 'package:maplibre_gl/maplibre_gl.dart' as ml;
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../data/campus_map_data.dart';
import '../data/campus_map_style.dart';
import '../models/area_pin_model.dart';
import '../providers/map_provider.dart';
import '../widgets/area_detail_card.dart';
import '../widgets/osm_attribution_bar.dart';
import '../../chat/widgets/floating_guard_chat_button.dart';

/// Các loại khối 3D trong asset (thuộc tính `layer`), vẽ theo thứ tự này. Độ mờ theo web (CampusMapView.jsx:1386-1424).
/// Lớp `door` (mặt bằng cửa, không có màu / chiều cao) web không vẽ; khung cửa 3D đã nằm trong lớp `wall`
/// (wallKind DOOR_THRESHOLD / DOOR_JAMB / DOOR_LINTEL, campusIndoor3dData.js:3036-3100).
const List<({String layer, double opacity})> _campusLayers = [
  (layer: 'slab', opacity: 0.96),
  (layer: 'room', opacity: 0.9),
  (layer: 'wall', opacity: 1.0),
];

const String _pinsSource = 'campus-pins';
const String _pinsLayer = 'campus-pins-layer';
const String _guardSource = 'campus-guard';
const String _routeSource = 'campus-route';

/// Lề khi đưa khung bao (khuôn viên / tuyến đường) vào màn hình: chừa thanh tiêu đề + chip ở trên, thẻ khu ở dưới.
const EdgeInsets _fitPadding = EdgeInsets.fromLTRB(40, 170, 40, 220);

class GuardMapScreen extends StatefulWidget {
  final AreaPinModel? initialDestination;

  const GuardMapScreen({super.key, this.initialDestination});

  @override
  State<GuardMapScreen> createState() => _GuardMapScreenState();
}

class _GuardMapScreenState extends State<GuardMapScreen> {
  late final MapProvider _provider;
  ml.MapLibreMapController? _map;
  bool _styleReady = false;
  ml.CameraPosition? _initialCamera;
  ({LatLng target, double zoom})? _pendingFocus;

  // Chữ ký dữ liệu đã đẩy lên bản đồ (tránh gửi lại GeoJSON lớn khi không đổi)
  String? _campusKey;
  String? _highlightKey;
  String? _pinsKey;
  bool _syncing = false;
  bool _syncAgain = false;

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
    _provider.removeListener(_onProviderChanged);
    super.dispose();
  }

  void _onProviderChanged() => _scheduleSync();

  // ------------------------------------------------------------------ camera

  void _focusOn(LatLng target, double zoom) {
    if (_map == null || !_styleReady) {
      _pendingFocus = (target: target, zoom: zoom);
      return;
    }
    _map!.animateCamera(
      ml.CameraUpdate.newCameraPosition(ml.CameraPosition(
        target: ml.LatLng(target.latitude, target.longitude),
        zoom: zoom,
        tilt: campusDefaultTilt,
        bearing: campusDefaultBearing,
      )),
      duration: const Duration(milliseconds: 700),
    );
  }

  void _fitBounds(double south, double west, double north, double east) {
    _map?.animateCamera(
      ml.CameraUpdate.newLatLngBounds(
        ml.LatLngBounds(southwest: ml.LatLng(south, west), northeast: ml.LatLng(north, east)),
        left: _fitPadding.left,
        top: _fitPadding.top,
        right: _fitPadding.right,
        bottom: _fitPadding.bottom,
      ),
      duration: const Duration(milliseconds: 700),
    );
  }

  void _flyToCampus() {
    final b = _provider.campus?.bounds;
    if (b == null) {
      _showSnack(_provider.campusError ?? 'Chưa tải được dữ liệu bản đồ khuôn viên');
      return;
    }
    _fitBounds(b.south, b.west, b.north, b.east);
    _showSnack('Đã di chuyển tới toàn cảnh khuôn viên');
  }

  Future<void> _centerOnGuard() async {
    if (_provider.guardLocation == null) {
      await _provider.initLocation();
    }
    final target = _provider.guardLocation ?? _provider.campusCenter;
    if (target != null) _focusOn(target, 17.5);
  }

  void _fitRoute(List<LatLng> points) {
    if (points.isEmpty) return;
    var s = points.first.latitude, n = s, w = points.first.longitude, e = w;
    for (final p in points) {
      if (p.latitude < s) s = p.latitude;
      if (p.latitude > n) n = p.latitude;
      if (p.longitude < w) w = p.longitude;
      if (p.longitude > e) e = p.longitude;
    }
    _fitBounds(s, w, n, e);
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

  /// Camera ban đầu: tâm + zoom vừa khung bao khối 3D trong asset; chưa có asset thì theo vị trí GPS / khu đầu tiên.
  ml.CameraPosition? _computeInitialCamera(Size size) {
    final campus = _provider.campus;
    if (campus != null) {
      final b = campus.bounds;
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
    const empty = {'type': 'FeatureCollection', 'features': <dynamic>[]};
    try {
      for (final l in _campusLayers) {
        await map.addGeoJsonSource('campus-${l.layer}', empty);
        await map.addFillExtrusionLayer(
          'campus-${l.layer}',
          'campus-${l.layer}-layer',
          ml.FillExtrusionLayerProperties(
            fillExtrusionColor: const ['get', 'color'],
            fillExtrusionBase: const ['get', 'baseHeight'],
            fillExtrusionHeight: const ['get', 'extrudeHeight'],
            fillExtrusionOpacity: l.opacity,
            fillExtrusionVerticalGradient: true,
          ),
          enableInteraction: false,
        );
      }
      await map.addGeoJsonSource(_routeSource, empty);
      await map.addLineLayer(
        _routeSource,
        'campus-route-outline',
        const ml.LineLayerProperties(lineColor: '#ffffff', lineWidth: 6.5, lineCap: 'round', lineJoin: 'round'),
        enableInteraction: false,
      );
      await map.addLineLayer(
        _routeSource,
        'campus-route-line',
        ml.LineLayerProperties(
            lineColor: colorHex(AppColors.primaryLight), lineWidth: 4.0, lineCap: 'round', lineJoin: 'round'),
        enableInteraction: false,
      );
      await map.addGeoJsonSource(_pinsSource, empty);
      await map.addCircleLayer(
        _pinsSource,
        _pinsLayer,
        const ml.CircleLayerProperties(
          circleColor: ['get', 'color'],
          circleRadius: ['case', ['get', 'selected'], 13, 9],
          circleStrokeColor: '#ffffff',
          circleStrokeWidth: ['case', ['get', 'selected'], 3, 2],
          circlePitchAlignment: 'viewport',
        ),
      );
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
    } catch (e) {
      debugPrint('[GuardMapScreen] add layers: $e');
    }
    _styleReady = true;
    await _syncLayers();
    final pending = _pendingFocus;
    _pendingFocus = null;
    if (pending != null) _focusOn(pending.target, pending.zoom);
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
      final campus = p.campus;

      final campusKey = '${campus?.seedVersion}|${p.selectedBuilding}|${p.selectedFloorOrder}';
      if (campusKey != _campusKey) {
        _campusKey = campusKey;
        for (final l in _campusLayers) {
          await map.setGeoJsonSource(
            'campus-${l.layer}',
            campus?.featureCollectionFor(
                  building: p.selectedBuilding,
                  floorOrder: p.selectedFloorOrder,
                  layers: {l.layer},
                ) ??
                const {'type': 'FeatureCollection', 'features': <dynamic>[]},
          );
        }
      }

      final highlight = p.highlightRoomId;
      if (highlight != _highlightKey) {
        _highlightKey = highlight;
        await map.setLayerProperties(
          'campus-room-layer',
          ml.FillExtrusionLayerProperties(
            fillExtrusionColor: highlight == null
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

      final pins = List<AreaPinModel>.of(p.filteredPins);
      final selected = p.selectedArea;
      if (selected != null && selected.centerLatitude != 0.0 && !pins.any((x) => x.id == selected.id)) {
        pins.add(selected);
      }
      final pinsKey = '${pins.map((x) => x.id).join(',')}|${selected?.id}';
      if (pinsKey != _pinsKey) {
        _pinsKey = pinsKey;
        await map.setGeoJsonSource(_pinsSource, pinsGeoJson(pins, selectedId: selected?.id));
      }

      await map.setGeoJsonSource(_guardSource, pointGeoJson(p.guardLocation));
      await map.setGeoJsonSource(_routeSource, lineGeoJson(p.currentRoute?.points ?? const []));
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
    if (layerId != _pinsLayer) return;
    final pin = _provider.filteredPins.cast<AreaPinModel?>().firstWhere(
          (x) => x!.id == id,
          orElse: () => _provider.selectedArea?.id == id ? _provider.selectedArea : null,
        );
    if (pin == null) return;
    _provider.selectArea(pin);
    _focusOn(pin.latLng, 17.8);
  }

  // ------------------------------------------------------------------ UI

  String _floorLabel(int floorOrder) => floorOrder == 0 ? 'Tầng G' : 'Tầng $floorOrder';

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
          pinCount: provider.filteredPins.length,
        ));
    final mapProvider = _provider;
    final isDark = Theme.of(context).brightness == Brightness.dark;
    // Mọi thứ nổi ở đáy màn đặt trên dải ghi công OSM để không che chữ
    final bottomInset = osmAttributionBarHeight + MediaQuery.viewPaddingOf(context).bottom;

    return Scaffold(
      body: Stack(
        children: [
          // 1. Bản đồ MapLibre: nền OSM + khối 3D theo toà / tầng + ghim khu + GPS + tuyến đường.
          // Con KHÔNG định vị và chiếm hết chỗ để Stack (body của Scaffold, ràng buộc lỏng) có kích thước cả màn —
          // Positioned.fill sẽ làm Stack co theo cột tiêu đề.
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
                  compassEnabled: true,
                  // Nút (i) của MapLibre: góc dưới phải, trên dải ghi công, lệch trái khỏi cột nút nổi (rộng 40 + lề 16)
                  attributionEnabled: true,
                  attributionButtonPosition: ml.AttributionButtonPosition.bottomRight,
                  attributionButtonMargins: Point(72, bottomInset + 6),
                  onMapCreated: (controller) {
                    _map = controller;
                    _styleReady = false;
                    controller.onFeatureTapped.add(_onFeatureTapped);
                  },
                  onStyleLoadedCallback: _onStyleLoaded,
                );
              },
            ),
          ),

          // 2. Thanh tiêu đề + chip toà + chip tầng
          SafeArea(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  margin: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    color: AppColors.crd(context).withAlpha(235),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: AppColors.crdBorder(context)),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withAlpha(25),
                        blurRadius: 10,
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
                        padding: EdgeInsets.zero,
                        constraints: const BoxConstraints(),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Bản Đồ Tuần Tra',
                              style: TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.bold,
                                color: AppColors.txtPrimary(context),
                              ),
                            ),
                            Text(
                              '${mapState.pinCount} khu vực bảo vệ • ${_floorLabel(mapState.selectedFloor)}',
                              style: TextStyle(
                                fontSize: 11,
                                color: AppColors.txtSecondary(context),
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (mapState.isLoadingPins || mapState.isLoadingCampus)
                        const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.primaryLight),
                        )
                      else
                        IconButton(
                          icon: const Icon(Icons.refresh_rounded, size: 22),
                          color: AppColors.txtPrimary(context),
                          onPressed: () => mapProvider.fetchAreaPins(),
                          tooltip: 'Làm mới',
                          padding: EdgeInsets.zero,
                          constraints: const BoxConstraints(),
                        ),
                    ],
                  ),
                ),

                if (mapState.campusError != null)
                  Container(
                    margin: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                    padding: const EdgeInsets.fromLTRB(12, 6, 4, 6),
                    decoration: BoxDecoration(
                      color: Colors.orange.withAlpha(40),
                      borderRadius: BorderRadius.circular(6),
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
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      itemCount: mapState.buildings.length + 1,
                      itemBuilder: (context, index) {
                        final isAll = index == 0;
                        final bldName = isAll ? null : mapState.buildings[index - 1];
                        final isSelected = mapState.selectedBuilding == bldName;

                        String chipLabel;
                        if (isAll) {
                          chipLabel = 'Tất cả';
                        } else if (bldName != null &&
                            (bldName.trim().toLowerCase().startsWith('tòa ') ||
                                bldName.trim().toLowerCase().startsWith('toa '))) {
                          chipLabel = bldName.trim();
                        } else {
                          chipLabel = 'Tòa ${bldName ?? ""}';
                        }

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

                if (mapState.hasCampus && mapState.floors.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: SizedBox(
                      height: 34,
                      child: ListView(
                        scrollDirection: Axis.horizontal,
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        children: [
                          for (final f in mapState.floors)
                            Padding(
                              padding: const EdgeInsets.only(right: 6),
                              child: _chip(
                                context,
                                label: _floorLabel(f),
                                selected: mapState.selectedFloor == f,
                                onSelected: () => mapProvider.selectFloor(f),
                                dense: true,
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),

          // 3. Nút nổi bên phải
          Positioned(
            right: 16,
            bottom: bottomInset + ((mapState.selectedArea != null) ? 320 : 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                FloatingActionButton.small(
                  heroTag: 'fab_my_loc',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: AppColors.primaryLight,
                  elevation: 4,
                  onPressed: _centerOnGuard,
                  tooltip: 'Vị trí của tôi',
                  child: const Icon(Icons.my_location_rounded, size: 20),
                ),
                const SizedBox(height: 10),
                FloatingActionButton.small(
                  heroTag: 'fab_campus_center',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: Colors.orange.shade600,
                  elevation: 4,
                  onPressed: _flyToCampus,
                  tooltip: 'Toàn cảnh khuôn viên',
                  child: const Icon(Icons.school_rounded, size: 20),
                ),
                const SizedBox(height: 10),
                FloatingActionButton.small(
                  heroTag: 'fab_zoom_in',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: AppColors.txtPrimary(context),
                  elevation: 3,
                  onPressed: () => _map?.animateCamera(ml.CameraUpdate.zoomIn()),
                  tooltip: 'Phóng to',
                  child: const Icon(Icons.add_rounded, size: 20),
                ),
                const SizedBox(height: 8),
                FloatingActionButton.small(
                  heroTag: 'fab_zoom_out',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: AppColors.txtPrimary(context),
                  elevation: 3,
                  onPressed: () => _map?.animateCamera(ml.CameraUpdate.zoomOut()),
                  tooltip: 'Thu nhỏ',
                  child: const Icon(Icons.remove_rounded, size: 20),
                ),
              ],
            ),
          ),

          // 3b. Nút chat bảo vệ
          Positioned(
            left: 16,
            bottom: bottomInset + (mapState.selectedArea != null ? 230 : 28),
            child: const FloatingGuardChatButton(heroTag: 'fab_chat_map'),
          ),

          // 3c. Dòng ghi công OpenStreetMap — luôn hiện, không bị nút / thẻ che
          const Positioned(left: 0, right: 0, bottom: 0, child: OsmAttributionBar()),

          // 4. Thẻ chi tiết khu khi đang chọn khu
          if (mapState.selectedArea != null)
            Positioned(
              left: 0,
              right: 0,
              bottom: bottomInset,
              child: Selector<MapProvider, LatLng?>(
                selector: (_, provider) => provider.guardLocation,
                builder: (context, guardLocation, _) => AreaDetailCard(
                  area: mapState.selectedArea!,
                  guardLocation: guardLocation,
                  currentRoute: mapState.currentRoute,
                  isLoadingRoute: mapState.isLoadingRoute,
                  onNavigateTap: () async {
                    await mapProvider.calculateRouteTo(mapState.selectedArea!);
                    if (mapProvider.currentRoute != null) {
                      _fitRoute(mapProvider.currentRoute!.points);
                    }
                  },
                  onCancelRoute: () {
                    mapProvider.clearRoute();
                  },
                  onClose: () {
                    mapProvider.selectArea(null);
                    mapProvider.clearRoute();
                  },
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _chip(
    BuildContext context, {
    required String label,
    required bool selected,
    required VoidCallback onSelected,
    bool dense = false,
  }) {
    return FilterChip(
      label: Text(
        label,
        style: TextStyle(
          fontSize: dense ? 11 : 12,
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
      visualDensity: dense ? VisualDensity.compact : null,
    );
  }
}
