import 'package:flutter/material.dart';

import '../../../core/constants/app_colors.dart';
import '../data/campus_map_style.dart';

/// Chiều cao dải ghi công (không tính lề an toàn đáy màn hình). Các nút / thẻ nổi đặt cao hơn dải này.
const double osmAttributionBarHeight = 18;

/// Dải chữ "© OpenStreetMap contributors" luôn hiện sát đáy bản đồ.
/// Trên Android, attribution của MapLibre chỉ là nút (i) — phải bấm mới thấy chữ — nên cần dòng này để ghi công
/// luôn đọc được theo yêu cầu giấy phép tile OSM.
class OsmAttributionBar extends StatelessWidget {
  const OsmAttributionBar({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      key: const ValueKey('osm-attribution-bar'),
      color: AppColors.crd(context).withAlpha(215),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: osmAttributionBarHeight,
          child: Center(
            child: Text(
              osmAttributionText,
              style: TextStyle(fontSize: 10.5, color: AppColors.txtSecondary(context)),
            ),
          ),
        ),
      ),
    );
  }
}
