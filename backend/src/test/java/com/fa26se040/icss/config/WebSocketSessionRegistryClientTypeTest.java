package com.fa26se040.icss.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V93 (K6, X10): sổ phiên giữ loại client đọc từ header STOMP CONNECT client-type. */
class WebSocketSessionRegistryClientTypeTest {

    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();

    private static Principal principal(UUID userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("g@fpt.edu.vn", null, List.of());
        auth.setDetails(userId);
        return auth;
    }

    /** CONNECT_ACK như Spring phát: frame CONNECT gốc (có native header) nằm ở header simpConnectMessage. */
    private static SessionConnectedEvent connected(String sessionId, Principal user, String clientType) {
        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId(sessionId);
        if (clientType != null) {
            connect.setNativeHeader(WebSocketSessionRegistry.CLIENT_TYPE_HEADER, clientType);
        }
        Message<byte[]> connectMessage = MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());

        StompHeaderAccessor ack = StompHeaderAccessor.create(StompCommand.CONNECTED);
        ack.setSessionId(sessionId);
        ack.setHeader(SimpMessageHeaderAccessor.CONNECT_MESSAGE_HEADER, connectMessage);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], ack.getMessageHeaders());
        return new SessionConnectedEvent(new Object(), message, user);
    }

    private static SessionDisconnectEvent disconnected(String sessionId) {
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0],
                StompHeaderAccessor.create(StompCommand.DISCONNECT).getMessageHeaders());
        return new SessionDisconnectEvent(new Object(), message, sessionId, CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("client-type: mobile -> hasMobileSession = true")
    void mobileHeader_marksMobileSession() {
        UUID guard = UUID.randomUUID();
        registry.onConnected(connected("s-mobile", principal(guard), "mobile"));

        assertTrue(registry.hasMobileSession(guard));
        assertEquals(WebSocketSessionRegistry.CLIENT_TYPE_MOBILE, registry.clientTypeOfSession("s-mobile"));
    }

    @Test
    @DisplayName("client-type: web, thiếu header hoặc giá trị lạ -> coi là web")
    void webMissingOrUnknownHeader_isWeb() {
        UUID web = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        registry.onConnected(connected("s-web", principal(web), "web"));
        registry.onConnected(connected("s-missing", principal(missing), null));
        registry.onConnected(connected("s-unknown", principal(unknown), "tablet"));

        assertFalse(registry.hasMobileSession(web));
        assertFalse(registry.hasMobileSession(missing));
        assertFalse(registry.hasMobileSession(unknown));
        assertEquals(WebSocketSessionRegistry.CLIENT_TYPE_WEB, registry.clientTypeOfSession("s-missing"));
    }

    @Test
    @DisplayName("Header không phân biệt hoa thường, có khoảng trắng vẫn nhận là mobile")
    void mobileHeader_caseInsensitive() {
        UUID guard = UUID.randomUUID();
        registry.onConnected(connected("s1", principal(guard), " Mobile "));
        assertTrue(registry.hasMobileSession(guard));
    }

    @Test
    @DisplayName("Một user có cả web và mobile: đóng phiên mobile -> hết mobile, phiên web vẫn còn")
    void closingMobileSession_keepsWebSession() {
        UUID guard = UUID.randomUUID();
        registry.onConnected(connected("s-web", principal(guard), "web"));
        registry.onConnected(connected("s-mobile", principal(guard), "mobile"));
        assertTrue(registry.hasMobileSession(guard));

        registry.onDisconnect(disconnected("s-mobile"));

        assertFalse(registry.hasMobileSession(guard));
        assertEquals(java.util.Set.of("s-web"), registry.sessionIdsOf(guard));
        assertNull(registry.clientTypeOfSession("s-mobile"));
    }

    @Test
    @DisplayName("userConnected 2 tham số (đường cũ) -> web; user null -> không có phiên mobile")
    void legacyUserConnected_isWeb() {
        UUID guard = UUID.randomUUID();
        registry.userConnected("s-old", guard);
        assertFalse(registry.hasMobileSession(guard));
        assertFalse(registry.hasMobileSession(null));
    }
}
