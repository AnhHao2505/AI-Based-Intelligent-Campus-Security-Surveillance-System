import React, { useState, useCallback, useRef } from 'react';
import {
  Search,
  Loader2,
  Users,
  ShieldCheck,
  AlertTriangle,
  X,
  ListChecks,
} from 'lucide-react';
import { toast } from 'sonner';
import { useAuth } from '../../context/AuthContext';
import {
  searchUsers,
  updateUserAccessLevel,
  bulkUpdateUserAccessLevel,
  getImportBatchOptions,
  getAccessLevelFilterIds,
} from '../../services/userService';
import { formatDateTime } from '../../utils/formatDateTime';
import { ROLES, ROLE_LABELS } from '../../constants/roles';
import { getAccessLevelConfig } from '../../utils/areaHelpers';
import Button from '../../components/ui/Button';
import Modal from '../../components/ui/Modal';
import PageHeader from '../../components/ui/PageHeader';
import { LoadingState, EmptyState, ErrorState, Select } from '../../components/ui';
import Pagination from '../../components/ui/Pagination';
import ReasonTextarea from '../../components/ui/ReasonTextarea';
import { useReasonLimits } from '../../hooks/useReasonLimits';
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

// BR-AL-32: FM chỉ đổi cấp của người dùng thường và bảo vệ; dòng ADMIN / FM không có nút đổi, không tích chọn được
const LOCKED_TARGET_ROLES = [ROLES.ADMIN, ROLES.FACILITY_MANAGER];
const SELF_LOCK_REASON = 'Bạn không thể tự thay đổi cấp truy cập của chính mình';
const ROLE_LOCK_REASON = 'Chỉ đổi được cấp truy cập của người dùng thường và bảo vệ; không đổi cấp của quản trị viên hoặc quản lý cơ sở vật chất';

// Luồng nạp tài khoản theo lô (audit IMPORT): cùng tên gọi với màn Quản lý tài khoản
const IMPORT_FLOW_LABELS = {
  NORMAL: 'Người dùng',
  STAFF: 'Cán bộ',
};

const batchOptionLabel = (b) =>
  [
    formatDateTime(b.createdAt),
    IMPORT_FLOW_LABELS[b.flow],
    b.fileName,
    `${b.activeCount} người`,
  ]
    .filter(Boolean)
    .join(' · ');

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
  // Độ dài lý do theo REASON_MIN_LENGTH / REASON_MAX_LENGTH (như các màn khác), không viết cứng con số
  const reasonLimits = useReasonLimits();

  // BR-AL-28: phân trang, lọc theo cấp hiện tại, chọn nhiều người (giữ lựa chọn khi đổi trang / đổi từ khoá)
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [levelFilter, setLevelFilter] = useState('');
  // Lọc theo lô import: FM chọn một lô rồi "Chọn tất cả kết quả lọc" để đổi cấp cả lô
  const [batchFilter, setBatchFilter] = useState('');
  const [batchOptions, setBatchOptions] = useState([]);
  const [selectingAll, setSelectingAll] = useState(false);
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

  const handleSearchUsers = useCallback(async (q = '', pageNo = 0, level = '', batch = '') => {
    const clean = (q || '').trim();
    setLoadingUsers(true);
    setLoadError(null);
    try {
      const res = await searchUsers(clean, pageNo, PAGE_SIZE, level ? Number(level) : undefined, batch || undefined);
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
    handleSearchUsers('', 0, '', '');
  }, [handleSearchUsers]);

  // Danh sách lô cho ô lọc (chỉ tóm tắt lô, không có tên / email người trong lô)
  React.useEffect(() => {
    getImportBatchOptions()
      .then((res) => setBatchOptions(Array.isArray(res) ? res : []))
      .catch((err) => {
        console.error('Lỗi tải danh sách lô import:', err);
        setBatchOptions([]);
      });
  }, []);

  const handleKeywordChange = (e) => {
    const val = e.target.value;
    setKeyword(val);

    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }

    setLoadingUsers(true);
    debounceRef.current = setTimeout(() => {
      handleSearchUsers(val, 0, levelFilter, batchFilter);
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

  const lockReason = (item) => {
    if (isSelfUser(item)) return SELF_LOCK_REASON;
    if (LOCKED_TARGET_ROLES.includes(item.role)) return ROLE_LOCK_REASON;
    return null;
  };

  const toggleSelect = (item) => {
    setSelected((prev) => {
      const next = { ...prev };
      if (next[item.id]) delete next[item.id];
      else next[item.id] = item;
      return next;
    });
  };

  const selectablePageUsers = users.filter((u) => !lockReason(u));
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

  const hasActiveFilter = Boolean(keyword.trim() || levelFilter || batchFilter);

  // Chọn mọi người FM được đổi cấp theo bộ lọc (kể cả trang khác). Vượt giới hạn -> giữ nguyên lựa chọn, báo lỗi backend.
  const handleSelectAllFiltered = async () => {
    setSelectingAll(true);
    try {
      const ids = await getAccessLevelFilterIds(
        keyword,
        levelFilter ? Number(levelFilter) : undefined,
        batchFilter || undefined
      );
      const list = Array.isArray(ids) ? ids : [];
      const pageById = Object.fromEntries(users.map((u) => [u.id, u]));
      setSelected((prev) => {
        const next = { ...prev };
        list.forEach((id) => {
          next[id] = next[id] || pageById[id] || { id };
        });
        return next;
      });
      const skipped = totalElements - list.length;
      toast.success(
        skipped > 0
          ? `Đã chọn ${list.length} người theo bộ lọc (bỏ qua ${skipped} tài khoản không đổi cấp được)`
          : `Đã chọn ${list.length} người theo bộ lọc`
      );
    } catch (err) {
      console.error('Lỗi chọn tất cả kết quả lọc:', err);
      toast.error(err?.message || 'Không thể chọn tất cả kết quả lọc');
    } finally {
      setSelectingAll(false);
    }
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
    if (!reasonLimits.isValid(trimmedReason)) {
      setBulkModal((prev) => ({
        ...prev,
        reasonError: reasonLimits.error(trimmedReason),
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
      handleSearchUsers(keyword, page, levelFilter, batchFilter);
    } catch (err) {
      console.error('Lỗi đổi cấp nhiều người:', err);
      toast.error(err?.message || 'Không thể đổi cấp truy cập');
      setBulkModal((prev) => ({ ...prev, isSaving: false }));
    }
  };

  const handleConfirmSaveUserLevel = async () => {
    const { user, newLevel, reason } = confirmUserModal;
    const trimmedReason = reason?.trim() || '';
    if (!reasonLimits.isValid(trimmedReason)) {
      setConfirmUserModal((prev) => ({
        ...prev,
        reasonError: reasonLimits.error(trimmedReason),
      }));
      userReasonRef.current?.focus();
      return;
    }

    setConfirmUserModal((prev) => ({ ...prev, isSaving: true, submitError: '' }));
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
      // BR-AL-30 (409 ERR_AC_011): người dùng đang có đơn chờ duyệt -> giữ câu lỗi trong modal để FM đọc đủ danh sách khu
      if (err?.status === 409) {
        setConfirmUserModal((prev) => ({ ...prev, isSaving: false, submitError: err.message }));
        return;
      }
      toast.error(err?.message || 'Không thể cập nhật cấp độ truy cập');
      setConfirmUserModal((prev) => ({ ...prev, isSaving: false }));
    }
  };

  return (
    <div className="access-level-page">
      <PageHeader
        title="Cấp truy cập người dùng"
        description="Tra cứu và điều chỉnh cấp độ truy cập (Cấp 1, Cấp 2, Cấp 3) cho người dùng trong khuôn viên."
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
                  handleSearchUsers('', 0, levelFilter, batchFilter);
                }}
                title="Xóa từ khóa"
              >
                <X size={14} />
              </button>
            )}
          </div>
          <Select
            className="ui-select-group--filter"
            value={levelFilter}
            onChange={(e) => {
              const val = e.target.value;
              setLevelFilter(val);
              handleSearchUsers(keyword, 0, val, batchFilter);
            }}
            aria-label="Lọc theo cấp hiện tại"
            options={[
              { value: '', label: 'Mọi cấp hiện tại' },
              ...ACCESS_LEVELS.map((opt) => ({ value: opt.level, label: `Đang ở cấp ${opt.level}` })),
            ]}
          />
          <Select
            className="ui-select-group--filter access-level-batch-filter"
            value={batchFilter}
            onChange={(e) => {
              const val = e.target.value;
              setBatchFilter(val);
              handleSearchUsers(keyword, 0, levelFilter, val);
            }}
            aria-label="Lọc theo lô import"
            options={[
              { value: '', label: 'Mọi lô import' },
              ...batchOptions.map((b) => ({ value: b.importBatchId, label: batchOptionLabel(b) })),
            ]}
          />
          {isFM && hasActiveFilter && totalElements > 0 && (
            <Button
              variant="outline"
              size="sm"
              icon={
                selectingAll ? <Loader2 size={16} className="animate-spin" /> : <ListChecks size={16} />
              }
              onClick={handleSelectAllFiltered}
              disabled={selectingAll || loadingUsers}
            >
              Chọn tất cả kết quả lọc ({totalElements} người)
            </Button>
          )}
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
              onRetry={() => handleSearchUsers(keyword, page, levelFilter, batchFilter)}
            />
          ) : users.length === 0 ? (
            <EmptyState
              icon={Users}
              title="Không tìm thấy người dùng phù hợp"
              description={
                keyword || levelFilter || batchFilter
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
                    const isSelf = isSelfUser(item);
                    const locked = lockReason(item);

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
                              disabled={Boolean(locked)}
                              aria-label={`Chọn ${item.userCode}`}
                              title={locked || undefined}
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
                            <Select
                              value={selectedLevel}
                              onChange={(e) => handleLevelChange(item.id, e.target.value)}
                              disabled={Boolean(locked) || (!isFM && !isAdmin)}
                              title={locked || undefined}
                              options={ACCESS_LEVELS.map((opt) => ({ value: opt.level, label: opt.name }))}
                            />
                          </div>
                        </td>
                        <td style={{ textAlign: 'center' }}>
                          {locked ? (
                            <span className="access-level-self-hint" title={locked}>
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
                  onPageChange={(newPage) => handleSearchUsers(keyword, newPage, levelFilter, batchFilter)}
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
                  <Select
                    id="bulk-new-level"
                    label="Cấp mới"
                    value={bulkModal.newLevel}
                    onChange={(e) => setBulkModal((prev) => ({ ...prev, newLevel: Number(e.target.value) }))}
                    disabled={bulkModal.isSaving}
                    options={ACCESS_LEVELS.map((opt) => ({ value: opt.level, label: opt.name }))}
                  />
                </div>

                <div className="modal-field">
                  <span className="change-summary-label">Những người sẽ bị đổi cấp:</span>
                  <ul className="access-level-bulk-list">
                    {selectedList.filter((u) => u.userCode).map((u) => (
                      <li key={u.id}>
                        {u.fullName} ({u.userCode}) — đang ở cấp {u.accessLevel ?? 1}
                      </li>
                    ))}
                    {selectedList.some((u) => !u.userCode) && (
                      <li>
                        … và {selectedList.filter((u) => !u.userCode).length} người khác theo kết quả lọc
                      </li>
                    )}
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
                          prev.reasonError && reasonLimits.isValid(val)
                            ? ''
                            : prev.reasonError,
                      }));
                    }}
                    placeholder={`Nhập lý do điều chỉnh cấp độ truy cập (từ ${reasonLimits.min} đến ${reasonLimits.max} ký tự)...`}
                    rows={3}
                    error={bulkModal.reasonError}
                    disabled={bulkModal.isSaving}
                  />
                </div>

                <div className="modal-actions">
                  <Button variant="outline" onClick={closeBulkModal} disabled={bulkModal.isSaving}>
                    Huỷ bỏ
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
                      prev.reasonError && reasonLimits.isValid(val)
                        ? ''
                        : prev.reasonError,
                  }));
                }}
                placeholder={`Nhập lý do cụ thể điều chỉnh cấp độ truy cập (từ ${reasonLimits.min} đến ${reasonLimits.max} ký tự)...`}
                rows={3}
                error={confirmUserModal.reasonError}
                disabled={confirmUserModal.isSaving}
              />
              {confirmUserModal.submitError && (
                <div className="access-level-submit-error" role="alert">
                  <AlertTriangle size={16} />
                  <span>{confirmUserModal.submitError}</span>
                </div>
              )}
            </div>

            <div className="modal-actions">
              <Button
                variant="outline"
                onClick={() =>
                  setConfirmUserModal((prev) => ({ ...prev, isOpen: false }))
                }
                disabled={confirmUserModal.isSaving}
              >
                Huỷ bỏ
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
