import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  ClipboardCheck,
  Clock,
  CheckCircle2,
  XCircle,
  Ban,
  CalendarX,
  CheckCheck,
  Calendar,
  Search,
  RefreshCw,
  Eye,
  Check,
  X,
  ChevronLeft,
  ChevronRight,
  AlertTriangle
} from 'lucide-react';
import accessRequestService from '../../services/accessRequestService';
import { getLevelConfig, AREA_LEVEL_CONFIG } from '../../utils/areaHelpers';
import '../../styles/AccessRequestReviewPage.css';
import PageHeader from '../../components/ui/PageHeader';
import ReasonTextarea from '../../components/ui/ReasonTextarea';
import '../../components/ui/Button.css';
import { formatLocation } from '../../utils/formatLocation';
import { formatDateTime } from '../../utils/formatDateTime';

export default function AccessRequestReviewPage() {
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedAreaId, setSelectedAreaId] = useState('');
  const [areasList, setAreasList] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  // Load available areas for filter dropdown using lightweight AreaSimpleResponse endpoint
  useEffect(() => {
    const fetchAreas = async () => {
      try {
        const data = await accessRequestService.getAvailableAreas();
        setAreasList(Array.isArray(data) ? data : data?.content || []);
      } catch (err) {
        console.error('Lỗi tải danh sách khu vực:', err);
      }
    };
    fetchAreas();
  }, []);

  // Modals state
  const [detailItem, setDetailItem] = useState(null);
  const [approveItem, setApproveItem] = useState(null);
  const [rejectItem, setRejectItem] = useState(null);
  // BR-RQ-44: FM chuyển đơn APPROVED sang FINISHED
  const [finishItem, setFinishItem] = useState(null);
  const [rejectionReason, setRejectionReason] = useState('');
  const [rejectionReasonError, setRejectionReasonError] = useState(null);
  const rejectReasonInputRef = useRef(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [actionError, setActionError] = useState(null);
  const [actionSuccess, setActionSuccess] = useState(null);
  const [actionWarning, setActionWarning] = useState(null);

  // Stats
  const [stats, setStats] = useState({
    total: 0,
    pending: 0,
    approved: 0,
    rejected: 0,
    cancelled: 0,
    expired: 0,
    finished: 0
  });

  // Load Requests
  const loadRequests = useCallback(async (targetPage = 0, status = statusFilter, areaId = selectedAreaId) => {
    setLoading(true);
    try {
      const res = await accessRequestService.getAllRequests({
        status: status || undefined,
        areaId: areaId || undefined,
        page: targetPage,
        size: 10
      });
      setRequests(res?.content || []);
      setTotalPages(res?.totalPages || 1);
      setTotalElements(res?.totalElements || 0);
      setPage(targetPage);
    } catch (err) {
      console.error('Lỗi khi tải danh sách phê duyệt:', err);
    } finally {
      setLoading(false);
    }
  }, [statusFilter, selectedAreaId]);

  // Load Stats counts
  const loadStats = useCallback(async () => {
    try {
      // Đủ 6 trạng thái của RequestStatus để tổng các thẻ = "Tổng yêu cầu"
      const [allRes, pendingRes, approvedRes, rejectedRes, cancelledRes, expiredRes, finishedRes] = await Promise.all([
        accessRequestService.getAllRequests({ page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'PENDING', page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'APPROVED', page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'REJECTED', page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'CANCELLED', page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'EXPIRED', page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'FINISHED', page: 0, size: 1 })
      ]);
      setStats({
        total: allRes?.totalElements || 0,
        pending: pendingRes?.totalElements || 0,
        approved: approvedRes?.totalElements || 0,
        rejected: rejectedRes?.totalElements || 0,
        cancelled: cancelledRes?.totalElements || 0,
        expired: expiredRes?.totalElements || 0,
        finished: finishedRes?.totalElements || 0
      });
    } catch (err) {
      console.error('Lỗi khi tải thống kê:', err);
    }
  }, []);

  useEffect(() => {
    loadRequests(0, statusFilter, selectedAreaId);
    loadStats();
  }, [loadRequests, loadStats, statusFilter, selectedAreaId]);

  // Handle Approve
  const handleConfirmApprove = async () => {
    if (!approveItem) return;
    setActionLoading(true);
    setActionError(null);
    try {
      await accessRequestService.reviewRequest(approveItem.id, {
        status: 'APPROVED'
      });
      setActionSuccess('Đã phê duyệt yêu cầu thành công!');
      setApproveItem(null);
      loadRequests(page, statusFilter, selectedAreaId);
      loadStats();
      setTimeout(() => setActionSuccess(null), 3000);
    } catch (err) {
      if (err.status === 409) {
        setApproveItem(null);
        setActionWarning(err.message || 'Yêu cầu này đã được xử lý bởi người khác. Danh sách đã được làm mới.');
        loadRequests(page, statusFilter, selectedAreaId);
        loadStats();
        setTimeout(() => setActionWarning(null), 7000);
      } else {
        setActionError(err.message || 'Lỗi khi phê duyệt yêu cầu');
      }
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Finish (BR-RQ-44) — backend không yêu cầu lý do, chỉ xác nhận
  const handleConfirmFinish = async () => {
    if (!finishItem) return;
    setActionLoading(true);
    setActionError(null);
    try {
      await accessRequestService.finishRequest(finishItem.id);
      setActionSuccess('Đã chuyển yêu cầu sang Hoàn thành.');
      setFinishItem(null);
      loadRequests(page, statusFilter, selectedAreaId);
      loadStats();
      setTimeout(() => setActionSuccess(null), 3000);
    } catch (err) {
      setActionError(err.message || 'Lỗi khi chuyển yêu cầu sang Hoàn thành');
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Reject
  const handleConfirmReject = async () => {
    if (!rejectItem) return;
    const trimmed = rejectionReason.trim();
    if (!trimmed || trimmed.length < 10 || trimmed.length > 500) {
      setRejectionReasonError(`Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmed.length}).`);
      rejectReasonInputRef.current?.focus();
      return;
    }

    setActionLoading(true);
    setActionError(null);
    setRejectionReasonError(null);
    try {
      await accessRequestService.reviewRequest(rejectItem.id, {
        status: 'REJECTED',
        rejectionReason: trimmed
      });
      setActionSuccess('Đã từ chối yêu cầu truy cập.');
      setRejectItem(null);
      setRejectionReason('');
      setRejectionReasonError(null);
      loadRequests(page, statusFilter, selectedAreaId);
      loadStats();
      setTimeout(() => setActionSuccess(null), 3000);
    } catch (err) {
      if (err.status === 409) {
        setRejectItem(null);
        setRejectionReason('');
        setRejectionReasonError(null);
        setActionWarning(err.message || 'Yêu cầu này đã được xử lý bởi người khác. Danh sách đã được làm mới.');
        loadRequests(page, statusFilter, selectedAreaId);
        loadStats();
        setTimeout(() => setActionWarning(null), 7000);
      } else {
        setActionError(err.message || 'Lỗi khi từ chối yêu cầu');
      }
    } finally {
      setActionLoading(false);
    }
  };

  // User initials
  const getInitials = (name) => {
    if (!name) return 'U';
    return name
      .split(' ')
      .map(n => n[0])
      .slice(0, 2)
      .join('')
      .toUpperCase();
  };

  // Client-side search filter (lọc khu vực và trạng thái đã xử lý hoàn toàn tại server)
  const filteredRequests = requests.filter(req => {
    if (!searchTerm.trim()) return true;
    const term = searchTerm.toLowerCase();
    return (
      (req.requesterName && req.requesterName.toLowerCase().includes(term)) ||
      (req.requesterCode && req.requesterCode.toLowerCase().includes(term))
    );
  });

  return (
    <div className="arr-container">
      {/* Header */}
      <PageHeader
        title="Phê duyệt yêu cầu truy cập"
        description={`Xét duyệt yêu cầu ra vào các khu vực ${AREA_LEVEL_CONFIG.INTERNAL_CONFIDENTIAL.badgeLabel}, ${AREA_LEVEL_CONFIG.CONFIDENTIAL_CONTACT_REQUIRED.badgeLabel} và ${AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.badgeLabel}.`}
        actions={
          <button
            type="button"
            className="ui-btn ui-btn--secondary ui-btn--md"
            onClick={() => { loadRequests(page, statusFilter, selectedAreaId); loadStats(); }}
            title="Làm mới dữ liệu"
          >
            <RefreshCw size={16} className={loading ? 'spin' : ''} />
            <span>Làm mới</span>
          </button>
        }
      />

      {/* Notifications */}
      {actionSuccess && (
        <div className="arr-alert arr-alert--success">
          <CheckCircle2 size={18} />
          <span>{actionSuccess}</span>
        </div>
      )}

      {actionWarning && (
        <div className="arr-alert arr-alert--warning">
          <AlertTriangle size={18} />
          <span>{actionWarning}</span>
        </div>
      )}

      {/* Stats Cards */}
      <div className="arr-stats-grid">
        <div className="arr-stat-card">
          <div className="arr-stat-card__icon arr-stat-card__icon--total">
            <ClipboardCheck size={22} />
          </div>
          <div className="arr-stat-card__content">
            <span className="arr-stat-card__label">Tổng yêu cầu</span>
            <span className="arr-stat-card__value">{stats.total}</span>
          </div>
        </div>

        <div className="arr-stat-card">
          <div className="arr-stat-card__icon arr-stat-card__icon--pending">
            <Clock size={22} />
          </div>
          <div className="arr-stat-card__content">
            <span className="arr-stat-card__label">Chờ phê duyệt</span>
            <span className="arr-stat-card__value">{stats.pending}</span>
          </div>
        </div>

        <div className="arr-stat-card">
          <div className="arr-stat-card__icon arr-stat-card__icon--approved">
            <CheckCircle2 size={22} />
          </div>
          <div className="arr-stat-card__content">
            <span className="arr-stat-card__label">Đã phê duyệt</span>
            <span className="arr-stat-card__value">{stats.approved}</span>
          </div>
        </div>

        <div className="arr-stat-card">
          <div className="arr-stat-card__icon arr-stat-card__icon--rejected">
            <XCircle size={22} />
          </div>
          <div className="arr-stat-card__content">
            <span className="arr-stat-card__label">Đã từ chối</span>
            <span className="arr-stat-card__value">{stats.rejected}</span>
          </div>
        </div>

        <div className="arr-stat-card">
          <div className="arr-stat-card__icon arr-stat-card__icon--cancelled">
            <Ban size={22} />
          </div>
          <div className="arr-stat-card__content">
            <span className="arr-stat-card__label">Đã hủy</span>
            <span className="arr-stat-card__value">{stats.cancelled}</span>
          </div>
        </div>

        <div className="arr-stat-card">
          <div className="arr-stat-card__icon arr-stat-card__icon--expired">
            <CalendarX size={22} />
          </div>
          <div className="arr-stat-card__content">
            <span className="arr-stat-card__label">Hết hạn</span>
            <span className="arr-stat-card__value">{stats.expired}</span>
          </div>
        </div>

        <div className="arr-stat-card">
          <div className="arr-stat-card__icon arr-stat-card__icon--finished">
            <CheckCheck size={22} />
          </div>
          <div className="arr-stat-card__content">
            <span className="arr-stat-card__label">Hoàn thành</span>
            <span className="arr-stat-card__value">{stats.finished}</span>
          </div>
        </div>
      </div>

      {/* Filter Toolbar */}
      <div className="arr-toolbar">
        <div className="arr-filter-group">
          {[
            { label: 'Tất cả', val: '' },
            { label: 'Chờ duyệt', val: 'PENDING' },
            { label: 'Đã duyệt', val: 'APPROVED' },
            { label: 'Đã từ chối', val: 'REJECTED' },
            { label: 'Đã hủy', val: 'CANCELLED' },
            { label: 'Hết hạn', val: 'EXPIRED' },
            { label: 'Hoàn thành', val: 'FINISHED' }
          ].map(f => (
            <button
              key={f.val}
              type="button"
              className={`arr-filter-btn ${statusFilter === f.val ? 'arr-filter-btn--active' : ''}`}
              onClick={() => {
                setStatusFilter(f.val);
                setPage(0);
              }}
            >
              {f.label}
            </button>
          ))}
        </div>

        <div className="arr-toolbar-right">
          <select
            className="arr-select"
            value={selectedAreaId}
            onChange={(e) => {
              setSelectedAreaId(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Tất cả khu vực</option>
            {areasList.map((a) => {
              const loc = formatLocation(a.building, a.floor);
              return (
                <option key={a.id} value={a.id}>
                  {loc ? `${a.name} (${loc})` : a.name}
                </option>
              );
            })}
          </select>

          <div className="arr-search-wrap">
            <Search size={14} className="arr-search-icon" />
            <input
              type="text"
              className="arr-search-input"
              placeholder="Tìm theo tên, mã số người yêu cầu..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
            />
          </div>
        </div>
      </div>

      {/* Table Card */}
      <div className="arr-table-card">
        {loading ? (
          <div className="arr-table-empty">
            <RefreshCw size={24} className="spin" style={{ marginBottom: '0.5rem' }} />
            <div>Đang tải dữ liệu yêu cầu...</div>
          </div>
        ) : filteredRequests.length === 0 ? (
          <div className="arr-table-empty">
            <Calendar size={32} style={{ opacity: 0.4, marginBottom: '0.5rem' }} />
            <div>Không tìm thấy yêu cầu truy cập nào</div>
          </div>
        ) : (
          <div className="arr-table-container">
            <table className="arr-table">
              <thead>
                <tr>
                  <th>Người yêu cầu</th>
                  <th>Khu vực đăng ký</th>
                  <th className="ui-col-time-range">Thời gian truy cập</th>
                  <th>Hình thức</th>
                  <th>Trạng thái</th>
                  <th className="ui-col-time">Ngày gửi</th>
                  <th className="arr-col-actions">Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {filteredRequests.map(req => (
                  <tr key={req.id}>
                    {/* Requester */}
                    <td>
                      <div className="arr-user-cell">
                        <div className="arr-user-avatar">
                          {getInitials(req.requesterName)}
                        </div>
                        <div className="arr-user-meta">
                          <span className="arr-user-name">{req.requesterName || 'Người dùng'}</span>
                          <span className="arr-user-code">{req.requesterCode || req.requesterEmail}</span>
                        </div>
                      </div>
                    </td>

                    {/* Area */}
                    <td>
                      <div className="arr-area-tag">
                        <span className="arr-area-name">{req.areaName}</span>
                        <span className="arr-area-sub">
                          {formatLocation(req.building, req.floor)
                            ? `${formatLocation(req.building, req.floor)} - `
                            : ''}
                          {getLevelConfig(req.areaLevel).name}
                        </span>
                      </div>
                    </td>

                    {/* Time */}
                    <td>
                      <div style={{ fontSize: '0.8125rem' }}>{formatDateTime(req.startTime)}</div>
                      <div className="arr-text-muted" style={{ fontSize: '0.75rem' }}>
                        đến {formatDateTime(req.endTime)}
                      </div>
                    </td>

                    {/* Type */}
                    <td>
                      <span className={`arr-badge ${req.requestType === 'GROUP' ? 'arr-badge--group' : 'arr-badge--individual'}`}>
                        {req.requestType === 'GROUP' ? `Nhóm (${req.members?.length || 0})` : 'Cá nhân'}
                      </span>
                    </td>

                    {/* Status */}
                    <td>
                      <span className={`arr-badge arr-badge--${req.status.toLowerCase()}`}>
                        {req.status === 'PENDING' && 'Chờ duyệt'}
                        {req.status === 'APPROVED' && 'Đã duyệt'}
                        {req.status === 'REJECTED' && 'Từ chối'}
                        {req.status === 'CANCELLED' && 'Đã hủy'}
                        {req.status === 'EXPIRED' && 'Hết hạn'}
                        {req.status === 'FINISHED' && 'Hoàn thành'}
                      </span>
                      {req.status === 'CANCELLED' && req.cancelSource === 'SYSTEM' && (
                        <div className="arr-cancel-system" title={req.cancelReason || ''}>
                          Hủy bởi hệ thống: {req.cancelReason}
                        </div>
                      )}
                    </td>

                    {/* Created At */}
                    <td className="arr-text-muted" style={{ fontSize: '0.8125rem' }}>
                      {formatDateTime(req.createdAt)}
                    </td>

                    {/* Actions */}
                    <td className="arr-col-actions">
                      <div className="arr-actions">
                        {req.status === 'PENDING' && (
                          <>
                            <button
                              type="button"
                              className="arr-btn-icon arr-btn-icon--approve"
                              onClick={() => { setApproveItem(req); setActionError(null); }}
                              title="Phê duyệt yêu cầu"
                            >
                              <Check size={16} />
                            </button>
                            <button
                              type="button"
                              className="arr-btn-icon arr-btn-icon--reject"
                              onClick={() => { setRejectItem(req); setRejectionReason(''); setActionError(null); }}
                              title="Từ chối yêu cầu"
                            >
                              <X size={16} />
                            </button>
                          </>
                        )}
                        {req.status === 'APPROVED' && (
                          <button
                            type="button"
                            className="arr-btn-icon"
                            onClick={() => { setFinishItem(req); setActionError(null); }}
                            title="Chuyển sang Hoàn thành"
                          >
                            <CheckCheck size={16} />
                          </button>
                        )}
                        <button
                          type="button"
                          className="arr-btn-icon"
                          onClick={() => setDetailItem(req)}
                          title="Xem chi tiết"
                        >
                          <Eye size={16} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="arr-pagination">
            <span>Hiển thị trang {page + 1} / {totalPages} ({totalElements} bản ghi)</span>
            <div className="arr-pagination__btns">
              <button
                type="button"
                className="arr-filter-btn"
                disabled={page <= 0}
                onClick={() => loadRequests(page - 1, statusFilter, selectedAreaId)}
              >
                <ChevronLeft size={14} />
                <span>Trước</span>
              </button>
              <button
                type="button"
                className="arr-filter-btn"
                disabled={page >= totalPages - 1}
                onClick={() => loadRequests(page + 1, statusFilter, selectedAreaId)}
              >
                <span>Tiếp</span>
                <ChevronRight size={14} />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* APPROVE CONFIRMATION MODAL */}
      {approveItem && (
        <div className="arr-modal-overlay" onClick={() => !actionLoading && setApproveItem(null)}>
          <div className="arr-modal arr-modal--sm" onClick={e => e.stopPropagation()}>
            <div className="arr-modal__header">
              <h2 className="arr-modal__title">Xác nhận phê duyệt</h2>
              <button
                type="button"
                className="arr-modal__close"
                onClick={() => !actionLoading && setApproveItem(null)}
              >
                <X size={18} />
              </button>
            </div>

            <div className="arr-modal__body">
              {actionError && (
                <div className="arr-alert arr-alert--danger">
                  {actionError}
                </div>
              )}

              <p className="arr-confirm-text">
                Bạn có chắc chắn muốn <strong>phê duyệt</strong> yêu cầu truy cập khu vực <strong>{approveItem.areaName}</strong> cho <strong>{approveItem.requesterName}</strong>?
              </p>

              <div className="arr-info-box">
                <div>
                  <strong>Khu vực:</strong> {approveItem.areaName}
                  {formatLocation(approveItem.building, approveItem.floor)
                    ? ` (${formatLocation(approveItem.building, approveItem.floor)})`
                    : ''}
                </div>
                <div><strong>Thời gian:</strong> {formatDateTime(approveItem.startTime)} - {formatDateTime(approveItem.endTime)}</div>
                <div><strong>Mục đích:</strong> {approveItem.purpose}</div>
              </div>
            </div>

            <div className="arr-modal__footer">
              <button
                type="button"
                className="arr-filter-btn"
                onClick={() => setApproveItem(null)}
                disabled={actionLoading}
              >
                Hủy bỏ
              </button>
              <button
                type="button"
                className="arr-filter-btn arr-btn--approve-modal"
                onClick={handleConfirmApprove}
                disabled={actionLoading}
              >
                <Check size={16} />
                <span>{actionLoading ? 'Đang duyệt...' : 'Xác nhận duyệt'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* FINISH CONFIRMATION MODAL — BR-RQ-44 */}
      {finishItem && (
        <div className="arr-modal-overlay" onClick={() => !actionLoading && setFinishItem(null)}>
          <div className="arr-modal arr-modal--sm" onClick={e => e.stopPropagation()}>
            <div className="arr-modal__header">
              <h2 className="arr-modal__title">Xác nhận hoàn thành</h2>
              <button
                type="button"
                className="arr-modal__close"
                onClick={() => !actionLoading && setFinishItem(null)}
              >
                <X size={18} />
              </button>
            </div>

            <div className="arr-modal__body">
              {actionError && (
                <div className="arr-alert arr-alert--danger">
                  {actionError}
                </div>
              )}

              <p className="arr-confirm-text">
                Chuyển yêu cầu truy cập khu vực <strong>{finishItem.areaName}</strong> của <strong>{finishItem.requesterName}</strong> sang <strong>Hoàn thành</strong>?
              </p>

              <div className="arr-info-box">
                <div>
                  <strong>Khu vực:</strong> {finishItem.areaName}
                  {formatLocation(finishItem.building, finishItem.floor)
                    ? ` (${formatLocation(finishItem.building, finishItem.floor)})`
                    : ''}
                </div>
                <div><strong>Thời gian:</strong> {formatDateTime(finishItem.startTime)} - {formatDateTime(finishItem.endTime)}</div>
                <div><strong>Mục đích:</strong> {finishItem.purpose}</div>
              </div>
            </div>

            <div className="arr-modal__footer">
              <button
                type="button"
                className="arr-filter-btn"
                onClick={() => setFinishItem(null)}
                disabled={actionLoading}
              >
                Hủy bỏ
              </button>
              <button
                type="button"
                className="arr-filter-btn arr-btn--approve-modal"
                onClick={handleConfirmFinish}
                disabled={actionLoading}
              >
                <CheckCheck size={16} />
                <span>{actionLoading ? 'Đang cập nhật...' : 'Xác nhận hoàn thành'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* REJECT MODAL */}
      {rejectItem && (
        <div className="arr-modal-overlay" onClick={() => !actionLoading && setRejectItem(null)}>
          <div className="arr-modal" onClick={e => e.stopPropagation()}>
            <div className="arr-modal__header">
              <h2 className="arr-modal__title">Từ chối yêu cầu truy cập</h2>
              <button
                type="button"
                className="arr-modal__close"
                onClick={() => !actionLoading && setRejectItem(null)}
              >
                <X size={18} />
              </button>
            </div>

            <div className="arr-modal__body">
              {actionError && (
                <div className="arr-alert arr-alert--danger">
                  {actionError}
                </div>
              )}

              <p className="arr-confirm-text">
                Từ chối yêu cầu của <strong>{rejectItem.requesterName}</strong> tại khu vực <strong>{rejectItem.areaName}</strong>. Vui lòng nêu rõ lý do:
              </p>

              <ReasonTextarea
                ref={rejectReasonInputRef}
                label="Lý do từ chối"
                placeholder="Ví dụ: Khu vực đang bảo trì thiết bị, trùng lịch sự kiện quan trọng, mục đích không phù hợp..."
                value={rejectionReason}
                onChange={(e) => {
                  setRejectionReason(e.target.value);
                  if (rejectionReasonError && e.target.value.trim().length >= 10 && e.target.value.trim().length <= 500) {
                    setRejectionReasonError(null);
                  }
                }}
                error={rejectionReasonError}
                min={10}
                max={500}
                disabled={actionLoading}
                required
              />
            </div>

            <div className="arr-modal__footer">
              <button
                type="button"
                className="arr-filter-btn"
                onClick={() => {
                  setRejectItem(null);
                  setRejectionReasonError(null);
                }}
                disabled={actionLoading}
              >
                Hủy bỏ
              </button>
              <button
                type="button"
                className="arr-filter-btn arr-btn--reject-modal"
                onClick={handleConfirmReject}
                disabled={actionLoading}
              >
                <X size={16} />
                <span>{actionLoading ? 'Đang xử lý...' : 'Xác nhận từ chối'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* DETAIL MODAL */}
      {detailItem && (
        <div className="arr-modal-overlay" onClick={() => setDetailItem(null)}>
          <div className="arr-modal" onClick={e => e.stopPropagation()}>
            <div className="arr-modal__header">
              <h2 className="arr-modal__title">Chi tiết yêu cầu truy cập</h2>
              <button
                type="button"
                className="arr-modal__close"
                onClick={() => setDetailItem(null)}
              >
                <X size={18} />
              </button>
            </div>

            <div className="arr-modal__body">
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '1rem' }}>
                <div>
                  <div className="arr-detail-label">NGƯỜI YÊU CẦU</div>
                  <div style={{ fontWeight: 600, fontSize: '0.9375rem' }}>{detailItem.requesterName}</div>
                  <div className="arr-text-muted" style={{ fontSize: '0.8125rem' }}>
                    Mã số: {detailItem.requesterCode || '—'} | {detailItem.requesterEmail}
                  </div>
                </div>

                <div>
                  <div className="arr-detail-label">TRẠNG THÁI</div>
                  <div style={{ marginTop: '0.2rem' }}>
                    <span className={`arr-badge arr-badge--${detailItem.status.toLowerCase()}`}>
                      {detailItem.status === 'PENDING' && 'Chờ phê duyệt'}
                      {detailItem.status === 'APPROVED' && 'Đã phê duyệt'}
                      {detailItem.status === 'REJECTED' && 'Bị từ chối'}
                      {detailItem.status === 'CANCELLED' && 'Đã hủy'}
                      {detailItem.status === 'EXPIRED' && 'Hết hạn'}
                      {detailItem.status === 'FINISHED' && 'Hoàn thành'}
                    </span>
                    {detailItem.status === 'CANCELLED' && detailItem.cancelSource === 'SYSTEM' && (
                      <div className="arr-cancel-system arr-cancel-system--detail">
                        Hủy bởi hệ thống: {detailItem.cancelReason}
                      </div>
                    )}
                  </div>
                </div>

                <div>
                  <div className="arr-detail-label">KHU VỰC ĐĂNG KÝ</div>
                  <div style={{ fontWeight: 600 }}>
                    {detailItem.areaName}
                    {formatLocation(detailItem.building, detailItem.floor)
                      ? ` (${formatLocation(detailItem.building, detailItem.floor)})`
                      : ''}
                  </div>
                  <div className="arr-text-muted" style={{ fontSize: '0.8125rem' }}>
                    Loại khu vực: {getLevelConfig(detailItem.areaLevel).name}
                  </div>
                </div>

                <div>
                  <div className="arr-detail-label">HÌNH THỨC</div>
                  <div style={{ fontWeight: 600 }}>
                    {detailItem.requestType === 'GROUP' ? 'Tập thể / Nhóm' : 'Cá nhân'}
                  </div>
                </div>

                <div>
                  <div className="arr-detail-label">THỜI GIAN BẮT ĐẦU</div>
                  <div style={{ fontSize: '0.875rem' }}>{formatDateTime(detailItem.startTime)}</div>
                </div>

                <div>
                  <div className="arr-detail-label">THỜI GIAN KẾT THÚC</div>
                  <div style={{ fontSize: '0.875rem' }}>{formatDateTime(detailItem.endTime)}</div>
                </div>
              </div>

              {/* Purpose */}
              <div>
                <div className="arr-detail-label" style={{ marginBottom: '0.25rem' }}>MỤC ĐÍCH SỬ DỤNG</div>
                <div className="arr-detail-box">
                  {detailItem.purpose}
                </div>
              </div>

              {/* Members (if group) */}
              {detailItem.requestType === 'GROUP' && detailItem.members && detailItem.members.length > 0 && (
                <div>
                  <div className="arr-detail-label" style={{ marginBottom: '0.35rem' }}>
                    DANH SÁCH THÀNH VIÊN NHÓM ({detailItem.members.length})
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
                    {detailItem.members.map(m => (
                      <span key={m.userId || m.userCode} className="arr-member-chip">
                        <strong>{m.userCode}</strong> - {m.fullName}
                        {m.sponsored && (
                          <span style={{ marginLeft: '6px', fontSize: '11px', padding: '1px 6px', borderRadius: '4px', backgroundColor: 'rgba(59, 130, 246, 0.15)', color: 'var(--brand-blue, #3b82f6)', fontWeight: 600 }}>
                            Bảo lãnh
                          </span>
                        )}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* Rejection reason (if rejected) */}
              {detailItem.status === 'REJECTED' && detailItem.rejectionReason && (
                <div>
                  <div className="arr-detail-label arr-detail-label--danger" style={{ marginBottom: '0.25rem' }}>LÝ DO TỪ CHỐI</div>
                  <div className="arr-detail-box arr-detail-box--danger">
                    {detailItem.rejectionReason}
                  </div>
                </div>
              )}

              {/* Reviewer info */}
              {detailItem.reviewedAt && (
                <div className="arr-reviewer-meta">
                  Xử lý bởi: <strong>{detailItem.reviewerName || 'Ban quản lý'}</strong> ({detailItem.reviewerEmail}) vào lúc {formatDateTime(detailItem.reviewedAt)}
                </div>
              )}
            </div>

            <div className="arr-modal__footer">
              <button
                type="button"
                className="arr-filter-btn"
                onClick={() => setDetailItem(null)}
              >
                Đóng
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
