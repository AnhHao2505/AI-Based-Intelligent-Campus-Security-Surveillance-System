import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../models/guard_shift_request_model.dart';
import '../providers/shift_provider.dart';

class MyShiftRequestsSheet extends StatefulWidget {
  const MyShiftRequestsSheet({super.key});

  static Future<void> show(BuildContext context) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (ctx) => const MyShiftRequestsSheet(),
    );
  }

  @override
  State<MyShiftRequestsSheet> createState() => _MyShiftRequestsSheetState();
}

class _MyShiftRequestsSheetState extends State<MyShiftRequestsSheet> {
  List<GuardShiftRequestModel> _requests = [];
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _fetch();
  }

  Future<void> _fetch() async {
    setState(() {
      _loading = true;
    });

    final provider = context.read<ShiftProvider>();
    final list = await provider.getMyShiftRequests();

    if (mounted) {
      setState(() {
        _requests = list;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: BoxConstraints(
        maxHeight: MediaQuery.of(context).size.height * 0.75,
      ),
      padding: const EdgeInsets.only(top: 20, left: 20, right: 20, bottom: 24),
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
      ),
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
                'Lịch Sử Đổi / Xin Nghỉ Ca',
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
          const SizedBox(height: 14),

          // Body
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _requests.isEmpty
                    ? Center(
                        child: Text(
                          'Bạn chưa có yêu cầu đổi ca hoặc xin nghỉ nào',
                          style: TextStyle(fontSize: 13, color: AppColors.txtMuted(context)),
                        ),
                      )
                    : RefreshIndicator(
                        onRefresh: _fetch,
                        child: ListView.separated(
                          itemCount: _requests.length,
                          separatorBuilder: (context, index) => const SizedBox(height: 10),
                          itemBuilder: (ctx, index) {
                            final req = _requests[index];
                            final isApproved = req.isApproved;
                            final isRejected = req.isRejected;

                            final statusBg = isApproved
                                ? AppColors.success.withAlpha(25)
                                : isRejected
                                    ? AppColors.danger.withAlpha(25)
                                    : AppColors.warning.withAlpha(25);

                            final statusFg = isApproved
                                ? AppColors.success
                                : isRejected
                                    ? AppColors.danger
                                    : AppColors.warning;

                            return Container(
                              padding: const EdgeInsets.all(14),
                              decoration: BoxDecoration(
                                color: AppColors.surfLight(context),
                                borderRadius: BorderRadius.circular(14),
                                border: Border.all(color: AppColors.crdBorder(context)),
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                    children: [
                                      Text(
                                        req.typeLabel,
                                        style: TextStyle(
                                          fontSize: 12,
                                          fontWeight: FontWeight.bold,
                                          color: AppColors.primaryLight,
                                        ),
                                      ),
                                      Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                        decoration: BoxDecoration(
                                          color: statusBg,
                                          borderRadius: BorderRadius.circular(6),
                                        ),
                                        child: Text(
                                          req.statusLabel,
                                          style: TextStyle(
                                            fontSize: 11,
                                            fontWeight: FontWeight.bold,
                                            color: statusFg,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 6),

                                  if (req.shiftDate != null)
                                    Text(
                                      'Ca trực: ${req.shiftDate!.split('-').length == 3 ? "${req.shiftDate!.split('-')[2]}-${req.shiftDate!.split('-')[1]}-${req.shiftDate!.split('-')[0]}" : req.shiftDate} (${req.shiftStartTime?.substring(0, 5)} - ${req.shiftEndTime?.substring(0, 5)})',
                                      style: TextStyle(
                                        fontSize: 13,
                                        fontWeight: FontWeight.w600,
                                        color: AppColors.txtPrimary(context),
                                      ),
                                    ),

                                  if (req.isSwap && req.targetSubstituteGuardName != null) ...[
                                    const SizedBox(height: 4),
                                    Text(
                                      'Đổi với: ${req.targetSubstituteGuardName}${req.formattedTargetShiftDate.isNotEmpty ? " (Ca gốc của ${req.targetSubstituteGuardName}: ${req.formattedTargetShiftDate})" : ""}',
                                      style: const TextStyle(
                                        fontSize: 12,
                                        fontWeight: FontWeight.w600,
                                        color: AppColors.primary,
                                      ),
                                    ),
                                  ],

                                  if (req.reason.isNotEmpty) ...[
                                    const SizedBox(height: 4),
                                    Text(
                                      'Lý do: "${req.reason}"',
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontStyle: FontStyle.italic,
                                        color: AppColors.txtMuted(context),
                                      ),
                                    ),
                                  ],

                                  if (req.reviewNote != null && req.reviewNote!.isNotEmpty) ...[
                                    const SizedBox(height: 6),
                                    Text(
                                      'Phản hồi từ quản lý: "${req.reviewNote}"',
                                      style: TextStyle(
                                        fontSize: 12,
                                        color: isRejected ? AppColors.danger : AppColors.txtSecondary(context),
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            );
                          },
                        ),
                      ),
          ),
        ],
      ),
    );
  }
}
