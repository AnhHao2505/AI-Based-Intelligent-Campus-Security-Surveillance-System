const USER_KEY = 'user';
const TOKEN_KEY = 'accessToken';
const LEGACY_TOKEN_KEY = 'token';

/**
 * Lấy thông tin user an toàn từ localStorage.
 * Bọc try/catch. Gặp null, "undefined", chuỗi rỗng hoặc JSON hỏng thì removeItem('user') và trả về null.
 *
 * @returns {object|null}
 */
export function getStoredUser() {
  try {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw || raw === 'undefined' || raw === 'null') {
      if (raw !== null) {
        localStorage.removeItem(USER_KEY);
      }
      return null;
    }

    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      localStorage.removeItem(USER_KEY);
      return null;
    }

    return parsed;
  } catch {
    try {
      localStorage.removeItem(USER_KEY);
    } catch {
      // ignore
    }
    return null;
  }
}

/**
 * Lưu thông tin user an toàn vào localStorage.
 * Chỉ ghi khi user là plain object hợp lệ; nếu không phải thì removeItem('user').
 *
 * @param {any} user
 */
export function setStoredUser(user) {
  try {
    if (user && typeof user === 'object' && !Array.isArray(user)) {
      localStorage.setItem(USER_KEY, JSON.stringify(user));
    } else {
      localStorage.removeItem(USER_KEY);
    }
  } catch {
    try {
      localStorage.removeItem(USER_KEY);
    } catch {
      // ignore
    }
  }
}

/**
 * Xóa thông tin xác thực (user và token) khỏi localStorage khi đăng xuất hoặc hết hạn phiên.
 */
export function clearStoredAuth() {
  try {
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(LEGACY_TOKEN_KEY);
  } catch {
    // ignore
  }
}
