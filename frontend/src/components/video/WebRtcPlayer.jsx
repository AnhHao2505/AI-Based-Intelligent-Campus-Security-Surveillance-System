import React, {
	useEffect,
	useRef,
	useState,
	useImperativeHandle,
	forwardRef,
} from "react";
import "../../styles/WebRtcPlayer.css";

/**
 * WebRtcPlayer: Component phát luồng video WebRTC (WHEP) từ MediaMTX với chuẩn giao diện OSD CCTV chuyên nghiệp.
 * Tích hợp nút toggle [TRACKING] cho phép bảo vệ/giám sát viên xem trực tiếp Bounding Box, Track ID, Khuôn mặt & Vùng ROI.
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
	const [status, setStatus] = useState(enabled ? "connecting" : "offline"); // 'connecting' | 'live' | 'error' | 'offline'
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

	// Reset retryCount và tracking error khi đổi kênh stream, host hoặc trạng thái enabled
	useEffect(() => {
		setRetryCount(0);
		setTrackingError(false);
	}, [streamPath, host, cameraCode, enabled]);

	// Xử lý bật/tắt luồng AI Tracking
	const handleToggleTracking = (e) => {
		if (e) e.stopPropagation();
		setTrackingError(false);
		setShowTracking((prev) => !prev);
	};

	// Đồng hồ số chạy thời gian thực mỗi 1 giây chuẩn CCTV (HH:mm:ss)
	const [liveClock, setLiveClock] = useState(() => {
		return new Date().toLocaleTimeString("vi-VN", { hour12: false });
	});

	useEffect(() => {
		const timer = setInterval(() => {
			setLiveClock(new Date().toLocaleTimeString("vi-VN", { hour12: false }));
		}, 1000);
		return () => clearInterval(timer);
	}, []);

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

				// Chỉ yêu cầu nhận Video (không nhận Audio) để tránh lỗi lệch clock rate
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
						// Nếu video đã có frame hoặc đang play thì kích hoạt live ngay
						if (
							videoRef.current &&
							(videoRef.current.videoWidth > 0 || !videoRef.current.paused)
						) {
							markLive();
						} else {
							// Đợi I-Frame từ camera, nếu quá 1.5s chưa có event onPlaying thì fallback sang live
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
						}, 1500); // Giảm từ 3s → 1.5s để tái kết nối nhanh hơn trên LAN
					}
				};

				// 1. Tạo SDP Offer
				const offer = await peerConnection.createOffer();
				await peerConnection.setLocalDescription(offer);

				// 2. Gửi Offer sang MediaMTX WHEP endpoint
				const whepUrl = `http://${host}/${streamPath}/whep`;
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

				// 3. Nhận SDP Answer từ MediaMTX
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
					}, 2000); // Giảm từ 4s → 2s để tái kết nối nhanh hơn trên LAN
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

	const displayCamCode = (cameraCode || streamPath || "CAM-001").toUpperCase();
	const displayCamName = cameraName || "Camera quan sát";

	return (
		<div
			className={`cctv-player-wrapper ${status !== "live" ? "cctv-player--blackout" : ""}`}
		>
			{/* Thẻ Video phát luồng trực tiếp WebRTC (luôn mount ngầm để giữ nguyên peer connection khi toggle tracking) */}
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

			{/* Luồng AI Tracking Annotation trực tiếp (YOLO11, ByteTrack, Khuôn mặt & Vùng ROI) */}
			{showTracking && (
				<img
					src={`http://${aiHost}/api/v1/stream/live-annotated?camera_code=${encodeURIComponent(cameraCode || streamPath || "CAM-001")}`}
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

			{/* Thông báo khi không kết nối được luồng Tracking */}
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

			{/* ┌──────────────────────────────────────────────────────────────┐
          │  CAM-001 Cổng chính    ● LIVE    ● REC    ● TRACKING   15:53 │
          └──────────────────────────────────────────────────────────────┘
          Thanh OSD Phía Trên: Tên camera đứng 1 mình ở góc trái trên */}
			<div className="cctv-osd-top-bar">
				{/* Góc trái trên: Tên camera đứng 1 mình */}
				<div className="cctv-osd-top-left">
					<span className="cctv-cam-name-isolated">{displayCamName}</span>
				</div>

				{/* Phía trên bên phải: LIVE, REC, AI TRACKING, Đồng hồ số chạy từng giây */}
				<div className="cctv-osd-top-right">
					<div className="cctv-indicator-group">
						{/* Đèn chỉ thị LIVE xanh ngọc */}
						<span
							className={`cctv-tag cctv-tag--live ${status === "live" ? "active" : "idle"}`}
						>
							<span className="cctv-dot cctv-dot--green" />
							LIVE
						</span>

						{/* Đèn chỉ thị REC đỏ cờ đang ghi hình */}
						{isRecording && (
							<span className="cctv-tag cctv-tag--rec">
								<span className="cctv-dot cctv-dot--red" />
								REC
							</span>
						)}

						{/* Nút bật/tắt AI Tracking & Bounding Box */}
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

					{/* Đồng hồ số thời gian thực chuẩn CCTV */}
					<span className="cctv-clock-text">{liveClock}</span>

					{/* Nút phóng to nhanh ô camera (nếu có callback) */}
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

			{/* ┌──────────────────────────────────────────────────────────────┐
          │  CAM-001 • 1080p • 30 FPS • AI OVERLAY        15:53:21        │
          └──────────────────────────────────────────────────────────────┘
          Thanh OSD Phía Dưới: Mã camera ở góc trái dưới kèm thông số */}
			<div className="cctv-osd-bottom-bar">
				{/* Góc trái dưới: Mã camera • 1080p • 30 FPS • AI Badge (nếu đang bật tracking) */}
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

			{/* Màn hình ĐEN THUẦN khi mất kết nối / Đang tải (Chuẩn màn hình giám sát chuyên nghiệp) */}
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