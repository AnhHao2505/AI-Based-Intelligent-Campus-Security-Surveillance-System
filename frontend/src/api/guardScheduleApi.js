import { apiGet, apiPost, apiPut, apiDelete } from './apiClient';

export const guardScheduleApi = {
  // Templates (Admin)
  getTemplates: (building) => {
    const query = building ? `?building=${encodeURIComponent(building)}` : '';
    return apiGet(`/api/guard-schedules/templates${query}`);
  },

  createTemplate: (data) => apiPost('/api/guard-schedules/templates', data),

  updateTemplate: (id, data) => apiPut(`/api/guard-schedules/templates/${id}`, data),

  deleteTemplate: (id) => apiDelete(`/api/guard-schedules/templates/${id}`),

  generateShifts: (data) => apiPost('/api/guard-schedules/generate', data),

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
