package com.fa26se040.icss.scheduler;

import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.EscalationCloseReason;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.InAppNotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job leo thang sự cố lên Quản lý cơ sở vật chất (BR-IE-04..10).
 *
 * <p>Chu kỳ đọc INCIDENT_ESCALATION_CHECK_SECONDS mỗi lần tính lần chạy kế (Trigger động, BR-IE-09) nên sửa cấu hình
 * có hiệu lực từ lần chạy sau, không cần restart. Cờ vận hành {@code icss.scheduler.incident-escalation-enabled}
 * chỉ bật / tắt job (profile test tắt, test gọi thẳng {@link #runOnce} / {@link #processEscalation}).</p>
 *
 * <p>Mỗi bản ghi xử lý trong transaction riêng; lỗi một sự cố không chặn sự cố khác.
 * Quyền báo FM giành bằng conditional UPDATE {@code WHERE closed_at IS NULL} (BR-IE-07), không pre-check rồi ghi.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEscalationJob implements SchedulingConfigurer {

    public static final String REF_TYPE_SECURITY_INCIDENT = "SECURITY_INCIDENT";
    public static final String FM_TOPIC = "/topic/escalations/facility-managers";

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    /** Kết quả xử lý một bản ghi (phục vụ test và log). */
    public enum Outcome {
        /** Không có bản ghi, hoặc đã đóng trước đó (kể cả do lần chạy khác vừa đóng). */
        SKIPPED,
        /** Còn NEW nhưng chưa tới hạn. */
        NOT_DUE,
        /** Sự cố rời NEW -> đóng, không báo FM (BR-IE-06). */
        CLOSED,
        /** Đã báo FM (BR-IE-04 / BR-IE-05). */
        ESCALATED,
        /** Lỗi, transaction của bản ghi này rollback; lần chạy sau thử lại. */
        FAILED
    }

    private final IncidentEscalationRepository escalationRepository;
    private final UserRepository userRepository;
    private final SystemConfigService systemConfigService;
    private final InAppNotificationService inAppNotificationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final PlatformTransactionManager transactionManager;

    @Value("${icss.scheduler.incident-escalation-enabled:true}")
    private boolean enabled;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) {
            log.info("[INCIDENT-ESCALATION] job tắt (icss.scheduler.incident-escalation-enabled=false)");
            return;
        }
        registrar.addTriggerTask(this::tick, this::nextExecution);
    }

    /** BR-IE-09: lần chạy kế = lần chạy trước kết thúc + INCIDENT_ESCALATION_CHECK_SECONDS (đọc lại mỗi lần). */
    Instant nextExecution(TriggerContext context) {
        Instant lastCompletion = context.lastCompletion();
        Instant base = lastCompletion != null ? lastCompletion : context.getClock().instant();
        return base.plusSeconds(checkIntervalSeconds());
    }

    int checkIntervalSeconds() {
        int seconds = systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATION_CHECK_SECONDS);
        if (seconds <= 0) {
            // Giá trị sửa thẳng trong DB lọt khỏi min_value -> không để job chạy dồn liên tục
            int fallback = Integer.parseInt(ConfigKey.INCIDENT_ESCALATION_CHECK_SECONDS.getDefaultValue());
            log.warn("[INCIDENT-ESCALATION] {}={} không hợp lệ, dùng mặc định {}",
                    ConfigKey.INCIDENT_ESCALATION_CHECK_SECONDS.getKey(), seconds, fallback);
            return fallback;
        }
        return seconds;
    }

    void tick() {
        try {
            runOnce(OffsetDateTime.now());
        } catch (Exception ex) {
            log.error("[INCIDENT-ESCALATION] lần chạy lỗi: {}", ex.getMessage(), ex);
        }
    }

    /** Một lần kiểm tra: mọi bản ghi còn mở. Trả về số sự cố đã báo FM. */
    public int runOnce(OffsetDateTime now) {
        int escalated = 0;
        for (UUID escalationId : escalationRepository.findOpenIds()) {
            if (processEscalation(escalationId, now) == Outcome.ESCALATED) {
                escalated++;
            }
        }
        return escalated;
    }

    /** Xử lý một bản ghi trong transaction riêng (REQUIRES_NEW). */
    public Outcome processEscalation(UUID escalationId, OffsetDateTime now) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            Outcome outcome = tx.execute(status -> doProcess(escalationId, now));
            return outcome != null ? outcome : Outcome.SKIPPED;
        } catch (Exception ex) {
            log.error("[INCIDENT-ESCALATION] bản ghi {} lỗi: {}", escalationId, ex.getMessage(), ex);
            return Outcome.FAILED;
        }
    }

    private Outcome doProcess(UUID escalationId, OffsetDateTime now) {
        IncidentEscalation escalation = escalationRepository.findByIdWithIncident(escalationId).orElse(null);
        if (escalation == null || escalation.getClosedAt() != null) {
            return Outcome.SKIPPED;
        }
        SecurityIncident incident = escalation.getIncident();

        // BR-IE-06: sự cố đã rời NEW -> đóng với lý do tương ứng, không báo FM
        if (incident.getStatus() != IncidentStatus.NEW) {
            EscalationCloseReason reason = closeReasonOf(incident.getStatus());
            int rows = escalationRepository.closeIfOpen(escalation.getId(), reason, now);
            if (rows == 1) {
                log.info("[INCIDENT-ESCALATION] incident={} rời NEW ({}) -> đóng {}", incident.getId(), incident.getStatus(), reason);
                return Outcome.CLOSED;
            }
            return Outcome.SKIPPED;
        }

        // BR-IE-04 / BR-IE-05 / BR-IE-08: hạn tính lại từ lúc tạo sự cố theo FM_MINUTES hiện tại; 0 bảo vệ -> tới hạn ngay
        int fmMinutes = systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATE_FM_MINUTES);
        OffsetDateTime incidentCreatedAt = incident.getCreatedAt();
        boolean noGuardNotified = escalation.getGuardRecipientCount() == null || escalation.getGuardRecipientCount() == 0;
        boolean due = noGuardNotified || !now.isBefore(incidentCreatedAt.plusMinutes(fmMinutes));
        if (!due) {
            return Outcome.NOT_DUE;
        }

        // BR-IE-07: kết quả conditional UPDATE là nguồn chân lý duy nhất
        int rows = escalationRepository.markEscalatedIfOpen(escalation.getId(), now);
        if (rows != 1) {
            log.info("[INCIDENT-ESCALATION] incident={} đã được lần chạy khác xử lý, bỏ qua", incident.getId());
            return Outcome.SKIPPED;
        }

        // [RÀNG BUỘC NOTIFICATION / SIDE-EFFECTS] chỉ gửi ở nhánh UPDATE trả về 1 dòng.
        // Lỗi tạo thông báo in-app -> ném ra, transaction rollback, bản ghi còn mở để lần chạy sau thử lại.
        long minutesElapsed = Duration.between(incidentCreatedAt, now).toMinutes();
        int fmRecipientCount = notifyFacilityManagers(incident, incidentCreatedAt, minutesElapsed, noGuardNotified);
        escalationRepository.updateFmRecipientCount(escalation.getId(), fmRecipientCount);

        long escalationDelaySeconds = Duration.between(incidentCreatedAt, now).getSeconds();
        log.info("[INCIDENT-ESCALATION] incident={} báo {} FM, guardRecipientCount={}, escalationDelaySeconds={}",
                incident.getId(), fmRecipientCount, escalation.getGuardRecipientCount(), escalationDelaySeconds);
        return Outcome.ESCALATED;
    }

    /** BR-IE-10: chỉ FM đang hoạt động, chưa xoá; ADMIN không nhận. Trả về số FM nhận thông báo in-app. */
    private int notifyFacilityManagers(SecurityIncident incident, OffsetDateTime incidentCreatedAt,
                                       long minutesElapsed, boolean noGuardNotified) {
        String areaName = incident.getArea() != null ? incident.getArea().getName() : "khu vực";
        String building = incident.getBuilding();

        List<User> fms = userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER);
        int fmRecipientCount;
        if (fms.isEmpty()) {
            log.warn("[INCIDENT-ESCALATION] incident={}: không có Quản lý cơ sở vật chất nào đang hoạt động để báo", incident.getId());
            fmRecipientCount = 0;
        } else if (inAppNotificationService.existsByReferenceIdAndType(incident.getId(), NotificationType.INCIDENT_ESCALATED)) {
            // Chỉ xảy ra khi lần trước đã tạo thông báo nhưng transaction của bản ghi rollback
            log.warn("[INCIDENT-ESCALATION] incident={}: đã có thông báo INCIDENT_ESCALATED, không tạo lại", incident.getId());
            fmRecipientCount = fms.size();
        } else {
            String createdAtText = incidentCreatedAt.atZoneSameInstant(VN_ZONE).format(TIME_FORMATTER);
            String reason = noGuardNotified
                    ? "chưa có bảo vệ tiếp nhận vì không có bảo vệ nào đang trực khi sự cố xảy ra"
                    : "đã chờ " + minutesElapsed + " phút nhưng chưa có bảo vệ tiếp nhận";
            String title = "Sự cố an ninh chưa được tiếp nhận";
            String message = "Sự cố " + incident.getEventType() + " tại khu vực " + areaName + " (" + building + ") lúc "
                    + createdAtText + " " + reason + ". Vui lòng liên hệ trực tiếp bảo vệ qua bộ đàm hoặc điện thoại.";
            List<Notification> created = inAppNotificationService.createForUsers(
                    fms, NotificationType.INCIDENT_ESCALATED, title, message, incident.getId(), REF_TYPE_SECURITY_INCIDENT);
            fmRecipientCount = created.size();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", incident.getId());
        payload.put("areaName", areaName);
        payload.put("building", building);
        payload.put("eventType", incident.getEventType());
        payload.put("createdAt", incidentCreatedAt);
        payload.put("minutesElapsed", minutesElapsed);
        try {
            messagingTemplate.convertAndSend(FM_TOPIC, payload);
        } catch (Exception ex) {
            log.error("[INCIDENT-ESCALATION] incident={}: gửi real-time {} lỗi: {}", incident.getId(), FM_TOPIC, ex.getMessage(), ex);
        }
        return fmRecipientCount;
    }

    /** IncidentStatus -> close_reason: CLAIMED / RESOLVED_VERIFIED -> RESOLVED / RESOLVED_DISMISSED -> DISMISSED. */
    static EscalationCloseReason closeReasonOf(IncidentStatus status) {
        return switch (status) {
            case CLAIMED -> EscalationCloseReason.CLAIMED;
            case RESOLVED_VERIFIED -> EscalationCloseReason.RESOLVED;
            case RESOLVED_DISMISSED -> EscalationCloseReason.DISMISSED;
            case NEW -> throw new IllegalArgumentException("Sự cố còn NEW, không có lý do đóng");
        };
    }
}
