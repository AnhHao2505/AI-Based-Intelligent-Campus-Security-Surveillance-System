import React, { useState, useEffect, useMemo, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { Client } from "@stomp/stompjs";
import {
	ShieldAlert,
	Video,
	Bell,
	CheckCircle2,
	Volume2,
	VolumeX,
	Clock,
	Loader2,
	Flame,
	ArrowRight,
	RotateCcw,
	Maximize2,
	Minimize2,
	AlertTriangle,
	BellOff,
	Eye,
	CheckCheck,
	Camera as CameraIcon,
	X,
	Film,
} from "lucide-react";
import { useAuthorizedImage } from "../../hooks/useAuthorizedImage";
import WebRtcPlayer from "../../components/video/WebRtcPlayer";
import { fetchCameras } from "../../services/cameraService";
import {
	triggerTestAlert,
	triggerBatchTestAlerts,
	getActiveIncidents,
	acknowledgeIncident,
	acknowledgeAllIncidents,
} from "../../services/incidentService";
import "../../styles/GuardDashboardPage.css";
import { LoadingState, ErrorState } from "../../components/ui";

// Chuẩn hóa 5 nhóm sự kiện an ninh theo độ ưu tiên (Priority):
// 1. UNAUTHORIZED_ACCESS: Không đủ thẩm quyền (Trọng số 3 - Nguy cấp, #ef4444)
// 2. INTRUSION: Sau giờ hoạt động (Trọng số 2 - Cao, #f97316)
// 3. ACCESS_OUT_OF_TIME: Quá thời hạn thẩm quyền (Trọng số 2 - Cao, #eab308)
// 4. UNKNOWN_PERSON: Đối tượng không xác định (Trọng số 1 - Cảnh báo, #8b5cf6)
// 5. AUTHORIZED_ACCESS: Hợp lệ (Trọng số 0, #10b981)
const EVENT_SEVERITY_CONFIG = {
	AUTHORIZED_ACCESS: {
		weight: 0,
		label: "Hợp lệ",
		badgeClass: "severity-success",
		color: "#10b981",
	},
	UNAUTHORIZED_ACCESS: {
		weight: 3,
		label: "Không đủ thẩm quyền",
		badgeClass: "severity-critical",
		color: "#ef4444",
	},
	UNAUTHORIZED: {
		weight: 3,
		label: "Không đủ thẩm quyền",
		badgeClass: "severity-critical",
		color: "#ef4444",
	},
	INTRUSION: {
		weight: 2,
		label: "Sau giờ hoạt động",
		badgeClass: "severity-high",
		color: "#f97316",
	},
	AFTER_HOURS_PRESENCE: {
		weight: 2,
		label: "Sau giờ hoạt động",
		badgeClass: "severity-high",
		color: "#f97316",
	},
	AFTER_HOURS_ACCESS: {
		weight: 2,
		label: "Sau giờ hoạt động",
		badgeClass: "severity-high",
		color: "#f97316",
	},
	AFTER_HOURS: {
		weight: 2,
		label: "Sau giờ hoạt động",
		badgeClass: "severity-high",
		color: "#f97316",
	},
	ACCESS_OUT_OF_TIME: {
		weight: 2,
		label: "Quá thời hạn thẩm quyền",
		badgeClass: "severity-high",
		color: "#eab308",
	},
	UNKNOWN_PERSON: {
		weight: 1,
		label: "Đối tượng không xác định",
		badgeClass: "severity-warning",
		color: "#8b5cf6",
	},
	UNKNOWN: {
		weight: 1,
		label: "Đối tượng không xác định",
		badgeClass: "severity-warning",
		color: "#8b5cf6",
	},
};

const cleanIncidentMessage = (msg) => {
	if (!msg) return "Phát hiện sự cố an ninh tại khu vực giám sát";
	return String(msg)
		.replace(/\[[A-Z0-9_]+\]\s*/g, "")
		.trim();
};

const isAlertPending = (status) => {
	if (!status) return true;
	const s = String(status).toUpperCase();
	return !s.startsWith("RESOLVED") && s !== "DISMISSED" && s !== "IGNORED";
};

function getSeverityMeta(eventType) {
	return (
		EVENT_SEVERITY_CONFIG[eventType] || {
			weight: 1,
			label: eventType || "Cảnh báo an ninh",
			badgeClass: "severity-warning",
			color: "#8b5cf6",
		}
	);
}

const SOC_ACK_STORAGE_KEY = "soc_acknowledged_incident_ids";
const SOC_CLEARED_STORAGE_KEY = "soc_cleared_incident_ids";

const getStoredSet = (key) => {
	try {
		const raw = localStorage.getItem(key);
		if (!raw) return new Set();
		const arr = JSON.parse(raw);
		return Array.isArray(arr) ? new Set(arr.map(String)) : new Set();
	} catch (e) {
		console.warn(`Lỗi đọc ${key} từ localStorage:`, e);
		return new Set();
	}
};

const saveStoredSet = (key, set) => {
	try {
		const arr = Array.from(set).slice(-1000);
		localStorage.setItem(key, JSON.stringify(arr));
	} catch (e) {
		console.warn(`Lỗi lưu ${key} vào localStorage:`, e);
	}
};

export function formatEvidenceImageUrl(rawUrl) {
	if (!rawUrl) return "";
	const url = String(rawUrl).trim();
	if (url.startsWith("/api/media/")) return url;
	const minioMatch = url.match(/^https?:\/\/[^/]+(?::\d+)?\/([^/]+)\/(.+)$/);
	if (minioMatch) {
		const bucket = minioMatch[1];
		const path = minioMatch[2];
		return `/api/media/${bucket}/${path}`;
	}
	if (url.startsWith("security-evidence/") || url.startsWith("face-profiles/")) {
		return `/api/media/${url}`;
	}
	return url;
}

function EvidenceImage({ src, alt, className }) {
	const formatted = formatEvidenceImageUrl(src);
	const { url, loading, error } = useAuthorizedImage(formatted);
	if (error) {
		return (
			<div className="soc-evidence-error">
				<AlertTriangle size={24} />
				<span>Không tải được ảnh bằng chứng sự cố</span>
			</div>
		);
	}
	if (loading || !url) {
		return (
			<div className="soc-evidence-loading">
				<Loader2 size={24} className="animate-spin" />
				<span>Đang tải ảnh bằng chứng...</span>
			</div>
		);
	}
	return <img src={url} alt={alt || "Ảnh bằng chứng vi phạm"} className={className} />;
}

/**
 * Trung tâm Giám sát An ninh (SOC) — Security Surveillance Center
 * Tên trước: GuardDashboardPage
 */
export function SecuritySurveillancePage() {
	const navigate = useNavigate();
	const [selectedCamera, setSelectedCamera] = useState("");
	const [cameraLayout, setCameraLayout] = useState(1); // 1 | 4 (2x2) | 9 (3x3) | 16 (4x4)
	const [isTheaterMode, setIsTheaterMode] = useState(false); // Chế độ phóng to chỉ view màn hình
	const [soundEnabled, setSoundEnabled] = useState(true);
	const [wsConnected, setWsConnected] = useState(false);
	const [activeAlerts, setActiveAlerts] = useState([]);
	// Lỗi / đang tải danh sách sự cố ban đầu: không được hiện "HỆ THỐNG AN TOÀN" khi chưa tải được
	const [incidentsLoading, setIncidentsLoading] = useState(true);
	const [incidentsError, setIncidentsError] = useState(null);
	const [incidentsReloadKey, setIncidentsReloadKey] = useState(0);
	const [cameraList, setCameraList] = useState([]);
	const [camerasLoading, setCamerasLoading] = useState(true);
	const [alertFilter, setAlertFilter] = useState("ALL"); // 'ALL' | 'PENDING'
	const [flashAlertCamera, setFlashAlertCamera] = useState(null);
	const [statusNotification, setStatusNotification] = useState(null);
	const [evidenceModalAlert, setEvidenceModalAlert] = useState(null);
	const [isSimulating, setIsSimulating] = useState(false);

	const flashTimeoutRef = useRef(null);
	const soundEnabledRef = useRef(soundEnabled);

	useEffect(() => {
		soundEnabledRef.current = soundEnabled;
	}, [soundEnabled]);

	// Phím tắt ESC để thoát chế độ phóng to hoặc đóng modal xem ảnh bằng chứng
	useEffect(() => {
		const handleKeyDown = (e) => {
			if (e.key === "Escape") {
				if (evidenceModalAlert) {
					setEvidenceModalAlert(null);
				} else if (isTheaterMode) {
					setIsTheaterMode(false);
				}
			}
		};
		window.addEventListener("keydown", handleKeyDown);
		return () => window.removeEventListener("keydown", handleKeyDown);
	}, [isTheaterMode, evidenceModalAlert]);

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
					const firstOnline = list.find((c) => c.status === "ONLINE") || list[0];
					setSelectedCamera((prev) =>
						prev && list.some((c) => c.code === prev) ? prev : firstOnline.code,
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
			if (!alert.socAcknowledged && isAlertPending(alert.status)) {
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
		const incidentId = incident.id || incident.event_id || Date.now();
		const eventId = incident.eventId || incident.event_id;
		const ackSet = getStoredSet(SOC_ACK_STORAGE_KEY);
		const isAck =
			ackSet.has(String(incidentId)) ||
			(eventId && ackSet.has(String(eventId)));
		const camCodeClean = (
			incident.camera_code ||
			incident.cameraCode ||
			"cam-006"
		)
			.toLowerCase()
			.replace(/[^a-z0-9_-]/g, "")
			.trim();

		const newAlert = {
			id: incidentId,
			eventId: eventId,
			cameraCode: (
				incident.camera_code ||
				incident.cameraCode ||
				"CAM-006"
			).toUpperCase(),
			camCodeClean,
			cameraName:
				incident.areaName ||
				incident.area_name ||
				incident.cameraName ||
				"Khu vực camera",
			eventType:
				incident.event_type || incident.eventType || "UNAUTHORIZED_ACCESS",
			message: cleanIncidentMessage(
				incident.details ||
				incident.resolutionNotes ||
				incident.message
			),
			duration: incident.duration_seconds
				? `${incident.duration_seconds}s`
				: incident.duration || "Vừa phát hiện",
			timestamp: new Date().toLocaleTimeString("vi-VN"),
			rawTime: Date.now(),
			status: incident.status || "NEW",
			socAcknowledged: isAck,
			claimedByName: incident.claimedByName || incident.claimed_by_name,
			imageUrl: incident.imageUrl || incident.image_url || null,
		};

		setActiveAlerts((prev) => {
			const existingIndex = prev.findIndex((a) => {
				// 1. Trùng ID sự cố (UUID hoặc ID)
				if (incident.id && (a.id === incident.id || String(a.id) === String(incident.id))) {
					return true;
				}
				// 2. Trùng Event ID
				if (eventId && (a.eventId === eventId || a.id === eventId || String(a.id) === String(eventId))) {
					return true;
				}
				// 3. Khử trùng lặp an toàn: cùng camera + cùng loại sự cố trong vòng 4 giây
				const isSameCamera =
					(a.cameraCode && newAlert.cameraCode && a.cameraCode === newAlert.cameraCode) ||
					(a.camCodeClean && newAlert.camCodeClean && a.camCodeClean === newAlert.camCodeClean);
				const isSameType = a.eventType === newAlert.eventType;
				const isRecent = Math.abs((newAlert.rawTime || Date.now()) - (a.rawTime || 0)) < 4000;
				if (isSameCamera && isSameType && isRecent) {
					return true;
				}
				return false;
			});

			if (existingIndex >= 0) {
				const updated = [...prev];
				updated[existingIndex] = {
					...updated[existingIndex],
					...newAlert,
					id: incident.id || updated[existingIndex].id,
					eventId: eventId || updated[existingIndex].eventId,
				};
				return updated;
			}
			return [newAlert, ...prev];
		});
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
			connectHeaders: { Authorization: `Bearer ${localStorage.getItem('accessToken')}` },
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
										? {
											...a,
											status: update.status || a.status,
											claimedByName: update.claimedByName || a.claimedByName,
											claimedById: update.claimedById || a.claimedById,
										}
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

	// Nạp danh sách sự cố chưa xử lý ban đầu từ Backend REST API khi tải trang
	useEffect(() => {
		async function loadInitialActiveIncidents() {
			setIncidentsLoading(true);
			try {
				const active = await getActiveIncidents();
				setIncidentsError(null);
				if (Array.isArray(active) && active.length > 0) {
					const ackSet = getStoredSet(SOC_ACK_STORAGE_KEY);
					const clearedSet = getStoredSet(SOC_CLEARED_STORAGE_KEY);

					const mapped = active
						.filter((incident) => {
							const idStr = String(incident.id || incident.eventId);
							return !clearedSet.has(idStr);
						})
						.map((incident) => {
							const incId = incident.id || incident.eventId;
							const camCodeClean = (
								incident.camera_code ||
								incident.cameraCode ||
								"CAM-001"
							)
								.toLowerCase()
								.replace(/[^a-z0-9_-]/g, "")
								.trim();

							return {
								id: incId,
								cameraCode: (
									incident.camera_code ||
									incident.cameraCode ||
									"CAM-001"
								).toUpperCase(),
								camCodeClean,
								cameraName:
									incident.areaName || incident.cameraName || "Khu vực camera",
								eventType: incident.eventType || "UNAUTHORIZED_ACCESS",
								message: cleanIncidentMessage(
									incident.details ||
									incident.resolutionNotes ||
									incident.message
								),
								duration: incident.duration_seconds
									? `${incident.duration_seconds}s`
									: "Vừa phát hiện",
								timestamp: incident.detectedAt
									? new Date(incident.detectedAt).toLocaleTimeString("vi-VN")
									: new Date().toLocaleTimeString("vi-VN"),
								rawTime: incident.detectedAt
									? new Date(incident.detectedAt).getTime()
									: Date.now(),
								status: incident.status || "NEW",
								socAcknowledged: ackSet.has(String(incId)),
								claimedByName: incident.claimedByName || incident.claimed_by_name,
								imageUrl: incident.imageUrl || incident.image_url || null,
							};
						});
					setActiveAlerts(mapped);
				}
			} catch (e) {
				console.warn("Không thể tải danh sách sự cố ban đầu:", e);
				setIncidentsError(e?.message || "Không tải được danh sách sự cố.");
			} finally {
				setIncidentsLoading(false);
			}
		}
		loadInitialActiveIncidents();
	}, [incidentsReloadKey]);

	// Nghiệp vụ SOC: Xác nhận đã xem & tắt còi báo động tại phòng trực điều hành
	// Đồng thời kết thúc ghi hình sự cố an ninh và hoàn tất lưu video vào evidence/
	const handleAcknowledge = async (id) => {
		const strId = String(id);
		const ackSet = getStoredSet(SOC_ACK_STORAGE_KEY);
		ackSet.add(strId);

		const targetAlert = activeAlerts.find(
			(a) => String(a.id) === strId || (a.eventId && String(a.eventId) === strId),
		);
		if (targetAlert?.eventId) {
			ackSet.add(String(targetAlert.eventId));
		}
		if (targetAlert?.id) {
			ackSet.add(String(targetAlert.id));
		}
		saveStoredSet(SOC_ACK_STORAGE_KEY, ackSet);

		const camCode = targetAlert?.cameraCode || targetAlert?.camCodeClean || targetAlert?.camera_code || "";

		setActiveAlerts((prev) =>
			prev.map((a) =>
				String(a.id) === strId || (targetAlert?.eventId && String(a.eventId) === String(targetAlert.eventId))
					? { ...a, socAcknowledged: true }
					: a,
			),
		);
		setFlashAlertCamera(null);

		try {
			await acknowledgeIncident(strId, camCode);
			triggerNotification("Đã xác nhận xem sự cố. Video bằng chứng đã được lưu vào thư mục evidence!");
		} catch (err) {
			console.warn("Lỗi khi kết thúc ghi hình sự cố:", err);
			triggerNotification("Đã xác nhận xem sự cố");
		}
	};

	// Nghiệp vụ SOC: Xác nhận đã xem toàn bộ sự cố đang chờ xử lý
	// Đồng thời kết thúc toàn bộ video ghi hình sự cố an ninh
	const handleAcknowledgeAll = async () => {
		const ackSet = getStoredSet(SOC_ACK_STORAGE_KEY);
		activeAlerts.forEach((a) => {
			if (isAlertPending(a.status)) {
				ackSet.add(String(a.id));
			}
		});
		saveStoredSet(SOC_ACK_STORAGE_KEY, ackSet);

		setActiveAlerts((prev) =>
			prev.map((a) =>
				isAlertPending(a.status) ? { ...a, socAcknowledged: true } : a,
			),
		);
		setFlashAlertCamera(null);

		try {
			await acknowledgeAllIncidents();
			triggerNotification("Đã xác nhận xem tất cả sự cố. Toàn bộ video bằng chứng đã được lưu vào thư mục evidence!");
		} catch (err) {
			console.warn("Lỗi khi kết thúc toàn bộ ghi hình sự cố:", err);
			triggerNotification("Đã xác nhận xem tất cả sự cố đang chờ");
		}
	};

	// Xóa các sự cố đã tắt còi (SOC đã xem) hoặc đã được hiện trường đóng khỏi bảng trực
	const handleClearResolved = () => {
		const clearedSet = getStoredSet(SOC_CLEARED_STORAGE_KEY);
		activeAlerts.forEach((a) => {
			if (a.socAcknowledged || !isAlertPending(a.status)) {
				clearedSet.add(String(a.id));
			}
		});
		saveStoredSet(SOC_CLEARED_STORAGE_KEY, clearedSet);

		setActiveAlerts((prev) =>
			prev.filter((a) => !a.socAcknowledged && isAlertPending(a.status)),
		);
		triggerNotification("Đã dọn dẹp các sự cố đã xem/đã đóng");
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

	// 1. NÚT TEST: Bắn 1 sự cố ngẫu nhiên qua Backend REST -> STOMP broadcast toàn hệ thống (Web + Mobile)
	// Chỉ ghi hình khi camera đang ONLINE và đang kết nối stream thực tế
	const handleSimulateSingleAlert = async () => {
		if (isSimulating) return;
		setIsSimulating(true);

		const onlineCameras = cameraList.filter(
			(c) => c.status === "ONLINE" || c.operationalStatus === "ONLINE",
		);
		const targetPool =
			onlineCameras.length > 0
				? onlineCameras
				: cameraList.filter((c) => c.status !== "DECOMMISSIONED");

		if (targetPool.length === 0) {
			triggerNotification("⚠️ Không có camera nào sẵn sàng trong hệ thống!");
			setIsSimulating(false);
			return;
		}

		// Ưu tiên camera đang chọn nếu có trong danh sách
		const targetCam =
			targetPool.find((c) => c.code === selectedCamera) || targetPool[0];

		const now = Date.now();
		const eventId = `EVT-${now}`;
		const cameraCode = (targetCam.cameraCode || targetCam.code).toUpperCase();
		const cleanCode = targetCam.code || cameraCode.toLowerCase();

		const eventPayload = {
			event_id: eventId,
			camera_code: cameraCode,
			event_type: "UNAUTHORIZED_ACCESS",
			details: `CẢNH BÁO (Mức 3 - Khẩn cấp): Phát hiện xâm nhập trái phép tại khu vực ${targetCam.name || cameraCode}!`,
			duration_seconds: 3.5,
		};

		// 1. Hiển thị cảnh báo trực tiếp trên giao diện và phát âm thanh ngay lập tức (Zero-Latency)
		processNewIncident({
			...eventPayload,
			id: eventId,
			eventId,
			cameraCode,
			camCodeClean: cleanCode,
			cameraName: targetCam.name || cameraCode,
		});
		triggerNotification(`🚨 Đã kích hoạt sự cố thử nghiệm cho camera [${cameraCode}]!`);

		// 2. Đồng bộ sang Backend CSDL (Backend lưu incident UUID, tự kích hoạt ghi hình AI và broadcast STOMP)
		try {
			const res = await triggerTestAlert(eventPayload);
			// Nếu WebSocket STOMP chưa kết nối thì mới nạp thủ công từ HTTP response để tránh x2 cảnh báo
			if (!wsConnected) {
				const created = res?.data || res;
				if (created && created.id) {
					processNewIncident(created);
				}
			}
		} catch (err) {
			console.warn("Backend test-alert background sync warning:", err);
		} finally {
			setTimeout(() => setIsSimulating(false), 1000);
		}
	};

	// 2. NÚT TEST: Bắn đa sự cố đồng thời với 3 mức độ ưu tiên chuẩn hóa qua Backend REST
	// Chỉ kích hoạt trên các camera ĐANG ONLINE (đang có luồng ghi hình thực tế)
	const handleSimulateMultiPriorityAlerts = async () => {
		if (isSimulating) return;
		setIsSimulating(true);

		const onlineCameras = cameraList.filter(
			(c) => c.status === "ONLINE" || c.operationalStatus === "ONLINE",
		);
		const targetPool =
			onlineCameras.length > 0
				? onlineCameras
				: cameraList.filter((c) => c.status !== "DECOMMISSIONED");

		if (targetPool.length === 0) {
			triggerNotification("⚠️ Không có camera nào sẵn sàng trong hệ thống!");
			setIsSimulating(false);
			return;
		}

		// 3 mức ưu tiên an ninh chuẩn hóa theo EVENT_SEVERITY_CONFIG:
		// Mức 3 (Khẩn cấp): UNAUTHORIZED_ACCESS (Trọng số 3, Đỏ)
		// Mức 2 (Cao): AFTER_HOURS_ACCESS (Trọng số 2, Cam)
		// Mức 1 (Cảnh báo): UNKNOWN_PERSON (Trọng số 1, Tím)
		const priorityPresets = [
			{
				type: "UNAUTHORIZED_ACCESS",
				title: "NGUY CẤP (Mức 3): Đối tượng không đủ thẩm quyền xâm nhập khu vực giám sát!",
				durationSec: 3.0,
			},
			{
				type: "AFTER_HOURS_ACCESS",
				title: "CẢNH BÁO (Mức 2): Có người hiện diện sau khung giờ hoạt động quy định.",
				durationSec: 8.0,
			},
			{
				type: "UNKNOWN_PERSON",
				title: "NHẮC NHỞ (Mức 1): Phát hiện đối tượng chưa xác minh danh tính.",
				durationSec: 15.0,
			},
		];

		const now = Date.now();
		const batchPayload = priorityPresets.map((preset, index) => {
			const assignedCam = targetPool[index % targetPool.length];
			const camCode = (assignedCam.cameraCode || assignedCam.code).toUpperCase();
			return {
				event_id: `EVT-${now}-${index}`,
				camera_code: camCode,
				event_type: preset.type,
				details: `[${camCode}] ${preset.title}`,
				duration_seconds: preset.durationSec,
			};
		});

		// 1. Hiển thị ngay 3 sự cố trên danh sách cảnh báo SOC
		batchPayload.forEach((ev) => {
			processNewIncident({
				...ev,
				id: ev.event_id,
				eventId: ev.event_id,
				cameraCode: ev.camera_code,
				camCodeClean: ev.camera_code.toLowerCase(),
				cameraName: ev.camera_code,
			});
		});
		triggerNotification(`🚨 Đã kích hoạt đồng thời 3 sự cố an ninh theo thứ tự ưu tiên!`);

		// 2. Gửi batch sang backend (Backend sẽ lưu 3 incidents, kích hoạt ghi hình sự cố AI và broadcast STOMP)
		try {
			const res = await triggerBatchTestAlerts(batchPayload);
			// Nếu WebSocket STOMP chưa kết nối thì mới nạp thủ công từ HTTP response
			if (!wsConnected) {
				const createdList = res?.data || res;
				if (Array.isArray(createdList) && createdList.length > 0) {
					createdList.forEach((item) => processNewIncident(item));
				}
			}
		} catch (err) {
			console.warn("Backend batch test-alert background sync warning:", err);
		} finally {
			setTimeout(() => setIsSimulating(false), 1500);
		}
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
		(a) => !a.socAcknowledged && isAlertPending(a.status),
	).length;
	const filteredAlerts = useMemo(() => {
		const base =
			alertFilter === "PENDING"
				? activeAlerts.filter(
					(a) => !a.socAcknowledged && isAlertPending(a.status),
				)
				: [...activeAlerts];

		return base.sort((a, b) => {
			const weightA = getSeverityMeta(a.eventType).weight;
			const weightB = getSeverityMeta(b.eventType).weight;
			if (weightB !== weightA) {
				return weightB - weightA;
			}
			return (b.rawTime || 0) - (a.rawTime || 0);
		});
	}, [activeAlerts, alertFilter]);

	return (
		<div
			className={`guard-dashboard-container ${isTheaterMode ? "soc-theater-mode" : ""}`}
		>
			{/* Toast Notification */}
			{statusNotification && (
				<div className="soc-floating-toast">
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

						{/* Kho video ghi hình & bằng chứng */}
						<button
							type="button"
							className="btn-theater-mode"
							onClick={() => navigate("/recordings")}
							title="Mở Kho lưu trữ video ghi hình và bằng chứng an ninh"
						>
							<Film size={15} />
							<span>Kho video ghi hình</span>
						</button>

						{/* Bật/Tắt âm thanh */}
						<button
							type="button"
							className={`btn-sound-toggle ${soundEnabled ? "active" : "muted"}`}
							onClick={() => {
								const next = !soundEnabled;
								setSoundEnabled(next);
							}}
							title={soundEnabled ? "Tắt âm thanh" : "Bật âm thanh"}
						>
							{soundEnabled ? <Volume2 size={15} /> : <VolumeX size={15} />}
							<span>{soundEnabled ? "Âm thanh" : "Tắt âm thanh"}</span>
						</button>

						{/* Nhóm nút TEST mô phỏng */}
						<div className="test-btn-group">
							<button
								type="button"
								className="btn-sim-alert"
								onClick={handleSimulateSingleAlert}
								disabled={isSimulating}
								title="Mô phỏng 1 sự cố ngẫu nhiên"
							>
								<Bell size={14} />
								<span>{isSimulating ? "Đang xử lý..." : "Test 1 sự cố"}</span>
							</button>

							<button
								type="button"
								className="btn-sim-multi-alert"
								onClick={handleSimulateMultiPriorityAlerts}
								disabled={isSimulating}
								title="Mô phỏng đồng thời nhiều sự cố với độ ưu tiên khác nhau"
							>
								<Flame size={14} />
								<span>{isSimulating ? "Đang xử lý..." : "Test đa sự cố (Ưu tiên)"}</span>
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
									title={soundEnabled ? "Tắt âm thanh" : "Bật âm thanh"}
								>
									{soundEnabled ? <Volume2 size={12} /> : <VolumeX size={12} />}
									<span>{soundEnabled ? "Âm thanh" : "Tắt âm thanh"}</span>
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
											enabled={camera.status === "ONLINE"}
											host="localhost:8889"
											onStatusChange={(playerStatus) => {
												if (playerStatus === "live") {
													setCameraList((prev) =>
														prev.map((c) =>
															c.code === camera.code
																? { ...c, status: "ONLINE" }
																: c,
														),
													);
												}
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
								Cần xử lý {pendingAlertCount}
							</button>

							{pendingAlertCount > 1 && (
								<button
									type="button"
									className="soc-btn-ack-all"
									onClick={handleAcknowledgeAll}
									title="Xác nhận đã xem tất cả sự cố chưa xử lý"
								>
									<CheckCheck size={13} />
									<span>Xem hết</span>
								</button>
							)}

							{activeAlerts.some(
								(a) => a.socAcknowledged || !isAlertPending(a.status),
							) && (
									<button
										type="button"
										className="soc-btn-clear"
										onClick={handleClearResolved}
										title="Dọn dẹp sự cố đã xem hoặc đã giải quyết"
									>
										<RotateCcw size={12} />
									</button>
								)}
						</div>

						{/* Khu Vực Hiển Thị Sự Kiện Hoặc Trạng Thái An Toàn */}
						<div className="soc-alert-feed-body">
							{incidentsError && (
								<ErrorState
									size="sm"
									title="Không tải được danh sách sự cố"
									message={incidentsError}
									onRetry={() => setIncidentsReloadKey((k) => k + 1)}
									retrying={incidentsLoading}
								/>
							)}
							{filteredAlerts.length === 0 && (incidentsError || incidentsLoading) ? (
								incidentsError ? null : <LoadingState size="sm" text="Đang tải danh sách sự cố..." />
							) : filteredAlerts.length === 0 ? (
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
										const status = String(alert.status || "NEW").toUpperCase();
										const isPending = isAlertPending(status);

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
													{!alert.socAcknowledged && (
														<span
															className="soc-rec-badge"
															title="Đang ghi hình sự cố an ninh vào thư mục evidence/ cho tới khi bấm Xác nhận đã xem"
														>
															<span className="rec-dot"></span> REC
														</span>
													)}
													<span className="soc-incident-time">
														<Clock size={11} /> {alert.timestamp}
													</span>
												</div>

												<p className="soc-incident-msg">{alert.message}</p>

												<div className="soc-incident-bottom">
													<div className="soc-incident-bottom-left">
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

														{/* Nút xem ảnh bằng chứng quả tang từ AI */}
														{alert.imageUrl && (
															<button
																type="button"
																className="btn-soc-evidence"
																onClick={() => setEvidenceModalAlert(alert)}
																title="Xem ảnh chụp bằng chứng quả tang từ AI"
															>
																<CameraIcon size={12} />
																<span>Bằng chứng</span>
															</button>
														)}
													</div>

													<div
														className="soc-incident-workflow"
														aria-label="Trạng thái và thao tác phòng trực SOC"
													>
														<span className="soc-incident-workflow__heading">
															Phòng trực
														</span>
														{/* Trạng thái & Thao tác nghiệp vụ phòng trực SOC */}
														{!isPending ? (
															<span
																className="soc-resolved-label"
																title="Sự cố đã được bảo vệ giải quyết tại hiện trường"
															>
																<CheckCircle2 size={12} /> Hiện trường đã đóng
															</span>
														) : status === "CLAIMED" ? (
															<div className="soc-action-group">
																<span
																	className="soc-claimed-label"
																	title={`Bảo vệ ${alert.claimedByName || ""} đang đến hiện trường`}
																>
																	<ShieldAlert size={12} />{" "}
																	{alert.claimedByName
																		? `${alert.claimedByName} đang đến`
																		: "Đang xử lý"}
																</span>
																{!alert.socAcknowledged && (
																	<button
																		type="button"
																		className="btn-soc-ack"
																		onClick={() => handleAcknowledge(alert.id)}
																		title="Xác nhận đã theo dõi qua Camera"
																	>
																		<Eye size={11} />
																		<span>Đã xem</span>
																	</button>
																)}
															</div>
														) : alert.socAcknowledged ? (
															<span
																className="soc-ack-badge"
																title="Phòng SOC đã ghi nhận và đang quan sát qua camera"
															>
																<Eye size={12} /> Đã xem
															</span>
														) : (
															<button
																type="button"
																className="btn-soc-ack"
																onClick={() => handleAcknowledge(alert.id)}
																title="Xác nhận đã xem"
															>
																<BellOff size={12} />
																<span>Xác nhận đã xem</span>
															</button>
														)}
													</div>
												</div>
											</div>
										);
									})}
								</div>
							)}
						</div>
					</div>
				)}
			</div>

			{/* Modal hiển thị ảnh bằng chứng quả tang từ AI */}
			{evidenceModalAlert && (
				<div
					className="soc-evidence-modal-backdrop"
					onClick={() => setEvidenceModalAlert(null)}
				>
					<div
						className="soc-evidence-modal-content"
						onClick={(e) => e.stopPropagation()}
					>
						<div className="soc-evidence-modal-header">
							<div className="soc-evidence-modal-title">
								<ShieldAlert
									size={18}
									style={{
										color: getSeverityMeta(evidenceModalAlert.eventType).color,
									}}
								/>
								<h4>ẢNH BẰNG CHỨNG VI PHẠM AN NINH</h4>
								<span
									className={`soc-incident-tag ${getSeverityMeta(evidenceModalAlert.eventType).badgeClass}`}
								>
									{getSeverityMeta(evidenceModalAlert.eventType).label}
								</span>
							</div>
							<button
								type="button"
								className="soc-evidence-close-btn"
								onClick={() => setEvidenceModalAlert(null)}
								title="Đóng (ESC)"
							>
								<X size={18} />
							</button>
						</div>

						<div className="soc-evidence-modal-body">
							<div className="soc-evidence-image-container">
								<EvidenceImage
									src={evidenceModalAlert.imageUrl}
									alt="Ảnh bằng chứng vi phạm an ninh"
									className="soc-evidence-full-img"
								/>
							</div>

							<div className="soc-evidence-meta-panel">
								<div className="soc-evidence-meta-row">
									<span className="soc-evidence-meta-label">Camera:</span>
									<span className="soc-evidence-meta-val">
										<strong>{evidenceModalAlert.cameraCode}</strong> — {evidenceModalAlert.cameraName}
									</span>
								</div>
								<div className="soc-evidence-meta-row">
									<span className="soc-evidence-meta-label">Thời điểm:</span>
									<span className="soc-evidence-meta-val">{evidenceModalAlert.timestamp}</span>
								</div>
								<div className="soc-evidence-meta-row">
									<span className="soc-evidence-meta-label">Nội dung:</span>
									<span className="soc-evidence-meta-val">{evidenceModalAlert.message}</span>
								</div>
							</div>
						</div>

						<div className="soc-evidence-modal-footer">
							<button
								type="button"
								className="btn-soc-jump-cam"
								onClick={() => {
									handleSelectCamera(
										evidenceModalAlert.camCodeClean ||
											evidenceModalAlert.cameraCode,
									);
									setEvidenceModalAlert(null);
								}}
								title="Chuyển màn hình sang xem trực tiếp camera này"
							>
								<Video size={13} />
								<span>Xem trực tiếp camera {evidenceModalAlert.cameraCode}</span>
							</button>
							<button
								type="button"
								className="btn-soc-evidence-close"
								onClick={() => setEvidenceModalAlert(null)}
							>
								Đóng
							</button>
						</div>
					</div>
				</div>
			)}
		</div>
	);
}

export default SecuritySurveillancePage;
