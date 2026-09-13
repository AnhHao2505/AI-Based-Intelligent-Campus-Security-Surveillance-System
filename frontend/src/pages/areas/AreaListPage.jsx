import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import {
  Building2,
  Map as MapIcon,
  List as ListIcon,
  Plus,
  Pencil,
  Trash2,
  Check,
  EyeOff,
  AlertCircle,
  X,
  Loader2,
  Undo2,
  Layers,
  Cctv,
  VideoOff,
  MapPinPlus,
} from 'lucide-react';
import {
  getAreas,
  getDependencies,
  createArea,
  updateArea,
  deactivateArea,
  getFloorPlans,
  saveAreaGeometry,
  deleteAreaGeometry,
  getAreaCameras,
} from '../../services/areaService';
import {
  AREA_LEVEL_CONFIG,
  getLevelConfig,
  getErrorMessage,
} from '../../utils/areaHelpers';
import '../../styles/AreaListPage.css';

const EPS = 0.0005;
const round6 = (n) => Math.round(n * 1e6) / 1e6;

const GEOMETRY_ERROR_MESSAGES = {
  ERR_AREA_001: 'Mã khu vực đã tồn tại trong hệ thống.',
  ERR_AREA_002: 'Mã khu vực không đúng định dạng cho phép.',
  ERR_AREA_003: 'Không tìm thấy khu vực được yêu cầu.',
  ERR_AREA_004: 'Khu vực này hiện đang có camera hoặc dữ liệu liên kết, không thể xoá.',
  ERR_AREA_005: 'Khu vực này hiện đang bị vô hiệu hoá.',
  ERR_AREA_006: 'Khu vực này hiện đã ở trạng thái hoạt động.',
  ERR_AREA_007: 'Lý do giải trình không được để trống khi hạ cấp an ninh.',
  ERR_AREA_008: 'Dữ liệu toạ độ hình đa giác không hợp lệ.',
  ERR_AREA_009: 'Hình đa giác phải có từ 3 đến 20 đỉnh.',
  ERR_AREA_010: 'Toạ độ các đỉnh phải nằm trong khoảng chuẩn hoá [0.0, 1.0].',
  ERR_AREA_011: 'Các cạnh của hình đa giác không được cắt nhau.',
  ERR_AREA_012: 'Khu vực này đã được vẽ hình trên bản đồ.',
  ERR_AREA_013: 'Hình bị chồng lấn với khu vực khác trên cùng tầng.',
  ERR_AREA_015: 'Khu vực này chưa có thông tin toà nhà và tầng.',
  ERR_AREA_016: 'Hình phải có ít nhất 3 đỉnh khác nhau.',
};

const AREA_LEVEL_CARDS = [
  { value: 'PUBLIC', name: 'Công cộng', level: 'Level 1', color: '#22c55e' },
  { value: 'SEMI_PRIVATE', name: 'Hạn chế', level: 'Level 2', color: '#fbbf24' },
  { value: 'PRIVATE', name: 'Riêng tư', level: 'Level 3', color: '#f87171' },
];

export default function AreaListPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';

  const [searchParams, setSearchParams] = useSearchParams();
  const viewMode = searchParams.get('view') === 'list' ? 'list' : 'map';

  const handleToggleView = (mode) => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('view', mode);
      return next;
    });
  };

  // Data states
  const [areas, setAreas] = useState([]);
  const [floorPlans, setFloorPlans] = useState([]);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState(null);
  const [imageError, setImageError] = useState(false);

  // Filters
  const [selectedBuilding, setSelectedBuilding] = useState('');
  const [selectedFloor, setSelectedFloor] = useState('');
  const [selectedAreaId, setSelectedAreaId] = useState(null);
  const [cameraCounts, setCameraCounts] = useState({});

  // Drawing states
  const [drawingAreaId, setDrawingAreaId] = useState(null);
  const [draftVertices, setDraftVertices] = useState([]);
  const [drawError, setDrawError] = useState(null);
  const [savingGeometry, setSavingGeometry] = useState(false);
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);
  const [deletingGeometryId, setDeletingGeometryId] = useState(null);

  // Modal states
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [deactivateModalOpen, setDeactivateModalOpen] = useState(false);
  const [modalLoading, setModalLoading] = useState(false);
  const [modalError, setModalError] = useState(null);
  const [dependencies, setDependencies] = useState(null);

  // Form states
  const [formData, setFormData] = useState({
    code: '',
    name: '',
    areaLevel: 'PUBLIC',
    building: 'FPT_AROUND',
    floor: 'G',
    description: '',
    reason: '',
  });

  const rowRefs = useRef({});

  // Fetch all data
  const fetchData = useCallback(async (keepSelectedId = null) => {
    setLoading(true);
    setPageError(null);
    setImageError(false);
    try {
      const [areasRes, plansRes] = await Promise.all([
        getAreas({ size: 100, isActive: true }),
        getFloorPlans().catch(() => []),
      ]);

      const areaList = areasRes?.content || areasRes || [];
      setAreas(Array.isArray(areaList) ? areaList : []);

      const activePlans = (plansRes || []).filter((fp) => fp.isActive !== false);
      setFloorPlans(activePlans);

      if (keepSelectedId) {
        setSelectedAreaId(keepSelectedId);
      }
    } catch (err) {
      console.error('Error loading area data:', err);
      setPageError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Derived available buildings
  const availableBuildings = useMemo(() => {
    const bSet = new Set();
    floorPlans.forEach((fp) => { if (fp.building) bSet.add(fp.building); });
    areas.forEach((a) => { if (a.building) bSet.add(a.building); });
    const list = Array.from(bSet).sort();
    return list.length > 0 ? list : ['FPT_AROUND'];
  }, [floorPlans, areas]);

  // Default building initialization
  useEffect(() => {
    if (!selectedBuilding && availableBuildings.length > 0) {
      if (availableBuildings.includes('FPT_AROUND')) {
        setSelectedBuilding('FPT_AROUND');
      } else {
        setSelectedBuilding(availableBuildings[0]);
      }
    }
  }, [availableBuildings, selectedBuilding]);

  // Derived available floors for current building
  const availableFloors = useMemo(() => {
    const fSet = new Set();
    floorPlans
      .filter((fp) => fp.building === selectedBuilding)
      .forEach((fp) => { if (fp.floor) fSet.add(fp.floor); });
    areas
      .filter((a) => a.building === selectedBuilding)
      .forEach((a) => { if (a.floor) fSet.add(a.floor); });

    return Array.from(fSet).sort((a, b) => {
      const isNumA = /^\d+$/.test(a);
      const isNumB = /^\d+$/.test(b);
      if (!isNumA && isNumB) return -1;
      if (isNumA && !isNumB) return 1;
      if (!isNumA && !isNumB) return a.localeCompare(b);
      return parseInt(a, 10) - parseInt(b, 10);
    });
  }, [floorPlans, areas, selectedBuilding]);

  // Default floor initialization
  useEffect(() => {
    if (availableFloors.length > 0 && !availableFloors.includes(selectedFloor)) {
      setSelectedFloor(availableFloors[0]);
    }
  }, [availableFloors, selectedFloor]);

  // Current floor plan matching building & floor
  const selectedPlan = useMemo(() => {
    return (
      floorPlans.find(
        (fp) => fp.building === selectedBuilding && fp.floor === selectedFloor
      ) || null
    );
  }, [floorPlans, selectedBuilding, selectedFloor]);

  // Filtered areas on current building and floor (for list view)
  const floorAreas = useMemo(() => {
    return areas.filter(
      (a) => a.building === selectedBuilding && a.floor === selectedFloor
    );
  }, [areas, selectedBuilding, selectedFloor]);

  // Selected area object
  const selectedArea = useMemo(() => {
    return areas.find((a) => a.id === selectedAreaId) || null;
  }, [areas, selectedAreaId]);

  // Fetch camera count for selected area if not fetched
  useEffect(() => {
    if (!selectedAreaId) return;
    if (cameraCounts[selectedAreaId] !== undefined) return;

    let active = true;
    getAreaCameras(selectedAreaId)
      .then((res) => {
        if (active) {
          const count = res?.cameras?.length ?? 0;
          setCameraCounts((prev) => ({ ...prev, [selectedAreaId]: count }));
        }
      })
      .catch(() => {
        if (active) {
          setCameraCounts((prev) => ({ ...prev, [selectedAreaId]: 0 }));
        }
      });

    return () => { active = false; };
  }, [selectedAreaId, cameraCounts]);

  // Handle switching building or floor
  const handleSelectBuilding = (b) => {
    setSelectedBuilding(b);
    setSelectedAreaId(null);
    if (drawingAreaId !== null) cancelDrawing();
    setConfirmDeleteId(null);
    setImageError(false);
  };

  const handleSelectFloor = (fl) => {
    setSelectedFloor(fl);
    setSelectedAreaId(null);
    if (drawingAreaId !== null) cancelDrawing();
    setConfirmDeleteId(null);
    setImageError(false);
  };

  // Bidirectional selection
  const handleSelectArea = (areaId, fromMap = false) => {
    setSelectedAreaId(areaId);
    setConfirmDeleteId(null);
    if (fromMap && rowRefs.current[areaId]) {
      rowRefs.current[areaId].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  };

  // Polygon drawing logic
  const startDrawing = (areaId) => {
    setDrawingAreaId(areaId);
    setDraftVertices([]);
    setDrawError(null);
    setConfirmDeleteId(null);
  };

  const cancelDrawing = () => {
    setDrawingAreaId(null);
    setDraftVertices([]);
    setDrawError(null);
  };

  const handleUndoVertex = () => {
    setDraftVertices((prev) => prev.slice(0, -1));
  };

  const finishDrawing = async () => {
    if (savingGeometry || drawingAreaId === null || draftVertices.length < 3) return;
    setSavingGeometry(true);
    setDrawError(null);
    try {
      await saveAreaGeometry(drawingAreaId, draftVertices);
      const targetId = drawingAreaId;
      cancelDrawing();
      await fetchData(targetId);
    } catch (err) {
      const msg =
        GEOMETRY_ERROR_MESSAGES[err.code] ||
        err.message ||
        'Không lưu được hình. Vui lòng thử lại.';
      setDrawError(msg);
    } finally {
      setSavingGeometry(false);
    }
  };

  const handleDeleteGeometry = async (areaId) => {
    if (deletingGeometryId !== null) return;
    setDeletingGeometryId(areaId);
    try {
      await deleteAreaGeometry(areaId);
      setConfirmDeleteId(null);
      await fetchData(areaId);
    } catch (err) {
      console.error('Failed to delete area geometry:', err);
      setPageError('Không xoá được hình đa giác. Vui lòng thử lại.');
    } finally {
      setDeletingGeometryId(null);
    }
  };

  const handleSvgClick = (event) => {
    if (savingGeometry || drawingAreaId === null || !selectedPlan) return;
    const svg = event.currentTarget;
    const ctm = svg.getScreenCTM();
    if (!ctm) return;
    const pt = svg.createSVGPoint();
    pt.x = event.clientX;
    pt.y = event.clientY;
    const local = pt.matrixTransform(ctm.inverse());
    const nx = local.x / selectedPlan.originalWidth;
    const ny = local.y / selectedPlan.originalHeight;
    if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return;
    const roundedX = round6(nx);
    const roundedY = round6(ny);
    const isDuplicate = draftVertices.some(
      (v) => Math.abs(v.x - roundedX) < EPS && Math.abs(v.y - roundedY) < EPS
    );
    if (isDuplicate) return;
    setDraftVertices((prev) => [...prev, { x: roundedX, y: roundedY }]);
  };

  // Level Polygon Class
  const getLevelPolygonClass = (level) => {
    const key = typeof level === 'object' && level !== null ? (level.code || level.areaLevel || level.level) : level;
    if (key === 'PUBLIC' || key === 1 || key === '1') return 'zone-polygon--public';
    if (key === 'SEMI_PRIVATE' || key === 2 || key === '2') return 'zone-polygon--semi';
    if (key === 'PRIVATE' || key === 3 || key === '3') return 'zone-polygon--private';
    return 'zone-polygon--default';
  };

  // Polygons for current floor plan
  const mapPolygons = useMemo(() => {
    if (!selectedPlan) return [];
    return areas.filter(
      (a) =>
        a.building === selectedBuilding &&
        a.floor === selectedFloor &&
        a.geometry &&
        Array.isArray(a.geometry.vertices) &&
        a.geometry.vertices.length >= 3
    );
  }, [areas, selectedBuilding, selectedFloor, selectedPlan]);

  // Sorted list for Card 1 (areas in current floor first, then others dimmed)
  const sortedAreasForRail = useMemo(() => {
    return [...areas].sort((a, b) => {
      const aInScope = a.building === selectedBuilding && a.floor === selectedFloor;
      const bInScope = b.building === selectedBuilding && b.floor === selectedFloor;
      if (aInScope && !bInScope) return -1;
      if (!aInScope && bInScope) return 1;
      return (a.name || '').localeCompare(b.name || '');
    });
  }, [areas, selectedBuilding, selectedFloor]);

  const totalAreasCount = areas.length;
  const noGeometryCount = useMemo(() => {
    return areas.filter(
      (a) =>
        !a.hasGeometry &&
        !(a.geometry && Array.isArray(a.geometry.vertices) && a.geometry.vertices.length >= 3)
    ).length;
  }, [areas]);

  // Modal Handlers
  const handleOpenCreateModal = () => {
    setFormData({
      code: '',
      name: '',
      areaLevel: 'PUBLIC',
      building: selectedBuilding || 'FPT_AROUND',
      floor: selectedFloor || 'G',
      description: '',
      reason: '',
    });
    setModalError(null);
    setCreateModalOpen(true);
  };

  const handleCreateSubmit = async (e) => {
    e.preventDefault();
    setModalError(null);
    setModalLoading(true);
    try {
      const payload = {
        code: formData.code.trim().toUpperCase(),
        name: formData.name.trim(),
        areaLevel: formData.areaLevel,
        building: formData.building ? formData.building.trim() : null,
        floor: formData.floor ? formData.floor.trim() : null,
        description: formData.description ? formData.description.trim() : null,
      };

      const created = await createArea(payload);
      setCreateModalOpen(false);
      await fetchData(created.id);
    } catch (err) {
      console.error('Create area failed:', err);
      setModalError(getErrorMessage(err));
    } finally {
      setModalLoading(false);
    }
  };

  const handleOpenEditModal = () => {
    if (!selectedArea) return;
    setFormData({
      code: selectedArea.code,
      name: selectedArea.name,
      areaLevel: selectedArea.areaLevel || selectedArea.level?.code || 'PUBLIC',
      building: selectedArea.building || '',
      floor: selectedArea.floor || '',
      description: selectedArea.description || '',
      reason: '',
    });
    setModalError(null);
    setEditModalOpen(true);
  };

  const handleEditSubmit = async (e) => {
    e.preventDefault();
    setModalError(null);

    const oldLevelRank = AREA_LEVEL_CONFIG[selectedArea.areaLevel || selectedArea.level?.code || 'PUBLIC']?.rank || 1;
    const newLevelRank = AREA_LEVEL_CONFIG[formData.areaLevel]?.rank || 1;
    const isDowngrade = newLevelRank < oldLevelRank;

    if (isDowngrade && (!formData.reason || formData.reason.trim().length < 10 || formData.reason.trim().length > 255)) {
      setModalError('Khi hạ cấp độ an ninh, lý do là bắt buộc và phải từ 10 đến 255 ký tự.');
      return;
    }

    setModalLoading(true);
    try {
      const payload = {
        code: selectedArea.code,
        name: formData.name.trim(),
        areaLevel: formData.areaLevel,
        building: formData.building ? formData.building.trim() : null,
        floor: formData.floor ? formData.floor.trim() : null,
        description: formData.description ? formData.description.trim() : null,
        reason: isDowngrade ? formData.reason.trim() : null,
      };

      const updated = await updateArea(selectedArea.id, payload);
      setEditModalOpen(false);
      await fetchData(updated.id);
    } catch (err) {
      console.error('Update area failed:', err);
      setModalError(getErrorMessage(err));
    } finally {
      setModalLoading(false);
    }
  };

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

  const handleDeactivateSubmit = async () => {
    if (!selectedArea) return;
    setModalLoading(true);
    setModalError(null);
    try {
      await deactivateArea(selectedArea.id);
      setDeactivateModalOpen(false);
      setSelectedAreaId(null);
      await fetchData();
    } catch (err) {
      console.error('Deactivate area failed:', err);
      setModalError(getErrorMessage(err));
    } finally {
      setModalLoading(false);
    }
  };

  const currentAreaRank = AREA_LEVEL_CONFIG[selectedArea?.areaLevel || selectedArea?.level?.code || 'PUBLIC']?.rank || 1;
  const selectedFormRank = AREA_LEVEL_CONFIG[formData.areaLevel]?.rank || 1;
  const isDowngradingInEdit = editModalOpen && selectedArea && selectedFormRank < currentAreaRank;

  const isSelectedAreaInCurrentScope =
    selectedArea &&
    selectedArea.building === selectedBuilding &&
    selectedArea.floor === selectedFloor;

  const selectedAreaHasGeometry =
    selectedArea &&
    (selectedArea.hasGeometry ||
      (selectedArea.geometry &&
        Array.isArray(selectedArea.geometry.vertices) &&
        selectedArea.geometry.vertices.length >= 3));

  return (
    <div className="zone-page">
      {/* Inline Page Error Banner */}
      {pageError && (
        <div className="zone-alert zone-alert--error">
          <AlertCircle size={18} />
          <span>{pageError}</span>
          <button
            type="button"
            className="zone-alert__close"
            onClick={() => setPageError(null)}
            title="Đóng thông báo"
          >
            <X size={16} />
          </button>
        </div>
      )}

      {/* ============================================================ */}
      {/* 1. TOOLBAR: Building, Floor tabs, Spacer, Toggle, Add button */}
      {/* ============================================================ */}
      <div className="zone-toolbar">
        {/* Building Selector */}
        <div className="zone-toolbar__building">
          <Building2 size={16} className="zone-toolbar__building-icon" />
          <select
            className="zone-toolbar__building-select"
            value={selectedBuilding}
            onChange={(e) => handleSelectBuilding(e.target.value)}
            title="Chọn toà nhà"
          >
            {availableBuildings.map((b) => (
              <option key={b} value={b}>
                {b}
              </option>
            ))}
          </select>
        </div>

        {/* Floor Tabs */}
        <div className="zone-toolbar__tabs">
          {availableFloors.map((fl) => {
            const isActive = selectedFloor === fl;
            return (
              <button
                key={fl}
                type="button"
                className={`zone-toolbar__tab ${isActive ? 'zone-toolbar__tab--active' : ''}`}
                onClick={() => handleSelectFloor(fl)}
              >
                Tầng {fl}
              </button>
            );
          })}
        </div>

        <div className="zone-toolbar__spacer" />

        {/* View Toggle + Add Area Button Group */}
        <div className="zone-toolbar__actions">
          <div className="zone-view-toggle">
            <button
              type="button"
              className={`zone-view-toggle__btn ${viewMode === 'map' ? 'zone-view-toggle__btn--active' : ''}`}
              onClick={() => handleToggleView('map')}
            >
              <MapIcon size={15} />
              <span>Bản đồ</span>
            </button>
            <button
              type="button"
              className={`zone-view-toggle__btn ${viewMode === 'list' ? 'zone-view-toggle__btn--active' : ''}`}
              onClick={() => handleToggleView('list')}
            >
              <ListIcon size={15} />
              <span>Danh sách</span>
            </button>
          </div>

          {isAdmin && (
            <button
              type="button"
              className="zone-toolbar__add-btn"
              onClick={handleOpenCreateModal}
            >
              <Plus size={16} strokeWidth={2.5} />
              <span>Thêm vùng</span>
            </button>
          )}
        </div>
      </div>

      {/* Loading state */}
      {loading && (
        <div className="zone-page__loading">
          <Loader2 className="animate-spin" size={32} />
          <span>Đang nạp dữ liệu khu vực...</span>
        </div>
      )}

      {/* ============================================================ */}
      {/* 2. MAIN CONTENT (MAP VIEW OR LIST VIEW)                      */}
      {/* ============================================================ */}
      {!loading && viewMode === 'map' && (
        <div className="zone-map-layout">
          {/* LEFT: MAIN CANVAS (~60%) */}
          <div className="zone-canvas-card">
            {/* Dimensions Readout */}
            {selectedPlan && !imageError && (
              <div className="zone-canvas-readout">
                {selectedPlan.originalWidth} × {selectedPlan.originalHeight}
              </div>
            )}

            {/* Drawing Hint Bar */}
            {drawingAreaId !== null && (
              <div className="zone-draw-bar">
                <div className="zone-draw-bar__info">
                  <span>
                    Đang vẽ: <strong>{areas.find((a) => a.id === drawingAreaId)?.name || ''}</strong>
                  </span>
                  <span className="zone-draw-bar__sep">·</span>
                  <span className="zone-draw-bar__count">Đã đặt {draftVertices.length} đỉnh</span>
                  <span className="zone-draw-bar__hint">(Nhấp lên ảnh để thêm đỉnh)</span>
                </div>
                <div className="zone-draw-bar__actions">
                  <button
                    type="button"
                    className="zone-draw-btn zone-draw-btn--undo"
                    onClick={handleUndoVertex}
                    disabled={draftVertices.length === 0 || savingGeometry}
                    title="Hoàn tác đỉnh cuối"
                  >
                    <Undo2 size={13} />
                    <span>Hoàn tác</span>
                  </button>
                  <button
                    type="button"
                    className="zone-draw-btn zone-draw-btn--finish"
                    disabled={draftVertices.length < 3 || savingGeometry}
                    onClick={finishDrawing}
                  >
                    {savingGeometry ? (
                      <Loader2 size={13} className="animate-spin" />
                    ) : (
                      <Check size={13} />
                    )}
                    <span>{savingGeometry ? 'Đang lưu...' : 'Hoàn tất'}</span>
                  </button>
                  <button
                    type="button"
                    className="zone-draw-btn zone-draw-btn--cancel"
                    onClick={cancelDrawing}
                    disabled={savingGeometry}
                  >
                    Huỷ
                  </button>
                </div>
              </div>
            )}

            {/* Drawing Error Banner */}
            {drawError && (
              <div className="zone-draw-error">
                <AlertCircle size={16} />
                <span>{drawError}</span>
                <button
                  type="button"
                  className="zone-draw-error__close"
                  onClick={() => setDrawError(null)}
                >
                  <X size={14} />
                </button>
              </div>
            )}

            {/* Canvas Viewport */}
            <div className="zone-canvas-viewport">
              {!selectedPlan || imageError ? (
                <div className="zone-canvas-empty">
                  <div className="zone-canvas-empty__icon">
                    <Layers size={36} />
                  </div>
                  <div className="zone-canvas-empty__title">Sơ đồ mặt bằng</div>
                  <div className="zone-canvas-empty__desc">
                    Chưa có sơ đồ mặt bằng cho Tòa {selectedBuilding || '—'} · Tầng {selectedFloor || '—'}.
                  </div>
                  <button
                    type="button"
                    className="zone-canvas-empty__switch-btn"
                    onClick={() => handleToggleView('list')}
                  >
                    Chuyển sang chế độ Danh sách
                  </button>
                </div>
              ) : (
                <div className="zone-canvas-wrapper">
                  <img
                    src={`/floor-plans/${selectedPlan.imageKey}`}
                    alt={`Sơ đồ Tòa ${selectedPlan.building} - Tầng ${selectedPlan.floor}`}
                    className="zone-canvas-img"
                    onError={() => setImageError(true)}
                  />
                  <svg
                    className={`zone-canvas-svg ${drawingAreaId !== null ? 'zone-canvas-svg--drawing' : ''}`}
                    viewBox={`0 0 ${selectedPlan.originalWidth} ${selectedPlan.originalHeight}`}
                    preserveAspectRatio="none"
                    xmlns="http://www.w3.org/2000/svg"
                    onClick={handleSvgClick}
                  >
                    {/* Render existing polygons */}
                    {mapPolygons.map((area) => {
                      const isSelected = selectedAreaId === area.id;
                      const points = area.geometry.vertices
                        .map(
                          (v) =>
                            `${v.x * selectedPlan.originalWidth},${v.y * selectedPlan.originalHeight}`
                        )
                        .join(' ');

                      return (
                        <polygon
                          key={area.id}
                          points={points}
                          className={`zone-map-polygon ${getLevelPolygonClass(area.areaLevel || area.level)} ${
                            isSelected ? 'zone-map-polygon--selected' : ''
                          }`}
                          onClick={(e) => {
                            if (drawingAreaId === null) {
                              e.stopPropagation();
                              handleSelectArea(area.id, true);
                            }
                          }}
                        >
                          <title>{area.name} ({area.code})</title>
                        </polygon>
                      );
                    })}

                    {/* Render active drawing draft line and vertices */}
                    {drawingAreaId !== null && draftVertices.length > 0 && (
                      <>
                        {draftVertices.length >= 2 && (
                          <polyline
                            points={draftVertices
                              .map(
                                (v) =>
                                  `${v.x * selectedPlan.originalWidth},${v.y * selectedPlan.originalHeight}`
                              )
                              .join(' ')}
                            className="zone-draft-line"
                          />
                        )}
                        {draftVertices.map((v, index) => (
                          <circle
                            key={index}
                            cx={v.x * selectedPlan.originalWidth}
                            cy={v.y * selectedPlan.originalHeight}
                            r={6}
                            className={`zone-draft-vertex ${
                              index === 0 ? 'zone-draft-vertex--first' : ''
                            }`}
                          />
                        ))}
                      </>
                    )}
                  </svg>
                </div>
              )}
            </div>

            {/* Colour Legend */}
            <div className="zone-canvas-legend">
              <div className="zone-canvas-legend__item">
                <span className="zone-canvas-legend__dot zone-canvas-legend__dot--public" />
                <span>Public</span>
              </div>
              <div className="zone-canvas-legend__item">
                <span className="zone-canvas-legend__dot zone-canvas-legend__dot--semi" />
                <span>Semi private</span>
              </div>
              <div className="zone-canvas-legend__item">
                <span className="zone-canvas-legend__dot zone-canvas-legend__dot--private" />
                <span>Private</span>
              </div>
            </div>
          </div>

          {/* RIGHT: TWO STACKED CARDS (~40%) */}
          <div className="zone-rail">
            {/* Card 1 — Area list */}
            <div className="zone-rail-card">
              <div className="zone-rail-header">
                <span className="zone-rail-header__title">
                  {totalAreasCount} khu vực · {noGeometryCount} chưa vẽ hình
                </span>
              </div>

              <div className="zone-rail-list">
                {sortedAreasForRail.length === 0 ? (
                  <div className="zone-rail-empty">Chưa có khu vực nào trong hệ thống</div>
                ) : (
                  sortedAreasForRail.map((area) => {
                    const isSelected = selectedAreaId === area.id;
                    const inScope =
                      area.building === selectedBuilding && area.floor === selectedFloor;
                    const hasGeo =
                      area.hasGeometry ||
                      (area.geometry &&
                        Array.isArray(area.geometry.vertices) &&
                        area.geometry.vertices.length >= 3);

                    const levelKey =
                      typeof area.areaLevel === 'string'
                        ? area.areaLevel
                        : area.level?.code || 'PUBLIC';

                    return (
                      <div
                        key={area.id}
                        ref={(el) => { rowRefs.current[area.id] = el; }}
                        className={`zone-rail-item ${isSelected ? 'zone-rail-item--selected' : ''} ${
                          !inScope ? 'zone-rail-item--dimmed' : ''
                        }`}
                        onClick={() => handleSelectArea(area.id, false)}
                      >
                        <div className="zone-rail-item__left">
                          <span
                            className={`zone-level-dot zone-level-dot--${levelKey.toLowerCase()}`}
                          />
                          <span className="zone-rail-item__name">{area.name}</span>
                        </div>

                        <div className="zone-rail-item__right">
                          {inScope ? (
                            hasGeo ? (
                              <Check size={16} className="zone-status-icon zone-status-icon--check" />
                            ) : (
                              <EyeOff size={16} className="zone-status-icon zone-status-icon--none" />
                            )
                          ) : (
                            <span className="zone-rail-item__location-badge">
                              Tòa {area.building || '—'}
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </div>

            {/* Card 2 — Detail of Selected Area */}
            <div className="zone-rail-card zone-rail-card--detail">
              {!selectedArea ? (
                <div className="zone-detail-empty">
                  <p>Chọn một khu vực trên bản đồ hoặc trong danh sách để xem cấu hình chi tiết.</p>
                </div>
              ) : (
                <div className="zone-detail-content">
                  <div className="zone-detail-header">
                    <h2 className="zone-detail-title">{selectedArea.name}</h2>
                    <span className="zone-detail-code">{selectedArea.code}</span>
                  </div>

                  <div className="zone-detail-meta">
                    <div className="zone-detail-meta-row">
                      <span className="zone-detail-meta-label">Mức an ninh</span>
                      <span className="zone-detail-meta-val">
                        {getLevelConfig(selectedArea.areaLevel || selectedArea.level?.code || 'PUBLIC').name}
                      </span>
                    </div>

                    <div className="zone-detail-meta-row">
                      <span className="zone-detail-meta-label">Vị trí</span>
                      <span className="zone-detail-meta-val">
                        Tòa {selectedArea.building || '—'}, Tầng {selectedArea.floor || '—'}
                      </span>
                    </div>

                    <div className="zone-detail-meta-row">
                      <span className="zone-detail-meta-label">Camera gán</span>
                      <span className="zone-detail-meta-val">
                        {cameraCounts[selectedArea.id] !== undefined
                          ? cameraCounts[selectedArea.id]
                          : '...'}
                      </span>
                    </div>
                  </div>

                  {/* Actions */}
                  <div className="zone-detail-actions">
                    {isAdmin && (
                      <button
                        type="button"
                        className="zone-btn-action"
                        onClick={handleOpenEditModal}
                      >
                        Sửa
                      </button>
                    )}

                    {isAdmin && isSelectedAreaInCurrentScope && (
                      <button
                        type="button"
                        className="zone-btn-action zone-btn-action--primary"
                        onClick={() => startDrawing(selectedArea.id)}
                        disabled={drawingAreaId !== null}
                      >
                        {selectedAreaHasGeometry ? 'Vẽ lại hình' : 'Vẽ hình'}
                      </button>
                    )}

                    {isAdmin && selectedAreaHasGeometry && (
                      confirmDeleteId === selectedArea.id ? (
                        <div className="zone-inline-confirm">
                          <span className="zone-inline-confirm__prompt">Xoá hình?</span>
                          <button
                            type="button"
                            className="zone-inline-confirm__btn-yes"
                            onClick={() => handleDeleteGeometry(selectedArea.id)}
                            disabled={deletingGeometryId === selectedArea.id}
                          >
                            {deletingGeometryId === selectedArea.id ? 'Đang xoá...' : 'Xoá'}
                          </button>
                          <button
                            type="button"
                            className="zone-inline-confirm__btn-no"
                            onClick={() => setConfirmDeleteId(null)}
                            disabled={deletingGeometryId === selectedArea.id}
                          >
                            Không
                          </button>
                        </div>
                      ) : (
                        <button
                          type="button"
                          className="zone-btn-action zone-btn-action--danger"
                          onClick={() => setConfirmDeleteId(selectedArea.id)}
                          disabled={drawingAreaId !== null}
                        >
                          Xoá hình
                        </button>
                      )
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ============================================================ */}
      {/* 3. LIST VIEW (Full-width card grid)                          */}
      {/* ============================================================ */}
      {!loading && viewMode === 'list' && (
        <div className="zone-list-layout">
          {floorAreas.length === 0 ? (
            <div className="area-empty-state">
              <div className="area-empty-state__icon">
                <AlertCircle size={28} />
              </div>
              <div className="area-empty-state__title">Chưa có khu vực nào trên Tầng {selectedFloor} (Tòa {selectedBuilding})</div>
              {isAdmin && (
                <button
                  type="button"
                  className="zone-toolbar__add-btn"
                  style={{ marginTop: '12px' }}
                  onClick={handleOpenCreateModal}
                >
                  <Plus size={16} />
                  <span>Thêm khu vực đầu tiên</span>
                </button>
              )}
            </div>
          ) : (
            <div className="area-grid">
              {floorAreas.map((area) => {
                const isSelected = selectedAreaId === area.id;
                const levelKey = area.areaLevel || area.level?.code || area.level || 'PUBLIC';
                const levelConfig = getLevelConfig(levelKey);

                return (
                  <div
                    key={area.id}
                    className={`zone-card ${levelConfig.cardClass} ${isSelected ? 'zone-card--selected' : ''}`}
                    onClick={() => setSelectedAreaId(area.id)}
                  >
                    <div className="zone-card__header">
                      <span className={`level-badge ${levelConfig.badgeClass}`}>
                        {levelConfig.badgeLabel}
                      </span>
                      <span
                        className={`zone-card__status-dot ${area.isActive ? '' : 'zone-card__status-dot--inactive'}`}
                        title={area.isActive ? 'Active' : 'Inactive'}
                      />
                    </div>

                    <h3 className="zone-card__title">{area.name}</h3>
                    <div className="zone-card__code">{area.code}</div>

                    <div className="zone-card__footer">
                      <div className="zone-card__camera-status">
                        {area.cameraCount && area.cameraCount > 0 ? (
                          <span className="zone-card__camera-status--has">
                            <Cctv size={13} />
                            <span>{area.cameraCount} camera</span>
                          </span>
                        ) : (
                          <span className="zone-card__camera-status--none">
                            <VideoOff size={13} />
                            <span>Chưa có camera</span>
                          </span>
                        )}
                      </div>

                      {isAdmin && (
                        <div className="zone-card__quick-actions">
                          <button
                            type="button"
                            className="zone-card__quick-btn"
                            onClick={(e) => {
                              e.stopPropagation();
                              setSelectedAreaId(area.id);
                              handleOpenEditModal();
                            }}
                            title="Sửa khu vực"
                          >
                            <Pencil size={13} />
                          </button>
                          <button
                            type="button"
                            className="zone-card__quick-btn zone-card__quick-btn--danger"
                            onClick={(e) => {
                              e.stopPropagation();
                              setSelectedAreaId(area.id);
                              handleOpenDeactivateModal();
                            }}
                            title="Vô hiệu hoá"
                          >
                            <Trash2 size={13} />
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* ============================================================ */}
      {/* 4. MODALS (CREATE, EDIT, DEACTIVATE)                         */}
      {/* ============================================================ */}

      {/* CREATE ZONE MODAL */}
      {createModalOpen && (
        <div className="area-modal-backdrop" onClick={() => setCreateModalOpen(false)}>
          <div className="area-modal" onClick={(e) => e.stopPropagation()}>
            <div className="area-modal__header">
              <div className="area-modal__header-left">
                <div className="area-modal__icon-badge">
                  <MapPinPlus size={16} />
                </div>
                <div className="area-modal__header-text">
                  <h3 className="area-modal__title">Thêm vùng mới</h3>
                  <p className="area-modal__subtitle">Tạo khu vực giám sát trong toà nhà</p>
                </div>
              </div>
              <button
                type="button"
                className="area-modal__close-btn"
                onClick={() => setCreateModalOpen(false)}
                aria-label="Đóng"
              >
                <X size={16} />
              </button>
            </div>

            <form onSubmit={handleCreateSubmit}>
              <div className="area-modal__body">
                {modalError && (
                  <div className="zone-modal-alert">
                    <AlertCircle size={15} />
                    <span>{modalError}</span>
                  </div>
                )}

                {/* 3a. Mã khu vực (bắt buộc) */}
                <div className="area-form-group">
                  <label htmlFor="create-code" className="area-form-label">
                    Mã khu vực <span className="required">*</span>
                  </label>
                  <input
                    id="create-code"
                    type="text"
                    required
                    className="area-form-input area-form-input--code"
                    placeholder="FPTA-G-GATE"
                    value={formData.code}
                    onChange={(e) => setFormData({ ...formData, code: e.target.value })}
                  />
                  <div className="area-form-hint">Định dạng: [TÒA]-[TẦNG]-[TÊN_VIẾT_TẮT]</div>
                </div>

                {/* 3b. Tên khu vực (bắt buộc) */}
                <div className="area-form-group">
                  <label htmlFor="create-name" className="area-form-label">
                    Tên khu vực <span className="required">*</span>
                  </label>
                  <input
                    id="create-name"
                    type="text"
                    required
                    className="area-form-input"
                    placeholder="Cổng chính toà nhà"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  />
                </div>

                {/* 3c. Cấp độ an ninh (bắt buộc) - 3 thẻ chọn */}
                <div className="area-form-group">
                  <label className="area-form-label">
                    Cấp độ an ninh <span className="required">*</span>
                  </label>
                  <div className="area-level-selector">
                    {AREA_LEVEL_CARDS.map((card) => {
                      const isSelected = formData.areaLevel === card.value;
                      const levelClass =
                        card.value === 'PUBLIC'
                          ? 'area-level-btn--public'
                          : card.value === 'SEMI_PRIVATE'
                          ? 'area-level-btn--semi'
                          : 'area-level-btn--private';

                      return (
                        <button
                          key={card.value}
                          type="button"
                          className={`area-level-btn ${levelClass} ${isSelected ? 'is-selected' : ''}`}
                          onClick={() => setFormData({ ...formData, areaLevel: card.value })}
                        >
                          <span
                            className="area-level-btn__dot"
                            style={{ backgroundColor: card.color }}
                          />
                          <span className="area-level-btn__name">{card.name}</span>
                          <span className="area-level-btn__sub">{card.level}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* 3d. Toà nhà và Tầng - xếp cạnh nhau */}
                <div className="area-form-row">
                  <div className="area-form-group">
                    <label htmlFor="create-building" className="area-form-label">
                      Toà nhà
                    </label>
                    <input
                      id="create-building"
                      type="text"
                      className="area-form-input"
                      placeholder="VD: FPT_AROUND"
                      value={formData.building}
                      onChange={(e) => setFormData({ ...formData, building: e.target.value })}
                    />
                  </div>

                  <div className="area-form-group">
                    <label htmlFor="create-floor" className="area-form-label">
                      Tầng
                    </label>
                    <input
                      id="create-floor"
                      type="text"
                      className="area-form-input"
                      placeholder="VD: G hoặc 1"
                      value={formData.floor}
                      onChange={(e) => setFormData({ ...formData, floor: e.target.value })}
                    />
                  </div>
                </div>

                {/* 3e. Mô tả */}
                <div className="area-form-group">
                  <label htmlFor="create-desc" className="area-form-label">
                    Mô tả
                  </label>
                  <textarea
                    id="create-desc"
                    rows={3}
                    className="area-form-input area-form-input--textarea"
                    placeholder="Thông tin chi tiết về phạm vi, chức năng của khu vực..."
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  />
                </div>
              </div>

              {/* 4. Phần chân: 2 nút chia đôi chiều rộng, gap 9px */}
              <div className="area-modal__footer">
                <button
                  type="button"
                  className="area-btn-modal area-btn-modal--cancel"
                  onClick={() => setCreateModalOpen(false)}
                  disabled={modalLoading}
                >
                  Huỷ
                </button>
                <button
                  type="submit"
                  className="area-btn-modal area-btn-modal--submit"
                  disabled={modalLoading}
                >
                  {modalLoading ? 'Đang tạo...' : 'Tạo khu vực'}
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
              <div className="area-modal__header-left">
                <div className="area-modal__icon-badge">
                  <Pencil size={16} />
                </div>
                <div className="area-modal__header-text">
                  <h3 className="area-modal__title">Chỉnh sửa khu vực</h3>
                  <p className="area-modal__subtitle">Cập nhật thông tin mã: {selectedArea.code}</p>
                </div>
              </div>
              <button
                type="button"
                className="area-modal__close-btn"
                onClick={() => setEditModalOpen(false)}
                aria-label="Đóng"
              >
                <X size={16} />
              </button>
            </div>

            <form onSubmit={handleEditSubmit}>
              <div className="area-modal__body">
                {modalError && (
                  <div className="zone-modal-alert">
                    <AlertCircle size={15} />
                    <span>{modalError}</span>
                  </div>
                )}

                <div className="area-form-group">
                  <label className="area-form-label">Mã khu vực (Không thể sửa)</label>
                  <input
                    type="text"
                    disabled
                    className="area-form-input area-form-input--code"
                    value={selectedArea.code}
                  />
                </div>

                <div className="area-form-group">
                  <label htmlFor="edit-name" className="area-form-label">
                    Tên khu vực <span className="required">*</span>
                  </label>
                  <input
                    id="edit-name"
                    type="text"
                    required
                    className="area-form-input"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  />
                </div>

                <div className="area-form-group">
                  <label className="area-form-label">
                    Cấp độ an ninh <span className="required">*</span>
                  </label>
                  <div className="area-level-selector">
                    {AREA_LEVEL_CARDS.map((card) => {
                      const isSelected = formData.areaLevel === card.value;
                      const levelClass =
                        card.value === 'PUBLIC'
                          ? 'area-level-btn--public'
                          : card.value === 'SEMI_PRIVATE'
                          ? 'area-level-btn--semi'
                          : 'area-level-btn--private';

                      return (
                        <button
                          key={card.value}
                          type="button"
                          className={`area-level-btn ${levelClass} ${isSelected ? 'is-selected' : ''}`}
                          onClick={() => setFormData({ ...formData, areaLevel: card.value })}
                        >
                          <span
                            className="area-level-btn__dot"
                            style={{ backgroundColor: card.color }}
                          />
                          <span className="area-level-btn__name">{card.name}</span>
                          <span className="area-level-btn__sub">{card.level}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>

                {isDowngradingInEdit && (
                  <div className="area-form-group area-downgrade-warning">
                    <div className="area-downgrade-warning__title">
                      <AlertCircle size={15} />
                      <span>Cảnh báo hạ cấp độ an ninh</span>
                    </div>
                    <p className="area-downgrade-warning__desc">
                      Bạn đang hạ cấp an ninh của khu vực này từ{' '}
                      <strong>{selectedArea.areaLevel || selectedArea.level?.code}</strong> xuống{' '}
                      <strong>{formData.areaLevel}</strong>. Vui lòng nhập lý do giải trình bắt buộc (10 - 255 ký tự).
                    </p>
                    <textarea
                      required
                      rows={3}
                      className="area-form-input area-form-input--textarea"
                      placeholder="Nhập lý do hạ cấp an ninh..."
                      value={formData.reason}
                      onChange={(e) => setFormData({ ...formData, reason: e.target.value })}
                    />
                  </div>
                )}

                <div className="area-form-row">
                  <div className="area-form-group">
                    <label htmlFor="edit-building" className="area-form-label">
                      Toà nhà
                    </label>
                    <input
                      id="edit-building"
                      type="text"
                      className="area-form-input"
                      value={formData.building}
                      onChange={(e) => setFormData({ ...formData, building: e.target.value })}
                    />
                  </div>

                  <div className="area-form-group">
                    <label htmlFor="edit-floor" className="area-form-label">
                      Tầng
                    </label>
                    <input
                      id="edit-floor"
                      type="text"
                      className="area-form-input"
                      value={formData.floor}
                      onChange={(e) => setFormData({ ...formData, floor: e.target.value })}
                    />
                  </div>
                </div>

                <div className="area-form-group">
                  <label htmlFor="edit-desc" className="area-form-label">
                    Mô tả
                  </label>
                  <textarea
                    id="edit-desc"
                    rows={3}
                    className="area-form-input area-form-input--textarea"
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  />
                </div>
              </div>

              <div className="area-modal__footer">
                <button
                  type="button"
                  className="area-btn-modal area-btn-modal--cancel"
                  onClick={() => setEditModalOpen(false)}
                  disabled={modalLoading}
                >
                  Huỷ
                </button>
                <button
                  type="submit"
                  className="area-btn-modal area-btn-modal--submit"
                  disabled={modalLoading}
                >
                  {modalLoading ? 'Đang lưu...' : 'Lưu thay đổi'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* DEACTIVATE ZONE MODAL */}
      {deactivateModalOpen && selectedArea && (
        <div className="area-modal-backdrop" onClick={() => setDeactivateModalOpen(false)}>
          <div className="area-modal" onClick={(e) => e.stopPropagation()}>
            <div className="area-modal__header">
              <div className="area-modal__header-left">
                <div className="area-modal__icon-badge area-modal__icon-badge--danger">
                  <Trash2 size={16} />
                </div>
                <div className="area-modal__header-text">
                  <h3 className="area-modal__title">Vô hiệu hoá khu vực</h3>
                  <p className="area-modal__subtitle">Xác nhận ngừng kích hoạt khu vực này</p>
                </div>
              </div>
              <button
                type="button"
                className="area-modal__close-btn"
                onClick={() => setDeactivateModalOpen(false)}
                aria-label="Đóng"
              >
                <X size={16} />
              </button>
            </div>

            <div className="area-modal__body">
              {modalError && (
                <div className="zone-modal-alert">
                  <AlertCircle size={15} />
                  <span>{modalError}</span>
                </div>
              )}

              <p className="area-modal__lead">
                Bạn có chắc chắn muốn vô hiệu hoá khu vực <strong>{selectedArea.name}</strong> ({selectedArea.code})?
              </p>

              {dependencies && (
                <div className="area-dependencies-box">
                  <div className="area-dependencies-box__title">
                    Phụ thuộc liên quan (sẽ bị ảnh hưởng):
                  </div>
                  <ul>
                    <li>Camera đang gán: <strong>{dependencies.assignedCamerasCount || 0}</strong></li>
                    <li>Lượt yêu cầu truy cập còn hiệu lực: <strong>{dependencies.activeAccessRequestsCount || 0}</strong></li>
                  </ul>
                </div>
              )}
            </div>

            <div className="area-modal__footer">
              <button
                type="button"
                className="area-btn-modal area-btn-modal--cancel"
                onClick={() => setDeactivateModalOpen(false)}
                disabled={modalLoading}
              >
                Huỷ
              </button>
              <button
                type="button"
                className="area-btn-modal area-btn-modal--danger"
                onClick={handleDeactivateSubmit}
                disabled={modalLoading}
              >
                {modalLoading ? 'Đang vô hiệu hoá...' : 'Xác nhận vô hiệu hoá'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
