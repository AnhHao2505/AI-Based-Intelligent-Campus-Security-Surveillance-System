package com.fa26se040.icss.config;

import com.fa26se040.icss.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Xác thực/phân quyền ở tầng STOMP. Handshake /ws-security/** vẫn permitAll (SecurityConfig) vì trình duyệt
 * không gửi được header Authorization khi mở WebSocket; JWT được kiểm ở frame CONNECT.
 *
 * <p>Topic đang được publish (messagingTemplate.convertAndSend):
 * <ul>
 *   <li>/topic/security-alerts — SecurityIncidentService.createIncident (sự cố mới, broadcast toàn cục)</li>
 *   <li>/topic/buildings/{BUILDING}/alerts — SecurityIncidentService.createIncident (sự cố mới theo toà nhà)</li>
 *   <li>/topic/guards/{guardUserId}/alerts — SecurityIncidentService.createIncident (gửi riêng bảo vệ đang trong geofence)</li>
 *   <li>/topic/incidents/updates — SecurityIncidentService (claim / resolve sự cố)</li>
 *   <li>/topic/guards/locations — GuardLocationService (vị trí bảo vệ realtime)</li>
 *   <li>/topic/campus/geofence — CampusGeofenceService (cập nhật geofence)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    static final String AUTHORIZATION_HEADER = "Authorization";
    static final String BEARER_PREFIX = "Bearer ";

    static final String TOPIC_PREFIX = "/topic/";
    static final String APP_PREFIX = "/app/";

    static final String ROLE_ADMIN = "ROLE_ADMIN";
    static final String ROLE_FACILITY_MANAGER = "ROLE_FACILITY_MANAGER";
    static final String ROLE_GUARD = "ROLE_GUARD";

    /** Role được subscribe mọi /topic/** (trừ kênh riêng của bảo vệ). */
    static final Set<String> TOPIC_SUBSCRIBER_ROLES = Set.of(ROLE_ADMIN, ROLE_FACILITY_MANAGER, ROLE_GUARD);

    /** Role được subscribe kênh riêng của bất kỳ bảo vệ nào, và được dùng wildcard trong destination. */
    static final Set<String> GUARD_CHANNEL_OVERSEER_ROLES = Set.of(ROLE_ADMIN, ROLE_FACILITY_MANAGER);

    /** Kênh riêng của một bảo vệ: /topic/guards/{guardUserId} và mọi đường dẫn con (vd. /alerts). */
    static final Pattern GUARD_PRIVATE_TOPIC = Pattern.compile(
            "^/topic/guards/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            accessor.setUser(authenticate(accessor, message));
        } else if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscribe(accessor, message);
        } else if (command == StompCommand.SEND) {
            authorizeSend(accessor, message);
        }
        return message;
    }

    private Authentication authenticate(StompHeaderAccessor accessor, Message<?> message) {
        String header = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.warn("STOMP CONNECT bị từ chối (session {}): thiếu header Authorization", accessor.getSessionId());
            throw new MessagingException(message, "Thiếu token xác thực (Authorization: Bearer <jwt>)");
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || !jwtTokenProvider.validateToken(token)) {
            log.warn("STOMP CONNECT bị từ chối (session {}): token không hợp lệ", accessor.getSessionId());
            throw new MessagingException(message, "Token không hợp lệ hoặc đã hết hạn");
        }

        String email;
        String role;
        UUID userId;
        try {
            email = jwtTokenProvider.getEmailFromToken(token);
            role = jwtTokenProvider.getRoleFromToken(token);
            userId = jwtTokenProvider.getUserIdFromToken(token);
        } catch (RuntimeException e) {
            log.warn("STOMP CONNECT bị từ chối (session {}): không đọc được claims", accessor.getSessionId());
            throw new MessagingException(message, "Token không hợp lệ hoặc đã hết hạn");
        }
        if (email == null || role == null) {
            throw new MessagingException(message, "Token không hợp lệ hoặc đã hết hạn");
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        authentication.setDetails(userId);
        return authentication;
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor, Message<?> message) {
        Authentication authentication = requireAuthenticated(accessor, message);
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            throw deny(message, authentication, destination);
        }

        Set<String> roles = rolesOf(authentication);
        boolean overseer = roles.stream().anyMatch(GUARD_CHANNEL_OVERSEER_ROLES::contains);

        // SimpleBroker coi destination có * ? { } là pattern: /topic/guards/*/alerts sẽ nhận kênh riêng của mọi bảo vệ.
        if (!overseer && isPattern(destination)) {
            throw deny(message, authentication, destination);
        }

        Matcher guardChannel = GUARD_PRIVATE_TOPIC.matcher(destination);
        if (guardChannel.matches()) {
            UUID channelOwner = UUID.fromString(guardChannel.group(1));
            boolean isOwner = roles.contains(ROLE_GUARD) && channelOwner.equals(authentication.getDetails());
            if (overseer || isOwner) {
                return;
            }
            throw deny(message, authentication, destination);
        }

        if (roles.stream().noneMatch(TOPIC_SUBSCRIBER_ROLES::contains)) {
            throw deny(message, authentication, destination);
        }
    }

    private void authorizeSend(StompHeaderAccessor accessor, Message<?> message) {
        Authentication authentication = requireAuthenticated(accessor, message);
        String destination = accessor.getDestination();
        // Client SEND thẳng tới /topic/** sẽ được SimpleBroker phát lại cho mọi subscriber (giả mạo cảnh báo) → chỉ cho /app/**.
        if (destination == null || !destination.startsWith(APP_PREFIX)) {
            throw deny(message, authentication, destination);
        }
    }

    private Authentication requireAuthenticated(StompHeaderAccessor accessor, Message<?> message) {
        Principal user = accessor.getUser();
        if (user instanceof Authentication authentication && authentication.isAuthenticated()) {
            return authentication;
        }
        log.warn("STOMP {} bị từ chối (session {}): chưa xác thực", accessor.getCommand(), accessor.getSessionId());
        throw new MessagingException(message, "Chưa xác thực WebSocket");
    }

    private MessagingException deny(Message<?> message, Authentication authentication, String destination) {
        log.warn("STOMP bị từ chối: user={}, destination={}", authentication.getName(), destination);
        return new MessagingException(message, "Không có quyền truy cập " + destination);
    }

    private static Set<String> rolesOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private static boolean isPattern(String destination) {
        return destination.indexOf('*') >= 0 || destination.indexOf('?') >= 0
                || destination.indexOf('{') >= 0 || destination.indexOf('}') >= 0;
    }
}
