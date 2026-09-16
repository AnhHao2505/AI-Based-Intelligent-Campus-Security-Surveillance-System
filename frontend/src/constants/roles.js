export const ROLES = {
  ADMIN: 'ADMIN',
  FACILITY_MANAGER: 'FACILITY_MANAGER',
  GUARD: 'GUARD',
};

export const ROLE_LABELS = {
  [ROLES.ADMIN]: 'Quản trị viên',
  [ROLES.FACILITY_MANAGER]: 'Quản lý cơ sở',
  [ROLES.GUARD]: 'Bảo vệ',
};

export function normalizeRole(role) {
  if (role === 'INTERNAL_GUARD' || role === 'OUTSOURCED_GUARD') {
    return ROLES.GUARD;
  }
  return role;
}
