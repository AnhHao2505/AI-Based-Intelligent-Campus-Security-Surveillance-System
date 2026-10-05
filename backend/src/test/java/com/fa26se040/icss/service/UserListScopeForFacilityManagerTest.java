package com.fa26se040.icss.service;

import com.fa26se040.icss.controller.UserController;
import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.dto.user.UserPageResponse;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/users: FACILITY_MANAGER chỉ được thấy tài khoản GUARD — bộ lọc ép ở server,
 * không phụ thuộc accountType client gửi. ADMIN giữ nguyên hành vi cũ.
 */
@ExtendWith(MockitoExtension.class)
class UserListScopeForFacilityManagerTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private final Pageable pageable = PageRequest.of(0, 100);
    private List<User> allUsers;

    @BeforeEach
    void setUp() {
        allUsers = List.of(
                user("AD001", Role.ADMIN),
                user("FM001", Role.FACILITY_MANAGER),
                user("BV001", Role.GUARD),
                user("BV002", Role.GUARD),
                user("SV001", Role.NORMAL_USER),
                user("SV002", Role.NORMAL_USER),
                user("SV003", Role.NORMAL_USER));

        // Giả lập DB: repository lọc thật trên tập user mẫu theo đúng tham số service truyền xuống
        lenient().when(userRepository.searchUsers(any(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(allUsers, inv.getArgument(1), allUsers.size()));
        lenient().when(userRepository.searchFilteredUsers(any(), anyCollection(), any(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Collection<Role> roles = inv.getArgument(1);
                    Boolean isActive = inv.getArgument(2);
                    List<User> matched = allUsers.stream()
                            .filter(u -> roles.contains(u.getRole()))
                            .filter(u -> isActive == null || isActive.equals(u.getIsActive()))
                            .toList();
                    return new PageImpl<>(matched, inv.getArgument(3), matched.size());
                });
        lenient().when(userRepository.countByRolesAndDeletedAtIsNull(anyCollection()))
                .thenAnswer(inv -> {
                    Collection<Role> roles = inv.getArgument(0);
                    return allUsers.stream().filter(u -> roles.contains(u.getRole())).count();
                });
    }

    @Test
    @DisplayName("FM không gửi accountType -> chỉ nhận GUARD, không lộ tổng số tài khoản thường")
    void facilityManager_NoFilter_OnlyGuards() {
        UserPageResponse response = userService.getUsers(null, null, null, pageable, Role.FACILITY_MANAGER);

        assertEquals(Set.of(Role.GUARD), rolesIn(response));
        assertEquals(2, response.getUsers().getTotalElements());
        assertEquals(0, response.getNormalCount());
        assertEquals(2, response.getSystemCount());
        verify(userRepository, never()).searchUsers(any(), any());
        verify(userRepository, never()).countByRolesAndDeletedAtIsNull(UserService.NORMAL_ROLES);
        verify(userRepository, never()).countByRolesAndDeletedAtIsNull(UserService.SYSTEM_ROLES);
    }

    @Test
    @DisplayName("FM gửi accountType=NORMAL -> không có NORMAL_USER nào trong kết quả")
    void facilityManager_AccountTypeNormal_NoNormalUsers() {
        UserPageResponse response = userService.getUsers(null, "NORMAL", null, pageable, Role.FACILITY_MANAGER);

        assertFalse(rolesIn(response).contains(Role.NORMAL_USER));
        assertEquals(Set.of(Role.GUARD), rolesIn(response));
        assertEquals(0, response.getNormalCount());
    }

    @Test
    @DisplayName("FM gửi accountType=SYSTEM -> không có ADMIN/FACILITY_MANAGER, chỉ GUARD")
    void facilityManager_AccountTypeSystem_NoAdminOrFacilityManager() {
        UserPageResponse response = userService.getUsers(null, "SYSTEM", true, pageable, Role.FACILITY_MANAGER);

        Set<Role> roles = rolesIn(response);
        assertFalse(roles.contains(Role.ADMIN));
        assertFalse(roles.contains(Role.FACILITY_MANAGER));
        assertEquals(Set.of(Role.GUARD), roles);
        assertEquals(2, response.getSystemCount());
    }

    @Test
    @DisplayName("FM: keyword và isActive vẫn được truyền xuống, chỉ role bị ép về GUARD")
    void facilityManager_KeywordAndIsActivePassedThrough() {
        userService.getUsers("  bv  ", "NORMAL", true, pageable, Role.FACILITY_MANAGER);

        verify(userRepository).searchFilteredUsers(eq("bv"), eq(List.of(Role.GUARD)), eq(true), eq(pageable));
    }

    @Test
    @DisplayName("ADMIN không filter -> thấy mọi role, số đếm như cũ")
    void admin_NoFilter_SeesEveryone() {
        UserPageResponse response = userService.getUsers(null, null, null, pageable, Role.ADMIN);

        assertEquals(Set.of(Role.ADMIN, Role.FACILITY_MANAGER, Role.GUARD, Role.NORMAL_USER), rolesIn(response));
        assertEquals(7, response.getUsers().getTotalElements());
        assertEquals(3, response.getNormalCount());
        assertEquals(4, response.getSystemCount());
        verify(userRepository).searchUsers(isNull(), eq(pageable));
    }

    @Test
    @DisplayName("ADMIN accountType=NORMAL -> chỉ NORMAL_USER (hành vi cũ)")
    void admin_AccountTypeNormal_OnlyNormalUsers() {
        UserPageResponse response = userService.getUsers(null, "NORMAL", null, pageable, Role.ADMIN);

        assertEquals(Set.of(Role.NORMAL_USER), rolesIn(response));
        assertEquals(3, response.getNormalCount());
        assertEquals(4, response.getSystemCount());
    }

    @Nested
    @DisplayName("UserController truyền role người gọi (từ Authentication) vào service")
    class ControllerPassesCallerRole {

        @Mock
        private UserService mockUserService;

        private MockMvc mockMvc;

        @BeforeEach
        void setUpMvc() {
            mockMvc = MockMvcBuilders.standaloneSetup(new UserController(mockUserService)).build();
            when(mockUserService.getUsers(any(), any(), any(), any(), any()))
                    .thenReturn(UserPageResponse.builder().build());
        }

        @Test
        @DisplayName("Principal ROLE_FACILITY_MANAGER -> service nhận callerRole=FACILITY_MANAGER")
        void facilityManagerPrincipal_PassesFacilityManagerRole() throws Exception {
            mockMvc.perform(get("/api/users")
                            .param("accountType", "SYSTEM")
                            .param("isActive", "true")
                            .principal(auth("fm@fpt.edu.vn", "ROLE_FACILITY_MANAGER")))
                    .andExpect(status().isOk());

            verify(mockUserService).getUsers(isNull(), eq("SYSTEM"), eq(true), any(Pageable.class), eq(Role.FACILITY_MANAGER));
        }

        @Test
        @DisplayName("Principal ROLE_ADMIN -> service nhận callerRole=ADMIN")
        void adminPrincipal_PassesAdminRole() throws Exception {
            mockMvc.perform(get("/api/users")
                            .principal(auth("admin@fpt.edu.vn", "ROLE_ADMIN")))
                    .andExpect(status().isOk());

            verify(mockUserService).getUsers(isNull(), isNull(), isNull(), any(Pageable.class), eq(Role.ADMIN));
        }

        private UsernamePasswordAuthenticationToken auth(String email, String authority) {
            return new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority(authority)));
        }
    }

    private static User user(String code, Role role) {
        return User.builder()
                .id(UUID.randomUUID())
                .userCode(code)
                .fullName("User " + code)
                .email(code.toLowerCase() + "@fpt.edu.vn")
                .role(role)
                .isActive(true)
                .build();
    }

    private static Set<Role> rolesIn(UserPageResponse response) {
        return response.getUsers().getContent().stream()
                .map(UserListResponse::role)
                .collect(Collectors.toSet());
    }
}
