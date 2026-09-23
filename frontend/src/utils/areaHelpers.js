/**
 * Area Level Visual Mapping & Helpers
 */
export const AREA_LEVEL_CONFIG = {
  PUBLIC: {
    code: 'PUBLIC',
    name: 'Công khai',
    rank: 1,
    badgeLabel: 'Công khai',
    badgeClass: 'level-badge--public',
    cardClass: 'zone-card--public',
    color: '#10b981',
    bgColor: 'rgba(16, 185, 129, 0.08)',
    borderColor: 'rgba(16, 185, 129, 0.35)',
    icon: 'globe',
    description: 'Khu vực tự do ra vào cho tất cả người dùng (Level 1, 2, 3).'
  },
  INTERNAL_CONFIDENTIAL: {
    code: 'INTERNAL_CONFIDENTIAL',
    name: 'Bảo mật nội bộ',
    rank: 2,
    badgeLabel: 'Bảo mật nội bộ',
    badgeClass: 'level-badge--internal',
    cardClass: 'zone-card--internal',
    color: '#3b82f6',
    bgColor: 'rgba(59, 130, 246, 0.08)',
    borderColor: 'rgba(59, 130, 246, 0.35)',
    icon: 'shield',
    description: 'Khu vực nội bộ campus. Chỉ dành cho người dùng từ Level 2 trở lên.'
  },
  CONFIDENTIAL_CONTACT_REQUIRED: {
    code: 'CONFIDENTIAL_CONTACT_REQUIRED',
    name: 'Bảo mật - liên hệ trước',
    rank: 2,
    badgeLabel: 'Liên hệ trước',
    badgeClass: 'level-badge--contact',
    cardClass: 'zone-card--contact',
    color: '#f59e0b',
    bgColor: 'rgba(245, 158, 11, 0.08)',
    borderColor: 'rgba(245, 158, 11, 0.35)',
    icon: 'alert-triangle',
    description: 'Khu vực yêu cầu người dùng Level 2 làm đơn đăng ký / liên hệ trước. Người dùng Level 3 có clearance ra vào trực tiếp.'
  },
  HIGHLY_CONFIDENTIAL: {
    code: 'HIGHLY_CONFIDENTIAL',
    name: 'Bảo mật cao - Tuyệt đối cấm vào',
    rank: 3,
    badgeLabel: 'Bảo mật cao',
    badgeClass: 'level-badge--private',
    cardClass: 'zone-card--private',
    color: '#ef4444',
    bgColor: 'rgba(239, 68, 68, 0.08)',
    borderColor: 'rgba(239, 68, 68, 0.35)',
    icon: 'lock',
    description: 'Khu vực an ninh đặc biệt nghiêm ngặt. Chỉ người dùng Level 3 hoặc nhân sự gán cố định mới được truy cập.'
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
  if (key === 2 || key === '2' || key === 'SEMI_PRIVATE') key = 'INTERNAL_CONFIDENTIAL';
  if (key === 3 || key === '3' || key === 'PRIVATE') key = 'HIGHLY_CONFIDENTIAL';

  return AREA_LEVEL_CONFIG[key] || {
    code: key || 'UNKNOWN',
    name: key || 'Unknown Level',
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
 * Lấy class CSS polygon tương ứng cho từng Cấp độ An ninh khu vực
 */
export function getLevelPolygonClass(level) {
  let key = level;
  if (typeof level === 'object' && level !== null) {
    key = level.code || level.areaLevel || level.level;
  }
  if (key === 'PUBLIC' || key === 1 || key === '1') return 'zone-polygon--public';
  if (key === 'INTERNAL_CONFIDENTIAL' || key === 'SEMI_PRIVATE' || key === 2 || key === '2') return 'zone-polygon--internal';
  if (key === 'CONFIDENTIAL_CONTACT_REQUIRED') return 'zone-polygon--contact';
  if (key === 'HIGHLY_CONFIDENTIAL' || key === 'PRIVATE' || key === 3 || key === '3') return 'zone-polygon--private';
  return 'zone-polygon--default';
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
