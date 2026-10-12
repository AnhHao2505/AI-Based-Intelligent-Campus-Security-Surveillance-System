package com.fa26se040.icss.dto.guest;

import com.fa26se040.icss.enums.AreaLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * BR-GV-04 + BR-GV-06: khu vực chọn được trong form lượt khách.
 * hostCanInvite / reasonCode chỉ điền khi gọi kèm đủ startTime và endTime (null thì không trả ra — giữ tương thích
 * với client cũ chỉ đọc id, name, areaLevel, building, floor).
 * reasonCode là mã lỗi mà bước tạo lượt sẽ trả cho khu vực này: ERR_GUEST_002 (người gọi không đủ điều kiện mời khách)
 * hoặc ERR_GUEST_014 (không vào được khu vực trong trọn khung giờ).
 */
public record GuestSelectableAreaResponse(
    UUID id,
    String name,
    AreaLevel areaLevel,
    String building,
    String floor,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Boolean hostCanInvite,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String reasonCode
) {}
