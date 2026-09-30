import { apiGet, apiPost, apiPut, apiDelete } from './apiClient';

export const guardScheduleApi = {
  // Shifts (Admin)
  getShifts: (params = {}) => {
    const query = new URLSearchParams();
    if (params.startDate) query.append('startDate', params.startDate);
    if (params.endDate) query.append('endDate', params.endDate);
    if (params.guardId) query.append('guardId', params.guardId);
    if (params.building) query.append('building', params.building);
    if (params.status) query.append('status', params.status);
    const queryString = query.toString() ? `?${query.toString()}` : '';
    return apiGet(`/api/guard-shifts${queryString}`);
  },

  createShift: (data) => apiPost('/api/guard-shifts', data),

  updateShift: (id, data) => apiPut(`/api/guard-shifts/${id}`, data),

  deleteShift: (id) => apiDelete(`/api/guard-shifts/${id}`),

  bulkClearShifts: (data) => apiPost('/api/guard-shifts/bulk-clear', data),

  // Guard Self-Service
  getMyShifts: (params = {}) => {
    const query = new URLSearchParams();
    if (params.startDate) query.append('startDate', params.startDate);
    if (params.endDate) query.append('endDate', params.endDate);
    const queryString = query.toString() ? `?${query.toString()}` : '';
    return apiGet(`/api/guard-shifts/my-shifts${queryString}`);
  },

  checkIn: (id) => apiPost(`/api/guard-shifts/${id}/check-in`),

  checkOut: (id) => apiPost(`/api/guard-shifts/${id}/check-out`),

  // Staffing Wizard & Capacity
  calculateCapacity: (data) => apiPost('/api/guard-shifts/capacity/calculate', data),

  generateShiftsFromWizard: (data) => apiPost('/api/guard-shifts/wizard/generate', data),

  getAvailableSubstitutes: (shiftId) => apiGet(`/api/guard-shifts/${shiftId}/available-substitutes`),

  getAvailableSwapShifts: (shiftId) => apiGet(`/api/guard-shifts/${shiftId}/available-swap-shifts`),

  // Export Timesheet Excel
  exportTimesheet: async ({ year, month, teamId }) => {
    const query = new URLSearchParams();
    query.append('year', year);
    query.append('month', month);
    if (teamId) query.append('teamId', teamId);

    const token = localStorage.getItem('accessToken') || localStorage.getItem('token');
    const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

    // Demo Mode Fallback: Tạo file tổng hợp demo nếu đăng nhập qua tài khoản demo
    if (token?.startsWith('frontend-demo-')) {
      const csvContent = '\uFEFF' +
        `BẢNG CHẤM CÔNG VÀ TỔNG HỢP CA TRỰC BẢO VỆ\n` +
        `Tháng: ${String(month).padStart(2, '0')}/${year}\n` +
        `Đơn vị: ${teamId ? 'Đội đã chọn' : 'Toàn bộ các đội bảo vệ'}\n\n` +
        `STT,Mã Bảo Vệ,Họ và Tên,Đội Biên Chế,Tổng Ca Trực,Ca Sáng,Ca Chiều,Ca Đêm,Làm Thêm (OT),Nghỉ Phép\n` +
        `1,NV-BV01,Nguyễn Văn An,Tổ 1 - An ninh Cổng & Vòng ngoài,24,10,8,6,2,1\n` +
        `2,NV-BV02,Bảo Vệ Demo,Tổ 1 - An ninh Cổng & Vòng ngoài,22,8,8,6,0,2\n`;
      const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `Bang_Cham_Cong_Bao_Ve_Thang_${String(month).padStart(2, '0')}_${year}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
      return;
    }

    const response = await fetch(`${API_BASE_URL}/api/guard-shifts/export-timesheet?${query.toString()}`, {
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      }
    });

    if (!response.ok) {
      if (response.status === 401) {
        throw new Error('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
      }
      if (response.status === 403) {
        throw new Error('Bạn không có quyền xuất báo cáo chấm công (cần tài khoản Quản lý CSVC hoặc Quản trị viên).');
      }
      let errDetail = '';
      try {
        const errJson = await response.json();
        errDetail = errJson.message || errJson.error || '';
      } catch (_) {
        try {
          errDetail = await response.text();
        } catch (_) {}
      }
      throw new Error(errDetail || `Không thể tải file báo cáo chấm công (Mã lỗi ${response.status}).`);
    }

    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;

    // Lấy tên file từ header Content-Disposition nếu có
    let filename = `Bang_Cham_Cong_Bao_Ve_Thang_${String(month).padStart(2, '0')}_${year}.xlsx`;
    const disposition = response.headers.get('content-disposition');
    if (disposition && disposition.includes('filename=')) {
      const match = disposition.match(/filename="?([^"]+)"?/);
      if (match && match[1]) {
        filename = decodeURIComponent(match[1]);
      }
    }

    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(url);
  },

  // Shift Requests
  getShiftRequests: (params = {}) => {
    const query = new URLSearchParams();
    if (params.status) query.append('status', params.status);
    if (params.type) query.append('type', params.type);
    const queryString = query.toString() ? `?${query.toString()}` : '';
    return apiGet(`/api/guard-shift-requests${queryString}`);
  },

  getMyShiftRequests: () => apiGet('/api/guard-shift-requests/my-requests'),

  createShiftRequest: (data) => apiPost('/api/guard-shift-requests', data),

  approveShiftRequest: (id, data = {}) => apiPut(`/api/guard-shift-requests/${id}/approve`, data),

  rejectShiftRequest: (id, data = {}) => apiPut(`/api/guard-shift-requests/${id}/reject`, data),

  // Guard Teams
  getTeams: (status = 'ACTIVE') => {
    const query = status ? `?status=${encodeURIComponent(status)}` : '';
    return apiGet(`/api/guard-teams${query}`);
  },

  getTeamById: (id) => apiGet(`/api/guard-teams/${id}`),

  createTeam: (data) => apiPost('/api/guard-teams', data),

  updateTeam: (id, data) => apiPut(`/api/guard-teams/${id}`, data),

  deleteTeam: (id) => apiDelete(`/api/guard-teams/${id}`),

  assignTeamMembers: (id, data) => apiPut(`/api/guard-teams/${id}/members`, data),

  getUnassignedGuards: () => apiGet('/api/guard-teams/unassigned-guards'),

  // Temporary Guard Dispatches (Điều động tăng cường sự kiện)
  getDispatches: (params = {}) => {
    const query = new URLSearchParams();
    if (params.teamId) query.append('teamId', params.teamId);
    if (params.date) query.append('date', params.date);
    if (params.startDate) query.append('startDate', params.startDate);
    if (params.endDate) query.append('endDate', params.endDate);
    const queryString = query.toString() ? `?${query.toString()}` : '';
    return apiGet(`/api/guard-teams/dispatches${queryString}`);
  },

  getAvailableDispatchGuards: (params = {}) => {
    const query = new URLSearchParams();
    if (params.toTeamId) query.append('toTeamId', params.toTeamId);
    if (params.startDate) query.append('startDate', params.startDate);
    if (params.endDate) query.append('endDate', params.endDate);
    if (params.shiftType && params.shiftType !== 'ALL') query.append('shiftType', params.shiftType);
    const queryString = query.toString() ? `?${query.toString()}` : '';
    return apiGet(`/api/guard-teams/dispatches/available-guards${queryString}`);
  },

  createDispatches: (data) => apiPost('/api/guard-teams/dispatches', data),

  cancelDispatch: (id) => apiDelete(`/api/guard-teams/dispatches/${id}`),
};
