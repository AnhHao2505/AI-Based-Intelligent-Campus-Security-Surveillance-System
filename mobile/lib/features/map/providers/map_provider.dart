import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/network/api_client.dart';
import '../data/campus_floor_resolver.dart';
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

  bool _isLoadingCampus = false;
  bool get isLoadingCampus => _isLoadingCampus;

  String? _campusError;
  String? get campusError => _campusError;

  /// Tâm khuôn viên = tâm khung bao các khối 3D trong asset (không viết cứng toạ độ); null khi chưa nạp được.
  LatLng? get campusCenter {
    final b = _campus?.bounds;
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

  /// Chuẩn hóa vị trí GPS, tự động đưa thiết bị máy ảo (hoặc tọa độ ngoài khuôn viên trên đường N3 / Mountain View)
  /// về sân trường FPT University để bảo vệ luôn nằm trong khuôn viên khi thử nghiệm trên máy ảo.
  LatLng _normalizePosition(double lat, double lng) {
    if (((lat - 10.8468).abs() < 0.005 && (lng - 106.8091).abs() < 0.005) ||
        ((lat - 37.42).abs() < 0.1 && (lng - -122.08).abs() < 0.1)) {
      final center = campusCenter;
      if (center != null) {
        debugPrint('[MapProvider] Phát hiện tọa độ máy ảo mặc định ($lat, $lng). Tự động chuẩn hóa về sân trường ($center).');
        return center;
      }
    }
    return LatLng(lat, lng);
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

  /// Các tầng có bản đồ 3D của toà đang chọn (null = mọi toà).
  List<int> get availableFloorOrders => _campus?.floorOrdersFor(_selectedBuilding) ?? const [0];

  /// Ghim khu theo toà / tầng đang chọn (bỏ khu không có toạ độ; khu không có tầng luôn hiện).
  List<AreaPinModel> get filteredPins => filterPinsForView(
        pins: _areaPins,
        building: _selectedBuilding,
        floorOrder: _selectedFloorOrder,
        campus: _campus,
      );

  void _clampFloorToBuilding() {
    final floors = availableFloorOrders;
    if (floors.isNotEmpty && !floors.contains(_selectedFloorOrder)) {
      _selectedFloorOrder = floors.first;
    }
  }

  /// Initialize location tracking using geolocator
  Future<void> initLocation() async {
    _isLocating = true;
    notifyListeners();

    try {
      bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        debugPrint('[MapProvider] Location services are disabled on device/emulator.');
        _guardLocation ??= campusCenter;
        notifyListeners();
      }

      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }

      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        debugPrint('[MapProvider] Location permission not granted: $permission');
        _isLocating = false;
        _guardLocation ??= campusCenter;
        notifyListeners();
        return;
      }

      // Configure settings specifically for Android Emulator & Real Devices
      late LocationSettings locationSettings;
      if (defaultTargetPlatform == TargetPlatform.android) {
        locationSettings = AndroidSettings(
          accuracy: LocationAccuracy.best,
          distanceFilter: 0, // Catch every tiny movement from emulator
          forceLocationManager: true, // Forces Android LocationManager for emulator mock GPS
          intervalDuration: const Duration(milliseconds: 500),
        );
      } else {
        locationSettings = const LocationSettings(
          accuracy: LocationAccuracy.best,
          distanceFilter: 0,
        );
      }

      // 1. Instantly use last known position if available
      final lastKnown = await Geolocator.getLastKnownPosition();
      if (lastKnown != null) {
        final pos = _normalizePosition(lastKnown.latitude, lastKnown.longitude);
        debugPrint('[MapProvider] Using last known location: ${pos.latitude}, ${pos.longitude}');
        _guardLocation = pos;
        _guardHeading = lastKnown.heading;
        notifyListeners();
      } else {
        _guardLocation ??= campusCenter;
        notifyListeners();
      }

      // 2. Fetch fresh position asynchronously
      Geolocator.getCurrentPosition(locationSettings: locationSettings)
          .then((position) {
        final pos = _normalizePosition(position.latitude, position.longitude);
        debugPrint('[MapProvider] Fresh current position: ${pos.latitude}, ${pos.longitude}');
        _guardLocation = pos;
        _guardHeading = position.heading;
        notifyListeners();
      }).catchError((e) {
        debugPrint('[MapProvider] getCurrentPosition timeout/error: $e');
      });

      // 3. Start continuous live tracking stream
      _locationSubscription?.cancel();
      _locationSubscription = Geolocator.getPositionStream(
        locationSettings: locationSettings,
      ).listen(
        (Position newPos) {
          final pos = _normalizePosition(newPos.latitude, newPos.longitude);
          debugPrint('[MapProvider] GPS stream update: ${pos.latitude}, ${pos.longitude}');
          _guardLocation = pos;
          _guardHeading = newPos.heading;
          notifyListeners();

        },
        onError: (err) {
          debugPrint('[MapProvider] getPositionStream error: $err');
        },
      );

    } catch (e) {
      debugPrint('[MapProvider] initLocation exception: $e');
      _guardLocation ??= campusCenter;
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
          .where((p) => p.isActive && p.centerLatitude != 0.0 && p.centerLongitude != 0.0)
          .toList();

      // Extract unique building names for filter (quy về tên chuẩn của bản đồ 3D khi có)
      final bldSet = <String>{};
      for (final pin in _areaPins) {
        if (pin.building != null && pin.building!.trim().isNotEmpty) {
          bldSet.add(_campus?.buildingFor(pin.building)?.name ?? pin.building!.trim());
        }
      }
      for (final b in _campus?.buildings ?? const <CampusBuilding>[]) {
        bldSet.add(b.name);
      }
      _buildings = bldSet.toList()..sort();
    } catch (e) {
      _errorMessage = ApiClient.formatError(e);
    } finally {
      _isLoadingPins = false;
      notifyListeners();
    }
  }

  void selectBuilding(String? building) {
    _selectedBuilding = building;
    _clampFloorToBuilding();
    notifyListeners();
  }

  void selectFloor(int floorOrder) {
    _selectedFloorOrder = floorOrder;
    notifyListeners();
  }

  void selectArea(AreaPinModel? area) {
    _selectedArea = area;
    _highlightRoomId = area == null ? null : resolveIncidentFocus(area, _campus).roomId;
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
    final focus = resolveIncidentFocus(resolved, _campus);
    _selectedArea = resolved;
    _highlightRoomId = focus.roomId;
    if (focus.building != null) {
      _selectedBuilding = focus.building;
    }
    _selectedFloorOrder = focus.floorOrder;
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
      final destFloor = floorOrderOfPin(destination, _campus);
      _currentRoute = planCampusRoute(
        graph: _campus?.graph,
        campus: _campus,
        gps: _guardLocation,
        selectedBuilding: _selectedBuilding,
        selectedFloorOrder: _selectedFloorOrder,
        destinationLatitude: pinHasCoordinates(destination) ? destination.centerLatitude : null,
        destinationLongitude: pinHasCoordinates(destination) ? destination.centerLongitude : null,
        destinationBuilding: destination.building,
        destinationFloorOrder: destFloor,
        eta: eta,
      );
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
    notifyListeners();
  }

  /// Tìm hoặc tạo pin tương ứng với sự cố (dùng để preview trên bản đồ)
  AreaPinModel? findPinForIncident({
    String? areaId,
    String? areaName,
    String? cameraCode,
    LatLng? targetLatLng,
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
          (_areaPins.isNotEmpty ? _areaPins.first.latLng : campusCenter) ??
          const LatLng(0, 0);
      matchedPin = AreaPinModel(
        id: areaId ?? 'incident_target',
        name: areaName ?? 'Vị trí sự cố (${cameraCode ?? "CAM"})',
        areaLevel: 'RESTRICTED',
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
