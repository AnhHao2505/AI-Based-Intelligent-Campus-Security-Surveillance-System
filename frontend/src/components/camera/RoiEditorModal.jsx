import React, { useState, useEffect, useRef, useCallback } from "react";
import {
	X,
	Plus,
	Trash2,
	Save,
	RotateCcw,
	RotateCw,
	Check,
	AlertTriangle,
	Layers,
	HelpCircle,
	Loader2,
	Move,
	ArrowRight,
	ArrowLeftRight,
	ShieldAlert,
	LogIn,
	LogOut,
	Maximize2,
} from "lucide-react";
import "../../styles/RoiEditorModal.css";

const MAX_POLYGONS = 10;
const MAX_ENTRY_LINES = 5;
const MIN_VERTICES = 3;

export default function RoiEditorModal({
	isOpen,
	onClose,
	snapshotBase64,
	snapshotWidth = 1920,
	snapshotHeight = 1080,
	initialRoiGeometry,
	availableAreas = [],
	onSave,
}) {
	const [polygons, setPolygons] = useState([]);
	const [entryLines, setEntryLines] = useState([]);
	const [selectedItem, setSelectedItem] = useState(null); // { type: "POLYGON" | "LINE", index: number }

	// Drawing state
	const [drawMode, setDrawMode] = useState(null); // null | "POLYGON" | "LINE"
	const [draftVertices, setDraftVertices] = useState([]);
	const [draftLineStart, setDraftLineStart] = useState(null);
	const [cursorPos, setCursorPos] = useState(null);
	const [isNearFirst, setIsNearFirst] = useState(false);

	// Dragging state
	const [draggedTarget, setDraggedTarget] = useState(null); // { type: "POLYGON", pIdx, vIdx } | { type: "LINE", lIdx, point: "A" | "B" }

	const [saving, setSaving] = useState(false);
	const [error, setError] = useState(null);

	const [imgDimensions, setImgDimensions] = useState({
		width: snapshotWidth || 1920,
		height: snapshotHeight || 1080,
	});

	const svgRef = useRef(null);
	const imgRef = useRef(null);

	const formattedSnapshot =
		snapshotBase64 && snapshotBase64.includes("minio:9000")
			? snapshotBase64.replace("minio:9000", "localhost:9000")
			: snapshotBase64;

	const imageSrc = formattedSnapshot
		? formattedSnapshot.startsWith("data:") ||
			formattedSnapshot.startsWith("http")
			? formattedSnapshot
			: `data:image/jpeg;base64,${formattedSnapshot}`
		: null;

	// Initialize from initialRoiGeometry
	useEffect(() => {
		if (isOpen) {
			setError(null);
			setDrawMode(null);
			setDraftVertices([]);
			setDraftLineStart(null);
			setCursorPos(null);
			setIsNearFirst(false);
			setDraggedTarget(null);

			if (imgRef.current?.naturalWidth && imgRef.current?.naturalHeight) {
				setImgDimensions({
					width: imgRef.current.naturalWidth,
					height: imgRef.current.naturalHeight,
				});
			} else {
				setImgDimensions({
					width: snapshotWidth || 1920,
					height: snapshotHeight || 1080,
				});
			}

			// 1. Polygons
			const polys = Array.isArray(initialRoiGeometry?.polygons)
				? initialRoiGeometry.polygons.map((p, idx) => ({
						id: `poly_${Date.now()}_${idx}`,
						label: p.label || `Vùng giám sát ${idx + 1}`,
						vertices: (p.vertices || []).map((v) => ({
							x: Number(v.x),
							y: Number(v.y),
						})),
					}))
				: [];
			setPolygons(polys);

			// 2. Entry Lines
			const rawLines =
				initialRoiGeometry?.entry_lines || initialRoiGeometry?.entryLines || [];
			const lines = Array.isArray(rawLines)
				? rawLines.map((l, idx) => ({
						id: `line_${Date.now()}_${idx}`,
						label: l.label || `Đường ranh ${idx + 1}`,
						pointA: {
							x: Number(l.point_a?.x ?? l.pointA?.x ?? 0),
							y: Number(l.point_a?.y ?? l.pointA?.y ?? 0),
						},
						pointB: {
							x: Number(l.point_b?.x ?? l.pointB?.x ?? 0),
							y: Number(l.point_b?.y ?? l.pointB?.y ?? 0),
						},
						direction: l.direction === "AB_IS_OUT" ? "AB_IS_OUT" : "AB_IS_IN",
					}))
				: [];
			setEntryLines(lines);

			if (polys.length > 0) {
				setSelectedItem({ type: "POLYGON", index: 0 });
			} else if (lines.length > 0) {
				setSelectedItem({ type: "LINE", index: 0 });
			} else {
				setSelectedItem(null);
			}
		}
	}, [isOpen, initialRoiGeometry, snapshotWidth, snapshotHeight]);

	const handleImageLoad = (e) => {
		if (e.target.naturalWidth && e.target.naturalHeight) {
			setImgDimensions({
				width: e.target.naturalWidth,
				height: e.target.naturalHeight,
			});
		}
	};

	// Convert mouse event to normalized [0.0, 1.0] coordinate
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
		[imgDimensions],
	);

	const handleRadius = Math.max(7, Math.round(imgDimensions.width * 0.006));
	const snapDistance = handleRadius * 2.5;

	// Complete drawing polygon
	const completeCurrentPolygon = useCallback(() => {
		if (draftVertices.length < MIN_VERTICES) {
			setError(`Vùng giám sát phải có ít nhất ${MIN_VERTICES} đỉnh.`);
			return;
		}

		const newPolygon = {
			id: `poly_${Date.now()}`,
			label: `Vùng giám sát ${polygons.length + 1}`,
			vertices: [...draftVertices],
		};

		const nextPolygons = [...polygons, newPolygon];
		setPolygons(nextPolygons);
		setSelectedItem({ type: "POLYGON", index: nextPolygons.length - 1 });
		setDraftVertices([]);
		setCursorPos(null);
		setIsNearFirst(false);
		setDrawMode(null);
		setError(null);
	}, [draftVertices, polygons]);

	// Handle click on SVG
	const handleSvgClick = (e) => {
		if (!drawMode) return;

		const pt = getNormalizedPoint(e);
		if (!pt) return;

		if (drawMode === "POLYGON") {
			if (draftVertices.length >= MIN_VERTICES && isNearFirst) {
				completeCurrentPolygon();
				return;
			}

			if (draftVertices.length > 0) {
				const last = draftVertices[draftVertices.length - 1];
				const dist = Math.hypot(
					(pt.x - last.x) * imgDimensions.width,
					(pt.y - last.y) * imgDimensions.height,
				);
				if (dist < 4) return;
			}

			setDraftVertices((prev) => [...prev, pt]);
		} else if (drawMode === "LINE") {
			if (!draftLineStart) {
				// Point A placed
				setDraftLineStart(pt);
			} else {
				// Point B placed -> complete line
				const dist = Math.hypot(
					(pt.x - draftLineStart.x) * imgDimensions.width,
					(pt.y - draftLineStart.y) * imgDimensions.height,
				);
				if (dist < 10) {
					setError(
						"Điểm kết thúc quá gần điểm bắt đầu. Vui lòng chọn điểm khác.",
					);
					return;
				}

				const newLine = {
					id: `line_${Date.now()}`,
					label: `Đường ranh ${entryLines.length + 1}`,
					pointA: draftLineStart,
					pointB: pt,
					direction: "AB_IS_IN",
				};

				const nextLines = [...entryLines, newLine];
				setEntryLines(nextLines);
				setSelectedItem({ type: "LINE", index: nextLines.length - 1 });
				setDraftLineStart(null);
				setCursorPos(null);
				setDrawMode(null);
				setError(null);
			}
		}
	};

	// Double click on SVG to finish polygon
	const handleSvgDoubleClick = (e) => {
		e.preventDefault();
		if (drawMode === "POLYGON" && draftVertices.length >= MIN_VERTICES) {
			completeCurrentPolygon();
		}
	};

	// Mouse move over SVG
	const handleSvgMouseMove = (e) => {
		const pt = getNormalizedPoint(e);
		if (!pt) return;

		// Handle dragging handles
		if (draggedTarget) {
			if (draggedTarget.type === "POLYGON") {
				setPolygons((prev) =>
					prev.map((poly, pIdx) => {
						if (pIdx !== draggedTarget.pIdx) return poly;
						const nextVertices = [...poly.vertices];
						nextVertices[draggedTarget.vIdx] = pt;
						return { ...poly, vertices: nextVertices };
					}),
				);
			} else if (draggedTarget.type === "LINE") {
				setEntryLines((prev) =>
					prev.map((line, lIdx) => {
						if (lIdx !== draggedTarget.lIdx) return line;
						return {
							...line,
							[draggedTarget.point === "A" ? "pointA" : "pointB"]: pt,
						};
					}),
				);
			}
			return;
		}

		if (drawMode) {
			setCursorPos(pt);

			if (drawMode === "POLYGON" && draftVertices.length >= MIN_VERTICES) {
				const first = draftVertices[0];
				const dist = Math.hypot(
					(pt.x - first.x) * imgDimensions.width,
					(pt.y - first.y) * imgDimensions.height,
				);
				setIsNearFirst(dist <= snapDistance);
			} else {
				setIsNearFirst(false);
			}
		}
	};

	const handleMouseUp = () => {
		if (draggedTarget) {
			setDraggedTarget(null);
		}
	};

	// Keyboard navigation
	useEffect(() => {
		if (!isOpen) return;

		const handleKeyDown = (e) => {
			if (e.key === "Escape") {
				if (drawMode) {
					setDraftVertices([]);
					setDraftLineStart(null);
					setCursorPos(null);
					setIsNearFirst(false);
					setDrawMode(null);
				} else {
					onClose();
				}
			} else if (
				(e.key === "Backspace" || (e.ctrlKey && e.key === "z")) &&
				drawMode === "POLYGON"
			) {
				e.preventDefault();
				setDraftVertices((prev) => prev.slice(0, -1));
			} else if (
				e.key === "Enter" &&
				drawMode === "POLYGON" &&
				draftVertices.length >= MIN_VERTICES
			) {
				e.preventDefault();
				completeCurrentPolygon();
			}
		};

		window.addEventListener("keydown", handleKeyDown);
		return () => window.removeEventListener("keydown", handleKeyDown);
	}, [isOpen, drawMode, draftVertices, completeCurrentPolygon, onClose]);

	// Start Polygon Drawing
	const startDrawingPolygon = () => {
		if (polygons.length >= MAX_POLYGONS) {
			setError(`Đã đạt giới hạn tối đa ${MAX_POLYGONS} vùng giám sát đa giác.`);
			return;
		}
		setError(null);
		setSelectedItem(null);
		setDraftVertices([]);
		setDraftLineStart(null);
		setCursorPos(null);
		setIsNearFirst(false);
		setDrawMode("POLYGON");
	};

	// Start Line Drawing
	const startDrawingLine = () => {
		if (entryLines.length >= MAX_ENTRY_LINES) {
			setError(`Đã đạt giới hạn tối đa ${MAX_ENTRY_LINES} đường ranh ra/vào.`);
			return;
		}
		setError(null);
		setSelectedItem(null);
		setDraftVertices([]);
		setDraftLineStart(null);
		setCursorPos(null);
		setDrawMode("LINE");
	};

	// Cancel Drawing
	const cancelDrawing = () => {
		setDraftVertices([]);
		setDraftLineStart(null);
		setCursorPos(null);
		setIsNearFirst(false);
		setDrawMode(null);
		setError(null);
	};

	// Delete Item
	const handleDeletePolygon = (indexToDelete) => {
		setPolygons((prev) => prev.filter((_, idx) => idx !== indexToDelete));
		if (selectedItem?.type === "POLYGON") {
			if (selectedItem.index === indexToDelete) {
				setSelectedItem(null);
			} else if (selectedItem.index > indexToDelete) {
				setSelectedItem({ type: "POLYGON", index: selectedItem.index - 1 });
			}
		}
	};

	const handleDeleteEntryLine = (indexToDelete) => {
		setEntryLines((prev) => prev.filter((_, idx) => idx !== indexToDelete));
		if (selectedItem?.type === "LINE") {
			if (selectedItem.index === indexToDelete) {
				setSelectedItem(null);
			} else if (selectedItem.index > indexToDelete) {
				setSelectedItem({ type: "LINE", index: selectedItem.index - 1 });
			}
		}
	};

	// Update Selected Item
	const handleUpdatePolygonLabel = (label) => {
		if (selectedItem?.type !== "POLYGON") return;
		setPolygons((prev) =>
			prev.map((p, idx) => (idx === selectedItem.index ? { ...p, label } : p)),
		);
	};

	const handleUpdateLine = (field, value) => {
		if (selectedItem?.type !== "LINE") return;
		setEntryLines((prev) =>
			prev.map((l, idx) =>
				idx === selectedItem.index ? { ...l, [field]: value } : l,
			),
		);
	};

	// Save changes
	const handleSave = async () => {
		if (drawMode) {
			setError(
				"Vui lòng hoàn tất hoặc hủy đối tượng đang vẽ dở trước khi lưu.",
			);
			return;
		}

		if (polygons.length === 0 && entryLines.length === 0) {
			setError("Cần ít nhất 1 vùng giám sát an ninh hoặc 1 đường ranh ra/vào.");
			return;
		}

		// Validate polygons
		for (let i = 0; i < polygons.length; i++) {
			const p = polygons[i];
			if (!p.vertices || p.vertices.length < MIN_VERTICES) {
				setError(
					`Vùng giám sát "${p.label || i + 1}" phải có ít nhất ${MIN_VERTICES} đỉnh.`,
				);
				return;
			}
		}

		// Validate lines
		for (let i = 0; i < entryLines.length; i++) {
			const l = entryLines[i];
			const dist = Math.hypot(
				(l.pointB.x - l.pointA.x) * imgDimensions.width,
				(l.pointB.y - l.pointA.y) * imgDimensions.height,
			);
			if (dist < 10) {
				setError(`Đường ranh "${l.label || i + 1}" có 2 điểm quá gần nhau.`);
				return;
			}
		}

		setSaving(true);
		setError(null);

		const payload = {
			polygons: polygons.map((p) => ({
				label: p.label ? p.label.trim().slice(0, 100) : undefined,
				vertices: p.vertices.map((v) => ({
					x: Number(Math.min(Math.max(Number(v.x) || 0, 0), 1).toFixed(4)),
					y: Number(Math.min(Math.max(Number(v.y) || 0, 0), 1).toFixed(4)),
				})),
			})),
			entry_lines: entryLines.map((l) => ({
				label: l.label ? l.label.trim().slice(0, 100) : undefined,
				point_a: {
					x: Number(
						Math.min(Math.max(Number(l.pointA.x) || 0, 0), 1).toFixed(4),
					),
					y: Number(
						Math.min(Math.max(Number(l.pointA.y) || 0, 0), 1).toFixed(4),
					),
				},
				point_b: {
					x: Number(
						Math.min(Math.max(Number(l.pointB.x) || 0, 0), 1).toFixed(4),
					),
					y: Number(
						Math.min(Math.max(Number(l.pointB.y) || 0, 0), 1).toFixed(4),
					),
				},
				direction: l.direction || "AB_IS_IN",
			})),
		};

		try {
			await onSave(payload, {
				snapshotBase64:
					snapshotBase64 && !snapshotBase64.startsWith("http")
						? snapshotBase64
						: null,
				snapshotWidth: imgDimensions.width || snapshotWidth || 1920,
				snapshotHeight: imgDimensions.height || snapshotHeight || 1080,
			});
			onClose();
		} catch (err) {
			console.error("Failed to save ROI:", err);
			setError(err.message || "Lỗi khi lưu cấu hình ROI");
		} finally {
			setSaving(false);
		}
	};

	// Centroid for polygon label
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

	// Helper to calculate line midpoint for label placement
	const computeLineOrientation = (pointA, pointB) => {
		const ax = pointA.x * imgDimensions.width;
		const ay = pointA.y * imgDimensions.height;
		const bx = pointB.x * imgDimensions.width;
		const by = pointB.y * imgDimensions.height;

		const mx = (ax + bx) / 2;
		const my = (ay + by) / 2;

		return {
			midX: mx,
			midY: my,
		};
	};

	if (!isOpen) return null;

	const selectedPoly =
		selectedItem?.type === "POLYGON" ? polygons[selectedItem.index] : null;
	const selectedLine =
		selectedItem?.type === "LINE" ? entryLines[selectedItem.index] : null;

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
							<Layers
								size={20}
								color="#38bdf8"
							/>
							Cấu hình vùng quan sát của camera
						</h3>
						<span className="roi-counter-badge">
							{polygons.length}/10 vùng • {entryLines.length}/5 đường ranh
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
							disabled={saving || !!drawMode}
						>
							{saving ? (
								<>
									<Loader2
										size={16}
										className="animate-spin"
									/>
									Đang lưu...
								</>
							) : (
								<>
									<Save size={16} />
									Lưu vùng kiểm soát
								</>
							)}
						</button>
						<button
							className="roi-modal-close-btn"
							onClick={onClose}
						>
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
								{!drawMode ? (
									<>
										<button
											className="roi-btn roi-btn-primary"
											onClick={startDrawingPolygon}
											disabled={polygons.length >= MAX_POLYGONS}
											title="Khoanh vùng đa giác để giám sát: Người lạ, Xâm nhập, Ngoài giờ"
										>
											<Plus size={16} />
											Vẽ vùng giám sát (Đa giác)
										</button>
										<button
											className="roi-btn roi-btn-teal"
											onClick={startDrawingLine}
											disabled={entryLines.length >= MAX_ENTRY_LINES}
											title="Vẽ 1 đoạn thẳng A -> B cho mỗi cửa để tự động nhận diện cả 2 chiều Ra và Vào"
										>
											<ArrowRight size={16} />
											Vẽ đường ranh Ra/Vào (Đoạn thẳng)
										</button>
									</>
								) : drawMode === "POLYGON" ? (
									<>
										<button
											className="roi-btn roi-btn-primary"
											onClick={completeCurrentPolygon}
											disabled={draftVertices.length < MIN_VERTICES}
										>
											<Check size={16} />
											Hoàn tất vùng ({draftVertices.length} đỉnh)
										</button>
										<button
											className="roi-btn roi-btn-secondary"
											onClick={() =>
												setDraftVertices((prev) => prev.slice(0, -1))
											}
											disabled={draftVertices.length === 0}
										>
											<RotateCcw size={16} />
											Undo đỉnh
										</button>
										<button
											className="roi-btn roi-btn-danger"
											onClick={cancelDrawing}
										>
											Hủy vẽ (Esc)
										</button>
									</>
								) : (
									<>
										<span
											style={{
												fontSize: "0.85rem",
												color: "#22d3ee",
												fontWeight: 600,
												padding: "0.4rem 0.6rem",
												background: "rgba(6, 182, 212, 0.15)",
												borderRadius: "6px",
											}}
										>
											{draftLineStart
												? "Đang vẽ Điểm B (Click để kết thúc đường ranh)"
												: "Click để đặt Điểm A (bắt đầu)"}
										</span>
										<button
											className="roi-btn roi-btn-danger"
											onClick={cancelDrawing}
										>
											Hủy vẽ (Esc)
										</button>
									</>
								)}
							</div>

							<div className="roi-instruction-text">
								<HelpCircle size={15} />
								{drawMode === "POLYGON" ? (
									<span>
										Click để đặt đỉnh. Click vào{" "}
										<strong>đỉnh đầu (xanh lá)</strong> hoặc double-click để
										hoàn tất.
									</span>
								) : drawMode === "LINE" ? (
									<span>
										Chỉ cần 1 đường cho 1 cửa: Click điểm A, sau đó click điểm B
										để tự động nhận diện cả 2 chiều Ra/Vào.
									</span>
								) : (
									<span>
										Click vào vùng hoặc đường ranh để chọn và chỉnh sửa toạ độ.
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
										ref={imgRef}
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
									className={`roi-svg-overlay ${!drawMode ? "mode-select" : ""}`}
									onClick={handleSvgClick}
									onDoubleClick={handleSvgDoubleClick}
									onMouseMove={handleSvgMouseMove}
								>
									<defs>
										<marker
											id="arrow-head-in"
											viewBox="0 0 10 10"
											refX="6"
											refY="5"
											markerWidth="6"
											markerHeight="6"
											orient="auto-start-reverse"
										>
											<path
												d="M 0 1 L 10 5 L 0 9 z"
												fill="#10b981"
											/>
										</marker>
										<marker
											id="arrow-head-out"
											viewBox="0 0 10 10"
											refX="6"
											refY="5"
											markerWidth="6"
											markerHeight="6"
											orient="auto-start-reverse"
										>
											<path
												d="M 0 1 L 10 5 L 0 9 z"
												fill="#38bdf8"
											/>
										</marker>
										<marker
											id="arrow-line-dir"
											viewBox="0 0 10 10"
											refX="5"
											refY="5"
											markerWidth="5"
											markerHeight="5"
											orient="auto"
										>
											<path
												d="M 0 2 L 8 5 L 0 8 z"
												fill="#06b6d4"
											/>
										</marker>
									</defs>

									{/* Render Saved Polygons */}
									{polygons.map((poly, pIdx) => {
										const isSelected =
											selectedItem?.type === "POLYGON" &&
											selectedItem.index === pIdx;
										const pointsStr = poly.vertices
											.map(
												(v) =>
													`${v.x * imgDimensions.width},${v.y * imgDimensions.height}`,
											)
											.join(" ");
										const centroid = computeCentroid(poly.vertices);

										return (
											<g key={poly.id || pIdx}>
												<polygon
													points={pointsStr}
													className={`roi-polygon-shape ${isSelected ? "selected" : ""}`}
													onClick={(e) => {
														if (!drawMode) {
															e.stopPropagation();
															setSelectedItem({
																type: "POLYGON",
																index: pIdx,
															});
														}
													}}
												>
													<title>{poly.label || `Vùng ${pIdx + 1}`}</title>
												</polygon>

												{poly.vertices && poly.vertices.length >= 3 && (
													<text
														x={centroid.x}
														y={centroid.y}
														className="roi-poly-label-svg"
													>
														{pIdx + 1}. {poly.label || `Vùng ${pIdx + 1}`}
													</text>
												)}

												{isSelected &&
													!drawMode &&
													poly.vertices.map((v, vIdx) => (
														<circle
															key={vIdx}
															cx={v.x * imgDimensions.width}
															cy={v.y * imgDimensions.height}
															r={handleRadius}
															className="roi-edit-handle"
															onMouseDown={(e) => {
																e.stopPropagation();
																setDraggedTarget({
																	type: "POLYGON",
																	pIdx,
																	vIdx,
																});
															}}
														/>
													))}
											</g>
										);
									})}

									{/* Render Saved Entry Lines */}
									{entryLines.map((line, lIdx) => {
										const isSelected =
											selectedItem?.type === "LINE" &&
											selectedItem.index === lIdx;
										const ax = line.pointA.x * imgDimensions.width;
										const ay = line.pointA.y * imgDimensions.height;
										const bx = line.pointB.x * imgDimensions.width;
										const by = line.pointB.y * imgDimensions.height;

										const orient = computeLineOrientation(
											line.pointA,
											line.pointB,
											line.direction,
										);

										return (
											<g key={line.id || lIdx}>
												{/* The main line segment */}
												<line
													x1={ax}
													y1={ay}
													x2={bx}
													y2={by}
													className={`roi-line-shape ${isSelected ? "selected" : ""}`}
													onClick={(e) => {
														if (!drawMode) {
															e.stopPropagation();
															setSelectedItem({ type: "LINE", index: lIdx });
														}
													}}
												/>

												{/* Midpoint line label */}
												{orient && (
													<g className="roi-line-indicator">
														{/* Label showing Line name in the middle */}
														<text
															x={orient.midX}
															y={orient.midY - 8}
															fill="#f8fafc"
															fontSize={12}
															fontWeight={700}
															textAnchor="middle"
															filter="drop-shadow(0 1px 3px rgba(0, 0, 0, 0.95))"
														>
															{line.label || `Ranh ${lIdx + 1}`}
														</text>
													</g>
												)}

												{/* Endpoints A and B */}
												<circle
													cx={ax}
													cy={ay}
													r={handleRadius * 0.9}
													fill="#06b6d4"
													stroke="#ffffff"
													strokeWidth={2}
												/>
												<text
													x={ax}
													y={ay - 10}
													fill="#ffffff"
													fontSize={11}
													fontWeight={700}
													textAnchor="middle"
												>
													A
												</text>

												<circle
													cx={bx}
													cy={by}
													r={handleRadius * 0.9}
													fill="#0891b2"
													stroke="#ffffff"
													strokeWidth={2}
												/>
												<text
													x={bx}
													y={by - 10}
													fill="#ffffff"
													fontSize={11}
													fontWeight={700}
													textAnchor="middle"
												>
													B
												</text>

												{/* Draggable handles when line is selected */}
												{isSelected && !drawMode && (
													<>
														<circle
															cx={ax}
															cy={ay}
															r={handleRadius * 1.2}
															className="roi-edit-handle"
															onMouseDown={(e) => {
																e.stopPropagation();
																setDraggedTarget({
																	type: "LINE",
																	lIdx,
																	point: "A",
																});
															}}
														/>
														<circle
															cx={bx}
															cy={by}
															r={handleRadius * 1.2}
															className="roi-edit-handle"
															onMouseDown={(e) => {
																e.stopPropagation();
																setDraggedTarget({
																	type: "LINE",
																	lIdx,
																	point: "B",
																});
															}}
														/>
													</>
												)}
											</g>
										);
									})}

									{/* Render Draft Polygon currently being drawn */}
									{drawMode === "POLYGON" && draftVertices.length > 0 && (
										<g>
											{draftVertices.length >= 2 && cursorPos && (
												<polygon
													points={[
														...draftVertices.map(
															(v) =>
																`${v.x * imgDimensions.width},${v.y * imgDimensions.height}`,
														),
														`${cursorPos.x * imgDimensions.width},${cursorPos.y * imgDimensions.height}`,
													].join(" ")}
													className="roi-draft-fill"
												/>
											)}

											{draftVertices.length >= 2 && (
												<polyline
													points={draftVertices
														.map(
															(v) =>
																`${v.x * imgDimensions.width},${v.y * imgDimensions.height}`,
														)
														.join(" ")}
													className="roi-draft-line"
												/>
											)}

											{cursorPos && (
												<line
													x1={
														draftVertices[draftVertices.length - 1].x *
														imgDimensions.width
													}
													y1={
														draftVertices[draftVertices.length - 1].y *
														imgDimensions.height
													}
													x2={cursorPos.x * imgDimensions.width}
													y2={cursorPos.y * imgDimensions.height}
													className="roi-draft-guide"
												/>
											)}

											{draftVertices.length >= 2 && cursorPos && (
												<line
													x1={cursorPos.x * imgDimensions.width}
													y1={cursorPos.y * imgDimensions.height}
													x2={draftVertices[0].x * imgDimensions.width}
													y2={draftVertices[0].y * imgDimensions.height}
													className="roi-draft-closing-guide"
												/>
											)}

											{draftVertices.map((v, index) => {
												const isFirst = index === 0;
												return (
													<g key={index}>
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
															r={
																isFirst && isNearFirst
																	? handleRadius * 1.4
																	: handleRadius
															}
															className={`roi-draft-vertex ${
																isFirst ? "roi-draft-vertex--first" : ""
															} ${
																isFirst && isNearFirst
																	? "roi-draft-vertex--closing"
																	: ""
															}`}
														/>

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
																		completeCurrentPolygon();
																	}
																}}
															/>
														)}
													</g>
												);
											})}
										</g>
									)}

									{/* Render Draft Line currently being drawn */}
									{drawMode === "LINE" && draftLineStart && cursorPos && (
										<g>
											<line
												x1={draftLineStart.x * imgDimensions.width}
												y1={draftLineStart.y * imgDimensions.height}
												x2={cursorPos.x * imgDimensions.width}
												y2={cursorPos.y * imgDimensions.height}
												stroke="#06b6d4"
												strokeWidth={3}
												strokeDasharray="6 4"
											/>
											<circle
												cx={draftLineStart.x * imgDimensions.width}
												cy={draftLineStart.y * imgDimensions.height}
												r={handleRadius}
												fill="#06b6d4"
												stroke="#ffffff"
												strokeWidth={2}
											/>
											<text
												x={draftLineStart.x * imgDimensions.width}
												y={draftLineStart.y * imgDimensions.height - 10}
												fill="#ffffff"
												fontSize={11}
												fontWeight={700}
												textAnchor="middle"
											>
												A
											</text>
											<circle
												cx={cursorPos.x * imgDimensions.width}
												cy={cursorPos.y * imgDimensions.height}
												r={handleRadius * 0.8}
												fill="#f59e0b"
												stroke="#ffffff"
												strokeWidth={1.5}
											/>
										</g>
									)}
								</svg>
							</div>
						</div>
					</div>

					{/* Sidebar Section */}
					<div className="roi-sidebar">
						<div className="roi-sidebar-header">
							<span>Danh sách hình học ROI</span>
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

							{/* Group 1: Surveillance Polygons */}
							<div className="roi-section-group">
								<div className="roi-section-heading">
									<span>1. Vùng giám sát an ninh ({polygons.length}/10)</span>
								</div>
								<div className="roi-polygon-list">
									{polygons.length === 0 ? (
										<div className="roi-empty-state">
											Chưa có vùng giám sát an ninh.
										</div>
									) : (
										polygons.map((poly, idx) => {
											const isSelected =
												selectedItem?.type === "POLYGON" &&
												selectedItem.index === idx;
											return (
												<div
													key={poly.id || idx}
													className={`roi-polygon-item ${isSelected ? "active" : ""}`}
													onClick={() =>
														setSelectedItem({ type: "POLYGON", index: idx })
													}
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
							</div>

							{/* Group 2: Entry/Exit Lines */}
							<div className="roi-section-group">
								<div className="roi-section-heading">
									<span>2. Đường ranh Ra/Vào ({entryLines.length}/5)</span>
								</div>
								<div className="roi-polygon-list">
									{entryLines.length === 0 ? (
										<div className="roi-empty-state">
											Chưa có đường ranh ra/vào.
										</div>
									) : (
										entryLines.map((line, idx) => {
											const isSelected =
												selectedItem?.type === "LINE" &&
												selectedItem.index === idx;
											const isInRight = line.direction === "AB_IS_IN";
											return (
												<div
													key={line.id || idx}
													className={`roi-polygon-item ${isSelected ? "active" : ""}`}
													onClick={() =>
														setSelectedItem({ type: "LINE", index: idx })
													}
												>
													<div className="roi-polygon-item-left">
														<span className="roi-line-badge">{idx + 1}</span>
														<div>
															<div className="roi-poly-label">
																{line.label || `Đường ranh ${idx + 1}`}
															</div>
														</div>
													</div>

													<button
														className="roi-poly-delete-btn"
														title="Xóa đường ranh"
														onClick={(e) => {
															e.stopPropagation();
															handleDeleteEntryLine(idx);
														}}
													>
														<Trash2 size={16} />
													</button>
												</div>
											);
										})
									)}
								</div>
							</div>

							{/* Form 1: Edit Selected Polygon */}
							{selectedPoly && (
								<div className="roi-edit-form">
									<div className="roi-form-title">
										<Move
											size={15}
											color="#eab308"
										/>
										<span>
											Chi tiết vùng:{" "}
											{selectedPoly.label || `Vùng ${selectedItem.index + 1}`}
										</span>
									</div>

									<div className="roi-form-group">
										<label className="roi-form-label">Tên vùng giám sát</label>
										<input
											type="text"
											className="roi-form-input"
											value={selectedPoly.label || ""}
											maxLength={100}
											placeholder="VD: Sảnh chính, Khu làm việc..."
											onChange={(e) => handleUpdatePolygonLabel(e.target.value)}
										/>
									</div>

									<div className="roi-info-banner">
										<div className="roi-info-banner-title">
											<ShieldAlert size={15} />
											Tự động giám sát 3 sự cố an ninh:
										</div>
										<div>
											Đa giác ROI giám sát sẽ tự động đối chiếu phân tích 3 sự
											cố cho khu vực gán của camera:
										</div>
										<div className="roi-tag-list">
											<span className="roi-tag roi-tag-alert">
												1. Người lạ (UNKNOWN)
											</span>
											<span className="roi-tag roi-tag-alert">
												2. Xâm nhập (UNAUTHORIZED)
											</span>
											<span className="roi-tag roi-tag-alert">
												3. Ngoài giờ (AFTER_HOURS)
											</span>
										</div>
									</div>
								</div>
							)}

							{/* Form 2: Edit Selected Entry Line */}
							{selectedLine && (
								<div className="roi-edit-form">
									<div className="roi-form-title">
										<ArrowLeftRight
											size={15}
											color="#06b6d4"
										/>
										<span>
											Chi tiết đường ranh:{" "}
											{selectedLine.label || `Ranh ${selectedItem.index + 1}`}
										</span>
									</div>

									<div className="roi-form-group">
										<label className="roi-form-label">Tên đường ranh</label>
										<input
											type="text"
											className="roi-form-input"
											value={selectedLine.label || ""}
											maxLength={100}
											placeholder="VD: Cổng vào chính, Cửa tầng 1..."
											onChange={(e) =>
												handleUpdateLine("label", e.target.value)
											}
										/>
									</div>

									<div
										className="roi-info-banner"
										style={{
											background: "rgba(6, 182, 212, 0.08)",
											borderColor: "rgba(6, 182, 212, 0.25)",
											marginTop: "0.5rem",
										}}
									>
										<div
											className="roi-info-banner-title"
											style={{ color: "#22d3ee" }}
										>
											<ArrowLeftRight size={15} />
											Đường ranh ghi nhận ra vào:
										</div>
										<div
											style={{
												fontSize: "0.825rem",
												color: "#cbd5e1",
												lineHeight: 1.45,
											}}
										>
											Đoạn thẳng này được dùng để phát hiện và ghi nhận nhật ký
											người đi qua lại cửa/lối vào (Access Log). Không cần phân
											biệt chiều ra vào khi vẽ.
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
