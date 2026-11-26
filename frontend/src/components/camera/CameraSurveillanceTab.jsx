import React, { useState, useRef, useEffect } from "react";
import { Scan, Layers } from "lucide-react";
import WebRtcPlayer from "../video/WebRtcPlayer";
import {
	computePolygonCentroid,
	resolveRoiModalSnapshot,
} from "../../utils/roiUtils";

export default function CameraSurveillanceTab({ camera, onOpenRoiEditor }) {
	const playerRef = useRef(null);
	const [showRoiOverlay, setShowRoiOverlay] = useState(true);
	const [playerStatus, setPlayerStatus] = useState("connecting");
	// Kích thước thật của khung hình camera. Player hiển thị video bằng object-fit: contain trong khung 16:9,
	// nên camera không phải 16:9 sẽ có viền đen; overlay ROI phải co theo đúng vùng ảnh, không kéo giãn cả khung.
	const [videoDims, setVideoDims] = useState(null);

	useEffect(() => {
		if (playerStatus !== "live") return undefined;
		const videoEl = playerRef.current?.getVideoElement?.();
		if (!videoEl) return undefined;
		const update = () => {
			const w = videoEl.videoWidth;
			const h = videoEl.videoHeight;
			if (w > 0 && h > 0) {
				setVideoDims((prev) => (prev && prev.width === w && prev.height === h ? prev : { width: w, height: h }));
			}
		};
		update();
		videoEl.addEventListener("loadedmetadata", update);
		videoEl.addEventListener("resize", update);
		return () => {
			videoEl.removeEventListener("loadedmetadata", update);
			videoEl.removeEventListener("resize", update);
		};
	}, [playerStatus]);

	const roiPolygons = camera?.roiGeometry?.polygons || [];
	const totalRoiCount = roiPolygons.length;
	const isOnline = camera?.operationalStatus === "ONLINE";
	const rawCameraCode = String(camera?.cameraCode || "").trim();
	const cleanStreamPath = rawCameraCode
		.toLowerCase()
		.replace(/[^a-z0-9_-]/g, "");

	const handleOpenEditor = () => {
		if (!onOpenRoiEditor) return;
		const snapshotMeta = resolveRoiModalSnapshot(
			playerStatus,
			playerRef.current,
		);
		onOpenRoiEditor(snapshotMeta);
	};

	const renderRoiSvgElements = (width = 1920, height = 1080, preserveAspectRatio = "none") => (
		<svg
			viewBox={`0 0 ${width} ${height}`}
			preserveAspectRatio={preserveAspectRatio}
			className="roi-preview-svg"
		>
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
							className="roi-preview-poly roi-preview-poly--ref"
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
					{onOpenRoiEditor && (
						<button
							type="button"
							className="btn-open-editor"
							onClick={handleOpenEditor}
							title={
								playerStatus === "live"
									? "Chụp khung hình trực tiếp hiện tại và mở trình vẽ vùng ROI"
									: "Mở trình vẽ vùng ROI trên khung lưới 16:9 chuẩn hóa"
							}
						>
							<Scan size={16} />
							<span>Thiết lập / Chỉnh sửa vùng</span>
						</button>
					)}

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
								checked={showRoiOverlay}
								onChange={(e) => setShowRoiOverlay(e.target.checked)}
							/>
							<span>Hiện vùng ({totalRoiCount})</span>
						</label>
					)}
				</div>
			</div>

			{isOnline ? (
				<div className="roi-live-preview-container">
					<div className="roi-live-banner">
						<div className="roi-live-badge">
							<span className="pulse-dot-red" />
							<span>LUỒNG TRỰC TIẾP &amp;</span>
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
								streamPath={cleanStreamPath}
								cameraCode={rawCameraCode}
								cameraName={camera?.name || "Camera"}
								enabled={Boolean(rawCameraCode)}
								host={
									import.meta.env.VITE_MEDIAMTX_HOST ||
									(typeof window !== "undefined" && window.location.hostname
										? `${window.location.hostname}:8889`
										: "localhost:8889")
								}
								autoPlay={true}
								onStatusChange={setPlayerStatus}
							/>
							{showRoiOverlay &&
								totalRoiCount > 0 &&
								playerStatus === "live" && (
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
										{videoDims
											? renderRoiSvgElements(videoDims.width, videoDims.height, "xMidYMid meet")
											: renderRoiSvgElements(1920, 1080)}
									</div>
								)}
						</div>
					</div>
				</div>
			) : (
				<div className="roi-single-preview">
					<div className="roi-preview-wrapper">
						<div
							className="roi-preview-stage roi-grid-preview-stage"
							style={{ width: "100%", maxWidth: "860px" }}
						>
							<div
								className="roi-grid-canvas"
								style={{ width: "100%" }}
							>
								<span>
									{totalRoiCount > 0
										? `Khung lưới 16:9 — ${totalRoiCount} vùng giám sát (Camera đang Offline)`
										: "Khung lưới 16:9 — Chưa cấu hình vùng ROI (cần ít nhất 1 vùng)"}
								</span>
							</div>
							{showRoiOverlay &&
								totalRoiCount > 0 &&
								playerStatus === "live" && (
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
										{renderRoiSvgElements(1920, 1080)}
									</div>
								)}
						</div>
					</div>

					<div className="roi-preview-meta">
						<span className="roi-meta-badge">
							<Layers size={14} />
							{totalRoiCount > 0
								? `${totalRoiCount} vùng giám sát`
								: "Chưa có vùng ROI (cần 1–10 vùng)"}
						</span>
					</div>
				</div>
			)}
		</div>
	);
}
