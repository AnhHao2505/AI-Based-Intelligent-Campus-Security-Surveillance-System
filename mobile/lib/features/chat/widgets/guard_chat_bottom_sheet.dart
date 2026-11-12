import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../models/guard_chat_member.dart';
import '../providers/guard_chat_provider.dart';
import '../screens/guard_chat_conversation_screen.dart';

class GuardChatBottomSheet extends StatefulWidget {
  const GuardChatBottomSheet({super.key});

  static Future<void> show(BuildContext context) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => const GuardChatBottomSheet(),
    );
  }

  @override
  State<GuardChatBottomSheet> createState() => _GuardChatBottomSheetState();
}

class _GuardChatBottomSheetState extends State<GuardChatBottomSheet> {
  final TextEditingController _searchCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<GuardChatProvider>().fetchGuards();
    });
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final chatProvider = context.watch<GuardChatProvider>();
    final guards = chatProvider.filteredGuards;
    final totalOnDuty = chatProvider.guards.where((g) => g.onDuty).length;
    final sheetHeight = MediaQuery.of(context).size.height * 0.85;

    return Container(
      height: sheetHeight,
      decoration: BoxDecoration(
        color: AppColors.crd(context),
        borderRadius: const BorderRadius.vertical(top: Radius.circular(20)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.18),
            blurRadius: 16,
            offset: const Offset(0, -4),
          ),
        ],
      ),
      child: Column(
        children: [
          // Drag handle
          Center(
            child: Container(
              margin: const EdgeInsets.only(top: 10, bottom: 6),
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.crdBorder(context),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),

          // Header
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(7),
                  decoration: BoxDecoration(
                    color: AppColors.primary.withOpacity(0.12),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(Icons.chat_rounded, color: AppColors.primary, size: 20),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Tin nhắn Bảo vệ',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: AppColors.txtPrimary(context),
                        ),
                      ),
                      Text(
                        '${chatProvider.guards.length} nhân sự  •  $totalOnDuty đang trong ca',
                        style: TextStyle(
                          fontSize: 12,
                          color: AppColors.txtMuted(context),
                        ),
                      ),
                    ],
                  ),
                ),
                IconButton(
                  icon: Icon(Icons.close_rounded, color: AppColors.txtMuted(context)),
                  onPressed: () => Navigator.pop(context),
                ),
              ],
            ),
          ),

          // Filter bar: All vs On-Duty this shift
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: Row(
              children: [
                Expanded(
                  child: _FilterTabButton(
                    label: 'Tất cả (${chatProvider.guards.length})',
                    isSelected: !chatProvider.showOnDutyOnly,
                    onTap: () => chatProvider.toggleOnDutyOnly(false),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: _FilterTabButton(
                    label: 'Đang trong ca ($totalOnDuty)',
                    showDot: true,
                    isSelected: chatProvider.showOnDutyOnly,
                    onTap: () => chatProvider.toggleOnDutyOnly(true),
                  ),
                ),
              ],
            ),
          ),

          // Search field
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
            child: Container(
              decoration: BoxDecoration(
                color: AppColors.surf(context),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.crdBorder(context)),
              ),
              child: TextField(
                controller: _searchCtrl,
                style: TextStyle(fontSize: 13, color: AppColors.txtPrimary(context)),
                onChanged: chatProvider.setSearchKeyword,
                decoration: InputDecoration(
                  hintText: 'Tìm theo tên, mã số, ca, toà nhà...',
                  hintStyle: TextStyle(fontSize: 12.5, color: AppColors.txtMuted(context)),
                  prefixIcon: const Icon(Icons.search_rounded, size: 20),
                  prefixIconColor: AppColors.txtMuted(context),
                  suffixIcon: _searchCtrl.text.isNotEmpty
                      ? IconButton(
                          icon: const Icon(Icons.clear_rounded, size: 18),
                          onPressed: () {
                            _searchCtrl.clear();
                            chatProvider.setSearchKeyword('');
                          },
                        )
                      : null,
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(vertical: 10),
                  border: InputBorder.none,
                ),
              ),
            ),
          ),

          const Divider(height: 1, thickness: 0.8),

          // Guard List
          Expanded(
            child: chatProvider.isLoadingGuards
                ? const Center(child: CircularProgressIndicator(strokeWidth: 2.5))
                : guards.isEmpty
                    ? Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.person_search_rounded,
                                size: 48, color: AppColors.txtMuted(context)),
                            const SizedBox(height: 8),
                            Text(
                              chatProvider.showOnDutyOnly
                                  ? 'Hiện không có bảo vệ nào đang trong ca trực'
                                  : 'Không tìm thấy bảo vệ nào phù hợp',
                              style: TextStyle(
                                fontSize: 13,
                                color: AppColors.txtSecondary(context),
                              ),
                            ),
                          ],
                        ),
                      )
                    : ListView.separated(
                        padding: const EdgeInsets.symmetric(vertical: 6),
                        itemCount: guards.length,
                        separatorBuilder: (_, __) => Divider(
                          height: 1,
                          indent: 72,
                          color: AppColors.crdBorder(context).withOpacity(0.5),
                        ),
                        itemBuilder: (context, index) {
                          final guard = guards[index];
                          return _GuardListTile(
                            guard: guard,
                            onTap: () {
                              Navigator.pop(context); // Close bottom sheet
                              Navigator.push(
                                context,
                                MaterialPageRoute(
                                  builder: (_) => GuardChatConversationScreen(guard: guard),
                                ),
                              );
                            },
                          );
                        },
                      ),
          ),
        ],
      ),
    );
  }
}

class _FilterTabButton extends StatelessWidget {
  final String label;
  final bool isSelected;
  final bool showDot;
  final VoidCallback onTap;

  const _FilterTabButton({
    required this.label,
    required this.isSelected,
    this.showDot = false,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(vertical: 8),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: isSelected
              ? AppColors.primary
              : AppColors.surf(context),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: isSelected ? AppColors.primary : AppColors.crdBorder(context),
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (showDot) ...[
              Container(
                width: 7,
                height: 7,
                decoration: BoxDecoration(
                  color: isSelected ? Colors.white : AppColors.success,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 6),
            ],
            Text(
              label,
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                color: isSelected ? Colors.white : AppColors.txtSecondary(context),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GuardListTile extends StatelessWidget {
  final GuardChatMember guard;
  final VoidCallback onTap;

  const _GuardListTile({
    required this.guard,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final timeStr = guard.lastMessageAt != null
        ? DateFormat('HH:mm').format(guard.lastMessageAt!.toLocal())
        : null;

    return ListTile(
      onTap: onTap,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      leading: Stack(
        children: [
          CircleAvatar(
            radius: 22,
            backgroundColor: AppColors.primary.withOpacity(0.12),
            backgroundImage: guard.avatarUrl != null && guard.avatarUrl!.isNotEmpty
                ? NetworkImage(guard.avatarUrl!)
                : null,
            child: guard.avatarUrl == null || guard.avatarUrl!.isEmpty
                ? Text(
                    guard.fullName.isNotEmpty ? guard.fullName[0].toUpperCase() : 'B',
                    style: const TextStyle(
                      color: AppColors.primary,
                      fontWeight: FontWeight.bold,
                      fontSize: 16,
                    ),
                  )
                : null,
          ),
          Positioned(
            right: 0,
            bottom: 0,
            child: Container(
              width: 11,
              height: 11,
              decoration: BoxDecoration(
                color: guard.onDuty ? AppColors.success : Colors.grey.shade400,
                shape: BoxShape.circle,
                border: Border.all(
                  color: AppColors.crd(context),
                  width: 2,
                ),
              ),
            ),
          ),
        ],
      ),
      title: Row(
        children: [
          Expanded(
            child: Text(
              guard.fullName,
              style: TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: AppColors.txtPrimary(context),
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (timeStr != null)
            Text(
              timeStr,
              style: TextStyle(
                fontSize: 11,
                color: guard.unreadCount > 0 ? AppColors.primary : AppColors.txtMuted(context),
                fontWeight: guard.unreadCount > 0 ? FontWeight.bold : FontWeight.normal,
              ),
            ),
        ],
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 3),
          Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1.5),
                decoration: BoxDecoration(
                  color: guard.onDuty
                      ? AppColors.success.withOpacity(0.12)
                      : AppColors.surf(context),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  guard.onDuty
                      ? (guard.currentShiftName != null ? 'Ca: ${guard.currentShiftName}' : 'Đang trực')
                      : 'Ngoại ca',
                  style: TextStyle(
                    fontSize: 10.5,
                    fontWeight: FontWeight.w600,
                    color: guard.onDuty ? AppColors.success : AppColors.txtMuted(context),
                  ),
                ),
              ),
              if (guard.assignedBuilding != null && guard.assignedBuilding!.isNotEmpty) ...[
                const SizedBox(width: 6),
                Text(
                  '• ${guard.assignedBuilding}',
                  style: TextStyle(
                    fontSize: 11,
                    color: AppColors.txtSecondary(context),
                  ),
                ),
              ],
            ],
          ),
          if (guard.lastMessage != null && guard.lastMessage!.isNotEmpty) ...[
            const SizedBox(height: 3),
            Row(
              children: [
                Expanded(
                  child: Text(
                    guard.lastMessage!,
                    style: TextStyle(
                      fontSize: 12,
                      color: guard.unreadCount > 0
                          ? AppColors.txtPrimary(context)
                          : AppColors.txtMuted(context),
                      fontWeight: guard.unreadCount > 0 ? FontWeight.w600 : FontWeight.normal,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (guard.unreadCount > 0)
                  Container(
                    margin: const EdgeInsets.only(left: 6),
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: AppColors.danger,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(
                      '${guard.unreadCount}',
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

