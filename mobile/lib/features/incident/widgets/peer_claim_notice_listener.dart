import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/incident_provider.dart';

/// DEF-17: khi bảo vệ khác nhận một sự cố đang nằm trong danh sách của mình, IncidentProvider đặt
/// [IncidentProvider.peerClaimNotice] ("Sự cố đã được … tiếp nhận."). Widget này hiện câu đó thành snackbar
/// trên màn hình đang mở (qua [messengerKey] của MaterialApp) rồi xoá notice. Không vẽ gì.
class PeerClaimNoticeListener extends StatefulWidget {
  final GlobalKey<ScaffoldMessengerState> messengerKey;

  const PeerClaimNoticeListener({super.key, required this.messengerKey});

  @override
  State<PeerClaimNoticeListener> createState() => _PeerClaimNoticeListenerState();
}

class _PeerClaimNoticeListenerState extends State<PeerClaimNoticeListener> {
  bool _scheduled = false;

  @override
  Widget build(BuildContext context) {
    final notice = context.select<IncidentProvider, String?>((p) => p.peerClaimNotice);
    if (notice != null && !_scheduled) {
      _scheduled = true;
      WidgetsBinding.instance.addPostFrameCallback((_) => _show(notice));
    }
    return const SizedBox.shrink();
  }

  void _show(String notice) {
    _scheduled = false;
    if (!mounted) return;
    debugPrint('[PeerClaimNotice] show: $notice');
    widget.messengerKey.currentState?.showSnackBar(
      SnackBar(
        content: Text(notice),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 4),
      ),
    );
    context.read<IncidentProvider>().clearPeerClaimNotice();
  }
}
