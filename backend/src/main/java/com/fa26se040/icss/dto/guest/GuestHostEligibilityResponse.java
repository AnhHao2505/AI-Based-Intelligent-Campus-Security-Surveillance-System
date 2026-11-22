package com.fa26se040.icss.dto.guest;

/**
 * B-04: điều kiện mời khách của người đang đăng nhập (BR-GV-01) để FE ẩn form và giải thích lý do.
 * canHost = GuestRules.hostEligible (role, đang hoạt động, cấp ≥ GUEST_HOST_MIN_LEVEL).
 * consentNoticeVersion (GUEST_CONSENT_NOTICE_VERSION) và photoMaxKb (icss.guest.photo-max-kb) để form gắn ảnh khách
 * hiện đúng phiên bản thông báo đồng ý và giới hạn dung lượng ảnh.
 */
public record GuestHostEligibilityResponse(
    boolean canHost,
    Integer myLevel,
    int minLevel,
    String consentNoticeVersion,
    int photoMaxKb
) {}
