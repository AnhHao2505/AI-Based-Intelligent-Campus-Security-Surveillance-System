package com.fa26se040.icss.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KI-1: sổ phiên WebSocket theo user, để đóng phiên của tài khoản bị vô hiệu hoá / xoá.
 *
 * <p>WebSocketAuthChannelInterceptor (S1b) chỉ chặn ở CONNECT / SUBSCRIBE; subscription đã mở vẫn nhận tin tới khi
 * client tự ngắt. Đóng hẳn WebSocketSession thì SubProtocolWebSocketHandler phát SessionDisconnectEvent và
 * SimpleBroker gỡ mọi subscription của phiên; client kết nối lại sẽ bị từ chối ở CONNECT.
 *
 * <p>SimpUserRegistry của Spring chỉ cho biết id phiên theo tên principal (e-mail), không giữ WebSocketSession để đóng,
 * nên sổ này giữ cả hai: WebSocketSession lấy qua decorator (afterConnectionEstablished), user lấy từ principal mà
 * interceptor gán ở CONNECT (details = userId trong DB), cập nhật qua SessionConnectedEvent / SessionDisconnectEvent.
 * Id của WebSocketSession chính là simpSessionId của STOMP (SubProtocolWebSocketHandler dùng session.getId()).
 */
@Slf4j
@Component
public class WebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory {

    private final Map<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, UUID> userBySessionId = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sessionIdsByUser = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionOpened(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    sessionClosed(session.getId());
                }
            }
        };
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        UUID userId = userIdOf(event.getUser());
        if (sessionId != null && userId != null) {
            userConnected(sessionId, userId);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        sessionClosed(event.getSessionId());
    }

    void sessionOpened(WebSocketSession session) {
        sessionsById.put(session.getId(), session);
    }

    void userConnected(String sessionId, UUID userId) {
        UUID previous = userBySessionId.put(sessionId, userId);
        if (previous != null && !previous.equals(userId)) {
            removeFromUser(previous, sessionId);
        }
        sessionIdsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    void sessionClosed(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessionsById.remove(sessionId);
        UUID userId = userBySessionId.remove(sessionId);
        if (userId != null) {
            removeFromUser(userId, sessionId);
        }
    }

    /** Id các phiên STOMP đang mở của user (bản sao, để kiểm / ghi log). */
    public Set<String> sessionIdsOf(UUID userId) {
        Set<String> ids = sessionIdsByUser.get(userId);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    /**
     * Đóng mọi phiên WebSocket đang mở của user với CloseStatus.POLICY_VIOLATION. Gọi sau khi transaction vô hiệu hoá /
     * xoá tài khoản đã commit. Lỗi đóng một phiên chỉ ghi log, không chặn các phiên còn lại.
     *
     * @return số phiên đã yêu cầu đóng
     */
    public int closeSessionsOf(UUID userId) {
        int closed = 0;
        for (String sessionId : sessionIdsOf(userId)) {
            WebSocketSession session = sessionsById.get(sessionId);
            if (session == null) {
                sessionClosed(sessionId);
                continue;
            }
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("Tài khoản không còn hoạt động"));
                    closed++;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Không đóng được phiên WebSocket {} của user {}: {}", sessionId, userId, e.getMessage());
            } finally {
                sessionClosed(sessionId);
            }
        }
        if (closed > 0) {
            log.info("Đã đóng {} phiên WebSocket của user {}", closed, userId);
        }
        return closed;
    }

    private void removeFromUser(UUID userId, String sessionId) {
        sessionIdsByUser.computeIfPresent(userId, (k, ids) -> {
            ids.remove(sessionId);
            return ids.isEmpty() ? null : ids;
        });
    }

    private static UUID userIdOf(Principal principal) {
        if (principal instanceof Authentication authentication && authentication.getDetails() instanceof UUID userId) {
            return userId;
        }
        return null;
    }
}
