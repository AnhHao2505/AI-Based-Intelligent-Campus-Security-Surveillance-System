import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../models/guard_shift_model.dart';
import '../models/guard_shift_request_model.dart';
import '../providers/shift_provider.dart';

class ShiftRequestBottomSheet extends StatefulWidget {
  final GuardShiftModel shift;

  const ShiftRequestBottomSheet({
    super.key,
    required this.shift,
  });

  static Future<void> show(BuildContext context, GuardShiftModel shift) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => ShiftRequestBottomSheet(shift: shift),
    );
  }

  @override
  State<ShiftRequestBottomSheet> createState() => _ShiftRequestBottomSheetState();
}

class _ShiftRequestBottomSheetState extends State<ShiftRequestBottomSheet> {
  // 'SWAP' | 'LEAVE'
  String _requestType = 'SWAP';
  final TextEditingController _reasonController = TextEditingController();

  List<AvailableSwapShiftModel> _swapOptions = [];
  String? _selectedTargetShiftId;
  bool _loadingSwapOptions = false;
  bool _submitting = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _fetchSwapOptions();
  }

  @override
  void dispose() {
    _reasonController.dispose();
    super.dispose();
  }

  Future<void> _fetchSwapOptions() async {
    setState(() {
      _loadingSwapOptions = true;
      _errorMessage = null;
    });

    final provider = context.read<ShiftProvider>();
    final list = await provider.getAvailableSwapShifts(widget.shift.id);

    // Sort by shift date ascending -> start time -> full name
    list.sort((a, b) {
      final d = a.shiftDate.compareTo(b.shiftDate);
      if (d != 0) return d;
      final t = a.startTime.compareTo(b.startTime);
      if (t != 0) return t;
      return a.fullName.compareTo(b.fullName);
    });

    if (mounted) {
      setState(() {
        _swapOptions = list;
        _loadingSwapOptions = false;
        if (list.isNotEmpty) {
          _selectedTargetShiftId = list.first.targetShiftId;
        }
      });
    }
  }

  bool get _isShiftEmergency {
    try {
      final parts = widget.shift.shiftDate.split('-');
      final timeParts = widget.shift.startTime.split(':');
      if (parts.length == 3 && timeParts.length >= 2) {
        final shiftStart = DateTime(
          int.parse(parts[0]),
          int.parse(parts[1]),
          int.parse(parts[2]),
          int.parse(timeParts[0]),
          int.parse(timeParts[1]),
        );
        return shiftStart.difference(DateTime.now()).inHours < 24;
      }
    } catch (_) {}
    return true;
  }

  Future<void> _handleSubmit() async {
    final inputReason = _reasonController.text.trim();
    final reason = inputReason.isNotEmpty
        ? inputReason
        : (_requestType == 'SWAP' ? 'Đổi ca trực cùng tuần với đồng nghiệp' : '');

    if (_requestType == 'SWAP' && (_selectedTargetShiftId == null || _selectedTargetShiftId!.isEmpty)) {
      setState(() {
        _errorMessage = 'Vui lòng chọn một ca trực của đồng nghiệp để hoán đổi';
      });
      return;
    }

    if (_requestType == 'LEAVE' && inputReason.isEmpty) {
      setState(() {
        _errorMessage = 'Vui lòng nhập lý do xin nghỉ';
      });
      return;
    }

    setState(() {
      _submitting = true;
      _errorMessage = null;
    });

    final provider = context.read<ShiftProvider>();

    String? subId;
    if (_requestType == 'SWAP') {
      final selectedOpt = _swapOptions.firstWhere(
        (o) => o.targetShiftId == _selectedTargetShiftId,
        orElse: () => _swapOptions.first,
      );
      subId = selectedOpt.guardId;
    }

    final err = await provider.createShiftRequest(
      shiftId: widget.shift.id,
      requestType: _requestType,
      targetShiftId: _requestType == 'SWAP' ? _selectedTargetShiftId : null,
      targetSubstituteGuardId: subId,
      reason: reason,
    );

    if (!mounted) return;

    setState(() {
      _submitting = false;
    });

    if (err != null) {
      setState(() {
        _errorMessage = err;
      });
    } else {
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_requestType == 'SWAP'
              ? 'Đã gửi yêu cầu đổi ca đến Quản lý cơ sở'
              : 'Đã gửi đơn xin nghỉ ca đến Quản lý cơ sở'),
          backgroundColor: AppColors.success,
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.shift;
    final isDark = AppColors.isDark(context);
    final isEmergency = _isShiftEmergency;

    final dateFormatted = s.shiftDate.split('-').length == 3
        ? '${s.shiftDate.split('-')[2]}-${s.shiftDate.split('-')[1]}-${s.shiftDate.split('-')[0]}'
        : s.shiftDate;

    return Container(
      padding: EdgeInsets.only(
        top: 20,
        left: 20,
        right: 20,
        bottom: MediaQuery.of(context).viewInsets.bottom + 24,
      ),
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Drag handle
            Center(
              child: Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: AppColors.crdBorder(context),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Header
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Đổi Ca / Xin Nghỉ',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: AppColors.txtPrimary(context),
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close, size: 20),
                  color: AppColors.txtMuted(context),
                ),
              ],
            ),
            const SizedBox(height: 6),

            // Current shift info card
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.surfLight(context),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.crdBorder(context)),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${s.shiftTypeName} (${s.shiftTimeRange})',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.bold,
                            color: AppColors.txtPrimary(context),
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          'Ngày trực của bạn: $dateFormatted',
                          style: TextStyle(
                            fontSize: 12,
                            color: AppColors.txtSecondary(context),
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (s.isOvertime)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: Colors.amber.shade200,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: const Text(
                        'OT',
                        style: TextStyle(fontSize: 10, fontWeight: FontWeight.bold, color: Colors.black87),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // Toggle Tab: [Đổi ca] / [Xin nghỉ]
            Row(
              children: [
                Expanded(
                  child: InkWell(
                    onTap: () {
                      setState(() {
                        _requestType = 'SWAP';
                        _errorMessage = null;
                      });
                    },
                    borderRadius: BorderRadius.circular(10),
                    child: Container(
                      padding: const EdgeInsets.symmetric(vertical: 10),
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: _requestType == 'SWAP'
                            ? AppColors.primary
                            : (isDark ? Colors.grey.shade900 : Colors.grey.shade100),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        'Đổi ca',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.bold,
                          color: _requestType == 'SWAP' ? Colors.white : AppColors.txtSecondary(context),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: InkWell(
                    onTap: () {
                      setState(() {
                        _requestType = 'LEAVE';
                        _errorMessage = null;
                      });
                    },
                    borderRadius: BorderRadius.circular(10),
                    child: Container(
                      padding: const EdgeInsets.symmetric(vertical: 10),
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: _requestType == 'LEAVE'
                            ? AppColors.primary
                            : (isDark ? Colors.grey.shade900 : Colors.grey.shade100),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        'Xin nghỉ ca',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.bold,
                          color: _requestType == 'LEAVE' ? Colors.white : AppColors.txtSecondary(context),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),

            // Lead time guideline banner
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: AppColors.surfLight(context),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppColors.crdBorder(context)),
              ),
              child: Text(
                _requestType == 'SWAP'
                    ? 'Lưu ý: Hoán đổi 2 ca trực giữa bạn và đồng nghiệp. Đơn cần gửi trước giờ ca ít nhất 2 giờ.'
                    : (isEmergency
                        ? 'Phân loại: [Nghỉ đột xuất] do gửi trong vòng 24h trước ca. Quản lý sẽ chỉ định người trực thay trên hệ thống.'
                        : 'Phân loại: [Nghỉ phép thường]. Quản lý cơ sở sẽ xem xét và bố trí nhân sự phù hợp.'),
                style: TextStyle(
                  fontSize: 11,
                  color: _requestType == 'LEAVE' && isEmergency ? AppColors.warning : AppColors.txtMuted(context),
                  fontStyle: FontStyle.italic,
                  fontWeight: _requestType == 'LEAVE' && isEmergency ? FontWeight.w600 : FontWeight.normal,
                ),
              ),
            ),
            const SizedBox(height: 14),

            // Error message if any
            if (_errorMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppColors.danger.withAlpha(20),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppColors.danger.withAlpha(80)),
                ),
                child: Text(
                  _errorMessage!,
                  style: const TextStyle(fontSize: 12, color: AppColors.danger),
                ),
              ),
              const SizedBox(height: 14),
            ],

            // SECTION A: SWAP SHIFT SELECTION (for SWAP)
            if (_requestType == 'SWAP') ...[
              Text(
                'Chọn ca trực của đồng nghiệp muốn đổi:',
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: AppColors.txtPrimary(context),
                ),
              ),
              const SizedBox(height: 8),

              if (_loadingSwapOptions)
                const Center(
                  child: Padding(
                    padding: EdgeInsets.symmetric(vertical: 14),
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                )
              else if (_swapOptions.isEmpty)
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: AppColors.surfLight(context),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    'Hiện không có ca trực của đồng nghiệp nào thỏa mãn điều kiện hoán đổi (không trùng lịch và đạt chuẩn nghỉ ngơi).',
                    style: TextStyle(fontSize: 12, color: AppColors.txtMuted(context)),
                  ),
                )
              else
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  decoration: BoxDecoration(
                    color: AppColors.surfLight(context),
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: AppColors.crdBorder(context)),
                  ),
                  child: DropdownButtonHideUnderline(
                    child: DropdownButton<String>(
                      isExpanded: true,
                      value: _selectedTargetShiftId,
                      dropdownColor: AppColors.crd(context),
                      itemHeight: null,
                      selectedItemBuilder: (BuildContext context) {
                        return _swapOptions.map<Widget>((opt) {
                          return Align(
                            alignment: Alignment.centerLeft,
                            child: Text(
                              '${opt.formattedDate} • ${opt.pureShiftName} — ${opt.fullName}',
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w600,
                                color: AppColors.txtPrimary(context),
                              ),
                              overflow: TextOverflow.ellipsis,
                              maxLines: 1,
                            ),
                          );
                        }).toList();
                      },
                      items: _swapOptions.map((opt) {
                        return DropdownMenuItem<String>(
                          value: opt.targetShiftId,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 4),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              mainAxisAlignment: MainAxisAlignment.center,
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(
                                  '${opt.formattedDate} • ${opt.pureShiftName}',
                                  style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.bold,
                                    color: AppColors.txtPrimary(context),
                                  ),
                                  overflow: TextOverflow.ellipsis,
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  'Đồng nghiệp: ${opt.fullName}',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: AppColors.txtSecondary(context),
                                    fontWeight: FontWeight.w500,
                                  ),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ],
                            ),
                          ),
                        );
                      }).toList(),
                      onChanged: (val) {
                        setState(() {
                          _selectedTargetShiftId = val;
                        });
                      },
                    ),
                  ),
                ),
              const SizedBox(height: 14),
            ],

            // SECTION B: REASON INPUT
            Text(
              _requestType == 'SWAP' ? 'Lý do đổi ca (Tùy chọn):' : 'Lý do xin nghỉ ca (Bắt buộc):',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.bold,
                color: AppColors.txtPrimary(context),
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _reasonController,
              maxLines: 3,
              style: TextStyle(fontSize: 13, color: AppColors.txtPrimary(context)),
              decoration: InputDecoration(
                hintText: _requestType == 'SWAP'
                    ? 'Nhập lý do gửi đến Quản lý cơ sở...'
                    : 'Nhập lý do xin nghỉ (việc gia đình, đau ốm đột xuất...)...',
                hintStyle: TextStyle(fontSize: 12, color: AppColors.txtMuted(context)),
                filled: true,
                fillColor: AppColors.surfLight(context),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: AppColors.crdBorder(context)),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: AppColors.crdBorder(context)),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: AppColors.primary, width: 1.5),
                ),
              ),
            ),
            const SizedBox(height: 20),

            // Submit Button
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.primary,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: _submitting ? null : _handleSubmit,
              child: _submitting
                  ? const SizedBox(
                      height: 18,
                      width: 18,
                      child: CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
                    )
                  : Text(
                      _requestType == 'SWAP' ? 'Gửi Yêu Cầu Đổi Ca' : 'Gửi Yêu Cầu Xin Nghỉ',
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.bold),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
