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
import { searchUsers, updateUserAccessLevel, bulkUpdateUserAccessLevel } from '../../services/userService';
import { ROLES, ROLE_LABELS } from '../../constants/roles';
import { getAccessLevelConfig } from '../../utils/areaHelpers';
import Button from '../../components/ui/Button';
import Modal from '../../components/ui/Modal';
import PageHeader from '../../components/ui/PageHeader';
import { LoadingState, EmptyState, ErrorState } from '../../components/ui';
import Pagination from '../../components/ui/Pagination';
import ReasonTextarea from '../../components/ui/ReasonTextarea';
import '../../styles/UserAccessLevelPage.css';

const PAGE_SIZE = 20;

// BR-AL-28: nhãn kết quả đổi cấp theo từng người
const OUTCOME_LABELS = {
  UPDATED: 'Thành công',
  UNCHANGED: 'Không đổi',
  FAILED: 'Lỗi',
};

const EMPTY_BULK_MODAL = {
  isOpen: false,
  newLevel: 2,
  reason: '',
  reasonError: '',
  isSaving: false,
  result: null,
};

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
  const [loadError, setLoadError] = useState(null);
  const [selectedLevels, setSelectedLevels] = useState({});
  const debounceRef = useRef(null);
  const userReasonRef = useRef(null);

  // BR-AL-28: phân trang, lọc theo cấp hiện tại, chọn nhiều người (giữ lựa chọn khi đổi trang / đổi từ khoá)
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [levelFilter, setLevelFilter] = useState('');
  const [selected, setSelected] = useState({});
  const [bulkModal, setBulkModal] = useState(EMPTY_BULK_MODAL);
  const bulkReasonRef = useRef(null);
  const selectedList = Object.values(selected);

  const [confirmUserModal, setConfirmUserModal] = useState({
    isOpen: false,
    user: null,
    newLevel: 1,
    reason: '',
    reasonError: '',
    isSaving: false,
  });

  const handleSearchUsers = useCallback(async (q = '', pageNo = 0, level = '') => {
    const clean = (q || '').trim();
    setLoadingUsers(true);
    setLoadError(null);
    try {
      const res = await searchUsers(clean, pageNo, PAGE_SIZE, level ? Number(level) : undefined);
      const items = res?.content || [];
      setUsers(items);
      setPage(pageNo);
      setTotalPages(res?.totalPages ?? 0);
      setTotalElements(res?.totalElements ?? items.length);

      const initialMap = {};
      items.forEach((u) => {
        initialMap[u.id] = u.accessLevel ?? 1;
      });
      setSelectedLevels(initialMap);
    } catch (err) {
      console.error('Lỗi tải danh sách người dùng:', err);
      setLoadError(err?.message || 'Không thể tải danh sách người dùng');
      setUsers([]);
    } finally {
      setLoadingUsers(false);
    }
  }, []);

  // Tự động tải trang 1 của toàn bộ danh sách khi mở trang
  React.useEffect(() => {
    handleSearchUsers('', 0, '');
  }, [handleSearchUsers]);

  const handleKeywordChange = (e) => {
    const val = e.target.value;
    setKeyword(val);

    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }

    setLoadingUsers(true);
    debounceRef.current = setTimeout(() => {
      handleSearchUsers(val, 0, levelFilter);
    }, 300);
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

  const isSelfUser = (item) =>
    (currentUser?.id && item.id === currentUser.id) ||
    (currentUser?.email && item.email?.toLowerCase() === currentUser.email?.toLowerCase());

  const toggleSelect = (item) => {
    setSelected((prev) => {
      const next = { ...prev };
      if (next[item.id]) delete next[item.id];
      else next[item.id] = item;
      return next;
    });
  };

  const selectablePageUsers = users.filter((u) => !isSelfUser(u));
  const allPageSelected =
    selectablePageUsers.length > 0 && selectablePageUsers.every((u) => selected[u.id]);

  const toggleSelectPage = () => {
    setSelected((prev) => {
      const next = { ...prev };
      if (allPageSelected) selectablePageUsers.forEach((u) => delete next[u.id]);
      else selectablePageUsers.forEach((u) => { next[u.id] = u; });
      return next;
    });
  };

  const openBulkModal = () => {
    if (selectedList.length === 0) return;
    setBulkModal({ ...EMPTY_BULK_MODAL, isOpen: true });
  };

  const closeBulkModal = () => {
    const hadResult = Boolean(bulkModal.result);
    setBulkModal(EMPTY_BULK_MODAL);
    if (hadResult) setSelected({});
  };

  const handleConfirmBulk = async () => {
    const trimmedReason = bulkModal.reason?.trim() || '';
    if (trimmedReason.length < 10 || trimmedReason.length > 500) {
      setBulkModal((prev) => ({
        ...prev,
        reasonError: `Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmedReason.length}).`,
      }));
      bulkReasonRef.current?.focus();
      return;
    }
    setBulkModal((prev) => ({ ...prev, isSaving: true, reasonError: '' }));
    try {
      const result = await bulkUpdateUserAccessLevel(
        selectedList.map((u) => u.id),
        Number(bulkModal.newLevel),
        trimmedReason
      );
      setBulkModal((prev) => ({ ...prev, isSaving: false, result }));
      toast.success(
        `Đổi cấp xong: ${result.updated} thành công, ${result.unchanged} không đổi, ${result.failed} lỗi`
      );
      handleSearchUsers(keyword, page, levelFilter);
    } catch (err) {
      console.error('Lỗi đổi cấp nhiều người:', err);
      toast.error(err?.message || 'Không thể đổi cấp truy cập');
      setBulkModal((prev) => ({ ...prev, isSaving: false }));
    }
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
        title="Cấp truy cập người dùng"
        description="Tra cứu và điều chỉnh cấp độ truy cập (Level 1, Level 2, Level 3) cho người dùng trong khuôn viên."
      />

      <div className="tab-pane">
        {/* Search Toolbar */}
        <div className="access-level-search-card">
          <div className="access-level-search-box">
            <Search size={16} className="access-level-search-box__icon" />
            <input
              type="text"
              className="access-level-search-box__input"
              placeholder="Tìm kiếm người dùng theo họ tên hoặc mã số..."
              value={keyword}
              onChange={handleKeywordChange}
            />
            {keyword && (
              <button
                type="button"
                className="access-level-search-box__clear"
                onClick={() => {
                  setKeyword('');
                  handleSearchUsers('', 0, levelFilter);
                }}
                title="Xóa từ khóa"
              >
                <X size={14} />
              </button>
            )}
          </div>
          <select
            className="access-level-select access-level-filter"
            value={levelFilter}
            onChange={(e) => {
              const val = e.target.value;
              setLevelFilter(val);
              handleSearchUsers(keyword, 0, val);
            }}
            aria-label="Lọc theo cấp hiện tại"
          >
            <option value="">Mọi cấp hiện tại</option>
            {ACCESS_LEVELS.map((opt) => (
              <option key={opt.level} value={opt.level}>
                Đang ở cấp {opt.level}
              </option>
            ))}
          </select>
        </div>

        {isFM && selectedList.length > 0 && (
          <div className="access-level-bulk-bar" role="status">
            <span>
              Đã chọn <strong>{selectedList.length}</strong> người
            </span>
            <div className="access-level-bulk-bar__actions">
              <Button variant="outline" size="sm" onClick={() => setSelected({})}>
                Bỏ chọn
              </Button>
              <Button variant="primary" size="sm" icon={ShieldCheck} onClick={openBulkModal}>
                Đổi cấp
              </Button>
            </div>
          </div>
        )}

        {/* Table Card */}
        <div className="access-level-table-card">
          {loadingUsers ? (
            <LoadingState text="Đang tải danh sách người dùng..." />
          ) : loadError ? (
            <ErrorState
              message={loadError}
              onRetry={() => handleSearchUsers(keyword, page, levelFilter)}
            />
          ) : users.length === 0 ? (
            <EmptyState
              icon={Users}
              title="Không tìm thấy người dùng phù hợp"
              description={
                keyword || levelFilter
                  ? "Không có kết quả nào khớp với bộ lọc hiện tại. Thử kiểm tra lại từ khóa hoặc xóa bộ lọc."
                  : "Chưa có người dùng nào trong hệ thống."
              }
            />
          ) : (
            <div className="access-level-table-wrapper">
              <table className="access-level-table">
                <thead>
                  <tr>
                    {isFM && (
                      <th className="access-level-cell--check">
                        <input
                          type="checkbox"
                          checked={allPageSelected}
                          onChange={toggleSelectPage}
                          disabled={selectablePageUsers.length === 0}
                          aria-label="Chọn tất cả trang này"
                          title="Chọn tất cả trang này"
                        />
                      </th>
                    )}
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
                        {isFM && (
                          <td className="access-level-cell--check">
                            <input
                              type="checkbox"
                              checked={Boolean(selected[item.id])}
                              onChange={() => toggleSelect(item)}
                              disabled={isSelf}
                              aria-label={`Chọn ${item.userCode}`}
                              title={isSelf ? 'Bạn không thể tự thay đổi cấp truy cập của chính mình' : undefined}
                            />
                          </td>
                        )}
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
              {totalPages > 1 && (
                <Pagination
                  currentPage={page}
                  totalPages={totalPages}
                  totalElements={totalElements}
                  pageSize={PAGE_SIZE}
                  onPageChange={(newPage) => handleSearchUsers(keyword, newPage, levelFilter)}
                  itemLabel="người dùng"
                />
              )}
            </div>
          )}
        </div>
      </div>

      {/* Modal: đổi cấp nhiều người (BR-AL-28) */}
      {bulkModal.isOpen && (
        <Modal
          isOpen={bulkModal.isOpen}
          onClose={() => !bulkModal.isSaving && closeBulkModal()}
          title={bulkModal.result ? 'Kết quả đổi cấp truy cập' : `Đổi cấp truy cập cho ${selectedList.length} người`}
        >
          <div className="user-modal-content">
            {!bulkModal.result ? (
              <>
                <div className="modal-field">
                  <label className="change-summary-label" htmlFor="bulk-new-level">Cấp mới</label>
                  <select
                    id="bulk-new-level"
                    className="access-level-select"
                    value={bulkModal.newLevel}
                    onChange={(e) => setBulkModal((prev) => ({ ...prev, newLevel: Number(e.target.value) }))}
                    disabled={bulkModal.isSaving}
                  >
                    {ACCESS_LEVELS.map((opt) => (
                      <option key={opt.level} value={opt.level}>
                        {opt.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="modal-field">
                  <span className="change-summary-label">Những người sẽ bị đổi cấp:</span>
                  <ul className="access-level-bulk-list">
                    {selectedList.map((u) => (
                      <li key={u.id}>
                        {u.fullName} ({u.userCode}) — đang ở cấp {u.accessLevel ?? 1}
                      </li>
                    ))}
                  </ul>
                </div>

                <div className="modal-field">
                  <ReasonTextarea
                    ref={bulkReasonRef}
                    label="Lý do thay đổi"
                    required
                    value={bulkModal.reason}
                    onChange={(e) => {
                      const val = e.target.value;
                      setBulkModal((prev) => ({
                        ...prev,
                        reason: val,
                        reasonError:
                          prev.reasonError && val.trim().length >= 10 && val.trim().length <= 500
                            ? ''
                            : prev.reasonError,
                      }));
                    }}
                    min={10}
                    max={500}
                    placeholder="Nhập lý do điều chỉnh cấp độ truy cập (từ 10 đến 500 ký tự)..."
                    rows={3}
                    error={bulkModal.reasonError}
                    disabled={bulkModal.isSaving}
                  />
                </div>

                <div className="modal-actions">
                  <Button variant="outline" onClick={closeBulkModal} disabled={bulkModal.isSaving}>
                    Hủy bỏ
                  </Button>
                  <Button
                    variant="primary"
                    onClick={handleConfirmBulk}
                    disabled={bulkModal.isSaving}
                    icon={
                      bulkModal.isSaving ? (
                        <Loader2 size={16} className="animate-spin" />
                      ) : (
                        <ShieldCheck size={16} />
                      )
                    }
                  >
                    {bulkModal.isSaving ? 'Đang lưu...' : 'Xác nhận đổi cấp'}
                  </Button>
                </div>
              </>
            ) : (
              <>
                <p className="access-level-bulk-summary">
                  {bulkModal.result.updated} thành công · {bulkModal.result.unchanged} không đổi · {bulkModal.result.failed} lỗi
                </p>
                <div className="access-level-table-wrapper">
                  <table className="access-level-table">
                    <thead>
                      <tr>
                        <th>Người dùng</th>
                        <th>Kết quả</th>
                        <th>Chi tiết</th>
                      </tr>
                    </thead>
                    <tbody>
                      {bulkModal.result.results.map((r) => (
                        <tr key={r.userId}>
                          <td>{r.fullName ? `${r.fullName} (${r.userCode})` : r.userId}</td>
                          <td>
                            <span className={`access-level-outcome access-level-outcome--${r.outcome}`}>
                              {OUTCOME_LABELS[r.outcome] || r.outcome}
                            </span>
                          </td>
                          <td>
                            {r.outcome === 'UPDATED'
                              ? `Cấp ${r.oldLevel ?? '—'} → ${r.newLevel}`
                              : r.message || '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="modal-actions">
                  <Button variant="primary" onClick={closeBulkModal}>
                    Đóng
                  </Button>
                </div>
              </>
            )}
          </div>
        </Modal>
      )}

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
                onChange={(e) => {
                  const val = e.target.value;
                  setConfirmUserModal((prev) => ({
                    ...prev,
                    reason: val,
                    reasonError:
                      prev.reasonError && val.trim().length >= 10 && val.trim().length <= 500
                        ? ''
                        : prev.reasonError,
                  }));
                }}
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
                icon={
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
