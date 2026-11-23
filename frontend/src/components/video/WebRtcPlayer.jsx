import React, {
	useEffect,
	useRef,
	useState,
	useImperativeHandle,
	forwardRef,
	useCallback,
	useMemo,
} from "react";
import {
	Play,
	Pause,
	RotateCcw,
	RotateCw,
	Radio,
	ShieldAlert,
	Flame,
	Info,
	Navigation,
} from "lucide-react";
import {
	buildDvrStreamUrl,
	fetchDvrSegments,
	getAnnotatedStreamToken,
	toggleCloudRelay,
} from "../../services/cameraService";
import {
	computeDvrWindow,
	computeRecordedIntervalsPercent,
	formatClockTime,
	formatRelativeBehindLive,
	mapAlertsToScrubberMarkers,
	resolvePlaybackSeekTarget,
} from "../../utils/dvrTimeline";
import {
	formatWhepHttpError,
	hasDecodedVideoFrame,
	resolveWhepFailureTransition,
} from "../../utils/webrtcState";
import "../../styles/WebRtcPlayer.css";

const ENV_CLOUD_WHEP_BASE = import.meta.env.VITE_CLOUD_WHEP_BASE_URL || "";
const ENV_MEDIAMTX_WHEP_BASE =
	import.meta.env.VITE_MEDIAMTX_WHEP_BASE_URL || "";
const ENV_AI_STREAM_BASE = import.meta.env.VITE_AI_STREAM_BASE_URL || "";

/**
 * Chuẩn hóa URL WHEP hỗ trợ Dual-Endpoint (Local LAN HTTP hoặc Cloud HTTPS Relay không lỗi Mixed Content).
 */
function resolveWhepEndpoint(hostProp, streamPath) {
	const cleanPath = String(streamPath || "cam01").replace(/^\/+|\/+$/g, "");
	const rawBase = (
		ENV_CLOUD_WHEP_BASE ||
		ENV_MEDIAMTX_WHEP_BASE ||
		hostProp ||
		"localhost:8889"
	)
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

/**
 * URL luồng live-annotated của AI. Bắt buộc kèm token ngắn hạn do backend cấp
 * (POST /api/cameras/{cameraCode}/annotated-stream-token); AI từ chối nếu thiếu/sai token.
 */
function resolveAiStreamUrl(aiHostProp, cameraCode, streamToken) {
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
	return `${base}/api/v1/stream/live-annotated?camera_code=${encodeURIComponent(cameraCode)}&token=${encodeURIComponent(streamToken)}`;
}

/**
 * WebRtcPlayer: Trình phát luồng video YouTube-Live Style DVR Player (Live WebRTC WHEP + MediaMTX Playback fMP4).
 * Cho phép xem trực tiếp, kéo thanh tua xem lại bất kỳ thời điểm nào, và hiển thị chấm đỏ cảnh báo theo thời gian thực.
 */
const WebRtcPlayer = forwardRef(function WebRtcPlayer(
	{
		streamPath = "cam01",
		cameraCode = "CAM-001",
		cameraName = "Cổng chính",
		displayLabel = null,
		aiModeLabel = null,
		incidentCount = 0,
		incidentSeverityClass = "severity-warning",
		alwaysShowDvr = false,
		adjacentCameras = [],
		onSelectAdjacentCamera = null,
		relationBadge = null,
		resolution = null,
		fps = null,
		isRecording = false,
		showLiveTag = false,
		showRecTag = false,
		host = "localhost:8889",
		aiHost = typeof window !== "undefined" && window.location.hostname
			? `${window.location.hostname}:8000`
			: "localhost:8000",
		enableTrackingToggle = true,
		enableDvr = true,
		alerts = [],
		initialSeekTimeMs = null,
		focusedAlertId = null,
		compactScrubber = false,
		autoPlay = true,
		muted = true,
		enabled = true,
		onStatusChange = null,
		onToggleMaximize = null,
		onAlertMarkerClick = null,
	},
	ref,
) {
	const videoRef = useRef(null);
	const dvrVideoRef = useRef(null);
	const pcRef = useRef(null);
	const scrubberTrackRef = useRef(null);
	const sessionStartRef = useRef(Date.now());
	const dvrTokenRef = useRef({ token: null, expiresAt: 0 });
	const seekSeqRef = useRef(0);
	const lastAutoSeekMsRef = useRef(null);
	const scrubCleanupRef = useRef(null);
	const playbackNoticeTimerRef = useRef(null);

	useEffect(() => {
		return () => {
			if (scrubCleanupRef.current) {
				scrubCleanupRef.current();
				scrubCleanupRef.current = null;
			}
			if (playbackNoticeTimerRef.current) {
				clearTimeout(playbackNoticeTimerRef.current);
				playbackNoticeTimerRef.current = null;
			}
		};
	}, []);

	const initialIsPlayback =
		Number.isFinite(Number(initialSeekTimeMs)) && Number(initialSeekTimeMs) > 0;
	const [mode, setMode] = useState(initialIsPlayback ? "playback" : "live");
	const [status, setStatus] = useState(enabled ? "connecting" : "offline");
	const [errorMsg, setErrorMsg] = useState("");
	const [showTracking, setShowTracking] = useState(false);
	const [trackingError, setTrackingError] = useState(false);
	const [streamToken, setStreamToken] = useState(null);
	const [tokenLoading, setTokenLoading] = useState(false);
	const [tokenRetryTrigger, setTokenRetryTrigger] = useState(0);

	// DVR Timeline state
	const [segments, setSegments] = useState([]);
	const [resolvedStreamPath, setResolvedStreamPath] = useState(null);
	const [clockOffsetMs, setClockOffsetMs] = useState(0);
	const [nowTickMs, setNowTickMs] = useState(() => Date.now());
	const [playbackRate, setPlaybackRate] = useState(1);
	const [isScrubbing, setIsScrubbing] = useState(false);
	const [scrubPreviewMs, setScrubPreviewMs] = useState(null);
	const [hoveredMarker, setHoveredMarker] = useState(null);
	const [playbackNotice, setPlaybackNotice] = useState("");
	const [dvrPlayback, setDvrPlayback] = useState({
		anchorMs: initialIsPlayback ? Number(initialSeekTimeMs) : 0,
		srcUrl: "",
		playheadSec: 0,
		isPaused: false,
		isBuffering: false,
		errorMsg: "",
		seq: 0,
	});

	const [connectAttempt, setConnectAttempt] = useState(0);
	const consecutiveFailuresRef = useRef(0);
	const hadLiveFrameRef = useRef(false);

	const displayCamCode = (cameraCode || streamPath || "CAM-001").toUpperCase();
	const displayCamName = cameraName || "Camera quan sát";

	useImperativeHandle(ref, () => ({
		captureFrame: () => {
			const activeEl =
				mode === "playback" ? dvrVideoRef.current : videoRef.current;
			if (!activeEl || activeEl.videoWidth === 0) return null;
			const w = activeEl.videoWidth || 1920;
			const h = activeEl.videoHeight || 1080;
			const canvas = document.createElement("canvas");
			canvas.width = w;
			canvas.height = h;
			const ctx = canvas.getContext("2d");
			ctx.drawImage(activeEl, 0, 0, w, h);
			return {
				base64: canvas.toDataURL("image/jpeg", 0.95),
				width: w,
				height: h,
			};
		},
		getVideoElement: () =>
			mode === "playback" ? dvrVideoRef.current : videoRef.current,
		getStatus: () => (mode === "playback" ? "playback" : status),
		isTrackingActive: () => showTracking,
		toggleTracking: (val) =>
			setShowTracking(val !== undefined ? val : (p) => !p),
		seekToTimestamp: (ms) => seekToTimestampRef.current?.(ms),
		jumpToLive: () => jumpToLiveRef.current?.(),
	}));

	useEffect(() => {
		consecutiveFailuresRef.current = 0;
		hadLiveFrameRef.current = false;
		setConnectAttempt(0);
		setTrackingError(false);
		setStreamToken(null);
		dvrTokenRef.current = { token: null, expiresAt: 0 };
	}, [streamPath, host, cameraCode, enabled]);

	const ensureDvrToken = useCallback(async () => {
		const cached = dvrTokenRef.current;
		if (
			cached?.token &&
			Number(cached.expiresAt) * 1000 > Date.now() + 10_000
		) {
			return cached.token;
		}
		const res = await getAnnotatedStreamToken(displayCamCode);
		if (!res?.token) {
			throw new Error("Không thể cấp token xác thực DVR stream");
		}
		dvrTokenRef.current = {
			token: res.token,
			expiresAt: res.expiresAt || Math.floor(Date.now() / 1000) + 120,
		};
		return res.token;
	}, [displayCamCode]);

	const refreshDvrSegments = useCallback(async () => {
		if (!enabled || !enableDvr) return [];
		try {
			const data = await fetchDvrSegments(displayCamCode);
			if (data && typeof data === "object") {
				if (Number.isFinite(data.serverNowMs)) {
					setClockOffsetMs(data.serverNowMs - Date.now());
				}
				if (data.streamPath) {
					setResolvedStreamPath(data.streamPath);
				}
				const list = Array.isArray(data.segments) ? data.segments : [];
				setSegments(list);
				return list;
			}
		} catch {
			// Bỏ qua lỗi polling thoáng qua để không gián đoạn live player
		}
		return [];
	}, [displayCamCode, enabled, enableDvr]);

	// Poll danh sách DVR segments mỗi 10s
	useEffect(() => {
		if (!enabled || !enableDvr) return undefined;
		refreshDvrSegments();
		const id = setInterval(refreshDvrSegments, 10_000);
		return () => clearInterval(id);
	}, [enabled, enableDvr, refreshDvrSegments]);

	// Đồng hồ tick mỗi 1s để thanh scrubber LIVE tịnh tiến mượt theo thời gian thực (chỉ chạy khi luồng bật)
	useEffect(() => {
		if (!enabled) return undefined;
		const timer = setInterval(() => {
			setNowTickMs(Date.now());
		}, 1000);
		return () => clearInterval(timer);
	}, [enabled]);

	const liveNowMs = nowTickMs + clockOffsetMs;
	const liveClock = useMemo(() => formatClockTime(liveNowMs), [liveNowMs]);

	const showPlaybackNotice = useCallback((msg) => {
		setPlaybackNotice(msg || "");
		if (playbackNoticeTimerRef.current) {
			clearTimeout(playbackNoticeTimerRef.current);
		}
		if (msg) {
			playbackNoticeTimerRef.current = setTimeout(() => {
				playbackNoticeTimerRef.current = null;
				setPlaybackNotice("");
			}, 4500);
		}
	}, []);

	const jumpToLive = useCallback(() => {
		seekSeqRef.current += 1;
		setShowTracking(false);
		setMode("live");
		showPlaybackNotice("");
		setDvrPlayback({
			anchorMs: 0,
			srcUrl: "",
			playheadSec: 0,
			isPaused: false,
			isBuffering: false,
			errorMsg: "",
			seq: seekSeqRef.current,
		});
		if (dvrVideoRef.current) {
			try {
				dvrVideoRef.current.pause();
				dvrVideoRef.current.removeAttribute("src");
				dvrVideoRef.current.load();
			} catch {
				// ignore
			}
		}
	}, []);

	const seekToTimestamp = useCallback(
		async (requestedMs) => {
			if (!enableDvr || !Number.isFinite(requestedMs) || requestedMs <= 0)
				return;
			const seq = ++seekSeqRef.current;
			let currentLiveMs = Date.now() + clockOffsetMs;

			if (requestedMs >= currentLiveMs - 2500) {
				jumpToLive();
				return;
			}

			// Nếu state segments chưa có hoặc không phủ mốc vừa bấm, làm mới ngay từ Backend trước khi quyết định
			const hasDirectCoverage =
				Array.isArray(segments) &&
				segments.some(
					(s) =>
						Number.isFinite(s?.startMs) &&
						Number.isFinite(s?.endMs) &&
						requestedMs >= s.startMs &&
						requestedMs <= s.endMs,
				);
			let activeSegments = segments;
			if (!hasDirectCoverage) {
				const fresh = await refreshDvrSegments();
				if (seekSeqRef.current !== seq) return;
				if (Array.isArray(fresh) && fresh.length > 0) {
					activeSegments = fresh;
				}
				currentLiveMs = Date.now() + clockOffsetMs;
			}

			const resolution = resolvePlaybackSeekTarget({
				requestedMs,
				segments: activeSegments,
				liveNowMs: currentLiveMs,
			});

			if (resolution.action === "live") {
				jumpToLive();
				return;
			}

			if (resolution.action === "unavailable") {
				setShowTracking(false);
				setMode("playback");
				setDvrPlayback((prev) => ({
					...prev,
					anchorMs: requestedMs,
					srcUrl: "",
					playheadSec: 0,
					isPaused: true,
					isBuffering: false,
					errorMsg: resolution.reason,
					seq,
				}));
				return;
			}

			const effectiveRequestedMs = resolution.targetMs;
			if (resolution.snapped && resolution.notice) {
				showPlaybackNotice(resolution.notice);
			}

			// Kiểm tra nếu video xem lại đang mở đã buffer sẵn khoảng sought này -> tua nội bộ ngay tức thì
			const dvrEl = dvrVideoRef.current;
			if (
				mode === "playback" &&
				dvrEl &&
				dvrPlayback.srcUrl &&
				dvrPlayback.anchorMs > 0
			) {
				const offsetSec = (effectiveRequestedMs - dvrPlayback.anchorMs) / 1000;
				if (offsetSec >= 0 && dvrEl.seekable && dvrEl.seekable.length > 0) {
					for (let i = 0; i < dvrEl.seekable.length; i += 1) {
						if (
							offsetSec >= dvrEl.seekable.start(i) &&
							offsetSec <= dvrEl.seekable.end(i)
						) {
							dvrEl.currentTime = offsetSec;
							setDvrPlayback((prev) => ({
								...prev,
								playheadSec: offsetSec,
								isBuffering: false,
								errorMsg: "",
							}));
							if (dvrEl.paused) {
								dvrEl.play().catch(() => {});
							}
							return;
						}
					}
				}
			}

			setShowTracking(false);
			setMode("playback");
			setDvrPlayback((prev) => ({
				...prev,
				anchorMs: effectiveRequestedMs,
				playheadSec: 0,
				isPaused: false,
				isBuffering: true,
				errorMsg: "",
				seq,
			}));

			try {
				const token = await ensureDvrToken();
				if (seekSeqRef.current !== seq) return;
				const sliceDurationSec = Math.min(
					600,
					Math.max(
						30,
						Math.ceil((currentLiveMs - effectiveRequestedMs) / 1000) + 15,
					),
				);
				const srcUrl = buildDvrStreamUrl(displayCamCode, {
					startMs: effectiveRequestedMs,
					durationSec: sliceDurationSec,
					path: resolvedStreamPath,
					token,
				});
				setDvrPlayback({
					anchorMs: effectiveRequestedMs,
					srcUrl,
					playheadSec: 0,
					isPaused: false,
					isBuffering: true,
					errorMsg: "",
					seq,
				});
			} catch (err) {
				if (seekSeqRef.current !== seq) return;
				setDvrPlayback((prev) => ({
					...prev,
					isBuffering: false,
					errorMsg:
						err?.message || "Không thể mở đoạn phát lại, vui lòng thử lại.",
				}));
			}
		},
		[
			clockOffsetMs,
			displayCamCode,
			dvrPlayback.anchorMs,
			dvrPlayback.srcUrl,
			enableDvr,
			ensureDvrToken,
			jumpToLive,
			mode,
			refreshDvrSegments,
			resolvedStreamPath,
			segments,
			showPlaybackNotice,
		],
	);

	const seekToTimestampRef = useRef(seekToTimestamp);
	seekToTimestampRef.current = seekToTimestamp;
	const jumpToLiveRef = useRef(jumpToLive);
	jumpToLiveRef.current = jumpToLive;

	// Tự động mở đúng mốc thời gian khi component được mount với initialSeekTimeMs (VD: Modal Bằng chứng)
	useEffect(() => {
		const target = Number(initialSeekTimeMs);
		if (enabled && enableDvr && Number.isFinite(target) && target > 0) {
			if (lastAutoSeekMsRef.current !== target) {
				lastAutoSeekMsRef.current = target;
				seekToTimestamp(target);
			}
		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [initialSeekTimeMs, displayCamCode, enabled, enableDvr]);

	// Tự động xin token xác thực ngắn hạn trước khi mở luồng Live-Annotated AI Tracking
	useEffect(() => {
		if (!showTracking) {
			setStreamToken(null);
			setTrackingError(false);
			return;
		}

		let isMounted = true;
		const targetCode = cameraCode || streamPath || "CAM-001";
		setTokenLoading(true);
		setTrackingError(false);

		getAnnotatedStreamToken(targetCode)
			.then((res) => {
				if (!isMounted) return;
				if (res && res.token) {
					setStreamToken(res.token);
				} else {
					setTrackingError(true);
				}
			})
			.catch(() => {
				if (!isMounted) return;
				setTrackingError(true);
			})
			.finally(() => {
				if (isMounted) setTokenLoading(false);
			});

		return () => {
			isMounted = false;
		};
	}, [showTracking, cameraCode, streamPath, tokenRetryTrigger]);

	const handleToggleTracking = (e) => {
		if (e) e.stopPropagation();
		if (mode === "playback") {
			jumpToLive();
		}
		setTrackingError(false);
		setShowTracking((prev) => !prev);
	};

	// Tự động kích hoạt On-Demand Cloud Relay qua MQTT khi triển khai trên Cloud HTTPS
	useEffect(() => {
		if (!enabled || !ENV_CLOUD_WHEP_BASE) return undefined;
		toggleCloudRelay(displayCamCode, true).catch(() => {});
		return () => {
			toggleCloudRelay(displayCamCode, false).catch(() => {});
		};
	}, [displayCamCode, enabled]);

	// Kết nối WebRTC WHEP chỉ chạy khi đang ở chế độ LIVE
	useEffect(() => {
		const releaseVideoStream = () => {
			const videoEl = videoRef.current;
			if (!videoEl) return;
			const currentStream = videoEl.srcObject;
			if (currentStream && typeof currentStream.getTracks === "function") {
				currentStream.getTracks().forEach((track) => {
					try {
						track.onended = null;
						track.stop();
					} catch {
						// Bỏ qua nếu track đã giải phóng
					}
				});
			}
			videoEl.srcObject = null;
		};

		if (!enabled || mode !== "live") {
			releaseVideoStream();
			if (!enabled) {
				setStatus("offline");
				setErrorMsg("");
				if (onStatusChange) onStatusChange("offline");
			}
			return undefined;
		}

		let isMounted = true;
		let peerConnection = null;
		let activeTrack = null;
		let retryTimerId = null;
		let frameWatchdogTimerId = null;
		const abortController = new AbortController();

		const clearTimers = () => {
			if (retryTimerId) {
				clearTimeout(retryTimerId);
				retryTimerId = null;
			}
			if (frameWatchdogTimerId) {
				clearTimeout(frameWatchdogTimerId);
				frameWatchdogTimerId = null;
			}
		};

		const markLiveIfDecoded = () => {
			if (!isMounted) return false;
			if (!hasDecodedVideoFrame(videoRef.current)) return false;
			clearTimers();
			consecutiveFailuresRef.current = 0;
			hadLiveFrameRef.current = true;
			setErrorMsg("");
			setStatus("live");
			if (onStatusChange) onStatusChange("live");
			return true;
		};

		const scheduleReconnect = (reasonMessage) => {
			if (!isMounted || retryTimerId) return;
			clearTimers();
			const transition = resolveWhepFailureTransition({
				consecutiveFailures: consecutiveFailuresRef.current,
				hadLiveFrame: hadLiveFrameRef.current,
			});
			consecutiveFailuresRef.current = transition.nextFailures;
			setStatus(transition.status);
			setErrorMsg(reasonMessage || "Mất kết nối luồng video");
			if (onStatusChange) onStatusChange(transition.status);
			retryTimerId = setTimeout(() => {
				retryTimerId = null;
				if (isMounted) {
					setConnectAttempt((prev) => prev + 1);
				}
			}, transition.retryDelayMs);
		};

		async function startWebRTC() {
			if (consecutiveFailuresRef.current === 0 && !hadLiveFrameRef.current) {
				setStatus("connecting");
			}

			try {
				peerConnection = new RTCPeerConnection({
					iceServers: [
						{ urls: "stun:stun.l.google.com:19302" },
						{ urls: "stun:stun1.l.google.com:19302" },
					],
				});
				pcRef.current = peerConnection;

				peerConnection.addTransceiver("video", { direction: "recvonly" });

				peerConnection.ontrack = (event) => {
					if (!isMounted) return;
					if (event.track) {
						activeTrack = event.track;
						event.track.onended = () => {
							scheduleReconnect("Nguồn phát video từ camera vừa ngừng truyền");
						};
					}
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
									markLiveIfDecoded();
								})
								.catch((e) => {
									console.debug("Tự động phát video đang chờ sẵn:", e);
								});
						}
					}
				};

				const startNoFrameWatchdog = () => {
					if (frameWatchdogTimerId || !isMounted) return;
					frameWatchdogTimerId = setTimeout(() => {
						frameWatchdogTimerId = null;
						if (!isMounted) return;
						if (!hasDecodedVideoFrame(videoRef.current)) {
							scheduleReconnect(
								"Đã kết nối WebRTC nhưng chưa giải mã được khung hình (kiểm tra codec H.264)",
							);
						} else {
							markLiveIfDecoded();
						}
					}, 5000);
				};

				peerConnection.onconnectionstatechange = () => {
					if (!isMounted) return;
					const state = peerConnection.connectionState;
					if (state === "connected") {
						if (!markLiveIfDecoded()) {
							startNoFrameWatchdog();
						}
					} else if (
						state === "disconnected" ||
						state === "failed" ||
						state === "closed"
					) {
						scheduleReconnect("Kết nối WebRTC tới MediaMTX bị gián đoạn");
					}
				};

				peerConnection.oniceconnectionstatechange = () => {
					if (!isMounted) return;
					if (peerConnection.iceConnectionState === "failed") {
						scheduleReconnect(
							"Không thể thiết lập kết nối mạng WebRTC (ICE failed)",
						);
					}
				};

				const offer = await peerConnection.createOffer();
				await peerConnection.setLocalDescription(offer);

				const targetCode = cameraCode || streamPath || "CAM-001";
				const tokenRes = await getAnnotatedStreamToken(targetCode);
				if (!isMounted) return;
				if (!tokenRes || !tokenRes.token) {
					throw new Error("Không thể cấp token xác thực luồng WebRTC");
				}
				dvrTokenRef.current = {
					token: tokenRes.token,
					expiresAt: tokenRes.expiresAt || Math.floor(Date.now() / 1000) + 120,
				};

				const baseWhepUrl = resolveWhepEndpoint(host, streamPath);
				const sep = baseWhepUrl.includes("?") ? "&" : "?";
				const whepUrl = `${baseWhepUrl}${sep}token=${encodeURIComponent(tokenRes.token)}`;
				const response = await fetch(whepUrl, {
					method: "POST",
					headers: {
						"Content-Type": "application/sdp",
					},
					body: offer.sdp,
					signal: abortController.signal,
				});

				if (!response.ok) {
					const errText = await response.text().catch(() => "");
					throw new Error(formatWhepHttpError(response.status, errText));
				}

				const answerSdp = await response.text();
				if (!isMounted) return;
				await peerConnection.setRemoteDescription({
					type: "answer",
					sdp: answerSdp,
				});
			} catch (err) {
				if (err?.name === "AbortError" || !isMounted) return;
				scheduleReconnect(err?.message || "Mất kết nối luồng video");
			}
		}

		startWebRTC();

		return () => {
			isMounted = false;
			clearTimers();
			abortController.abort();
			if (activeTrack) {
				activeTrack.onended = null;
				activeTrack = null;
			}
			if (peerConnection) {
				peerConnection.ontrack = null;
				peerConnection.onconnectionstatechange = null;
				peerConnection.oniceconnectionstatechange = null;
				peerConnection.close();
				if (pcRef.current === peerConnection) {
					pcRef.current = null;
				}
			}
			releaseVideoStream();
		};
	}, [streamPath, host, connectAttempt, enabled, mode]);

	const handleVideoReady = () => {
		if (mode !== "live") return;
		if (!hasDecodedVideoFrame(videoRef.current)) return;
		consecutiveFailuresRef.current = 0;
		hadLiveFrameRef.current = true;
		setErrorMsg("");
		setStatus("live");
		if (onStatusChange) onStatusChange("live");
	};

	// Timeline window & markers calculation
	const timeline = useMemo(
		() =>
			computeDvrWindow({
				segments,
				alerts,
				liveNowMs,
				sessionStartMs: sessionStartRef.current,
				initialSeekTimeMs,
			}),
		[segments, alerts, liveNowMs, initialSeekTimeMs],
	);

	const alertMarkers = useMemo(
		() =>
			mapAlertsToScrubberMarkers({
				alerts,
				windowStartMs: timeline.windowStartMs,
				windowEndMs: timeline.windowEndMs,
				liveNowMs,
				focusedAlertId,
			}),
		[
			alerts,
			timeline.windowStartMs,
			timeline.windowEndMs,
			liveNowMs,
			focusedAlertId,
		],
	);

	const recordedIntervals = useMemo(
		() =>
			computeRecordedIntervalsPercent({
				segments,
				windowStartMs: timeline.windowStartMs,
				windowEndMs: timeline.windowEndMs,
			}),
		[segments, timeline.windowStartMs, timeline.windowEndMs],
	);

	const playheadMs = useMemo(() => {
		if (isScrubbing && Number.isFinite(scrubPreviewMs)) {
			return scrubPreviewMs;
		}
		if (mode === "live") {
			return timeline.windowEndMs;
		}
		const computed =
			dvrPlayback.anchorMs + (Number(dvrPlayback.playheadSec) || 0) * 1000;
		return Math.min(
			timeline.windowEndMs,
			Math.max(timeline.windowStartMs, computed),
		);
	}, [
		isScrubbing,
		scrubPreviewMs,
		mode,
		timeline.windowEndMs,
		timeline.windowStartMs,
		dvrPlayback.anchorMs,
		dvrPlayback.playheadSec,
	]);

	const progressPercent = useMemo(() => {
		if (mode === "live" && !isScrubbing) return 100;
		return Math.min(
			100,
			Math.max(
				0,
				((playheadMs - timeline.windowStartMs) / timeline.spanMs) * 100,
			),
		);
	}, [mode, isScrubbing, playheadMs, timeline.windowStartMs, timeline.spanMs]);

	const calcMsFromPointerEvent = (e) => {
		const rect = scrubberTrackRef.current?.getBoundingClientRect();
		if (!rect || rect.width <= 0) return liveNowMs;
		const clientX = e.touches?.[0]?.clientX ?? e.clientX ?? 0;
		const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
		return Math.round(timeline.windowStartMs + ratio * timeline.spanMs);
	};

	const handleTrackMouseMove = (e) => {
		const hoverMs = calcMsFromPointerEvent(e);
		setScrubPreviewMs(isScrubbing ? hoverMs : null);
	};

	const handleTrackPointerDown = (e) => {
		e.stopPropagation();
		if (scrubCleanupRef.current) {
			scrubCleanupRef.current();
		}
		const targetMs = calcMsFromPointerEvent(e);
		setIsScrubbing(true);
		setScrubPreviewMs(targetMs);

		const onMove = (moveEvt) => {
			setScrubPreviewMs(calcMsFromPointerEvent(moveEvt));
		};
		const cleanupListeners = () => {
			window.removeEventListener("mousemove", onMove);
			window.removeEventListener("mouseup", onUp);
			window.removeEventListener("touchmove", onMove);
			window.removeEventListener("touchend", onUp);
			if (scrubCleanupRef.current === cleanupListeners) {
				scrubCleanupRef.current = null;
			}
		};
		const onUp = (upEvt) => {
			cleanupListeners();
			const finalMs = calcMsFromPointerEvent(upEvt);
			setIsScrubbing(false);
			setScrubPreviewMs(null);
			seekToTimestamp(finalMs);
		};
		scrubCleanupRef.current = cleanupListeners;
		window.addEventListener("mousemove", onMove);
		window.addEventListener("mouseup", onUp);
		window.addEventListener("touchmove", onMove);
		window.addEventListener("touchend", onUp);
	};

	const togglePlaybackPause = (e) => {
		if (e) e.stopPropagation();
		if (mode === "live") {
			// Tạm dừng từ LIVE -> neo ngay tại ~3 giây trước LIVE để người dùng xem tiếp từ điểm đó
			seekToTimestamp(Math.max(timeline.windowStartMs, liveNowMs - 3000));
			return;
		}
		const el = dvrVideoRef.current;
		if (!el) return;
		if (el.paused) {
			el.play().catch(() => {});
			setDvrPlayback((p) => ({ ...p, isPaused: false }));
		} else {
			el.pause();
			setDvrPlayback((p) => ({ ...p, isPaused: true }));
		}
	};

	const nudgeSeconds = (deltaSec, e) => {
		if (e) e.stopPropagation();
		const baseMs = mode === "live" ? liveNowMs : playheadMs;
		seekToTimestamp(Math.max(timeline.windowStartMs, baseMs + deltaSec * 1000));
	};

	const cyclePlaybackRate = (e) => {
		if (e) e.stopPropagation();
		const rates = [1, 1.5, 2, 0.5];
		const nextRate = rates[(rates.indexOf(playbackRate) + 1) % rates.length];
		setPlaybackRate(nextRate);
		if (dvrVideoRef.current) {
			dvrVideoRef.current.playbackRate = nextRate;
		}
	};

	const focusedMarker = useMemo(
		() => alertMarkers.find((m) => m.isFocused) || null,
		[alertMarkers],
	);

	const showLiveBlackout = mode === "live" && status !== "live";

	return (
		<div
			className={`cctv-player-wrapper ${showLiveBlackout ? "cctv-player--blackout" : ""} ${enableDvr ? "cctv-player--with-dvr" : ""}`}
		>
			{/* 1. Luồng WebRTC LIVE */}
			<video
				ref={videoRef}
				autoPlay={autoPlay}
				muted={muted}
				playsInline
				className={`cctv-video-element ${mode === "live" && status === "live" && !showTracking ? "active" : ""}`}
				style={{
					display: mode !== "live" || showTracking ? "none" : undefined,
				}}
				onPlaying={handleVideoReady}
				onLoadedData={handleVideoReady}
				onResize={handleVideoReady}
			/>

			{/* 2. Luồng MediaMTX fMP4 DVR Playback */}
			{mode === "playback" && dvrPlayback.srcUrl && (
				<video
					ref={dvrVideoRef}
					key={`dvr-${dvrPlayback.seq}`}
					src={dvrPlayback.srcUrl}
					autoPlay
					muted={muted}
					playsInline
					preload="auto"
					className="cctv-video-element active"
					onLoadedMetadata={(e) => {
						e.currentTarget.playbackRate = playbackRate;
						setDvrPlayback((p) => ({ ...p, isBuffering: false, errorMsg: "" }));
					}}
					onPlaying={() => {
						setDvrPlayback((p) => ({
							...p,
							isPaused: false,
							isBuffering: false,
							errorMsg: "",
						}));
					}}
					onPause={() => {
						setDvrPlayback((p) => ({ ...p, isPaused: true }));
					}}
					onWaiting={() => {
						setDvrPlayback((p) => ({ ...p, isBuffering: true }));
					}}
					onTimeUpdate={(e) => {
						const ct = e.currentTarget?.currentTime || 0;
						setDvrPlayback((p) => ({
							...p,
							playheadSec: ct,
							isBuffering: false,
						}));
					}}
					onEnded={() => {
						const currentEndMs =
							dvrPlayback.anchorMs + (dvrPlayback.playheadSec || 0) * 1000;
						if (currentEndMs >= Date.now() + clockOffsetMs - 6000) {
							jumpToLive();
						} else {
							seekToTimestamp(currentEndMs + 500);
						}
					}}
					onError={() => {
						setDvrPlayback((p) => ({
							...p,
							isBuffering: false,
							errorMsg:
								"Đoạn video này đang được lưu hoặc camera vừa mới kết nối lại.",
						}));
					}}
				/>
			)}

			{/* 3. Luồng AI Tracking Annotation trực tiếp */}
			{mode === "live" && showTracking && streamToken && !trackingError && (
				<img
					src={resolveAiStreamUrl(
						aiHost,
						cameraCode || streamPath || "CAM-001",
						streamToken,
					)}
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

			{/* Overlay lỗi AI Tracking */}
			{mode === "live" &&
				showTracking &&
				(trackingError || (!tokenLoading && !streamToken)) && (
					<div className="cctv-tracking-error-overlay">
						<div className="cctv-tracking-error-box">
							<span className="cctv-tracking-error-title">
								⚠️ AI Tracking Chưa Khả Dụng
							</span>
							<span className="cctv-tracking-error-desc">
								Không thể kết nối luồng phân tích thời gian thực cho [
								{displayCamCode}]. Vui lòng đảm bảo camera đã kết nối RTSP và AI
								Service đang chạy.
							</span>
							<div className="cctv-tracking-error-actions">
								<button
									type="button"
									onClick={(e) => {
										e.stopPropagation();
										setTrackingError(false);
										setTokenRetryTrigger((prev) => prev + 1);
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
										setTokenRetryTrigger((prev) => prev + 1);
									}}
									className="cctv-tracking-return-btn"
								>
									Quay lại luồng gốc
								</button>
							</div>
						</div>
					</div>
				)}

			{/* Overlay trạng thái xem lại (Đang tải hoặc Chưa có bản ghi thân thiện, không che đen toàn khung) */}
			{mode === "playback" &&
				dvrPlayback.isBuffering &&
				!dvrPlayback.errorMsg && (
					<div className="dvr-status-overlay">
						<div className="dvr-buffering-pill">
							Đang tải bản ghi lúc {formatClockTime(dvrPlayback.anchorMs)}...
						</div>
					</div>
				)}

			{playbackNotice && (
				<div
					className="playback-notice-toast"
					onClick={(e) => e.stopPropagation()}
				>
					<Info size={13} />
					<span>{playbackNotice}</span>
				</div>
			)}

			{mode === "playback" && dvrPlayback.errorMsg && (
				<div
					className="playback-friendly-overlay"
					onClick={(e) => e.stopPropagation()}
				>
					<div className="playback-friendly-card">
						<div className="playback-friendly-title">
							<Info size={14} />
							<span>Chưa có bản ghi tại lúc này</span>
						</div>
						<div className="playback-friendly-desc">{dvrPlayback.errorMsg}</div>
						<div className="dvr-error-actions">
							<button
								type="button"
								className="dvr-action-pill dvr-action-pill--live"
								onClick={(e) => {
									e.stopPropagation();
									jumpToLive();
								}}
							>
								<Radio size={12} /> Về trực tiếp
							</button>
							{timeline.earliestRecordedMs && (
								<button
									type="button"
									className="dvr-action-pill"
									onClick={(e) => {
										e.stopPropagation();
										seekToTimestamp(timeline.earliestRecordedMs);
									}}
								>
									Từ đầu bản ghi ({formatClockTime(timeline.earliestRecordedMs)}
									)
								</button>
							)}
						</div>
					</div>
				</div>
			)}

			{/* Thanh OSD Phía Trên */}
			<div className="cctv-osd-top-bar">
				<div className="cctv-osd-top-left">
					<span
						className="cctv-spec-code"
						title={displayLabel || displayCamCode}
					>
						{displayLabel || displayCamCode}
					</span>
					{incidentCount > 0 && (
						<span className={`cctv-incident-pill ${incidentSeverityClass}`}>
							<Flame size={11} />
							<span>{incidentCount}</span>
						</span>
					)}
					{relationBadge && (
						<span
							className="cctv-relation-pill"
							title="Khoảng cách / tầng lân cận so với ô tiêu điểm"
						>
							<Navigation size={10} />
							<span>{relationBadge}</span>
						</span>
					)}
				</div>

				<div className="cctv-osd-top-right">
					{(enabled || mode === "playback") &&
						(showLiveTag ||
							(isRecording && showRecTag) ||
							enableTrackingToggle ||
							aiModeLabel ||
							mode === "playback") && (
							<div className="cctv-indicator-group">
								{isRecording && showRecTag && (
									<span className="cctv-tag cctv-tag--rec">
										<span className="cctv-dot cctv-dot--red" />
										REC
									</span>
								)}

								{aiModeLabel && enabled && (
									<span className="cctv-tag cctv-tag--ai-mode">
										<span className="cctv-dot cctv-dot--green" />
										{aiModeLabel}
									</span>
								)}

								{enableTrackingToggle && enabled && (
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
										<span
											className={`cctv-dot ${showTracking ? "cctv-dot--cyan" : "cctv-dot--idle"}`}
										/>
										{showTracking ? "TRACKING: ON" : "TRACKING"}
									</button>
								)}
							</div>
						)}

					{(enabled || mode === "playback") && (
						<span className="cctv-clock-text">
							{mode === "playback" ? formatClockTime(playheadMs) : liveClock}
						</span>
					)}

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

			{/* Thanh Bottom cổ điển chỉ hiện khi tắt DVR */}
			{!enableDvr && (
				<div className="cctv-osd-bottom-bar">
					<div className="cctv-osd-bottom-left">
						<span className="cctv-spec-code">{displayCamCode}</span>
						{resolution && (
							<>
								<span className="cctv-spec-divider">•</span>
								<span className="cctv-spec-item">{resolution}</span>
							</>
						)}
						{fps && (
							<>
								<span className="cctv-spec-divider">•</span>
								<span className="cctv-spec-item">{fps}</span>
							</>
						)}
						{showTracking && streamToken && !trackingError && (
							<>
								<span className="cctv-spec-divider">•</span>
								<span className="cctv-ai-active-tag">AI LIVE OVERLAY</span>
							</>
						)}
					</div>
				</div>
			)}

			{/* Thanh chọn nhanh Camera lân cận (tính theo tọa độ 2D) hiển thị ở ô tiêu điểm */}
			{alwaysShowDvr &&
				enabled &&
				mode === "live" &&
				adjacentCameras.length > 0 &&
				typeof onSelectAdjacentCamera === "function" && (
					<div
						className="adjacent-cam-bar"
						onClick={(e) => e.stopPropagation()}
					>
						<span
							className="adjacent-cam-label"
							title="Các camera lân cận gần nhất theo mặt bằng 2D"
						>
							<Navigation size={11} /> Lân cận:
						</span>
						{adjacentCameras.map((adj) => (
							<button
								key={adj.camera.code}
								type="button"
								className="adjacent-cam-pill"
								onClick={() => onSelectAdjacentCamera(adj.camera.code)}
								title={`Chuyển sang ${adj.camera.name} (${adj.relationLabel})`}
							>
								<span>{adj.camera.name}</span>
								{adj.distanceMeters != null && adj.distanceMeters <= 500 && (
									<span className="adjacent-cam-dist">
										{adj.distanceMeters}m
									</span>
								)}
							</button>
						))}
					</div>
				)}

			{/* Thanh Xem lại Video Scrubber (2 màu: Đã ghi vs Chưa ghi) + Chấm đỏ sự cố */}
			{enableDvr && (enabled || mode === "playback") && (
				<div
					className={`dvr-scrubber-bar ${compactScrubber ? "is-compact" : ""} ${alwaysShowDvr || mode === "playback" ? "is-pinned" : ""} ${isScrubbing ? "is-scrubbing" : ""}`}
					onClick={(e) => e.stopPropagation()}
				>
					{/* Hàng 1: Timeline Scrubber Track + Chấm đỏ cảnh báo */}
					<div
						ref={scrubberTrackRef}
						className="dvr-track-hitarea"
						onMouseDown={handleTrackPointerDown}
						onTouchStart={handleTrackPointerDown}
						onMouseMove={handleTrackMouseMove}
						onMouseLeave={() => {
							setHoveredMarker(null);
							if (!isScrubbing) setScrubPreviewMs(null);
						}}
						title="Kéo hoặc bấm thanh xanh để xem lại • Bấm chấm đỏ để nhảy tới sự cố"
					>
						<div className="dvr-track-rail">
							{recordedIntervals.map((seg, idx) => (
								<div
									key={`rec-${idx}`}
									className="dvr-track-recorded-segment"
									style={{
										left: `${seg.leftPercent}%`,
										width: `${seg.widthPercent}%`,
									}}
								/>
							))}
							<div
								className={`dvr-track-fill ${mode === "live" ? "is-live" : "is-dvr"}`}
								style={{ width: `${progressPercent}%` }}
							/>
							<div
								className={`dvr-track-thumb ${mode === "live" ? "is-live" : "is-dvr"}`}
								style={{ left: `${progressPercent}%` }}
							/>
						</div>

						{/* Các chấm đỏ sự cố gắn trực tiếp theo thời gian thực trên thanh tua */}
						{alertMarkers.map((marker) => (
							<button
								key={marker.id}
								type="button"
								className={`dvr-alert-dot ${marker.isRecent ? "is-recent" : ""} ${marker.isFocused ? "is-focused" : ""}`}
								style={{ left: `${marker.percent}%` }}
								onMouseDown={(e) => e.stopPropagation()}
								onClick={(e) => {
									e.stopPropagation();
									lastAutoSeekMsRef.current = marker.timeMs;
									seekToTimestamp(marker.timeMs);
									if (onAlertMarkerClick && marker.rawAlert) {
										onAlertMarkerClick(marker.rawAlert);
									}
								}}
								onMouseEnter={() => setHoveredMarker(marker)}
								onMouseLeave={() => setHoveredMarker(null)}
								aria-label={`Sự cố lúc ${marker.timestampLabel}: ${marker.title}`}
							/>
						))}

						{/* Tooltip hiển thị khi rê chuột qua chấm đỏ hoặc kéo thanh tua */}
						{hoveredMarker && (
							<div
								className="dvr-scrubber-tooltip is-alert"
								style={{
									left: `${Math.min(92, Math.max(8, hoveredMarker.percent))}%`,
								}}
							>
								<span className="dvr-tooltip-dot" />
								<strong>{hoveredMarker.timestampLabel}</strong>
								<span>{hoveredMarker.title}</span>
							</div>
						)}
						{!hoveredMarker &&
							isScrubbing &&
							Number.isFinite(scrubPreviewMs) && (
								<div
									className="dvr-scrubber-tooltip"
									style={{
										left: `${Math.min(92, Math.max(8, progressPercent))}%`,
									}}
								>
									<strong>{formatClockTime(scrubPreviewMs)}</strong>
									<span>
										({formatRelativeBehindLive(scrubPreviewMs, liveNowMs)})
									</span>
								</div>
							)}
					</div>

					{/* Hàng 2: Nút điều khiển Play/Pause, -10s, +10s, Live, Mốc thời gian */}
					<div className="dvr-controls-row">
						<div className="dvr-controls-left">
							<button
								type="button"
								className="dvr-ctrl-btn"
								onClick={togglePlaybackPause}
								title={
									mode === "live" || !dvrPlayback.isPaused
										? "Tạm dừng"
										: "Phát tiếp"
								}
							>
								{mode === "playback" && dvrPlayback.isPaused ? (
									<Play
										size={13}
										fill="currentColor"
									/>
								) : (
									<Pause
										size={13}
										fill="currentColor"
									/>
								)}
							</button>

							<button
								type="button"
								className="dvr-ctrl-btn"
								onClick={(e) => nudgeSeconds(-10, e)}
								title="Tua lùi 10 giây"
							>
								<RotateCcw size={12} />
								<span className="dvr-ctrl-sub">10</span>
							</button>

							<button
								type="button"
								className="dvr-ctrl-btn"
								onClick={(e) => nudgeSeconds(10, e)}
								title="Tua tiến 10 giây"
							>
								<RotateCw size={12} />
								<span className="dvr-ctrl-sub">10</span>
							</button>

							<button
								type="button"
								className={`dvr-live-pill ${mode === "live" ? "is-at-live" : "is-behind"}`}
								onClick={(e) => {
									e.stopPropagation();
									jumpToLive();
								}}
								title={
									mode === "live"
										? "Đang xem trực tiếp"
										: "Bấm để nhảy ngay về trực tiếp (LIVE)"
								}
							>
								<span className="dvr-live-dot" />
								<span>TRỰC TIẾP</span>
							</button>

							<span className="dvr-time-readout">
								{mode === "live"
									? formatClockTime(liveNowMs)
									: `${formatClockTime(playheadMs)} (${formatRelativeBehindLive(playheadMs, liveNowMs)})`}
							</span>
						</div>

						<div className="dvr-controls-right">
							{focusedMarker && (
								<button
									type="button"
									className="dvr-jump-alert-btn"
									onClick={(e) => {
										e.stopPropagation();
										seekToTimestamp(focusedMarker.timeMs);
									}}
									title={`Nhảy về đúng chấm đỏ sự cố lúc ${focusedMarker.timestampLabel}`}
								>
									<ShieldAlert size={12} />
									<span>Sự cố ({focusedMarker.timestampLabel})</span>
								</button>
							)}

							{mode === "playback" && (
								<button
									type="button"
									className="dvr-speed-pill"
									onClick={cyclePlaybackRate}
									title="Đổi tốc độ phát lại"
								>
									{playbackRate}x
								</button>
							)}

							{alertMarkers.length > 0 && (
								<span
									className="dvr-alert-count-chip"
									title="Số cảnh báo trên dòng thời gian hiện tại"
								>
									● {alertMarkers.length}
								</span>
							)}
						</div>
					</div>
				</div>
			)}

			{showLiveBlackout && (
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
						{enabled && errorMsg && (
							<div className="cctv-offline-reason">{errorMsg}</div>
						)}
					</div>
				</div>
			)}
		</div>
	);
});

export default WebRtcPlayer;
