import { useState, useEffect } from 'react';
import { Layers, AlertCircle, RotateCcw, Loader2, Pencil, Undo2, Check, X } from 'lucide-react';
import { getFloorPlans, getAreaGeometries, saveAreaGeometry } from '../../services/areaService';
import { getLevelConfig } from '../../utils/areaHelpers';
import { useAuth } from '../../context/AuthContext';
import { ROLES } from '../../constants/roles';
import './AreaMapPage.css';

const EPS = 0.0005;
const round6 = (n) => Math.round(n * 1e6) / 1e6;

const GEOMETRY_ERROR_MESSAGES = {
  ERR_AREA_011: 'Hình phải có ít nhất 3 đỉnh.',
  ERR_AREA_012: 'Có đỉnh nằm ngoài phạm vi bản đồ. Vui lòng vẽ lại.',
  ERR_AREA_013: 'Hình bị chồng lấn với khu vực khác trên cùng tầng.',
  ERR_AREA_015: 'Khu vực này chưa có thông tin toà nhà và tầng.',
  ERR_AREA_016: 'Hình phải có ít nhất 3 đỉnh khác nhau.',
};

export default function AreaMapPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === ROLES.ADMIN;

  const [floorPlans, setFloorPlans] = useState([]);
  const [selectedPlan, setSelectedPlan] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [imageError, setImageError] = useState(false);

  const [areas, setAreas] = useState([]);
  const [areasLoading, setAreasLoading] = useState(false);
  const [areasError, setAreasError] = useState(null);

  const [drawingAreaId, setDrawingAreaId] = useState(null);
  const [draftVertices, setDraftVertices] = useState([]);
  const [drawError, setDrawError] = useState(null);
  const [savingGeometry, setSavingGeometry] = useState(false);

  const startDrawing = (areaId) => {
    setDrawingAreaId(areaId);
    setDraftVertices([]);
    setDrawError(null);
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
    if (savingGeometry) return;
    if (drawingAreaId === null) return;
    if (draftVertices.length < 3) return;

    setSavingGeometry(true);
    setDrawError(null);
    try {
      await saveAreaGeometry(drawingAreaId, draftVertices);
      cancelDrawing();
      await loadAreas(selectedPlan);
    } catch (err) {
      const message =
        GEOMETRY_ERROR_MESSAGES[err.code] ||
        err.message ||
        'Không lưu được hình. Vui lòng thử lại.';
      setDrawError(message);
    } finally {
      setSavingGeometry(false);
    }
  };

  const handleSvgClick = (event) => {
    if (savingGeometry) return;
    if (drawingAreaId === null || !selectedPlan) return;

    const svg = event.currentTarget;
    const ctm = svg.getScreenCTM();
    if (!ctm) return;

    const pt = svg.createSVGPoint();
    pt.x = event.clientX;
    pt.y = event.clientY;
    const local = pt.matrixTransform(ctm.inverse());

    const nx = local.x / selectedPlan.originalWidth;
    const ny = local.y / selectedPlan.originalHeight;

    // Chặn 1: ngoài khung [0,1]
    if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return;

    // Làm tròn 6 chữ số thập phân
    const roundedX = round6(nx);
    const roundedY = round6(ny);

    // Chặn 2: trùng đỉnh đã có
    const isDuplicate = draftVertices.some(
      (v) => Math.abs(v.x - roundedX) < EPS && Math.abs(v.y - roundedY) < EPS
    );
    if (isDuplicate) return;

    setDraftVertices((prev) => [...prev, { x: roundedX, y: roundedY }]);
  };

  const sortFloorPlans = (plans) => {
    return [...plans].sort((a, b) => {
      const isNumA = /^\d+$/.test(a.floor);
      const isNumB = /^\d+$/.test(b.floor);
      if (!isNumA && isNumB) return -1;
      if (isNumA && !isNumB) return 1;
      if (!isNumA && !isNumB) return a.floor.localeCompare(b.floor);
      return parseInt(a.floor, 10) - parseInt(b.floor, 10);
    });
  };

  const loadFloorPlans = async () => {
    setLoading(true);
    setError(null);
    setImageError(false);
    try {
      const data = await getFloorPlans();
      const activePlans = (data || []).filter((fp) => fp.isActive !== false);
      const sorted = sortFloorPlans(activePlans);
      setFloorPlans(sorted);
      if (sorted.length > 0) {
        setSelectedPlan(sorted[0]);
      } else {
        setSelectedPlan(null);
      }
    } catch (err) {
      console.error('Failed to load floor plans:', err);
      setError(err.message || 'Lỗi tải sơ đồ tầng.');
    } finally {
      setLoading(false);
    }
  };

  const loadAreas = async (plan) => {
    if (!plan || !plan.building || !plan.floor) {
      setAreas([]);
      return;
    }
    setAreasLoading(true);
    setAreasError(null);
    try {
      const data = await getAreaGeometries(plan.building, plan.floor);
      setAreas(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load area geometries:', err);
      setAreasError(err.message || 'Lỗi tải danh sách khu vực.');
    } finally {
      setAreasLoading(false);
    }
  };

  useEffect(() => {
    loadFloorPlans();
  }, []);

  useEffect(() => {
    if (selectedPlan) {
      loadAreas(selectedPlan);
    } else {
      setAreas([]);
    }
  }, [selectedPlan]);

  const handleSelectFloor = (plan) => {
    if (drawingAreaId !== null) {
      cancelDrawing();
    }
    setSelectedPlan(plan);
    setImageError(false);
  };

  const getLevelDotClass = (level) => {
    if (level === 1) return 'area-map-page__level-dot--1';
    if (level === 2) return 'area-map-page__level-dot--2';
    if (level === 3) return 'area-map-page__level-dot--3';
    return 'area-map-page__level-dot--default';
  };

  const getLevelPolygonClass = (level) => {
    if (level === 1) return 'area-map-page__polygon--1';
    if (level === 2) return 'area-map-page__polygon--2';
    if (level === 3) return 'area-map-page__polygon--3';
    return 'area-map-page__polygon--default';
  };

  return (
    <div className="area-map-page">
      <div className="area-map-page__header">
        <div>
          <h1 className="area-map-page__title">Bản đồ khu vực</h1>
          <p className="area-map-page__subtitle">Xem và quản lý sơ đồ các tầng</p>
        </div>
        {selectedPlan && (
          <div className="area-map-page__meta">
            <span className="area-map-page__building-tag">{selectedPlan.building}</span>
            <span className="area-map-page__dim-tag">
              {selectedPlan.originalWidth} × {selectedPlan.originalHeight}
            </span>
          </div>
        )}
      </div>

      {loading && (
        <div className="area-map-page__state">
          <Loader2 className="area-map-page__spinner" size={32} />
          <span>Đang tải sơ đồ tầng...</span>
        </div>
      )}

      {!loading && error && (
        <div className="area-map-page__state area-map-page__state--error">
          <AlertCircle size={32} />
          <span className="area-map-page__error-text">{error}</span>
          <button type="button" className="area-map-page__btn-retry" onClick={loadFloorPlans}>
            <RotateCcw size={16} />
            <span>Thử lại</span>
          </button>
        </div>
      )}

      {!loading && !error && floorPlans.length === 0 && (
        <div className="area-map-page__state area-map-page__state--empty">
          <Layers size={36} />
          <span>Chưa có sơ đồ tầng nào</span>
        </div>
      )}

      {!loading && !error && floorPlans.length > 0 && selectedPlan && (
        <div className="area-map-page__content">
          <div className="area-map-page__tabs">
            {floorPlans.map((fp) => {
              const isActive = selectedPlan.id === fp.id;
              return (
                <button
                  key={fp.id}
                  type="button"
                  className={`area-map-page__tab ${isActive ? 'area-map-page__tab--active' : ''}`}
                  onClick={() => handleSelectFloor(fp)}
                >
                  <Layers size={16} />
                  <span>Tầng {fp.floor}</span>
                </button>
              );
            })}
          </div>

          <div className="area-map-page__body">
            <div className="area-map-page__viewport-card">
              <div className="area-map-page__image-wrapper">
                {imageError ? (
                  <div className="area-map-page__image-fallback">
                    <AlertCircle size={28} />
                    <span>Không tải được ảnh: {selectedPlan.imageKey}</span>
                  </div>
                ) : (
                  <>
                    <img
                      src={`/floor-plans/${selectedPlan.imageKey}`}
                      alt={`Sơ đồ tầng ${selectedPlan.floor}`}
                      className="area-map-page__image"
                      onError={() => setImageError(true)}
                    />
                    <svg
                      className={`area-map-page__overlay ${
                        drawingAreaId !== null ? 'area-map-page__overlay--drawing' : ''
                      }`}
                      viewBox={`0 0 ${selectedPlan.originalWidth} ${selectedPlan.originalHeight}`}
                      preserveAspectRatio="none"
                      xmlns="http://www.w3.org/2000/svg"
                      onClick={handleSvgClick}
                    >
                      {areas
                        .filter(
                          (area) =>
                            area.geometry &&
                            Array.isArray(area.geometry.vertices) &&
                            area.geometry.vertices.length >= 3
                        )
                        .map((area) => {
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
                              className={`area-map-page__polygon ${getLevelPolygonClass(area.level)}`}
                            >
                              <title>{area.name}</title>
                            </polygon>
                          );
                        })}
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
                              className="area-map-page__draft-line"
                            />
                          )}
                          {draftVertices.map((v, index) => (
                            <circle
                              key={index}
                              cx={v.x * selectedPlan.originalWidth}
                              cy={v.y * selectedPlan.originalHeight}
                              r={6}
                              className={`area-map-page__draft-vertex ${
                                index === 0 ? 'area-map-page__draft-vertex--first' : ''
                              }`}
                            />
                          ))}
                        </>
                      )}
                    </svg>
                  </>
                )}
              </div>
            </div>

            <div className="area-map-page__sidebar-card">
              {areasLoading && (
                <div className="area-map-page__sidebar-state">
                  <Loader2 className="area-map-page__spinner" size={24} />
                  <span>Đang tải danh sách khu vực...</span>
                </div>
              )}

              {!areasLoading && areasError && (
                <div className="area-map-page__sidebar-state area-map-page__sidebar-state--error">
                  <AlertCircle size={24} />
                  <span className="area-map-page__error-text">{areasError}</span>
                  <button
                    type="button"
                    className="area-map-page__btn-retry"
                    onClick={() => loadAreas(selectedPlan)}
                  >
                    <RotateCcw size={14} />
                    <span>Thử lại</span>
                  </button>
                </div>
              )}

              {!areasLoading && !areasError && areas.length === 0 && (
                <div className="area-map-page__sidebar-state area-map-page__sidebar-state--empty">
                  <span>Tầng này chưa có khu vực nào</span>
                </div>
              )}

              {!areasLoading && !areasError && areas.length > 0 && (
                <div className="area-map-page__sidebar-content">
                  {drawingAreaId !== null && (
                    <div className="area-map-page__draw-bar">
                      <div className="area-map-page__draw-info">
                        <div className="area-map-page__draw-title">
                          <span>
                            Đang vẽ: <strong>{areas.find((a) => a.id === drawingAreaId)?.name || ''}</strong>
                          </span>
                        </div>
                        <div className="area-map-page__draw-meta">
                          <p className="area-map-page__draw-hint">Nhấp lên bản đồ để đặt đỉnh</p>
                          <span className="area-map-page__draw-sep">·</span>
                          <span className="area-map-page__draw-count">Đã đặt {draftVertices.length} đỉnh</span>
                        </div>
                      </div>
                      <div className="area-map-page__draw-actions">
                        <button
                          type="button"
                          className="area-map-page__btn-undo"
                          onClick={handleUndoVertex}
                          disabled={draftVertices.length === 0 || savingGeometry}
                          title="Hoàn tác đỉnh cuối"
                        >
                          <Undo2 size={14} />
                          <span>Hoàn tác</span>
                        </button>
                        <button
                          type="button"
                          className="area-map-page__btn-finish"
                          disabled={draftVertices.length < 3 || savingGeometry}
                          onClick={finishDrawing}
                          title="Hoàn tất polygon"
                        >
                          {savingGeometry ? (
                            <Loader2 className="area-map-page__spinner" size={14} />
                          ) : (
                            <Check size={14} />
                          )}
                          <span>{savingGeometry ? 'Đang lưu...' : 'Hoàn tất'}</span>
                        </button>
                        <button
                          type="button"
                          className="area-map-page__btn-cancel-draw"
                          onClick={cancelDrawing}
                          disabled={savingGeometry}
                        >
                          Huỷ
                        </button>
                      </div>
                    </div>
                  )}

                  {drawError !== null && (
                    <div className="area-map-page__sidebar-state area-map-page__sidebar-state--error area-map-page__draw-error">
                      <AlertCircle size={20} />
                      <span className="area-map-page__error-text">{drawError}</span>
                      <button
                        type="button"
                        className="area-map-page__btn-close-error"
                        onClick={() => setDrawError(null)}
                        title="Đóng thông báo"
                      >
                        <X size={14} />
                      </button>
                    </div>
                  )}

                  <div className="area-map-page__summary-bar">
                    <span className="area-map-page__summary-text">
                      {areas.length} khu vực · {areas.filter((a) => !a.geometry).length} chưa có hình
                    </span>
                  </div>
                  <div className="area-map-page__area-list">
                    {areas.map((area) => {
                      const isDrawingThis = drawingAreaId === area.id;
                      return (
                        <div
                          key={area.id}
                          className={`area-map-page__area-item ${
                            isDrawingThis ? 'area-map-page__area-item--drawing' : ''
                          }`}
                        >
                          <div className="area-map-page__area-main">
                            <span
                              className={`area-map-page__level-dot ${getLevelDotClass(area.level)}`}
                              title={`Level ${area.level || '?'}: ${getLevelConfig(area.level).name}`}
                            />
                            <div className="area-map-page__area-info">
                              <span className="area-map-page__area-name">{area.name}</span>
                              <span className="area-map-page__area-code">{area.code}</span>
                            </div>
                          </div>
                          <div className="area-map-page__area-actions">
                            {isAdmin && !area.geometry && drawingAreaId === null && (
                              <button
                                type="button"
                                className="area-map-page__btn-draw"
                                onClick={() => startDrawing(area.id)}
                                title="Vẽ hình khu vực"
                              >
                                <Pencil size={13} />
                                <span>Vẽ hình</span>
                              </button>
                            )}
                            <span
                              className={`area-map-page__geo-badge ${
                                !area.geometry
                                  ? 'area-map-page__geo-badge--none'
                                  : 'area-map-page__geo-badge--has'
                              }`}
                            >
                              {!area.geometry ? 'Chưa có hình' : 'Đã có hình'}
                            </span>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

