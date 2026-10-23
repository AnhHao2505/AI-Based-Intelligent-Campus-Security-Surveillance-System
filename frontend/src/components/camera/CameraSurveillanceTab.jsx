import React, { useState, useRef } from "react";
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
	Video,
	ArrowLeft,
} from "lucide-react";
import WebRtcPlayer from "../video/WebRtcPlayer";
import { useAuthorizedImage } from "../../hooks/useAuthorizedImage";
import {
	formatImageUrl,
	computePolygonCentroid,
} from "../../utils/roiUtils";

// Ảnh /api/media cần JWT -> tải qua useAuthorizedImage; lỗi chỉ hiện thông báo, không vỡ trang
function AuthorizedImg({ src, alt, className }) {
	const { url, loading, error } = useAuthorizedImage(src);
	if (error) {
		return <div className="roi-preview-img-state">Không tải được ảnh</div>;
	}
	if (loading || !url) {
		return (
			<div className="roi-preview-img-state">
				<Loader2
					size={18}
					className="animate-spin"
				/>
			</div>
		);
	}
	return (
		<img
			src={url}
			alt={alt}
			className={className}
		/>
	);
}

export default function CameraSurveillanceTab({
	camera,
	capturingSnapshot,
	refreshingLive,
	compareMode,
	liveSnapshot,
	onCaptureNewSnapshot,
	onEditCurrentRoi,
	onCheckDrift,
	onCloseCompare,
	onCaptureFromLiveStream,
	viewMode: controlledViewMode,
	onViewModeChange,
	onError,
}) {
	const playerRef = useRef(null);
	const [internalViewMode, setInternalViewMode] = useState("snapshot");
	const viewMode = onViewModeChange ? controlledViewMode : internalViewMode;
	const setViewMode = onViewModeChange ? onViewModeChange : setInternalViewMode;
	const [showRoiOnLive, setShowRoiOnLive] = useState(true);
	const [playerStatus, setPlayerStatus] = useState("connecting");

	const handleCaptureFromLive = () => {
		if (playerStatus === "live" && playerRef.current) {
			const frameData = playerRef.current.captureFrame();
			if (frameData && frameData.base64) {
				if (onCaptureFromLiveStream) {
					onCaptureFromLiveStream(frameData);
					return;
				}
			}
		}

		// Fallback: nếu WebRTC chưa sẵn sàng hoặc không lấy được frame canvas,
		// gọi trực tiếp service trích xuất snapshot từ camera
		if (onCaptureNewSnapshot) {
			onCaptureNewSnapshot();
			return;
		}

		const msg =
			"Không thể lấy khung hình từ camera. Vui lòng kiểm tra lại thiết bị!";
		if (onError) onError(msg);
	};
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
	const totalRoiCount = roiPolygons.length;

	// Render SVG Shapes helper
	const renderRoiSvgElements = (width, height, isDrift = false) => (
		<svg
			viewBox={`0 0 ${width} ${height}`}
			preserveAspectRatio="none"
			className="roi-preview-svg"
		>
			{/* Polygons */}
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
		</svg>
	);

	return (
		<div className="roi-config-section">
			<div className="roi-section-header">
				<div className="roi-action-bar">
					{refUrl ? (
						<>
							<button
								type="button"
								className={`btn-toggle-live-cam ${
									viewMode === "live" ? "btn-toggle-live-cam--active" : ""
								}`}
								onClick={() =>
									setViewMode(viewMode === "live" ? "snapshot" : "live")
								}
								disabled={capturingSnapshot || refreshingLive}
								title="Xem góc quay trực tiếp từ camera để căn chỉnh hoặc chụp ảnh mới"
							>
								<Video size={16} />
								<span>
									{viewMode === "live"
										? "Kết thúc hành động"
										: "Phát trực tiếp"}
								</span>
							</button>

							<button
								type="button"
								className="btn-open-editor"
								onClick={onEditCurrentRoi}
								disabled={capturingSnapshot || refreshingLive}
								title="Chỉnh sửa các polygon ROI trên ảnh tham chiếu hiện tại"
							>
								<Scan size={16} />
								<span>Chỉnh sửa vùng giám sát</span>
							</button>

							<button
								type="button"
								className={`btn-refresh-compare ${
									compareMode ? "btn-refresh-compare--active" : ""
								}`}
								onClick={onCheckDrift}
								disabled={refreshingLive || capturingSnapshot}
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
					) : viewMode === "live" ? (
						<button
							type="button"
							className="btn-open-editor"
							onClick={() => setViewMode("snapshot")}
							title="Quay lại hướng dẫn thiết lập"
						>
							<ArrowLeft size={16} />
							<span>Quay lại hướng dẫn</span>
						</button>
					) : null}
				</div>
			</div>

			{/* VIEW MODE 1: LIVE CAM STREAM FOR ANGLE ADJUSTMENT & SNAPSHOT */}
			{viewMode === "live" ? (
				<div className="roi-live-preview-container">
					<div className="roi-live-banner">
						<div className="roi-live-badge">
							<span className="pulse-dot-red" />
							<span>GÓC QUAY TRỰC TIẾP</span>
						</div>
					</div>

					<div
						className="roi-preview-wrapper"
						style={{ position: "relative" }}
					>
						<div
							className="roi-preview-stage"
							style={{ width: "100%", maxWidth: "860px" }}
						>
							<WebRtcPlayer
								ref={playerRef}
								streamPath={
									camera?.cameraCode
										?.toLowerCase()
										.replace(/[^a-z0-9_-]/g, "") || "cam-001"
								}
								cameraCode={camera?.cameraCode || "CAM-001"}
								cameraName={camera?.name || "Camera"}
								enabled={camera?.operationalStatus === "ONLINE"}
								host={
									import.meta.env.VITE_MEDIAMTX_HOST ||
									(typeof window !== "undefined" && window.location.hostname
										? `${window.location.hostname}:8889`
										: "localhost:8889")
								}
								autoPlay={true}
								onStatusChange={setPlayerStatus}
							/>
							{showRoiOnLive && totalRoiCount > 0 && (
								<div
									style={{
										position: "absolute",
										top: 0,
										left: 0,
										width: "100%",
										height: "100%",
										pointerEvents: "none",
										zIndex: 10,
									}}
								>
									{renderRoiSvgElements(refWidth, refHeight, false)}
								</div>
							)}
						</div>
					</div>

					<div className="roi-live-actions">
						<div
							style={{
								display: "flex",
								alignItems: "center",
								gap: "0.75rem",
								flexWrap: "wrap",
							}}
						>
							<button
								type="button"
								className="btn-capture-from-live"
								onClick={handleCaptureFromLive}
								disabled={capturingSnapshot}
								title={
									playerStatus === "live"
										? "Chụp lại chính xác khung hình camera đang hiển thị để chuyển sang vẽ vùng ROI"
										: "Camera chưa sẵn sàng luồng WebRTC - Bấm để trích xuất ảnh trực tiếp từ RTSP"
								}
							>
								{capturingSnapshot ? (
									<>
										<Loader2
											className="animate-spin"
											size={18}
										/>
										<span>Đang xử lý ảnh...</span>
									</>
								) : playerStatus === "live" ? (
									<>
										<CameraIcon size={18} />
										<span>Chụp khung hình này để vẽ vùng giám sát</span>
									</>
								) : (
									<>
										<CameraIcon size={18} />
										<span>Chụp khung hình để vẽ vùng giám sát</span>
									</>
								)}
							</button>

							{totalRoiCount > 0 && (
								<label
									style={{
										display: "inline-flex",
										alignItems: "center",
										gap: "0.4rem",
										fontSize: "0.85rem",
										cursor: "pointer",
										color: "var(--theme-text-muted)",
									}}
								>
									<input
										type="checkbox"
										checked={showRoiOnLive}
										onChange={(e) => setShowRoiOnLive(e.target.checked)}
									/>
									<span>Hiện vùng giám sát</span>
								</label>
							)}
						</div>
					</div>
				</div>
			) : (
				/* VIEW MODE 2: SNAPSHOT & ROI PREVIEW */
				<>
					{/* Drift Inspection Warning Banner in Compare Mode */}
					{compareMode && liveSnapshot && (
						<div className="drift-warning-banner">
							<div className="drift-warning-icon">
								<AlertTriangle size={22} />
							</div>
							<div className="drift-warning-content">
								<h5>Kiểm tra sai lệch góc quan sát</h5>
								<p>
									Đối chiếu <strong>Khung hình gốc</strong> và{" "}
									<strong>Khung hình trực tiếp</strong> để phát hiện camera bị rung
									lắc hoặc xê dịch góc quay.
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
										<span>Khung hình tham chiếu gốc</span>
									</div>
								</div>

								<div className="roi-preview-wrapper">
									<div className="roi-preview-stage">
										<AuthorizedImg
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
									<span>{roiPolygons.length} vùng giám sát</span>
								</div>
							</div>

							{/* Right: Live Snapshot */}
							<div className="roi-compare-card">
								<div className="roi-compare-header">
									<div className="roi-compare-badge roi-compare-badge--live">
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
										style={{ color: "var(--theme-success)" }}
									>
										Độ phân giải: {liveSnapshot.width || 1920}x
										{liveSnapshot.height || 1080}
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
											<AuthorizedImg
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
											Khung hình gốc ({refWidth}x{refHeight})
										</span>
										<span
											className="roi-meta-badge"
											style={{
												color:
													roiPolygons.length > 0
														? "var(--theme-text)"
														: "#38bdf8",
											}}
										>
											{roiPolygons.length > 0
												? `${roiPolygons.length} vùng giám sát`
												: "Chế độ: Nhận diện AI toàn khung hình"}
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
								<div className="roi-empty-setup-container">
									<div className="roi-empty-icon-wrap">
										<Layers size={36} />
									</div>
									<h4 className="roi-empty-title">Chưa có vùng giám sát</h4>
									<p className="roi-empty-desc">
										Camera này chưa có ảnh tham chiếu và vùng bảo vệ. Hãy bật
										Live Cam để quan sát góc máy thực tế, căn chỉnh vị trí và
										chụp ảnh để vẽ vùng ROI.
									</p>
									<button
										type="button"
										className="btn-open-live-cta"
										onClick={() => setViewMode("live")}
									>
										<Video size={18} />
										<span>Xem camera trực tiếp, vẽ vùng giám sát ROI</span>
									</button>
								</div>
							)}
						</div>
					)}
				</>
			)}
		</div>
	);
}
