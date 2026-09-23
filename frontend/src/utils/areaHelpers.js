/**
 * Area Level Visual Mapping & Helpers
 */
export const AREA_LEVEL_CONFIG = {
  PUBLIC: {
    code: 'PUBLIC',
    name: 'Công khai',
    badgeLabel: 'Công khai',
    badgeClass: 'level-badge--public',
    cardClass: 'zone-card--public',
    color: '#10b981',
    bgColor: 'rgba(16, 185, 129, 0.08)',
    borderColor: 'rgba(16, 185, 129, 0.35)',
    icon: 'globe',
    description: 'Khu vực tự do ra vào cho tất cả người dùng trong khuôn viên (ai cũng vào).'
  },
  INTERNAL_CONFIDENTIAL: {
    code: 'INTERNAL_CONFIDENTIAL',
    name: 'Bảo mật nội bộ',
    badgeLabel: 'Bảo mật nội bộ',
    badgeClass: 'level-badge--internal',
    cardClass: 'zone-card--internal',
    color: '#3b82f6',
    bgColor: 'rgba(59, 130, 246, 0.08)',
    borderColor: 'rgba(59, 130, 246, 0.35)',
    icon: 'shield',
    description: 'Khu vực nội bộ campus. Người dùng đủ cấp độ truy cập (level) là được vào.'
  },
  CONFIDENTIAL_CONTACT_REQUIRED: {
    code: 'CONFIDENTIAL_CONTACT_REQUIRED',
    name: 'Bảo mật - liên hệ trước',
    badgeLabel: 'Liên hệ trước',
    badgeClass: 'level-badge--contact',
    cardClass: 'zone-card--contact',
    color: '#f59e0b',
    bgColor: 'rgba(245, 158, 11, 0.08)',
    borderColor: 'rgba(245, 158, 11, 0.35)',
    icon: 'alert-triangle',
    description: 'Khu vực yêu cầu: cấp độ cao vào tự do, còn lại cần nhân sự chỉ định hoặc đơn đăng ký.'
  },
  HIGHLY_CONFIDENTIAL: {
    code: 'HIGHLY_CONFIDENTIAL',
    name: 'Tuyệt mật – chỉ người được chỉ định',
    badgeLabel: 'Tuyệt mật',
    badgeClass: 'level-badge--private',
    cardClass: 'zone-card--private',
    color: '#ef4444',
    bgColor: 'rgba(239, 68, 68, 0.08)',
    borderColor: 'rgba(239, 68, 68, 0.35)',
    icon: 'lock',
    description: 'Khu vực an ninh đặc biệt nghiêm ngặt. Chỉ người được chỉ định mới được phép vào.'
  }
};

/**
 * Lấy config hiển thị cho Level
 */
export function getLevelConfig(level) {
  let key = level;
  if (typeof level === 'object' && level !== null) {
    key = level.code || level.areaLevel || level.areaType || level.level;
  }
  if (key === 1 || key === '1') key = 'PUBLIC';
  if (key === 2 || key === '2' || key === 'SEMI_PRIVATE') key = 'INTERNAL_CONFIDENTIAL';
  if (key === 3 || key === '3' || key === 'PRIVATE') key = 'HIGHLY_CONFIDENTIAL';

  return AREA_LEVEL_CONFIG[key] || {
    code: key || 'UNKNOWN',
    name: key || 'Unknown Level',
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
    key = level.code || level.areaLevel || level.areaType || level.level;
  }
  if (key === 'PUBLIC' || key === 1 || key === '1') return 'zone-polygon--public';
  if (key === 'INTERNAL_CONFIDENTIAL' || key === 'SEMI_PRIVATE' || key === 2 || key === '2') return 'zone-polygon--internal';
  if (key === 'CONFIDENTIAL_CONTACT_REQUIRED') return 'zone-polygon--contact';
  if (key === 'HIGHLY_CONFIDENTIAL' || key === 'PRIVATE' || key === 3 || key === '3') return 'zone-polygon--private';
  return 'zone-polygon--default';
}

/**
 * BR-AR-01: Chuẩn hoá Unicode NFC trước, sau đó trim và gộp khoảng trắng liên tiếp.
 */
export function normalizeAreaName(name) {
  if (!name) return '';
  return name.normalize('NFC').trim().replace(/ +/g, ' ');
}

/**
 * BR-AR-01..04: Validate tên khu vực theo chuẩn backend.
 * Trả về message lỗi nếu không hợp lệ, hoặc null nếu hợp lệ.
 */
export function validateAreaName(name) {
  if (!name || !name.trim()) {
    return 'Tên khu vực bắt buộc, dài 3–100 ký tự.';
  }
  const normalized = normalizeAreaName(name);

  // BR-AR-02: Độ dài 3–100 ký tự sau chuẩn hoá
  if (normalized.length < 3 || normalized.length > 100) {
    return 'Tên khu vực bắt buộc, dài 3–100 ký tự.';
  }

  // BR-AR-03: Phải chứa ít nhất một chữ cái (Unicode, gồm tiếng Việt có dấu)
  if (!/\p{L}/u.test(normalized)) {
    return 'Tên khu vực phải chứa ít nhất một chữ cái.';
  }

  // BR-AR-04: Ký tự cho phép: chữ (Unicode), số, khoảng trắng thường ' ', - _ ( ) . , /
  if (!/^[\p{L}0-9 \-_().,/]+$/u.test(normalized)) {
    return 'Tên khu vực chỉ được chứa chữ cái, số, khoảng trắng và các ký tự: - _ ( ) . , /';
  }

  return null;
}

/**
 * Error Code Mapping sang thông báo thân thiện
 */
export const ERROR_MESSAGES = {
  ERR_AREA_001: 'Mã khu vực đã tồn tại trên hệ thống.',
  ERR_AREA_002: 'Không tìm thấy khu vực hoặc khu vực đã bị vô hiệu hóa.',
  ERR_AREA_003: 'Cấp độ an ninh không hợp lệ hoặc đã bị vô hiệu hóa.',
  ERR_AREA_004: 'Mã khu vực chỉ gồm chữ in hoa, số và dấu gạch ngang, dài 3–50 ký tự.',
  ERR_AREA_005: 'Tên khu vực bắt buộc, dài 3–100 ký tự.',
  ERR_AREA_007: 'Không được thay đổi mã khu vực sau khi tạo.',
  ERR_AREA_008: 'Khi hạ cấp độ an ninh, lý do là bắt buộc (10–255 ký tự).',
  ERR_AREA_009: 'Không thể vô hiệu hóa khu vực do còn camera đang gán.',
  ERR_AREA_010: 'Không thể vô hiệu hóa khu vực do còn quyền truy cập.',
  ERR_AREA_018: 'Tên khu vực phải chứa ít nhất một chữ cái.',
  ERR_AREA_019: 'Tên khu vực chỉ được chứa chữ cái, số, khoảng trắng và các ký tự: - _ ( ) . , /',
  ERR_AREA_020: 'Tên khu vực đã tồn tại trong cùng toà nhà và tầng.',
};


export function getErrorMessage(error) {
  if (!error) return 'Đã có lỗi xảy ra. Vui lòng thử lại.';
  if (error.message && !error.message.startsWith('Yêu cầu thất bại (HTTP')) {
    return error.message;
  }
  if (error.code && ERROR_MESSAGES[error.code]) {
    return `[${error.code}] ${ERROR_MESSAGES[error.code]}`;
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
