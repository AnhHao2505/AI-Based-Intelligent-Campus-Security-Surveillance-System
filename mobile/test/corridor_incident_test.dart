import 'package:flutter_test/flutter_test.dart';
import 'package:latlong2/latlong.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/map/data/campus_floor_resolver.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_style.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';
import 'package:guard_mobile_app/features/map/providers/map_provider.dart';

void main() {
  group('CORRIDOR Incident & Pin Resolution', () {
    test('IncidentModel.fromJson parses corridor camera pin & floor metadata cleanly', () {
      final inc = IncidentModel.fromJson({
        'id': 'inc-corr-1',
        'cameraCode': 'CAM-019',
        'cameraName': 'Hành lang Black',
        'cameraType': 'CORRIDOR',
        'areaId': null,
        'areaName': null,
        'building': 'Tòa Epsilon',
        'floor': 'Tầng 3',
        'mapLatitude': 10.84118,
        'mapLongitude': 106.80985,
        'mapFloorOrder': 3,
        'eventType': 'PERSON_INTRUSION',
        'status': 'NEW',
      });

      expect(inc.isCorridor, isTrue);
      expect(inc.displayLocationName, 'Hành lang Black');
      expect(inc.displayBuildingFloor, 'Tòa Epsilon • Tầng 3');
      expect(inc.mapFloorOrder, 3);
      expect(inc.mapLatitude, closeTo(10.84118, 1e-6));
      expect(inc.mapLongitude, closeTo(106.80985, 1e-6));
    });

    test('findPinForIncident + focusIncident keeps floorOrder, building, and camera pin coords for CORRIDOR', () {
      final provider = MapProvider(ApiClient());
      final roomPin = AreaPinModel(
        id: 'room-1',
        name: 'Phòng họp',
        areaLevel: 'CONFIDENTIAL',
        building: 'Tòa Epsilon',
        floor: 'Tầng 1',
        centerLatitude: 10.84000,
        centerLongitude: 106.80900,
        isActive: true,
      );

      final corridorPin = provider.findPinForIncident(
        areaId: null,
        areaName: null,
        cameraCode: 'CAM-019',
        cameraName: 'Hành lang Black',
        cameraType: 'CORRIDOR',
        building: 'Tòa Epsilon',
        floor: 'Tầng 3',
        floorOrder: 3,
        targetLatLng: const LatLng(10.84118, 106.80985),
      );

      expect(corridorPin, isNotNull);
      expect(corridorPin!.id, 'corridor_CAM-019');
      expect(corridorPin.name, 'Hành lang Black');
      expect(corridorPin.building, 'Tòa Epsilon');
      expect(corridorPin.floorOrder, 3);
      expect(floorOrderOfPin(corridorPin, null), 3);

      final focus = provider.focusIncident(corridorPin);
      expect(focus.floorOrder, 3);
      expect(provider.incidentFloorOrder, 3);
      expect(provider.incidentPin?.id, 'corridor_CAM-019');

      // Tách ghim sự cố đúng chuẩn commit 8344d2ef / 0d7b0210
      final split = splitIncidentPin(
        [roomPin],
        selected: provider.selectedArea,
        incidentAreaId: provider.incidentAreaId,
        incidentArea: provider.incidentPin,
      );
      expect(split.incident, isNotNull);
      expect(split.incident!.id, 'corridor_CAM-019');
      expect(split.incident!.centerLatitude, closeTo(10.84118, 1e-6));
      expect(split.areas.map((e) => e.id), ['room-1']);
    });

    test('floorOrderOfPin prioritizes explicit AreaPinModel.floorOrder including Ground floor (0)', () {
      const groundCorridor = AreaPinModel(
        id: 'corridor_CAM-001',
        name: 'Hành lang Sảnh chính',
        areaLevel: AreaPinModel.unknownLevel,
        building: 'Tòa Epsilon',
        floor: null,
        floorOrder: 0,
        centerLatitude: 10.841,
        centerLongitude: 106.809,
        isActive: true,
      );
      expect(floorOrderOfPin(groundCorridor, null), 0);
    });
  });
}
