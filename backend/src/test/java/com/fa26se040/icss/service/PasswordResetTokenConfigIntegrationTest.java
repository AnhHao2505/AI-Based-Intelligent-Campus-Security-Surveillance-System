package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.ForgotPasswordRequest;
import com.fa26se040.icss.entity.PasswordResetToken;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.PasswordResetTokenRepository;
import com.fa26se040.icss.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M-03 (V89): hạn link đặt lại mật khẩu đọc từ PASSWORD_RESET_TOKEN_MINUTES lúc tạo token, mail ghi đúng số phút.
 * JavaMailSender là mock (không gửi mail thật); NotificationService thật nên mail chụp lại được nội dung.
 * Không dùng @MockBean: mỗi bộ mock là một context riêng giữ thêm một pool kết nối, DB test dùng chung đã gần
 * chạm giới hạn kết nối ("too many clients"). Lớp này dùng context mặc định và chỉ thay mailSender của
 * NotificationService trong lúc chạy, trả lại ở @AfterEach.
 */
class PasswordResetTokenConfigIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY = ConfigKey.PASSWORD_RESET_TOKEN_MINUTES.getKey();

    @Autowired private AuthService authService;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationService notificationService;

    private JavaMailSender mailSender;
    private Object realMailSender;

    private User admin;
    private User target;
    private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
    private CountDownLatch mailLatch;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        Object mailHolder = AopTestUtils.getUltimateTargetObject(notificationService);
        realMailSender = ReflectionTestUtils.getField(mailHolder, "mailSender");
        ReflectionTestUtils.setField(mailHolder, "mailSender", mailSender);
        sent.clear();
        mailLatch = new CountDownLatch(1);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(i -> {
            sent.add(i.getArgument(0));
            mailLatch.countDown();
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(User.builder()
                .email("prt.adm." + suffix + "@fpt.edu.vn").userCode("PRT-ADM-" + suffix).fullName("PRT admin " + suffix)
                .role(Role.ADMIN).accessLevel(3).isActive(true).build());
        target = userRepository.save(User.builder()
                .email("prt.usr." + suffix + "@fpt.edu.vn").userCode("PRT-USR-" + suffix).fullName("PRT user " + suffix)
                .role(Role.NORMAL_USER).accessLevel(1).isActive(true).build());
        // Lần chạy trước dừng giữa chừng có thể để lại giá trị khác -> luôn bắt đầu từ mặc định
        resetToDefault();
    }

    private void resetToDefault() {
        String def = ConfigKey.PASSWORD_RESET_TOKEN_MINUTES.getDefaultValue();
        if (!def.equals(systemConfigService.getString(ConfigKey.PASSWORD_RESET_TOKEN_MINUTES))) {
            systemConfigService.update(KEY, def, admin.getEmail());
        }
    }

    @AfterEach
    void cleanUp() {
        Object mailHolder = AopTestUtils.getUltimateTargetObject(notificationService);
        ReflectionTestUtils.setField(mailHolder, "mailSender", realMailSender);
        resetToDefault();
        passwordResetTokenRepository.findAll().stream()
                .filter(t -> target.getEmail().equals(t.getEmail()))
                .forEach(passwordResetTokenRepository::delete);
        userRepository.delete(target);
        // admin có dòng trong system_configuration_change_logs -> chỉ vô hiệu hoá, không xoá
        admin.setIsActive(false);
        userRepository.save(admin);
    }

    private static ForgotPasswordRequest forgot(String email) {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail(email);
        return request;
    }

    private PasswordResetToken onlyTokenOf(String email) {
        List<PasswordResetToken> tokens = passwordResetTokenRepository.findAll().stream()
                .filter(t -> email.equals(t.getEmail()))
                .toList();
        assertThat(tokens).hasSize(1);
        return tokens.get(0);
    }

    @Test
    @DisplayName("T-03: PASSWORD_RESET_TOKEN_MINUTES = 30 -> token hết hạn sau ~30 phút, mail ghi \"30 phút\"")
    void tokenAndMailUseConfiguredMinutes() throws Exception {
        systemConfigService.update(KEY, "30", admin.getEmail());

        OffsetDateTime before = OffsetDateTime.now();
        authService.sendResetLink(forgot(target.getEmail()));
        OffsetDateTime after = OffsetDateTime.now();

        PasswordResetToken token = onlyTokenOf(target.getEmail());
        assertThat(token.getExpiredAt()).isBetween(before.plusMinutes(30).minusSeconds(1), after.plusMinutes(30).plusSeconds(1));

        assertThat(mailLatch.await(10, TimeUnit.SECONDS)).as("mail đặt lại mật khẩu phải được gửi").isTrue();
        String html = NotificationServiceMailContentTest.htmlOf(sent.get(0));
        assertThat(html).contains("có hiệu lực trong vòng 30 phút");
        assertThat(html).doesNotContain("15 phút");
    }

    @Test
    @DisplayName("M-03: giá trị mặc định 15 -> token hết hạn sau ~15 phút")
    void defaultIsFifteenMinutes() throws Exception {
        authService.sendResetLink(forgot(target.getEmail()));

        PasswordResetToken token = onlyTokenOf(target.getEmail());
        Duration validFor = Duration.between(OffsetDateTime.now(), token.getExpiredAt());
        assertThat(validFor).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));
        assertThat(mailLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(NotificationServiceMailContentTest.htmlOf(sent.get(0))).contains("có hiệu lực trong vòng 15 phút");
    }

    @Test
    @DisplayName("T-04: 4 và 61 bị từ chối theo min/max có sẵn, giá trị giữ 15")
    void outOfRangeRejected() {
        assertThatThrownBy(() -> systemConfigService.update(KEY, "4", admin.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Giá trị không được nhỏ hơn 5");
        assertThatThrownBy(() -> systemConfigService.update(KEY, "61", admin.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Giá trị không được lớn hơn 60");

        assertThat(systemConfigService.getInt(ConfigKey.PASSWORD_RESET_TOKEN_MINUTES)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT config_value FROM system_configurations WHERE config_key = ?", String.class, KEY))
                .isEqualTo("15");
    }

    @Test
    @DisplayName("T-09: V89 tạo khoá PASSWORD_RESET_TOKEN_MINUTES mặc định 15, min 5, max 60")
    void migrationRow() {
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '89'", Boolean.class)).isTrue();
        assertThat(ConfigKey.PASSWORD_RESET_TOKEN_MINUTES.getDefaultValue()).isEqualTo("15");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT config_value, data_type, min_value, max_value, unit, config_group, editable "
                        + "FROM system_configurations WHERE config_key = ?", KEY);

        assertThat(row.get("config_value")).isEqualTo("15");
        assertThat(row.get("data_type")).isEqualTo("INTEGER");
        assertThat(new BigDecimal(row.get("min_value").toString())).isEqualByComparingTo("5");
        assertThat(new BigDecimal(row.get("max_value").toString())).isEqualByComparingTo("60");
        assertThat(row.get("unit")).isEqualTo("phút");
        assertThat(row.get("config_group")).isEqualTo("SECURITY");
        assertThat(row.get("editable")).isEqualTo(true);
    }
}
