import React, { useState, useEffect, useMemo } from 'react';
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
  HelpCircle
} from 'lucide-react';
import { guardScheduleApi } from '../../api/guardScheduleApi';

const STATUS_FILTERS = [
  {
    key: 'PENDING',
    label: 'Chờ Duyệt',
    icon: Clock,
    activeClass: 'bg-amber-500 hover:bg-amber-600 text-white border-amber-600 shadow-sm',
    inactiveClass: 'bg-amber-50/70 hover:bg-amber-100 dark:bg-amber-950/40 dark:hover:bg-amber-900/50 text-amber-700 dark:text-amber-300 border-amber-200/80 dark:border-amber-800/80',
    iconClass: 'text-amber-500'
  },
  {
    key: 'APPROVED',
    label: 'Đã Duyệt',
    icon: CheckCircle2,
    activeClass: 'bg-emerald-600 hover:bg-emerald-700 text-white border-emerald-700 shadow-sm',
    inactiveClass: 'bg-emerald-50/70 hover:bg-emerald-100 dark:bg-emerald-950/40 dark:hover:bg-emerald-900/50 text-emerald-700 dark:text-emerald-300 border-emerald-200/80 dark:border-emerald-800/80',
    iconClass: 'text-emerald-500'
  },
  {
    key: 'REJECTED',
    label: 'Từ Chối',
    icon: XCircle,
    activeClass: 'bg-rose-600 hover:bg-rose-700 text-white border-rose-700 shadow-sm',
    inactiveClass: 'bg-rose-50/70 hover:bg-rose-100 dark:bg-rose-950/40 dark:hover:bg-rose-900/50 text-rose-700 dark:text-rose-300 border-rose-200/80 dark:border-rose-800/80',
    iconClass: 'text-rose-500'
  },
  {
    key: 'ALL',
    label: 'Tất Cả',
    icon: Filter,
    activeClass: 'bg-blue-600 hover:bg-blue-700 text-white border-blue-700 shadow-sm',
    inactiveClass: 'bg-slate-50 hover:bg-slate-100 dark:bg-slate-800/60 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-300 border-slate-200/80 dark:border-slate-700/80',
    iconClass: 'text-blue-500'
  }
];

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
      return `${hours}:${mins} ${day}-${month}-${year}`;
    }
  } catch (e) {
    // ignore
  }
  return formatDateVN(dateStr);
};

export default function ShiftRequestsTab({ onRequestsUpdated }) {
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const [statusFilter, setStatusFilter] = useState('PENDING');
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

  // Fetch Requests
  const fetchRequests = async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {};
      if (statusFilter !== 'ALL') {
        params.status = statusFilter;
      }
      const res = await guardScheduleApi.getShiftRequests(params);
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
  }, [statusFilter]);

  // Filter by search
  const filteredRequests = useMemo(() => {
    if (!searchKeyword.trim()) return requests;
    const kw = searchKeyword.toLowerCase().trim();
    return requests.filter((r) => {
      const reqName = r.requesterName || r.requesterGuard?.fullName || '';
      const reqCode = r.requesterCode || r.requesterGuard?.userCode || '';
      const subName = r.substituteGuardName || r.targetSubstituteGuard?.fullName || '';
      const reason = r.reason || '';
      return (
        reqName.toLowerCase().includes(kw) ||
        reqCode.toLowerCase().includes(kw) ||
        subName.toLowerCase().includes(kw) ||
        reason.toLowerCase().includes(kw)
      );
    });
  }, [requests, searchKeyword]);

  // Approve Swap Shift
  const handleApproveSwap = async (req) => {
    if (
      !window.confirm(
        `Xác nhận duyệt yêu cầu đổi ca của ${req.requesterName || 'bảo vệ'}? Ca trực sẽ được chuyển sang người nhận trực thay.`
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

  const pendingCount = useMemo(() => {
    return requests.filter((r) => r.status === 'PENDING').length;
  }, [requests]);

  return (
    <div className="space-y-6">
      {/* 1. Header Toolbar & Filters */}
      <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3">
        {/* Status Filter Buttons */}
        <div className="flex flex-wrap items-center gap-2.5 sm:gap-3">
          {STATUS_FILTERS.map((f) => {
            const Icon = f.icon;
            const isActive = statusFilter === f.key;
            return (
              <button
                key={f.key}
                type="button"
                onClick={() => setStatusFilter(f.key)}
                className={`px-5 py-2.5 sm:py-3 rounded-xl text-sm font-bold border transition-all flex items-center gap-2 cursor-pointer shadow-xs ${
                  isActive ? f.activeClass : f.inactiveClass
                }`}
              >
                {Icon && <Icon size={18} className={isActive ? 'text-white' : f.iconClass} />}
                <span>{f.label}</span>
                {f.key === 'PENDING' && pendingCount > 0 && (
                  <span
                    className={`ml-1 px-2 py-0.5 rounded-full text-xs font-bold ${
                      isActive ? 'bg-white/30 text-white' : 'bg-amber-500 text-white shadow-xs'
                    }`}
                  >
                    {pendingCount}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {/* Search & Refresh */}
        <div className="flex items-center gap-2.5">
          <div className="schedule-search-box flex-1 sm:w-80 h-[44px]">
            <Search size={17} className="schedule-search-icon" />
            <input
              type="text"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="Tìm theo người gửi, lý do..."
              className="schedule-search-input text-sm"
            />
            {searchKeyword && (
              <button
                type="button"
                onClick={() => setSearchKeyword('')}
                className="schedule-search-clear"
                title="Xóa tìm kiếm"
              >
                <X size={15} />
              </button>
            )}
          </div>

          <button
            type="button"
            onClick={fetchRequests}
            disabled={loading}
            className="schedule-btn-secondary p-2.5 h-[44px] min-w-[44px] flex items-center justify-center rounded-xl"
            title="Làm mới danh sách"
          >
            <RefreshCw size={17} className={loading ? 'animate-spin' : ''} />
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

      {/* 2. Requests List */}
      {loading && requests.length === 0 ? (
        <div className="text-center py-16 text-slate-500 text-xs">
          Đang tải danh sách yêu cầu ca trực...
        </div>
      ) : filteredRequests.length === 0 ? (
        <div className="schedule-empty-state">
          <div className="schedule-empty-state__icon schedule-empty-state__icon--emerald">
            <CheckCircle2 size={28} />
          </div>
          <h3 className="schedule-empty-state__title">
            {statusFilter === 'PENDING'
              ? 'Không có yêu cầu nào đang chờ duyệt'
              : 'Không có yêu cầu nào trong danh mục này'}
          </h3>
          <p className="schedule-empty-state__desc">
            Khi nhân viên bảo vệ gửi đơn xin đổi ca hoặc nghỉ đột xuất qua hệ thống, đơn sẽ xuất hiện tại đây để Quản lý phê duyệt.
          </p>
        </div>
      ) : (
        <div className="space-y-3.5">
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

            return (
              <div
                key={req.id}
                className="bg-white dark:bg-slate-800 border border-slate-200/90 dark:border-slate-700/80 rounded-2xl p-4 sm:p-5 shadow-xs hover:shadow-md transition-all flex flex-col md:flex-row items-start md:items-center justify-between gap-4"
              >
                {/* Left: Request info */}
                <div className="flex items-start gap-3.5 flex-1 min-w-0">
                  <div
                    className={`w-11 h-11 rounded-2xl flex items-center justify-center flex-shrink-0 shadow-xs ${
                      isSwap
                        ? 'bg-blue-50 dark:bg-blue-950/60 text-blue-600 dark:text-blue-400 border border-blue-200/70 dark:border-blue-800/70'
                        : isEmergency
                        ? 'bg-rose-50 dark:bg-rose-950/60 text-rose-600 dark:text-rose-400 border border-rose-200/70 dark:border-rose-800/70'
                        : 'bg-amber-50 dark:bg-amber-950/60 text-amber-600 dark:text-amber-400 border border-amber-200/70 dark:border-amber-800/70'
                    }`}
                  >
                    {isSwap ? <ArrowRightLeft size={19} /> : <UserX size={19} />}
                  </div>

                  <div className="space-y-1.5 flex-1 min-w-0">
                    {/* Header: Name, Code, Type, Status */}
                    <div className="flex items-center flex-wrap gap-2">
                      <span className="font-bold text-sm text-slate-900 dark:text-white tracking-tight">
                        {requesterName}
                      </span>
                      <span className="text-[11px] font-mono font-medium px-2 py-0.5 rounded-md bg-slate-100 dark:bg-slate-700/70 text-slate-600 dark:text-slate-300">
                        {requesterCode}
                      </span>
                      <span
                        className={`px-2.5 py-0.5 rounded-full text-[11px] font-bold ${
                          isSwap
                            ? 'bg-blue-50 text-blue-700 border border-blue-200/80 dark:bg-blue-950/70 dark:text-blue-300 dark:border-blue-800'
                            : isEmergency
                            ? 'bg-rose-50 text-rose-700 border border-rose-200/80 dark:bg-rose-950/70 dark:text-rose-300 dark:border-rose-800'
                            : 'bg-amber-50 text-amber-700 border border-amber-200/80 dark:bg-amber-950/70 dark:text-amber-300 dark:border-amber-800'
                        }`}
                      >
                        {isSwap
                          ? 'Đổi Ca'
                          : isEmergency
                          ? 'Nghỉ Đột Xuất'
                          : 'Nghỉ Phép Thường'}
                      </span>
                      <span
                        className={`px-2.5 py-0.5 rounded-full text-[11px] font-bold flex items-center gap-1.5 ${
                          isPending
                            ? 'bg-amber-500/10 text-amber-700 dark:text-amber-300 border border-amber-500/30'
                            : isApproved
                            ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300 border border-emerald-500/30'
                            : 'bg-rose-500/10 text-rose-700 dark:text-rose-300 border border-rose-500/30'
                        }`}
                      >
                        <span
                          className={`w-1.5 h-1.5 rounded-full ${
                            isPending
                              ? 'bg-amber-500 animate-pulse'
                              : isApproved
                              ? 'bg-emerald-500'
                              : 'bg-rose-500'
                          }`}
                        />
                        {isPending
                          ? 'Chờ Duyệt'
                          : isApproved
                          ? 'Đã Duyệt'
                          : 'Đã Từ Chối'}
                      </span>

                      {req.createdAt && (
                        <span className="text-[11px] text-slate-400 dark:text-slate-500 ml-auto hidden sm:inline">
                          {formatDateTimeVN(req.createdAt)}
                        </span>
                      )}
                    </div>

                    {/* Shift Details: 2-way swap vs Leave */}
                    {isSwap ? (
                      <div className="flex flex-wrap items-center gap-2 pt-0.5 text-xs">
                        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-blue-50/80 dark:bg-blue-950/50 border border-blue-200/70 dark:border-blue-800/60 text-blue-900 dark:text-blue-200">
                          <Calendar size={13} className="text-blue-600 flex-shrink-0" />
                          <span>Ca trực:</span>
                          <strong className="font-semibold">{displayDate} ({startTime} — {endTime})</strong>
                        </div>

                        <ArrowRightLeft size={14} className="text-blue-500 flex-shrink-0" />

                        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-indigo-50/80 dark:bg-indigo-950/50 border border-indigo-200/70 dark:border-indigo-800/60 text-indigo-900 dark:text-indigo-200">
                          <UserCheck size={13} className="text-indigo-600 flex-shrink-0" />
                          <span>Đổi với <strong>{substituteName || 'Người nhận đổi'}</strong>{targetDate ? ':' : ''}</span>
                          {targetDate ? (
                            <strong className="font-semibold">
                              {formatDateVN(targetDate)} ({targetStartTime} — {targetEndTime})
                            </strong>
                          ) : (
                            <span className="text-slate-400 italic text-[11px]">(Đơn cũ)</span>
                          )}
                        </div>
                      </div>
                    ) : (
                      <div className="flex flex-wrap items-center gap-2 pt-0.5 text-xs text-slate-600 dark:text-slate-300">
                        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-slate-50 dark:bg-slate-900/50 border border-slate-200/70 dark:border-slate-700/60">
                          <Calendar size={13} className="text-blue-500 flex-shrink-0" />
                          <span className="text-slate-500 dark:text-slate-400">Ca xin nghỉ:</span>
                          <strong className="font-semibold text-slate-800 dark:text-slate-200">{displayDate}</strong>
                        </div>

                        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-slate-50 dark:bg-slate-900/50 border border-slate-200/70 dark:border-slate-700/60">
                          <Clock size={13} className="text-amber-500 flex-shrink-0" />
                          <span className="text-slate-500 dark:text-slate-400">Giờ trực:</span>
                          <strong className="font-semibold text-slate-800 dark:text-slate-200">{startTime} — {endTime}</strong>
                        </div>

                        {substituteName && (
                          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-emerald-50/80 dark:bg-emerald-950/50 border border-emerald-200/70 dark:border-emerald-800/60 text-emerald-800 dark:text-emerald-300">
                            <UserCheck size={13} className="text-emerald-600 flex-shrink-0" />
                            <span>Đã gán trực thay:</span>
                            <strong className="font-bold">{substituteName}</strong>
                          </div>
                        )}
                      </div>
                    )}

                    {/* Reason */}
                    {req.reason && (
                      <div className="text-xs text-slate-600 dark:text-slate-300 italic pt-0.5 px-3 py-1.5 rounded-lg bg-slate-50/80 dark:bg-slate-900/40 border-l-2 border-slate-300 dark:border-slate-600">
                        <span className="not-italic font-medium text-slate-400 dark:text-slate-500 mr-1">Lý do:</span>
                        "{req.reason}"
                      </div>
                    )}

                    {/* Review Note */}
                    {req.reviewNote && (
                      <div className="text-[11px] text-slate-500 dark:text-slate-400 pt-0.5 flex items-center gap-1.5">
                        <MessageSquare size={12} className="text-slate-400 flex-shrink-0" />
                        <span>Ghi chú duyệt: <strong>{req.reviewNote}</strong></span>
                      </div>
                    )}
                  </div>
                </div>

                {/* Right: Actions */}
                {isPending && (
                  <div className="flex items-center gap-2 self-end md:self-center w-full md:w-auto justify-end border-t md:border-t-0 pt-2.5 md:pt-0">
                    <button
                      type="button"
                      onClick={() => handleOpenReject(req)}
                      className="px-3.5 py-2 rounded-xl text-xs font-bold text-rose-600 dark:text-rose-400 bg-rose-50 hover:bg-rose-100 dark:bg-rose-950/40 dark:hover:bg-rose-900/50 border border-rose-200/80 dark:border-rose-800/80 transition flex items-center gap-1.5 shadow-xs cursor-pointer"
                    >
                      <X size={14} />
                      <span>Từ Chối</span>
                    </button>

                    {isSwap ? (
                      <button
                        type="button"
                        onClick={() => handleApproveSwap(req)}
                        className="px-4 py-2 rounded-xl text-xs font-bold text-white bg-blue-600 hover:bg-blue-700 active:bg-blue-800 shadow-sm hover:shadow transition flex items-center gap-1.5 cursor-pointer"
                      >
                        <Check size={14} />
                        <span>Duyệt Đổi Ca</span>
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={() => handleOpenApproveLeave(req)}
                        className="px-4 py-2 rounded-xl text-xs font-bold text-white bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 shadow-sm hover:shadow transition flex items-center gap-1.5 cursor-pointer"
                      >
                        <Check size={14} />
                        <span>Duyệt Nghỉ Trực</span>
                      </button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* 3. MODAL: APPROVE LEAVE WITH SUBSTITUTE */}
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
                <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 text-xs space-y-1">
                  <div>
                    <strong>Nhân viên xin nghỉ:</strong>{' '}
                    {leaveModal.request?.requesterName ||
                      leaveModal.request?.requesterGuard?.fullName}
                  </div>
                  <div>
                    <strong>Ngày trực:</strong>{' '}
                    {formatDateVN(leaveModal.request?.shiftDate ||
                      leaveModal.request?.shift?.shiftDate)}
                  </div>
                  <div>
                    <strong>Lý do:</strong>{' '}
                    <span className="italic">
                      {leaveModal.request?.reason || 'Không ghi rõ'}
                    </span>
                  </div>
                </div>

                <div>
                  <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1">
                    Chọn Nhân Viên Bảo Vệ Trực Thay <span className="text-rose-500">*</span>
                  </label>
                  {leaveModal.loadingSubstitutes ? (
                    <div className="text-xs text-slate-500 py-2">
                      Đang tìm kiếm nhân viên bảo vệ rảnh ca...
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
                      className="w-full px-3 py-2 text-xs rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200"
                    >
                      <option value="">-- Chọn bảo vệ trực thay --</option>
                      {leaveModal.substitutes.map((s) => (
                        <option key={s.id} value={s.id}>
                          {s.fullName} ({s.userCode || 'NV-BV'}) — {s.isSameTeam ? `[Cùng đội] ${s.teamName}` : s.teamName}
                        </option>
                      ))}
                      <option value="CANCEL_SHIFT">
                        -- Hủy ca trực này (Không cần người trực thay) --
                      </option>
                    </select>
                  )}
                  <p className="text-[11px] text-slate-400 mt-1">
                    Hệ thống tự động lọc các bảo vệ đang nghỉ trong ngày và đảm bảo nhịp sinh học nghỉ ngơi (ưu tiên cùng đội).
                  </p>
                </div>
              </div>

              <div className="schedule-modal__footer flex items-center justify-end gap-2">
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

      {/* 4. MODAL: REJECT DIALOG */}
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
                <div className="schedule-modal__icon-badge">
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
                  <strong>
                    {rejectDialog.request?.requesterName ||
                      rejectDialog.request?.requesterGuard?.fullName}
                  </strong>
                  .
                </p>

                <div>
                  <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1">
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
                    className="w-full px-3 py-2 text-xs rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
              </div>

              <div className="schedule-modal__footer flex items-center justify-end gap-2">
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
                  className="px-4 py-2 rounded-lg text-xs font-semibold text-white bg-rose-600 hover:bg-rose-700 transition"
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
