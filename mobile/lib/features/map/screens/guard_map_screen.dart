import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../../incident/providers/incident_provider.dart';
import '../../incident/widgets/incident_alert_banner.dart';
import '../../incident/widgets/incident_detail_sheet.dart';
import '../../incident/widgets/incident_queue_sheet.dart';
import '../models/area_pin_model.dart';
import '../providers/map_provider.dart';
import '../widgets/area_detail_card.dart';
import '../widgets/area_marker_layer.dart';

class GuardMapScreen extends StatefulWidget {
  final AreaPinModel? initialDestination;

  const GuardMapScreen({super.key, this.initialDestination});

  @override
  State<GuardMapScreen> createState() => _GuardMapScreenState();
}

class _GuardMapScreenState extends State<GuardMapScreen>
    with TickerProviderStateMixin {
  late final MapController _mapController;
  IncidentProvider? _incidentProvider;

  @override
  void initState() {
    super.initState();
    _mapController = MapController();

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final mapProvider = context.read<MapProvider>();
      final incidentProvider = context.read<IncidentProvider>();

      incidentProvider.initStomp();
      incidentProvider.loadActiveIncidents();

      await mapProvider.initLocation();
      await mapProvider.fetchAreaPins();

      if (widget.initialDestination != null) {
        mapProvider.selectArea(widget.initialDestination);
        _flyTo(widget.initialDestination!.latLng, 17.5);
      } else if (mapProvider.guardLocation != null) {
        _flyTo(mapProvider.guardLocation!, 17.0);
      }
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final provider = context.read<IncidentProvider>();
    if (_incidentProvider != provider) {
      _incidentProvider?.removeListener(_onIncidentProviderChanged);
      _incidentProvider = provider;
      _incidentProvider?.addListener(_onIncidentProviderChanged);
    }
  }

  void _onIncidentProviderChanged() {
    if (!mounted) return;
    final provider = _incidentProvider;
    if (provider == null) return;

    if (provider.errorMessage != null) {
      final msg = provider.errorMessage!;
      provider.clearErrorMessage();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(msg),
          backgroundColor: const Color(0xFFEF4444),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 4),
        ),
      );
    }

    if (provider.peerClaimNotice != null) {
      final notice = provider.peerClaimNotice!;
      provider.clearPeerClaimNotice();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Row(
            children: [
              const Icon(Icons.info_outline, color: Colors.white, size: 20),
              const SizedBox(width: 8),
              Expanded(child: Text(notice)),
            ],
          ),
          backgroundColor: const Color(0xFF3B82F6),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 3),
        ),
      );
    }
  }

  @override
  void dispose() {
    _incidentProvider?.removeListener(_onIncidentProviderChanged);
    super.dispose();
  }

  void _flyTo(LatLng target, double targetZoom) {
    final camera = _mapController.camera;
    final startLatLng = camera.center;
    final startZoom = camera.zoom;

    final latTween =
        Tween<double>(begin: startLatLng.latitude, end: target.latitude);
    final lngTween =
        Tween<double>(begin: startLatLng.longitude, end: target.longitude);
    final zoomTween = Tween<double>(begin: startZoom, end: targetZoom);

    final controller = AnimationController(
      duration: const Duration(milliseconds: 700),
      vsync: this,
    );

    final animation =
        CurvedAnimation(parent: controller, curve: Curves.easeInOutCubic);

    animation.addListener(() {
      _mapController.move(
        LatLng(latTween.evaluate(animation), lngTween.evaluate(animation)),
        zoomTween.evaluate(animation),
      );
    });

    animation.addStatusListener((status) {
      if (status == AnimationStatus.completed ||
          status == AnimationStatus.dismissed) {
        controller.dispose();
      }
    });

    controller.forward();
  }

  void _flyToCampus() {
    _flyTo(MapProvider.campusCenter, 17.5);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Row(
          children: [
            Icon(Icons.school_rounded, color: Colors.white, size: 20),
            SizedBox(width: 8),
            Text('Đã di chuyển tới trung tâm Campus ĐH FPT HCM'),
          ],
        ),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 80),
      ),
    );
  }

  Future<void> _centerOnGuard() async {
    final mapProvider = context.read<MapProvider>();
    if (mapProvider.guardLocation == null) {
      await mapProvider.initLocation();
    }
    final target = mapProvider.guardLocation ?? MapProvider.campusCenter;
    _flyTo(target, 17.5);
  }

  void _centerOnArea(AreaPinModel area) {
    _flyTo(area.latLng, 17.8);
  }

  void _fitRoute(List<LatLng> points) {
    if (points.isEmpty) return;
    final bounds = LatLngBounds.fromPoints(points);
    _mapController.fitCamera(
      CameraFit.bounds(
        bounds: bounds,
        padding: const EdgeInsets.fromLTRB(40, 80, 40, 220),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    // GPS fixes arrive continuously; selectively subscribe to map elements
    final mapState = context.select((MapProvider provider) => (
          pins: provider.areaPins,
          buildings: provider.buildings,
          selectedBuilding: provider.selectedBuilding,
          selectedArea: provider.selectedArea,
          currentRoute: provider.currentRoute,
          isLoadingPins: provider.isLoadingPins,
          isLoadingRoute: provider.isLoadingRoute,
        ));
    final guardLocation =
        context.select((MapProvider provider) => provider.guardLocation);
    final guardHeading =
        context.select((MapProvider provider) => provider.guardHeading);
    final mapProvider = context.read<MapProvider>();
    final filteredPins = mapState.selectedBuilding == null ||
            mapState.selectedBuilding!.isEmpty
        ? mapState.pins
        : mapState.pins
            .where((pin) =>
                pin.building != null &&
                pin.building!.toLowerCase() ==
                    mapState.selectedBuilding!.toLowerCase())
            .toList();
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final incProvider = context.watch<IncidentProvider>();
    final hasIncomingAlert = incProvider.currentAlert != null && !incProvider.isHandlingIncident;

    return Scaffold(
      body: Stack(
        children: [
          // 1. The Interactive OpenStreetMap
          FlutterMap(
            mapController: _mapController,
            options: MapOptions(
              initialCenter: mapProvider.guardLocation ?? MapProvider.campusCenter,
              initialZoom: 16.8,
              minZoom: 12.0,
              maxZoom: 18.8,
            ),
            children: [
              // Standard OSM Raster Tiles with responsive brightness
              TileLayer(
                urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                userAgentPackageName: 'com.fa26se040.icss.guard',
                maxZoom: 19,
                tileBuilder: isDark
                    ? (context, tileWidget, tile) {
                        return ColorFiltered(
                          colorFilter: const ColorFilter.matrix([
                            0.2, 0, 0, 0, 0,
                            0, 0.25, 0, 0, 0,
                            0, 0, 0.3, 0, 0,
                            0, 0, 0, 1, 0,
                          ]),
                          child: tileWidget,
                        );
                      }
                    : null,
              ),

              // Navigation Polyline Route Layer
              if (mapState.currentRoute != null &&
                  mapState.currentRoute!.points.isNotEmpty) ...[
                // Outer glow / outline
                PolylineLayer(
                  polylines: [
                    Polyline(
                      points: mapState.currentRoute!.points,
                      color: Colors.white,
                      strokeWidth: 6.5,
                      borderStrokeWidth: 1.0,
                      borderColor: Colors.black26,
                    ),
                  ],
                ),
                // Inner vibrant blue navigation line
                PolylineLayer(
                  polylines: [
                    Polyline(
                      points: mapState.currentRoute!.points,
                      color: AppColors.primaryLight,
                      strokeWidth: 4.0,
                    ),
                  ],
                ),
              ],

              // Live Guard GPS Location Marker Layer (Pulsing blue radar beacon with compass heading)
              if (guardLocation != null)
                MarkerLayer(
                  markers: [
                    Marker(
                      point: guardLocation,
                      width: 52,
                      height: 52,
                      alignment: Alignment.center,
                      child: IgnorePointer(
                        child: Stack(
                          alignment: Alignment.center,
                          children: [
                            // Outer translucent radar pulse ring
                            Container(
                              width: 48,
                              height: 48,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                color: AppColors.primaryLight.withAlpha(50),
                              ),
                            ),
                            // Middle glowing halo
                            Container(
                              width: 28,
                              height: 28,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                color: AppColors.primaryLight.withAlpha(95),
                                border: Border.all(
                                  color: Colors.white.withAlpha(230),
                                  width: 2.0,
                                ),
                              ),
                            ),
                            // Core vibrant blue pin with heading arrow or dot
                            Container(
                              width: 18,
                              height: 18,
                              decoration: const BoxDecoration(
                                shape: BoxShape.circle,
                                color: AppColors.primary,
                                boxShadow: [
                                  BoxShadow(
                                    color: Colors.black26,
                                    blurRadius: 4,
                                    offset: Offset(0, 2),
                                  ),
                                ],
                              ),
                              child: Center(
                                child: (guardHeading != null && guardHeading != 0.0)
                                    ? Transform.rotate(
                                        angle: (guardHeading * math.pi / 180),
                                        child: const Icon(
                                          Icons.navigation_rounded,
                                          size: 11,
                                          color: Colors.white,
                                        ),
                                      )
                                    : Container(
                                        width: 6,
                                        height: 6,
                                        decoration: const BoxDecoration(
                                          shape: BoxShape.circle,
                                          color: Colors.white,
                                        ),
                                      ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),

              // Area Location Pins Layer (On top, always receives click events)
              AreaMarkerLayer(
                pins: filteredPins,
                selectedPin: mapState.selectedArea,
                onPinTap: (pin) {
                  mapProvider.selectArea(pin);
                  _centerOnArea(pin);
                },
              ),
            ],
          ),

          // 2. Top Header & Building Filter Bar
          SafeArea(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                // Top App Bar
                Container(
                  margin: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    color: AppColors.crd(context).withAlpha(235),
                    borderRadius: BorderRadius.circular(16),
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
                              '${filteredPins.length} khu vực bảo vệ',
                              style: TextStyle(
                                fontSize: 11,
                                color: AppColors.txtSecondary(context),
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (mapState.isLoadingPins)
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

                // Horizontal Building Filter Chips
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

                        return Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: FilterChip(
                            label: Text(
                              isAll ? 'Tất cả' : 'Tòa $bldName',
                              style: TextStyle(
                                fontSize: 12,
                                fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                                color: isSelected
                                    ? Colors.white
                                    : AppColors.txtSecondary(context),
                              ),
                            ),
                            selected: isSelected,
                            onSelected: (_) => mapProvider.selectBuilding(bldName),
                            backgroundColor: AppColors.crd(context).withAlpha(220),
                            selectedColor: AppColors.primary,
                            checkmarkColor: Colors.white,
                            side: BorderSide(
                              color: isSelected ? AppColors.primary : AppColors.crdBorder(context),
                            ),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 0),
                          ),
                        );
                      },
                    ),
                  ),
              ],
            ),
          ),

          // 3. Floating Quick Action Controls (Right side)
          Positioned(
            right: 16,
            bottom: incProvider.isHandlingIncident
                ? 200
                : ((mapState.selectedArea != null && !hasIncomingAlert) ? 320 : 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Center on Guard Location button
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
                // Fly to Campus (Test button)
                FloatingActionButton.small(
                  heroTag: 'fab_campus_center',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: Colors.orange.shade600,
                  elevation: 4,
                  onPressed: _flyToCampus,
                  tooltip: 'Bay tới Campus ĐH FPT (Test)',
                  child: const Icon(Icons.school_rounded, size: 20),
                ),
                const SizedBox(height: 10),
                // Zoom in button
                FloatingActionButton.small(
                  heroTag: 'fab_zoom_in',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: AppColors.txtPrimary(context),
                  elevation: 3,
                  onPressed: () {
                    final currentZoom = _mapController.camera.zoom;
                    _mapController.move(_mapController.camera.center, currentZoom + 1.0);
                  },
                  tooltip: 'Phóng to',
                  child: const Icon(Icons.add_rounded, size: 20),
                ),
                const SizedBox(height: 8),
                // Zoom out button
                FloatingActionButton.small(
                  heroTag: 'fab_zoom_out',
                  backgroundColor: AppColors.crd(context),
                  foregroundColor: AppColors.txtPrimary(context),
                  elevation: 3,
                  onPressed: () {
                    final currentZoom = _mapController.camera.zoom;
                    _mapController.move(_mapController.camera.center, currentZoom - 1.0);
                  },
                  tooltip: 'Thu nhỏ',
                  child: const Icon(Icons.remove_rounded, size: 20),
                ),
              ],
            ),
          ),

          // 4. Area Detail Card at bottom when an area is selected (only when not handling active incident and no incoming emergency alert)
          if (!incProvider.isHandlingIncident && !hasIncomingAlert && mapState.selectedArea != null)
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
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

          // 5. Incident Real-time Alert Banner (Top of screen)
          IncidentAlertBanner(
            onClaim: (incident) async {
              final incidentProvider = context.read<IncidentProvider>();
              final success = await incidentProvider.claimIncident(incident.id);
              if (success) {
                final pin = await mapProvider.navigateToIncidentArea(
                  areaId: incident.areaId,
                  areaName: incident.areaName,
                  cameraCode: incident.cameraCode,
                );
                if (pin != null) {
                  _flyTo(pin.latLng, 17.5);
                }
                if (mapProvider.currentRoute != null) {
                  _fitRoute(mapProvider.currentRoute!.points);
                }
              }
            },
            onTapBadge: () {
              IncidentQueueSheet.show(
                context: context,
                onClaim: (incident) async {
                  final incidentProvider = context.read<IncidentProvider>();
                  final success = await incidentProvider.claimIncident(incident.id);
                  if (success) {
                    final pin = await mapProvider.navigateToIncidentArea(
                      areaId: incident.areaId,
                      areaName: incident.areaName,
                      cameraCode: incident.cameraCode,
                    );
                    if (pin != null) {
                      _flyTo(pin.latLng, 17.5);
                    }
                    if (mapProvider.currentRoute != null) {
                      _fitRoute(mapProvider.currentRoute!.points);
                    }
                  }
                },
                onPreviewOnMap: (incident) {
                  final pin = mapProvider.findPinForIncident(
                    areaId: incident.areaId,
                    areaName: incident.areaName,
                    cameraCode: incident.cameraCode,
                  );
                  if (pin != null) {
                    mapProvider.selectArea(pin);
                    _flyTo(pin.latLng, 18.0);
                  }
                },
              );
            },
          ),

          // 6. Active Incident Detail & Resolution Sheet (Bottom of screen)
          Consumer<IncidentProvider>(
            builder: (context, incProvider, child) {
              final activeIncident = incProvider.activeIncident;
              if (activeIncident == null) return const SizedBox.shrink();

              return IncidentDetailSheet(
                incident: activeIncident,
                onResolved: () {
                  mapProvider.clearRoute();
                  mapProvider.selectArea(null);
                },
                onCancelNavigation: () {
                  mapProvider.clearRoute();
                },
              );
            },
          ),
        ],
      ),
    );
  }
}
