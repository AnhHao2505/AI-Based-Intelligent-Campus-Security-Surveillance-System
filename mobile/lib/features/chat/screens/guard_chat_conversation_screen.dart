import 'dart:io';
import 'package:image_picker/image_picker.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';
import '../../../core/constants/app_colors.dart';
import '../../../core/widgets/authorized_network_image.dart';
import '../models/guard_chat_member.dart';
import '../models/guard_chat_message.dart';
import '../providers/guard_chat_provider.dart';

class GuardChatConversationScreen extends StatefulWidget {
  final GuardChatMember guard;

  const GuardChatConversationScreen({super.key, required this.guard});

  @override
  State<GuardChatConversationScreen> createState() =>
      _GuardChatConversationScreenState();
}

class _GuardChatConversationScreenState
    extends State<GuardChatConversationScreen> {
  final TextEditingController _textController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final ImagePicker _imagePicker = ImagePicker();
  File? _selectedImage;

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

  Future<void> _pickImage(ImageSource source) async {
    try {
      final picked = await _imagePicker.pickImage(
        source: source,
        imageQuality: 85,
        maxWidth: 1600,
        maxHeight: 1600,
      );
      if (picked != null && mounted) {
        setState(() {
          _selectedImage = File(picked.path);
        });
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Không thể chọn hình ảnh: $e'),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  void _showImageSourceSheet() {
    showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.photo_library_outlined, color: AppColors.primary),
              title: const Text('Chọn ảnh từ thư viện'),
              onTap: () {
                Navigator.pop(ctx);
                _pickImage(ImageSource.gallery);
              },
            ),
            ListTile(
              leading: const Icon(Icons.camera_alt_outlined, color: AppColors.primary),
              title: const Text('Chụp ảnh mới'),
              onTap: () {
                Navigator.pop(ctx);
                _pickImage(ImageSource.camera);
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _handleSend() async {
    final text = _textController.text.trim();
    final imageToSend = _selectedImage;
    if (text.isEmpty && imageToSend == null) return;

    final provider = context.read<GuardChatProvider>();
    if (provider.isSendingMessage) return;

    _textController.clear();
    if (imageToSend != null) {
      setState(() {
        _selectedImage = null;
      });
    }

    final success = imageToSend != null
        ? await provider.sendImageMessage(imageToSend, content: text)
        : await provider.sendMessage(text);

    if (success) {
      Future.delayed(
        const Duration(milliseconds: 100),
        () => _scrollToBottom(animated: true),
      );
    } else if (mounted) {
      setState(() {
        if (imageToSend != null) {
          _selectedImage = imageToSend;
        }
        if (text.isNotEmpty && _textController.text.isEmpty) {
          _textController.text = text;
        }
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            imageToSend != null
                ? 'Không gửi được hình ảnh. Vui lòng thử lại.'
                : 'Không gửi được tin nhắn. Vui lòng thử lại.',
          ),
          behavior: SnackBarBehavior.floating,
        ),
      );
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
            icon: Icon(
              Icons.arrow_back_rounded,
              color: AppColors.txtPrimary(context),
            ),
            onPressed: () => Navigator.pop(context),
          ),
          title: Row(
            children: [
              Stack(
                children: [
                  CircleAvatar(
                    radius: 20,
                    backgroundColor: AppColors.primary.withValues(alpha: 0.15),
                    backgroundImage:
                        guard.avatarUrl != null && guard.avatarUrl!.isNotEmpty
                        ? NetworkImage(guard.avatarUrl!)
                        : null,
                    child: guard.avatarUrl == null || guard.avatarUrl!.isEmpty
                        ? Text(
                            guard.fullName.isNotEmpty
                                ? guard.fullName[0].toUpperCase()
                                : 'B',
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
                        color: guard.onDuty
                            ? AppColors.success
                            : Colors.grey.shade400,
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
                      guard.isFacilityManager
                          ? 'Quản lý CSVC'
                          : (guard.onDuty
                                ? (guard.currentShiftName != null
                                      ? 'Đang trực (${guard.currentShiftName})'
                                      : 'Đang trong ca trực')
                                : 'Ngoài ca trực'),
                      style: TextStyle(
                        fontSize: 11,
                        color: guard.isFacilityManager
                            ? AppColors.primary
                            : (guard.onDuty
                                  ? AppColors.success
                                  : AppColors.txtMuted(context)),
                        fontWeight: (guard.onDuty || guard.isFacilityManager)
                            ? FontWeight.w600
                            : FontWeight.normal,
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
                icon: const Icon(
                  Icons.phone_outlined,
                  color: AppColors.primary,
                ),
                tooltip: 'Sao chép SĐT',
                onPressed: () => _copyPhoneNumber(guard.phoneNumber!),
              ),
            const SizedBox(width: 6),
          ],
        ),
        body: Column(
          children: [
            // Shift info banner if on duty
            if (guard.onDuty &&
                (guard.currentShiftTime != null ||
                    guard.assignedBuilding != null))
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: AppColors.primary.withValues(alpha: 0.08),
                  border: Border(
                    bottom: BorderSide(
                      color: AppColors.primary.withValues(alpha: 0.2),
                      width: 0.8,
                    ),
                  ),
                ),
                child: Row(
                  children: [
                    const Icon(
                      Icons.info_outline_rounded,
                      size: 15,
                      color: AppColors.primary,
                    ),
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
                    return const Center(
                      child: CircularProgressIndicator(strokeWidth: 2.5),
                    );
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

                  WidgetsBinding.instance.addPostFrameCallback(
                    (_) => _scrollToBottom(animated: false),
                  );

                  return ListView.builder(
                    controller: _scrollController,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 12,
                    ),
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

            // Selected image attachment preview
            if (_selectedImage != null)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                decoration: BoxDecoration(
                  color: AppColors.crd(context),
                  border: Border(
                    top: BorderSide(
                      color: AppColors.crdBorder(context),
                      width: 0.8,
                    ),
                  ),
                ),
                child: Row(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: Image.file(
                        _selectedImage!,
                        width: 52,
                        height: 52,
                        fit: BoxFit.cover,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'Đã chọn ảnh đính kèm (có thể thêm chú thích)',
                        style: TextStyle(
                          fontSize: 12,
                          color: AppColors.txtSecondary(context),
                        ),
                      ),
                    ),
                    IconButton(
                      icon: Icon(
                        Icons.close_rounded,
                        size: 20,
                        color: AppColors.txtMuted(context),
                      ),
                      tooltip: 'Bỏ chọn ảnh',
                      onPressed: () {
                        setState(() {
                          _selectedImage = null;
                        });
                      },
                    ),
                  ],
                ),
              ),

            // Input bar
            Container(
              padding: EdgeInsets.fromLTRB(
                8,
                8,
                12,
                8 + MediaQuery.of(context).padding.bottom,
              ),
              decoration: BoxDecoration(
                color: AppColors.crd(context),
                border: Border(
                  top: BorderSide(
                    color: AppColors.crdBorder(context),
                    width: 0.8,
                  ),
                ),
              ),
              child: Consumer<GuardChatProvider>(
                builder: (context, chatProvider, _) {
                  return Row(
                    children: [
                      IconButton(
                        icon: Icon(
                          Icons.add_photo_alternate_outlined,
                          color: chatProvider.isSendingMessage
                              ? AppColors.txtMuted(context)
                              : AppColors.primary,
                        ),
                        tooltip: 'Gửi hình ảnh',
                        onPressed: chatProvider.isSendingMessage
                            ? null
                            : _showImageSourceSheet,
                      ),
                      Expanded(
                        child: Container(
                          decoration: BoxDecoration(
                            color: AppColors.surf(context),
                            borderRadius: BorderRadius.circular(20),
                            border: Border.all(
                              color: AppColors.crdBorder(context),
                            ),
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
                              hintText: _selectedImage != null
                                  ? 'Thêm chú thích ảnh...'
                                  : 'Nhập tin nhắn cho ${guard.fullName}...',
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
                      SizedBox(
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
                          onPressed: chatProvider.isSendingMessage
                              ? null
                              : _handleSend,
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
                      ),
                    ],
                  );
                },
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
    final hasText = msg.content.trim().isNotEmpty;

    return Align(
      alignment: isMe ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.74,
        ),
        padding: EdgeInsets.symmetric(
          horizontal: msg.hasImage ? 8 : 13,
          vertical: msg.hasImage ? 8 : 9,
        ),
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
              color: Colors.black.withValues(alpha: 0.04),
              blurRadius: 3,
              offset: const Offset(0, 1),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: isMe
              ? CrossAxisAlignment.end
              : CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            if (msg.hasImage) ...[
              AuthorizedNetworkImage(
                imageUrl: msg.imageUrl,
                width: 210,
                height: 160,
                fit: BoxFit.cover,
                borderRadius: BorderRadius.circular(10),
                enableZoomOnTap: true,
              ),
              if (hasText) const SizedBox(height: 6),
            ],
            if (hasText)
              Padding(
                padding: EdgeInsets.symmetric(horizontal: msg.hasImage ? 4 : 0),
                child: Text(
                  msg.content,
                  style: TextStyle(
                    fontSize: 13.5,
                    height: 1.35,
                    color: isMe ? Colors.white : AppColors.txtPrimary(context),
                  ),
                ),
              ),
            const SizedBox(height: 3),
            Padding(
              padding: EdgeInsets.symmetric(horizontal: msg.hasImage ? 4 : 0),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    timeStr,
                    style: TextStyle(
                      fontSize: 10,
                      color: isMe
                          ? Colors.white.withValues(alpha: 0.75)
                          : AppColors.txtMuted(context),
                    ),
                  ),
                  if (isMe) ...[
                    const SizedBox(width: 4),
                    Icon(
                      msg.isRead ? Icons.done_all_rounded : Icons.done_rounded,
                      size: 13,
                      color: msg.isRead
                          ? Colors.lightBlueAccent
                          : Colors.white.withValues(alpha: 0.75),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
