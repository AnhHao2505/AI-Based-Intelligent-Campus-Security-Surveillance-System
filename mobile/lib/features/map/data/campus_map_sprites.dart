import 'dart:typed_data';
import 'dart:ui' as ui;

import 'campus_map_overlays.dart';

// Ảnh vẽ bằng Canvas cho lớp symbol của MapLibre (P-MAP-3): biểu tượng cầu thang / thang máy. Mọi ảnh vẽ ở @2x, đăng ký bằng addImage(..., pixelRatio: 2).

/// Tỉ lệ điểm ảnh của mọi ảnh trong file này (web: map.addImage(id, data, { pixelRatio: 2 })).
const double campusSpritePixelRatio = 2;

/// Ảnh đã vẽ: PNG + kích thước điểm ảnh thật (đã nhân [campusSpritePixelRatio]).
class CampusSprite {
  final Uint8List png;
  final int width;
  final int height;

  const CampusSprite({required this.png, required this.width, required this.height});
}

ui.Color _hex(int rgb, [double alpha = 1]) => ui.Color.fromARGB((alpha * 255).round(), (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);

Future<CampusSprite> _encode(ui.PictureRecorder recorder, int width, int height) async {
  final picture = recorder.endRecording();
  final image = await picture.toImage(width, height);
  picture.dispose();
  try {
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    if (data == null) throw StateError('Không mã hoá được ảnh bản đồ');
    return CampusSprite(png: data.buffer.asUint8List(), width: width, height: height);
  } finally {
    image.dispose();
  }
}

/// Bóng giống `ctx.shadowBlur` của canvas web: canvas mờ theo sigma = blur / 2.
void _drawShadow(ui.Canvas canvas, ui.Path path, ui.Color color, double blur, double offsetY) {
  canvas.drawPath(
    path.shift(ui.Offset(0, offsetY)),
    ui.Paint()
      ..color = color
      ..maskFilter = ui.MaskFilter.blur(ui.BlurStyle.normal, blur / 2),
  );
}

// ------------------------------------------------------------------ cầu thang / thang máy

/// Kích thước icon cầu thang / thang máy (điểm ảnh, đã là @2x) — web createBadgeCanvas(64).
const int portalIconSize = 64;

ui.Path _roundedRect(double x, double y, double w, double h, double r) =>
    ui.Path()..addRRect(ui.RRect.fromLTRBR(x, y, x + w, y + h, ui.Radius.circular(r)));

/// Huy hiệu: viền trắng có bóng + ô màu [bg] bên trong (web renderPortalBadge, mapPortalIcons.js:24-53).
Future<CampusSprite> _portalBadge(ui.Color bg, void Function(ui.Canvas canvas) drawGlyph) {
  final recorder = ui.PictureRecorder();
  final canvas = ui.Canvas(recorder);
  final frame = _roundedRect(8, 8, 48, 48, 14);
  _drawShadow(canvas, frame, _hex(0x0F172A, 0.45), 6, 2);
  canvas.drawPath(frame, ui.Paint()..color = _hex(0xFFFFFF));
  canvas.drawPath(_roundedRect(11.5, 11.5, 41, 41, 11), ui.Paint()..color = bg);
  drawGlyph(canvas);
  return _encode(recorder, portalIconSize, portalIconSize);
}

ui.Paint _whiteStroke(double width) => ui.Paint()
  ..color = _hex(0xFFFFFF)
  ..style = ui.PaintingStyle.stroke
  ..strokeWidth = width
  ..strokeCap = ui.StrokeCap.round
  ..strokeJoin = ui.StrokeJoin.round;

/// Cầu thang: nền tím #9333ea, bậc thang trắng + mũi tên chéo (web renderStairsIconData, mapPortalIcons.js:55-83).
Future<CampusSprite> renderStairsIcon() => _portalBadge(_hex(0x9333EA), (canvas) {
      canvas.drawPath(
        ui.Path()
          ..moveTo(19, 43)
          ..lineTo(26, 43)
          ..lineTo(26, 36)
          ..lineTo(33, 36)
          ..lineTo(33, 29)
          ..lineTo(40, 29)
          ..lineTo(40, 22)
          ..lineTo(45, 22),
        _whiteStroke(3.4),
      );
      canvas.drawPath(
        ui.Path()
          ..moveTo(19, 30)
          ..lineTo(27, 22)
          ..moveTo(22.5, 22)
          ..lineTo(27, 22)
          ..lineTo(27, 26.5),
        _whiteStroke(2.6),
      );
    });

/// Thang máy: nền xanh #0284c7, hai cánh cửa + tam giác lên / xuống (web renderElevatorIconData, mapPortalIcons.js:85-112).
Future<CampusSprite> renderElevatorIcon() => _portalBadge(_hex(0x0284C7), (canvas) {
      final white = ui.Paint()..color = _hex(0xFFFFFF);
      canvas.drawPath(_roundedRect(17, 19, 9.2, 26, 2), white);
      canvas.drawPath(_roundedRect(28, 19, 9.2, 26, 2), white);
      canvas.drawPath(
        ui.Path()
          ..moveTo(45.2, 20.5)
          ..lineTo(49.4, 29.5)
          ..lineTo(41, 29.5)
          ..close(),
        white,
      );
      canvas.drawPath(
        ui.Path()
          ..moveTo(41, 34.5)
          ..lineTo(49.4, 34.5)
          ..lineTo(45.2, 43.5)
          ..close(),
        white,
      );
    });

/// Vẽ icon theo id ảnh ([portalStairsImageId] / [portalElevatorImageId]); id khác → null.
Future<CampusSprite?> renderPortalIcon(String id) {
  switch (id) {
    case portalStairsImageId:
      return renderStairsIcon();
    case portalElevatorImageId:
      return renderElevatorIcon();
    default:
      return Future.value(null);
  }
}
