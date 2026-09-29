import React, { useCallback, useEffect, useState } from 'react';
import {
  Camera,
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
import Pagination from '../../components/ui/Pagination';
import guestVisitService from '../../services/guestVisitService';
import systemConfigService from '../../services/systemConfigService';
import {
  getGuestBiometricStatus,
  formatDateTime,
} from '../../utils/guestHelpers';
import '../../styles/GuestVisitPage.css';

const PAGE_SIZE = 10;

export default function GuestPhotoPage() {
  const [page, setPage] = useState(0);
  const [visits, setVisits] = useState([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Consent Notice Version
  const [consentVersion, setConsentVersion] = useState(null);

  const [configError, setConfigError] = useState(null);

  // Attach Photo Modal
  const [attachTarget, setAttachTarget] = useState(null); // { visit, guest }
  const [selectedFile, setSelectedFile] = useState(null);
  const [previewUrl, setPreviewUrl] = useState(null);
  const [consentConfirmed, setConsentConfirmed] = useState(false);
  const [uploadLoading, setUploadLoading] = useState(false);
  const [uploadError, setUploadError] = useState(null);

  // View Photo Modal
  const [viewTarget, setViewTarget] = useState(null); // { visit, guest }
  const [photoUrl, setPhotoUrl] = useState(null);
  const [photoLoading, setPhotoLoading] = useState(false);
  const [photoError, setPhotoError] = useState(null);

  // Load consent version configuration
  const loadConsentConfig = useCallback(async () => {

    setConfigError(null);
    try {
      const configs = await systemConfigService.getSystemConfigs();
      const cfg = (configs || []).find((c) => c.configKey === 'GUEST_CONSENT_NOTICE_VERSION');
      if (cfg && cfg.configValue && cfg.configValue.trim()) {
        setConsentVersion(cfg.configValue.trim());
      } else {
        setConfigError('Chưa cấu hình phiên bản thông báo đồng ý (GUEST_CONSENT_NOTICE_VERSION).');
        setConsentVersion(null);
      }
    } catch (err) {
      setConfigError(err?.message || 'Không thể tải cấu hình phiên bản thông báo đồng ý.');
      setConsentVersion(null);
    }
  }, []);

  // Load approved visits (only those not yet expired)
  const loadVisits = useCallback(async (targetPage = 0) => {
    setLoading(true);
    setError(null);
    try {
      const res = await guestVisitService.listVisits({
        status: 'APPROVED',
        page: targetPage,
        size: PAGE_SIZE,
      });

      const now = new Date();
      const allContent = res?.content || [];
      // Filter out visits that have already ended
      const activeContent = allContent.filter((v) => new Date(v.endTime) > now);

      setVisits(activeContent);
      setTotalPages(res?.totalPages || 0);
      setTotalElements(res?.totalElements || 0);
      setPage(res?.number ?? targetPage);
    } catch (err) {
      setError(err?.message || 'Không thể tải danh sách lượt khách đã duyệt.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadConsentConfig();
    loadVisits(0);
  }, [loadConsentConfig, loadVisits]);

  // Clean up preview object URL
  useEffect(() => {
    return () => {
      if (previewUrl) {
        URL.revokeObjectURL(previewUrl);
      }
    };
  }, [previewUrl]);

  // Open Attach Modal
  const openAttachModal = (visit, guest) => {
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
    }
    setAttachTarget({ visit, guest });
    setSelectedFile(null);
    setPreviewUrl(null);
    setConsentConfirmed(false);
    setUploadError(null);
  };

  // Close Attach Modal
  const closeAttachModal = () => {
    if (uploadLoading) return;
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
    }
    setAttachTarget(null);
    setSelectedFile(null);
    setPreviewUrl(null);
    setConsentConfirmed(false);
    setUploadError(null);
  };

  // Handle File Selection
  const handleFileChange = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!['image/jpeg', 'image/png'].includes(file.type)) {
      setUploadError('Chỉ chấp nhận file ảnh định dạng JPG hoặc PNG.');
      return;
    }

    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
    }

    setSelectedFile(file);
    setPreviewUrl(URL.createObjectURL(file));
    setUploadError(null);
  };

  // Submit Photo Upload
  const handleUploadPhoto = async () => {
    if (!attachTarget || !selectedFile || !consentConfirmed || !consentVersion) return;
    setUploadLoading(true);
    setUploadError(null);

    try {
      await guestVisitService.uploadGuestPhoto(
        attachTarget.visit.id,
        attachTarget.guest.id,
        {
          file: selectedFile,
          consentConfirmed,
          consentNoticeVersion: consentVersion,
        }
      );
      closeAttachModal();
      loadVisits(page);
    } catch (err) {
      // Backend gắn ảnh không dùng version (không trả 016): hiện nguyên câu lỗi (030–037), giữ modal mở
      setUploadError(err?.message || 'Không thể gắn ảnh cho khách.');
    } finally {
      setUploadLoading(false);
    }
  };

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

  const isAttachDisabled = !selectedFile || !consentConfirmed || !consentVersion || uploadLoading;

  return (
    <div className="guest-visit">
      <PageHeader
        title="Quản lý ảnh khách"
        description="Gắn ảnh nhận diện khuôn mặt và quản lý dữ liệu sinh trắc học cho khách của các lượt đã duyệt."
        actions={
          <Button
            variant="secondary"
            icon={RefreshCw}
            loading={loading}
            onClick={() => {
              loadConsentConfig();
              loadVisits(page);
            }}
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

      {configError && (
        <div className="guest-visit__alert guest-visit__alert--warning">
          <AlertTriangle size={16} />
          <span>
            {configError} Nút gắn ảnh đã bị khóa để đảm bảo tuân thủ chính sách đồng ý.
          </span>
        </div>
      )}

      {/* List of Visits */}
      <div className="guest-visit__card">
        {loading && visits.length === 0 ? (
          <div className="guest-visit__empty">
            <RefreshCw size={24} className="guest-visit__spin" />
            <span>Đang tải danh sách lượt khách...</span>
          </div>
        ) : visits.length === 0 ? (
          <div className="guest-visit__empty">
            <Users size={32} />
            <span>Không có lượt khách đã duyệt nào đang hoạt động.</span>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
            {visits.map((v) => {
              const areaNames = (v.areas || []).map((a) => a.name).join(', ');
              return (
                <div
                  key={v.id}
                  style={{
                    border: '1px solid var(--theme-border)',
                    borderRadius: '12px',
                    padding: '16px',
                    background: 'var(--theme-bg-surface-elevated, var(--theme-bg-surface))',
                  }}
                >
                  {/* Visit Header */}
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
                    </div>
                    <div style={{ textAlign: 'right' }}>
                      <div style={{ fontSize: '13px', fontWeight: 500 }}>
                        {formatDateTime(v.startTime)} → {formatDateTime(v.endTime)}
                      </div>
                      <div className="guest-visit__muted" style={{ fontSize: '12px', marginTop: '2px' }}>
                        Khu vực: {areaNames || '—'}
                      </div>
                    </div>
                  </div>

                  {/* Guests Table */}
                  <div style={{ marginTop: '12px' }}>
                    <div style={{ fontSize: '13px', fontWeight: 600, marginBottom: '8px', color: 'var(--theme-text-secondary)' }}>
                      Danh sách khách ({(v.guests || []).length})
                    </div>
                    <table className="guest-visit__table">
                      <thead>
                        <tr>
                          <th>Họ tên</th>
                          <th>Đơn vị</th>
                          <th>Trạng thái ảnh</th>
                          <th>Phiên bản đồng ý</th>
                          <th>Thời điểm gắn</th>
                          <th style={{ textAlign: 'right' }}>Thao tác</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(v.guests || []).map((g) => {
                          const bio = getGuestBiometricStatus(g.biometricStatus);
                          const isDeleted = g.biometricStatus === 'DELETED';
                          const hasPhoto = g.biometricStatus === 'PHOTO_READY' || g.biometricStatus === 'PHOTO_ONLY';

                          return (
                            <tr key={g.id}>
                              <td style={{ fontWeight: 500 }}>{g.fullName}</td>
                              <td>{g.organization || '—'}</td>
                              <td>
                                <Badge variant={bio.variant}>{bio.label}</Badge>
                              </td>
                              <td className="guest-visit__muted">
                                {g.consentNoticeVersion || '—'}
                              </td>
                              <td className="guest-visit__muted">
                                {g.photoAttachedAt ? formatDateTime(g.photoAttachedAt) : '—'}
                              </td>
                              <td className="guest-visit__actions">
                                {!isDeleted ? (
                                  <div style={{ display: 'inline-flex', gap: '6px' }}>
                                    {hasPhoto && (
                                      <Button
                                        variant="secondary"
                                        size="sm"
                                        icon={Eye}
                                        onClick={() => openViewModal(v, g)}
                                        title="Xem ảnh khách"
                                      >
                                        Xem ảnh
                                      </Button>
                                    )}
                                    <Button
                                      variant={hasPhoto ? 'secondary' : 'primary'}
                                      size="sm"
                                      icon={Camera}
                                      onClick={() => openAttachModal(v, g)}
                                      disabled={!consentVersion}
                                      title={!consentVersion ? 'Chưa có cấu hình thông báo đồng ý' : 'Gắn ảnh'}
                                    >
                                      {hasPhoto ? 'Gắn lại' : 'Gắn ảnh'}
                                    </Button>
                                  </div>
                                ) : (
                                  <span className="guest-visit__muted">Đã xoá dữ liệu</span>
                                )}
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

      {/* ATTACH PHOTO MODAL */}
      <Modal
        isOpen={!!attachTarget}
        onClose={closeAttachModal}
        title="Gắn ảnh nhận diện cho khách"
        icon={Camera}
        size="md"
        closeOnBackdrop={!uploadLoading}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={closeAttachModal}
              disabled={uploadLoading}
            >
              Hủy bỏ
            </Button>
            <Button
              variant="primary"
              onClick={handleUploadPhoto}
              loading={uploadLoading}
              disabled={isAttachDisabled}
            >
              Lưu và trích xuất
            </Button>
          </>
        }
      >
        {attachTarget && (
          <div className="guest-visit__form">
            {uploadError && (
              <div className="guest-visit__alert guest-visit__alert--danger">
                <AlertCircle size={16} />
                <span>{uploadError}</span>
              </div>
            )}

            {(attachTarget.guest.biometricStatus === 'PHOTO_READY' ||
              attachTarget.guest.biometricStatus === 'PHOTO_ONLY') && (
              <div className="guest-visit__alert guest-visit__alert--warning">
                <AlertTriangle size={16} />
                <span>
                  Lưu ý: Khách này đã có ảnh. Gắn ảnh mới sẽ xoá ảnh cũ và trích xuất lại dữ liệu khuôn mặt.
                </span>
              </div>
            )}

            <div style={{ fontSize: '14px' }}>
              Khách: <strong>{attachTarget.guest.fullName}</strong>
              {attachTarget.guest.organization && (
                <span> ({attachTarget.guest.organization})</span>
              )}
            </div>

            {/* File Input */}
            <label className="guest-visit__field">
              <span className="guest-visit__label">Chọn file ảnh (JPG, PNG) *</span>
              <input
                type="file"
                accept="image/jpeg,image/png"
                onChange={handleFileChange}
                disabled={uploadLoading}
                style={{
                  background: 'var(--theme-bg-input)',
                  border: '1px solid var(--theme-input-border)',
                  borderRadius: '8px',
                  padding: '8px',
                  color: 'var(--theme-text-primary)',
                }}
              />
              <span className="guest-visit__hint">
                Ảnh phải rõ khuôn mặt, đúng 1 người và không quá giới hạn dung lượng hệ thống.
              </span>
            </label>

            {/* Local Preview */}
            {previewUrl && (
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: '8px',
                  padding: '12px',
                  border: '1px solid var(--theme-border)',
                  borderRadius: '10px',
                  background: 'var(--theme-bg-desc)',
                }}
              >
                <img
                  src={previewUrl}
                  alt="Xem trước ảnh khách"
                  style={{
                    maxWidth: '100%',
                    maxHeight: '220px',
                    borderRadius: '8px',
                    objectFit: 'contain',
                  }}
                />
                <span className="guest-visit__muted" style={{ fontSize: '12px' }}>
                  {selectedFile?.name} ({(selectedFile?.size / 1024).toFixed(1)} KB)
                </span>
              </div>
            )}

            {/* Consent Checkbox */}
            <div
              style={{
                marginTop: '6px',
                padding: '10px 12px',
                border: '1px solid var(--theme-border)',
                borderRadius: '8px',
                background: 'var(--theme-bg-desc)',
              }}
            >
              <label
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: '10px',
                  cursor: 'pointer',
                  fontSize: '13px',
                  lineHeight: '1.4',
                  color: 'var(--theme-text-primary)',
                }}
              >
                <input
                  type="checkbox"
                  checked={consentConfirmed}
                  onChange={(e) => setConsentConfirmed(e.target.checked)}
                  disabled={uploadLoading || !consentVersion}
                  style={{ marginTop: '2px', width: '16px', height: '16px', cursor: 'pointer' }}
                />
                <span>
                  Khách đã đồng ý chụp ảnh tại quầy theo thông báo phiên bản{' '}
                  <strong>{consentVersion || '—'}</strong>.
                </span>
              </label>
            </div>
          </div>
        )}
      </Modal>

      {/* VIEW PHOTO MODAL */}
      <Modal
        isOpen={!!viewTarget}
        onClose={closeViewModal}
        title="Xem ảnh khách"
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
                  borderRadius: '10px',
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
                    borderRadius: '8px',
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
