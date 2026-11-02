package com.fa26se040.icss.service;

import com.fa26se040.icss.config.WebSocketSessionRegistry;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.DutyPostType;
import com.fa26se040.icss.entity.IncidentDispatchOffer;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.DispatchOfferCancelReason;
import com.fa26se040.icss.enums.DispatchOfferResponse;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.repository.DutyPostTypeRepository;
import com.fa26se040.icss.repository.IncidentDispatchOfferRepository;
import com.fa26se040.icss.repository.IncidentDispatchPolicyRepository;
import com.fa26se040.icss.repository.IncidentDispatchQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phân phối sự cố theo đợt (V93, K3–K6, BR-DSP-01..10).
 *
 * <p>Service này không phụ thuộc {@link SecurityIncidentService} (tránh vòng phụ thuộc): việc phát broadcast cũ vẫn
 * nằm ở SecurityIncidentService. Ở đây chỉ có: đọc chính sách, chọn và xếp hạng ứng viên, tạo / đóng lời mời, gửi các
 * tin MỚI (lời mời, huỷ lời mời, thông báo CRITICAL) sau commit.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentDispatchService {

    public static final String TYPE_OFFER = "INCIDENT_OFFER";
    public static final String TYPE_OFFER_CANCELLED = "INCIDENT_OFFER_CANCELLED";
    /** Lý do gửi kèm INCIDENT_OFFER_CANCELLED khi lời mời hết hạn (không phải giá trị cancel_reason trong DB). */
    public static final String REASON_EXPIRED = "EXPIRED";
    public static final String CRITICAL_NOTICE_TOPIC = "/topic/incidents/critical-notices";

    private final SystemConfigService systemConfigService;
    private final IncidentDispatchPolicyRepository policyRepository;
    private final IncidentDispatchOfferRepository offerRepository;
    private final IncidentDispatchQueryRepository dispatchQueryRepository;
    private final DutyPostTypeRepository dutyPostTypeRepository;
    private final IncidentRecipientResolver incidentRecipientResolver;
    private final WebSocketSessionRegistry sessionRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    /** Kênh lời mời riêng của một bảo vệ (W-a). Interceptor đã chỉ cho chính bảo vệ đó subscribe /topic/guards/{id}/**. */
    public static String offerTopic(UUID guardId) {
        return "/topic/guards/" + guardId + "/offers";
    }

    /** BR-DSP-01: DISPATCH_ENABLED. Đọc lỗi -> coi là tắt (hành vi cũ). */
    public boolean isEnabled() {
        try {
            return systemConfigService.getBoolean(ConfigKey.DISPATCH_ENABLED);
        } catch (Exception e) {
            log.error("[DISPATCH] không đọc được {}: {}", ConfigKey.DISPATCH_ENABLED.getKey(), e.getMessage(), e);
            return false;
        }
    }

    /** Chính sách của mức sự cố; severity null -> INCIDENT_SEVERITY_DEFAULT (BR-SEV-03). */
    public Optional<IncidentDispatchPolicy> policyFor(IncidentSeverity severity) {
        IncidentSeverity effective = severity != null ? severity : defaultSeverity();
        if (effective == null) {
            return Optional.empty();
        }
        return policyRepository.findById(effective);
    }

    private IncidentSeverity defaultSeverity() {
        String raw = systemConfigService.getString(ConfigKey.INCIDENT_SEVERITY_DEFAULT);
        try {
            return raw == null ? null : IncidentSeverity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("[DISPATCH] {}={} không hợp lệ", ConfigKey.INCIDENT_SEVERITY_DEFAULT.getKey(), raw);
            return null;
        }
    }

    /** BR-GB-07: toà của khu vực xảy ra sự cố = toà của tầng (FK). Khu không gắn tầng -> null (không có bước 1). */
    public static UUID incidentBuildingId(Area area) {
        if (area == null || area.getFloorEntity() == null || area.getFloorEntity().getBuilding() == null) {
            return null;
        }
        return area.getFloorEntity().getBuilding().getId();
    }

    /**
     * BR-DSP-02..04: chọn tối đa {@code step1_size} bảo vệ cho bước 1, đã xếp theo load score. Rỗng khi step1_size = 0,
     * khu không có toà, hoặc không có ứng viên (E1).
     */
    public List<User> selectStep1Guards(SecurityIncident incident, IncidentDispatchPolicy policy, OffsetDateTime now) {
        int size = policy.getStep1Size() == null ? 0 : policy.getStep1Size();
        if (size <= 0) {
            return List.of();
        }
        UUID buildingId = incidentBuildingId(incident.getArea());
        if (buildingId == null) {
            log.info("[DISPATCH] incident={}: khu vực không gắn toà, bỏ qua bước 1", incident.getId());
            return List.of();
        }
        Set<UUID> excluded = new HashSet<>(dispatchQueryRepository.findGuardIdsHoldingClaimed());
        excluded.addAll(offerRepository.findGuardIdsWithPendingOffer());
        Set<String> dispatchablePosts = dutyPostTypeRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .filter(p -> Boolean.TRUE.equals(p.getDispatchable()))
                .map(DutyPostType::getCode)
                .collect(Collectors.toSet());

        List<User> candidates = incidentRecipientResolver.findDispatchCandidates(
                now, buildingId, incident.getSubjectUserCode(), excluded, sessionRegistry::hasMobileSession,
                dispatchablePosts);
        List<User> ranked = rankByLoadScore(candidates, now);
        return ranked.size() <= size ? ranked : new ArrayList<>(ranked.subList(0, size));
    }

    /**
     * BR-DSP-03: số sự cố đã nhận trong DISPATCH_LOAD_WINDOW_MINUTES (tăng dần), rồi lần nhận gần nhất (chưa nhận lần nào
     * đứng trước, sau đó tăng dần), rồi user ID. Điểm thấp được mời trước.
     */
    List<User> rankByLoadScore(List<User> candidates, OffsetDateTime now) {
        if (candidates.size() <= 1) {
            return new ArrayList<>(candidates);
        }
        List<UUID> ids = candidates.stream().map(User::getId).toList();
        int windowMinutes = systemConfigService.getInt(ConfigKey.DISPATCH_LOAD_WINDOW_MINUTES);
        if (windowMinutes <= 0) {
            windowMinutes = Integer.parseInt(ConfigKey.DISPATCH_LOAD_WINDOW_MINUTES.getDefaultValue());
        }
        Map<UUID, Long> claims = new HashMap<>();
        for (Object[] row : dispatchQueryRepository.countClaimsSince(ids, now.minusMinutes(windowMinutes))) {
            claims.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<UUID, OffsetDateTime> lastClaim = new HashMap<>();
        for (Object[] row : dispatchQueryRepository.findLastClaimTimes(ids)) {
            lastClaim.put((UUID) row[0], (OffsetDateTime) row[1]);
        }
        Comparator<User> byLoad = Comparator
                .comparingLong((User u) -> claims.getOrDefault(u.getId(), 0L))
                .thenComparing(u -> lastClaim.get(u.getId()), Comparator.nullsFirst(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(u -> u.getId().toString());
        List<User> ranked = new ArrayList<>(candidates);
        ranked.sort(byLoad);
        return ranked;
    }

    /** BR-DSP-04: tạo lời mời, hết hạn sau step1_timeout_seconds. */
    public List<IncidentDispatchOffer> createOffers(SecurityIncident incident, List<User> guards,
                                                    IncidentDispatchPolicy policy, OffsetDateTime now) {
        OffsetDateTime expiresAt = now.plusSeconds(policy.getStep1TimeoutSeconds());
        List<IncidentDispatchOffer> offers = new ArrayList<>();
        for (User guard : guards) {
            offers.add(IncidentDispatchOffer.builder()
                    .incident(incident)
                    .guard(guard)
                    .offeredAt(now)
                    .expiresAt(expiresAt)
                    .build());
        }
        return offerRepository.saveAll(offers);
    }

    /** Gửi INCIDENT_OFFER cho từng người được mời, sau khi transaction commit (quy định 3 của PR-2). */
    public void sendOffersAfterCommit(List<IncidentDispatchOffer> offers, IncidentDetailResponse incident) {
        List<Map.Entry<String, Map<String, Object>>> messages = new ArrayList<>();
        for (IncidentDispatchOffer offer : offers) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", TYPE_OFFER);
            payload.put("offerId", offer.getId());
            payload.put("incidentId", incident.getId());
            payload.put("offeredAt", offer.getOfferedAt());
            payload.put("expiresAt", offer.getExpiresAt());
            payload.put("incident", incident);
            messages.add(Map.entry(offerTopic(offer.getGuard().getId()), payload));
        }
        runAfterCommit(() -> messages.forEach(m -> send(m.getKey(), m.getValue())));
    }

    /** BR-DSP-09: notify_fm_immediately của mức sự cố -> thông báo riêng cho FM qua topic critical-notices, sau commit. */
    public void sendCriticalNoticeIfConfigured(SecurityIncident incident) {
        Optional<IncidentDispatchPolicy> policy = policyFor(incident.getSeverity());
        if (policy.isEmpty() || !Boolean.TRUE.equals(policy.get().getNotifyFmImmediately())) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", incident.getId());
        payload.put("severity", policy.get().getSeverity() != null ? policy.get().getSeverity().name() : null);
        payload.put("eventType", incident.getEventType());
        payload.put("areaName", incident.getArea() != null ? incident.getArea().getName() : null);
        payload.put("building", incident.getBuilding());
        payload.put("createdAt", incident.getCreatedAt());
        runAfterCommit(() -> send(CRITICAL_NOTICE_TOPIC, payload));
    }

    /** BR-DSP-06: bảo vệ có lời mời còn chờ, chưa hết hạn cho sự cố này. */
    public boolean hasActiveOffer(UUID incidentId, UUID guardId, OffsetDateTime now) {
        return offerRepository.existsActivePendingOffer(incidentId, guardId, now);
    }

    public boolean hasPendingOffers(UUID incidentId) {
        return offerRepository.countPendingByIncidentId(incidentId) > 0;
    }

    /**
     * K5 / BR-DSP-07: sau khi claim thành công: lời mời của người nhận -> ACCEPTED, lời mời khác còn chờ -> CANCELLED
     * (CLAIMED_BY_OTHER) và báo cho người đó sau commit. Không có lời mời nào thì không làm gì.
     */
    public void onClaimed(UUID incidentId, UUID claimerId, OffsetDateTime now) {
        offerRepository.acceptIfPending(incidentId, claimerId, now);
        for (IncidentDispatchOffer offer : offerRepository.findPendingByIncidentId(incidentId)) {
            cancelAndNotify(offer, DispatchOfferCancelReason.CLAIMED_BY_OTHER, incidentId, now);
        }
    }

    /** K5: bảo vệ từ chối lời mời còn chờ, chưa hết hạn của chính mình. true = đã từ chối. */
    public boolean decline(UUID incidentId, UUID guardId, OffsetDateTime now) {
        return offerRepository.declineIfPending(incidentId, guardId, now) == 1;
    }

    /** K4 bước 1: lời mời quá hạn -> EXPIRED, báo người được mời. Trả về số lời mời đã đóng. */
    @Transactional
    public int expireDueOffers(OffsetDateTime now) {
        int expired = 0;
        for (IncidentDispatchOffer offer : offerRepository.findDuePending(now)) {
            if (offerRepository.respondIfPending(offer.getId(), DispatchOfferResponse.EXPIRED, null, now) == 1) {
                expired++;
                notifyCancelled(offer.getGuard().getId(), offer.getId(), offer.getIncident().getId(), REASON_EXPIRED);
            }
        }
        return expired;
    }

    /**
     * K4 bước 2–3 (BR-DSP-08): huỷ lời mời còn chờ khi sự cố không còn NEW (INCIDENT_CLOSED), bảo vệ hết ca
     * (SHIFT_ENDED) hoặc không còn phiên mobile (DISCONNECTED). Trả về số lời mời đã huỷ.
     */
    @Transactional
    public int cancelStaleOffers(OffsetDateTime now) {
        List<IncidentDispatchOffer> pending = offerRepository.findAllPending();
        if (pending.isEmpty()) {
            return 0;
        }
        Set<UUID> onDuty = incidentRecipientResolver.findOnDutyGuards(now).stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        int cancelled = 0;
        for (IncidentDispatchOffer offer : pending) {
            UUID guardId = offer.getGuard().getId();
            DispatchOfferCancelReason reason = null;
            if (offer.getIncident().getStatus() != IncidentStatus.NEW) {
                reason = DispatchOfferCancelReason.INCIDENT_CLOSED;
            } else if (!onDuty.contains(guardId)) {
                reason = DispatchOfferCancelReason.SHIFT_ENDED;
            } else if (!sessionRegistry.hasMobileSession(guardId)) {
                reason = DispatchOfferCancelReason.DISCONNECTED;
            }
            if (reason != null && cancelAndNotify(offer, reason, offer.getIncident().getId(), now)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    private boolean cancelAndNotify(IncidentDispatchOffer offer, DispatchOfferCancelReason reason, UUID incidentId,
                                    OffsetDateTime now) {
        if (offerRepository.respondIfPending(offer.getId(), DispatchOfferResponse.CANCELLED, reason, now) != 1) {
            return false;
        }
        notifyCancelled(offer.getGuard().getId(), offer.getId(), incidentId, reason.name());
        return true;
    }

    private void notifyCancelled(UUID guardId, UUID offerId, UUID incidentId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE_OFFER_CANCELLED);
        payload.put("offerId", offerId);
        payload.put("incidentId", incidentId);
        payload.put("reason", reason);
        String topic = offerTopic(guardId);
        runAfterCommit(() -> send(topic, payload));
    }

    private void send(String topic, Object payload) {
        try {
            messagingTemplate.convertAndSend(topic, payload);
        } catch (Exception e) {
            log.error("[DISPATCH] gửi {} lỗi: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * Chạy sau khi transaction hiện tại commit; không có transaction đang đồng bộ (vd. unit test) -> chạy ngay.
     * Chỉ dùng cho các lần gửi MỚI của PR-2; 6 chỗ gửi cũ vẫn gửi trong transaction như trước.
     */
    public static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
