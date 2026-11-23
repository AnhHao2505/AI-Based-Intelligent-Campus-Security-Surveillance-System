package com.fa26se040.icss.dto.guest;

/**
 * B-04: điều kiện mời khách của người đang đăng nhập (BR-GV-01) để FE ẩn form và giải thích lý do.
 * canHost = GuestRules.hostEligible (role, đang hoạt động, cấp ≥ GUEST_HOST_MIN_LEVEL).
 * consentNoticeVersion (GUEST_CONSENT_NOTICE_VERSION) và photoMaxKb (GUEST_PHOTO_MAX_KB, V141) để form gắn ảnh khách
 * hiện đúng phiên bản thông báo đồng ý và giới hạn dung lượng ảnh. Người dùng thường không đọc được
 * /api/system-configurations nên form lấy giới hạn ảnh khách từ đây.
 */
public record GuestHostEligibilityResponse(
    boolean canHost,
    Integer myLevel,
    int minLevel,
    String consentNoticeVersion,
    int photoMaxKb
) {}
