import { useState, useEffect } from 'react';
import { Network, Search, ArrowRight, ArrowLeft, Save, Loader2, CheckCircle2, AlertCircle, Video } from 'lucide-react';
import { getAreas, getAreaCameras, updateAreaCameras } from '../../services/areaService';
import { fetchAllSimpleCameras } from '../../services/cameraService';
import '../../styles/AreaCameraManagementPage.css';

export default function AreaCameraManagementPage() {
  const [areas, setAreas] = useState([]);
  const [selectedAreaId, setSelectedAreaId] = useState('');
  const [allCameras, setAllCameras] = useState([]);
  const [assignedCameraIds, setAssignedCameraIds] = useState(new Set());

  const [loadingAreas, setLoadingAreas] = useState(true);
  const [loadingCameras, setLoadingCameras] = useState(false);
  const [saving, setSaving] = useState(false);

  const [searchAvailable, setSearchAvailable] = useState('');
  const [searchAssigned, setSearchAssigned] = useState('');

  const [error, setError] = useState(null);
  const [successMsg, setSuccessMsg] = useState(null);

  useEffect(() => {
    initData();
  }, []);

  const initData = async () => {
    setLoadingAreas(true);
    setError(null);
    try {
      const [areaRes, cameraRes] = await Promise.all([
        getAreas({ page: 0, size: 100 }),
        fetchAllSimpleCameras()
      ]);

      const areaList = areaRes?.content || [];
      setAreas(areaList);
      setAllCameras(cameraRes || []);

      if (areaList.length > 0) {
        setSelectedAreaId(areaList[0].id);
        loadAreaCameras(areaList[0].id);
      }
    } catch (err) {
      console.error('Lỗi khi tải dữ liệu khởi tạo:', err);
      setError('Không thể tải danh sách Khu vực và Camera.');
    } finally {
      setLoadingAreas(false);
    }
  };

  const loadAreaCameras = async (areaId) => {
    if (!areaId) return;
    setLoadingCameras(true);
    setError(null);
    setSuccessMsg(null);
    try {
      const data = await getAreaCameras(areaId);
      const assignedIds = new Set((data.cameras || []).map((c) => c.id));
      setAssignedCameraIds(assignedIds);
    } catch (err) {
      console.error('Lỗi khi tải danh sách camera của khu vực:', err);
      setError('Lỗi lấy danh sách camera cho Khu vực đã chọn.');
    } finally {
      setLoadingCameras(false);
    }
  };

  const handleAreaChange = (e) => {
    const areaId = e.target.value;
    setSelectedAreaId(areaId);
    loadAreaCameras(areaId);
  };

  const assignCamera = (camId) => {
    setAssignedCameraIds((prev) => new Set([...prev, camId]));
  };

  const unassignCamera = (camId) => {
    setAssignedCameraIds((prev) => {
      const updated = new Set(prev);
      updated.delete(camId);
      return updated;
    });
  };

  const handleSave = async () => {
    if (!selectedAreaId) return;
    setSaving(true);
    setError(null);
    setSuccessMsg(null);
    try {
      const cameraIdsArray = Array.from(assignedCameraIds);
      await updateAreaCameras(selectedAreaId, cameraIdsArray);
      setSuccessMsg('Cập nhật liên kết Camera – Khu vực thành công!');
    } catch (err) {
      console.error('Lỗi khi lưu danh sách camera:', err);
      setError(err.message || 'Cập nhật thất bại.');
    } finally {
      setSaving(false);
    }
  };

  // Filter lists
  const availableCameras = allCameras.filter((cam) => !assignedCameraIds.has(cam.id));
  const assignedCameras = allCameras.filter((cam) => assignedCameraIds.has(cam.id));

  const filteredAvailable = availableCameras.filter(
    (cam) =>
      cam.name.toLowerCase().includes(searchAvailable.toLowerCase()) ||
      cam.cameraCode.toLowerCase().includes(searchAvailable.toLowerCase())
  );

  const filteredAssigned = assignedCameras.filter(
    (cam) =>
      cam.name.toLowerCase().includes(searchAssigned.toLowerCase()) ||
      cam.cameraCode.toLowerCase().includes(searchAssigned.toLowerCase())
  );

  if (loadingAreas) {
    return (
      <div className="area-camera-container">
        <div style={{ textAlign: 'center', padding: '4rem', color: '#94a3b8' }}>
          <Loader2 className="animate-spin text-blue" size={44} />
          <p style={{ marginTop: '1rem' }}>Đang tải danh sách khu vực...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="area-camera-container">
      <div className="area-camera-header">
        <h1>
          <Network size={28} className="text-blue" />
          Gán Camera Vào Khu Vực (N - N)
        </h1>
        <p>Quản lý và hỗ trợ liên kết linh hoạt giữa các Camera giám sát và Khu vực thuộc khuôn viên trường.</p>
      </div>

      <div className="area-selector-card">
        <label htmlFor="area-select">Chọn Khu Vực Quản Lý:</label>
        <select
          id="area-select"
          className="area-select-dropdown"
          value={selectedAreaId}
          onChange={handleAreaChange}
        >
          {areas.map((area) => (
            <option key={area.id} value={area.id}>
              [{area.code}] {area.name} ({area.building ? `Tòa ${area.building}` : ''} {area.floor ? `Tầng ${area.floor}` : ''})
            </option>
          ))}
        </select>
      </div>

      {error && (
        <div className="ai-alert-banner error" style={{ marginBottom: '1.5rem' }}>
          <AlertCircle size={20} />
          <span>{error}</span>
        </div>
      )}

      {successMsg && (
        <div className="ai-alert-banner success" style={{ marginBottom: '1.5rem' }}>
          <CheckCircle2 size={20} />
          <span>{successMsg}</span>
        </div>
      )}

      {loadingCameras ? (
        <div style={{ textAlign: 'center', padding: '3rem', color: '#94a3b8' }}>
          <Loader2 className="animate-spin text-blue" size={36} />
          <p style={{ marginTop: '0.5rem' }}>Đang nạp danh sách camera của khu vực...</p>
        </div>
      ) : (
        <>
          <div className="transfer-grid">
            {/* Left Box: Unassigned Cameras */}
            <div className="transfer-box">
              <div className="transfer-box-header">
                <h3>
                  <Video size={18} className="text-blue" />
                  Camera Khả Dụng (Chưa Gán)
                </h3>
                <span className="badge-count">{filteredAvailable.length}</span>
              </div>

              <div className="search-input-wrapper">
                <Search size={16} className="search-icon-inside" />
                <input
                  type="text"
                  placeholder="Tìm camera theo tên, mã..."
                  value={searchAvailable}
                  onChange={(e) => setSearchAvailable(e.target.value)}
                />
              </div>

              <div className="camera-list-scroll">
                {filteredAvailable.length === 0 ? (
                  <p style={{ color: '#64748b', textAlign: 'center', margin: 'auto', fontSize: '0.9rem' }}>
                    Không có camera khả dụng.
                  </p>
                ) : (
                  filteredAvailable.map((cam) => (
                    <div key={cam.id} className="camera-item-card" onClick={() => assignCamera(cam.id)}>
                      <div className="camera-item-info">
                        <span className="camera-item-code">{cam.cameraCode}</span>
                        <span className="camera-item-name">{cam.name}</span>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                        <span className={`status-dot ${cam.operationalStatus === 'ONLINE' ? 'online' : 'offline'}`} />
                        <ArrowRight size={16} className="text-blue" />
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>

            {/* Middle Controls */}
            <div className="transfer-controls">
              <button
                className="btn-transfer"
                title="Gán tất cả camera hiển thị"
                disabled={filteredAvailable.length === 0}
                onClick={() => {
                  filteredAvailable.forEach((c) => assignCamera(c.id));
                }}
              >
                <ArrowRight size={20} />
              </button>
              <button
                className="btn-transfer"
                title="Bỏ gán tất cả camera hiển thị"
                disabled={filteredAssigned.length === 0}
                onClick={() => {
                  filteredAssigned.forEach((c) => unassignCamera(c.id));
                }}
              >
                <ArrowLeft size={20} />
              </button>
            </div>

            {/* Right Box: Assigned Cameras */}
            <div className="transfer-box">
              <div className="transfer-box-header">
                <h3>
                  <CheckCircle2 size={18} style={{ color: '#10b981' }} />
                  Camera Đã Gán Vẫn Đang Hoạt Động
                </h3>
                <span className="badge-count" style={{ background: 'rgba(16, 185, 129, 0.2)', color: '#34d399', borderColor: 'rgba(16, 185, 129, 0.3)' }}>
                  {filteredAssigned.length}
                </span>
              </div>

              <div className="search-input-wrapper">
                <Search size={16} className="search-icon-inside" />
                <input
                  type="text"
                  placeholder="Tìm trong danh sách đã gán..."
                  value={searchAssigned}
                  onChange={(e) => setSearchAssigned(e.target.value)}
                />
              </div>

              <div className="camera-list-scroll">
                {filteredAssigned.length === 0 ? (
                  <p style={{ color: '#64748b', textAlign: 'center', margin: 'auto', fontSize: '0.9rem' }}>
                    Chưa gán camera nào vào khu vực này.
                  </p>
                ) : (
                  filteredAssigned.map((cam) => (
                    <div key={cam.id} className="camera-item-card" onClick={() => unassignCamera(cam.id)}>
                      <div className="camera-item-info">
                        <span className="camera-item-code">{cam.cameraCode}</span>
                        <span className="camera-item-name">{cam.name}</span>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                        <span className={`status-dot ${cam.operationalStatus === 'ONLINE' ? 'online' : 'offline'}`} />
                        <ArrowLeft size={16} style={{ color: '#ef4444' }} />
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          </div>

          <div className="area-save-footer">
            <button className="btn-save-area-cameras" onClick={handleSave} disabled={saving}>
              {saving ? <Loader2 size={18} className="animate-spin" /> : <Save size={18} />}
              <span>{saving ? 'Đang lưu...' : 'Lưu Thay Đổi Liên Kết'}</span>
            </button>
          </div>
        </>
      )}
    </div>
  );
}
