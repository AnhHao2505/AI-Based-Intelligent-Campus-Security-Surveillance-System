import '../../../core/constants/api_endpoints.dart';
import '../../../core/network/api_client.dart';
import '../models/guard_chat_member.dart';
import '../models/guard_chat_message.dart';

class GuardChatService {
  final ApiClient apiClient;

  GuardChatService(this.apiClient);

  Future<List<GuardChatMember>> getGuards() async {
    final response = await apiClient.dio.get(ApiEndpoints.guardChatGuards);
    final data = response.data;
    if (data is List) {
      return data.map((e) => GuardChatMember.fromJson(e as Map<String, dynamic>)).toList();
    }
    return [];
  }

  Future<List<GuardChatMessage>> getMessages(String peerId, {int limit = 50}) async {
    final response = await apiClient.dio.get(ApiEndpoints.guardChatMessages(peerId, limit: limit));
    final data = response.data;
    if (data is List) {
      return data.map((e) => GuardChatMessage.fromJson(e as Map<String, dynamic>)).toList();
    }
    return [];
  }

  Future<GuardChatMessage> sendMessage(String receiverId, String content) async {
    final response = await apiClient.dio.post(
      ApiEndpoints.guardChatSendMessage,
      data: {'receiverId': receiverId, 'content': content},
    );
    return GuardChatMessage.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> markAsRead(String peerId) async {
    await apiClient.dio.post(ApiEndpoints.guardChatMarkRead(peerId));
  }

  Future<int> getUnreadCount() async {
    final response = await apiClient.dio.get(ApiEndpoints.guardChatUnreadCount);
    if (response.data is num) {
      return (response.data as num).toInt();
    }
    return 0;
  }
}

