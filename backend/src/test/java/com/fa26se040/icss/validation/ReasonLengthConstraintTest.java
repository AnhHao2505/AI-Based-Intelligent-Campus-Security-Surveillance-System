package com.fa26se040.icss.validation;

import com.fa26se040.icss.dto.accessrequest.AccessRequestFinishRequest;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.systemconfig.SystemConfigUpdateRequest;
import com.fa26se040.icss.enums.RequestStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ReasonLength} / {@code @ValidRejectionReason} với Validator dựng ngoài Spring (không có ReasonValidator):
 * dùng mặc định ConfigKey 10 / 500. Trong Spring, giới hạn lấy từ cấu hình (xem ReasonLengthConfigIntegrationTest).
 */
class ReasonLengthConstraintTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Map<String, String> errors(Object dto) {
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        return violations.stream().collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }

    @Test
    @DisplayName("@ReasonLength: 9 ký tự sai (câu báo theo subject), 10 và 500 đúng, 501 sai, đo sau trim")
    void reasonLengthBoundaries() {
        assertEquals(Map.of("reason", "Lý do hoàn thành phải có từ 10 đến 500 ký tự"),
                errors(new AccessRequestFinishRequest("a".repeat(9))));
        assertTrue(errors(new AccessRequestFinishRequest("a".repeat(10))).isEmpty());
        assertTrue(errors(new AccessRequestFinishRequest("a".repeat(500))).isEmpty());
        assertEquals(Map.of("reason", "Lý do hoàn thành phải có từ 10 đến 500 ký tự"),
                errors(new AccessRequestFinishRequest("a".repeat(501))));
        assertEquals(Map.of("reason", "Lý do hoàn thành phải có từ 10 đến 500 ký tự"),
                errors(new AccessRequestFinishRequest("     " + "a".repeat(9) + "     ")));
    }

    @Test
    @DisplayName("@ReasonLength không báo trùng với @NotBlank khi chỉ có khoảng trắng; subject mặc định là \"Lý do\"")
    void blankLeftToNotBlank() {
        assertEquals(Map.of("reason", "Lý do không được để trống"),
                errors(new SystemConfigUpdateRequest("20", "          ")));
        assertEquals(Map.of("reason", "Lý do phải có từ 10 đến 500 ký tự"),
                errors(new SystemConfigUpdateRequest("20", "ngắn")));
    }

    @Test
    @DisplayName("@ValidRejectionReason: từ chối bắt buộc lý do 10–500 sau trim; duyệt chỉ chặn lý do quá dài; lỗi gắn trường rejectionReason")
    void rejectionReason() {
        String rejectMessage = "Lý do từ chối phải có từ 10 đến 500 ký tự khi từ chối yêu cầu";
        assertEquals(Map.of("rejectionReason", rejectMessage), errors(new AccessRequestReviewRequest(RequestStatus.REJECTED, null)));
        assertEquals(Map.of("rejectionReason", rejectMessage), errors(new AccessRequestReviewRequest(RequestStatus.REJECTED, "   ")));
        assertEquals(Map.of("rejectionReason", rejectMessage), errors(new AccessRequestReviewRequest(RequestStatus.REJECTED, "a".repeat(9))));
        assertTrue(errors(new AccessRequestReviewRequest(RequestStatus.REJECTED, "a".repeat(10))).isEmpty());

        assertTrue(errors(new AccessRequestReviewRequest(RequestStatus.APPROVED, null)).isEmpty());
        assertTrue(errors(new AccessRequestReviewRequest(RequestStatus.APPROVED, "ok")).isEmpty());
        assertEquals(Map.of("rejectionReason", "Lý do từ chối tối đa 500 ký tự"),
                errors(new AccessRequestReviewRequest(RequestStatus.APPROVED, "a".repeat(501))));
    }

    @Test
    @DisplayName("Câu báo lỗi được escape: ký tự { } $ không bị Hibernate Validator hiểu là biến")
    void messageIsEscaped() {
        assertEquals("Lý do \\{a\\} \\$\\{b\\} \\\\", ReasonLengthValidator.escape("Lý do {a} ${b} \\"));
    }
}
