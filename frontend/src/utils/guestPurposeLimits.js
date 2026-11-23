/**
 * Độ dài mục đích lượt khách (GUEST_PURPOSE_MIN_LENGTH / GUEST_PURPOSE_MAX_LENGTH, BR-GV-03) cho MỘT lần mở form
 * "Tạo lượt khách". Mỗi lần mở form đọc lại GET /api/system-configurations/guest-purpose-limits, không dùng giá trị
 * đọc lúc tải trang (cùng cách giới hạn ảnh, TC-F12-S07).
 * Đang đọc / đọc lỗi: không đoán số -> không báo lỗi độ dài, bộ đếm chỉ hiện số ký tự, backend kiểm theo cấu hình hiện hành
 * (giống purposeLimitState của đơn truy cập khi limits.loaded = false).
 * status: 'idle' (chưa mở) | 'loading' | 'ready' | 'error'.
 */
export const GUEST_PURPOSE_LIMITS_INITIAL = Object.freeze({ status: 'idle', min: null, max: null });

/**
 * { minLength, maxLength } của backend -> { min, max }; thiếu / không phải số nguyên dương / min > max -> null (không đoán số).
 * @param {{ minLength?: number, maxLength?: number }|null|undefined} data
 * @returns {{ min: number, max: number }|null}
 */
export function readGuestPurposeLimits(data) {
  const min = Number(data?.minLength);
  const max = Number(data?.maxLength);
  if (!Number.isSafeInteger(min) || !Number.isSafeInteger(max)) return null;
  if (min < 1 || max < min) return null;
  return { min, max };
}

/**
 * @param {{ status: string, min: number|null, max: number|null }} state
 * @param {{ type: 'open' } | { type: 'loaded', data: object|null } | { type: 'failed' }} action
 */
export function guestPurposeLimitsReducer(state, action) {
  switch (action?.type) {
    case 'open':
      return { status: 'loading', min: null, max: null };
    case 'loaded': {
      const limits = readGuestPurposeLimits(action.data);
      return limits ? { status: 'ready', ...limits } : { status: 'error', min: null, max: null };
    }
    case 'failed':
      return { status: 'error', min: null, max: null };
    default:
      return state;
  }
}

/**
 * Hàm đọc lại giới hạn cho mỗi lần mở form: dispatch 'open' (xoá giá trị cũ) rồi 'loaded' / 'failed'.
 * Kết quả (thành công hay lỗi) của lần mở trước trả về muộn bị bỏ, không ghi đè lần mở mới.
 * @param {() => Promise<object|null>} fetchLimits gọi GET /api/system-configurations/guest-purpose-limits
 * @param {(action: object) => void} dispatch dispatch của guestPurposeLimitsReducer
 * @returns {() => Promise<void>}
 */
export function createGuestPurposeLimitsLoader(fetchLimits, dispatch) {
  let latest = 0;
  return async function reload() {
    const seq = ++latest;
    dispatch({ type: 'open' });
    try {
      const data = (await fetchLimits()) || null;
      if (seq === latest) dispatch({ type: 'loaded', data });
    } catch {
      if (seq === latest) dispatch({ type: 'failed' });
    }
  };
}

/**
 * Trạng thái ô "Mục đích" của form tạo lượt khách. Độ dài tính sau khi trim, giống backend.
 * - Đã đọc được giới hạn: báo lỗi khi đã nhập mà ngoài [min, max]; bộ đếm "n/max ký tự (tối thiểu min)";
 *   khoá "Gửi duyệt" khi ngoài [min, max].
 * - Chưa đọc được (đang tải / lỗi): không báo lỗi độ dài, bộ đếm "n ký tự"; chỉ khoá khi ô trống (mục đích bắt buộc).
 * @param {string} value
 * @param {{ status: string, min: number|null, max: number|null }} limits
 * @returns {{ enforced: boolean, length: number, error: boolean, counter: string, blocker: (string|null) }}
 */
export function guestPurposeState(value, limits) {
  const length = (value || '').trim().length;
  const enforced = limits?.status === 'ready' && Number.isFinite(limits.min) && Number.isFinite(limits.max);
  if (!enforced) {
    return {
      enforced: false,
      length,
      error: false,
      counter: `${length} ký tự`,
      blocker: length === 0 ? 'nhập mục đích' : null,
    };
  }
  const { min, max } = limits;
  const outOfRange = length < min || length > max;
  return {
    enforced: true,
    length,
    error: length > 0 && outOfRange,
    counter: `${length}/${max} ký tự (tối thiểu ${min})`,
    blocker: outOfRange ? `mục đích ${min}–${max} ký tự (hiện có ${length})` : null,
  };
}
