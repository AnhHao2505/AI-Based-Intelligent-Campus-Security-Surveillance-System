import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../models/guard_shift_model.dart';
import '../providers/shift_provider.dart';

class ActiveShiftCard extends StatelessWidget {
  final GuardShiftModel? shift;
  final DateTime selectedDate;
  final int? shiftIndex;
  final int? totalShifts;

  const ActiveShiftCard({
    super.key,
    required this.shift,
    required this.selectedDate,
    this.shiftIndex,
    this.totalShifts,
  });

  void _showResultDialog(BuildContext context, {required String title, required String message, bool isSuccess = false}) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.crd(context),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Row(
          children: [
            Icon(
              isSuccess ? Icons.check_circle_outline : Icons.info_outline,
              color: isSuccess ? AppColors.success : AppColors.warning,
              size: 22,
            ),
            const SizedBox(width: 8),
            Text(
              title,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
                color: AppColors.txtPrimary(context),
              ),
            ),
          ],
        ),
        content: Text(
          message,
          style: TextStyle(fontSize: 13, color: AppColors.txtSecondary(context), height: 1.4),
        ),
        actions: [
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: isSuccess ? AppColors.success : AppColors.primary,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              minimumSize: const Size(80, 36),
            ),
            onPressed: () => Navigator.pop(ctx),
            child: const Text('OK', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }

  void _confirmCheckIn(BuildContext context, ShiftProvider provider, GuardShiftModel shift) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.crd(context),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Row(
          children: [
            const Icon(Icons.check_circle_outline, color: AppColors.success, size: 24),
            const SizedBox(width: 8),
            Text(
              'Nhận Ca Trực',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppColors.txtPrimary(context)),
            ),
          ],
        ),
        content: Text(
          'Bạn có chắc chắn muốn nhận ca trực này (${shift.shiftTimeRange})?',
          style: TextStyle(fontSize: 13, color: AppColors.txtSecondary(context)),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text('Hủy', style: TextStyle(color: AppColors.txtMuted(context))),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.success,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              minimumSize: const Size(90, 38),
            ),
            onPressed: () async {
              Navigator.pop(ctx);
              final error = await provider.checkIn(shift.id);
              if (context.mounted) {
                if (error != null) {
                  _showResultDialog(
                    context,
                    title: 'Thông Báo',
                    message: error,
                    isSuccess: false,
                  );
                } else {
                  _showResultDialog(
                    context,
                    title: 'Thành Công',
                    message: 'Điểm danh nhận ca trực thành công!',
                    isSuccess: true,
                  );
                }
              }
            },
            child: const Text('Xác nhận'),
          ),
        ],
      ),
    );
  }

  void _confirmCheckOut(BuildContext context, ShiftProvider provider, GuardShiftModel shift) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.crd(context),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Row(
          children: [
            const Icon(Icons.logout, color: AppColors.danger, size: 24),
            const SizedBox(width: 8),
            Text(
              'Kết Thúc Ca Trực',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppColors.txtPrimary(context)),
            ),
          ],
        ),
        content: Text(
          'Bạn có chắc chắn muốn kết thúc ca trực này (${shift.shiftTimeRange})?',
          style: TextStyle(fontSize: 13, color: AppColors.txtSecondary(context)),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text('Hủy', style: TextStyle(color: AppColors.txtMuted(context))),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.danger,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              minimumSize: const Size(90, 38),
            ),
            onPressed: () async {
              Navigator.pop(ctx);
              final error = await provider.checkOut(shift.id);
              if (context.mounted) {
                if (error != null) {
                  _showResultDialog(
                    context,
                    title: 'Thông Báo',
                    message: error,
                    isSuccess: false,
                  );
                } else {
                  _showResultDialog(
                    context,
                    title: 'Thành Công',
                    message: 'Kết thúc ca trực thành công!',
                    isSuccess: true,
                  );
                }
              }
            },
            child: const Text('Xác nhận'),
          ),
        ],
      ),
    );
  }

  static String _formatVietnameseDate(DateTime date) {
    const days = [
      '',
      'Thứ Hai',
      'Thứ Ba',
      'Thứ Tư',
      'Thứ Năm',
      'Thứ Sáu',
      'Thứ Bảy',
      'Chủ Nhật',
    ];
    final dayName = days[date.weekday];
    final formatted = DateFormat('dd/MM').format(date);
    return '$dayName, $formatted';
  }

  @override
  Widget build(BuildContext context) {
    final shiftProvider = context.read<ShiftProvider>();
    final isToday = DateFormat('yyyy-MM-dd').format(selectedDate) ==
        DateFormat('yyyy-MM-dd').format(DateTime.now());

    final hasMultiple = totalShifts != null && totalShifts! > 1;
    final shiftSuffix = hasMultiple ? ' • Ca $shiftIndex/$totalShifts' : '';
    final formattedDateTitle = isToday
        ? 'Hôm nay, ${DateFormat('dd/MM').format(selectedDate)}$shiftSuffix'
        : '${_formatVietnameseDate(selectedDate)}$shiftSuffix';

    // If no shift is scheduled
    if (shift == null) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          color: AppColors.crd(context),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: AppColors.crdBorder(context)),
        ),
        child: Column(
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: AppColors.surfLight(context),
                shape: BoxShape.circle,
              ),
              child: Icon(Icons.coffee_outlined, color: AppColors.txtMuted(context), size: 36),
            ),
            const SizedBox(height: 12),
            Text(
              formattedDateTitle,
              style: const TextStyle(fontSize: 13, color: AppColors.primaryLight, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 4),
            Text(
              'Không có ca trực phân công',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppColors.txtPrimary(context)),
            ),
            const SizedBox(height: 6),
            Text(
              'Bạn không được xếp lịch làm việc vào ngày này.',
              style: TextStyle(fontSize: 12, color: AppColors.txtSecondary(context)),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      );
    }

    final s = shift!;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: s.isCheckedIn
              ? AppColors.success.withAlpha(180)
              : AppColors.crdBorder(context),
          width: s.isCheckedIn ? 1.5 : 1.0,
        ),
        boxShadow: s.isCheckedIn
            ? [
                BoxShadow(
                  color: AppColors.success.withAlpha(40),
                  blurRadius: 16,
                  offset: const Offset(0, 4),
                )
              ]
            : null,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header: Date & Status Chip
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: Row(
                  children: [
                    Icon(s.typeIcon, color: s.typeColor, size: 18),
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        formattedDateTitle,
                        style: TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                          color: isToday ? AppColors.primaryLight : AppColors.txtSecondary(context),
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: s.statusColor.withAlpha(35),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: s.statusColor.withAlpha(100)),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (s.isCheckedIn)
                      Container(
                        width: 6,
                        height: 6,
                        margin: const EdgeInsets.only(right: 6),
                        decoration: const BoxDecoration(
                          shape: BoxShape.circle,
                          color: AppColors.success,
                        ),
                      ),
                    Text(
                      s.statusLabel,
                      style: TextStyle(
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                        color: s.statusColor,
                        letterSpacing: 0.5,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),

          // Shift Name & Time
          Text(
            s.shiftTypeLabel,
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.bold,
              color: AppColors.txtPrimary(context),
            ),
          ),
          const SizedBox(height: 12),
          Divider(color: AppColors.crdBorder(context), height: 1),
          const SizedBox(height: 12),

          // Details: Area / Post
          Row(
            children: [
              const Icon(Icons.location_on_outlined, color: AppColors.primaryLight, size: 18),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  s.areaName ?? 'Chốt chưa chỉ định',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: AppColors.txtPrimary(context),
                  ),
                ),
              ),
              if (s.building != null)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: AppColors.surfLight(context),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    s.building!,
                    style: TextStyle(fontSize: 11, color: AppColors.txtSecondary(context)),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),

          // Radio Channel
          Row(
            children: [
              const Icon(Icons.radio_outlined, color: AppColors.warning, size: 18),
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.radioPill,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  s.radioChannel ?? 'Kênh tổng',
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    color: AppColors.radioText,
                  ),
                ),
              ),
            ],
          ),

          // Notes from Admin (if any)
          if (s.notes != null && s.notes!.isNotEmpty) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.surfLight(context),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.crdBorder(context)),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.info_outline, size: 15, color: AppColors.txtMuted(context)),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      'Lưu ý: ${s.notes}',
                      style: TextStyle(fontSize: 12, color: AppColors.txtSecondary(context)),
                    ),
                  ),
                ],
              ),
            ),
          ],

          // Attendance Action Buttons
          const SizedBox(height: 18),
          if (s.isScheduled) ...[
            if (s.isBeforeCheckInWindow && s.shiftStartDateTime != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  children: [
                    const Icon(Icons.access_time, size: 14, color: AppColors.warning),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        'Nhận ca từ trước giờ bắt đầu 5 phút (từ ${DateFormat('HH:mm').format(s.shiftStartDateTime!.subtract(const Duration(minutes: 5)))}).',
                        style: TextStyle(fontSize: 11, color: AppColors.txtSecondary(context)),
                      ),
                    ),
                  ],
                ),
              ),
            ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: s.isBeforeCheckInWindow ? AppColors.surfLight(context) : AppColors.success,
                foregroundColor: s.isBeforeCheckInWindow ? AppColors.txtMuted(context) : Colors.white,
                shadowColor: s.isBeforeCheckInWindow ? Colors.transparent : AppColors.success.withAlpha(100),
                elevation: s.isBeforeCheckInWindow ? 0 : 4,
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
              ),
              onPressed: s.isBeforeCheckInWindow ? null : () => _confirmCheckIn(context, shiftProvider, s),
              icon: Icon(
                s.isBeforeCheckInWindow ? Icons.schedule : Icons.check_circle,
                size: 18,
                color: s.isBeforeCheckInWindow ? AppColors.txtMuted(context) : Colors.white,
              ),
              label: FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  s.isBeforeCheckInWindow ? 'CHƯA ĐẾN CA TRỰC' : 'NHẬN CA TRỰC',
                  maxLines: 1,
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.bold,
                    color: s.isBeforeCheckInWindow ? AppColors.txtMuted(context) : Colors.white,
                  ),
                ),
              ),
            ),
          ] else if (s.isCheckedIn) ...[
            if (s.isBeforeCheckOutWindow)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  children: [
                    const Icon(Icons.info_outline, size: 14, color: AppColors.primaryLight),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        'Hoàn thành ca từ đúng giờ kết thúc (${s.shortEndTime}) đến sau 5 phút.',
                        style: TextStyle(fontSize: 11, color: AppColors.txtSecondary(context)),
                      ),
                    ),
                  ],
                ),
              ),
            OutlinedButton.icon(
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.dangerLight,
                side: const BorderSide(color: AppColors.danger, width: 1.5),
                minimumSize: const Size.fromHeight(48),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
              ),
              onPressed: () => _confirmCheckOut(context, shiftProvider, s),
              icon: const Icon(Icons.logout, size: 18),
              label: const FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  'KẾT THÚC CA',
                  maxLines: 1,
                  style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.bold),
                ),
              ),
            ),
          ] else if (s.isCompleted) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 10),
              decoration: BoxDecoration(
                color: AppColors.surfLight(context),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.crdBorder(context)),
              ),
              child: const Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.task_alt, color: AppColors.success, size: 18),
                  SizedBox(width: 8),
                  Text(
                    'Ca trực đã hoàn tất thành công',
                    style: TextStyle(fontSize: 13, color: AppColors.success, fontWeight: FontWeight.w600),
                  ),
                ],
              ),
            ),
          ] else if (s.isAbsent) ...[
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 12),
              decoration: BoxDecoration(
                color: AppColors.danger.withAlpha(20),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.danger.withAlpha(80)),
              ),
              child: const Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.cancel_outlined, color: AppColors.danger, size: 18),
                  SizedBox(width: 8),
                  Flexible(
                    child: Text(
                      'Ca trực ghi nhận VẮNG MẶT (quá hạn nhận ca)',
                      style: TextStyle(fontSize: 12, color: AppColors.danger, fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}
