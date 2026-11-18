import 'package:flutter/material.dart';

import '../../../core/constants/app_colors.dart';
import '../models/area_pin_model.dart';

/// Các mục chú giải: mã cấp khu + nhãn, đúng thứ tự và nhãn `badgeLabel` của web
/// (frontend/src/components/area/CampusMapView.jsx, khối campus-map-legend; frontend/src/utils/areaHelpers.js).
/// Không có mục "Phòng trống": app không tô phòng trống. Màu chấm lấy từ [AreaPinModel.levelColorOf].
const List<({String level, String label})> campusMapLegendEntries = [
  (level: 'PUBLIC', label: 'Công khai'),
  (level: 'CONFIDENTIAL', label: 'Bảo mật'),
  (level: 'HIGHLY_CONFIDENTIAL', label: 'Bảo mật cao'),
];

/// Kích thước theo `.campus-map-legend` của web (frontend/src/styles/CampusMapView.css).
const double campusMapLegendDotSize = 9;
const double campusMapLegendFontSize = 11.5;
const double campusMapLegendItemSpacing = 12;
const double campusMapLegendDotGap = 6;
const double campusMapLegendRadius = 4;

/// Nền tối của web: rgba(15, 23, 42, 0.92).
const Color _darkBackground = Color(0xEB0F172A);

/// Chú giải màu cấp khu nổi trên bản đồ, giống web: nền trắng 92 % (tối: slate-900 92 %), viền, bo 4, bóng nhẹ,
/// chấm tròn 9 + nhãn chữ 11.5, các mục xếp ngang cách nhau 12 (hẹp quá thì xuống dòng).
class CampusMapLegend extends StatelessWidget {
  const CampusMapLegend({super.key});

  @override
  Widget build(BuildContext context) {
    final isDark = AppColors.isDark(context);
    final textColor = AppColors.txtSecondary(context);
    return Container(
      key: const ValueKey('campus-map-legend'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: isDark ? _darkBackground : Colors.white.withAlpha(235),
        borderRadius: BorderRadius.circular(campusMapLegendRadius),
        border: Border.all(color: isDark ? Colors.white.withAlpha(31) : AppColors.crdBorder(context)),
        boxShadow: [BoxShadow(color: Colors.black.withAlpha(20), blurRadius: 10, offset: const Offset(0, 2))],
      ),
      child: Wrap(
        spacing: campusMapLegendItemSpacing,
        runSpacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          for (final e in campusMapLegendEntries)
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  key: ValueKey('campus-map-legend-dot-${e.level}'),
                  width: campusMapLegendDotSize,
                  height: campusMapLegendDotSize,
                  decoration: BoxDecoration(color: AreaPinModel.levelColorOf(e.level), shape: BoxShape.circle),
                ),
                const SizedBox(width: campusMapLegendDotGap),
                Text(e.label, style: TextStyle(fontSize: campusMapLegendFontSize, color: textColor)),
              ],
            ),
        ],
      ),
    );
  }
}
