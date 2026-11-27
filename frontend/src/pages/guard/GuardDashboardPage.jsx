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
	Broom,
	RotateCcw,
	Maximize2,
	Minimize2,
	AlertTriangle,
	Eye,
	Check,
	CheckCheck,
	Camera as CameraIcon,
	X,
	Film,
	CirclePlay,
	ExternalLink,
	UserX,
	Box,
	Layers,
	Filter,
	Calendar,
	ChevronDown,
	ChevronLeft,
	ChevronRight,
	VideoOff,
	Building2,
	MapPin,
	Download,
} from "lucide-react";
import { useAuth } from "../../context/AuthContext";
import { useAuthorizedImage } from "../../hooks/useAuthorizedImage";
import WebRtcPlayer from "../../components/video/WebRtcPlayer";
import {
	SOC_ACK_STORAGE_KEY,
	SOC_CLEARED_STORAGE_KEY,
	clearSocStoredSets,
	readSocClearedBefore,
	readSocStoredSet,
	writeSocClearedBefore,
	writeSocStoredSet,
} from "../../utils/dashboardHelpers";
import {
	computeVisibleGridSlice,
	extractAreaNameOptions,
	extractBuildingOptions,
	extractFloorOptions,
	filterCamerasByHierarchy,
	formatCameraChipLabel,
	formatCameraLocationSubtitle,
	formatCameraSubLabel,
	formatCameraTileTitle,
	formatIncidentCameraLabel,
	formatShortFloorCode,
	resolveCameraCodeLabel,
	sortCamerasStableByLocation,
} from "../../utils/cameraGridHelpers";
import { fetchCameras } from "../../services/cameraService";
import {
	getActiveIncidents,
	acknowledgeIncident,
	acknowledgeAllIncidents,
} from "../../services/incidentService";
import "../../styles/GuardDashboardPage.css";
import { LoadingState, ErrorState } from "../../components/ui";
import { minimizeReason } from "../../utils/accessHistoryHelpers";
import {
	installAlertSoundUnlock,
	playIncidentAlert,
	unlockAlertSound,
} from "../../utils/alertSound";
import {
	filterAlertsByDateRange,
	formatDateFilterSummary,
} from "../../utils/alertFilters";

// Chuẩn hóa 5 nhóm/nhãn sự kiện an ninh theo độ ưu tiên (Priority). Nhãn đầy đủ = AccessResult của backend;
// badge trên màn hình dùng nhãn ngắn (trong ngoặc):
// 1. UNAUTHORIZED_PERSON: Không có quyền (ngắn: Không có quyền) - Trọng số 3, Nguy cấp, #ef4444
// 2. PERSON_INTRUSION: Đột nhập sau giờ hoạt động (ngắn: Đột nhập ngoài giờ) - Trọng số 3, Nguy cấp, #ef4444
// 3. SUSPICIOUS_MOVING_OBJECT: Vật thể chuyển động bất thường (ngắn: Vật thể bất thường) - Trọng số 2, Cao, #f97316
// 4. UNKNOWN_PERSON: Đối tượng không xác định (ngắn: Người lạ) - Trọng số 1, Cảnh báo, #8b5cf6
// 5. AUTHORIZED_PERSON: Hợp lệ - Trọng số 0, #10b981, chỉ dùng cho lịch sử ra vào, không tạo sự cố
// Mã cũ (UNAUTHORIZED_ACCESS, INTRUSION, AFTER_HOURS_*, ...) hiển thị theo nhãn của mã mới tương ứng.
const EVENT_SEVERITY_CONFIG = {
	// 5 Tag Chuẩn hóa
	AUTHORIZED_PERSON: {
		weight: 0,
		label: "Hợp lệ",
		badgeClass: "severity-success",
		color: "#10b981",
		icon: CheckCircle2,
	},
	UNAUTHORIZED_PERSON: {
		weight: 3,
		label: "Không có quyền",
		badgeClass: "severity-critical",
		color: "#ef4444",
		icon: ShieldAlert,
	},
	PERSON_INTRUSION: {
		weight: 3,
		label: "Đột nhập ngoài giờ",
		badgeClass: "severity-critical",
		color: "#ef4444",
		icon: Clock,
	},
	UNKNOWN_PERSON: {
		weight: 1,
		label: "Người lạ",
		badgeClass: "severity-warning",
		color: "#8b5cf6",
		icon: UserX,
	},
	SUSPICIOUS_MOVING_OBJECT: {
		weight: 2,
		label: "Vật thể bất thường",
		badgeClass: "severity-high",
		color: "#f97316",
		icon: Box,
	},

	// Tương thích ngược các mã sự cố cũ
	AUTHORIZED_ACCESS: {
		weight: 0,
		label: "Hợp lệ",
		badgeClass: "severity-success",
		color: "#10b981",
		icon: CheckCircle2,
	},
	UNAUTHORIZED_ACCESS: {
		weight: 3,
		label: "Không có quyền",
		badgeClass: "severity-critical",
		color: "#ef4444",
		icon: ShieldAlert,
	},
	UNAUTHORIZED: {
		weight: 3,
		label: "Không có quyền",
		badgeClass: "severity-critical",
		color: "#ef4444",
		icon: ShieldAlert,
	},
	INTRUSION: {
		weight: 2,
		label: "Đột nhập ngoài giờ",
		badgeClass: "severity-critical",
		color: "#ef4444",
		icon: Clock,
	},
	AFTER_HOURS_PRESENCE: {
		weight: 2,
		label: "Đột nhập ngoài giờ",
		badgeClass: "severity-high",
		color: "#ef4444",
		icon: Clock,
	},
	AFTER_HOURS_ACCESS: {
		weight: 2,
		label: "Đột nhập ngoài giờ",
		badgeClass: "severity-high",
		color: "#ef4444",
		icon: Clock,
	},
	AFTER_HOURS: {
		weight: 2,
		label: "Đột nhập ngoài giờ",
		badgeClass: "severity-high",
		color: "#ef4444",
		icon: Clock,
	},
	// Sai khung giờ tính như không có quyền (03/11)
	ACCESS_OUT_OF_TIME: {
		weight: 3,
		label: "Không có quyền",
		badgeClass: "severity-critical",
		color: "#ef4444",
		icon: ShieldAlert,
	},
	OUT_OF_TIME: {
		weight: 3,
		label: "Không có quyền",
		badgeClass: "severity-critical",
		color: "#ef4444",
		icon: ShieldAlert,
	},
	UNKNOWN: {
		weight: 1,
		label: "Người lạ",
		badgeClass: "severity-warning",
		color: "#8b5cf6",
		icon: UserX,
	},
	SUSPICIOUS_MOTION: {
		weight: 2,
		label: "Vật thể bất thường",
		badgeClass: "severity-high",
		color: "#f97316",
		icon: Box,
	},
	MOVING_OBJECT: {
		weight: 2,
		label: "Vật thể bất thường",
		badgeClass: "severity-high",
		color: "#f97316",
		icon: Box,
	},
};

// Cấu hình các nhóm sự cố nghiệp vụ chuẩn để hiển thị bộ lọc nhanh (filter chips)
const CANONICAL_INCIDENT_CATEGORIES = [
	{
		key: "ALL",
		label: "Tất cả",
		icon: Layers,
		chipClass: "cat-all",
	},
	{
		key: "Vật thể bất thường",
		label: "Vật thể bất thường",
		icon: Box,
		chipClass: "cat-object",
	},
	{
		key: "Người lạ",
		label: "Người lạ",
		icon: UserX,
		chipClass: "cat-stranger",
	},
	{
		key: "Không có quyền",
		label: "Không có quyền",
		icon: ShieldAlert,
		chipClass: "cat-unauthorized",
	},
	{
		key: "Đột nhập ngoài giờ",
		label: "Đột nhập ngoài giờ",
		icon: Clock,
		chipClass: "cat-intrusion",
	},
];

const parseIncidentTimestamp = (incident) => {
	if (!incident) return Date.now();
	if (typeof incident === "number") return incident;
	if (typeof incident === "string") {
		const parsed = new Date(incident).getTime();
		return Number.isFinite(parsed) && parsed > 0 ? parsed : Date.now();
	}
	const raw =
		incident.detectedAt ||
		incident.detected_at ||
		incident.createdAt ||
		incident.created_at ||
		incident.timestamp ||
		incident.time;
	if (!raw) return Date.now();
	const parsed = new Date(raw).getTime();
	return Number.isFinite(parsed) && parsed > 0 ? parsed : Date.now();
};

const getIncidentIsoString = (incident) => {
	if (!incident) return null;
	if (typeof incident === "string") return incident;
	return (
		incident.detectedAt ||
		incident.detected_at ||
		incident.createdAt ||
		incident.created_at ||
		incident.timestamp ||
		null
	);
};

const getIncidentTimeParts = (isoString) => {
	let d = isoString ? new Date(isoString) : new Date();
	if (Number.isNaN(d.getTime())) {
		d = new Date();
	}
	const time = d.toLocaleTimeString("vi-VN", {
		hour: "2-digit",
		minute: "2-digit",
		second: "2-digit",
		hour12: false,
	});
	const day = String(d.getDate()).padStart(2, "0");
	const month = String(d.getMonth() + 1).padStart(2, "0");
	const year = d.getFullYear();
	const today = new Date();
	const isToday =
		d.getDate() === today.getDate() &&
		d.getMonth() === today.getMonth() &&
		d.getFullYear() === today.getFullYear();

	return {
		time,
		date: `${day}/${month}/${year}`,
		shortDate: `${day}/${month}`,
		full: `${time} ${day}/${month}/${year}`,
		isToday,
	};
};

const formatIncidentTimestamp = (isoString) => {
	return getIncidentTimeParts(isoString).full;
};

const cleanIncidentMessage = (
	msg,
	eventType = "",
	areaName = "",
	cameraCode = "",
) => {
	const cleanLocStr = (str) => {
		if (!str) return str;
		return str.replace(/\s*\([^)]*(?:vùng|roi)[^)]*\)/gi, "").trim();
	};
	const isGenericOrRoiLocation = (loc) => {
		if (!loc) return true;
		const trimmed = String(loc).trim();
		if (!trimmed) return true;
		return /^(?:vùng\s+phát\s+hiện(?:\s+xâm\s+nhập)?(?:\s*\d+)?|vùng\s+giám\s+sát|roi(?:\s*\d+)?|khu\s+vực\s+giám\s+sát|khu\s+vực\s+camera\s+[a-z0-9_-]+)$/i.test(
			trimmed,
		);
	};
	const preferredAreaLoc = cleanLocStr(areaName || "");

	if (!msg) {
		const loc = cleanLocStr(
			preferredAreaLoc ||
				(cameraCode ? `Khu vực camera ${cameraCode}` : "Khu vực giám sát"),
		);
		return `Vị trí: ${loc}`;
	}
	let raw = String(msg)
		.replace(/^\[[A-Z0-9_]+\]\s*/g, "")
		.trim();

	// Nếu đã chứa cấu trúc phân dòng chuẩn (Tên: / Lý do: / Vị trí:), chuẩn hóa tối giản dòng Lý do và thay thế vị trí ROI/chung chung bằng tên khu vực thật
	if (raw.includes("Vị trí:") || raw.includes("Tên:")) {
		let formatted = raw.replace(
			/Lý do:\s*([^\n]+)/i,
			(_, r) => `Lý do: ${minimizeReason(r)}`,
		);
		formatted = formatted.replace(/Vị trí:\s*([^\n]+)/i, (_, loc) => {
			const cleaned = cleanLocStr(loc);
			if (isGenericOrRoiLocation(cleaned)) {
				const fallback =
					preferredAreaLoc ||
					(cameraCode ? `Khu vực camera ${cameraCode}` : "Khu vực giám sát");
				return `Vị trí: ${fallback}`;
			}
			return `Vị trí: ${cleaned}`;
		});
		return formatted;
	}

	const evUpper = String(eventType).toUpperCase();
	const locDefault = cleanLocStr(
		areaName
			? `${areaName}`
			: cameraCode
				? `Khu vực camera ${cameraCode}`
				: "Khu vực giám sát",
	);

	if (evUpper.includes("UNAUTHORIZED")) {
		const nameMatch = raw.match(
			/(?:truy cập|quy định):\s*([^()\n]+?\s*\([A-Za-z0-9_.-]+\))/i,
		);
		const reasonMatch = raw.match(/Lý do:\s*(.*)$/i);
		const areaMatch = raw.match(/tại\s+([^.\n]+)/i);

		const nameStr = nameMatch
			? nameMatch[1].trim()
			: "Đối tượng chưa cấp quyền";
		const reasonStr = minimizeReason(reasonMatch ? reasonMatch[1] : "");
		const locStr = cleanLocStr(areaMatch ? areaMatch[1].trim() : locDefault);

		return `Tên: ${nameStr}\nLý do: ${reasonStr}\nVị trí: ${locStr}`;
	}

	if (evUpper.includes("INTRUSION")) {
		const nameMatch = raw.match(
			/(?:quy định|giờ):\s*([^()\n]+?\s*\([A-Za-z0-9_.-]+\))/i,
		);
		const areaMatch = raw.match(/tại\s+([^.\n]+)/i);
		const locStr = cleanLocStr(areaMatch ? areaMatch[1].trim() : locDefault);
		if (nameMatch) {
			return `Tên: ${nameMatch[1].trim()}\nLý do: Ngoài giờ hoạt động\nVị trí: ${locStr}`;
		}
		return `Vị trí: ${locStr}`;
	}

	if (evUpper.includes("UNKNOWN")) {
		const areaMatch = raw.match(/tại\s+([^.\n]+)/i);
		return `Vị trí: ${cleanLocStr(areaMatch ? areaMatch[1].trim() : locDefault)}`;
	}

	if (evUpper.includes("SUSPICIOUS")) {
		const areaMatch = raw.match(/tại\s+([^.\n]+)/i);
		return `Vị trí: ${cleanLocStr(areaMatch ? areaMatch[1].trim() : locDefault)}`;
	}

	return `Vị trí: ${locDefault}`;
};

// Hiển thị nội dung chi tiết sự cố theo dạng danh sách key-value phân cấp trực quan
const renderIncidentDetails = (msg) => {
	if (!msg) return null;
	const lines = String(msg)
		.split("\n")
		.map((l) => l.trim())
		.filter(Boolean);
	const hasKeyValue = lines.some((l) =>
		/^(Tên|Lý do|Vị trí|Đối tượng|Khu vực):/i.test(l),
	);

	if (!hasKeyValue) {
		return <p className="soc-incident-msg-text">{msg}</p>;
	}

	return (
		<div className="soc-incident-details-list">
			{lines.map((line, idx) => {
				const colonIdx = line.indexOf(":");
				if (colonIdx > 0 && colonIdx < 15) {
					const label = line.slice(0, colonIdx).trim();
					const val = line.slice(colonIdx + 1).trim();
					const isReason = /^lý do/i.test(label);
					const isName = /^tên/i.test(label);
					return (
						<div
							key={idx}
							className="soc-incident-detail-row"
						>
							<span className="soc-detail-label">{label}:</span>
							<span
								className={`soc-detail-val ${isReason ? "is-reason" : ""} ${isName ? "is-name" : ""}`}
							>
								{val}
							</span>
						</div>
					);
				}
				return (
					<div
						key={idx}
						className="soc-incident-detail-row"
					>
						<span className="soc-detail-val">{line}</span>
					</div>
				);
			})}
		</div>
	);
};

const isAlertPending = (status) => {
	if (!status) return true;
	const s = String(status).toUpperCase();
	return !s.startsWith("RESOLVED") && s !== "DISMISSED" && s !== "IGNORED";
};

// V93 (X1): server là nguồn chân lý về mức sự cố. Có severity trong payload thì trọng số / màu theo mức đó, nhãn vẫn
// theo loại sự cố; sự cố cũ (severity null) dùng bảng EVENT_SEVERITY_CONFIG như trước.
const SERVER_SEVERITY_META = {
	CRITICAL: { weight: 3, badgeClass: "severity-critical", color: "#ef4444" },
	HIGH: { weight: 2, badgeClass: "severity-high", color: "#f97316" },
	MEDIUM: { weight: 1, badgeClass: "severity-warning", color: "#eab308" },
};

function getAlertSeverityMeta(alert) {
	const base = getSeverityMeta(alert?.eventType);
	const server = alert?.severity
		? SERVER_SEVERITY_META[String(alert.severity).toUpperCase()]
		: null;
	return server ? { ...base, ...server } : base;
}

function getSeverityMeta(eventType) {
	return (
		EVENT_SEVERITY_CONFIG[eventType] || {
			weight: 1,
			label: eventType || "Cảnh báo an ninh",
			badgeClass: "severity-warning",
			color: "#8b5cf6",
			icon: AlertTriangle,
		}
	);
}

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
	if (
		url.startsWith("security-evidence/") ||
		url.startsWith("face-profiles/")
	) {
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
				<Loader2
					size={24}
					className="animate-spin"
				/>
				<span>Đang tải ảnh bằng chứng...</span>
			</div>
		);
	}
	return (
		<img
			src={url}
			alt={alt || "Ảnh bằng chứng vi phạm"}
			className={className}
		/>
	);
}

/**
 * Trung tâm Giám sát An ninh (SOC) — Security Surveillance Center
 * Tên trước: GuardDashboardPage
 */
export function SecuritySurveillancePage() {
	const navigate = useNavigate();
	const { user } = useAuth();
	const guardUserId = user?.id ?? null;
	const [selectedCamera, setSelectedCamera] = useState("");
	const [cameraLayout, setCameraLayout] = useState(1); // 1 | 4 (2x2) | 9 (3x3) | 16 (4x4) | "auto"
	const [gridPage, setGridPage] = useState(0);
	const [buildingFilter, setBuildingFilter] = useState("ALL");
	const [floorFilter, setFloorFilter] = useState("ALL");
	const [areaFilter, setAreaFilter] = useState("ALL");
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
	const [categoryFilter, setCategoryFilter] = useState("ALL"); // 'ALL' | 'Vật thể bất thường' | 'Người lạ' | ...
	const [fromDate, setFromDate] = useState("");
	const [toDate, setToDate] = useState("");
	const [isCategoryDropdownOpen, setIsCategoryDropdownOpen] = useState(false);
	const categoryDropdownRef = useRef(null);
	const [hasClearedIncidents, setHasClearedIncidents] = useState(
		() =>
			readSocStoredSet(SOC_CLEARED_STORAGE_KEY, guardUserId).size > 0 ||
			readSocClearedBefore(guardUserId) !== null,
	);
	useEffect(() => {
		setHasClearedIncidents(
			readSocStoredSet(SOC_CLEARED_STORAGE_KEY, guardUserId).size > 0 ||
				readSocClearedBefore(guardUserId) !== null,
		);
	}, [guardUserId]);
	const [flashAlertCamera, setFlashAlertCamera] = useState(null);
	const [statusNotification, setStatusNotification] = useState(null);
	const [evidenceModalAlert, setEvidenceModalAlert] = useState(null);
	const [evidenceModalTab, setEvidenceModalTab] = useState("image"); // "image" | "video"
	const evidencePlayerRef = useRef(null);

	const cameraAreaByCode = useMemo(() => {
		const areaMap = {};
		for (const cam of cameraList) {
			if (!cam.code) continue;
			const area = (cam.areaName || "").trim();
			if (area) areaMap[cam.code] = area;
		}
		return areaMap;
	}, [cameraList]);
	const cameraAreaByCodeRef = useRef(cameraAreaByCode);
	useEffect(() => {
		cameraAreaByCodeRef.current = cameraAreaByCode;
	}, [cameraAreaByCode]);

	const flashTimeoutRef = useRef(null);
	const notificationTimeoutRef = useRef(null);
	const soundEnabledRef = useRef(soundEnabled);
	const selectedCameraRef = useRef(selectedCamera);
	const activeAlertsRef = useRef(activeAlerts);
	// Hàm tải lại danh sách sự cố, giữ trong ref để callback STOMP (tạo một lần) luôn gọi bản mới nhất
	const reloadActiveIncidentsRef = useRef(null);
	// Đã kết nối STOMP ít nhất một lần chưa: lần onConnect sau đó là kết nối lại
	const hasConnectedRef = useRef(false);

	useEffect(() => {
		soundEnabledRef.current = soundEnabled;
	}, [soundEnabled]);

	useEffect(() => {
		selectedCameraRef.current = selectedCamera;
	}, [selectedCamera]);

	useEffect(() => {
		activeAlertsRef.current = activeAlerts;
	}, [activeAlerts]);

	// Trình duyệt chỉ cho phát âm thanh sau thao tác đầu tiên của người dùng (autoplay policy)
	useEffect(() => installAlertSoundUnlock(), []);

	// Đóng menu dropdown lọc khi click ra ngoài
	useEffect(() => {
		const handleClickOutside = (e) => {
			if (
				categoryDropdownRef.current &&
				!categoryDropdownRef.current.contains(e.target)
			) {
				setIsCategoryDropdownOpen(false);
			}
		};
		if (isCategoryDropdownOpen) {
			document.addEventListener("mousedown", handleClickOutside);
		}
		return () => {
			document.removeEventListener("mousedown", handleClickOutside);
		};
	}, [isCategoryDropdownOpen]);

	// Phím tắt ESC để đóng dropdown/modal và Space / Mũi tên trái-phải điều khiển video bằng chứng
	useEffect(() => {
		const handleKeyDown = (e) => {
			if (e.key === "Escape") {
				if (isCategoryDropdownOpen) {
					setIsCategoryDropdownOpen(false);
				} else if (evidenceModalAlert) {
					setEvidenceModalAlert(null);
				} else if (isTheaterMode) {
					setIsTheaterMode(false);
				}
				return;
			}
			if (!evidenceModalAlert || evidenceModalTab !== "video") return;
			const tag = document.activeElement?.tagName;
			if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
			if (e.key === " " && tag !== "BUTTON" && document.activeElement?.getAttribute("role") !== "slider") {
				e.preventDefault();
				evidencePlayerRef.current?.togglePause?.();
			} else if (e.key === "ArrowLeft") {
				e.preventDefault();
				evidencePlayerRef.current?.nudgeSeconds?.(-5);
			} else if (e.key === "ArrowRight") {
				e.preventDefault();
				evidencePlayerRef.current?.nudgeSeconds?.(5);
			}
		};
		window.addEventListener("keydown", handleKeyDown);
		return () => window.removeEventListener("keydown", handleKeyDown);
	}, [isTheaterMode, evidenceModalAlert, evidenceModalTab, isCategoryDropdownOpen]);

	// Đồng bộ class body.soc-theater-active để ẩn chat/menu nổi và khóa nền tối toàn màn hình
	useEffect(() => {
		if (typeof document === "undefined") return undefined;
		document.body.classList.toggle("soc-theater-active", isTheaterMode);
		return () => document.body.classList.remove("soc-theater-active");
	}, [isTheaterMode]);

	// Dọn dẹp timeout còn tồn đọng khi unmount khỏi trang Giám sát
	useEffect(() => {
		return () => {
			if (flashTimeoutRef.current) clearTimeout(flashTimeoutRef.current);
			if (notificationTimeoutRef.current)
				clearTimeout(notificationTimeoutRef.current);
		};
	}, []);

	// Hiển thị thông báo toast tạm thời
	const triggerNotification = (msg) => {
		setStatusNotification(msg);
		if (notificationTimeoutRef.current) {
			clearTimeout(notificationTimeoutRef.current);
		}
		notificationTimeoutRef.current = setTimeout(() => {
			notificationTimeoutRef.current = null;
			setStatusNotification(null);
		}, 4500);
	};

	// Tải danh sách camera đang hoạt động từ Backend CSDL và đồng bộ định kỳ mỗi 15 giây
	useEffect(() => {
		let isMounted = true;

		async function loadActiveCameras(silent = false) {
			if (!silent) {
				setCamerasLoading(true);
			}
			try {
				const response = await fetchCameras({
					page: 0,
					size: 50,
					status: "ACTIVE",
				});
				if (!isMounted) return;
				const list = (response?.content || []).map((cam) => ({
					id: cam.id,
					code: (cam.cameraCode || "")
						.toLowerCase()
						.replace(/[^a-z0-9_-]/g, "")
						.trim(),
					cameraCode: cam.cameraCode,
					name: cam.name,
					areaName: cam.areaName || "",
					building: cam.building || cam.mapBuilding || "",
					floor: cam.floor || "",
					mapBuilding: cam.mapBuilding || "",
					mapFloorOrder: cam.mapFloorOrder,
					mapLatitude: cam.mapLatitude,
					mapLongitude: cam.mapLongitude,
					cameraType: cam.cameraType || "RESTRICTED_AREA",
					zone: "Khuôn viên trường",
					status: cam.operationalStatus || "OFFLINE",
				}));

				// Sắp xếp tự nhiên tăng dần theo mã camera (CAM-001, CAM-002, CAM-003, ...)
				list.sort((a, b) =>
					(a.cameraCode || "").localeCompare(b.cameraCode || "", undefined, {
						numeric: true,
						sensitivity: "base",
					}),
				);

				setCameraList((prev) => {
					const unchanged =
						prev.length === list.length &&
						prev.every(
							(c, idx) =>
								c.id === list[idx].id &&
								c.status === list[idx].status &&
								c.name === list[idx].name &&
								c.building === list[idx].building &&
								c.mapFloorOrder === list[idx].mapFloorOrder &&
								c.mapLatitude === list[idx].mapLatitude &&
								c.mapLongitude === list[idx].mapLongitude,
						);
					return unchanged ? prev : list;
				});
				if (list.length > 0) {
					const firstOnline =
						list.find((c) => c.status === "ONLINE") || list[0];
					setSelectedCamera((prev) =>
						prev && list.some((c) => c.code === prev) ? prev : firstOnline.code,
					);
				}
			} catch (err) {
				console.error("Không thể tải danh sách camera:", err);
			} finally {
				if (!silent && isMounted) {
					setCamerasLoading(false);
				}
			}
		}

		loadActiveCameras(false);
		const pollId = setInterval(() => loadActiveCameras(true), 15000);
		const handleVisibility = () => {
			if (document.visibilityState === "visible") {
				loadActiveCameras(true);
			}
		};
		document.addEventListener("visibilitychange", handleVisibility);

		return () => {
			isMounted = false;
			clearInterval(pollId);
			document.removeEventListener("visibilitychange", handleVisibility);
		};
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
				const sev = getAlertSeverityMeta(alert);
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

	// Gom danh sách cảnh báo chưa ghi nhận theo mã camera chuẩn hóa để gắn chấm đỏ trực tiếp lên thanh tua DVR
	// Khi bảo vệ bấm "Ghi nhận" (Đã xem) hoặc sự cố đã đóng -> chấm đỏ của sự cố đó biến mất khỏi thanh tua
	const alertsByCamera = useMemo(() => {
		const map = {};
		for (const alert of activeAlerts) {
			if (alert.socAcknowledged || !isAlertPending(alert.status)) continue;
			const cCode = (alert.camCodeClean || alert.cameraCode || "")
				.toLowerCase()
				.replace(/[^a-z0-9_-]/g, "")
				.trim();
			if (!cCode) continue;
			if (!map[cCode]) {
				map[cCode] = [];
			}
			map[cCode].push(alert);
		}
		return map;
	}, [activeAlerts]);

	// Giữ thứ tự camera ổn định theo Tòa / Tầng / Mã để tránh nhảy ô gây rối trí nhớ vị trí
	const sortedCameras = useMemo(
		() => sortCamerasStableByLocation(cameraList),
		[cameraList],
	);

	const buildingFilterOptions = useMemo(
		() => extractBuildingOptions(sortedCameras),
		[sortedCameras],
	);

	const floorFilterOptions = useMemo(
		() => extractFloorOptions(sortedCameras, buildingFilter),
		[sortedCameras, buildingFilter],
	);

	const areaNameFilterOptions = useMemo(
		() => extractAreaNameOptions(sortedCameras, buildingFilter, floorFilter),
		[sortedCameras, buildingFilter, floorFilter],
	);

	const filteredGridCameras = useMemo(
		() =>
			filterCamerasByHierarchy(sortedCameras, {
				buildingKey: buildingFilter,
				floorKey: floorFilter,
				areaKey: areaFilter,
			}),
		[sortedCameras, buildingFilter, floorFilter, areaFilter],
	);

	useEffect(() => {
		if (
			buildingFilter !== "ALL" &&
			!buildingFilterOptions.some((o) => o.key === buildingFilter)
		) {
			setBuildingFilter("ALL");
		}
	}, [buildingFilter, buildingFilterOptions]);

	useEffect(() => {
		if (
			floorFilter !== "ALL" &&
			!floorFilterOptions.some((o) => o.key === floorFilter)
		) {
			setFloorFilter("ALL");
		}
	}, [floorFilter, floorFilterOptions]);

	useEffect(() => {
		if (
			areaFilter !== "ALL" &&
			!areaNameFilterOptions.some((o) => o.key === areaFilter)
		) {
			setAreaFilter("ALL");
		}
	}, [areaFilter, areaNameFilterOptions]);

	// Số liệu thống kê hệ thống (System Status Metrics)
	const onlineCameraCount = cameraList.filter(
		(c) => c.status === "ONLINE",
	).length;
	const totalCameraCount = cameraList.length || 4;
	const recordingCameraCount = onlineCameraCount > 0 ? onlineCameraCount : 3;

	// Âm thanh cảnh báo: mỗi sự cố kêu 1 lần (khử trùng theo id/eventId, gộp cảnh báo dồn dập, 1 tab kêu)
	const playAlertSound = (incidentKeys, group) => {
		if (!soundEnabledRef.current) return;
		playIncidentAlert({ keys: incidentKeys, group }).catch(() => {});
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
		const ackSet = readSocStoredSet(SOC_ACK_STORAGE_KEY, guardUserId);
		const isAck =
			ackSet.has(String(incidentId)) ||
			(eventId && ackSet.has(String(eventId)));
		const rawCamCode = String(
			incident.camera_code || incident.cameraCode || "",
		).trim();
		const camCodeClean = rawCamCode
			.toLowerCase()
			.replace(/[^a-z0-9_-]/g, "")
			.trim();

		const rawTime = parseIncidentTimestamp(incident);
		const isoDate = getIncidentIsoString(incident);

		const newAlert = {
			id: incidentId,
			eventId: eventId,
			cameraCode: rawCamCode.toUpperCase(),
			camCodeClean,
			cameraName:
				incident.areaName ||
				incident.area_name ||
				incident.cameraName ||
				"Khu vực camera",
			eventType:
				incident.event_type || incident.eventType || "UNAUTHORIZED_PERSON",
			message: cleanIncidentMessage(
				incident.details || incident.resolutionNotes || incident.message,
				incident.event_type || incident.eventType,
				incident.areaName ||
					incident.area_name ||
					cameraAreaByCodeRef.current[camCodeClean],
				incident.cameraCode || incident.camera_code,
			),
			duration: incident.duration_seconds
				? `${incident.duration_seconds}s`
				: incident.duration || "Vừa phát hiện",
			timeParts: getIncidentTimeParts(isoDate || rawTime),
			timestamp: formatIncidentTimestamp(isoDate || rawTime),
			detectedAt: isoDate || new Date(rawTime).toISOString(),
			rawTime,
			status: incident.status || "NEW",
			socAcknowledged: isAck,
			claimedByName: incident.claimedByName || incident.claimed_by_name,
			imageUrl: incident.imageUrl || incident.image_url || null,
			severity: incident.severity || null,
			dispatchStage: incident.dispatchStage || null,
			repeatCount: incident.repeatCount || 1,
		};

		setActiveAlerts((prev) => {
			const existingIndex = prev.findIndex((a) => {
				// 1. Trùng ID sự cố (UUID hoặc ID)
				if (
					incident.id &&
					(a.id === incident.id || String(a.id) === String(incident.id))
				) {
					return true;
				}
				// 2. Trùng Event ID
				if (
					eventId &&
					(a.eventId === eventId ||
						a.id === eventId ||
						String(a.id) === String(eventId))
				) {
					return true;
				}
				// 3. Khử trùng lặp an toàn: cùng camera + cùng loại sự cố trong vòng 4 giây
				const isSameCamera =
					(a.cameraCode &&
						newAlert.cameraCode &&
						a.cameraCode === newAlert.cameraCode) ||
					(a.camCodeClean &&
						newAlert.camCodeClean &&
						a.camCodeClean === newAlert.camCodeClean);
				const isSameType = a.eventType === newAlert.eventType;
				const isRecent =
					Math.abs((newAlert.rawTime || Date.now()) - (a.rawTime || 0)) < 4000;
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
		if (!isAck) {
			playAlertSound(
				[incidentId, eventId],
				`${camCodeClean}|${newAlert.eventType}`,
			);
		}
		flashCameraAlert(camCodeClean);

		// Tự đưa sự cố mức CRITICAL vào ô tiêu điểm
		const severityMeta = getAlertSeverityMeta(newAlert);
		if (severityMeta.weight >= 3) {
			setSelectedCamera(camCodeClean);
		}
	};

	// Lắng nghe sự kiện cảnh báo an ninh qua WebSocket STOMP
	useEffect(() => {
		const API_BASE_URL =
			import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";
		const brokerURL = API_BASE_URL.replace(/^http/, "ws") + "/ws-security";

		hasConnectedRef.current = false;
		const stompClient = new Client({
			brokerURL,
			reconnectDelay: 4000,
			heartbeatIncoming: 4000,
			heartbeatOutgoing: 4000,
			// Đọc token mỗi lần (kết nối lại) để không dùng token cũ
			beforeConnect: () => {
				const token = localStorage.getItem("accessToken");
				stompClient.connectHeaders = token
					? { Authorization: `Bearer ${token}` }
					: {};
			},
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
							setActiveAlerts((prev) => {
								if (update.mergedIntoIncidentId) {
									return prev.filter(
										(a) =>
											a.id !== update.incidentId &&
											String(a.id) !== String(update.incidentId),
									);
								}
								return prev.map((a) =>
									a.id === update.incidentId ||
									String(a.id) === String(update.incidentId)
										? {
												...a,
												status: update.status || a.status,
												claimedByName: update.claimedByName || a.claimedByName,
												claimedById: update.claimedById || a.claimedById,
												severity: update.severity || a.severity,
												dispatchStage: update.dispatchStage || a.dispatchStage,
												// V129: AI nhận ra người sau cảnh báo người lạ -> backend cập nhật loại / nội dung sự cố
												...(update.identityUpdate && update.eventType
													? { eventType: update.eventType }
													: {}),
												...(update.identityUpdate && update.resolutionNotes
													? {
															message: cleanIncidentMessage(
																update.resolutionNotes,
																update.eventType || a.eventType,
																a.cameraName,
																a.cameraCode,
															),
														}
													: {}),
												...(update.imageUrl
													? { imageUrl: update.imageUrl }
													: {}),
												...(update.repeatAlert
													? {
															repeatCount:
																update.repeatCount || (a.repeatCount || 1) + 1,
															...(update.detectedAt
																? {
																		detectedAt: update.detectedAt,
																		timeParts: getIncidentTimeParts(
																			update.detectedAt,
																		),
																		timestamp: formatIncidentTimestamp(
																			update.detectedAt,
																		),
																		rawTime: new Date(
																			update.detectedAt,
																		).getTime(),
																	}
																: {}),
														}
													: {}),
											}
										: a,
								);
							});
							if (update.repeatAlert) {
								playAlertSound([
									update.repeatEventId ||
										`${update.incidentId}:repeat:${Date.now()}`,
								]);
							}
							setEvidenceModalAlert((prev) => {
								if (
									prev &&
									(prev.id === update.incidentId ||
										String(prev.id) === String(update.incidentId))
								) {
									return {
										...prev,
										status: update.status || prev.status,
										severity: update.severity || prev.severity,
										dispatchStage: update.dispatchStage || prev.dispatchStage,
										...(update.eventType
											? { eventType: update.eventType }
											: {}),
										...(update.resolutionNotes
											? {
													message: cleanIncidentMessage(
														update.resolutionNotes,
														update.eventType || prev.eventType,
														prev.cameraName,
														prev.cameraCode,
													),
												}
											: {}),
										...(update.imageUrl ? { imageUrl: update.imageUrl } : {}),
									};
								}
								return prev;
							});
							// Câu báo xác minh danh tính muộn (tự đóng / cập nhật), hiện như toast, không phát âm thanh
							if (update.identityNotice) {
								triggerNotification(update.identityNotice);
							}
						} catch (err) {
							console.error("Lỗi parse incident update:", err);
						}
					}
				});

				// Kết nối lại: tin phát trong lúc mất kết nối đã mất -> tải lại từ API để bù
				if (hasConnectedRef.current) {
					reloadActiveIncidentsRef.current?.({ silent: true });
					window.dispatchEvent(new CustomEvent("notification-updated"));
				}
				hasConnectedRef.current = true;
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

	// Gộp danh sách tải lại sau khi kết nối lại: cập nhật sự cố đã có, thêm sự cố mới lên đầu.
	// Sự cố mới hiển thị như nhận qua WebSocket nhưng chỉ phát âm thanh một lần cho cả đợt.
	const mergeReloadedIncidents = (mapped) => {
		const collectIds = (alerts) => {
			const ids = new Set();
			alerts.forEach((a) => {
				if (a.id) ids.add(String(a.id));
				if (a.eventId) ids.add(String(a.eventId));
			});
			return ids;
		};
		const newestFirst = (a, b) => (b.rawTime || 0) - (a.rawTime || 0);
		const knownIds = collectIds(activeAlertsRef.current);

		setActiveAlerts((prev) => {
			const prevIds = collectIds(prev);
			const reloadedById = new Map(mapped.map((a) => [String(a.id), a]));
			const updated = prev.map((a) => {
				const reloaded =
					reloadedById.get(String(a.id)) ||
					(a.eventId ? reloadedById.get(String(a.eventId)) : undefined);
				return reloaded ? { ...a, ...reloaded, eventId: a.eventId } : a;
			});
			const added = mapped
				.filter((a) => !prevIds.has(String(a.id)))
				.sort(newestFirst);
			return added.length > 0 ? [...added, ...updated] : updated;
		});

		const fresh = mapped
			.filter((a) => !knownIds.has(String(a.id)))
			.sort(newestFirst);
		if (fresh.length > 0) {
			const unacked = fresh.filter((a) => !a.socAcknowledged);
			if (unacked.length > 0) {
				playAlertSound(
					unacked.map((a) => a.id),
					`${unacked[0].camCodeClean}|${unacked[0].eventType}`,
				);
			}
			flashCameraAlert(fresh[0].camCodeClean);
			setSelectedCamera(fresh[0].camCodeClean);
		}
	};

	// Nạp danh sách sự cố chưa xử lý từ Backend REST API: khi tải trang và khi STOMP kết nối lại.
	// silent: không bật trạng thái loading toàn trang, gộp vào danh sách đang có thay vì thay thế.
	const reloadActiveIncidents = async ({ silent = false } = {}) => {
		if (!silent) {
			setIncidentsLoading(true);
		}
		try {
			const active = await getActiveIncidents();
			setIncidentsError(null);
			if (Array.isArray(active) && active.length > 0) {
				const ackSet = readSocStoredSet(SOC_ACK_STORAGE_KEY, guardUserId);
				const clearedSet = readSocStoredSet(
					SOC_CLEARED_STORAGE_KEY,
					guardUserId,
				);

				const mapped = active
					.filter((incident) => {
						const idStr = String(incident.id || incident.eventId);
						const evtStr = incident.eventId ? String(incident.eventId) : null;
						return (
							!clearedSet.has(idStr) && !(evtStr && clearedSet.has(evtStr))
						);
					})
					.map((incident) => {
						const incId = incident.id || incident.eventId;
						const rawCamCode = String(
							incident.camera_code || incident.cameraCode || "",
						).trim();
						const camCodeClean = rawCamCode
							.toLowerCase()
							.replace(/[^a-z0-9_-]/g, "")
							.trim();

						const rawTime = parseIncidentTimestamp(incident);
						const isoDate = getIncidentIsoString(incident);

						return {
							id: incId,
							eventId: incident.eventId || incident.event_id || null,
							cameraCode: rawCamCode.toUpperCase(),
							camCodeClean,
							cameraName:
								incident.areaName || incident.cameraName || "Khu vực camera",
							eventType: incident.eventType || "UNAUTHORIZED_PERSON",
							message: cleanIncidentMessage(
								incident.details ||
									incident.resolutionNotes ||
									incident.message,
								incident.eventType,
								incident.areaName || cameraAreaByCodeRef.current[camCodeClean],
								incident.cameraCode,
							),
							duration: incident.duration_seconds
								? `${incident.duration_seconds}s`
								: "Vừa phát hiện",
							timeParts: getIncidentTimeParts(isoDate || rawTime),
							timestamp: formatIncidentTimestamp(isoDate || rawTime),
							detectedAt: isoDate || incident.detectedAt || null,
							rawTime,
							status: incident.status || "NEW",
							socAcknowledged: ackSet.has(String(incId)),
							claimedByName: incident.claimedByName || incident.claimed_by_name,
							imageUrl: incident.imageUrl || incident.image_url || null,
							severity: incident.severity || null,
							dispatchStage: incident.dispatchStage || null,
							repeatCount: incident.repeatCount || 1,
						};
					});
				if (silent) {
					mergeReloadedIncidents(mapped);
				} else {
					setActiveAlerts(mapped);
				}
			}
		} catch (e) {
			console.warn("Không thể tải danh sách sự cố:", e);
			// Tải lại im lặng mà lỗi thì giữ danh sách đang có, không thay bằng màn lỗi
			if (!silent) {
				setIncidentsError(e?.message || "Không tải được danh sách sự cố.");
			}
		} finally {
			if (!silent) {
				setIncidentsLoading(false);
			}
		}
	};

	useEffect(() => {
		reloadActiveIncidentsRef.current = reloadActiveIncidents;
	});

	useEffect(() => {
		reloadActiveIncidentsRef.current?.();
	}, [incidentsReloadKey, guardUserId]);

	// Nghiệp vụ SOC: Xác nhận đã xem & tắt còi báo động tại phòng trực điều hành
	// Đồng thời kết thúc ghi hình sự cố an ninh và hoàn tất lưu video vào evidence/
	const handleAcknowledge = async (id) => {
		const strId = String(id);
		const ackSet = readSocStoredSet(SOC_ACK_STORAGE_KEY, guardUserId);
		ackSet.add(strId);

		const targetAlert = activeAlerts.find(
			(a) =>
				String(a.id) === strId || (a.eventId && String(a.eventId) === strId),
		);
		if (targetAlert?.eventId) {
			ackSet.add(String(targetAlert.eventId));
		}
		if (targetAlert?.id) {
			ackSet.add(String(targetAlert.id));
		}
		writeSocStoredSet(SOC_ACK_STORAGE_KEY, ackSet, guardUserId);

		const camCode =
			targetAlert?.cameraCode ||
			targetAlert?.camCodeClean ||
			targetAlert?.camera_code ||
			"";

		setActiveAlerts((prev) =>
			prev.map((a) =>
				String(a.id) === strId ||
				(targetAlert?.eventId &&
					String(a.eventId) === String(targetAlert.eventId))
					? { ...a, socAcknowledged: true }
					: a,
			),
		);
		setFlashAlertCamera(null);

		try {
			await acknowledgeIncident(strId, camCode);
			triggerNotification("Đã ghi nhận sự cố");
		} catch (err) {
			console.warn("Lỗi khi ghi nhận sự cố:", err);
			triggerNotification("Đã ghi nhận sự cố");
		}
	};

	// Nghiệp vụ SOC: Ghi nhận toàn bộ sự cố đang chờ
	// Đồng thời kết thúc toàn bộ video ghi hình sự cố an ninh
	const handleAcknowledgeAll = async () => {
		const ackSet = readSocStoredSet(SOC_ACK_STORAGE_KEY, guardUserId);
		activeAlerts.forEach((a) => {
			if (isAlertPending(a.status)) {
				ackSet.add(String(a.id));
				if (a.eventId) ackSet.add(String(a.eventId));
			}
		});
		writeSocStoredSet(SOC_ACK_STORAGE_KEY, ackSet, guardUserId);

		setActiveAlerts((prev) =>
			prev.map((a) =>
				isAlertPending(a.status) ? { ...a, socAcknowledged: true } : a,
			),
		);
		setFlashAlertCamera(null);

		try {
			await acknowledgeAllIncidents();
			triggerNotification("Đã ghi nhận tất cả sự cố");
		} catch (err) {
			console.warn("Lỗi khi ghi nhận tất cả sự cố:", err);
			triggerNotification("Đã ghi nhận tất cả sự cố");
		}
	};

	// Dọn dẹp các sự cố đã ghi nhận (SOC đã xem) hoặc đã được hiện trường đóng khỏi bảng trực
	const handleClearResolved = () => {
		const clearedSet = readSocStoredSet(SOC_CLEARED_STORAGE_KEY, guardUserId);
		activeAlerts.forEach((a) => {
			if (a.socAcknowledged || !isAlertPending(a.status)) {
				clearedSet.add(String(a.id));
				if (a.eventId) clearedSet.add(String(a.eventId));
			}
		});
		writeSocStoredSet(SOC_CLEARED_STORAGE_KEY, clearedSet, guardUserId);
		writeSocClearedBefore(Date.now(), guardUserId);
		setHasClearedIncidents(true);

		setActiveAlerts((prev) =>
			prev.filter((a) => !a.socAcknowledged && isAlertPending(a.status)),
		);
		triggerNotification("Đã dọn dẹp các sự cố đã ghi nhận khỏi bảng trực");
	};

	// Khôi phục lại toàn bộ danh sách sự cố đã ẩn khỏi bảng trực
	const handleRestoreCleared = () => {
		clearSocStoredSets(guardUserId);
		setHasClearedIncidents(false);
		setIncidentsReloadKey((k) => k + 1);
		triggerNotification("Đã khôi phục toàn bộ danh sách sự cố an ninh");
	};

	// Mở modal xem Ảnh hoặc Video hiện trường (YouTube-Live DVR Playback tại mốc sự cố)
	const handleOpenIncidentMedia = (alert, initialTab = "image") => {
		setEvidenceModalAlert(alert);
		setEvidenceModalTab(initialTab);
	};

	// Chuyển đổi qua lại giữa Ảnh và Video trong modal
	const handleSwitchModalTab = (tab) => {
		setEvidenceModalTab(tab);
	};

	// Chuyển trực tiếp video sang camera cụ thể
	const handleSelectCamera = (targetCamCode) => {
		const clean = (targetCamCode || "")
			.toLowerCase()
			.replace(/[^a-z0-9_-]/g, "")
			.trim();
		setSelectedCamera(clean);
		flashCameraAlert(clean);
		const hasActiveFilter =
			buildingFilter !== "ALL" || floorFilter !== "ALL" || areaFilter !== "ALL";
		const inFiltered = filteredGridCameras.some((c) => c.code === clean);
		const effectiveList =
			!inFiltered && hasActiveFilter ? sortedCameras : filteredGridCameras;

		if (!inFiltered && hasActiveFilter) {
			setBuildingFilter("ALL");
			setFloorFilter("ALL");
			setAreaFilter("ALL");
		}

		if (
			cameraLayout === 4 ||
			cameraLayout === 9 ||
			cameraLayout === 16 ||
			cameraLayout === "auto"
		) {
			const idx = effectiveList.findIndex((c) => c.code === clean);
			if (idx >= 0) {
				const size =
					cameraLayout === "auto"
						? Math.max(1, effectiveList.length)
						: Number(cameraLayout);
				setGridPage(Math.floor(idx / size));
			}
		}
	};

	// 5 TÙY CHỌN BỐ CỤC LƯỚI CAMERA: 1, 2x2, 3x3, 4x4, Tự vừa (N)
	const cameraLayoutOptions = [
		{ value: 1, label: "1" },
		{ value: 4, label: "2x2" },
		{ value: 9, label: "3x3" },
		{ value: 16, label: "4x4" },
		{ value: "auto", label: `Tự vừa (${filteredGridCameras.length})` },
	];

	// Danh sách camera hiển thị trên lưới theo layout, lọc Tòa/Tầng/Khu vực & phân trang hiện tại
	const {
		config: gridConfig,
		visibleCameras,
		emptySlotCount,
		totalPages: gridTotalPages,
		currentPage: safeGridPage,
	} = computeVisibleGridSlice(
		filteredGridCameras,
		cameraLayout,
		selectedCamera,
		gridPage,
	);

	const pendingAlertCount = activeAlerts.filter(
		(a) => !a.socAcknowledged && isAlertPending(a.status),
	).length;

	const handleFromDateChange = (nextFrom) => {
		setFromDate(nextFrom);
		if (nextFrom && toDate && nextFrom > toDate) {
			setToDate(nextFrom);
		}
	};

	const handleToDateChange = (nextTo) => {
		setToDate(nextTo);
		if (nextTo && fromDate && nextTo < fromDate) {
			setFromDate(nextTo);
		}
	};

	const handleClearDateFilter = () => {
		setFromDate("");
		setToDate("");
	};

	// 1. Lọc nền tảng theo trạng thái ('ALL' | 'PENDING')
	const baseStatusAlerts = useMemo(() => {
		if (alertFilter === "PENDING") {
			return activeAlerts.filter(
				(a) => !a.socAcknowledged && isAlertPending(a.status),
			);
		}
		return activeAlerts;
	}, [activeAlerts, alertFilter]);

	// 2. Lọc theo khoảng ngày (Từ ngày -> Đến ngày)
	const dateFilteredAlerts = useMemo(() => {
		return filterAlertsByDateRange(baseStatusAlerts, fromDate, toDate);
	}, [baseStatusAlerts, fromDate, toDate]);

	// 3. Thống kê số lượng sự cố theo từng nhóm nghiệp vụ dựa trên tab trạng thái & khoảng ngày đang chọn
	const categoryCounts = useMemo(() => {
		const counts = { ALL: dateFilteredAlerts.length };
		dateFilteredAlerts.forEach((a) => {
			const label = getAlertSeverityMeta(a).label || "Khác";
			counts[label] = (counts[label] || 0) + 1;
		});
		return counts;
	}, [dateFilteredAlerts]);

	// 4. Danh sách các tùy chọn dropdown phân loại sự cố (kèm số đếm thực tế)
	const categoryFilterOptions = useMemo(() => {
		const options = CANONICAL_INCIDENT_CATEGORIES.map((cat) => ({
			...cat,
			count: categoryCounts[cat.key] || 0,
		}));

		// Tự động thu thập thêm loại sự cố nếu backend có nhãn ngoài các nhóm chuẩn
		const knownKeys = new Set(options.map((o) => o.key));
		Object.keys(categoryCounts).forEach((key) => {
			if (!knownKeys.has(key)) {
				options.push({
					key,
					label: key,
					icon: AlertTriangle,
					chipClass: "cat-other",
					count: categoryCounts[key] || 0,
				});
			}
		});

		return options;
	}, [categoryCounts]);

	// Phân loại đang chọn (phục vụ hiển thị icon và class tương ứng trên khung select)
	const activeCategoryMeta = useMemo(() => {
		return (
			categoryFilterOptions.find((opt) => opt.key === categoryFilter) ||
			categoryFilterOptions[0]
		);
	}, [categoryFilterOptions, categoryFilter]);

	const filteredAlerts = useMemo(() => {
		let base = dateFilteredAlerts;
		if (categoryFilter !== "ALL") {
			base = base.filter((a) => {
				const label = getAlertSeverityMeta(a).label;
				return label === categoryFilter;
			});
		}

		return [...base].sort((a, b) => {
			const timeA = a.rawTime || 0;
			const timeB = b.rawTime || 0;
			// 1. Sắp xếp ưu tiên thời gian mới nhất lên đầu (Mới nhất -> Cũ nhất)
			if (timeB !== timeA) {
				return timeB - timeA;
			}
			// 2. Nếu cùng thời gian thì ưu tiên sự cố có mức độ nghiêm trọng cao hơn
			const weightA = getAlertSeverityMeta(a).weight;
			const weightB = getAlertSeverityMeta(b).weight;
			return weightB - weightA;
		});
	}, [dateFilteredAlerts, categoryFilter]);

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
							<span>Phóng to</span>
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
								if (next) unlockAlertSound();
								setSoundEnabled(next);
							}}
							title={soundEnabled ? "Tắt âm thanh" : "Bật âm thanh"}
						>
							{soundEnabled ? <Volume2 size={15} /> : <VolumeX size={15} />}
						</button>
					</div>
				</div>
			)}

			{/* Main Grid: Left Video Surveillance vs Right Alert Stream */}
			<div className="guard-main-grid">
				{/* Cột Trái: Giám Sát Video Trực Tiếp */}
				<div className="video-surveillance-panel">
					{/* Thanh công cụ điều khiển camera & Bộ chọn 5 Layouts + Hàng chip bên dưới */}
					<div className="camera-control-bar">
						<div className="camera-control-top-row">
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
											onClick={() => {
												setCameraLayout(opt.value);
												setGridPage(0);
											}}
										>
											{opt.label}
										</button>
									))}
								</div>

								{sortedCameras.length > 0 && (
									<div className="camera-hierarchy-filters">
										<div
											className="area-filter-box"
											title="Lọc camera theo Tòa nhà"
										>
											<Building2
												size={13}
												className="area-filter-icon"
											/>
											<select
												className="area-filter-select"
												aria-label="Lọc camera theo tòa"
												value={buildingFilter}
												onChange={(e) => {
													setBuildingFilter(e.target.value);
													setFloorFilter("ALL");
													setAreaFilter("ALL");
													setGridPage(0);
												}}
											>
												<option value="ALL">
													Tất cả tòa ({sortedCameras.length})
												</option>
												{buildingFilterOptions.map((opt) => (
													<option
														key={opt.key}
														value={opt.key}
													>
														{opt.label} ({opt.count})
													</option>
												))}
											</select>
										</div>

										<div
											className="area-filter-box"
											title="Lọc camera theo Tầng"
										>
											<Layers
												size={13}
												className="area-filter-icon"
											/>
											<select
												className="area-filter-select"
												aria-label="Lọc camera theo tầng"
												value={floorFilter}
												onChange={(e) => {
													setFloorFilter(e.target.value);
													setAreaFilter("ALL");
													setGridPage(0);
												}}
											>
												<option value="ALL">Tất cả tầng</option>
												{floorFilterOptions.map((opt) => (
													<option
														key={opt.key}
														value={opt.key}
													>
														{opt.label} ({opt.count})
													</option>
												))}
											</select>
										</div>

										{areaNameFilterOptions.length > 0 && (
											<div
												className="area-filter-box"
												title="Lọc camera theo Khu vực / Hành lang (camera hành lang được gộp chung)"
											>
												<MapPin
													size={13}
													className="area-filter-icon"
												/>
												<select
													className="area-filter-select"
													aria-label="Lọc camera theo khu vực"
													value={areaFilter}
													onChange={(e) => {
														setAreaFilter(e.target.value);
														setGridPage(0);
													}}
												>
													<option value="ALL">Tất cả khu vực</option>
													{areaNameFilterOptions.map((opt) => (
														<option
															key={opt.key}
															value={opt.key}
														>
															{opt.label} ({opt.count})
														</option>
													))}
												</select>
											</div>
										)}
									</div>
								)}
							</div>

							<div className="camera-control-top-actions">
								{gridTotalPages > 1 && cameraLayout !== 1 && (
									<div
										className="grid-pagination-bar"
										aria-label="Phân trang lưới camera"
									>
										<button
											type="button"
											className="grid-page-btn"
											onClick={() => setGridPage(Math.max(0, safeGridPage - 1))}
											disabled={safeGridPage <= 0}
											title="Trang trước"
										>
											<ChevronLeft size={14} />
										</button>
										<span className="grid-page-readout">
											{safeGridPage + 1}/{gridTotalPages}
										</span>
										<button
											type="button"
											className="grid-page-btn"
											onClick={() =>
												setGridPage(
													Math.min(gridTotalPages - 1, safeGridPage + 1),
												)
											}
											disabled={safeGridPage >= gridTotalPages - 1}
											title="Trang sau"
										>
											<ChevronRight size={14} />
										</button>
									</div>
								)}

								{/* Nút Thao tác khi ở Theater Mode: Âm thanh & Thu nhỏ */}
								{isTheaterMode && (
									<div className="theater-actions-bar">
										<button
											type="button"
											className={`btn-theater-test ${soundEnabled ? "" : "muted"}`}
											onClick={() => {
												const next = !soundEnabled;
												if (next) unlockAlertSound();
												setSoundEnabled(next);
											}}
											title={soundEnabled ? "Tắt âm thanh" : "Bật âm thanh"}
										>
											{soundEnabled ? (
												<Volume2 size={12} />
											) : (
												<VolumeX size={12} />
											)}
										</button>
										<button
											type="button"
											className="soc-exit-theater-btn"
											onClick={() => setIsTheaterMode(false)}
											title="Thoát chế độ phóng to màn hình (Phím ESC)"
										>
											<Minimize2 size={13} />
										</button>
									</div>
								)}
							</div>
						</div>

						{/* Hàng 2: Dải Camera Quick Switcher (Mã CAM-xxx ổn định theo Tòa · Tầng) */}
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
								filteredGridCameras.map((cam) => {
									const summary = cameraIncidentSummary[cam.code] || {
										count: 0,
									};
									const hasIncident = summary.count > 0;
									const isSelected = selectedCamera === cam.code;
									const isFlashing = flashAlertCamera === cam.code;
									const severityClass =
										summary.highestSeverity?.badgeClass || "severity-warning";

									const locSubtitle = formatCameraLocationSubtitle(cam);
									const subLabel = formatCameraSubLabel(cam);
									const floorBadge = formatShortFloorCode(cam);
									const chipLabel = formatCameraChipLabel(cam);

									return (
										<button
											key={cam.code || cam.id}
											type="button"
											disabled={!cam.code}
											className={`cam-chip-btn ${isSelected ? "active" : ""} ${hasIncident ? `has-incident ${severityClass}` : ""} ${isFlashing ? `flashing ${severityClass}` : ""}`}
											onClick={() => {
												if (cam.code) {
													handleSelectCamera(cam.code);
												}
											}}
											title={`${chipLabel}${subLabel ? ` — ${subLabel}` : ""}${floorBadge ? ` (${floorBadge})` : ""}${locSubtitle ? ` · ${locSubtitle}` : ""}${hasIncident ? ` — ${summary.count} sự cố` : ""}`}
										>
											<span
												className={`status-indicator-dot ${cam.status === "ONLINE" ? "online" : "standby"}`}
											/>
											<span className="cam-chip-name">{chipLabel}</span>

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
					</div>

					{/* Lưới Khung Hình Video (1, 2x2, 3x3, 4x4, Tự vừa) */}
					<div
						className={`camera-grid camera-grid--${gridConfig.mode}`}
						style={{
							"--auto-cols": String(gridConfig.cols),
							"--grid-rows": String(gridConfig.rows),
						}}
					>
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
							<>
								{visibleCameras.map((camera) => {
									const summary = cameraIncidentSummary[camera.code] || {
										count: 0,
									};
									const hasIncident = summary.count > 0;
									const isFlashing = flashAlertCamera === camera.code;
									const severityClass =
										summary.highestSeverity?.badgeClass || "severity-warning";
									const isFullFocusTile = gridConfig.mode === "1";

									return (
										<div
											className={`video-viewport-card ${hasIncident ? `card-has-incident ${severityClass}` : ""} ${isFlashing ? `card-flashing ${severityClass}` : ""}`}
											key={camera.code || camera.id}
											onClick={() => {
												if (
													camera.code &&
													!isFullFocusTile &&
													selectedCamera !== camera.code
												) {
													setSelectedCamera(camera.code);
												}
											}}
										>
											<WebRtcPlayer
												streamPath={camera.code}
												cameraCode={camera.cameraCode}
												cameraName={camera.name}
												displayLabel={formatCameraTileTitle(camera)}
												floorCode={formatShortFloorCode(camera)}
												cameraSubLabel={formatCameraSubLabel(camera)}
												incidentCount={summary.count}
												incidentSeverityClass={severityClass}
												enabled={Boolean(camera.code) && camera.status === "ONLINE"}
												enableDvr={true}
												alwaysShowDvr={false}
												enableDvrToggle={true}
												alerts={alertsByCamera[camera.code] || []}
												compactScrubber={!isFullFocusTile}
												host={
													import.meta.env.VITE_MEDIAMTX_HOST ||
													(typeof window !== "undefined" &&
													window.location.hostname
														? `${window.location.hostname}:8889`
														: "localhost:8889")
												}
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
								})}

								{Array.from({ length: emptySlotCount }, (_, idx) => (
									<div
										className="camera-grid-empty-slot"
										key={`empty-slot-${idx}`}
										aria-hidden="true"
									>
										<VideoOff size={20} />
										<span>Ô trống</span>
									</div>
								))}
							</>
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
								<span title="Danh sách sự cố an ninh (Thời gian mới nhất lên đầu)">
									SỰ CỐ AN NINH
								</span>
							</div>
							<span
								className={`soc-header-count ${pendingAlertCount > 0 ? "has-incidents" : ""}`}
								title="Số sự cố chờ ghi nhận"
							>
								{pendingAlertCount}
							</span>
						</div>

						{/* ┌──────────────────────────────┐
						    │ [Tất cả 0] [Chờ ghi nhận 0]  │
						    └──────────────────────────────┘ */}
						<div className="soc-filter-section">
							<div className="soc-filter-tabs">
								<button
									type="button"
									className={`soc-tab-btn ${alertFilter === "ALL" ? "active" : ""}`}
									onClick={() => setAlertFilter("ALL")}
									title="Tất cả sự cố (Sắp xếp theo thời gian mới nhất)"
								>
									<span>Tất cả</span>
									<span className="soc-tab-badge">{activeAlerts.length}</span>
								</button>
								<button
									type="button"
									className={`soc-tab-btn ${alertFilter === "PENDING" ? "active" : ""}`}
									onClick={() => setAlertFilter("PENDING")}
									title="Sự cố chờ phòng trực ghi nhận (Sắp xếp theo thời gian mới nhất)"
								>
									<span>Chờ ghi nhận</span>
									<span
										className={`soc-tab-badge ${pendingAlertCount > 0 ? "highlight" : ""}`}
									>
										{pendingAlertCount}
									</span>
								</button>
							</div>

							{/* Hộp Dropdown Lọc Phân Loại Sự Cố Tùy Biến (Custom Popover Select) */}
							{activeAlerts.length > 0 && (
								<div
									className="soc-category-select-row"
									ref={categoryDropdownRef}
								>
									<button
										type="button"
										className={`soc-category-dropdown-trigger ${isCategoryDropdownOpen ? "is-open" : ""} ${categoryFilter !== "ALL" ? "is-filtered" : ""}`}
										onClick={() => setIsCategoryDropdownOpen((prev) => !prev)}
										aria-haspopup="listbox"
										aria-expanded={isCategoryDropdownOpen}
										aria-label="Lọc theo loại sự cố an ninh"
									>
										<div className="soc-trigger-left">
											{activeCategoryMeta?.icon ? (
												<activeCategoryMeta.icon
													size={14}
													className={`soc-trigger-cat-icon ${activeCategoryMeta.chipClass || ""}`}
												/>
											) : (
												<Filter
													size={14}
													className="soc-trigger-cat-icon text-muted"
												/>
											)}
											<span className="soc-trigger-label-prefix">
												Loại sự cố:
											</span>
											<span className="soc-trigger-selected-title">
												{categoryFilter === "ALL"
													? "Tất cả loại sự cố"
													: activeCategoryMeta?.label}
											</span>
											<span className="soc-trigger-count-badge">
												{activeCategoryMeta?.count || 0}
											</span>
										</div>

										<div className="soc-trigger-right">
											{categoryFilter !== "ALL" && (
												<span
													role="button"
													tabIndex={0}
													className="soc-trigger-btn-clear"
													onClick={(e) => {
														e.stopPropagation();
														setCategoryFilter("ALL");
														setIsCategoryDropdownOpen(false);
													}}
													onKeyDown={(e) => {
														if (e.key === "Enter" || e.key === " ") {
															e.stopPropagation();
															setCategoryFilter("ALL");
															setIsCategoryDropdownOpen(false);
														}
													}}
													title="Bỏ lọc, quay về tất cả loại sự cố"
													aria-label="Bỏ lọc loại sự cố"
												>
													<X size={11} />
												</span>
											)}
											<ChevronDown
												size={14}
												className={`soc-trigger-chevron ${isCategoryDropdownOpen ? "rotate-180" : ""}`}
											/>
										</div>
									</button>

									{/* Menu Dropdown Tùy Biến Nổi (Custom Popover Menu) */}
									{isCategoryDropdownOpen && (
										<div
											className="soc-category-popover"
											role="listbox"
											aria-label="Danh sách loại sự cố an ninh"
										>
											{categoryFilterOptions.map((opt) => {
												const Icon = opt.icon;
												const isSelected = categoryFilter === opt.key;
												return (
													<button
														key={opt.key}
														type="button"
														role="option"
														aria-selected={isSelected}
														className={`soc-popover-item ${isSelected ? "is-selected" : ""} ${opt.chipClass || ""} ${opt.count === 0 ? "is-empty" : ""}`}
														onClick={() => {
															setCategoryFilter(opt.key);
															setIsCategoryDropdownOpen(false);
														}}
													>
														<div className="soc-popover-item-left">
															<span
																className={`soc-popover-icon-box ${opt.chipClass || ""}`}
															>
																<Icon size={14} />
															</span>
															<span className="soc-popover-item-label">
																{opt.key === "ALL"
																	? "Tất cả loại sự cố"
																	: opt.label}
															</span>
														</div>

														<div className="soc-popover-item-right">
															<span className="soc-popover-count-pill">
																{opt.count}
															</span>
															{isSelected && (
																<Check
																	size={14}
																	className="soc-popover-check-icon"
																/>
															)}
														</div>
													</button>
												);
											})}
										</div>
									)}
								</div>
							)}

							{/* Bộ lọc Từ ngày - Đến ngày (luôn hiển thị để người dùng lọc bất cứ lúc nào) */}
							<div className="soc-date-filter-row">
								<label
									className={`soc-date-field ${fromDate ? "is-active" : ""}`}
									title="Lọc sự cố từ ngày"
								>
									<Calendar
										size={12}
										className="soc-date-icon"
									/>
									<span className="soc-date-label">Từ</span>
									<input
										type="date"
										className="soc-date-input"
										aria-label="Lọc sự cố từ ngày"
										value={fromDate}
										max={toDate || undefined}
										onChange={(e) => handleFromDateChange(e.target.value)}
									/>
								</label>
								<label
									className={`soc-date-field ${toDate ? "is-active" : ""}`}
									title="Lọc sự cố đến ngày"
								>
									<span className="soc-date-label">Đến</span>
									<input
										type="date"
										className="soc-date-input"
										aria-label="Lọc sự cố đến ngày"
										value={toDate}
										min={fromDate || undefined}
										onChange={(e) => handleToDateChange(e.target.value)}
									/>
								</label>
								{(fromDate || toDate) && (
									<button
										type="button"
										className="soc-date-clear-btn"
										onClick={handleClearDateFilter}
										title="Xóa lọc ngày"
										aria-label="Xóa lọc theo ngày"
									>
										<X size={12} />
									</button>
								)}
							</div>

							{(pendingAlertCount > 1 ||
								hasClearedIncidents ||
								activeAlerts.some(
									(a) => a.socAcknowledged || !isAlertPending(a.status),
								)) && (
								<div className="soc-filter-actions-bar">
									<div className="soc-filter-actions-left">
										{activeAlerts.some(
											(a) => a.socAcknowledged || !isAlertPending(a.status),
										) && (
											<button
												type="button"
												className="soc-btn-clear"
												onClick={handleClearResolved}
												title="Dọn dẹp bảng trực (chỉ ẩn các sự cố đã ghi nhận/đã đóng khỏi màn hình, không xóa dữ liệu lưu trữ)"
												aria-label="Dọn dẹp bảng trực"
											>
												<Broom size={12} />
												<span>Dọn dẹp</span>
											</button>
										)}

										{hasClearedIncidents && (
											<button
												type="button"
												className="soc-btn-restore"
												onClick={handleRestoreCleared}
												title="Khôi phục lại toàn bộ danh sách sự cố đã ẩn"
											>
												<RotateCcw size={12} />
												<span>Khôi phục</span>
											</button>
										)}
									</div>

									{pendingAlertCount > 1 && (
										<button
											type="button"
											className="soc-btn-ack-all"
											onClick={handleAcknowledgeAll}
											title="Ghi nhận toàn bộ sự cố và tắt cảnh báo âm thanh"
										>
											<CheckCheck size={13} />
											<span>Ghi nhận tất cả</span>
										</button>
									)}
								</div>
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
							{filteredAlerts.length === 0 &&
							(incidentsError || incidentsLoading) ? (
								incidentsError ? null : (
									<LoadingState
										size="sm"
										text="Đang tải danh sách sự cố..."
									/>
								)
							) : filteredAlerts.length === 0 && activeAlerts.length > 0 ? (
								/* Trạng thái bộ lọc không có sự cố phù hợp */
								<div className="soc-filter-empty-state">
									<div className="soc-filter-empty-icon-box">
										<Filter size={20} />
									</div>
									<h4 className="soc-filter-empty-title">
										Không có sự cố phù hợp
									</h4>
									<p className="soc-filter-empty-desc">
										{(() => {
											const dateDesc = formatDateFilterSummary(
												fromDate,
												toDate,
											);
											const catDesc =
												categoryFilter !== "ALL" ? ` "${categoryFilter}"` : "";
											const statusDesc =
												alertFilter === "PENDING" ? " đang chờ ghi nhận" : "";
											if (dateDesc || catDesc) {
												return `Không có sự cố${catDesc}${statusDesc}${dateDesc ? ` ${dateDesc}` : ""}.`;
											}
											return "Không có sự cố nào ở trạng thái chờ ghi nhận.";
										})()}
									</p>
									<button
										type="button"
										className="soc-btn-reset-filter"
										onClick={() => {
											setAlertFilter("ALL");
											setCategoryFilter("ALL");
											setFromDate("");
											setToDate("");
										}}
									>
										<RotateCcw size={12} />
										<span>Xem tất cả sự cố ({activeAlerts.length})</span>
									</button>
								</div>
							) : filteredAlerts.length === 0 ? (
								/* ┌──────────────────────────────┐
				   │        🟢                    │
				   │    HỆ THỐNG AN TOÀN          │
				   │    Không có sự kiện          │
				   └──────────────────────────────┘ */
								<div className="soc-safe-system-state">
									<div className="soc-green-pulse-circle" />
									<h4 className="soc-safe-title">HỆ THỐNG AN TOÀN</h4>
									<p className="soc-safe-desc">Không có sự cố cần chú ý</p>
									{hasClearedIncidents && (
										<button
											type="button"
											className="soc-btn-restore-safe"
											onClick={handleRestoreCleared}
											title="Khôi phục lại toàn bộ sự cố đã ẩn trước đó"
										>
											<RotateCcw size={13} />
											<span>Khôi phục danh sách</span>
										</button>
									)}
								</div>
							) : (
								<div className="soc-incident-list">
									{filteredAlerts.map((alert) => {
										const sev = getAlertSeverityMeta(alert);
										const status = String(alert.status || "NEW").toUpperCase();
										const isPending = isAlertPending(status);

										return (
											<div
												key={alert.id}
												className={`soc-incident-card ${isPending ? "pending" : "resolved"} ${sev.badgeClass}`}
											>
												<div className="soc-incident-top">
													<div className="soc-incident-tags-group">
														{(() => {
															const TagIcon = sev.icon || AlertTriangle;
															return (
																<div
																	className={`soc-incident-tag ${sev.badgeClass || ""}`}
																>
																	<TagIcon
																		size={13}
																		className="soc-tag-incident-icon"
																	/>
																	<span>{sev.label}</span>
																</div>
															);
														})()}
														{(alert.repeatCount || 1) > 1 && (
															<span
																className="soc-claimed-label"
																title={`Cùng đối tượng này đã bị cảnh báo ${alert.repeatCount} lần trong sự cố đang mở`}
															>
																Lặp {alert.repeatCount} lần
															</span>
														)}
														{/* V93 (BR-DSP-11): đang mời một nhóm bảo vệ trên mobile; web chỉ theo dõi */}
														{isPending &&
															status === "NEW" &&
															String(
																alert.dispatchStage || "",
															).toUpperCase() === "STEP1" && (
																<span
																	className="soc-claimed-label"
																	title="Hệ thống đang mời một nhóm bảo vệ tiếp nhận qua ứng dụng di động"
																>
																	Đang mời bảo vệ
																</span>
															)}
													</div>

													<div
														className="soc-incident-time-pill"
														title={`Thời điểm phát hiện: ${alert.timeParts?.full || alert.timestamp}${alert.duration ? ` (${alert.duration})` : ""}`}
													>
														<Clock
															size={11}
															className="soc-time-clock-icon"
														/>
														<span className="soc-time-clock-text">
															{alert.timeParts?.time || alert.timestamp}
														</span>
														{!alert.timeParts?.isToday &&
															alert.timeParts?.shortDate && (
																<span className="soc-time-date-badge">
																	{alert.timeParts.shortDate}
																</span>
															)}
													</div>
												</div>

												{renderIncidentDetails(
													cameraAreaByCode[alert.camCodeClean]
														? cleanIncidentMessage(
																alert.message,
																alert.eventType,
																cameraAreaByCode[alert.camCodeClean],
																alert.cameraCode,
															)
														: alert.message,
												)}

												<div className="soc-incident-bottom">
													<div className="soc-incident-bottom-left">
														{/* Nút định vị nhanh tới camera trên lưới (luôn hiện đúng mã camera thật hoặc Không xác định) */}
														{(() => {
															const resolvedCamCode =
																resolveCameraCodeLabel(alert);
															const camCodeLabel =
																formatIncidentCameraLabel(alert);
															const areaLabel =
																cameraAreaByCode[alert.camCodeClean] ||
																(alert.cameraName &&
																alert.cameraName !== "Khu vực camera"
																	? alert.cameraName
																	: "");
															return (
																<button
																	type="button"
																	className="btn-soc-jump-cam"
																	disabled={!resolvedCamCode}
																	onClick={() => {
																		if (resolvedCamCode) {
																			handleSelectCamera(
																				alert.camCodeClean || resolvedCamCode,
																			);
																		}
																	}}
																	title={
																		resolvedCamCode
																			? `Định vị tới ${resolvedCamCode}${areaLabel ? ` — ${areaLabel}` : ""}`
																			: "Không xác định được mã camera của sự cố"
																	}
																>
																	<Video size={12} />
																	<span>{camCodeLabel}</span>
																</button>
															);
														})()}

														{/* Nút xem ảnh bằng chứng quả tang từ AI */}
														{alert.imageUrl && (
															<button
																type="button"
																className="btn-soc-evidence"
																onClick={() =>
																	handleOpenIncidentMedia(alert, "image")
																}
																title="Xem ảnh chụp bằng chứng quả tang từ AI"
															>
																<CameraIcon size={12} />
															</button>
														)}

														{/* Nút xem video sự cố ngay trong tab này */}
														<button
															type="button"
															className="btn-soc-video"
															onClick={() =>
																handleOpenIncidentMedia(alert, "video")
															}
															title="Xem video hiện trường sự cố ngay trong tab này"
														>
															<CirclePlay size={12} />
														</button>
													</div>

													<div
														className="soc-incident-workflow"
														aria-label="Trạng thái và thao tác phòng trực SOC"
													>
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
																		title="Ghi nhận đã theo dõi qua Camera"
																	>
																		<Check size={11} />
																		<span>Ghi nhận</span>
																	</button>
																)}
															</div>
														) : alert.socAcknowledged ? (
															<span
																className="soc-ack-badge"
																title="Phòng SOC đã ghi nhận và đang quan sát qua camera"
															>
																<Eye size={12} /> Đã ghi nhận
															</span>
														) : (
															<button
																type="button"
																className="btn-soc-ack"
																onClick={() => handleAcknowledge(alert.id)}
																title="Ghi nhận đã xem"
															>
																<Check size={12} />
																<span>Ghi nhận</span>
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

			{/* Modal hiển thị ảnh và video hiện trường sự cố (YouTube-Live DVR Playback) */}
			{evidenceModalAlert && (
				<div
					className="soc-evidence-modal-backdrop"
					onClick={() => {
						setEvidenceModalAlert(null);
					}}
				>
					<div
						className="soc-evidence-modal-content"
						role="dialog"
						aria-modal="true"
						aria-label="Bằng chứng vi phạm an ninh"
						onClick={(e) => e.stopPropagation()}
					>
						<div className="soc-evidence-modal-header">
							<div className="soc-evidence-modal-title">
								<ShieldAlert
									size={18}
									style={{
										color: getAlertSeverityMeta(evidenceModalAlert).color,
									}}
								/>
								<h4>BẰNG CHỨNG VI PHẠM AN NINH</h4>
								{(() => {
									const modalSev = getAlertSeverityMeta(evidenceModalAlert);
									const ModalTagIcon = modalSev.icon || AlertTriangle;
									return (
										<span className={`soc-incident-tag ${modalSev.badgeClass}`}>
											<ModalTagIcon
												size={14}
												className="soc-tag-incident-icon"
											/>
											<span>{modalSev.label}</span>
										</span>
									);
								})()}
							</div>
							<button
								type="button"
								className="soc-evidence-close-btn"
								onClick={() => {
									setEvidenceModalAlert(null);
								}}
								title="Đóng (ESC)"
							>
								<X size={18} />
							</button>
						</div>

						<div className="soc-evidence-modal-body">
							{/* Thanh chuyển chế độ Ảnh / Video ngay trong tab này */}
							<div className="soc-media-mode-toggle">
								<button
									type="button"
									className={`soc-media-tab-btn ${evidenceModalTab === "image" ? "active" : ""}`}
									onClick={() => handleSwitchModalTab("image")}
								>
									<CameraIcon size={14} />
									<span>Ảnh</span>
								</button>
								<button
									type="button"
									className={`soc-media-tab-btn ${evidenceModalTab === "video" ? "active" : ""}`}
									onClick={() => handleSwitchModalTab("video")}
								>
									<Film size={14} />
									<span>Video</span>
								</button>
							</div>

							{evidenceModalTab === "image" ? (
								<div className="soc-evidence-image-container">
									<EvidenceImage
										key={evidenceModalAlert.id}
										src={evidenceModalAlert.imageUrl}
										alt="Ảnh bằng chứng vi phạm an ninh"
										className="soc-evidence-full-img"
									/>
								</div>
							) : (
								<div className="soc-evidence-video-container">
									<WebRtcPlayer
										ref={evidencePlayerRef}
										key={`evidence-dvr-${evidenceModalAlert.camCodeClean || evidenceModalAlert.cameraCode || "cam"}`}
										streamPath={evidenceModalAlert.camCodeClean || ""}
										cameraCode={evidenceModalAlert.cameraCode || ""}
										cameraName={
											evidenceModalAlert.cameraName || "Camera hiện trường"
										}
										displayLabel={
											cameraAreaByCode[evidenceModalAlert.camCodeClean] ||
											(evidenceModalAlert.cameraName &&
											evidenceModalAlert.cameraName !== "Khu vực camera"
												? evidenceModalAlert.cameraName
												: "Camera hiện trường")
										}
										cameraSubLabel={formatIncidentCameraLabel(
											evidenceModalAlert,
										)}
										enabled={Boolean(
											evidenceModalAlert.camCodeClean ||
											evidenceModalAlert.cameraCode,
										)}
										enableDvr={true}
										enableTrackingToggle={false}
										enableDvrToggle={false}
										alwaysShowDvr={true}
										showLiveTag={true}
										initialSeekTimeMs={
											evidenceModalAlert.detectedAt
												? new Date(evidenceModalAlert.detectedAt).getTime()
												: evidenceModalAlert.rawTime
										}
										focusedAlertId={evidenceModalAlert.id}
										alerts={
											alertsByCamera[evidenceModalAlert.camCodeClean] || []
										}
										onAlertMarkerClick={(clickedAlert) => {
											if (clickedAlert) {
												setEvidenceModalAlert(clickedAlert);
											}
										}}
										host={
											import.meta.env.VITE_MEDIAMTX_HOST ||
											(typeof window !== "undefined" && window.location.hostname
												? `${window.location.hostname}:8889`
												: "localhost:8889")
										}
										muted={false}
									/>
								</div>
							)}

							<div className="soc-evidence-meta-panel">
								<div className="soc-evidence-meta-row">
									<span className="soc-evidence-meta-label">Camera:</span>
									<span className="soc-evidence-meta-val">
										{formatIncidentCameraLabel(evidenceModalAlert)}
									</span>
								</div>
								<div className="soc-evidence-meta-row">
									<span className="soc-evidence-meta-label">Thời điểm:</span>
									<span className="soc-evidence-meta-val">
										{evidenceModalAlert.timestamp}
									</span>
								</div>
								<div className="soc-evidence-meta-row">
									<span className="soc-evidence-meta-label">Nội dung:</span>
									<span className="soc-evidence-meta-val">
										{evidenceModalAlert.message}
									</span>
								</div>
							</div>
						</div>

						<div className="soc-evidence-modal-footer">
							<div className="soc-evidence-footer-left">
								{!evidenceModalAlert.socAcknowledged ? (
									<button
										type="button"
										className="btn-soc-acknowledge"
										onClick={() => {
											handleAcknowledge(evidenceModalAlert.id);
											setEvidenceModalAlert((prev) =>
												prev ? { ...prev, socAcknowledged: true } : prev,
											);
										}}
										title="Ghi nhận đã xem và tiếp nhận xử lý sự cố này"
									>
										<Check size={13} />
										<span>Ghi nhận</span>
									</button>
								) : (
									<span className="soc-ack-badge">
										<Check size={12} />
										<span>Đã ghi nhận</span>
									</span>
								)}
								<button
									type="button"
									className="btn-soc-download-clip"
									onClick={() => {
										if (evidenceModalTab !== "video") {
											handleSwitchModalTab("video");
											return;
										}
										const info = evidencePlayerRef.current?.getPlaybackInfo?.();
										if (!info?.srcUrl) {
											triggerNotification("Chưa có đoạn video phát lại để tải về.");
											return;
										}
										const cam =
											resolveCameraCodeLabel(evidenceModalAlert) || "camera";
										const link = document.createElement("a");
										link.href = info.srcUrl;
										link.download = `evidence-${cam}-${info.anchorMs || Date.now()}.mp4`;
										link.target = "_blank";
										link.rel = "noopener noreferrer";
										document.body.appendChild(link);
										link.click();
										document.body.removeChild(link);
									}}
									title={
										evidenceModalTab === "video"
											? "Tải đoạn video đang phát lại"
											: "Chuyển sang tab Video để tải clip"
									}
								>
									<Download size={13} />
									<span>Tải clip</span>
								</button>
								{evidenceModalTab === "video" && (
									<button
										type="button"
										className="btn-soc-download-clip"
										onClick={() => {
											const snap =
												evidencePlayerRef.current?.captureFrame?.();
											if (!snap?.base64) {
												triggerNotification(
													"Chưa có khung hình video để chụp.",
												);
												return;
											}
											const cam =
												resolveCameraCodeLabel(evidenceModalAlert) || "camera";
											const link = document.createElement("a");
											link.href = snap.base64;
											link.download = `snapshot-${cam}-${Date.now()}.jpg`;
											document.body.appendChild(link);
											link.click();
											document.body.removeChild(link);
										}}
										title="Chụp và tải khung hình hiện tại từ video"
									>
										<CameraIcon size={13} />
										<span>Chụp khung hình</span>
									</button>
								)}
							</div>
							<div className="soc-evidence-footer-right">
								{(() => {
									const modalCamCode =
										resolveCameraCodeLabel(evidenceModalAlert);
									return (
										<button
											type="button"
											className="btn-soc-jump-cam"
											disabled={!modalCamCode}
											onClick={() => {
												if (modalCamCode) {
													handleSelectCamera(
														evidenceModalAlert.camCodeClean || modalCamCode,
													);
													setEvidenceModalAlert(null);
												}
											}}
											title={
												modalCamCode
													? `Chuyển màn hình sang xem trực tiếp camera ${modalCamCode}`
													: "Không xác định được mã camera để xem trực tiếp"
											}
										>
											<Video size={13} />
											<span>
												{modalCamCode
													? `Xem trực tiếp camera ${modalCamCode}`
													: "Camera không xác định"}
											</span>
										</button>
									);
								})()}
								<button
									type="button"
									className="btn-soc-evidence-close"
									onClick={() => {
										setEvidenceModalAlert(null);
									}}
								>
									Đóng
								</button>
							</div>
						</div>
					</div>
				</div>
			)}
		</div>
	);
}

export default SecuritySurveillancePage;
