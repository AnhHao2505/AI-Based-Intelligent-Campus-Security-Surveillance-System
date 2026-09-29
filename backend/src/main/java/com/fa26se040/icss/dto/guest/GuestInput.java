package com.fa26se040.icss.dto.guest;

/** Một khách trong lượt (BR-GV-02). Chỉ họ tên + đơn vị; trường khác bị từ chối. */
public record GuestInput(String fullName, String organization) {
}
