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
        <div className="area-camera-loading">
          <Loader2 className="animate-spin area-camera-spinner" size={40} />
          <p>Đang tải danh sách khu vực...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="area-camera-container">
      <div className="area-camera-header">
        <h1>
          <Network size={26} className="area-camera-header__icon" />
          <span>Gán Camera Vào Khu Vực (N - N)</span>
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
        <div className="ai-alert-banner error">
          <AlertCircle size={18} />
          <span>{error}</span>
        </div>
      )}

      {successMsg && (
        <div className="ai-alert-banner success">
          <CheckCircle2 size={18} />
          <span>{successMsg}</span>
        </div>
      )}

      {loadingCameras ? (
        <div className="area-camera-loading">
          <Loader2 className="animate-spin area-camera-spinner" size={32} />
          <p>Đang nạp danh sách camera của khu vực...</p>
        </div>
      ) : (
        <>
          <div className="transfer-grid">
            {/* Left Box: Unassigned Cameras */}
            <div className="transfer-box">
              <div className="transfer-box-header">
                <h3>
                  <Video size={18} className="transfer-box-header__icon" />
                  <span>Camera Khả Dụng (Chưa Gán)</span>
                </h3>
                <span className="badge-count">{filteredAvailable.length}</span>
              </div>

              <div className="transfer-box-body">
                <div className="transfer-search-wrapper">
                  <Search size={16} className="search-icon-inside" />
                  <input
                    type="text"
                    placeholder="Tìm camera theo tên, mã..."
                    value={searchAvailable}
                    onChange={(e) => setSearchAvailable(e.target.value)}
                  />
                </div>

                {filteredAvailable.length > 0 ? (
                  <div className="camera-list">
                    {filteredAvailable.map((cam) => (
                      <div key={cam.id} className="camera-item-card" onClick={() => assignCamera(cam.id)}>
                        <div className="camera-item-info">
                          <span className="camera-item-code">{cam.cameraCode}</span>
                          <span className="camera-item-name">{cam.name}</span>
                        </div>
                        <div className="camera-item-meta">
                          <span className={`status-dot ${cam.operationalStatus === 'ONLINE' ? 'online' : 'offline'}`} />
                          <ArrowRight size={16} className="camera-item-arrow" />
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="camera-list__empty">
                    <Search size={24} className="camera-list__empty-icon" />
                    <p className="camera-list__empty-text">
                      {searchAvailable.trim()
                        ? 'Không tìm thấy camera khớp từ khoá.'
                        : 'Không còn camera nào chưa gán.'}
                    </p>
                  </div>
                )}
              </div>
            </div>

            {/* Middle Controls */}
            <div className="transfer-controls">
              <button
                type="button"
                className="btn-transfer"
                title="Gán tất cả camera hiển thị"
                disabled={filteredAvailable.length === 0}
                onClick={() => {
                  filteredAvailable.forEach((c) => assignCamera(c.id));
                }}
              >
                <ArrowRight size={18} />
              </button>
              <button
                type="button"
                className="btn-transfer"
                title="Bỏ gán tất cả camera hiển thị"
                disabled={filteredAssigned.length === 0}
                onClick={() => {
                  filteredAssigned.forEach((c) => unassignCamera(c.id));
                }}
              >
                <ArrowLeft size={18} />
              </button>
            </div>

            {/* Right Box: Assigned Cameras */}
            <div className="transfer-box">
              <div className="transfer-box-header">
                <h3>
                  <CheckCircle2 size={18} className="transfer-box-header__icon transfer-box-header__icon--success" />
                  <span>Camera Đã Gán Vẫn Đang Hoạt Động</span>
                </h3>
                <span className="badge-count">{filteredAssigned.length}</span>
              </div>

              <div className="transfer-box-body">
                <div className="transfer-search-wrapper">
                  <Search size={16} className="search-icon-inside" />
                  <input
                    type="text"
                    placeholder="Tìm trong danh sách đã gán..."
                    value={searchAssigned}
                    onChange={(e) => setSearchAssigned(e.target.value)}
                  />
                </div>

                {filteredAssigned.length > 0 ? (
                  <div className="camera-list">
                    {filteredAssigned.map((cam) => (
                      <div key={cam.id} className="camera-item-card" onClick={() => unassignCamera(cam.id)}>
                        <div className="camera-item-info">
                          <span className="camera-item-code">{cam.cameraCode}</span>
                          <span className="camera-item-name">{cam.name}</span>
                        </div>
                        <div className="camera-item-meta">
                          <span className={`status-dot ${cam.operationalStatus === 'ONLINE' ? 'online' : 'offline'}`} />
                          <ArrowLeft size={16} className="camera-item-arrow" />
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="camera-list__empty">
                    <Search size={24} className="camera-list__empty-icon" />
                    <p className="camera-list__empty-text">
                      {searchAssigned.trim()
                        ? 'Không tìm thấy camera khớp từ khoá.'
                        : 'Chưa có camera nào được gán cho khu vực này.'}
                    </p>
                  </div>
                )}
              </div>
            </div>
          </div>

          <div className="area-save-footer">
            <button type="button" className="btn-save-area-cameras" onClick={handleSave} disabled={saving}>
              {saving ? <Loader2 size={18} className="animate-spin" /> : <Save size={18} />}
              <span>{saving ? 'Đang lưu...' : 'Lưu Thay Đổi Liên Kết'}</span>
            </button>
          </div>
        </>
      )}
    </div>
  );
}
