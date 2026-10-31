import React, {
	useEffect,
	useRef,
	useState,
	useImperativeHandle,
	forwardRef,
} from "react";
import { toggleCloudRelay } from "../../services/cameraService";
import "../../styles/WebRtcPlayer.css";

const ENV_CLOUD_WHEP_BASE = import.meta.env.VITE_CLOUD_WHEP_BASE_URL || "";
const ENV_MEDIAMTX_WHEP_BASE = import.meta.env.VITE_MEDIAMTX_WHEP_BASE_URL || "";
const ENV_AI_STREAM_BASE = import.meta.env.VITE_AI_STREAM_BASE_URL || "";

/**
 * Chuẩn hóa URL WHEP hỗ trợ Dual-Endpoint (Local LAN HTTP hoặc Cloud HTTPS Relay không lỗi Mixed Content).
 */
function resolveWhepEndpoint(hostProp, streamPath) {
	const cleanPath = String(streamPath || "cam01").replace(/^\/+|\/+$/g, "");
	const rawBase = (ENV_CLOUD_WHEP_BASE || ENV_MEDIAMTX_WHEP_BASE || hostProp || "localhost:8889")
		.trim()
		.replace(/\/+$/, "");

	if (rawBase.endsWith("/whep")) {
		return rawBase;
	}
	if (/^https?:\/\//i.test(rawBase)) {
		return `${rawBase}/${cleanPath}/whep`;
	}
	const isHttpsPage =
		typeof window !== "undefined" && window.location?.protocol === "https:";
	const isLoopback = /^(localhost|127\.0\.0\.1)(:\d+)?$/i.test(rawBase);
	const protocol = isHttpsPage && !isLoopback ? "https://" : "http://";
	return `${protocol}${rawBase}/${cleanPath}/whep`;
}

function resolveAiStreamUrl(aiHostProp, cameraCode) {
	const rawAi = (ENV_AI_STREAM_BASE || aiHostProp || "localhost:8000")
		.trim()
		.replace(/\/+$/, "");
	const base = /^https?:\/\//i.test(rawAi)
		? rawAi
		: typeof window !== "undefined" &&
			  window.location?.protocol === "https:" &&
			  !/^(localhost|127\.0\.0\.1)(:\d+)?$/i.test(rawAi)
			? `https://${rawAi}`
			: `http://${rawAi}`;
	return `${base}/api/v1/stream/live-annotated?camera_code=${encodeURIComponent(cameraCode)}`;
}

/**
 * WebRtcPlayer: Component phát luồng video WebRTC (WHEP) từ MediaMTX với chuẩn giao diện OSD CCTV chuyên nghiệp.
 * Hỗ trợ kiến trúc Cloud-Edge (Dual-Endpoint Local LAN + On-Demand HTTPS Cloud Relay).
 */
const WebRtcPlayer = forwardRef(function WebRtcPlayer(
	{
		streamPath = "cam01",
		cameraCode = "CAM-001",
		cameraName = "Cổng chính",
		resolution = "1080p",
		fps = "30 FPS",
		isRecording = true,
		host = "localhost:8889",
		aiHost = typeof window !== "undefined" && window.location.hostname
			? `${window.location.hostname}:8000`
			: "localhost:8000",
		enableTrackingToggle = true,
		autoPlay = true,
		muted = true,
		enabled = true,
		onStatusChange = null,
		onToggleMaximize = null,
	},
	ref,
) {
	const videoRef = useRef(null);
	const pcRef = useRef(null);
	const [status, setStatus] = useState(enabled ? "connecting" : "offline");
	const [errorMsg, setErrorMsg] = useState("");
	const [showTracking, setShowTracking] = useState(false);
	const [trackingError, setTrackingError] = useState(false);

	useImperativeHandle(ref, () => ({
		captureFrame: () => {
			if (!videoRef.current || videoRef.current.videoWidth === 0) return null;
			const v = videoRef.current;
			const w = v.videoWidth || 1920;
			const h = v.videoHeight || 1080;
			const canvas = document.createElement("canvas");
			canvas.width = w;
			canvas.height = h;
			const ctx = canvas.getContext("2d");
			ctx.drawImage(v, 0, 0, w, h);
			return {
				base64: canvas.toDataURL("image/jpeg", 0.95),
				width: w,
				height: h,
			};
		},
		getVideoElement: () => videoRef.current,
		getStatus: () => status,
		isTrackingActive: () => showTracking,
		toggleTracking: (val) => setShowTracking(val !== undefined ? val : (p) => !p),
	}));
	const [retryCount, setRetryCount] = useState(0);

	useEffect(() => {
		setRetryCount(0);
		setTrackingError(false);
	}, [streamPath, host, cameraCode, enabled]);

	const handleToggleTracking = (e) => {
		if (e) e.stopPropagation();
		setTrackingError(false);
		setShowTracking((prev) => !prev);
	};

	const [liveClock, setLiveClock] = useState(() => {
		return new Date().toLocaleTimeString("vi-VN", { hour12: false });
	});

	useEffect(() => {
		const timer = setInterval(() => {
			setLiveClock(new Date().toLocaleTimeString("vi-VN", { hour12: false }));
		}, 1000);
		return () => clearInterval(timer);
	}, []);

	const displayCamCode = (cameraCode || streamPath || "CAM-001").toUpperCase();
	const displayCamName = cameraName || "Camera quan sát";

	// Tự động kích hoạt On-Demand Cloud Relay qua MQTT khi triển khai trên Cloud HTTPS
	useEffect(() => {
		if (!enabled || !ENV_CLOUD_WHEP_BASE) return undefined;
		toggleCloudRelay(displayCamCode, true).catch(() => {});
		return () => {
			toggleCloudRelay(displayCamCode, false).catch(() => {});
		};
	}, [displayCamCode, enabled]);

	useEffect(() => {
		if (!enabled) {
			setStatus("offline");
			if (onStatusChange) onStatusChange("offline");
			return;
		}

		let isMounted = true;
		let peerConnection = null;

		async function startWebRTC() {
			if (retryCount === 0) {
				setStatus("connecting");
			}
			setErrorMsg("");

			try {
				peerConnection = new RTCPeerConnection({
					iceServers: [
						{ urls: "stun:stun.l.google.com:19302" },
						{ urls: "stun:stun1.l.google.com:19302" },
					],
				});
				pcRef.current = peerConnection;

				peerConnection.addTransceiver("video", { direction: "recvonly" });

				const markLive = () => {
					if (!isMounted) return;
					setStatus("live");
					setRetryCount(0);
					if (onStatusChange) onStatusChange("live");
				};

				peerConnection.ontrack = (event) => {
					if (videoRef.current) {
						if (event.streams && event.streams[0]) {
							videoRef.current.srcObject = event.streams[0];
						} else if (event.track) {
							videoRef.current.srcObject = new MediaStream([event.track]);
						}
						const playPromise = videoRef.current.play();
						if (playPromise !== undefined) {
							playPromise
								.then(() => {
									if (videoRef.current && videoRef.current.videoWidth > 0) {
										markLive();
									}
								})
								.catch((e) => {
									console.debug("Tự động phát video đang chờ sẵn:", e);
								});
						}
					}
				};

				peerConnection.onconnectionstatechange = () => {
					if (!isMounted) return;
					const state = peerConnection.connectionState;
					if (state === "connected") {
						if (
							videoRef.current &&
							(videoRef.current.videoWidth > 0 || !videoRef.current.paused)
						) {
							markLive();
						} else {
							setTimeout(() => {
								if (
									isMounted &&
									peerConnection?.connectionState === "connected"
								) {
									markLive();
								}
							}, 1500);
						}
					} else if (state === "disconnected" || state === "failed") {
						setStatus("offline");
						if (onStatusChange) onStatusChange("offline");
						setTimeout(() => {
							if (isMounted) setRetryCount((prev) => prev + 1);
						}, 1500);
					}
				};

				const offer = await peerConnection.createOffer();
				await peerConnection.setLocalDescription(offer);

				const whepUrl = resolveWhepEndpoint(host, streamPath);
				const response = await fetch(whepUrl, {
					method: "POST",
					headers: {
						"Content-Type": "application/sdp",
					},
					body: offer.sdp,
				});

				if (!response.ok) {
					throw new Error(
						`Kênh ${streamPath} không phản hồi (${response.status})`,
					);
				}

				const answerSdp = await response.text();
				await peerConnection.setRemoteDescription({
					type: "answer",
					sdp: answerSdp,
				});
			} catch (err) {
				if (isMounted) {
					setStatus("offline");
					setErrorMsg(err.message || "Mất kết nối luồng video");
					if (onStatusChange) onStatusChange("offline");
					setTimeout(() => {
						if (isMounted) setRetryCount((prev) => prev + 1);
					}, 2000);
				}
			}
		}

		startWebRTC();

		return () => {
			isMounted = false;
			if (peerConnection) {
				peerConnection.close();
			}
		};
	}, [streamPath, host, retryCount, enabled]);

	return (
		<div
			className={`cctv-player-wrapper ${status !== "live" ? "cctv-player--blackout" : ""}`}
		>
			<video
				ref={videoRef}
				autoPlay={autoPlay}
				muted={muted}
				playsInline
				className={`cctv-video-element ${status === "live" && !showTracking ? "active" : ""}`}
				style={{ display: showTracking ? "none" : undefined }}
				onPlaying={() => {
					setStatus("live");
					setRetryCount(0);
					if (onStatusChange) onStatusChange("live");
				}}
				onLoadedData={() => {
					setStatus("live");
					setRetryCount(0);
					if (onStatusChange) onStatusChange("live");
				}}
			/>

			{showTracking && (
				<img
					src={resolveAiStreamUrl(aiHost, cameraCode || streamPath || "CAM-001")}
					alt={`AI Tracking ${cameraCode}`}
					className="cctv-annotated-stream"
					onError={() => {
						setTrackingError(true);
					}}
					onLoad={() => {
						setTrackingError(false);
					}}
				/>
			)}

			{showTracking && trackingError && (
				<div className="cctv-tracking-error-overlay">
					<div className="cctv-tracking-error-box">
						<span className="cctv-tracking-error-title">⚠️ AI Tracking Chưa Khả Dụng</span>
						<span className="cctv-tracking-error-desc">
							Không thể kết nối luồng phân tích thời gian thực cho [{displayCamCode}]. Vui lòng đảm bảo camera đã kết nối RTSP và AI Service đang chạy.
						</span>
						<div className="cctv-tracking-error-actions">
							<button
								type="button"
								onClick={(e) => {
									e.stopPropagation();
									setTrackingError(false);
								}}
								className="cctv-tracking-retry-btn"
							>
								Thử lại
							</button>
							<button
								type="button"
								onClick={(e) => {
									e.stopPropagation();
									setShowTracking(false);
									setTrackingError(false);
								}}
								className="cctv-tracking-return-btn"
							>
								Quay lại luồng gốc
							</button>
						</div>
					</div>
				</div>
			)}

			<div className="cctv-osd-top-bar">
				<div className="cctv-osd-top-left">
					<span className="cctv-cam-name-isolated">{displayCamName}</span>
				</div>

				<div className="cctv-osd-top-right">
					<div className="cctv-indicator-group">
						<span
							className={`cctv-tag cctv-tag--live ${status === "live" ? "active" : "idle"}`}
						>
							<span className="cctv-dot cctv-dot--green" />
							LIVE
						</span>

						{isRecording && (
							<span className="cctv-tag cctv-tag--rec">
								<span className="cctv-dot cctv-dot--red" />
								REC
							</span>
						)}

						{enableTrackingToggle && (
							<button
								type="button"
								className={`cctv-tag cctv-tag--tracking ${showTracking ? "active" : ""}`}
								onClick={handleToggleTracking}
								title={
									showTracking
										? "Đang bật AI Tracking (Bounding Box, Track ID, Face & ROI). Bấm để chuyển về luồng gốc."
										: "Bấm để bật AI Tracking (hiển thị Bounding Box, Track ID, Face & ROI)"
								}
							>
								<span className={`cctv-dot ${showTracking ? "cctv-dot--cyan" : "cctv-dot--idle"}`} />
								{showTracking ? "TRACKING: ON" : "TRACKING"}
							</button>
						)}
					</div>

					<span className="cctv-clock-text">{liveClock}</span>

					{onToggleMaximize && (
						<button
							type="button"
							className="cctv-quick-expand-btn"
							onClick={onToggleMaximize}
							title="Phóng to chỉ xem camera này"
						>
							⛶
						</button>
					)}
				</div>
			</div>

			<div className="cctv-osd-bottom-bar">
				<div className="cctv-osd-bottom-left">
					<span className="cctv-spec-code">{displayCamCode}</span>
					<span className="cctv-spec-divider">•</span>
					<span className="cctv-spec-item">{resolution}</span>
					<span className="cctv-spec-divider">•</span>
					<span className="cctv-spec-item">{fps}</span>
					{showTracking && (
						<>
							<span className="cctv-spec-divider">•</span>
							<span className="cctv-ai-active-tag">AI LIVE OVERLAY</span>
						</>
					)}
				</div>
			</div>

			{status !== "live" && (
				<div className="cctv-blackout-screen">
					<div className="cctv-offline-telemetry">
						<div
							className={
								!enabled
									? "cctv-no-signal-badge"
									: status === "connecting"
										? "cctv-connecting-badge"
										: "cctv-no-signal-badge"
							}
						>
							{!enabled
								? "[ LUỒNG STREAM ĐANG TẮT ]"
								: status === "connecting"
									? "[ ĐANG KẾT NỐI... ]"
									: "[ MẤT TÍN HIỆU ]"}
						</div>
					</div>
				</div>
			)}
		</div>
	);
});

export default WebRtcPlayer;
