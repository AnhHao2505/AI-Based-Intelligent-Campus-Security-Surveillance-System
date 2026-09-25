import React, { useState, useEffect } from 'react';
import { X, Search, Check, AlertCircle, Clock, Calendar, CheckCircle2 } from 'lucide-react';
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

export default function ShiftRequestsModal({
  isOpen,
  onClose,
  onUpdated
}) {
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [statusFilter, setStatusFilter] = useState('PENDING'); // PENDING | APPROVED | REJECTED | ALL

  // Sub-modal for approving LEAVE request
  const [leaveSubModal, setLeaveSubModal] = useState({
    isOpen: false,
    request: null,
    substitutes: [],
    selectedSubstituteId: '',
    loadingSubstitutes: false,
    submitting: false
  });

  // Prompt for rejection note
  const [rejectDialog, setRejectDialog] = useState({
    isOpen: false,
    requestId: null,
    reviewNote: '',
    submitting: false
  });

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
    if (isOpen) {
      fetchRequests();
    }
  }, [isOpen, statusFilter]);

  // SWAP approve
  const handleApproveSwap = async (reqId) => {
    if (!window.confirm('Xác nhận duyệt yêu cầu đổi ca này? Ca trực sẽ được chuyển sang cho người nhận trực thay.')) {
      return;
    }
    setLoading(true);
    try {
      await guardScheduleApi.approveShiftRequest(reqId, {});
      await fetchRequests();
      if (onUpdated) onUpdated();
    } catch (err) {
      alert(err.message || 'Lỗi khi duyệt đổi ca');
    } finally {
      setLoading(false);
    }
  };

  // LEAVE approve modal opener
  const handleOpenApproveLeave = async (req) => {
    setLeaveSubModal({
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
        setLeaveSubModal((prev) => ({
          ...prev,
          substitutes: Array.isArray(subs) ? subs : [],
          loadingSubstitutes: false
        }));
      }
    } catch (err) {
      console.error('Lỗi lấy danh sách bảo vệ có thể trực thay:', err);
      setLeaveSubModal((prev) => ({
        ...prev,
        substitutes: [],
        loadingSubstitutes: false
      }));
    }
  };

  // LEAVE approve submit
  const handleSubmitApproveLeave = async (e) => {
    e.preventDefault();
    const req = leaveSubModal.request;
    if (!req) return;

    setLeaveSubModal((prev) => ({ ...prev, submitting: true }));
    try {
      const subId = leaveSubModal.selectedSubstituteId;
      const payload = {
        action: 'APPROVE',
        substituteGuardId: subId === 'CANCEL_SHIFT' || !subId ? null : subId
      };
      await guardScheduleApi.approveShiftRequest(req.id, payload);
      setLeaveSubModal({
        isOpen: false,
        request: null,
        substitutes: [],
        selectedSubstituteId: '',
        loadingSubstitutes: false,
        submitting: false
      });
      await fetchRequests();
      if (onUpdated) onUpdated();
    } catch (err) {
      alert(err.message || 'Lỗi khi duyệt xin nghỉ');
      setLeaveSubModal((prev) => ({ ...prev, submitting: false }));
    }
  };

  // Reject opener
  const handleOpenReject = (reqId) => {
    setRejectDialog({
      isOpen: true,
      requestId: reqId,
      reviewNote: '',
      submitting: false
    });
  };

  // Reject submit
  const handleSubmitReject = async (e) => {
    e.preventDefault();
    if (!rejectDialog.requestId) return;

    setRejectDialog((prev) => ({ ...prev, submitting: true }));
    try {
      await guardScheduleApi.rejectShiftRequest(rejectDialog.requestId, {
        reviewNotes: rejectDialog.reviewNote,
        reviewNote: rejectDialog.reviewNote
      });
      setRejectDialog({
        isOpen: false,
        requestId: null,
        reviewNote: '',
        submitting: false
      });
      await fetchRequests();
      if (onUpdated) onUpdated();
    } catch (err) {
      alert(err.message || 'Lỗi khi từ chối yêu cầu');
      setRejectDialog((prev) => ({ ...prev, submitting: false }));
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
      <div className="schedule-modal schedule-modal--lg max-w-4xl">
        <div className="schedule-modal__header">
          <div className="schedule-modal__header-left">
            <div className="schedule-modal__icon-badge">
              <Calendar size={18} />
            </div>
            <div className="schedule-modal__header-text">
              <h3 className="schedule-modal__title">Quản Lý Yêu Cầu Đổi / Nghỉ Ca</h3>
              <p className="schedule-modal__subtitle">
                Xét duyệt yêu cầu nhờ trực thay và xin nghỉ đột xuất của nhân viên bảo vệ
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

        {/* Filter Tabs */}
        <div className="px-6 pt-3 border-b border-slate-200 dark:border-slate-800 flex gap-2">
          {[
            { key: 'PENDING', label: 'Chờ duyệt' },
            { key: 'APPROVED', label: 'Đã duyệt' },
            { key: 'REJECTED', label: 'Từ chối' },
            { key: 'ALL', label: 'Tất cả' }
          ].map((tab) => (
            <button
              key={tab.key}
              type="button"
              onClick={() => setStatusFilter(tab.key)}
              className={`pb-2.5 px-3 text-xs font-bold border-b-2 transition ${
                statusFilter === tab.key
                  ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                  : 'border-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        <div className="schedule-modal__body max-h-[60vh] overflow-y-auto pr-1">
          {error && (
            <div className="p-3 mb-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-300 dark:border-rose-800 rounded-xl text-rose-800 dark:text-rose-200 text-xs flex items-center gap-2">
              <AlertCircle size={15} />
              <span>{error}</span>
            </div>
          )}

          {loading ? (
            <div className="text-center py-10 text-xs text-slate-500">Đang tải danh sách yêu cầu...</div>
          ) : requests.length === 0 ? (
            <div className="text-center py-12 text-slate-400 text-xs">
              Không có yêu cầu nào trong trạng thái này
            </div>
          ) : (
            <div className="space-y-3">
              {requests.map((req) => {
                const isPending = req.status === 'PENDING';
                const isRejected = req.status === 'REJECTED';
                const isSwap = req.requestType === 'SWAP' || req.requestType === 'SWAP_SHIFT';
                const shiftDate = req.shiftDate || req.shift?.shiftDate || '';
                const startTime = (req.startTime || req.shift?.startTime || '').substring(0, 5);
                const endTime = (req.endTime || req.shift?.endTime || '').substring(0, 5);
                const requesterName = req.requesterName || req.requesterGuard?.fullName || 'Bảo vệ';
                const requesterCode = req.requesterCode || req.requesterGuard?.userCode || 'NV-BV';
                const substituteName = req.substituteGuardName || req.targetSubstituteGuard?.fullName;
                const substituteCode = req.substituteGuardCode || req.targetSubstituteGuard?.userCode;
                const reviewNote = req.reviewNotes || req.reviewNote;
                const isEmergency = req.isEmergency === true;
                const targetDate = req.targetShiftDate || req.targetShift?.shiftDate || '';
                const targetStartTime = (req.targetStartTime || req.targetShift?.startTime || '').substring(0, 5);
                const targetEndTime = (req.targetEndTime || req.targetShift?.endTime || '').substring(0, 5);

                return (
                  <div
                    key={req.id}
                    className="p-4 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 flex flex-col md:flex-row md:items-center justify-between gap-4"
                  >
                    <div className="space-y-1.5 min-w-0">
                      {/* Top Badges */}
                      <div className="flex items-center gap-2">
                        <span
                          className={`px-2 py-0.5 rounded text-[11px] font-bold ${
                            isSwap
                              ? 'bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300'
                              : isEmergency
                              ? 'bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300'
                              : 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300'
                          }`}
                        >
                          {isSwap
                            ? '[Đổi ca]'
                            : isEmergency
                            ? '[Nghỉ đột xuất]'
                            : '[Nghỉ phép thường]'}
                        </span>

                        <span
                          className={`px-2 py-0.5 rounded text-[11px] font-semibold ${
                            req.status === 'APPROVED'
                              ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                              : req.status === 'REJECTED'
                              ? 'bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300'
                              : 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-300'
                          }`}
                        >
                          {req.status === 'APPROVED'
                            ? 'Đã duyệt'
                            : req.status === 'REJECTED'
                            ? 'Từ chối'
                            : 'Chờ duyệt'}
                        </span>

                        {req.createdAt && (
                          <span className="text-[10px] text-slate-400">
                            {new Date(req.createdAt).toLocaleDateString('vi-VN')}
                          </span>
                        )}
                      </div>

                      {/* Requester & Shift Info */}
                      <div className="text-xs text-slate-800 dark:text-slate-100 font-semibold">
                        <span>{requesterName} ({requesterCode})</span>
                        <span className="mx-1 text-slate-400">•</span>
                        <span>Ca trực: {formatDateVN(shiftDate)} ({startTime} - {endTime})</span>
                      </div>

                      {/* Detail note / substitute */}
                      {isSwap ? (
                        <div className="text-xs text-slate-600 dark:text-slate-300">
                          Đổi với: <strong className="text-slate-900 dark:text-white">{substituteName}</strong> {substituteCode ? `(${substituteCode})` : ''} {targetDate && (
                            <span>— <strong>{formatDateVN(targetDate)} ({targetStartTime} — {targetEndTime})</strong></span>
                          )}
                        </div>
                      ) : (
                        <div className="text-xs text-slate-600 dark:text-slate-300">
                          Người trực thay được duyệt: {substituteName ? (
                            <strong className="text-slate-900 dark:text-white">{substituteName}</strong>
                          ) : (
                            <span className="text-slate-400 italic">Chưa chỉ định / Hủy ca</span>
                          )}
                        </div>
                      )}

                      {req.reason && (
                        <div className="text-xs text-slate-500 dark:text-slate-400 italic bg-slate-50 dark:bg-slate-900/40 p-2 rounded border border-slate-100 dark:border-slate-800">
                          Lý do: "{req.reason}"
                        </div>
                      )}

                      {reviewNote && (
                        <div className={`text-xs p-2 rounded-lg border flex items-start gap-1.5 ${
                          isRejected
                            ? 'bg-rose-50 dark:bg-rose-950/40 border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-200'
                            : 'bg-slate-50 dark:bg-slate-900/40 border-slate-200 dark:border-slate-800 text-slate-600 dark:text-slate-300'
                        }`}>
                          <span className="font-bold mr-1">
                            {isRejected ? 'Lý do từ chối của Quản lý:' : 'Ghi chú duyệt:'}
                          </span>
                          <span>"{reviewNote}"</span>
                        </div>
                      )}
                    </div>

                    {/* Action buttons (only when PENDING) */}
                    {isPending && (
                      <div className="flex items-center gap-2 self-end md:self-center shrink-0">
                        {isSwap ? (
                          <button
                            type="button"
                            onClick={() => handleApproveSwap(req.id)}
                            className="px-3 py-1.5 rounded-lg text-xs font-bold bg-emerald-600 hover:bg-emerald-700 text-white transition shadow-sm"
                          >
                            Duyệt Đổi Ca
                          </button>
                        ) : (
                          <button
                            type="button"
                            onClick={() => handleOpenApproveLeave(req)}
                            className="px-3 py-1.5 rounded-lg text-xs font-bold bg-blue-600 hover:bg-blue-700 text-white transition shadow-sm"
                          >
                            Duyệt Nghỉ
                          </button>
                        )}

                        <button
                          type="button"
                          onClick={() => handleOpenReject(req.id)}
                          className="px-3 py-1.5 rounded-lg text-xs font-bold bg-slate-100 hover:bg-slate-200 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 transition"
                        >
                          Từ Chối
                        </button>
                      </div>
                    )}
                  </div>
                );
              })}
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

      {/* SUB-MODAL: APPROVE LEAVE REQUEST */}
      {leaveSubModal.isOpen && (
        <div
          className="schedule-modal-backdrop z-50"
          onClick={(e) => {
            if (e.target === e.currentTarget && !leaveSubModal.submitting) {
              setLeaveSubModal((prev) => ({ ...prev, isOpen: false }));
            }
          }}
        >
          <div className="schedule-modal schedule-modal--md max-w-md">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__icon-badge">
                  <CheckCircle2 size={18} />
                </div>
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">Duyệt Yêu Cầu Nghỉ Đột Xuất</h3>
                  <p className="schedule-modal__subtitle">
                    Chỉ định nhân sự trực thay hoặc hủy ca trực
                  </p>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => setLeaveSubModal((prev) => ({ ...prev, isOpen: false }))}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            <form onSubmit={handleSubmitApproveLeave}>
              <div className="schedule-modal__body space-y-4">
                <div className="p-3 bg-slate-50 dark:bg-slate-800/60 rounded-lg text-xs space-y-1">
                  <div>Nhân viên xin nghỉ: <strong>{leaveSubModal.request?.requesterName || leaveSubModal.request?.requesterGuard?.fullName || 'Bảo vệ'}</strong></div>
                  <div>Ca trực: <strong>{formatDateVN(leaveSubModal.request?.shiftDate || leaveSubModal.request?.shift?.shiftDate)} ({(leaveSubModal.request?.startTime || leaveSubModal.request?.shift?.startTime || '').substring(0, 5)} - {(leaveSubModal.request?.endTime || leaveSubModal.request?.shift?.endTime || '').substring(0, 5)})</strong></div>
                  {leaveSubModal.request?.reason && (
                    <div className="text-slate-500 italic">"{leaveSubModal.request.reason}"</div>
                  )}
                </div>

                <div className="space-y-1.5">
                  <label className="block text-xs font-bold text-slate-700 dark:text-slate-300">
                    Chọn Nhân Sự Trực Thay (Có Ngày Nghỉ)
                  </label>
                  {leaveSubModal.loadingSubstitutes ? (
                    <div className="text-xs text-slate-400 py-2">Đang tìm nhân sự đủ điều kiện...</div>
                  ) : (
                    <select
                      value={leaveSubModal.selectedSubstituteId}
                      onChange={(e) =>
                        setLeaveSubModal((prev) => ({
                          ...prev,
                          selectedSubstituteId: e.target.value
                        }))
                      }
                      className="w-full px-3 py-2 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200"
                    >
                      <option value="">-- Chọn nhân viên bảo vệ trực thay --</option>
                      {leaveSubModal.substitutes.map((sub) => (
                        <option key={sub.id} value={sub.id}>
                          {sub.fullName} ({sub.userCode || 'NV-BV'}) — {sub.isSameTeam ? `[Cùng đội] ${sub.teamName}` : (sub.teamName || 'Chưa phân đội')}
                        </option>
                      ))}
                      <option value="CANCEL_SHIFT">Không có người thay (Hủy bỏ ca trực này)</option>
                    </select>
                  )}
                  <p className="text-[11px] text-slate-500">
                    Danh sách hiển thị tất cả bảo vệ có lịch nghỉ trong ngày (ưu tiên cùng đội) và đảm bảo an toàn nghỉ ngơi.
                  </p>
                </div>
              </div>

              <div className="schedule-modal__footer">
                <button
                  type="button"
                  onClick={() => setLeaveSubModal((prev) => ({ ...prev, isOpen: false }))}
                  disabled={leaveSubModal.submitting}
                  className="schedule-btn-modal schedule-btn-modal--cancel"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={leaveSubModal.submitting}
                  className="schedule-btn-modal schedule-btn-modal--submit"
                >
                  {leaveSubModal.submitting ? 'Đang Xử Lý...' : 'Xác Nhận Duyệt'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* SUB-MODAL: REJECT DIALOG */}
      {rejectDialog.isOpen && (
        <div
          className="schedule-modal-backdrop z-50"
          onClick={(e) => {
            if (e.target === e.currentTarget && !rejectDialog.submitting) {
              setRejectDialog((prev) => ({ ...prev, isOpen: false }));
            }
          }}
        >
          <div className="schedule-modal schedule-modal--sm max-w-sm">
            <div className="schedule-modal__header">
              <div className="schedule-modal__header-left">
                <div className="schedule-modal__header-text">
                  <h3 className="schedule-modal__title">Từ Chối Yêu Cầu</h3>
                </div>
              </div>
              <button
                type="button"
                className="schedule-modal__close-btn"
                onClick={() => setRejectDialog((prev) => ({ ...prev, isOpen: false }))}
                aria-label="Đóng"
              >
                <X size={18} />
              </button>
            </div>

            <form onSubmit={handleSubmitReject}>
              <div className="schedule-modal__body space-y-3">
                <div>
                  <label className="block text-xs font-bold text-slate-700 dark:text-slate-300 mb-1">
                    Lý Do Từ Chối (Tùy chọn)
                  </label>
                  <textarea
                    rows={3}
                    placeholder="Nhập lý do phản hồi cho nhân viên..."
                    value={rejectDialog.reviewNote}
                    onChange={(e) =>
                      setRejectDialog((prev) => ({ ...prev, reviewNote: e.target.value }))
                    }
                    className="w-full px-3 py-2 text-xs rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200"
                  />
                </div>
              </div>

              <div className="schedule-modal__footer">
                <button
                  type="button"
                  onClick={() => setRejectDialog((prev) => ({ ...prev, isOpen: false }))}
                  disabled={rejectDialog.submitting}
                  className="schedule-btn-modal schedule-btn-modal--cancel"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={rejectDialog.submitting}
                  className="schedule-btn-modal bg-rose-600 hover:bg-rose-700 text-white font-bold text-xs px-4 py-2 rounded-lg"
                >
                  {rejectDialog.submitting ? 'Đang Xử Lý...' : 'Xác Nhận Từ Chối'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
