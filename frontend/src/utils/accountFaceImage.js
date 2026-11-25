// V140: dung lượng tối đa ảnh khuôn mặt tài khoản nằm ở cấu hình USER_FACE_IMAGE_MAX_KB (GET /api/system-configurations).
// Backend (FaceDataService.registerFace, UserBulkImportHelper) luôn kiểm lại; phía trình duyệt chỉ chặn sớm khi đã đọc
// được cấu hình, không đoán con số.

export const USER_FACE_IMAGE_MAX_KB_KEY = 'USER_FACE_IMAGE_MAX_KB';

/**
 * Đọc USER_FACE_IMAGE_MAX_KB từ danh sách cấu hình.
 * Trả null (không chặn ở trình duyệt) khi: không có khoá, không phải số nguyên dương, hoặc nằm ngoài min_value / max_value
 * của chính dòng cấu hình đó (backend khi ấy dùng mặc định riêng qua getIntWithinBounds, trình duyệt không biết số đó).
 * @param {Array<{configKey: string, configValue: string, minValue?: number|null, maxValue?: number|null}>|null|undefined} configs
 * @returns {number|null}
 */
export function readFaceImageMaxKb(configs) {
  const cfg = (configs || []).find((c) => c?.configKey === USER_FACE_IMAGE_MAX_KB_KEY);
  if (!cfg) return null;
  const raw = String(cfg.configValue ?? '').trim();
  if (!/^\d+$/.test(raw)) return null;
  const kb = Number(raw);
  if (!Number.isSafeInteger(kb) || kb <= 0) return null;
  if (cfg.minValue != null && kb < Number(cfg.minValue)) return null;
  if (cfg.maxValue != null && kb > Number(cfg.maxValue)) return null;
  return kb;
}

/**
 * Kiểm dung lượng ảnh khuôn mặt tài khoản (cùng mốc với backend: lớn hơn maxKb * 1024 byte mới bị từ chối).
 * maxKb null (chưa đọc được cấu hình) -> không chặn, để backend tự kiểm.
 * @param {{size: number}|null|undefined} file
 * @param {number|null} maxKb
 * @returns {string|null} câu lỗi nêu giá trị cấu hình, hoặc null nếu không chặn
 */
export function validateFaceImageSize(file, maxKb) {
  if (!file || !Number.isFinite(maxKb) || maxKb <= 0) return null;
  if (file.size > maxKb * 1024) {
    return `Dung lượng ảnh (${(file.size / 1024).toFixed(1)} KB) vượt quá giới hạn tối đa ${maxKb} KB. Vui lòng chọn hoặc nén ảnh nhỏ hơn.`;
  }
  return null;
}

/**
 * Cụm chữ giới hạn dung lượng cho hướng dẫn nạp theo lô / ô chọn ảnh.
 * @param {number|null} maxKb
 * @returns {string} "tối đa 350 KB/ảnh" hoặc "dung lượng tối đa theo cấu hình hệ thống" khi chưa đọc được
 */
export function faceImageLimitText(maxKb) {
  return Number.isFinite(maxKb) && maxKb > 0
    ? `tối đa ${maxKb} KB/ảnh`
    : 'dung lượng tối đa theo cấu hình hệ thống';
}

/**
 * B1: mọi lỗi ảnh khuôn mặt tài khoản backend trả errorCode / code dạng ERR_FACE_xxx (FaceErrorCode: 001 trùng mặt,
 * 002 rỗng, 003 quá dung lượng, 004 sai định dạng, 005 không có mặt, 006 nhiều mặt, 007 AI không xử lý được ảnh,
 * 008 dịch vụ AI không khả dụng). Màn tạo tài khoản dựa vào mã để hiện lỗi dưới ô ảnh, không so câu chữ.
 * @param {string|null|undefined} code err.code từ apiClient
 * @returns {boolean}
 */
export function isAccountFaceErrorCode(code) {
  return typeof code === 'string' && /^ERR_FACE_\d{3}$/.test(code);
}
