import React, { useState, useEffect, useRef, useCallback } from "react";
import {
	X,
	Plus,
	Trash2,
	RotateCcw,
	Check,
	Layers,
	HelpCircle,
	Loader2,
	Move,
} from "lucide-react";
import "../../styles/RoiEditorModal.css";
import { formatImageUrl } from "../../utils/roiUtils";
import { useAuthorizedImage } from "../../hooks/useAuthorizedImage";

const MAX_POLYGONS = 10;
const MIN_VERTICES = 3;
const MIN_AREA_PERCENT = 5;
const MAX_RECOMMENDED_AREA_PERCENT = 90;

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
	const [selectedItem, setSelectedItem] = useState(null); // { type: "POLYGON", index: number }

	// Drawing state
	const [drawMode, setDrawMode] = useState(null); // null | "POLYGON"
	const [draftVertices, setDraftVertices] = useState([]);
	const [cursorPos, setCursorPos] = useState(null);
	const [isNearFirst, setIsNearFirst] = useState(false);

	// Dragging state
	const [draggedTarget, setDraggedTarget] = useState(null); // { type: "POLYGON", pIdx, vIdx }

	const [saving, setSaving] = useState(false);
	const [error, setError] = useState(null);

	const [imgDimensions, setImgDimensions] = useState({
		width: snapshotWidth || 1920,
		height: snapshotHeight || 1080,
	});

	const svgRef = useRef(null);
	const imgRef = useRef(null);
	const canvasWrapperRef = useRef(null);
	// Kích thước khung vẽ (px) vừa khít vùng trống thật của .roi-canvas-wrapper, giữ đúng tỉ lệ khung hình camera.
	// Trước đây ảnh chỉ bị giới hạn bằng max-height ước lượng (92vh - 160px) nên hay tràn và bị cắt sát mép.
	const [fitSize, setFitSize] = useState(null);

	useEffect(() => {
		if (!isOpen) return undefined;
		const wrapper = canvasWrapperRef.current;
		if (!wrapper) return undefined;
		const ratio = imgDimensions.width > 0 && imgDimensions.height > 0
			? imgDimensions.width / imgDimensions.height
			: 16 / 9;
		const recompute = () => {
			const cs = window.getComputedStyle(wrapper);
			const availW = wrapper.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
			const availH = wrapper.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
			if (availW <= 0 || availH <= 0) return;
			let w = availW;
			let h = w / ratio;
			if (h > availH) {
				h = availH;
				w = h * ratio;
			}
			const next = { width: Math.floor(w), height: Math.floor(h) };
			setFitSize((prev) =>
				prev && prev.width === next.width && prev.height === next.height ? prev : next,
			);
		};
		recompute();
		if (typeof ResizeObserver === "undefined") {
			window.addEventListener("resize", recompute);
			return () => window.removeEventListener("resize", recompute);
		}
		const ro = new ResizeObserver(recompute);
		ro.observe(wrapper);
		return () => ro.disconnect();
	}, [isOpen, imgDimensions.width, imgDimensions.height]);

	// URL MinIO (http://host:9000/bucket/key) -> /api/media/bucket/key; ảnh /api/media tải kèm JWT qua useAuthorizedImage
	const formattedSnapshot = snapshotBase64
		? formatImageUrl(snapshotBase64)
		: null;

	const rawImageSrc = formattedSnapshot
		? formattedSnapshot.startsWith("data:") ||
			formattedSnapshot.startsWith("http") ||
			formattedSnapshot.startsWith("/api/media/")
			? formattedSnapshot
			: `data:image/jpeg;base64,${formattedSnapshot}`
		: null;
	const {
		url: imageSrc,
		loading: imageLoading,
		error: imageError,
	} = useAuthorizedImage(rawImageSrc);

	// Initialize from initialRoiGeometry
	useEffect(() => {
		if (isOpen) {
			setError(null);
			setDrawMode(null);
			setDraftVertices([]);
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

			// Polygons
			const polys = Array.isArray(initialRoiGeometry?.polygons)
				? initialRoiGeometry.polygons.map((p, idx) => ({
						id: `poly_${Date.now()}_${idx}`,
						label: p.label || `Vùng phát hiện xâm nhập ${idx + 1}`,
						vertices: (p.vertices || []).map((v) => ({
							x: Number(v.x),
							y: Number(v.y),
						})),
					}))
				: [];
			setPolygons(polys);

			if (polys.length > 0) {
				setSelectedItem({ type: "POLYGON", index: 0 });
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
			setError(`Vùng phát hiện xâm nhập phải có ít nhất ${MIN_VERTICES} đỉnh.`);
			return;
		}

		const newPolygon = {
			id: `poly_${Date.now()}`,
			label: `Vùng phát hiện xâm nhập ${polygons.length + 1}`,
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
			setError(
				`Đã đạt giới hạn tối đa ${MAX_POLYGONS} vùng phát hiện xâm nhập.`,
			);
			return;
		}
		setError(null);
		setSelectedItem(null);
		setDraftVertices([]);
		setCursorPos(null);
		setIsNearFirst(false);
		setDrawMode("POLYGON");
	};

	// Cancel Drawing
	const cancelDrawing = () => {
		setDraftVertices([]);
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

	// Update Selected Item
	const handleUpdatePolygonLabel = (label) => {
		if (selectedItem?.type !== "POLYGON") return;
		setPolygons((prev) =>
			prev.map((p, idx) => (idx === selectedItem.index ? { ...p, label } : p)),
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

		if (polygons.length < 1) {
			setError(
				"Camera phải có ít nhất 1 vùng phát hiện xâm nhập (tối đa 10 vùng) trước khi lưu.",
			);
			return;
		}

		// Validate polygons (1..10 vùng)
		for (let i = 0; i < polygons.length; i++) {
			const p = polygons[i];
			if (!p.vertices || p.vertices.length < MIN_VERTICES) {
				setError(
					`Vùng phát hiện xâm nhập "${p.label || i + 1}" phải có ít nhất ${MIN_VERTICES} đỉnh.`,
				);
				return;
			}
			const areaPct = computeAreaPercent(p.vertices);
			if (areaPct < MIN_AREA_PERCENT) {
				setError(
					`Vùng "${p.label || i + 1}" quá nhỏ (${areaPct.toFixed(2)}% < ${MIN_AREA_PERCENT}% khung hình), dễ gây bỏ sót xâm nhập. Vui lòng mở rộng vùng.`,
				);
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
		};

		const isNewBase64 =
			snapshotBase64 &&
			typeof snapshotBase64 === "string" &&
			(snapshotBase64.startsWith("data:image") ||
				(!snapshotBase64.startsWith("http") &&
					!snapshotBase64.startsWith("/api/")));

		try {
			await onSave(payload, {
				snapshotBase64: isNewBase64 ? snapshotBase64 : null,
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

	// Shoelace formula on normalized [0,1] coordinates -> area percentage (0..100)
	const computeAreaPercent = (vertices) => {
		if (!vertices || vertices.length < 3) return 0;
		let sum = 0;
		for (let i = 0; i < vertices.length; i++) {
			const curr = vertices[i];
			const next = vertices[(i + 1) % vertices.length];
			sum += curr.x * next.y - next.x * curr.y;
		}
		return Math.abs(sum) * 50;
	};

	if (!isOpen) return null;

	const selectedPoly =
		selectedItem?.type === "POLYGON" ? polygons[selectedItem.index] : null;

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
							Cấu hình vùng phát hiện xâm nhập
						</h3>
						<span className="roi-counter-badge">
							{polygons.length}/10 vùng (tối thiểu 1)
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
								"Lưu cấu hình"
							)}
						</button>
						<button
							className="roi-modal-close-btn"
							onClick={onClose}
							aria-label="Đóng"
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
									<button
										className="roi-btn roi-btn-primary"
										onClick={startDrawingPolygon}
										disabled={polygons.length >= MAX_POLYGONS}
										title="Khoanh vùng đa giác để phát hiện xâm nhập"
									>
										<Plus size={16} />
										Vẽ vùng mới
									</button>
								) : (
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
								)}
							</div>

							<div className="roi-instruction-text">
								<HelpCircle size={15} />
								{drawMode === "POLYGON" ? (
									<span>
										Nhấp chuột lên ảnh để thêm đỉnh. Nhấp vào đỉnh đầu tiên
										(vòng vàng) hoặc nhấn Enter để khép kín hình.
									</span>
								) : (
									<span>
										Click vào vùng để chọn và chỉnh sửa toạ độ. Kéo thả các đỉnh
										để di chuyển.
									</span>
								)}
							</div>
						</div>

						{/* Snapshot Canvas Container */}
						<div className="roi-canvas-wrapper" ref={canvasWrapperRef}>
							<div
								className={`roi-viewport ${fitSize ? "roi-viewport--fitted" : ""}`}
								style={{
									position: "relative",
									display: "inline-block",
									lineHeight: 0,
									...(fitSize ? { width: `${fitSize.width}px`, height: `${fitSize.height}px` } : {}),
								}}
							>
								{imageSrc ? (
									<img
										ref={imgRef}
										src={imageSrc}
										alt="Camera Snapshot Reference"
										className="roi-snapshot-img"
										onLoad={handleImageLoad}
										draggable={false}
									/>
								) : imageError ? (
									<div className="roi-no-image">
										<p>Không tải được ảnh</p>
									</div>
								) : imageLoading ? (
									<div className="roi-no-image">
										<p>Đang tải ảnh...</p>
									</div>
								) : (
									<div
										className="roi-grid-canvas"
										aria-label="Khung lưới 16:9"
									>
										<span className="roi-grid-status-pill">
											Khung lưới chuẩn hóa 16:9 • Camera chưa phát Live
										</span>
									</div>
								)}

								{/* Interactive SVG Overlay */}
								<svg
									ref={svgRef}
									viewBox={`0 0 ${imgDimensions.width} ${imgDimensions.height}`}
									preserveAspectRatio="none"
									className={`roi-svg-overlay ${drawMode ? "drawing" : ""}`}
									onClick={handleSvgClick}
									onDoubleClick={handleSvgDoubleClick}
									onMouseMove={handleSvgMouseMove}
								>
									{/* Render Existing Polygons */}
									{polygons.map((poly, pIdx) => {
										const isSelected =
											selectedItem?.type === "POLYGON" &&
											selectedItem.index === pIdx;
										const centroid = computeCentroid(poly.vertices);
										const pts = (poly.vertices || [])
											.map(
												(v) =>
													`${v.x * imgDimensions.width},${v.y * imgDimensions.height}`,
											)
											.join(" ");

										return (
											<g key={poly.id || pIdx}>
												<polygon
													points={pts}
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
												/>

												{/* Center Label for Polygon */}
												<text
													x={centroid.x}
													y={centroid.y}
													className="roi-poly-label-svg"
													style={{
														fontSize: `${Math.max(16, Math.round(imgDimensions.width * 0.016))}px`,
													}}
												>
													{poly.label || `Vùng phát hiện xâm nhập ${pIdx + 1}`}
												</text>

												{/* Draggable vertex handles */}
												{isSelected &&
													!drawMode &&
													(poly.vertices || []).map((v, vIdx) => {
														const cx = v.x * imgDimensions.width;
														const cy = v.y * imgDimensions.height;
														return (
															<circle
																key={vIdx}
																cx={cx}
																cy={cy}
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
														);
													})}
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
																className="roi-snap-ring"
															/>
														)}

														<circle
															cx={v.x * imgDimensions.width}
															cy={v.y * imgDimensions.height}
															r={handleRadius}
															className={`roi-draft-handle ${
																isFirst && isNearFirst
																	? "roi-draft-handle-snap"
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
								</svg>
							</div>
						</div>
					</div>

					{/* Sidebar Section */}
					<div className="roi-sidebar">
						<div className="roi-sidebar-header">
							<span>Vùng phát hiện xâm nhập</span>
						</div>

						{error && (
							<div
								className="roi-error-banner"
								role="alert"
							>
								{error}
							</div>
						)}

						<div className="roi-sidebar-content">
							{/* Polygons */}
							<div className="roi-section-group">
								<div className="roi-section-heading">
									<span>Danh sách vùng ({polygons.length}/10)</span>
								</div>
								<div className="roi-polygon-list">
									{polygons.length === 0 ? (
										<div className="roi-empty-state">
											<p className="roi-empty-state-title">
												Chưa có vùng phát hiện xâm nhập nào.
											</p>
											<span className="roi-empty-state-hint">
												Vui lòng vẽ ít nhất 1 vùng phát hiện xâm nhập (tối đa 10
												vùng) trước khi lưu.
											</span>
										</div>
									) : (
										polygons.map((poly, idx) => {
											const isSelected =
												selectedItem?.type === "POLYGON" &&
												selectedItem.index === idx;
											const areaPct = computeAreaPercent(poly.vertices);
											const isTooSmall = areaPct < MIN_AREA_PERCENT;
											const isTooLarge = areaPct > MAX_RECOMMENDED_AREA_PERCENT;
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
																{poly.label ||
																	`Vùng phát hiện xâm nhập ${idx + 1}`}
															</div>
															<div className="roi-poly-meta">
																{poly.vertices?.length || 0} đỉnh •{" "}
																{areaPct.toFixed(1)}% khung hình
															</div>
															{isTooSmall && (
																<div className="roi-area-error">
																	Quá nhỏ (&lt; {MIN_AREA_PERCENT}%), dễ bỏ sót
																</div>
															)}
															{isTooLarge && (
																<div className="roi-area-warning">
																	Phủ &gt; {MAX_RECOMMENDED_AREA_PERCENT}%, dễ báo giả
																</div>
															)}
														</div>
													</div>

													<button
														className="roi-poly-delete-btn"
														title="Xóa vùng này"
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

							{/* Form: Edit Selected Polygon */}
							{selectedPoly && (
								<div className="roi-edit-form">
									<div className="roi-form-title">
										<Move
											size={15}
											color="#eab308"
										/>
										<span>Chi tiết vùng: </span>
									</div>

									<div className="roi-form-group">
										<label className="roi-form-label">Tên</label>
										<input
											type="text"
											className="roi-form-input"
											value={selectedPoly.label || ""}
											maxLength={100}
											placeholder="VD: Sảnh chính, Khu làm việc..."
											onChange={(e) => handleUpdatePolygonLabel(e.target.value)}
										/>
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
