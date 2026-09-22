import React, { useState, useEffect, useCallback } from 'react';
import {
  Users,
  UserPlus,
  CalendarClock,
  UserX,
  AlertCircle,
  Clock,
  CheckCircle2,
  Calendar,
  X,
  HelpCircle,
  FileText
} from 'lucide-react';
import { toast } from 'sonner';
import Modal from '../ui/Modal';
import Button from '../ui/Button';
import UserSearchCombobox from '../user/UserSearchCombobox';
import {
  getAssignedPersonnel,
  assignPersonnel,
  updateAssignedPersonnel,
  revokeAssignedPersonnel
} from '../../services/areaService';
import { ROLE_LABELS } from '../../constants/roles';
import {
  formatToOffsetDateTime,
  formatDisplayDateTime
} from '../../utils/areaHelpers';
import './AreaAssignedPersonnelModal.css';

const STATUS_FILTERS = [
  { key: 'ALL', label: 'Tất cả' },
  { key: 'ACTIVE', label: 'Đang hiệu lực' },
  { key: 'UPCOMING', label: 'Sắp hiệu lực' },
  { key: 'EXPIRED', label: 'Hết hạn' },
  { key: 'REVOKED', label: 'Đã thu hồi' },
];

export default function AreaAssignedPersonnelModal({
  isOpen,
  onClose,
  area,
  isFacilityManager = false,
}) {
  const [personnelList, setPersonnelList] = useState([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Form Thêm State
  const [showAddForm, setShowAddForm] = useState(false);
  const [selectedUser, setSelectedUser] = useState(null);
  const [validFromInput, setValidFromInput] = useState('');
  const [validToInput, setValidToInput] = useState('');
  const [isIndefinite, setIsIndefinite] = useState(false);
  const [noteInput, setNoteInput] = useState('');
  const [submittingAdd, setSubmittingAdd] = useState(false);

  // Modal Sửa Hạn State
  const [editItem, setEditItem] = useState(null);
  const [editValidToInput, setEditValidToInput] = useState('');
  const [editIsIndefinite, setEditIsIndefinite] = useState(false);
  const [submittingEdit, setSubmittingEdit] = useState(false);

  // Modal Thu Hồi State
  const [revokeItem, setRevokeItem] = useState(null);
  const [revokeReason, setRevokeReason] = useState('');
  const [submittingRevoke, setSubmittingRevoke] = useState(false);

  // Load danh sách nhân sự đã gán
  const loadData = useCallback(async () => {
    if (!area?.id) return;
    setLoading(true);
    try {
      const filterParam = statusFilter === 'ALL' ? null : statusFilter;
      const data = await getAssignedPersonnel(area.id, filterParam);
      setPersonnelList(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Lỗi khi tải danh sách nhân sự gán:', err);
      toast.error(err?.message || 'Không thể tải danh sách nhân sự gán');
    } finally {
      setLoading(false);
    }
  }, [area?.id, statusFilter]);

  useEffect(() => {
    if (isOpen && area?.id) {
      loadData();
      setShowAddForm(false);
      setSelectedUser(null);
      setValidFromInput('');
      setValidToInput('');
      setIsIndefinite(false);
      setNoteInput('');
      setEditItem(null);
      setRevokeItem(null);
    }
  }, [isOpen, area?.id, loadData]);

  if (!area) return null;

  // Xử lý Gán nhân sự mới
  const handleCreateAssignment = async (e) => {
    e?.preventDefault();
    if (!selectedUser) {
      toast.error('Vui lòng chọn người dùng cần gán');
      return;
    }

    setSubmittingAdd(true);
    try {
      const payload = {
        userId: selectedUser.id,
        validFrom: validFromInput ? formatToOffsetDateTime(validFromInput) : null,
        validTo: isIndefinite || !validToInput ? null : formatToOffsetDateTime(validToInput),
        note: noteInput.trim() || null,
      };

      await assignPersonnel(area.id, payload);
      toast.success(`Đã gán thành công người dùng ${selectedUser.fullName} vào ${area.name}`);
      setShowAddForm(false);
      setSelectedUser(null);
      setValidFromInput('');
      setValidToInput('');
      setIsIndefinite(false);
      setNoteInput('');
      loadData();
    } catch (err) {
      console.error('Lỗi khi gán nhân sự:', err);
      if (err?.status === 409 || err?.code === 'ERR_AP_004') {
        toast.error('Người này đã có quyền trùng thời gian tại khu vực này');
      } else {
        toast.error(err?.message || 'Không thể gán nhân sự vào khu vực');
      }
    } finally {
      setSubmittingAdd(false);
    }
  };

  // Mở modal sửa hạn
  const handleOpenEditValidTo = (item) => {
    setEditItem(item);
    if (!item.validTo) {
      setEditIsIndefinite(true);
      setEditValidToInput('');
    } else {
      setEditIsIndefinite(false);
      try {
        const d = new Date(item.validTo);
        const pad = (n) => String(n).padStart(2, '0');
        const localStr = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
        setEditValidToInput(localStr);
      } catch {
        setEditValidToInput('');
      }
    }
  };

  // Xác nhận sửa hạn
  const handleSaveValidTo = async (e) => {
    e?.preventDefault();
    if (!editItem) return;

    setSubmittingEdit(true);
    try {
      const payload = {
        validTo: editIsIndefinite || !editValidToInput ? null : formatToOffsetDateTime(editValidToInput),
      };

      await updateAssignedPersonnel(area.id, editItem.id, payload);
      toast.success(`Đã cập nhật thời hạn cho ${editItem.user?.fullName}`);
      setEditItem(null);
      loadData();
    } catch (err) {
      console.error('Lỗi khi sửa thời hạn:', err);
      if (err?.status === 409 || err?.code === 'ERR_AP_004') {
        toast.error('Người này đã có quyền trùng thời gian tại khu vực này');
      } else {
        toast.error(err?.message || 'Không thể sửa thời hạn gán');
      }
    } finally {
      setSubmittingEdit(false);
    }
  };

  // Mở modal thu hồi
  const handleOpenRevoke = (item) => {
    setRevokeItem(item);
    setRevokeReason('');
  };

  // Xác nhận thu hồi
  const handleConfirmRevoke = async (e) => {
    e?.preventDefault();
    if (!revokeItem) return;
    if (!revokeReason.trim()) {
      toast.error('Lý do thu hồi là bắt buộc');
      return;
    }

    setSubmittingRevoke(true);
    try {
      await revokeAssignedPersonnel(area.id, revokeItem.id, {
        reason: revokeReason.trim(),
      });
      toast.success(`Đã thu hồi quyền ra vào của ${revokeItem.user?.fullName}`);
      setRevokeItem(null);
      loadData();
    } catch (err) {
      console.error('Lỗi khi thu hồi quyền:', err);
      toast.error(err?.message || 'Không thể thu hồi quyền gán');
    } finally {
      setSubmittingRevoke(false);
    }
  };

  // Status Badge Component
  const renderStatusBadge = (status) => {
    switch (status) {
      case 'ACTIVE':
        return <span className="ap-badge ap-badge--active">Đang hiệu lực</span>;
      case 'UPCOMING':
        return <span className="ap-badge ap-badge--upcoming">Sắp hiệu lực</span>;
      case 'EXPIRED':
        return <span className="ap-badge ap-badge--expired">Hết hạn</span>;
      case 'REVOKED':
        return <span className="ap-badge ap-badge--revoked">Đã thu hồi</span>;
      default:
        return <span className="ap-badge">{status}</span>;
    }
  };

  // Active / Upcoming IDs to avoid duplicate selection in add form
  const activeUserIds = personnelList
    .filter((p) => p.status === 'ACTIVE' || p.status === 'UPCOMING')
    .map((p) => p.user?.id)
    .filter(Boolean);

  const mainFooter = (
    <div className="ap-modal__footer">
      <span className="ap-modal__count">
        Tổng số: <strong>{personnelList.length}</strong> bản ghi
      </span>
      <Button variant="secondary" onClick={onClose} type="button">
        Đóng
      </Button>
    </div>
  );

  return (
    <>
      <Modal
        isOpen={isOpen}
        onClose={onClose}
        title="Nhân sự chỉ định cố định (Assigned Personnel)"
        subtitle={`Quản lý danh sách người được đặc cách ra vào: ${area.name} (${area.code})`}
        icon={Users}
        iconVariant="brand"
        size="lg"
        footer={mainFooter}
      >
        <div className="ap-modal-content">
          {/* Top Bar: Filters + Add Button */}
          <div className="ap-top-bar">
            <div className="ap-filter-tabs">
              {STATUS_FILTERS.map((f) => (
                <button
                  key={f.key}
                  type="button"
                  className={`ap-filter-tab ${statusFilter === f.key ? 'is-active' : ''}`}
                  onClick={() => setStatusFilter(f.key)}
                >
                  {f.label}
                </button>
              ))}
            </div>

            {isFacilityManager && (
              <Button
                variant={showAddForm ? 'secondary' : 'primary'}
                size="sm"
                icon={showAddForm ? X : UserPlus}
                onClick={() => setShowAddForm(!showAddForm)}
              >
                {showAddForm ? 'Đóng form gán' : '+ Gán nhân sự'}
              </Button>
            )}
          </div>

          {/* Form Thêm Nhân Sự (Facility Manager) */}
          {isFacilityManager && showAddForm && (
            <form onSubmit={handleCreateAssignment} className="ap-add-form">
              <div className="ap-add-form__header">
                <UserPlus size={16} className="ap-add-form__icon" />
                <span className="ap-add-form__title">Gán người dùng vào khu vực</span>
              </div>

              <div className="ap-add-form__grid">
                {/* User Search Combobox */}
                <div className="ap-form-group ap-form-group--full">
                  <label className="ap-form-label">
                    Người dùng <span className="ap-form-required">*</span>
                  </label>
                  <UserSearchCombobox
                    onSelect={(u) => setSelectedUser(u)}
                    selectedUser={selectedUser}
                    onClear={() => setSelectedUser(null)}
                    excludeUserIds={activeUserIds}
                    placeholder="Tìm theo tên hoặc mã người dùng (tối thiểu 2 ký tự)..."
                  />
                </div>

                {/* Valid From */}
                <div className="ap-form-group">
                  <label className="ap-form-label">
                    Hiệu lực từ <span className="ap-form-hint">(Bỏ trống = bây giờ)</span>
                  </label>
                  <input
                    type="datetime-local"
                    className="ap-form-input"
                    value={validFromInput}
                    onChange={(e) => setValidFromInput(e.target.value)}
                  />
                </div>

                {/* Valid To */}
                <div className="ap-form-group">
                  <label className="ap-form-label">
                    Hiệu lực đến
                  </label>
                  <input
                    type="datetime-local"
                    className="ap-form-input"
                    value={validToInput}
                    onChange={(e) => {
                      setValidToInput(e.target.value);
                      if (e.target.value) setIsIndefinite(false);
                    }}
                    disabled={isIndefinite}
                  />
                  <label className="ap-form-checkbox-inline">
                    <input
                      type="checkbox"
                      checked={isIndefinite}
                      onChange={(e) => {
                        setIsIndefinite(e.target.checked);
                        if (e.target.checked) setValidToInput('');
                      }}
                    />
                    <span>Không thời hạn (vô hạn)</span>
                  </label>
                </div>

                {/* Note */}
                <div className="ap-form-group ap-form-group--full">
                  <label className="ap-form-label">Ghi chú (tuỳ chọn)</label>
                  <input
                    type="text"
                    className="ap-form-input"
                    placeholder="Ví dụ: Hiệu trưởng, Cán bộ phụ trách phòng thí nghiệm..."
                    value={noteInput}
                    onChange={(e) => setNoteInput(e.target.value)}
                    maxLength={1000}
                  />
                </div>
              </div>

              <div className="ap-add-form__actions">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setShowAddForm(false)}
                  disabled={submittingAdd}
                >
                  Huỷ
                </Button>
                <Button
                  type="submit"
                  variant="primary"
                  size="sm"
                  loading={submittingAdd}
                  disabled={!selectedUser}
                >
                  Xác nhận gán
                </Button>
              </div>
            </form>
          )}

          {/* Table List */}
          <div className="ap-table-container">
            {loading ? (
              <div className="ap-table-empty">
                <Clock size={24} className="animate-spin" />
                <span>Đang tải danh sách nhân sự...</span>
              </div>
            ) : personnelList.length === 0 ? (
              <div className="ap-table-empty">
                <Users size={32} />
                <p className="ap-table-empty__title">Không có nhân sự nào trong danh mục này</p>
                <span className="ap-table-empty__desc">
                  {statusFilter === 'ALL'
                    ? 'Khu vực này hiện chưa có nhân sự nào được gán cố định.'
                    : `Không tìm thấy bản ghi nào với trạng thái ${statusFilter}.`}
                </span>
              </div>
            ) : (
              <table className="ap-table">
                <thead>
                  <tr>
                    <th>Mã số</th>
                    <th>Họ và tên</th>
                    <th>Vai trò</th>
                    <th>Hiệu lực từ</th>
                    <th>Hiệu lực đến</th>
                    <th>Trạng thái</th>
                    <th>Người cấp</th>
                    <th>Ghi chú</th>
                    {isFacilityManager && <th style={{ textAlign: 'center' }}>Thao tác</th>}
                  </tr>
                </thead>
                <tbody>
                  {personnelList.map((item) => {
                    const isRevoked = item.status === 'REVOKED';

                    return (
                      <tr key={item.id} className={isRevoked ? 'ap-row--revoked' : ''}>
                        <td className="ap-cell--code">{item.user?.userCode || '—'}</td>
                        <td className="ap-cell--name">{item.user?.fullName || '—'}</td>
                        <td>
                          <span className={`ap-role-pill role--${item.user?.role}`}>
                            {ROLE_LABELS[item.user?.role] || item.user?.role || '—'}
                          </span>
                        </td>
                        <td>{formatDisplayDateTime(item.validFrom || item.createdAt)}</td>
                        <td>
                          {item.validTo ? (
                            formatDisplayDateTime(item.validTo)
                          ) : (
                            <span className="ap-text--indefinite">Không thời hạn</span>
                          )}
                        </td>
                        <td>{renderStatusBadge(item.status)}</td>
                        <td className="ap-cell--creator" title={item.createdBy || '—'}>
                          {item.createdBy || '—'}
                        </td>
                        <td className="ap-cell--note">
                          {isRevoked ? (
                            <span className="ap-revoke-note" title={item.revokeReason}>
                              Thu hồi: {item.revokeReason}
                            </span>
                          ) : (
                            item.note || '—'
                          )}
                        </td>

                        {/* Actions for FM */}
                        {isFacilityManager && (
                          <td style={{ textAlign: 'center' }}>
                            {!isRevoked ? (
                              <div className="ap-actions">
                                <button
                                  type="button"
                                  className="ap-action-btn ap-action-btn--edit"
                                  onClick={() => handleOpenEditValidTo(item)}
                                  title="Sửa ngày hết hạn"
                                >
                                  <CalendarClock size={14} />
                                </button>
                                <button
                                  type="button"
                                  className="ap-action-btn ap-action-btn--revoke"
                                  onClick={() => handleOpenRevoke(item)}
                                  title="Thu hồi quyền gán"
                                >
                                  <UserX size={14} />
                                </button>
                              </div>
                            ) : (
                              <span className="ap-action-disabled">—</span>
                            )}
                          </td>
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </Modal>

      {/* Modal Con: Sửa thời hạn validTo */}
      {editItem && (
        <Modal
          isOpen={Boolean(editItem)}
          onClose={() => setEditItem(null)}
          title="Sửa thời hạn gán nhân sự"
          subtitle={`Cập nhật ngày hết hạn cho: ${editItem.user?.fullName} (${editItem.user?.userCode})`}
          icon={CalendarClock}
          size="sm"
          footer={
            <div className="ap-modal__footer">
              <Button variant="secondary" onClick={() => setEditItem(null)} disabled={submittingEdit}>
                Huỷ
              </Button>
              <Button variant="primary" onClick={handleSaveValidTo} loading={submittingEdit}>
                Lưu thời hạn
              </Button>
            </div>
          }
        >
          <div className="ap-edit-form">
            <div className="ap-form-group">
              <label className="ap-form-label">Ngày hết hạn mới (Hiệu lực đến)</label>
              <input
                type="datetime-local"
                className="ap-form-input"
                value={editValidToInput}
                onChange={(e) => {
                  setEditValidToInput(e.target.value);
                  if (e.target.value) setEditIsIndefinite(false);
                }}
                disabled={editIsIndefinite}
              />
              <label className="ap-form-checkbox-inline" style={{ marginTop: '8px' }}>
                <input
                  type="checkbox"
                  checked={editIsIndefinite}
                  onChange={(e) => {
                    setEditIsIndefinite(e.target.checked);
                    if (e.target.checked) setEditValidToInput('');
                  }}
                />
                <span>Không thời hạn (quyền cố định vô hạn)</span>
              </label>
            </div>
          </div>
        </Modal>
      )}

      {/* Modal Con: Thu hồi quyền (Bắt buộc nhập lý do) */}
      {revokeItem && (
        <Modal
          isOpen={Boolean(revokeItem)}
          onClose={() => setRevokeItem(null)}
          title="Thu hồi quyền chỉ định ra vào"
          subtitle={`Thu hồi quyền của: ${revokeItem.user?.fullName} (${revokeItem.user?.userCode})`}
          icon={UserX}
          iconVariant="danger"
          size="sm"
          footer={
            <div className="ap-modal__footer">
              <Button variant="secondary" onClick={() => setRevokeItem(null)} disabled={submittingRevoke}>
                Huỷ
              </Button>
              <Button
                variant="danger"
                onClick={handleConfirmRevoke}
                loading={submittingRevoke}
                disabled={!revokeReason.trim()}
              >
                Xác nhận thu hồi
              </Button>
            </div>
          }
        >
          <div className="ap-revoke-form">
            <div className="ap-revoke-warning">
              <AlertCircle size={18} className="ap-revoke-warning__icon" />
              <div className="ap-revoke-warning__text">
                Hành động này sẽ <strong>thu hồi quyền cố định</strong> của người dùng tại khu vực này.
                Người dùng sẽ không thể vào khu vực trừ khi được cấp quyền lại hoặc có đơn được duyệt.
              </div>
            </div>

            <div className="ap-form-group" style={{ marginTop: '12px' }}>
              <label className="ap-form-label">
                Lý do thu hồi <span className="ap-form-required">*</span>
              </label>
              <textarea
                className="ap-form-textarea"
                rows={3}
                placeholder="Nhập lý do thu hồi (bắt buộc, ví dụ: Chuyển công tác, hết nhiệm kỳ)..."
                value={revokeReason}
                onChange={(e) => setRevokeReason(e.target.value)}
                maxLength={500}
                required
              />
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}
