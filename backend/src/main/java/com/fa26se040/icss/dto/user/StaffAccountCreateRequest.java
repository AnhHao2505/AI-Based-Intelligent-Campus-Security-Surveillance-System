package com.fa26se040.icss.dto.user;

import com.fa26se040.icss.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffAccountCreateRequest {

    @NotBlank(message = "Họ và tên không được để trống")
    @jakarta.validation.constraints.Size(min = 3, max = 100, message = "Họ và tên phải có độ dài từ 3 đến 100 ký tự")
    private String fullName;

    @NotBlank(message = "Mã cán bộ không được để trống")
    @jakarta.validation.constraints.Size(min = 1, max = 50, message = "Mã cán bộ phải có độ dài từ 1 đến 50 ký tự")
    @jakarta.validation.constraints.Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Mã cán bộ chỉ chứa chữ cái, chữ số, dấu gạch ngang hoặc gạch dưới")
    private String userCode;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    @jakarta.validation.constraints.Size(max = 255, message = "Email không được vượt quá 255 ký tự")
    private String email;

    @NotNull(message = "Vai trò không được để trống")
    private Role role;

    @NotNull(message = "Ảnh khuôn mặt không được để trống")
    private MultipartFile faceImage;
}
