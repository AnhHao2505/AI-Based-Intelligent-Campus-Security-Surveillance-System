package com.fa26se040.icss.enums;

/**
 * Step 5b (BR-TC-15): ai huỷ đơn truy cập. NULL ở đơn CANCELLED trước V56 = không rõ nguồn.
 */
public enum CancelSource {
    USER,
    SYSTEM
}
