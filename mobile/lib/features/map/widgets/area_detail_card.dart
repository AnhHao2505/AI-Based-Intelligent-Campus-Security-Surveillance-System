import 'package:material_ui/material_ui.dart';
import '../../../core/constants/app_colors.dart';
import '../data/campus_route_planner.dart';
import '../models/area_pin_model.dart';

class AreaDetailCard extends StatelessWidget {
  final AreaPinModel area;
  final CampusRoute? currentRoute;
  final bool isLoadingRoute;
  final VoidCallback onNavigateTap;
  final VoidCallback onCancelRoute;
  final VoidCallback onClose;
  final VoidCallback? onFocusDestination;
  final ValueChanged<CampusFloorChange>? onSelectFloorStep;

  const AreaDetailCard({
    super.key,
    required this.area,
    this.currentRoute,
    required this.isLoadingRoute,
    required this.onNavigateTap,
    required this.onCancelRoute,
    required this.onClose,
    this.onFocusDestination,
    this.onSelectFloorStep,
  });

  @override
  Widget build(BuildContext context) {
    final hasActiveRoute = currentRoute != null;

    return Container(
      key: const ValueKey('area-detail-card'),
      margin: const EdgeInsets.fromLTRB(12, 0, 12, 10),
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        borderRadius: BorderRadius.circular(14),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(65),
            blurRadius: 18,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header: Chạm vào tiêu đề khi đang dẫn đường để focus về phòng đích
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Expanded(
                child: InkWell(
                  borderRadius: BorderRadius.circular(8),
                  onTap: hasActiveRoute ? onFocusDestination : null,
                  child: Row(
                    children: [
                      Container(
                        width: 36,
                        height: 36,
                        decoration: BoxDecoration(
                          color: area.levelColor.withAlpha(28),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Icon(
                          area.levelIcon,
                          color: area.levelColor,
                          size: 20,
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              area.name,
                              style: TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w700,
                                color: AppColors.txtPrimary(context),
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            const SizedBox(height: 2),
                            Row(
                              children: [
                                if (!hasActiveRoute) ...[
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 6,
                                      vertical: 1.5,
                                    ),
                                    decoration: BoxDecoration(
                                      color: area.levelColor.withAlpha(24),
                                      borderRadius: BorderRadius.circular(4),
                                    ),
                                    child: Text(
                                      area.levelDisplayName,
                                      style: TextStyle(
                                        fontSize: 10,
                                        fontWeight: FontWeight.w600,
                                        color: area.levelColor,
                                      ),
                                    ),
                                  ),
                                  const SizedBox(width: 6),
                                ],
                                Expanded(
                                  child: Text(
                                    area.locationSubtitle,
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: AppColors.txtSecondary(context),
                                    ),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(width: 4),
              IconButton(
                icon: const Icon(Icons.close_rounded, size: 20),
                color: AppColors.txtMuted(context),
                tooltip: 'Đóng',
                onPressed: onClose,
                padding: const EdgeInsets.all(6),
                constraints: const BoxConstraints(),
              ),
            ],
          ),

          if (hasActiveRoute) ...[
            const SizedBox(height: 10),
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Row(
                        children: [
                          const Icon(
                            Icons.directions_walk_rounded,
                            color: Color(0xFF0EA5E9),
                            size: 18,
                          ),
                          const SizedBox(width: 6),
                          Flexible(
                            child: Text(
                              '${currentRoute!.formattedDuration} · ${currentRoute!.formattedDistance}',
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.w700,
                                fontFeatures: const [
                                  FontFeature.tabularFigures(),
                                ],
                                color: AppColors.txtPrimary(context),
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                      if (currentRoute!.startMode ==
                              CampusRouteStartMode.selectedFloor &&
                          currentRoute!.startBuilding != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(
                            'Từ ${campusFloorLabel(currentRoute!.startFloorOrder)} (${currentRoute!.startBuilding})',
                            style: TextStyle(
                              fontSize: 11.5,
                              color: AppColors.txtSecondary(context),
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      if (currentRoute!.isDirectFallback)
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(
                            CampusRoute.directFallbackMessage,
                            style: TextStyle(
                              fontSize: 11.5,
                              color: AppColors.txtMuted(context),
                            ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                OutlinedButton(
                  style: OutlinedButton.styleFrom(
                    side: const BorderSide(color: AppColors.danger),
                    foregroundColor: AppColors.danger,
                    minimumSize: const Size(68, 36),
                    padding: const EdgeInsets.symmetric(horizontal: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                  onPressed: onCancelRoute,
                  child: const Text(
                    'Hủy',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
              ],
            ),
            if (currentRoute!.floorChanges.isNotEmpty) ...[
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                runSpacing: 4,
                children: [
                  for (final c in currentRoute!.floorChanges)
                    InkWell(
                      borderRadius: BorderRadius.circular(6),
                      onTap: onSelectFloorStep == null
                          ? null
                          : () => onSelectFloorStep!(c),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 4,
                        ),
                        decoration: BoxDecoration(
                          color: AppColors.warning.withAlpha(24),
                          borderRadius: BorderRadius.circular(6),
                          border: Border.all(
                            color: AppColors.warning.withAlpha(85),
                          ),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              c.via == 'ELEVATOR'
                                  ? Icons.elevator_rounded
                                  : Icons.stairs_rounded,
                              size: 13,
                              color: AppColors.warning,
                            ),
                            const SizedBox(width: 5),
                            Text(
                              '${c.viaLabel}: ${c.floorRangeLabel}',
                              style: const TextStyle(
                                fontSize: 11.5,
                                fontWeight: FontWeight.w600,
                                color: AppColors.warning,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ],
          ] else ...[
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppColors.primary,
                  foregroundColor: Colors.white,
                  minimumSize: const Size(0, 42),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                  elevation: 0,
                ),
                onPressed: isLoadingRoute ? null : onNavigateTap,
                icon: isLoadingRoute
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.navigation_rounded, size: 17),
                label: Text(
                  isLoadingRoute ? 'Đang tìm đường...' : 'Dẫn đường',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
