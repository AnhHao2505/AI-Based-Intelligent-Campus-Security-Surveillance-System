import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { 
  Video, 
  Search, 
  Plus, 
  ChevronLeft, 
  ChevronRight, 
  Power, 
  PowerOff, 
  Eye, 
  Loader2 
} from 'lucide-react';
import { fetchCameras, decommissionCamera, reactivateCamera } from '../../services/cameraService';
import CameraCreateModal from '../../components/CameraCreateModal';
import '../../styles/CameraListPage.css';

const STATUS_LABELS = {
  ACTIVE: 'Đang chạy',
  DECOMMISSIONED: 'Đã tắt',
};

const OP_STATUS_LABELS = {
  ONLINE: 'Online',
  OFFLINE: 'Offline',
  ERROR: 'Lỗi kết nối',
};

export default function CameraListPage() {
  const navigate = useNavigate();
  const [cameras, setCameras] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  
  // Filters & Pagination state
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [opStatusFilter, setOpStatusFilter] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  
  // Modal state
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [actionLoadingId, setActionLoadingId] = useState(null);

  const loadCameras = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchCameras({
        page,
        size: 10,
        search,
        status: statusFilter || undefined,
        operationalStatus: opStatusFilter || undefined,
      });
      setCameras(data.content || []);
      setTotalPages(data.totalPages || 0);
      setTotalElements(data.totalElements || 0);
    } catch (err) {
      console.error('Failed to load cameras:', err);
      setError(err.message || 'Lỗi tải danh sách camera.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadCameras();
  }, [page, statusFilter, opStatusFilter]);

  const handleSearchSubmit = (e) => {
    e.preventDefault();
    setPage(0);
    loadCameras();
  };

  const handleToggleDecommission = async (id, isDecommissioned) => {
    setActionLoadingId(id);
    try {
      if (isDecommissioned) {
        await reactivateCamera(id);
      } else {
        await decommissionCamera(id);
      }
      // Reload list
      await loadCameras();
    } catch (err) {
      alert(err.message || 'Thao tác thất bại.');
    } finally {
      setActionLoadingId(null);
    }
  };

  const handleCreateSuccess = () => {
    setPage(0);
    loadCameras();
  };

  return (
    <div className="camera-list-page">
      {/* Background Ambience */}
      <div className="camera-ambient">
        <div className="camera-ambient__orb camera-ambient__orb--1" />
        <div className="camera-ambient__orb camera-ambient__orb--2" />
      </div>

      <div className="page-header">
        <div>
          <h1>Hệ thống Camera Giám sát</h1>
          <p className="subtitle">Quản lý và cấu hình thiết bị camera trong khuôn viên trường</p>
        </div>
        <button className="btn-add-camera" onClick={() => setIsCreateOpen(true)}>
          <Plus size={18} />
          <span>Thêm Camera</span>
        </button>
      </div>

      {/* Filters Form */}
      <div className="filter-card">
        <form onSubmit={handleSearchSubmit} className="filter-form">
          <div className="search-box">
            <Search size={18} className="search-icon" />
            <input
              type="text"
              placeholder="Tìm theo tên, mã camera, khu vực..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>

          <div className="filter-dropdowns">
            <select
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value);
                setPage(0);
              }}
            >
              <option value="">-- Trạng thái thiết bị --</option>
              <option value="ACTIVE">Đang hoạt động</option>
              <option value="DECOMMISSIONED">Đã tắt/Huỷ</option>
            </select>

            <select
              value={opStatusFilter}
              onChange={(e) => {
                setOpStatusFilter(e.target.value);
                setPage(0);
              }}
            >
              <option value="">-- Trạng thái kết nối --</option>
              <option value="ONLINE">Online</option>
              <option value="OFFLINE">Offline</option>
              <option value="ERROR">Lỗi</option>
            </select>

            <button type="submit" className="btn-search">
              Tìm kiếm
            </button>
          </div>
        </form>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {/* Main Table */}
      <div className="table-container">
        {loading ? (
          <div className="table-loading">
            <Loader2 className="animate-spin text-blue" size={36} />
            <span>Đang tải danh sách camera...</span>
          </div>
        ) : cameras.length === 0 ? (
          <div className="empty-state">
            <Video size={48} className="empty-icon" />
            <h3>Không tìm thấy camera nào</h3>
            <p>Thử điều chỉnh bộ lọc hoặc thêm camera mới để bắt đầu giám sát</p>
          </div>
        ) : (
          <>
            <table className="camera-table">
              <thead>
                <tr>
                  <th>Mã Camera</th>
                  <th>Tên thiết bị</th>
                  <th>Trạng thái thiết bị</th>
                  <th>Trạng thái kết nối</th>
                  <th className="text-right">Hành động</th>
                </tr>
              </thead>
              <tbody>
                {cameras.map((cam) => {
                  const isDecommissioned = cam.status === 'DECOMMISSIONED';
                  const opStatus = cam.operationalStatus;

                  return (
                    <tr key={cam.id}>
                      <td className="font-mono font-bold text-blue">{cam.cameraCode}</td>
                      <td>
                        <div className="camera-name-cell">
                          <span className="camera-name">{cam.name}</span>
                        </div>
                      </td>
                      <td>
                        <span className={`status-badge badge-${cam.status ? cam.status.toLowerCase() : ''}`}>
                          {STATUS_LABELS[cam.status] || cam.status}
                        </span>
                      </td>
                      <td>
                        <span className={`status-badge op-badge-${opStatus ? opStatus.toLowerCase() : ''}`}>
                          <span className="badge-dot">●</span>
                          {OP_STATUS_LABELS[opStatus] || opStatus}
                        </span>
                      </td>
                      <td className="text-right actions-cell">
                        <button 
                          className="btn-action btn-view" 
                          onClick={() => navigate(`/cameras/${cam.id}`)}
                          title="Xem chi tiết & Cấu hình"
                        >
                          <Eye size={16} />
                          <span>Chi tiết</span>
                        </button>
                        <button
                          className={`btn-action ${isDecommissioned ? 'btn-activate' : 'btn-decommission'}`}
                          onClick={() => handleToggleDecommission(cam.id, isDecommissioned)}
                          disabled={actionLoadingId === cam.id}
                          title={isDecommissioned ? 'Kích hoạt lại' : 'Tắt camera'}
                        >
                          {actionLoadingId === cam.id ? (
                            <Loader2 className="animate-spin" size={16} />
                          ) : isDecommissioned ? (
                            <Power size={16} />
                          ) : (
                            <PowerOff size={16} />
                          )}
                          <span>{isDecommissioned ? 'Bật' : 'Tắt'}</span>
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>

            {/* Pagination Controls */}
            {totalPages > 1 && (
              <div className="pagination-bar">
                <span className="pagination-info">
                  Hiển thị trang {page + 1} / {totalPages} (Tổng số {totalElements} camera)
                </span>
                <div className="pagination-buttons">
                  <button
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={page === 0}
                    className="pagination-btn"
                  >
                    <ChevronLeft size={18} />
                  </button>
                  <button
                    onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                    disabled={page === totalPages - 1}
                    className="pagination-btn"
                  >
                    <ChevronRight size={18} />
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      <CameraCreateModal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        onSuccess={handleCreateSuccess}
      />
    </div>
  );
}
