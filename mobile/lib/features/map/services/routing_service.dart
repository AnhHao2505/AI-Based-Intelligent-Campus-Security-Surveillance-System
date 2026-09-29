import 'package:dio/dio.dart';
import 'package:latlong2/latlong.dart';

class RouteResult {
  final List<LatLng> points;
  final double distanceMeters;
  final double durationSeconds;
  final bool isFallbackDirect;

  const RouteResult({
    required this.points,
    required this.distanceMeters,
    required this.durationSeconds,
    this.isFallbackDirect = false,
  });

  String get formattedDistance {
    if (distanceMeters >= 1000) {
      return '${(distanceMeters / 1000).toStringAsFixed(1)} km';
    }
    return '${distanceMeters.round()} m';
  }

  String get formattedDuration {
    final minutes = (durationSeconds / 60).round();
    if (minutes < 1) return '< 1 phút đi bộ';
    return '$minutes phút đi bộ';
  }
}

class RoutingService {
  final Dio _dio;

  RoutingService({Dio? dio}) : _dio = dio ?? Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
    ),
  );

  /// Fetch walking route between two GPS coordinates using OSRM public demo server (GeoJSON)
  Future<RouteResult> getWalkingRoute({
    required LatLng start,
    required LatLng destination,
  }) async {
    const distanceCalc = Distance();
    final directDistance = distanceCalc.as(LengthUnit.Meter, start, destination);

    // If start and destination are very close (< 10m), direct connection is sufficient
    if (directDistance < 10) {
      return RouteResult(
        points: [start, destination],
        distanceMeters: directDistance,
        durationSeconds: directDistance / 1.4,
        isFallbackDirect: true,
      );
    }

    final url =
        'https://router.project-osrm.org/route/v1/walking/'
        '${start.longitude},${start.latitude};'
        '${destination.longitude},${destination.latitude}'
        '?overview=full&geometries=geojson';

    try {
      final response = await _dio.get(url);

      if (response.statusCode == 200 && response.data != null) {
        final data = response.data;
        if (data['code'] == 'Ok' && data['routes'] != null && (data['routes'] as List).isNotEmpty) {
          final route = data['routes'][0];
          final distance = (route['distance'] as num?)?.toDouble() ?? 0.0;
          final duration = (route['duration'] as num?)?.toDouble() ?? 0.0;
          final geometry = route['geometry'];

          final List<LatLng> latLngList = [];
          if (geometry is Map && geometry['coordinates'] is List) {
            final coords = geometry['coordinates'] as List;
            for (final c in coords) {
              if (c is List && c.length >= 2) {
                latLngList.add(LatLng((c[1] as num).toDouble(), (c[0] as num).toDouble()));
              }
            }
          }

          // If OSRM snapped both waypoints to the same road point (distance < 5m but directDistance > 15m),
          // OSRM failed to route through campus internal paths: fallback to direct walking path
          if (latLngList.isEmpty || (distance < 5 && directDistance > 15)) {
            return RouteResult(
              points: [start, destination],
              distanceMeters: directDistance,
              durationSeconds: directDistance / 1.4,
              isFallbackDirect: true,
            );
          }

          // Ensure route polyline connects directly to exact start and destination
          if (distanceCalc.as(LengthUnit.Meter, start, latLngList.first) > 2) {
            latLngList.insert(0, start);
          }
          if (distanceCalc.as(LengthUnit.Meter, destination, latLngList.last) > 2) {
            latLngList.add(destination);
          }

          return RouteResult(
            points: latLngList,
            distanceMeters: distance > 0 ? distance : directDistance,
            durationSeconds: duration > 0 ? duration : (directDistance / 1.4),
            isFallbackDirect: false,
          );
        }
      }
    } catch (_) {
      // In case of network timeout or OSRM unavailable, fallback to straight line
    }

    // Direct line fallback calculation (Average walking speed ~ 1.4 m/s / 5 km/h)
    return RouteResult(
      points: [start, destination],
      distanceMeters: directDistance,
      durationSeconds: directDistance / 1.4,
      isFallbackDirect: true,
    );
  }
}
