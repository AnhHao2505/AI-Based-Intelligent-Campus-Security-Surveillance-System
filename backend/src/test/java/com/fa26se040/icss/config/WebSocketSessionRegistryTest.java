package com.fa26se040.icss.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebSocketSessionRegistryTest {

    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();

    private static WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    private static Principal principal(UUID userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("u@fpt.edu.vn", null, List.of());
        auth.setDetails(userId);
        return auth;
    }

    private static SessionConnectedEvent connected(String sessionId, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setSessionId(sessionId);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectedEvent(new Object(), message, user);
    }

    private static SessionDisconnectEvent disconnected(String sessionId) {
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0],
                StompHeaderAccessor.create(StompCommand.DISCONNECT).getMessageHeaders());
        return new SessionDisconnectEvent(new Object(), message, sessionId, CloseStatus.NORMAL);
    }

    /** Mở phiên qua decorator rồi CONNECT thành công với principal của user. */
    private WebSocketSession open(String sessionId, UUID userId) throws Exception {
        WebSocketSession s = session(sessionId);
        registry.decorate(mock(WebSocketHandler.class)).afterConnectionEstablished(s);
        registry.onConnected(connected(sessionId, principal(userId)));
        return s;
    }

    @Test
    @DisplayName("Thêm: phiên mở + CONNECT thì nằm trong sổ của user")
    void connect_addsSession() throws Exception {
        UUID user = UUID.randomUUID();
        open("s1", user);

        assertEquals(Set.of("s1"), registry.sessionIdsOf(user));
    }

    @Test
    @DisplayName("Nhiều phiên một user: đóng phiên của user thì đóng hết các phiên đó (POLICY_VIOLATION), không đụng user khác")
    void closeSessionsOf_closesAllSessionsOfThatUserOnly() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        WebSocketSession s1 = open("s1", user);
        WebSocketSession s2 = open("s2", user);
        WebSocketSession s3 = open("s3", other);

        assertEquals(Set.of("s1", "s2"), registry.sessionIdsOf(user));
        assertEquals(2, registry.closeSessionsOf(user));

        verify(s1).close(argThat(cs -> cs.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        verify(s2).close(argThat(cs -> cs.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        verify(s3, never()).close(any());
        assertTrue(registry.sessionIdsOf(user).isEmpty());
        assertEquals(Set.of("s3"), registry.sessionIdsOf(other));
    }

    @Test
    @DisplayName("Bớt: SessionDisconnectEvent hoặc decorator afterConnectionClosed gỡ phiên khỏi sổ")
    void disconnect_removesSession() throws Exception {
        UUID user = UUID.randomUUID();
        open("s1", user);
        WebSocketSession s2 = open("s2", user);

        registry.onDisconnect(disconnected("s1"));
        assertEquals(Set.of("s2"), registry.sessionIdsOf(user));

        registry.decorate(mock(WebSocketHandler.class)).afterConnectionClosed(s2, CloseStatus.NORMAL);
        assertTrue(registry.sessionIdsOf(user).isEmpty());
        assertEquals(0, registry.closeSessionsOf(user));
    }

    @Test
    @DisplayName("Phiên mở nhưng chưa CONNECT (hoặc principal không có userId) thì không gắn vào user nào")
    void connectWithoutUserId_notTracked() throws Exception {
        WebSocketSession s = session("s1");
        registry.decorate(mock(WebSocketHandler.class)).afterConnectionEstablished(s);
        registry.onConnected(connected("s1", new UsernamePasswordAuthenticationToken("u", null, List.of())));
        registry.onConnected(connected("s1", null));

        UUID anyUser = UUID.randomUUID();
        assertTrue(registry.sessionIdsOf(anyUser).isEmpty());
        assertEquals(0, registry.closeSessionsOf(anyUser));
        verify(s, never()).close(any());
    }

    @Test
    @DisplayName("Lỗi khi đóng một phiên không chặn các phiên còn lại; phiên đã đóng sẵn thì không gọi close")
    void closeFailure_doesNotStopOthers() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession failing = open("s1", user);
        doThrow(new IOException("broken pipe")).when(failing).close(any());
        WebSocketSession alreadyClosed = open("s2", user);
        when(alreadyClosed.isOpen()).thenReturn(false);
        WebSocketSession ok = open("s3", user);

        assertEquals(1, registry.closeSessionsOf(user));

        verify(ok).close(any());
        verify(alreadyClosed, never()).close(any());
        assertTrue(registry.sessionIdsOf(user).isEmpty());
    }

    @Test
    @DisplayName("Decorator chuyển tiếp afterConnectionEstablished / afterConnectionClosed cho handler gốc")
    void decorator_delegates() throws Exception {
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        WebSocketHandler decorated = registry.decorate(delegate);
        WebSocketSession s = session("s1");

        decorated.afterConnectionEstablished(s);
        decorated.afterConnectionClosed(s, CloseStatus.NORMAL);

        verify(delegate).afterConnectionEstablished(s);
        verify(delegate).afterConnectionClosed(s, CloseStatus.NORMAL);
    }
}
