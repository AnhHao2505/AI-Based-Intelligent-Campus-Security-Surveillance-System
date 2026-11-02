import 'dart:async';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/incident_offer_model.dart';
import '../providers/incident_provider.dart';

/// Banner "Bạn được mời xử lý" cho lời mời bước 1 (INCIDENT_OFFER).
///
/// Gắn ở MaterialApp.builder nên hiện trên mọi màn hình. Không có lời mời (backend chưa gửi
/// /topic/guards/{id}/offers) thì không hiện gì. Đếm ngược tới deadline của lời mời; hết hạn hoặc bị huỷ thì provider
/// gỡ lời mời và banner tự ẩn.
class IncidentOfferBanner extends StatefulWidget {
  /// Gọi sau khi tiếp nhận thành công (vd. mở màn danh sách sự cố).
  final void Function(String incidentId)? onClaimed;

  const IncidentOfferBanner({super.key, this.onClaimed});

  @override
  State<IncidentOfferBanner> createState() => _IncidentOfferBannerState();
}

class _IncidentOfferBannerState extends State<IncidentOfferBanner> {
  Timer? _ticker;

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  /// Chỉ chạy đồng hồ 1 giây khi đang có lời mời.
  void _syncTicker(bool hasOffer) {
    if (hasOffer && _ticker == null) {
      _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
        if (mounted) setState(() {});
      });
    } else if (!hasOffer && _ticker != null) {
      _ticker?.cancel();
      _ticker = null;
    }
  }

  void _showSnack(String message, Color color) {
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(content: Text(message), backgroundColor: color, duration: const Duration(seconds: 3)),
    );
  }

  Future<void> _claim(IncidentProvider provider, IncidentOfferModel offer) async {
    final success = await provider.claimIncident(offer.incidentId);
    if (!mounted) return;
    if (success) {
      _showSnack('Tiếp nhận sự cố thành công!', const Color(0xFF10B981));
      widget.onClaimed?.call(offer.incidentId);
    } else if (provider.errorMessage != null) {
      _showSnack(provider.errorMessage!, const Color(0xFFEF4444));
    }
  }

  Future<void> _decline(IncidentProvider provider, IncidentOfferModel offer) async {
    final success = await provider.declineOffer(offer.incidentId);
    if (!mounted) return;
    if (!success && provider.errorMessage != null) {
      _showSnack(provider.errorMessage!, const Color(0xFFEF4444));
    }
  }

  static String _formatRemaining(Duration d) {
    final minutes = d.inMinutes;
    final seconds = d.inSeconds % 60;
    return '$minutes:${seconds.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<IncidentProvider>();
    final offer = provider.currentOffer;
    _syncTicker(offer != null);
    if (offer == null) return const SizedBox.shrink();

    final incident = offer.toIncident();
    final severityColor = _severityColor(incident.severityLevel);
    final remaining = offer.remaining(DateTime.now());
    final moreCount = provider.offers.length - 1;
    final busy = provider.isClaimLoading || provider.isDeclineLoading;

    return Positioned(
      left: 12,
      right: 12,
      bottom: MediaQuery.of(context).padding.bottom + 12,
      child: Material(
        key: ValueKey('offer_${offer.incidentId}'),
        elevation: 12,
        borderRadius: BorderRadius.circular(16),
        color: const Color(0xFF0F172A),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: severityColor, width: 2),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(Icons.campaign_rounded, size: 18, color: severityColor),
                  const SizedBox(width: 6),
                  const Expanded(
                    child: Text(
                      'BẠN ĐƯỢC MỜI XỬ LÝ',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 0.5,
                      ),
                    ),
                  ),
                  if (moreCount > 0)
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: Text(
                        '+$moreCount lời mời',
                        style: const TextStyle(color: Colors.white60, fontSize: 11),
                      ),
                    ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: severityColor.withAlpha(51),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.timer_outlined, size: 13, color: severityColor),
                        const SizedBox(width: 3),
                        Text(
                          _formatRemaining(remaining),
                          style: TextStyle(color: severityColor, fontSize: 12, fontWeight: FontWeight.bold),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                '${incident.severityLabel} · ${incident.eventTypeDisplay}',
                style: const TextStyle(color: Colors.white, fontSize: 15, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              Text(
                [
                  offer.cameraCode,
                  offer.areaName ?? 'Khu vực chưa rõ',
                  if (offer.building != null) offer.building!,
                ].join(' • '),
                style: const TextStyle(color: Colors.white70, fontSize: 12.5),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    flex: 2,
                    child: OutlinedButton(
                      onPressed: busy ? null : () => _decline(provider, offer),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: Colors.white70,
                        side: const BorderSide(color: Color(0xFF475569)),
                        padding: const EdgeInsets.symmetric(vertical: 10),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                      ),
                      child: Text(
                        provider.isDeclineLoading ? 'Đang từ chối...' : 'Từ chối',
                        style: const TextStyle(fontSize: 13),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    flex: 3,
                    child: ElevatedButton.icon(
                      onPressed: busy ? null : () => _claim(provider, offer),
                      icon: provider.isClaimLoading
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                            )
                          : const Icon(Icons.assignment_turned_in_outlined, size: 16),
                      label: Text(
                        provider.isClaimLoading ? 'Đang nhận...' : 'Nhận',
                        style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold),
                      ),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: severityColor,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 10),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Color _severityColor(int level) {
    switch (level) {
      case 3:
        return const Color(0xFFEF4444);
      case 2:
        return const Color(0xFFF59E0B);
      case 1:
      default:
        return const Color(0xFF3B82F6);
    }
  }
}
