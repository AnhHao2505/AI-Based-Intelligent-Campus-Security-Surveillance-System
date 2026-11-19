package com.fa26se040.icss.dto.systemconfig;

/**
 * Khung ban đêm đang hiệu lực (AI_AFTER_HOUR_START / AI_AFTER_HOUR_END, dạng HH:mm, giờ Việt Nam; hai giá trị luôn
 * khác nhau: BE từ chối lưu START = END, #262). Trả qua GET /api/system-configurations/after-hours-window cho ADMIN và FACILITY_MANAGER, để
 * form lịch sự kiện (EV-N7) và form gán nhân sự chỉ định (giờ đóng cửa) hiện gợi ý mà không cần đọc toàn bộ cấu hình.
 */
public record AfterHoursWindowResponse(String start, String end) {
}
