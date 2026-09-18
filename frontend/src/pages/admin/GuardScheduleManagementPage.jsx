import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import {
  Calendar,
  ChevronLeft,
  ChevronRight,
  Plus,
  Wand2,
  Settings,
  AlertTriangle,
  Clock,
  Radio,
  MapPin,
  CheckCircle2,
  Trash2,
  Edit2,
  Users,
  Shield,
  X,
  Sun,
  Sunset,
  Moon,
  Search,
  Filter,
  RefreshCw,
  Building2,
  LayoutGrid,
  Check,
  AlertCircle
} from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';
import { getUsers } from '../../services/userService';
import { getAreas } from '../../services/areaService';
import { ROLES } from '../../constants/roles';
import '../../styles/GuardSchedulePage.css';

// Shift Type definitions
const SHIFT_TYPES = {
  SHIFT_MORNING: {
    label: 'Ca Sáng',
    time: '06:00 - 14:00',
    startTime: '06:00',
    endTime: '14:00',
    icon: Sun,
    cssClass: 'shift-morning'
  },
  SHIFT_AFTERNOON: {
    label: 'Ca Chiều',
    time: '14:00 - 22:00',
    startTime: '14:00',
    endTime: '22:00',
    icon: Sunset,
    cssClass: 'shift-afternoon'
  },
  SHIFT_NIGHT: {
    label: 'Ca Đêm',
    time: '22:00 - 06:00',
    startTime: '22:00',
    endTime: '06:00',
    icon: Moon,
    cssClass: 'shift-night'
  }
};

// Helper to format Date to YYYY-MM-DD in local time (prevents UTC offset issues)
const formatLocalDate = (date) => {
  if (!date) return '';
  const d = new Date(date);
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

export default function GuardScheduleManagementPage() {
  const [currentDate, setCurrentDate] = useState(new Date());
  const [selectedBuilding, setSelectedBuilding] = useState('ALL');
  const [searchKeyword, setSearchKeyword] = useState('');

  const [guards, setGuards] = useState([]);
  const [areas, setAreas] = useState([]);
  const [shifts, setShifts] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Modals
  const [showGenerateModal, setShowGenerateModal] = useState(false);
  const [showShiftModal, setShowShiftModal] = useState(false);
  const [showTemplateModal, setShowTemplateModal] = useState(false);
  const [showWarningModal, setShowWarningModal] = useState(false);
  const [editingShift, setEditingShift] = useState(null);
  const [generateResult, setGenerateResult] = useState(null);

  // New template inline form
  const [newTemplate, setNewTemplate] = useState({
    dayOfWeek: 2, // Monday
    guardId: '',
    shiftType: 'SHIFT_MORNING',
    startTime: '06:00',
    endTime: '14:00',
    areaId: '',
    radioChannel: 'Kênh 1 - Phòng Camera',
    building: 'CO_SO_HCM'
  });

  // Calculate Monday to Sunday of the active week in local time
  const weekDays = useMemo(() => {
    const curr = new Date(currentDate);
    const day = curr.getDay(); // 0 is Sun, 1 is Mon
    const diffToMonday = curr.getDate() - (day === 0 ? 6 : day - 1);
    const monday = new Date(curr);
    monday.setDate(diffToMonday);
    monday.setHours(0, 0, 0, 0);

    const days = [];
    for (let i = 0; i < 7; i++) {
      const d = new Date(monday);
      d.setDate(monday.getDate() + i);
      days.push(d);
    }
    return days;
  }, [currentDate]);

  const startDateStr = formatLocalDate(weekDays[0]);
  const endDateStr = formatLocalDate(weekDays[6]);
  const todayStr = formatLocalDate(new Date());

  // Fetch all schedule data
  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // 1. Fetch system users and extract active guards
      const usersRes = await getUsers({ accountType: 'SYSTEM', isActive: true, size: 100 });
      const pageObj = usersRes?.users || usersRes;
      const allUsers = Array.isArray(pageObj?.content)
        ? pageObj.content
        : Array.isArray(usersRes?.content)
        ? usersRes.content
        : Array.isArray(usersRes)
        ? usersRes
        : [];

      let guardList = allUsers.filter(
        (u) =>
          u.isActive !== false &&
          (u.role === ROLES.GUARD ||
           u.role === 'GUARD' ||
           u.role === 'INTERNAL_GUARD' ||
           u.role === 'OUTSOURCED_GUARD')
      );

      // Fallback demo guards ONLY if system has no users endpoint response at all (offline demo mode)
      if (guardList.length === 0 && !pageObj?.content && !Array.isArray(usersRes)) {
        guardList = [
          {
            id: 'fa744a70-3db2-434d-a6c3-6c00a452578c',
            fullName: 'Nguyễn Văn An (Bảo Vệ)',
            userCode: 'NV-BV01',
            email: 'guard.an@fpt.edu.vn',
            role: 'GUARD'
          },
          {
            id: '84cc1997-bae7-4a88-a20e-519b857cb722',
            fullName: 'Bảo Vệ Demo',
            userCode: 'NV-BV02',
            email: 'guard.demo@fpt.edu.vn',
            role: 'GUARD'
          }
        ];
      }
      setGuards(guardList);

      // 2. Fetch areas
      const areasRes = await getAreas({ size: 100 });
      let areaList = Array.isArray(areasRes?.content)
        ? areasRes.content
        : Array.isArray(areasRes?.areas)
        ? areasRes.areas
        : Array.isArray(areasRes)
        ? areasRes
        : [];

      if (areaList.length === 0) {
        areaList = [
          { id: 'area-1', name: 'Chốt Cổng Chính & Bãi Xe', building: 'CO_SO_HCM' },
          { id: 'area-2', name: 'Phòng Điều Khiển Camera & Trực Ban', building: 'CO_SO_HCM' },
          { id: 'area-3', name: 'Sảnh Chính Tầng Trệt', building: 'CO_SO_HCM' },
          { id: 'area-4', name: 'Tuần Tra Hành Lang & Các Tầng', building: 'CO_SO_HCM' }
        ];
      }
      setAreas(areaList);

      // 3. Fetch shifts for this week
      const shiftsRes = await guardScheduleApi.getShifts({
        startDate: startDateStr,
        endDate: endDateStr,
        building: (selectedBuilding === 'ALL' || selectedBuilding === 'CO_SO_HCM') ? undefined : selectedBuilding
      });
      const parsedShifts = Array.isArray(shiftsRes) ? shiftsRes : shiftsRes?.content || [];
      setShifts(parsedShifts);

      // 4. Fetch templates
      const templatesRes = await guardScheduleApi.getTemplates(
        (selectedBuilding === 'ALL' || selectedBuilding === 'CO_SO_HCM') ? undefined : selectedBuilding
      );
      setTemplates(Array.isArray(templatesRes) ? templatesRes : templatesRes?.content || []);
    } catch (err) {
      console.error('Lỗi tải dữ liệu lịch trực:', err);
      setError(err.message || 'Không thể tải dữ liệu lịch trực');
    } finally {
      setLoading(false);
    }
  }, [startDateStr, endDateStr, selectedBuilding]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Week navigation handlers
  const handlePrevWeek = () => {
    const prev = new Date(currentDate);
    prev.setDate(prev.getDate() - 7);
    setCurrentDate(prev);
  };

  const handleNextWeek = () => {
    const next = new Date(currentDate);
    next.setDate(next.getDate() + 7);
    setCurrentDate(next);
  };

  const handleToday = () => {
    setCurrentDate(new Date());
  };

  // Date picker handler to jump directly to any selected week
  const datePickerInputRef = useRef(null);

  const handleOpenDatePicker = () => {
    if (datePickerInputRef.current) {
      if (typeof datePickerInputRef.current.showPicker === 'function') {
        datePickerInputRef.current.showPicker();
      } else {
        datePickerInputRef.current.focus();
        datePickerInputRef.current.click();
      }
    }
  };

  const handleDatePicked = (e) => {
    const val = e.target.value;
    if (val) {
      const [year, month, day] = val.split('-').map(Number);
      const pickedDate = new Date(year, month - 1, day);
      setCurrentDate(pickedDate);
    }
  };

  // KPI Calculations
  const totalGuardsCount = guards.length;
  const totalShiftsCount = shifts.length;
  const todayShifts = useMemo(() => shifts.filter((s) => s.shiftDate === todayStr), [shifts, todayStr]);
  const todayMorningCount = todayShifts.filter((s) => s.shiftType === 'SHIFT_MORNING').length;
  const todayAfternoonCount = todayShifts.filter((s) => s.shiftType === 'SHIFT_AFTERNOON').length;
  const todayNightCount = todayShifts.filter((s) => s.shiftType === 'SHIFT_NIGHT').length;

  // Security Room Coverage Warnings
  const missingSecurityRoomWarnings = useMemo(() => {
    const warnings = [];
    const shiftTypes = ['SHIFT_MORNING', 'SHIFT_AFTERNOON', 'SHIFT_NIGHT'];

    weekDays.forEach((day) => {
      const dateStr = formatLocalDate(day);
      const dayShifts = shifts.filter((s) => s.shiftDate === dateStr);

      shiftTypes.forEach((st) => {
        const matching = dayShifts.filter((s) => s.shiftType === st);
        if (matching.length > 0) {
          const hasSecurityRoom = matching.some((s) => {
            const name = (s.areaName || '').toLowerCase();
            return (
              name.includes('phòng bảo vệ') ||
              name.includes('security room') ||
              name.includes('camera') ||
              name.includes('điều khiển')
            );
          });

          if (!hasSecurityRoom) {
            const label =
              st === 'SHIFT_MORNING' ? 'Ca Sáng' : st === 'SHIFT_AFTERNOON' ? 'Ca Chiều' : 'Ca Đêm';
            warnings.push(
              `Ngày ${day.toLocaleDateString('vi-VN', {
                day: '2-digit',
                month: '2-digit'
              })} - ${label}: Chưa có bảo vệ phân công trực phòng camera an ninh!`
            );
          }
        }
      });
    });

    return warnings;
  }, [shifts, weekDays]);

  // Delete shift handler
  const handleDeleteShift = async (id) => {
    if (!window.confirm('Bạn có chắc chắn muốn xóa ca trực này?')) return;
    try {
      await guardScheduleApi.deleteShift(id);
      fetchData();
    } catch (err) {
      alert(err.message || 'Lỗi khi xóa ca trực');
    }
  };

  // Filtered guards by search
  const filteredGuards = useMemo(() => {
    if (!searchKeyword.trim()) return guards;
    const kw = searchKeyword.toLowerCase().trim();
    return guards.filter(
      (g) =>
        (g.fullName && g.fullName.toLowerCase().includes(kw)) ||
        (g.userCode && g.userCode.toLowerCase().includes(kw)) ||
        (g.email && g.email.toLowerCase().includes(kw))
    );
  }, [guards, searchKeyword]);



  // Create Template Handler
  const handleCreateTemplate = async (e) => {
    e.preventDefault();
    try {
      await guardScheduleApi.createTemplate({
        ...newTemplate,
        building: newTemplate.building === 'ALL' ? undefined : newTemplate.building,
        areaId: newTemplate.areaId ? newTemplate.areaId : null
      });
      fetchData();
      alert('Đã thêm mẫu ca trực thành công!');
    } catch (err) {
      alert(err.message || 'Lỗi khi tạo mẫu ca');
    }
  };

  return (
    <div className="guard-schedule-page space-y-6">
      {/* 1. Header & Command Bar */}
      <div className="schedule-header">
        <div>
          <h1 className="schedule-header-title">
            <Calendar className="text-blue-600 dark:text-blue-400" /> Quản Lý Lịch Trực Bảo Vệ
          </h1>
        </div>

        <div className="schedule-header__actions">
          <button
            type="button"
            onClick={() => setShowTemplateModal(true)}
            className="schedule-btn-secondary"
          >
            <Settings size={16} />
            <span>Cấu Hình Lịch Mẫu</span>
          </button>

          <button
            type="button"
            onClick={() => setShowGenerateModal(true)}
            className="schedule-btn-secondary"
          >
            <Wand2 size={16} />
            <span>Sinh Lịch Tự Động</span>
          </button>

          <button
            type="button"
            onClick={() => {
              setEditingShift(null);
              setShowShiftModal(true);
            }}
            className="schedule-btn-primary"
          >
            <Plus size={16} strokeWidth={2.5} />
            <span>Thêm Ca Trực Lẻ</span>
          </button>
        </div>
      </div>

      {/* 2. Readiness KPI Cards */}
      <div className="schedule-kpi-grid">
        {/* Card 1: Total Guards */}
        <div className="schedule-kpi-card">
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Quân Số Sẵn Sàng
            </span>
            <div className="text-2xl font-black text-slate-900 dark:text-white mt-1">
              {totalGuardsCount} <span className="text-sm font-normal text-slate-500">nhân viên</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2">
              <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                <span className="live-pulse" /> Sẵn sàng trực
              </span>
            </div>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-blue">
            <Users size={22} />
          </div>
        </div>

        {/* Card 2: Week Shifts */}
        <div className="schedule-kpi-card">
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Tổng Ca Tuần Này
            </span>
            <div className="text-2xl font-black text-slate-900 dark:text-white mt-1">
              {totalShiftsCount} <span className="text-sm font-normal text-slate-500">lượt ca</span>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-2">
              {weekDays[0].toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })} —{' '}
              {weekDays[6].toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })}
            </p>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-purple">
            <Calendar size={22} />
          </div>
        </div>

        {/* Card 3: Today Shifts Breakdown */}
        <div className="schedule-kpi-card">
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Ca Trực Hôm Nay
            </span>
            <div className="text-2xl font-black text-slate-900 dark:text-white mt-1">
              {todayShifts.length} <span className="text-sm font-normal text-slate-500">ca trực</span>
            </div>
            <div className="flex items-center gap-2 mt-2 text-xs font-medium text-slate-600 dark:text-slate-300">
              <span className="text-sky-600 dark:text-sky-400">{todayMorningCount} Ca Sáng</span>
              <span>•</span>
              <span className="text-amber-600 dark:text-amber-400">{todayAfternoonCount} Ca Chiều</span>
              <span>•</span>
              <span className="text-purple-600 dark:text-purple-400">{todayNightCount} Ca Đêm</span>
            </div>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-amber">
            <Clock size={22} />
          </div>
        </div>

        {/* Card 4: Security Room Warning Status */}
        <div
          id="kpiCardSecurityRoom"
          className={`schedule-kpi-card ${
            missingSecurityRoomWarnings.length > 0
              ? 'cursor-pointer border-amber-300 dark:border-amber-800/80 hover:border-amber-500 hover:shadow-md transition'
              : ''
          }`}
          onClick={() => {
            if (missingSecurityRoomWarnings.length > 0) setShowWarningModal(true);
          }}
          title={missingSecurityRoomWarnings.length > 0 ? 'Nhấn để xem chi tiết cảnh báo' : ''}
        >
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Độ Phủ Phòng Camera
            </span>
            <div className="text-2xl font-black text-slate-900 dark:text-white mt-1">
              {missingSecurityRoomWarnings.length === 0 ? (
                <span className="text-emerald-600 dark:text-emerald-400 text-xl font-bold flex items-center gap-1">
                  <CheckCircle2 size={20} /> 100% Đạt Chuẩn
                </span>
              ) : (
                <span className="text-amber-600 dark:text-amber-400 text-xl font-bold flex items-center gap-1">
                  <AlertTriangle size={20} /> {missingSecurityRoomWarnings.length} Cảnh Báo
                </span>
              )}
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-2">
              {missingSecurityRoomWarnings.length === 0
                ? 'Đầy đủ bảo vệ trực màn hình giám sát'
                : 'Nhấn để xem chi tiết ca thiếu quân số'}
            </p>
          </div>
          <div
            className={`schedule-kpi-icon-wrap ${
              missingSecurityRoomWarnings.length === 0 ? 'kpi-icon-emerald' : 'kpi-icon-amber'
            }`}
          >
            <Shield size={22} />
          </div>
        </div>
      </div>

      {/* 4. Toolbar: Date Navigation & View Mode Switcher */}
      <div className="schedule-toolbar flex-col gap-3">
        {/* Top Row: Week Navigator on Left, View Mode Tabs on Right */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 w-full">
          {/* Week Controls */}
          <div className="flex items-center gap-2">
            <div className="week-navigator">
              <button onClick={handlePrevWeek} className="week-nav-btn" title="Tuần trước">
                <ChevronLeft size={18} />
              </button>
              <button
                type="button"
                onClick={handleToday}
                className="week-today-btn"
                title="Quay về tuần hiện tại"
              >
                Hôm nay
              </button>
              <button onClick={handleNextWeek} className="week-nav-btn" title="Tuần sau">
                <ChevronRight size={18} />
              </button>
            </div>

            <div
              className="week-date-badge"
              onClick={handleOpenDatePicker}
              title="Nhấn để chọn ngày và chuyển tuần hiển thị"
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  handleOpenDatePicker();
                }
              }}
            >
              <Calendar size={14} className="text-blue-600 dark:text-blue-400 shrink-0" />
              <span>
                {weekDays[0].toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })} —{' '}
                {weekDays[6].toLocaleDateString('vi-VN', {
                  day: '2-digit',
                  month: '2-digit',
                  year: 'numeric'
                })}
              </span>
              <input
                ref={datePickerInputRef}
                type="date"
                value={formatLocalDate(currentDate)}
                onChange={handleDatePicked}
                className="week-date-badge__native-input"
                tabIndex={-1}
                aria-hidden="true"
              />
            </div>

            <button
              onClick={fetchData}
              disabled={loading}
              className="p-2 text-slate-500 hover:text-blue-600 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-lg transition"
              title="Làm mới dữ liệu"
            >
              <RefreshCw size={16} className={loading ? 'animate-spin' : ''} />
            </button>
          </div>
        </div>

        {/* Bottom Row: Building Selector & Search Filter */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pt-3 border-t border-slate-100 dark:border-slate-800/80 w-full">
          <div className="flex items-center gap-2">
            <Building2 size={16} className="text-slate-400 shrink-0" />
            <span className="text-xs font-semibold text-slate-500">Khuôn viên:</span>
            <select
              value={selectedBuilding}
              onChange={(e) => setSelectedBuilding(e.target.value)}
              className="px-3 py-1.5 text-xs font-semibold rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500 min-w-[180px]"
            >
              <option value="ALL">-- Tất cả cơ sở --</option>
              <option value="CO_SO_HCM">FPT TP.HCM</option>
            </select>
          </div>

          <div className="schedule-search-box">
            <Search size={14} className="schedule-search-icon" />
            <input
              type="text"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="Tìm theo tên, mã NV, chốt..."
              className="schedule-search-input"
            />
            {searchKeyword && (
              <button
                type="button"
                onClick={() => setSearchKeyword('')}
                className="schedule-search-clear"
                title="Xóa tìm kiếm"
              >
                <X size={13} />
              </button>
            )}
          </div>
        </div>
      </div>

      {/* 5. GUARD MATRIX (Guards x 7 Days) */}
      <div className="schedule-matrix-wrap">
          <table className="schedule-matrix-table">
            <thead>
              <tr>
                <th className="matrix-th-guard">Nhân Viên Bảo Vệ ({filteredGuards.length})</th>
                {weekDays.map((d, i) => {
                  const dayStr = formatLocalDate(d);
                  const isToday = dayStr === todayStr;
                  const dayShiftsCount = shifts.filter(
                    (s) => s.shiftDate === dayStr
                  ).length;
                  return (
                    <th key={i} className={`matrix-th-day ${isToday ? 'is-today' : ''}`}>
                      <div className="text-[11px] uppercase font-bold text-slate-500 dark:text-slate-400">
                        {['Chủ nhật', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'][d.getDay()]}
                      </div>
                      <div className="text-base font-extrabold text-slate-800 dark:text-white mt-0.5">
                        {d.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })}
                      </div>
                      <div className="text-[10px] font-medium text-slate-400 dark:text-slate-500 mt-0.5">
                        {dayShiftsCount} ca trực
                      </div>
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {filteredGuards.length === 0 ? (
                <tr>
                  <td colSpan={8}>
                    <div className="schedule-empty-card">
                      <div className="schedule-empty-icon">
                        <Users size={32} />
                      </div>
                      <h3 className="text-base font-bold text-slate-800 dark:text-white mb-1">
                        Chưa tìm thấy nhân sự bảo vệ nào
                      </h3>
                      <p className="text-xs text-slate-500 dark:text-slate-400 max-w-md mb-4">
                        Hệ thống chưa có tài khoản bảo vệ phù hợp với từ khóa tìm kiếm hoặc chưa tạo
                        nhân sự vai trò GUARD.
                      </p>
                    </div>
                  </td>
                </tr>
              ) : (
                filteredGuards.map((guard) => (
                  <tr key={guard.id}>
                    {/* Guard Info Cell */}
                    <td className="matrix-td-info">
                      <div className="flex flex-col justify-center min-w-0 py-0.5">
                        <div
                          className="font-bold text-slate-900 dark:text-slate-100 text-sm leading-snug truncate"
                          title={guard.fullName}
                        >
                          {guard.fullName || 'Nhân viên bảo vệ'}
                        </div>
                        <div className="mt-1.5 flex items-center">
                          <span className="inline-flex items-center font-mono font-semibold bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-700 px-2 py-0.5 rounded text-[11px] whitespace-nowrap shadow-xs">
                            {guard.userCode || 'NV-BV'}
                          </span>
                        </div>
                      </div>
                    </td>

                    {/* 7 Day Slot Cells */}
                    {weekDays.map((d, i) => {
                      const dateStr = formatLocalDate(d);
                      const guardDayShifts = shifts.filter(
                        (s) => s.guardId === guard.id && s.shiftDate === dateStr
                      );
                      const isToday = dateStr === todayStr;

                      return (
                        <td
                          key={i}
                          className={`matrix-td-slot ${isToday ? 'is-today' : ''}`}
                        >
                          <div className="space-y-2 min-h-[90px]">
                            {guardDayShifts.map((shift) => {
                              const shiftConfig = SHIFT_TYPES[shift.shiftType] || SHIFT_TYPES.SHIFT_MORNING;
                              const Icon = shiftConfig.icon;

                              return (
                                <div key={shift.id} className={`shift-card ${shiftConfig.cssClass}`}>
                                  {/* Header: Shift Title & Actions */}
                                  <div className="flex items-center justify-between font-bold">
                                    <span className="flex items-center gap-1">
                                      <Icon size={13} /> {shiftConfig.label}
                                    </span>
                                    <div className="flex items-center gap-1">
                                      <button
                                        onClick={() => {
                                          setEditingShift(shift);
                                          setShowShiftModal(true);
                                        }}
                                        className="p-1 hover:bg-black/10 rounded transition"
                                        title="Chỉnh sửa ca"
                                      >
                                        <Edit2 size={12} />
                                      </button>
                                      <button
                                        onClick={() => handleDeleteShift(shift.id)}
                                        className="p-1 hover:bg-black/10 text-red-600 rounded transition"
                                        title="Xóa ca"
                                      >
                                        <Trash2 size={12} />
                                      </button>
                                    </div>
                                  </div>

                                  {/* Time */}
                                  <div className="flex items-center gap-1 mt-1 text-[11px] font-semibold opacity-90">
                                    <Clock size={11} />
                                    <span>
                                      {shift.startTime} — {shift.endTime}
                                    </span>
                                  </div>

                                  {/* Area */}
                                  {shift.areaName && (
                                    <div
                                      className="flex items-center gap-1 mt-1 text-[11px] font-medium truncate"
                                      title={shift.areaName}
                                    >
                                      <MapPin size={11} className="shrink-0" />
                                      <span className="truncate">{shift.areaName}</span>
                                    </div>
                                  )}

                                  {/* Radio */}
                                  {shift.radioChannel && (
                                    <div className="flex items-center gap-1 mt-0.5 text-[10px] opacity-80">
                                      <Radio size={10} />
                                      <span>{shift.radioChannel}</span>
                                    </div>
                                  )}

                                  {/* Status badge */}
                                  <div className="mt-1.5 pt-1 border-t border-black/10 flex items-center justify-between text-[10px]">
                                    {shift.status === 'CHECKED_IN' ? (
                                      <span className="text-emerald-700 dark:text-emerald-300 font-bold flex items-center gap-1">
                                        <span className="live-pulse" /> Đang nhận ca
                                      </span>
                                    ) : shift.status === 'COMPLETED' ? (
                                      <span className="opacity-75">Đã hoàn thành</span>
                                    ) : (
                                      <span className="opacity-75">Đã lên lịch</span>
                                    )}
                                  </div>
                                </div>
                              );
                            })}

                            {/* Quick Add Shift button */}
                            <button
                              onClick={() => {
                                setEditingShift({
                                  guardId: guard.id,
                                  shiftDate: dateStr,
                                  shiftType: 'SHIFT_MORNING',
                                  startTime: '06:00',
                                  endTime: '14:00',
                                  radioChannel: 'Kênh 1 - Phòng Camera'
                                });
                                setShowShiftModal(true);
                              }}
                              className="slot-quick-add-btn"
                              title="Thêm ca trực nhanh cho ngày này"
                            >
                              <Plus size={13} /> Phân ca
                            </button>
                          </div>
                        </td>
                      );
                    })}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>


      {/* ========================================================
          MODAL: ADD / EDIT SHIFT
      ======================================================== */}
      {showShiftModal && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setShowShiftModal(false);
              setEditingShift(null);
            }
          }}
        >
          <div className="schedule-modal schedule-modal--md">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge">
                  <Shield size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">
                    {editingShift && editingShift.id ? 'Chỉnh Sửa Ca Trực' : 'Phân Bổ Ca Trực Mới'}
                  </h3>
                  <p className="schedule-modal__subtitle">
                    Thiết lập nhân viên, thời gian và vị trí phân công an ninh
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => {
                  setShowShiftModal(false);
                  setEditingShift(null);
                }}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            <form
              onSubmit={async (e) => {
                e.preventDefault();
                const form = e.target;
                const payload = {
                  guardId: form.guardId.value,
                  shiftDate: form.shiftDate.value,
                  shiftType: form.shiftType.value,
                  startTime: form.startTime.value,
                  endTime: form.endTime.value,
                  areaId: form.areaId.value ? form.areaId.value : null,
                  radioChannel: form.radioChannel.value,
                  notes: form.notes.value,
                  status: form.status ? form.status.value : 'SCHEDULED'
                };

                try {
                  if (editingShift && editingShift.id) {
                    await guardScheduleApi.updateShift(editingShift.id, payload);
                  } else {
                    await guardScheduleApi.createShift(payload);
                  }
                  setShowShiftModal(false);
                  setEditingShift(null);
                  fetchData();
                } catch (err) {
                  alert(err.message || 'Lỗi lưu ca trực');
                }
              }}
            >
              <div className="schedule-modal__body">
                <div className="schedule-form-group">
                  <label className="schedule-form-label">
                    <span>Nhân Viên Bảo Vệ</span>
                    <span className="required">*</span>
                  </label>
                  <select
                    name="guardId"
                    defaultValue={editingShift ? editingShift.guardId : ''}
                    required
                    className="schedule-form-select"
                  >
                    <option value="">-- Chọn nhân viên bảo vệ --</option>
                    {guards.map((g) => (
                      <option key={g.id} value={g.id}>
                        {g.fullName} ({g.userCode || 'NV-BV'})
                      </option>
                    ))}
                  </select>
                </div>

                <div className="schedule-form-row">
                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Ngày Trực</span>
                      <span className="required">*</span>
                    </label>
                    <input
                      type="date"
                      name="shiftDate"
                      defaultValue={editingShift ? editingShift.shiftDate : todayStr}
                      required
                      className="schedule-form-input"
                    />
                  </div>

                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Loại Ca</span>
                    </label>
                    <select
                      name="shiftType"
                      defaultValue={editingShift ? editingShift.shiftType : 'SHIFT_MORNING'}
                      onChange={(e) => {
                        const type = e.target.value;
                        const sInput = document.getElementById('startTimeInput');
                        const eInput = document.getElementById('endTimeInput');
                        if (type === 'SHIFT_MORNING') {
                          if (sInput) sInput.value = '06:00';
                          if (eInput) eInput.value = '14:00';
                        } else if (type === 'SHIFT_AFTERNOON') {
                          if (sInput) sInput.value = '14:00';
                          if (eInput) eInput.value = '22:00';
                        } else if (type === 'SHIFT_NIGHT') {
                          if (sInput) sInput.value = '22:00';
                          if (eInput) eInput.value = '06:00';
                        }
                      }}
                      className="schedule-form-select"
                    >
                      <option value="SHIFT_MORNING">Ca Sáng (06:00 - 14:00)</option>
                      <option value="SHIFT_AFTERNOON">Ca Chiều (14:00 - 22:00)</option>
                      <option value="SHIFT_NIGHT">Ca Đêm (22:00 - 06:00)</option>
                    </select>
                  </div>
                </div>

                <div className="schedule-form-row">
                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Giờ Bắt Đầu</span>
                      <span className="required">*</span>
                    </label>
                    <input
                      type="time"
                      id="startTimeInput"
                      name="startTime"
                      defaultValue={editingShift ? editingShift.startTime : '06:00'}
                      required
                      className="schedule-form-input"
                    />
                  </div>

                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Giờ Kết Thúc</span>
                      <span className="required">*</span>
                    </label>
                    <input
                      type="time"
                      id="endTimeInput"
                      name="endTime"
                      defaultValue={editingShift ? editingShift.endTime : '14:00'}
                      required
                      className="schedule-form-input"
                    />
                  </div>
                </div>

                <div className="schedule-form-group">
                  <label className="schedule-form-label">
                    <span>Chốt Trực Phân Công (Khu Vực)</span>
                  </label>
                  <select
                    name="areaId"
                    defaultValue={editingShift ? editingShift.areaId : ''}
                    className="schedule-form-select"
                  >
                    <option value="">-- Chưa gán chốt (Tuần tra cơ động) --</option>
                    {areas.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.name} ({a.building || 'Khuôn viên FPT TP.HCM'})
                      </option>
                    ))}
                  </select>
                </div>

                {editingShift && editingShift.id ? (
                  <div className="schedule-form-row">
                    <div className="schedule-form-group">
                      <label className="schedule-form-label">
                        <span>Kênh Bộ Đàm</span>
                      </label>
                      <input
                        type="text"
                        name="radioChannel"
                        defaultValue={editingShift.radioChannel || 'Kênh 1 - Phòng Camera'}
                        placeholder="Kênh 1 - Phòng Camera"
                        className="schedule-form-input"
                      />
                    </div>

                    <div className="schedule-form-group">
                      <label className="schedule-form-label">
                        <span>Trạng Thái Điểm Danh</span>
                      </label>
                      <select
                        name="status"
                        defaultValue={editingShift.status}
                        className="schedule-form-select"
                      >
                        <option value="SCHEDULED">SCHEDULED (Lên lịch)</option>
                        <option value="CHECKED_IN">CHECKED_IN (Đang trực)</option>
                        <option value="COMPLETED">COMPLETED (Hoàn thành)</option>
                        <option value="ABSENT">ABSENT (Vắng mặt)</option>
                      </select>
                    </div>
                  </div>
                ) : (
                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Kênh Bộ Đàm</span>
                    </label>
                    <input
                      type="text"
                      name="radioChannel"
                      defaultValue="Kênh 1 - Phòng Camera"
                      placeholder="Kênh 1 - Phòng Camera"
                      className="schedule-form-input"
                    />
                  </div>
                )}

                <div className="schedule-form-group">
                  <label className="schedule-form-label">
                    <span>Ghi Chú Nhiệm Vụ</span>
                  </label>
                  <textarea
                    name="notes"
                    rows={2}
                    defaultValue={editingShift ? editingShift.notes : ''}
                    placeholder="Kiểm soát ra vào, trực màn hình an ninh hoặc tuần tra định kỳ..."
                    className="schedule-form-textarea"
                  />
                </div>
              </div>

              <div className="schedule-modal__footer">
                <button
                  type="button"
                  onClick={() => {
                    setShowShiftModal(false);
                    setEditingShift(null);
                  }}
                  className="schedule-btn-modal schedule-btn-modal--cancel"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  className="schedule-btn-modal schedule-btn-modal--submit"
                >
                  Lưu Ca Trực
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ========================================================
          MODAL: GENERATE SHIFTS FROM TEMPLATES
      ======================================================== */}
      {showGenerateModal && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setShowGenerateModal(false);
              setGenerateResult(null);
            }
          }}
        >
          <div className="schedule-modal schedule-modal--sm">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge">
                  <Wand2 size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">Sinh Lịch Tự Động</h3>
                  <p className="schedule-modal__subtitle">
                    Tạo ca trực hàng loạt từ khung lịch mẫu tuần
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => {
                  setShowGenerateModal(false);
                  setGenerateResult(null);
                }}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            {generateResult ? (
              <div>
                <div className="schedule-modal__body">
                  <div className="p-4 bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-300 dark:border-emerald-800 rounded-xl text-emerald-800 dark:text-emerald-200 text-sm">
                    <p className="font-bold text-base flex items-center gap-2">
                      <CheckCircle2 size={18} className="text-emerald-600 dark:text-emerald-400" />
                      Đã sinh thành công {generateResult.totalGenerated} ca trực mới!
                    </p>
                    <p className="text-xs text-emerald-700 dark:text-emerald-300 mt-1">
                      Các ca trực đã được cập nhật vào bảng lịch tuần của các bảo vệ.
                    </p>
                  </div>

                  {generateResult.warnings && generateResult.warnings.length > 0 && (
                    <div className="p-3 bg-amber-50 dark:bg-amber-950/40 border border-amber-300 dark:border-amber-800 rounded-xl text-amber-800 dark:text-amber-200 text-xs space-y-1">
                      <p className="font-bold text-xs flex items-center gap-1.5">
                        <AlertTriangle size={14} className="text-amber-500" /> Lưu ý phân công:
                      </p>
                      {generateResult.warnings.map((w, idx) => (
                        <p key={idx} className="pl-4">• {w}</p>
                      ))}
                    </div>
                  )}
                </div>

                <div className="schedule-modal__footer">
                  <button
                    type="button"
                    onClick={() => {
                      setShowGenerateModal(false);
                      setGenerateResult(null);
                      fetchData();
                    }}
                    className="schedule-btn-modal schedule-btn-modal--submit w-full"
                  >
                    Xong & Xem Lịch Trực
                  </button>
                </div>
              </div>
            ) : (
              <form
                onSubmit={async (e) => {
                  e.preventDefault();
                  const form = e.target;
                  const start = form.startDate.value;
                  const end = form.endDate.value;
                  const bld = form.building.value;

                  try {
                    const res = await guardScheduleApi.generateShifts({
                      startDate: start,
                      endDate: end,
                      building: bld === 'ALL' ? undefined : bld
                    });
                    setGenerateResult(res);
                  } catch (err) {
                    alert(err.message || 'Lỗi khi sinh ca trực');
                  }
                }}
              >
                <div className="schedule-modal__body">
                  <div className="schedule-form-row">
                    <div className="schedule-form-group">
                      <label className="schedule-form-label">
                        <span>Từ Ngày</span>
                        <span className="required">*</span>
                      </label>
                      <input
                        type="date"
                        name="startDate"
                        defaultValue={startDateStr}
                        required
                        className="schedule-form-input"
                      />
                    </div>

                    <div className="schedule-form-group">
                      <label className="schedule-form-label">
                        <span>Đến Ngày</span>
                        <span className="required">*</span>
                      </label>
                      <input
                        type="date"
                        name="endDate"
                        defaultValue={endDateStr}
                        required
                        className="schedule-form-input"
                      />
                    </div>
                  </div>

                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Cơ Sở / Khuôn Viên Áp Dụng</span>
                    </label>
                    <select
                      name="building"
                      defaultValue={selectedBuilding}
                      className="schedule-form-select"
                    >
                      <option value="ALL">Toàn bộ khuôn viên</option>
                      <option value="CO_SO_HCM">FPT TP.HCM</option>
                    </select>
                  </div>
                </div>

                <div className="schedule-modal__footer">
                  <button
                    type="button"
                    onClick={() => setShowGenerateModal(false)}
                    className="schedule-btn-modal schedule-btn-modal--cancel"
                  >
                    Hủy
                  </button>
                  <button
                    type="submit"
                    className="schedule-btn-modal schedule-btn-modal--submit"
                  >
                    <Wand2 size={14} /> Bắt Đầu Sinh Lịch
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}

      {/* ========================================================
          MODAL: TEMPLATE MANAGEMENT
      ======================================================== */}
      {showTemplateModal && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setShowTemplateModal(false);
            }
          }}
        >
          <div className="schedule-modal schedule-modal--lg">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge">
                  <Settings size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">Cấu Hình Khung Lịch Mẫu Tuần</h3>
                  <p className="schedule-modal__subtitle">
                    Khung phân bổ mẫu Thứ 2 – Chủ nhật dùng để tự động tạo ca định kỳ
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => setShowTemplateModal(false)}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            <div className="schedule-modal__body">
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Lịch mẫu quy định khung trực cố định hàng tuần. Khi bấm &quot;Sinh Lịch Tự Động&quot;, hệ thống sẽ căn cứ vào đây để phân bổ ca trực cho các nhân sự an ninh.
              </p>

              {/* Add new template inline form */}
              <form
                onSubmit={handleCreateTemplate}
                className="schedule-template-create-card"
              >
                <div className="schedule-template-card-title">
                  <Plus size={15} className="text-blue-600 dark:text-blue-400" /> Thêm Khung Mẫu Ca Mới
                </div>

                <div className="schedule-form-row--3">
                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Thứ</span>
                    </label>
                    <select
                      value={newTemplate.dayOfWeek}
                      onChange={(e) =>
                        setNewTemplate({ ...newTemplate, dayOfWeek: parseInt(e.target.value) })
                      }
                      className="schedule-form-select"
                    >
                      <option value={2}>Thứ 2</option>
                      <option value={3}>Thứ 3</option>
                      <option value={4}>Thứ 4</option>
                      <option value={5}>Thứ 5</option>
                      <option value={6}>Thứ 6</option>
                      <option value={7}>Thứ 7</option>
                      <option value={1}>Chủ nhật</option>
                    </select>
                  </div>

                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Bảo Vệ</span>
                      <span className="required">*</span>
                    </label>
                    <select
                      value={newTemplate.guardId}
                      onChange={(e) => setNewTemplate({ ...newTemplate, guardId: e.target.value })}
                      required
                      className="schedule-form-select"
                    >
                      <option value="">-- Chọn bảo vệ --</option>
                      {guards.map((g) => (
                        <option key={g.id} value={g.id}>
                          {g.fullName}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Ca Trực</span>
                    </label>
                    <select
                      value={newTemplate.shiftType}
                      onChange={(e) => {
                        const type = e.target.value;
                        let s = '06:00';
                        let en = '14:00';
                        if (type === 'SHIFT_AFTERNOON') {
                          s = '14:00';
                          en = '22:00';
                        }
                        if (type === 'SHIFT_NIGHT') {
                          s = '22:00';
                          en = '06:00';
                        }
                        setNewTemplate({
                          ...newTemplate,
                          shiftType: type,
                          startTime: s,
                          endTime: en
                        });
                      }}
                      className="schedule-form-select"
                    >
                      <option value="SHIFT_MORNING">Ca Sáng (06:00 - 14:00)</option>
                      <option value="SHIFT_AFTERNOON">Ca Chiều (14:00 - 22:00)</option>
                      <option value="SHIFT_NIGHT">Ca Đêm (22:00 - 06:00)</option>
                    </select>
                  </div>
                </div>

                <div className="schedule-form-row">
                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Chốt Gác Phân Công</span>
                    </label>
                    <select
                      value={newTemplate.areaId}
                      onChange={(e) => setNewTemplate({ ...newTemplate, areaId: e.target.value })}
                      className="schedule-form-select"
                    >
                      <option value="">-- Chưa gán chốt (Cơ động) --</option>
                      {areas.map((a) => (
                        <option key={a.id} value={a.id}>
                          {a.name} ({a.building || 'Khuôn viên FPT TP.HCM'})
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="schedule-form-group">
                    <label className="schedule-form-label">
                      <span>Kênh Bộ Đàm</span>
                    </label>
                    <input
                      type="text"
                      value={newTemplate.radioChannel}
                      onChange={(e) => setNewTemplate({ ...newTemplate, radioChannel: e.target.value })}
                      placeholder="Kênh 1 - Phòng Camera"
                      className="schedule-form-input"
                    />
                  </div>
                </div>

                <div className="flex justify-end pt-1">
                  <button
                    type="submit"
                    className="schedule-btn-modal schedule-btn-modal--submit"
                  >
                    <Plus size={14} /> Lưu Mẫu Ca
                  </button>
                </div>
              </form>

              {/* Template list */}
              <div className="flex items-center justify-between pt-1">
                <span className="text-xs font-semibold text-slate-700 dark:text-slate-300">
                  Danh Sách Khung Mẫu Đã Lưu ({templates.length})
                </span>
              </div>

              {templates.length === 0 ? (
                <div className="p-8 text-center border border-dashed rounded-xl text-slate-400 text-xs dark:border-slate-700">
                  Chưa có lịch mẫu tuần nào được lưu trong hệ thống.
                </div>
              ) : (
                <div className="schedule-template-list">
                  {templates.map((t) => (
                    <div
                      key={t.id}
                      className="schedule-template-item"
                    >
                      <div className="flex flex-col gap-1 min-w-0 flex-1">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="schedule-template-day-badge">
                            {['', 'Chủ nhật', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'][t.dayOfWeek]}
                          </span>
                          <span className="font-semibold text-sm text-slate-900 dark:text-slate-100">
                            {t.guardName}
                          </span>
                          <span className="schedule-template-time-pill">
                            <Clock size={12} /> {t.startTime} - {t.endTime}
                          </span>
                        </div>
                        <div className="flex items-center gap-3 text-xs text-slate-500 dark:text-slate-400">
                          <span className="flex items-center gap-1">
                            <MapPin size={12} /> {t.areaName || 'Chưa gán chốt'}
                          </span>
                          <span>•</span>
                          <span className="flex items-center gap-1">
                            <Radio size={12} /> {t.radioChannel || '—'}
                          </span>
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={async () => {
                          if (window.confirm('Xóa mẫu ca này?')) {
                            await guardScheduleApi.deleteTemplate(t.id);
                            fetchData();
                          }
                        }}
                        className="schedule-btn-delete"
                        title="Xóa mẫu"
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div className="schedule-modal__footer">
              <button
                type="button"
                onClick={() => setShowTemplateModal(false)}
                className="schedule-btn-modal schedule-btn-modal--cancel"
              >
                Đóng
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================
          MODAL: SECURITY ROOM WARNING DETAILS
      ======================================================== */}
      {showWarningModal && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget) {
              setShowWarningModal(false);
            }
          }}
        >
          <div className="schedule-modal schedule-modal--md">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge schedule-modal__icon-badge--warning">
                  <AlertTriangle size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title text-amber-600 dark:text-amber-400">
                    Cảnh Báo Quân Số Phòng Camera ({missingSecurityRoomWarnings.length})
                  </h3>
                  <p className="schedule-modal__subtitle">
                    Phát hiện ca trực an ninh chưa có nhân sự trực phòng camera
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => setShowWarningModal(false)}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            <div className="schedule-modal__body">
              <div className="p-3 bg-amber-50 dark:bg-amber-950/40 border border-amber-300 dark:border-amber-800 rounded-xl text-amber-800 dark:text-amber-200 text-xs font-medium leading-relaxed">
                Phát hiện {missingSecurityRoomWarnings.length} ca trực trong tuần chưa có bảo vệ phụ trách phòng camera điều khiển. Vui lòng phân công bổ sung bảo vệ cho các ca này để đảm bảo an ninh khuôn viên 24/7.
              </div>

              <div className="space-y-2 text-xs">
                {missingSecurityRoomWarnings.map((warning, idx) => (
                  <div
                    key={idx}
                    className="p-3 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50 flex items-start gap-2.5 text-slate-700 dark:text-slate-200"
                  >
                    <AlertTriangle size={15} className="text-amber-500 shrink-0 mt-0.5" />
                    <span className="font-medium">{warning}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="schedule-modal__footer">
              <button
                type="button"
                onClick={() => setShowWarningModal(false)}
                className="schedule-btn-modal schedule-btn-modal--cancel"
              >
                Đã hiểu & Đóng
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
