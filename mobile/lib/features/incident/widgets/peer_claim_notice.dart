import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/incident_provider.dart';

/// Thời gian hiện câu "Sự cố đã được … tiếp nhận."
const Duration peerClaimNoticeDuration = Duration(seconds: 4);

/// DEF-17: bảo vệ khác nhận sự cố đang có trong danh sách → hiện SnackBar với đúng câu
/// [IncidentProvider.peerClaimNotice], rồi xoá câu đó để không hiện lại.
///
/// Gắn vào State của màn danh sách sự cố và màn bản đồ tuần tra. Hai màn cùng mở thì màn nào nghe trước
/// xoá câu trước, màn kia thấy null → chỉ một SnackBar cho mỗi lần nhận.
/// Không có IncidentProvider phía trên (vd. test bản đồ) thì không làm gì.
mixin PeerClaimNoticeMixin<T extends StatefulWidget> on State<T> {
  IncidentProvider? _peerClaimProvider;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final provider = context.read<IncidentProvider?>();
    if (identical(provider, _peerClaimProvider)) return;
    _peerClaimProvider?.removeListener(_showPeerClaimNotice);
    _peerClaimProvider = provider;
    provider?.addListener(_showPeerClaimNotice);
    // Câu đã có sẵn trước khi mở màn → hiện sau frame đầu
    WidgetsBinding.instance.addPostFrameCallback((_) => _showPeerClaimNotice());
  }

  void _showPeerClaimNotice() {
    final provider = _peerClaimProvider;
    final notice = provider?.peerClaimNotice;
    if (!mounted || provider == null || notice == null) return;
    provider.clearPeerClaimNotice();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(notice),
        duration: peerClaimNoticeDuration,
      ),
    );
  }

  @override
  void dispose() {
    _peerClaimProvider?.removeListener(_showPeerClaimNotice);
    _peerClaimProvider = null;
    super.dispose();
  }
}
