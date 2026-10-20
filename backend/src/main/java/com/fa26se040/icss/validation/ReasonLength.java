package com.fa26se040.icss.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Checklist 6.1: độ dài lý do theo cấu hình hiện hành (REASON_MIN_LENGTH / REASON_MAX_LENGTH), đo sau khi trim.
 * Thay {@code @Size(min = 10, max = 500)}: vẫn trả 400 VALIDATION_ERROR theo từng trường như trước, câu báo lỗi ghi
 * giá trị đang cấu hình. null / chỉ khoảng trắng coi là hợp lệ ở đây, để {@code @NotBlank} báo thiếu lý do.
 */
@Documented
@Constraint(validatedBy = ReasonLengthValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReasonLength {

    /** Câu báo lỗi dựng lúc kiểm theo giới hạn hiện hành; giá trị này không dùng. */
    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Chủ ngữ của câu báo lỗi, vd. "Lý do huỷ" -> "Lý do huỷ phải có từ 10 đến 500 ký tự". */
    String subject() default "Lý do";
}
