/**
 * Area Level Visual Mapping & Helpers
 */
export const AREA_LEVEL_CONFIG = {
  PUBLIC: {
    code: 'PUBLIC',
    name: 'Công cộng',
    rank: 1,
    badgeLabel: 'PUBLIC',
    badgeClass: 'level-badge--public',
    cardClass: 'zone-card--public',
    color: '#10b981',
    bgColor: 'rgba(16, 185, 129, 0.08)',
    borderColor: 'rgba(16, 185, 129, 0.35)',
    icon: 'globe'
  },
  SEMI_PRIVATE: {
    code: 'SEMI_PRIVATE',
    name: 'Bán hạn chế',
    rank: 2,
    badgeLabel: 'SEMI PRIVATE',
    badgeClass: 'level-badge--semi-private',
    cardClass: 'zone-card--semi-private',
    color: '#f59e0b',
    bgColor: 'rgba(245, 158, 11, 0.08)',
    borderColor: 'rgba(245, 158, 11, 0.35)',
    icon: 'shield'
  },
  PRIVATE: {
    code: 'PRIVATE',
    name: 'Hạn chế tuyệt đối',
    rank: 3,
    badgeLabel: 'PRIVATE',
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
  let key = level;
  if (typeof level === 'object' && level !== null) {
    key = level.code || level.areaLevel || level.level;
  }
  if (key === 1 || key === '1') key = 'PUBLIC';
  if (key === 2 || key === '2') key = 'SEMI_PRIVATE';
  if (key === 3 || key === '3') key = 'PRIVATE';

  return AREA_LEVEL_CONFIG[key] || {
    code: key || 'UNKNOWN',
    name: 'Unknown Level',
    rank: 0,
    badgeLabel: `${key || '?'}`,
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
  ERR_AREA_004: 'Mã khu vực chỉ gồm chữ in hoa, số và dấu gạch ngang, dài 3–50 ký tự.',
  ERR_AREA_005: 'Tên khu vực bắt buộc, tối đa 150 ký tự.',
  ERR_AREA_007: 'Không được thay đổi mã khu vực sau khi tạo.',
  ERR_AREA_008: 'Khi hạ cấp độ an ninh, lý do là bắt buộc (10–255 ký tự).',
  ERR_AREA_009: 'Không thể vô hiệu hóa khu vực do còn camera đang gán.',
  ERR_AREA_010: 'Không thể vô hiệu hóa khu vực do còn quyền truy cập.',
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
