import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Users,
  UserPlus,
  Search,
  X,
  Trash2,
  UserX,
  UserCheck,
  ChevronLeft,
  ChevronRight,
  ChevronDown,
  AlertCircle,
  CheckCircle2,
  RotateCw,
  Camera,
  Upload
} from 'lucide-react';
import {
  getUsers,
  createStaffAccount,
  downloadUserTemplate,
  toggleUserActive,
  deleteUser
} from '../../services/userService';
import { ROLES, ROLE_LABELS } from '../../constants/roles';
import { useAuth } from '../../context/AuthContext';
import '../../styles/ManageAccountPage.css';

const DEFAULT_PAGE_SIZE = 10;

// System account roles eligible for creation
const SYSTEM_STAFF_ROLES = [
  ROLES.FACILITY_MANAGER,
  ROLES.INTERNAL_GUARD,
  ROLES.OUTSOURCED_GUARD
];

export default function ManageAccountPage() {
  const { user: currentUser } = useAuth();

  // Tab State: 'NORMAL' (default) | 'SYSTEM'
  const [activeTab, setActiveTab] = useState('NORMAL');

  // Count Summary for Tab Badges (Populated automatically by getUsers)
  const [counts, setCounts] = useState({ normalCount: 0, systemCount: 0 });

  // List & Pagination State
  const [users, setUsers] = useState([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [currentPage, setCurrentPage] = useState(0);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [debouncedKeyword, setDebouncedKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Modals State: 'create' | 'delete' | 'disable' | 'activate' | null
  const [modalType, setModalType] = useState(null);
  const [selectedUser, setSelectedUser] = useState(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formErrors, setFormErrors] = useState({});

  // Create Staff Account Form State
  const [createForm, setCreateForm] = useState({
    fullName: '',
    userCode: '',
    email: '',
    role: ROLES.INTERNAL_GUARD,
  });
  const [frontFile, setFrontFile] = useState(null);
  const [frontPreview, setFrontPreview] = useState(null);

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

  // Debounce search input (400ms)
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedKeyword(searchKeyword.trim());
      setCurrentPage(0);
    }, 400);
    return () => clearTimeout(timer);
  }, [searchKeyword]);

  // Fetch Users (Returns both user list page and merged counts)
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
      
      const pageObj = data?.users || data;
      setUsers(pageObj?.content || []);
      setTotalElements(pageObj?.totalElements || 0);
      setTotalPages(pageObj?.totalPages || 1);

      if (data?.normalCount !== undefined && data?.systemCount !== undefined) {
        setCounts({
          normalCount: data.normalCount,
          systemCount: data.systemCount
        });
      }
    } catch (err) {
      console.error('Error loading users:', err);
      setError(err.message || 'Không thể tải danh sách tài khoản');
    } finally {
      setLoading(false);
    }
  }, [debouncedKeyword, activeTab, currentPage, statusFilter]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  // Tab switching handler
  const handleTabChange = (tab) => {
    if (tab === activeTab) return;
    setActiveTab(tab);
    setCurrentPage(0);
    setStatusFilter('');
    setSearchKeyword('');
    setDebouncedKeyword('');
  };

  // Close Modal
  const closeModal = useCallback(() => {
    if (isSubmitting) return;
    setModalType(null);
    setSelectedUser(null);
    setFormErrors({});
    setFrontFile(null);
    if (frontPreview) URL.revokeObjectURL(frontPreview);
    setFrontPreview(null);
  }, [isSubmitting, frontPreview]);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') closeModal();
    };
    if (modalType) {
      window.addEventListener('keydown', handleKeyDown);
    }
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [modalType, closeModal]);

  // Open Create Modal
  const handleOpenCreate = () => {
    setCreateForm({
      fullName: '',
      userCode: '',
      email: '',
      role: ROLES.INTERNAL_GUARD,
    });
    setFrontFile(null);
    setFrontPreview(null);
    setFormErrors({});
    setModalType('create');
  };

  // Handle Image File Input Change
  const handleImageChange = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!file.type.startsWith('image/')) {
      setFormErrors((prev) => ({ ...prev, faceImage: 'Vui lòng chọn file hình ảnh (JPG, PNG...)' }));
      return;
    }

    setFrontFile(file);
    setFrontPreview(URL.createObjectURL(file));
    setFormErrors((prev) => ({ ...prev, faceImage: null }));
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

  // Submit Create Staff Account
  const handleSubmitCreate = async (e) => {
    e.preventDefault();
    const errors = {};

    if (!createForm.fullName.trim()) {
      errors.fullName = 'Họ và tên là bắt buộc';
    }

    if (!createForm.userCode.trim()) {
      errors.userCode = 'Mã cán bộ là bắt buộc';
    }

    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!createForm.email.trim()) {
      errors.email = 'Email là bắt buộc';
    } else if (!emailRegex.test(createForm.email.trim())) {
      errors.email = 'Định dạng email không hợp lệ';
    }

    if (!frontFile) {
      errors.faceImage = 'Vui lòng tải lên ảnh chân dung chính diện để đăng ký khuôn mặt';
    }

    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      return;
    }

    setIsSubmitting(true);
    setFormErrors({});

    try {
      await createStaffAccount({
        fullName: createForm.fullName.trim(),
        userCode: createForm.userCode.trim(),
        email: createForm.email.trim(),
        role: createForm.role,
        faceImage: frontFile
      });
      showToast('Tạo tài khoản cán bộ thành công! Mật khẩu khởi tạo đã được gửi đến email.', 'success');
      closeModal();
      fetchUsers();
    } catch (err) {
      console.error('Error creating staff account:', err);
      const msg = err.message || 'Không thể tạo tài khoản cán bộ';
      if (msg.toLowerCase().includes('email')) {
        setFormErrors({ email: msg });
      } else if (msg.toLowerCase().includes('mã') || msg.toLowerCase().includes('code')) {
        setFormErrors({ userCode: msg });
      } else {
        setFormErrors({ general: msg });
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  // Confirm Toggle Active Status
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
    } catch (err) {
      console.error('Error toggling user status:', err);
      showToast(err.message || 'Không thể thay đổi trạng thái tài khoản', 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  // Confirm Delete User
  const handleConfirmDelete = async () => {
    if (!selectedUser) return;
    setIsSubmitting(true);
    try {
      await deleteUser(selectedUser.id);
      showToast(`Đã xóa tài khoản ${selectedUser.fullName}`, 'success');
      closeModal();
      fetchUsers();
    } catch (err) {
      console.error('Error deleting user:', err);
      showToast(err.message || 'Không thể xóa tài khoản', 'error');
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

        {activeTab === 'SYSTEM' && (
          <button
            type="button"
            id="btn-create-account"
            className="account-header__create-btn"
            onClick={handleOpenCreate}
          >
            <UserPlus size={18} />
            <span>+ Thêm tài khoản cán bộ</span>
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
                <th>Mã định danh</th>
                <th>Họ và tên</th>
                <th>Email</th>
                {activeTab === 'SYSTEM' && <th>Quyền</th>}
                <th>Trạng thái</th>
                <th style={{ textAlign: 'center' }}>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
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
                          ? 'Người dùng thường sẽ được tự động đồng bộ khi tương tác với hệ thống.'
                          : 'Hệ thống hiện chưa có tài khoản cán bộ nào. Hãy nhấn nút bên dưới để tạo tài khoản đầu tiên.'}
                      </p>
                      {activeTab === 'SYSTEM' && !debouncedKeyword && (
                        <button
                          type="button"
                          className="account-empty-state__btn"
                          onClick={handleOpenCreate}
                        >
                          <UserPlus size={14} />
                          <span>+ Thêm tài khoản cán bộ</span>
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ) : (
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
                          <button
                            type="button"
                            className={`account-action-btn ${
                              item.isActive
                                ? 'account-action-btn--toggle-disable'
                                : 'account-action-btn--toggle-activate'
                            }`}
                            title={
                              isSelf
                                ? 'Không thể tự thay đổi trạng thái của chính mình'
                                : item.isActive
                                ? 'Vô hiệu hóa tài khoản'
                                : 'Kích hoạt tài khoản'
                            }
                            disabled={isSelf}
                            onClick={() => handleOpenToggle(item)}
                          >
                            {item.isActive ? <UserX size={15} /> : <UserCheck size={15} />}
                          </button>

                          {activeTab === 'SYSTEM' && (
                            <button
                              type="button"
                              className="account-action-btn account-action-btn--delete"
                              title={isSelf ? 'Không thể tự xóa tài khoản của chính mình' : 'Xóa tài khoản'}
                              disabled={isSelf}
                              onClick={() => handleOpenDelete(item)}
                            >
                              <Trash2 size={15} />
                            </button>
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

              {Array.from({ length: totalPages }).map((_, i) => (
                <button
                  key={i}
                  type="button"
                  className={`account-page-btn ${currentPage === i ? 'account-page-btn--active' : ''}`}
                  onClick={() => setCurrentPage(i)}
                >
                  {i + 1}
                </button>
              ))}

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
          CREATE STAFF ACCOUNT MODAL (MF1.1 Manual Creation with Face Image)
          ==================================================================== */}
      {modalType === 'create' && (
        <div
          className="account-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !isSubmitting) closeModal();
          }}
        >
          <div className="account-modal" role="dialog" aria-modal="true" style={{ maxWidth: '520px' }}>
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--primary">
                  <UserPlus size={18} />
                </div>
                <h2 className="account-modal__title">Thêm tài khoản cán bộ/nhân viên</h2>
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
                    <span>Mã cán bộ/nhân viên<span className="account-form-label__required">*</span></span>
                  </label>
                  <input
                    type="text"
                    className={`account-form-input ${formErrors.userCode ? 'account-form-input--error' : ''}`}
                    placeholder="Ví dụ: NV-SEC-001, FM-002..."
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
                    <span>Email liên hệ<span className="account-form-label__required">*</span></span>
                  </label>
                  <input
                    type="email"
                    className={`account-form-input ${formErrors.email ? 'account-form-input--error' : ''}`}
                    placeholder="Ví dụ: staff@fpt.edu.vn"
                    value={createForm.email}
                    onChange={(e) => setCreateForm({ ...createForm, email: e.target.value })}
                    disabled={isSubmitting}
                  />
                  {formErrors.email && (
                    <span className="account-form-error">{formErrors.email}</span>
                  )}
                </div>

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
                    {SYSTEM_STAFF_ROLES.map((r) => (
                      <option key={r} value={r}>
                        {ROLE_LABELS[r] || r}
                      </option>
                    ))}
                  </select>
                </div>

                {/* Face Image Upload Section */}
                <div className="account-form-group">
                  <label className="account-form-label">
                    <span>Ảnh chân dung chính diện (Đăng ký khuôn mặt)<span className="account-form-label__required">*</span></span>
                  </label>
                  
                  {frontPreview ? (
                    <div style={{ position: 'relative', textAlign: 'center', margin: '8px 0' }}>
                      <img
                        src={frontPreview}
                        alt="Khuôn mặt chính diện"
                        style={{
                          width: '120px',
                          height: '140px',
                          objectFit: 'cover',
                          borderRadius: '8px',
                          border: '2px solid var(--theme-primary, #3b82f6)'
                        }}
                      />
                      <div style={{ marginTop: '6px' }}>
                        <label
                          htmlFor="input-staff-face"
                          style={{
                            cursor: 'pointer',
                            color: 'var(--theme-primary, #3b82f6)',
                            fontSize: '0.8125rem',
                            fontWeight: 500
                          }}
                        >
                          Chọn ảnh khác
                        </label>
                      </div>
                    </div>
                  ) : (
                    <label
                      htmlFor="input-staff-face"
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        justifyContent: 'center',
                        padding: '20px',
                        border: '2px dashed #cbd5e1',
                        borderRadius: '8px',
                        cursor: 'pointer',
                        backgroundColor: '#f8fafc',
                        marginTop: '4px'
                      }}
                    >
                      <Camera size={32} color="#64748b" />
                      <span style={{ fontSize: '0.875rem', marginTop: '8px', color: '#475569' }}>
                        Tải lên ảnh chân dung chính diện
                      </span>
                      <span style={{ fontSize: '0.75rem', color: '#94a3b8', marginTop: '2px' }}>
                        Hỗ trợ định dạng JPG, PNG...
                      </span>
                    </label>
                  )}
                  <input
                    id="input-staff-face"
                    type="file"
                    accept="image/*"
                    onChange={handleImageChange}
                    style={{ display: 'none' }}
                    disabled={isSubmitting}
                  />
                  {formErrors.faceImage && (
                    <span className="account-form-error">{formErrors.faceImage}</span>
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
                  <span>{isSubmitting ? 'Đang khởi tạo tài khoản...' : 'Khởi tạo tài khoản'}</span>
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Disable / Activate / Delete Modals */}
      {modalType === 'disable' && selectedUser && (
        <div className="account-modal-backdrop">
          <div className="account-modal account-modal--confirm" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--warning">
                  <UserX size={18} />
                </div>
                <h2 className="account-modal__title">Vô hiệu hóa tài khoản</h2>
              </div>
              <button type="button" className="account-modal__close-btn" onClick={closeModal} disabled={isSubmitting}>
                <X size={18} />
              </button>
            </div>
            <div className="account-modal__body">
              <p className="account-confirm-text">
                Bạn có chắc chắn muốn vô hiệu hóa tài khoản này? Người dùng sẽ không thể đăng nhập cho đến khi được kích hoạt lại.
              </p>
              <div className="account-confirm-user-info">
                <span className="account-confirm-user-name">{selectedUser.fullName}</span>
                <span className="account-confirm-user-code">Mã: {selectedUser.userCode} • Email: {selectedUser.email}</span>
              </div>
            </div>
            <div className="account-modal__footer">
              <button type="button" className="account-modal-btn account-modal-btn--secondary" onClick={closeModal} disabled={isSubmitting}>Huỷ</button>
              <button type="button" className="account-modal-btn account-modal-btn--warning" onClick={handleConfirmToggle} disabled={isSubmitting}>
                {isSubmitting && <RotateCw size={14} className="spin" />}
                <span>{isSubmitting ? 'Đang xử lý...' : 'Vô hiệu hoá'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {modalType === 'activate' && selectedUser && (
        <div className="account-modal-backdrop">
          <div className="account-modal account-modal--confirm" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--success">
                  <UserCheck size={18} />
                </div>
                <h2 className="account-modal__title">Kích hoạt tài khoản</h2>
              </div>
              <button type="button" className="account-modal__close-btn" onClick={closeModal} disabled={isSubmitting}>
                <X size={18} />
              </button>
            </div>
            <div className="account-modal__body">
              <p className="account-confirm-text">
                Bạn có chắc chắn muốn kích hoạt lại tài khoản này? Người dùng sẽ có thể đăng nhập vào hệ thống bình thường.
              </p>
              <div className="account-confirm-user-info">
                <span className="account-confirm-user-name">{selectedUser.fullName}</span>
                <span className="account-confirm-user-code">Mã: {selectedUser.userCode} • Email: {selectedUser.email}</span>
              </div>
            </div>
            <div className="account-modal__footer">
              <button type="button" className="account-modal-btn account-modal-btn--secondary" onClick={closeModal} disabled={isSubmitting}>Huỷ</button>
              <button type="button" className="account-modal-btn account-modal-btn--primary" onClick={handleConfirmToggle} disabled={isSubmitting}>
                {isSubmitting && <RotateCw size={14} className="spin" />}
                <span>{isSubmitting ? 'Đang xử lý...' : 'Kích hoạt'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

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
              <button type="button" className="account-modal__close-btn" onClick={closeModal} disabled={isSubmitting}>
                <X size={18} />
              </button>
            </div>
            <div className="account-modal__body">
              <p className="account-confirm-text">
                Bạn có chắc chắn muốn xóa tài khoản này? Tài khoản sẽ bị loại bỏ khỏi danh sách quản lý.
              </p>
              <div className="account-confirm-user-info">
                <span className="account-confirm-user-name">{selectedUser.fullName}</span>
                <span className="account-confirm-user-code">Mã: {selectedUser.userCode} • Email: {selectedUser.email}</span>
              </div>
            </div>
            <div className="account-modal__footer">
              <button type="button" className="account-modal-btn account-modal-btn--secondary" onClick={closeModal} disabled={isSubmitting}>Hủy</button>
              <button type="button" className="account-modal-btn account-modal-btn--danger" onClick={handleConfirmDelete} disabled={isSubmitting}>
                {isSubmitting && <RotateCw size={14} className="spin" />}
                <span>{isSubmitting ? 'Đang xóa...' : 'Xóa tài khoản'}</span>
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
