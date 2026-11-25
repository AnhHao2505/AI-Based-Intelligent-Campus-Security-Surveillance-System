// B1: gán lỗi của "Tạo tài khoản" (POST /api/users/staff-accounts) vào ô của form theo errorCode / data của backend,
// không so chữ trong message. Lỗi không nhận ra (kể cả 500) -> vùng lỗi chung của popup.
import { isAccountFaceErrorCode } from './accountFaceImage.js';

/**
 * Các ô của form tạo tài khoản có hiện lỗi theo trường (khớp tên trường của StaffAccountCreateRequest). "role" không có
 * chỗ hiện lỗi riêng nên lỗi của nó rơi về vùng lỗi chung.
 */
export const CREATE_ACCOUNT_FIELDS = ['fullName', 'userCode', 'email', 'accessLevel', 'faceImage'];

/**
 * Lấy map trường → câu từ body lỗi: 400 VALIDATION_ERROR (lỗi @Valid) và 409 Conflict trùng mã / email đều để ở data.
 * apiClient gắn cả body vào err.data, nên map nằm ở err.data.data.
 * @param {any} err
 * @returns {Record<string, string>} chỉ giữ trường có trong CREATE_ACCOUNT_FIELDS và câu không rỗng
 */
function fieldErrorsOf(err) {
  const data = err?.data?.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return {};
  const mapped = {};
  for (const field of CREATE_ACCOUNT_FIELDS) {
    const message = data[field];
    if (typeof message === 'string' && message.trim() !== '') mapped[field] = message;
  }
  return mapped;
}

/**
 * Lỗi của request tạo tài khoản → object cho setFormErrors.
 * - ERR_AC_014 → ô "Cấp truy cập ban đầu"
 * - ERR_FACE_001..008 → ô ảnh khuôn mặt
 * - VALIDATION_ERROR (400) / Conflict (409, trùng mã / email) có data theo trường → đúng các ô đó
 * - còn lại (500, lỗi mạng, mã lạ, 409 không có data…) → vùng lỗi chung (general)
 * @param {any} err Error do apiClient ném (.status, .code, .message, .data)
 * @param {string} fallbackMessage câu dùng khi err không có message
 * @returns {Record<string, string>}
 */
export function mapCreateAccountError(err, fallbackMessage) {
  const message = err?.message || fallbackMessage;
  const code = err?.code;
  if (code === 'ERR_AC_014') return { accessLevel: message };
  if (isAccountFaceErrorCode(code)) return { faceImage: message };
  if (code === 'VALIDATION_ERROR' || code === 'Conflict') {
    const fields = fieldErrorsOf(err);
    if (Object.keys(fields).length > 0) return fields;
  }
  return { general: message };
}
