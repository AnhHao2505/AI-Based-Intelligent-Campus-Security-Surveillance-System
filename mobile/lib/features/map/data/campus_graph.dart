import 'dart:math' as math;

/// Phạt đổi tầng của thuật toán tìm đường (mét quy đổi cho mỗi tầng chênh), chép từ web
/// (frontend/src/data/campusIndoor3dData.js, buildOrGetCachedRouteBase): cost cạnh = haversine + |Δtầng| × 18,
/// cùng cho cầu thang và thang máy. Đây là HẰNG CỦA THUẬT TOÁN (để chọn đường ít đổi tầng), không phải tham số
/// nghiệp vụ nên không đưa vào cấu hình hệ thống. graph.json đã tính sẵn phạt này trong `cost`; mobile dùng `cost`
/// nguyên văn và chỉ dùng hằng này để kiểm tra asset (cost − length ≈ |Δtầng| × 18).
const double kFloorChangePenaltyMeters = 18;

/// Bán kính Trái Đất dùng trong haversine — cùng giá trị với web (haversineMeters, campusIndoor3dData.js).
const double _earthRadiusMeters = 6371000;

/// Khoảng cách mặt phẳng (m) giữa hai điểm (lat, lon) — cùng công thức haversine của web.
double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
  double rad(double d) => d * math.pi / 180;
  final dLat = rad(lat2 - lat1);
  final dLon = rad(lon2 - lon1);
  final a = math.pow(math.sin(dLat / 2), 2) +
      math.cos(rad(lat1)) * math.cos(rad(lat2)) * math.pow(math.sin(dLon / 2), 2);
  return 2 * _earthRadiusMeters * math.atan2(math.sqrt(a), math.sqrt(1 - a));
}

/// Một nút của đồ thị đường đi (graph.json → nodes).
class CampusGraphNode {
  final String id;
  final double latitude;
  final double longitude;

  /// Tên toà chuẩn (vd "Tòa Alpha") hoặc "OUTDOOR".
  final String building;
  final int floorOrder;

  /// NODE, DOOR, STAIRS, ELEVATOR, CORRIDOR.
  final String type;
  final bool isOutdoor;

  /// Nút cửa ảo do web sinh ra (`__door_…__`).
  final bool isVirtual;

  const CampusGraphNode({
    required this.id,
    required this.latitude,
    required this.longitude,
    required this.building,
    required this.floorOrder,
    required this.type,
    required this.isOutdoor,
    required this.isVirtual,
  });

  bool get isVerticalAccess => type == 'STAIRS' || type == 'ELEVATOR';
}

/// Một cạnh vô hướng (graph.json → edges).
class CampusGraphEdge {
  final String a;
  final String b;
  final double lengthMeters;
  final double cost;

  const CampusGraphEdge({required this.a, required this.b, required this.lengthMeters, required this.cost});
}

class _Arc {
  final int to;
  final double cost;
  final double lengthMeters;

  const _Arc(this.to, this.cost, this.lengthMeters);
}

/// Kết quả Dijkstra giữa hai nút.
class CampusGraphPath {
  final List<CampusGraphNode> nodes;

  /// Tổng `cost` (đã gồm phạt đổi tầng) — dùng để so với web.
  final double cost;

  /// Tổng chiều dài các cạnh (m, không gồm phạt đổi tầng).
  final double lengthMeters;

  /// Tổng số tầng chênh dọc đường (Σ |Δtầng|), cùng nghĩa floorsClimbed của web.
  final int floorsClimbed;

  const CampusGraphPath({
    required this.nodes,
    required this.cost,
    required this.lengthMeters,
    required this.floorsClimbed,
  });

  List<String> get nodeIds => [for (final n in nodes) n.id];
}

/// Đồ thị đường đi khuôn viên (trong nhà + ngoài trời, nhiều tầng, nhiều toà) nạp từ mobile/assets/campus/graph.json.
class CampusGraph {
  final List<CampusGraphNode> nodes;
  final List<CampusGraphEdge> edges;
  final Map<String, int> _indexById;
  final List<List<_Arc>> _adjacency;

  CampusGraph._(this.nodes, this.edges, this._indexById, this._adjacency);

  /// Dựng đồ thị. Danh sách kề xây theo đúng thứ tự cạnh trong file, mỗi cạnh thêm a→b rồi b→a — giống web
  /// (buildOrGetCachedRouteBase) để Dijkstra hoà điểm chọn cùng một đường.
  factory CampusGraph.build(List<CampusGraphNode> nodes, List<CampusGraphEdge> edges) {
    final index = <String, int>{};
    for (var i = 0; i < nodes.length; i++) {
      index[nodes[i].id] = i;
    }
    final adjacency = List<List<_Arc>>.generate(nodes.length, (_) => <_Arc>[]);
    for (final e in edges) {
      final a = index[e.a];
      final b = index[e.b];
      if (a == null || b == null) {
        throw FormatException('Cạnh ${e.a} – ${e.b} trỏ tới nút không tồn tại');
      }
      adjacency[a].add(_Arc(b, e.cost, e.lengthMeters));
      adjacency[b].add(_Arc(a, e.cost, e.lengthMeters));
    }
    return CampusGraph._(List.unmodifiable(nodes), List.unmodifiable(edges), index, adjacency);
  }

  /// Phân tích nội dung graph.json đã jsonDecode.
  factory CampusGraph.fromJson(Map<String, dynamic> json) {
    final nodes = <CampusGraphNode>[
      for (final raw in (json['nodes'] as List? ?? const []))
        () {
          final n = raw as Map<String, dynamic>;
          return CampusGraphNode(
            id: n['id'].toString(),
            latitude: (n['lat'] as num).toDouble(),
            longitude: (n['lon'] as num).toDouble(),
            building: n['building']?.toString() ?? 'OUTDOOR',
            floorOrder: (n['floorOrder'] as num?)?.toInt() ?? 0,
            type: n['type']?.toString() ?? 'NODE',
            isOutdoor: n['isOutdoor'] == true,
            isVirtual: n['virtual'] == true,
          );
        }(),
    ];
    final edges = <CampusGraphEdge>[
      for (final raw in (json['edges'] as List? ?? const []))
        () {
          final e = raw as Map<String, dynamic>;
          return CampusGraphEdge(
            a: e['a'].toString(),
            b: e['b'].toString(),
            lengthMeters: (e['lengthMeters'] as num).toDouble(),
            cost: (e['cost'] as num).toDouble(),
          );
        }(),
    ];
    return CampusGraph.build(nodes, edges);
  }

  CampusGraphNode? nodeById(String id) {
    final i = _indexById[id];
    return i == null ? null : nodes[i];
  }

  /// Đường ngắn nhất (theo `cost`) từ [fromId] tới [toId]; null khi không có đường hoặc id không tồn tại.
  ///
  /// Hoà điểm giống web (runDijkstraPass): web quét tập mở theo thứ tự chèn và giữ nút có gScore nhỏ nhất với `<`
  /// chặt, nên trong các nút bằng điểm, nút vào tập mở SỚM NHẤT được lấy trước; nút đã trong tập mở được nới lỏng
  /// thì giữ vị trí cũ. Ở đây hàng đợi ưu tiên dùng khoá (điểm, thứ tự lần đầu vào tập mở) cho đúng hành vi đó.
  CampusGraphPath? shortestPath(String fromId, String toId) {
    final s = _indexById[fromId];
    final t = _indexById[toId];
    if (s == null || t == null) return null;

    final n = nodes.length;
    final score = List<double>.filled(n, double.infinity);
    final prev = List<int>.filled(n, -1);
    final prevArc = List<_Arc?>.filled(n, null);
    final seq = List<int>.filled(n, -1);
    final visited = List<bool>.filled(n, false);
    final heap = _MinHeap();
    var nextSeq = 0;

    score[s] = 0;
    seq[s] = nextSeq++;
    heap.push(_HeapItem(0, seq[s], s));

    var reached = false;
    while (heap.isNotEmpty) {
      final item = heap.pop();
      final u = item.node;
      if (visited[u] || item.score != score[u]) continue; // bản ghi cũ
      if (u == t) {
        reached = true;
        break;
      }
      visited[u] = true;
      final best = score[u];
      for (final arc in _adjacency[u]) {
        final v = arc.to;
        if (visited[v]) continue;
        final alt = best + arc.cost;
        if (alt < score[v]) {
          score[v] = alt;
          prev[v] = u;
          prevArc[v] = arc;
          if (seq[v] < 0) seq[v] = nextSeq++;
          heap.push(_HeapItem(alt, seq[v], v));
        }
      }
    }
    if (!reached) return null;

    final path = <CampusGraphNode>[];
    var length = 0.0;
    var floors = 0;
    for (var cur = t; cur != -1; cur = prev[cur]) {
      path.add(nodes[cur]);
      final arc = prevArc[cur];
      if (arc != null) {
        length += arc.lengthMeters;
        floors += (nodes[cur].floorOrder - nodes[prev[cur]].floorOrder).abs();
      }
      if (cur == s) break;
    }
    return CampusGraphPath(
      nodes: path.reversed.toList(growable: false),
      cost: score[t],
      lengthMeters: length,
      floorsClimbed: floors,
    );
  }
}

class _HeapItem {
  final double score;
  final int seq;
  final int node;

  const _HeapItem(this.score, this.seq, this.node);

  bool lessThan(_HeapItem o) => score < o.score || (score == o.score && seq < o.seq);
}

/// Binary min-heap theo (score, seq).
class _MinHeap {
  final List<_HeapItem> _a = [];

  bool get isNotEmpty => _a.isNotEmpty;

  void push(_HeapItem x) {
    _a.add(x);
    var i = _a.length - 1;
    while (i > 0) {
      final p = (i - 1) >> 1;
      if (!_a[i].lessThan(_a[p])) break;
      final tmp = _a[i];
      _a[i] = _a[p];
      _a[p] = tmp;
      i = p;
    }
  }

  _HeapItem pop() {
    final top = _a.first;
    final last = _a.removeLast();
    if (_a.isNotEmpty) {
      _a[0] = last;
      var i = 0;
      while (true) {
        final l = 2 * i + 1;
        final r = l + 1;
        var m = i;
        if (l < _a.length && _a[l].lessThan(_a[m])) m = l;
        if (r < _a.length && _a[r].lessThan(_a[m])) m = r;
        if (m == i) break;
        final tmp = _a[i];
        _a[i] = _a[m];
        _a[m] = tmp;
        i = m;
      }
    }
    return top;
  }
}
