import { useState, useEffect, useMemo, useCallback } from 'react';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import {
  getAreas,
  getAreaLevels,
  getDependencies,
  createArea,
  updateArea,
  deactivateArea,
  createTemporaryUsage,
} from '../services/areaService';
import {
  getLevelConfig,
  getErrorMessage,
  formatToOffsetDateTime,
} from '../utils/areaHelpers';
import './AreaListPage.css';

export default function AreaListPage() {
  const { user } = useAuth();
  const { theme, toggleTheme } = useTheme();

  const isAdmin = user?.role === 'ADMIN';

  // Data states
  const [areas, setAreas] = useState([]);
  const [areaLevels, setAreaLevels] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selectedArea, setSelectedArea] = useState(null);
  const [selectedFloor, setSelectedFloor] = useState('1');
  const [zoomLevel, setZoomLevel] = useState(1);
  const [toast, setToast] = useState(null);

  // Modal states
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [deactivateModalOpen, setDeactivateModalOpen] = useState(false);
  const [tempUsageModalOpen, setTempUsageModalOpen] = useState(false);

  const [modalLoading, setModalLoading] = useState(false);
  const [modalError, setModalError] = useState(null);
  const [dependencies, setDependencies] = useState(null);

  // Form states
  const [formData, setFormData] = useState({
    code: '',
    name: '',
    areaLevel: 1,
    building: 'A',
    floor: '1',
    description: '',
    mapX: '',
    mapY: '',
    reason: '',
  });

  const [tempUsageData, setTempUsageData] = useState({
    eventName: '',
    startTime: '',
    endTime: '',
    reason: '',
  });

  // Show Toast helper
  const showToast = (message) => {
    setToast(message);
    setTimeout(() => {
      setToast(null);
    }, 4000);
  };

  // Fetch Areas and Levels
  const fetchData = useCallback(async (keepSelectedId = null) => {
    try {
      setLoading(true);
      const [areasRes, levelsRes] = await Promise.all([
        getAreas({ size: 100, isActive: true }),
        getAreaLevels(),
      ]);

      const areaList = areasRes?.content || areasRes || [];
      setAreas(areaList);
      setAreaLevels(levelsRes || []);

      if (keepSelectedId) {
        const found = areaList.find((a) => a.id === keepSelectedId);
        if (found) setSelectedArea(found);
      }
    } catch (err) {
      console.error('Error loading areas:', err);
      showToast(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Compute unique floors
  const availableFloors = useMemo(() => {
    const floorSet = new Set();
    areas.forEach((a) => {
      if (a.floor) floorSet.add(a.floor.toString());
    });
    if (floorSet.size === 0) {
      return ['1', '2'];
    }
    return Array.from(floorSet).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  }, [areas]);

  // Set default floor on load if current not in available
  useEffect(() => {
    if (availableFloors.length > 0 && !availableFloors.includes(selectedFloor)) {
      setSelectedFloor(availableFloors[0]);
    }
  }, [availableFloors, selectedFloor]);

  // Filtered areas on current floor
  const floorAreas = useMemo(() => {
    return areas.filter((a) => (a.floor ? a.floor.toString() === selectedFloor : selectedFloor === '1'));
  }, [areas, selectedFloor]);

  // Summary Metrics
  const totalZonesCount = areas.length;
  const highSecurityCount = useMemo(() => {
    return areas.filter((a) => a.level?.level === 3 || a.level?.code === 'PRIVATE').length;
  }, [areas]);

  // Open Create Modal
  const handleOpenCreateModal = () => {
    setFormData({
      code: '',
      name: '',
      areaLevel: areaLevels[0]?.level || 1,
      building: 'A',
      floor: selectedFloor || '1',
      description: '',
      mapX: '',
      mapY: '',
      reason: '',
    });
    setModalError(null);
    setCreateModalOpen(true);
  };

  // Submit Create
  const handleCreateSubmit = async (e) => {
    e.preventDefault();
    setModalError(null);

    // Coordinate validation (must be both or none)
    const hasX = formData.mapX !== '' && formData.mapX !== null;
    const hasY = formData.mapY !== '' && formData.mapY !== null;
    if ((hasX && !hasY) || (!hasX && hasY)) {
      setModalError('Tọa độ Map X và Map Y phải cùng có hoặc cùng để trống.');
      return;
    }

    setModalLoading(true);
    try {
      const payload = {
        code: formData.code.trim().toUpperCase(),
        name: formData.name.trim(),
        areaLevel: Number(formData.areaLevel),
        building: formData.building ? formData.building.trim() : null,
        floor: formData.floor ? formData.floor.trim() : null,
        description: formData.description ? formData.description.trim() : null,
        mapX: hasX ? Number(formData.mapX) : null,
        mapY: hasY ? Number(formData.mapY) : null,
      };

      const created = await createArea(payload);
      setCreateModalOpen(false);
      showToast(`Tạo thành công khu vực: ${created.name}`);
      await fetchData();
      setSelectedArea(created);
    } catch (err) {
      console.error('Create area failed:', err);
      setModalError(getErrorMessage(err));
    } finally {
      setModalLoading(false);
    }
  };

  // Open Edit Modal
  const handleOpenEditModal = () => {
    if (!selectedArea) return;
    setFormData({
      code: selectedArea.code,
      name: selectedArea.name,
      areaLevel: selectedArea.level?.level || 1,
      building: selectedArea.building || '',
      floor: selectedArea.floor || '',
      description: selectedArea.description || '',
      mapX: selectedArea.mapX !== null && selectedArea.mapX !== undefined ? selectedArea.mapX : '',
      mapY: selectedArea.mapY !== null && selectedArea.mapY !== undefined ? selectedArea.mapY : '',
      reason: '',
    });
    setModalError(null);
    setEditModalOpen(true);
  };

  // Submit Edit
  const handleEditSubmit = async (e) => {
    e.preventDefault();
    setModalError(null);

    const oldLevel = selectedArea.level?.level || 1;
    const newLevel = Number(formData.areaLevel);
    const isDowngrade = newLevel < oldLevel;

    if (isDowngrade && (!formData.reason || formData.reason.trim().length < 10 || formData.reason.trim().length > 255)) {
      setModalError('Khi hạ cấp độ an ninh, lý do là bắt buộc và phải từ 10 đến 255 ký tự.');
      return;
    }

    const hasX = formData.mapX !== '' && formData.mapX !== null;
    const hasY = formData.mapY !== '' && formData.mapY !== null;
    if ((hasX && !hasY) || (!hasX && hasY)) {
      setModalError('Tọa độ Map X và Map Y phải cùng có hoặc cùng để trống.');
      return;
    }

    setModalLoading(true);
    try {
      const payload = {
        code: selectedArea.code,
        name: formData.name.trim(),
        areaLevel: newLevel,
        building: formData.building ? formData.building.trim() : null,
        floor: formData.floor ? formData.floor.trim() : null,
        description: formData.description ? formData.description.trim() : null,
        mapX: hasX ? Number(formData.mapX) : null,
        mapY: hasY ? Number(formData.mapY) : null,
        reason: isDowngrade ? formData.reason.trim() : null,
      };

      const updated = await updateArea(selectedArea.id, payload);
      setEditModalOpen(false);
      showToast(`Cập nhật thành công khu vực: ${updated.name}`);
      await fetchData();
      setSelectedArea(updated);
    } catch (err) {
      console.error('Update area failed:', err);
      setModalError(getErrorMessage(err));
    } finally {
      setModalLoading(false);
    }
  };

  // Open Deactivate Modal
  const handleOpenDeactivateModal = async () => {
    if (!selectedArea) return;
    setModalError(null);
    setDependencies(null);
    setDeactivateModalOpen(true);
    setModalLoading(true);

    try {
      const depRes = await getDependencies(selectedArea.id);
      setDependencies(depRes);
    } catch (err) {
      console.error('Failed to get area dependencies:', err);
      setModalError(getErrorMessage(err));
    } finally {
      setModalLoading(false);
    }
  };

  // Submit Deactivate
  const handleDeactivateSubmit = async () => {
    if (!selectedArea) return;
    setModalLoading(true);
    setModalError(null);

    try {
      await deactivateArea(selectedArea.id);
      setDeactivateModalOpen(false);
      showToast(`Đã vô hiệu hóa khu vực: ${selectedArea.name}`);
      setSelectedArea(null);
      await fetchData();
    } catch (err) {
      console.error('Deactivate area failed:', err);
      setModalError(getErrorMessage(err));
    } finally {
      setModalLoading(false);
    }
  };

  // Open Temporary Usage Modal
  const handleOpenTempUsageModal = () => {
    const now = new Date();
    const pad = (n) => (n < 10 ? '0' + n : n);
    const startStr = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours() + 1)}:00`;
    const endStr = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours() + 3)}:00`;

    setTempUsageData({
      eventName: '',
      startTime: startStr,
      endTime: endStr,
      reason: '',
    });
    setModalError(null);
    setTempUsageModalOpen(true);
  };

  // Submit Temporary Usage
  const handleTempUsageSubmit = async (e) => {
    e.preventDefault();
    setModalError(null);

    if (!tempUsageData.eventName || !tempUsageData.eventName.trim()) {
      setModalError('Tên sự kiện không được để trống.');
      return;
    }

    if (!tempUsageData.startTime || !tempUsageData.endTime) {
      setModalError('Vui lòng chọn đầy đủ thời gian bắt đầu và kết thúc.');
      return;
    }

    const startDate = new Date(tempUsageData.startTime);
    const endDate = new Date(tempUsageData.endTime);
    const nowDate = new Date();

    if (endDate <= startDate) {
      setModalError('Thời gian kết thúc phải sau thời gian bắt đầu.');
      return;
    }

    if (endDate <= nowDate) {
      setModalError('Thời gian kết thúc phải sau thời điểm hiện tại.');
      return;
    }

    setModalLoading(true);
    try {
      const payload = {
        eventName: tempUsageData.eventName.trim(),
        startTime: formatToOffsetDateTime(tempUsageData.startTime),
        endTime: formatToOffsetDateTime(tempUsageData.endTime),
        reason: tempUsageData.reason ? tempUsageData.reason.trim() : null,
      };

      await createTemporaryUsage(selectedArea.id, payload);
      setTempUsageModalOpen(false);
      showToast(`Đã tạo thời gian sử dụng tạm thời cho phòng ${selectedArea.name}`);
    } catch (err) {
      console.error('Create temporary usage failed:', err);
      setModalError(getErrorMessage(err));
    } finally {
      setModalLoading(false);
    }
  };

  // Selected level config
  const selectedLevelConfig = selectedArea ? getLevelConfig(selectedArea.level) : null;
  const isDowngradingInEdit =
    editModalOpen && selectedArea && Number(formData.areaLevel) < (selectedArea.level?.level || 1);

  return (
    <div className="area-dashboard">
      {/* Background Ambience */}
      <div className="area-ambient">
        <div className="area-ambient__orb area-ambient__orb--1" />
        <div className="area-ambient__orb area-ambient__orb--2" />
      </div>

      {/* ============================================================ */}
      {/* 2. CENTER: MAIN CAMPUS AREA MAP                              */}
      {/* ============================================================ */}
      <main className="area-main">
        <header className="area-header">
          <div className="area-header__title-group">
            <h1>Campus Area Map</h1>
            <p>Hệ thống giám sát phân vùng &amp; cấu hình bản đồ an ninh</p>
          </div>
          <button
            type="button"
            className="area-theme-toggle"
            onClick={toggleTheme}
            title={theme === 'light' ? 'Chuyển sang Dark Mode' : 'Chuyển sang Light Mode'}
          >
            {theme === 'light' ? (
              <>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
                </svg>
                <span>Dark Mode</span>
              </>
            ) : (
              <>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="5" />
                  <line x1="12" y1="1" x2="12" y2="3" />
                  <line x1="12" y1="21" x2="12" y2="23" />
                  <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                  <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                  <line x1="1" y1="12" x2="3" y2="12" />
                  <line x1="21" y1="12" x2="23" y2="12" />
                  <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                  <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
                </svg>
                <span>Light Mode</span>
              </>
            )}
          </button>
        </header>

        {/* Toolbar: Floor Tabs & Zoom */}
        <div className="area-toolbar">
          <div className="area-floor-tabs">
            {availableFloors.map((fl) => (
              <button
                key={fl}
                type="button"
                className={`area-floor-tab ${selectedFloor === fl ? 'area-floor-tab--active' : ''}`}
                onClick={() => setSelectedFloor(fl)}
              >
                Tầng {fl}
              </button>
            ))}
          </div>

          <div className="area-map-controls">
            <button
              type="button"
              className="area-control-btn"
              onClick={() => setZoomLevel((z) => Math.min(1.3, z + 0.1))}
              title="Phóng to"
            >
              +
            </button>
            <button
              type="button"
              className="area-control-btn"
              onClick={() => setZoomLevel((z) => Math.max(0.7, z - 0.1))}
              title="Thu nhỏ"
            >
              -
            </button>
          </div>
        </div>

        {/* Main Map Container */}
        <div
          className="area-canvas"
          style={{ transform: `scale(${zoomLevel})`, transformOrigin: 'top left' }}
        >
          {loading ? (
            <div className="area-empty-state">
              <div className="area-empty-state__icon">
                <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="login-card__spinner">
                  <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                </svg>
              </div>
              <div className="area-empty-state__title">Đang tải dữ liệu khu vực...</div>
            </div>
          ) : floorAreas.length === 0 ? (
            <div className="area-empty-state">
              <div className="area-empty-state__icon">
                <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
              </div>
              <div className="area-empty-state__title">No zones configured for this floor.</div>
              <div className="area-empty-state__desc">
                Chưa có khu vực nào được cấu hình trên Tầng {selectedFloor}.
              </div>
              {isAdmin && (
                <button type="button" className="zone-btn zone-btn--primary" style={{ width: 'auto' }} onClick={handleOpenCreateModal}>
                  + Create your first zone
                </button>
              )}
            </div>
          ) : (
            <div className="area-grid">
              {floorAreas.map((area) => {
                const isSelected = selectedArea?.id === area.id;
                const levelConfig = getLevelConfig(area.level);

                return (
                  <div
                    key={area.id}
                    className={`zone-card ${levelConfig.cardClass} ${isSelected ? 'zone-card--selected' : ''}`}
                    onClick={() => setSelectedArea(area)}
                  >
                    <div className="zone-card__header">
                      <span className={`level-badge ${levelConfig.badgeClass}`}>
                        {levelConfig.badgeLabel}
                      </span>
                      <span className={`zone-card__status-dot ${area.isActive ? '' : 'zone-card__status-dot--inactive'}`} title={area.isActive ? 'Active' : 'Inactive'} />
                    </div>

                    <h3 className="zone-card__title">{area.name}</h3>

                    <div className="zone-card__footer">
                      <span className="zone-card__code">{area.code}</span>
                      <span className="zone-card__location">
                        {area.building ? `Tòa ${area.building}` : ''} {area.floor ? `· Tầng ${area.floor}` : ''}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </main>

      {/* ============================================================ */}
      {/* 3. RIGHT: SECURITY SUMMARY & ZONE DETAILS                    */}
      {/* ============================================================ */}
      <aside className="area-panel-right">
        {/* Security Summary Cards */}
        <div className="area-summary-cards">
          <div className="area-summary-card area-summary-card--alert">
            <span className="area-summary-card__label">High Security (L3)</span>
            <span className="area-summary-card__value">{highSecurityCount}</span>
          </div>

          <div className="area-summary-card area-summary-card--success">
            <span className="area-summary-card__label">Total Zones</span>
            <span className="area-summary-card__value">{totalZonesCount}</span>
          </div>

          <div className="area-summary-card">
            <span className="area-summary-card__label">Active Incidents</span>
            <span className="area-summary-card__value">0</span>
          </div>
        </div>

        {/* Zone Details Card */}
        <div className="zone-details">
          {!selectedArea ? (
            <div className="area-empty-state" style={{ padding: '20px 0' }}>
              <div className="area-empty-state__icon" style={{ width: '44px', height: '44px' }}>
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
                  <circle cx="12" cy="10" r="3" />
                </svg>
              </div>
              <div className="area-empty-state__title" style={{ fontSize: '0.95rem' }}>Zone Details</div>
              <div className="area-empty-state__desc" style={{ fontSize: '0.775rem' }}>
                Click vào một vùng trên bản đồ để xem cấu hình chi tiết
              </div>
            </div>
          ) : (
            <>
              <div className="zone-details__header">
                <div className="zone-details__title-group">
                  <h2 className="zone-details__title">{selectedArea.name}</h2>
                  <span className="zone-details__code">{selectedArea.code}</span>
                </div>
                <span className={`level-badge ${selectedLevelConfig?.badgeClass}`}>
                  {selectedLevelConfig?.badgeLabel}
                </span>
              </div>

              <div className="zone-details__rows">
                <div className="zone-details__row">
                  <span className="zone-details__label">Trạng thái</span>
                  <span className="zone-details__val" style={{ color: selectedArea.isActive ? 'var(--theme-active-dot)' : 'var(--theme-inactive-dot)' }}>
                    {selectedArea.isActive ? '● Đang hoạt động' : '○ Đã vô hiệu hóa'}
                  </span>
                </div>

                <div className="zone-details__row">
                  <span className="zone-details__label">Vị trí</span>
                  <span className="zone-details__val">
                    Tòa {selectedArea.building || '—'}, Tầng {selectedArea.floor || '—'}
                  </span>
                </div>

                <div className="zone-details__row">
                  <span className="zone-details__label">Tọa độ Map</span>
                  <span className="zone-details__val">
                    {selectedArea.mapX != null && selectedArea.mapY != null
                      ? `X: ${selectedArea.mapX}, Y: ${selectedArea.mapY}`
                      : 'Chưa cấu hình'}
                  </span>
                </div>

                <div className="zone-details__row">
                  <span className="zone-details__label">Nhận diện khuôn mặt</span>
                  <span className="zone-details__val">
                    {selectedArea.level?.requiresFaceRecognition ? 'Bắt buộc (L3)' : 'Không'}
                  </span>
                </div>

                <div className="zone-details__row">
                  <span className="zone-details__label">Mô tả</span>
                </div>
                <div className="zone-details__desc-box">
                  {selectedArea.description || 'Không có mô tả chi tiết cho khu vực này.'}
                </div>
              </div>

              {/* ADMIN Actions for Selected Area */}
              {isAdmin && (
                <div className="zone-details__actions">
                  <button type="button" className="zone-btn zone-btn--outline" onClick={handleOpenEditModal}>
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                    </svg>
                    <span>Edit Zone</span>
                  </button>

                  <button type="button" className="zone-btn zone-btn--destructive" onClick={handleOpenDeactivateModal}>
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <polyline points="3 6 5 6 21 6" />
                      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                    </svg>
                    <span>Deactivate Zone</span>
                  </button>
                </div>
              )}

              {/* Temporary Event Usage Section */}
              <div className="zone-temp-usage">
                <div className="zone-temp-usage__header">
                  <span className="zone-temp-usage__title">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
                      <line x1="16" y1="2" x2="16" y2="6" />
                      <line x1="8" y1="2" x2="8" y2="6" />
                      <line x1="3" y1="10" x2="21" y2="10" />
                    </svg>
                    Temporary Event Usage
                  </span>
                </div>

                {isAdmin && (
                  <button
                    type="button"
                    className="zone-btn zone-btn--primary"
                    style={{ fontSize: '0.8rem', padding: '8px 12px' }}
                    onClick={handleOpenTempUsageModal}
                  >
                    + Temporary Usage
                  </button>
                )}

                <div className="zone-temp-usage__notice">
                  💡 Extend action requires active temporary usage instance. (Backend GET Temporary Usage list endpoint is in development).
                </div>
              </div>
            </>
          )}
        </div>

        {/* Global Action: Add New Zone (ADMIN Only) */}
        {isAdmin && (
          <button type="button" className="zone-btn zone-btn--primary" onClick={handleOpenCreateModal}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
            <span>+ Thêm vùng mới</span>
          </button>
        )}
      </aside>

      {/* ============================================================ */}
      {/* 4. MODALS                                                    */}
      {/* ============================================================ */}

      {/* CREATE ZONE MODAL */}
      {createModalOpen && (
        <div className="area-modal-backdrop" onClick={() => setCreateModalOpen(false)}>
          <div className="area-modal" onClick={(e) => e.stopPropagation()}>
            <div className="area-modal__header">
              <h3 className="area-modal__title">Add New Zone</h3>
              <button type="button" className="area-modal__close-btn" onClick={() => setCreateModalOpen(false)}>✕</button>
            </div>

            <form onSubmit={handleCreateSubmit}>
              <div className="area-modal__body">
                {modalError && (
                  <div className="modal-error-box">
                    <span>⚠️ {modalError}</span>
                  </div>
                )}

                <div className="modal-form-group">
                  <label className="modal-label">
                    Zone Code <span className="modal-label__req">*</span>
                  </label>
                  <input
                    type="text"
                    required
                    placeholder="VD: HALL-A01"
                    className="modal-input"
                    value={formData.code}
                    onChange={(e) => setFormData({ ...formData, code: e.target.value.toUpperCase() })}
                    disabled={modalLoading}
                  />
                </div>

                <div className="modal-form-group">
                  <label className="modal-label">
                    Zone Name <span className="modal-label__req">*</span>
                  </label>
                  <input
                    type="text"
                    required
                    placeholder="VD: Hội trường A01"
                    className="modal-input"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    disabled={modalLoading}
                  />
                </div>

                <div className="modal-form-group">
                  <label className="modal-label">
                    Security Level <span className="modal-label__req">*</span>
                  </label>
                  <select
                    className="modal-select"
                    value={formData.areaLevel}
                    onChange={(e) => setFormData({ ...formData, areaLevel: e.target.value })}
                    disabled={modalLoading}
                  >
                    {areaLevels.map((lvl) => (
                      <option key={lvl.level} value={lvl.level}>
                        Level {lvl.level} — {lvl.name} ({lvl.code})
                      </option>
                    ))}
                  </select>
                </div>

                <div className="modal-form-row">
                  <div className="modal-form-group">
                    <label className="modal-label">Building</label>
                    <input
                      type="text"
                      placeholder="VD: A"
                      className="modal-input"
                      value={formData.building}
                      onChange={(e) => setFormData({ ...formData, building: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                  <div className="modal-form-group">
                    <label className="modal-label">Floor</label>
                    <input
                      type="text"
                      placeholder="VD: 1"
                      className="modal-input"
                      value={formData.floor}
                      onChange={(e) => setFormData({ ...formData, floor: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                </div>

                <div className="modal-form-row">
                  <div className="modal-form-group">
                    <label className="modal-label">Map X</label>
                    <input
                      type="number"
                      step="any"
                      placeholder="VD: 120"
                      className="modal-input"
                      value={formData.mapX}
                      onChange={(e) => setFormData({ ...formData, mapX: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                  <div className="modal-form-group">
                    <label className="modal-label">Map Y</label>
                    <input
                      type="number"
                      step="any"
                      placeholder="VD: 80"
                      className="modal-input"
                      value={formData.mapY}
                      onChange={(e) => setFormData({ ...formData, mapY: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                </div>

                <div className="modal-form-group">
                  <label className="modal-label">Description</label>
                  <textarea
                    placeholder="Mô tả chức năng hoặc ghi chú của khu vực..."
                    className="modal-textarea"
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    disabled={modalLoading}
                  />
                </div>
              </div>

              <div className="area-modal__footer">
                <button
                  type="button"
                  className="zone-btn zone-btn--outline"
                  style={{ width: 'auto' }}
                  onClick={() => setCreateModalOpen(false)}
                  disabled={modalLoading}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="zone-btn zone-btn--primary"
                  style={{ width: 'auto' }}
                  disabled={modalLoading}
                >
                  {modalLoading ? 'Creating...' : 'Create Zone'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* EDIT ZONE MODAL */}
      {editModalOpen && selectedArea && (
        <div className="area-modal-backdrop" onClick={() => setEditModalOpen(false)}>
          <div className="area-modal" onClick={(e) => e.stopPropagation()}>
            <div className="area-modal__header">
              <h3 className="area-modal__title">Edit Zone: {selectedArea.name}</h3>
              <button type="button" className="area-modal__close-btn" onClick={() => setEditModalOpen(false)}>✕</button>
            </div>

            <form onSubmit={handleEditSubmit}>
              <div className="area-modal__body">
                {modalError && (
                  <div className="modal-error-box">
                    <span>⚠️ {modalError}</span>
                  </div>
                )}

                <div className="modal-form-group">
                  <label className="modal-label">Zone Code (Read-only)</label>
                  <input
                    type="text"
                    disabled
                    className="modal-input"
                    value={formData.code}
                  />
                </div>

                <div className="modal-form-group">
                  <label className="modal-label">
                    Zone Name <span className="modal-label__req">*</span>
                  </label>
                  <input
                    type="text"
                    required
                    className="modal-input"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    disabled={modalLoading}
                  />
                </div>

                <div className="modal-form-group">
                  <label className="modal-label">
                    Security Level <span className="modal-label__req">*</span>
                  </label>
                  <select
                    className="modal-select"
                    value={formData.areaLevel}
                    onChange={(e) => setFormData({ ...formData, areaLevel: e.target.value })}
                    disabled={modalLoading}
                  >
                    {areaLevels.map((lvl) => (
                      <option key={lvl.level} value={lvl.level}>
                        Level {lvl.level} — {lvl.name} ({lvl.code})
                      </option>
                    ))}
                  </select>
                </div>

                {/* Conditional Reason for Security Downgrade */}
                {isDowngradingInEdit && (
                  <div className="modal-form-group">
                    <label className="modal-label" style={{ color: 'var(--theme-level-semi-text)' }}>
                      Reason for security level change <span className="modal-label__req">*</span> (10–255 ký tự)
                    </label>
                    <textarea
                      required
                      placeholder="Bắt buộc nhập lý do khi hạ cấp độ an ninh của khu vực..."
                      className="modal-textarea"
                      style={{ borderColor: 'var(--theme-level-semi-border)' }}
                      value={formData.reason}
                      onChange={(e) => setFormData({ ...formData, reason: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                )}

                <div className="modal-form-row">
                  <div className="modal-form-group">
                    <label className="modal-label">Building</label>
                    <input
                      type="text"
                      className="modal-input"
                      value={formData.building}
                      onChange={(e) => setFormData({ ...formData, building: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                  <div className="modal-form-group">
                    <label className="modal-label">Floor</label>
                    <input
                      type="text"
                      className="modal-input"
                      value={formData.floor}
                      onChange={(e) => setFormData({ ...formData, floor: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                </div>

                <div className="modal-form-row">
                  <div className="modal-form-group">
                    <label className="modal-label">Map X</label>
                    <input
                      type="number"
                      step="any"
                      className="modal-input"
                      value={formData.mapX}
                      onChange={(e) => setFormData({ ...formData, mapX: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                  <div className="modal-form-group">
                    <label className="modal-label">Map Y</label>
                    <input
                      type="number"
                      step="any"
                      className="modal-input"
                      value={formData.mapY}
                      onChange={(e) => setFormData({ ...formData, mapY: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                </div>

                <div className="modal-form-group">
                  <label className="modal-label">Description</label>
                  <textarea
                    className="modal-textarea"
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    disabled={modalLoading}
                  />
                </div>
              </div>

              <div className="area-modal__footer">
                <button
                  type="button"
                  className="zone-btn zone-btn--outline"
                  style={{ width: 'auto' }}
                  onClick={() => setEditModalOpen(false)}
                  disabled={modalLoading}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="zone-btn zone-btn--primary"
                  style={{ width: 'auto' }}
                  disabled={modalLoading}
                >
                  {modalLoading ? 'Saving...' : 'Save Changes'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* DEACTIVATE CONFIRMATION MODAL */}
      {deactivateModalOpen && selectedArea && (
        <div className="area-modal-backdrop" onClick={() => setDeactivateModalOpen(false)}>
          <div className="area-modal" onClick={(e) => e.stopPropagation()}>
            <div className="area-modal__header">
              <h3 className="area-modal__title" style={{ color: 'var(--theme-level-priv-text)' }}>Deactivate Zone</h3>
              <button type="button" className="area-modal__close-btn" onClick={() => setDeactivateModalOpen(false)}>✕</button>
            </div>

            <div className="area-modal__body">
              {modalError && (
                <div className="modal-error-box">
                  <span>⚠️ {modalError}</span>
                </div>
              )}

              <p style={{ fontSize: '0.9rem', color: 'var(--theme-text-primary)', lineHeight: 1.5, margin: 0 }}>
                Bạn có chắc chắn muốn vô hiệu hóa khu vực <strong>{selectedArea.name}</strong> ({selectedArea.code}) không?
              </p>

              {dependencies?.blockers && dependencies.blockers.length > 0 && (
                <div className="modal-error-box">
                  <div>
                    <strong>Không thể vô hiệu hóa do có ràng buộc:</strong>
                    <ul style={{ margin: '6px 0 0 16px', padding: 0 }}>
                      {dependencies.blockers.map((b, idx) => (
                        <li key={idx}>{b.message || b.errorCode}</li>
                      ))}
                    </ul>
                  </div>
                </div>
              )}

              {dependencies?.warnings && dependencies.warnings.length > 0 && (
                <div className="modal-warning-box">
                  <div>
                    <strong>Cảnh báo:</strong>
                    <ul style={{ margin: '6px 0 0 16px', padding: 0 }}>
                      {dependencies.warnings.map((w, idx) => (
                        <li key={idx}>{w}</li>
                      ))}
                    </ul>
                  </div>
                </div>
              )}

              {dependencies?.note && (
                <p style={{ fontSize: '0.8rem', color: '#94a3b8', fontStyle: 'italic', margin: 0 }}>
                  {dependencies.note}
                </p>
              )}
            </div>

            <div className="area-modal__footer">
              <button
                type="button"
                className="zone-btn zone-btn--outline"
                style={{ width: 'auto' }}
                onClick={() => setDeactivateModalOpen(false)}
                disabled={modalLoading}
              >
                Cancel
              </button>
              <button
                type="button"
                className="zone-btn zone-btn--destructive"
                style={{ width: 'auto' }}
                onClick={handleDeactivateSubmit}
                disabled={modalLoading || (dependencies?.blockers && dependencies.blockers.length > 0)}
              >
                {modalLoading ? 'Deactivating...' : 'Confirm Deactivate'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* CREATE TEMPORARY USAGE MODAL */}
      {tempUsageModalOpen && selectedArea && (
        <div className="area-modal-backdrop" onClick={() => setTempUsageModalOpen(false)}>
          <div className="area-modal" onClick={(e) => e.stopPropagation()}>
            <div className="area-modal__header">
              <h3 className="area-modal__title">Create Temporary Usage</h3>
              <button type="button" className="area-modal__close-btn" onClick={() => setTempUsageModalOpen(false)}>✕</button>
            </div>

            <form onSubmit={handleTempUsageSubmit}>
              <div className="area-modal__body">
                {modalError && (
                  <div className="modal-error-box">
                    <span>⚠️ {modalError}</span>
                  </div>
                )}

                <div className="modal-form-group">
                  <label className="modal-label">Zone</label>
                  <input
                    type="text"
                    disabled
                    className="modal-input"
                    value={`${selectedArea.name} (${selectedArea.code})`}
                  />
                </div>

                <div className="modal-form-group">
                  <label className="modal-label">
                    Event Name <span className="modal-label__req">*</span>
                  </label>
                  <input
                    type="text"
                    required
                    placeholder="VD: Hội thảo Công nghệ AI"
                    className="modal-input"
                    value={tempUsageData.eventName}
                    onChange={(e) => setTempUsageData({ ...tempUsageData, eventName: e.target.value })}
                    disabled={modalLoading}
                  />
                </div>

                <div className="modal-form-row">
                  <div className="modal-form-group">
                    <label className="modal-label">
                      Start Time <span className="modal-label__req">*</span>
                    </label>
                    <input
                      type="datetime-local"
                      required
                      className="modal-input"
                      value={tempUsageData.startTime}
                      onChange={(e) => setTempUsageData({ ...tempUsageData, startTime: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>

                  <div className="modal-form-group">
                    <label className="modal-label">
                      End Time <span className="modal-label__req">*</span>
                    </label>
                    <input
                      type="datetime-local"
                      required
                      className="modal-input"
                      value={tempUsageData.endTime}
                      onChange={(e) => setTempUsageData({ ...tempUsageData, endTime: e.target.value })}
                      disabled={modalLoading}
                    />
                  </div>
                </div>

                <div className="modal-form-group">
                  <label className="modal-label">Reason / Ghi chú</label>
                  <textarea
                    placeholder="Lý do cấp quyền sử dụng phòng tạm thời..."
                    className="modal-textarea"
                    value={tempUsageData.reason}
                    onChange={(e) => setTempUsageData({ ...tempUsageData, reason: e.target.value })}
                    disabled={modalLoading}
                  />
                </div>
              </div>

              <div className="area-modal__footer">
                <button
                  type="button"
                  className="zone-btn zone-btn--outline"
                  style={{ width: 'auto' }}
                  onClick={() => setTempUsageModalOpen(false)}
                  disabled={modalLoading}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="zone-btn zone-btn--primary"
                  style={{ width: 'auto' }}
                  disabled={modalLoading}
                >
                  {modalLoading ? 'Creating...' : 'Create Temporary Usage'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Toast Notification */}
      {toast && (
        <div className="area-toast">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
            <polyline points="22 4 12 14.01 9 11.01" />
          </svg>
          <span>{toast}</span>
        </div>
      )}
    </div>
  );
}
