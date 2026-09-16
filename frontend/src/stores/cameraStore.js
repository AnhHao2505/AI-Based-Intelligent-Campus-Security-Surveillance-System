import { create } from 'zustand';

export const useCameraStore = create((set) => ({
  search: '',
  status: '',
  operationalStatus: '',
  page: 0,
  isCreateOpen: false,
  setFilters: (filters) => set({ ...filters, page: 0 }),
  setSearch: (search) => set({ search }),
  setPage: (page) => set({ page }),
  setCreateOpen: (isCreateOpen) => set({ isCreateOpen }),
}));