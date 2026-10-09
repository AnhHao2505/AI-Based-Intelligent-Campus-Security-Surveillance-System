import React, { useCallback, useEffect, useMemo, useState, useRef } from 'react';
import {
  Users,
  Eye,
  Check,
  X,
  Ban,
  RefreshCw,
  AlertCircle,
  AlertTriangle,
  ClipboardCheck,
} from 'lucide-react';
import Modal from '../../components/ui/Modal';
import Button from '../../components/ui/Button';
import Badge from '../../components/ui/Badge';
import PageHeader from '../../components/ui/PageHeader';
import Pagination from '../../components/ui/Pagination';
import ReasonTextarea from '../../components/ui/ReasonTextarea';
import guestVisitService from '../../services/guestVisitService';
import { useAuth } from '../../context/AuthContext';
import {
  getGuestVisitStatus,
  getGuestBiometricStatus,
  formatDateTime,
} from '../../utils/guestHelpers';
import '../../styles/GuestVisitPage.css';
import { useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

const PAGE_SIZE = 10;

const STATUS_FILTERS = [
  { value: 'PENDING', label: 'Chờ duyệt' },
  { value: 'APPROVED', label: 'Đã duyệt' },
  { value: 'REJECTED', label: 'Bị từ chối' },
  { value: 'CANCELLED', label: 'Đã hủy' },
  { value: 'REVOKED', label: 'Bị thu hồi' },
  { value: 'EXPIRED', label: 'Hết hạn' },
  { value: 'COMPLETED', label: 'Đã kết thúc' },
  { value: 'ALL', label: 'Tất cả' },
];

function ReasonBlock({ label, value, author, time, variant = 'default' }) {
  if (!value) return null;
  return (
    <div className={`guest-visit__reason ${variant === 'danger' ? 'guest-visit__reason--danger' : ''}`}>
      <span className="guest-visit__reason-label">{label}</span>
      <span>{value}</span>
      {(author || time) && (
        <span className="guest-visit__muted" style={{ fontSize: '12px', marginTop: '2px' }}>
          {author ? `Bởi ${author}` : ''} {time ? `lúc ${formatDateTime(time)}` : ''}
        </span>
      )}
    </div>
  );
}

export default function GuestVisitReviewPage() {
  const { user } = useAuth();
  const [statusFilter, setStatusFilter] = useState('PENDING');
  const [page, setPage] = useState(0);
  const [visits, setVisits] = useState([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Detail Modal
  const [detail, setDetail] = useState(null);
  // Bấm thông báo -> ?visitId: tô sáng dòng lượt khách hoặc mở popup chi tiết
  const [searchParams, setSearchParams] = useSearchParams();
  const [visitsLoaded, setVisitsLoaded] = useState(false);
  const [highlightVisitId, setHighlightVisitId] = useState(null);
  const handledVisitIdRef = useRef(null);

  // Action Modals
  const [approveTarget, setApproveTarget] = useState(null);
  const [approveReason, setApproveReason] = useState('');

  const [rejectTarget, setRejectTarget] = useState(null);
  const [rejectReason, setRejectReason] = useState('');
  const [rejectReasonError, setRejectReasonError] = useState(null);
  const rejectReasonRef = useRef(null);

  const [revokeTarget, setRevokeTarget] = useState(null);
  const [revokeReason, setRevokeReason] = useState('');
  const [revokeReasonError, setRevokeReasonError] = useState(null);
  const revokeReasonRef = useRef(null);

  const [actionLoading, setActionLoading] = useState(false);
  const [actionError, setActionError] = useState(null);

  const loadVisits = useCallback(async (targetPage = 0) => {
    setLoading(true);
    setError(null);
    try {
      const res = await guestVisitService.listVisits({
        status: statusFilter,
        page: targetPage,
        size: PAGE_SIZE,
      });
      setVisits(res?.content || []);
      setTotalPages(res?.totalPages || 0);
      setTotalElements(res?.totalElements || 0);
      setPage(res?.number ?? targetPage);
    } catch (err) {
      setError(err?.message || 'Không thể tải danh sách lượt khách.');
    } finally {
      setLoading(false);
      setVisitsLoaded(true);
    }
  }, [statusFilter]);

  useEffect(() => {
    loadVisits(0);
  }, [loadVisits]);

  // ?visitId (từ thông báo): lượt có trong danh sách đang hiện -> cuộn tới + tô sáng;
  // không có (khác trạng thái / trang) -> GET /api/guest-visits/{id} mở popup chi tiết; lỗi -> toast.
  useEffect(() => {
    const visitId = searchParams.get('visitId');
    if (!visitId || !visitsLoaded || loading) return;
    if (handledVisitIdRef.current === visitId) return;
    handledVisitIdRef.current = visitId;
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete('visitId');
      return next;
    }, { replace: true });
    if (visits.some((v) => v.id === visitId)) {
      setHighlightVisitId(visitId);
      requestAnimationFrame(() => {
        document.querySelector(`[data-visit-id="${visitId}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      });
      return;
    }
    guestVisitService.getVisit(visitId)
      .then((data) => { if (data) setDetail(data); })
      .catch(() => toast.error('Không xem được lượt khách này'));
  }, [searchParams, setSearchParams, visitsLoaded, loading, visits]);

  useEffect(() => {
    if (!highlightVisitId) return;
    const timer = setTimeout(() => setHighlightVisitId(null), 2000);
    return () => clearTimeout(timer);
  }, [highlightVisitId]);

  // Open Approve Modal
  const openApprove = (item) => {
    setApproveTarget(item);
    setApproveReason('');
    setActionError(null);
  };

  // Open Reject Modal
  const openReject = (item) => {
    setRejectTarget(item);
    setRejectReason('');
    setActionError(null);
  };

  // Open Revoke Modal
  const openRevoke = (item) => {
    setRevokeTarget(item);
    setRevokeReason('');
    setActionError(null);
  };

  // Handle Approve Submit
  const handleApprove = async () => {
    if (!approveTarget) return;
    setActionLoading(true);
    setActionError(null);
    try {
      await guestVisitService.reviewVisit(approveTarget.id, {
        version: approveTarget.version,
        decision: 'APPROVED',
        reason: approveReason.trim() || undefined,
      });
      setApproveTarget(null);
      setDetail(null);
      loadVisits(page);
    } catch (err) {
      if (err?.code === 'ERR_GUEST_016') {
        setActionError('Lượt khách đã được người khác cập nhật, vui lòng tải lại.');
        setTimeout(() => {
          setApproveTarget(null);
          setDetail(null);
          loadVisits(page);
        }, 1500);
      } else {
        setActionError(err?.message || 'Không thể duyệt lượt khách.');
      }
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Reject Submit
  const handleReject = async () => {
    if (!rejectTarget) return;
    const trimmed = rejectReason.trim();
    if (trimmed.length < 10 || trimmed.length > 500) {
      setRejectReasonError(`Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmed.length}).`);
      rejectReasonRef.current?.focus();
      return;
    }
    setActionLoading(true);
    setActionError(null);
    setRejectReasonError(null);
    try {
      await guestVisitService.reviewVisit(rejectTarget.id, {
        version: rejectTarget.version,
        decision: 'REJECTED',
        reason: trimmed,
      });
      setRejectTarget(null);
      setRejectReason('');
      setRejectReasonError(null);
      setDetail(null);
      loadVisits(page);
    } catch (err) {
      if (err?.code === 'ERR_GUEST_016') {
        setActionError('Lượt khách đã được người khác cập nhật, vui lòng tải lại.');
        setTimeout(() => {
          setRejectTarget(null);
          setRejectReason('');
          setRejectReasonError(null);
          setDetail(null);
          loadVisits(page);
        }, 1500);
      } else {
        setActionError(err?.message || 'Không thể từ chối lượt khách.');
      }
    } finally {
      setActionLoading(false);
    }
  };

  // Handle Revoke Submit
  const handleRevoke = async () => {
    if (!revokeTarget) return;
    const trimmed = revokeReason.trim();
    if (trimmed.length < 10 || trimmed.length > 500) {
      setRevokeReasonError(`Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmed.length}).`);
      revokeReasonRef.current?.focus();
      return;
    }
    setActionLoading(true);
    setActionError(null);
    setRevokeReasonError(null);
    try {
      await guestVisitService.revokeVisit(revokeTarget.id, {
        version: revokeTarget.version,
        reason: trimmed,
      });
      setRevokeTarget(null);
      setRevokeReason('');
      setRevokeReasonError(null);
      setDetail(null);
      loadVisits(page);
    } catch (err) {
      if (err?.code === 'ERR_GUEST_016') {
        setActionError('Lượt khách đã được người khác cập nhật, vui lòng tải lại.');
        setTimeout(() => {
          setRevokeTarget(null);
          setRevokeReason('');
          setRevokeReasonError(null);
          setDetail(null);
          loadVisits(page);
        }, 1500);
      } else {
        setActionError(err?.message || 'Không thể thu hồi lượt khách.');
      }
    } finally {
      setActionLoading(false);
    }
  };

  const isDetailApprovedAndActive = useMemo(() => {
    if (!detail || detail.status !== 'APPROVED') return false;
    return new Date(detail.endTime) > new Date();
  }, [detail]);

  // A-08 (BR-GV-10): FM không tự duyệt / từ chối / thu hồi lượt mình là người mời (BE trả 403 ERR_GUEST_022).
  // GuestVisitResponse không có hostId nên so theo mã người dùng.
  const isOwnVisit = Boolean(detail && user?.userCode && detail.hostCode === user.userCode);

  return (
    <div className="guest-visit">
      <PageHeader
        title="Duyệt lượt khách"
        description="Xem xét, phê duyệt hoặc từ chối các lượt đăng ký mời khách vào khuôn viên trường."
        actions={
          <Button
            variant="secondary"
            icon={RefreshCw}
            loading={loading}
            onClick={() => loadVisits(page)}
          >
            Làm mới
          </Button>
        }
      />

      {error && (
        <div className="guest-visit__alert guest-visit__alert--danger">
          <AlertCircle size={16} />
          <span>{error}</span>
        </div>
      )}

      {/* Filter Tabs */}
      <div className="guest-visit__filters">
        {STATUS_FILTERS.map((f) => (
          <button
            key={f.value}
            type="button"
            className={`guest-visit__filter-btn ${statusFilter === f.value ? 'guest-visit__filter-btn--active' : ''}`}
            onClick={() => setStatusFilter(f.value)}
          >
            {f.label}
          </button>
        ))}
      </div>

      {/* Table Card */}
      <div className="guest-visit__card">
        {loading && visits.length === 0 ? (
          <div className="guest-visit__empty">
            <RefreshCw size={24} className="guest-visit__spin" />
            <span>Đang tải danh sách lượt khách...</span>
          </div>
        ) : visits.length === 0 ? (
          <div className="guest-visit__empty">
            <Users size={32} />
            <span>Không có lượt khách nào phù hợp.</span>
          </div>
        ) : (
          <table className="guest-visit__table">
            <thead>
              <tr>
                <th>Người mời</th>
                <th>Khung giờ</th>
                <th>Khu vực</th>
                <th>Số khách</th>
                <th>Trạng thái</th>
                <th style={{ textAlign: 'right' }}>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {visits.map((v) => {
                const statusMeta = getGuestVisitStatus(v.status);
                const areaNames = (v.areas || []).map((a) => a.name).join(', ');
                return (
                  <tr
                    key={v.id}
                    data-visit-id={v.id}
                    className={highlightVisitId === v.id ? 'guest-visit__row--highlight' : undefined}
                  >
                    <td>
                      <div style={{ fontWeight: 600 }}>{v.hostName || '—'}</div>
                      <div className="guest-visit__muted">{v.hostCode || '—'}</div>
                    </td>
                    <td>
                      <div>{formatDateTime(v.startTime)}</div>
                      <div className="guest-visit__muted">→ {formatDateTime(v.endTime)}</div>
                    </td>
                    <td>
                      <span title={areaNames}>
                        {areaNames || '—'}
                      </span>
                    </td>
                    <td>
                      <span className="guest-visit__count">
                        <Users size={14} />
                        {(v.guests || []).length}
                      </span>
                    </td>
                    <td>
                      <Badge variant={statusMeta.variant}>{statusMeta.label}</Badge>
                    </td>
                    <td className="guest-visit__actions">
                      <Button
                        variant="secondary"
                        size="sm"
                        icon={Eye}
                        onClick={() => setDetail(v)}
                      >
                        Chi tiết
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}

        {totalPages > 1 && (
          <Pagination
            currentPage={page}
            totalPages={totalPages}
            totalElements={totalElements}
            pageSize={PAGE_SIZE}
            onPageChange={(newPage) => loadVisits(newPage)}
            itemLabel="lượt khách"
          />
        )}
      </div>

      {/* DETAIL MODAL */}
      <Modal
        isOpen={!!detail}
        onClose={() => setDetail(null)}
        title="Chi tiết lượt khách"
        icon={ClipboardCheck}
        size="lg"
        footer={
          <>
            {isOwnVisit && (detail?.status === 'PENDING' || isDetailApprovedAndActive) && (
              <span className="guest-visit__muted" role="note">
                Bạn là người mời lượt này nên không được tự duyệt, từ chối hoặc thu hồi.
              </span>
            )}
            {detail?.status === 'PENDING' && !isOwnVisit && (
              <>
                <Button
                  variant="danger"
                  icon={X}
                  onClick={() => openReject(detail)}
                >
                  Từ chối
                </Button>
                <Button
                  variant="primary"
                  icon={Check}
                  onClick={() => openApprove(detail)}
                >
                  Phê duyệt
                </Button>
              </>
            )}
            {isDetailApprovedAndActive && !isOwnVisit && (
              <Button
                variant="danger"
                icon={Ban}
                onClick={() => openRevoke(detail)}
              >
                Thu hồi
              </Button>
            )}
            <Button variant="secondary" onClick={() => setDetail(null)}>
              Đóng
            </Button>
          </>
        }
      >
        {detail && (
          <div className="guest-visit__detail">
            <div className="guest-visit__detail-row">
              <span className="guest-visit__label">Người mời</span>
              <div>
                <strong style={{ fontSize: '15px' }}>{detail.hostName}</strong>
                <div className="guest-visit__muted">Mã người dùng: {detail.hostCode || '—'}</div>
              </div>
            </div>

            <div className="guest-visit__detail-row">
              <span className="guest-visit__label">Trạng thái</span>
              <div>
                <Badge variant={getGuestVisitStatus(detail.status).variant}>
                  {getGuestVisitStatus(detail.status).label}
                </Badge>
              </div>
            </div>

            <div className="guest-visit__detail-row">
              <span className="guest-visit__label">Khung giờ</span>
              <span>
                {formatDateTime(detail.startTime)} → {formatDateTime(detail.endTime)}
              </span>
            </div>

            <div className="guest-visit__detail-row">
              <span className="guest-visit__label">Khu vực</span>
              <span>{(detail.areas || []).map((a) => a.name).join(', ')}</span>
            </div>

            <div className="guest-visit__detail-row">
              <span className="guest-visit__label">Mục đích</span>
              <span>{detail.purpose}</span>
            </div>

            <ReasonBlock
              label="Lý do duyệt / từ chối"
              value={detail.reviewReason}
              author={detail.reviewedByName}
              time={detail.reviewedAt}
              variant={detail.status === 'REJECTED' ? 'danger' : 'default'}
            />

            <ReasonBlock
              label="Lý do thu hồi"
              value={detail.revokeReason}
              author={detail.revokedByName}
              time={detail.revokedAt}
              variant="danger"
            />

            <ReasonBlock
              label="Lý do hủy"
              value={detail.cancelReason}
              time={detail.cancelledAt}
            />

            <div style={{ marginTop: '8px' }}>
              <span className="guest-visit__label" style={{ display: 'block', marginBottom: '6px' }}>
                Danh sách khách ({(detail.guests || []).length})
              </span>
              <table className="guest-visit__table">
                <thead>
                  <tr>
                    <th>Họ tên</th>
                    <th>Đơn vị</th>
                    <th>Trạng thái ảnh</th>
                  </tr>
                </thead>
                <tbody>
                  {(detail.guests || []).map((g) => {
                    const bio = getGuestBiometricStatus(g.biometricStatus);
                    return (
                      <tr key={g.id}>
                        <td>{g.fullName}</td>
                        <td>{g.organization || '—'}</td>
                        <td>
                          <Badge variant={bio.variant}>{bio.label}</Badge>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </Modal>

      {/* APPROVE MODAL */}
      <Modal
        isOpen={!!approveTarget}
        onClose={() => !actionLoading && setApproveTarget(null)}
        title="Phê duyệt lượt khách"
        icon={Check}
        iconVariant="success"
        size="md"
        closeOnBackdrop={!actionLoading}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setApproveTarget(null)}
              disabled={actionLoading}
            >
              Hủy bỏ
            </Button>
            <Button
              variant="primary"
              onClick={handleApprove}
              loading={actionLoading}
            >
              Xác nhận duyệt
            </Button>
          </>
        }
      >
        {approveTarget && (
          <div className="guest-visit__form">
            {actionError && (
              <div className="guest-visit__alert guest-visit__alert--danger">
                <AlertCircle size={16} />
                <span>{actionError}</span>
              </div>
            )}
            <p style={{ margin: 0, fontSize: '14px' }}>
              Bạn đang phê duyệt lượt khách của <strong>{approveTarget.hostName}</strong> ({approveTarget.hostCode}) với <strong>{(approveTarget.guests || []).length}</strong> khách.
            </p>
            <label className="guest-visit__field">
              <span className="guest-visit__label">Ghi chú phê duyệt (không bắt buộc)</span>
              <textarea
                rows={3}
                value={approveReason}
                onChange={(e) => setApproveReason(e.target.value)}
                placeholder="Nhập ghi chú cho lượt khách này..."
                disabled={actionLoading}
              />
            </label>
          </div>
        )}
      </Modal>

      {/* REJECT MODAL */}
      <Modal
        isOpen={!!rejectTarget}
        onClose={() => {
          if (!actionLoading) {
            setRejectTarget(null);
            setRejectReasonError(null);
          }
        }}
        title="Từ chối lượt khách"
        icon={X}
        iconVariant="danger"
        size="md"
        closeOnBackdrop={!actionLoading}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setRejectTarget(null);
                setRejectReasonError(null);
              }}
              disabled={actionLoading}
            >
              Hủy bỏ
            </Button>
            <Button
              variant="danger"
              onClick={handleReject}
              loading={actionLoading}
              disabled={actionLoading}
            >
              Xác nhận từ chối
            </Button>
          </>
        }
      >
        {rejectTarget && (
          <div className="guest-visit__form">
            {actionError && (
              <div className="guest-visit__alert guest-visit__alert--danger">
                <AlertCircle size={16} />
                <span>{actionError}</span>
              </div>
            )}
            <p style={{ margin: 0, fontSize: '14px' }}>
              Từ chối lượt khách của <strong>{rejectTarget.hostName}</strong> ({rejectTarget.hostCode}). Vui lòng nêu rõ lý do:
            </p>
            <ReasonTextarea
              ref={rejectReasonRef}
              label="Lý do từ chối"
              placeholder="Ví dụ: Khu vực đang có sự kiện bảo mật, không phù hợp tiếp khách vào khung giờ này..."
              value={rejectReason}
              onChange={(e) => {
                setRejectReason(e.target.value);
                if (rejectReasonError && e.target.value.trim().length >= 10 && e.target.value.trim().length <= 500) {
                  setRejectReasonError(null);
                }
              }}
              error={rejectReasonError}
              min={10}
              max={500}
              disabled={actionLoading}
              required
            />
          </div>
        )}
      </Modal>

      {/* REVOKE MODAL */}
      <Modal
        isOpen={!!revokeTarget}
        onClose={() => {
          if (!actionLoading) {
            setRevokeTarget(null);
            setRevokeReasonError(null);
          }
        }}
        title="Thu hồi lượt khách đã duyệt"
        subtitle="Khách sẽ mất quyền truy cập vào khu vực ngay lập tức."
        icon={Ban}
        iconVariant="danger"
        size="md"
        closeOnBackdrop={!actionLoading}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setRevokeTarget(null);
                setRevokeReasonError(null);
              }}
              disabled={actionLoading}
            >
              Hủy bỏ
            </Button>
            <Button
              variant="danger"
              onClick={handleRevoke}
              loading={actionLoading}
              disabled={actionLoading}
            >
              Xác nhận thu hồi
            </Button>
          </>
        }
      >
        {revokeTarget && (
          <div className="guest-visit__form">
            <div className="guest-visit__alert guest-visit__alert--warning">
              <AlertTriangle size={16} />
              <span>
                Cảnh báo: Thu hồi lượt khách sẽ hủy quyền vào các khu vực của khách ngay lập tức và toàn bộ dữ liệu khuôn mặt đã gắn sẽ bị xóa khỏi hệ thống.
              </span>
            </div>

            {actionError && (
              <div className="guest-visit__alert guest-visit__alert--danger">
                <AlertCircle size={16} />
                <span>{actionError}</span>
              </div>
            )}

            <p style={{ margin: 0, fontSize: '14px' }}>
              Thu hồi lượt khách của <strong>{revokeTarget.hostName}</strong> ({revokeTarget.hostCode}). Vui lòng nêu rõ lý do:
            </p>

            <ReasonTextarea
              ref={revokeReasonRef}
              label="Lý do thu hồi"
              placeholder="Ví dụ: Sự cố kỹ thuật trong khu vực, yêu cầu hủy khẩn cấp từ ban giám hiệu..."
              value={revokeReason}
              onChange={(e) => {
                setRevokeReason(e.target.value);
                if (revokeReasonError && e.target.value.trim().length >= 10 && e.target.value.trim().length <= 500) {
                  setRevokeReasonError(null);
                }
              }}
              error={revokeReasonError}
              min={10}
              max={500}
              disabled={actionLoading}
              required
            />
          </div>
        )}
      </Modal>
    </div>
  );
}
