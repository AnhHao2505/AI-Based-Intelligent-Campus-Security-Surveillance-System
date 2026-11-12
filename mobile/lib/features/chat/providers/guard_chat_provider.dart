import 'package:flutter/foundation.dart';
import '../../../core/utils/storage_helper.dart';
import '../../incident/services/stomp_service.dart';
import '../models/guard_chat_member.dart';
import '../models/guard_chat_message.dart';
import '../services/guard_chat_service.dart';

class GuardChatProvider extends ChangeNotifier {
  final GuardChatService chatService;
  final StompService stompService;

  List<GuardChatMember> _guards = [];
  bool _isLoadingGuards = false;
  bool _showOnDutyOnly = false;
  String _searchKeyword = '';

  int _totalUnreadCount = 0;

  String? _activePeerId;
  List<GuardChatMessage> _activeMessages = [];
  bool _isLoadingMessages = false;
  bool _isSendingMessage = false;

  String? _currentUserId;

  GuardChatProvider({
    required this.chatService,
    required this.stompService,
  }) {
    _initStompHook();
  }

  List<GuardChatMember> get guards => _guards;
  bool get isLoadingGuards => _isLoadingGuards;
  bool get showOnDutyOnly => _showOnDutyOnly;
  String get searchKeyword => _searchKeyword;
  int get totalUnreadCount => _totalUnreadCount;

  String? get activePeerId => _activePeerId;
  List<GuardChatMessage> get activeMessages => _activeMessages;
  bool get isLoadingMessages => _isLoadingMessages;
  bool get isSendingMessage => _isSendingMessage;
  String? get currentUserId => _currentUserId;

  List<GuardChatMember> get filteredGuards {
    return _guards.where((g) {
      if (_showOnDutyOnly && !g.onDuty) {
        return false;
      }
      if (_searchKeyword.trim().isNotEmpty) {
        final q = _searchKeyword.trim().toLowerCase();
        final nameMatch = g.fullName.toLowerCase().contains(q);
        final codeMatch = (g.userCode ?? '').toLowerCase().contains(q);
        final shiftMatch = (g.currentShiftName ?? '').toLowerCase().contains(q);
        final bldgMatch = (g.assignedBuilding ?? '').toLowerCase().contains(q);
        if (!nameMatch && !codeMatch && !shiftMatch && !bldgMatch) {
          return false;
        }
      }
      return true;
    }).toList();
  }

  void _initStompHook() {
    stompService.onChatMessage = handleIncomingChatMessage;
  }

  Future<void> fetchGuards() async {
    _isLoadingGuards = true;
    notifyListeners();

    try {
      final user = await StorageHelper.getUser();
      _currentUserId = user?.id;

      final results = await chatService.getGuards();
      _guards = results;
      _totalUnreadCount = _guards.fold(0, (sum, g) => sum + g.unreadCount);
    } catch (e) {
      debugPrint('[GuardChatProvider] Error fetching guards: $e');
    } finally {
      _isLoadingGuards = false;
      notifyListeners();
    }
  }

  Future<void> refreshUnreadCount() async {
    try {
      final count = await chatService.getUnreadCount();
      _totalUnreadCount = count;
      notifyListeners();
    } catch (_) {}
  }

  void toggleOnDutyOnly(bool value) {
    _showOnDutyOnly = value;
    notifyListeners();
  }

  void setSearchKeyword(String query) {
    _searchKeyword = query;
    notifyListeners();
  }

  Future<void> openConversation(String peerId) async {
    _activePeerId = peerId;
    _activeMessages = [];
    _isLoadingMessages = true;
    notifyListeners();

    try {
      final user = await StorageHelper.getUser();
      _currentUserId = user?.id;

      final msgs = await chatService.getMessages(peerId, limit: 60);
      _activeMessages = msgs;

      // Mark as read
      await chatService.markAsRead(peerId);

      // Reset local unread count for this guard
      final idx = _guards.indexWhere((g) => g.id == peerId);
      if (idx >= 0) {
        final prevUnread = _guards[idx].unreadCount;
        _guards[idx] = _guards[idx].copyWith(unreadCount: 0);
        _totalUnreadCount = (_totalUnreadCount - prevUnread).clamp(0, 9999);
      }
    } catch (e) {
      debugPrint('[GuardChatProvider] Error opening conversation: $e');
    } finally {
      _isLoadingMessages = false;
      notifyListeners();
    }
  }

  void closeConversation() {
    _activePeerId = null;
    _activeMessages = [];
    notifyListeners();
  }

  Future<bool> sendMessage(String content) async {
    if (_activePeerId == null || content.trim().isEmpty) return false;

    _isSendingMessage = true;
    notifyListeners();

    try {
      final sent = await chatService.sendMessage(_activePeerId!, content.trim());
      _activeMessages.add(sent);

      // Update last message in guard member list
      final idx = _guards.indexWhere((g) => g.id == _activePeerId);
      if (idx >= 0) {
        _guards[idx] = _guards[idx].copyWith(
          lastMessage: sent.content,
          lastMessageAt: sent.createdAt,
        );
      }

      notifyListeners();
      return true;
    } catch (e) {
      debugPrint('[GuardChatProvider] Error sending message: $e');
      return false;
    } finally {
      _isSendingMessage = false;
      notifyListeners();
    }
  }

  void handleIncomingChatMessage(Map<String, dynamic> json) {
    try {
      final msg = GuardChatMessage.fromJson(json);

      // If active conversation matches either sender or receiver
      if (_activePeerId != null &&
          (msg.senderId == _activePeerId || msg.receiverId == _activePeerId)) {
        // Prevent duplicate if already added by sendMessage
        final exists = _activeMessages.any((m) =>
            m.id != null && msg.id != null && m.id == msg.id);
        if (!exists) {
          _activeMessages.add(msg);
        }

        // Auto mark as read if received from peer while viewing conversation
        if (msg.senderId == _activePeerId) {
          chatService.markAsRead(_activePeerId!).catchError((_) {});
        }
      } else {
        // Not currently active conversation, increase unread if addressed to me
        if (_currentUserId != null && msg.receiverId == _currentUserId) {
          _totalUnreadCount += 1;

          final idx = _guards.indexWhere((g) => g.id == msg.senderId);
          if (idx >= 0) {
            _guards[idx] = _guards[idx].copyWith(
              unreadCount: _guards[idx].unreadCount + 1,
              lastMessage: msg.content,
              lastMessageAt: msg.createdAt,
            );
          }
        }
      }

      // Update lastMessage preview for relevant guard
      final targetId = msg.senderId == _currentUserId ? msg.receiverId : msg.senderId;
      final targetIdx = _guards.indexWhere((g) => g.id == targetId);
      if (targetIdx >= 0) {
        _guards[targetIdx] = _guards[targetIdx].copyWith(
          lastMessage: msg.content,
          lastMessageAt: msg.createdAt,
        );
      }

      notifyListeners();
    } catch (e) {
      debugPrint('[GuardChatProvider] Error processing incoming chat message: $e');
    }
  }
}

