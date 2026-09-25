/**
 * Cấu hình kiểm soát chế độ đăng nhập Demo Bypass.
 * Chỉ cho phép khi:
 * 1. Chạy ở môi trường Development (import.meta.env.DEV === true)
 * 2. Cờ VITE_ENABLE_DEMO_LOGIN được cấu hình rõ ràng là 'true'
 * Mặc định luôn là false để ngăn chặn rò rỉ dữ liệu giả hoặc bypass xác thực trên Production / Dev thông thường.
 */
export const DEMO_LOGIN_ENABLED =
  import.meta.env.DEV === true &&
  import.meta.env.VITE_ENABLE_DEMO_LOGIN === 'true';
