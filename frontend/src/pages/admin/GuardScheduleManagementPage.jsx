import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import {
  Calendar,
  ChevronLeft,
  ChevronRight,
  Plus,
  Wand2,
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
  AlertCircle,
  ClipboardList
} from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';
import { getUsers } from '../../services/userService';
import { getAreas } from '../../services/areaService';
import { getBuildings } from '../../services/buildingService';
import { ROLES } from '../../constants/roles';
import StaffingWizardModal from '../../components/guard/StaffingWizardModal';
import GuardTeamsTab from '../../components/guard/GuardTeamsTab';
import ShiftRequestsTab from '../../components/guard/ShiftRequestsTab';
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
  // Navigation Tabs: 'SCHEDULE' | 'TEAMS' | 'REQUESTS'
  const [activeTab, setActiveTab] = useState('SCHEDULE');

  const [currentDate, setCurrentDate] = useState(new Date());
  const [selectedBuilding, setSelectedBuilding] = useState('ALL');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [buildings, setBuildings] = useState([]);

  const [guards, setGuards] = useState([]);
  const [areas, setAreas] = useState([]);
  const [shifts, setShifts] = useState([]);
  const [teams, setTeams] = useState([]);
  const [dispatches, setDispatches] = useState([]);
  const [selectedTeam, setSelectedTeam] = useState('ALL');
  const [shiftRequests, setShiftRequests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Modals
  const [showShiftModal, setShowShiftModal] = useState(false);
  const [showWizardModal, setShowWizardModal] = useState(false);
  const [editingShift, setEditingShift] = useState(null);
  const [isCreateTeamModalOpen, setIsCreateTeamModalOpen] = useState(false);
  const [showBulkClearModal, setShowBulkClearModal] = useState(false);
  const [bulkClearScope, setBulkClearScope] = useState('ALL');
  const [bulkClearSelectedTeamId, setBulkClearSelectedTeamId] = useState('');
  const [clearingShifts, setClearingShifts] = useState(false);

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

    // 1. Fetch guard teams & active dispatches for the week
    let teamList = [];
    try {
      const teamsRes = await guardScheduleApi.getTeams();
      teamList = Array.isArray(teamsRes) ? teamsRes : [];
      setTeams(teamList);
    } catch (e) {
      console.warn('Lỗi tải danh sách đội bảo vệ:', e);
    }

    let dispatchList = [];
    try {
      const dispatchesRes = await guardScheduleApi.getDispatches({
        startDate: startDateStr,
        endDate: endDateStr
      });
      dispatchList = Array.isArray(dispatchesRes) ? dispatchesRes : [];
      setDispatches(dispatchList);
    } catch (e) {
      console.warn('Lỗi tải danh sách điều động tăng cường:', e);
    }

    const guardTeamMap = new Map();
    teamList.forEach((t) => {
      (t.members || []).forEach((m) => {
        guardTeamMap.set(m.id, { id: t.id, teamName: t.teamName, colorCode: t.colorCode });
      });
    });

    const activeDispatches = dispatchList.filter((d) => d.status === 'ACTIVE');

    // 2. Fetch system users and extract active guards with team information
    try {
      const usersRes = await getUsers({ accountType: 'SYSTEM', isActive: true, size: 100 });
      const pageObj = usersRes?.users || usersRes;
      const allUsers = Array.isArray(pageObj?.content)
        ? pageObj.content
        : Array.isArray(usersRes?.content)
        ? usersRes.content
        : Array.isArray(usersRes)
        ? usersRes
        : [];

      let guardList = allUsers
        .filter(
          (u) =>
            u.isActive !== false &&
            (u.role === ROLES.GUARD ||
             u.role === 'GUARD' ||
             u.role === 'INTERNAL_GUARD' ||
             u.role === 'OUTSOURCED_GUARD')
        )
        .map((u) => {
          const teamInfo = guardTeamMap.get(u.id);
          const activeTeam = teamInfo || (u.teamId && teamList.find((t) => t.id === u.teamId)) || null;
          const teamId = activeTeam?.id || null;
          const teamName = activeTeam?.teamName || null;
          const activeDisp = activeDispatches.find((d) => d.guardId === u.id);
          return {
            ...u,
            teamId,
            teamName,
            team: activeTeam ? { id: teamId, teamName, colorCode: activeTeam.colorCode } : null,
            activeDispatch: activeDisp ? {
              id: activeDisp.id,
              toTeamId: activeDisp.toTeamId,
              toTeamName: activeDisp.toTeamName,
              fromTeamId: activeDisp.fromTeamId,
              fromTeamName: activeDisp.fromTeamName,
              startDate: activeDisp.startDate,
              endDate: activeDisp.endDate,
              shiftType: activeDisp.shiftType,
              reason: activeDisp.reason
            } : null
          };
        });
      guardList.sort((a, b) => {
        const codeA = (a.userCode || '').trim();
        const codeB = (b.userCode || '').trim();
        if (codeA && codeB) {
          const cmp = codeA.localeCompare(codeB, undefined, { numeric: true, sensitivity: 'base' });
          if (cmp !== 0) return cmp;
        } else if (codeA) return -1;
        else if (codeB) return 1;
        return (a.fullName || '').localeCompare(b.fullName || '', 'vi', { sensitivity: 'base' });
      });
      setGuards(guardList);
    } catch (e) {
      console.warn('Lỗi tải danh sách nhân viên bảo vệ:', e);
    }

    // 3. Fetch areas & buildings
    try {
      const [areasRes, buildingsRes] = await Promise.all([
        getAreas({ size: 100 }),
        getBuildings().catch(() => [])
      ]);

      let areaList = Array.isArray(areasRes?.content)
        ? areasRes.content
        : Array.isArray(areasRes?.areas)
        ? areasRes.areas
        : Array.isArray(areasRes)
        ? areasRes
        : [];
      if (areaList.length === 0) {
        areaList = [
          { id: 'area-1', name: 'Chốt Cổng Chính & Bãi Xe', building: 'FPT_AROUND' },
          { id: 'area-2', name: 'Phòng Điều Khiển Camera & Trực Ban', building: 'FPT_AROUND' },
          { id: 'area-3', name: 'Sảnh Chính Tầng Trệt', building: 'FPT_AROUND' },
          { id: 'area-4', name: 'Tuần Tra Hành Lang & Các Tầng', building: 'FPT_AROUND' }
        ];
      }
      setAreas(areaList);
      setBuildings(Array.isArray(buildingsRes) ? buildingsRes : []);
    } catch (e) {
      console.warn('Lỗi tải khu vực / tòa nhà:', e);
    }

    // 4. Fetch shifts for this week
    try {
      const shiftsRes = await guardScheduleApi.getShifts({
        startDate: startDateStr,
        endDate: endDateStr,
        building: selectedBuilding === 'ALL' ? undefined : selectedBuilding
      });
      const parsedShifts = Array.isArray(shiftsRes) ? shiftsRes : shiftsRes?.content || [];
      setShifts(parsedShifts);
    } catch (e) {
      console.warn('Lỗi tải ca trực tuần:', e);
    }

    // 5. Fetch shift requests
    try {
      const requestsRes = await guardScheduleApi.getShiftRequests();
      setShiftRequests(Array.isArray(requestsRes) ? requestsRes : []);
    } catch (e) {
      console.warn('Lỗi tải yêu cầu ca trực:', e);
    }

    setLoading(false);
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

  const pendingRequestsCount = useMemo(() => {
    return shiftRequests.filter((r) => r.status === 'PENDING').length;
  }, [shiftRequests]);

  // Set of guard IDs having shifts in current view
  const guardIdsWithShiftsThisWeek = useMemo(() => {
    const set = new Set();
    shifts.forEach((s) => {
      if (s.guard?.id) set.add(s.guard.id);
      if (s.guardId) set.add(s.guardId);
    });
    return set;
  }, [shifts]);

  // Teams that have active shifts at the currently selected building
  const activeTeamsForBuilding = useMemo(() => {
    if (selectedBuilding === 'ALL') return [];

    const guardTeamMap = new Map();
    guards.forEach((g) => {
      const tid = g.team?.id || g.teamId;
      if (tid) guardTeamMap.set(g.id, tid);
    });

    const teamShiftCounts = new Map();
    shifts.forEach((s) => {
      const gid = s.guard?.id || s.guardId;
      const tid = guardTeamMap.get(gid);
      if (tid) {
        teamShiftCounts.set(tid, (teamShiftCounts.get(tid) || 0) + 1);
      }
    });

    return teams
      .map((t) => ({
        team: t,
        shiftCount: teamShiftCounts.get(t.id) || 0
      }))
      .filter((item) => item.shiftCount > 0)
      .sort((a, b) => b.shiftCount - a.shiftCount);
  }, [selectedBuilding, shifts, guards, teams]);

  // Map of teamId -> shiftCount for dropdown labels
  const teamShiftCountsMap = useMemo(() => {
    if (selectedBuilding === 'ALL') return new Map();
    const map = new Map();
    activeTeamsForBuilding.forEach(({ team, shiftCount }) => {
      map.set(team.id, shiftCount);
    });
    return map;
  }, [selectedBuilding, activeTeamsForBuilding]);

  // Filtered guards by team, building and search
  const filteredGuards = useMemo(() => {
    let result = guards;
    if (selectedTeam !== 'ALL') {
      const foundTeam = (teams || []).find((t) => t.id === selectedTeam);
      const teamMemberIds = (foundTeam?.members && foundTeam.members.length > 0)
        ? new Set(foundTeam.members.map((m) => m.id))
        : null;
      result = result.filter(
        (g) =>
          g.team?.id === selectedTeam ||
          g.teamId === selectedTeam ||
          (teamMemberIds && teamMemberIds.has(g.id)) ||
          (g.activeDispatch && g.activeDispatch.toTeamId === selectedTeam)
      );
    } else if (selectedBuilding !== 'ALL') {
      // Smart Auto-Filter: Khi chọn khuôn viên cụ thể và để Đội = Tất cả, chỉ hiện các bảo vệ có ca trực tại khuôn viên đó
      result = result.filter((g) => guardIdsWithShiftsThisWeek.has(g.id));
    }

    if (searchKeyword.trim()) {
      const kw = searchKeyword.toLowerCase().trim();
      result = result.filter(
        (g) =>
          (g.fullName && g.fullName.toLowerCase().includes(kw)) ||
          (g.userCode && g.userCode.toLowerCase().includes(kw)) ||
          (g.email && g.email.toLowerCase().includes(kw))
      );
    }
    return result;
  }, [guards, selectedTeam, selectedBuilding, teams, guardIdsWithShiftsThisWeek, searchKeyword]);



  // Open Bulk Clear Modal
  const handleOpenBulkClear = () => {
    if (selectedTeam !== 'ALL') {
      setBulkClearScope('TEAM');
      setBulkClearSelectedTeamId(selectedTeam);
    } else {
      setBulkClearScope('ALL');
      setBulkClearSelectedTeamId(teams.length > 0 ? teams[0].id : '');
    }
    setShowBulkClearModal(true);
  };

  // Target shifts count for bulk clear preview
  const bulkClearTargetShiftsCount = useMemo(() => {
    const scheduledShifts = shifts.filter((s) => s.status === 'SCHEDULED');
    if (bulkClearScope === 'ALL') {
      return scheduledShifts.length;
    }
    if (bulkClearScope === 'TEAM' && bulkClearSelectedTeamId) {
      const foundTeam = teams.find((t) => t.id === bulkClearSelectedTeamId);
      const memberIds = new Set((foundTeam?.members || []).map((m) => m.id));
      const teamDispatches = dispatches.filter((d) => d.toTeamId === bulkClearSelectedTeamId && d.status === 'ACTIVE');
      teamDispatches.forEach((d) => memberIds.add(d.guardId));

      return scheduledShifts.filter((s) => memberIds.has(s.guardId) || memberIds.has(s.guard?.id)).length;
    }
    return scheduledShifts.length;
  }, [shifts, bulkClearScope, bulkClearSelectedTeamId, teams, dispatches]);

  // Execute Bulk Clear
  const handleExecuteBulkClear = async () => {
    setClearingShifts(true);
    try {
      const targetTeamId = bulkClearScope === 'TEAM' ? bulkClearSelectedTeamId : undefined;
      const res = await guardScheduleApi.bulkClearShifts({
        startDate: startDateStr,
        endDate: endDateStr,
        teamId: targetTeamId || undefined,
        building: selectedBuilding === 'ALL' ? undefined : selectedBuilding,
        onlyScheduled: true
      });
      await fetchData();
      setShowBulkClearModal(false);
      alert(res?.message || `Đã xóa thành công ${res?.clearedCount || 0} ca trực!`);
    } catch (err) {
      alert(err.message || 'Lỗi khi xóa lịch trực');
    } finally {
      setClearingShifts(false);
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
          <p className="schedule-header-subtitle">
            Phân công ca trực theo đội, điều phối chốt an ninh và phê duyệt yêu cầu đổi/nghỉ ca
          </p>
        </div>

        <div className="schedule-header__actions">
          {activeTab === 'SCHEDULE' && (
            <div className="flex items-center gap-2">
              {shifts.length > 0 && (
                <button
                  type="button"
                  onClick={handleOpenBulkClear}
                  className="schedule-btn-secondary text-rose-600 hover:text-rose-700 dark:text-rose-400 border-rose-200 dark:border-rose-900/60 hover:bg-rose-50 dark:hover:bg-rose-950/40"
                  title="Xóa nhanh các ca trực tuần chưa diễn ra (để phân lại khi cần)"
                >
                  <Trash2 size={15} className="text-rose-500" />
                  <span>Xóa Lịch Tuần</span>
                </button>
              )}
              <button
                type="button"
                onClick={() => setShowWizardModal(true)}
                className="schedule-btn-primary"
              >
                <Wand2 size={16} />
                <span>Phân Lịch Trực Cho Đội</span>
              </button>
            </div>
          )}

          {activeTab === 'TEAMS' && (
            <button
              type="button"
              onClick={() => setIsCreateTeamModalOpen(true)}
              className="schedule-btn-primary"
            >
              <Plus size={16} strokeWidth={2.5} />
              <span>Thêm Đội Mới</span>
            </button>
          )}
        </div>
      </div>

      {/* 2. Navigation Tabs (Đồng bộ chuẩn ManageAccountPage) */}
      <nav className="schedule-tabs" aria-label="Phân nhóm quản lý lịch trực và đội bảo vệ">
        <button
          type="button"
          className={`schedule-tab-btn ${activeTab === 'SCHEDULE' ? 'schedule-tab-btn--active' : ''}`}
          onClick={() => setActiveTab('SCHEDULE')}
        >
          <Calendar size={17} />
          <span>Lịch Trực Tuần</span>
        </button>

        <button
          type="button"
          className={`schedule-tab-btn ${activeTab === 'TEAMS' ? 'schedule-tab-btn--active' : ''}`}
          onClick={() => setActiveTab('TEAMS')}
        >
          <Users size={17} />
          <span>Cơ Cấu Đội Bảo Vệ</span>
          <span className="schedule-tab-badge">
            {teams.length}
          </span>
        </button>

        <button
          type="button"
          className={`schedule-tab-btn ${activeTab === 'REQUESTS' ? 'schedule-tab-btn--active' : ''}`}
          onClick={() => setActiveTab('REQUESTS')}
        >
          <ClipboardList size={17} />
          <span>Yêu Cầu Đổi / Nghỉ Ca</span>
          {pendingRequestsCount > 0 && (
            <span className="schedule-tab-badge schedule-tab-badge--warning">
              {pendingRequestsCount}
            </span>
          )}
        </button>
      </nav>

      {/* 3. TAB 1: WEEKLY SCHEDULE VIEW */}
      {activeTab === 'SCHEDULE' && (
        <div className="space-y-6">
          {/* Readiness KPI Cards */}
          <div className="schedule-kpi-grid">
        {/* Card 1: Total Guards */}
        <div className="schedule-kpi-card">
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
              Tổng Quân Số Bảo Vệ
            </span>
            <div className="text-2xl font-black text-slate-900 dark:text-white mt-1">
              {totalGuardsCount} <span className="text-sm font-normal text-slate-500">nhân viên</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2">
              <span className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 dark:text-slate-400">
                <span className="inline-block w-2 h-2 rounded-full bg-emerald-500" /> Đang hoạt động
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

        {/* Bottom Row: Building Selector, Team Selector & Search Filter */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pt-3 border-t border-slate-100 dark:border-slate-800/80 w-full">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <Building2 size={16} className="text-slate-400 shrink-0" />
              <span className="text-xs font-semibold text-slate-500">Khuôn viên:</span>
              <select
                value={selectedBuilding}
                onChange={(e) => setSelectedBuilding(e.target.value)}
                className="px-3 py-1.5 text-xs font-semibold rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500 min-w-[150px]"
              >
                <option value="ALL">-- Tất cả cơ sở / tòa nhà --</option>
                {buildings.map((b) => (
                  <option key={b.id || b.code} value={b.code}>
                    {b.name}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex items-center gap-2">
              <Users size={16} className="text-slate-400 shrink-0" />
              <span className="text-xs font-semibold text-slate-500">Đội:</span>
              <select
                value={selectedTeam}
                onChange={(e) => setSelectedTeam(e.target.value)}
                className="px-3 py-1.5 text-xs font-semibold rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500 min-w-[150px]"
              >
                <option value="ALL">-- Tất cả đội bảo vệ --</option>
                {teams.map((t) => {
                  const buildingShifts = teamShiftCountsMap.get(t.id);
                  const suffix = selectedBuilding !== 'ALL'
                    ? (buildingShifts > 0 ? ` — (${buildingShifts} ca) ⭐` : ' — (0 ca)')
                    : '';
                  return (
                    <option key={t.id} value={t.id}>
                      {t.teamName}{suffix}
                    </option>
                  );
                })}
              </select>
            </div>
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
                        <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
                          <span className="inline-flex items-center font-mono font-semibold bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-700 px-2 py-0.5 rounded text-[11px] whitespace-nowrap shadow-xs">
                            {guard.userCode || 'NV-BV'}
                          </span>
                          {(guard.teamName || guard.team?.teamName) ? (
                            <span
                              className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold truncate max-w-[130px]"
                              style={{
                                backgroundColor: guard.team?.colorCode ? `${guard.team.colorCode}18` : '#eff6ff',
                                color: guard.team?.colorCode || '#2563eb',
                                border: `1px solid ${guard.team?.colorCode ? `${guard.team.colorCode}40` : '#bfdbfe'}`
                              }}
                              title={`Đội gốc: ${guard.teamName || guard.team?.teamName}`}
                            >
                              {guard.teamName || guard.team?.teamName}
                            </span>
                          ) : (
                            <span className="text-[10px] text-slate-400 font-medium">
                              Chưa phân đội
                            </span>
                          )}
                          {guard.activeDispatch && (
                            <span
                              className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[10px] font-bold bg-amber-50 dark:bg-amber-950/40 text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-700/60 shadow-xs"
                              title={`Điều động tăng cường: ${guard.activeDispatch.toTeamName} (${guard.activeDispatch.shiftType ? (guard.activeDispatch.shiftType === 'SHIFT_MORNING' ? 'Ca Sáng (06-14h)' : guard.activeDispatch.shiftType === 'SHIFT_AFTERNOON' ? 'Ca Chiều (14-22h)' : 'Ca Đêm (22-06h)') + ' • ' : ''}${guard.activeDispatch.startDate} ~ ${guard.activeDispatch.endDate})${guard.activeDispatch.reason ? ' - Lý do: ' + guard.activeDispatch.reason : ''}`}
                            >
                              ⚡ {guard.activeDispatch.toTeamName}
                              {guard.activeDispatch.shiftType && (
                                <span className="font-normal opacity-85">
                                  ({guard.activeDispatch.shiftType === 'SHIFT_MORNING' ? 'Sáng' : guard.activeDispatch.shiftType === 'SHIFT_AFTERNOON' ? 'Chiều' : 'Đêm'})
                                </span>
                              )}
                            </span>
                          )}
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
                                      {(shift.startTime && shift.startTime.length >= 5 ? shift.startTime.substring(0, 5) : shift.startTime)} — {(shift.endTime && shift.endTime.length >= 5 ? shift.endTime.substring(0, 5) : shift.endTime)}
                                    </span>
                                  </div>

                                  {/* Status badge */}
                                  <div className="mt-1.5 pt-1 border-t border-black/10 flex items-center justify-between text-[10px]">
                                    {shift.status === 'CHECKED_IN' ? (
                                      <span className="text-emerald-700 dark:text-emerald-300 font-bold flex items-center gap-1">
                                        <span className="live-pulse" /> Đang nhận ca
                                      </span>
                                    ) : shift.status === 'COMPLETED' ? (
                                      <span className="opacity-80 font-medium">Đã hoàn thành</span>
                                    ) : shift.status === 'ABSENT' ? (
                                      <span className="text-rose-600 dark:text-rose-400 font-bold">Vắng mặt</span>
                                    ) : shift.status === 'CANCELLED' ? (
                                      <span className="text-slate-500 font-medium line-through">Đã hủy</span>
                                    ) : (
                                      <span className="opacity-75">Đã lên lịch</span>
                                    )}

                                    {shift.isOvertime && (
                                      <span className="px-1 py-0.2 rounded text-[9px] font-bold bg-amber-200 dark:bg-amber-900/70 text-amber-900 dark:text-amber-200">
                                        OT
                                      </span>
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
                                  endTime: '14:00'
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
      </div>
      )}

      {/* 4. TAB 2: GUARD TEAMS MANAGEMENT */}
      {activeTab === 'TEAMS' && (
        <GuardTeamsTab
          teams={teams}
          allGuards={guards}
          areas={areas}
          buildings={buildings}
          shifts={shifts}
          onTeamsUpdated={fetchData}
          isCreateModalOpen={isCreateTeamModalOpen}
          setIsCreateModalOpen={setIsCreateTeamModalOpen}
        />
      )}

      {/* 5. TAB 3: SHIFT REQUESTS (SWAP & LEAVE) */}
      {activeTab === 'REQUESTS' && (
        <ShiftRequestsTab onRequestsUpdated={fetchData} />
      )}

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
                const shiftType = form.shiftType.value;
                let startTime = '06:00';
                let endTime = '14:00';
                if (shiftType === 'SHIFT_AFTERNOON') {
                  startTime = '14:00';
                  endTime = '22:00';
                } else if (shiftType === 'SHIFT_NIGHT') {
                  startTime = '22:00';
                  endTime = '06:00';
                }

                const payload = {
                  guardId: form.guardId.value,
                  shiftDate: form.shiftDate.value,
                  shiftType: shiftType,
                  startTime: startTime,
                  endTime: endTime,
                  areaId: null,
                  radioChannel: null,
                  notes: form.notes ? form.notes.value : '',
                  status: form.status ? form.status.value : (editingShift?.status || 'SCHEDULED'),
                  isOvertime: form.isOvertime ? form.isOvertime.checked : (editingShift?.isOvertime || false)
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
                      className="schedule-form-select"
                    >
                      <option value="SHIFT_MORNING">Ca Sáng (06:00 - 14:00)</option>
                      <option value="SHIFT_AFTERNOON">Ca Chiều (14:00 - 22:00)</option>
                      <option value="SHIFT_NIGHT">Ca Đêm (22:00 - 06:00)</option>
                    </select>
                  </div>
                </div>

                {editingShift && editingShift.id && (
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

                <div className="schedule-form-group pt-1">
                  <label className="flex items-center gap-2 cursor-pointer text-xs font-semibold text-slate-700 dark:text-slate-300">
                    <input
                      type="checkbox"
                      name="isOvertime"
                      defaultChecked={editingShift ? !!editingShift.isOvertime : false}
                      className="rounded border-slate-300 text-blue-600 focus:ring-blue-500 h-4 w-4"
                    />
                    <span>Ca trực tăng ca / Sự kiện ngoài giờ (Overtime)</span>
                  </label>
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
          MODAL: BULK CLEAR SHIFTS
      ======================================================== */}
      {showBulkClearModal && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !clearingShifts) {
              setShowBulkClearModal(false);
            }
          }}
        >
          <div className="schedule-modal schedule-modal--md max-w-lg">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge schedule-modal__icon-badge--warning bg-rose-50 text-rose-600 border-rose-200 dark:bg-rose-950/50 dark:text-rose-400 dark:border-rose-800">
                  <Trash2 size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title text-rose-700 dark:text-rose-400">
                    Xóa & Đặt Lại Lịch Trực Tuần
                  </h3>
                  <p className="schedule-modal__subtitle">
                    Thu hồi nhanh các ca trực chưa diễn ra khi phân công nhầm
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => setShowBulkClearModal(false)}
                disabled={clearingShifts}
              >
                <X size={18} />
              </button>
            </div>

            <div className="schedule-modal__body space-y-4">
              {/* Week time frame info */}
              <div className="p-3 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-xl flex items-center justify-between text-xs">
                <span className="text-slate-600 dark:text-slate-300">
                  Tuần áp dụng: <strong>{weekDays[0].toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })} — {weekDays[6].toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' })}</strong>
                </span>
                <span className="font-bold text-slate-800 dark:text-slate-100">
                  {shifts.length} ca trực
                </span>
              </div>

              {/* Scope Selection */}
              <div className="space-y-2.5">
                <label className="block text-xs font-bold text-slate-700 dark:text-slate-300">
                  Chọn phạm vi muốn xóa:
                </label>

                <div className="space-y-2">
                  <label className={`flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition text-xs ${
                    bulkClearScope === 'ALL'
                      ? 'border-rose-300 bg-rose-50/50 dark:border-rose-800 dark:bg-rose-950/30'
                      : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/40'
                  }`}>
                    <input
                      type="radio"
                      name="bulkClearScope"
                      value="ALL"
                      checked={bulkClearScope === 'ALL'}
                      onChange={() => setBulkClearScope('ALL')}
                      className="mt-0.5 text-rose-600 focus:ring-rose-500"
                    />
                    <div>
                      <div className="font-bold text-slate-800 dark:text-slate-100">
                        Xóa toàn bộ ca trực trong tuần
                      </div>
                      <div className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                        Dọn sạch toàn bộ ca trực của tất cả các đội trong tuần này để phân công lại từ đầu.
                      </div>
                    </div>
                  </label>

                  {teams.length > 0 && (
                    <label className={`flex items-start gap-3 p-3 rounded-xl border cursor-pointer transition text-xs ${
                      bulkClearScope === 'TEAM'
                        ? 'border-rose-300 bg-rose-50/50 dark:border-rose-800 dark:bg-rose-950/30'
                        : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/40'
                    }`}>
                      <input
                        type="radio"
                        name="bulkClearScope"
                        value="TEAM"
                        checked={bulkClearScope === 'TEAM'}
                        onChange={() => setBulkClearScope('TEAM')}
                        className="mt-0.5 text-rose-600 focus:ring-rose-500"
                      />
                      <div className="flex-1">
                        <div className="font-bold text-slate-800 dark:text-slate-100">
                          Chỉ xóa ca trực của một đội cụ thể
                        </div>
                        <div className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5 mb-2">
                          Chỉ xóa các ca trực của các thành viên thuộc đội được chỉ định, giữ nguyên các đội khác.
                        </div>

                        {bulkClearScope === 'TEAM' && (
                          <select
                            value={bulkClearSelectedTeamId}
                            onChange={(e) => setBulkClearSelectedTeamId(e.target.value)}
                            className="w-full px-2.5 py-1.5 text-xs rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 font-medium"
                          >
                            {teams.map((t) => (
                              <option key={t.id} value={t.id}>
                                {t.teamName}
                              </option>
                            ))}
                          </select>
                        )}
                      </div>
                    </label>
                  )}
                </div>
              </div>

              {/* Safe Note Alert */}
              <div className="p-3 bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-800/80 rounded-xl text-xs text-amber-800 dark:text-amber-200 flex items-start gap-2.5">
                <AlertCircle size={16} className="text-amber-600 shrink-0 mt-0.5" />
                <div className="leading-relaxed text-[11px]">
                  <strong>Bảo vệ dữ liệu an toàn:</strong> Hệ thống chỉ xóa các ca trực <strong>Chưa diễn ra (SCHEDULED)</strong>. Các ca đã điểm danh trực (<code>CHECKED_IN</code>) hoặc đã hoàn thành (<code>COMPLETED</code>) sẽ được bảo toàn nguyên vẹn.
                </div>
              </div>
            </div>

            <div className="schedule-modal__footer flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => setShowBulkClearModal(false)}
                disabled={clearingShifts}
                className="schedule-btn-secondary"
              >
                Hủy
              </button>
              <button
                type="button"
                onClick={handleExecuteBulkClear}
                disabled={clearingShifts || bulkClearTargetShiftsCount === 0}
                className="px-4 py-2 text-xs font-bold rounded-xl bg-rose-600 hover:bg-rose-700 text-white shadow-sm transition flex items-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Trash2 size={14} />
                <span>
                  {clearingShifts
                    ? 'Đang Xóa...'
                    : `Xác Nhận Xóa (${bulkClearTargetShiftsCount} Ca Trực)`}
                </span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================
          MODAL: DEMAND-DRIVEN STAFFING WIZARD
      ======================================================== */}
      <StaffingWizardModal
        isOpen={showWizardModal}
        onClose={() => setShowWizardModal(false)}
        guards={guards}
        teams={teams}
        areas={areas}
        buildings={buildings}
        currentWeekMonday={weekDays[0]}
        onSuccess={() => {
          fetchData();
        }}
      />
    </div>
  );
}
