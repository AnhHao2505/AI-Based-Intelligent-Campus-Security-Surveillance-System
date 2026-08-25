import { apiGet, apiPost } from '../api/apiClient';

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
 * Trả về 3 trạng thái phân quyền riêng biệt:
 * - 'ADMIN': Thuộc nhóm quản trị / bảo vệ -> /dashboard
 * - 'PERSONAL': Thuộc nhóm người dùng cá nhân (Student/Teacher/Staff...) -> /personal/access-history
 * - 'UNKNOWN': Không xác định được vai trò (role_type bị thiếu/hỏng) -> Từ chối cấp quyền cả 2 portal, bắt đăng nhập lại
 */
export function getUserPortalRoute(user) {
  const roleType = user?.roleType || user?.role_type || user?.role;
  if (!roleType) return 'UNKNOWN';
  return ADMIN_ROLES.includes(roleType) ? 'ADMIN' : 'PERSONAL';
}

/**
 * Gửi Google ID token lên backend để xác thực
 * POST /api/auth/google
 */
export async function loginWithGoogle(idToken) {
  return await apiPost('/api/auth/google', { idToken });
}

/**
 * Lấy thông tin user hiện tại từ token đã lưu
 * GET /api/auth/me
 */
export async function getCurrentUser() {
  return await apiGet('/api/auth/me');
}
