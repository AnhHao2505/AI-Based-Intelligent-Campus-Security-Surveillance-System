package com.fa26se040.icss.config;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KI-1, kiểm bằng STOMP thật: client kết nối và subscribe qua /ws-security, ADMIN vô hiệu hoá tài khoản,
 * phiên bị server đóng, tin phát sau đó không tới nữa, kết nối lại bằng token cũ bị từ chối ở CONNECT (S1b).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSessionCloseIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "/topic/incidents/updates";
    private static final long TIMEOUT_S = 10;

    @LocalServerPort private int port;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private WebSocketSessionRegistry registry;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<StompSession> openSessions = new ArrayList<>();
    private WebSocketStompClient stompClient;
    private String suffix;
    private User admin;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = user("adm", Role.ADMIN);
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
    }

    @AfterEach
    void tearDown() {
        openSessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role) {
        User u = userRepository.save(User.builder()
                .userCode("KI1-" + tag.toUpperCase() + "-" + suffix)
                .fullName("KI1 " + tag + " " + suffix)
                .email("ki1-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    /** Nhận tin và lỗi của một phiên STOMP. */
    private static final class Client extends StompSessionHandlerAdapter {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CountDownLatch errorOrLost = new CountDownLatch(1);

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            // Frame ERROR của server (CONNECT bị từ chối) cũng tới đây
            errorOrLost.countDown();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errorOrLost.countDown();
        }
    }

    private CompletableFuture<StompSession> connect(String token, Client client) {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return stompClient.connectAsync("ws://localhost:" + port + "/ws-security", new WebSocketHttpHeaders(),
                connectHeaders, client);
    }

    private StompSession connectAndSubscribe(User user, Client client) throws Exception {
        StompSession session = connect(jwtTokenProvider.generateToken(user), client).get(TIMEOUT_S, TimeUnit.SECONDS);
        openSessions.add(session);
        session.subscribe(TOPIC, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                client.messages.add((String) payload);
            }
        });
        return session;
    }

    /** Phát tin tới topic cho tới khi client nhận được (SUBSCRIBE xử lý bất đồng bộ phía server). */
    private boolean receivesBroadcast(Client client, String payload) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S);
        while (System.nanoTime() < deadline) {
            messagingTemplate.convertAndSend(TOPIC, payload);
            String got = client.messages.poll(200, TimeUnit.MILLISECONDS);
            if (payload.equals(got)) {
                return true;
            }
        }
        return false;
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("KI-1: GUARD đang subscribe bị vô hiệu hoá -> server đóng phiên, không nhận tin nữa, kết nối lại bằng token cũ bị từ chối; FM khác không bị ảnh hưởng")
    void deactivatedUser_sessionClosed_noMoreMessages_reconnectRejected() throws Exception {
        User guard = user("grd", Role.GUARD);
        User fm = user("fm", Role.FACILITY_MANAGER);
        String guardToken = jwtTokenProvider.generateToken(guard);
        Client guardClient = new Client();
        Client fmClient = new Client();
        StompSession guardSession = connectAndSubscribe(guard, guardClient);
        StompSession fmSession = connectAndSubscribe(fm, fmClient);

        assertTrue(receivesBroadcast(guardClient, "before-" + suffix), "GUARD phải nhận được tin trước khi bị khoá");
        assertTrue(receivesBroadcast(fmClient, "fm-before-" + suffix));
        assertEquals(1, registry.sessionIdsOf(guard.getId()).size());

        // ADMIN vô hiệu hoá (qua proxy @Transactional -> commit thật)
        userService.toggleActive(guard.getId(), admin.getEmail());

        assertTrue(guardClient.errorOrLost.await(TIMEOUT_S, TimeUnit.SECONDS), "server phải đóng phiên của GUARD");
        assertTrue(waitUntil(() -> !guardSession.isConnected()));
        assertTrue(registry.sessionIdsOf(guard.getId()).isEmpty());

        // Tin phát sau khi khoá không tới GUARD; FM vẫn nhận
        guardClient.messages.clear();
        assertTrue(receivesBroadcast(fmClient, "after-" + suffix), "FM không bị ảnh hưởng");
        assertTrue(fmSession.isConnected());
        assertNull(guardClient.messages.poll(500, TimeUnit.MILLISECONDS), "GUARD đã khoá không được nhận tin");

        // Kết nối lại bằng token cũ: CONNECT bị từ chối (S1b)
        Client retry = new Client();
        CompletableFuture<StompSession> reconnect = connect(guardToken, retry);
        assertTrue(retry.errorOrLost.await(TIMEOUT_S, TimeUnit.SECONDS), "CONNECT bằng token cũ phải bị từ chối");
        boolean reconnected = reconnect.isDone() && !reconnect.isCompletedExceptionally();
        assertFalse(reconnected, "không được có phiên STOMP mới (CONNECTED) cho tài khoản đã khoá");
    }

    @Test
    @DisplayName("KI-1: xoá mềm tài khoản đang subscribe -> server đóng phiên")
    void softDeletedUser_sessionClosed() throws Exception {
        User fm = user("fmdel", Role.FACILITY_MANAGER);
        Client client = new Client();
        StompSession session = connectAndSubscribe(fm, client);
        assertTrue(receivesBroadcast(client, "del-before-" + suffix));

        userService.softDelete(fm.getId(), admin.getEmail());

        assertTrue(client.errorOrLost.await(TIMEOUT_S, TimeUnit.SECONDS), "server phải đóng phiên của tài khoản đã xoá");
        assertTrue(waitUntil(() -> !session.isConnected()));
        assertTrue(registry.sessionIdsOf(fm.getId()).isEmpty());
    }

    @Test
    @DisplayName("Kích hoạt lại tài khoản không đóng phiên đang mở (chỉ chiều vô hiệu hoá)")
    void reactivation_doesNotCloseSessions() throws Exception {
        User inactive = user("ina", Role.GUARD);
        User guard = user("grd2", Role.GUARD);
        inactive.setIsActive(false);
        userRepository.save(inactive);
        Client client = new Client();
        StompSession session = connectAndSubscribe(guard, client);

        userService.toggleActive(inactive.getId(), admin.getEmail());

        assertTrue(receivesBroadcast(client, "react-" + suffix));
        assertTrue(session.isConnected());
    }
}
