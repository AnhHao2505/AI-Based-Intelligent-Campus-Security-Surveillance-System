package com.fa26se040.icss.dto.guest;

/**
 * Một khách trong lượt (BR-GV-02). Chỉ họ tên + đơn vị; trường khác bị từ chối.
 * consentConfirmed: host xác nhận khách đã đồng ý chụp ảnh (BR-GV-15), bắt buộc true khi tạo lượt.
 */
public record GuestInput(String fullName, String organization, Boolean consentConfirmed) {
}
