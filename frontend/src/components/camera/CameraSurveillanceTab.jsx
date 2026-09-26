import React from "react";
import {
	Radio,
	Camera as CameraIcon,
	Scan,
	RefreshCw,
	Loader2,
	X,
	CheckCircle2,
	Layers,
	AlertTriangle,
	ShieldAlert,
	ArrowRight,
	LogIn,
	LogOut,
} from "lucide-react";

export function formatImageUrl(url) {
	if (!url) return "";
	if (url.includes("minio:9000")) {
		return url.replace("minio:9000", "localhost:9000");
	}
	return url;
}

export function computePolygonCentroid(vertices) {
	if (!vertices || vertices.length === 0) return { x: 0.5, y: 0.5 };
	let sumX = 0;
	let sumY = 0;
	vertices.forEach((v) => {
		sumX += Number(v.x) || 0;
		sumY += Number(v.y) || 0;
	});
	return {
		x: sumX / vertices.length,
		y: sumY / vertices.length,
	};
}

export function computeLineOrientation(
	pointA,
	pointB,
	direction,
	width,
	height,
) {
	const ax = (Number(pointA?.x) || 0) * width;
	const ay = (Number(pointA?.y) || 0) * height;
	const bx = (Number(pointB?.x) || 0) * width;
	const by = (Number(pointB?.y) || 0) * height;
	const mx = (ax + bx) / 2;
	const my = (ay + by) / 2;

	return {
		ax,
		ay,
		bx,
		by,
		midX: mx,
		midY: my,
	};
}

export default function CameraSurveillanceTab({
	camera,
	connecting,
	capturingSnapshot,
	refreshingLive,
	compareMode,
	liveSnapshot,
	onConnectCamera,
	onCaptureNewSnapshot,
	onEditCurrentRoi,
	onCheckDrift,
	onCloseCompare,
}) {
	const rawRefUrl =
		camera?.roiGeometry?.reference_snapshot_url ||
		camera?.roiGeometry?.referenceSnapshotUrl;
	const refUrl = formatImageUrl(rawRefUrl);
	const refWidth =
		camera?.roiGeometry?.reference_snapshot_width ||
		camera?.roiGeometry?.referenceSnapshotWidth ||
		1920;
	const refHeight =
		camera?.roiGeometry?.reference_snapshot_height ||
		camera?.roiGeometry?.referenceSnapshotHeight ||
		1080;
	const refCapturedAt =
		camera?.roiGeometry?.reference_captured_at ||
		camera?.roiGeometry?.referenceCapturedAt;

	const roiPolygons = camera?.roiGeometry?.polygons || [];
	const roiEntryLines =
		camera?.roiGeometry?.entry_lines || camera?.roiGeometry?.entryLines || [];

	const totalRoiCount = roiPolygons.length + roiEntryLines.length;

	// Render SVG Shapes helper
	const renderRoiSvgElements = (width, height, isDrift = false) => (
		<svg
			viewBox={`0 0 ${width} ${height}`}
			preserveAspectRatio="none"
			className="roi-preview-svg"
		>
			<defs>
				<marker
					id={`arrow-in-${isDrift ? "drift" : "ref"}`}
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
					id={`arrow-out-${isDrift ? "drift" : "ref"}`}
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
			</defs>

			{/* 1. Polygons */}
			{roiPolygons.map((poly, idx) => {
				const pts = (poly.vertices || [])
					.map(
						(v) =>
							`${(Number(v.x) || 0) * width},${(Number(v.y) || 0) * height}`,
					)
					.join(" ");
				const centroid = computePolygonCentroid(poly.vertices);
				return (
					<g key={`poly-${idx}`}>
						<polygon
							points={pts}
							className={`roi-preview-poly ${
								isDrift ? "roi-preview-poly--drift" : "roi-preview-poly--ref"
							}`}
						>
							<title>{poly.label || `Vùng ${idx + 1}`}</title>
						</polygon>
						{poly.vertices && poly.vertices.length >= 3 && (
							<text
								x={centroid.x * width}
								y={centroid.y * height}
								className="roi-poly-label-svg"
							>
								{idx + 1}. {poly.label || `Vùng ${idx + 1}`}
							</text>
						)}
					</g>
				);
			})}

			{/* 2. Entry Lines */}
			{roiEntryLines.map((line, idx) => {
				const orient = computeLineOrientation(
					line.point_a || line.pointA,
					line.point_b || line.pointB,
					line.direction || "AB_IS_IN",
					width,
					height,
				);
				if (!orient) return null;

				return (
					<g key={`line-${idx}`}>
						{/* Main line */}
						<line
							x1={orient.ax}
							y1={orient.ay}
							x2={orient.bx}
							y2={orient.by}
							stroke="#06b6d4"
							strokeWidth={3}
							strokeLinecap="round"
						/>

						{/* Point A */}
						<circle
							cx={orient.ax}
							cy={orient.ay}
							r={6}
							fill="#06b6d4"
							stroke="#ffffff"
							strokeWidth={1.5}
						/>

						{/* Point B */}
						<circle
							cx={orient.bx}
							cy={orient.by}
							r={6}
							fill="#0891b2"
							stroke="#ffffff"
							strokeWidth={1.5}
						/>

						{/* Text label in the middle */}
						<text
							x={orient.midX}
							y={orient.midY - 8}
							fill="#ffffff"
							fontSize={12}
							fontWeight={700}
							textAnchor="middle"
							filter="drop-shadow(0 1px 2px rgba(0, 0, 0, 0.8))"
						>
							{line.label || `Ranh ${idx + 1}`}
						</text>
					</g>
				);
			})}
		</svg>
	);

	return (
		<div className="roi-config-section">
			<div className="roi-section-header">
				<div className="roi-section-info">
					<p>
						Quản lý vùng giám sát an ninh (Tự động phát hiện Người lạ,
						Người không có thẩm quyền truy cập, Ngoài giờ) và đường ranh Ra/Vào (ghi nhận Access Log).
					</p>
				</div>

				<div className="roi-action-bar">
					<button
						type="button"
						className="btn-connect"
						onClick={onConnectCamera}
						disabled={connecting || capturingSnapshot || refreshingLive}
						title="Kết nối luồng RTSP camera và cập nhật trạng thái hoạt động"
					>
						{connecting ? (
							<>
								<Loader2
									className="animate-spin"
									size={16}
								/>
								<span>Đang kết nối RTSP...</span>
							</>
						) : (
							<>
								<Radio size={16} />
								<span>Kết nối Camera</span>
							</>
						)}
					</button>

					<button
						type="button"
						className="btn-connect"
						onClick={onCaptureNewSnapshot}
						disabled={connecting || capturingSnapshot || refreshingLive}
						title="Trích xuất khung hình mới từ camera để thiết lập hoặc thay thế ROI"
					>
						{capturingSnapshot ? (
							<>
								<Loader2
									className="animate-spin"
									size={16}
								/>
								<span>Đang chụp hình...</span>
							</>
						) : (
							<>
								<CameraIcon size={16} />
								<span>Chụp ảnh mới</span>
							</>
						)}
					</button>

					{refUrl && (
						<>
							<button
								type="button"
								className="btn-open-editor"
								onClick={onEditCurrentRoi}
								disabled={connecting || capturingSnapshot || refreshingLive}
								title="Chỉnh sửa các polygon ROI và đường ranh trên ảnh tham chiếu hiện tại"
							>
								<Scan size={16} />
								<span>Edit vùng ROI</span>
							</button>

							<button
								type="button"
								className={`btn-refresh-compare ${
									compareMode ? "btn-refresh-compare--active" : ""
								}`}
								onClick={onCheckDrift}
								disabled={refreshingLive || connecting || capturingSnapshot}
								title="Lấy khung hình thực tế hiện tại để kiểm tra xem góc quan sát camera có bị sai lệch không"
							>
								{refreshingLive ? (
									<>
										<Loader2
											className="animate-spin"
											size={16}
										/>
										<span>Đang kiểm tra...</span>
									</>
								) : (
									<>
										<RefreshCw size={16} />
										<span>Kiểm tra sai lệch</span>
									</>
								)}
							</button>
						</>
					)}
				</div>
			</div>

			{/* Drift Inspection Warning Banner in Compare Mode */}
			{compareMode && liveSnapshot && (
				<div className="drift-warning-banner">
					<div className="drift-warning-icon">
						<AlertTriangle size={22} />
					</div>
					<div className="drift-warning-content">
						<h5>Kiểm Tra Sai Lệch Khung Hình Camera</h5>
						<p>
							Đối chiếu trực quan giữa <strong>Ảnh tham chiếu gốc</strong> và{" "}
							<strong>Khung hình thực tế hiện tại</strong>. Giúp nhận biết
							camera có bị rung lắc, xoay góc hoặc dịch chuyển vị trí thực địa
							so với khi lắp đặt.
						</p>
					</div>
					<div className="drift-warning-actions">
						<button
							type="button"
							className="btn-close-compare"
							onClick={onCloseCompare}
							title="Đóng chế độ kiểm tra sai lệch"
						>
							<X size={15} />
							<span>Đóng kiểm tra</span>
						</button>
					</div>
				</div>
			)}

			{/* Dual Comparison Mode or Single Reference Preview */}
			{compareMode && liveSnapshot ? (
				<div className="roi-compare-grid">
					{/* Left: Reference Snapshot */}
					<div className="roi-compare-card">
						<div className="roi-compare-header">
							<div className="roi-compare-badge roi-compare-badge--ref">
								<span>Ảnh tham chiếu gốc</span>
							</div>
						</div>

						<div className="roi-preview-wrapper">
							<div className="roi-preview-stage">
								<img
									src={refUrl}
									alt="Reference Snapshot"
									className="roi-preview-img"
								/>
								{totalRoiCount > 0 &&
									renderRoiSvgElements(refWidth, refHeight, false)}
							</div>
						</div>

						<div className="roi-preview-meta">
							<span className="roi-meta-badge">
								Độ phân giải: {refWidth}x{refHeight}
							</span>
							<span>
								{roiPolygons.length} vùng • {roiEntryLines.length} đường ranh
							</span>
						</div>
					</div>

					{/* Right: Live Snapshot */}
					<div className="roi-compare-card">
						<div className="roi-compare-header">
							<div className="roi-compare-badge roi-compare-badge--live">
								<span className="pulse-dot" />
								<span>Khung hình thực tế</span>
							</div>
						</div>

						<div className="roi-preview-wrapper">
							<div className="roi-preview-stage">
								<img
									src={liveSnapshot.snapshotBase64}
									alt="Live Snapshot"
									className="roi-preview-img"
								/>
								{totalRoiCount > 0 &&
									renderRoiSvgElements(
										liveSnapshot.width || 1920,
										liveSnapshot.height || 1080,
										true,
									)}
							</div>
						</div>

						<div className="roi-preview-meta">
							<span
								className="roi-meta-badge"
								style={{ color: "#34d399" }}
							>
								Độ phân giải: {liveSnapshot.width || 1920}x
								{liveSnapshot.height || 1080}
							</span>
							<span style={{ color: "#f59e0b", fontWeight: 600 }}>
								Đang kiểm tra sai lệch
							</span>
						</div>
					</div>
				</div>
			) : (
				/* Single Reference Preview Mode */
				<div className="roi-single-preview">
					{refUrl ? (
						<>
							<div className="roi-preview-wrapper">
								<div className="roi-preview-stage">
									<img
										src={refUrl}
										alt="Current Reference Snapshot"
										className="roi-preview-img"
									/>
									{totalRoiCount > 0 &&
										renderRoiSvgElements(refWidth, refHeight, false)}
								</div>
							</div>
							<div className="roi-preview-meta">
								<span className="roi-meta-badge">
									<CheckCircle2
										size={14}
										className="text-emerald-400"
									/>
									Ảnh tham chiếu ({refWidth}x{refHeight})
								</span>
								<span>
									Thời gian lưu:{" "}
									{refCapturedAt
										? new Date(refCapturedAt).toLocaleString("vi-VN")
										: "Đã lưu trên MinIO"}
								</span>
							</div>
						</>
					) : (
						<div className="roi-preview-placeholder">
							<Layers size={44} />
							<p
								style={{
									fontWeight: 600,
									fontSize: "1rem",
									color: "var(--theme-text-primary)",
								}}
							>
								Chưa cấu hình vùng giám sát
							</p>
							<p>
								Camera này chưa có ảnh tham chiếu và vùng ROI. Bấm nút{" "}
								<strong>"Chụp ảnh mới"</strong> để trích xuất khung hình từ
								camera và bắt đầu khoanh vùng giám sát an ninh hoặc đặt đường
								ranh ra/vào.
							</p>
						</div>
					)}
				</div>
			)}
		</div>
	);
}
