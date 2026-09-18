import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../../../core/constants/app_colors.dart';
import '../models/guard_shift_model.dart';

class ShiftListItem extends StatelessWidget {
  final GuardShiftModel shift;
  final bool isSelected;
  final VoidCallback onTap;

  const ShiftListItem({
    super.key,
    required this.shift,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    // Parse date for display
    DateTime? date;
    try {
      date = DateTime.parse(shift.shiftDate);
    } catch (_) {}

    final dateStr = date != null ? DateFormat('dd/MM').format(date) : shift.shiftDate;
    final weekdayStr = date != null ? const ['', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'][date.weekday] : '';

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      color: isSelected
          ? (AppColors.isDark(context) ? AppColors.darkSurfaceLight : const Color(0xFFEFF6FF))
          : AppColors.crd(context),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(
          color: isSelected ? AppColors.primaryLight : AppColors.crdBorder(context),
          width: isSelected ? 1.5 : 1.0,
        ),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Row(
            children: [
              // Date Box
              Container(
                width: 52,
                padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 2),
                decoration: BoxDecoration(
                  color: AppColors.surfLight(context),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppColors.crdBorder(context)),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      weekdayStr,
                      style: TextStyle(fontSize: 10, color: AppColors.txtSecondary(context), fontWeight: FontWeight.bold),
                      maxLines: 1,
                    ),
                    const SizedBox(height: 2),
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(
                        dateStr,
                        style: TextStyle(fontSize: 12, color: AppColors.txtPrimary(context), fontWeight: FontWeight.bold),
                        maxLines: 1,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),

              // Shift Info
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(shift.typeIcon, color: shift.typeColor, size: 14),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            shift.shiftTypeLabel,
                            style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                              color: AppColors.txtPrimary(context),
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Icon(Icons.location_on_outlined, size: 12, color: AppColors.txtMuted(context)),
                        const SizedBox(width: 4),
                        Expanded(
                          child: Text(
                            shift.areaName ?? 'Chốt chưa chỉ định',
                            style: TextStyle(fontSize: 11, color: AppColors.txtSecondary(context)),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),

              // Status Badge
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: shift.statusColor.withAlpha(25),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: shift.statusColor.withAlpha(80)),
                ),
                child: Text(
                  shift.statusLabel,
                  style: TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.bold,
                    color: shift.statusColor,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
