class GuardChatMessage {
  final int? id;
  final String senderId;
  final String? senderName;
  final String receiverId;
  final String? receiverName;
  final String content;
  final bool isRead;
  final DateTime? readAt;
  final DateTime createdAt;

  GuardChatMessage({
    this.id,
    required this.senderId,
    this.senderName,
    required this.receiverId,
    this.receiverName,
    required this.content,
    this.isRead = false,
    this.readAt,
    required this.createdAt,
  });

  factory GuardChatMessage.fromJson(Map<String, dynamic> json) {
    return GuardChatMessage(
      id: (json['id'] as num?)?.toInt(),
      senderId: json['senderId']?.toString() ?? '',
      senderName: json['senderName']?.toString(),
      receiverId: json['receiverId']?.toString() ?? '',
      receiverName: json['receiverName']?.toString(),
      content: json['content']?.toString() ?? '',
      isRead: json['isRead'] == true || json['read'] == true,
      readAt: json['readAt'] != null ? DateTime.tryParse(json['readAt'].toString()) : null,
      createdAt: json['createdAt'] != null
          ? (DateTime.tryParse(json['createdAt'].toString()) ?? DateTime.now())
          : DateTime.now(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'receiverId': receiverId,
      'content': content,
    };
  }
}

