import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/incident_model.dart';
import '../providers/incident_provider.dart';

class IncidentAlertBanner extends StatelessWidget {
  final Function(IncidentModel) onClaim;
  final VoidCallback? onTapBadge;

  const IncidentAlertBanner({
    super.key,
    required this.onClaim,
    this.onTapBadge,
  });

  @override
  Widget build(BuildContext context) {
    return Consumer<IncidentProvider>(
      builder: (context, provider, child) {
        final alert = provider.currentAlert;
        if (alert == null) return const SizedBox.shrink();

        // Nếu guard đang bận xử lý 1 sự cố khác -> Hiện mini-badge thu nhỏ nổi trên đỉnh
        if (provider.isHandlingIncident) {
          return _buildMiniFloatingBadge(context, provider);
        }

        // Đầy đủ: Banner cảnh báo xếp chồng với nút thao tác
        return _buildFullAlertBanner(context, provider, alert);
      },
    );
  }

  Widget _buildMiniFloatingBadge(BuildContext context, IncidentProvider provider) {
    final topAlert = provider.currentAlert;
    final topTitle = topAlert != null ? topAlert.eventTypeDisplay : 'Sự cố an ninh mới';
    final topCamera = topAlert?.cameraCode ?? '';
    final moreCount = provider.pendingCount - 1;
    final moreText = moreCount > 0 ? ' (+$moreCount)' : '';

    return Positioned(
      top: MediaQuery.of(context).padding.top + 105,
      left: 16,
      right: 16,
      child: Center(
        child: Material(
          elevation: 6,
          borderRadius: BorderRadius.circular(24),
          color: const Color(0xFF0F172A),
          child: InkWell(
            onTap: onTapBadge,
            borderRadius: BorderRadius.circular(24),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(24),
                border: Border.all(color: const Color(0xFFEF4444), width: 1.5),
                boxShadow: [
                  BoxShadow(
                    color: const Color(0xFFEF4444).withAlpha(60),
                    blurRadius: 10,
                    offset: const Offset(0, 2),
                  ),
                ],
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 8,
                    height: 8,
                    decoration: const BoxDecoration(
                      shape: BoxShape.circle,
                      color: Color(0xFFEF4444),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Flexible(
                    child: Text(
                      '$topTitle • $topCamera$moreText',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 6),
                  const Icon(Icons.list_alt_rounded, size: 16, color: Color(0xFF38BDF8)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFullAlertBanner(
    BuildContext context,
    IncidentProvider provider,
    IncidentModel alert,
  ) {
    final severityColor = _getSeverityColor(alert.severityWeight);
    final count = provider.pendingCount;

    return Positioned(
      top: MediaQuery.of(context).padding.top + 8,
      left: 14,
      right: 14,
      child: Dismissible(
        key: Key('alert_${alert.id}'),
        direction: DismissDirection.horizontal,
        onDismissed: (_) {
          provider.dismissCurrentAlert();
        },
        child: Material(
          elevation: 10,
          borderRadius: BorderRadius.circular(16),
          color: const Color(0xFF0F172A),
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: severityColor.withAlpha(230), width: 2),
              boxShadow: [
                BoxShadow(
                  color: severityColor.withAlpha(64),
                  blurRadius: 16,
                  spreadRadius: 2,
                ),
              ],
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Header: Severity badge + Multi-incident Counter
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: severityColor.withAlpha(51),
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: severityColor, width: 1),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.warning_amber_rounded, size: 14, color: severityColor),
                          const SizedBox(width: 4),
                          Text(
                            alert.severityLabel,
                            style: TextStyle(
                              color: severityColor,
                              fontSize: 11,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 0.5,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const Spacer(),
                    if (count > 1)
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                        decoration: BoxDecoration(
                          color: const Color(0xFF334155),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(Icons.notifications_active, size: 13, color: Colors.amberAccent),
                            const SizedBox(width: 4),
                            Text(
                              '$count sự cố',
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 11,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 8),

                // Main Info: Event Type & Location
                Text(
                  alert.eventTypeDisplay.toUpperCase(),
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Row(
                  children: [
                    const Icon(Icons.videocam, size: 15, color: Colors.white70),
                    const SizedBox(width: 4),
                    Text(
                      alert.cameraCode,
                      style: const TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.w500),
                    ),
                    if (alert.areaName != null && alert.areaName!.isNotEmpty) ...[
                      const Text(' • ', style: TextStyle(color: Colors.white38)),
                      Expanded(
                        child: Text(
                          alert.areaName!,
                          style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ],
                ),
                if (alert.resolutionNotes != null && alert.resolutionNotes!.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    alert.resolutionNotes!,
                    style: const TextStyle(color: Colors.white60, fontSize: 12),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
                const SizedBox(height: 12),

                // Action Buttons
                Row(
                  children: [
                    // Bỏ qua (đẩy xuống cuối hàng đợi)
                    Expanded(
                      flex: 2,
                      child: OutlinedButton(
                        onPressed: provider.isClaimLoading
                            ? null
                            : () => provider.dismissCurrentAlert(),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: Colors.white70,
                          side: const BorderSide(color: Color(0xFF475569)),
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8),
                          ),
                        ),
                        child: const Text('Bỏ qua', style: TextStyle(fontSize: 13)),
                      ),
                    ),
                    const SizedBox(width: 10),

                    // Tiếp nhận & Dẫn đường
                    Expanded(
                      flex: 3,
                      child: ElevatedButton.icon(
                        onPressed: provider.isClaimLoading
                            ? null
                            : () => onClaim(alert),
                        icon: provider.isClaimLoading
                            ? const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                              )
                            : const Icon(Icons.navigation, size: 16),
                        label: Text(
                          provider.isClaimLoading ? 'Đang nhận...' : 'Tiếp nhận & Đi',
                          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold),
                        ),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: severityColor,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          elevation: 2,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Color _getSeverityColor(int weight) {
    switch (weight) {
      case 3:
        return const Color(0xFFEF4444); // Đỏ
      case 2:
        return const Color(0xFFF59E0B); // Cam
      case 1:
      default:
        return const Color(0xFF3B82F6); // Xanh dương
    }
  }
}
