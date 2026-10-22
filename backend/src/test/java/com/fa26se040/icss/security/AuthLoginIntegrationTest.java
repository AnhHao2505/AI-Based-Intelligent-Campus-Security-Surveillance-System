package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.UserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T2 (20/10): đăng nhập e-mail / mật khẩu (POST /api/auth/login), chỉ ghi lại hành vi hiện có.
 * Mật khẩu là chuỗi ngẫu nhiên sinh trong test; token chỉ kiểm có / không, không in ra.
 */
class AuthLoginIntegrationTest extends AbstractIntegrationTest {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Cùng bộ mock với AuthPasswordResetIntegrationTest để dùng chung Spring context
    @MockBean
    private NotificationService notificationService;

    private final String password = "T2-" + UUID.randomUUID();
    private User admin;

    @BeforeAll
    void setUpAdmin() {
        admin = newUser("adm", Role.ADMIN);
    }

    private User newUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .email("t2." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("T2-" + tag.toUpperCase(Locale.ROOT) + "-" + suffix)
                .fullName("T2 " + tag + " " + suffix)
                .password(passwordEncoder.encode(password))
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    private ResultActions login(String email, String pwd) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + pwd + "\"}"));
    }

    @Test
    @DisplayName("T2: đúng mật khẩu -> 200, có access token và thông tin user")
    void correctPassword_returnsToken() throws Exception {
        User user = newUser("ok", Role.NORMAL_USER);
        login(user.getEmail(), password)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken", not(emptyOrNullString())))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.email").value(user.getEmail()));
    }

    @Test
    @DisplayName("T2: sai mật khẩu -> 401 'Invalid email or password'")
    void wrongPassword_returns401() throws Exception {
        User user = newUser("bad", Role.NORMAL_USER);
        login(user.getEmail(), password + "x")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    @DisplayName("T2: tài khoản bị vô hiệu hoá, đúng mật khẩu -> 401 cùng câu với sai mật khẩu")
    void deactivatedAccount_returns401() throws Exception {
        User user = newUser("off", Role.NORMAL_USER);
        user.setIsActive(false);
        userRepository.save(user);
        login(user.getEmail(), password)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("T2: tài khoản đã xoá (UserService.softDelete), đúng mật khẩu -> 401 cùng câu")
    void deletedAccount_returns401() throws Exception {
        User user = newUser("del", Role.NORMAL_USER);
        userService.softDelete(user.getId(), admin.getEmail());
        login(user.getEmail(), password)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("T2: e-mail không có tài khoản -> 401 cùng câu")
    void unknownEmail_returns401() throws Exception {
        login("t2.unknown." + UUID.randomUUID().toString().substring(0, 8) + "@fpt.edu.vn", password)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS));
    }
}
