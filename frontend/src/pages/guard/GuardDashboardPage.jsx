import React, { useState, useEffect, useMemo, useRef } from "react";
import { Client } from "@stomp/stompjs";
import {
	ShieldAlert,
	Video,
	Bell,
	CheckCircle2,
	Volume2,
	VolumeX,
	Radio,
	Clock,
	Loader2,
	Zap,
	Flame,
	ArrowRight,
	RotateCcw,
	Sparkles,
	Maximize2,
	Minimize2,
	AlertTriangle,
} from "lucide-react";
import WebRtcPlayer from "../../components/video/WebRtcPlayer";
import { fetchCameras } from "../../services/cameraService";
import "../../styles/GuardDashboardPage.css";

// Trọng số phân cấp mức độ nghiêm trọng của sự kiện an ninh
const EVENT_SEVERITY_CONFIG = {
	UNAUTHORIZED_ACCESS: {
		weight: 3,
		label: "Xâm nhập trái phép",
		badgeClass: "severity-critical",
		color: "#ef4444",
	},
	ENTRY_EXIT_TRACKING: {
		weight: 3,
		label: "Cảnh báo Ra/Vào",
		badgeClass: "severity-critical",
		color: "#ef4444",
	},
	LOITERING: {
		weight: 2,
		label: "Lảng vảng trong khu vực",
		badgeClass: "severity-high",
		color: "#f59e0b",
	},
	LOITERING_UNIDENTIFIED_PERSON: {
		weight: 2,
		label: "Lảng vảng nghi vấn",
		badgeClass: "severity-high",
		color: "#f59e0b",
	},
	LOITERING_UNIDENTIFIED: {
		weight: 2,
		label: "Lảng vảng nghi vấn",
		badgeClass: "severity-high",
		color: "#f59e0b",
	},
	CROWD_OVERCROWDING: {
		weight: 1,
		label: "Tụ tập đám đông",
		badgeClass: "severity-warning",
		color: "#38bdf8",
	},
	OVERCROWDING: {
		weight: 1,
		label: "Tụ tập đám đông",
		badgeClass: "severity-warning",
		color: "#38bdf8",
	},
};

function getSeverityMeta(eventType) {
	return (
		EVENT_SEVERITY_CONFIG[eventType] || {
			weight: 1,
			label: eventType || "Cảnh báo an ninh",
			badgeClass: "severity-warning",
			color: "#f59e0b",
		}
	);
}

/**
 * Trung tâm Giám sát An ninh (SOC) — Security Surveillance Center
 * Tên trước: GuardDashboardPage
 */
export function SecuritySurveillancePage() {
	const [selectedCamera, setSelectedCamera] = useState("");
	const [cameraLayout, setCameraLayout] = useState(1); // 1 | 4 (2x2) | 9 (3x3) | 16 (4x4)
	const [isTheaterMode, setIsTheaterMode] = useState(false); // Chế độ phóng to chỉ view màn hình
	const [soundEnabled, setSoundEnabled] = useState(true);
	const [wsConnected, setWsConnected] = useState(false);
	const [activeAlerts, setActiveAlerts] = useState([]);
	const [cameraList, setCameraList] = useState([]);
	const [camerasLoading, setCamerasLoading] = useState(true);
	const [alertFilter, setAlertFilter] = useState("ALL"); // 'ALL' | 'PENDING'
	const [flashAlertCamera, setFlashAlertCamera] = useState(null);
	const [statusNotification, setStatusNotification] = useState(null);

	const flashTimeoutRef = useRef(null);
	const soundEnabledRef = useRef(soundEnabled);

	useEffect(() => {
		soundEnabledRef.current = soundEnabled;
	}, [soundEnabled]);

	// Phím tắt ESC để thoát chế độ phóng to màn hình
	useEffect(() => {
		const handleKeyDown = (e) => {
			if (e.key === "Escape" && isTheaterMode) {
				setIsTheaterMode(false);
			}
		};
		window.addEventListener("keydown", handleKeyDown);
		return () => window.removeEventListener("keydown", handleKeyDown);
	}, [isTheaterMode]);

	// Hiển thị thông báo toast tạm thời
	const triggerNotification = (msg) => {
		setStatusNotification(msg);
		setTimeout(() => setStatusNotification(null), 4500);
	};

	// Tải danh sách camera đang hoạt động từ Backend CSDL
	useEffect(() => {
		async function loadActiveCameras() {
			setCamerasLoading(true);
			try {
				const response = await fetchCameras({
					page: 0,
					size: 50,
					status: "ACTIVE",
				});
				const list = (response?.content || []).map((cam) => ({
					id: cam.id,
					code: (cam.cameraCode || "")
						.toLowerCase()
						.replace(/[^a-z0-9_-]/g, "")
						.trim(),
					cameraCode: cam.cameraCode,
					name: cam.name,
					zone: "Khuôn viên trường",
					status: cam.operationalStatus || "ONLINE",
				}));

				// Sắp xếp tự nhiên tăng dần theo mã camera (CAM-001, CAM-002, CAM-003, ...)
				list.sort((a, b) =>
					(a.cameraCode || "").localeCompare(b.cameraCode || "", undefined, {
						numeric: true,
						sensitivity: "base",
					}),
				);

				setCameraList(list);
				if (list.length > 0) {
					setSelectedCamera((prev) =>
						prev && list.some((c) => c.code === prev) ? prev : list[0].code,
					);
				}
			} catch (err) {
				console.error("Không thể tải danh sách camera:", err);
			} finally {
				setCamerasLoading(false);
			}
		}

		loadActiveCameras();
	}, []);

	// Tính toán tóm tắt sự cố & mức độ ưu tiên của từng camera dựa trên activeAlerts PENDING
	const cameraIncidentSummary = useMemo(() => {
		const summary = {};
		for (const cam of cameraList) {
			summary[cam.code] = {
				count: 0,
				maxWeight: 0,
				latestTimestamp: 0,
				highestSeverity: null,
			};
		}

		for (const alert of activeAlerts) {
			if (alert.status === "PENDING") {
				const cCode = (alert.cameraCode || "")
					.toLowerCase()
					.replace(/[^a-z0-9_-]/g, "")
					.trim();
				const sev = getSeverityMeta(alert.eventType);
				if (!summary[cCode]) {
					summary[cCode] = {
						count: 0,
						maxWeight: 0,
						latestTimestamp: 0,
						highestSeverity: null,
					};
				}
				summary[cCode].count += 1;
				if (sev.weight > summary[cCode].maxWeight) {
					summary[cCode].maxWeight = sev.weight;
					summary[cCode].highestSeverity = sev;
				}
				const alertTime = alert.rawTime || 0;
				if (alertTime > summary[cCode].latestTimestamp) {
					summary[cCode].latestTimestamp = alertTime;
				}
			}
		}
		return summary;
	}, [cameraList, activeAlerts]);

	// Sắp xếp danh sách camera: Mặc định tăng dần, tự động đưa camera có sự cố lên đầu theo mức độ ưu tiên
	const sortedCameras = useMemo(() => {
		return [...cameraList].sort((a, b) => {
			const sA = cameraIncidentSummary[a.code] || {
				count: 0,
				maxWeight: 0,
				latestTimestamp: 0,
			};
			const sB = cameraIncidentSummary[b.code] || {
				count: 0,
				maxWeight: 0,
				latestTimestamp: 0,
			};

			// Nếu có sự cố, tự động ưu tiên camera có sự cố lên đầu
			if (sA.count > 0 && sB.count === 0) return -1;
			if (sA.count === 0 && sB.count > 0) return 1;

			if (sA.count > 0 && sB.count > 0) {
				// Cùng có sự cố: So sánh trọng số nghiêm trọng (3 > 2 > 1)
				if (sA.maxWeight !== sB.maxWeight) {
					return sB.maxWeight - sA.maxWeight;
				}
				// Cùng mức độ: Sự cố mới nhất lên trước
				if (sA.latestTimestamp !== sB.latestTimestamp) {
					return sB.latestTimestamp - sA.latestTimestamp;
				}
			}

			// Mặc định hoặc khi cùng mức độ ưu tiên: Sắp xếp tăng dần tự nhiên theo mã camera (CAM-001, CAM-002, CAM-003, ...)
			return (a.cameraCode || "").localeCompare(b.cameraCode || "", undefined, {
				numeric: true,
				sensitivity: "base",
			});
		});
	}, [cameraList, cameraIncidentSummary]);

	// Số liệu thống kê hệ thống (System Status Metrics)
	const onlineCameraCount = cameraList.filter(
		(c) => c.status === "ONLINE",
	).length;
	const totalCameraCount = cameraList.length || 4;
	const recordingCameraCount = onlineCameraCount > 0 ? onlineCameraCount : 3;

	// Âm thanh cảnh báo
	const playAlertSound = () => {
		if (!soundEnabledRef.current) return;
		try {
			const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
			const osc = audioCtx.createOscillator();
			const gain = audioCtx.createGain();
			osc.type = "sawtooth";
			osc.frequency.setValueAtTime(850, audioCtx.currentTime);
			osc.frequency.exponentialRampToValueAtTime(
				380,
				audioCtx.currentTime + 0.35,
			);
			gain.gain.setValueAtTime(0.3, audioCtx.currentTime);
			gain.gain.linearRampToValueAtTime(0.01, audioCtx.currentTime + 0.35);
			osc.connect(gain);
			gain.connect(audioCtx.destination);
			osc.start();
			osc.stop(audioCtx.currentTime + 0.35);
		} catch (e) {
			console.warn("Audio Context không được phép tự động phát:", e);
		}
	};

	// Kích hoạt nhấp nháy nổi bật camera khi có sự cố
	const flashCameraAlert = (camCode) => {
		setFlashAlertCamera(camCode);
		if (flashTimeoutRef.current) clearTimeout(flashTimeoutRef.current);
		flashTimeoutRef.current = setTimeout(() => {
			setFlashAlertCamera(null);
		}, 7000);
	};

	// Nhận diện và tiếp nhận cảnh báo mới (dùng chung cho WebSocket & Nút Test)
	const processNewIncident = (incident) => {
		const camCodeClean = (
			incident.camera_code ||
			incident.cameraCode ||
			"CAM-001"
		)
			.toLowerCase()
			.replace(/[^a-z0-9_-]/g, "")
			.trim();

		const newAlert = {
			id: incident.id || incident.event_id || Date.now(),
			cameraCode: (
				incident.camera_code ||
				incident.cameraCode ||
				"CAM-001"
			).toUpperCase(),
			camCodeClean,
			cameraName:
				incident.areaName ||
				incident.area_name ||
				incident.cameraName ||
				"Khu vực camera",
			eventType: incident.event_type || incident.eventType || "LOITERING",
			message:
				incident.details ||
				incident.resolutionNotes ||
				incident.message ||
				"Phát hiện sự cố an ninh trong vùng cấm",
			duration: incident.duration_seconds
				? `${incident.duration_seconds}s`
				: incident.duration || "Vừa phát hiện",
			timestamp: new Date().toLocaleTimeString("vi-VN"),
			rawTime: Date.now(),
			status: incident.status || "PENDING",
		};

		setActiveAlerts((prev) => [newAlert, ...prev]);
		playAlertSound();
		flashCameraAlert(camCodeClean);

		// Tự động chuyển camera đang chọn (cho Layout 1)
		setSelectedCamera(camCodeClean);
	};

	// Lắng nghe sự kiện cảnh báo an ninh qua WebSocket STOMP
	useEffect(() => {
		const API_BASE_URL =
			import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";
		const brokerURL = API_BASE_URL.replace(/^http/, "ws") + "/ws-security";

		const stompClient = new Client({
			brokerURL,
			reconnectDelay: 4000,
			heartbeatIncoming: 4000,
			heartbeatOutgoing: 4000,
			onConnect: () => {
				setWsConnected(true);

				stompClient.subscribe("/topic/security-alerts", (message) => {
					if (message.body) {
						try {
							const incident = JSON.parse(message.body);
							processNewIncident(incident);
						} catch (err) {
							console.error("Lỗi parse incident JSON:", err);
						}
					}
				});

				stompClient.subscribe("/topic/incidents/updates", (message) => {
					if (message.body) {
						try {
							const update = JSON.parse(message.body);
							setActiveAlerts((prev) =>
								prev.map((a) =>
									a.id === update.incidentId ||
									String(a.id) === String(update.incidentId)
										? { ...a, status: update.status }
										: a,
								),
							);
						} catch (err) {
							console.error("Lỗi parse incident update:", err);
						}
					}
				});
			},
			onDisconnect: () => setWsConnected(false),
			onWebSocketClose: () => setWsConnected(false),
			onWebSocketError: () => setWsConnected(false),
			onStompError: () => setWsConnected(false),
		});

		stompClient.activate();
		return () => {
			stompClient.deactivate();
		};
	}, []);

	// Xác nhận xử lý cảnh báo
	const handleAcknowledge = (id) => {
		setActiveAlerts((prev) =>
			prev.map((a) => (a.id === id ? { ...a, status: "RESOLVED" } : a)),
		);
	};

	// Xóa toàn bộ sự cố đã giải quyết
	const handleClearResolved = () => {
		setActiveAlerts((prev) => prev.filter((a) => a.status === "PENDING"));
	};

	// Chuyển trực tiếp video sang camera cụ thể
	const handleSelectCamera = (targetCamCode) => {
		const clean = (targetCamCode || "")
			.toLowerCase()
			.replace(/[^a-z0-9_-]/g, "")
			.trim();
		setSelectedCamera(clean);
		flashCameraAlert(clean);
	};

	// 1. NÚT TEST: Bắn 1 sự cố ngẫu nhiên để kiểm tra tự chuyển camera
	const handleSimulateSingleAlert = () => {
		const otherCameras = cameraList.filter((c) => c.code !== selectedCamera);
		const targetCam =
			otherCameras.length > 0
				? otherCameras[Math.floor(Math.random() * otherCameras.length)]
				: cameraList[0] || {
						cameraCode: "CAM-002",
						name: "Cổng Phụ",
						code: "cam-002",
					};

		processNewIncident({
			id: Date.now(),
			camera_code: targetCam.cameraCode || targetCam.code.toUpperCase(),
			area_name: targetCam.name,
			event_type: "UNAUTHORIZED_ACCESS",
			details: `CẢNH BÁO: Phát hiện xâm nhập trái phép tại khu vực ${targetCam.name}!`,
			duration_seconds: 4.5,
		});
	};

	// 2. NÚT TEST: Bắn đa sự cố đồng thời với mức độ ưu tiên khác nhau
	const handleSimulateMultiPriorityAlerts = () => {
		playAlertSound();

		const targets = [
			{
				cam: cameraList[2] || {
					cameraCode: "CAM-003",
					name: "Phòng Server / Thiết bị",
					code: "cam-003",
				},
				type: "UNAUTHORIZED_ACCESS",
				details:
					"CỰC KỲ NGUY CẤP: Đối tượng lạ phá khóa đột nhập phòng Server!",
				duration: "3.0s",
			},
			{
				cam: cameraList[0] || {
					cameraCode: "CAM-001",
					name: "Hành Lang Chính Tầng 1",
					code: "cam-001",
				},
				type: "LOITERING",
				details:
					"Cảnh báo: Đối tượng che mặt lảng vảng ngoài hành lang hơn 20s.",
				duration: "21.0s",
			},
			{
				cam: cameraList[1] || {
					cameraCode: "CAM-002",
					name: "Khu Vực Căn Tin",
					code: "cam-002",
				},
				type: "CROWD_OVERCROWDING",
				details: "Thông báo: Tụ tập đám đông vượt ngưỡng quy định (>15 người).",
				duration: "12.0s",
			},
		];

		const timestamp = new Date().toLocaleTimeString("vi-VN");
		const now = Date.now();

		const generatedAlerts = targets.map((item, index) => {
			const cleanCode = item.cam.code || item.cam.cameraCode.toLowerCase();
			return {
				id: now + index,
				cameraCode: item.cam.cameraCode.toUpperCase(),
				camCodeClean: cleanCode,
				cameraName: item.cam.name,
				eventType: item.type,
				message: item.details,
				duration: item.duration,
				timestamp,
				rawTime: now - index * 100, // CAM-003 mới nhất
				status: "PENDING",
			};
		});

		setActiveAlerts((prev) => [...generatedAlerts, ...prev]);

		// Tự động chuyển ngay sang camera có sự cố NGUY CẤP NHẤT (CAM-003)
		const highestPriorityCam =
			targets[0].cam.code || targets[0].cam.cameraCode.toLowerCase();
		setSelectedCamera(highestPriorityCam);
		flashCameraAlert(highestPriorityCam);
	};

	// 4 TÙY CHỌN BỐ CỤC LƯỚI CAMERA: 1, 2x2, 3x3, 4x4
	const cameraLayoutOptions = [
		{ value: 1, label: "1" },
		{ value: 4, label: "2x2" },
		{ value: 9, label: "3x3" },
		{ value: 16, label: "4x4" },
	];

	// Danh sách camera hiển thị trên lưới theo layout đã chọn
	const visibleCameras =
		cameraLayout === 1
			? [
					sortedCameras.find((cam) => cam.code === selectedCamera) ||
						sortedCameras[0],
				].filter(Boolean)
			: sortedCameras.slice(0, cameraLayout);

	const pendingAlertCount = activeAlerts.filter(
		(a) => a.status === "PENDING",
	).length;
	const filteredAlerts =
		alertFilter === "PENDING"
			? activeAlerts.filter((a) => a.status === "PENDING")
			: activeAlerts;

	return (
		<div
			className={`guard-dashboard-container ${isTheaterMode ? "soc-theater-mode" : ""}`}
		>
			{/* Toast Notification */}
			{statusNotification && (
				<div className="soc-floating-toast">
					<Sparkles size={16} />
					<span>{statusNotification}</span>
				</div>
			)}

			{/* Top Header Điều Hành An Ninh (SOC Header) — Ẩn khi phóng to */}
			{!isTheaterMode && (
				<div className="guard-top-header">
					<div className="guard-title-box">
						<div>
							<h2>Trung tâm Giám sát An ninh</h2>
						</div>
					</div>

					<div className="guard-header-actions">
						{/* Nút Phóng to chỉ view màn hình */}
						<button
							type="button"
							className={`btn-theater-mode ${isTheaterMode ? "active" : ""}`}
							onClick={() => setIsTheaterMode(!isTheaterMode)}
							title="Phóng to chỉ view màn hình camera (ẩn các panel xung quanh)"
						>
							<Maximize2 size={15} />
							<span>Phóng to giám sát</span>
						</button>

						{/* Bật/Tắt âm thanh chuông */}
						<button
							type="button"
							className={`btn-sound-toggle ${soundEnabled ? "active" : "muted"}`}
							onClick={() => {
								const next = !soundEnabled;
								setSoundEnabled(next);
							}}
							title={
								soundEnabled ? "Tắt âm thanh còi báo" : "Bật âm thanh còi báo"
							}
						>
							{soundEnabled ? <Volume2 size={15} /> : <VolumeX size={15} />}
							<span>{soundEnabled ? "Âm thanh" : "Tắt tiếng"}</span>
						</button>

						{/* Nhóm nút TEST mô phỏng */}
						<div className="test-btn-group">
							<button
								type="button"
								className="btn-sim-alert"
								onClick={handleSimulateSingleAlert}
								title="Mô phỏng 1 sự cố ngẫu nhiên"
							>
								<Bell size={14} />
								<span>Test 1 sự cố</span>
							</button>

							<button
								type="button"
								className="btn-sim-multi-alert"
								onClick={handleSimulateMultiPriorityAlerts}
								title="Mô phỏng đồng thời nhiều sự cố với độ ưu tiên khác nhau"
							>
								<Flame size={14} />
								<span>Test đa sự cố (Ưu tiên)</span>
							</button>
						</div>
					</div>
				</div>
			)}

			{/* Main Grid: Left Video Surveillance vs Right Alert Stream */}
			<div className="guard-main-grid">
				{/* Cột Trái: Giám Sát Video Trực Tiếp */}
				<div className="video-surveillance-panel">
					{/* Thanh công cụ điều khiển camera & Bộ chọn 5 Layouts */}
					<div className="camera-control-bar">
						<div className="camera-layout-group">
							<span className="control-bar-label">
								<Video size={14} /> Bố cục:
							</span>
							<div className="camera-layout-options">
								{cameraLayoutOptions.map((opt) => (
									<button
										key={opt.value}
										type="button"
										className={`camera-layout-option ${cameraLayout === opt.value ? "active" : ""}`}
										onClick={() => setCameraLayout(opt.value)}
									>
										{opt.label}
									</button>
								))}
							</div>
						</div>

						{/* Dải Camera Quick Switcher (Sắp xếp theo thứ tự ưu tiên sự cố với màu sắc tương ứng) */}
						<div className="camera-quick-switcher">
							{camerasLoading ? (
								<span className="switcher-loading">
									<Loader2
										size={13}
										className="animate-spin"
									/>{" "}
									Đang tải camera...
								</span>
							) : (
								sortedCameras.map((cam) => {
									const summary = cameraIncidentSummary[cam.code] || {
										count: 0,
									};
									const hasIncident = summary.count > 0;
									const isSelected = selectedCamera === cam.code;
									const isFlashing = flashAlertCamera === cam.code;
									const severityClass =
										summary.highestSeverity?.badgeClass || "severity-warning";

									return (
										<button
											key={cam.code}
											type="button"
											className={`cam-chip-btn ${isSelected ? "active" : ""} ${hasIncident ? `has-incident ${severityClass}` : ""} ${isFlashing ? `flashing ${severityClass}` : ""}`}
											onClick={() => handleSelectCamera(cam.code)}
											title={`Xem Camera ${cam.cameraCode}: ${cam.name}${hasIncident ? ` (${summary.count} sự cố: ${summary.highestSeverity?.label || "Cảnh báo"})` : ""}`}
										>
											<span
												className={`status-indicator-dot ${cam.status === "ONLINE" ? "online" : "standby"}`}
											/>
											<span className="cam-chip-code">{cam.cameraCode}</span>

											{/* Priority Incident Badge theo màu sắc mức độ */}
											{hasIncident && (
												<span
													className={`cam-chip-incident-badge ${severityClass}`}
												>
													<Flame size={10} /> {summary.count}
												</span>
											)}
										</button>
									);
								})
							)}
						</div>

						{/* Nút Thao tác khi ở Theater Mode: Test sự cố & Thu nhỏ */}
						{isTheaterMode && (
							<div className="theater-actions-bar">
								<button
									type="button"
									className={`btn-theater-test ${soundEnabled ? "" : "muted"}`}
									onClick={() => {
										const next = !soundEnabled;
										setSoundEnabled(next);
									}}
									title={
										soundEnabled
											? "Tắt âm thanh còi báo"
											: "Bật âm thanh còi báo"
									}
								>
									{soundEnabled ? <Volume2 size={12} /> : <VolumeX size={12} />}
									<span>{soundEnabled ? "Âm thanh" : "Tắt tiếng"}</span>
								</button>
								<button
									type="button"
									className="btn-theater-test"
									onClick={handleSimulateSingleAlert}
									title="Mô phỏng 1 sự cố để kiểm tra tự chuyển camera & tô đỏ màn hình trong chế độ phóng to"
								>
									<Bell size={12} />
									<span>Test 1 sự cố</span>
								</button>
								<button
									type="button"
									className="btn-theater-test priority"
									onClick={handleSimulateMultiPriorityAlerts}
									title="Mô phỏng đa sự cố ưu tiên trong chế độ phóng to"
								>
									<Flame size={12} />
									<span>Test đa sự cố</span>
								</button>
								<button
									type="button"
									className="soc-exit-theater-btn"
									onClick={() => setIsTheaterMode(false)}
									title="Thoát chế độ phóng to màn hình (Phím ESC)"
								>
									<Minimize2 size={13} />
									<span>Thu nhỏ (ESC)</span>
								</button>
							</div>
						)}
					</div>

					{/* Lưới Khung Hình Video (Hỗ trợ 5 Layouts: 1, 2, 4, 6, 9) */}
					<div className={`camera-grid camera-grid--${cameraLayout}`}>
						{camerasLoading ? (
							<div className="camera-grid-placeholder">
								<Loader2
									className="animate-spin"
									size={32}
								/>
								<span>Đang kết nối danh sách camera...</span>
							</div>
						) : visibleCameras.length === 0 ? (
							<div className="camera-grid-placeholder">
								<Video
									size={44}
									style={{ opacity: 0.35 }}
								/>
								<span>Không có camera khả dụng</span>
							</div>
						) : (
							visibleCameras.map((camera) => {
								const summary = cameraIncidentSummary[camera.code] || {
									count: 0,
								};
								const hasIncident = summary.count > 0;
								const isFlashing = flashAlertCamera === camera.code;
								const severityClass =
									summary.highestSeverity?.badgeClass || "severity-warning";

								return (
									<div
										className={`video-viewport-card ${hasIncident ? `card-has-incident ${severityClass}` : ""} ${isFlashing ? `card-flashing ${severityClass}` : ""}`}
										key={camera.code}
									>
										{/* Banner cảnh báo trên khung video với màu sắc tương ứng mức độ ưu tiên */}
										{hasIncident && (
											<div
												className={`viewport-incident-banner ${severityClass}`}
											>
												<Flame
													size={13}
													className="banner-flame-icon"
												/>
												<span>
													{summary.count} SỰ CỐ:{" "}
													{summary.highestSeverity?.label || "Cảnh báo an ninh"}
												</span>
											</div>
										)}

										<WebRtcPlayer
											streamPath={camera.code}
											cameraCode={camera.cameraCode}
											cameraName={camera.name}
											resolution="1080p"
											fps="30 FPS"
											isRecording={true}
											host="localhost:8889"
											onStatusChange={(playerStatus) => {
												const newOpStatus =
													playerStatus === "live" ? "ONLINE" : "OFFLINE";
												setCameraList((prev) =>
													prev.map((c) =>
														c.code === camera.code
															? { ...c, status: newOpStatus }
															: c,
													),
												);
											}}
											onToggleMaximize={() => {
												setSelectedCamera(camera.code);
												setCameraLayout(1);
											}}
										/>
									</div>
								);
							})
						)}
					</div>
				</div>

				{/* Cột Phải: SỰ KIỆN AN NINH & THỐNG KÊ HỆ THỐNG — Ẩn khi phóng to */}
				{!isTheaterMode && (
					<div className="incident-alerts-panel">
						{/* ┌──────────────────────────────┐
						    │ 🛡 SỰ KIỆN AN NINH     0     │
						    └──────────────────────────────┘ */}
						<div className="soc-panel-header">
							<div className="soc-panel-title">
								<ShieldAlert
									size={18}
									className="soc-shield-icon"
								/>
								<span>SỰ KIỆN AN NINH</span>
							</div>
							<span
								className={`soc-header-count ${pendingAlertCount > 0 ? "has-incidents" : ""}`}
							>
								{pendingAlertCount}
							</span>
						</div>

						{/* ┌──────────────────────────────┐
						    │ [Tất cả 0] [Chưa xử lý 0]    │
						    └──────────────────────────────┘ */}
						<div className="soc-filter-tabs">
							<button
								type="button"
								className={`soc-tab-btn ${alertFilter === "ALL" ? "active" : ""}`}
								onClick={() => setAlertFilter("ALL")}
							>
								Tất cả {activeAlerts.length}
							</button>
							<button
								type="button"
								className={`soc-tab-btn ${alertFilter === "PENDING" ? "active" : ""}`}
								onClick={() => setAlertFilter("PENDING")}
							>
								Chưa xử lý {pendingAlertCount}
							</button>

							{activeAlerts.some((a) => a.status === "RESOLVED") && (
								<button
									type="button"
									className="soc-btn-clear"
									onClick={handleClearResolved}
									title="Dọn dẹp sự cố đã giải quyết"
								>
									<RotateCcw size={12} />
								</button>
							)}
						</div>

						{/* Khu Vực Hiển Thị Sự Kiện Hoặc Trạng Thái An Toàn */}
						<div className="soc-alert-feed-body">
							{filteredAlerts.length === 0 ? (
								/* ┌──────────────────────────────┐
                   │        🟢                    │
                   │    HỆ THỐNG AN TOÀN          │
                   │    Không có sự kiện          │
                   └──────────────────────────────┘ */
								<div className="soc-safe-system-state">
									<div className="soc-green-pulse-circle" />
									<h4 className="soc-safe-title">HỆ THỐNG AN TOÀN</h4>
									<p className="soc-safe-desc">Không có sự cố</p>
								</div>
							) : (
								<div className="soc-incident-list">
									{filteredAlerts.map((alert) => {
										const sev = getSeverityMeta(alert.eventType);
										const isPending = alert.status === "PENDING";

										return (
											<div
												key={alert.id}
												className={`soc-incident-card ${isPending ? "pending" : "resolved"} ${sev.badgeClass}`}
											>
												<div className="soc-incident-top">
													<div className="soc-incident-tag">
														<AlertTriangle size={12} />
														<span>{sev.label}</span>
													</div>
													<span className="soc-incident-time">
														<Clock size={11} /> {alert.timestamp}
													</span>
												</div>

												<p className="soc-incident-msg">{alert.message}</p>

												<div className="soc-incident-bottom">
													{/* Nút xem nhanh camera */}
													<button
														type="button"
														className="btn-soc-jump-cam"
														onClick={() =>
															handleSelectCamera(
																alert.camCodeClean || alert.cameraCode,
															)
														}
														title="Chuyển màn hình sang camera này"
													>
														<Video size={12} />
														<span>{alert.cameraCode}</span>
														<ArrowRight size={11} />
													</button>

													{/* Nút xác nhận đã xử lý */}
													{isPending ? (
														<button
															type="button"
															className="btn-soc-ack"
															onClick={() => handleAcknowledge(alert.id)}
															title="Xác nhận đã xử lý sự cố"
														>
															<CheckCircle2 size={13} />
															<span>Đã xử lý</span>
														</button>
													) : (
														<span className="soc-resolved-label">
															<CheckCircle2 size={12} /> Đã xử lý
														</span>
													)}
												</div>
											</div>
										);
									})}
								</div>
							)}
						</div>

						{/* ──────────────────────────── (Đường phân cách) */}
						<div className="soc-panel-divider" />

						{/* ┌──────────────────────────────┐
						    │ Camera online          3/4   │
						    │ Đang ghi hình           3    │
						    └──────────────────────────────┘ */}
						<div className="soc-system-status-footer">
							<div className="soc-stat-row">
								<span className="soc-stat-label">Camera online</span>
								<span className="soc-stat-value">
									{onlineCameraCount}/{totalCameraCount}
								</span>
							</div>
							<div className="soc-stat-row">
								<span className="soc-stat-label">Đang ghi hình</span>
								<span className="soc-stat-value">{recordingCameraCount}</span>
							</div>
						</div>
					</div>
				)}
			</div>
		</div>
	);
}

export default SecuritySurveillancePage;
