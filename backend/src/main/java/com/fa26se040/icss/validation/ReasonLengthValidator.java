package com.fa26se040.icss.validation;

import com.fa26se040.icss.dto.systemconfig.ReasonLimits;
import com.fa26se040.icss.service.ReasonValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Kiểm {@link ReasonLength} qua {@link ReasonValidator} (đọc cấu hình mỗi lần kiểm).
 * Trong Spring, validator được tạo qua SpringConstraintValidatorFactory nên nhận ReasonValidator;
 * dựng ngoài Spring (vd. MockMvc standalone trong test) thì dùng giá trị mặc định của ConfigKey.
 */
public class ReasonLengthValidator implements ConstraintValidator<ReasonLength, String> {

    private final ReasonValidator reasonValidator;
    private String subject;

    public ReasonLengthValidator() {
        this(null);
    }

    @Autowired
    public ReasonLengthValidator(ReasonValidator reasonValidator) {
        this.reasonValidator = reasonValidator;
    }

    @Override
    public void initialize(ReasonLength annotation) {
        this.subject = annotation.subject();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // @NotBlank báo thiếu lý do
        }
        ReasonLimits limits = currentLimits(reasonValidator);
        if (ReasonValidator.accepts(value, limits)) {
            return true;
        }
        replaceMessage(context, ReasonValidator.rangeMessage(subject, limits));
        return false;
    }

    static ReasonLimits currentLimits(ReasonValidator reasonValidator) {
        return reasonValidator != null ? reasonValidator.limits() : ReasonValidator.DEFAULT_LIMITS;
    }

    /** Câu báo lỗi có giá trị hiện hành; escape để Hibernate Validator không hiểu nhầm {...} / ${...} là biến. */
    static void replaceMessage(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(escape(message)).addConstraintViolation();
    }

    static String escape(String message) {
        return message.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("$", "\\$");
    }
}
