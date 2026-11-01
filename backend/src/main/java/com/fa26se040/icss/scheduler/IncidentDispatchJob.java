package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.repository.IncidentDispatchQueryRepository;
import com.fa26se040.icss.service.IncidentDispatchService;
import com.fa26se040.icss.service.SecurityIncidentService;
import com.fa26se040.icss.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Job hết hạn lời mời của phân phối theo đợt (V93, K4, BR-DSP-05, BR-DSP-08). Mẫu như {@link IncidentEscalationJob}.
 *
 * <p>Chu kỳ đọc DISPATCH_CHECK_SECONDS mỗi lần tính lần chạy kế (Trigger động) nên sửa cấu hình có hiệu lực từ lần chạy
 * sau, không cần restart. Cờ vận hành {@code icss.scheduler.incident-dispatch-enabled} chỉ bật / tắt job (profile test tắt,
 * test gọi thẳng {@link #runOnce}). Job KHÔNG phụ thuộc DISPATCH_ENABLED: tắt dispatch giữa chừng thì sự cố đang ở bước 1
 * vẫn đi tới broadcast (E9).</p>
 *
 * <p>Mỗi lần chạy: (1) lời mời quá hạn -> EXPIRED; (2) huỷ lời mời của sự cố đã rời NEW, bảo vệ hết ca hoặc mất phiên
 * mobile; (3) mỗi sự cố còn NEW ở bước 1 mà không còn lời mời chờ -> BROADCAST, mỗi sự cố một transaction. Lỗi một bước /
 * một sự cố chỉ ghi log, không chặn phần còn lại.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentDispatchJob implements SchedulingConfigurer {

    private final IncidentDispatchService incidentDispatchService;
    private final SecurityIncidentService securityIncidentService;
    private final IncidentDispatchQueryRepository incidentDispatchQueryRepository;
    private final SystemConfigService systemConfigService;

    @Value("${icss.scheduler.incident-dispatch-enabled:true}")
    private boolean enabled;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) {
            log.info("[INCIDENT-DISPATCH] job tắt (icss.scheduler.incident-dispatch-enabled=false)");
            return;
        }
        registrar.addTriggerTask(this::tick, this::nextExecution);
    }

    /** Lần chạy kế = lần chạy trước kết thúc + DISPATCH_CHECK_SECONDS (đọc lại mỗi lần). */
    Instant nextExecution(TriggerContext context) {
        Instant lastCompletion = context.lastCompletion();
        Instant base = lastCompletion != null ? lastCompletion : context.getClock().instant();
        return base.plusSeconds(checkIntervalSeconds());
    }

    int checkIntervalSeconds() {
        int seconds = systemConfigService.getInt(ConfigKey.DISPATCH_CHECK_SECONDS);
        if (seconds <= 0) {
            int fallback = Integer.parseInt(ConfigKey.DISPATCH_CHECK_SECONDS.getDefaultValue());
            log.warn("[INCIDENT-DISPATCH] {}={} không hợp lệ, dùng mặc định {}",
                    ConfigKey.DISPATCH_CHECK_SECONDS.getKey(), seconds, fallback);
            return fallback;
        }
        return seconds;
    }

    void tick() {
        try {
            runOnce(OffsetDateTime.now());
        } catch (Exception ex) {
            log.error("[INCIDENT-DISPATCH] lần chạy lỗi: {}", ex.getMessage(), ex);
        }
    }

    /** Một lần chạy. Trả về số sự cố đã chuyển sang BROADCAST. */
    public int runOnce(OffsetDateTime now) {
        try {
            int expired = incidentDispatchService.expireDueOffers(now);
            if (expired > 0) {
                log.info("[INCIDENT-DISPATCH] {} lời mời hết hạn", expired);
            }
        } catch (Exception ex) {
            log.error("[INCIDENT-DISPATCH] đóng lời mời hết hạn lỗi: {}", ex.getMessage(), ex);
        }
        try {
            int cancelled = incidentDispatchService.cancelStaleOffers(now);
            if (cancelled > 0) {
                log.info("[INCIDENT-DISPATCH] huỷ {} lời mời (sự cố đã đóng / hết ca / mất phiên mobile)", cancelled);
            }
        } catch (Exception ex) {
            log.error("[INCIDENT-DISPATCH] huỷ lời mời lỗi: {}", ex.getMessage(), ex);
        }

        int promoted = 0;
        for (UUID incidentId : incidentDispatchQueryRepository.findStep1NewIncidentIds()) {
            try {
                if (securityIncidentService.evaluateDispatch(incidentId, now)) {
                    promoted++;
                }
            } catch (Exception ex) {
                log.error("[INCIDENT-DISPATCH] incident={} chuyển BROADCAST lỗi: {}", incidentId, ex.getMessage(), ex);
            }
        }
        return promoted;
    }
}
