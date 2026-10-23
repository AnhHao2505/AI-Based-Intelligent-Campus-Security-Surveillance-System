package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.AsyncConfig;
import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * M-04 (23/10): mail tài khoản mới chỉ gửi sau khi transaction commit và chạy nền trên mailExecutor.
 * JavaMailSender là mock (không gửi mail thật); FaceDataService là mock nên không cần AI Service / MinIO.
 * Đợi mail bằng CountDownLatch, không sleep cố định ở luồng test.
 * Không dùng @MockBean: mỗi bộ mock là một context riêng giữ thêm một pool kết nối, DB test dùng chung đã gần
 * chạm giới hạn kết nối ("too many clients"). Lớp này dùng context mặc định, chỉ thay mailSender của
 * NotificationService và faceDataService của UserService / UserBulkImportHelper trong lúc chạy, trả lại ở @AfterEach.
 */
class AccountMailAfterCommitIntegrationTest extends AbstractIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired @Qualifier(AsyncConfig.MAIL_EXECUTOR) private ThreadPoolTaskExecutor mailExecutor;
    @Autowired private NotificationService notificationService;
    @Autowired private UserBulkImportHelper userBulkImportHelper;

    private JavaMailSender mailSender;
    private FaceDataService faceDataService;
    /** Bean đích -> {tên field, giá trị thật} để trả lại sau mỗi test. */
    private final List<Object[]> swapped = new ArrayList<>();

    private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
    private final List<String> createdCodes = new ArrayList<>();
    private final List<UUID> fixtureUsers = new ArrayList<>();
    private String suffix;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        faceDataService = mock(FaceDataService.class);
        swap(notificationService, "mailSender", mailSender);
        swap(userService, "faceDataService", faceDataService);
        swap(userBulkImportHelper, "faceDataService", faceDataService);
        sent.clear();
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        when(faceDataService.registerFace(anyString(), any())).thenAnswer(i -> FaceDataResponseDto.builder()
                .id(UUID.randomUUID()).code(i.getArgument(0)).imageFrontUrl("face-profiles/" + i.getArgument(0) + ".jpg").build());
    }

    private void swap(Object bean, String field, Object replacement) {
        Object target = AopTestUtils.getUltimateTargetObject(bean);
        swapped.add(new Object[]{target, field, ReflectionTestUtils.getField(target, field)});
        ReflectionTestUtils.setField(target, field, replacement);
    }

    @AfterEach
    void cleanUp() {
        swapped.forEach(s -> ReflectionTestUtils.setField(s[0], (String) s[1], s[2]));
        swapped.clear();
        createdCodes.forEach(code -> userRepository.findByUserCode(code).ifPresent(userRepository::delete));
        createdCodes.clear();
        userRepository.deleteAllById(fixtureUsers);
        fixtureUsers.clear();
    }

    private StaffAccountCreateRequest request(String code, String fullName) {
        createdCodes.add(code);
        return StaffAccountCreateRequest.builder()
                .fullName(fullName)
                .userCode(code)
                .email(code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .faceImage(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                .build();
    }

    /** Mỗi lần send: ghi lại mail rồi đếm xuống. */
    private CountDownLatch recordMails(int expected, Runnable onSend) {
        CountDownLatch latch = new CountDownLatch(expected);
        doAnswer(i -> {
            onSend.run();
            sent.add(i.getArgument(0));
            latch.countDown();
            return null;
        }).when(mailSender).send(any(MimeMessage.class));
        return latch;
    }

    private boolean userCommitted(String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_code = ?", Integer.class, code);
        return n != null && n == 1;
    }

    private static String recipient(MimeMessage m) {
        try {
            Address[] to = m.getRecipients(Message.RecipientType.TO);
            return to[0].toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("T-01: tạo tài khoản tên <b>A</b> & \"B\" -> HTML trong mail đã escape, không còn thẻ thô")
    void createAccount_mailHtmlEscaped() throws Exception {
        CountDownLatch latch = recordMails(1, () -> { });

        userService.createStaffAccount(request("T01-" + suffix, "<b>A</b> & \"B\""));

        assertThat(latch.await(10, TimeUnit.SECONDS)).as("mail phải được gửi").isTrue();
        String html = NotificationServiceMailContentTest.htmlOf(sent.get(0));
        assertThat(html).contains("&lt;b&gt;A&lt;/b&gt; &amp; &quot;B&quot;");
        assertThat(html).doesNotContain("<b>A</b>");
        assertThat(html).doesNotContain("đổi mật khẩu ngay").contains("Quên mật khẩu");
    }

    @Test
    @DisplayName("T-05: transaction bao ngoài rollback sau khi tạo tài khoản -> không có mail nào được gửi")
    void rollbackAfterUserSaved_noMail() {
        String code = "T05-" + suffix;
        long queuedBefore = mailExecutor.getThreadPoolExecutor().getTaskCount();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            userService.createStaffAccount(request(code, "Rollback Test"));
            // User đã được save + linkToUser xong; ép lỗi trước khi commit
            assertThat(userRepository.findByUserCode(code)).isPresent();
            throw new IllegalStateException("ép rollback sau khi lưu user");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(userCommitted(code)).as("user không được commit").isFalse();
        // Không có task nào được xếp vào mailExecutor (xếp hàng là đồng bộ trong afterCommit) -> không cần chờ
        assertThat(mailExecutor.getThreadPoolExecutor().getTaskCount()).isEqualTo(queuedBefore);
        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("T-06: tạo tài khoản thành công -> mail gửi sau commit, trên luồng mail-")
    void createAccount_mailSentAfterCommitOnMailThread() throws Exception {
        String code = "T06-" + suffix;
        List<Boolean> committedAtSend = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch latch = recordMails(1, () -> {
            // Kết nối riêng của luồng mail chỉ thấy dữ liệu đã commit
            committedAtSend.add(userCommitted(code));
            threads.add(Thread.currentThread().getName());
        });

        userService.createStaffAccount(request(code, "After Commit"));

        assertThat(latch.await(10, TimeUnit.SECONDS)).as("mail phải được gửi").isTrue();
        assertThat(committedAtSend).containsExactly(true);
        assertThat(threads).hasSize(1);
        assertThat(threads.get(0)).startsWith("mail-");
        assertThat(recipient(sent.get(0))).isEqualTo(code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn");
    }

    @Test
    @DisplayName("T-07: nạp hàng loạt 3 dòng, mỗi mail mất 2 giây -> request không chờ ~6 giây, cuối cùng đủ 3 mail")
    void bulkImport_doesNotWaitForMail() throws Exception {
        User admin = userRepository.save(User.builder()
                .email("t07.adm." + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn").userCode("T07-ADM-" + suffix)
                .fullName("T07 admin").role(Role.ADMIN).accessLevel(3).isActive(true).build());
        fixtureUsers.add(admin.getId());

        List<String> codes = List.of("T07A" + suffix, "T07B" + suffix, "T07C" + suffix);
        createdCodes.addAll(codes);
        List<Boolean> committedAtSend = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        doAnswer(i -> {
            MimeMessage m = i.getArgument(0);
            String to = recipient(m);
            committedAtSend.add(userCommitted(to.substring(0, to.indexOf('@')).toUpperCase(Locale.ROOT)));
            Thread.sleep(2000); // SMTP giả chậm
            sent.add(m);
            latch.countDown();
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        long started = System.nanoTime();
        MvcResult result = mockMvc.perform(multipart("/api/users/normal/bulk-import")
                        .file(zipOf(codes))
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin)))
                .andReturn();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        long mailsLeftAtReturn = latch.getCount();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"successCount\":3");
        assertThat(elapsedMs).as("request không được chờ gửi đủ 3 mail (~6 giây)").isLessThan(6000);
        assertThat(mailsLeftAtReturn).as("lúc request trả về, mail vẫn đang gửi nền").isPositive();

        assertThat(latch.await(20, TimeUnit.SECONDS)).as("cả 3 mail cuối cùng đều được gửi").isTrue();
        Set<String> recipients = sent.stream().map(AccountMailAfterCommitIntegrationTest::recipient).collect(Collectors.toSet());
        assertThat(recipients).containsExactlyInAnyOrderElementsOf(
                codes.stream().map(c -> c.toLowerCase(Locale.ROOT) + "@fpt.edu.vn").toList());
        assertThat(committedAtSend).as("mỗi dòng đã commit trước khi gửi mail của dòng đó").containsOnly(true).hasSize(3);
    }

    /** ZIP nạp người dùng thường: metadata.csv (user_code, full_name, email) + images/<mã>.jpg. */
    private static MockMultipartFile zipOf(List<String> codes) throws Exception {
        StringBuilder csv = new StringBuilder("user_code,full_name,email\n");
        for (String code : codes) {
            csv.append(code).append(",Người dùng ").append(code).append(",").append(code.toLowerCase(Locale.ROOT)).append("@fpt.edu.vn\n");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry("metadata.csv"));
            zos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (String code : codes) {
                zos.putNextEntry(new ZipEntry("images/" + code + ".jpg"));
                zos.write(new byte[]{1, 2, 3});
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", "import.zip", "application/zip", bytes.toByteArray());
    }
}
