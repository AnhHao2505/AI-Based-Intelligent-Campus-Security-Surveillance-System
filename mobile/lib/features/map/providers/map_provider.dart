import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/network/api_client.dart';
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

  StreamSubscription<Position>? _locationSubscription;

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
        debugPrint('[MapProvider] Using last known location: ${lastKnown.latitude}, ${lastKnown.longitude}');
        _guardLocation = LatLng(lastKnown.latitude, lastKnown.longitude);
        _guardHeading = lastKnown.heading;
        notifyListeners();
      } else {
        _guardLocation ??= campusCenter;
        notifyListeners();
      }

      // 2. Fetch fresh position asynchronously
      Geolocator.getCurrentPosition(locationSettings: locationSettings)
          .then((position) {
        debugPrint('[MapProvider] Fresh current position: ${position.latitude}, ${position.longitude}');
        _guardLocation = LatLng(position.latitude, position.longitude);
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
          debugPrint('[MapProvider] GPS stream update: ${newPos.latitude}, ${newPos.longitude}');
          _guardLocation = LatLng(newPos.latitude, newPos.longitude);
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
          .where((p) => p.centerLatitude != 0.0 && p.centerLongitude != 0.0)
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

  @override
  void dispose() {
    _locationSubscription?.cancel();
    super.dispose();
  }
}
