import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../../../core/theme/theme_provider.dart';
import '../../auth/models/user_model.dart';
import '../../auth/providers/auth_provider.dart';
import '../providers/shift_provider.dart';
import '../widgets/active_shift_card.dart';
import '../widgets/my_shift_requests_sheet.dart';
import '../widgets/shift_list_item.dart';
import '../widgets/weekly_date_bar.dart';

class GuardScheduleScreen extends StatefulWidget {
  const GuardScheduleScreen({super.key});

  @override
  State<GuardScheduleScreen> createState() => _GuardScheduleScreenState();
}

class _GuardScheduleScreenState extends State<GuardScheduleScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<ShiftProvider>().fetchWeeklyShifts();
    });
  }

  void _confirmLogout(BuildContext context, AuthProvider authProvider) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.crd(context),
        title: Text(
          'Đăng Xuất',
          style: TextStyle(fontSize: 16, color: AppColors.txtPrimary(context), fontWeight: FontWeight.bold),
        ),
        content: Text(
          'Bạn có chắc chắn muốn đăng xuất?',
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
              minimumSize: const Size(90, 38),
            ),
            onPressed: () {
              Navigator.pop(ctx);
              authProvider.logout();
            },
            child: const Text('Đăng xuất'),
          ),
        ],
      ),
    );
  }

  void _showProfileDialog(BuildContext context, UserModel? user) {
    final rawName = user?.fullName ?? 'Nhân viên Bảo vệ';
    final cleanName = rawName.replaceAll(RegExp(r'\s*\([^)]*\)'), '').trim();
    final userCode = user?.userCode ?? 'Chưa cập nhật';
    final email = user?.email ?? 'Chưa cập nhật';
    final role = (user?.role == 'GUARD' || user?.role == 'INTERNAL_GUARD')
        ? 'Bảo vệ tuần tra'
        : (user?.role ?? 'Bảo vệ');

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.crd(context),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        contentPadding: const EdgeInsets.fromLTRB(20, 24, 20, 16),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 68,
              height: 68,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: const LinearGradient(
                  colors: [AppColors.primary, AppColors.primaryLight],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                boxShadow: [
                  BoxShadow(
                    color: AppColors.primary.withAlpha(80),
                    blurRadius: 10,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: const Icon(Icons.person, size: 38, color: Colors.white),
            ),
            const SizedBox(height: 14),
            Text(
              cleanName,
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
                color: AppColors.txtPrimary(context),
              ),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 4),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
              decoration: BoxDecoration(
                color: AppColors.surfLight(context),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                role,
                style: const TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  color: AppColors.primaryLight,
                ),
              ),
            ),
            const SizedBox(height: 18),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.surf(context),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: AppColors.crdBorder(context)),
              ),
              child: Column(
                children: [
                  _buildProfileRow(context, Icons.badge_outlined, 'Mã nhân viên', userCode),
                  const Divider(height: 16),
                  _buildProfileRow(context, Icons.email_outlined, 'Email', email),
                  const Divider(height: 16),
                  _buildProfileRow(context, Icons.business_outlined, 'Cơ sở', 'FPT Campus'),
                ],
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Đóng', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }

  Widget _buildProfileRow(BuildContext context, IconData icon, String label, String value) {
    return Row(
      children: [
        Icon(icon, size: 16, color: AppColors.txtMuted(context)),
        const SizedBox(width: 8),
        Text(
          label,
          style: TextStyle(fontSize: 12, color: AppColors.txtMuted(context)),
        ),
        const Spacer(),
        Text(
          value,
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: AppColors.txtPrimary(context),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final authProvider = context.watch<AuthProvider>();
    final shiftProvider = context.watch<ShiftProvider>();
    final themeProvider = context.watch<ThemeProvider>();
    final user = authProvider.user;

    final selectedShifts = shiftProvider.shiftsForSelectedDate;
    final allWeekShifts = shiftProvider.shifts;

    final rawName = user?.fullName ?? 'Bảo vệ';
    final cleanName = rawName.replaceAll(RegExp(r'\s*\([^)]*\)'), '').trim();
    final userCode = user?.userCode;

    return Scaffold(
      backgroundColor: AppColors.bg(context),
      body: SafeArea(
        child: RefreshIndicator(
          color: AppColors.primaryLight,
          backgroundColor: AppColors.surf(context),
          onRefresh: () => shiftProvider.fetchWeeklyShifts(),
          child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              // Sticky Custom App Bar Header
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 12, 12, 8),
                  child: Row(
                    children: [
                      // Screen Title (Clean, no subtitle)
                      Expanded(
                        child: Text(
                          'Lịch Trực',
                          style: TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.bold,
                            color: AppColors.txtPrimary(context),
                            letterSpacing: -0.4,
                          ),
                        ),
                      ),

                      // Theme Toggle button
                      IconButton(
                        visualDensity: VisualDensity.compact,
                        padding: const EdgeInsets.all(6),
                        constraints: const BoxConstraints(),
                        icon: Icon(
                          themeProvider.isDarkMode ? Icons.light_mode_outlined : Icons.dark_mode_outlined,
                          color: AppColors.txtSecondary(context),
                          size: 20,
                        ),
                        tooltip: themeProvider.isDarkMode ? 'Chuyển sang chế độ Sáng' : 'Chuyển sang chế độ Tối',
                        onPressed: () => themeProvider.toggleTheme(),
                      ),
                      const SizedBox(width: 2),

                      // Refresh button
                      IconButton(
                        visualDensity: VisualDensity.compact,
                        padding: const EdgeInsets.all(6),
                        constraints: const BoxConstraints(),
                        icon: Icon(Icons.refresh, color: AppColors.txtSecondary(context), size: 20),
                        tooltip: 'Làm mới lịch trực',
                        onPressed: () => shiftProvider.fetchWeeklyShifts(),
                      ),
                      const SizedBox(width: 2),

                      // Shift requests history button
                      IconButton(
                        visualDensity: VisualDensity.compact,
                        padding: const EdgeInsets.all(6),
                        constraints: const BoxConstraints(),
                        icon: Icon(Icons.swap_horiz, color: AppColors.txtSecondary(context), size: 22),
                        tooltip: 'Lịch sử đổi / xin nghỉ ca',
                        onPressed: () => MyShiftRequestsSheet.show(context),
                      ),
                      const SizedBox(width: 4),

                      // Profile & Account Icon Menu (Top Right)
                      PopupMenuButton<String>(
                        icon: Icon(
                          Icons.account_circle,
                          color: AppColors.txtPrimary(context),
                          size: 30,
                        ),
                        tooltip: 'Tài khoản & Tùy chọn',
                        color: AppColors.crd(context),
                        elevation: 6,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16),
                          side: BorderSide(color: AppColors.crdBorder(context)),
                        ),
                        offset: const Offset(0, 42),
                        onSelected: (value) {
                          if (value == 'profile') {
                            _showProfileDialog(context, user);
                          } else if (value == 'requests') {
                            MyShiftRequestsSheet.show(context);
                          } else if (value == 'logout') {
                            _confirmLogout(context, authProvider);
                          }
                        },
                        itemBuilder: (ctx) => [
                          PopupMenuItem<String>(
                            enabled: false,
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  cleanName,
                                  style: TextStyle(
                                    fontSize: 14,
                                    fontWeight: FontWeight.bold,
                                    color: AppColors.txtPrimary(context),
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  userCode != null && userCode.isNotEmpty
                                      ? 'Mã NV: $userCode'
                                      : (user?.email ?? ''),
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: AppColors.txtMuted(context),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          const PopupMenuDivider(),
                          PopupMenuItem<String>(
                            value: 'profile',
                            child: Row(
                              children: [
                                Icon(Icons.person_outline, size: 19, color: AppColors.txtPrimary(context)),
                                const SizedBox(width: 10),
                                Text(
                                  'Hồ sơ cá nhân',
                                  style: TextStyle(fontSize: 13, color: AppColors.txtPrimary(context)),
                                ),
                              ],
                            ),
                          ),
                          PopupMenuItem<String>(
                            value: 'requests',
                            child: Row(
                              children: [
                                Icon(Icons.swap_horiz, size: 19, color: AppColors.txtPrimary(context)),
                                const SizedBox(width: 10),
                                Text(
                                  'Lịch sử đổi / xin nghỉ',
                                  style: TextStyle(fontSize: 13, color: AppColors.txtPrimary(context)),
                                ),
                              ],
                            ),
                          ),
                          const PopupMenuDivider(),
                          PopupMenuItem<String>(
                            value: 'logout',
                            child: const Row(
                              children: [
                                Icon(Icons.logout, size: 19, color: AppColors.dangerLight),
                                SizedBox(width: 10),
                                Text(
                                  'Đăng xuất',
                                  style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    color: AppColors.dangerLight,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),

              // Error notification if present
              if (shiftProvider.errorMessage != null)
                SliverToBoxAdapter(
                  child: Container(
                    margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: AppColors.danger.withAlpha(30),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppColors.danger.withAlpha(100)),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.error_outline, color: AppColors.dangerLight, size: 18),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            shiftProvider.errorMessage!,
                            style: const TextStyle(fontSize: 12, color: AppColors.dangerLight),
                          ),
                        ),
                        TextButton(
                          onPressed: () => shiftProvider.fetchWeeklyShifts(),
                          child: const Text('Thử lại', style: TextStyle(fontSize: 12, color: Colors.white)),
                        ),
                      ],
                    ),
                  ),
                ),

              // Weekly 7-Day Date Bar
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: WeeklyDateBar(),
                ),
              ),

              // Active Shift Card(s) for Selected Date
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: shiftProvider.isLoading && allWeekShifts.isEmpty
                      ? Container(
                          height: 180,
                          decoration: BoxDecoration(
                            color: AppColors.card,
                            borderRadius: BorderRadius.circular(20),
                          ),
                          child: const Center(
                            child: CircularProgressIndicator(color: AppColors.primaryLight),
                          ),
                        )
                      : (selectedShifts.isEmpty
                          ? ActiveShiftCard(
                              shift: null,
                              selectedDate: shiftProvider.selectedDate,
                            )
                          : Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                if (selectedShifts.length > 1)
                                  Padding(
                                    padding: const EdgeInsets.only(bottom: 8, left: 4),
                                    child: Row(
                                      children: [
                                        const Icon(Icons.layers_outlined, size: 14, color: AppColors.primaryLight),
                                        const SizedBox(width: 6),
                                        Text(
                                          'Có ${selectedShifts.length} ca trực trong ngày',
                                          style: const TextStyle(
                                            fontSize: 12,
                                            fontWeight: FontWeight.bold,
                                            color: AppColors.primaryLight,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                ...selectedShifts.asMap().entries.map((entry) {
                                  final idx = entry.key;
                                  final s = entry.value;
                                  return Padding(
                                    padding: EdgeInsets.only(
                                      bottom: idx < selectedShifts.length - 1 ? 12.0 : 0.0,
                                    ),
                                    child: ActiveShiftCard(
                                      shift: s,
                                      selectedDate: shiftProvider.selectedDate,
                                      shiftIndex: selectedShifts.length > 1 ? idx + 1 : null,
                                      totalShifts: selectedShifts.length > 1 ? selectedShifts.length : null,
                                    ),
                                  );
                                }),
                              ],
                            )),
                ),
              ),

              // Weekly Overview Section Header
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          'LỊCH TRỰC TRONG TUẦN',
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                            letterSpacing: 0.6,
                            color: AppColors.txtSecondary(context),
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                        decoration: BoxDecoration(
                          color: AppColors.surfLight(context),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Text(
                          '${allWeekShifts.length} ca trực',
                          style: const TextStyle(fontSize: 11, color: AppColors.primaryLight, fontWeight: FontWeight.bold),
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              // Weekly Shift List Items
              if (allWeekShifts.isEmpty && !shiftProvider.isLoading)
                SliverToBoxAdapter(
                  child: Container(
                    margin: const EdgeInsets.all(16),
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: AppColors.surf(context),
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: AppColors.crdBorder(context)),
                    ),
                    child: Center(
                      child: Text(
                        'Admin chưa tạo lịch trực cho tuần này.',
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 13, color: AppColors.txtMuted(context)),
                      ),
                    ),
                  ),
                )
              else
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 30),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate(
                      (context, index) {
                        final s = allWeekShifts[index];
                        final isSelected = s.shiftDate == shiftProvider.selectedDateFormatted;

                        return ShiftListItem(
                          shift: s,
                          isSelected: isSelected,
                          onTap: () {
                            try {
                              final d = DateTime.parse(s.shiftDate);
                              shiftProvider.setSelectedDate(d);
                            } catch (_) {}
                          },
                        );
                      },
                      childCount: allWeekShifts.length,
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
