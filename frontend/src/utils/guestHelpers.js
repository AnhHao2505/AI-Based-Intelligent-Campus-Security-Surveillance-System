import { format } from 'date-fns';

/** Nhãn + màu (variant của Badge) cho trạng thái lượt khách. */
export const GUEST_VISIT_STATUS = {
  PENDING: { label: 'Chờ duyệt', variant: 'warning' },
  APPROVED: { label: 'Đã duyệt', variant: 'success' },
  REJECTED: { label: 'Bị từ chối', variant: 'danger' },
  CANCELLED: { label: 'Đã huỷ', variant: 'neutral' },
  REVOKED: { label: 'Bị thu hồi', variant: 'danger' },
  EXPIRED: { label: 'Hết hạn (chưa duyệt)', variant: 'neutral' },
  COMPLETED: { label: 'Đã kết thúc', variant: 'brand' },
};

export function getGuestVisitStatus(status) {
  return GUEST_VISIT_STATUS[status] || { label: status || '—', variant: 'neutral' };
}

/** Trạng thái dữ liệu sinh trắc của từng khách. */
export const GUEST_BIOMETRIC_STATUS = {
  NO_PHOTO: { label: 'Chưa có ảnh', variant: 'neutral' },
  PHOTO_READY: { label: 'Đã có ảnh', variant: 'success' },
  PHOTO_ONLY: { label: 'Có ảnh, chưa nhận diện được', variant: 'warning' },
  DELETED: { label: 'Đã xoá dữ liệu khuôn mặt', variant: 'neutral' },
};

export function getGuestBiometricStatus(status) {
  return GUEST_BIOMETRIC_STATUS[status] || { label: status || '—', variant: 'neutral' };
}

/** Loại khu vực nhận khách (BR-GV-04, V83): chỉ Bảo mật. */
export const GUEST_ALLOWED_AREA_LEVELS = ['CONFIDENTIAL'];

export function formatDateTime(value) {
  if (!value) return '—';
  try {
    return format(new Date(value), 'HH:mm dd/MM/yyyy');
  } catch {
    return String(value);
  }
}

/** Host còn huỷ được: PENDING / APPROVED và chưa tới giờ kết thúc (BR-GV-09). */
export function canHostCancel(visit, now = new Date()) {
  if (!visit) return false;
  if (visit.status !== 'PENDING' && visit.status !== 'APPROVED') return false;
  return new Date(visit.endTime) > now;
}

/**
 * BR-GV-38: lượt ĐÃ DUYỆT còn khách chưa PHOTO_READY (chưa đăng ký khuôn mặt tại quầy -> camera chưa nhận diện được).
 * Trả { missing, total } để hiện nhãn "Chưa có ảnh (x/y khách)"; null nếu không cần nhãn.
 */
export function getMissingPhotoInfo(visit) {
  if (!visit || visit.status !== 'APPROVED') return null;
  const guests = visit.guests || [];
  const missing = guests.filter((g) => g.biometricStatus !== 'PHOTO_READY').length;
  return missing > 0 ? { missing, total: guests.length } : null;
}

/** Khách còn ảnh (xem được qua URL có hạn). */
export function guestHasPhoto(guest) {
  return guest?.biometricStatus === 'PHOTO_READY' || guest?.biometricStatus === 'PHOTO_ONLY';
}

/**
 * V141: giới hạn ảnh khách (GUEST_PHOTO_MAX_KB) lấy từ /api/guest-visits/eligibility (người dùng thường không đọc được
 * /api/system-configurations). Không có / không phải số nguyên dương -> null: không đoán số, trình duyệt không chặn.
 * @param {{ photoMaxKb?: number }|null|undefined} eligibility
 * @returns {number|null}
 */
export function readGuestPhotoMaxKb(eligibility) {
  const kb = Number(eligibility?.photoMaxKb);
  return Number.isSafeInteger(kb) && kb > 0 ? kb : null;
}

/**
 * TC-F12-S07: giới hạn ảnh (GUEST_PHOTO_MAX_KB) và phiên bản thông báo đồng ý của MỘT lần mở form khách
 * (form tạo lượt / hộp gắn - thay ảnh). Mỗi lần mở form đọc lại /eligibility, không dùng giá trị đọc lúc tải trang.
 * status: 'idle' (chưa mở) | 'loading' (đang đọc) | 'ready' (đã đọc) | 'error' (đọc lỗi).
 * Đang đọc hoặc đọc lỗi: photoMaxKb = null -> trình duyệt không chặn dung lượng, backend kiểm (ERR_GUEST_033);
 * consentVersion = null -> khoá ô đồng ý như khi chưa tải được phiên bản thông báo.
 */
export const GUEST_FORM_LIMITS_INITIAL = Object.freeze({
  status: 'idle',
  photoMaxKb: null,
  consentVersion: null,
});

/**
 * @param {{ status: string, photoMaxKb: number|null, consentVersion: string|null }} state
 * @param {{ type: 'open' } | { type: 'loaded', eligibility: object|null } | { type: 'failed' }} action
 */
export function guestFormLimitsReducer(state, action) {
  switch (action?.type) {
    case 'open':
      return { status: 'loading', photoMaxKb: null, consentVersion: null };
    case 'loaded':
      return {
        status: 'ready',
        photoMaxKb: readGuestPhotoMaxKb(action.eligibility),
        consentVersion: action.eligibility?.consentNoticeVersion || null,
      };
    case 'failed':
      return { status: 'error', photoMaxKb: null, consentVersion: null };
    default:
      return state;
  }
}

/**
 * Hàm đọc lại /eligibility cho mỗi lần mở form: dispatch 'open' (xoá giá trị cũ) rồi 'loaded' / 'failed'.
 * Mở form lần sau khi lần trước chưa trả về: kết quả của lần cũ bị bỏ qua (không ghi đè giá trị mới).
 * @param {() => Promise<object|null>} fetchEligibility gọi GET /api/guest-visits/eligibility
 * @param {(action: object) => void} dispatch dispatch của guestFormLimitsReducer
 * @returns {() => Promise<object|null>} trả dữ liệu /eligibility của lần mở mới nhất; null nếu lỗi hoặc đã có lần mở mới hơn
 */
export function createGuestFormLimitsLoader(fetchEligibility, dispatch) {
  let latest = 0;
  return async function reload() {
    const seq = ++latest;
    dispatch({ type: 'open' });
    try {
      const data = (await fetchEligibility()) || null;
      if (seq !== latest) return null;
      dispatch({ type: 'loaded', eligibility: data });
      return data;
    } catch {
      if (seq === latest) dispatch({ type: 'failed' });
      return null;
    }
  };
}

/**
 * Cụm chữ giới hạn dung lượng cho nhãn ô chọn ảnh khách.
 * @param {number|null} maxKb
 * @returns {string} "tối đa 2048 KB" hoặc "dung lượng tối đa theo cấu hình hệ thống" khi chưa đọc được
 */
export function guestPhotoLimitText(maxKb) {
  return Number.isFinite(maxKb) && maxKb > 0 ? `tối đa ${maxKb} KB` : 'dung lượng tối đa theo cấu hình hệ thống';
}

/**
 * A1: kiểm ảnh khách phía trình duyệt trước khi gửi (backend vẫn kiểm lại, ERR_GUEST_033): JPG / PNG, không quá maxKb.
 * maxKb null (chưa đọc được giới hạn) -> không kiểm dung lượng ở trình duyệt, để backend kiểm.
 * @returns {string|null} câu lỗi, hoặc null nếu hợp lệ
 */
export function validateGuestPhotoFile(file, maxKb) {
  if (!file) return 'Chưa chọn ảnh khuôn mặt';
  if (!['image/jpeg', 'image/png'].includes(file.type)) return 'Ảnh phải là JPG hoặc PNG';
  if (Number.isFinite(maxKb) && maxKb > 0 && file.size > maxKb * 1024) {
    return `Ảnh tối đa ${maxKb} KB (ảnh đang chọn ${Math.ceil(file.size / 1024)} KB)`;
  }
  return null;
}

/**
 * C2: FM thấy nút Duyệt / Từ chối trên dòng lượt khách khi lượt còn Chờ duyệt, chưa tới giờ bắt đầu
 * và người mời không phải chính FM đang đăng nhập (không tự duyệt, BR-GV-10).
 */
export function canReviewGuestVisit(visit, viewerCode, now = new Date()) {
  if (!visit || visit.status !== 'PENDING') return false;
  if (!(new Date(visit.startTime) > now)) return false;
  if (viewerCode && visit.hostCode === viewerCode) return false;
  return true;
}

/** A5: host thay được ảnh khách khi lượt Chờ duyệt hoặc Đã duyệt mà chưa tới giờ bắt đầu, khách chưa bị xoá dữ liệu. */
export function canHostReplacePhoto(visit, guest, now = new Date()) {
  if (!visit || !guest) return false;
  if (visit.status !== 'PENDING' && visit.status !== 'APPROVED') return false;
  if (!(new Date(visit.startTime) > now)) return false;
  return guest.biometricStatus !== 'DELETED';
}

/** Lỗi backend gắn với một khách (data.guestIndex khi tạo lượt kèm ảnh): trả chỉ số khách, hoặc null. */
export function getErrorGuestIndex(err) {
  const index = err?.data?.data?.guestIndex;
  return Number.isInteger(index) ? index : null;
}
