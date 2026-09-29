import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';
import 'package:guard_mobile_app/features/map/services/routing_service.dart';
import 'package:latlong2/latlong.dart';

void main() {
  group('AreaPinModel Tests', () {
    test('fromJson parses correctly', () {
      final json = {
        'id': '123e4567-e89b-12d3-a456-426614174000',
        'name': 'Cổng chính',
        'areaLevel': 'PUBLIC',
        'areaAccessLevel': 1,
        'building': 'FPT_AROUND',
        'floor': 'G',
        'centerLatitude': 10.84175,
        'centerLongitude': 106.80922,
        'isActive': true,
      };

      final pin = AreaPinModel.fromJson(json);

      expect(pin.id, '123e4567-e89b-12d3-a456-426614174000');
      expect(pin.name, 'Cổng chính');
      expect(pin.centerLatitude, 10.84175);
      expect(pin.centerLongitude, 106.80922);
      expect(pin.latLng.latitude, 10.84175);
      expect(pin.latLng.longitude, 106.80922);
      expect(pin.levelDisplayName, 'Công cộng (Level 1)');
      expect(pin.locationSubtitle, 'Tòa FPT_AROUND • Tầng G');
    });

    test('levelColor and levelIcon returns appropriate values for HIGHLY_CONFIDENTIAL', () {
      const pin = AreaPinModel(
        id: '1',
        name: 'Phòng Máy Chủ',
        areaLevel: 'HIGHLY_CONFIDENTIAL',
        centerLatitude: 10.84155,
        centerLongitude: 106.81025,
        isActive: true,
      );

      expect(pin.levelDisplayName, 'Tối mật (Level 3)');
      expect(pin.locationSubtitle, 'Khuôn viên ngoài trời');
    });
  });

  group('RouteResult Tests', () {
    test('formattedDistance formats meters and kilometers properly', () {
      const shortRoute = RouteResult(
        points: [LatLng(10.0, 106.0), LatLng(10.001, 106.001)],
        distanceMeters: 450.2,
        durationSeconds: 320,
      );
      expect(shortRoute.formattedDistance, '450 m');
      expect(shortRoute.formattedDuration, '5 phút đi bộ');

      const longRoute = RouteResult(
        points: [LatLng(10.0, 106.0), LatLng(10.01, 106.01)],
        distanceMeters: 1450.8,
        durationSeconds: 900,
      );
      expect(longRoute.formattedDistance, '1.5 km');
      expect(longRoute.formattedDuration, '15 phút đi bộ');
    });
  });
}
