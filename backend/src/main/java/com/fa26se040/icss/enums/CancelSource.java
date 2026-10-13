package com.fa26se040.icss.enums;

/**
 * Step 5b (BR-TC-15): ai huỷ đơn truy cập. NULL ở đơn CANCELLED trước V56 = không rõ nguồn.
 * STAFF (V73, BR-RQ-47): FM huỷ đơn đã duyệt chưa bắt đầu — tách khỏi USER (người tạo tự huỷ đơn PENDING).
 */
public enum CancelSource {
    USER,
    SYSTEM,
    STAFF
}
