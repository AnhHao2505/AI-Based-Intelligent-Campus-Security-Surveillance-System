package com.fa26se040.icss.validation;

import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.systemconfig.ReasonLimits;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.service.ReasonValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;

/** Kiểm {@link ValidRejectionReason}; giới hạn lấy như {@link ReasonLengthValidator}. */
public class RejectionReasonValidator implements ConstraintValidator<ValidRejectionReason, AccessRequestReviewRequest> {

    private final ReasonValidator reasonValidator;

    public RejectionReasonValidator() {
        this(null);
    }

    @Autowired
    public RejectionReasonValidator(ReasonValidator reasonValidator) {
        this.reasonValidator = reasonValidator;
    }

    @Override
    public boolean isValid(AccessRequestReviewRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        ReasonLimits limits = ReasonLengthValidator.currentLimits(reasonValidator);
        String reason = request.rejectionReason();
        if (request.status() == RequestStatus.REJECTED) {
            if (ReasonValidator.accepts(reason, limits)) {
                return true;
            }
            addViolation(context, "rejectionReason",
                    ReasonValidator.rangeMessage("Lý do từ chối", limits) + " khi từ chối yêu cầu");
            return false;
        }
        if (reason == null || reason.trim().length() <= limits.maxLength()) {
            return true;
        }
        addViolation(context, "rejectionReason", "Lý do từ chối tối đa " + limits.maxLength() + " ký tự");
        return false;
    }

    private static void addViolation(ConstraintValidatorContext context, String property, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(ReasonLengthValidator.escape(message))
                .addPropertyNode(property)
                .addConstraintViolation();
    }
}
