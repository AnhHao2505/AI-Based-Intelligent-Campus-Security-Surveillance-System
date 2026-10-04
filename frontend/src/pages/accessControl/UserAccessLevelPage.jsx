import React, { useState, useCallback, useRef } from 'react';
import {
  Search,
  Loader2,
  Users,
  ShieldCheck,
  AlertTriangle,
  X,
} from 'lucide-react';
import { toast } from 'sonner';
import { useAuth } from '../../context/AuthContext';
import { searchUsers, updateUserAccessLevel } from '../../services/userService';
import { ROLES, ROLE_LABELS } from '../../constants/roles';
import { getAccessLevelConfig } from '../../utils/areaHelpers';
import Button from '../../components/ui/Button';
import Modal from '../../components/ui/Modal';
import PageHeader from '../../components/ui/PageHeader';
import ReasonTextarea from '../../components/ui/ReasonTextarea';
import './UserAccessLevelPage.css';

const ACCESS_LEVELS = [
  { level: 1, name: 'Cấp 1 — Mọi người dùng' },
  { level: 2, name: 'Cấp 2 — Nhân viên' },
  { level: 3, name: 'Cấp 3 — Cấp cao' },
];

export default function UserAccessLevelPage() {
  const { user: currentUser } = useAuth();
  const isFM = currentUser?.role === ROLES.FACILITY_MANAGER;
  const isAdmin = currentUser?.role === ROLES.ADMIN;

  const [keyword, setKeyword] = useState('');
  const [users, setUsers] = useState([]);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [selectedLevels, setSelectedLevels] = useState({});
  const debounceRef = useRef(null);
  const userReasonRef = useRef(null);

  const [confirmUserModal, setConfirmUserModal] = useState({
    isOpen: false,
    user: null,
    newLevel: 1,
    reason: '',
    reasonError: '',
    isSaving: false,
  });

  const handleSearchUsers = useCallback(async (q) => {
    const clean = q.trim();
    if (clean.length < 2) {
      setUsers([]);
      setLoadingUsers(false);
      setHasSearched(false);
      return;
    }

    setLoadingUsers(true);
    setHasSearched(true);
    try {
      const res = await searchUsers(clean, 0, 20);
      const items = res?.content || [];
      setUsers(items);

      const initialMap = {};
      items.forEach((u) => {
        initialMap[u.id] = u.accessLevel ?? 1;
      });
      setSelectedLevels(initialMap);
    } catch (err) {
      console.error('Lỗi tìm kiếm người dùng:', err);
      toast.error(err?.message || 'Không thể tìm kiếm người dùng');
      setUsers([]);
    } finally {
      setLoadingUsers(false);
    }
  }, []);

  const handleKeywordChange = (e) => {
    const val = e.target.value;
    setKeyword(val);

    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }

    if (val.trim().length >= 2) {
      setLoadingUsers(true);
      debounceRef.current = setTimeout(() => {
        handleSearchUsers(val);
      }, 300);
    } else {
      setUsers([]);
      setLoadingUsers(false);
      setHasSearched(false);
    }
  };

  const handleLevelChange = (userId, newLevel) => {
    setSelectedLevels((prev) => ({
      ...prev,
      [userId]: Number(newLevel),
    }));
  };

  const openSaveUserLevelModal = (targetUser) => {
    const newLevel = selectedLevels[targetUser.id];
    if (newLevel === undefined || newLevel === targetUser.accessLevel) {
      return;
    }
    setConfirmUserModal({
      isOpen: true,
      user: targetUser,
      newLevel,
      reason: '',
      reasonError: '',
      isSaving: false,
    });
  };

  const handleConfirmSaveUserLevel = async () => {
    const { user, newLevel, reason } = confirmUserModal;
    const trimmedReason = reason?.trim() || '';
    if (trimmedReason.length < 10 || trimmedReason.length > 500) {
      setConfirmUserModal((prev) => ({
        ...prev,
        reasonError: `Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmedReason.length}).`,
      }));
      userReasonRef.current?.focus();
      return;
    }

    setConfirmUserModal((prev) => ({ ...prev, isSaving: true }));
    try {
      const updated = await updateUserAccessLevel(user.id, newLevel, trimmedReason);
      toast.success(
        `Đã cập nhật cấp độ truy cập của ${user.fullName} thành Cấp ${updated.accessLevel}`
      );

      setUsers((prev) =>
        prev.map((u) => (u.id === user.id ? { ...u, accessLevel: updated.accessLevel } : u))
      );
      setConfirmUserModal({
        isOpen: false,
        user: null,
        newLevel: 1,
        reason: '',
        reasonError: '',
        isSaving: false,
      });
    } catch (err) {
      console.error('Lỗi cập nhật cấp độ truy cập:', err);
      toast.error(err?.message || 'Không thể cập nhật cấp độ truy cập');
      setConfirmUserModal((prev) => ({ ...prev, isSaving: false }));
    }
  };

  return (
    <div className="user-access-level-page">
      <PageHeader
        title="Phân quyền Cấp độ Người dùng"
        subtitle="Tra cứu và điều chỉnh cấp độ truy cập (Level 1, Level 2, Level 3) cho người dùng trong khuôn viên."
      />

      <div className="tab-pane">
        {/* Search Toolbar */}
        <div className="access-level-search-card">
          <div className="access-level-search-box">
            <Search size={16} className="access-level-search-box__icon" />
            <input
              type="text"
              className="access-level-search-box__input"
              placeholder="Tìm kiếm người dùng theo họ tên hoặc mã số (tối thiểu 2 ký tự)..."
              value={keyword}
              onChange={handleKeywordChange}
            />
            {keyword && (
              <button
                type="button"
                className="access-level-search-box__clear"
                onClick={() => {
                  setKeyword('');
                  setUsers([]);
                  setHasSearched(false);
                }}
                title="Xoá từ khoá"
              >
                <X size={14} />
              </button>
            )}
          </div>
        </div>

        {/* Table Card */}
        <div className="access-level-table-card">
          {loadingUsers ? (
            <div className="access-level-empty">
              <Loader2 size={28} className="animate-spin" />
              <p>Đang tìm kiếm người dùng...</p>
            </div>
          ) : !hasSearched ? (
            <div className="access-level-empty">
              <Search size={32} />
              <p className="access-level-empty__title">Tra cứu người dùng để điều chỉnh cấp độ</p>
              <span className="access-level-empty__desc">
                Nhập tối thiểu 2 ký tự vào ô tìm kiếm bên trên để xem kết quả.
              </span>
            </div>
          ) : users.length === 0 ? (
            <div className="access-level-empty">
              <Users size={32} />
              <p className="access-level-empty__title">Không tìm thấy người dùng phù hợp</p>
              <span className="access-level-empty__desc">
                Vui lòng kiểm tra lại từ khóa tìm kiếm (tên hoặc mã số người dùng).
              </span>
            </div>
          ) : (
            <div className="access-level-table-wrapper">
              <table className="access-level-table">
                <thead>
                  <tr>
                    <th>Mã định danh</th>
                    <th>Họ và tên</th>
                    <th>Vai trò</th>
                    <th>Cấp độ hiện tại</th>
                    <th>Cấp độ mới</th>
                    <th style={{ textAlign: 'center' }}>Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((item) => {
                    const isSelf =
                      (currentUser?.id && item.id === currentUser.id) ||
                      (currentUser?.email &&
                        item.email?.toLowerCase() === currentUser.email?.toLowerCase());

                    const currentLevel = item.accessLevel ?? 1;
                    const selectedLevel = selectedLevels[item.id] ?? currentLevel;
                    const isDirty = selectedLevel !== currentLevel;

                    return (
                      <tr key={item.id} className={isSelf ? 'access-level-row--self' : ''}>
                        <td className="access-level-cell--code">{item.userCode}</td>
                        <td className="access-level-cell--name">
                          <div className="user-name-wrapper">
                            <span>{item.fullName}</span>
                            {isSelf && (
                              <span className="user-self-badge" title="Tài khoản đang đăng nhập">
                                Bạn
                              </span>
                            )}
                          </div>
                        </td>
                        <td>
                          <span className={`access-level-role-pill role--${item.role}`}>
                            {ROLE_LABELS[item.role] || item.role}
                          </span>
                        </td>
                        <td>
                          <span className={getAccessLevelConfig(currentLevel).className}>
                            {getAccessLevelConfig(currentLevel).label}
                          </span>
                        </td>
                        <td>
                          <div className="access-level-select-wrap">
                            <select
                              className="access-level-select"
                              value={selectedLevel}
                              onChange={(e) => handleLevelChange(item.id, e.target.value)}
                              disabled={isSelf || (!isFM && !isAdmin)}
                              title={
                                isSelf
                                  ? 'Bạn không thể tự thay đổi cấp truy cập của chính mình'
                                  : undefined
                              }
                            >
                              {ACCESS_LEVELS.map((opt) => (
                                <option key={opt.level} value={opt.level}>
                                  {opt.name}
                                </option>
                              ))}
                            </select>
                          </div>
                        </td>
                        <td style={{ textAlign: 'center' }}>
                          {isSelf ? (
                            <span
                              className="access-level-self-hint"
                              title="Không thể tự thay đổi cấp truy cập của chính mình"
                            >
                              Không khả dụng
                            </span>
                          ) : (
                            <Button
                              variant="primary"
                              size="sm"
                              onClick={() => openSaveUserLevelModal(item)}
                              disabled={!isDirty}
                            >
                              Lưu
                            </Button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Modal: Xác nhận thay đổi cấp độ người dùng */}
      {confirmUserModal.isOpen && (
        <Modal
          isOpen={confirmUserModal.isOpen}
          onClose={() =>
            !confirmUserModal.isSaving &&
            setConfirmUserModal((prev) => ({ ...prev, isOpen: false }))
          }
          title="Xác nhận điều chỉnh cấp độ truy cập"
        >
          <div className="user-modal-content">
            <div className="change-summary-box">
              <div className="change-summary-item">
                <span className="change-summary-label">Người dùng:</span>
                <strong>{confirmUserModal.user?.fullName} ({confirmUserModal.user?.userCode})</strong>
              </div>
              <div className="change-summary-row">
                <div className="change-summary-item">
                  <span className="change-summary-label">Cấp hiện tại:</span>
                  <span className={getAccessLevelConfig(confirmUserModal.user?.accessLevel).className}>
                    {getAccessLevelConfig(confirmUserModal.user?.accessLevel).label}
                  </span>
                </div>
                <span className="audit-arrow">→</span>
                <div className="change-summary-item">
                  <span className="change-summary-label">Cấp mới:</span>
                  <span className={getAccessLevelConfig(confirmUserModal.newLevel).className}>
                    {getAccessLevelConfig(confirmUserModal.newLevel).label}
                  </span>
                </div>
              </div>
            </div>

            <div className="modal-field">
              <ReasonTextarea
                ref={userReasonRef}
                label="Lý do thay đổi"
                required
                value={confirmUserModal.reason}
                onChange={(val) =>
                  setConfirmUserModal((prev) => ({
                    ...prev,
                    reason: val,
                    reasonError:
                      prev.reasonError && val.trim().length >= 10 && val.trim().length <= 500
                        ? ''
                        : prev.reasonError,
                  }))
                }
                minLength={10}
                maxLength={500}
                placeholder="Nhập lý do cụ thể điều chỉnh cấp độ truy cập (từ 10 đến 500 ký tự)..."
                rows={3}
                error={confirmUserModal.reasonError}
                disabled={confirmUserModal.isSaving}
              />
            </div>

            <div className="modal-actions">
              <Button
                variant="outline"
                onClick={() =>
                  setConfirmUserModal((prev) => ({ ...prev, isOpen: false }))
                }
                disabled={confirmUserModal.isSaving}
              >
                Hủy bỏ
              </Button>
              <Button
                variant="primary"
                onClick={handleConfirmSaveUserLevel}
                disabled={confirmUserModal.isSaving}
                leftIcon={
                  confirmUserModal.isSaving ? (
                    <Loader2 size={16} className="animate-spin" />
                  ) : (
                    <ShieldCheck size={16} />
                  )
                }
              >
                {confirmUserModal.isSaving ? 'Đang lưu...' : 'Xác nhận cập nhật'}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
