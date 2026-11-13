import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/map/data/campus_graph.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';

/// P-MAP-2 (M2): Dijkstra Dart trên graph.json phải chọn ĐÚNG đường web chọn (golden-routes.json do
/// frontend/scripts/export-campus-map.mjs sinh từ computeDigitizedRoute). So từ nút đầu tới nút cuối của pathNodeIds
/// web (phần nối toạ độ → nút là bước gắn điểm, không thuộc Dijkstra).
void main() {
  final graphJson = jsonDecode(File('assets/campus/graph.json').readAsStringSync()) as Map<String, dynamic>;
  final golden = jsonDecode(File('assets/campus/golden-routes.json').readAsStringSync()) as Map<String, dynamic>;
  final cases = (golden['cases'] as List).cast<Map<String, dynamic>>();
  final graph = CampusGraph.fromJson(graphJson);

  test('asset golden đủ 33 ca, cùng seedVersion với graph.json', () {
    expect(cases, hasLength(33));
    expect(golden['seedVersion'], graphJson['seedVersion']);
  });

  test('cost cạnh = chiều dài + |Δtầng| × phạt đổi tầng của thuật toán (asset khớp hằng mobile)', () {
    for (final e in graph.edges) {
      final a = graph.nodeById(e.a)!;
      final b = graph.nodeById(e.b)!;
      final dFloor = (a.floorOrder - b.floorOrder).abs();
      final flat = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude);
      // cạnh nối cửa ảo: web dùng max(0.05, haversine)
      final expected = (e.a.startsWith('__door_') || e.b.startsWith('__door_'))
          ? [flat + dFloor * kFloorChangePenaltyMeters, (flat < 0.05 ? 0.05 : flat) + dFloor * kFloorChangePenaltyMeters]
          : [flat + dFloor * kFloorChangePenaltyMeters];
      expect(expected.any((x) => (x - e.cost).abs() <= 1e-3), isTrue, reason: '${e.a}–${e.b}: ${e.cost} vs $expected');
    }
  });

  group('golden-routes (33 ca)', () {
    final timings = <String, double>{};

    for (final c in cases) {
      final id = c['id'] as String;
      final expected = c['expected'] as Map<String, dynamic>;
      test('$id ${c['category']}', () {
        if (expected['reachable'] != true) {
          // GR-033: thiếu toạ độ đích → không có nút đích để gắn → không có đường
          final end = c['end'] as Map<String, dynamic>;
          expect(end['lat'], isNull);
          expect(end['nodeId'], isNull);
          expect(graph.shortestPath((c['start'] as Map)['nodeId'] as String, 'không-tồn-tại'), isNull);
          return;
        }
        final ids = (expected['pathNodeIds'] as List).cast<String>();
        final sw = Stopwatch()..start();
        final path = graph.shortestPath(ids.first, ids.last);
        sw.stop();
        timings[id] = sw.elapsedMicroseconds / 1000;
        expect(path, isNotNull);
        expect(path!.nodeIds, ids);
        final webCost = (expected['totalCost'] as num).toDouble();
        expect((path.cost - webCost).abs(), lessThanOrEqualTo(1e-3), reason: 'cost ${path.cost} vs web $webCost');
        expect(path.floorsClimbed, expected['floorsClimbed']);
      });
    }

    tearDownAll(() {
      if (timings.isEmpty) return;
      final values = timings.values.toList();
      final avg = values.reduce((a, b) => a + b) / values.length;
      final max = values.reduce((a, b) => a > b ? a : b);
      // In ra để ghi báo cáo (thời gian Dijkstra mỗi ca trên máy chạy test)
      debugPrint('DIJKSTRA_TIMING cases=${values.length} avgMs=${avg.toStringAsFixed(3)} maxMs=${max.toStringAsFixed(3)}');
    });
  });

  group('nạp đồ thị cùng asset bản đồ (parseCampusAssets)', () {
    Map<String, String> filesOnDisk({bool withGraph = true}) {
      final manifestRaw = File('assets/campus/manifest.json').readAsStringSync();
      final manifest = jsonDecode(manifestRaw) as Map<String, dynamic>;
      final files = <String, String>{'manifest.json': manifestRaw};
      for (final b in manifest['buildings'] as List) {
        for (final f in (b as Map)['floors'] as List) {
          final rel = (f as Map)['file'] as String;
          files[rel] = File('assets/campus/$rel').readAsStringSync();
        }
      }
      if (withGraph) files['graph.json'] = File('assets/campus/graph.json').readAsStringSync();
      return files;
    }

    test('đồ thị có đúng số nút / cạnh ghi trong manifest', () {
      final manifest = jsonDecode(File('assets/campus/manifest.json').readAsStringSync()) as Map<String, dynamic>;
      final data = parseCampusAssets(filesOnDisk());
      expect(data.graph, isNotNull);
      expect(data.graph!.nodes, hasLength(manifest['graph']['nodes']));
      expect(data.graph!.edges, hasLength(manifest['graph']['edges']));
    });

    test('thiếu graph.json → bản đồ 3D vẫn mở, graph = null', () {
      final data = parseCampusAssets(filesOnDisk(withGraph: false));
      expect(data.graph, isNull);
      expect(data.floors, isNotEmpty);
    });

    test('graph.json hỏng → graph = null, không ném lỗi', () {
      final files = filesOnDisk()..['graph.json'] = '{"nodes": [{"id": "x"}]';
      expect(parseCampusAssets(files).graph, isNull);
    });
  });

  test('đồ thị không liên thông → không có đường (null)', () {
    final g = CampusGraph.build(
      const [
        CampusGraphNode(id: 'a', latitude: 10.84, longitude: 106.81, building: 'OUTDOOR', floorOrder: 0, type: 'NODE', isOutdoor: true, isVirtual: false),
        CampusGraphNode(id: 'b', latitude: 10.8401, longitude: 106.81, building: 'OUTDOOR', floorOrder: 0, type: 'NODE', isOutdoor: true, isVirtual: false),
        CampusGraphNode(id: 'c', latitude: 10.8402, longitude: 106.81, building: 'OUTDOOR', floorOrder: 0, type: 'NODE', isOutdoor: true, isVirtual: false),
      ],
      const [CampusGraphEdge(a: 'a', b: 'b', lengthMeters: 11.1, cost: 11.1)],
    );
    expect(g.shortestPath('a', 'c'), isNull);
    expect(g.shortestPath('a', 'b')!.nodeIds, ['a', 'b']);
    expect(g.shortestPath('a', 'a')!.nodeIds, ['a']);
  });

  test('hoà điểm: nút vào tập mở trước được chọn trước (giống web)', () {
    // a có hai đường cùng chi phí tới d: a-b-d và a-c-d; cạnh a-b đứng trước a-c → web đi qua b
    CampusGraphNode n(String id) =>
        CampusGraphNode(id: id, latitude: 0, longitude: 0, building: 'OUTDOOR', floorOrder: 0, type: 'NODE', isOutdoor: true, isVirtual: false);
    final g1 = CampusGraph.build([n('a'), n('b'), n('c'), n('d')], const [
      CampusGraphEdge(a: 'a', b: 'b', lengthMeters: 1, cost: 1),
      CampusGraphEdge(a: 'a', b: 'c', lengthMeters: 1, cost: 1),
      CampusGraphEdge(a: 'c', b: 'd', lengthMeters: 1, cost: 1),
      CampusGraphEdge(a: 'b', b: 'd', lengthMeters: 1, cost: 1),
    ]);
    expect(g1.shortestPath('a', 'd')!.nodeIds, ['a', 'b', 'd']);
    final g2 = CampusGraph.build([n('a'), n('b'), n('c'), n('d')], const [
      CampusGraphEdge(a: 'a', b: 'c', lengthMeters: 1, cost: 1),
      CampusGraphEdge(a: 'a', b: 'b', lengthMeters: 1, cost: 1),
      CampusGraphEdge(a: 'c', b: 'd', lengthMeters: 1, cost: 1),
      CampusGraphEdge(a: 'b', b: 'd', lengthMeters: 1, cost: 1),
    ]);
    expect(g2.shortestPath('a', 'd')!.nodeIds, ['a', 'c', 'd']);
  });
}
