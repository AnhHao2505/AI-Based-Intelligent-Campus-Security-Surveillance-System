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
  { key: 'PENDING', label: 'Chờ Duyệt', icon: Clock },
  { key: 'APPROVED', label: 'Đã Duyệt', icon: CheckCircle2 },
  { key: 'REJECTED', label: 'Từ Chối', icon: XCircle },
  { key: 'ALL', label: 'Tất Cả', icon: Filter }
];

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
        <div className="schedule-filter-group">
          {STATUS_FILTERS.map((f) => {
            const Icon = f.icon;
            const isActive = statusFilter === f.key;
            return (
              <button
                key={f.key}
                type="button"
                onClick={() => setStatusFilter(f.key)}
                className={`schedule-filter-btn ${isActive ? 'schedule-filter-btn--active' : ''}`}
              >
                {Icon && <Icon size={15} />}
                <span>{f.label}</span>
                {f.key === 'PENDING' && pendingCount > 0 && (
                  <span className="schedule-filter-badge">
                    {pendingCount}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {/* Search & Refresh */}
        <div className="flex items-center gap-2">
          <div className="schedule-search-box flex-1 sm:w-72">
            <Search size={15} className="schedule-search-icon" />
            <input
              type="text"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="Tìm theo người gửi, lý do..."
              className="schedule-search-input"
            />
            {searchKeyword && (
              <button
                type="button"
                onClick={() => setSearchKeyword('')}
                className="schedule-search-clear"
                title="Xóa tìm kiếm"
              >
                <X size={14} />
              </button>
            )}
          </div>

          <button
            type="button"
            onClick={fetchRequests}
            disabled={loading}
            className="schedule-btn-secondary p-2 h-[38px] flex items-center justify-center"
            title="Làm mới danh sách"
          >
            <RefreshCw size={15} className={loading ? 'animate-spin' : ''} />
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
          Đang tải danh sách yêu cầu đổi/nghỉ ca...
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
        <div className="space-y-3">
          {filteredRequests.map((req) => {
            const isPending = req.status === 'PENDING';
            const isApproved = req.status === 'APPROVED';
            const isRejected = req.status === 'REJECTED';
            const isSwap =
              req.requestType === 'SWAP' || req.requestType === 'SWAP_SHIFT';

            const shiftDate = req.shiftDate || req.shift?.shiftDate || '';
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

            return (
              <div
                key={req.id}
                className="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl p-4 sm:p-5 shadow-sm hover:shadow transition flex flex-col md:flex-row items-start md:items-center justify-between gap-4"
              >
                {/* Left: Request info */}
                <div className="flex items-start gap-3.5 flex-1">
                  <div
                    className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 ${
                      isSwap
                        ? 'bg-blue-50 dark:bg-blue-950/70 text-blue-600 dark:text-blue-400 border border-blue-200 dark:border-blue-800'
                        : 'bg-amber-50 dark:bg-amber-950/70 text-amber-600 dark:text-amber-400 border border-amber-200 dark:border-amber-800'
                    }`}
                  >
                    {isSwap ? <ArrowRightLeft size={18} /> : <UserX size={18} />}
                  </div>

                  <div className="space-y-1">
                    <div className="flex items-center flex-wrap gap-2">
                      <span className="font-bold text-sm text-slate-900 dark:text-white">
                        {requesterName}
                      </span>
                      <span className="text-[11px] font-mono text-slate-500 dark:text-slate-400">
                        ({requesterCode})
                      </span>
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                          isSwap
                            ? 'bg-blue-100 text-blue-800 dark:bg-blue-900/60 dark:text-blue-300'
                            : 'bg-amber-100 text-amber-800 dark:bg-amber-900/60 dark:text-amber-300'
                        }`}
                      >
                        {isSwap ? 'Xin Đổi Ca' : 'Xin Nghỉ Trực'}
                      </span>
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                          isPending
                            ? 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
                            : isApproved
                            ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                            : 'bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300'
                        }`}
                      >
                        {isPending
                          ? 'Chờ Duyệt'
                          : isApproved
                          ? 'Đã Duyệt'
                          : 'Đã Từ Chối'}
                      </span>
                    </div>

                    {/* Shift Details */}
                    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-600 dark:text-slate-300 pt-1">
                      <span className="flex items-center gap-1">
                        <Calendar size={13} className="text-slate-400" />
                        <strong>Ngày trực:</strong> {shiftDate}
                      </span>
                      <span className="flex items-center gap-1">
                        <Clock size={13} className="text-slate-400" />
                        <strong>Giờ trực:</strong> {startTime} — {endTime}
                      </span>
                      {isSwap && substituteName && (
                        <span className="flex items-center gap-1 text-blue-600 dark:text-blue-400">
                          <UserCheck size={13} />
                          <strong>Trực thay:</strong> {substituteName}
                        </span>
                      )}
                    </div>

                    {/* Reason */}
                    {req.reason && (
                      <div className="text-xs text-slate-500 dark:text-slate-400 italic pt-0.5">
                        "{req.reason}"
                      </div>
                    )}

                    {/* Review Note */}
                    {req.reviewNote && (
                      <div className="text-[11px] text-slate-500 dark:text-slate-400 pt-0.5 flex items-center gap-1">
                        <MessageSquare size={12} />
                        <span>Ghi chú xét duyệt: {req.reviewNote}</span>
                      </div>
                    )}
                  </div>
                </div>

                {/* Right: Actions */}
                {isPending && (
                  <div className="flex items-center gap-2 self-end md:self-center w-full md:w-auto justify-end border-t md:border-t-0 pt-2 md:pt-0">
                    <button
                      type="button"
                      onClick={() => handleOpenReject(req)}
                      className="px-3 py-1.5 rounded-lg text-xs font-semibold text-rose-700 dark:text-rose-300 bg-rose-50 hover:bg-rose-100 dark:bg-rose-950/50 dark:hover:bg-rose-900/60 border border-rose-200 dark:border-rose-800 transition flex items-center gap-1"
                    >
                      <X size={14} />
                      <span>Từ Chối</span>
                    </button>

                    {isSwap ? (
                      <button
                        type="button"
                        onClick={() => handleApproveSwap(req)}
                        className="px-3.5 py-1.5 rounded-lg text-xs font-semibold text-white bg-blue-600 hover:bg-blue-700 shadow-sm transition flex items-center gap-1"
                      >
                        <Check size={14} />
                        <span>Duyệt Đổi Ca</span>
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={() => handleOpenApproveLeave(req)}
                        className="px-3.5 py-1.5 rounded-lg text-xs font-semibold text-white bg-emerald-600 hover:bg-emerald-700 shadow-sm transition flex items-center gap-1"
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
                    {leaveModal.request?.shiftDate ||
                      leaveModal.request?.shift?.shiftDate}
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
                          {s.fullName} ({s.userCode})
                        </option>
                      ))}
                      <option value="CANCEL_SHIFT">
                        -- Hủy ca trực này (Không cần người trực thay) --
                      </option>
                    </select>
                  )}
                  <p className="text-[11px] text-slate-400 mt-1">
                    Hệ thống tự động lọc ra các bảo vệ đang không có ca trùng giờ vào ngày này.
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
