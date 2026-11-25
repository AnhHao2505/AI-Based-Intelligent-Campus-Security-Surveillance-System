import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/constants/api_endpoints.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/chat/models/guard_chat_member.dart';
import 'package:guard_mobile_app/features/chat/models/guard_chat_message.dart';
import 'package:guard_mobile_app/features/chat/providers/guard_chat_provider.dart';
import 'package:guard_mobile_app/features/chat/screens/guard_chat_conversation_screen.dart';
import 'package:guard_mobile_app/features/chat/services/guard_chat_service.dart';
import 'package:guard_mobile_app/features/chat/widgets/guard_chat_bottom_sheet.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _StubGuardChatService extends GuardChatService {
  List<GuardChatMember> stubGuards = [];
  List<GuardChatMessage> stubMessages = [];
  GuardChatMessage? lastImageSent;
  String? lastCaptionSent;
  File? lastFileSent;

  _StubGuardChatService() : super(ApiClient());

  @override
  Future<List<GuardChatMember>> getGuards() async => stubGuards;

  @override
  Future<List<GuardChatMessage>> getMessages(String peerId, {int limit = 50}) async =>
      stubMessages;

  @override
  Future<void> markAsRead(String peerId) async {}

  @override
  Future<GuardChatMessage> sendImageMessage(
    String receiverId,
    File imageFile, {
    String? content,
  }) async {
    lastFileSent = imageFile;
    lastCaptionSent = content;
    final msg = GuardChatMessage(
      id: '11111111-1111-1111-1111-111111111111',
      senderId: 'guard-me',
      receiverId: receiverId,
      content: content ?? '',
      imageUrl: '/api/media/chat-media/guard-me/pic.jpg',
      createdAt: DateTime.now(),
    );
    lastImageSent = msg;
    return msg;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  group('Guard Chat Image Messaging', () {
    test('GuardChatMessage parses UUID string id and imageUrl', () {
      final msg = GuardChatMessage.fromJson({
        'id': 'a1b2c3d4-e5f6-7890-1234-567890abcdef',
        'senderId': 'u1',
        'receiverId': 'u2',
        'content': '',
        'imageUrl': '/api/media/chat-media/u1/test.jpg',
        'isRead': false,
        'createdAt': '2026-11-25T10:00:00Z',
      });

      expect(msg.id, equals('a1b2c3d4-e5f6-7890-1234-567890abcdef'));
      expect(msg.imageUrl, equals('/api/media/chat-media/u1/test.jpg'));
      expect(msg.hasImage, isTrue);
      expect(ApiEndpoints.guardChatSendImage, endsWith('/api/guard-chat/messages/image'));
    });

    test('GuardChatProvider.sendImageMessage appends bubble and updates [Hình ảnh] preview', () async {
      final service = _StubGuardChatService();
      service.stubGuards = [
        GuardChatMember(id: 'peer-1', fullName: 'Guard Binh'),
      ];

      final provider = GuardChatProvider(
        chatService: service,
        stompService: StompService(),
      );

      await provider.fetchGuards();
      await provider.openConversation('peer-1');

      final tempDir = Directory.systemTemp.createTempSync('chat_img_test');
      final tempFile = File('${tempDir.path}/sample.jpg')..writeAsBytesSync([0xFF, 0xD8, 0xFF]);

      try {
        final ok = await provider.sendImageMessage(tempFile, content: 'Cổng B đang đông');
        expect(ok, isTrue);
        expect(service.lastFileSent?.path, equals(tempFile.path));
        expect(service.lastCaptionSent, equals('Cổng B đang đông'));
        expect(provider.activeMessages.length, equals(1));
        expect(provider.activeMessages.first.hasImage, isTrue);
        expect(provider.guards.first.lastMessage, equals('[Hình ảnh] Cổng B đang đông'));

        final okNoCaption = await provider.sendImageMessage(tempFile);
        expect(okNoCaption, isTrue);
        expect(provider.guards.first.lastMessage, equals('[Hình ảnh]'));
      } finally {
        tempDir.deleteSync(recursive: true);
      }
    });

    testWidgets('Back button in GuardChatConversationScreen returns to GuardChatBottomSheet contact list', (tester) async {
      final service = _StubGuardChatService();
      service.stubGuards = [
        GuardChatMember(
          id: 'fm-1',
          fullName: 'Trần Bình (Quản Lý CSVC)',
          role: 'FACILITY_MANAGER',
        ),
      ];
      final provider = GuardChatProvider(
        chatService: service,
        stompService: StompService(),
      );

      await tester.pumpWidget(
        ChangeNotifierProvider<GuardChatProvider>.value(
          value: provider,
          child: MaterialApp(
            home: Scaffold(
              body: Builder(
                builder: (ctx) => Center(
                  child: ElevatedButton(
                    onPressed: () => GuardChatBottomSheet.show(ctx),
                    child: const Text('Open Chat'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Open Chat'));
      await tester.pumpAndSettle();

      expect(find.byType(GuardChatBottomSheet), findsOneWidget);
      expect(find.text('Trần Bình (Quản Lý CSVC)'), findsOneWidget);
      expect(find.text('Quản lý CSVC'), findsOneWidget);

      await tester.tap(find.text('Trần Bình (Quản Lý CSVC)'));
      await tester.pumpAndSettle();

      expect(find.byType(GuardChatConversationScreen), findsOneWidget);

      await tester.tap(find.byIcon(Icons.arrow_back_rounded));
      await tester.pumpAndSettle();

      expect(find.byType(GuardChatConversationScreen), findsNothing);
      expect(find.byType(GuardChatBottomSheet), findsOneWidget);
      expect(find.text('Trần Bình (Quản Lý CSVC)'), findsOneWidget);
    });
  });
}
