package com.fa26se040.icss.enums;

/**
 * Giai đoạn phân phối sự cố (V93, K6).
 * <ul>
 *   <li>{@link #STEP1}: đang mời một nhóm bảo vệ (BR-DSP-04); chỉ người có lời mời còn chờ mới nhận được (BR-DSP-06)</li>
 *   <li>{@link #BROADCAST}: đã phát cảnh báo cho mọi bảo vệ đang trực như trước PR-2 (BR-DSP-01, BR-DSP-05)</li>
 * </ul>
 * Sự cố cũ và mọi sự cố tạo khi DISPATCH_ENABLED = false là BROADCAST.
 */
public enum IncidentDispatchStage {
    STEP1,
    BROADCAST
}
