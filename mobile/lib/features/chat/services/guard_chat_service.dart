import 'dart:io';
import 'package:dio/dio.dart';
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
    List<dynamic>? rawList;
    if (data is List) {
      rawList = data;
    } else if (data is Map && data['content'] is List) {
      rawList = data['content'] as List;
    }
    if (rawList == null) return [];
    final messages = rawList
        .map((e) => GuardChatMessage.fromJson(e as Map<String, dynamic>))
        .toList();
    messages.sort((a, b) => a.createdAt.compareTo(b.createdAt));
    return messages;
  }

  Future<GuardChatMessage> sendMessage(String receiverId, String content, {String? imageUrl}) async {
    final response = await apiClient.dio.post(
      ApiEndpoints.guardChatSendMessage,
      data: {
        'receiverId': receiverId,
        'content': content,
        if (imageUrl != null && imageUrl.isNotEmpty) 'imageUrl': imageUrl,
      },
    );
    return GuardChatMessage.fromJson(response.data as Map<String, dynamic>);
  }

  Future<GuardChatMessage> sendImageMessage(
    String receiverId,
    File imageFile, {
    String? content,
  }) async {
    final fileName = imageFile.path.split(RegExp(r'[/\\]')).last;
    final formData = FormData.fromMap({
      'receiverId': receiverId,
      if (content != null && content.trim().isNotEmpty) 'content': content.trim(),
      'file': await MultipartFile.fromFile(
        imageFile.path,
        filename: fileName.isNotEmpty ? fileName : 'chat_image.jpg',
      ),
    });

    final response = await apiClient.dio.post(
      ApiEndpoints.guardChatSendImage,
      data: formData,
      options: Options(contentType: Headers.multipartFormDataContentType),
    );
    return GuardChatMessage.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> markAsRead(String peerId) async {
    await apiClient.dio.post(ApiEndpoints.guardChatMarkRead(peerId));
  }

  Future<int> getUnreadCount() async {
    final response = await apiClient.dio.get(ApiEndpoints.guardChatUnreadCount);
    final data = response.data;
    if (data is num) {
      return data.toInt();
    }
    if (data is Map && data['unreadCount'] is num) {
      return (data['unreadCount'] as num).toInt();
    }
    return 0;
  }
}

