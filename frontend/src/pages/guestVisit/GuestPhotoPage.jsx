import React, { useCallback, useEffect, useState } from 'react';
import {
  Eye,
  RefreshCw,
  AlertCircle,
  AlertTriangle,
  Users,
} from 'lucide-react';
import Modal from '../../components/ui/Modal';
import Button from '../../components/ui/Button';
import Badge from '../../components/ui/Badge';
import PageHeader from '../../components/ui/PageHeader';
import { LoadingState, EmptyState, ErrorState } from '../../components/ui';
import StatusTabs from '../../components/ui/StatusTabs';
import guestVisitService from '../../services/guestVisitService';
import systemConfigService from '../../services/systemConfigService';
import {
  getGuestBiometricStatus,
  getGuestVisitStatus,
  formatDateTime,
} from '../../utils/guestHelpers';
import '../../styles/GuestVisitPage.css';

// B2: ảnh khách do người mời gắn khi tạo lượt; ADMIN không gắn ảnh nữa, màn này chỉ còn xem ảnh của lượt đã kết thúc.
// BR-194 (BR-GV-27): lượt đã hết quyền mà khách còn ảnh trong thời hạn lưu -> ADMIN chỉ xem ảnh để điều tra
const ENDED_STATUSES = ['COMPLETED', 'REVOKED', 'CANCELLED'];
const ENDED_FETCH_SIZE = 100; // GuestVisitController giới hạn size tối đa 100
const ENDED_MAX_PAGES = 20;

const guestHasPhoto = (g) => g.biometricStatus === 'PHOTO_READY' || g.biometricStatus === 'PHOTO_ONLY';

/** Thời điểm khách hết quyền, lấy từ dữ liệu API: thu hồi -> revokedAt, huỷ -> cancelledAt, kết thúc -> endTime. */
function getAccessEnd(v) {
  if (v.status === 'REVOKED') return { label: 'Bị thu hồi lúc', at: v.revokedAt };
  if (v.status === 'CANCELLED') return { label: 'Bị huỷ lúc', at: v.cancelledAt };
  return { label: 'Hết giờ lượt lúc', at: v.endTime };
}

export default function GuestPhotoPage() {

  // View Photo Modal
  const [viewTarget, setViewTarget] = useState(null); // { visit, guest }
  const [photoUrl, setPhotoUrl] = useState(null);
  const [photoLoading, setPhotoLoading] = useState(false);
  const [photoError, setPhotoError] = useState(null);

  // B2: chỉ còn tab lượt đã kết thúc còn ảnh (chỉ xem); bỏ tab lượt đang hiệu lực và nút gắn ảnh
  const [tab, setTab] = useState('ENDED');
  const [endedVisits, setEndedVisits] = useState([]);
  const [endedLoading, setEndedLoading] = useState(false);
  const [endedError, setEndedError] = useState(null);
  const [endedLoaded, setEndedLoaded] = useState(false);
  const [endedTruncated, setEndedTruncated] = useState(false);
  const [retentionHours, setRetentionHours] = useState(null);

  // Thời hạn lưu ảnh (GUEST_FACE_RETENTION_HOURS) để giải thích trên trang; lỗi tải thì bỏ câu giải thích
  const loadRetentionConfig = useCallback(async () => {
    try {
      const configs = await systemConfigService.getSystemConfigs();
      const retention = (configs || []).find((c) => c.configKey === 'GUEST_FACE_RETENTION_HOURS');
      setRetentionHours(retention?.configValue ?? null);
    } catch {
      setRetentionHours(null);
    }
  }, []);

  // Lượt COMPLETED / REVOKED / CANCELLED còn khách có ảnh. API chỉ lọc một trạng thái mỗi lần -> gọi lần lượt, đọc hết các trang.
  const loadEndedVisits = useCallback(async () => {
    setEndedLoading(true);
    setEndedError(null);
    try {
      const found = [];
      let truncated = false;
      for (const status of ENDED_STATUSES) {
        let p = 0;
        let pages = 1;
        while (p < pages) {
          if (p >= ENDED_MAX_PAGES) {
            truncated = true;
            break;
          }
          const res = await guestVisitService.listVisits({ status, page: p, size: ENDED_FETCH_SIZE });
          (res?.content || []).forEach((v) => {
            if ((v.guests || []).some(guestHasPhoto)) found.push(v);
          });
          pages = res?.totalPages || 0;
          p += 1;
        }
      }
      found.sort((a, b) => new Date(getAccessEnd(b).at) - new Date(getAccessEnd(a).at));
      setEndedVisits(found);
      setEndedTruncated(truncated);
    } catch (err) {
      setEndedVisits([]);
      setEndedError(err?.message || 'Không thể tải danh sách lượt khách đã kết thúc.');
    } finally {
      setEndedLoaded(true);
      setEndedLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRetentionConfig();
  }, [loadRetentionConfig]);

  useEffect(() => {
    if (tab === 'ENDED' && !endedLoaded) {
      loadEndedVisits();
    }
  }, [tab, endedLoaded, loadEndedVisits]);


  // Open View Photo Modal
  const openViewModal = async (visit, guest) => {
    setViewTarget({ visit, guest });
    setPhotoUrl(null);
    setPhotoError(null);
    setPhotoLoading(true);

    try {
      const res = await guestVisitService.getGuestPhotoUrl(visit.id, guest.id);
      if (res?.url) {
        setPhotoUrl(res.url);
      } else {
        setPhotoError('Không thể lấy đường dẫn xem ảnh.');
      }
    } catch (err) {
      setPhotoError(err?.message || 'Không thể tải ảnh của khách.');
    } finally {
      setPhotoLoading(false);
    }
  };

  // Close View Photo Modal
  const closeViewModal = () => {
    setViewTarget(null);
    setPhotoUrl(null);
    setPhotoError(null);
  };

  return (
    <div className="guest-visit">
      <PageHeader
        title="Ảnh khách"
        description="Xem lại ảnh khách của các lượt đã kết thúc, bị thu hồi hoặc bị huỷ trong thời hạn lưu. Ảnh khách do người mời gắn khi tạo lượt, quản lý cơ sở vật chất xem khi duyệt."
        actions={
          <Button
            variant="secondary"
            icon={RefreshCw}
            loading={endedLoading}
            onClick={() => {
              loadRetentionConfig();
              if (tab === 'ENDED') {
                loadEndedVisits();
              }
            }}
          >
            Làm mới
          </Button>
        }
      />

      <StatusTabs
        ariaLabel="Nhóm lượt khách"
        items={[
          { key: 'ENDED', label: 'Lượt đã kết thúc (còn ảnh)', count: endedLoaded ? endedVisits.length : null },
        ]}
        value={tab}
        onChange={setTab}
      />

      {/* Lượt đã hết quyền còn ảnh (BR-194 (BR-GV-27)): chỉ xem ảnh để điều tra, không gắn / gắn lại */}
      {tab === 'ENDED' && (
        <div className="guest-visit__card">
          <div className="guest-visit__hint" style={{ marginBottom: '12px' }}>
            Lượt đã kết thúc, bị thu hồi hoặc bị huỷ mà ảnh khách còn trong thời hạn lưu
            {retentionHours ? ` (cấu hình hiện hành: ${retentionHours} giờ kể từ khi hết quyền)` : ''}.
            Hết thời hạn, hệ thống tự động xóa ảnh. Ở đây chỉ xem ảnh, không gắn ảnh mới.
          </div>

          {endedTruncated && (
            <div className="guest-visit__alert guest-visit__alert--warning">
              <AlertTriangle size={16} />
              <span>
                Danh sách có thể chưa đầy đủ: chỉ đọc {ENDED_MAX_PAGES * ENDED_FETCH_SIZE} lượt gần nhất của mỗi trạng thái.
              </span>
            </div>
          )}

          {endedLoading && endedVisits.length === 0 ? (
            <LoadingState size="sm" text="Đang tải lượt khách đã kết thúc..." />
          ) : endedError ? (
            <ErrorState size="sm" message={endedError} onRetry={loadEndedVisits} />
          ) : endedVisits.length === 0 ? (
            <EmptyState size="sm" icon={Users} title="Không có lượt đã kết thúc nào còn ảnh khách" />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
              {endedVisits.map((v) => {
                const areaNames = (v.areas || []).map((a) => a.name).join(', ');
                const visitStatus = getGuestVisitStatus(v.status);
                const accessEnd = getAccessEnd(v);
                const guestsWithPhoto = (v.guests || []).filter(guestHasPhoto);
                const endReason = v.status === 'REVOKED' ? v.revokeReason : v.status === 'CANCELLED' ? v.cancelReason : null;
                return (
                  <div
                    key={v.id}
                    style={{
                      border: '1px solid var(--theme-border)',
                      borderRadius: '6px',
                      padding: '16px',
                      background: 'var(--theme-bg-surface-elevated, var(--theme-bg-surface))',
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'flex-start',
                        flexWrap: 'wrap',
                        gap: '12px',
                        paddingBottom: '12px',
                        borderBottom: '1px solid var(--theme-border)',
                      }}
                    >
                      <div>
                        <div style={{ fontSize: '15px', fontWeight: 600, color: 'var(--theme-text-primary)' }}>
                          Người mời: {v.hostName} <span className="guest-visit__muted">({v.hostCode})</span>
                        </div>
                        <div className="guest-visit__muted" style={{ marginTop: '4px', fontSize: '13px' }}>
                          Mục đích: {v.purpose}
                        </div>
                        {endReason && (
                          <div className="guest-visit__muted" style={{ marginTop: '4px', fontSize: '13px' }}>
                            Lý do {v.status === 'REVOKED' ? 'thu hồi' : 'huỷ'}: {endReason}
                          </div>
                        )}
                      </div>
                      <div style={{ textAlign: 'right' }}>
                        <Badge variant={visitStatus.variant}>{visitStatus.label}</Badge>
                        <div style={{ fontSize: '13px', fontWeight: 500, marginTop: '6px' }}>
                          {formatDateTime(v.startTime)} → {formatDateTime(v.endTime)}
                        </div>
                        <div className="guest-visit__muted" style={{ fontSize: '12px', marginTop: '2px' }}>
                          Khu vực: {areaNames || '—'}
                        </div>
                        <div style={{ fontSize: '12px', marginTop: '2px', color: 'var(--theme-text-primary)' }}>
                          {accessEnd.label}: <strong>{formatDateTime(accessEnd.at)}</strong>
                          {v.status === 'REVOKED' && v.revokedByName ? ` (${v.revokedByName})` : ''}
                        </div>
                        {/* V137: người huỷ; lượt huỷ trước V137 không có dữ liệu thì không hiện dòng này */}
                        {v.status === 'CANCELLED' && v.cancelledByName && (
                          <div className="guest-visit__muted" style={{ fontSize: '12px', marginTop: '2px' }}>
                            {v.cancelledBySystem
                              ? `Hệ thống huỷ (khi ${v.cancelledByName} thay đổi khu vực)`
                              : `Người mời tự huỷ (${v.cancelledByName})`}
                          </div>
                        )}
                      </div>
                    </div>

                    <div style={{ marginTop: '12px' }}>
                      <div style={{ fontSize: '13px', fontWeight: 600, marginBottom: '8px', color: 'var(--theme-text-secondary)' }}>
                        Khách còn ảnh ({guestsWithPhoto.length}/{(v.guests || []).length})
                      </div>
                      <table className="guest-visit__table">
                        <thead>
                          <tr>
                            <th>Họ tên</th>
                            <th>Đơn vị</th>
                            <th>Trạng thái ảnh</th>
                            <th>Thời điểm gắn</th>
                            <th style={{ textAlign: 'right' }}>Thao tác</th>
                          </tr>
                        </thead>
                        <tbody>
                          {guestsWithPhoto.map((g) => {
                            const bio = getGuestBiometricStatus(g.biometricStatus);
                            return (
                              <tr key={g.id}>
                                <td style={{ fontWeight: 500 }}>{g.fullName}</td>
                                <td>{g.organization || '—'}</td>
                                <td>
                                  <Badge variant={bio.variant}>{bio.label}</Badge>
                                </td>
                                <td className="guest-visit__muted">
                                  {g.photoAttachedAt ? formatDateTime(g.photoAttachedAt) : '—'}
                                </td>
                                <td className="guest-visit__actions">
                                  <Button
                                    variant="secondary"
                                    size="sm"
                                    icon={Eye}
                                    onClick={() => openViewModal(v, g)}
                                    title="Xem ảnh khách"
                                  >
                                    Xem ảnh
                                  </Button>
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* VIEW PHOTO MODAL */}
      <Modal
        isOpen={!!viewTarget}
        onClose={closeViewModal}
        title="Xem ảnh khách"
        closeOnBackdrop
        icon={Eye}
        size="md"
        footer={
          <Button variant="secondary" onClick={closeViewModal}>
            Đóng
          </Button>
        }
      >
        {viewTarget && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px' }}>
            <div style={{ alignSelf: 'flex-start', fontSize: '14px' }}>
              Khách: <strong>{viewTarget.guest.fullName}</strong>
              {viewTarget.guest.organization && <span> ({viewTarget.guest.organization})</span>}
            </div>

            {photoLoading && (
              <div className="guest-visit__empty" style={{ padding: '40px 0' }}>
                <RefreshCw size={24} className="guest-visit__spin" />
                <span>Đang tải ảnh từ kho lưu trữ...</span>
              </div>
            )}

            {photoError && (
              <div className="guest-visit__alert guest-visit__alert--danger" style={{ width: '100%' }}>
                <AlertCircle size={16} />
                <span>{photoError}</span>
              </div>
            )}

            {photoUrl && !photoLoading && (
              <div
                style={{
                  padding: '8px',
                  borderRadius: '5px',
                  border: '1px solid var(--theme-border)',
                  background: 'var(--theme-bg-desc)',
                }}
              >
                <img
                  src={photoUrl}
                  alt={`Ảnh của khách ${viewTarget.guest.fullName}`}
                  style={{
                    maxWidth: '100%',
                    maxHeight: '340px',
                    borderRadius: '4px',
                    objectFit: 'contain',
                    display: 'block',
                  }}
                />
              </div>
            )}

            <div
              className="guest-visit__muted"
              style={{
                fontSize: '12px',
                textAlign: 'center',
                fontStyle: 'italic',
                marginTop: '4px',
              }}
            >
              Mỗi lần xem được ghi nhật ký kiểm toán hệ thống.
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
