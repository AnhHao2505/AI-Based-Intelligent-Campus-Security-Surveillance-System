import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Users,
  UserPlus,
  Search,
  X,
  Edit2,
  Trash2,
  UserX,
  UserCheck,
  ChevronLeft,
  ChevronRight,
  ChevronDown,
  AlertCircle,
  CheckCircle2,
  Eye,
  EyeOff,
  RotateCw,
  Upload,
  FileSpreadsheet,
  Download,
  AlertTriangle,
  FileUp
} from 'lucide-react';
import {
  getUsers,
  getUserCounts,
  importUsers,
  downloadUserTemplate,
  registerUser,
  updateUser,
  toggleUserActive,
  deleteUser
} from '../../services/userService';
import { ROLES, ROLE_LABELS } from '../../constants/roles';
import { useAuth } from '../../context/AuthContext';
import '../../styles/ManageAccountPage.css';

const DEFAULT_PAGE_SIZE = 10;

// System account roles (NORMAL_USER excluded)
const SYSTEM_ROLES_LIST = [
  ROLES.ADMIN,
  ROLES.FACILITY_MANAGER,
  ROLES.INTERNAL_GUARD,
  ROLES.OUTSOURCED_GUARD
];

export default function ManageAccountPage() {
  const { user: currentUser } = useAuth();

  // Tab State: 'NORMAL' (default) | 'SYSTEM'
  const [activeTab, setActiveTab] = useState('NORMAL');

  // Count Summary for Tab Badges
  const [counts, setCounts] = useState({ normalCount: 0, systemCount: 0 });

  // List & Pagination State
  const [users, setUsers] = useState([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [currentPage, setCurrentPage] = useState(0);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [debouncedKeyword, setDebouncedKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState(''); // '' (all), 'true' (active), 'false' (inactive)
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Modals State: 'create' | 'edit' | 'delete' | 'disable' | 'activate' | 'import' | null
  const [modalType, setModalType] = useState(null);
  const [selectedUser, setSelectedUser] = useState(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formErrors, setFormErrors] = useState({});

  // Create Form State (Only for System Accounts)
  const [createForm, setCreateForm] = useState({
    fullName: '',
    userCode: '',
    email: '',
    role: ROLES.INTERNAL_GUARD,
    password: '',
    confirmPassword: ''
  });
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);

  // Edit Form State
  const [editForm, setEditForm] = useState({
    fullName: '',
    role: ''
  });

  // CSV Import State
  const [importFile, setImportFile] = useState(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [importResult, setImportResult] = useState(null);
  const [isDownloadingTemplate, setIsDownloadingTemplate] = useState(false);
  const fileInputRef = useRef(null);

  // Toast State
  const [toast, setToast] = useState(null);
  const toastTimeoutRef = useRef(null);

  const showToast = useCallback((message, type = 'success') => {
    if (toastTimeoutRef.current) clearTimeout(toastTimeoutRef.current);
    setToast({ message, type });
    toastTimeoutRef.current = setTimeout(() => {
      setToast(null);
    }, 4000);
  }, []);

  // Fetch Counts
  const fetchCounts = useCallback(async () => {
    try {
      const data = await getUserCounts();
      if (data) {
        setCounts({
          normalCount: data.normalCount ?? 0,
          systemCount: data.systemCount ?? 0
        });
      }
    } catch (err) {
      console.warn('Failed to load user counts:', err);
    }
  }, []);

  // Debounce search input (400ms)
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedKeyword(searchKeyword.trim());
      setCurrentPage(0);
    }, 400);
    return () => clearTimeout(timer);
  }, [searchKeyword]);

  // Fetch Users
  const fetchUsers = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {
        keyword: debouncedKeyword,
        accountType: activeTab,
        page: currentPage,
        size: DEFAULT_PAGE_SIZE,
        sort: 'createdAt,desc'
      };
      if (activeTab === 'NORMAL' && statusFilter !== '') {
        params.isActive = statusFilter;
      }

      const data = await getUsers(params);
      setUsers(data?.content || []);
      setTotalElements(data?.totalElements || 0);
      setTotalPages(data?.totalPages || 1);
    } catch (err) {
      console.error('Error loading users:', err);
      setError(err.message || 'Không thể tải danh sách tài khoản');
    } finally {
      setLoading(false);
    }
  }, [debouncedKeyword, activeTab, currentPage, statusFilter]);

  // Initial load & when tab/page/filter changes
  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  useEffect(() => {
    fetchCounts();
  }, [fetchCounts]);

  // Tab switching handler
  const handleTabChange = (tab) => {
    if (tab === activeTab) return;
    setActiveTab(tab);
    setCurrentPage(0);
    setStatusFilter('');
    setSearchKeyword('');
    setDebouncedKeyword('');
  };

  // Modal Closer with keyboard ESC
  const closeModal = useCallback(() => {
    if (isSubmitting) return;
    setModalType(null);
    setSelectedUser(null);
    setFormErrors({});
    setImportFile(null);
    setImportResult(null);
  }, [isSubmitting]);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') closeModal();
    };
    if (modalType) {
      window.addEventListener('keydown', handleKeyDown);
    }
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [modalType, closeModal]);

  // Open Create Modal (Only for System Accounts)
  const handleOpenCreate = () => {
    setCreateForm({
      fullName: '',
      userCode: '',
      email: '',
      role: ROLES.INTERNAL_GUARD,
      password: '',
      confirmPassword: ''
    });
    setShowPassword(false);
    setShowConfirmPassword(false);
    setFormErrors({});
    setModalType('create');
  };

  // Open Edit Modal
  const handleOpenEdit = (user) => {
    setSelectedUser(user);
    setEditForm({
      fullName: user.fullName || '',
      role: user.role || ROLES.NORMAL_USER
    });
    setFormErrors({});
    setModalType('edit');
  };

  // Open Toggle Modal (Disable or Activate)
  const handleOpenToggle = (user) => {
    setSelectedUser(user);
    setModalType(user.isActive ? 'disable' : 'activate');
  };

  // Open Delete Modal
  const handleOpenDelete = (user) => {
    setSelectedUser(user);
    setModalType('delete');
  };

  // Open Import Modal
  const handleOpenImport = () => {
    setImportFile(null);
    setImportResult(null);
    setFormErrors({});
    setModalType('import');
  };

  // Handle Download CSV Template
  const handleDownloadTemplate = async () => {
    setIsDownloadingTemplate(true);
    try {
      await downloadUserTemplate();
    } catch (err) {
      showToast(err.message || 'Không thể tải file mẫu', 'error');
    } finally {
      setIsDownloadingTemplate(false);
    }
  };

  // Handle Submit: Create System Account
  const handleSubmitCreate = async (e) => {
    e.preventDefault();
    const errors = {};

    if (!createForm.fullName.trim()) {
      errors.fullName = 'Họ và tên là bắt buộc';
    } else if (createForm.fullName.trim().length > 100) {
      errors.fullName = 'Họ và tên không quá 100 ký tự';
    }

    if (!createForm.userCode.trim()) {
      errors.userCode = 'Mã định danh là bắt buộc';
    } else if (createForm.userCode.trim().length > 50) {
      errors.userCode = 'Mã định danh không quá 50 ký tự';
    }

    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!createForm.email.trim()) {
      errors.email = 'Email là bắt buộc';
    } else if (!emailRegex.test(createForm.email.trim())) {
      errors.email = 'Định dạng email không hợp lệ';
    }

    if (!createForm.password) {
      errors.password = 'Mật khẩu là bắt buộc';
    } else if (createForm.password.length < 6) {
      errors.password = 'Mật khẩu phải có ít nhất 6 ký tự';
    }

    if (!createForm.confirmPassword) {
      errors.confirmPassword = 'Vui lòng xác nhận mật khẩu';
    } else if (createForm.password !== createForm.confirmPassword) {
      errors.confirmPassword = 'Mật khẩu xác nhận không khớp';
    }

    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      return;
    }

    setIsSubmitting(true);
    setFormErrors({});

    try {
      await registerUser({
        fullName: createForm.fullName.trim(),
        userCode: createForm.userCode.trim(),
        email: createForm.email.trim(),
        role: createForm.role,
        password: createForm.password
      });
      showToast('Tạo tài khoản hệ thống mới thành công!', 'success');
      closeModal();
      fetchUsers();
      fetchCounts();
    } catch (err) {
      console.error('Error creating user:', err);
      const msg = err.message || 'Không thể tạo tài khoản';
      if (msg.toLowerCase().includes('email')) {
        setFormErrors({ email: msg });
      } else if (msg.toLowerCase().includes('user code') || msg.toLowerCase().includes('mã')) {
        setFormErrors({ userCode: msg });
      } else {
        setFormErrors({ general: msg });
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  // Handle Submit: Edit User
  const handleSubmitEdit = async (e) => {
    e.preventDefault();
    const errors = {};

    if (!editForm.fullName.trim()) {
      errors.fullName = 'Họ và tên là bắt buộc';
    } else if (editForm.fullName.trim().length > 100) {
      errors.fullName = 'Họ và tên không quá 100 ký tự';
    }

    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      return;
    }

    setIsSubmitting(true);
    setFormErrors({});

    try {
      // For Normal Users, preserve existing role (cannot change to system role)
      const targetRole = selectedUser.role === ROLES.NORMAL_USER
        ? ROLES.NORMAL_USER
        : editForm.role;

      await updateUser(selectedUser.id, {
        fullName: editForm.fullName.trim(),
        role: targetRole
      });
      showToast('Cập nhật thông tin tài khoản thành công!', 'success');
      closeModal();
      fetchUsers();
      fetchCounts();
    } catch (err) {
      console.error('Error updating user:', err);
      setFormErrors({ general: err.message || 'Không thể cập nhật thông tin tài khoản' });
    } finally {
      setIsSubmitting(false);
    }
  };

  // Handle Confirm: Toggle Active Status
  const handleConfirmToggle = async () => {
    if (!selectedUser) return;
    setIsSubmitting(true);
    try {
      await toggleUserActive(selectedUser.id);
      const newStatus = !selectedUser.isActive;
      showToast(
        newStatus
          ? `Đã kích hoạt tài khoản ${selectedUser.fullName}`
          : `Đã vô hiệu hóa tài khoản ${selectedUser.fullName}`,
        'success'
      );
      closeModal();
      fetchUsers();
      fetchCounts();
    } catch (err) {
      console.error('Error toggling user status:', err);
      showToast(err.message || 'Không thể thay đổi trạng thái tài khoản', 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Handle Confirm: Delete User (System Accounts only)
  const handleConfirmDelete = async () => {
    if (!selectedUser || selectedUser.role === ROLES.NORMAL_USER) return;
    setIsSubmitting(true);
    try {
      await deleteUser(selectedUser.id);
      showToast(`Đã xóa tài khoản ${selectedUser.fullName}`, 'success');
      closeModal();
      fetchUsers();
      fetchCounts();
    } catch (err) {
      console.error('Error deleting user:', err);
      showToast(err.message || 'Không thể xóa tài khoản', 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Handle File Drop / Select for CSV Import
  const handleFileSelect = (e) => {
    const file = e.target.files?.[0];
    if (file) {
      if (!file.name.toLowerCase().endsWith('.csv')) {
        showToast('Vui lòng chọn file có định dạng .csv', 'error');
        return;
      }
      setImportFile(file);
      setImportResult(null);
      setFormErrors({});
    }
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setIsDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) {
      if (!file.name.toLowerCase().endsWith('.csv')) {
        showToast('Vui lòng chọn file có định dạng .csv', 'error');
        return;
      }
      setImportFile(file);
      setImportResult(null);
      setFormErrors({});
    }
  };

  // Handle Submit: CSV Import
  const handleSubmitImport = async (e) => {
    e.preventDefault();
    if (!importFile) {
      showToast('Vui lòng chọn file CSV để import.', 'error');
      return;
    }

    setIsSubmitting(true);
    setFormErrors({});
    try {
      const res = await importUsers(importFile);
      setImportResult(res);
      showToast(
        `Import hoàn tất: Thành công ${res.successCount}/${res.totalProcessed} người dùng!`,
        res.failedCount > 0 ? 'error' : 'success'
      );
      fetchUsers();
      fetchCounts();
    } catch (err) {
      console.error('Error importing users:', err);
      setFormErrors({ general: err.message || 'Lỗi khi import danh sách người dùng' });
    } finally {
      setIsSubmitting(false);
    }
  };

  // Pagination bounds
  const startItem = totalElements === 0 ? 0 : currentPage * DEFAULT_PAGE_SIZE + 1;
  const endItem = Math.min((currentPage + 1) * DEFAULT_PAGE_SIZE, totalElements);

  return (
    <div className="account-page">
      {/* Toast Notification */}
      {toast && (
        <div className="account-toast-container">
          <div className={`account-toast account-toast--${toast.type}`}>
            <div className="account-toast__icon">
              {toast.type === 'success' ? <CheckCircle2 size={20} /> : <AlertCircle size={20} />}
            </div>
            <span className="account-toast__message">{toast.message}</span>
            <button
              type="button"
              className="account-toast__close"
              onClick={() => setToast(null)}
              aria-label="Đóng thông báo"
            >
              <X size={16} />
            </button>
          </div>
        </div>
      )}

      {/* Page Header */}
      <header className="account-header">
        <div className="account-header__info">
          <h1 className="account-header__title">Quản lý tài khoản</h1>
          <p className="account-header__subtitle">
            {activeTab === 'NORMAL'
              ? `${totalElements.toLocaleString('vi-VN')} người dùng thường trong hệ thống`
              : `${totalElements.toLocaleString('vi-VN')} tài khoản hệ thống trong hệ thống`}
          </p>
        </div>

        {activeTab === 'NORMAL' ? (
          <button
            type="button"
            id="btn-import-accounts"
            className="account-header__create-btn"
            onClick={handleOpenImport}
          >
            <Upload size={18} />
            <span>Import danh sách</span>
          </button>
        ) : (
          <button
            type="button"
            id="btn-create-account"
            className="account-header__create-btn"
            onClick={handleOpenCreate}
          >
            <UserPlus size={18} />
            <span>+ Thêm tài khoản</span>
          </button>
        )}
      </header>

      {/* Tab Navigation (Người dùng thường / Tài khoản hệ thống) */}
      <nav className="account-tabs" aria-label="Phân nhóm tài khoản">
        <button
          type="button"
          className={`account-tab-btn ${activeTab === 'NORMAL' ? 'account-tab-btn--active' : ''}`}
          onClick={() => handleTabChange('NORMAL')}
        >
          <span>Người dùng thường</span>
          <span className="account-tab-badge">
            {counts.normalCount.toLocaleString('vi-VN')}
          </span>
        </button>

        <button
          type="button"
          className={`account-tab-btn ${activeTab === 'SYSTEM' ? 'account-tab-btn--active' : ''}`}
          onClick={() => handleTabChange('SYSTEM')}
        >
          <span>Tài khoản hệ thống</span>
          <span className="account-tab-badge">
            {counts.systemCount.toLocaleString('vi-VN')}
          </span>
        </button>
      </nav>

      {/* Search & Toolbar */}
      <div className="account-toolbar">
        <div className="account-search-wrapper">
          <Search size={18} className="account-search__icon" />
          <input
            type="text"
            id="input-search-account"
            className="account-search__input"
            placeholder="Tìm theo mã định danh, họ tên hoặc email..."
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
          />
          {searchKeyword && (
            <button
              type="button"
              className="account-search__clear-btn"
              onClick={() => setSearchKeyword('')}
              title="Xóa tìm kiếm"
            >
              <X size={16} />
            </button>
          )}
        </div>

        {/* Status filter only for NORMAL user tab */}
        {activeTab === 'NORMAL' && (
          <div className="account-filter-select-wrapper">
            <select
              className="account-filter-select"
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value);
                setCurrentPage(0);
              }}
              aria-label="Lọc trạng thái người dùng thường"
            >
              <option value="">Trạng thái: Tất cả</option>
              <option value="true">Đang hoạt động</option>
              <option value="false">Đã vô hiệu hoá</option>
            </select>
            <ChevronDown size={16} className="account-filter-select-icon" />
          </div>
        )}
      </div>

      {/* Table Card */}
      <div className="account-table-card">
        <div className="account-table-container">
          <table className="account-table">
            <thead>
              <tr>
                {activeTab === 'NORMAL' ? (
                  <>
                    <th>Mã định danh</th>
                    <th>Họ và tên</th>
                    <th>Email</th>
                    <th>Trạng thái</th>
                    <th style={{ textAlign: 'center' }}>Thao tác</th>
                  </>
                ) : (
                  <>
                    <th>Mã định danh</th>
                    <th>Họ và tên</th>
                    <th>Email</th>
                    <th>Quyền</th>
                    <th>Trạng thái</th>
                    <th style={{ textAlign: 'center' }}>Thao tác</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                // Skeleton Rows
                Array.from({ length: 6 }).map((_, idx) => (
                  <tr key={idx} className="account-skeleton-row">
                    <td><div className="account-skeleton-block" style={{ width: '80px' }} /></td>
                    <td><div className="account-skeleton-block" style={{ width: '160px' }} /></td>
                    <td><div className="account-skeleton-block" style={{ width: '190px' }} /></td>
                    {activeTab === 'SYSTEM' && (
                      <td>
                        <div
                          className="account-skeleton-block"
                          style={{ width: '100px', borderRadius: '9999px' }}
                        />
                      </td>
                    )}
                    <td>
                      <div
                        className="account-skeleton-block"
                        style={{ width: '90px', borderRadius: '9999px' }}
                      />
                    </td>
                    <td style={{ textAlign: 'center' }}>
                      <div className="account-skeleton-block" style={{ width: '80px', margin: '0 auto' }} />
                    </td>
                  </tr>
                ))
              ) : error ? (
                // Error State
                <tr>
                  <td colSpan={activeTab === 'NORMAL' ? 5 : 6}>
                    <div className="account-empty-state">
                      <div className="account-empty-state__icon">
                        <AlertCircle size={28} />
                      </div>
                      <h3 className="account-empty-state__title">Không thể tải danh sách tài khoản.</h3>
                      <p className="account-empty-state__desc">{error}</p>
                      <button
                        type="button"
                        className="account-empty-state__btn"
                        onClick={fetchUsers}
                      >
                        <RotateCw size={14} />
                        <span>Thử lại</span>
                      </button>
                    </div>
                  </td>
                </tr>
              ) : users.length === 0 ? (
                // Empty State
                <tr>
                  <td colSpan={activeTab === 'NORMAL' ? 5 : 6}>
                    <div className="account-empty-state">
                      <div className="account-empty-state__icon">
                        <Users size={28} />
                      </div>
                      <h3 className="account-empty-state__title">
                        {debouncedKeyword || statusFilter !== ''
                          ? 'Không tìm thấy tài khoản phù hợp'
                          : activeTab === 'NORMAL'
                          ? 'Chưa có người dùng thường'
                          : 'Chưa có tài khoản hệ thống'}
                      </h3>
                      <p className="account-empty-state__desc">
                        {debouncedKeyword || statusFilter !== ''
                          ? 'Không có kết quả nào khớp với bộ lọc hiện tại. Thử kiểm tra lại từ khóa hoặc xóa bộ lọc.'
                          : activeTab === 'NORMAL'
                          ? 'Import danh sách để thêm người dùng thường vào hệ thống.'
                          : 'Hệ thống hiện chưa có tài khoản nào. Hãy nhấn nút bên dưới để tạo tài khoản đầu tiên.'}
                      </p>
                      {debouncedKeyword || statusFilter !== '' ? (
                        <button
                          type="button"
                          className="account-empty-state__btn"
                          onClick={() => {
                            setSearchKeyword('');
                            setStatusFilter('');
                          }}
                        >
                          <X size={14} />
                          <span>Xóa bộ lọc</span>
                        </button>
                      ) : activeTab === 'NORMAL' ? (
                        <button
                          type="button"
                          className="account-empty-state__btn"
                          onClick={handleOpenImport}
                        >
                          <Upload size={14} />
                          <span>Import danh sách</span>
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="account-empty-state__btn"
                          onClick={handleOpenCreate}
                        >
                          <UserPlus size={14} />
                          <span>+ Thêm tài khoản</span>
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ) : (
                // Data Rows
                users.map((item) => {
                  const isSelf = currentUser?.email && item.email?.toLowerCase() === currentUser.email?.toLowerCase();

                  return (
                    <tr key={item.id}>
                      <td className="account-cell--code" title={item.userCode}>
                        {item.userCode}
                      </td>

                      <td className="account-cell--name" title={item.fullName}>
                        {item.fullName}
                      </td>

                      <td className="account-cell--email" title={item.email}>
                        {item.email}
                      </td>

                      {/* SYSTEM tab role column */}
                      {activeTab === 'SYSTEM' && (
                        <td>
                          <span className={`account-badge account-badge--role-${item.role}`}>
                            {ROLE_LABELS[item.role] || item.role}
                          </span>
                        </td>
                      )}

                      <td>
                        <span
                          className={`account-badge ${
                            item.isActive ? 'account-badge--active' : 'account-badge--inactive'
                          }`}
                        >
                          <span className="account-badge__dot" />
                          <span>{item.isActive ? 'Đang hoạt động' : 'Đã vô hiệu hoá'}</span>
                        </span>
                      </td>

                      <td style={{ textAlign: 'center' }}>
                        <div className="account-actions" style={{ justifyContent: 'center' }}>
                          {/* Tạm ẩn chức năng chỉnh sửa */}
                          {/*
                          <button
                            type="button"
                            className="account-action-btn account-action-btn--edit"
                            title="Chỉnh sửa"
                            onClick={() => handleOpenEdit(item)}
                          >
                            <Edit2 size={15} />
                          </button>
                          */}

                          {/* Action toggle: text link for NORMAL tab, icon for SYSTEM tab */}
                          {activeTab === 'NORMAL' ? (
                            <button
                              type="button"
                              className={`account-action-link ${
                                item.isActive ? 'account-action-link--disable' : 'account-action-link--activate'
                              }`}
                              disabled={isSelf}
                              onClick={() => handleOpenToggle(item)}
                            >
                              {item.isActive ? 'Vô hiệu hoá' : 'Kích hoạt'}
                            </button>
                          ) : (
                            <>
                              <button
                                type="button"
                                className="account-action-btn account-action-btn--delete"
                                title={isSelf ? 'Không thể tự xóa tài khoản của chính mình' : 'Xóa tài khoản'}
                                disabled={isSelf}
                                onClick={() => handleOpenDelete(item)}
                              >
                                <Trash2 size={15} />
                              </button>

                              <button
                                type="button"
                                className={`account-action-btn ${
                                  item.isActive
                                    ? 'account-action-btn--toggle-disable'
                                    : 'account-action-btn--toggle-activate'
                                }`}
                                title={
                                  isSelf
                                    ? 'Không thể tự vô hiệu hóa tài khoản của chính mình'
                                    : item.isActive
                                    ? 'Vô hiệu hóa tài khoản'
                                    : 'Kích hoạt tài khoản'
                                }
                                disabled={isSelf}
                                onClick={() => handleOpenToggle(item)}
                              >
                                {item.isActive ? <UserX size={15} /> : <UserCheck size={15} />}
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination Footer */}
        {!loading && totalElements > 0 && (
          <div className="account-pagination">
            <span className="account-pagination__info">
              Hiển thị {startItem}–{endItem} trên tổng số {totalElements.toLocaleString('vi-VN')}{' '}
              {activeTab === 'NORMAL' ? 'người dùng' : 'tài khoản'}
            </span>
            <div className="account-pagination__buttons">
              <button
                type="button"
                className="account-page-btn"
                disabled={currentPage === 0}
                onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                title="Trang trước"
              >
                <ChevronLeft size={16} />
              </button>

              {Array.from({ length: totalPages }).map((_, i) => {
                if (
                  totalPages > 7 &&
                  i !== 0 &&
                  i !== totalPages - 1 &&
                  Math.abs(i - currentPage) > 1
                ) {
                  if (i === 1 || i === totalPages - 2) {
                    return (
                      <span key={i} style={{ padding: '0 4px', color: 'var(--theme-text-muted)' }}>
                        ...
                      </span>
                    );
                  }
                  return null;
                }

                return (
                  <button
                    key={i}
                    type="button"
                    className={`account-page-btn ${currentPage === i ? 'account-page-btn--active' : ''}`}
                    onClick={() => setCurrentPage(i)}
                  >
                    {i + 1}
                  </button>
                );
              })}

              <button
                type="button"
                className="account-page-btn"
                disabled={currentPage >= totalPages - 1}
                onClick={() => setCurrentPage((p) => Math.min(totalPages - 1, p + 1))}
                title="Trang tiếp theo"
              >
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* ====================================================================
          MODALS
          ==================================================================== */}

      {/* 1. Create Modal (Only for System Accounts) */}
      {modalType === 'create' && (
        <div
          className="account-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !isSubmitting) closeModal();
          }}
        >
          <div className="account-modal" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--primary">
                  <UserPlus size={18} />
                </div>
                <h2 className="account-modal__title">Thêm tài khoản hệ thống</h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                <X size={18} />
              </button>
            </div>

            <form onSubmit={handleSubmitCreate}>
              <div className="account-modal__body">
                {formErrors.general && (
                  <div
                    style={{
                      padding: '10px 14px',
                      borderRadius: '6px',
                      backgroundColor: '#fef2f2',
                      color: '#dc2626',
                      fontSize: '0.8125rem',
                      border: '1px solid #fecaca'
                    }}
                  >
                    {formErrors.general}
                  </div>
                )}

                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Họ và tên<span className="account-form-label__required">*</span></span>
                  </label>
                  <input
                    type="text"
                    className={`account-form-input ${formErrors.fullName ? 'account-form-input--error' : ''}`}
                    placeholder="Ví dụ: Nguyễn Văn An"
                    value={createForm.fullName}
                    onChange={(e) => setCreateForm({ ...createForm, fullName: e.target.value })}
                    disabled={isSubmitting}
                    autoFocus
                  />
                  {formErrors.fullName && (
                    <span className="account-form-error">{formErrors.fullName}</span>
                  )}
                </div>

                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Mã định danh<span className="account-form-label__required">*</span></span>
                  </label>
                  <input
                    type="text"
                    className={`account-form-input ${formErrors.userCode ? 'account-form-input--error' : ''}`}
                    placeholder="Ví dụ: AD-002, SEC-002..."
                    value={createForm.userCode}
                    onChange={(e) => setCreateForm({ ...createForm, userCode: e.target.value })}
                    disabled={isSubmitting}
                  />
                  {formErrors.userCode && (
                    <span className="account-form-error">{formErrors.userCode}</span>
                  )}
                </div>

                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Email<span className="account-form-label__required">*</span></span>
                  </label>
                  <input
                    type="email"
                    className={`account-form-input ${formErrors.email ? 'account-form-input--error' : ''}`}
                    placeholder="Ví dụ: admin2@fpt.edu.vn"
                    value={createForm.email}
                    onChange={(e) => setCreateForm({ ...createForm, email: e.target.value })}
                    disabled={isSubmitting}
                  />
                  {formErrors.email && (
                    <span className="account-form-error">{formErrors.email}</span>
                  )}
                </div>

                {/* Role dropdown only contains SYSTEM roles, NEVER NORMAL_USER */}
                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Quyền hạn<span className="account-form-label__required">*</span></span>
                  </label>
                  <select
                    className="account-form-select"
                    value={createForm.role}
                    onChange={(e) => setCreateForm({ ...createForm, role: e.target.value })}
                    disabled={isSubmitting}
                  >
                    {SYSTEM_ROLES_LIST.map((r) => (
                      <option key={r} value={r}>
                        {ROLE_LABELS[r] || r}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Mật khẩu<span className="account-form-label__required">*</span></span>
                  </label>
                  <div className="account-form-password-wrapper">
                    <input
                      type={showPassword ? 'text' : 'password'}
                      className={`account-form-input ${formErrors.password ? 'account-form-input--error' : ''}`}
                      placeholder="Ít nhất 6 ký tự"
                      value={createForm.password}
                      onChange={(e) => setCreateForm({ ...createForm, password: e.target.value })}
                      disabled={isSubmitting}
                    />
                    <button
                      type="button"
                      className="account-form-password-toggle"
                      onClick={() => setShowPassword(!showPassword)}
                      tabIndex="-1"
                    >
                      {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                    </button>
                  </div>
                  {formErrors.password && (
                    <span className="account-form-error">{formErrors.password}</span>
                  )}
                </div>

                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Xác nhận mật khẩu<span className="account-form-label__required">*</span></span>
                  </label>
                  <div className="account-form-password-wrapper">
                    <input
                      type={showConfirmPassword ? 'text' : 'password'}
                      className={`account-form-input ${formErrors.confirmPassword ? 'account-form-input--error' : ''}`}
                      placeholder="Nhập lại mật khẩu"
                      value={createForm.confirmPassword}
                      onChange={(e) => setCreateForm({ ...createForm, confirmPassword: e.target.value })}
                      disabled={isSubmitting}
                    />
                    <button
                      type="button"
                      className="account-form-password-toggle"
                      onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                      tabIndex="-1"
                    >
                      {showConfirmPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                    </button>
                  </div>
                  {formErrors.confirmPassword && (
                    <span className="account-form-error">{formErrors.confirmPassword}</span>
                  )}
                </div>
              </div>

              <div className="account-modal__footer">
                <button
                  type="button"
                  className="account-modal-btn account-modal-btn--secondary"
                  onClick={closeModal}
                  disabled={isSubmitting}
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  className="account-modal-btn account-modal-btn--primary"
                  disabled={isSubmitting}
                >
                  {isSubmitting && <RotateCw size={14} className="spin" />}
                  <span>{isSubmitting ? 'Đang tạo...' : 'Tạo tài khoản'}</span>
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* 2. Edit Modal */}
      {modalType === 'edit' && selectedUser && (
        <div
          className="account-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !isSubmitting) closeModal();
          }}
        >
          <div className="account-modal" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--primary">
                  <Edit2 size={18} />
                </div>
                <h2 className="account-modal__title">Chỉnh sửa tài khoản</h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                <X size={18} />
              </button>
            </div>

            <form onSubmit={handleSubmitEdit}>
              <div className="account-modal__body">
                {formErrors.general && (
                  <div
                    style={{
                      padding: '10px 14px',
                      borderRadius: '6px',
                      backgroundColor: '#fef2f2',
                      color: '#dc2626',
                      fontSize: '0.8125rem',
                      border: '1px solid #fecaca'
                    }}
                  >
                    {formErrors.general}
                  </div>
                )}

                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Mã định danh</span>
                  </label>
                  <input
                    type="text"
                    className="account-form-input"
                    value={selectedUser.userCode}
                    disabled
                  />
                  <span className="account-form-note">Mã định danh không thể thay đổi</span>
                </div>

                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Email</span>
                  </label>
                  <input
                    type="email"
                    className="account-form-input"
                    value={selectedUser.email}
                    disabled
                  />
                  <span className="account-form-note">Email không thể thay đổi</span>
                </div>

                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Họ và tên<span className="account-form-label__required">*</span></span>
                  </label>
                  <input
                    type="text"
                    className={`account-form-input ${formErrors.fullName ? 'account-form-input--error' : ''}`}
                    value={editForm.fullName}
                    onChange={(e) => setEditForm({ ...editForm, fullName: e.target.value })}
                    disabled={isSubmitting}
                    autoFocus
                  />
                  {formErrors.fullName && (
                    <span className="account-form-error">{formErrors.fullName}</span>
                  )}
                </div>

                {/* Role field: For Normal Users, show read-only. For System accounts, allow choosing among system roles */}
                {selectedUser.role === ROLES.NORMAL_USER ? (
                  <div className="account-form-group">
                    <label className="account-form-label">
                      <span>Quyền hạn</span>
                    </label>
                    <input
                      type="text"
                      className="account-form-input"
                      value={ROLE_LABELS[ROLES.NORMAL_USER]}
                      disabled
                    />
                    <span className="account-form-note">
                      Tài khoản người dùng thường không thể chuyển thành tài khoản hệ thống tại đây
                    </span>
                  </div>
                ) : (
                  <div className="account-form-group">
                    <label className="account-form-label">
                      <span>Quyền hạn<span className="account-form-label__required">*</span></span>
                    </label>
                    <select
                      className="account-form-select"
                      value={editForm.role}
                      onChange={(e) => setEditForm({ ...editForm, role: e.target.value })}
                      disabled={isSubmitting}
                    >
                      {SYSTEM_ROLES_LIST.map((r) => (
                        <option key={r} value={r}>
                          {ROLE_LABELS[r] || r}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
              </div>

              <div className="account-modal__footer">
                <button
                  type="button"
                  className="account-modal-btn account-modal-btn--secondary"
                  onClick={closeModal}
                  disabled={isSubmitting}
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  className="account-modal-btn account-modal-btn--primary"
                  disabled={isSubmitting}
                >
                  {isSubmitting && <RotateCw size={14} className="spin" />}
                  <span>{isSubmitting ? 'Đang lưu...' : 'Lưu thay đổi'}</span>
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* 3. Disable Confirmation Modal */}
      {modalType === 'disable' && selectedUser && (
        <div className="account-modal-backdrop">
          <div className="account-modal account-modal--confirm" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--warning">
                  <UserX size={18} />
                </div>
                <h2 className="account-modal__title">
                  {selectedUser.role === ROLES.NORMAL_USER ? 'Vô hiệu hoá người dùng' : 'Vô hiệu hóa tài khoản'}
                </h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                <X size={18} />
              </button>
            </div>

            <div className="account-modal__body">
              <p className="account-confirm-text">
                {selectedUser.role === ROLES.NORMAL_USER
                  ? 'Người dùng này sẽ không thể tiếp tục sử dụng hệ thống cho đến khi được kích hoạt lại.'
                  : 'Bạn có chắc chắn muốn vô hiệu hóa tài khoản này? Người dùng sẽ không thể tiếp tục đăng nhập vào hệ thống cho đến khi được kích hoạt lại.'}
              </p>
              <div className="account-confirm-user-info">
                <span className="account-confirm-user-name">{selectedUser.fullName}</span>
                <span className="account-confirm-user-code">
                  Mã: {selectedUser.userCode} • Email: {selectedUser.email}
                </span>
              </div>
            </div>

            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--secondary"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                Huỷ
              </button>
              <button
                type="button"
                className="account-modal-btn account-modal-btn--warning"
                onClick={handleConfirmToggle}
                disabled={isSubmitting}
              >
                {isSubmitting && <RotateCw size={14} className="spin" />}
                <span>{isSubmitting ? 'Đang xử lý...' : 'Vô hiệu hoá'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 4. Activate Confirmation Modal */}
      {modalType === 'activate' && selectedUser && (
        <div className="account-modal-backdrop">
          <div className="account-modal account-modal--confirm" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--success">
                  <UserCheck size={18} />
                </div>
                <h2 className="account-modal__title">
                  {selectedUser.role === ROLES.NORMAL_USER ? 'Kích hoạt người dùng' : 'Kích hoạt tài khoản'}
                </h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                <X size={18} />
              </button>
            </div>

            <div className="account-modal__body">
              <p className="account-confirm-text">
                {selectedUser.role === ROLES.NORMAL_USER
                  ? 'Người dùng sẽ có thể sử dụng hệ thống trở lại.'
                  : 'Bạn có chắc chắn muốn kích hoạt lại tài khoản này? Người dùng sẽ có thể đăng nhập và sử dụng hệ thống bình thường.'}
              </p>
              <div className="account-confirm-user-info">
                <span className="account-confirm-user-name">{selectedUser.fullName}</span>
                <span className="account-confirm-user-code">
                  Mã: {selectedUser.userCode} • Email: {selectedUser.email}
                </span>
              </div>
            </div>

            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--secondary"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                Huỷ
              </button>
              <button
                type="button"
                className="account-modal-btn account-modal-btn--primary"
                onClick={handleConfirmToggle}
                disabled={isSubmitting}
              >
                {isSubmitting && <RotateCw size={14} className="spin" />}
                <span>{isSubmitting ? 'Đang xử lý...' : 'Kích hoạt'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 5. Delete Confirmation Modal */}
      {modalType === 'delete' && selectedUser && (
        <div className="account-modal-backdrop">
          <div className="account-modal account-modal--confirm" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--danger">
                  <Trash2 size={18} />
                </div>
                <h2 className="account-modal__title">Xóa tài khoản</h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                <X size={18} />
              </button>
            </div>

            <div className="account-modal__body">
              <p className="account-confirm-text">
                Bạn có chắc chắn muốn xóa tài khoản này? Tài khoản sẽ bị vô hiệu hóa và loại bỏ khỏi danh sách quản lý.
              </p>
              <div className="account-confirm-user-info">
                <span className="account-confirm-user-name">{selectedUser.fullName}</span>
                <span className="account-confirm-user-code">
                  Mã: {selectedUser.userCode} • Email: {selectedUser.email}
                </span>
              </div>
            </div>

            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--secondary"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                Hủy
              </button>
              <button
                type="button"
                className="account-modal-btn account-modal-btn--danger"
                onClick={handleConfirmDelete}
                disabled={isSubmitting}
              >
                {isSubmitting && <RotateCw size={14} className="spin" />}
                <span>{isSubmitting ? 'Đang xóa...' : 'Xóa tài khoản'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 6. Import Modal (Normal Users CSV) */}
      {modalType === 'import' && (
        <div
          className="account-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !isSubmitting) closeModal();
          }}
        >
          <div className="account-modal account-modal--import" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--primary">
                  <Upload size={18} />
                </div>
                <div>
                  <h2 className="account-modal__title">Import danh sách người dùng</h2>
                </div>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={closeModal}
                disabled={isSubmitting}
              >
                <X size={18} />
              </button>
            </div>

            <form onSubmit={handleSubmitImport}>
              <div className="account-modal__body">
                <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--theme-text-muted)' }}>
                  Thêm nhiều người dùng thường vào hệ thống bằng file CSV. Các tài khoản hợp lệ sẽ được tự động tạo với quyền Người dùng thường.
                </p>

                {formErrors.general && (
                  <div
                    style={{
                      padding: '10px 14px',
                      borderRadius: '6px',
                      backgroundColor: '#fef2f2',
                      color: '#dc2626',
                      fontSize: '0.8125rem',
                      border: '1px solid #fecaca'
                    }}
                  >
                    {formErrors.general}
                  </div>
                )}

                {/* Dropzone or Selected File */}
                {!importFile ? (
                  <div
                    className={`account-dropzone ${isDragOver ? 'account-dropzone--active' : ''}`}
                    onDragOver={(e) => {
                      e.preventDefault();
                      setIsDragOver(true);
                    }}
                    onDragLeave={() => setIsDragOver(false)}
                    onDrop={handleDrop}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <FileUp size={36} className="account-dropzone__icon" />
                    <p className="account-dropzone__title">
                      Kéo thả file CSV vào đây hoặc <span style={{ color: 'var(--theme-primary)' }}>chọn file từ máy</span>
                    </p>
                    <p className="account-dropzone__subtitle">Hỗ trợ định dạng file .csv (UTF-8)</p>
                    <input
                      ref={fileInputRef}
                      type="file"
                      accept=".csv"
                      style={{ display: 'none' }}
                      onChange={handleFileSelect}
                    />
                  </div>
                ) : (
                  <div className="account-file-card">
                    <div className="account-file-card__details">
                      <FileSpreadsheet size={28} color="var(--theme-primary)" />
                      <div>
                        <div className="account-file-card__name">{importFile.name}</div>
                        <div className="account-file-card__size">
                          {(importFile.size / 1024).toFixed(1)} KB
                        </div>
                      </div>
                    </div>
                    {!isSubmitting && (
                      <button
                        type="button"
                        className="account-file-card__remove-btn"
                        onClick={() => {
                          setImportFile(null);
                          setImportResult(null);
                        }}
                        title="Hủy chọn file"
                      >
                        <X size={16} />
                      </button>
                    )}
                  </div>
                )}

                {/* Instructions & Template Download */}
                <div className="account-import-info-box">
                  <div>
                    <strong>Các cột bắt buộc:</strong> Mã định danh, Họ và tên, Email, Mật khẩu (tùy chọn, mặc định 123456).
                  </div>
                  <div>
                    Tải về file mẫu để đảm bảo định dạng file CSV chuẩn xác:
                  </div>
                  <button
                    type="button"
                    className="account-import-template-btn"
                    onClick={handleDownloadTemplate}
                    disabled={isDownloadingTemplate}
                  >
                    <Download size={14} />
                    <span>{isDownloadingTemplate ? 'Đang tải mẫu...' : 'Tải file mẫu (sample_users.csv)'}</span>
                  </button>
                </div>

                {/* Import Results Summary (if any) */}
                {importResult && (
                  <div>
                    <h4 style={{ margin: '8px 0', fontSize: '0.875rem' }}>Kết quả import:</h4>
                    <div className="account-import-summary">
                      <div className="account-import-stat account-import-stat--total">
                        <div className="account-import-stat__num">{importResult.totalProcessed}</div>
                        <div className="account-import-stat__label">Tổng số</div>
                      </div>
                      <div className="account-import-stat account-import-stat--success">
                        <div className="account-import-stat__num">{importResult.successCount}</div>
                        <div className="account-import-stat__label">Thành công</div>
                      </div>
                      <div className="account-import-stat account-import-stat--failed">
                        <div className="account-import-stat__num">{importResult.failedCount}</div>
                        <div className="account-import-stat__label">Thất bại</div>
                      </div>
                    </div>

                    {/* Error Table if failed rows exist */}
                    {importResult.errors && importResult.errors.length > 0 && (
                      <div style={{ marginTop: '12px' }}>
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: '6px',
                            color: '#dc2626',
                            fontSize: '0.8125rem',
                            fontWeight: 600,
                            marginBottom: '6px'
                          }}
                        >
                          <AlertTriangle size={15} />
                          <span>Chi tiết lỗi ({importResult.errors.length} dòng):</span>
                        </div>
                        <div className="account-import-error-table-container">
                          <table className="account-import-error-table">
                            <thead>
                              <tr>
                                <th style={{ width: '50px' }}>Dòng</th>
                                <th style={{ width: '110px' }}>Mã định danh</th>
                                <th>Lỗi</th>
                              </tr>
                            </thead>
                            <tbody>
                              {importResult.errors.map((err, idx) => (
                                <tr key={idx}>
                                  <td>{err.row}</td>
                                  <td><code>{err.userCode || '-'}</code></td>
                                  <td>{err.message}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>

              <div className="account-modal__footer">
                <button
                  type="button"
                  className="account-modal-btn account-modal-btn--secondary"
                  onClick={closeModal}
                  disabled={isSubmitting}
                >
                  {importResult ? 'Đóng' : 'Hủy'}
                </button>
                {!importResult && (
                  <button
                    type="submit"
                    className="account-modal-btn account-modal-btn--primary"
                    disabled={isSubmitting || !importFile}
                  >
                    {isSubmitting && <RotateCw size={14} className="spin" />}
                    <span>{isSubmitting ? 'Đang import danh sách...' : 'Bắt đầu Import'}</span>
                  </button>
                )}
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
