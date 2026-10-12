package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guest.GuestAccessDecision;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.GuestBiometricStatus;
import com.fa26se040.icss.enums.GuestEntryDenyReason;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.repository.GuestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * G-C: quyết định khách vào khu vực (BR-GV-20). Service riêng, KHÔNG đổi AccessDecisionService#checkEntry của user.
 * Chế độ sự kiện không áp cho khách (BR-GV-21): không xét OPEN_EVENT. Chưa nối vào luồng sự kiện AI.
 */
@Service
@RequiredArgsConstructor
public class GuestAccessDecisionService {

    private final GuestRepository guestRepository;
    private final GuestRules rules;

    /**
     * Cho vào ⇔ lượt APPROVED ∧ start ≤ at < end ∧ khu vực thuộc lượt ∧ khu vực đang hoạt động ∧ loại khu vực nhận khách tại at
     * ∧ host vẫn thoả BR-GV-01 + 06 tại at ∧ sinh trắc PHOTO_READY (BR-GV-38: đã đăng ký khuôn mặt tại quầy).
     * Sinh trắc: DELETED -> BIOMETRIC_DELETED; NO_PHOTO / PHOTO_ONLY / null -> BIOMETRIC_NOT_READY. Thứ tự kiểm = thứ tự mã lý do.
     */
    @Transactional(readOnly = true)
    public GuestAccessDecision checkGuestEntry(UUID guestId, UUID areaId, OffsetDateTime at) {
        if (guestId == null || areaId == null || at == null) {
            throw new IllegalArgumentException("guestId, areaId và at không được để trống");
        }
        Guest guest = guestRepository.findById(guestId).orElse(null);
        if (guest == null) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.VISIT_NOT_ACTIVE, null);
        }
        GuestVisit visit = guest.getVisit();
        UUID visitId = visit.getId();
        if (visit.getStatus() != GuestVisitStatus.APPROVED) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.VISIT_NOT_ACTIVE, visitId);
        }
        if (at.isBefore(visit.getStartTime()) || !at.isBefore(visit.getEndTime())) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.OUTSIDE_WINDOW, visitId);
        }
        Area area = visit.getAreas().stream().filter(a -> a.getId().equals(areaId)).findFirst().orElse(null);
        if (area == null) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.AREA_NOT_IN_VISIT, visitId);
        }
        if (!rules.areaActive(area)) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.AREA_INACTIVE, visitId);
        }
        if (!rules.areaTypeAllowed(area)) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.AREA_TYPE_NOT_ALLOWED, visitId);
        }
        // BR-GV-01 + 06 tại at: host còn đủ điều kiện và còn quyền vào khu vực cho phần còn lại của lượt [at, end)
        User host = visit.getHost();
        if (!rules.hostEligible(host) || !rules.hostCoversArea(host, area, at, visit.getEndTime())) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.HOST_LOST_ACCESS, visitId);
        }
        if (guest.getBiometricStatus() == GuestBiometricStatus.DELETED) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.BIOMETRIC_DELETED, visitId);
        }
        // BR-GV-38: chỉ khách đã có ảnh + embedding (PHOTO_READY) mới nhận diện được
        if (guest.getBiometricStatus() != GuestBiometricStatus.PHOTO_READY) {
            return GuestAccessDecision.deny(GuestEntryDenyReason.BIOMETRIC_NOT_READY, visitId);
        }
        return GuestAccessDecision.allow(visitId);
    }
}
