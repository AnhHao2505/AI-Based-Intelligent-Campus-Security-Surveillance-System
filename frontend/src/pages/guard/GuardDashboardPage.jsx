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
	Play,
	Pause,
	RotateCw,
	CirclePlay,
	Download,
	ExternalLink,
	RefreshCw,
	UserX,
	Box,
	Layers,
	Filter,
	ChevronDown,
	VideoOff,
} from "lucide-react";
import { fetchDiskRecordings, withRecordingStreamToken } from "../../services/recordingService";
import { useAuthorizedImage } from "../../hooks/useAuthorizedImage";
import WebRtcPlayer from "../../components/video/WebRtcPlayer";
import { fetchCameras } from "../../services/cameraService";
import {
	getActiveIncidents,
	acknowledgeIncident,
	acknowledgeAllIncidents,
} from "../../services/incidentService";
import "../../styles/GuardDashboardPage.css";
import { LoadingState, ErrorState } from "../../components/ui";
import { minimizeReason } from "../../utils/accessHistoryHelpers";

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

const cleanIncidentMessage = (msg, eventType = "", areaName = "", cameraCode = "") => {
	const cleanLocStr = (str) => {
		if (!str) return str;
		return str.replace(/\s*\([^)]*(?:vùng|roi)[^)]*\)/gi, "").trim();
	};

	if (!msg) {
		const loc = cleanLocStr(areaName || cameraCode || "Khu vực giám sát");
		return `Vị trí: ${loc}`;
	}
	let raw = String(msg).replace(/^\[[A-Z0-9_]+\]\s*/g, "").trim();

	// Nếu đã chứa cấu trúc phân dòng chuẩn (Tên: / Lý do: / Vị trí:), chuẩn hóa tối giản dòng Lý do và lọc sạch ROI suffix
	if (raw.includes("Vị trí:") || raw.includes("Tên:")) {
		let formatted = raw.replace(/Lý do:\s*([^\n]+)/i, (_, r) => `Lý do: ${minimizeReason(r)}`);
		formatted = formatted.replace(/Vị trí:\s*([^\n]+)/i, (_, loc) => `Vị trí: ${cleanLocStr(loc)}`);
		return formatted;
	}

	const evUpper = String(eventType).toUpperCase();
	const locDefault = cleanLocStr(areaName ? `${areaName}` : (cameraCode ? `Khu vực camera ${cameraCode}` : "Khu vực giám sát"));

	if (evUpper.includes("UNAUTHORIZED")) {
		const nameMatch = raw.match(/(?:truy cập|quy định):\s*([^()\n]+?\s*\([A-Za-z0-9_.-]+\))/i);
		const reasonMatch = raw.match(/Lý do:\s*(.*)$/i);
		const areaMatch = raw.match(/tại\s+([^.\n]+)/i);

		const nameStr = nameMatch ? nameMatch[1].trim() : "Đối tượng chưa cấp quyền";
		const reasonStr = minimizeReason(reasonMatch ? reasonMatch[1] : "");
		const locStr = cleanLocStr(areaMatch ? areaMatch[1].trim() : locDefault);

		return `Tên: ${nameStr}\nLý do: ${reasonStr}\nVị trí: ${locStr}`;
	}

	if (evUpper.includes("INTRUSION")) {
		const nameMatch = raw.match(/(?:quy định|giờ):\s*([^()\n]+?\s*\([A-Za-z0-9_.-]+\))/i);
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
	const lines = String(msg).split("\n").map((l) => l.trim()).filter(Boolean);
	const hasKeyValue = lines.some((l) => /^(Tên|Lý do|Vị trí|Đối tượng|Khu vực):/i.test(l));

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
						<div key={idx} className="soc-incident-detail-row">
							<span className="soc-detail-label">{label}:</span>
							<span className={`soc-detail-val ${isReason ? "is-reason" : ""} ${isName ? "is-name" : ""}`}>
								{val}
							</span>
						</div>
					);
				}
				return (
					<div key={idx} className="soc-incident-detail-row">
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
	const server = alert?.severity ? SERVER_SEVERITY_META[String(alert.severity).toUpperCase()] : null;
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
		const arr = Array.from(set).slice(-5000);
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

function SurveillanceEvidencePlayer({
	videoData,
	onDownload,
	onError,
}) {
	const videoRef = useRef(null);
	const [isPlaying, setIsPlaying] = useState(true);
	const [currentTime, setCurrentTime] = useState(0);
	const [duration, setDuration] = useState(60);
	const [isSeeking, setIsSeeking] = useState(false);
	const [playbackRate, setPlaybackRate] = useState(1);
	const [hasInitialSeek, setHasInitialSeek] = useState(false);

	const targetSec = Number(videoData.targetSeekSeconds) || 0;

	// Khi nạp xong metadata, tự động tua tới đúng mốc thời gian cảnh báo
	const handleLoadedMetadata = (e) => {
		const vid = e.currentTarget;
		if (vid) {
			const d = vid.duration;
			if (Number.isFinite(d) && d > 0) {
				setDuration(d);
			}
			if (targetSec > 0 && !hasInitialSeek) {
				try {
					vid.currentTime = Math.min(targetSec, (d && d > 0) ? d : targetSec);
					setCurrentTime(vid.currentTime);
					setHasInitialSeek(true);
				} catch (err) {
					console.warn("Lỗi tua mốc ban đầu:", err);
				}
			}
		}
	};

	const handleTimeUpdate = (e) => {
		if (!isSeeking && e.currentTarget) {
			setCurrentTime(e.currentTarget.currentTime);
			const d = e.currentTarget.duration;
			if (Number.isFinite(d) && d > 0 && d !== duration) {
				setDuration(d);
			}
		}
	};

	const togglePlay = () => {
		const vid = videoRef.current;
		if (!vid) return;
		if (vid.paused) {
			vid.play().catch(() => {});
			setIsPlaying(true);
		} else {
			vid.pause();
			setIsPlaying(false);
		}
	};

	const handleSliderChange = (e) => {
		const val = parseFloat(e.target.value);
		setCurrentTime(val);
		if (videoRef.current) {
			videoRef.current.currentTime = val;
		}
	};

	const jumpToTime = (sec) => {
		const vid = videoRef.current;
		if (!vid) return;
		const cleanSec = Math.max(0, Math.min(sec, duration));
		vid.currentTime = cleanSec;
		setCurrentTime(cleanSec);
	};

	const changeSpeed = (rate) => {
		setPlaybackRate(rate);
		if (videoRef.current) {
			videoRef.current.playbackRate = rate;
		}
	};

	const formatTime = (secs) => {
		if (!Number.isFinite(secs) || secs < 0) return "00:00";
		const m = Math.floor(secs / 60);
		const s = Math.floor(secs % 60);
		return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
	};

	const progressPercent = duration > 0 ? Math.min(100, Math.max(0, (currentTime / duration) * 100)) : 0;
	const targetMarkerPercent = duration > 0 && targetSec > 0 ? Math.min(100, Math.max(0, (targetSec / duration) * 100)) : null;

	return (
		<div className="soc-video-player-wrapper">
			<div className="soc-evidence-video-screen" onClick={togglePlay}>
				<video
					ref={videoRef}
					key={videoData.signedUrl}
					src={videoData.signedUrl}
					autoPlay
					playsInline
					preload="auto"
					className="soc-evidence-video-player"
					onLoadedMetadata={handleLoadedMetadata}
					onTimeUpdate={handleTimeUpdate}
					onPlay={() => setIsPlaying(true)}
					onPause={() => setIsPlaying(false)}
					onError={onError}
				>
					Trình duyệt không hỗ trợ thẻ video HTML5.
				</video>
			</div>

			{/* THANH TUA & ĐIỀU KHIỂN CHUYÊN DỤNG (SURVEILLANCE PLAYBACK BAR) */}
			<div className="soc-playback-control-bar">
				{/* Header thanh tua */}
				<div className="soc-playback-bar-header">
					<div className="soc-playback-bar-title">
						<Film size={13} style={{ color: "#38bdf8" }} />
						<span>THANH TUA & XEM LẠI SỰ CỐ (PLAYBACK CONTROLS)</span>
					</div>
					{targetSec > 0 && (
						<button
							type="button"
							className="soc-ctrl-btn soc-btn-jump-alert"
							onClick={() => jumpToTime(targetSec)}
							title={`Nhảy tới đúng giây cảnh báo: ${formatTime(targetSec)}`}
						>
							<ShieldAlert size={13} color="#ef4444" />
							<span>⏱️ Nhảy tới mốc sự cố ({formatTime(targetSec)})</span>
						</button>
					)}
				</div>

				{/* 1. Thanh Timeline Slider (Seek Bar) */}
				<div className="soc-timeline-track-wrapper">
					<div
						className="soc-timeline-progress-fill"
						style={{ width: `${progressPercent}%` }}
					/>
					<input
						type="range"
						min="0"
						max={duration || 60}
						step="0.1"
						value={currentTime}
						onMouseDown={() => setIsSeeking(true)}
						onMouseUp={() => setIsSeeking(false)}
						onTouchStart={() => setIsSeeking(true)}
						onTouchEnd={() => setIsSeeking(false)}
						onChange={handleSliderChange}
						className="soc-timeline-slider"
						title={`Kéo thanh này để tua video: ${formatTime(currentTime)} / ${formatTime(duration)}`}
					/>
					{/* Điểm đánh dấu sự cố (Alert Pin Marker) */}
					{targetMarkerPercent !== null && (
						<div
							className="soc-timeline-alert-marker"
							style={{ left: `${targetMarkerPercent}%` }}
							onClick={(e) => {
								e.stopPropagation();
								jumpToTime(targetSec);
							}}
							title={`📌 Mốc sự cố: ${formatTime(targetSec)} (Click để tua ngay)`}
						>
							<div className="soc-alert-pin-dot" />
							<span className="soc-alert-pin-label">Cảnh báo ({formatTime(targetSec)})</span>
						</div>
					)}
				</div>

				{/* 2. Dãy nút chức năng & Thời gian */}
				<div className="soc-playback-buttons-row">
					<div className="soc-playback-left-controls">
						<button
							type="button"
							className="soc-ctrl-btn soc-play-btn"
							onClick={togglePlay}
							title={isPlaying ? "Tạm dừng" : "Phát video"}
						>
							{isPlaying ? <Pause size={14} fill="currentColor" /> : <Play size={14} fill="currentColor" />}
							<span>{isPlaying ? "Tạm dừng" : "Phát"}</span>
						</button>

						<button
							type="button"
							className="soc-ctrl-btn"
							onClick={() => jumpToTime(currentTime - 5)}
							title="Lùi 5 giây"
						>
							<RotateCcw size={12} /> -5s
						</button>

						<button
							type="button"
							className="soc-ctrl-btn"
							onClick={() => jumpToTime(currentTime + 5)}
							title="Tiến 5 giây"
						>
							<RotateCw size={12} /> +5s
						</button>

						<button
							type="button"
							className="soc-ctrl-btn"
							onClick={() => jumpToTime(0)}
							title="Tua về đầu video (00:00)"
						>
							↺ Từ đầu
						</button>

						<span className="soc-playback-time-text">
							<strong>{formatTime(currentTime)}</strong> / {formatTime(duration)}
						</span>
					</div>

					<div className="soc-playback-right-controls">
						{/* Bộ chọn tốc độ */}
						<div className="soc-speed-selector">
							{[1, 1.5, 2].map((rate) => (
								<button
									key={rate}
									type="button"
									className={`soc-speed-btn ${playbackRate === rate ? "active" : ""}`}
									onClick={() => changeSpeed(rate)}
								>
									{rate}x
								</button>
							))}
						</div>

						<button
							type="button"
							className="btn-soc-dl-video"
							onClick={onDownload}
							title="Tải video này về máy"
						>
							<Download size={12} /> Tải MP4
						</button>
					</div>
				</div>
			</div>

			{/* Dòng thông tin metadata */}
			<div className="soc-video-meta-bar">
				<span>
					<strong>Tệp:</strong> {videoData.filename || videoData.id}
				</span>
				{targetSec > 0 && (
					<span style={{ color: "#38bdf8", fontWeight: 600 }}>
						⏱️ Nhảy tới thời điểm cảnh báo: {formatTime(targetSec)} ({targetSec}s)
					</span>
				)}
				{videoData.durationDisplay && (
					<span>
						<strong>Thời lượng:</strong> {videoData.durationDisplay}
					</span>
				)}
			</div>
		</div>
	);
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
	const [categoryFilter, setCategoryFilter] = useState("ALL"); // 'ALL' | 'Vật thể bất thường' | 'Người lạ' | ...
	const [isCategoryDropdownOpen, setIsCategoryDropdownOpen] = useState(false);
	const categoryDropdownRef = useRef(null);
	const [hasClearedIncidents, setHasClearedIncidents] = useState(
		() => getStoredSet(SOC_CLEARED_STORAGE_KEY).size > 0,
	);
	const [flashAlertCamera, setFlashAlertCamera] = useState(null);
	const [statusNotification, setStatusNotification] = useState(null);
	const [evidenceModalAlert, setEvidenceModalAlert] = useState(null);
	const [evidenceModalTab, setEvidenceModalTab] = useState("image"); // "image" | "video"
	const [incidentVideoLoading, setIncidentVideoLoading] = useState(false);
	const [incidentVideoData, setIncidentVideoData] = useState(null);
	const [incidentVideoError, setIncidentVideoError] = useState(null);
	const incidentVideoRef = useRef(null);

	const flashTimeoutRef = useRef(null);
	const soundEnabledRef = useRef(soundEnabled);

	useEffect(() => {
		soundEnabledRef.current = soundEnabled;
	}, [soundEnabled]);

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

	// Phím tắt ESC để đóng dropdown lọc, thoát chế độ phóng to hoặc đóng modal xem ảnh/video bằng chứng
	useEffect(() => {
		const handleKeyDown = (e) => {
			if (e.key === "Escape") {
				if (isCategoryDropdownOpen) {
					setIsCategoryDropdownOpen(false);
				} else if (evidenceModalAlert) {
					setEvidenceModalAlert(null);
					setIncidentVideoData(null);
					setIncidentVideoError(null);
				} else if (isTheaterMode) {
					setIsTheaterMode(false);
				}
			}
		};
		window.addEventListener("keydown", handleKeyDown);
		return () => window.removeEventListener("keydown", handleKeyDown);
	}, [isTheaterMode, evidenceModalAlert, isCategoryDropdownOpen]);

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

		const rawTime = parseIncidentTimestamp(incident);
		const isoDate = getIncidentIsoString(incident);

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
				incident.event_type || incident.eventType || "UNAUTHORIZED_PERSON",
			message: cleanIncidentMessage(
				incident.details ||
				incident.resolutionNotes ||
				incident.message,
				incident.event_type || incident.eventType,
				incident.areaName || incident.area_name,
				incident.cameraCode || incident.camera_code
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
											...(update.imageUrl ? { imageUrl: update.imageUrl } : {}),
										}
										: a,
								),
							);
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
										...(update.eventType ? { eventType: update.eventType } : {}),
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

							const rawTime = parseIncidentTimestamp(incident);
							const isoDate = getIncidentIsoString(incident);

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
								eventType: incident.eventType || "UNAUTHORIZED_PERSON",
								message: cleanIncidentMessage(
									incident.details ||
									incident.resolutionNotes ||
									incident.message,
									incident.eventType,
									incident.areaName,
									incident.cameraCode
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
			triggerNotification("Đã ghi nhận sự cố");
		} catch (err) {
			console.warn("Lỗi khi ghi nhận sự cố:", err);
			triggerNotification("Đã ghi nhận sự cố");
		}
	};

	// Nghiệp vụ SOC: Ghi nhận toàn bộ sự cố đang chờ
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
			triggerNotification("Đã ghi nhận tất cả sự cố");
		} catch (err) {
			console.warn("Lỗi khi ghi nhận tất cả sự cố:", err);
			triggerNotification("Đã ghi nhận tất cả sự cố");
		}
	};

	// Dọn dẹp các sự cố đã ghi nhận (SOC đã xem) hoặc đã được hiện trường đóng khỏi bảng trực
	const handleClearResolved = () => {
		const clearedSet = getStoredSet(SOC_CLEARED_STORAGE_KEY);
		activeAlerts.forEach((a) => {
			if (a.socAcknowledged || !isAlertPending(a.status)) {
				clearedSet.add(String(a.id));
			}
		});
		saveStoredSet(SOC_CLEARED_STORAGE_KEY, clearedSet);
		setHasClearedIncidents(clearedSet.size > 0);

		setActiveAlerts((prev) =>
			prev.filter((a) => !a.socAcknowledged && isAlertPending(a.status)),
		);
		triggerNotification("Đã dọn dẹp các sự cố đã ghi nhận khỏi bảng trực");
	};

	// Khôi phục lại toàn bộ danh sách sự cố đã ẩn khỏi bảng trực
	const handleRestoreCleared = () => {
		localStorage.removeItem(SOC_CLEARED_STORAGE_KEY);
		localStorage.removeItem(SOC_ACK_STORAGE_KEY);
		setHasClearedIncidents(false);
		setIncidentsReloadKey((k) => k + 1);
		triggerNotification("Đã khôi phục toàn bộ danh sách sự cố an ninh");
	};

	// Tải và phát video hiện trường sự cố trực tiếp trong tab này:
	// Ưu tiên phát "video định kỳ" (NORMAL_RECORDING) bao phủ thời điểm phát hiện cảnh báo,
	// và tự động nhảy đúng vị trí giây (seek/timestamp) của sự cố.
	const loadIncidentVideo = async (alert) => {
		if (!alert) return;
		setIncidentVideoLoading(true);
		setIncidentVideoError(null);
		try {
			const camCode = alert.camCodeClean || alert.cameraCode;
			const list = await fetchDiskRecordings({ cameraId: camCode });
			if (!list || list.length === 0) {
				setIncidentVideoError("Chưa có bản ghi video nào của camera này trên máy chủ.");
				return;
			}

			const alertTime = alert.detectedAt
				? new Date(alert.detectedAt).getTime()
				: (alert.rawTime || Date.now());

			// 1. Tìm video định kỳ (NORMAL_RECORDING) bao trọn hoặc chứa thời điểm phát hiện sự cố (alertTime)
			const normalRecordings = list.filter((v) => {
				const isSecurity =
					v.eventType === "SECURITY_EVENT" ||
					(v.filename && v.filename.toLowerCase().startsWith("incident"));
				return !isSecurity;
			});

			let matchedRecording = null;
			let seekOffsetSeconds = 0;

			// A. Tìm video định kỳ mà [startTime <= alertTime <= endTime]
			for (const rec of normalRecordings) {
				const startMs = new Date(rec.startTime).getTime();
				const endMs = new Date(rec.endTime).getTime();
				if (startMs <= alertTime && alertTime <= endMs) {
					matchedRecording = rec;
					seekOffsetSeconds = Math.max(0, (alertTime - startMs) / 1000);
					break;
				}
			}

			// B. Nếu không có video bao trùm chính xác, tìm video định kỳ bắt đầu gần nhất trước hoặc sau sự cố (trong ±1 giờ)
			if (!matchedRecording && normalRecordings.length > 0) {
				const candidates = normalRecordings.map((rec) => {
					const startMs = new Date(rec.startTime).getTime();
					const endMs = new Date(rec.endTime).getTime();
					const diff = startMs <= alertTime && alertTime <= endMs
						? 0
						: Math.min(Math.abs(alertTime - startMs), Math.abs(alertTime - endMs));
					return { rec, diff, startMs, endMs };
				});
				candidates.sort((a, b) => a.diff - b.diff);
				const best = candidates[0];
				// Chỉ chấp nhận nếu cách thời điểm sự cố dưới 10 phút
				if (best.diff <= 10 * 60 * 1000) {
					matchedRecording = best.rec;
					if (alertTime >= best.startMs) {
						seekOffsetSeconds = Math.max(0, (alertTime - best.startMs) / 1000);
					} else {
						seekOffsetSeconds = 0;
					}
				}
			}

			// C. Fallback: Nếu không có video định kỳ nào, dùng video sự cố (SECURITY_EVENT)
			if (!matchedRecording) {
				const incidentVideos = list.filter((v) => {
					const isSecurity =
						v.eventType === "SECURITY_EVENT" ||
						(v.filename && v.filename.toLowerCase().startsWith("incident"));
					if (!isSecurity) return false;
					const videoTime = new Date(v.startTime).getTime();
					return (
						videoTime >= alertTime - 30 * 1000 &&
						videoTime <= alertTime + 10 * 60 * 1000
					);
				});
				if (incidentVideos.length > 0) {
					incidentVideos.sort((a, b) => {
						const diffA = Math.abs(new Date(a.startTime).getTime() - alertTime);
						const diffB = Math.abs(new Date(b.startTime).getTime() - alertTime);
						return diffA - diffB;
					});
					matchedRecording = incidentVideos[0];
					const startMs = new Date(matchedRecording.startTime).getTime();
					seekOffsetSeconds = Math.max(0, (alertTime - startMs) / 1000);
				}
			}

			if (!matchedRecording || !matchedRecording.videoUrl) {
				setIncidentVideoError(
					"Không tìm thấy video ghi hình định kỳ tương ứng với thời điểm phát hiện cảnh báo.",
				);
				return;
			}

			let signedUrl = matchedRecording.videoUrl;
			try {
				signedUrl = await withRecordingStreamToken(matchedRecording.videoUrl);
			} catch (tokenErr) {
				console.warn("Lỗi xin stream token, fallback sang URL trực tiếp:", tokenErr);
			}

			// Thêm hash hoặc query param để trình duyệt / player hiểu mốc thời gian
			const cleanSeek = Math.max(0, Math.floor(seekOffsetSeconds));

			setIncidentVideoData({
				...matchedRecording,
				signedUrl,
				targetSeekSeconds: cleanSeek,
				alertTimeMs: alertTime,
			});
		} catch (err) {
			console.warn("Lỗi tải video sự cố:", err);
			setIncidentVideoError(err.message || "Không thể kết nối đến máy chủ video.");
		} finally {
			setIncidentVideoLoading(false);
		}
	};

	// Mở modal xem Ảnh hoặc Video sự cố trong tab hiện tại
	const handleOpenIncidentMedia = (alert, initialTab = "image") => {
		setEvidenceModalAlert(alert);
		setEvidenceModalTab(initialTab);
		setIncidentVideoData(null);
		setIncidentVideoError(null);
		if (initialTab === "video") {
			loadIncidentVideo(alert);
		}
	};

	// Chuyển đổi qua lại giữa Ảnh và Video trong modal
	const handleSwitchModalTab = (tab) => {
		setEvidenceModalTab(tab);
		if (tab === "video" && !incidentVideoData && !incidentVideoLoading) {
			loadIncidentVideo(evidenceModalAlert);
		}
	};

	// Tải file video MP4 về máy
	const handleDownloadIncidentVideo = async () => {
		if (!incidentVideoData) return;
		const popup = window.open("", "_blank");
		try {
			const signedUrl = await withRecordingStreamToken(
				incidentVideoData.downloadUrl || incidentVideoData.videoUrl,
			);
			if (popup) {
				popup.location.href = signedUrl;
			} else {
				window.location.assign(signedUrl);
			}
		} catch {
			popup?.close();
			triggerNotification("Không thể lấy liên kết tải video");
		}
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

	// 1. Lọc nền tảng theo trạng thái ('ALL' | 'PENDING')
	const baseStatusAlerts = useMemo(() => {
		if (alertFilter === "PENDING") {
			return activeAlerts.filter(
				(a) => !a.socAcknowledged && isAlertPending(a.status),
			);
		}
		return activeAlerts;
	}, [activeAlerts, alertFilter]);

	// 2. Thống kê số lượng sự cố theo từng nhóm nghiệp vụ dựa trên tab trạng thái đang chọn
	const categoryCounts = useMemo(() => {
		const counts = { ALL: baseStatusAlerts.length };
		baseStatusAlerts.forEach((a) => {
			const label = getAlertSeverityMeta(a).label || "Khác";
			counts[label] = (counts[label] || 0) + 1;
		});
		return counts;
	}, [baseStatusAlerts]);

	// 3. Danh sách các tùy chọn dropdown phân loại sự cố (kèm số đếm thực tế)
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
		let base = baseStatusAlerts;
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
	}, [baseStatusAlerts, categoryFilter]);

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
											title={`Xem Camera ${cam.cameraCode}: ${cam.name}${hasIncident ? ` (${summary.count} sự cố)` : ""}`}
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

						{/* Nút Thao tác khi ở Theater Mode: Âm thanh & Thu nhỏ */}
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
												<span>{summary.count} SỰ CỐ</span>
											</div>
										)}

										<WebRtcPlayer
											streamPath={camera.code}
											cameraCode={camera.cameraCode}
											cameraName={camera.name}
											enabled={camera.status === "ONLINE"}
											host={
												import.meta.env.VITE_MEDIAMTX_HOST ||
												(typeof window !== "undefined" && window.location.hostname
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
								<span title="Danh sách sự cố an ninh (Thời gian mới nhất lên đầu)">SỰ CỐ AN NINH</span>
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
									<span className={`soc-tab-badge ${pendingAlertCount > 0 ? "highlight" : ""}`}>
										{pendingAlertCount}
									</span>
								</button>
							</div>

							{/* Hộp Dropdown Lọc Phân Loại Sự Cố Tùy Biến (Custom Popover Select) */}
							{activeAlerts.length > 0 && (
								<div className="soc-category-select-row" ref={categoryDropdownRef}>
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
												<Filter size={14} className="soc-trigger-cat-icon text-muted" />
											)}
											<span className="soc-trigger-label-prefix">Loại sự cố:</span>
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
															<span className={`soc-popover-icon-box ${opt.chipClass || ""}`}>
																<Icon size={14} />
															</span>
															<span className="soc-popover-item-label">
																{opt.key === "ALL" ? "Tất cả loại sự cố" : opt.label}
															</span>
														</div>

														<div className="soc-popover-item-right">
															<span className="soc-popover-count-pill">{opt.count}</span>
															{isSelected && (
																<Check size={14} className="soc-popover-check-icon" />
															)}
														</div>
													</button>
												);
											})}
										</div>
									)}
								</div>
							)}

							{(pendingAlertCount > 1 || hasClearedIncidents || activeAlerts.some((a) => a.socAcknowledged || !isAlertPending(a.status))) && (
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
							{filteredAlerts.length === 0 && (incidentsError || incidentsLoading) ? (
								incidentsError ? null : <LoadingState size="sm" text="Đang tải danh sách sự cố..." />
							) : filteredAlerts.length === 0 && activeAlerts.length > 0 ? (
								/* Trạng thái bộ lọc không có sự cố phù hợp */
								<div className="soc-filter-empty-state">
									<div className="soc-filter-empty-icon-box">
										<Filter size={20} />
									</div>
									<h4 className="soc-filter-empty-title">Không có sự cố phù hợp</h4>
									<p className="soc-filter-empty-desc">
										{categoryFilter !== "ALL"
											? `Không có sự cố "${categoryFilter}" nào ${alertFilter === "PENDING" ? "đang chờ ghi nhận" : "trong danh sách"}.`
											: "Không có sự cố nào ở trạng thái chờ ghi nhận."}
									</p>
									<button
										type="button"
										className="soc-btn-reset-filter"
										onClick={() => {
											setAlertFilter("ALL");
											setCategoryFilter("ALL");
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
																<div className={`soc-incident-tag ${sev.badgeClass || ""}`}>
																	<TagIcon size={13} className="soc-tag-incident-icon" />
																	<span>{sev.label}</span>
																</div>
															);
														})()}
														{/* V93 (BR-DSP-11): đang mời một nhóm bảo vệ trên mobile; web chỉ theo dõi */}
														{isPending && status === "NEW" && String(alert.dispatchStage || "").toUpperCase() === "STEP1" && (
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
														<Clock size={11} className="soc-time-clock-icon" />
														<span className="soc-time-clock-text">
															{alert.timeParts?.time || alert.timestamp}
														</span>
														{!alert.timeParts?.isToday && alert.timeParts?.shortDate && (
															<span className="soc-time-date-badge">{alert.timeParts.shortDate}</span>
														)}
													</div>
												</div>

												{renderIncidentDetails(alert.message)}

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
														</button>

														{/* Nút xem ảnh bằng chứng quả tang từ AI */}
														{alert.imageUrl && (
															<button
																type="button"
																className="btn-soc-evidence"
																onClick={() => handleOpenIncidentMedia(alert, "image")}
																title="Xem ảnh chụp bằng chứng quả tang từ AI"
															>
																<CameraIcon size={12} />
															</button>
														)}

														{/* Nút xem video sự cố ngay trong tab này */}
														<button
															type="button"
															className="btn-soc-video"
															onClick={() => handleOpenIncidentMedia(alert, "video")}
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

			{/* Modal hiển thị ảnh và video bằng chứng quả tang từ AI */}
			{evidenceModalAlert && (
				<div
					className="soc-evidence-modal-backdrop"
					onClick={() => {
						setEvidenceModalAlert(null);
						setIncidentVideoData(null);
					}}
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
										color: getAlertSeverityMeta(evidenceModalAlert).color,
									}}
								/>
								<h4>BẰNG CHỨNG VI PHẠM AN NINH</h4>
								{(() => {
									const modalSev = getAlertSeverityMeta(evidenceModalAlert);
									const ModalTagIcon = modalSev.icon || AlertTriangle;
									return (
										<span className={`soc-incident-tag ${modalSev.badgeClass}`}>
											<ModalTagIcon size={14} className="soc-tag-incident-icon" />
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
									setIncidentVideoData(null);
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
									<span>Ảnh bằng chứng quả tang</span>
								</button>
								<button
									type="button"
									className={`soc-media-tab-btn ${evidenceModalTab === "video" ? "active" : ""}`}
									onClick={() => handleSwitchModalTab("video")}
								>
									<Film size={14} />
									<span>Video hiện trường sự cố</span>
								</button>
							</div>

							{evidenceModalTab === "image" ? (
								<div className="soc-evidence-image-container">
									<EvidenceImage
										src={evidenceModalAlert.imageUrl}
										alt="Ảnh bằng chứng vi phạm an ninh"
										className="soc-evidence-full-img"
									/>
								</div>
							) : (
								<div className="soc-evidence-video-container">
									{incidentVideoLoading ? (
										<div className="soc-video-loading-box">
											<Loader2 size={32} className="soc-spinner" />
											<span>Đang kết nối và tải video sự cố...</span>
										</div>
									) : incidentVideoError ? (
										<div className="soc-video-empty-box">
											<VideoOff size={48} color="#94a3b8" strokeWidth={1.5} />
											<p className="soc-video-unavailable-title">Video không khả dụng</p>
											<p className="soc-video-unavailable-sub">
												Không tìm thấy video sự cố tương ứng với thời điểm phát hiện.
											</p>
										</div>
									) : incidentVideoData?.signedUrl ? (
										<SurveillanceEvidencePlayer
											videoData={incidentVideoData}
											onDownload={handleDownloadIncidentVideo}
											onError={() =>
												setIncidentVideoError(
													"Không thể phát trực tiếp video trên trình duyệt (codec chưa hỗ trợ hoặc tệp đang ghi). Bạn có thể tải file MP4 về máy.",
												)
											}
										/>
									) : (
										<div className="soc-video-empty-box">
											<Film size={36} color="#64748b" />
											<p>Chưa có video được chọn</p>
											<button
												type="button"
												className="btn-soc-retry-video"
												onClick={() => loadIncidentVideo(evidenceModalAlert)}
											>
												<RefreshCw size={13} /> Tải video
											</button>
										</div>
									)}
								</div>
							)}

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
							<div className="soc-evidence-footer-left">
								{evidenceModalTab === "image" ? (
									<button
										type="button"
										className="btn-soc-switch-tab"
										onClick={() => handleSwitchModalTab("video")}
										title="Chuyển sang xem video sự cố ngay trong modal"
									>
										<Film size={13} />
										<span>Xem Video sự cố</span>
									</button>
								) : (
									<button
										type="button"
										className="btn-soc-switch-tab"
										onClick={() => handleSwitchModalTab("image")}
										title="Chuyển về xem ảnh bằng chứng"
									>
										<CameraIcon size={13} />
										<span>Xem Ảnh bằng chứng</span>
									</button>
								)}

								<button
									type="button"
									className="btn-soc-link-archive"
									onClick={() =>
										window.open(
											`/recordings?cameraId=${evidenceModalAlert.camCodeClean || evidenceModalAlert.cameraCode}`,
											"_blank",
										)
									}
									title="Mở toàn bộ kho lưu trữ ghi hình camera này ở tab mới"
								>
									<ExternalLink size={12} />
									<span>Kho lưu trữ ↗</span>
								</button>
							</div>

							<div className="soc-evidence-footer-right">
								<button
									type="button"
									className="btn-soc-jump-cam"
									onClick={() => {
										handleSelectCamera(
											evidenceModalAlert.camCodeClean ||
											evidenceModalAlert.cameraCode,
										);
										setEvidenceModalAlert(null);
										setIncidentVideoData(null);
									}}
									title="Chuyển màn hình sang xem trực tiếp camera này"
								>
									<Video size={13} />
									<span>Xem trực tiếp camera {evidenceModalAlert.cameraCode}</span>
								</button>
								<button
									type="button"
									className="btn-soc-evidence-close"
									onClick={() => {
										setEvidenceModalAlert(null);
										setIncidentVideoData(null);
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
