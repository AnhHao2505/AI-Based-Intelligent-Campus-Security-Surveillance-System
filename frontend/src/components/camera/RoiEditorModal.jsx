import React, { useState, useEffect, useRef, useCallback } from "react";
import {
  X,
  Plus,
  Trash2,
  Save,
  RotateCcw,
  Check,
  AlertTriangle,
  Layers,
  HelpCircle,
  Loader2,
  Move,
} from "lucide-react";
import "../../styles/RoiEditorModal.css";

const MAX_POLYGONS = 10;
const MIN_VERTICES = 3;

const AVAILABLE_ALERT_RULES = [
  { id: "INTRUSION_DETECTION", label: "Phát hiện xâm nhập (Intrusion)" },
  { id: "LOITERING_DETECTION", label: "Phát hiện lảng vảng (Loitering)" },
  { id: "UNAUTHORIZED_ACCESS", label: "Truy cập trái phép (Unauthorized)" },
];

export default function RoiEditorModal({
  isOpen,
  onClose,
  snapshotBase64,
  snapshotWidth = 1920,
  snapshotHeight = 1080,
  initialRoiGeometry,
  onSave,
}) {
  const [polygons, setPolygons] = useState([]);
  const [selectedPolygonIndex, setSelectedPolygonIndex] = useState(null);
  const [isDrawing, setIsDrawing] = useState(false);
  const [draftVertices, setDraftVertices] = useState([]);
  const [cursorPos, setCursorPos] = useState(null);
  const [isNearFirst, setIsNearFirst] = useState(false);
  const [draggedVertex, setDraggedVertex] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const [imgDimensions, setImgDimensions] = useState({
    width: snapshotWidth || 1920,
    height: snapshotHeight || 1080,
  });

  const svgRef = useRef(null);

  const formattedSnapshot = snapshotBase64 && snapshotBase64.includes("minio:9000")
    ? snapshotBase64.replace("minio:9000", "localhost:9000")
    : snapshotBase64;

  const imageSrc = formattedSnapshot
    ? (formattedSnapshot.startsWith("data:") || formattedSnapshot.startsWith("http")
        ? formattedSnapshot
        : `data:image/jpeg;base64,${formattedSnapshot}`)
    : null;

  // Initialize polygons from initialRoiGeometry
  useEffect(() => {
    if (isOpen) {
      setError(null);
      setIsDrawing(false);
      setDraftVertices([]);
      setCursorPos(null);
      setIsNearFirst(false);
      setDraggedVertex(null);

      if (initialRoiGeometry && Array.isArray(initialRoiGeometry.polygons)) {
        const mapped = initialRoiGeometry.polygons.map((p, idx) => ({
          id: `poly_${Date.now()}_${idx}`,
          label: p.label || `Vùng giám sát ${idx + 1}`,
          alertRules: p.alert_rules || p.alertRules || ["INTRUSION_DETECTION"],
          vertices: (p.vertices || []).map((v) => ({
            x: Number(v.x),
            y: Number(v.y),
          })),
        }));
        setPolygons(mapped);
        setSelectedPolygonIndex(mapped.length > 0 ? 0 : null);
      } else {
        setPolygons([]);
        setSelectedPolygonIndex(null);
      }
    }
  }, [isOpen, initialRoiGeometry]);

  // Read actual image natural dimensions when image loads
  const handleImageLoad = (e) => {
    if (e.target.naturalWidth && e.target.naturalHeight) {
      setImgDimensions({
        width: e.target.naturalWidth,
        height: e.target.naturalHeight,
      });
    }
  };

  // Convert client mouse position into normalized coordinate [0.0 -> 1.0] using getScreenCTM
  const getNormalizedPoint = useCallback(
    (e) => {
      if (!svgRef.current) return null;
      const svg = svgRef.current;
      const ctm = svg.getScreenCTM();
      if (!ctm) return null;

      const pt = svg.createSVGPoint();
      pt.x = e.clientX;
      pt.y = e.clientY;
      const local = pt.matrixTransform(ctm.inverse());

      const nx = Math.min(Math.max(local.x / imgDimensions.width, 0), 1);
      const ny = Math.min(Math.max(local.y / imgDimensions.height, 0), 1);

      return {
        x: Number(nx.toFixed(4)),
        y: Number(ny.toFixed(4)),
      };
    },
    [imgDimensions]
  );

  const handleRadius = Math.max(7, Math.round(imgDimensions.width * 0.006));
  const snapDistance = handleRadius * 2.5;

  // Complete current drawing polygon
  const completeCurrentDrawing = useCallback(() => {
    if (draftVertices.length < MIN_VERTICES) {
      setError(`Mỗi polygon phải có ít nhất ${MIN_VERTICES} đỉnh.`);
      return;
    }

    const newPolygon = {
      id: `poly_${Date.now()}`,
      label: `Vùng ${polygons.length + 1}`,
      alertRules: ["INTRUSION_DETECTION"],
      vertices: [...draftVertices],
    };

    const nextPolygons = [...polygons, newPolygon];
    setPolygons(nextPolygons);
    setSelectedPolygonIndex(nextPolygons.length - 1);
    setDraftVertices([]);
    setCursorPos(null);
    setIsNearFirst(false);
    setIsDrawing(false);
    setError(null);
  }, [draftVertices, polygons]);

  // Handle click on SVG
  const handleSvgClick = (e) => {
    if (!isDrawing) return;

    const pt = getNormalizedPoint(e);
    if (!pt) return;

    // Check if clicking near the first vertex to complete polygon
    if (draftVertices.length >= MIN_VERTICES && isNearFirst) {
      completeCurrentDrawing();
      return;
    }

    // Avoid duplicate point
    if (draftVertices.length > 0) {
      const last = draftVertices[draftVertices.length - 1];
      const dist = Math.hypot(
        (pt.x - last.x) * imgDimensions.width,
        (pt.y - last.y) * imgDimensions.height
      );
      if (dist < 4) return;
    }

    setDraftVertices((prev) => [...prev, pt]);
  };

  // Double click to close polygon
  const handleSvgDoubleClick = (e) => {
    e.preventDefault();
    if (isDrawing && draftVertices.length >= MIN_VERTICES) {
      completeCurrentDrawing();
    }
  };

  // Mouse move over SVG: update guide line or vertex drag
  const handleSvgMouseMove = (e) => {
    const pt = getNormalizedPoint(e);
    if (!pt) return;

    if (draggedVertex) {
      setPolygons((prev) =>
        prev.map((poly, pIdx) => {
          if (pIdx !== draggedVertex.polygonIndex) return poly;
          const nextVertices = [...poly.vertices];
          nextVertices[draggedVertex.vertexIndex] = pt;
          return { ...poly, vertices: nextVertices };
        })
      );
      return;
    }

    if (isDrawing) {
      setCursorPos(pt);

      if (draftVertices.length >= MIN_VERTICES) {
        const first = draftVertices[0];
        const dist = Math.hypot(
          (pt.x - first.x) * imgDimensions.width,
          (pt.y - first.y) * imgDimensions.height
        );
        setIsNearFirst(dist <= snapDistance);
      } else {
        setIsNearFirst(false);
      }
    }
  };

  // Mouse up to finish dragging
  const handleMouseUp = () => {
    if (draggedVertex) {
      setDraggedVertex(null);
    }
  };

  // Keyboard navigation
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e) => {
      if (e.key === "Escape") {
        if (isDrawing) {
          setDraftVertices([]);
          setCursorPos(null);
          setIsNearFirst(false);
          setIsDrawing(false);
        } else {
          onClose();
        }
      } else if (
        (e.key === "Backspace" || (e.ctrlKey && e.key === "z")) &&
        isDrawing
      ) {
        e.preventDefault();
        setDraftVertices((prev) => prev.slice(0, -1));
      } else if (e.key === "Enter" && isDrawing && draftVertices.length >= MIN_VERTICES) {
        e.preventDefault();
        completeCurrentDrawing();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, isDrawing, draftVertices, completeCurrentDrawing, onClose]);

  // Start new drawing
  const startDrawing = () => {
    if (polygons.length >= MAX_POLYGONS) {
      setError(`Đã đạt giới hạn tối đa ${MAX_POLYGONS} vùng ROI.`);
      return;
    }
    setError(null);
    setSelectedPolygonIndex(null);
    setDraftVertices([]);
    setCursorPos(null);
    setIsNearFirst(false);
    setIsDrawing(true);
  };

  // Cancel drawing
  const cancelDrawing = () => {
    setDraftVertices([]);
    setCursorPos(null);
    setIsNearFirst(false);
    setIsDrawing(false);
    setError(null);
  };

  // Delete polygon
  const handleDeletePolygon = (indexToDelete) => {
    setPolygons((prev) => prev.filter((_, idx) => idx !== indexToDelete));
    if (selectedPolygonIndex === indexToDelete) {
      setSelectedPolygonIndex(null);
    } else if (selectedPolygonIndex > indexToDelete) {
      setSelectedPolygonIndex(selectedPolygonIndex - 1);
    }
  };

  // Update selected polygon property
  const handleUpdateSelected = (field, value) => {
    if (selectedPolygonIndex === null || !polygons[selectedPolygonIndex]) return;
    setPolygons((prev) =>
      prev.map((p, idx) =>
        idx === selectedPolygonIndex ? { ...p, [field]: value } : p
      )
    );
  };

  // Toggle alert rule
  const handleToggleAlertRule = (ruleId) => {
    if (selectedPolygonIndex === null || !polygons[selectedPolygonIndex]) return;
    const currentRules = polygons[selectedPolygonIndex].alertRules || [];
    const nextRules = currentRules.includes(ruleId)
      ? currentRules.filter((r) => r !== ruleId)
      : [...currentRules, ruleId];
    handleUpdateSelected("alertRules", nextRules);
  };

  // Save changes
  const handleSave = async () => {
    if (isDrawing && draftVertices.length > 0) {
      setError("Vui lòng hoàn tất hoặc hủy vùng vẽ dở trước khi lưu.");
      return;
    }

    if (polygons.length === 0) {
      setError("Cần ít nhất 1 vùng polygon ROI.");
      return;
    }

    for (let i = 0; i < polygons.length; i++) {
      const p = polygons[i];
      if (!p.vertices || p.vertices.length < MIN_VERTICES) {
        setError(
          `Vùng "${p.label || i + 1}" phải có ít nhất ${MIN_VERTICES} đỉnh.`
        );
        return;
      }
    }

    setSaving(true);
    setError(null);

    const payload = {
      polygons: polygons.map((p) => ({
        label: p.label ? p.label.trim().slice(0, 100) : undefined,
        alert_rules:
          p.alertRules && p.alertRules.length > 0
            ? p.alertRules
            : ["INTRUSION_DETECTION"],
        vertices: p.vertices.map((v) => ({
          x: Number(v.x.toFixed(4)),
          y: Number(v.y.toFixed(4)),
        })),
      })),
    };

    try {
      await onSave(payload, {
        snapshotBase64: snapshotBase64 && !snapshotBase64.startsWith("http") ? snapshotBase64 : null,
        snapshotWidth: imgDimensions.width,
        snapshotHeight: imgDimensions.height,
      });
      onClose();
    } catch (err) {
      console.error("Failed to save ROI:", err);
      setError(err.message || "Lỗi khi lưu ROI geometry");
    } finally {
      setSaving(false);
    }
  };

  // Compute centroid of polygon for label placement
  const computeCentroid = (vertices) => {
    if (!vertices || vertices.length === 0) return { x: 0, y: 0 };
    let sumX = 0;
    let sumY = 0;
    vertices.forEach((v) => {
      sumX += v.x * imgDimensions.width;
      sumY += v.y * imgDimensions.height;
    });
    return {
      x: sumX / vertices.length,
      y: sumY / vertices.length,
    };
  };

  if (!isOpen) return null;

  const selectedPoly =
    selectedPolygonIndex !== null ? polygons[selectedPolygonIndex] : null;

  return (
    <div
      className="roi-modal-overlay"
      onMouseUp={handleMouseUp}
    >
      <div className="roi-modal-container">
        {/* Modal Header */}
        <div className="roi-modal-header">
          <div className="roi-modal-header-left">
            <h3 className="roi-modal-title">
              <Layers size={20} color="#38bdf8" />
              Cấu hình vùng quan sát camera (ROI Editor)
            </h3>
            <span className="roi-counter-badge">
              {polygons.length} / {MAX_POLYGONS} vùng
            </span>
          </div>

          <div className="roi-modal-actions">
            <button
              className="roi-btn roi-btn-secondary"
              onClick={onClose}
              disabled={saving}
            >
              Hủy
            </button>
            <button
              className="roi-btn roi-btn-primary"
              onClick={handleSave}
              disabled={saving || isDrawing}
            >
              {saving ? (
                <>
                  <Loader2 size={16} className="animate-spin" />
                  Đang lưu...
                </>
              ) : (
                <>
                  <Save size={16} />
                  Lưu cấu hình ROI
                </>
              )}
            </button>
            <button className="roi-modal-close-btn" onClick={onClose}>
              <X size={20} />
            </button>
          </div>
        </div>

        {/* Modal Body */}
        <div className="roi-modal-body">
          {/* Canvas Section */}
          <div className="roi-canvas-section">
            {/* Toolbar */}
            <div className="roi-canvas-toolbar">
              <div className="roi-toolbar-left">
                {!isDrawing ? (
                  <button
                    className="roi-btn roi-btn-primary"
                    onClick={startDrawing}
                    disabled={polygons.length >= MAX_POLYGONS}
                  >
                    <Plus size={16} />
                    Vẽ vùng mới
                  </button>
                ) : (
                  <>
                    <button
                      className="roi-btn roi-btn-primary"
                      onClick={completeCurrentDrawing}
                      disabled={draftVertices.length < MIN_VERTICES}
                    >
                      <Check size={16} />
                      Hoàn tất vùng ({draftVertices.length} đỉnh)
                    </button>
                    <button
                      className="roi-btn roi-btn-secondary"
                      onClick={() => setDraftVertices((prev) => prev.slice(0, -1))}
                      disabled={draftVertices.length === 0}
                    >
                      <RotateCcw size={16} />
                      Undo đỉnh
                    </button>
                    <button className="roi-btn roi-btn-danger" onClick={cancelDrawing}>
                      Hủy vẽ (Esc)
                    </button>
                  </>
                )}
              </div>

              <div className="roi-instruction-text">
                <HelpCircle size={15} />
                {isDrawing ? (
                  <span>
                    Click để đặt đỉnh. Click vào <strong>đỉnh đầu (màu xanh lá)</strong> hoặc double-click để hoàn tất đa giác.
                  </span>
                ) : (
                  <span>
                    Click vào vùng để chọn, kéo thả các đỉnh vàng để căn chỉnh toạ độ.
                  </span>
                )}
              </div>
            </div>

            {/* Canvas Viewport */}
            <div className="roi-canvas-wrapper">
              <div
                className="roi-viewport"
                style={{
                  position: "relative",
                  display: "inline-block",
                  lineHeight: 0,
                }}
              >
                {imageSrc ? (
                  <img
                    src={imageSrc}
                    alt="Camera Snapshot"
                    className="roi-snapshot-img"
                    onLoad={handleImageLoad}
                  />
                ) : (
                  <div className="roi-empty-state">
                    Không có ảnh snapshot để hiển thị.
                  </div>
                )}

                {/* SVG Overlay matching image dimensions */}
                <svg
                  ref={svgRef}
                  viewBox={`0 0 ${imgDimensions.width} ${imgDimensions.height}`}
                  preserveAspectRatio="none"
                  className={`roi-svg-overlay ${!isDrawing ? "mode-select" : ""}`}
                  onClick={handleSvgClick}
                  onDoubleClick={handleSvgDoubleClick}
                  onMouseMove={handleSvgMouseMove}
                >
                  {/* Render Saved Polygons */}
                  {polygons.map((poly, pIdx) => {
                    const isSelected = selectedPolygonIndex === pIdx;
                    const pointsStr = poly.vertices
                      .map((v) => `${v.x * imgDimensions.width},${v.y * imgDimensions.height}`)
                      .join(" ");
                    const centroid = computeCentroid(poly.vertices);

                    return (
                      <g key={poly.id || pIdx}>
                        {/* Polygon Shape */}
                        <polygon
                          points={pointsStr}
                          className={`roi-polygon-shape ${isSelected ? "selected" : ""}`}
                          onClick={(e) => {
                            if (!isDrawing) {
                              e.stopPropagation();
                              setSelectedPolygonIndex(pIdx);
                            }
                          }}
                        >
                          <title>{poly.label || `Vùng ${pIdx + 1}`}</title>
                        </polygon>

                        {/* Centroid Label */}
                        {poly.vertices && poly.vertices.length >= 3 && (
                          <text
                            x={centroid.x}
                            y={centroid.y}
                            className="roi-poly-label-svg"
                          >
                            {pIdx + 1}. {poly.label || `Vùng ${pIdx + 1}`}
                          </text>
                        )}

                        {/* Draggable handles when selected */}
                        {isSelected &&
                          !isDrawing &&
                          poly.vertices.map((v, vIdx) => (
                            <circle
                              key={vIdx}
                              cx={v.x * imgDimensions.width}
                              cy={v.y * imgDimensions.height}
                              r={handleRadius}
                              className="roi-edit-handle"
                              onMouseDown={(e) => {
                                e.stopPropagation();
                                setDraggedVertex({
                                  polygonIndex: pIdx,
                                  vertexIndex: vIdx,
                                });
                              }}
                            />
                          ))}
                      </g>
                    );
                  })}

                  {/* Render Draft Polygon currently being drawn */}
                  {isDrawing && draftVertices.length > 0 && (
                    <g>
                      {/* Semi-transparent fill of draft polygon with cursor */}
                      {draftVertices.length >= 2 && cursorPos && (
                        <polygon
                          points={[
                            ...draftVertices.map(
                              (v) => `${v.x * imgDimensions.width},${v.y * imgDimensions.height}`
                            ),
                            `${cursorPos.x * imgDimensions.width},${cursorPos.y * imgDimensions.height}`,
                          ].join(" ")}
                          className="roi-draft-fill"
                        />
                      )}

                      {/* Solid/dashed lines connecting placed vertices */}
                      {draftVertices.length >= 2 && (
                        <polyline
                          points={draftVertices
                            .map(
                              (v) => `${v.x * imgDimensions.width},${v.y * imgDimensions.height}`
                            )
                            .join(" ")}
                          className="roi-draft-line"
                        />
                      )}

                      {/* Dynamic guide line from last vertex to cursor */}
                      {cursorPos && (
                        <line
                          x1={draftVertices[draftVertices.length - 1].x * imgDimensions.width}
                          y1={draftVertices[draftVertices.length - 1].y * imgDimensions.height}
                          x2={cursorPos.x * imgDimensions.width}
                          y2={cursorPos.y * imgDimensions.height}
                          className="roi-draft-guide"
                        />
                      )}

                      {/* Faint guide line from cursor back to first vertex */}
                      {draftVertices.length >= 2 && cursorPos && (
                        <line
                          x1={cursorPos.x * imgDimensions.width}
                          y1={cursorPos.y * imgDimensions.height}
                          x2={draftVertices[0].x * imgDimensions.width}
                          y2={draftVertices[0].y * imgDimensions.height}
                          className="roi-draft-closing-guide"
                        />
                      )}

                      {/* Render placed vertex circles */}
                      {draftVertices.map((v, index) => {
                        const isFirst = index === 0;
                        return (
                          <g key={index}>
                            {/* Halo around first vertex when user is close */}
                            {isFirst && isNearFirst && (
                              <circle
                                cx={v.x * imgDimensions.width}
                                cy={v.y * imgDimensions.height}
                                r={handleRadius * 2.2}
                                className="roi-first-target-halo"
                              />
                            )}

                            <circle
                              cx={v.x * imgDimensions.width}
                              cy={v.y * imgDimensions.height}
                              r={isFirst && isNearFirst ? handleRadius * 1.4 : handleRadius}
                              className={`roi-draft-vertex ${
                                isFirst ? "roi-draft-vertex--first" : ""
                              } ${isFirst && isNearFirst ? "roi-draft-vertex--closing" : ""}`}
                            />

                            {/* Large invisible click target on first vertex to easily close polygon */}
                            {isFirst && (
                              <circle
                                cx={v.x * imgDimensions.width}
                                cy={v.y * imgDimensions.height}
                                r={handleRadius * 3}
                                fill="transparent"
                                style={{
                                  cursor:
                                    draftVertices.length >= MIN_VERTICES
                                      ? "pointer"
                                      : "crosshair",
                                }}
                                onClick={(e) => {
                                  if (draftVertices.length >= MIN_VERTICES) {
                                    e.stopPropagation();
                                    completeCurrentDrawing();
                                  }
                                }}
                              />
                            )}
                          </g>
                        );
                      })}
                    </g>
                  )}
                </svg>
              </div>
            </div>
          </div>

          {/* Sidebar Section */}
          <div className="roi-sidebar">
            <div className="roi-sidebar-header">
              <span>Danh sách vùng ROI</span>
              {error && (
                <span
                  style={{
                    color: "#f87171",
                    fontSize: "0.75rem",
                    display: "flex",
                    alignItems: "center",
                    gap: "4px",
                  }}
                >
                  <AlertTriangle size={14} /> Có lỗi
                </span>
              )}
            </div>

            <div className="roi-sidebar-content">
              {error && (
                <div
                  style={{
                    padding: "0.65rem 0.85rem",
                    background: "rgba(239, 68, 68, 0.15)",
                    border: "1px solid rgba(239, 68, 68, 0.3)",
                    borderRadius: "8px",
                    color: "#fca5a5",
                    fontSize: "0.825rem",
                  }}
                >
                  {error}
                </div>
              )}

              {/* Polygon List */}
              <div className="roi-polygon-list">
                {polygons.length === 0 ? (
                  <div className="roi-empty-state">
                    Chưa có vùng ROI nào. Bấm <strong>"Vẽ vùng mới"</strong> để bắt đầu khoanh vùng quan sát trên camera.
                  </div>
                ) : (
                  polygons.map((poly, idx) => {
                    const isSelected = selectedPolygonIndex === idx;
                    return (
                      <div
                        key={poly.id || idx}
                        className={`roi-polygon-item ${isSelected ? "active" : ""}`}
                        onClick={() => setSelectedPolygonIndex(idx)}
                      >
                        <div className="roi-polygon-item-left">
                          <span className="roi-poly-badge">{idx + 1}</span>
                          <div>
                            <div className="roi-poly-label">
                              {poly.label || `Vùng ${idx + 1}`}
                            </div>
                            <div className="roi-poly-sub">
                              {poly.vertices ? poly.vertices.length : 0} đỉnh
                            </div>
                          </div>
                        </div>

                        <button
                          className="roi-poly-delete-btn"
                          title="Xóa vùng"
                          onClick={(e) => {
                            e.stopPropagation();
                            handleDeletePolygon(idx);
                          }}
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    );
                  })
                )}
              </div>

              {/* Edit Selected Polygon Form */}
              {selectedPoly && (
                <div className="roi-edit-form">
                  <div className="roi-form-title">
                    <Move size={15} color="#eab308" />
                    <span>Chi tiết: {selectedPoly.label || `Vùng ${selectedPolygonIndex + 1}`}</span>
                  </div>

                  {/* Label */}
                  <div className="roi-form-group">
                    <label className="roi-form-label">Tên vùng (Label)</label>
                    <input
                      type="text"
                      className="roi-form-input"
                      value={selectedPoly.label || ""}
                      maxLength={100}
                      placeholder="VD: Cổng chính, Lối ra vào..."
                      onChange={(e) => handleUpdateSelected("label", e.target.value)}
                    />
                  </div>

                  {/* Alert Rules */}
                  <div className="roi-form-group">
                    <label className="roi-form-label">Quy tắc cảnh báo (Alert Rules)</label>
                    <div className="roi-checkbox-group">
                      {AVAILABLE_ALERT_RULES.map((rule) => {
                        const isChecked = (selectedPoly.alertRules || []).includes(rule.id);
                        return (
                          <label key={rule.id} className="roi-checkbox-label">
                            <input
                              type="checkbox"
                              checked={isChecked}
                              onChange={() => handleToggleAlertRule(rule.id)}
                            />
                            <span>{rule.label}</span>
                          </label>
                        );
                      })}
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
