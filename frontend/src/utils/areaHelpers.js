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
  // Gộp loại 19/10 (V83): INTERNAL_CONFIDENTIAL + CONFIDENTIAL_CONTACT_REQUIRED -> CONFIDENTIAL. Loại ⇔ cấp (1/2/3).
  CONFIDENTIAL: {
    code: 'CONFIDENTIAL',
    name: 'Bảo mật',
    badgeLabel: 'Bảo mật',
    badgeClass: 'level-badge--internal',
    cardClass: 'zone-card--internal',
    color: '#3b82f6',
    bgColor: 'rgba(59, 130, 246, 0.08)',
    borderColor: 'rgba(59, 130, 246, 0.35)',
    icon: 'shield',
    description: 'Cấp 2. Người dùng cấp 2 trở lên vào thẳng; người khác vào qua đơn được duyệt, nhân sự chỉ định, khách hoặc chế độ sự kiện.'
  },
  HIGHLY_CONFIDENTIAL: {
    code: 'HIGHLY_CONFIDENTIAL',
    name: 'Tuyệt mật',
    badgeLabel: 'Tuyệt mật',
    badgeClass: 'level-badge--private',
    cardClass: 'zone-card--private',
    color: '#ef4444',
    bgColor: 'rgba(239, 68, 68, 0.08)',
    borderColor: 'rgba(239, 68, 68, 0.35)',
    icon: 'lock',
    description: 'Cấp 3. Người dùng cấp 3 vào thẳng; người khác vào qua đơn cá nhân được duyệt, nhân sự chỉ định hoặc bảo vệ đang xử lý sự cố.'
  }
};

/**
 * Tên loại trước V83 (còn trong audit, thông báo, dữ liệu cũ): hiển thị như Bảo mật, kèm tên cũ.
 * Không nằm trong AREA_LEVEL_CONFIG để mọi danh sách chọn loại chỉ có 3 loại.
 */
const LEGACY_AREA_LEVELS = {
  INTERNAL_CONFIDENTIAL: 'Nội bộ',
  CONFIDENTIAL_CONTACT_REQUIRED: 'Liên hệ trước',
  SEMI_PRIVATE: 'Nội bộ',
};

/** Ba loại khu vực theo thứ tự cấp, dùng cho danh sách chọn / bộ lọc / chú thích. */
export const AREA_LEVEL_OPTIONS = ['PUBLIC', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'].map((k) => AREA_LEVEL_CONFIG[k]);

/**
 * Lấy config hiển thị cho Level
 */
export function getLevelConfig(level) {
  let key = level;
  if (typeof level === 'object' && level !== null) {
    key = level.code || level.areaLevel || level.areaType || level.level;
  }
  if (key === 1 || key === '1') key = 'PUBLIC';
  if (key === 2 || key === '2') key = 'CONFIDENTIAL';
  if (key === 3 || key === '3' || key === 'PRIVATE') key = 'HIGHLY_CONFIDENTIAL';
  if (LEGACY_AREA_LEVELS[key]) {
    const label = `${AREA_LEVEL_CONFIG.CONFIDENTIAL.name} (cũ: ${LEGACY_AREA_LEVELS[key]})`;
    return { ...AREA_LEVEL_CONFIG.CONFIDENTIAL, code: key, name: label, badgeLabel: label };
  }

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
 * Cấp truy cập người dùng / khu vực (1–3): MỘT nhãn và MỘT bộ màu cho mọi trang.
 * Màu định nghĩa ở styles/components.css (.access-level-badge--N) qua token theme.css.
 */
export const ACCESS_LEVEL_CONFIG = {
  1: { level: 1, label: 'Cấp 1', className: 'access-level-badge access-level-badge--1' },
  2: { level: 2, label: 'Cấp 2', className: 'access-level-badge access-level-badge--2' },
  3: { level: 3, label: 'Cấp 3', className: 'access-level-badge access-level-badge--3' },
};

/**
 * Lấy nhãn + class hiển thị cho cấp truy cập (mặc định Cấp 1 khi thiếu dữ liệu)
 */
export function getAccessLevelConfig(level) {
  const n = Number(level);
  return ACCESS_LEVEL_CONFIG[n] || ACCESS_LEVEL_CONFIG[1];
}

/**
 * Hiển thị trạng thái lịch chế độ sự kiện (chỉ trình bày, không đổi dữ liệu).
 * Step 5b (BR-ES-S3): nhãn theo trạng thái ĐÃ LƯU, không tính theo giờ xem.
 * STARTED -> COMPLETED (phiên đóng do hết giờ) | ENDED_EARLY (FM tắt sớm) do backend chuyển.
 * variant dùng cho components/ui/Badge.
 */
// Nhãn theo trạng thái lưu, không tính lại theo giờ xem (BR-ES-L1) — màn Quản lý sự kiện
const SCHEDULE_STATUS_VIEW = {
  SCHEDULED: { label: 'Sắp diễn ra', variant: 'brand' },
  STARTED: { label: 'Đang diễn ra', variant: 'success' },
  COMPLETED: { label: 'Đã kết thúc', variant: 'neutral' },
  ENDED_EARLY: { label: 'Dừng sớm', variant: 'warning' },
  CANCELLED: { label: 'Đã huỷ', variant: 'neutral' },
  FAILED: { label: 'Không kích hoạt được', variant: 'danger' },
};

/** Thứ tự trạng thái cho bộ lọc màn Quản lý sự kiện */
export const SCHEDULE_STATUS_ORDER = ['SCHEDULED', 'STARTED', 'COMPLETED', 'ENDED_EARLY', 'CANCELLED', 'FAILED'];

export function getScheduleStatusView(status) {
  return SCHEDULE_STATUS_VIEW[status] || { label: status || '—', variant: 'neutral' };
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
  if (key === 'CONFIDENTIAL' || LEGACY_AREA_LEVELS[key] || key === 2 || key === '2') return 'zone-polygon--internal';
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
  ERR_AREA_002: 'Không tìm thấy khu vực hoặc khu vực đã bị vô hiệu hóa.',
  ERR_AREA_003: 'Cấp độ an ninh không hợp lệ hoặc đã bị vô hiệu hóa.',
  ERR_AREA_004: 'Mã khu vực chỉ gồm chữ in hoa, số và dấu gạch ngang, dài 3–50 ký tự.',
  ERR_AREA_005: 'Tên khu vực bắt buộc, dài 3–100 ký tự.',
  ERR_AREA_006: 'Toạ độ bản đồ phải có đủ cả X và Y.',
  ERR_AREA_007: 'Không được thay đổi mã khu vực sau khi tạo.',
  ERR_AREA_008: 'Khi hạ cấp độ an ninh, lý do là bắt buộc (10–255 ký tự).',
  ERR_AREA_009: 'Không thể vô hiệu hóa khu vực do còn camera đang gán.',
  ERR_AREA_010: 'Không thể vô hiệu hóa khu vực do còn quyền truy cập.',
  ERR_AREA_017: 'Khu vực đã ngừng hoạt động hoặc đã bị xoá.',
  ERR_AREA_018: 'Tên khu vực phải chứa ít nhất một chữ cái.',
  ERR_AREA_019: 'Tên khu vực chỉ được chứa chữ cái, số, khoảng trắng và các ký tự: - _ ( ) . , /',
  ERR_AREA_020: 'Tên khu vực đã tồn tại trong cùng tầng.',
  ERR_AREA_021: 'Thông tin tầng không hợp lệ hoặc không tìm thấy tầng tương ứng.',
};


export function getErrorMessage(error) {
  if (!error) return 'Đã có lỗi xảy ra. Vui lòng thử lại.';
  if (error.message && !error.message.startsWith('Yêu cầu thất bại (HTTP')) {
    return error.message;
  }
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
