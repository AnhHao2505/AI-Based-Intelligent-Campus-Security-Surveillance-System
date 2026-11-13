package com.fa26se040.icss.dto.systemconfig;

/**
 * P-MAP-2 (V123, MM-05): tham số ước tính thời gian di chuyển trên bản đồ — WALKING_SPEED_MPS (m/giây) và
 * FLOOR_CHANGE_SECONDS (giây / lần đổi tầng). Trả qua GET /api/system-configurations/route-settings cho mọi tài khoản
 * đã đăng nhập (web và app bảo vệ dùng để tính thời gian dự kiến của lộ trình).
 */
public record RouteSettingsResponse(double walkingSpeedMps, int floorChangeSeconds) {
}
