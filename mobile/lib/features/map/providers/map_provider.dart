import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/network/api_client.dart';
import '../data/campus_floor_resolver.dart';
import '../data/campus_graph.dart';
import '../data/campus_map_data.dart';
import '../data/campus_map_repository.dart';
import '../data/campus_route_planner.dart';
import '../models/area_pin_model.dart';
import '../services/route_settings_service.dart';

class MapProvider extends ChangeNotifier {
  final ApiClient _apiClient;
  final RouteSettingsService _routeSettingsService;
  final CampusMapRepository _campusRepository;

  // Dữ liệu bản đồ 3D khuôn viên (asset sinh từ seed web, mobile/assets/campus/)
  CampusMapData? _campus;
  CampusMapData? get campus => _campus;

  /// Bản đồ khuôn viên đã dọn sạch Tòa Alpha (chỉ giữ Tòa Epsilon như trên web).
  CampusMapData? _visibleCampus;
  CampusMapData? get visibleCampus => _visibleCampus;

  bool _isLoadingCampus = false;
  bool get isLoadingCampus => _isLoadingCampus;

  String? _campusError;
  String? get campusError => _campusError;

  /// Tâm khuôn viên = tâm khung bao các khối 3D trong asset (không viết cứng toạ độ); null khi chưa nạp được.
  LatLng? get campusCenter {
    final b = _campus?.bounds;
    return b == null ? null : LatLng(b.centerLatitude, b.centerLongitude);
  }

  /// Khung bao của các tòa đang hiển thị (Tòa Epsilon, đã loại bỏ Tòa Alpha).
  CampusBounds? get visibleCampusBounds => _visibleCampus?.bounds ?? _campus?.visibleBounds;

  /// Tâm của các tòa đang hiển thị (Tòa Epsilon).
  LatLng? get visibleCampusCenter {
    final b = visibleCampusBounds;
    return b == null ? null : LatLng(b.centerLatitude, b.centerLongitude);
  }

  /// Tầng đang xem; mặc định tầng trệt.
  int _selectedFloorOrder = 0;
  int get selectedFloorOrder => _selectedFloorOrder;

  /// Phòng cần tô nổi (khu của sự cố / khu đang chọn).
  String? _highlightRoomId;
  String? get highlightRoomId => _highlightRoomId;

  List<AreaPinModel> _areaPins = [];
  List<AreaPinModel> get areaPins => _areaPins;

  List<String> _buildings = [];
  List<String> get buildings => _buildings;

  String? _selectedBuilding;
  String? get selectedBuilding => _selectedBuilding;

  /// Tòa hiệu lực để vẽ khối 3D, cầu thang và nhãn phòng (đồng bộ effectiveBuilding3d của web: loại bỏ Tòa Alpha).
  String? get effectiveBuilding {
    if (_selectedBuilding != null && !_isAlpha(_selectedBuilding)) {
      return _selectedBuilding;
    }
    if (_buildings.isNotEmpty) return _buildings.first;
    final vis = _campus?.visibleBuildings ?? const <CampusBuilding>[];
    return vis.isNotEmpty ? vis.first.name : _selectedBuilding;
  }

  AreaPinModel? _selectedArea;
  AreaPinModel? get selectedArea => _selectedArea;

  /// Tuyến dẫn đường hiện tại trên đồ thị khuôn viên (P-MAP-2); không còn gọi OSRM.
  CampusRoute? _currentRoute;
  CampusRoute? get currentRoute => _currentRoute;

  LatLng? _guardLocation;
  LatLng? get guardLocation => _guardLocation;

  double? _guardHeading;
  double? get guardHeading => _guardHeading;

  bool _isLoadingPins = false;
  bool get isLoadingPins => _isLoadingPins;

  bool _isLoadingRoute = false;
  bool get isLoadingRoute => _isLoadingRoute;

  bool _isLocating = false;
  bool get isLocating => _isLocating;

  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  StreamSubscription<Position>? _locationSubscription;
  RouteEtaSettings _lastEtaSettings = RouteEtaSettings.defaults;
  LatLng? _lastRoutedGps;

  bool _isAlpha(String? building) =>
      _campus?.isAlphaBuilding(building) ?? isAlphaBuildingName(building);

  void _syncVisibleBuildings() {
    final bldSet = <String>{};
    for (final pin in _areaPins) {
      if (pin.building != null && pin.building!.trim().isNotEmpty) {
        final canonical = _campus?.buildingFor(pin.building)?.name ?? pin.building!.trim();
        if (!_isAlpha(canonical)) {
          bldSet.add(canonical);
        }
      }
    }
    for (final b in _campus?.visibleBuildings ?? const <CampusBuilding>[]) {
      bldSet.add(b.name);
    }
    _buildings = bldSet.toList()..sort();
    if (_selectedBuilding != null && _isAlpha(_selectedBuilding)) {
      _selectedBuilding = _buildings.isNotEmpty ? _buildings.first : null;
    }
  }

  MapProvider(this._apiClient, {RouteSettingsService? routeSettingsService, CampusMapRepository? campusRepository})
      : _routeSettingsService = routeSettingsService ?? RouteSettingsService(_apiClient.dio),
        _campusRepository = campusRepository ?? CampusMapRepository();

  /// Nạp asset bản đồ 3D (một lần). Lỗi → [campusError] (câu thân thiện), không ném ra ngoài.
  Future<void> loadCampusMap({bool force = false}) async {
    if (_isLoadingCampus || (_campus != null && !force)) return;
    _isLoadingCampus = true;
    _campusError = null;
    notifyListeners();
    try {
      _campus = await _campusRepository.load();
      _visibleCampus = _campus?.withoutAlpha();
      _syncVisibleBuildings();
      _clampFloorToBuilding();
    } on CampusMapLoadException catch (e) {
      debugPrint('[MapProvider] $e');
      _campusError = e.message;
    } catch (e) {
      debugPrint('[MapProvider] loadCampusMap: $e');
      _campusError = 'Không đọc được dữ liệu bản đồ khuôn viên';
    } finally {
      _isLoadingCampus = false;
      notifyListeners();
    }
  }

  /// Các tầng có bản đồ 3D của toà đang chọn (null = các tòa hiển thị).
  List<int> get availableFloorOrders {
    if (_selectedBuilding != null && !_isAlpha(_selectedBuilding)) {
      return (_visibleCampus ?? _campus)?.floorOrdersFor(_selectedBuilding) ?? const [0];
    }
    return (_visibleCampus ?? _campus)?.floorOrdersFor(null) ?? const [0];
  }

  /// Ghim khu theo toà / tầng đang chọn (bỏ khu không có toạ độ và khu thuộc Tòa Alpha đã xóa; khu không có tầng luôn hiện).
  List<AreaPinModel> get filteredPins => filterPinsForView(
        pins: _areaPins.where((p) => !_isAlpha(p.building)).toList(),
        building: _selectedBuilding,
        floorOrder: _selectedFloorOrder,
        campus: _visibleCampus ?? _campus,
      );

  void _clampFloorToBuilding() {
    final floors = availableFloorOrders;
    if (floors.isNotEmpty && !floors.contains(_selectedFloorOrder)) {
      _selectedFloorOrder = floors.first;
    }
  }

  /// Cập nhật vị trí GPS thực tế (real-time) từ thiết bị và tự động tính lại lộ trình nếu đang dẫn đường.
  void updateGuardLocation(LatLng location, {double? heading}) {
    if (!location.latitude.isFinite || !location.longitude.isFinite) return;
    _guardLocation = location;
    if (heading != null && heading.isFinite) {
      _guardHeading = heading;
    }

    // Khi đang ở chế độ dẫn đường, tự động cập nhật lộ trình theo vị trí GPS real-time (ngưỡng dịch chuyển >= 1m)
    final destination = _selectedArea;
    if (_currentRoute != null && destination != null && pinHasCoordinates(destination)) {
      final movedEnough = _lastRoutedGps == null ||
          haversineMeters(
                _lastRoutedGps!.latitude,
                _lastRoutedGps!.longitude,
                location.latitude,
                location.longitude,
              ) >=
              1.0;
      if (movedEnough) {
        try {
          final routingCampus = _visibleCampus ?? _campus;
          final destFloor = floorOrderOfPin(destination, routingCampus);
          _currentRoute = planCampusRoute(
            graph: routingCampus?.graph,
            campus: routingCampus,
            gps: _guardLocation,
            selectedBuilding: _selectedBuilding ?? effectiveBuilding,
            selectedFloorOrder: _selectedFloorOrder,
            destinationLatitude: destination.centerLatitude,
            destinationLongitude: destination.centerLongitude,
            destinationBuilding: destination.building,
            destinationFloorOrder: destFloor,
            eta: _lastEtaSettings,
          );
          _lastRoutedGps = location;
        } catch (e) {
          debugPrint('[MapProvider] Real-time route update skipped: $e');
        }
      }
    }
    notifyListeners();
  }

  /// Initialize real-time location tracking using geolocator (no fixed mock fallback)
  Future<void> initLocation() async {
    _isLocating = true;
    notifyListeners();

    try {
      bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        debugPrint('[MapProvider] Location services are disabled on device.');
        _isLocating = false;
        notifyListeners();
        return;
      }

      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }

      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        debugPrint('[MapProvider] Location permission not granted: $permission');
        _isLocating = false;
        notifyListeners();
        return;
      }

      late LocationSettings locationSettings;
      if (defaultTargetPlatform == TargetPlatform.android) {
        locationSettings = AndroidSettings(
          accuracy: LocationAccuracy.best,
          distanceFilter: 0,
          forceLocationManager: true,
          intervalDuration: const Duration(milliseconds: 500),
        );
      } else {
        locationSettings = const LocationSettings(
          accuracy: LocationAccuracy.best,
          distanceFilter: 0,
        );
      }

      // 1. Sử dụng ngay vị trí GPS gần nhất của thiết bị nếu có
      final lastKnown = await Geolocator.getLastKnownPosition();
      if (lastKnown != null) {
        final pos = LatLng(lastKnown.latitude, lastKnown.longitude);
        debugPrint('[MapProvider] Using last known location: ${pos.latitude}, ${pos.longitude}');
        updateGuardLocation(pos, heading: lastKnown.heading);
      }

      // 2. Lấy vị trí GPS thực tế hiện tại
      Geolocator.getCurrentPosition(locationSettings: locationSettings)
          .then((position) {
        final pos = LatLng(position.latitude, position.longitude);
        debugPrint('[MapProvider] Fresh current position: ${pos.latitude}, ${pos.longitude}');
        updateGuardLocation(pos, heading: position.heading);
      }).catchError((e) {
        debugPrint('[MapProvider] getCurrentPosition timeout/error: $e');
      });

      // 3. Lắng nghe luồng GPS real-time liên tục
      _locationSubscription?.cancel();
      _locationSubscription = Geolocator.getPositionStream(
        locationSettings: locationSettings,
      ).listen(
        (Position newPos) {
          final pos = LatLng(newPos.latitude, newPos.longitude);
          debugPrint('[MapProvider] GPS stream update: ${pos.latitude}, ${pos.longitude}');
          updateGuardLocation(pos, heading: newPos.heading);
        },
        onError: (err) {
          debugPrint('[MapProvider] getPositionStream error: $err');
        },
      );
    } catch (e) {
      debugPrint('[MapProvider] initLocation exception: $e');
    } finally {
      _isLocating = false;
      notifyListeners();
    }
  }

  /// Fetch Area Pins from backend
  Future<void> fetchAreaPins({String? building}) async {
    _isLoadingPins = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final endpoint = (building != null && building.isNotEmpty)
          ? ApiEndpoints.areaMapPinsByBuilding(building)
          : ApiEndpoints.areaMapPins;

      final response = await _apiClient.dio.get(endpoint);
      final rawData = response.data;

      List items = [];
      if (rawData is Map && rawData['data'] is List) {
        items = rawData['data'] as List;
      } else if (rawData is List) {
        items = rawData;
      }

      _areaPins = items
          .map((e) => AreaPinModel.fromJson(Map<String, dynamic>.from(e as Map)))
          .where((p) => p.isActive && p.centerLatitude != 0.0 && p.centerLongitude != 0.0 && !_isAlpha(p.building))
          .toList();

      _syncVisibleBuildings();
    } catch (e) {
      _errorMessage = ApiClient.formatError(e);
    } finally {
      _isLoadingPins = false;
      notifyListeners();
    }
  }

  void selectBuilding(String? building) {
    if (building != null && _isAlpha(building)) {
      _selectedBuilding = _buildings.isNotEmpty ? _buildings.first : null;
    } else {
      _selectedBuilding = building;
    }
    _clampFloorToBuilding();
    notifyListeners();
  }

  void selectFloor(int floorOrder) {
    _selectedFloorOrder = floorOrder;
    notifyListeners();
  }

  /// Id khu của sự cố đang mở trên bản đồ (focusIncident); khu này vẽ ở lớp ghim sự cố. Chọn khu khác / đóng thẻ → hết.
  String? _incidentAreaId;
  String? get incidentAreaId => _incidentAreaId;

  void selectArea(AreaPinModel? area) {
    if (area?.id != _incidentAreaId) _incidentAreaId = null;
    _selectedArea = area;
    final targetCampus = _visibleCampus ?? _campus;
    _highlightRoomId = area == null ? null : resolveIncidentFocus(area, targetCampus).roomId;
    notifyListeners();
  }

  /// Mở bản đồ từ thẻ sự cố: chọn khu, chuyển tới đúng toà / tầng của khu và tô nổi phòng chứa tâm khu.
  /// [pin] có thể là ghim tạm (chưa có toà / tầng) → thử ghép lại với ghim thật theo id / tên khi đã có danh sách khu.
  IncidentMapFocus focusIncident(AreaPinModel pin) {
    final resolved = _areaPins.cast<AreaPinModel?>().firstWhere(
              (p) => p!.id.toLowerCase() == pin.id.toLowerCase(),
              orElse: () => null,
            ) ??
        _areaPins.cast<AreaPinModel?>().firstWhere(
              (p) => p!.name.toLowerCase() == pin.name.toLowerCase(),
              orElse: () => null,
            ) ??
        pin;
    final targetCampus = _visibleCampus ?? _campus;
    final focus = resolveIncidentFocus(resolved, targetCampus);
    _selectedArea = resolved;
    _incidentAreaId = resolved.id;
    _highlightRoomId = focus.roomId;
    if (focus.building != null && !_isAlpha(focus.building)) {
      _selectedBuilding = focus.building;
    } else if (_selectedBuilding == null || _isAlpha(_selectedBuilding)) {
      _selectedBuilding = effectiveBuilding;
    }
    _selectedFloorOrder = focus.floorOrder;
    _clampFloorToBuilding();
    notifyListeners();
    return focus;
  }

  /// Dựng tuyến tới khu [destination] trên đồ thị khuôn viên (trong nhà + ngoài trời, nhiều tầng, nhiều toà).
  /// Xuất phát: GPS ngoài trời trong khuôn viên; còn lại (trong nhà / không GPS / GPS xa) theo toà + tầng đang chọn.
  /// Thời gian dự kiến dùng cấu hình route-settings, đọc lại mỗi lần dẫn đường để đổi cấu hình có hiệu lực ngay.
  Future<void> calculateRouteTo(AreaPinModel destination) async {
    _selectedArea = destination;
    _isLoadingRoute = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final eta = await _routeSettingsService.load();
      _lastEtaSettings = eta;
      final routingCampus = _visibleCampus ?? _campus;
      final destFloor = floorOrderOfPin(destination, routingCampus);
      _currentRoute = planCampusRoute(
        graph: routingCampus?.graph,
        campus: routingCampus,
        gps: _guardLocation,
        selectedBuilding: _selectedBuilding,
        selectedFloorOrder: _selectedFloorOrder,
        destinationLatitude: pinHasCoordinates(destination) ? destination.centerLatitude : null,
        destinationLongitude: pinHasCoordinates(destination) ? destination.centerLongitude : null,
        destinationBuilding: destination.building,
        destinationFloorOrder: destFloor,
        eta: eta,
      );
      _lastRoutedGps = _guardLocation;
    } on CampusRouteUnavailable catch (e) {
      _currentRoute = null;
      _errorMessage = e.message;
    } catch (e) {
      debugPrint('[MapProvider] calculateRouteTo: $e');
      _currentRoute = null;
      _errorMessage = 'Không thể tìm lộ trình dẫn đường';
    } finally {
      _isLoadingRoute = false;
      notifyListeners();
    }
  }

  void clearRoute() {
    _currentRoute = null;
    _lastRoutedGps = null;
    notifyListeners();
  }

  /// Tìm hoặc tạo pin tương ứng với sự cố (dùng để preview trên bản đồ).
  /// Ghim tạm (không khớp khu nào) lấy [areaLevel] nếu payload sự cố có; không có → [AreaPinModel.unknownLevel].
  AreaPinModel? findPinForIncident({
    String? areaId,
    String? areaName,
    String? cameraCode,
    LatLng? targetLatLng,
    String? areaLevel,
  }) {
    AreaPinModel? matchedPin;

    if (areaId != null && areaId.isNotEmpty) {
      matchedPin = _areaPins.cast<AreaPinModel?>().firstWhere(
        (p) => p?.id.toLowerCase() == areaId.toLowerCase(),
        orElse: () => null,
      );
    }

    if (matchedPin == null && areaName != null && areaName.isNotEmpty) {
      matchedPin = _areaPins.cast<AreaPinModel?>().firstWhere(
        (p) => p?.name.toLowerCase() == areaName.toLowerCase(),
        orElse: () => null,
      );
    }

    if (matchedPin == null) {
      // Chưa có toạ độ nào → (0, 0) = "không có toạ độ" (pinHasCoordinates); màn bản đồ ghép lại theo id / tên khi
      // danh sách khu đã tải (focusIncident).
      final latLng = targetLatLng ??
          (_areaPins.isNotEmpty ? _areaPins.first.latLng : (visibleCampusCenter ?? campusCenter)) ??
          const LatLng(0, 0);
      matchedPin = AreaPinModel(
        id: areaId ?? 'incident_target',
        name: areaName ?? 'Vị trí sự cố (${cameraCode ?? "CAM"})',
        areaLevel: (areaLevel != null && areaLevel.trim().isNotEmpty)
            ? areaLevel.trim().toUpperCase()
            : AreaPinModel.unknownLevel,
        centerLatitude: latLng.latitude,
        centerLongitude: latLng.longitude,
        isActive: true,
      );
    }

    return matchedPin;
  }

  /// Dẫn đường trực tiếp tới vị trí sự cố (Incident)
  Future<AreaPinModel?> navigateToIncidentArea({
    String? areaId,
    String? areaName,
    String? cameraCode,
    LatLng? targetLatLng,
  }) async {
    final matchedPin = findPinForIncident(
      areaId: areaId,
      areaName: areaName,
      cameraCode: cameraCode,
      targetLatLng: targetLatLng,
    );

    if (matchedPin != null) {
      await calculateRouteTo(matchedPin);
    }
    return matchedPin;
  }

  @override
  void dispose() {
    _locationSubscription?.cancel();
    super.dispose();
  }
}
