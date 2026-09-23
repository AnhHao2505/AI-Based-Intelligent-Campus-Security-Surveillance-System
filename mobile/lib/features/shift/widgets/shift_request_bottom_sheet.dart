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

  List<AvailableSubstituteModel> _substitutes = [];
  String? _selectedSubstituteId;
  bool _loadingSubstitutes = false;
  bool _submitting = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _fetchSubstitutes();
  }

  @override
  void dispose() {
    _reasonController.dispose();
    super.dispose();
  }

  Future<void> _fetchSubstitutes() async {
    setState(() {
      _loadingSubstitutes = true;
      _errorMessage = null;
    });

    final provider = context.read<ShiftProvider>();
    final list = await provider.getAvailableSubstitutes(widget.shift.id);

    if (mounted) {
      setState(() {
        _substitutes = list;
        _loadingSubstitutes = false;
        if (list.isNotEmpty) {
          _selectedSubstituteId = list.first.id;
        }
      });
    }
  }

  Future<void> _handleSubmit() async {
    final reason = _reasonController.text.trim();

    if (_requestType == 'SWAP' && (_selectedSubstituteId == null || _selectedSubstituteId!.isEmpty)) {
      setState(() {
        _errorMessage = 'Vui lòng chọn nhân viên bảo vệ trực thay';
      });
      return;
    }

    if (_requestType == 'LEAVE' && reason.isEmpty) {
      setState(() {
        _errorMessage = 'Vui lòng nhập lý do xin nghỉ đột xuất';
      });
      return;
    }

    setState(() {
      _submitting = true;
      _errorMessage = null;
    });

    final provider = context.read<ShiftProvider>();
    final err = await provider.createShiftRequest(
      shiftId: widget.shift.id,
      requestType: _requestType,
      targetSubstituteGuardId: _requestType == 'SWAP' ? _selectedSubstituteId : null,
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
              ? 'Đã gửi yêu cầu nhờ trực thay đến Quản lý cơ sở'
              : 'Đã gửi yêu cầu nghỉ đột xuất đến Quản lý cơ sở'),
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

            // Shift summary banner
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
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
                          'Ngày trực: ${s.shiftDate}',
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

            // Toggle Tab: [Nhờ trực thay] / [Nghỉ đột xuất]
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
                        'Nhờ trực thay',
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
                        'Nghỉ đột xuất',
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

            // Lead time guideline banner (Khong emoji)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: AppColors.surfLight(context),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppColors.crdBorder(context)),
              ),
              child: Text(
                _requestType == 'SWAP'
                    ? 'Lưu ý: Đơn đổi ca cần được gửi trước giờ bắt đầu ca tối thiểu 2 giờ.'
                    : 'Lưu ý: Đơn xin nghỉ cần được gửi trước khi ca trực bắt đầu.',
                style: TextStyle(
                  fontSize: 11,
                  color: AppColors.txtMuted(context),
                  fontStyle: FontStyle.italic,
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

            // SECTION A: SUBSTITUTE SELECTION (for SWAP)
            if (_requestType == 'SWAP') ...[
              Text(
                'Chọn đồng nghiệp trực thay:',
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: AppColors.txtPrimary(context),
                ),
              ),
              const SizedBox(height: 8),

              if (_loadingSubstitutes)
                const Center(
                  child: Padding(
                    padding: EdgeInsets.symmetric(vertical: 14),
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                )
              else if (_substitutes.isEmpty)
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: AppColors.surfLight(context),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    'Không có đồng nghiệp nào có lịch nghỉ trong ngày này để trực thay.',
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
                      value: _selectedSubstituteId,
                      dropdownColor: AppColors.crd(context),
                      items: _substitutes.map((sub) {
                        return DropdownMenuItem<String>(
                          value: sub.id,
                          child: Text(
                            sub.fullName,
                            style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                              color: AppColors.txtPrimary(context),
                            ),
                          ),
                        );
                      }).toList(),
                      onChanged: (val) {
                        setState(() {
                          _selectedSubstituteId = val;
                        });
                      },
                    ),
                  ),
                ),
              const SizedBox(height: 14),
            ],

            // SECTION B: REASON INPUT
            Text(
              _requestType == 'SWAP' ? 'Lý do đổi ca (Tùy chọn):' : 'Lý do xin nghỉ đột xuất:',
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
                    : 'Nhập lý do xin nghỉ (việc gia đình, đột xuất...)...',
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
