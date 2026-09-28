import 'package:flutter/material.dart';
import 'package:latlong2/latlong.dart';
import '../../../core/constants/app_colors.dart';
import '../models/area_pin_model.dart';
import '../services/routing_service.dart';

class AreaDetailCard extends StatelessWidget {
  final AreaPinModel area;
  final LatLng? guardLocation;
  final RouteResult? currentRoute;
  final bool isLoadingRoute;
  final VoidCallback onNavigateTap;
  final VoidCallback onCancelRoute;
  final VoidCallback onClose;

  const AreaDetailCard({
    super.key,
    required this.area,
    this.guardLocation,
    this.currentRoute,
    required this.isLoadingRoute,
    required this.onNavigateTap,
    required this.onCancelRoute,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    String directDistanceStr = '';
    if (guardLocation != null) {
      const dist = Distance();
      final meters = dist.as(LengthUnit.Meter, guardLocation!, area.latLng);
      directDistanceStr = meters >= 1000
          ? '${(meters / 1000).toStringAsFixed(1)} km'
          : '${meters.round()} m';
    }

    final hasActiveRoute = currentRoute != null;

    return Container(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.crdBorder(context)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(50),
            blurRadius: 18,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header: Area name, Level badge, close button
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: area.levelColor.withAlpha(30),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: area.levelColor.withAlpha(80)),
                ),
                child: Icon(area.levelIcon, color: area.levelColor, size: 22),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      area.name,
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                        color: AppColors.txtPrimary(context),
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 3),
                    Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      spacing: 8,
                      runSpacing: 4,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(
                            color: area.levelColor.withAlpha(25),
                            borderRadius: BorderRadius.circular(6),
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
                        if (directDistanceStr.isNotEmpty)
                          Text(
                            '• Cách bạn $directDistanceStr',
                            style: TextStyle(
                              fontSize: 11,
                              color: AppColors.txtSecondary(context),
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
              IconButton(
                icon: const Icon(Icons.close, size: 20),
                color: AppColors.txtMuted(context),
                onPressed: onClose,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(),
              ),
            ],
          ),

          const SizedBox(height: 12),

          // Subtitle info: Building & Floor
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            decoration: BoxDecoration(
              color: AppColors.surf(context),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Row(
              children: [
                Icon(Icons.apartment_rounded, size: 16, color: AppColors.txtSecondary(context)),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    area.locationSubtitle,
                    style: TextStyle(
                      fontSize: 12,
                      color: AppColors.txtSecondary(context),
                    ),
                  ),
                ),
                Text(
                  '${area.centerLatitude.toStringAsFixed(5)}, ${area.centerLongitude.toStringAsFixed(5)}',
                  style: TextStyle(
                    fontSize: 10,
                    fontFamily: 'monospace',
                    color: AppColors.txtMuted(context),
                  ),
                ),
              ],
            ),
          ),

          // Route info if active
          if (hasActiveRoute) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.primaryLight.withAlpha(20),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.primaryLight.withAlpha(80)),
              ),
              child: Row(
                children: [
                  const Icon(Icons.directions_walk_rounded, color: AppColors.primaryLight, size: 22),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Lộ trình: ${currentRoute!.formattedDistance} • ${currentRoute!.formattedDuration}',
                          style: const TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.bold,
                            color: AppColors.primaryLight,
                          ),
                        ),
                        if (currentRoute!.isFallbackDirect)
                          Text(
                            'Ước tính đường thẳng (OSRM không khả dụng)',
                            style: TextStyle(
                              fontSize: 10,
                              color: AppColors.txtMuted(context),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],

          const SizedBox(height: 14),

          // Action Buttons
          Row(
            children: [
              if (hasActiveRoute)
                Expanded(
                  child: OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      side: const BorderSide(color: AppColors.danger),
                      foregroundColor: AppColors.danger,
                      minimumSize: const Size(0, 44),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    onPressed: onCancelRoute,
                    icon: const Icon(Icons.cancel_outlined, size: 18),
                    label: const Text('Hủy dẫn đường'),
                  ),
                )
              else
                Expanded(
                  child: ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppColors.primary,
                      foregroundColor: Colors.white,
                      minimumSize: const Size(0, 44),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                      elevation: 2,
                    ),
                    onPressed: isLoadingRoute ? null : onNavigateTap,
                    icon: isLoadingRoute
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : const Icon(Icons.navigation_rounded, size: 18),
                    label: Text(isLoadingRoute ? 'Đang tìm đường...' : 'Dẫn đường đến đây'),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
