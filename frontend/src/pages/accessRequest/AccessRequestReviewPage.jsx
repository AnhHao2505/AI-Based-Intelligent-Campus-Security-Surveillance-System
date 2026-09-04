import React, { useState, useEffect, useCallback } from 'react';
import {
  ClipboardCheck,
  Clock,
  CheckCircle2,
  XCircle,
  Users,
  User,
  Building,
  Search,
  RefreshCw,
  Eye,
  Check,
  X,
  AlertCircle,
  FileText,
  Calendar,
  ChevronLeft,
  ChevronRight
} from 'lucide-react';
import accessRequestService from '../../services/accessRequestService';
import '../../styles/AccessRequestReviewPage.css';

export default function AccessRequestReviewPage() {
  const [requests, setRequests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  // Modals state
  const [detailItem, setDetailItem] = useState(null);
  const [approveItem, setApproveItem] = useState(null);
  const [rejectItem, setRejectItem] = useState(null);
  const [rejectionReason, setRejectionReason] = useState('');
  const [actionLoading, setActionLoading] = useState(false);
  const [actionError, setActionError] = useState(null);
  const [actionSuccess, setActionSuccess] = useState(null);

  // Stats
  const [stats, setStats] = useState({
    total: 0,
    pending: 0,
    approved: 0,
    rejected: 0
  });

  // Load Requests
  const loadRequests = useCallback(async (targetPage = 0, status = statusFilter) => {
    setLoading(true);
    try {
      const res = await accessRequestService.getAllRequests({
        status: status || undefined,
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
  }, [statusFilter]);

  // Load Stats counts
  const loadStats = useCallback(async () => {
    try {
      const [allRes, pendingRes, approvedRes, rejectedRes] = await Promise.all([
        accessRequestService.getAllRequests({ page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'PENDING', page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'APPROVED', page: 0, size: 1 }),
        accessRequestService.getAllRequests({ status: 'REJECTED', page: 0, size: 1 })
      ]);
      setStats({
        total: allRes?.totalElements || 0,
        pending: pendingRes?.totalElements || 0,
        approved: approvedRes?.totalElements || 0,
        rejected: rejectedRes?.totalElements || 0
      });
    } catch (err) {
      console.error('Lỗi khi tải thống kê:', err);
    }
  }, []);

  useEffect(() => {
    loadRequests(0, statusFilter);
    loadStats();
  }, [loadRequests, loadStats, statusFilter]);

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
      loadRequests(page, statusFilter);
      loadStats();
      setTimeout(() => setActionSuccess(null), 3000);
    } catch (err) {
      setActionError(err.message || 'Lỗi khi phê duyệt yêu cầu');
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Reject
  const handleConfirmReject = async () => {
    if (!rejectItem) return;
    if (!rejectionReason.trim()) {
      setActionError('Vui lòng nhập lý do từ chối yêu cầu');
      return;
    }

    setActionLoading(true);
    setActionError(null);
    try {
      await accessRequestService.reviewRequest(rejectItem.id, {
        status: 'REJECTED',
        rejectionReason: rejectionReason.trim()
      });
      setActionSuccess('Đã từ chối yêu cầu truy cập.');
      setRejectItem(null);
      setRejectionReason('');
      loadRequests(page, statusFilter);
      loadStats();
      setTimeout(() => setActionSuccess(null), 3000);
    } catch (err) {
      setActionError(err.message || 'Lỗi khi từ chối yêu cầu');
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

  // Format date helper
  const formatDateTime = (isoString) => {
    if (!isoString) return '—';
    const d = new Date(isoString);
    return d.toLocaleString('vi-VN', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  // Client-side search filter
  const filteredRequests = requests.filter(req => {
    if (!searchTerm.trim()) return true;
    const term = searchTerm.toLowerCase();
    return (
      (req.requesterName && req.requesterName.toLowerCase().includes(term)) ||
      (req.requesterCode && req.requesterCode.toLowerCase().includes(term)) ||
      (req.areaName && req.areaName.toLowerCase().includes(term)) ||
      (req.areaCode && req.areaCode.toLowerCase().includes(term))
    );
  });

  return (
    <div className="arr-container">
      {/* Header */}
      <div className="arr-header">
        <div>
          <h1 className="arr-header__title">Phê duyệt Yêu cầu Truy cập Khu vực</h1>
          <p className="arr-header__subtitle">
            Xét duyệt và quản lý các yêu cầu đăng ký ra vào khu vực bán riêng tư và riêng tư trong campus.
          </p>
        </div>

        <button
          type="button"
          className="arr-filter-btn"
          onClick={() => { loadRequests(page, statusFilter); loadStats(); }}
          title="Làm mới dữ liệu"
        >
          <RefreshCw size={14} className={loading ? 'spin' : ''} />
          <span>Làm mới</span>
        </button>
      </div>

      {/* Notifications */}
      {actionSuccess && (
        <div style={{
          padding: '0.75rem 1rem',
          borderRadius: '0.5rem',
          background: 'var(--theme-success-bg)',
          border: '1px solid var(--theme-success-border)',
          color: 'var(--theme-success-text)',
          marginBottom: '1rem',
          display: 'flex',
          alignItems: 'center',
          gap: '0.5rem'
        }}>
          <CheckCircle2 size={18} />
          <span>{actionSuccess}</span>
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
      </div>

      {/* Filter Toolbar */}
      <div className="arr-toolbar">
        <div className="arr-filter-group">
          {[
            { label: 'Tất cả', val: '' },
            { label: 'Chờ duyệt', val: 'PENDING' },
            { label: 'Đã duyệt', val: 'APPROVED' },
            { label: 'Đã từ chối', val: 'REJECTED' }
          ].map(f => (
            <button
              key={f.val}
              type="button"
              className={`arr-filter-btn ${statusFilter === f.val ? 'arr-filter-btn--active' : ''}`}
              onClick={() => setStatusFilter(f.val)}
            >
              {f.label}
            </button>
          ))}
        </div>

        <div style={{ position: 'relative', minWidth: '240px' }}>
          <Search size={14} style={{ position: 'absolute', left: '0.75rem', top: '50%', transform: 'translateY(-50%)', color: 'var(--theme-text-muted)' }} />
          <input
            type="text"
            placeholder="Tìm theo tên, mã số, khu vực..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            style={{
              width: '100%',
              padding: '0.45rem 0.75rem 0.45rem 2.2rem',
              fontSize: '0.8125rem',
              borderRadius: '0.5rem',
              border: '1px solid var(--theme-input-border)',
              background: 'var(--theme-bg-input)',
              color: 'var(--theme-text-primary)'
            }}
          />
        </div>
      </div>

      {/* Table Card */}
      <div className="arr-table-card">
        {loading ? (
          <div style={{ textAlign: 'center', padding: '3rem', color: 'var(--theme-text-muted)' }}>
            <RefreshCw size={24} className="spin" style={{ marginBottom: '0.5rem' }} />
            <div>Đang tải dữ liệu yêu cầu...</div>
          </div>
        ) : filteredRequests.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '3rem', color: 'var(--theme-text-muted)' }}>
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
                  <th>Thời gian truy cập</th>
                  <th>Hình thức</th>
                  <th>Trạng thái</th>
                  <th>Ngày gửi</th>
                  <th style={{ textAlign: 'center' }}>Thao tác</th>
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
                          [{req.areaCode}] - {req.building || 'Campus'} - {req.areaLevel}
                        </span>
                      </div>
                    </td>

                    {/* Time */}
                    <td>
                      <div style={{ fontSize: '0.8125rem' }}>{formatDateTime(req.startTime)}</div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--theme-text-muted)' }}>
                        đến {formatDateTime(req.endTime)}
                      </div>
                    </td>

                    {/* Type */}
                    <td>
                      <span className={`arr-badge ${req.requestType === 'GROUP' ? 'arr-badge--group' : 'arr-badge--individual'}`} style={{
                        background: req.requestType === 'GROUP' ? '#ede9fe' : '#f1f5f9',
                        color: req.requestType === 'GROUP' ? '#6b21a8' : '#334155'
                      }}>
                        {req.requestType === 'GROUP' ? `Nhóm (${req.members?.length || 0})` : 'Cá nhân'}
                      </span>
                    </td>

                    {/* Status */}
                    <td>
                      <span className={`arr-badge arr-badge--${req.status.toLowerCase()}`}>
                        {req.status === 'PENDING' && 'Chờ duyệt'}
                        {req.status === 'APPROVED' && 'Đã duyệt'}
                        {req.status === 'REJECTED' && 'Từ chối'}
                      </span>
                    </td>

                    {/* Created At */}
                    <td style={{ fontSize: '0.8125rem', color: 'var(--theme-text-muted)' }}>
                      {formatDateTime(req.createdAt)}
                    </td>

                    {/* Actions */}
                    <td>
                      <div className="arr-actions" style={{ justifyContent: 'center' }}>
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
                onClick={() => loadRequests(page - 1, statusFilter)}
              >
                <ChevronLeft size={14} />
                <span>Trước</span>
              </button>
              <button
                type="button"
                className="arr-filter-btn"
                disabled={page >= totalPages - 1}
                onClick={() => loadRequests(page + 1, statusFilter)}
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
              <h2 className="arr-modal__title">Xác nhận Phê duyệt</h2>
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
                <div style={{ padding: '0.5rem', background: 'var(--theme-danger-bg)', color: 'var(--theme-danger-text)', borderRadius: '0.375rem', fontSize: '0.8125rem' }}>
                  {actionError}
                </div>
              )}

              <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--theme-text-primary)' }}>
                Bạn có chắc chắn muốn <strong>phê duyệt</strong> yêu cầu truy cập khu vực <strong>{approveItem.areaName}</strong> cho <strong>{approveItem.requesterName}</strong>?
              </p>

              <div style={{
                background: 'var(--theme-bg-page)',
                padding: '0.75rem 1rem',
                borderRadius: '0.5rem',
                fontSize: '0.8125rem',
                display: 'flex',
                flexDirection: 'column',
                gap: '0.25rem'
              }}>
                <div><strong>Khu vực:</strong> [{approveItem.areaCode}] {approveItem.areaName}</div>
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
                className="arr-filter-btn"
                style={{ background: 'var(--theme-success)', color: '#fff', borderColor: 'var(--theme-success)' }}
                onClick={handleConfirmApprove}
                disabled={actionLoading}
              >
                <Check size={16} />
                <span>{actionLoading ? 'Đang duyệt...' : 'Xác nhận Duyệt'}</span>
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
              <h2 className="arr-modal__title">Từ chối Yêu cầu Truy cập</h2>
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
                <div style={{ padding: '0.5rem', background: 'var(--theme-danger-bg)', color: 'var(--theme-danger-text)', borderRadius: '0.375rem', fontSize: '0.8125rem' }}>
                  {actionError}
                </div>
              )}

              <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--theme-text-primary)' }}>
                Từ chối yêu cầu của <strong>{rejectItem.requesterName}</strong> tại khu vực <strong>{rejectItem.areaName}</strong>. Vui lòng nêu rõ lý do:
              </p>

              <div>
                <label style={{ display: 'block', fontSize: '0.8125rem', fontWeight: 600, marginBottom: '0.35rem', color: 'var(--theme-text-primary)' }}>
                  Lý do từ chối <span style={{ color: 'var(--theme-danger)' }}>*</span>
                </label>
                <textarea
                  className="arr-textarea"
                  placeholder="Ví dụ: Khu vực đang bảo trì thiết bị, trùng lịch sự kiện quan trọng, mục đích không phù hợp..."
                  value={rejectionReason}
                  onChange={(e) => setRejectionReason(e.target.value)}
                  maxLength={500}
                  disabled={actionLoading}
                  required
                />
                <div style={{ fontSize: '0.75rem', color: 'var(--theme-text-muted)', textAlign: 'right' }}>
                  {rejectionReason.length}/500 ký tự
                </div>
              </div>
            </div>

            <div className="arr-modal__footer">
              <button
                type="button"
                className="arr-filter-btn"
                onClick={() => setRejectItem(null)}
                disabled={actionLoading}
              >
                Hủy bỏ
              </button>
              <button
                type="button"
                className="arr-filter-btn"
                style={{ background: 'var(--theme-danger)', color: '#fff', borderColor: 'var(--theme-danger)' }}
                onClick={handleConfirmReject}
                disabled={actionLoading || !rejectionReason.trim()}
              >
                <X size={16} />
                <span>{actionLoading ? 'Đang xử lý...' : 'Xác nhận Từ chối'}</span>
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
              <h2 className="arr-modal__title">Chi tiết Yêu cầu Truy cập</h2>
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
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-text-muted)' }}>NGƯỜI YÊU CẦU</div>
                  <div style={{ fontWeight: 600, fontSize: '0.9375rem' }}>{detailItem.requesterName}</div>
                  <div style={{ fontSize: '0.8125rem', color: 'var(--theme-text-muted)' }}>
                    Mã số: {detailItem.requesterCode || '—'} | {detailItem.requesterEmail}
                  </div>
                </div>

                <div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-text-muted)' }}>TRẠNG THÁI</div>
                  <div style={{ marginTop: '0.2rem' }}>
                    <span className={`arr-badge arr-badge--${detailItem.status.toLowerCase()}`}>
                      {detailItem.status === 'PENDING' && 'Chờ phê duyệt'}
                      {detailItem.status === 'APPROVED' && 'Đã phê duyệt'}
                      {detailItem.status === 'REJECTED' && 'Bị từ chối'}
                    </span>
                  </div>
                </div>

                <div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-text-muted)' }}>KHU VỰC ĐĂNG KÝ</div>
                  <div style={{ fontWeight: 600 }}>{detailItem.areaName} ({detailItem.areaCode})</div>
                  <div style={{ fontSize: '0.8125rem', color: 'var(--theme-text-muted)' }}>
                    Cấp độ: {detailItem.areaLevel} | {detailItem.building} - Tầng {detailItem.floor}
                  </div>
                </div>

                <div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-text-muted)' }}>HÌNH THỨC</div>
                  <div style={{ fontWeight: 600 }}>
                    {detailItem.requestType === 'GROUP' ? 'Tập thể / Nhóm' : 'Cá nhân'}
                  </div>
                </div>

                <div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-text-muted)' }}>THỜI GIAN BẮT ĐẦU</div>
                  <div style={{ fontSize: '0.875rem' }}>{formatDateTime(detailItem.startTime)}</div>
                </div>

                <div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-text-muted)' }}>THỜI GIAN KẾT THÚC</div>
                  <div style={{ fontSize: '0.875rem' }}>{formatDateTime(detailItem.endTime)}</div>
                </div>
              </div>

              {/* Purpose */}
              <div>
                <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-text-muted)', marginBottom: '0.25rem' }}>MỤC ĐÍCH SỬ DỤNG</div>
                <div style={{ background: 'var(--theme-bg-page)', padding: '0.75rem 1rem', borderRadius: '0.5rem', fontSize: '0.875rem', border: '1px solid var(--theme-border)' }}>
                  {detailItem.purpose}
                </div>
              </div>

              {/* Members (if group) */}
              {detailItem.requestType === 'GROUP' && detailItem.members && detailItem.members.length > 0 && (
                <div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-text-muted)', marginBottom: '0.35rem' }}>
                    DANH SÁCH THÀNH VIÊN NHÓM ({detailItem.members.length})
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
                    {detailItem.members.map(m => (
                      <span key={m.userId || m.userCode} style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: '0.4rem',
                        padding: '0.35rem 0.75rem',
                        borderRadius: '9999px',
                        background: 'var(--theme-primary-light)',
                        border: '1px solid var(--theme-primary-border)',
                        color: 'var(--theme-primary)',
                        fontSize: '0.8125rem'
                      }}>
                        <strong>{m.userCode}</strong> - {m.fullName}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {/* Rejection reason (if rejected) */}
              {detailItem.status === 'REJECTED' && detailItem.rejectionReason && (
                <div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--theme-danger)', marginBottom: '0.25rem' }}>LÝ DO TỪ CHỐI</div>
                  <div style={{ background: 'var(--theme-danger-bg)', padding: '0.75rem 1rem', borderRadius: '0.5rem', fontSize: '0.875rem', border: '1px solid var(--theme-danger-border)', color: 'var(--theme-danger-text)' }}>
                    {detailItem.rejectionReason}
                  </div>
                </div>
              )}

              {/* Reviewer info */}
              {detailItem.reviewedAt && (
                <div style={{ fontSize: '0.8125rem', color: 'var(--theme-text-secondary)', background: 'var(--theme-bg-page)', padding: '0.625rem 1rem', borderRadius: '0.5rem' }}>
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
