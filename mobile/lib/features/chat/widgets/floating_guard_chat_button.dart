import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../providers/guard_chat_provider.dart';
import 'guard_chat_bottom_sheet.dart';

class FloatingGuardChatButton extends StatelessWidget {
  final String heroTag;

  const FloatingGuardChatButton({
    super.key,
    this.heroTag = 'floating_guard_chat_btn',
  });

  @override
  Widget build(BuildContext context) {
    return Consumer<GuardChatProvider>(
      builder: (context, chatProvider, _) {
        final unreadCount = chatProvider.totalUnreadCount;

        return Stack(
          clipBehavior: Clip.none,
          children: [
            FloatingActionButton(
              heroTag: heroTag,
              elevation: 4,
              backgroundColor: const Color(0xFF0F766E), // Deep teal accent for messenger
              foregroundColor: Colors.white,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
              ),
              onPressed: () {
                GuardChatBottomSheet.show(context);
              },
              tooltip: 'Nhắn tin bảo vệ',
              child: const Icon(Icons.forum_rounded, size: 24),
            ),
            if (unreadCount > 0)
              Positioned(
                top: -4,
                right: -4,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: AppColors.danger,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: AppColors.crd(context),
                      width: 2,
                    ),
                  ),
                  constraints: const BoxConstraints(
                    minWidth: 20,
                    minHeight: 20,
                  ),
                  child: Text(
                    unreadCount > 99 ? '99+' : '$unreadCount',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 10,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

