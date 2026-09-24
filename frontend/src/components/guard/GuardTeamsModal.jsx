import React, { useState, useEffect } from 'react';
import { X, Plus, Users, Check, AlertCircle, Trash2, Edit2, Shield } from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';

export default function GuardTeamsModal({
  isOpen,
  onClose,
  allGuards = [],
  onTeamsUpdated
}) {
  const [teams, setTeams] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Tab: 'LIST' | 'CREATE' | 'MEMBERS'
  const [activeTab, setActiveTab] = useState('LIST');
  const [selectedTeam, setSelectedTeam] = useState(null);
  const [teamMembers, setTeamMembers] = useState([]);
  const [unassignedGuards, setUnassignedGuards] = useState([]);

  // New team form
  const [teamForm, setTeamForm] = useState({
    teamName: '',
    description: '',
    colorCode: '#2563eb'
  });

  const fetchTeams = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await guardScheduleApi.getTeams();
      setTeams(Array.isArray(res) ? res : []);
    } catch (err) {
      console.error('Lỗi tải danh sách đội bảo vệ:', err);
      setError(err.message || 'Không thể tải danh sách đội');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchTeams();
      setActiveTab('LIST');
      setSelectedTeam(null);
    }
  }, [isOpen]);

  // Open member manager for a team
  const handleOpenMembers = async (team) => {
    setSelectedTeam(team);
    setActiveTab('MEMBERS');
    setLoading(true);
    try {
      // Find guards currently in this team from allGuards
      const current = allGuards.filter((g) => g.team?.id === team.id || g.teamId === team.id);
      setTeamMembers(current.map((g) => g.id));

      const unassigned = await guardScheduleApi.getUnassignedGuards();
      setUnassignedGuards(Array.isArray(unassigned) ? unassigned : []);
    } catch (err) {
      console.error('Lỗi tải danh sách nhân viên đội:', err);
    } finally {
      setLoading(false);
    }
  };

  // Toggle member selection
  const handleToggleMember = (guardId) => {
    setTeamMembers((prev) =>
      prev.includes(guardId) ? prev.filter((id) => id !== guardId) : [...prev, guardId]
    );
  };

  // Save team members
  const handleSaveMembers = async () => {
    if (!selectedTeam) return;
    setLoading(true);
    try {
      await guardScheduleApi.assignTeamMembers(selectedTeam.id, {
        guardIds: teamMembers
      });
      await fetchTeams();
      if (onTeamsUpdated) onTeamsUpdated();
      setActiveTab('LIST');
      setSelectedTeam(null);
    } catch (err) {
      alert(err.message || 'Lỗi khi gán thành viên cho đội');
    } finally {
      setLoading(false);
    }
  };

  // Create team submit
  const handleCreateTeam = async (e) => {
    e.preventDefault();
    if (!teamForm.teamName.trim()) {
      alert('Vui lòng nhập tên đội bảo vệ');
      return;
    }

    setLoading(true);
    try {
      await guardScheduleApi.createTeam({
        teamName: teamForm.teamName.trim(),
        description: teamForm.description.trim(),
        colorCode: teamForm.colorCode
      });
      setTeamForm({ teamName: '', description: '', colorCode: '#2563eb' });
      await fetchTeams();
      if (onTeamsUpdated) onTeamsUpdated();
      setActiveTab('LIST');
    } catch (err) {
      alert(err.message || 'Lỗi khi tạo đội bảo vệ');
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="schedule-modal-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget && !loading) {
          onClose();
        }
      }}
    >
      <div className="schedule-modal schedule-modal--lg max-w-3xl">
        <div className="schedule-modal__header">
          <div className="schedule-modal__header-left">
            <div className="schedule-modal__icon-badge">
              <Users size={18} />
            </div>
            <div className="schedule-modal__header-text">
              <h3 className="schedule-modal__title">Quản Lý Đội Bảo Vệ</h3>
              <p className="schedule-modal__subtitle">
                Tổ chức phân nhóm nhân sự bảo vệ phục vụ phân ca theo nhu cầu
              </p>
            </div>
          </div>
          <button
            type="button"
            className="schedule-modal__close-btn"
            onClick={onClose}
            aria-label="Đóng"
          >
            <X size={18} />
          </button>
        </div>

        {/* Tab Controls */}
        <div className="px-6 pt-3 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between">
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setActiveTab('LIST')}
              className={`pb-2.5 px-3 text-xs font-bold border-b-2 transition ${
                activeTab === 'LIST'
                  ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                  : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'
              }`}
            >
              Danh sách đội ({teams.length})
            </button>
            {activeTab === 'MEMBERS' && (
              <button
                type="button"
                className="pb-2.5 px-3 text-xs font-bold border-b-2 border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400"
              >
                Phân bổ thành viên: {selectedTeam?.teamName}
              </button>
            )}
          </div>

          {activeTab === 'LIST' && (
            <button
              type="button"
              onClick={() => setActiveTab('CREATE')}
              className="text-xs font-bold text-blue-600 dark:text-blue-400 hover:underline flex items-center gap-1 pb-2.5"
            >
              <Plus size={14} /> Thêm Đội Mới
            </button>
          )}
        </div>

        <div className="schedule-modal__body max-h-[60vh] overflow-y-auto">
          {error && (
            <div className="p-3 mb-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-300 dark:border-rose-800 rounded-xl text-rose-800 dark:text-rose-200 text-xs flex items-center gap-2">
              <AlertCircle size={15} />
              <span>{error}</span>
            </div>
          )}

          {/* TAB 1: LIST TEAMS */}
          {activeTab === 'LIST' && (
            <div className="space-y-3">
              {teams.length === 0 ? (
                <div className="text-center py-10 text-xs text-slate-400">
                  Chưa có đội bảo vệ nào. Nhấn "Thêm Đội Mới" để tạo đội đầu tiên.
                </div>
              ) : (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {teams.map((t) => (
                    <div
                      key={t.id}
                      className="p-4 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 space-y-2 flex flex-col justify-between"
                    >
                      <div>
                        <div className="flex items-center justify-between">
                          <span className="font-bold text-sm text-slate-900 dark:text-white flex items-center gap-2">
                            <span
                              className="w-3 h-3 rounded-full"
                              style={{ backgroundColor: t.colorCode || '#2563eb' }}
                            />
                            {t.teamName}
                          </span>
                          <span className="px-2 py-0.5 rounded text-[11px] font-bold bg-blue-50 dark:bg-blue-950 text-blue-700 dark:text-blue-300">
                            {t.memberCount || 0} thành viên
                          </span>
                        </div>
                        {t.description && (
                          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 line-clamp-2">
                            {t.description}
                          </p>
                        )}
                      </div>

                      <div className="pt-2 border-t border-slate-100 dark:border-slate-700/60 flex items-center justify-end gap-2">
                        <button
                          type="button"
                          onClick={() => handleOpenMembers(t)}
                          className="px-3 py-1.5 rounded-lg text-xs font-semibold bg-slate-100 hover:bg-slate-200 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 transition"
                        >
                          Phân Bổ Nhân Sự
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* TAB 2: CREATE TEAM */}
          {activeTab === 'CREATE' && (
            <form onSubmit={handleCreateTeam} className="space-y-4">
              <div>
                <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1">
                  Tên Đội Bảo Vệ <span className="text-rose-500">*</span>
                </label>
                <input
                  type="text"
                  placeholder="Ví dụ: Đội 1 (Ca Sáng - Chiều), Đội An Ninh Cơ Động..."
                  value={teamForm.teamName}
                  onChange={(e) => setTeamForm((prev) => ({ ...prev, teamName: e.target.value }))}
                  required
                  className="w-full px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1">
                  Mô Tả / Khu Vực Phụ Trách
                </label>
                <textarea
                  rows={3}
                  placeholder="Mô tả phạm vi hoặc tính chất của đội..."
                  value={teamForm.description}
                  onChange={(e) => setTeamForm((prev) => ({ ...prev, description: e.target.value }))}
                  className="w-full px-3 py-2 text-xs rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1">
                  Màu Đại Diện
                </label>
                <div className="flex items-center gap-3">
                  <input
                    type="color"
                    value={teamForm.colorCode}
                    onChange={(e) => setTeamForm((prev) => ({ ...prev, colorCode: e.target.value }))}
                    className="w-10 h-8 rounded border border-slate-300 cursor-pointer"
                  />
                  <span className="text-xs font-mono text-slate-500">{teamForm.colorCode}</span>
                </div>
              </div>

              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setActiveTab('LIST')}
                  className="schedule-btn-modal schedule-btn-modal--cancel"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={loading}
                  className="schedule-btn-modal schedule-btn-modal--submit"
                >
                  {loading ? 'Đang Tạo...' : 'Tạo Đội Mới'}
                </button>
              </div>
            </form>
          )}

          {/* TAB 3: MEMBERS ASSIGNMENT */}
          {activeTab === 'MEMBERS' && selectedTeam && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div className="text-xs text-slate-600 dark:text-slate-300">
                  Chọn nhân viên bảo vệ thuộc <strong>{selectedTeam.teamName}</strong>:
                </div>
                <div className="text-xs font-bold text-blue-600">
                  Đã chọn: {teamMembers.length} nhân viên
                </div>
              </div>

              <div className="border border-slate-200 dark:border-slate-700 rounded-xl divide-y divide-slate-100 dark:divide-slate-800 max-h-64 overflow-y-auto bg-white dark:bg-slate-900 p-2">
                {allGuards.map((guard) => {
                  const isChecked = teamMembers.includes(guard.id);
                  const guardCurrentTeam = guard.team?.teamName;
                  const isOtherTeam = guard.team && guard.team.id !== selectedTeam.id;

                  return (
                    <label
                      key={guard.id}
                      className="flex items-center justify-between p-2 hover:bg-slate-50 dark:hover:bg-slate-800/60 rounded-lg cursor-pointer transition text-xs"
                    >
                      <div className="flex items-center gap-2.5">
                        <input
                          type="checkbox"
                          checked={isChecked}
                          onChange={() => handleToggleMember(guard.id)}
                          className="rounded border-slate-300 text-blue-600 focus:ring-blue-500 h-4 w-4"
                        />
                        <div>
                          <div className="font-bold text-slate-800 dark:text-slate-100">
                            {guard.fullName}
                          </div>
                          <div className="text-[11px] text-slate-500 dark:text-slate-400 font-mono">
                            {guard.userCode || 'NV-BV'}
                          </div>
                        </div>
                      </div>

                      {guardCurrentTeam && (
                        <span
                          className={`px-2 py-0.5 rounded text-[10px] font-semibold ${
                            isOtherTeam
                              ? 'bg-amber-50 text-amber-700 border border-amber-200'
                              : 'bg-blue-50 text-blue-700 border border-blue-200'
                          }`}
                        >
                          {guardCurrentTeam}
                        </span>
                      )}
                    </label>
                  );
                })}
              </div>

              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setActiveTab('LIST');
                    setSelectedTeam(null);
                  }}
                  className="schedule-btn-modal schedule-btn-modal--cancel"
                >
                  Quay Lại
                </button>
                <button
                  type="button"
                  onClick={handleSaveMembers}
                  disabled={loading}
                  className="schedule-btn-modal schedule-btn-modal--submit"
                >
                  {loading ? 'Đang Lưu...' : 'Lưu Danh Sách Thành Viên'}
                </button>
              </div>
            </div>
          )}
        </div>

        <div className="schedule-modal__footer">
          <button
            type="button"
            onClick={onClose}
            className="schedule-btn-modal schedule-btn-modal--cancel"
          >
            Đóng
          </button>
        </div>
      </div>
    </div>
  );
}
