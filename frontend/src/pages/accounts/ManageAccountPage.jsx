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
  RotateCcw,
  Camera,
  Upload,
  Download,
  FileText,
  Layers,
  Eye
} from 'lucide-react';
import {
  getUsers,
  createStaffAccount,
  downloadUserTemplate,
  downloadNormalUserTemplate,
  downloadStaffUserTemplate,
  bulkImportNormalUsers,
  bulkImportStaffUsers,
  toggleUserActive,
  deleteUser,
  getImportBatches,
  getImportBatchDetails,
  deleteImportBatch,
  restoreImportBatch
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

  // Bulk Import Normal User Form State
  const [bulkZipFile, setBulkZipFile] = useState(null);
  const [bulkImportResult, setBulkImportResult] = useState(null);
  const [bulkFilter, setBulkFilter] = useState('ALL');
  const [showBulkConfirmModal, setShowBulkConfirmModal] = useState(false);

  // Batch Management State
  const [showBatchListModal, setShowBatchListModal] = useState(false);
  const [batches, setBatches] = useState([]);
  const [batchPage, setBatchPage] = useState(0);
  const [batchTotalPages, setBatchTotalPages] = useState(0);
  const [batchTotalElements, setBatchTotalElements] = useState(0);
  const [isBatchesLoading, setIsBatchesLoading] = useState(false);

  // Batch Details Modal State
  const [selectedBatchId, setSelectedBatchId] = useState(null);
  const [batchDetails, setBatchDetails] = useState([]);
  const [isBatchDetailsLoading, setIsBatchDetailsLoading] = useState(false);
  const [showBatchDetailsModal, setShowBatchDetailsModal] = useState(false);

  // Batch Action Confirmation States
  const [batchToDelete, setBatchToDelete] = useState(null);
  const [isDeletingBatch, setIsDeletingBatch] = useState(false);
  const [batchToRestore, setBatchToRestore] = useState(null);
  const [isRestoringBatch, setIsRestoringBatch] = useState(false);
  const [restoreResult, setRestoreResult] = useState(null);

  const fetchBatches = async (page = 0) => {
    setIsBatchesLoading(true);
    try {
      const res = await getImportBatches(page, 10);
      setBatches(res?.content || []);
      setBatchTotalPages(res?.totalPages || 0);
      setBatchTotalElements(res?.totalElements || 0);
      setBatchPage(res?.number || 0);
    } catch (err) {
      console.error('Error fetching import batches:', err);
      showToast(err.message || 'Không thể tải danh sách lô import', 'error');
    } finally {
      setIsBatchesLoading(false);
    }
  };

  const handleOpenBatchesModal = () => {
    setShowBatchListModal(true);
    fetchBatches(0);
  };

  const handleViewBatchDetails = async (batchId) => {
    setSelectedBatchId(batchId);
    setShowBatchDetailsModal(true);
    setIsBatchDetailsLoading(true);
    try {
      const details = await getImportBatchDetails(batchId);
      setBatchDetails(details || []);
    } catch (err) {
      console.error('Error fetching batch details:', err);
      showToast(err.message || 'Không thể tải chi tiết lô', 'error');
    } finally {
      setIsBatchDetailsLoading(false);
    }
  };

  const handleConfirmDeleteBatch = (batch) => {
    setBatchToDelete(batch);
  };

  const handleExecuteDeleteBatch = async () => {
    if (!batchToDelete) return;
    setIsDeletingBatch(true);
    try {
      const res = await deleteImportBatch(batchToDelete.importBatchId);
      showToast(res?.message || `Đã gỡ ${res?.deletedCount || 0} tài khoản trong lô`, 'success');
      setBatchToDelete(null);
      await fetchBatches(batchPage);
      fetchUsers();
    } catch (err) {
      console.error('Error deleting import batch:', err);
      showToast(err.message || 'Lỗi khi gỡ lô tài khoản', 'error');
    } finally {
      setIsDeletingBatch(false);
    }
  };

  const handleConfirmRestoreBatch = (batch) => {
    setBatchToRestore(batch);
  };

  const handleExecuteRestoreBatch = async () => {
    if (!batchToRestore) return;
    setIsRestoringBatch(true);
    try {
      const res = await restoreImportBatch(batchToRestore.importBatchId);
      setBatchToRestore(null);
      if (res?.skippedCount > 0) {
        setRestoreResult(res);
        showToast(`Đã khôi phục ${res.restoredCount} tài khoản, bỏ qua ${res.skippedCount} tài khoản do xung đột`, 'warning');
      } else {
        showToast(`Đã khôi phục thành công ${res.restoredCount} tài khoản`, 'success');
      }
      await fetchBatches(batchPage);
      fetchUsers();
    } catch (err) {
      console.error('Error restoring import batch:', err);
      showToast(err.message || 'Lỗi khi khôi phục lô tài khoản', 'error');
    } finally {
      setIsRestoringBatch(false);
    }
  };

  const handleOpenBulkImport = () => {
    setBulkZipFile(null);
    setBulkImportResult(null);
    setBulkFilter('ALL');
    setFormErrors({});
    setShowBulkConfirmModal(false);
    setModalType('bulkImport');
  };

  const handleZipFileChange = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!file.name.toLowerCase().endsWith('.zip')) {
      setFormErrors((prev) => ({ ...prev, bulkZip: 'Vui lòng chọn file nén định dạng .zip' }));
      return;
    }

    if (file.size > 500 * 1024 * 1024) {
      setFormErrors((prev) => ({ ...prev, bulkZip: 'Kích thước file vượt quá giới hạn tối đa 500MB' }));
      return;
    }

    setBulkZipFile(file);
    setFormErrors((prev) => ({ ...prev, bulkZip: null }));
  };

  const handleDownloadTemplate = async () => {
    try {
      if (activeTab === 'NORMAL') {
        await downloadNormalUserTemplate();
      } else {
        await downloadStaffUserTemplate();
      }
    } catch (err) {
      showToast(err.message || 'Không thể tải file mẫu', 'error');
    }
  };

  const handleSubmitBulkImport = (e) => {
    e.preventDefault();
    if (!bulkZipFile) {
      setFormErrors({ bulkZip: 'Vui lòng chọn file .zip chứa dữ liệu' });
      return;
    }
    setFormErrors({});
    setShowBulkConfirmModal(true);
  };

  const handleExecuteBulkImport = async () => {
    if (!bulkZipFile) return;

    setIsSubmitting(true);
    setFormErrors({});

    try {
      const res = activeTab === 'NORMAL'
        ? await bulkImportNormalUsers(bulkZipFile)
        : await bulkImportStaffUsers(bulkZipFile);
      setBulkImportResult(res);
      setShowBulkConfirmModal(false);
      showToast(`Đã nạp thành công ${res.successCount}/${res.totalRows} tài khoản`, 'success');
      fetchUsers();
    } catch (err) {
      console.error('Error in bulk import:', err);
      setFormErrors({ general: err.message || 'Lỗi khi nạp danh sách từ file ZIP' });
      setShowBulkConfirmModal(false);
    } finally {
      setIsSubmitting(false);
    }
  };

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
    setShowBulkConfirmModal(false);
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
      role: activeTab === 'NORMAL' ? 'NORMAL_USER' : ROLES.INTERNAL_GUARD,
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

  // Submit Create Account (NORMAL_USER or Staff Account)
  const handleSubmitCreate = async (e) => {
    e.preventDefault();
    const isNormal = activeTab === 'NORMAL';
    const errors = {};

    if (!createForm.fullName.trim()) {
      errors.fullName = 'Họ và tên là bắt buộc';
    }

    if (!createForm.userCode.trim()) {
      errors.userCode = isNormal ? 'Mã người dùng là bắt buộc' : 'Mã cán bộ là bắt buộc';
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
        role: isNormal ? 'NORMAL_USER' : createForm.role,
        faceImage: frontFile
      });
      showToast(
        isNormal
          ? 'Tạo tài khoản người dùng thành công! Mật khẩu khởi tạo đã được gửi đến email.'
          : 'Tạo tài khoản cán bộ thành công! Mật khẩu khởi tạo đã được gửi đến email.',
        'success'
      );
      closeModal();
      fetchUsers();
    } catch (err) {
      console.error('Error creating account:', err);
      const msg = err.message || (isNormal ? 'Không thể tạo tài khoản người dùng' : 'Không thể tạo tài khoản cán bộ');
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

        {activeTab === 'NORMAL' && (
          <div className="account-toolbar__actions">
            <button
              type="button"
              id="btn-manage-batches-normal"
              className="account-toolbar__secondary-btn"
              onClick={handleOpenBatchesModal}
              title="Xem lịch sử và quản lý gỡ/khôi phục các lô nạp"
            >
              <Layers size={18} />
              <span>Quản lý lô nạp</span>
            </button>
            <button
              type="button"
              id="btn-bulk-import-normal"
              className="account-toolbar__secondary-btn"
              onClick={handleOpenBulkImport}
            >
              <Upload size={18} />
              <span>Nạp từ file ZIP</span>
            </button>
            <button
              type="button"
              id="btn-create-normal-account"
              className="account-header__create-btn"
              onClick={handleOpenCreate}
            >
              <UserPlus size={18} />
              <span>+ Tạo tài khoản</span>
            </button>
          </div>
        )}

        {activeTab === 'SYSTEM' && (
          <div className="account-toolbar__actions">
            <button
              type="button"
              id="btn-manage-batches-staff"
              className="account-toolbar__secondary-btn"
              onClick={handleOpenBatchesModal}
              title="Xem lịch sử và quản lý gỡ/khôi phục các lô nạp"
            >
              <Layers size={18} />
              <span>Quản lý lô nạp</span>
            </button>
            <button
              type="button"
              id="btn-bulk-import-staff"
              className="account-toolbar__secondary-btn"
              onClick={handleOpenBulkImport}
            >
              <Upload size={18} />
              <span>Nạp từ file ZIP</span>
            </button>
            <button
              type="button"
              id="btn-create-account"
              className="account-header__create-btn"
              onClick={handleOpenCreate}
            >
              <UserPlus size={18} />
              <span>+ Tạo tài khoản cán bộ</span>
            </button>
          </div>
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

                          <button
                            type="button"
                            className="account-action-btn account-action-btn--delete"
                            title={isSelf ? 'Không thể tự xóa tài khoản của chính mình' : 'Xóa tài khoản'}
                            disabled={isSelf}
                            onClick={() => handleOpenDelete(item)}
                          >
                            <Trash2 size={15} />
                          </button>
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
                <h2 className="account-modal__title">
                  {activeTab === 'NORMAL' ? 'Tạo tài khoản người dùng' : 'Tạo tài khoản cán bộ / nhân viên'}
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
                    <span>{activeTab === 'NORMAL' ? 'Mã người dùng' : 'Mã cán bộ/nhân viên'}<span className="account-form-label__required">*</span></span>
                  </label>
                  <input
                    type="text"
                    className={`account-form-input ${formErrors.userCode ? 'account-form-input--error' : ''}`}
                    placeholder={activeTab === 'NORMAL' ? 'Ví dụ: SV001, CB001...' : 'Ví dụ: NV-SEC-001, FM-002...'}
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

                {activeTab !== 'NORMAL' && (
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
                )}

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

      {/* Confirmation Modal Before Bulk Import Upload */}
      {showBulkConfirmModal && bulkZipFile && (
        <div className="account-modal-backdrop" style={{ zIndex: 1100 }}>
          <div className="account-modal account-modal--confirm" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--warning">
                  <AlertCircle size={18} />
                </div>
                <h2 className="account-modal__title">Xác nhận nạp danh sách</h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={() => setShowBulkConfirmModal(false)}
                disabled={isSubmitting}
              >
                <X size={18} />
              </button>
            </div>
            <div className="account-modal__body">
              <p className="account-confirm-text">
                Bạn có chắc chắn muốn tiến hành nạp danh sách tài khoản từ file này?
              </p>
              <div className="account-confirm-user-info" style={{ textAlign: 'left' }}>
                <div style={{ fontWeight: 600, marginBottom: '6px', color: 'var(--theme-text-primary, #1e293b)' }}>
                  File được chọn: <span style={{ color: 'var(--primary-color, #2563eb)' }}>{bulkZipFile.name}</span>
                </div>
                <ul style={{ margin: 0, paddingLeft: '18px', fontSize: '0.8125rem', color: 'var(--theme-text-muted, #64748b)', lineHeight: '1.5' }}>
                  <li>Tài khoản sẽ được tạo <strong>NGAY</strong> khi xử lý xong (không có bước xem trước).</li>
                  <li>Thao tác không hoàn tác được từ giao diện.</li>
                </ul>
              </div>
            </div>
            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--secondary"
                onClick={() => setShowBulkConfirmModal(false)}
                disabled={isSubmitting}
              >
                Hủy
              </button>
              <button
                type="button"
                className="account-modal-btn account-modal-btn--primary"
                onClick={handleExecuteBulkImport}
                disabled={isSubmitting}
              >
                {isSubmitting && <RotateCw size={14} className="spin" />}
                <span>{isSubmitting ? 'Đang nạp...' : 'Xác nhận nạp'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ====================================================================
          BULK IMPORT NORMAL USERS MODAL (MF1.2 Import .zip)
          ==================================================================== */}
      {modalType === 'bulkImport' && (
        <div
          className="account-modal-backdrop"
          onClick={(e) => {
            if (e.target === e.currentTarget && !isSubmitting) closeModal();
          }}
        >
          <div className="account-modal" role="dialog" aria-modal="true" style={{ maxWidth: bulkImportResult ? '760px' : '520px' }}>
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--primary">
                  <Upload size={18} />
                </div>
                <h2 className="account-modal__title">
                  {bulkImportResult
                    ? 'Kết quả nạp danh sách'
                    : (activeTab === 'NORMAL'
                        ? 'Nạp hàng loạt tài khoản người dùng (.zip)'
                        : 'Nạp hàng loạt tài khoản cán bộ (.zip)')}
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

            {!bulkImportResult ? (
              <form onSubmit={handleSubmitBulkImport}>
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

                  <div className="account-bulk-template-box">
                    <div className="account-bulk-template-info">
                      <FileText size={20} className="account-bulk-template-icon" />
                      <div>
                        <div className="account-bulk-template-title">File Excel Mẫu & Hướng dẫn nạp dữ liệu</div>
                        <div className="account-bulk-template-desc">
                          {activeTab === 'NORMAL' ? (
                            <>
                              • Tải file mẫu Excel (<code>.xlsx</code>) và nhập thông tin (3 cột: <code>user_code</code>, <code>full_name</code>, <code>email</code>, tối đa 200 dòng).<br />
                              • <strong>Lưu ý quan trọng:</strong> Cần Export / Lưu file Excel dưới dạng <code>metadata.csv</code>.<br />
                              • Nén file <code>metadata.csv</code> cùng thư mục <code>images/</code> chứa ảnh chân dung (tối đa 350KB/ảnh, tên ảnh khớp với <code>user_code</code>) vào file <code>.zip</code> để nạp.
                            </>
                          ) : (
                            <>
                              • Tải file mẫu Excel (<code>.xlsx</code>) và nhập thông tin (4 cột: <code>user_code</code>, <code>full_name</code>, <code>email</code>, <code>role</code>, tối đa 200 dòng).<br />
                              • Cột <code>role</code> bắt buộc có giá trị ở mọi dòng, chọn một trong các vai trò: <code>ADMIN</code>, <code>FACILITY_MANAGER</code>, <code>INTERNAL_GUARD</code>, <code>OUTSOURCED_GUARD</code>.<br />
                              • <strong>Lưu ý quan trọng:</strong> Cần Export / Lưu file Excel dưới dạng <code>metadata.csv</code>.<br />
                              • Nén file <code>metadata.csv</code> cùng thư mục <code>images/</code> chứa ảnh chân dung (tối đa 350KB/ảnh, tên ảnh khớp với <code>user_code</code>) vào file <code>.zip</code> để nạp.
                            </>
                          )}
                        </div>
                      </div>
                    </div>
                    <button
                      type="button"
                      className="account-bulk-download-btn"
                      onClick={handleDownloadTemplate}
                    >
                      <Download size={14} />
                      <span>Tải mẫu Excel (.xlsx)</span>
                    </button>
                  </div>

                  <div className="account-form-group">
                    <label className="account-form-label">
                      <span>Chọn file ZIP dữ liệu<span className="account-form-label__required">*</span></span>
                    </label>
                    <label
                      htmlFor="input-bulk-zip"
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        justifyContent: 'center',
                        padding: '24px',
                        border: bulkZipFile ? '2px solid var(--theme-primary, #3b82f6)' : '2px dashed #cbd5e1',
                        borderRadius: '8px',
                        cursor: 'pointer',
                        backgroundColor: bulkZipFile ? '#eff6ff' : '#f8fafc',
                        marginTop: '4px'
                      }}
                    >
                      <Upload size={32} color={bulkZipFile ? '#3b82f6' : '#64748b'} />
                      <span style={{ fontSize: '0.875rem', marginTop: '8px', fontWeight: 500, color: bulkZipFile ? '#1e40af' : '#475569' }}>
                        {bulkZipFile ? bulkZipFile.name : 'Tải lên file ZIP dữ liệu'}
                      </span>
                      <span style={{ fontSize: '0.75rem', color: '#94a3b8', marginTop: '2px' }}>
                        {bulkZipFile ? `${(bulkZipFile.size / (1024 * 1024)).toFixed(2)} MB` : 'Định dạng .zip, dung lượng tối đa 500MB'}
                      </span>
                    </label>
                    <input
                      id="input-bulk-zip"
                      type="file"
                      accept=".zip"
                      onChange={handleZipFileChange}
                      style={{ display: 'none' }}
                      disabled={isSubmitting}
                    />
                    {formErrors.bulkZip && (
                      <span className="account-form-error">{formErrors.bulkZip}</span>
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
                    disabled={isSubmitting || !bulkZipFile}
                  >
                    {isSubmitting && <RotateCw size={14} className="spin" />}
                    <span>{isSubmitting ? 'Đang xử lý nạp dữ liệu...' : 'Tải lên & Nạp dữ liệu'}</span>
                  </button>
                </div>
              </form>
            ) : (
              <div>
                <div className="account-modal__body">
                  {/* Summary Banner */}
                  <div className="account-bulk-summary-bar">
                    <div className="account-bulk-summary-item">
                      <span className="account-bulk-summary-label">Tổng số bản ghi:</span>
                      <span className="account-bulk-summary-value">{bulkImportResult.totalRows}</span>
                    </div>
                    <div className="account-bulk-summary-item account-bulk-summary-item--success">
                      <span className="account-bulk-summary-label">Thành công:</span>
                      <span className="account-bulk-summary-value">{bulkImportResult.successCount}</span>
                    </div>
                    <div className="account-bulk-summary-item account-bulk-summary-item--failed">
                      <span className="account-bulk-summary-label">Thất bại:</span>
                      <span className="account-bulk-summary-value">{bulkImportResult.failureCount}</span>
                    </div>
                  </div>

                  {bulkImportResult.importBatchId && (
                    <div
                      style={{
                        fontFamily: 'monospace',
                        fontSize: '0.8125rem',
                        color: 'var(--theme-text-muted, #64748b)',
                        marginBottom: '12px'
                      }}
                    >
                      Mã lô import: {bulkImportResult.importBatchId}
                    </div>
                  )}

                  {/* Filter Pills */}
                  <div className="account-bulk-filter-pills">
                    <button
                      type="button"
                      className={`account-bulk-pill ${bulkFilter === 'ALL' ? 'account-bulk-pill--active' : ''}`}
                      onClick={() => setBulkFilter('ALL')}
                    >
                      Tất cả ({bulkImportResult.results?.length || 0})
                    </button>
                    <button
                      type="button"
                      className={`account-bulk-pill ${bulkFilter === 'SUCCESS' ? 'account-bulk-pill--active' : ''}`}
                      onClick={() => setBulkFilter('SUCCESS')}
                    >
                      Thành công ({bulkImportResult.successCount})
                    </button>
                    <button
                      type="button"
                      className={`account-bulk-pill ${bulkFilter === 'FAILED' ? 'account-bulk-pill--active' : ''}`}
                      onClick={() => setBulkFilter('FAILED')}
                    >
                      Thất bại ({bulkImportResult.failureCount})
                    </button>
                  </div>

                  {/* Results Table */}
                  <div className="account-bulk-table-wrapper">
                    <table className="account-table account-bulk-result-table">
                      <thead>
                        <tr>
                          <th style={{ width: '60px' }}>Dòng</th>
                          <th>{activeTab === 'NORMAL' ? 'Mã ND' : 'Mã NV'}</th>
                          <th>Họ và tên</th>
                          <th>Email</th>
                          {activeTab === 'SYSTEM' && <th style={{ width: '130px' }}>Quyền</th>}
                          <th style={{ width: '110px' }}>Trạng thái</th>
                          <th>Chi tiết lỗi</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(bulkImportResult.results || [])
                          .filter((r) => {
                            if (bulkFilter === 'SUCCESS') return r.status === 'SUCCESS';
                            if (bulkFilter === 'FAILED') return r.status === 'FAILED';
                            return true;
                          })
                          .map((r, idx) => (
                            <tr key={idx}>
                              <td>{r.rowIndex}</td>
                              <td>{r.userCode || '-'}</td>
                              <td>{r.fullName || '-'}</td>
                              <td>{r.email || '-'}</td>
                              {activeTab === 'SYSTEM' && (
                                <td>
                                  <span style={{ fontWeight: 500, fontSize: '0.8125rem' }}>
                                    {r.role ? (ROLE_LABELS[r.role] || r.role) : '-'}
                                  </span>
                                </td>
                              )}
                              <td>
                                <span
                                  className={`account-badge ${
                                    r.status === 'SUCCESS' ? 'account-badge--active' : 'account-badge--inactive'
                                  }`}
                                >
                                  {r.status === 'SUCCESS' ? 'Thành công' : 'Thất bại'}
                                </span>
                              </td>
                              <td className="account-bulk-error-cell" title={r.errorMessage}>
                                {r.errorMessage || '-'}
                              </td>
                            </tr>
                          ))}
                      </tbody>
                    </table>
                  </div>
                </div>

                <div className="account-modal__footer">
                  <button
                    type="button"
                    className="account-modal-btn account-modal-btn--primary"
                    onClick={closeModal}
                  >
                    Hoàn tất
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* ====================================================================
          BATCH MANAGEMENT MODAL (Lịch sử & Quản lý lô nạp)
          ==================================================================== */}
      {showBatchListModal && (
        <div className="account-modal-backdrop" style={{ zIndex: 1050 }}>
          <div className="account-modal account-modal--batches" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--primary">
                  <Layers size={18} />
                </div>
                <h2 className="account-modal__title">Lịch sử & Quản lý lô nạp</h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={() => setShowBatchListModal(false)}
              >
                <X size={18} />
              </button>
            </div>

            <div className="account-modal__body" style={{ padding: '16px 24px', maxHeight: '65vh', overflowY: 'auto' }}>
              {isBatchesLoading ? (
                <div style={{ textAlign: 'center', padding: '40px 0', color: 'var(--theme-text-muted, #64748b)' }}>
                  <RotateCw size={24} className="spin" style={{ margin: '0 auto 12px' }} />
                  <p>Đang tải danh sách lô import...</p>
                </div>
              ) : batches.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '48px 0', color: 'var(--theme-text-muted, #64748b)' }}>
                  <Layers size={40} style={{ opacity: 0.3, marginBottom: '12px' }} />
                  <p style={{ fontWeight: 500, fontSize: '0.9375rem' }}>Chưa có lô nạp nào trong hệ thống</p>
                  <p style={{ fontSize: '0.8125rem' }}>Các tài khoản được nạp qua file ZIP sẽ tự động nhóm theo từng lô tại đây.</p>
                </div>
              ) : (
                <>
                  <div className="account-bulk-table-wrap">
                    <table className="account-bulk-table">
                      <thead>
                        <tr>
                          <th style={{ width: '220px' }}>Mã lô (Batch ID)</th>
                          <th style={{ width: '150px' }}>Thời gian nạp</th>
                          <th style={{ width: '170px' }}>Trạng thái tài khoản</th>
                          <th style={{ width: '180px', textAlign: 'right' }}>Thao tác</th>
                        </tr>
                      </thead>
                      <tbody>
                        {batches.map((batch) => {
                          const isAllDeleted = batch.activeCount === 0 && batch.deletedCount > 0;
                          return (
                            <tr key={batch.importBatchId}>
                              <td>
                                <code className="account-batch-code" title={batch.importBatchId}>
                                  {batch.importBatchId}
                                </code>
                              </td>
                              <td style={{ fontSize: '0.8125rem', color: 'var(--theme-text-secondary, #475569)' }}>
                                {batch.createdAt ? new Date(batch.createdAt).toLocaleString('vi-VN', {
                                  year: 'numeric',
                                  month: '2-digit',
                                  day: '2-digit',
                                  hour: '2-digit',
                                  minute: '2-digit'
                                }) : '-'}
                              </td>
                              <td>
                                {isAllDeleted ? (
                                  <span className="account-badge account-badge--inactive" style={{ background: '#fef2f2', color: '#b91c1c' }}>
                                    Đã gỡ ({batch.deletedCount})
                                  </span>
                                ) : (
                                  <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
                                    <span className="account-badge account-badge--active">
                                      {batch.activeCount} hoạt động
                                    </span>
                                    {batch.deletedCount > 0 && (
                                      <span className="account-badge account-badge--inactive">
                                        {batch.deletedCount} đã gỡ
                                      </span>
                                    )}
                                  </div>
                                )}
                              </td>
                              <td>
                                <div style={{ display: 'flex', gap: '6px', justifyContent: 'flex-end' }}>
                                  <button
                                    type="button"
                                    className="account-modal-btn account-modal-btn--secondary"
                                    style={{ padding: '5px 10px', fontSize: '0.75rem', height: 'auto' }}
                                    onClick={() => handleViewBatchDetails(batch.importBatchId)}
                                    title="Xem chi tiết các tài khoản trong lô"
                                  >
                                    <Eye size={13} style={{ marginRight: '4px' }} />
                                    Chi tiết
                                  </button>

                                  {batch.activeCount > 0 && (
                                    <button
                                      type="button"
                                      className="account-modal-btn account-modal-btn--danger"
                                      style={{ padding: '5px 10px', fontSize: '0.75rem', height: 'auto' }}
                                      onClick={() => handleConfirmDeleteBatch(batch)}
                                      title="Gỡ tất cả tài khoản đang hoạt động trong lô này"
                                    >
                                      <Trash2 size={13} style={{ marginRight: '4px' }} />
                                      Gỡ lô
                                    </button>
                                  )}

                                  {batch.deletedCount > 0 && (
                                    <button
                                      type="button"
                                      className="account-modal-btn account-modal-btn--primary"
                                      style={{ padding: '5px 10px', fontSize: '0.75rem', height: 'auto' }}
                                      onClick={() => handleConfirmRestoreBatch(batch)}
                                      title="Khôi phục các tài khoản đã bị gỡ trong lô này"
                                    >
                                      <RotateCcw size={13} style={{ marginRight: '4px' }} />
                                      Khôi phục
                                    </button>
                                  )}
                                </div>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>

                  {batchTotalPages > 1 && (
                    <div className="account-pagination" style={{ marginTop: '16px', borderTop: 'none', padding: 0 }}>
                      <span className="account-pagination__info" style={{ fontSize: '0.8125rem' }}>
                        Trang {batchPage + 1} / {batchTotalPages} ({batchTotalElements} lô)
                      </span>
                      <div className="account-pagination__controls">
                        <button
                          type="button"
                          className="account-pagination__btn"
                          onClick={() => fetchBatches(batchPage - 1)}
                          disabled={batchPage === 0 || isBatchesLoading}
                        >
                          <ChevronLeft size={16} />
                        </button>
                        <button
                          type="button"
                          className="account-pagination__btn"
                          onClick={() => fetchBatches(batchPage + 1)}
                          disabled={batchPage >= batchTotalPages - 1 || isBatchesLoading}
                        >
                          <ChevronRight size={16} />
                        </button>
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>

            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--secondary"
                onClick={() => setShowBatchListModal(false)}
              >
                Đóng
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ====================================================================
          BATCH DETAILS MODAL (Xem chi tiết tài khoản thuộc lô)
          ==================================================================== */}
      {showBatchDetailsModal && (
        <div className="account-modal-backdrop" style={{ zIndex: 1100 }}>
          <div className="account-modal account-modal--batch-details" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--primary">
                  <Eye size={18} />
                </div>
                <div>
                  <h2 className="account-modal__title">Chi tiết lô tài khoản</h2>
                  <div style={{ fontSize: '0.75rem', color: 'var(--theme-text-muted, #64748b)', fontFamily: 'monospace' }}>
                    {selectedBatchId}
                  </div>
                </div>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={() => setShowBatchDetailsModal(false)}
              >
                <X size={18} />
              </button>
            </div>

            <div className="account-modal__body" style={{ padding: '16px 24px', maxHeight: '60vh', overflowY: 'auto' }}>
              {isBatchDetailsLoading ? (
                <div style={{ textAlign: 'center', padding: '36px 0', color: 'var(--theme-text-muted, #64748b)' }}>
                  <RotateCw size={24} className="spin" style={{ margin: '0 auto 12px' }} />
                  <p>Đang tải chi tiết tài khoản...</p>
                </div>
              ) : batchDetails.length === 0 ? (
                <p style={{ textAlign: 'center', padding: '24px 0', color: 'var(--theme-text-muted, #64748b)' }}>
                  Không có tài khoản nào trong lô này.
                </p>
              ) : (
                <div className="account-bulk-table-wrap">
                  <table className="account-bulk-table">
                    <thead>
                      <tr>
                        <th style={{ width: '110px' }}>Mã người dùng</th>
                        <th>Họ và tên</th>
                        <th>Email</th>
                        <th style={{ width: '130px' }}>Vai trò</th>
                        <th style={{ width: '120px' }}>Trạng thái</th>
                      </tr>
                    </thead>
                    <tbody>
                      {batchDetails.map((u) => (
                        <tr key={u.id}>
                          <td style={{ fontWeight: 600 }}>{u.userCode}</td>
                          <td>{u.fullName}</td>
                          <td style={{ fontSize: '0.8125rem' }}>{u.email}</td>
                          <td style={{ fontSize: '0.8125rem' }}>
                            {ROLE_LABELS[u.role] || u.role}
                          </td>
                          <td>
                            {u.deletedAt ? (
                              <span className="account-badge account-badge--inactive" style={{ background: '#fef2f2', color: '#b91c1c' }}>
                                Đã gỡ
                              </span>
                            ) : u.isActive ? (
                              <span className="account-badge account-badge--active">Hoạt động</span>
                            ) : (
                              <span className="account-badge account-badge--inactive">Vô hiệu hóa</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--secondary"
                onClick={() => setShowBatchDetailsModal(false)}
              >
                Đóng
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ====================================================================
          BATCH DELETE CONFIRMATION MODAL
          ==================================================================== */}
      {batchToDelete && (
        <div className="account-modal-backdrop" style={{ zIndex: 1200 }}>
          <div className="account-modal account-modal--confirm" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--danger">
                  <Trash2 size={18} />
                </div>
                <h2 className="account-modal__title">Xác nhận gỡ lô tài khoản</h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={() => setBatchToDelete(null)}
                disabled={isDeletingBatch}
              >
                <X size={18} />
              </button>
            </div>

            <div className="account-modal__body">
              <p className="account-confirm-text">
                Bạn có chắc chắn muốn gỡ lô tài khoản này?
              </p>
              <div className="account-confirm-user-info" style={{ textAlign: 'left' }}>
                <div style={{ marginBottom: '6px' }}>
                  <strong>Mã lô:</strong> <code className="account-batch-code">{batchToDelete.importBatchId}</code>
                </div>
                <div style={{ marginBottom: '8px' }}>
                  <strong>Số tài khoản sẽ bị gỡ:</strong> <span style={{ color: '#b91c1c', fontWeight: 600 }}>{batchToDelete.activeCount} tài khoản</span>
                </div>
                <div style={{ padding: '8px 12px', background: '#eff6ff', borderRadius: '6px', fontSize: '0.8125rem', color: '#1e40af', lineHeight: '1.4' }}>
                  ℹ️ Thao tác này chỉ xoá mềm (soft-delete). Bạn <strong>hoàn toàn có thể khôi phục lại</strong> các tài khoản này sau đó từ danh sách lô.
                </div>
              </div>
            </div>

            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--secondary"
                onClick={() => setBatchToDelete(null)}
                disabled={isDeletingBatch}
              >
                Hủy
              </button>
              <button
                type="button"
                className="account-modal-btn account-modal-btn--danger"
                onClick={handleExecuteDeleteBatch}
                disabled={isDeletingBatch}
              >
                {isDeletingBatch && <RotateCw size={14} className="spin" />}
                <span>{isDeletingBatch ? 'Đang gỡ...' : 'Xác nhận gỡ lô'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ====================================================================
          BATCH RESTORE CONFIRMATION MODAL
          ==================================================================== */}
      {batchToRestore && (
        <div className="account-modal-backdrop" style={{ zIndex: 1200 }}>
          <div className="account-modal account-modal--confirm" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--warning">
                  <RotateCcw size={18} />
                </div>
                <h2 className="account-modal__title">Khôi phục lô tài khoản</h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={() => setBatchToRestore(null)}
                disabled={isRestoringBatch}
              >
                <X size={18} />
              </button>
            </div>

            <div className="account-modal__body">
              <p className="account-confirm-text">
                Bạn có chắc chắn muốn khôi phục các tài khoản đã bị gỡ trong lô này?
              </p>
              <div className="account-confirm-user-info" style={{ textAlign: 'left' }}>
                <div style={{ marginBottom: '6px' }}>
                  <strong>Mã lô:</strong> <code className="account-batch-code">{batchToRestore.importBatchId}</code>
                </div>
                <div style={{ marginBottom: '8px' }}>
                  <strong>Số tài khoản cần khôi phục:</strong> <span style={{ color: '#2563eb', fontWeight: 600 }}>{batchToRestore.deletedCount} tài khoản</span>
                </div>
                <div style={{ padding: '8px 12px', background: '#fefce8', borderRadius: '6px', fontSize: '0.8125rem', color: '#854d0e', lineHeight: '1.4' }}>
                  ⚠️ Nếu có tài khoản trùng mã người dùng hoặc email với tài khoản đang hoạt động khác, hệ thống sẽ tự động bỏ qua và thông báo chi tiết.
                </div>
              </div>
            </div>

            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--secondary"
                onClick={() => setBatchToRestore(null)}
                disabled={isRestoringBatch}
              >
                Hủy
              </button>
              <button
                type="button"
                className="account-modal-btn account-modal-btn--primary"
                onClick={handleExecuteRestoreBatch}
                disabled={isRestoringBatch}
              >
                {isRestoringBatch && <RotateCw size={14} className="spin" />}
                <span>{isRestoringBatch ? 'Đang khôi phục...' : 'Khôi phục lô'}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ====================================================================
          BATCH RESTORE RESULT MODAL (Hiển thị chi tiết các hàng bị bỏ qua)
          ==================================================================== */}
      {restoreResult && restoreResult.skippedCount > 0 && (
        <div className="account-modal-backdrop" style={{ zIndex: 1250 }}>
          <div className="account-modal account-modal--restore-result" role="dialog" aria-modal="true">
            <div className="account-modal__header">
              <div className="account-modal__title-wrap">
                <div className="account-modal__icon-badge account-modal__icon-badge--warning">
                  <AlertCircle size={18} />
                </div>
                <h2 className="account-modal__title">Kết quả khôi phục lô</h2>
              </div>
              <button
                type="button"
                className="account-modal__close-btn"
                onClick={() => setRestoreResult(null)}
              >
                <X size={18} />
              </button>
            </div>

            <div className="account-modal__body" style={{ padding: '16px 24px' }}>
              <div style={{ marginBottom: '14px', fontSize: '0.875rem' }}>
                Đã khôi phục thành công <strong style={{ color: '#16a34a' }}>{restoreResult.restoredCount}</strong> tài khoản. Có <strong style={{ color: '#dc2626' }}>{restoreResult.skippedCount}</strong> tài khoản bị bỏ qua do xung đột định danh:
              </div>
              <div className="account-bulk-table-wrap" style={{ maxHeight: '250px', overflowY: 'auto' }}>
                <table className="account-bulk-table">
                  <thead>
                    <tr>
                      <th style={{ width: '110px' }}>Mã người dùng</th>
                      <th>Email</th>
                      <th>Lý do bỏ qua</th>
                    </tr>
                  </thead>
                  <tbody>
                    {restoreResult.skippedUsers.map((item, idx) => (
                      <tr key={idx}>
                        <td style={{ fontWeight: 600 }}>{item.userCode}</td>
                        <td style={{ fontSize: '0.8125rem' }}>{item.email}</td>
                        <td style={{ fontSize: '0.8125rem', color: '#b91c1c' }}>{item.reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="account-modal__footer">
              <button
                type="button"
                className="account-modal-btn account-modal-btn--primary"
                onClick={() => setRestoreResult(null)}
              >
                Đã hiểu
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
