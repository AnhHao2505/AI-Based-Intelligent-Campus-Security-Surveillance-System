import React, { useEffect, useRef, useState, useImperativeHandle, forwardRef } from "react";
import "../../styles/WebRtcPlayer.css";

/**
 * WebRtcPlayer: Component phát luồng video WebRTC (WHEP) từ MediaMTX với chuẩn giao diện OSD CCTV chuyên nghiệp.
 */
const WebRtcPlayer = forwardRef(function WebRtcPlayer({
	streamPath = "cam01",
	cameraCode = "CAM-001",
	cameraName = "Cổng chính",
	resolution = "1080p",
	fps = "30 FPS",
	isRecording = true,
	host = "localhost:8889",
	autoPlay = true,
	muted = true,
	onStatusChange = null,
	onToggleMaximize = null,
}, ref) {
	const videoRef = useRef(null);
	const pcRef = useRef(null);
	const [status, setStatus] = useState("connecting"); // 'connecting' | 'live' | 'error' | 'offline'
	const [errorMsg, setErrorMsg] = useState("");

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
	}));
	const [retryCount, setRetryCount] = useState(0);

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
		let isMounted = true;
		let peerConnection = null;

		async function startWebRTC() {
			setStatus("connecting");
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

				peerConnection.ontrack = (event) => {
					if (videoRef.current) {
						if (event.streams && event.streams[0]) {
							videoRef.current.srcObject = event.streams[0];
						} else if (event.track) {
							videoRef.current.srcObject = new MediaStream([event.track]);
						}
						if (isMounted) {
							setStatus("live");
							if (onStatusChange) onStatusChange("live");
						}
					}
				};

				peerConnection.onconnectionstatechange = () => {
					if (!isMounted) return;
					const state = peerConnection.connectionState;
					if (state === "connected") {
						setStatus("live");
						if (onStatusChange) onStatusChange("live");
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
	}, [streamPath, host, retryCount]);

	const displayCamCode = (cameraCode || streamPath || "CAM-001").toUpperCase();
	const displayCamName = cameraName || "Camera quan sát";

	return (
		<div
			className={`cctv-player-wrapper ${status !== "live" ? "cctv-player--blackout" : ""}`}
		>
			{/* Thẻ Video phát luồng trực tiếp */}
			<video
				ref={videoRef}
				autoPlay={autoPlay}
				muted={muted}
				playsInline
				className={`cctv-video-element ${status === "live" ? "active" : ""}`}
				onPlaying={() => {
					setStatus("live");
					if (onStatusChange) onStatusChange("live");
				}}
				onLoadedMetadata={() => {
					setStatus("live");
					if (onStatusChange) onStatusChange("live");
				}}
			/>

			{/* ┌──────────────────────────────────────────────────────────────┐
          │  CAM-001 Cổng chính    ● LIVE    ● REC    15:53:21          │
          └──────────────────────────────────────────────────────────────┘
          Thanh OSD Phía Trên: Tên camera đứng 1 mình ở góc trái trên */}
			<div className="cctv-osd-top-bar">
				{/* Góc trái trên: Tên camera đứng 1 mình */}
				<div className="cctv-osd-top-left">
					<span className="cctv-cam-name-isolated">{displayCamName}</span>
				</div>

				{/* Phía trên bên phải: LIVE, REC, Đồng hồ số chạy từng giây */}
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
          │  CAM-001 • 1080p • 30 FPS                    15:53:21        │
          └──────────────────────────────────────────────────────────────┘
          Thanh OSD Phía Dưới: Mã camera ở góc trái dưới kèm thông số */}
			<div className="cctv-osd-bottom-bar">
				{/* Góc trái dưới: Mã camera • 1080p • 30 FPS */}
				<div className="cctv-osd-bottom-left">
					<span className="cctv-spec-code">{displayCamCode}</span>
					<span className="cctv-spec-divider">•</span>
					<span className="cctv-spec-item">{resolution}</span>
					<span className="cctv-spec-divider">•</span>
					<span className="cctv-spec-item">{fps}</span>
				</div>
			</div>

			{/* Màn hình ĐEN THUẦN khi mất kết nối / Đang tải (Chuẩn màn hình giám sát chuyên nghiệp) */}
			{status !== "live" && (
				<div className="cctv-blackout-screen">
					<div className="cctv-offline-telemetry">
						<div className="cctv-no-signal-badge">
							{status === "connecting" ? "[ ĐANG KẾT NỐI]" : "[ MẤT TÍN HIỆU ]"}
						</div>
						<div className="cctv-offline-details">
							<span>SOURCE: {displayCamCode}</span>
							<span className="cctv-sep">|</span>
							<span>{displayCamName.toUpperCase()}</span>
						</div>
					</div>
				</div>
			)}
		</div>
	);
});

export default WebRtcPlayer;
