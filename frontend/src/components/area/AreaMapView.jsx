import React from 'react';
import {
  Layers,
  Undo2,
  Check,
  Loader2,
  AlertCircle,
  X,
  EyeOff,
  Users,
  ShieldCheck,
} from 'lucide-react';
import { getLevelConfig } from '../../utils/areaHelpers';

export default function AreaMapView({
  areas,
  selectedBuilding,
  selectedFloor,
  selectedPlan,
  imageError,
  setImageError,
  drawingAreaId,
  draftVertices,
  savingGeometry,
  drawError,
  setDrawError,
  mapPolygons,
  selectedAreaId,
  selectedArea,
  cameraCounts,
  sortedAreasForRail,
  totalAreasCount,
  noGeometryCount,
  confirmDeleteId,
  deletingGeometryId,
  isFacilityManager,
  isAdmin,
  isSelectedAreaInCurrentScope,
  selectedAreaHasGeometry,
  rowRefs,
  onSelectArea,
  onUndoVertex,
  onFinishDrawing,
  onCancelDrawing,
  onSvgClick,
  onToggleView,
  onStartDrawing,
  onDeleteGeometry,
  setConfirmDeleteId,
  onOpenAssignedPersonnelModal,
  onOpenAccessRulesModal,
  onOpenEditModal,
  getLevelPolygonClass,
}) {
  return (
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
                onClick={onUndoVertex}
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
                onClick={onFinishDrawing}
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
                onClick={onCancelDrawing}
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
                onClick={() => onToggleView('list')}
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
                onClick={onSvgClick}
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
                          onSelectArea(area.id, true);
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
                    ref={(el) => {
                      if (rowRefs?.current) rowRefs.current[area.id] = el;
                    }}
                    className={`zone-rail-item ${isSelected ? 'zone-rail-item--selected' : ''} ${
                      !inScope ? 'zone-rail-item--dimmed' : ''
                    }`}
                    onClick={() => onSelectArea(area.id, false)}
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
                  <span className="zone-detail-meta-label">Level vào tự do</span>
                  <span className="zone-detail-meta-val">
                    Level {selectedArea.areaAccessLevel ?? 1}
                  </span>
                </div>

                <div className="zone-detail-meta-row">
                  <span className="zone-detail-meta-label">Chế độ vào</span>
                  <span className="zone-detail-meta-val">
                    {selectedArea.explicitAuthorizationRequired ? (
                      <span className="zone-pill-explicit">Chỉ định đích danh</span>
                    ) : (
                      <span className="zone-pill-standard">Vào theo cấp độ</span>
                    )}
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
                <button
                  type="button"
                  className="zone-btn-action"
                  onClick={() => onOpenAssignedPersonnelModal(selectedArea)}
                  title="Xem và quản lý nhân sự chỉ định cố định"
                >
                  <Users size={14} />
                  <span>Nhân sự gán</span>
                </button>

                {isFacilityManager && (
                  <button
                    type="button"
                    className="zone-btn-action zone-btn-action--primary"
                    onClick={() => onOpenAccessRulesModal(selectedArea)}
                    title="Cấu hình quy tắc truy cập khu vực"
                  >
                    <ShieldCheck size={14} />
                    <span>Quy tắc truy cập</span>
                  </button>
                )}

                {isAdmin && (
                  <button
                    type="button"
                    className="zone-btn-action"
                    onClick={onOpenEditModal}
                  >
                    Sửa
                  </button>
                )}

                {isAdmin && isSelectedAreaInCurrentScope && (
                  <button
                    type="button"
                    className="zone-btn-action zone-btn-action--primary"
                    onClick={() => onStartDrawing(selectedArea.id)}
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
                        onClick={() => onDeleteGeometry(selectedArea.id)}
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
  );
}
