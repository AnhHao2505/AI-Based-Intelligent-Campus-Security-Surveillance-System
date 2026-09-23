import React, { useState, useEffect, useMemo } from 'react';
import {
  X,
  AlertCircle,
  CheckCircle2,
  Building2,
  Calendar,
  ArrowRight
} from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';

export default function StaffingWizardModal({
  isOpen,
  onClose,
  guards = [],
  teams = [],
  areas = [],
  buildings = [],
  currentWeekMonday,
  onSuccess
}) {
  const [selectedTeamId, setSelectedTeamId] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [building, setBuilding] = useState('');

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  // Lấy danh sách building từ prop buildings (object/string) và areas
  const availableBuildings = useMemo(() => {
    const map = new Map();
    (buildings || []).forEach((b) => {
      if (typeof b === 'object' && b !== null) {
        if (b.code) map.set(b.code, b.name ? `${b.name} (${b.code})` : b.code);
      } else if (typeof b === 'string' && b.trim()) {
        map.set(b.trim(), b.trim());
      }
    });

    (areas || []).forEach((a) => {
      if (a.building && a.building.trim() && !map.has(a.building.trim())) {
        map.set(a.building.trim(), a.building.trim());
      }
    });

    if (map.size === 0) {
      map.set('FPT_AROUND', 'Khuôn viên Ngoài trời & Sảnh (FPT_AROUND)');
      map.set('TOA_ALPHA', 'Tòa Alpha - Giảng đường chính (TOA_ALPHA)');
      map.set('TOA_BETA', 'Tòa Beta - Phòng Lab & Kỹ thuật (TOA_BETA)');
      map.set('KHU_THE_THAO', 'Khu Thể Thao & Sân Bóng (KHU_THE_THAO)');
    }

    return Array.from(map.entries()).map(([code, label]) => ({ code, label }));
  }, [areas, buildings]);

  useEffect(() => {
    if (!building && availableBuildings.length > 0) {
      const defaultB = availableBuildings.find(b => b.code === 'FPT_AROUND') || availableBuildings[0];
      setBuilding(defaultB.code);
    }
  }, [availableBuildings, building]);

  // Helper tính ngày Thứ 2 & CN tuần này, tuần tới
  const thisMondayStr = useMemo(() => {
    const d = currentWeekMonday ? new Date(currentWeekMonday) : new Date();
    const day = d.getDay();
    const diffToMon = d.getDate() - (day === 0 ? 6 : day - 1);
    const mon = new Date(d);
    mon.setDate(diffToMon);
    const y = mon.getFullYear();
    const m = String(mon.getMonth() + 1).padStart(2, '0');
    const dt = String(mon.getDate()).padStart(2, '0');
    return `${y}-${m}-${dt}`;
  }, [currentWeekMonday]);

  const thisSundayStr = useMemo(() => {
    if (!thisMondayStr) return '';
    const mon = new Date(thisMondayStr);
    const sun = new Date(mon);
    sun.setDate(mon.getDate() + 6);
    const y = sun.getFullYear();
    const m = String(sun.getMonth() + 1).padStart(2, '0');
    const dt = String(sun.getDate()).padStart(2, '0');
    return `${y}-${m}-${dt}`;
  }, [thisMondayStr]);

  const nextMondayStr = useMemo(() => {
    const d = currentWeekMonday ? new Date(currentWeekMonday) : new Date();
    const day = d.getDay();
    const diffToNextMon = day === 0 ? 1 : 8 - day;
    const nextMon = new Date(d);
    nextMon.setDate(d.getDate() + diffToNextMon);
    const y = nextMon.getFullYear();
    const m = String(nextMon.getMonth() + 1).padStart(2, '0');
    const dt = String(nextMon.getDate()).padStart(2, '0');
    return `${y}-${m}-${dt}`;
  }, [currentWeekMonday]);

  const nextSundayStr = useMemo(() => {
    if (!nextMondayStr) return '';
    const mon = new Date(nextMondayStr);
    const sun = new Date(mon);
    sun.setDate(mon.getDate() + 6);
    const y = sun.getFullYear();
    const m = String(sun.getMonth() + 1).padStart(2, '0');
    const dt = String(sun.getDate()).padStart(2, '0');
    return `${y}-${m}-${dt}`;
  }, [nextMondayStr]);

  // Khởi tạo ngày bắt đầu, kết thúc và tự động chọn đội đầu tiên khi mở modal
  useEffect(() => {
    if (isOpen) {
      setError(null);
      setResult(null);
      setStartDate(thisMondayStr);
      setEndDate(thisSundayStr);

      if (teams && teams.length > 0) {
        const defaultTeam = teams[0];
        setSelectedTeamId(defaultTeam.id);
      }
    }
  }, [isOpen, thisMondayStr, thisSundayStr, teams]);

  // Đội bảo vệ đang được chọn
  const activeTeam = useMemo(() => {
    return (teams || []).find((t) => t.id === selectedTeamId) || null;
  }, [teams, selectedTeamId]);

  // Danh sách thành viên của đội đang chọn
  const activeTeamMembers = useMemo(() => {
    if (!activeTeam) return [];
    if (Array.isArray(activeTeam.members) && activeTeam.members.length > 0) {
      return activeTeam.members;
    }
    return guards.filter(
      (g) => g.team?.id === activeTeam.id || g.teamId === activeTeam.id
    );
  }, [activeTeam, guards]);

  const handleSelectTeam = (teamId) => {
    setSelectedTeamId(teamId);
  };

  const handleStartDateChange = (val) => {
    setStartDate(val);
    if (!endDate || val > endDate) {
      const startD = new Date(val);
      const endD = new Date(startD);
      endD.setDate(startD.getDate() + 6);
      const y = endD.getFullYear();
      const m = String(endD.getMonth() + 1).padStart(2, '0');
      const d = String(endD.getDate()).padStart(2, '0');
      setEndDate(`${y}-${m}-${d}`);
    }
  };

  const totalDays = useMemo(() => {
    if (!startDate || !endDate) return 0;
    const d1 = new Date(startDate);
    const d2 = new Date(endDate);
    const diffTime = d2 - d1;
    const days = Math.round(diffTime / (1000 * 60 * 60 * 24)) + 1;
    return days > 0 ? days : 0;
  }, [startDate, endDate]);

  const teamMemberCount = activeTeamMembers.length;

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!selectedTeamId) {
      setError('Vui lòng chọn một đội bảo vệ để gán lịch trực');
      return;
    }
    if (teamMemberCount === 0) {
      setError('Đội bảo vệ đã chọn chưa có thành viên nào. Vui lòng phân bổ quân số cho đội trước khi tạo lịch trực.');
      return;
    }
    if (!startDate) {
      setError('Vui lòng chọn ngày bắt đầu');
      return;
    }
    if (!endDate) {
      setError('Vui lòng chọn ngày kết thúc');
      return;
    }
    if (endDate < startDate) {
      setError('Ngày kết thúc phải lớn hơn hoặc bằng ngày bắt đầu');
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const selectedMemberIds = activeTeamMembers.map((m) => m.id);

      const payload = {
        teamId: selectedTeamId,
        startDate: startDate,
        endDate: endDate,
        morningDemand: 3,
        afternoonDemand: 4,
        nightDemand: 2,
        hasSundayCustom: true,
        hasWeekendCustom: true,
        sundayMorningDemand: 2,
        weekendMorningDemand: 2,
        sundayAfternoonDemand: 2,
        weekendAfternoonDemand: 2,
        sundayNightDemand: 2,
        weekendNightDemand: 2,
        selectedGuardIds: selectedMemberIds,
        memberGuardIds: selectedMemberIds,
        building: building || 'FPT_AROUND',
        saveAsTemplate: false
      };

      const res = await guardScheduleApi.generateShiftsFromWizard(payload);
      setResult(res);
      if (onSuccess) {
        onSuccess();
      }
    } catch (err) {
      console.error('Lỗi khi gán lịch trực cho đội:', err);
      setError(err.message || 'Không thể tạo lịch trực');
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="schedule-modal-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget && !loading) onClose();
      }}
    >
      <div className="schedule-modal schedule-modal--md max-w-xl">
        {/* HEADER */}
        <div className="schedule-modal__header">
          <div className="schedule-modal__header-left">
            <div className="schedule-modal__icon-badge">
              <Calendar size={18} />
            </div>
            <div className="schedule-modal__header-text">
              <h3 className="schedule-modal__title">
                Gán Lịch Trực Cho Đội Bảo Vệ
              </h3>
              <p className="schedule-modal__subtitle">
                Tự động phân bổ ca trực theo khoảng thời gian cho Đội bảo vệ
              </p>
            </div>
          </div>
          <button
            type="button"
            className="schedule-modal__close-btn"
            onClick={onClose}
            disabled={loading}
            aria-label="Đóng"
          >
            <X size={18} />
          </button>
        </div>

        {/* SUCCESS RESULT SCREEN */}
        {result ? (
          <div className="schedule-modal__body p-6 text-center space-y-4">
            <div className="w-12 h-12 rounded-full bg-emerald-100 dark:bg-emerald-950/80 text-emerald-600 dark:text-emerald-400 mx-auto flex items-center justify-center">
              <CheckCircle2 size={28} />
            </div>
            <div>
              <h4 className="text-base font-bold text-slate-800 dark:text-slate-100">
                Phân Lịch Trực Cho Đội Thành Công!
              </h4>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 max-w-md mx-auto">
                Đã tạo thành công {Array.isArray(result) ? result.length : 'toàn bộ'} ca trực cho <strong>{activeTeam?.teamName}</strong> từ <strong>{startDate}</strong> đến <strong>{endDate}</strong>.
              </p>
            </div>
            <div className="pt-2">
              <button
                type="button"
                onClick={onClose}
                className="schedule-btn-primary px-6 py-2 text-xs font-bold"
              >
                Hoàn Tất & Xem Lịch Trực
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="schedule-modal__body space-y-4 p-5">
              {/* ERROR ALERT */}
              {error && (
                <div className="p-3 bg-rose-50 dark:bg-rose-950/50 border border-rose-200 dark:border-rose-800/80 rounded-xl text-xs text-rose-700 dark:text-rose-300 flex items-start gap-2">
                  <AlertCircle size={16} className="shrink-0 mt-0.5 text-rose-600" />
                  <span>{error}</span>
                </div>
              )}

              {/* 1. CHỌN ĐỘI BẢO VỆ */}
              <div className="space-y-2">
                <label className="block text-xs font-bold text-slate-700 dark:text-slate-300">
                  1. Chọn Đội Bảo Vệ Phụ Trách <span className="text-rose-500">*</span>
                </label>
                <select
                  value={selectedTeamId}
                  onChange={(e) => handleSelectTeam(e.target.value)}
                  required
                  className="w-full px-3 py-2 text-xs font-bold rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                >
                  <option value="">-- Chọn đội bảo vệ để phân lịch --</option>
                  {teams.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.teamName} — ({t.members?.length || 0} thành viên)
                    </option>
                  ))}
                </select>

                {/* THẺ TÓM TẮT ĐỘI ĐÃ CHỌN (KHÔNG HIỂN THỊ DANH SÁCH TÊN TỪNG NGƯỜI) */}
                {activeTeam ? (
                  <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700/80">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span
                          className="w-3 h-3 rounded-full shrink-0"
                          style={{ backgroundColor: activeTeam.colorCode || '#2563eb' }}
                        />
                        <span className="text-xs font-bold text-slate-800 dark:text-slate-100">
                          {activeTeam.teamName}
                        </span>
                      </div>
                      <span className="px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-blue-50 dark:bg-blue-950/70 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800">
                        {teamMemberCount} thành viên
                      </span>
                    </div>

                    {teamMemberCount === 0 && (
                      <div className="mt-2 text-[11px] text-amber-600 dark:text-amber-400 italic flex items-center gap-1.5">
                        <AlertCircle size={13} />
                        <span>Đội này chưa có thành viên. Vui lòng vào tab <strong>Đội Bảo Vệ</strong> để gán quân số.</span>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="p-3 bg-slate-50 dark:bg-slate-800/40 border border-dashed border-slate-200 dark:border-slate-700 rounded-xl text-xs text-slate-400 text-center">
                    Vui lòng chọn một đội bảo vệ ở trên để áp dụng lịch trực
                  </div>
                )}
              </div>

              {/* 2. CHỌN THỜI GIAN TRỰC: NGÀY BẮT ĐẦU & KẾT THÚC */}
              <div className="space-y-2">
                <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 flex items-center gap-1">
                  <Calendar size={13} className="text-blue-500" />
                  <span>2. Thời Gian Trực <span className="text-rose-500">*</span></span>
                </label>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <span className="block text-[11px] text-slate-500 dark:text-slate-400 mb-1 font-medium">
                      Ngày bắt đầu
                    </span>
                    <input
                      type="date"
                      value={startDate}
                      onChange={(e) => handleStartDateChange(e.target.value)}
                      required
                      className="w-full px-3 py-2 text-xs font-semibold rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                  <div>
                    <span className="block text-[11px] text-slate-500 dark:text-slate-400 mb-1 font-medium">
                      Ngày kết thúc
                    </span>
                    <input
                      type="date"
                      value={endDate}
                      min={startDate}
                      onChange={(e) => setEndDate(e.target.value)}
                      required
                      className="w-full px-3 py-2 text-xs font-semibold rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-2 pt-0.5">
                  <div className="flex items-center gap-1.5">
                    <span className="text-[11px] text-slate-400">Chọn nhanh:</span>
                    <button
                      type="button"
                      onClick={() => {
                        setStartDate(thisMondayStr);
                        setEndDate(thisSundayStr);
                      }}
                      className={`text-[10px] px-2.5 py-0.5 rounded font-medium border transition ${
                        startDate === thisMondayStr && endDate === thisSundayStr
                          ? 'bg-blue-50 dark:bg-blue-950/60 text-blue-700 dark:text-blue-300 border-blue-300 dark:border-blue-700 font-bold'
                          : 'bg-slate-50 dark:bg-slate-800 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100'
                      }`}
                    >
                      Tuần này
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setStartDate(nextMondayStr);
                        setEndDate(nextSundayStr);
                      }}
                      className={`text-[10px] px-2.5 py-0.5 rounded font-medium border transition ${
                        startDate === nextMondayStr && endDate === nextSundayStr
                          ? 'bg-blue-50 dark:bg-blue-950/60 text-blue-700 dark:text-blue-300 border-blue-300 dark:border-blue-700 font-bold'
                          : 'bg-slate-50 dark:bg-slate-800 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100'
                      }`}
                    >
                      Tuần tới
                    </button>
                  </div>
                  {startDate && endDate && (
                    <div className="text-[11px] text-slate-500 dark:text-slate-400 flex items-center gap-1">
                      <span>{startDate}</span>
                      <ArrowRight size={10} />
                      <span>{endDate}</span>
                      <span className="font-semibold text-blue-600 dark:text-blue-400">
                        ({totalDays} ngày)
                      </span>
                    </div>
                  )}
                </div>
              </div>

              {/* 3. CHỌN TÒA NHÀ / CƠ SỞ PHỤ TRÁCH */}
              <div className="space-y-1.5">
                <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 flex items-center gap-1">
                  <Building2 size={13} className="text-blue-500" />
                  <span>3. Tòa Nhà / Cơ Sở Phụ Trách <span className="text-rose-500">*</span></span>
                </label>
                <select
                  value={building}
                  onChange={(e) => setBuilding(e.target.value)}
                  required
                  className="w-full px-3 py-2 text-xs font-semibold rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                >
                  {availableBuildings.map((b) => (
                    <option key={b.code} value={b.code}>
                      {b.label}
                    </option>
                  ))}
                </select>
                <p className="text-[10px] text-slate-400">
                  Cơ sở phân công trực tiếp để nhận cảnh báo AI Camera và kiểm soát an ninh
                </p>
              </div>
            </div>

            {/* MODAL FOOTER */}
            <div className="schedule-modal__footer flex items-center justify-end gap-2 p-4 border-t border-slate-100 dark:border-slate-800">
              <button
                type="button"
                onClick={onClose}
                disabled={loading}
                className="schedule-btn-secondary"
              >
                Hủy
              </button>
              <button
                type="submit"
                disabled={loading || !selectedTeamId || teamMemberCount === 0}
                className="schedule-btn-primary"
              >
                {loading ? 'Đang Tạo Lịch...' : `Gán Lịch Cho ${activeTeam?.teamName || 'Đội Bảo Vệ'}`}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
