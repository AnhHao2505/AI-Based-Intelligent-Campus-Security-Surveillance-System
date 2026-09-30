import React, { useState, useEffect, useMemo, useRef } from 'react';
import {
  Calendar,
  Clock,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Search,
  Filter,
  RefreshCw,
  ArrowRightLeft,
  UserX,
  UserCheck,
  Check,
  X,
  MessageSquare,
  Shield,
  HelpCircle,
  Sun,
  Sunset,
  Moon,
  User,
  AlertTriangle,
  ArrowRight,
  ClipboardList,
  MapPin,
  ChevronDown
} from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';

const formatDateVN = (dateStr) => {
  if (!dateStr) return '';
  const match = String(dateStr).match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (match) {
    const [, y, m, d] = match;
    return `${d}-${m}-${y}`;
  }
  try {
    const d = new Date(dateStr);
    if (!isNaN(d.getTime())) {
      const day = String(d.getDate()).padStart(2, '0');
      const month = String(d.getMonth() + 1).padStart(2, '0');
      const year = d.getFullYear();
      return `${day}-${month}-${year}`;
    }
  } catch (e) {
    // ignore
  }
  return dateStr;
};

const formatDateTimeVN = (dateStr) => {
  if (!dateStr) return '';
  try {
    const d = new Date(dateStr);
    if (!isNaN(d.getTime())) {
      const day = String(d.getDate()).padStart(2, '0');
      const month = String(d.getMonth() + 1).padStart(2, '0');
      const year = d.getFullYear();
      const hours = String(d.getHours()).padStart(2, '0');
      const mins = String(d.getMinutes()).padStart(2, '0');
      return `${hours}:${mins}, ${day}/${month}/${year}`;
    }
  } catch (e) {
    // ignore
  }
  return formatDateVN(dateStr);
};

// Helper: Determine shift category (Morning, Afternoon, Night) based on start time
const getShiftInfo = (startTime, endTime) => {
  const s = (startTime || '').substring(0, 5);
  const e = (endTime || '').substring(0, 5);
  if (!s) {
    return {
      label: 'Ca trực',
      time: s && e ? `${s} - ${e}` : 'Chưa định giờ',
      icon: Clock,
      color: 'text-slate-700 bg-slate-100 border-slate-200 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-300'
    };
  }

  const hour = parseInt(s.split(':')[0], 10);
  if (hour >= 5 && hour < 13) {
    return {
      label: 'Ca Sáng',
      time: `${s} - ${e || '14:00'}`,
      icon: Sun,
      color: 'text-amber-800 bg-amber-50/90 border-amber-200/90 dark:text-amber-300 dark:bg-amber-950/40 dark:border-amber-800/80',
      badgeClass: 'bg-amber-500/10 text-amber-700 dark:text-amber-300 border-amber-400/40'
    };
  }
  if (hour >= 13 && hour < 21) {
    return {
      label: 'Ca Chiều',
      time: `${s} - ${e || '22:00'}`,
      icon: Sunset,
      color: 'text-orange-800 bg-orange-50/90 border-orange-200/90 dark:text-orange-300 dark:bg-orange-950/40 dark:border-orange-800/80',
      badgeClass: 'bg-orange-500/10 text-orange-700 dark:text-orange-300 border-orange-400/40'
    };
  }
  return {
    label: 'Ca Đêm',
    time: `${s} - ${e || '06:00'}`,
    icon: Moon,
    color: 'text-indigo-800 bg-indigo-50/90 border-indigo-200/90 dark:text-indigo-300 dark:bg-indigo-950/40 dark:border-indigo-800/80',
    badgeClass: 'bg-indigo-500/10 text-indigo-700 dark:text-indigo-300 border-indigo-400/40'
  };
};

const getInitials = (name) => {
  if (!name) return 'BV';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

const formatTeamName = (team) => {
  if (!team) return '';
  const str = String(team).trim();
  if (/^\d+$/.test(str)) return `Đội ${str}`;
  return str.startsWith('Đội') ? str : `Đội ${str}`;
};

export default function ShiftRequestsTab({ onRequestsUpdated }) {
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Trục lọc chính theo TRẠNG THÁI (Approval Pipeline): 'PENDING' | 'APPROVED' | 'REJECTED' | 'ALL'
  const [statusFilter, setStatusFilter] = useState('PENDING');
  // Ô lọc phụ theo LOẠI ĐƠN: 'ALL' | 'SWAP' | 'LEAVE'
  const [typeFilter, setTypeFilter] = useState('ALL');
  const [isTypeDropdownOpen, setIsTypeDropdownOpen] = useState(false);
  const typeDropdownRef = useRef(null);
  const [searchKeyword, setSearchKeyword] = useState('');

  // Sub-modal: Approve Leave (Select Substitute)
  const [leaveModal, setLeaveModal] = useState({
    isOpen: false,
    request: null,
    substitutes: [],
    selectedSubstituteId: '',
    loadingSubstitutes: false,
    submitting: false
  });

  // Sub-modal: Reject Note Dialog
  const [rejectDialog, setRejectDialog] = useState({
    isOpen: false,
    request: null,
    reviewNote: '',
    submitting: false
  });

  // Đóng dropdown loại đơn khi click ra ngoài
  useEffect(() => {
    const handleOutsideClick = (e) => {
      if (typeDropdownRef.current && !typeDropdownRef.current.contains(e.target)) {
        setIsTypeDropdownOpen(false);
      }
    };
    if (isTypeDropdownOpen) {
      document.addEventListener('mousedown', handleOutsideClick);
    }
    return () => document.removeEventListener('mousedown', handleOutsideClick);
  }, [isTypeDropdownOpen]);

  // Fetch Requests (lấy đầy đủ để số liệu thống kê ở các thẻ KPI luôn chính xác)
  const fetchRequests = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await guardScheduleApi.getShiftRequests({});
      setRequests(Array.isArray(res) ? res : []);
    } catch (err) {
      console.error('Lỗi tải danh sách yêu cầu ca trực:', err);
      setError(err.message || 'Không thể tải danh sách yêu cầu');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchRequests();
  }, []);

  // Compute stats across current requests
  const stats = useMemo(() => {
    const total = requests.length;
    const pending = requests.filter((r) => r.status === 'PENDING').length;
    const approved = requests.filter((r) => r.status === 'APPROVED').length;
    const rejected = requests.filter((r) => r.status === 'REJECTED').length;

    // Đơn đổi ca
    const swaps = requests.filter(
      (r) => r.requestType === 'SWAP' || r.requestType === 'SWAP_SHIFT'
    ).length;

    // Đơn xin nghỉ
    const leaves = requests.filter(
      (r) => r.requestType !== 'SWAP' && r.requestType !== 'SWAP_SHIFT'
    ).length;

    // Ca nghỉ đột xuất
    const emergencyLeaves = requests.filter(
      (r) =>
        r.isEmergency === true &&
        r.requestType !== 'SWAP' &&
        r.requestType !== 'SWAP_SHIFT'
    ).length;

    return {
      total,
      pending,
      approved,
      rejected,
      swaps,
      leaves,
      emergencyLeaves
    };
  }, [requests]);

  // Filter by statusFilter, typeFilter & searchKeyword
  const filteredRequests = useMemo(() => {
    let list = requests;

    // 1. Lọc theo Trạng thái (từ 4 thẻ KPI)
    if (statusFilter === 'PENDING') {
      list = list.filter((r) => r.status === 'PENDING');
    } else if (statusFilter === 'APPROVED') {
      list = list.filter((r) => r.status === 'APPROVED');
    } else if (statusFilter === 'REJECTED') {
      list = list.filter((r) => r.status === 'REJECTED');
    }
    // statusFilter === 'ALL' giữ nguyên toàn bộ

    // 2. Lọc theo Loại đơn (từ ô lọc dropdown)
    if (typeFilter === 'SWAP') {
      list = list.filter(
        (r) => r.requestType === 'SWAP' || r.requestType === 'SWAP_SHIFT'
      );
    } else if (typeFilter === 'LEAVE') {
      list = list.filter(
        (r) => r.requestType !== 'SWAP' && r.requestType !== 'SWAP_SHIFT'
      );
    }

    // 3. Lọc theo từ khóa tìm kiếm
    if (searchKeyword.trim()) {
      const kw = searchKeyword.toLowerCase().trim();
      list = list.filter((r) => {
        const reqName = r.requesterName || r.requesterGuard?.fullName || '';
        const reqCode = r.requesterCode || r.requesterGuard?.userCode || '';
        const subName = r.substituteGuardName || r.targetSubstituteGuard?.fullName || '';
        const reason = r.reason || '';
        const area = r.areaName || r.shift?.area?.name || '';
        const targetArea = r.targetAreaName || r.targetShift?.area?.name || '';
        return (
          reqName.toLowerCase().includes(kw) ||
          reqCode.toLowerCase().includes(kw) ||
          subName.toLowerCase().includes(kw) ||
          reason.toLowerCase().includes(kw) ||
          area.toLowerCase().includes(kw) ||
          targetArea.toLowerCase().includes(kw)
        );
      });
    }

    return list;
  }, [requests, statusFilter, typeFilter, searchKeyword]);

  // Approve Swap Shift
  const handleApproveSwap = async (req) => {
    if (
      !window.confirm(
        `Xác nhận duyệt yêu cầu đổi ca của ${req.requesterName || 'bảo vệ'}? Ca trực sẽ được chuyển sang cho người nhận trực thay.`
      )
    ) {
      return;
    }
    setLoading(true);
    try {
      await guardScheduleApi.approveShiftRequest(req.id, {});
      await fetchRequests();
      if (onRequestsUpdated) onRequestsUpdated();
    } catch (err) {
      alert(err.message || 'Lỗi khi duyệt đổi ca');
    } finally {
      setLoading(false);
    }
  };

  // Open Leave Approval Modal
  const handleOpenApproveLeave = async (req) => {
    setLeaveModal({
      isOpen: true,
      request: req,
      substitutes: [],
      selectedSubstituteId: '',
      loadingSubstitutes: true,
      submitting: false
    });

    try {
      const shiftId = req.shiftId || req.shift?.id;
      if (shiftId) {
        const subs = await guardScheduleApi.getAvailableSubstitutes(shiftId);
        setLeaveModal((prev) => ({
          ...prev,
          substitutes: Array.isArray(subs) ? subs : [],
          loadingSubstitutes: false
        }));
      } else {
        setLeaveModal((prev) => ({ ...prev, loadingSubstitutes: false }));
      }
    } catch (err) {
      console.error('Lỗi lấy danh sách bảo vệ thay thế:', err);
      setLeaveModal((prev) => ({
        ...prev,
        substitutes: [],
        loadingSubstitutes: false
      }));
    }
  };

  // Submit Leave Approval
  const handleSubmitApproveLeave = async (e) => {
    e.preventDefault();
    const req = leaveModal.request;
    if (!req) return;

    setLeaveModal((prev) => ({ ...prev, submitting: true }));
    try {
      const subId = leaveModal.selectedSubstituteId;
      const payload = {
        action: 'APPROVE',
        substituteGuardId: subId === 'CANCEL_SHIFT' || !subId ? null : subId
      };
      await guardScheduleApi.approveShiftRequest(req.id, payload);
      setLeaveModal({
        isOpen: false,
        request: null,
        substitutes: [],
        selectedSubstituteId: '',
        loadingSubstitutes: false,
        submitting: false
      });
      await fetchRequests();
      if (onRequestsUpdated) onRequestsUpdated();
    } catch (err) {
      alert(err.message || 'Lỗi khi duyệt xin nghỉ');
      setLeaveModal((prev) => ({ ...prev, submitting: false }));
    }
  };

  // Open Reject Dialog
  const handleOpenReject = (req) => {
    setRejectDialog({
      isOpen: true,
      request: req,
      reviewNote: '',
      submitting: false
    });
  };

  // Submit Rejection
  const handleSubmitReject = async (e) => {
    e.preventDefault();
    if (!rejectDialog.request) return;

    setRejectDialog((prev) => ({ ...prev, submitting: true }));
    try {
      await guardScheduleApi.rejectShiftRequest(rejectDialog.request.id, {
        reviewNotes: rejectDialog.reviewNote,
        reviewNote: rejectDialog.reviewNote
      });
      setRejectDialog({
        isOpen: false,
        request: null,
        reviewNote: '',
        submitting: false
      });
      await fetchRequests();
      if (onRequestsUpdated) onRequestsUpdated();
    } catch (err) {
      alert(err.message || 'Lỗi khi từ chối yêu cầu');
      setRejectDialog((prev) => ({ ...prev, submitting: false }));
    }
  };

  return (
    <div className="space-y-5">
      {/* 1. OVERVIEW & PRIMARY TABS (4 Thẻ phân nhóm hoàn toàn theo TRẠNG THÁI - Approval Pipeline) */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
        {/* Card 1: Chờ Phê Duyệt (Màu cam) */}
        <div
          onClick={() => setStatusFilter('PENDING')}
          className={`schedule-kpi-card cursor-pointer select-none transition-all ${
            statusFilter === 'PENDING' ? 'is-filter-active is-active-amber' : ''
          }`}
          title="Xem danh sách đơn chờ phê duyệt"
        >
          <div>
            <span
              className={`text-xs ${
                statusFilter === 'PENDING'
                  ? 'font-bold text-amber-900 dark:text-amber-300'
                  : 'font-semibold text-slate-500 dark:text-slate-400'
              }`}
            >
              Chờ Phê Duyệt
            </span>
            <div className="text-2xl font-bold tracking-tight text-amber-600 dark:text-amber-400 mt-1">
              {stats.pending} <span className="text-sm font-normal text-slate-500">đơn</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs font-medium text-amber-600 dark:text-amber-400">
              {stats.pending > 0 ? (
                <>
                  <span className="w-2 h-2 rounded-full bg-amber-500 animate-pulse shrink-0" />
                  <span>Cần Quản lý xử lý</span>
                </>
              ) : (
                <span className="text-slate-500 dark:text-slate-400 font-normal">
                  Đã giải quyết hết
                </span>
              )}
            </div>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-amber">
            <Clock size={22} />
          </div>
        </div>

        {/* Card 2: Đã Phê Duyệt (Màu xanh lá) */}
        <div
          onClick={() => setStatusFilter('APPROVED')}
          className={`schedule-kpi-card cursor-pointer select-none transition-all ${
            statusFilter === 'APPROVED' ? 'is-filter-active is-active-emerald' : ''
          }`}
          title="Xem danh sách đơn đã phê duyệt"
        >
          <div>
            <span
              className={`text-xs ${
                statusFilter === 'APPROVED'
                  ? 'font-bold text-emerald-900 dark:text-emerald-300'
                  : 'font-semibold text-slate-500 dark:text-slate-400'
              }`}
            >
              Đã Phê Duyệt
            </span>
            <div className="text-2xl font-bold tracking-tight text-emerald-600 dark:text-emerald-400 mt-1">
              {stats.approved} <span className="text-sm font-normal text-slate-500">đơn</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs font-medium text-emerald-600 dark:text-emerald-400">
              <CheckCircle2 size={13} className="shrink-0" />
              <span>{stats.approved > 0 ? 'Đã duyệt thành công' : 'Chưa có đơn duyệt'}</span>
            </div>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-emerald">
            <CheckCircle2 size={22} />
          </div>
        </div>

        {/* Card 3: Từ Chối (Màu đỏ) */}
        <div
          onClick={() => setStatusFilter('REJECTED')}
          className={`schedule-kpi-card cursor-pointer select-none transition-all ${
            statusFilter === 'REJECTED' ? 'is-filter-active is-active-rose' : ''
          }`}
          title="Xem danh sách đơn đã từ chối"
        >
          <div>
            <span
              className={`text-xs ${
                statusFilter === 'REJECTED'
                  ? 'font-bold text-rose-900 dark:text-rose-300'
                  : 'font-semibold text-slate-500 dark:text-slate-400'
              }`}
            >
              Từ Chối
            </span>
            <div className="text-2xl font-bold tracking-tight text-rose-600 dark:text-rose-400 mt-1">
              {stats.rejected} <span className="text-sm font-normal text-slate-500">đơn</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs font-medium text-rose-600 dark:text-rose-400">
              <XCircle size={13} className="shrink-0" />
              <span>{stats.rejected > 0 ? 'Không được chấp thuận' : 'Không có đơn từ chối'}</span>
            </div>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-rose">
            <XCircle size={22} />
          </div>
        </div>

        {/* Card 4: Tất Cả Đơn (Màu xanh dương) */}
        <div
          onClick={() => setStatusFilter('ALL')}
          className={`schedule-kpi-card cursor-pointer select-none transition-all ${
            statusFilter === 'ALL' ? 'is-filter-active is-active-blue' : ''
          }`}
          title="Xem toàn bộ danh sách đơn"
        >
          <div>
            <span
              className={`text-xs ${
                statusFilter === 'ALL'
                  ? 'font-bold text-sky-900 dark:text-sky-300'
                  : 'font-semibold text-slate-500 dark:text-slate-400'
              }`}
            >
              Tất Cả Đơn
            </span>
            <div className="text-2xl font-bold tracking-tight text-slate-900 dark:text-white mt-1">
              {stats.total} <span className="text-sm font-normal text-slate-500">đơn</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs font-medium text-slate-600 dark:text-slate-400">
              <ArrowRightLeft size={13} className="text-sky-600 dark:text-sky-400 shrink-0" />
              <span>{stats.swaps} đổi ca • {stats.leaves} nghỉ trực</span>
            </div>
          </div>
          <div className="schedule-kpi-icon-wrap kpi-icon-blue">
            <ClipboardList size={22} />
          </div>
        </div>
      </div>

      {/* 2. TOOLBAR */}
      <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3 pt-1 pb-1">
        {/* Left: Section Identity & Badges */}
        <div className="flex items-center gap-3 flex-wrap">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-slate-100 dark:bg-slate-800 flex items-center justify-center text-slate-600 dark:text-slate-300 shrink-0">
              <ClipboardList size={16} />
            </div>
            <span className="text-sm sm:text-base font-bold text-slate-900 dark:text-white">
              {statusFilter === 'PENDING' && 'Đơn chờ phê duyệt'}
              {statusFilter === 'APPROVED' && 'Đơn đã phê duyệt'}
              {statusFilter === 'REJECTED' && 'Đơn đã từ chối'}
              {statusFilter === 'ALL' && 'Toàn bộ danh sách yêu cầu'}
            </span>

            <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 border border-slate-200/80 dark:border-slate-700/80">
              {filteredRequests.length} đơn
            </span>
          </div>

          {/* Active type filter badge if not ALL */}
          {typeFilter !== 'ALL' && (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-semibold bg-blue-50 text-blue-700 border border-blue-200 dark:bg-blue-950/50 dark:text-blue-300 dark:border-blue-800">
              <span>{typeFilter === 'SWAP' ? '⇄ Đổi ca trực' : '📅 Nghỉ trực'}</span>
              <button
                type="button"
                onClick={() => setTypeFilter('ALL')}
                className="hover:text-blue-900 dark:hover:text-white cursor-pointer ml-0.5"
                title="Bỏ lọc loại đơn"
              >
                <X size={12} />
              </button>
            </span>
          )}
        </div>

        {/* Right: Type Filter Dropdown, Search Box & Refresh Button */}
        <div className="flex items-center gap-2.5 flex-wrap sm:flex-nowrap">
          {/* Ô lọc nhỏ theo loại đơn: Custom Dropdown */}
          <div className="relative shrink-0" ref={typeDropdownRef}>
            <button
              type="button"
              onClick={() => setIsTypeDropdownOpen((prev) => !prev)}
              className={`h-[38px] px-3.5 rounded-xl text-xs font-semibold flex items-center justify-between gap-2.5 border transition-all cursor-pointer shadow-xs select-none ${
                typeFilter !== 'ALL'
                  ? 'bg-blue-50/80 border-blue-300 text-blue-700 dark:bg-blue-950/60 dark:border-blue-700 dark:text-blue-300'
                  : 'bg-white dark:bg-slate-900 border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-200 hover:border-slate-300 dark:hover:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/60'
              } ${isTypeDropdownOpen ? 'ring-2 ring-blue-500/20 border-blue-400 dark:border-blue-600' : ''}`}
              title="Lọc theo loại đơn"
            >
              <div className="flex items-center gap-2">
                {typeFilter === 'ALL' && <Filter size={13} className="text-slate-400 shrink-0" />}
                {typeFilter === 'SWAP' && <ArrowRightLeft size={13} className="text-blue-600 dark:text-blue-400 shrink-0" />}
                {typeFilter === 'LEAVE' && <Calendar size={13} className="text-amber-600 dark:text-amber-400 shrink-0" />}
                <span className="whitespace-nowrap">
                  {typeFilter === 'ALL' && 'Tất cả loại đơn'}
                  {typeFilter === 'SWAP' && 'Đổi ca trực'}
                  {typeFilter === 'LEAVE' && 'Nghỉ trực'}
                </span>
              </div>
              <ChevronDown
                size={14}
                className={`text-slate-400 shrink-0 transition-transform duration-200 ${
                  isTypeDropdownOpen ? 'rotate-180' : ''
                }`}
              />
            </button>

            {/* Custom Dropdown Menu Popover */}
            {isTypeDropdownOpen && (
              <div className="absolute right-0 top-full mt-1.5 w-56 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl shadow-xl z-50 p-1.5 backdrop-blur-md">
                <div className="px-2.5 py-1.5 text-[10.5px] font-bold uppercase tracking-wider text-slate-400 dark:text-slate-500 border-b border-slate-100 dark:border-slate-800/80 mb-1 flex items-center justify-between">
                  <span>Loại yêu cầu</span>
                  {typeFilter !== 'ALL' && (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setTypeFilter('ALL');
                        setIsTypeDropdownOpen(false);
                      }}
                      className="text-blue-600 dark:text-blue-400 hover:underline cursor-pointer font-semibold lowercase text-[10.5px]"
                    >
                      đặt lại
                    </button>
                  )}
                </div>

                {/* Option: Tất cả loại đơn */}
                <button
                  type="button"
                  onClick={() => {
                    setTypeFilter('ALL');
                    setIsTypeDropdownOpen(false);
                  }}
                  className={`w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs font-medium transition cursor-pointer text-left ${
                    typeFilter === 'ALL'
                      ? 'bg-slate-100 dark:bg-slate-800 text-slate-900 dark:text-white font-semibold'
                      : 'text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/60'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <Filter size={13} className="text-slate-400 shrink-0" />
                    <span>Tất cả loại đơn</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="px-1.5 py-0.5 rounded-full text-[11px] font-bold bg-slate-100 dark:bg-slate-800 text-slate-500 border border-slate-200/60 dark:border-slate-700">
                      {stats.total}
                    </span>
                    {typeFilter === 'ALL' && <Check size={13} className="text-blue-600 shrink-0 ml-0.5" />}
                  </div>
                </button>

                {/* Option: Đổi ca trực */}
                <button
                  type="button"
                  onClick={() => {
                    setTypeFilter('SWAP');
                    setIsTypeDropdownOpen(false);
                  }}
                  className={`w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs font-medium transition cursor-pointer text-left mt-0.5 ${
                    typeFilter === 'SWAP'
                      ? 'bg-blue-50 dark:bg-blue-950/50 text-blue-700 dark:text-blue-300 font-semibold'
                      : 'text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/60'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <ArrowRightLeft size={13} className="text-blue-600 dark:text-blue-400 shrink-0" />
                    <span>Đổi ca trực</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="px-1.5 py-0.5 rounded-full text-[11px] font-bold bg-blue-50 dark:bg-blue-950/80 text-blue-700 dark:text-blue-300 border border-blue-200/60 dark:border-blue-800">
                      {stats.swaps}
                    </span>
                    {typeFilter === 'SWAP' && <Check size={13} className="text-blue-600 shrink-0 ml-0.5" />}
                  </div>
                </button>

                {/* Option: Nghỉ trực */}
                <button
                  type="button"
                  onClick={() => {
                    setTypeFilter('LEAVE');
                    setIsTypeDropdownOpen(false);
                  }}
                  className={`w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs font-medium transition cursor-pointer text-left mt-0.5 ${
                    typeFilter === 'LEAVE'
                      ? 'bg-amber-50 dark:bg-amber-950/50 text-amber-700 dark:text-amber-300 font-semibold'
                      : 'text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/60'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <Calendar size={13} className="text-amber-600 dark:text-amber-400 shrink-0" />
                    <span>Nghỉ trực</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="px-1.5 py-0.5 rounded-full text-[11px] font-bold bg-amber-50 dark:bg-amber-950/80 text-amber-700 dark:text-amber-300 border border-amber-200/60 dark:border-amber-800">
                      {stats.leaves}
                    </span>
                    {typeFilter === 'LEAVE' && <Check size={13} className="text-amber-600 shrink-0 ml-0.5" />}
                  </div>
                </button>
              </div>
            )}
          </div>

          {/* Search Box */}
          <div className="schedule-search-box flex-1 sm:w-60 md:w-64 h-[38px]">
            <Search size={14} className="schedule-search-icon" />
            <input
              type="text"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="Tìm theo tên, mã NV, ca, lý do..."
              className="schedule-search-input text-xs !h-[38px]"
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

          {/* Refresh Button */}
          <button
            type="button"
            onClick={fetchRequests}
            disabled={loading}
            className="schedule-btn-secondary p-2.5 h-[38px] min-w-[38px] flex items-center justify-center rounded-xl cursor-pointer shrink-0"
            title="Làm mới danh sách"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin text-blue-600' : ''} />
          </button>
        </div>
      </div>

      {/* Error alert */}
      {error && (
        <div className="p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-300 dark:border-rose-800 rounded-xl text-rose-800 dark:text-rose-200 text-xs flex items-center gap-2">
          <AlertCircle size={15} />
          <span>{error}</span>
        </div>
      )}

      {/* 3. REQUESTS LIST */}
      {loading && requests.length === 0 ? (
        <div className="text-center py-16 text-slate-500 text-xs">
          Đang tải danh sách yêu cầu ca trực...
        </div>
      ) : filteredRequests.length === 0 ? (
        <div className="schedule-empty-state">
          <div
            className={`schedule-empty-state__icon ${
              statusFilter === 'APPROVED'
                ? 'schedule-empty-state__icon--emerald'
                : statusFilter === 'REJECTED'
                ? 'schedule-empty-state__icon--rose'
                : 'schedule-empty-state__icon--amber'
            }`}
          >
            {statusFilter === 'APPROVED' ? (
              <CheckCircle2 size={28} />
            ) : statusFilter === 'REJECTED' ? (
              <XCircle size={28} />
            ) : statusFilter === 'ALL' ? (
              <ClipboardList size={28} />
            ) : (
              <Clock size={28} />
            )}
          </div>
          <h3 className="schedule-empty-state__title">
            {statusFilter === 'PENDING'
              ? 'Không có yêu cầu nào đang chờ xử lý'
              : statusFilter === 'APPROVED'
              ? 'Chưa có yêu cầu nào được phê duyệt'
              : statusFilter === 'REJECTED'
              ? 'Không có yêu cầu nào bị từ chối'
              : 'Không tìm thấy yêu cầu phù hợp'}
          </h3>
          <p className="schedule-empty-state__desc">
            {searchKeyword
              ? `Không tìm thấy yêu cầu khớp với từ khóa "${searchKeyword}". Thử tìm kiếm với từ khóa khác.`
              : typeFilter !== 'ALL'
              ? `Không có đơn ${typeFilter === 'SWAP' ? 'đổi ca' : 'nghỉ trực'} nào trong mục này.`
              : statusFilter === 'PENDING'
              ? 'Tất cả đơn xin đổi ca và xin nghỉ từ nhân viên bảo vệ đã được phê duyệt hoặc xử lý hoàn tất.'
              : statusFilter === 'APPROVED'
              ? 'Các đơn sau khi được Quản lý chấp thuận sẽ hiển thị tại đây.'
              : statusFilter === 'REJECTED'
              ? 'Các đơn không hợp lệ hoặc bị từ chối sẽ hiển thị tại đây.'
              : 'Hiện tại hệ thống chưa ghi nhận đơn yêu cầu nào.'}
          </p>
          {(searchKeyword || typeFilter !== 'ALL' || statusFilter !== 'PENDING') && (
            <button
              type="button"
              onClick={() => {
                setStatusFilter('PENDING');
                setTypeFilter('ALL');
                setSearchKeyword('');
              }}
              className="mt-2 text-xs font-semibold text-blue-600 hover:text-blue-700 dark:text-blue-400 underline cursor-pointer"
            >
              Quay lại danh sách chờ duyệt
            </button>
          )}
        </div>
      ) : (
        <div className="space-y-4">
          {filteredRequests.map((req) => {
            const isPending = req.status === 'PENDING';
            const isApproved = req.status === 'APPROVED';
            const isRejected = req.status === 'REJECTED';
            const isSwap =
              req.requestType === 'SWAP' || req.requestType === 'SWAP_SHIFT';

            const rawDate = req.shiftDate || req.shift?.shiftDate || '';
            const displayDate = formatDateVN(rawDate);
            const startTime = (
              req.startTime ||
              req.shift?.startTime ||
              ''
            ).substring(0, 5);
            const endTime = (
              req.endTime ||
              req.shift?.endTime ||
              ''
            ).substring(0, 5);
            const requesterName =
              req.requesterName || req.requesterGuard?.fullName || 'Bảo vệ';
            const requesterCode =
              req.requesterCode || req.requesterGuard?.userCode || 'NV-BV';
            const substituteName =
              req.substituteGuardName ||
              req.targetSubstituteGuard?.fullName ||
              '';

            const isEmergency = req.isEmergency === true;
            const targetDate = req.targetShiftDate || req.targetShift?.shiftDate || '';
            const targetStartTime = (req.targetStartTime || req.targetShift?.startTime || '').substring(0, 5);
            const targetEndTime = (req.targetEndTime || req.targetShift?.endTime || '').substring(0, 5);
            const substituteCode = req.substituteGuardCode || req.targetSubstituteGuard?.userCode || '';
            const requesterTeamName = req.requesterTeamName || req.requesterGuard?.team?.teamName || '';
            const areaName = req.areaName || req.shift?.area?.name || '';
            const targetAreaName = req.targetAreaName || req.targetShift?.area?.name || '';
            const reviewNote = req.reviewNotes || req.reviewNote;

            // Shift categories
            const shift1Info = getShiftInfo(startTime, endTime);
            const targetShiftInfo = getShiftInfo(targetStartTime, targetEndTime);
            const Shift1Icon = shift1Info.icon;
            const TargetShiftIcon = targetShiftInfo.icon;

            return (
              <div key={req.id} className="schedule-request-card">
                {/* Header: Identity, Metadata, Badges */}
                <div className="schedule-request-header">
                  <div className="schedule-request-user">
                    <div
                      className={`schedule-request-avatar ${
                        isSwap
                          ? 'schedule-request-avatar--swap'
                          : isEmergency
                          ? 'schedule-request-avatar--emergency'
                          : 'schedule-request-avatar--leave'
                      }`}
                    >
                      {getInitials(requesterName)}
                    </div>
                    <div className="schedule-request-user-info">
                      <div className="schedule-request-user-top">
                        <span className="schedule-request-user-name">{requesterName}</span>
                        <span className="schedule-request-badge-code">{requesterCode}</span>
                        {requesterTeamName && (
                          <span className="schedule-request-badge-team">
                            {formatTeamName(requesterTeamName)}
                          </span>
                        )}
                        {req.createdAt && (
                          <span className="schedule-request-meta-time">
                            • Gửi lúc {formatDateTimeVN(req.createdAt)}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className="schedule-request-header-tags">
                    {/* Type pill */}
                    <span
                      className={`schedule-type-pill ${
                        isSwap
                          ? 'schedule-type-pill--swap'
                          : isEmergency
                          ? 'schedule-type-pill--emergency'
                          : 'schedule-type-pill--leave'
                      }`}
                    >
                      {isSwap ? (
                        <ArrowRightLeft size={13} />
                      ) : isEmergency ? (
                        <AlertTriangle size={13} />
                      ) : (
                        <Calendar size={13} />
                      )}
                      <span>
                        {isSwap
                          ? 'Đổi Ca Trực'
                          : isEmergency
                          ? 'Nghỉ Đột Xuất'
                          : 'Nghỉ Phép Thường'}
                      </span>
                    </span>

                    {/* Status pill */}
                    <span
                      className={`schedule-status-pill ${
                        isPending
                          ? 'schedule-status-pill--pending'
                          : isApproved
                          ? 'schedule-status-pill--approved'
                          : 'schedule-status-pill--rejected'
                      }`}
                    >
                      {isPending ? (
                        <Clock size={13} />
                      ) : isApproved ? (
                        <Check size={13} />
                      ) : (
                        <X size={13} />
                      )}
                      <span>
                        {isPending
                          ? 'Chờ Duyệt'
                          : isApproved
                          ? 'Đã Duyệt'
                          : 'Đã Từ Chối'}
                      </span>
                    </span>
                  </div>
                </div>

                {/* Shift Details Flow */}
                {isSwap ? (
                  <div className="schedule-shifts-grid schedule-shifts-grid--swap">
                    {/* Ca người gửi */}
                    <div className="schedule-shift-block">
                      <div className="schedule-shift-block__header">
                        <span className="schedule-shift-block__label">
                          <User size={13} /> Ca gửi đổi
                        </span>
                        <span className="schedule-shift-block__guard">{requesterName}</span>
                      </div>
                      <div className="schedule-shift-block__details">
                        <span className={`schedule-shift-pill ${shift1Info.color}`}>
                          <Shift1Icon size={13} /> {shift1Info.label}: {shift1Info.time}
                        </span>
                        <span className="schedule-shift-tag">
                          <Calendar size={13} /> {displayDate}
                        </span>
                        {areaName && (
                          <span className="schedule-shift-tag">
                            <MapPin size={13} /> {areaName}
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Center Arrow */}
                    <div className="schedule-shift-swap-arrow">
                      <div className="schedule-shift-swap-circle" title="Hoán đổi ca trực 2 chiều">
                        <ArrowRightLeft size={16} />
                      </div>
                    </div>

                    {/* Ca người nhận */}
                    <div className="schedule-shift-block">
                      <div className="schedule-shift-block__header">
                        <span className="schedule-shift-block__label">
                          <UserCheck size={13} /> Ca nhận đổi
                        </span>
                        <span className="schedule-shift-block__guard">
                          {substituteName || 'Người nhận đổi'}
                          {substituteCode && (
                            <span className="font-normal text-xs text-slate-500 ml-1">
                              ({substituteCode})
                            </span>
                          )}
                        </span>
                      </div>
                      {targetDate ? (
                        <div className="schedule-shift-block__details">
                          <span className={`schedule-shift-pill ${targetShiftInfo.color}`}>
                            <TargetShiftIcon size={13} /> {targetShiftInfo.label}: {targetShiftInfo.time}
                          </span>
                          <span className="schedule-shift-tag">
                            <Calendar size={13} /> {formatDateVN(targetDate)}
                          </span>
                          {targetAreaName && (
                            <span className="schedule-shift-tag">
                              <MapPin size={13} /> {targetAreaName}
                            </span>
                          )}
                        </div>
                      ) : (
                        <div className="text-xs italic text-slate-400 py-1">
                          Chưa có thông tin ca đối ứng
                        </div>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="schedule-shifts-grid schedule-shifts-grid--leave">
                    {/* Ca xin nghỉ */}
                    <div className="schedule-shift-block">
                      <div className="schedule-shift-block__header">
                        <span className="schedule-shift-block__label">
                          <Calendar size={13} /> Ca xin nghỉ
                        </span>
                      </div>
                      <div className="schedule-shift-block__details">
                        <span className={`schedule-shift-pill ${shift1Info.color}`}>
                          <Shift1Icon size={13} /> {shift1Info.label}: {shift1Info.time}
                        </span>
                        <span className="schedule-shift-tag">
                          <Calendar size={13} /> {displayDate}
                        </span>
                        {areaName && (
                          <span className="schedule-shift-tag">
                            <MapPin size={13} /> {areaName}
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Nhân sự trực thay */}
                    <div className="schedule-shift-block">
                      <div className="schedule-shift-block__header">
                        <span className="schedule-shift-block__label">
                          <UserCheck size={13} /> Nhân sự trực thay thế
                        </span>
                      </div>
                      <div className="schedule-shift-block__details">
                        {substituteName ? (
                          <span className="schedule-substitute-assigned">
                            <Check size={14} />
                            <span>
                              Đã chỉ định: <strong>{substituteName}</strong> ({substituteCode})
                            </span>
                          </span>
                        ) : (
                          <span className="schedule-substitute-pending">
                            <AlertCircle size={14} />
                            <span>Chưa chỉ định — Quản lý sẽ chọn khi duyệt</span>
                          </span>
                        )}
                      </div>
                    </div>
                  </div>
                )}

                {/* Lý do từ nhân viên */}
                {req.reason && (
                  <div className="schedule-request-reason">
                    <span className="schedule-request-reason__label">Lý do:</span>
                    <span className="schedule-request-reason__text">"{req.reason}"</span>
                  </div>
                )}

                {/* Phản hồi / Ghi chú phê duyệt */}
                {reviewNote && (
                  <div className={`schedule-review-feedback ${isRejected ? 'is-rejected' : 'is-approved'}`}>
                    <span className="font-semibold">
                      {isRejected ? 'Lý do từ chối của Quản lý:' : 'Ghi chú duyệt:'}
                    </span>
                    <span>"{reviewNote}"</span>
                  </div>
                )}

                {/* Actions Footer */}
                {isPending && (
                  <div className="schedule-request-actions">
                    <div className="schedule-request-actions__hint">
                      {isSwap
                        ? 'Duyệt đổi ca sẽ tự động hoán đổi lịch trực của 2 nhân viên'
                        : 'Duyệt xin nghỉ sẽ mở danh sách chọn nhân viên trực thay'}
                    </div>
                    <div className="schedule-request-actions__btns">
                      <button
                        type="button"
                        onClick={() => handleOpenReject(req)}
                        className="schedule-btn-reject"
                      >
                        <X size={14} />
                        <span>Từ Chối</span>
                      </button>

                      <button
                        type="button"
                        onClick={() =>
                          isSwap ? handleApproveSwap(req) : handleOpenApproveLeave(req)
                        }
                        className={`schedule-btn-approve ${
                          isSwap ? '' : 'schedule-btn-approve--emerald'
                        }`}
                      >
                        <Check size={14} />
                        <span>{isSwap ? 'Duyệt Đổi Ca' : 'Duyệt Nghỉ Trực'}</span>
                      </button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* 4. MODAL: APPROVE LEAVE WITH SUBSTITUTE */}
      {leaveModal.isOpen && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !leaveModal.submitting) {
              setLeaveModal((prev) => ({ ...prev, isOpen: false }));
            }
          }}
        >
          <div className="schedule-modal max-w-lg">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge">
                  <UserX size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">Duyệt Yêu Cầu Xin Nghỉ Trực</h3>
                  <p className="schedule-modal__subtitle">
                    Chỉ định nhân viên trực thay thế hoặc hủy hẳn ca trực
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => setLeaveModal((prev) => ({ ...prev, isOpen: false }))}
                disabled={leaveModal.submitting}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            <form onSubmit={handleSubmitApproveLeave}>
              <div className="schedule-modal__body space-y-4">
                {/* Summary Info */}
                <div className="p-3.5 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 text-xs space-y-1.5">
                  <div className="flex justify-between items-center">
                    <span className="text-slate-500">Nhân viên xin nghỉ:</span>
                    <strong className="text-slate-800 dark:text-slate-200">
                      {leaveModal.request?.requesterName ||
                        leaveModal.request?.requesterGuard?.fullName}{' '}
                      ({leaveModal.request?.requesterCode ||
                        leaveModal.request?.requesterGuard?.userCode ||
                        'NV-BV'})
                    </strong>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-slate-500">Ngày trực:</span>
                    <strong className="text-slate-800 dark:text-slate-200">
                      {formatDateVN(
                        leaveModal.request?.shiftDate ||
                          leaveModal.request?.shift?.shiftDate
                      )}
                    </strong>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-slate-500">Loại đơn:</span>
                    <span
                      className={`font-semibold px-2 py-0.5 rounded text-[11px] ${
                        leaveModal.request?.isEmergency
                          ? 'bg-rose-100 text-rose-700 dark:bg-rose-900/50 dark:text-rose-300'
                          : 'bg-amber-100 text-amber-700 dark:bg-amber-900/50 dark:text-amber-300'
                      }`}
                    >
                      {leaveModal.request?.isEmergency
                        ? 'Nghỉ Đột Xuất'
                        : 'Nghỉ Phép Thường'}
                    </span>
                  </div>
                  <div className="pt-1 border-t border-slate-200/60 dark:border-slate-700/60">
                    <span className="text-slate-500">Lý do: </span>
                    <span className="italic text-slate-700 dark:text-slate-300">
                      "{leaveModal.request?.reason || 'Không ghi rõ'}"
                    </span>
                  </div>
                </div>

                <div>
                  <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1.5">
                    Chọn Nhân Viên Bảo Vệ Trực Thay <span className="text-rose-500">*</span>
                  </label>
                  {leaveModal.loadingSubstitutes ? (
                    <div className="text-xs text-slate-500 py-3 flex items-center gap-2">
                      <RefreshCw size={14} className="animate-spin text-blue-600" />
                      <span>Đang tìm kiếm nhân viên bảo vệ rảnh ca...</span>
                    </div>
                  ) : (
                    <select
                      value={leaveModal.selectedSubstituteId}
                      onChange={(e) =>
                        setLeaveModal((prev) => ({
                          ...prev,
                          selectedSubstituteId: e.target.value
                        }))
                      }
                      required
                      className="w-full px-3 py-2.5 text-xs sm:text-sm rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500 shadow-2xs"
                    >
                      <option value="">-- Chọn bảo vệ trực thay --</option>
                      {leaveModal.substitutes.map((s) => (
                        <option key={s.id} value={s.id}>
                          {s.fullName} ({s.userCode || 'NV-BV'}) —{' '}
                          {s.isSameTeam ? `[Cùng đội] ${s.teamName}` : s.teamName}
                        </option>
                      ))}
                      <option value="CANCEL_SHIFT">
                        -- Hủy ca trực này (Không cần người trực thay) --
                      </option>
                    </select>
                  )}
                  <p className="text-[11px] text-slate-400 mt-1.5">
                    Hệ thống tự động lọc các bảo vệ đang nghỉ trong ngày và đảm bảo nhịp sinh học nghỉ ngơi (ưu tiên cùng đội).
                  </p>
                </div>
              </div>

              <div className="schedule-modal__footer flex items-center justify-end gap-2.5">
                <button
                  type="button"
                  onClick={() => setLeaveModal((prev) => ({ ...prev, isOpen: false }))}
                  disabled={leaveModal.submitting}
                  className="schedule-btn-secondary"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={leaveModal.submitting || !leaveModal.selectedSubstituteId}
                  className="schedule-btn-primary"
                >
                  {leaveModal.submitting ? 'Đang Xử Lý...' : 'Xác Nhận Phê Duyệt'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* 5. MODAL: REJECT DIALOG */}
      {rejectDialog.isOpen && (
        <div
          className="schedule-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !rejectDialog.submitting) {
              setRejectDialog((prev) => ({ ...prev, isOpen: false }));
            }
          }}
        >
          <div className="schedule-modal max-w-md">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge bg-rose-50 text-rose-600 dark:bg-rose-950/60 dark:text-rose-400">
                  <XCircle size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">Từ Chối Yêu Cầu Ca Trực</h3>
                  <p className="schedule-modal__subtitle">
                    Nhập lý do để thông báo lại cho nhân viên bảo vệ
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => setRejectDialog((prev) => ({ ...prev, isOpen: false }))}
                disabled={rejectDialog.submitting}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            <form onSubmit={handleSubmitReject}>
              <div className="schedule-modal__body space-y-3">
                <p className="text-xs text-slate-600 dark:text-slate-300">
                  Bạn đang từ chối yêu cầu của{' '}
                  <strong className="text-slate-900 dark:text-white">
                    {rejectDialog.request?.requesterName ||
                      rejectDialog.request?.requesterGuard?.fullName}
                  </strong>
                  .
                </p>

                <div>
                  <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1.5">
                    Lý Do Từ Chối (Tùy chọn)
                  </label>
                  <textarea
                    rows={3}
                    placeholder="Ví dụ: Thiếu nhân sự trực chốt, không tìm được người thay thế hợp lệ..."
                    value={rejectDialog.reviewNote}
                    onChange={(e) =>
                      setRejectDialog((prev) => ({
                        ...prev,
                        reviewNote: e.target.value
                      }))
                    }
                    className="w-full px-3 py-2 text-xs sm:text-sm rounded-xl border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500 shadow-2xs"
                  />
                </div>
              </div>

              <div className="schedule-modal__footer flex items-center justify-end gap-2.5">
                <button
                  type="button"
                  onClick={() => setRejectDialog((prev) => ({ ...prev, isOpen: false }))}
                  disabled={rejectDialog.submitting}
                  className="schedule-btn-secondary"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={rejectDialog.submitting}
                  className="px-4 py-2 rounded-xl text-xs font-semibold text-white bg-rose-600 hover:bg-rose-700 transition shadow-2xs"
                >
                  {rejectDialog.submitting ? 'Đang Lưu...' : 'Xác Nhận Từ Chối'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
