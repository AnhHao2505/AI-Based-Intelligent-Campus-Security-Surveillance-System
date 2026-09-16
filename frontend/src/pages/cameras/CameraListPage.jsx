import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { flexRender, getCoreRowModel, useReactTable } from '@tanstack/react-table';
import { toast } from 'sonner';
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
import CameraCreateModal from './CameraCreateModal';
import Button from '../../components/ui/Button';
import Input from '../../components/ui/Input';
import { useCameraStore } from '../../stores/cameraStore';
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
  const queryClient = useQueryClient();
  const { search, status, operationalStatus, page, isCreateOpen, setFilters, setSearch, setPage, setCreateOpen } = useCameraStore();
  const [searchDraft, setSearchDraft] = useState(search);
  const [actionLoadingId, setActionLoadingId] = useState(null);

  const cameraQuery = useQuery({
    queryKey: ['cameras', { page, search, status, operationalStatus }],
    queryFn: () => fetchCameras({ page, size: 10, search, status, operationalStatus }),
    placeholderData: (previousData) => previousData,
  });

  const toggleMutation = useMutation({
    mutationFn: ({ id, isDecommissioned }) => isDecommissioned ? reactivateCamera(id) : decommissionCamera(id),
    onSuccess: (_, variables) => {
      toast.success(variables.isDecommissioned ? 'Đã kích hoạt camera' : 'Đã tắt camera');
      queryClient.invalidateQueries({ queryKey: ['cameras'] });
    },
    onError: (err) => toast.error(err.message || 'Thao tác thất bại.'),
    onSettled: () => setActionLoadingId(null),
  });

  const cameras = cameraQuery.data?.content || [];
  const totalPages = cameraQuery.data?.totalPages || 0;
  const totalElements = cameraQuery.data?.totalElements || 0;
  const loading = cameraQuery.isLoading;
  const error = cameraQuery.error?.message;

  const handleSearchSubmit = (e) => {
    e.preventDefault();
    setSearch(searchDraft);
  };

  const handleToggleDecommission = async (id, isDecommissioned) => {
    setActionLoadingId(id);
    toggleMutation.mutate({ id, isDecommissioned });
  };

  const columns = [
    { accessorKey: 'cameraCode', header: 'Mã Camera', cell: ({ getValue }) => <span className="font-mono font-bold text-blue">{getValue()}</span> },
    { accessorKey: 'name', header: 'Tên thiết bị', cell: ({ getValue }) => <span className="camera-name">{getValue()}</span> },
    { id: 'location', header: 'Vị trí', cell: ({ row }) => `${row.original.floor !== null ? `Tầng ${row.original.floor}` : 'N/A'}${row.original.zoneName ? ` - ${row.original.zoneName}` : ''}` },
    { accessorKey: 'status', header: 'Trạng thái thiết bị', cell: ({ getValue }) => <span className={`status-badge badge-${getValue().toLowerCase()}`}>{STATUS_LABELS[getValue()] || getValue()}</span> },
    { accessorKey: 'operationalStatus', header: 'Trạng thái kết nối', cell: ({ getValue }) => <span className={`status-badge op-badge-${getValue().toLowerCase()}`}><span className="badge-dot">●</span>{OP_STATUS_LABELS[getValue()] || getValue()}</span> },
    { id: 'actions', header: 'Hành động', cell: ({ row }) => { const camera = row.original; const isDecommissioned = camera.status === 'DECOMMISSIONED'; return <div className="actions-cell"><Button variant="ghost" className="btn-action btn-view" onClick={() => navigate(`/cameras/${camera.id}`)} title="Xem chi tiết"><Eye size={16} /><span>Chi tiết</span></Button><Button variant="ghost" className={`btn-action ${isDecommissioned ? 'btn-activate' : 'btn-decommission'}`} onClick={() => handleToggleDecommission(camera.id, isDecommissioned)} disabled={actionLoadingId === camera.id}>{actionLoadingId === camera.id ? <Loader2 className="animate-spin" size={16} /> : isDecommissioned ? <Power size={16} /> : <PowerOff size={16} />}<span>{isDecommissioned ? 'Bật' : 'Tắt'}</span></Button></div>; } },
  ];
  const table = useReactTable({ data: cameras, columns, getCoreRowModel: getCoreRowModel() });

  return (
    <div className="camera-list-page">
      <div className="page-header">
        <div>
          <h1>Hệ thống Camera Giám sát</h1>
          <p className="subtitle">Quản lý và cấu hình thiết bị camera trong khuôn viên trường</p>
        </div>
        <Button className="btn-add-camera" onClick={() => setCreateOpen(true)}>
          <Plus size={18} />
          <span>Thêm Camera</span>
        </Button>
      </div>

      {/* Filters Form */}
      <div className="filter-card">
        <form onSubmit={handleSearchSubmit} className="filter-form">
          <div className="search-box">
            <Search size={18} className="search-icon" />
            <Input
              type="text"
              placeholder="Tìm theo tên, mã camera, khu vực..."
              value={searchDraft}
              onChange={(e) => setSearchDraft(e.target.value)}
            />
          </div>

          <div className="filter-dropdowns">
            <select
              value={status}
              onChange={(e) => {
                setFilters({ status: e.target.value });
              }}
            >
              <option value="">-- Trạng thái thiết bị --</option>
              <option value="ACTIVE">Đang hoạt động</option>
              <option value="DECOMMISSIONED">Đã tắt/Huỷ</option>
            </select>

            <select
              value={operationalStatus}
              onChange={(e) => {
                setFilters({ operationalStatus: e.target.value });
              }}
            >
              <option value="">-- Trạng thái kết nối --</option>
              <option value="ONLINE">Online</option>
              <option value="OFFLINE">Offline</option>
              <option value="ERROR">Lỗi</option>
            </select>

            <Button type="submit" variant="secondary" className="btn-search">
              Tìm kiếm
            </Button>
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
                  <th>Vị trí (Tầng / Khu vực)</th>
                  <th>Trạng thái thiết bị</th>
                  <th>Trạng thái kết nối</th>
                  <th className="text-right">Hành động</th>
                </tr>
              </thead>
              <tbody>
                {table.getRowModel().rows.map((row) => (
                  <tr key={row.id}>
                    {row.getVisibleCells().map((cell) => <td key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>)}
                  </tr>
                ))}
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
                    onClick={() => setPage(Math.max(0, page - 1))}
                    disabled={page === 0}
                    className="pagination-btn"
                  >
                    <ChevronLeft size={18} />
                  </button>
                  <button
                    onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
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
        onClose={() => setCreateOpen(false)}
      />
    </div>
  );
}
