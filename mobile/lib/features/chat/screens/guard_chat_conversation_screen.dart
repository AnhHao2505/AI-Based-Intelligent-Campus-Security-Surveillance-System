import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../models/guard_chat_member.dart';
import '../models/guard_chat_message.dart';
import '../providers/guard_chat_provider.dart';

class GuardChatConversationScreen extends StatefulWidget {
  final GuardChatMember guard;

  const GuardChatConversationScreen({
    super.key,
    required this.guard,
  });

  @override
  State<GuardChatConversationScreen> createState() => _GuardChatConversationScreenState();
}

class _GuardChatConversationScreenState extends State<GuardChatConversationScreen> {
  final TextEditingController _textController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<GuardChatProvider>().openConversation(widget.guard.id);
    });
  }

  @override
  void dispose() {
    _textController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _scrollToBottom({bool animated = true}) {
    if (!_scrollController.hasClients) return;
    if (animated) {
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent + 80,
        duration: const Duration(milliseconds: 250),
        curve: Curves.easeOut,
      );
    } else {
      _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
    }
  }

  Future<void> _handleSend() async {
    final text = _textController.text.trim();
    if (text.isEmpty) return;

    _textController.clear();
    final provider = context.read<GuardChatProvider>();
    final success = await provider.sendMessage(text);
    if (success) {
      Future.delayed(const Duration(milliseconds: 100), () => _scrollToBottom(animated: true));
    }
  }

  void _copyPhoneNumber(String phone) {
    Clipboard.setData(ClipboardData(text: phone));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Đã sao chép số điện thoại: $phone'),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final guard = widget.guard;

    return PopScope(
      onPopInvokedWithResult: (didPop, result) {
        context.read<GuardChatProvider>().closeConversation();
      },
      child: Scaffold(
        backgroundColor: AppColors.bg(context),
        appBar: AppBar(
          backgroundColor: AppColors.crd(context),
          elevation: 1,
          titleSpacing: 0,
          leading: IconButton(
            icon: Icon(Icons.arrow_back_rounded, color: AppColors.txtPrimary(context)),
            onPressed: () => Navigator.pop(context),
          ),
          title: Row(
            children: [
              Stack(
                children: [
                  CircleAvatar(
                    radius: 20,
                    backgroundColor: AppColors.primary.withOpacity(0.15),
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
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      guard.fullName,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                        color: AppColors.txtPrimary(context),
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      guard.onDuty
                          ? (guard.currentShiftName != null
                              ? 'Đang trực (${guard.currentShiftName})'
                              : 'Đang trong ca trực')
                          : 'Ngoài ca trực',
                      style: TextStyle(
                        fontSize: 11,
                        color: guard.onDuty ? AppColors.success : AppColors.txtMuted(context),
                        fontWeight: guard.onDuty ? FontWeight.w600 : FontWeight.normal,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
            ],
          ),
          actions: [
            if (guard.phoneNumber != null && guard.phoneNumber!.isNotEmpty)
              IconButton(
                icon: const Icon(Icons.phone_outlined, color: AppColors.primary),
                tooltip: 'Sao chép SĐT',
                onPressed: () => _copyPhoneNumber(guard.phoneNumber!),
              ),
            const SizedBox(width: 6),
          ],
        ),
        body: Column(
          children: [
            // Shift info banner if on duty
            if (guard.onDuty && (guard.currentShiftTime != null || guard.assignedBuilding != null))
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                decoration: BoxDecoration(
                  color: AppColors.primary.withOpacity(0.08),
                  border: Border(
                    bottom: BorderSide(
                      color: AppColors.primary.withOpacity(0.2),
                      width: 0.8,
                    ),
                  ),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.info_outline_rounded, size: 15, color: AppColors.primary),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text(
                        'Vị trí: ${guard.assignedBuilding ?? "Toàn khuôn viên"}  •  Ca: ${guard.currentShiftTime ?? ""}',
                        style: const TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w500,
                          color: AppColors.primary,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),

            // Message list
            Expanded(
              child: Consumer<GuardChatProvider>(
                builder: (context, chatProvider, _) {
                  if (chatProvider.isLoadingMessages) {
                    return const Center(child: CircularProgressIndicator(strokeWidth: 2.5));
                  }

                  final messages = chatProvider.activeMessages;
                  if (messages.isEmpty) {
                    return Center(
                      child: Padding(
                        padding: const EdgeInsets.all(32),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              padding: const EdgeInsets.all(16),
                              decoration: BoxDecoration(
                                color: AppColors.surf(context),
                                shape: BoxShape.circle,
                              ),
                              child: Icon(
                                Icons.chat_bubble_outline_rounded,
                                size: 36,
                                color: AppColors.txtMuted(context),
                              ),
                            ),
                            const SizedBox(height: 12),
                            Text(
                              'Chưa có tin nhắn nào',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                                color: AppColors.txtSecondary(context),
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              'Gửi tin nhắn để bắt đầu trao đổi với ${guard.fullName}',
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                fontSize: 12,
                                color: AppColors.txtMuted(context),
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  }

                  WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToBottom(animated: false));

                  return ListView.builder(
                    controller: _scrollController,
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                    itemCount: messages.length,
                    itemBuilder: (context, index) {
                      final msg = messages[index];
                      final isMe = msg.senderId == chatProvider.currentUserId;
                      return _buildMessageBubble(context, msg, isMe, isDark);
                    },
                  );
                },
              ),
            ),

            // Input bar
            Container(
              padding: EdgeInsets.fromLTRB(
                12,
                8,
                12,
                8 + MediaQuery.of(context).padding.bottom,
              ),
              decoration: BoxDecoration(
                color: AppColors.crd(context),
                border: Border(
                  top: BorderSide(color: AppColors.crdBorder(context), width: 0.8),
                ),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Container(
                      decoration: BoxDecoration(
                        color: AppColors.surf(context),
                        borderRadius: BorderRadius.circular(20),
                        border: Border.all(color: AppColors.crdBorder(context)),
                      ),
                      child: TextField(
                        controller: _textController,
                        style: TextStyle(
                          fontSize: 13.5,
                          color: AppColors.txtPrimary(context),
                        ),
                        maxLines: 4,
                        minLines: 1,
                        textInputAction: TextInputAction.send,
                        onSubmitted: (_) => _handleSend(),
                        decoration: InputDecoration(
                          hintText: 'Nhập tin nhắn cho ${guard.fullName}...',
                          hintStyle: TextStyle(
                            fontSize: 13,
                            color: AppColors.txtMuted(context),
                          ),
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 10,
                          ),
                          border: InputBorder.none,
                          isDense: true,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Consumer<GuardChatProvider>(
                    builder: (context, chatProvider, _) {
                      return SizedBox(
                        width: 42,
                        height: 42,
                        child: ElevatedButton(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: AppColors.primary,
                            foregroundColor: Colors.white,
                            padding: EdgeInsets.zero,
                            shape: const CircleBorder(),
                            elevation: 1,
                          ),
                          onPressed: chatProvider.isSendingMessage ? null : _handleSend,
                          child: chatProvider.isSendingMessage
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: Colors.white,
                                  ),
                                )
                              : const Icon(Icons.send_rounded, size: 18),
                        ),
                      );
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMessageBubble(
    BuildContext context,
    GuardChatMessage msg,
    bool isMe,
    bool isDark,
  ) {
    final timeStr = DateFormat('HH:mm').format(msg.createdAt.toLocal());

    return Align(
      alignment: isMe ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.74,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 9),
        decoration: BoxDecoration(
          color: isMe
              ? AppColors.primary
              : (isDark ? const Color(0xFF2A2D34) : const Color(0xFFF1F3F5)),
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(14),
            topRight: const Radius.circular(14),
            bottomLeft: Radius.circular(isMe ? 14 : 3),
            bottomRight: Radius.circular(isMe ? 3 : 14),
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withOpacity(0.04),
              blurRadius: 3,
              offset: const Offset(0, 1),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: isMe ? CrossAxisAlignment.end : CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              msg.content,
              style: TextStyle(
                fontSize: 13.5,
                height: 1.35,
                color: isMe
                    ? Colors.white
                    : AppColors.txtPrimary(context),
              ),
            ),
            const SizedBox(height: 3),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  timeStr,
                  style: TextStyle(
                    fontSize: 10,
                    color: isMe
                        ? Colors.white.withOpacity(0.75)
                        : AppColors.txtMuted(context),
                  ),
                ),
                if (isMe) ...[
                  const SizedBox(width: 4),
                  Icon(
                    msg.isRead ? Icons.done_all_rounded : Icons.done_rounded,
                    size: 13,
                    color: msg.isRead ? Colors.lightBlueAccent : Colors.white.withOpacity(0.75),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }
}
