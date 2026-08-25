// Mock Auth API layer for local dev
// TODO: Replace with fetch('/api/v1/auth/login') when Spring Boot Auth controller is ready

const MOCK_USERS = {
  'GUARD01': {
    user_code: 'GUARD01',
    full_name: 'Trần Văn Bảo (Bảo vệ)',
    role_type: 'INTERNAL_GUARD',
    email: 'guard01@campus.edu.vn'
  },
  'FM01': {
    user_code: 'FM01',
    full_name: 'Nguyễn Thị Mai (Facility Manager)',
    role_type: 'FACILITY_MANAGER',
    email: 'fm01@campus.edu.vn'
  },
  'ADMIN01': {
    user_code: 'ADMIN01',
    full_name: 'Quản trị viên Hệ thống',
    role_type: 'ADMIN',
    email: 'admin01@campus.edu.vn'
  }
};

export async function mockLoginApi(userCode, password) {
  // Giả lập delay mạng 600ms
  await new Promise((resolve) => setTimeout(resolve, 600));

  const trimmedCode = userCode ? userCode.trim().toUpperCase() : '';

  if (!trimmedCode) {
    throw new Error('Vui lòng nhập Mã định danh (MSSV / MSNV)');
  }

  if (!password) {
    throw new Error('Vui lòng nhập Mật khẩu');
  }

  const user = MOCK_USERS[trimmedCode];

  // Chấp nhận mật khẩu "123456" cho các tài khoản test
  if (user && password === '123456') {
    return {
      token: `mock-jwt-token-${user.role_type.toLowerCase()}-${Date.now()}`,
      user: { ...user }
    };
  }

  throw new Error('Mã định danh hoặc mật khẩu không chính xác');
}
