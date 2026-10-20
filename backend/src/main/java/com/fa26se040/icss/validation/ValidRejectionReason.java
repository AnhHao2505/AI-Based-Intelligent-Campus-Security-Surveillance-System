package com.fa26se040.icss.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MF3 (CLAUDE.md §9): từ chối bắt buộc có lý do, kiểm chéo ở DTO {@code AccessRequestReviewRequest}.
 * REJECTED: lý do bắt buộc, độ dài theo REASON_MIN_LENGTH..REASON_MAX_LENGTH; trạng thái khác: lý do nếu có không vượt
 * REASON_MAX_LENGTH. Đo sau khi trim. Lỗi gắn vào trường {@code rejectionReason} (Spring đọc được accessor của record
 * để lấy giá trị bị từ chối; tên khác như {@code rejectionReasonValid} không có accessor sẽ làm hỏng bước này).
 */
@Documented
@Constraint(validatedBy = RejectionReasonValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidRejectionReason {

    /** Câu báo lỗi dựng lúc kiểm theo giới hạn hiện hành; giá trị này không dùng. */
    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
