package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.systemconfig.ReasonLimits;
import com.fa26se040.icss.enums.ConfigKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Checklist 6.1 (V82): một chỗ kiểm duy nhất cho độ dài lý do thao tác (huỷ, từ chối, hoàn thành đơn, quy tắc khu vực,
 * cấp truy cập, preset, sửa cấu hình, lượt khách...). Đọc REASON_MIN_LENGTH / REASON_MAX_LENGTH mỗi lần gọi nên ADMIN
 * sửa cấu hình là có hiệu lực ngay, không cần restart. Độ dài đo sau khi trim.
 * DTO dùng qua {@code @ReasonLength} / {@code @ValidRejectionReason}; service gọi thẳng {@link #limits()} hoặc {@link #validate}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReasonValidator {

    /**
     * Cột nhỏ nhất đang lưu lý do là VARCHAR(500): audit_logs.reason (V42, mọi lý do đều ghi vào đây),
     * system_configuration_change_logs.reason, area_event_schedules.note / cancel_note, lý do lượt khách.
     * REASON_MAX_LENGTH không được vượt quá giá trị này.
     */
    public static final int STORAGE_MAX_LENGTH = 500;

    /** Mặc định khai báo ở ConfigKey: dùng khi cấu hình hỏng hoặc khi validator dựng ngoài Spring (MockMvc standalone). */
    public static final ReasonLimits DEFAULT_LIMITS = new ReasonLimits(
            Integer.parseInt(ConfigKey.REASON_MIN_LENGTH.getDefaultValue()),
            Integer.parseInt(ConfigKey.REASON_MAX_LENGTH.getDefaultValue()));

    private final SystemConfigService systemConfigService;

    /** Giới hạn đang hiệu lực. Cặp giá trị không hợp lệ (vd. sửa thẳng trong DB) -> log WARN, dùng mặc định. */
    public ReasonLimits limits() {
        int min = systemConfigService.getInt(ConfigKey.REASON_MIN_LENGTH);
        int max = systemConfigService.getInt(ConfigKey.REASON_MAX_LENGTH);
        if (!isConsistent(min, max)) {
            log.warn("Cấu hình độ dài lý do không hợp lệ (REASON_MIN_LENGTH={}, REASON_MAX_LENGTH={}), dùng mặc định {}",
                    min, max, DEFAULT_LIMITS);
            return DEFAULT_LIMITS;
        }
        return new ReasonLimits(min, max);
    }

    /** 1 ≤ min < max ≤ độ dài cột lưu lý do. */
    public static boolean isConsistent(int minLength, int maxLength) {
        return minLength >= 1 && minLength < maxLength && maxLength <= STORAGE_MAX_LENGTH;
    }

    /** Lý do đo sau trim nằm trong giới hạn; null hoặc chỉ có khoảng trắng -> false. */
    public static boolean accepts(String reason, ReasonLimits limits) {
        if (reason == null) {
            return false;
        }
        int length = reason.trim().length();
        return length >= limits.minLength() && length <= limits.maxLength();
    }

    /** Câu báo lỗi ghi giá trị cấu hình hiện hành, vd. "Lý do huỷ phải có từ 10 đến 500 ký tự". */
    public static String rangeMessage(String subject, ReasonLimits limits) {
        return subject + " phải có từ " + limits.minLength() + " đến " + limits.maxLength() + " ký tự";
    }

    public boolean isValid(String reason) {
        return accepts(reason, limits());
    }

    /** Lý do bắt buộc: trả lý do đã trim; thiếu hoặc sai độ dài -> IllegalArgumentException (400). */
    public String validate(String reason) {
        return validate(reason, "Lý do");
    }

    /** Như {@link #validate(String)}, câu báo lỗi bắt đầu bằng {@code subject} (vd. "Lý do từ chối"). */
    public String validate(String reason, String subject) {
        ReasonLimits limits = limits();
        if (!accepts(reason, limits)) {
            throw new IllegalArgumentException(rangeMessage(subject, limits));
        }
        return reason.trim();
    }
}
