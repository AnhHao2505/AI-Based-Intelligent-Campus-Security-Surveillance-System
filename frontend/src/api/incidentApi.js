import { apiGet, apiPost } from './apiClient';

export const incidentApi = {
  getActiveIncidents: (building) => {
    const query = building ? `?building=${encodeURIComponent(building)}` : '';
    return apiGet(`/api/incidents/active${query}`);
  },

  getIncidents: (building, status) => {
    const query = new URLSearchParams();
    if (building) query.append('building', building);
    if (status) query.append('status', status);
    const queryString = query.toString() ? `?${query.toString()}` : '';
    return apiGet(`/api/incidents${queryString}`);
  },

  claimIncident: (id) => apiPost(`/api/incidents/${id}/claim`),

  resolveIncident: (id, data) => apiPost(`/api/incidents/${id}/resolve`, data),
};
