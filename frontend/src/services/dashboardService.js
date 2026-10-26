import { apiGet } from '../api/apiClient';

/** Khoảng thống kê backend chấp nhận (enum SecurityStatsRange); ngày tính theo giờ Việt Nam, luôn gồm hôm nay. */
export const SECURITY_STATS_RANGES = ['TODAY', 'LAST_7_DAYS', 'LAST_30_DAYS'];
export const DEFAULT_SECURITY_STATS_RANGE = 'LAST_7_DAYS';

/**
 * Thống kê an ninh cho Dashboard (FACILITY_MANAGER, ADMIN), chỉ đọc: số sự cố theo trạng thái, tỉ lệ báo nhầm,
 * 5 khu nhiều sự cố nhất, lượt ra vào theo ngày và kết quả.
 * @param {'TODAY'|'LAST_7_DAYS'|'LAST_30_DAYS'} [range='LAST_7_DAYS']
 */
export async function getSecurityStats(range = DEFAULT_SECURITY_STATS_RANGE) {
  return apiGet(`/api/dashboard/security-stats?range=${encodeURIComponent(range)}`);
}
