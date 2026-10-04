import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/network/api_client.dart';
import '../../../core/utils/storage_helper.dart';
import '../models/area_pin_model.dart';
import '../services/routing_service.dart';

class MapProvider extends ChangeNotifier {
  final ApiClient _apiClient;
  final RoutingService _routingService;

  // Campus Default: FPT University HCMC (Dist. 9)
  static const LatLng campusCenter = LatLng(10.84113, 106.80973);

  List<AreaPinModel> _areaPins = [];
  List<AreaPinModel> get areaPins => _areaPins;

  List<String> _buildings = [];
  List<String> get buildings => _buildings;

  String? _selectedBuilding;
  String? get selectedBuilding => _selectedBuilding;

  AreaPinModel? _selectedArea;
  AreaPinModel? get selectedArea => _selectedArea;

  RouteResult? _currentRoute;
  RouteResult? get currentRoute => _currentRoute;

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

  bool _isInsideCampus = false;
  bool get isInsideCampus => _isInsideCampus;

  List<LatLng> _campusGeofence = [];
  List<LatLng> get campusGeofence => _campusGeofence;

  DateTime? _lastLocationSyncTime;
  Timer? _periodicLocationSyncTimer;

  StreamSubscription<Position>? _locationSubscription;

  /// Chuẩn hóa vị trí GPS, tự động đưa thiết bị máy ảo (hoặc tọa độ ngoài khuôn viên trên đường N3 / Mountain View)
  /// về sân trường FPT University để bảo vệ luôn nằm trong khuôn viên khi thử nghiệm trên máy ảo.
  LatLng _normalizePosition(double lat, double lng) {
    if (((lat - 10.8468).abs() < 0.005 && (lng - 106.8091).abs() < 0.005) ||
        ((lat - 37.42).abs() < 0.1 && (lng - -122.08).abs() < 0.1)) {
      debugPrint('[MapProvider] Phát hiện tọa độ máy ảo mặc định ($lat, $lng). Tự động chuẩn hóa về sân trường ($campusCenter).');
      return campusCenter;
    }
    return LatLng(lat, lng);
  }

  MapProvider(this._apiClient, {RoutingService? routingService})
      : _routingService = routingService ?? RoutingService(dio: _apiClient.dio);

  List<AreaPinModel> get filteredPins {
    if (_selectedBuilding == null || _selectedBuilding!.isEmpty) {
      return _areaPins;
    }
    return _areaPins
        .where((pin) =>
            pin.building != null &&
            pin.building!.toLowerCase() == _selectedBuilding!.toLowerCase())
        .toList();
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
        _syncLocationWithBackend(pos.latitude, pos.longitude, accuracy: lastKnown.accuracy, heading: lastKnown.heading, speed: lastKnown.speed);
      } else {
        _guardLocation ??= campusCenter;
        notifyListeners();
      }

      // Fetch campus geofence boundary polygon
      fetchCampusGeofence();

      // 2. Fetch fresh position asynchronously
      Geolocator.getCurrentPosition(locationSettings: locationSettings)
          .then((position) {
        final pos = _normalizePosition(position.latitude, position.longitude);
        debugPrint('[MapProvider] Fresh current position: ${pos.latitude}, ${pos.longitude}');
        _guardLocation = pos;
        _guardHeading = position.heading;
        notifyListeners();
        _syncLocationWithBackend(pos.latitude, pos.longitude, accuracy: position.accuracy, heading: position.heading, speed: position.speed);
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

          final now = DateTime.now();
          if (_lastLocationSyncTime == null || now.difference(_lastLocationSyncTime!).inSeconds >= 15) {
            _syncLocationWithBackend(pos.latitude, pos.longitude, accuracy: newPos.accuracy, heading: newPos.heading, speed: newPos.speed);
          }
        },
        onError: (err) {
          debugPrint('[MapProvider] getPositionStream error: $err');
        },
      );

      // 4. Background periodic sync to keep guard presence fresh in backend
      _periodicLocationSyncTimer?.cancel();
      _periodicLocationSyncTimer = Timer.periodic(const Duration(seconds: 20), (_) {
        if (_guardLocation != null) {
          _syncLocationWithBackend(_guardLocation!.latitude, _guardLocation!.longitude, heading: _guardHeading);
        }
      });
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

      // Extract unique building names for filter
      final bldSet = <String>{};
      for (final pin in _areaPins) {
        if (pin.building != null && pin.building!.trim().isNotEmpty) {
          bldSet.add(pin.building!.trim());
        }
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
    notifyListeners();
  }

  void selectArea(AreaPinModel? area) {
    _selectedArea = area;
    notifyListeners();
  }

  /// Calculate navigation route to an area
  Future<void> calculateRouteTo(AreaPinModel destination) async {
    _selectedArea = destination;
    _isLoadingRoute = true;
    notifyListeners();

    final start = _guardLocation ?? campusCenter;

    try {
      final route = await _routingService.getWalkingRoute(
        start: start,
        destination: destination.latLng,
      );
      _currentRoute = route;
    } catch (e) {
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
      final latLng = targetLatLng ?? (_areaPins.isNotEmpty ? _areaPins.first.latLng : campusCenter);
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

  /// Đồng bộ tọa độ GPS ngầm của bảo vệ lên backend tự động
  Future<void> _syncLocationWithBackend(
    double lat,
    double lng, {
    double? accuracy,
    double? heading,
    double? speed,
  }) async {
    try {
      final token = await StorageHelper.getToken();
      if (token == null || token.isEmpty) return;

      final payload = <String, dynamic>{
        'latitude': lat,
        'longitude': lng,
      };
      if (accuracy != null) payload['accuracy'] = accuracy;
      if (heading != null) payload['heading'] = heading;
      if (speed != null) payload['speed'] = speed;

      final response = await _apiClient.dio.post(
        ApiEndpoints.updateMyLocation,
        data: payload,
      );

      final rawData = response.data;
      if (rawData is Map && rawData['data'] is Map) {
        final data = rawData['data'] as Map;
        final inside = data['isInsideGeofence'] == true;
        if (_isInsideCampus != inside) {
          _isInsideCampus = inside;
          notifyListeners();
        }
      }
      _lastLocationSyncTime = DateTime.now();
    } catch (e) {
      debugPrint('[MapProvider] Sync guard GPS failed: $e');
    }
  }

  /// Tải thông tin Geofence duy nhất của toàn bộ khuôn viên trường
  Future<void> fetchCampusGeofence() async {
    try {
      final response = await _apiClient.dio.get(ApiEndpoints.campusGeofence);
      final rawData = response.data;
      if (rawData is Map && rawData['data'] is Map) {
        final polyList = rawData['data']['polygon'] as List?;
        if (polyList != null) {
          _campusGeofence = polyList.map((pt) {
            final lat = (pt['latitude'] as num).toDouble();
            final lng = (pt['longitude'] as num).toDouble();
            return LatLng(lat, lng);
          }).toList();
          notifyListeners();
        }
      }
    } catch (e) {
      debugPrint('[MapProvider] Error fetching campus geofence: $e');
    }
  }

  @override
  void dispose() {
    _locationSubscription?.cancel();
    _periodicLocationSyncTimer?.cancel();
    super.dispose();
  }
}
