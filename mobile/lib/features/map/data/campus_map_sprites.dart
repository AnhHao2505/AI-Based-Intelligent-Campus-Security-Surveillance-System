import 'dart:typed_data';
import 'dart:ui' as ui;

import '../models/area_pin_model.dart';
import 'campus_map_overlays.dart';

// Ảnh vẽ bằng Canvas cho lớp symbol của MapLibre (P-MAP-3): biểu tượng cầu thang / thang máy và nhãn tên phòng.
// Style bản đồ không có "glyphs" (campus_map_style.dart) nên không dùng text-field được; chữ được vẽ sẵn thành ảnh,
// giống web. Mọi ảnh vẽ ở @2x, đăng ký bằng addImage(..., pixelRatio: 2).

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

// ------------------------------------------------------------------ nhãn tên phòng

/// Độ dài tối đa tên trên nhãn (web DEFAULT_MAX_LABEL_LENGTH, mapRoomLabelSprites.js:8).
const int roomLabelMaxLength = 24;

/// Màu chấm cấp khu trên nhãn: cùng nguồn với ghim khu và chú giải ([AreaPinModel.levelColorOf]), không giữ bảng màu
/// riêng. Cấp trống → PUBLIC ([roomLabelLevelCode]); cấp lạ → xám mặc định của levelColorOf.
ui.Color roomLabelAccentColor(String? levelCode) => AreaPinModel.levelColorOf(roomLabelLevelCode(levelCode));

/// Lề trong suốt quanh viên nhãn để chừa chỗ cho bóng (đơn vị logic = điểm ảnh / [campusSpritePixelRatio]).
const double roomLabelShadowPad = 3;

/// Zoom nhỏ nhất hiện nhãn tên phòng (web CampusMapView.jsx, minzoom 16.4 của lớp nhãn).
const double roomLabelMinZoom = 16.4;

/// Cỡ nhãn theo zoom: (zoom, icon-size), nội suy tuyến tính (web CampusMapView.jsx, lớp campus-3d-room-labels-layer).
const List<(double, double)> roomLabelIconSizeStops = [(16.4, 0.78), (18.2, 0.94), (19.8, 1.05)];

/// Bán kính lớn nhất của ghim khu (ghim khu đang chọn, lớp campus-pins-layer của guard_map_screen.dart), dp.
const double areaPinMaxRadius = 13;

/// Viền trắng dày nhất của ghim khu (ghim khu đang chọn), dp. MapLibre vẽ viền NGOÀI bán kính, nên mép ghim nhìn thấy
/// cách tâm [areaPinMaxRadius] + [areaPinMaxStrokeWidth].
const double areaPinMaxStrokeWidth = 3;

/// Quầng đứng yên dưới ghim sự cố (lớp campus-incident-halo-layer của guard_map_screen.dart): phân biệt ghim sự cố
/// với ghim khu đang chọn cùng hình. Không nhấp nháy. Màu = AppColors.danger.
const String incidentHaloColor = '#ef4444';

/// Bán kính quầng, dp: gấp đôi ghim khu đang chọn.
const double incidentHaloRadius = areaPinMaxRadius * 2;
const double incidentHaloOpacity = 0.25;

/// Viền quầng (vẽ ngoài bán kính như mọi circle của MapLibre), dp.
const double incidentHaloStrokeWidth = 2;
const double incidentHaloStrokeOpacity = 0.6;

/// Khoảng hở giữa mép ghim nhìn thấy (gồm viền) và cạnh dưới viên nhãn, dp.
const double roomLabelPinGap = 4;

/// Neo nhãn ở cạnh dưới ảnh (điểm nhãn nằm dưới nhãn, ghim không che chữ).
const String roomLabelIconAnchor = 'bottom';

/// Nhãn đặt TRÊN ghim: cạnh dưới viên nhãn cách điểm neo
/// [areaPinMaxRadius] + [areaPinMaxStrokeWidth] + [roomLabelPinGap] (= 20 dp), tức hở [roomLabelPinGap] với mép mọi ghim.
/// Lý do: phòng khớp khu khi tâm khu nằm trong phòng, nên ghim (tâm khu) và điểm nhãn (điểm trong phòng) thường gần
/// nhau; nhãn ở giữa thì ghim (lớp trên cùng) che mất chữ (P-MAP-3, ảnh a3).
const double roomLabelLiftAbovePin = areaPinMaxRadius + areaPinMaxStrokeWidth + roomLabelPinGap;

/// `icon-offset` (đơn vị ảnh ở icon-size 1) của nhãn neo `bottom` khi icon-size = [iconSize]: MapLibre nhân offset với
/// icon-size, và cạnh dưới ảnh còn lề bóng [roomLabelShadowPad] (cũng nhân icon-size) dưới viên nhãn, nên
/// (offset + lề) × iconSize = [roomLabelLiftAbovePin] → offset = lift / iconSize − lề. Số âm = dịch lên.
List<double> roomLabelIconOffset(double iconSize) => [0, -(roomLabelLiftAbovePin / iconSize - roomLabelShadowPad)];

/// Biểu thức `icon-size` của lớp nhãn (theo [roomLabelIconSizeStops]).
List<Object> roomLabelIconSizeExpression() => [
      'interpolate',
      ['linear'],
      ['zoom'],
      for (final (zoom, size) in roomLabelIconSizeStops) ...[zoom, size],
    ];

/// Biểu thức `icon-offset` của lớp nhãn: đúng tại mỗi mốc của [roomLabelIconSizeStops], nội suy giữa các mốc
/// (sai lệch giữa hai mốc dưới 1 %, vd 20.18 thay vì 20 dp ở zoom 17.3).
List<Object> roomLabelIconOffsetExpression() => [
      'interpolate',
      ['linear'],
      ['zoom'],
      for (final (zoom, size) in roomLabelIconSizeStops) ...[
        zoom,
        ['literal', roomLabelIconOffset(size)],
      ],
    ];

/// Cắt tên dài, thêm một dấu "…" ở cuối (web truncateLabelText, mapRoomLabelSprites.js:19-26).
String truncateRoomLabel(String text, [int maxLength = roomLabelMaxLength]) {
  final raw = text.trim();
  if (raw.isEmpty || maxLength <= 0) return '';
  final chars = raw.runes.toList();
  if (chars.length <= maxLength) return raw;
  if (maxLength == 1) return '…';
  return '${String.fromCharCodes(chars.take(maxLength - 1))}…';
}

/// Viên thuốc bo tròn bằng đường cong bậc hai, như drawRoundedPill của web (mapRoomLabelSprites.js:79-92).
ui.Path _pill(double x, double y, double w, double h, double r) {
  final radius = [r, w / 2, h / 2].reduce((a, b) => a < b ? a : b);
  return ui.Path()
    ..moveTo(x + radius, y)
    ..lineTo(x + w - radius, y)
    ..quadraticBezierTo(x + w, y, x + w, y + radius)
    ..lineTo(x + w, y + h - radius)
    ..quadraticBezierTo(x + w, y + h, x + w - radius, y + h)
    ..lineTo(x + radius, y + h)
    ..quadraticBezierTo(x, y + h, x, y + h - radius)
    ..lineTo(x, y + radius)
    ..quadraticBezierTo(x, y, x + radius, y)
    ..close();
}

/// Nhãn tên phòng @2x (web createRoomLabelSpriteImageData, mapRoomLabelSprites.js:97-188): viên thuốc nền trắng
/// (đang chọn: nền tối, viền xanh, bóng xanh), chấm màu theo cấp khu, tên chữ 11 px đậm 600. Tên rỗng → null.
Future<CampusSprite?> renderRoomLabel({required String name, required String levelCode, required bool selected}) async {
  final label = truncateRoomLabel(name);
  if (label.isEmpty) return null;

  const scale = campusSpritePixelRatio;
  const dotRadius = 3.0, dotGap = 6.0, padX = 9.0, shadowPad = roomLabelShadowPad, pillHeight = 22.0;

  final builder = ui.ParagraphBuilder(ui.ParagraphStyle(fontSize: 11, fontWeight: ui.FontWeight.w600, maxLines: 1))
    ..pushStyle(ui.TextStyle(color: selected ? _hex(0xFFFFFF) : _hex(0x0F172A)))
    ..addText(label);
  final paragraph = builder.build()..layout(const ui.ParagraphConstraints(width: double.infinity));
  final textWidth = paragraph.maxIntrinsicWidth.ceilToDouble();

  final pillWidth = [36.0, padX + dotRadius * 2 + dotGap + textWidth + padX].reduce((a, b) => a > b ? a : b);
  final logicalWidth = pillWidth + shadowPad * 2;
  const logicalHeight = pillHeight + shadowPad * 2;
  final width = (logicalWidth * scale).round();
  final height = (logicalHeight * scale).round();

  final recorder = ui.PictureRecorder();
  final canvas = ui.Canvas(recorder)..scale(scale, scale);

  final body = _pill(shadowPad, shadowPad, pillWidth, pillHeight, pillHeight / 2);
  _drawShadow(canvas, body, selected ? _hex(0x2563EB, 0.35) : _hex(0x0F172A, 0.18), 4, 1.5);
  canvas.drawPath(body, ui.Paint()..color = selected ? _hex(0x0F172A) : _hex(0xFFFFFF, 0.96));

  canvas.drawPath(
    _pill(shadowPad + 0.5, shadowPad + 0.5, pillWidth - 1, pillHeight - 1, (pillHeight - 1) / 2),
    ui.Paint()
      ..style = ui.PaintingStyle.stroke
      ..strokeWidth = selected ? 1.5 : 1
      ..color = selected ? _hex(0x38BDF8) : _hex(0x0F172A, 0.16),
  );

  const dotCx = shadowPad + padX + dotRadius;
  const centerY = shadowPad + pillHeight / 2;
  canvas.drawCircle(const ui.Offset(dotCx, centerY), dotRadius, ui.Paint()..color = roomLabelAccentColor(levelCode));

  // textBaseline = "middle" của web: giữa dòng chữ nằm ở centerY + 0.5
  canvas.drawParagraph(paragraph, ui.Offset(dotCx + dotRadius + dotGap, centerY + 0.5 - paragraph.height / 2));
  paragraph.dispose();

  return _encode(recorder, width, height);
}

/// Vẽ nhãn theo id ảnh (roomLabelImageId); không phải id nhãn → null.
Future<CampusSprite?> renderRoomLabelForImageId(String id) {
  final parsed = parseRoomLabelImageId(id);
  if (parsed == null) return Future.value(null);
  return renderRoomLabel(name: parsed.name, levelCode: parsed.levelCode, selected: parsed.selected);
}
