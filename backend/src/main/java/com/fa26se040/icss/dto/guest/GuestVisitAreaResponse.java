package com.fa26se040.icss.dto.guest;

import com.fa26se040.icss.enums.AreaLevel;

import java.util.UUID;

public record GuestVisitAreaResponse(UUID id, String name, AreaLevel areaLevel) {
}
