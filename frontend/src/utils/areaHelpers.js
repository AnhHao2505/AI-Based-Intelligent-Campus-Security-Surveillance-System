/**
 * Area Level Visual Mapping & Helpers
 */
export const AREA_LEVEL_CONFIG = {
  1: {
    code: 'PUBLIC',
    name: 'Public Area',
    badgeLabel: 'LEVEL 1 — PUBLIC',
    badgeClass: 'level-badge--public',
    cardClass: 'zone-card--public',
    color: '#10b981',
    bgColor: 'rgba(16, 185, 129, 0.08)',
    borderColor: 'rgba(16, 185, 129, 0.35)',
    icon: 'globe'
  },
  2: {
    code: 'SEMI_PRIVATE',
    name: 'Semi-Private Area',
    badgeLabel: 'LEVEL 2 — SEMI PRIVATE',
    badgeClass: 'level-badge--semi-private',
    cardClass: 'zone-card--semi-private',
    color: '#f59e0b',
    bgColor: 'rgba(245, 158, 11, 0.08)',
    borderColor: 'rgba(245, 158, 11, 0.35)',
    icon: 'shield'
  },
  3: {
    code: 'PRIVATE',
    name: 'Private Area',
    badgeLabel: 'LEVEL 3 — PRIVATE',
    badgeClass: 'level-badge--private',
    cardClass: 'zone-card--private',
    color: '#ef4444',
    bgColor: 'rgba(239, 68, 68, 0.08)',
    borderColor: 'rgba(239, 68, 68, 0.35)',
    icon: 'lock'
  }
};

/**
 * Lấy config hiển thị cho Level
 */
export function getLevelConfig(level) {
  const numLevel = typeof level === 'object' ? level?.level : Number(level);
  return AREA_LEVEL_CONFIG[numLevel] || {
    code: 'UNKNOWN',
    name: 'Unknown Level',
    badgeLabel: `LEVEL ${numLevel || '?'}`,
    badgeClass: 'level-badge--unknown',
    cardClass: '',
    color: '#6366f1',
    bgColor: 'rgba(99, 102, 241, 0.08)',
    borderColor: 'rgba(99, 102, 241, 0.3)',
    icon: 'shield'
  };
}

/**
 * Error Code Mapping sang thông báo thân thiện
 */
export const ERROR_MESSAGES = {
  ERR_AREA_001: 'Mã khu vực đã tồn tại trên hệ thống.',
  ERR_AREA_002: 'Không tìm thấy khu vực hoặc khu vực đã bị vô hiệu hóa.',
  ERR_AREA_003: 'Cấp độ an ninh không hợp lệ hoặc đã bị vô hiệu hóa.',
  ERR_AREA_004: 'Mã khu vực không được phép thay đổi.',
  ERR_AREA_005: 'Khi hạ cấp độ an ninh, lý do là bắt buộc (10–255 ký tự).',
  ERR_AREA_006: 'Tọa độ bản đồ Map X và Map Y phải cùng có hoặc cùng để trống.',
  ERR_AREA_007: 'Không thể vô hiệu hóa khu vực do còn phụ thuộc (Camera / Thiết bị).',
  ERR_TEMP_USAGE_001: 'Thời gian kết thúc phải sau thời gian bắt đầu.',
  ERR_TEMP_USAGE_002: 'Thời gian kết thúc phải sau thời điểm hiện tại.',
  ERR_TEMP_USAGE_003: 'Thời gian sử dụng tạm thời bị trùng với phiên khác trong khu vực này.',
  ERR_TEMP_USAGE_004: 'Không tìm thấy phiên sử dụng tạm thời.',
  ERR_TEMP_USAGE_005: 'Thời gian kết thúc mới phải sau thời gian kết thúc hiện tại.',
  ERR_TEMP_USAGE_006: 'Phiên sử dụng tạm thời đã kết thúc, không thể gia hạn.',
  ERR_TEMP_USAGE_007: 'Lý do gia hạn không hợp lệ, yêu cầu từ 10 đến 255 ký tự.'
};

export function getErrorMessage(error) {
  if (!error) return 'Đã có lỗi xảy ra. Vui lòng thử lại.';
  if (error.code && ERROR_MESSAGES[error.code]) {
    return ERROR_MESSAGES[error.code];
  }
  return error.message || 'Đã có lỗi xảy ra. Vui lòng thử lại.';
}

/**
 * Format date sang dạng datetime-local input có ISO offset
 */
export function formatToOffsetDateTime(datetimeLocalValue) {
  if (!datetimeLocalValue) return null;
  const date = new Date(datetimeLocalValue);
  const tzo = -date.getTimezoneOffset();
  const dif = tzo >= 0 ? '+' : '-';
  const pad = (num) => (num < 10 ? '0' : '') + num;
  
  return (
    date.getFullYear() +
    '-' + pad(date.getMonth() + 1) +
    '-' + pad(date.getDate()) +
    'T' + pad(date.getHours()) +
    ':' + pad(date.getMinutes()) +
    ':' + pad(date.getSeconds()) +
    dif + pad(Math.floor(Math.abs(tzo) / 60)) +
    ':' + pad(Math.abs(tzo) % 60)
  );
}

/**
 * Format hiển thị ngày giờ thân thiện
 */
export function formatDisplayDateTime(isoString) {
  if (!isoString) return '—';
  try {
    const d = new Date(isoString);
    if (isNaN(d.getTime())) return isoString;
    return d.toLocaleString('vi-VN', {
      hour: '2-digit',
      minute: '2-digit',
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    });
  } catch {
    return isoString;
  }
}
