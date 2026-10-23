package com.fa26se040.icss.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final JavaMailSender mailSender;

    public void sendResetPasswordEmail(String toEmail, String resetLink, int validMinutes) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(toEmail);
            helper.setSubject("[Campus Security] Xác nhận khôi phục mật khẩu");

            // M-01: mọi giá trị đưa vào HTML đều escape (kể cả trong thuộc tính href)
            String safeLink = htmlEscape(resetLink);

            String htmlContent = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 10px;'>"
                    + "<h2 style='color: #4A90E2; text-align: center;'>Khôi phục mật khẩu tài khoản</h2>"
                    + "<p>Xin chào,</p>"
                    + "<p>Bạn nhận được email này vì đã gửi yêu cầu khôi phục mật khẩu cho tài khoản Campus Security của mình.</p>"
                    + "<p>Vui lòng click vào nút bên dưới để tiến hành đổi mật khẩu. Đường dẫn này có hiệu lực trong vòng " + validMinutes + " phút:</p>"
                    + "<div style='text-align: center; margin: 30px 0;'>"
                    + "  <a href='" + safeLink + "' style='background-color: #4A90E2; color: white; padding: 12px 24px; text-decoration: none; border-radius: 5px; font-weight: bold;'>Đổi mật khẩu mới</a>"
                    + "</div>"
                    + "<p>Nếu link nút trên không hoạt động, bạn có thể copy link sau dán vào trình duyệt:</p>"
                    + "<p style='word-break: break-all;'><a href='" + safeLink + "'>" + safeLink + "</a></p>"
                    + "<hr style='border: none; border-top: 1px solid #eee; margin-top: 30px;' />"
                    + "<p style='font-size: 12px; color: #888;'>Nếu bạn không yêu cầu thay đổi mật khẩu này, hãy bỏ qua email này an toàn.</p>"
                    + "</div>";

            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Reset password email successfully sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send reset password email to {}", toEmail, e);
            // 6.4: không ghi link đặt lại mật khẩu ra log (ai đọc log cũng chiếm được tài khoản)
        }
    }

    public void sendStaffAccountSetupEmail(String toEmail, String fullName, String userCode, String temporaryPassword) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(toEmail);
            helper.setSubject("[Campus Security] Thông tin tài khoản nhân viên mới");

            // M-01: tên, mã, e-mail, mật khẩu tạm có thể chứa < > & " ' -> escape trước khi ghép HTML

            String htmlContent = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 20px; border: 1px solid #ddd; border-radius: 10px;'>"
                    + "<h2 style='color: #4A90E2; text-align: center;'>Tài khoản Campus Security của bạn đã được tạo</h2>"
                    + "<p>Xin chào <strong>" + htmlEscape(fullName) + "</strong>,</p>"
                    + "<p>Tài khoản nhân viên của bạn đã được khởi tạo thành công trên hệ thống giám sát an ninh Campus Security với các thông tin sau:</p>"
                    + "<ul>"
                    + "  <li><strong>Mã nhân viên/cán bộ:</strong> " + htmlEscape(userCode) + "</li>"
                    + "  <li><strong>Email đăng nhập:</strong> " + htmlEscape(toEmail) + "</li>"
                    + "  <li><strong>Mật khẩu tạm thời:</strong> <code style='background:#f4f4f4; padding: 2px 6px; border-radius: 4px; font-size: 16px;'>" + htmlEscape(temporaryPassword) + "</code></li>"
                    + "</ul>"
                    + "<p>Để đặt mật khẩu của riêng bạn, hãy dùng chức năng <strong>Quên mật khẩu</strong> ở màn hình đăng nhập.</p>"
                    + "<hr style='border: none; border-top: 1px solid #eee; margin-top: 30px;' />"
                    + "<p style='font-size: 12px; color: #888;'>Email này được tự động gửi từ hệ thống Campus Security. Vui lòng không trả lời email này.</p>"
                    + "</div>";

            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Staff account setup email successfully sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send staff account setup email to {}", toEmail, e);
            // 6.4: không ghi mật khẩu tạm ra log; người dùng dùng "Quên mật khẩu" khi email không tới
        }
    }
}
