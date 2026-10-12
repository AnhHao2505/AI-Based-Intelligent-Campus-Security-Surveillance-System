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
  Loader2,
  Trash2
} from 'lucide-react';
import { fetchCameras, decommissionCamera, reactivateCamera, deleteCamera } from '../../services/cameraService';
import CameraCreateModal from '../../components/CameraCreateModal';
import { Modal, Button, PageHeader, LoadingState, EmptyState, ErrorState } from '../../components/ui';
import { formatLocation } from '../../utils/formatLocation';
import '../../styles/CameraListPage.css';

const STATUS_LABELS = {
  ACTIVE: 'Đang kích hoạt',
  DECOMMISSIONED: 'Đã tắt',
};

const OP_STATUS_LABELS = {
  ONLINE: 'Online',
  OFFLINE: 'Offline',
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
  // Xác nhận trước khi tắt camera (trước đây bấm "Tắt" là gọi API ngay)
  const [decommissionTarget, setDecommissionTarget] = useState(null);

  const loadCameras = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchCameras({
        page,
        size: 10,
        sort: 'cameraCode,asc',
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

  const handleDeleteCamera = async (camera) => {
    const confirmed = window.confirm(
      `Bạn có chắc chắn muốn xóa camera ${camera.cameraCode} - ${camera.name}?\n\nCamera sẽ bị xóa khỏi danh sách giám sát và hủy luồng kết nối.`
    );
    if (!confirmed) return;

    setActionLoadingId(camera.id);
    try {
      await deleteCamera(camera.id);
      await loadCameras();
    } catch (err) {
      alert(err.message || 'Xóa camera thất bại.');
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

      <PageHeader
        title="Hệ thống camera giám sát"
        description="Quản lý và cấu hình thiết bị camera trong khuôn viên trường"
        actions={
          <Button variant="primary" icon={Plus} onClick={() => setIsCreateOpen(true)}>
            Thêm camera
          </Button>
        }
      />

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
              <option value="DECOMMISSIONED">Đã tắt</option>
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
            </select>

            <button type="submit" className="btn-search">
              Tìm kiếm
            </button>
          </div>
        </form>
      </div>

      {/* Main Table — thứ tự: đang tải -> lỗi (Thử lại) -> rỗng -> dữ liệu */}
      <div className="table-container">
        {loading ? (
          <LoadingState text="Đang tải danh sách camera..." />
        ) : error ? (
          <ErrorState title="Không tải được danh sách camera" message={error} onRetry={loadCameras} />
        ) : cameras.length === 0 ? (
          <EmptyState
            icon={Video}
            title="Không tìm thấy camera nào"
            description="Thử điều chỉnh bộ lọc hoặc thêm camera mới để bắt đầu giám sát"
          />
        ) : (
          <>
            <table className="camera-table">
              <thead>
                <tr>
                  <th>Mã Camera</th>
                  <th>Tên thiết bị</th>
                  <th>Khu vực</th>
                  <th>Trạng thái thiết bị</th>
                  <th>Trạng thái kết nối</th>
                  <th className="text-right">Hành động</th>
                </tr>
              </thead>
              <tbody>
                {cameras.map((cam) => {
                  const isDecommissioned = cam.status === 'DECOMMISSIONED';
                  const opStatus = cam.operationalStatus;
                  const areaName = cam.areaName || cam.assignedArea?.name || (cam.assignedAreas && cam.assignedAreas.length > 0 ? cam.assignedAreas[0]?.name : null);
                  const areaBuilding = cam.building || cam.assignedArea?.building || (cam.assignedAreas && cam.assignedAreas.length > 0 ? cam.assignedAreas[0]?.building : null);
                  const areaFloor = cam.floor || cam.assignedArea?.floor || (cam.assignedAreas && cam.assignedAreas.length > 0 ? cam.assignedAreas[0]?.floor : null);

                  return (
                    <tr key={cam.id}>
                      <td className="font-mono font-bold text-blue">{cam.cameraCode}</td>
                      <td>
                        <div className="camera-name-cell">
                          <span className="camera-name">{cam.name}</span>
                        </div>
                      </td>
                      <td>
                        {areaName ? (
                          <span
                            className="location-tag"
                            style={{
                              fontSize: "0.8rem",
                              padding: "0.2rem 0.55rem",
                              borderRadius: "6px",
                              display: "inline-block",
                            }}
                          >
                            {areaName}
                            {formatLocation(areaBuilding, areaFloor) ? ` (${formatLocation(areaBuilding, areaFloor)})` : ""}
                          </span>
                        ) : (
                          <span style={{ color: "var(--theme-text-muted)", fontSize: "0.85rem" }}>
                            —
                          </span>
                        )}
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
                          onClick={() =>
                            isDecommissioned
                              ? handleToggleDecommission(cam.id, true)
                              : setDecommissionTarget(cam)
                          }
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
                        <button
                          className="btn-action btn-delete"
                          onClick={() => handleDeleteCamera(cam)}
                          disabled={actionLoadingId === cam.id}
                          title="Xóa camera"
                        >
                          <Trash2 size={16} />
                          <span>Xóa</span>
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

      <Modal
        isOpen={Boolean(decommissionTarget)}
        onClose={() => actionLoadingId !== decommissionTarget?.id && setDecommissionTarget(null)}
        title="Tắt camera"
        icon={PowerOff}
        iconVariant="danger"
        size="sm"
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setDecommissionTarget(null)}
              disabled={actionLoadingId === decommissionTarget?.id}
            >
              Hủy bỏ
            </Button>
            <Button
              variant="danger"
              icon={PowerOff}
              loading={actionLoadingId === decommissionTarget?.id}
              onClick={async () => {
                await handleToggleDecommission(decommissionTarget.id, false);
                setDecommissionTarget(null);
              }}
            >
              Tắt camera
            </Button>
          </>
        }
      >
        {decommissionTarget && (
          <p>
            Tắt camera <strong>{decommissionTarget.cameraCode} - {decommissionTarget.name}</strong>?
            Camera sẽ ngừng giám sát cho tới khi được bật lại.
          </p>
        )}
      </Modal>
    </div>
  );
}
