import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../models/guard_shift_model.dart';
import '../providers/shift_provider.dart';

class WeeklyDateBar extends StatelessWidget {
  const WeeklyDateBar({super.key});

  static const List<String> _dayNames = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'];

  @override
  Widget build(BuildContext context) {
    final shiftProvider = context.watch<ShiftProvider>();
    final selectedDate = shiftProvider.selectedDate;

    // Determine Monday of the selected week
    final monday = selectedDate.subtract(Duration(days: selectedDate.weekday - 1));
    final sunday = monday.add(const Duration(days: 6));

    final today = DateTime.now();
    final todayStr = DateFormat('yyyy-MM-dd').format(today);

    // Map shifts by date for quick dot indicator
    final shiftsByDate = <String, GuardShiftModel>{};
    for (final s in shiftProvider.shifts) {
      shiftsByDate[s.shiftDate] = s;
    }

    final weekRangeTitle = 'Tuần ${DateFormat('dd/MM').format(monday)} - ${DateFormat('dd/MM').format(sunday)}';

    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.crdBorder(context)),
      ),
      child: Column(
        children: [
          // Header: Week title + navigation buttons
          Row(
            children: [
              IconButton(
                icon: Icon(Icons.chevron_left, color: AppColors.txtSecondary(context), size: 22),
                padding: const EdgeInsets.all(4),
                constraints: const BoxConstraints(),
                onPressed: () {
                  final prevWeek = selectedDate.subtract(const Duration(days: 7));
                  shiftProvider.setSelectedDate(prevWeek);
                  shiftProvider.fetchWeeklyShifts(prevWeek);
                },
              ),
              Expanded(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.calendar_month, color: AppColors.primaryLight, size: 15),
                    const SizedBox(width: 6),
                    Flexible(
                      child: Text(
                        weekRangeTitle,
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
              ),
              IconButton(
                icon: Icon(Icons.chevron_right, color: AppColors.txtSecondary(context), size: 22),
                padding: const EdgeInsets.all(4),
                constraints: const BoxConstraints(),
                onPressed: () {
                  final nextWeek = selectedDate.add(const Duration(days: 7));
                  shiftProvider.setSelectedDate(nextWeek);
                  shiftProvider.fetchWeeklyShifts(nextWeek);
                },
              ),
            ],
          ),
          const SizedBox(height: 12),

          // 7-Day row
          Row(
            children: List.generate(7, (index) {
              final dayDate = monday.add(Duration(days: index));
              final dayDateStr = DateFormat('yyyy-MM-dd').format(dayDate);
              final isSelected = DateFormat('yyyy-MM-dd').format(selectedDate) == dayDateStr;
              final isToday = dayDateStr == todayStr;
              final hasShift = shiftsByDate.containsKey(dayDateStr);
              final shift = shiftsByDate[dayDateStr];

              return Expanded(
                child: GestureDetector(
                  onTap: () {
                    shiftProvider.setSelectedDate(dayDate);
                  },
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    margin: const EdgeInsets.symmetric(horizontal: 2),
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    decoration: BoxDecoration(
                      color: isSelected
                        ? AppColors.primary
                        : (isToday ? AppColors.surfLight(context) : Colors.transparent),
                    borderRadius: BorderRadius.circular(14),
                    border: isToday && !isSelected
                        ? Border.all(color: AppColors.primaryLight, width: 1.2)
                        : null,
                    boxShadow: isSelected
                        ? [
                            BoxShadow(
                              color: AppColors.primary.withAlpha(100),
                              blurRadius: 8,
                              offset: const Offset(0, 4),
                            )
                          ]
                        : null,
                  ),
                  child: Column(
                    children: [
                      Text(
                        _dayNames[index],
                        style: TextStyle(
                          fontSize: 11,
                          fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                          color: isSelected ? Colors.white : AppColors.txtSecondary(context),
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '${dayDate.day}',
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                          color: isSelected ? Colors.white : AppColors.txtPrimary(context),
                        ),
                      ),
                      const SizedBox(height: 4),
                      // Dot indicator for shift
                      Container(
                        width: 5,
                        height: 5,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: hasShift
                              ? (isSelected ? Colors.white : shift?.typeColor ?? AppColors.warning)
                              : Colors.transparent,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            );
          }),
          ),
        ],
      ),
    );
  }
}
