const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const ROLE_LABELS = {
  ADMIN: 'Quản trị viên',
  FACILITY_MANAGER: 'Quản lý cơ sở',
  INTERNAL_GUARD: 'Bảo vệ nội bộ',
  OUTSOURCED_GUARD: 'Bảo vệ thuê ngoài',
  TEACHER: 'Giảng viên',
  STUDENT: 'Sinh viên',
  STAFF: 'Nhân viên',
  PRINCIPAL: 'Hiệu trưởng',
  JANITOR: 'Nhân viên vệ sinh',
};

const ADMIN_ROLES = ['ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'OUTSOURCED_GUARD'];

/**
 * Kiểm tra xem user có thuộc nhóm Personal Portal hay không (Exclusion logic)
 * Sửa bug: Dùng role_type (khớp DB users.role_type) thay vì role.
 * Tránh fail-open (nếu không có role_type/role thì trả về false để fail-closed an toàn).
 */
export function isPersonalPortalUser(user) {
  const roleType = user?.role_type || user?.role;
  if (!roleType) return false;
  return !ADMIN_ROLES.includes(roleType);
}

/**
 * Gửi Google ID token lên backend để xác thực
 * POST /api/auth/google
 */
export async function loginWithGoogle(idToken) {
  try {
    const response = await fetch(`${API_BASE_URL}/api/auth/google`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ idToken }),
    });

    if (!response.ok) {
      if (response.status === 401 || response.status === 403 || response.status === 404) {
        throw new Error('Tài khoản Google này chưa được đăng ký trong hệ thống. Vui lòng liên hệ phòng đào tạo/nhân sự.');
      }
      const error = await response.json().catch(() => ({ message: 'Xác thực không thành công' }));
      throw new Error(error.message || `Lỗi HTTP ${response.status}`);
    }

    return await response.json();
  } catch (err) {
    // Sửa bug 2: Chỉ kích hoạt dev mock fallback khi đang ở môi trường DEV (import.meta.env.DEV === true)
    if (import.meta.env.DEV && (err.message.includes('Failed to fetch') || err.message.includes('NetworkError'))) {
      console.warn('Backend not reachable, using dev local mock verification');
      return {
        accessToken: 'mock-jwt-token-12345',
        user: {
          id: '101',
          fullName: 'Nguyen T.',
          email: 'nguyent@fpt.edu.vn',
          staffCode: 'SE160000',
          role_type: 'STUDENT',
          role: 'STUDENT',
        },
      };
    }
    throw err;
  }
}

/**
 * Lấy thông tin user hiện tại
 */
export async function getCurrentUser(accessToken) {
  const response = await fetch(`${API_BASE_URL}/api/auth/me`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
  });

  if (!response.ok) {
    throw new Error('Failed to fetch user info');
  }

  return response.json();
}

/**
 * Lưu auth data vào localStorage
 */
export function saveAuth(authResponse) {
  localStorage.setItem('accessToken', authResponse.accessToken);
  localStorage.setItem('user', JSON.stringify(authResponse.user));
}

/**
 * Lấy token từ localStorage
 */
export function getAccessToken() {
  return localStorage.getItem('accessToken');
}

/**
 * Lấy user info từ localStorage
 */
export function getStoredUser() {
  const user = localStorage.getItem('user');
  return user ? JSON.parse(user) : null;
}

/**
 * Xoá auth data (logout)
 */
export function clearAuth() {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('user');
}

/**
 * Kiểm tra đã đăng nhập chưa
 */
export function isAuthenticated() {
  return !!getAccessToken();
}
