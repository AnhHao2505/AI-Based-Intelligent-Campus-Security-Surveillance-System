export const ROLES = {
  ADMIN: 'ADMIN',
  FACILITY_MANAGER: 'FACILITY_MANAGER',
  GUARD: 'GUARD',
  NORMAL_USER: 'NORMAL_USER',
  // Aliases for seamless backward compatibility
  INTERNAL_GUARD: 'GUARD',
  OUTSOURCED_GUARD: 'GUARD',
};

export const ROLE_LABELS = {
  [ROLES.ADMIN]: 'Quản trị viên',
  [ROLES.FACILITY_MANAGER]: 'Quản lý cơ sở vật chất',
  [ROLES.GUARD]: 'Bảo vệ',
  [ROLES.NORMAL_USER]: 'Người dùng',
};

/** Nhãn ngắn cho chỗ chật (chân sidebar, chip): FACILITY_MANAGER viết tắt "Quản lý CSVC". */
export const ROLE_SHORT_LABELS = {
  ...ROLE_LABELS,
  [ROLES.FACILITY_MANAGER]: 'Quản lý CSVC',
};
