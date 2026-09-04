package com.fa26se040.icss.enums;

import lombok.Getter;

@Getter
public enum AreaLevel {
    PUBLIC("PUBLIC", "Công cộng", 1, false),
    SEMI_PRIVATE("SEMI_PRIVATE", "Bán hạn chế", 2, true),
    PRIVATE("PRIVATE", "Hạn chế tuyệt đối", 3, true);
}
