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
} from "lucide-react";

export const RULE_LABEL_MAP = {
	ENTRY_EXIT_TRACKING: "Ghi nhận Ra/Vào",
	LOITERING: "Lảng vảng",
	CROWD_OVERCROWDING: "Đám đông",
};

export function formatRuleBadge(rule) {
	return RULE_LABEL_MAP[rule] || rule;
}

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

	return (
		<div className="roi-config-section">
			<div className="roi-section-header">
				<div className="roi-section-info">
					<p>
						Quản lý các vùng phát hiện xâm nhập (ROI) và cấu hình quy tắc phát
						hiện sự kiện AI.
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
								title="Chỉnh sửa các polygon ROI trên ảnh tham chiếu hiện tại"
							>
								<Scan size={16} />
								<span>Sửa vùng ROI</span>
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
							Đối chiếu trực quan giữa <strong>Ảnh tham chiếu gốc </strong> và{" "}
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
								{roiPolygons.length > 0 && (
									<svg
										viewBox={`0 0 ${refWidth} ${refHeight}`}
										preserveAspectRatio="none"
										className="roi-preview-svg"
									>
										{roiPolygons.map((poly, idx) => {
											const pts = (poly.vertices || [])
												.map(
													(v) =>
														`${(Number(v.x) || 0) * refWidth},${(Number(v.y) || 0) * refHeight}`,
												)
												.join(" ");
											const centroid = computePolygonCentroid(poly.vertices);
											return (
												<g key={idx}>
													<polygon
														points={pts}
														className="roi-preview-poly roi-preview-poly--ref"
													>
														<title>{poly.label || `Vùng ${idx + 1}`}</title>
													</polygon>
													{poly.vertices && poly.vertices.length >= 3 && (
														<text
															x={centroid.x * refWidth}
															y={centroid.y * refHeight}
															className="roi-poly-label-svg"
														>
															{idx + 1}. {poly.label || `Vùng ${idx + 1}`}
														</text>
													)}
												</g>
											);
										})}
									</svg>
								)}
							</div>
						</div>

						<div className="roi-preview-meta">
							<span className="roi-meta-badge">
								Độ phân giải: {refWidth}x{refHeight}
							</span>
							<span>{roiPolygons.length} vùng ROI đã lưu</span>
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
								{roiPolygons.length > 0 && (
									<svg
										viewBox={`0 0 ${liveSnapshot.width || 1920} ${liveSnapshot.height || 1080}`}
										preserveAspectRatio="none"
										className="roi-preview-svg"
									>
										{roiPolygons.map((poly, idx) => {
											const sw = liveSnapshot.width || 1920;
											const sh = liveSnapshot.height || 1080;
											const pts = (poly.vertices || [])
												.map(
													(v) =>
														`${(Number(v.x) || 0) * sw},${(Number(v.y) || 0) * sh}`,
												)
												.join(" ");
											const centroid = computePolygonCentroid(poly.vertices);
											return (
												<g key={idx}>
													<polygon
														points={pts}
														className="roi-preview-poly roi-preview-poly--drift"
													>
														<title>{poly.label || `Vùng ${idx + 1}`}</title>
													</polygon>
													{poly.vertices && poly.vertices.length >= 3 && (
														<text
															x={centroid.x * sw}
															y={centroid.y * sh}
															className="roi-poly-label-svg"
														>
															{idx + 1}. {poly.label || `Vùng ${idx + 1}`}
														</text>
													)}
												</g>
											);
										})}
									</svg>
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
									{roiPolygons.length > 0 && (
										<svg
											viewBox={`0 0 ${refWidth} ${refHeight}`}
											preserveAspectRatio="none"
											className="roi-preview-svg"
										>
											{roiPolygons.map((poly, idx) => {
												const pts = (poly.vertices || [])
													.map(
														(v) =>
															`${(Number(v.x) || 0) * refWidth},${(Number(v.y) || 0) * refHeight}`,
													)
													.join(" ");
												const centroid = computePolygonCentroid(poly.vertices);
												return (
													<g key={idx}>
														<polygon
															points={pts}
															className="roi-preview-poly"
														>
															<title>{poly.label || `Vùng ${idx + 1}`}</title>
														</polygon>
														{poly.vertices && poly.vertices.length >= 3 && (
															<text
																x={centroid.x * refWidth}
																y={centroid.y * refHeight}
																className="roi-poly-label-svg"
															>
																{idx + 1}. {poly.label || `Vùng ${idx + 1}`}
															</text>
														)}
													</g>
												);
											})}
										</svg>
									)}
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
								camera và bắt đầu khoanh vùng giám sát an ninh.
							</p>
						</div>
					)}
				</div>
			)}

			{/* Existing ROI Polygons Summary */}
			{roiPolygons.length > 0 && (
				<div className="roi-summary-section">
					<h5
						style={{
							fontSize: "0.875rem",
							fontWeight: 700,
							marginBottom: "0.5rem",
							color: "var(--theme-text-primary)",
						}}
					>
						Vùng giám sát hiện hành ({roiPolygons.length} vùng)
					</h5>
					<div className="roi-summary-list">
						{roiPolygons.map((poly, idx) => {
							const targetId = poly.target_area_id || poly.targetAreaId;
							const targetArea = targetId
								? (camera?.assignedAreas || []).find((a) => a.id === targetId)
								: null;
							return (
								<div
									key={idx}
									className="roi-summary-card"
								>
									<div className="roi-summary-card-header">
										<div className="roi-summary-title">
											<span className="roi-summary-index">{idx + 1}</span>
											<span>{poly.label || `Vùng ${idx + 1}`}</span>
										</div>
										<span
											style={{
												fontSize: "0.75rem",
												color: "var(--theme-text-muted)",
											}}
										>
											{poly.vertices ? poly.vertices.length : 0} đỉnh
										</span>
									</div>
									<div className="roi-rules-tags">
										{targetArea && (
											<span
												className="roi-badge-rule"
												style={{
													backgroundColor: "rgba(56, 189, 248, 0.15)",
													color: "#38bdf8",
													borderColor: "rgba(56, 189, 248, 0.3)",
												}}
											>
												📍 {targetArea.name || targetArea.code}
											</span>
										)}
										{(
											poly.alert_rules ||
											poly.alertRules || ["ENTRY_EXIT_TRACKING"]
										).map((rule, rIdx) => (
											<span
												key={rIdx}
												className="roi-badge-rule"
											>
												{formatRuleBadge(rule)}
											</span>
										))}
									</div>
								</div>
							);
						})}
					</div>
				</div>
			)}
		</div>
	);
}
