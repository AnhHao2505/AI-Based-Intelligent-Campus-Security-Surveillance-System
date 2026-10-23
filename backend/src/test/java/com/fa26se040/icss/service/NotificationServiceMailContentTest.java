package com.fa26se040.icss.service;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M-01 / M-02 (23/10): nội dung HTML của mail tài khoản mới và mail đặt lại mật khẩu.
 * JavaMailSender là mock, không gửi mail thật; nội dung đọc lại từ MimeMessage đã chụp.
 */
class NotificationServiceMailContentTest {

    private JavaMailSender mailSender;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        notificationService = new NotificationService(mailSender);
    }

    /** Phần HTML duy nhất trong mail (MimeMessageHelper multipart: mixed -> related -> text/html). */
    static String htmlOf(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String s) {
            return s;
        }
        Multipart multipart = (Multipart) content;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart child = multipart.getBodyPart(i);
            String html = htmlOf(child);
            if (html != null) {
                return html;
            }
        }
        return null;
    }

    private String sentHtml() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return htmlOf(captor.getValue());
    }

    @Test
    @DisplayName("T-01: tên, mã, mật khẩu tạm có ký tự HTML -> được escape, không còn thẻ thô")
    void staffAccountMail_escapesHtml() throws Exception {
        notificationService.sendStaffAccountSetupEmail("t01@fpt.edu.vn", "<b>A</b> & \"B\"", "<i>SV</i>", "p&<x>'q");

        String html = sentHtml();
        assertThat(html).contains("&lt;b&gt;A&lt;/b&gt; &amp; &quot;B&quot;");
        assertThat(html).doesNotContain("<b>A</b>");
        assertThat(html).contains("&lt;i&gt;SV&lt;/i&gt;").doesNotContain("<i>SV</i>");
        assertThat(html).contains("p&amp;&lt;x&gt;&#39;q");
    }

    @Test
    @DisplayName("T-02: mail tài khoản mới không còn 'đổi mật khẩu ngay', có hướng dẫn 'Quên mật khẩu'")
    void staffAccountMail_pointsToForgotPassword() throws Exception {
        notificationService.sendStaffAccountSetupEmail("t02@fpt.edu.vn", "Nguyễn Văn A", "SV-T02", "Abc123");

        String html = sentHtml();
        assertThat(html).doesNotContain("đổi mật khẩu ngay");
        assertThat(html).contains("Để đặt mật khẩu của riêng bạn, hãy dùng chức năng <strong>Quên mật khẩu</strong> ở màn hình đăng nhập.");
    }

    @Test
    @DisplayName("M-01: link đặt lại mật khẩu được escape trong cả href lẫn phần chữ")
    void resetMail_escapesLink() throws Exception {
        notificationService.sendResetPasswordEmail("reset@fpt.edu.vn", "http://x.local/?token=a'b&c=<d>", 15);

        String html = sentHtml();
        assertThat(html).contains("href='http://x.local/?token=a&#39;b&amp;c=&lt;d&gt;'");
        assertThat(html).doesNotContain("a'b&c=<d>");
    }
}
