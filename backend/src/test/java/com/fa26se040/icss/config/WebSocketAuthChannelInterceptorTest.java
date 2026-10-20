package com.fa26se040.icss.config;

import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAuthChannelInterceptorTest {

    private static final String SECRET = "test-secret-for-websocket-interceptor-must-be-at-least-256-bits-long";
    private static final String OTHER_SECRET = "another-secret-that-did-not-sign-the-token-also-256-bits-or-more!!";
    private static final long ONE_HOUR_MS = 3_600_000L;

    private JwtTokenProvider jwtTokenProvider;
    private WebSocketAuthChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);
    /** "DB" của test: email -> user. User không có ở đây coi như không tồn tại. */
    private final Map<String, User> usersByEmail = new HashMap<>();

    private User guardA;
    private User guardB;
    private User facilityManager;
    private User normalUser;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET, ONE_HOUR_MS);
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(usersByEmail.get(inv.<String>getArgument(0))));
        interceptor = new WebSocketAuthChannelInterceptor(jwtTokenProvider, userRepository);

        guardA = user("guard.a@fpt.edu.vn", Role.GUARD);
        guardB = user("guard.b@fpt.edu.vn", Role.GUARD);
        facilityManager = user("fm@fpt.edu.vn", Role.FACILITY_MANAGER);
        normalUser = user("sv@fpt.edu.vn", Role.NORMAL_USER);
    }

    // ───────────── CONNECT ─────────────

    @Test
    @DisplayName("CONNECT không có header Authorization -> lỗi")
    void connect_NoAuthorizationHeader_Rejected() {
        Message<byte[]> connect = stomp(StompCommand.CONNECT, null, null, null);

        assertThrows(MessagingException.class, () -> interceptor.preSend(connect, channel));
    }

    @Test
    @DisplayName("CONNECT với header không phải Bearer -> lỗi")
    void connect_NonBearerHeader_Rejected() {
        String token = jwtTokenProvider.generateToken(guardA);
        Message<byte[]> connect = stomp(StompCommand.CONNECT, null, null, token);

        assertThrows(MessagingException.class, () -> interceptor.preSend(connect, channel));
    }

    @Test
    @DisplayName("CONNECT với token rác -> lỗi")
    void connect_GarbageToken_Rejected() {
        Message<byte[]> connect = stomp(StompCommand.CONNECT, null, null, "Bearer not-a-jwt");

        assertThrows(MessagingException.class, () -> interceptor.preSend(connect, channel));
    }

    @Test
    @DisplayName("CONNECT với token ký bằng secret khác -> lỗi")
    void connect_TokenSignedWithOtherKey_Rejected() {
        String forged = new JwtTokenProvider(OTHER_SECRET, ONE_HOUR_MS).generateToken(guardA);
        Message<byte[]> connect = stomp(StompCommand.CONNECT, null, null, "Bearer " + forged);

        assertThrows(MessagingException.class, () -> interceptor.preSend(connect, channel));
    }

    @Test
    @DisplayName("CONNECT với token đã hết hạn -> lỗi")
    void connect_ExpiredToken_Rejected() {
        String expired = new JwtTokenProvider(SECRET, -60_000L).generateToken(guardA);
        Message<byte[]> connect = stomp(StompCommand.CONNECT, null, null, "Bearer " + expired);

        assertThrows(MessagingException.class, () -> interceptor.preSend(connect, channel));
    }

    @Test
    @DisplayName("CONNECT với token hợp lệ -> gắn user (email, ROLE_*, userId)")
    void connect_ValidToken_SetsUser() {
        Principal principal = connectAs(guardA);

        Authentication authentication = assertInstanceOf(Authentication.class, principal);
        assertEquals("guard.a@fpt.edu.vn", authentication.getName());
        assertEquals("ROLE_GUARD", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).findFirst().orElseThrow());
        assertEquals(guardA.getId(), authentication.getDetails());
    }

    @Test
    @DisplayName("Frame STOMP (bí danh của CONNECT) cũng phải có token")
    void stompFrame_WithoutToken_Rejected() {
        Message<byte[]> connect = stomp(StompCommand.STOMP, null, null, null);

        assertThrows(MessagingException.class, () -> interceptor.preSend(connect, channel));
    }

    // ───────────── SUBSCRIBE ─────────────

    @Test
    @DisplayName("SUBSCRIBE khi chưa xác thực -> lỗi")
    void subscribe_Unauthenticated_Rejected() {
        Message<byte[]> subscribe = stomp(StompCommand.SUBSCRIBE, null, "/topic/security-alerts", null);

        assertThrows(MessagingException.class, () -> interceptor.preSend(subscribe, channel));
    }

    @Test
    @DisplayName("SUBSCRIBE /topic/security-alerts bởi NORMAL_USER -> lỗi")
    void subscribe_SecurityAlerts_NormalUser_Rejected() {
        Principal principal = connectAs(normalUser);
        Message<byte[]> subscribe = stomp(StompCommand.SUBSCRIBE, principal, "/topic/security-alerts", null);

        assertThrows(MessagingException.class, () -> interceptor.preSend(subscribe, channel));
    }

    @Test
    @DisplayName("SUBSCRIBE /topic/security-alerts bởi GUARD -> qua")
    void subscribe_SecurityAlerts_Guard_Allowed() {
        Principal principal = connectAs(guardA);
        Message<byte[]> subscribe = stomp(StompCommand.SUBSCRIBE, principal, "/topic/security-alerts", null);

        assertDoesNotThrow(() -> interceptor.preSend(subscribe, channel));
    }

    @Test
    @DisplayName("SUBSCRIBE /topic/incidents/updates bởi FACILITY_MANAGER -> qua")
    void subscribe_IncidentUpdates_FacilityManager_Allowed() {
        Principal principal = connectAs(facilityManager);
        Message<byte[]> subscribe = stomp(StompCommand.SUBSCRIBE, principal, "/topic/incidents/updates", null);

        assertDoesNotThrow(() -> interceptor.preSend(subscribe, channel));
    }

    @Test
    @DisplayName("SUBSCRIBE kênh riêng /topic/guards/{A} và /topic/guards/{A}/alerts bởi chính guard A -> qua")
    void subscribe_OwnGuardChannel_Allowed() {
        Principal principal = connectAs(guardA);

        assertDoesNotThrow(() -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/guards/" + guardA.getId(), null), channel));
        assertDoesNotThrow(() -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/guards/" + guardA.getId() + "/alerts", null), channel));
    }

    @Test
    @DisplayName("SUBSCRIBE kênh riêng của guard A bởi guard B -> lỗi")
    void subscribe_OtherGuardChannel_Rejected() {
        Principal principal = connectAs(guardB);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/guards/" + guardA.getId(), null), channel));
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/guards/" + guardA.getId() + "/alerts", null), channel));
    }

    @Test
    @DisplayName("SUBSCRIBE kênh riêng của guard A bởi FACILITY_MANAGER -> qua")
    void subscribe_GuardChannel_FacilityManager_Allowed() {
        Principal principal = connectAs(facilityManager);
        Message<byte[]> subscribe = stomp(StompCommand.SUBSCRIBE, principal,
                "/topic/guards/" + guardA.getId() + "/alerts", null);

        assertDoesNotThrow(() -> interceptor.preSend(subscribe, channel));
    }

    @Test
    @DisplayName("GUARD subscribe wildcard /topic/guards/*/alerts (nghe lén kênh riêng mọi bảo vệ) -> lỗi")
    void subscribe_WildcardGuardChannel_Guard_Rejected() {
        Principal principal = connectAs(guardB);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/guards/*/alerts", null), channel));
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/guards/**", null), channel));
    }

    @Test
    @DisplayName("SUBSCRIBE /topic/guards/locations (không phải kênh riêng) bởi GUARD -> qua theo luật /topic/** chung")
    void subscribe_GuardLocations_Guard_Allowed() {
        Principal principal = connectAs(guardA);
        Message<byte[]> subscribe = stomp(StompCommand.SUBSCRIBE, principal, "/topic/guards/locations", null);

        assertDoesNotThrow(() -> interceptor.preSend(subscribe, channel));
    }

    @Test
    @DisplayName("SUBSCRIBE destination ngoài /topic/** -> lỗi")
    void subscribe_NonTopicDestination_Rejected() {
        Principal principal = connectAs(facilityManager);
        Message<byte[]> subscribe = stomp(StompCommand.SUBSCRIBE, principal, "/queue/anything", null);

        assertThrows(MessagingException.class, () -> interceptor.preSend(subscribe, channel));
    }

    // ───────────── Trạng thái tài khoản trong DB (S1b) ─────────────

    @Test
    @DisplayName("CONNECT bằng token cũ của tài khoản đã vô hiệu hoá -> lỗi")
    void connect_DeactivatedUser_Rejected() {
        String token = jwtTokenProvider.generateToken(guardA);
        guardA.setIsActive(false);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.CONNECT, null, null, "Bearer " + token), channel));
    }

    @Test
    @DisplayName("CONNECT bằng token cũ của tài khoản đã xoá mềm -> lỗi")
    void connect_SoftDeletedUser_Rejected() {
        String token = jwtTokenProvider.generateToken(guardA);
        guardA.setDeletedAt(OffsetDateTime.now());

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.CONNECT, null, null, "Bearer " + token), channel));
    }

    @Test
    @DisplayName("CONNECT bằng token hợp lệ của user không có trong DB -> lỗi")
    void connect_UnknownUser_Rejected() {
        User ghost = User.builder().id(UUID.randomUUID()).email("ghost@fpt.edu.vn").fullName("ghost")
                .userCode("ghost").role(Role.ADMIN).build();
        String token = jwtTokenProvider.generateToken(ghost);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.CONNECT, null, null, "Bearer " + token), channel));
    }

    @Test
    @DisplayName("CONNECT lấy role từ DB, không lấy role trong token")
    void connect_RoleFromDatabase() {
        String token = jwtTokenProvider.generateToken(normalUser);
        normalUser.setRole(Role.FACILITY_MANAGER);

        Message<?> result = interceptor.preSend(stomp(StompCommand.CONNECT, null, null, "Bearer " + token), channel);
        Authentication authentication = assertInstanceOf(Authentication.class,
                SimpMessageHeaderAccessor.getUser(result.getHeaders()));
        assertEquals("ROLE_FACILITY_MANAGER", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).findFirst().orElseThrow());
    }

    @Test
    @DisplayName("Tài khoản bị vô hiệu hoá sau khi CONNECT -> SUBSCRIBE tiếp theo bị từ chối")
    void subscribe_DeactivatedAfterConnect_Rejected() {
        Principal principal = connectAs(guardA);
        guardA.setIsActive(false);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/security-alerts", null), channel));
        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/guards/" + guardA.getId() + "/alerts", null), channel));
    }

    @Test
    @DisplayName("Tài khoản bị xoá mềm sau khi CONNECT -> SUBSCRIBE bị từ chối")
    void subscribe_SoftDeletedAfterConnect_Rejected() {
        Principal principal = connectAs(facilityManager);
        facilityManager.setDeletedAt(OffsetDateTime.now());

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, principal, "/topic/incidents/updates", null), channel));
    }

    @Test
    @DisplayName("Đổi role sau khi CONNECT -> SUBSCRIBE dùng role mới ngay (hạ thì mất quyền, nâng thì có quyền)")
    void subscribe_RoleChangedAfterConnect_UsesDatabaseRole() {
        Principal guardPrincipal = connectAs(guardA);
        Principal normalPrincipal = connectAs(normalUser);
        guardA.setRole(Role.NORMAL_USER);
        normalUser.setRole(Role.FACILITY_MANAGER);

        assertThrows(MessagingException.class, () -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, guardPrincipal, "/topic/security-alerts", null), channel));
        assertDoesNotThrow(() -> interceptor.preSend(
                stomp(StompCommand.SUBSCRIBE, normalPrincipal, "/topic/security-alerts", null), channel));
    }

    // ───────────── SEND ─────────────

    @Test
    @DisplayName("SEND /app/** khi chưa xác thực -> lỗi")
    void send_Unauthenticated_Rejected() {
        Message<byte[]> send = stomp(StompCommand.SEND, null, "/app/ping", null);

        assertThrows(MessagingException.class, () -> interceptor.preSend(send, channel));
    }

    @Test
    @DisplayName("SEND /app/** khi đã xác thực -> qua")
    void send_ToApp_Authenticated_Allowed() {
        Principal principal = connectAs(guardA);
        Message<byte[]> send = stomp(StompCommand.SEND, principal, "/app/ping", null);

        assertDoesNotThrow(() -> interceptor.preSend(send, channel));
    }

    @Test
    @DisplayName("SEND thẳng tới /topic/security-alerts (giả mạo cảnh báo) -> lỗi kể cả đã xác thực")
    void send_DirectlyToTopic_Rejected() {
        Principal principal = connectAs(facilityManager);
        Message<byte[]> send = stomp(StompCommand.SEND, principal, "/topic/security-alerts", null);

        assertThrows(MessagingException.class, () -> interceptor.preSend(send, channel));
    }

    // ───────────── helpers ─────────────

    private Principal connectAs(User user) {
        String token = jwtTokenProvider.generateToken(user);
        Message<?> result = interceptor.preSend(stomp(StompCommand.CONNECT, null, null, "Bearer " + token), channel);
        Principal principal = SimpMessageHeaderAccessor.getUser(result.getHeaders());
        assertInstanceOf(Authentication.class, principal);
        return principal;
    }

    private static Message<byte[]> stomp(StompCommand command, Principal user, String destination, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("test-session");
        if (command == StompCommand.SUBSCRIBE) {
            accessor.setSubscriptionId("sub-0");
        }
        if (user != null) {
            accessor.setUser(user);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private User user(String email, Role role) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .fullName(email)
                .userCode(email.substring(0, email.indexOf('@')))
                .role(role)
                .build();
        usersByEmail.put(email, user);
        return user;
    }
}
