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
};
