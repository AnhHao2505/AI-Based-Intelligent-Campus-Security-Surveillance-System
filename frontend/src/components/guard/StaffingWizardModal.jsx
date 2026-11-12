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
import Select from '../ui/Select';

export default function StaffingWizardModal({
  isOpen,
  onClose,
  guards = [],
  teams = [],
  areas = [],
  buildings = [],
  currentWeekMonday,
  defaultTeamId,
  onSuccess
}) {
  const [selectedTeamId, setSelectedTeamId] = useState('');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [building, setBuilding] = useState('');
  const [showCrossConfirm, setShowCrossConfirm] = useState(false);
  const [crossBuildingReason, setCrossBuildingReason] = useState('');

  const [weekdayMorningDemand, setWeekdayMorningDemand] = useState(2);
  const [weekdayAfternoonDemand, setWeekdayAfternoonDemand] = useState(2);
  const [weekdayNightDemand, setWeekdayNightDemand] = useState(2);
  const [sundayMorningDemand, setSundayMorningDemand] = useState(2);
  const [sundayAfternoonDemand, setSundayAfternoonDemand] = useState(2);
  const [sundayNightDemand, setSundayNightDemand] = useState(2);
  const [hasSundayCustom, setHasSundayCustom] = useState(true);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  // Lấy danh sách tên building từ prop buildings và areas
  const availableBuildings = useMemo(() => {
    const bSet = new Set();
    (buildings || []).forEach((b) => {
      if (typeof b === 'object' && b !== null && b.name) {
        bSet.add(b.name.trim());
      } else if (typeof b === 'string' && b.trim()) {
        bSet.add(b.trim());
      }
    });

    (areas || []).forEach((a) => {
      if (a.building && a.building.trim()) {
        bSet.add(a.building.trim());
      }
    });

    if (bSet.size === 0) {
      bSet.add('Tòa Alpha');
      bSet.add('Tòa Epsilon');
    }

    return Array.from(bSet).sort();
  }, [areas, buildings]);

  useEffect(() => {
    if (!building && availableBuildings.length > 0) {
      const defaultB = availableBuildings.find(b => b === 'Tòa Alpha') || availableBuildings[0];
      setBuilding(defaultB);
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
    const d = new Date(thisMondayStr);
    d.setDate(d.getDate() + 6);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dt = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${dt}`;
  }, [thisMondayStr]);

  const nextMondayStr = useMemo(() => {
    const d = new Date(thisMondayStr);
    d.setDate(d.getDate() + 7);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dt = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${dt}`;
  }, [thisMondayStr]);

  const nextSundayStr = useMemo(() => {
    const d = new Date(nextMondayStr);
    d.setDate(d.getDate() + 6);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dt = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${dt}`;
  }, [nextMondayStr]);

  useEffect(() => {
    if (isOpen) {
      setError(null);
      setResult(null);
      setShowCrossConfirm(false);
      setCrossBuildingReason('');

      // Gán ngày mặc định là Thứ 2 tuần này tới Chủ Nhật tuần này
      setStartDate(thisMondayStr);
      setEndDate(thisSundayStr);

      const targetId = defaultTeamId || (teams.length > 0 ? teams[0].id : '');
      if (targetId) {
        handleSelectTeam(targetId);
      }
    }
  }, [isOpen, defaultTeamId, teams, thisMondayStr, thisSundayStr]);

  const activeTeam = useMemo(() => {
    return teams.find((t) => t.id === selectedTeamId) || null;
  }, [teams, selectedTeamId]);

  // Tìm toà nhà chính quy của đội
  const teamHomeBuildingName = useMemo(() => {
    if (!activeTeam) return null;
    if (activeTeam.building && activeTeam.building.name) return activeTeam.building.name;
    if (activeTeam.buildingName) return activeTeam.buildingName;
    if (activeTeam.assignedArea) return activeTeam.assignedArea;
    return null;
  }, [activeTeam]);

  // Thành viên của đội
  const activeTeamMembers = useMemo(() => {
    if (!activeTeam) return [];
    if (activeTeam.members && activeTeam.members.length > 0) {
      return activeTeam.members;
    }
    return guards.filter((g) => g.teamId === activeTeam.id || g.team?.id === activeTeam.id);
  }, [activeTeam, guards]);

  const handleSelectTeam = (teamId) => {
    setSelectedTeamId(teamId);
    const team = teams.find((t) => t.id === teamId);
    if (team) {
      const bName = team.building?.name || team.buildingName || team.assignedArea;
      if (bName && availableBuildings.includes(bName)) {
        setBuilding(bName);
      }

      // Nạp cấu hình quân số định biên mẫu nếu có
      if (team.shiftPatternJson) {
        try {
          const pat = typeof team.shiftPatternJson === 'string'
            ? JSON.parse(team.shiftPatternJson)
            : team.shiftPatternJson;
          if (pat) {
            setWeekdayMorningDemand(pat.weekdayMorningDemand ?? 2);
            setWeekdayAfternoonDemand(pat.weekdayAfternoonDemand ?? 2);
            setWeekdayNightDemand(pat.weekdayNightDemand ?? 2);
            setSundayMorningDemand(pat.sundayMorningDemand ?? pat.weekendMorningDemand ?? 2);
            setSundayAfternoonDemand(pat.sundayAfternoonDemand ?? pat.weekendAfternoonDemand ?? 2);
            setSundayNightDemand(pat.sundayNightDemand ?? pat.weekendNightDemand ?? 2);
            setHasSundayCustom(pat.hasSundayCustom ?? pat.hasWeekendCustom ?? true);
          }
        } catch (e) {
          console.warn('Lỗi parse shiftPatternJson của đội:', e);
        }
      }
    }
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

    const isCross = Boolean(
      activeTeam &&
      teamHomeBuildingName &&
      building &&
      building.trim().toLowerCase() !== teamHomeBuildingName.trim().toLowerCase()
    );

    if (isCross) {
      setShowCrossConfirm(true);
      return;
    }

    doGenerate(false, null);
  };

  const doGenerate = async (confirmCross, reason) => {
    setLoading(true);
    setError(null);
    try {
      const selectedMemberIds = activeTeamMembers.map((m) => m.id);
      const matchedBld = (buildings || []).find((b) => b.name === building || b.id === building);

      const payload = {
        teamId: selectedTeamId,
        startDate: startDate,
        endDate: endDate,
        morningDemand: weekdayMorningDemand,
        afternoonDemand: weekdayAfternoonDemand,
        nightDemand: weekdayNightDemand,
        hasSundayCustom: hasSundayCustom,
        hasWeekendCustom: hasSundayCustom,
        sundayMorningDemand: sundayMorningDemand,
        weekendMorningDemand: sundayMorningDemand,
        sundayAfternoonDemand: sundayAfternoonDemand,
        weekendAfternoonDemand: sundayAfternoonDemand,
        sundayNightDemand: sundayNightDemand,
        weekendNightDemand: sundayNightDemand,
        selectedGuardIds: selectedMemberIds,
        memberGuardIds: selectedMemberIds,
        buildingId: matchedBld?.id || undefined,
        confirmCrossBuilding: confirmCross ? true : undefined,
        crossBuildingReason: confirmCross && reason && reason.trim() ? reason.trim() : undefined,
        building: building || 'Tòa Alpha'
      };

      const res = await guardScheduleApi.generateShiftsFromWizard(payload);

      // Cập nhật tòa nhà phụ trách vào thông tin Đội để hiển thị vị trí ở tab Quản lý Đội
      if (selectedTeamId && building) {
        try {
          const targetTeam = teams.find((t) => t.id === selectedTeamId);
          if (targetTeam) {
            await guardScheduleApi.updateTeam(selectedTeamId, {
              teamName: targetTeam.teamName,
              description: targetTeam.description,
              colorCode: targetTeam.colorCode,
              buildingName: building,
              assignedArea: building,
              buildingId: matchedBld?.id || targetTeam.buildingId
            });
          }
        } catch (updateErr) {
          console.warn('Lỗi cập nhật toà nhà cho đội sau khi gán lịch:', updateErr);
        }
      }

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
    // Popup có form nhập liệu → không đóng khi bấm ra ngoài, chỉ đóng bằng X / Huỷ.
    <div className="schedule-modal-backdrop">
      <div className="schedule-modal schedule-modal--lg max-w-2xl w-full">
        {/* HEADER */}
        <div className="schedule-modal__header px-6 py-5 border-b border-slate-100 dark:border-slate-800 bg-slate-50/70 dark:bg-slate-950/50 flex items-center justify-between">
          <div className="flex items-center gap-3.5">
            <div className="w-11 h-11 rounded-xl bg-orange-500/10 text-orange-600 dark:text-orange-400 flex items-center justify-center border border-orange-500/20 shadow-xs shrink-0">
              <Calendar size={22} />
            </div>
            <div className="flex flex-col gap-0.5">
              <h3 className="text-base sm:text-lg font-bold text-slate-900 dark:text-white leading-tight">
                Gán Lịch Trực Theo Đội
              </h3>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Phân ca tự động theo khoảng ngày
              </p>
            </div>
          </div>
          <button
            type="button"
            className="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 transition"
            onClick={onClose}
            disabled={loading}
            aria-label="Đóng"
          >
            <X size={18} />
          </button>
        </div>

        {/* SUCCESS RESULT SCREEN */}
        {result ? (
          <div className="p-8 text-center flex flex-col items-center gap-5">
            <div className="w-14 h-14 rounded-full bg-emerald-100 dark:bg-emerald-950 text-emerald-600 dark:text-emerald-400 flex items-center justify-center border border-emerald-200 dark:border-emerald-800 shadow-xs">
              <CheckCircle2 size={32} />
            </div>
            <div className="flex flex-col gap-1.5">
              <h4 className="text-base sm:text-lg font-bold text-slate-900 dark:text-slate-100">
                Phân Lịch Trực Cho Đội Thành Công!
              </h4>
              <p className="text-xs sm:text-sm text-slate-500 dark:text-slate-400 max-w-md mx-auto leading-relaxed">
                Đã tạo thành công {Array.isArray(result) ? result.length : 'toàn bộ'} ca trực cho <strong>{activeTeam?.teamName}</strong> từ <strong>{startDate}</strong> đến <strong>{endDate}</strong>.
              </p>
            </div>
            <div className="pt-2">
              <button
                type="button"
                onClick={onClose}
                className="px-6 py-2.5 text-xs font-bold rounded-xl text-white bg-[#f37021] hover:bg-[#e05e10] shadow-sm transition active:scale-95"
              >
                Hoàn Tất & Xem Lịch Trực
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col flex-1 min-h-0 overflow-hidden">
            <div className="px-6 sm:px-7 py-6 flex flex-col gap-6 flex-1 min-h-0 overflow-y-auto">
              {/* ERROR ALERT */}
              {error && (
                <div className="p-3.5 bg-rose-50 dark:bg-rose-950/50 border border-rose-200 dark:border-rose-800/80 rounded-xl text-xs text-rose-700 dark:text-rose-300 flex items-start gap-3 shadow-xs">
                  <AlertCircle size={18} className="shrink-0 mt-0.5 text-rose-600 dark:text-rose-400" />
                  <span className="leading-relaxed">{error}</span>
                </div>
              )}

              {/* 1. CHỌN ĐỘI BẢO VỆ */}
              <div className="flex flex-col gap-2.5">
                <label className="text-xs font-bold text-slate-700 dark:text-slate-300">
                  1. Đội bảo vệ <span className="text-rose-500">*</span>
                </label>
                <Select
                  value={selectedTeamId}
                  onChange={(e) => handleSelectTeam(e.target.value)}
                  required
                  options={[
                    { value: '', label: 'Chọn đội bảo vệ' },
                    ...teams.map((t) => ({ value: t.id, label: `${t.teamName} (${t.members?.length || 0} người)` })),
                  ]}
                />

                {/* THẺ TÓM TẮT ĐỘI ĐÃ CHỌN */}
                {activeTeam ? (
                  <div className="p-4 sm:p-4.5 rounded-xl bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700/80 shadow-xs flex flex-col gap-3">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2.5">
                        <span
                          className="w-3.5 h-3.5 rounded-full shrink-0 shadow-xs"
                          style={{ backgroundColor: activeTeam.colorCode || '#f37021' }}
                        />
                        <span className="text-sm font-bold text-slate-900 dark:text-slate-100">
                          {activeTeam.teamName}
                        </span>
                      </div>
                      <span className="px-3 py-1 rounded-full text-xs font-bold bg-blue-50 dark:bg-blue-950/70 text-blue-700 dark:text-blue-300 border border-blue-200 dark:border-blue-800">
                        {teamMemberCount} thành viên
                      </span>
                    </div>

                    <div className="flex flex-col gap-2 pt-2.5 border-t border-slate-200/80 dark:border-slate-700/80 text-xs">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1 p-2.5 rounded-lg bg-white dark:bg-slate-900/60 border border-slate-200/60 dark:border-slate-800">
                        <span className="font-semibold text-slate-700 dark:text-slate-300">
                          Nhu cầu T2 – T7:
                        </span>
                        <span className="font-medium text-slate-800 dark:text-slate-200">
                          {weekdayMorningDemand} Sáng • {weekdayAfternoonDemand} Chiều • {weekdayNightDemand} Đêm (<strong>{weekdayMorningDemand + weekdayAfternoonDemand + weekdayNightDemand} ca/ngày</strong>)
                        </span>
                      </div>
                      {hasSundayCustom && (
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1 p-2.5 rounded-lg bg-white dark:bg-slate-900/60 border border-slate-200/60 dark:border-slate-800">
                          <span className="font-semibold text-slate-700 dark:text-slate-300">
                            Nhu cầu Chủ Nhật:
                          </span>
                          <span className="font-medium text-slate-800 dark:text-slate-200">
                            {sundayMorningDemand} Sáng • {sundayAfternoonDemand} Chiều • {sundayNightDemand} Đêm (<strong>{sundayMorningDemand + sundayAfternoonDemand + sundayNightDemand} ca/ngày</strong>)
                          </span>
                        </div>
                      )}
                    </div>

                    {teamMemberCount === 0 && (
                      <div className="p-3 bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-900/60 rounded-lg text-xs text-amber-700 dark:text-amber-300 flex items-center gap-2">
                        <AlertCircle size={15} className="shrink-0" />
                        <span>Đội này chưa có thành viên. Vui lòng vào tab <strong>Đội Bảo Vệ</strong> để gán quân số.</span>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="p-4 bg-slate-50 dark:bg-slate-800/40 border border-dashed border-slate-200 dark:border-slate-700 rounded-xl text-xs text-slate-400 text-center">
                    Chọn một đội bảo vệ để xem định mức ca trực
                  </div>
                )}
              </div>

              {/* 2. CHỌN THỜI GIAN TRỰC: NGÀY BẮT ĐẦU & KẾT THÚC */}
              <div className="flex flex-col gap-3">
                <label className="text-xs font-bold text-slate-700 dark:text-slate-300 flex items-center gap-2">
                  <Calendar size={14} className="text-[#f37021]" />
                  <span>2. Thời gian trực <span className="text-rose-500">*</span></span>
                </label>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="flex flex-col gap-1.5">
                    <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold">
                      Ngày bắt đầu
                    </span>
                    <input
                      type="date"
                      value={startDate}
                      onChange={(e) => handleStartDateChange(e.target.value)}
                      required
                      className="w-full h-11 px-4 py-2 text-sm font-semibold rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-[#f37021] shadow-xs transition"
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold">
                      Ngày kết thúc
                    </span>
                    <input
                      type="date"
                      value={endDate}
                      min={startDate}
                      onChange={(e) => setEndDate(e.target.value)}
                      required
                      className="w-full h-11 px-4 py-2 text-sm font-semibold rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-[#f37021] shadow-xs transition"
                    />
                  </div>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-slate-400 font-medium">Chọn nhanh:</span>
                    <button
                      type="button"
                      onClick={() => {
                        setStartDate(thisMondayStr);
                        setEndDate(thisSundayStr);
                      }}
                      className={`text-xs px-3.5 py-1.5 rounded-lg font-semibold border transition shadow-xs ${
                        startDate === thisMondayStr && endDate === thisSundayStr
                          ? 'bg-orange-50 dark:bg-orange-950/60 text-[#f37021] dark:text-orange-400 border-orange-300 dark:border-orange-800 font-bold'
                          : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-700'
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
                      className={`text-xs px-3.5 py-1.5 rounded-lg font-semibold border transition shadow-xs ${
                        startDate === nextMondayStr && endDate === nextSundayStr
                          ? 'bg-orange-50 dark:bg-orange-950/60 text-[#f37021] dark:text-orange-400 border-orange-300 dark:border-orange-800 font-bold'
                          : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-700'
                      }`}
                    >
                      Tuần tới
                    </button>
                  </div>
                  {startDate && endDate && (
                    <div className="px-3 py-1.5 rounded-lg bg-orange-50/80 dark:bg-orange-950/50 border border-orange-200 dark:border-orange-900/60 text-xs font-semibold text-orange-700 dark:text-orange-300 flex items-center gap-1.5">
                      <span>{startDate}</span>
                      <ArrowRight size={12} />
                      <span>{endDate}</span>
                      <span className="font-bold">({totalDays} ngày)</span>
                    </div>
                  )}
                </div>
              </div>

              {/* 3. CHỌN TÒA NHÀ / CƠ SỞ PHỤ TRÁCH */}
              <div className="flex flex-col gap-2">
                <label className="text-xs font-bold text-slate-700 dark:text-slate-300 flex items-center gap-2">
                  <Building2 size={14} className="text-[#f37021]" />
                  <span>3. Tòa nhà phụ trách <span className="text-rose-500">*</span></span>
                </label>
                <Select
                  value={building}
                  onChange={(e) => setBuilding(e.target.value)}
                  required
                  options={availableBuildings.map((bName) => ({ value: bName, label: bName }))}
                />
              </div>
            </div>

            {/* MODAL FOOTER */}
            <div className="px-6 sm:px-7 py-4.5 border-t border-slate-100 dark:border-slate-800 bg-slate-50/60 dark:bg-slate-950/40 flex items-center justify-end gap-3">
              <button
                type="button"
                onClick={onClose}
                disabled={loading}
                className="px-4.5 py-2.5 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition"
              >
                Hủy
              </button>
              <button
                type="submit"
                disabled={loading || !selectedTeamId || teamMemberCount === 0}
                className="px-5 py-2.5 text-xs font-bold rounded-xl text-white bg-[#f37021] hover:bg-[#e05e10] active:scale-95 disabled:opacity-50 disabled:pointer-events-none shadow-xs transition flex items-center gap-2"
              >
                {loading ? 'Đang tạo lịch...' : `Gán lịch — ${activeTeam?.teamName || 'Đội bảo vệ'}`}
              </button>
            </div>
          </form>
        )}
      </div>

      {/* Hộp thoại xác nhận luân chuyển tòa nhà */}
      {showCrossConfirm && (
        <div className="fixed inset-0 z-[1200] bg-black/60 backdrop-blur-xs flex items-center justify-center p-4 overflow-y-auto">
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl max-w-lg w-full max-h-[calc(100dvh-2rem)] my-auto overflow-y-auto p-6 shadow-2xl flex flex-col gap-5 animate-in zoom-in-95">
            <div className="flex items-start gap-3.5">
              <div className="p-3 rounded-xl bg-amber-100 dark:bg-amber-950 text-amber-600 dark:text-amber-400 shrink-0 border border-amber-200 dark:border-amber-800">
                <AlertCircle size={24} />
              </div>
              <div className="flex flex-col gap-1">
                <h4 className="text-base font-bold text-slate-900 dark:text-white">
                  Xác nhận ca luân chuyển
                </h4>
                <p className="text-xs text-slate-600 dark:text-slate-300 leading-relaxed">
                  <strong>{activeTeam?.teamName}</strong> trực chính tại <strong>{teamHomeBuildingName}</strong>, nhưng lịch này xếp ở <strong>{building}</strong>. Các ca tạo ra sẽ được đánh dấu luân chuyển.
                </p>
              </div>
            </div>

            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-slate-700 dark:text-slate-300">
                Lý do (tùy chọn):
              </label>
              <input
                type="text"
                value={crossBuildingReason}
                onChange={(e) => setCrossBuildingReason(e.target.value)}
                placeholder="Tăng cường sự kiện, bù quân số..."
                className="w-full h-11 px-4 py-2 text-xs rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-[#f37021]"
              />
            </div>

            <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-100 dark:border-slate-800">
              <button
                type="button"
                onClick={() => setShowCrossConfirm(false)}
                className="px-4 py-2 rounded-xl text-xs font-semibold text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 transition"
              >
                Hủy
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowCrossConfirm(false);
                  doGenerate(true, crossBuildingReason);
                }}
                className="px-5 py-2 rounded-xl text-xs font-bold text-white bg-[#f37021] hover:bg-[#e05e10] transition flex items-center gap-2"
              >
                <span>Xác nhận</span>
                <ArrowRight size={14} />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
