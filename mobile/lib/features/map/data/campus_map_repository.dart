import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'campus_map_data.dart';

/// Hàm phân tích asset; mặc định chạy trong isolate riêng (compute) để không giật giao diện.
typedef CampusAssetParser = Future<CampusMapData> Function(Map<String, String> files);

Future<CampusMapData> _parseInIsolate(Map<String, String> files) => compute(parseCampusAssets, files);

/// Nạp dữ liệu bản đồ khuôn viên từ mobile/assets/campus/ (sinh bởi `npm run export:campus-map`).
class CampusMapRepository {
  static const String basePath = 'assets/campus';

  final AssetBundle _bundle;
  final CampusAssetParser _parser;

  CampusMapRepository({AssetBundle? bundle, CampusAssetParser? parser})
      : _bundle = bundle ?? rootBundle,
        _parser = parser ?? _parseInIsolate;

  /// Đọc manifest + mọi GeoJSON khối 3D. Lỗi bất kỳ → [CampusMapLoadException] với câu thân thiện.
  Future<CampusMapData> load() async {
    try {
      final manifestRaw = await _bundle.loadString('$basePath/manifest.json', cache: false);
      final files = <String, String>{'manifest.json': manifestRaw};
      final manifest = jsonDecode(manifestRaw);
      if (manifest is! Map) {
        throw const CampusMapLoadException('manifest.json không đúng định dạng');
      }
      for (final b in (manifest['buildings'] as List? ?? const [])) {
        for (final f in ((b as Map)['floors'] as List? ?? const [])) {
          final rel = (f as Map)['file']?.toString();
          if (rel == null || files.containsKey(rel)) continue;
          files[rel] = await _bundle.loadString('$basePath/$rel', cache: false);
        }
      }
      return await _parser(files);
    } on CampusMapLoadException {
      rethrow;
    } catch (e) {
      throw CampusMapLoadException('Không đọc được dữ liệu bản đồ khuôn viên', e);
    }
  }
}
