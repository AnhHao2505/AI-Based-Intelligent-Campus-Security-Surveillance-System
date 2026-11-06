import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';

/// StompClient giả: không mở socket, chỉ ghi lại activate / deactivate.
class FakeStompClient extends StompClient {
  FakeStompClient(StompConfig config) : super(config: config);

  bool activated = false;
  bool deactivated = false;

  @override
  void activate() => activated = true;

  @override
  void deactivate() => deactivated = true;

  bool get alive => activated && !deactivated;
}

void noopIncident(dynamic _) {}
void noopUpdate(Map<String, dynamic> _) {}

void main() {
  late List<FakeStompClient> created;
  late StompService service;

  Future<void> connect() => service.connect(
        guardId: 'guard-a',
        onNewIncident: noopIncident,
        onIncidentUpdate: noopUpdate,
        onOfferMessage: noopUpdate,
      );

  setUp(() {
    created = [];
    service = StompService(
      clientFactory: (config) {
        final c = FakeStompClient(config);
        created.add(c);
        return c;
      },
      tokenReader: () async => 'token',
    );
  });

  group('B1 — DEF-16: chỉ một phiên STOMP', () {
    test('connect() 2 lần liên tiếp không await → chỉ tạo và giữ 1 client', () async {
      final first = connect();
      final second = connect();
      await Future.wait([first, second]);

      expect(created.where((c) => c.alive).length, 1);
      expect(service.openClientCount, 1);
      // Lần gọi đầu bị thay bởi lần sau khi còn chờ token → không tạo client thừa
      expect(created.length, 1);
    });

    test('Token đọc chậm ở lần đầu: lần đầu không được ghi đè lên client của lần sau', () async {
      final slowToken = Completer<String?>();
      var calls = 0;
      service = StompService(
        clientFactory: (config) {
          final c = FakeStompClient(config);
          created.add(c);
          return c;
        },
        tokenReader: () {
          calls++;
          return calls == 1 ? slowToken.future : Future.value('token-2');
        },
      );

      final first = connect();
      await connect();
      slowToken.complete('token-1');
      await first;

      expect(created.length, 1);
      expect(created.single.alive, isTrue);
      expect(service.openClientCount, 1);
    });

    test('connect() khi đã có client → client cũ bị deactivate, còn đúng 1 client sống', () async {
      await connect();
      await connect();

      expect(created.length, 2);
      expect(created.first.deactivated, isTrue);
      expect(created.last.alive, isTrue);
      expect(service.openClientCount, 1);
    });

    test('disconnect() đóng MỌI client và huỷ lần connect() đang chờ token', () async {
      await connect();
      final pending = connect();
      service.disconnect();
      await pending;

      expect(created.every((c) => !c.alive), isTrue);
      expect(service.openClientCount, 0);
      expect(service.isConnected, isFalse);
    });
  });
}
