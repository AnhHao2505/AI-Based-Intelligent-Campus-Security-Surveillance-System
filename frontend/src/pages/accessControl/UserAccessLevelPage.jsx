import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Search,
  Loader2,
  Info,
  X,
  Users,
  ShieldCheck,
  Sliders,
  History,
  AlertTriangle,
  RotateCcw,
  Edit3,
  Calendar,
  Filter,
} from 'lucide-react';
import { toast } from 'sonner';
import { useAuth } from '../../context/AuthContext';
import { searchUsers, updateUserAccessLevel } from '../../services/userService';
import { getAreas } from '../../services/areaService';
import {
  getLevelPresets,
  updateLevelPreset,
  getAuditLogs,
} from '../../services/accessControlService';
import { ROLES, ROLE_LABELS } from '../../constants/roles';
import { getLevelConfig, formatDisplayDateTime } from '../../utils/areaHelpers';
import Button from '../../components/ui/Button';
import Modal from '../../components/ui/Modal';
import UserSearchCombobox from '../../components/user/UserSearchCombobox';
import './UserAccessLevelPage.css';

const ACCESS_LEVELS = [
  { level: 1, name: 'Cấp 1 — Mọi người dùng' },
  { level: 2, name: 'Cấp 2 — Nhân viên' },
  { level: 3, name: 'Cấp 3 — Cấp cao' },
];

const TARGET_TYPE_OPTIONS = [
  { value: '', label: 'Tất cả loại thao tác' },
  { value: 'USER_ACCESS_LEVEL', label: 'Cấp truy cập người dùng' },
  { value: 'AREA_ACCESS_RULES', label: 'Quy tắc truy cập khu vực' },
  { value: 'AREA_ASSIGNMENT', label: 'Phân công nhân sự khu vực' },
  { value: 'LEVEL_PRESET', label: 'Mặc định theo loại khu vực' },
];

export default function UserAccessLevelPage() {
  const { user: currentUser } = useAuth();
  const isAdmin = currentUser?.role === ROLES.ADMIN;
  const isFM = currentUser?.role === ROLES.FACILITY_MANAGER;

  const [activeTab, setActiveTab] = useState('user_levels');

  // ==========================================
  // TAB 1: USER ACCESS LEVELS STATE
  // ==========================================
  const [keyword, setKeyword] = useState('');
  const [users, setUsers] = useState([]);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [selectedLevels, setSelectedLevels] = useState({});
  const debounceRef = useRef(null);

  // User Level Confirmation Modal
  const [confirmUserModal, setConfirmUserModal] = useState({
    isOpen: false,
    user: null,
    newLevel: 1,
    reason: '',
    isSaving: false,
  });

  // ==========================================
  // TAB 2: AREA PRESETS STATE
  // ==========================================
  const [presets, setPresets] = useState([]);
  const [loadingPresets, setLoadingPresets] = useState(false);
  const [editPresetModal, setEditPresetModal] = useState({
    isOpen: false,
    preset: null,
    accessLevel: 1,
    explicitAuthorizationRequired: false,
    reason: '',
    isSaving: false,
  });

  // ==========================================
  // TAB 3: AUDIT LOGS STATE
  // ==========================================
  const [logs, setLogs] = useState([]);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [currentPage, setCurrentPage] = useState(0);
  const pageSize = 15;

  // Filter State
  const [filterTargetType, setFilterTargetType] = useState('');
  const [filterAreaId, setFilterAreaId] = useState('');
  const [filterFrom, setFilterFrom] = useState('');
  const [filterTo, setFilterTo] = useState('');
  const [filterSubjectUser, setFilterSubjectUser] = useState(null);
  const [filterChangedByUser, setFilterChangedByUser] = useState(null);

  // Areas list for filter dropdown
  const [areasList, setAreasList] = useState([]);

  // ==========================================
  // TAB 1 HANDLERS
  // ==========================================
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
      isSaving: false,
    });
  };

  const handleConfirmSaveUserLevel = async () => {
    const { user, newLevel, reason } = confirmUserModal;
    if (!reason || !reason.trim()) {
      toast.error('Vui lòng nhập lý do điều chỉnh cấp độ truy cập');
      return;
    }
    if (reason.length > 500) {
      toast.error('Lý do không được vượt quá 500 ký tự');
      return;
    }

    setConfirmUserModal((prev) => ({ ...prev, isSaving: true }));
    try {
      const updated = await updateUserAccessLevel(user.id, newLevel, reason.trim());
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
        isSaving: false,
      });
    } catch (err) {
      console.error('Lỗi cập nhật cấp độ truy cập:', err);
      toast.error(err?.message || 'Không thể cập nhật cấp độ truy cập');
      setConfirmUserModal((prev) => ({ ...prev, isSaving: false }));
    }
  };

  // ==========================================
  // TAB 2 HANDLERS
  // ==========================================
  const loadPresets = useCallback(async () => {
    setLoadingPresets(true);
    try {
      const data = await getLevelPresets();
      setPresets(data || []);
    } catch (err) {
      console.error('Lỗi tải danh sách cấu hình mặc định:', err);
      toast.error(err?.message || 'Không thể tải cấu hình mặc định');
    } finally {
      setLoadingPresets(false);
    }
  }, []);

  const openEditPresetModal = (preset) => {
    setEditPresetModal({
      isOpen: true,
      preset,
      accessLevel: preset.areaAccessLevel ?? preset.accessLevel ?? 1,
      explicitAuthorizationRequired: Boolean(preset.explicitAuthorizationRequired),
      reason: '',
      isSaving: false,
    });
  };

  const handleSavePreset = async () => {
    const { preset, accessLevel, explicitAuthorizationRequired, reason } = editPresetModal;
    if (!reason || !reason.trim()) {
      toast.error('Vui lòng nhập lý do thay đổi cấu hình mặc định');
      return;
    }
    if (reason.length > 500) {
      toast.error('Lý do không được vượt quá 500 ký tự');
      return;
    }

    const areaLevelKey = preset.areaLevel || preset.areaType;
    const cfg = getLevelConfig(areaLevelKey);

    setEditPresetModal((prev) => ({ ...prev, isSaving: true }));
    try {
      await updateLevelPreset(areaLevelKey, {
        areaAccessLevel: accessLevel,
        explicitAuthorizationRequired,
        reason: reason.trim(),
        version: preset.version,
      });
      toast.success(`Đã cập nhật cấu hình mặc định cho loại ${cfg.name}`);
      setEditPresetModal({
        isOpen: false,
        preset: null,
        accessLevel: 1,
        explicitAuthorizationRequired: false,
        reason: '',
        isSaving: false,
      });
      loadPresets();
    } catch (err) {
      console.error('Lỗi lưu cấu hình preset:', err);
      if (err?.status === 409 || err?.code === 'ERR_AC_003') {
        toast.error('Dữ liệu cấu hình đã bị thay đổi bởi người khác. Vui lòng thử lại.');
        loadPresets();
      } else {
        toast.error(err?.message || 'Không thể cập nhật cấu hình mặc định');
      }
      setEditPresetModal((prev) => ({ ...prev, isSaving: false }));
    }
  };

  // ==========================================
  // TAB 3 HANDLERS
  // ==========================================
  const loadAuditLogs = useCallback(
    async (page = 0) => {
      setLoadingLogs(true);
      try {
        const params = {
          page,
          size: pageSize,
        };
        if (filterTargetType) params.targetType = filterTargetType;
        if (filterAreaId) params.areaId = filterAreaId;
        if (filterSubjectUser?.id) params.subjectUserId = filterSubjectUser.id;
        if (filterChangedByUser?.id) params.changedBy = filterChangedByUser.id;
        if (filterFrom) params.from = `${filterFrom}T00:00:00Z`;
        if (filterTo) params.to = `${filterTo}T23:59:59Z`;

        const res = await getAuditLogs(params);
        setLogs(res?.content || []);
        setTotalPages(res?.totalPages || 0);
        setTotalElements(res?.totalElements || 0);
        setCurrentPage(page);
      } catch (err) {
        console.error('Lỗi tải nhật ký thay đổi:', err);
        toast.error(err?.message || 'Không thể tải nhật ký phân quyền');
        setLogs([]);
      } finally {
        setLoadingLogs(false);
      }
    },
    [
      filterTargetType,
      filterAreaId,
      filterSubjectUser,
      filterChangedByUser,
      filterFrom,
      filterTo,
      pageSize,
    ]
  );

  const handleResetFilters = () => {
    setFilterTargetType('');
    setFilterAreaId('');
    setFilterFrom('');
    setFilterTo('');
    setFilterSubjectUser(null);
    setFilterChangedByUser(null);
  };

  // Load Areas for dropdown filter (Giới hạn tối đa 100 khu vực theo API /api/areas; chưa có endpoint danh sách rút gọn cho toàn bộ phân loại)
  useEffect(() => {
    const fetchAreas = async () => {
      try {
        const res = await getAreas({ size: 100 });
        setAreasList(res?.content || []);
      } catch (err) {
        console.error('Lỗi tải danh sách khu vực:', err);
      }
    };
    fetchAreas();
  }, []);

  // Fetch presets or audit logs on tab switch
  useEffect(() => {
    if (activeTab === 'area_presets') {
      loadPresets();
    } else if (activeTab === 'audit_logs') {
      loadAuditLogs(0);
    }
  }, [activeTab, loadPresets, loadAuditLogs]);

  // Helpers for formatting audit rows
  const renderAuditTargetType = (targetType, action) => {
    let label = targetType;
    let badgeClass = 'audit-type--default';

    if (targetType === 'USER_ACCESS_LEVEL') {
      label = 'Cấp người dùng';
      badgeClass = 'audit-type--user';
    } else if (targetType === 'AREA_ACCESS_RULES') {
      label = 'Quy tắc khu vực';
      badgeClass = 'audit-type--area';
    } else if (targetType === 'AREA_ASSIGNMENT') {
      label = 'Nhân sự chỉ định';
      badgeClass = 'audit-type--personnel';
    } else if (targetType === 'LEVEL_PRESET') {
      label = 'Mặc định loại';
      badgeClass = 'audit-type--preset';
    }

    let actionLabel = action;
    if (action === 'UPDATE') actionLabel = 'Cập nhật';
    else if (action === 'ASSIGN') actionLabel = 'Gán mới';
    else if (action === 'UPDATE_VALIDITY') actionLabel = 'Gia hạn';
    else if (action === 'REVOKE') actionLabel = 'Thu hồi';

    return (
      <div className="audit-target-col">
        <span className={`audit-badge ${badgeClass}`}>{label}</span>
        <span className="audit-action-label">{actionLabel}</span>
      </div>
    );
  };

  const renderAuditChange = (log) => {
    const { targetType, action, oldValue, newValue } = log;
    if (targetType === 'USER_ACCESS_LEVEL') {
      return (
        <div className="audit-detail-pill">
          <span>Cấp {oldValue?.accessLevel ?? '—'}</span>
          <span className="audit-arrow">→</span>
          <strong className="audit-val--new">Cấp {newValue?.accessLevel ?? '—'}</strong>
        </div>
      );
    }
    if (targetType === 'AREA_ACCESS_RULES' || targetType === 'LEVEL_PRESET') {
      const oldLvl = oldValue?.areaAccessLevel ?? oldValue?.accessLevel ?? '—';
      const newLvl = newValue?.areaAccessLevel ?? newValue?.accessLevel ?? '—';
      const oldReq = oldValue?.explicitAuthorizationRequired;
      const newReq = newValue?.explicitAuthorizationRequired;

      return (
        <div className="audit-detail-rules">
          <div className="audit-detail-row">
            <span className="audit-row-label">Cấp độ:</span>
            <span>Cấp {oldLvl}</span>
            <span className="audit-arrow">→</span>
            <strong className="audit-val--new">Cấp {newLvl}</strong>
          </div>
          <div className="audit-detail-row">
            <span className="audit-row-label">Đích danh:</span>
            <span>{oldReq ? 'Có' : 'Không'}</span>
            <span className="audit-arrow">→</span>
            <strong className="audit-val--new">{newReq ? 'Có' : 'Không'}</strong>
          </div>
        </div>
      );
    }
    if (targetType === 'AREA_ASSIGNMENT') {
      if (action === 'ASSIGN') {
        return (
          <div className="audit-detail-row">
            <span>Hiệu lực đến:</span>
            <strong className="audit-val--new">
              {newValue?.validTo ? formatDisplayDateTime(newValue.validTo) : 'Vô thời hạn'}
            </strong>
          </div>
        );
      }
      if (action === 'REVOKE') {
        return <span className="audit-badge audit-badge--danger">Thu hồi quyền</span>;
      }
      if (action === 'UPDATE_VALIDITY') {
        return (
          <div className="audit-detail-rules">
            <div className="audit-detail-row">
              <span className="audit-row-label">Hạn cũ:</span>
              <span>{oldValue?.validTo ? formatDisplayDateTime(oldValue.validTo) : 'Vô thời hạn'}</span>
            </div>
            <div className="audit-detail-row">
              <span className="audit-row-label">Hạn mới:</span>
              <strong className="audit-val--new">
                {newValue?.validTo ? formatDisplayDateTime(newValue.validTo) : 'Vô thời hạn'}
              </strong>
            </div>
          </div>
        );
      }
    }
    return '—';
  };

  return (
    <div className="access-level-page">
      {/* Header */}
      <div className="access-level-page__header">
        <div>
          <h1 className="access-level-page__title">Phân quyền truy cập</h1>
          <p className="access-level-page__subtitle">
            Quản lý cấp độ truy cập người dùng, thiết lập mặc định theo loại khu vực và tra cứu nhật ký thay đổi
          </p>
        </div>
      </div>

      {/* Tabs navigation */}
      <div className="access-level-tabs">
        <button
          type="button"
          className={`access-level-tab ${activeTab === 'user_levels' ? 'access-level-tab--active' : ''}`}
          onClick={() => setActiveTab('user_levels')}
        >
          <Users size={16} />
          <span>Cấp người dùng</span>
        </button>
        <button
          type="button"
          className={`access-level-tab ${activeTab === 'area_presets' ? 'access-level-tab--active' : ''}`}
          onClick={() => setActiveTab('area_presets')}
        >
          <Sliders size={16} />
          <span>Mặc định theo loại khu vực</span>
        </button>
        <button
          type="button"
          className={`access-level-tab ${activeTab === 'audit_logs' ? 'access-level-tab--active' : ''}`}
          onClick={() => setActiveTab('audit_logs')}
        >
          <History size={16} />
          <span>Nhật ký thay đổi</span>
        </button>
      </div>

      {/* ========================================================================= */}
      {/* TAB 1: CẤP NGƯỜI DÙNG */}
      {/* ========================================================================= */}
      {activeTab === 'user_levels' && (
        <div className="tab-pane">
          {/* Callout Banner */}
          <div className="access-level-callout">
            <div className="access-level-callout__icon">
              <Info size={20} />
            </div>
            <div className="access-level-callout__content">
              <div className="access-level-callout__title">Quy tắc phân cấp độ truy cập:</div>
              <div className="access-level-callout__text">
                Người dùng có <strong>Cấp độ truy cập (User Access Level)</strong> lớn hơn hoặc bằng{' '}
                <strong>Cấp độ khu vực (Area Access Level)</strong> sẽ được <strong>vào tự do</strong> tại các khu vực không bật cờ <em>"Chỉ định đích danh"</em>.
              </div>
              <div className="access-level-callout__tiers">
                <span className="tier-tag tier-tag--1">
                  <strong>Cấp 1:</strong> Mọi người dùng (Mặc định: Sinh viên, Giảng viên, Khách)
                </span>
                <span className="tier-tag tier-tag--2">
                  <strong>Cấp 2:</strong> Nhân viên (Mặc định: Bảo vệ, Quản lý cơ sở)
                </span>
                <span className="tier-tag tier-tag--3">
                  <strong>Cấp 3:</strong> Cấp cao (Khu vực đặc thù nhạy cảm, phòng máy chủ)
                </span>
              </div>
            </div>
          </div>

          {/* Search Toolbar */}
          <div className="access-level-toolbar">
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
                            <span className={`access-level-pill level--${currentLevel}`}>
                              Level {currentLevel}
                            </span>
                          </td>
                          <td>
                            <div className="access-level-select-wrap">
                              <select
                                className="access-level-select"
                                value={selectedLevel}
                                onChange={(e) => handleLevelChange(item.id, e.target.value)}
                                disabled={isSelf || isAdmin}
                                title={
                                  isAdmin
                                    ? 'Quản trị viên chỉ có quyền xem'
                                    : isSelf
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
                            {isAdmin ? (
                              <span className="access-level-readonly-hint">Chỉ xem</span>
                            ) : isSelf ? (
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
      )}

      {/* ========================================================================= */}
      {/* TAB 2: MẶC ĐỊNH THEO LOẠI KHU VỰC */}
      {/* ========================================================================= */}
      {activeTab === 'area_presets' && (
        <div className="tab-pane">
          {/* Preset Banner */}
          <div className="access-level-callout">
            <div className="access-level-callout__icon">
              <Sliders size={20} />
            </div>
            <div className="access-level-callout__content">
              <div className="access-level-callout__title">Quy tắc áp dụng cấu hình mặc định:</div>
              <div className="access-level-callout__text">
                Thiết lập tại bảng này chỉ được áp dụng làm <strong>giá trị mặc định khi tạo mới khu vực</strong>. 
                Các khu vực đã tồn tại trong hệ thống <strong>không bị thay đổi</strong> khi cập nhật cấu hình mặc định này.
              </div>
            </div>
          </div>

          <div className="access-level-table-card">
            {loadingPresets ? (
              <div className="access-level-empty">
                <Loader2 size={28} className="animate-spin" />
                <p>Đang tải cấu hình mặc định theo loại khu vực...</p>
              </div>
            ) : presets.length === 0 ? (
              <div className="access-level-empty">
                <AlertTriangle size={32} />
                <p className="access-level-empty__title">Chưa có cấu hình mặc định nào</p>
              </div>
            ) : (
              <div className="access-level-table-wrapper">
                <table className="access-level-table">
                  <thead>
                    <tr>
                      <th>Loại khu vực</th>
                      <th>Cấp độ truy cập mặc định</th>
                      <th>Yêu cầu chỉ định đích danh</th>
                      <th>Cập nhật lần cuối</th>
                      <th style={{ textAlign: 'center' }}>Thao tác</th>
                    </tr>
                  </thead>
                  <tbody>
                    {presets.map((preset) => {
                      const typeCode = preset.areaLevel || preset.areaType;
                      const cfg = getLevelConfig(typeCode);
                      return (
                        <tr key={preset.id || typeCode}>
                          <td>
                            <div className="preset-area-type">
                              <span className={`level-badge ${cfg.badgeClass}`}>
                                {cfg.name}
                              </span>
                              <span className="preset-area-code">{typeCode}</span>
                            </div>
                          </td>
                          <td>
                            <span className={`access-level-pill level--${preset.areaAccessLevel ?? preset.accessLevel}`}>
                              Level {preset.areaAccessLevel ?? preset.accessLevel}
                            </span>
                          </td>
                          <td>
                            {preset.explicitAuthorizationRequired ? (
                              <span className="preset-explicit-badge preset-explicit-badge--yes">
                                Bắt buộc
                              </span>
                            ) : (
                              <span className="preset-explicit-badge preset-explicit-badge--no">
                                Không bắt buộc
                              </span>
                            )}
                          </td>
                          <td>
                            <div className="preset-updated-meta">
                              <span className="preset-updated-time">
                                {formatDisplayDateTime(preset.updatedAt)}
                              </span>
                              {preset.updatedByName && (
                                <span className="preset-updated-author">
                                  bởi {preset.updatedByName} ({preset.updatedByUserCode || '—'})
                                </span>
                              )}
                            </div>
                          </td>
                          <td style={{ textAlign: 'center' }}>
                            {isAdmin ? (
                              <span className="access-level-readonly-hint">Chỉ xem</span>
                            ) : (
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => openEditPresetModal(preset)}
                              >
                                <Edit3 size={14} />
                                <span>Chỉnh sửa</span>
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
      )}

      {/* ========================================================================= */}
      {/* TAB 3: NHẬT KÝ THAY ĐỔI */}
      {/* ========================================================================= */}
      {activeTab === 'audit_logs' && (
        <div className="tab-pane">
          {/* Audit Filters Toolbar */}
          <div className="audit-filters-card">
            <div className="audit-filters-header">
              <Filter size={16} />
              <span>Bộ lọc nhật ký</span>
            </div>
            <div className="audit-filters-grid">
              {/* Target Type Filter */}
              <div className="audit-filter-item">
                <label className="audit-filter-label">Loại thao tác</label>
                <select
                  className="audit-filter-select"
                  value={filterTargetType}
                  onChange={(e) => setFilterTargetType(e.target.value)}
                >
                  {TARGET_TYPE_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>
                      {opt.label}
                    </option>
                  ))}
                </select>
              </div>

              {/* Area Filter */}
              <div className="audit-filter-item">
                <label className="audit-filter-label">Khu vực</label>
                <select
                  className="audit-filter-select"
                  value={filterAreaId}
                  onChange={(e) => setFilterAreaId(e.target.value)}
                >
                  <option value="">Tất cả khu vực</option>
                  {areasList.map((a) => {
                    const floorPart = a.floor ? (String(a.floor).startsWith('Tầng') ? a.floor : `Tầng ${a.floor}`) : null;
                    const loc = [a.building, floorPart].filter(Boolean).join(' · ');
                    return (
                      <option key={a.id} value={a.id}>
                        {loc ? `${a.name} (${loc})` : a.name}
                      </option>
                    );
                  })}
                </select>
              </div>

              {/* From Date */}
              <div className="audit-filter-item">
                <label className="audit-filter-label">Từ ngày</label>
                <input
                  type="date"
                  className="audit-filter-input"
                  value={filterFrom}
                  onChange={(e) => setFilterFrom(e.target.value)}
                />
              </div>

              {/* To Date */}
              <div className="audit-filter-item">
                <label className="audit-filter-label">Đến ngày</label>
                <input
                  type="date"
                  className="audit-filter-input"
                  value={filterTo}
                  onChange={(e) => setFilterTo(e.target.value)}
                />
              </div>

              {/* Subject User */}
              <div className="audit-filter-item audit-filter-item--wide">
                <label className="audit-filter-label">Người bị tác động</label>
                <UserSearchCombobox
                  selectedUser={filterSubjectUser}
                  onSelect={(u) => setFilterSubjectUser(u)}
                  onClear={() => setFilterSubjectUser(null)}
                  placeholder="Tìm theo tên/mã người bị tác động..."
                />
              </div>

              {/* Changed By User */}
              <div className="audit-filter-item audit-filter-item--wide">
                <label className="audit-filter-label">Người thực hiện</label>
                <UserSearchCombobox
                  selectedUser={filterChangedByUser}
                  onSelect={(u) => setFilterChangedByUser(u)}
                  onClear={() => setFilterChangedByUser(null)}
                  placeholder="Tìm theo tên/mã người thực hiện..."
                />
              </div>
            </div>

            <div className="audit-filters-actions">
              <Button
                variant="outline"
                size="sm"
                onClick={handleResetFilters}
              >
                <RotateCcw size={14} />
                <span>Đặt lại</span>
              </Button>
              <Button
                variant="primary"
                size="sm"
                onClick={() => loadAuditLogs(0)}
              >
                <Search size={14} />
                <span>Áp dụng lọc</span>
              </Button>
            </div>
          </div>

          {/* Audit Logs Table */}
          <div className="access-level-table-card">
            {loadingLogs ? (
              <div className="access-level-empty">
                <Loader2 size={28} className="animate-spin" />
                <p>Đang tải nhật ký thay đổi...</p>
              </div>
            ) : logs.length === 0 ? (
              <div className="access-level-empty">
                <History size={32} />
                <p className="access-level-empty__title">Không tìm thấy bản ghi nhật ký phù hợp</p>
                <span className="access-level-empty__desc">
                  Thử thay đổi bộ lọc tìm kiếm để xem kết quả khác.
                </span>
              </div>
            ) : (
              <>
                <div className="access-level-table-wrapper">
                  <table className="access-level-table">
                    <thead>
                      <tr>
                        <th>Thời gian</th>
                        <th>Thao tác & Đối tượng</th>
                        <th>Khu vực</th>
                        <th>Người bị tác động</th>
                        <th>Người thực hiện</th>
                        <th>Nội dung thay đổi</th>
                        <th>Lý do</th>
                      </tr>
                    </thead>
                    <tbody>
                      {logs.map((log) => (
                        <tr key={log.id}>
                          <td className="audit-cell--time">
                            {formatDisplayDateTime(log.changedAt)}
                          </td>
                          <td>
                            {renderAuditTargetType(log.targetType, log.action)}
                          </td>
                          <td>
                            {log.areaName ? (
                              <div className="audit-area-cell">
                                <span className="audit-area-name">{log.areaName}</span>
                              </div>
                            ) : (
                              <span className="audit-cell--empty">—</span>
                            )}
                          </td>
                          <td>
                            {log.subjectUserName ? (
                              <div className="audit-user-cell">
                                <span className="audit-user-name">{log.subjectUserName}</span>
                                <span className="audit-user-code">{log.subjectUserCode}</span>
                              </div>
                            ) : (
                              <span className="audit-cell--empty">—</span>
                            )}
                          </td>
                          <td>
                            <div className="audit-user-cell">
                              <span className="audit-user-name">{log.changedByName || 'Hệ thống'}</span>
                              {log.changedByUserCode && (
                                <span className="audit-user-code">{log.changedByUserCode}</span>
                              )}
                            </div>
                          </td>
                          <td>
                            {renderAuditChange(log)}
                          </td>
                          <td className="audit-cell--reason">
                            {log.reason ? (
                              <span className="audit-reason-text" title={log.reason}>
                                {log.reason}
                              </span>
                            ) : (
                              <span className="audit-cell--empty">—</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {/* Pagination */}
                {totalPages > 1 && (
                  <div className="audit-pagination">
                    <span className="audit-pagination__info">
                      Trang {currentPage + 1} / {totalPages} ({totalElements} bản ghi)
                    </span>
                    <div className="audit-pagination__buttons">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={currentPage === 0 || loadingLogs}
                        onClick={() => loadAuditLogs(currentPage - 1)}
                      >
                        Trang trước
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={currentPage >= totalPages - 1 || loadingLogs}
                        onClick={() => loadAuditLogs(currentPage + 1)}
                      >
                        Trang sau
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL: XÁC NHẬN CẬP NHẬT CẤP ĐỘ NGƯỜI DÙNG */}
      {/* ========================================================================= */}
      <Modal
        isOpen={confirmUserModal.isOpen}
        onClose={() =>
          !confirmUserModal.isSaving &&
          setConfirmUserModal((prev) => ({ ...prev, isOpen: false }))
        }
        title="Xác nhận điều chỉnh cấp độ truy cập"
        subtitle={`Người dùng: ${confirmUserModal.user?.fullName} (${confirmUserModal.user?.userCode})`}
        size="md"
        footer={
          <div className="modal-actions-wrapper">
            <Button
              variant="outline"
              onClick={() => setConfirmUserModal((prev) => ({ ...prev, isOpen: false }))}
              disabled={confirmUserModal.isSaving}
            >
              Hủy
            </Button>
            <Button
              variant="primary"
              onClick={handleConfirmSaveUserLevel}
              loading={confirmUserModal.isSaving}
            >
              Xác nhận cập nhật
            </Button>
          </div>
        }
      >
        <div className="modal-form-content">
          <div className="change-summary-box">
            <div className="change-summary-item">
              <span className="change-summary-label">Cấp hiện tại:</span>
              <span className={`access-level-pill level--${confirmUserModal.user?.accessLevel ?? 1}`}>
                Level {confirmUserModal.user?.accessLevel ?? 1}
              </span>
            </div>
            <span className="audit-arrow">→</span>
            <div className="change-summary-item">
              <span className="change-summary-label">Cấp mới:</span>
              <span className={`access-level-pill level--${confirmUserModal.newLevel}`}>
                Level {confirmUserModal.newLevel}
              </span>
            </div>
          </div>

          <div className="form-group">
            <label className="form-label">
              Lý do thay đổi <span className="text-danger">*</span>
            </label>
            <textarea
              className="form-textarea"
              rows={3}
              placeholder="Nhập lý do cụ thể điều chỉnh cấp độ truy cập (tối đa 500 ký tự)..."
              value={confirmUserModal.reason}
              maxLength={500}
              onChange={(e) =>
                setConfirmUserModal((prev) => ({ ...prev, reason: e.target.value }))
              }
            />
            <div className="char-count">
              {confirmUserModal.reason.length} / 500 ký tự
            </div>
          </div>
        </div>
      </Modal>

      {/* ========================================================================= */}
      {/* MODAL: CHỈNH SỬA CẤU HÌNH MẶC ĐỊNH PRESET */}
      {/* ========================================================================= */}
      <Modal
        isOpen={editPresetModal.isOpen}
        onClose={() =>
          !editPresetModal.isSaving &&
          setEditPresetModal((prev) => ({ ...prev, isOpen: false }))
        }
        title="Chỉnh sửa cấu hình mặc định"
        subtitle={`Loại khu vực: ${getLevelConfig(editPresetModal.preset?.areaLevel || editPresetModal.preset?.areaType).name}`}
        size="md"
        footer={
          <div className="modal-actions-wrapper">
            <Button
              variant="outline"
              onClick={() => setEditPresetModal((prev) => ({ ...prev, isOpen: false }))}
              disabled={editPresetModal.isSaving}
            >
              Hủy
            </Button>
            <Button
              variant="primary"
              onClick={handleSavePreset}
              loading={editPresetModal.isSaving}
            >
              Lưu cấu hình
            </Button>
          </div>
        }
      >
        <div className="modal-form-content">
          <div className="preset-modal-warning">
            <AlertTriangle size={18} className="preset-warning-icon" />
            <p>
              Cấu hình này chỉ tự động áp dụng cho các khu vực mới được tạo. Các khu vực hiện hữu sẽ không bị ảnh hưởng.
            </p>
          </div>

          <div className="form-group">
            <label className="form-label">
              Cấp độ truy cập mặc định <span className="text-danger">*</span>
            </label>
            <select
              className="form-select"
              value={editPresetModal.accessLevel}
              onChange={(e) =>
                setEditPresetModal((prev) => ({
                  ...prev,
                  accessLevel: Number(e.target.value),
                }))
              }
            >
              <option value={1}>Cấp 1 — Mọi người dùng (Level 1)</option>
              <option value={2}>Cấp 2 — Nhân viên (Level 2)</option>
              <option value={3}>Cấp 3 — Cấp cao (Level 3)</option>
            </select>
          </div>

          <div className="form-group">
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={editPresetModal.explicitAuthorizationRequired}
                onChange={(e) =>
                  setEditPresetModal((prev) => ({
                    ...prev,
                    explicitAuthorizationRequired: e.target.checked,
                  }))
                }
              />
              <span>Yêu cầu chỉ định đích danh (Chỉ người có phân công hoặc đơn duyệt mới được vào)</span>
            </label>
          </div>

          <div className="form-group">
            <label className="form-label">
              Lý do thay đổi cấu hình <span className="text-danger">*</span>
            </label>
            <textarea
              className="form-textarea"
              rows={3}
              placeholder="Nhập lý do điều chỉnh quy tắc mặc định theo loại khu vực (tối đa 500 ký tự)..."
              value={editPresetModal.reason}
              maxLength={500}
              onChange={(e) =>
                setEditPresetModal((prev) => ({ ...prev, reason: e.target.value }))
              }
            />
            <div className="char-count">
              {editPresetModal.reason.length} / 500 ký tự
            </div>
          </div>
        </div>
      </Modal>
    </div>
  );
}
