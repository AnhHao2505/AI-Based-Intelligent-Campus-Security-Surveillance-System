/**
 * Pure utilities for YouTube-Live-style DVR timeline window, red-dot alert markers,
 * and clock formatting on MediaMTX streams.
 */

export const DEFAULT_DVR_WINDOW_MS = 60 * 60 * 1000; // 1 giờ mặc định trên thanh tua DVR

export function formatClockTime(ms) {
	if (!Number.isFinite(ms) || ms <= 0) return "--:--:--";
	try {
		return new Date(ms).toLocaleTimeString("vi-VN", {
			hour12: false,
			hour: "2-digit",
			minute: "2-digit",
			second: "2-digit",
		});
	} catch {
		return "--:--:--";
	}
}

export function formatRelativeBehindLive(playheadMs, liveNowMs) {
	if (!Number.isFinite(playheadMs) || !Number.isFinite(liveNowMs)) return "";
	const diffSec = Math.max(0, Math.round((liveNowMs - playheadMs) / 1000));
	if (diffSec <= 2) return "TRỰC TIẾP";
	const hrs = Math.floor(diffSec / 3600);
	const mins = Math.floor((diffSec % 3600) / 60);
	const secs = diffSec % 60;
	if (hrs > 0) {
		return `-${hrs}:${String(mins).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
	}
	return `-${String(mins).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
}

export function computeDvrWindow({
	segments = [],
	alerts = [],
	liveNowMs,
	sessionStartMs,
	initialSeekTimeMs = null,
	maxSpanMs = DEFAULT_DVR_WINDOW_MS,
}) {
	const safeNow = Number.isFinite(liveNowMs) && liveNowMs > 0 ? liveNowMs : Date.now();
	const validSegments = Array.isArray(segments)
		? segments.filter((s) => Number.isFinite(s?.startMs) && Number.isFinite(s?.endMs) && s.endMs >= s.startMs)
		: [];

	const earliestRecordedMs = validSegments.length > 0
		? Math.min(...validSegments.map((s) => s.startMs))
		: null;
	const latestRecordedMs = validSegments.length > 0
		? Math.max(...validSegments.map((s) => s.endMs))
		: null;

	const alertTimes = (Array.isArray(alerts) ? alerts : [])
		.map((a) => Number(a?.rawTime))
		.filter((t) => Number.isFinite(t) && t > 0);
	const maxAlertMs = alertTimes.length > 0 ? Math.max(...alertTimes) : 0;

	const windowEndMs = Math.max(safeNow, latestRecordedMs || 0, maxAlertMs);

	// Mốc bắt đầu mặc định: nếu chưa ghi đủ maxSpanMs thì lấy từ đầu bản ghi hoặc đầu phiên xem (tối thiểu 60s)
	let defaultStartMs;
	if (earliestRecordedMs !== null) {
		defaultStartMs = Math.max(earliestRecordedMs, windowEndMs - maxSpanMs);
	} else {
		const safeSessionStart = Number.isFinite(sessionStartMs) && sessionStartMs > 0
			? sessionStartMs
			: safeNow - 60_000;
		defaultStartMs = Math.min(safeSessionStart, windowEndMs - 60_000);
	}

	// Nếu người dùng mở modal sự cố từ một thời điểm cũ hơn cửa sổ mặc định -> mở rộng cửa sổ về trước mốc đó
	let windowStartMs = defaultStartMs;
	if (Number.isFinite(initialSeekTimeMs) && initialSeekTimeMs > 0 && initialSeekTimeMs < windowStartMs + 30_000) {
		const targetWithLead = initialSeekTimeMs - 10 * 60 * 1000;
		windowStartMs = earliestRecordedMs !== null
			? Math.max(earliestRecordedMs, Math.min(windowStartMs, targetWithLead))
			: Math.min(windowStartMs, targetWithLead);
	}

	// Đảm bảo span tối thiểu 60 giây để thanh tua không bị co rúm khi stream vừa bật
	if (windowEndMs - windowStartMs < 60_000) {
		windowStartMs = windowEndMs - 60_000;
	}

	return {
		windowStartMs,
		windowEndMs,
		spanMs: Math.max(60_000, windowEndMs - windowStartMs),
		hasRecordedData: validSegments.length > 0,
		earliestRecordedMs,
		latestRecordedMs,
	};
}

export function mapAlertsToScrubberMarkers({
	alerts = [],
	windowStartMs,
	windowEndMs,
	liveNowMs,
	focusedAlertId = null,
}) {
	if (!Array.isArray(alerts) || alerts.length === 0) return [];
	const spanMs = Math.max(1000, windowEndMs - windowStartMs);

	return alerts
		.map((alert) => {
			const timeMs = Number(alert?.rawTime);
			if (!Number.isFinite(timeMs) || timeMs <= 0) return null;
			// Chỉ hiển thị các cảnh báo nằm trong cửa sổ timeline hiện tại (cho phép sai số nhẹ 5s ở rìa)
			if (timeMs < windowStartMs - 5000 || timeMs > windowEndMs + 5000) return null;

			const percent = Math.min(100, Math.max(0, ((timeMs - windowStartMs) / spanMs) * 100));
			const isRecent = Number.isFinite(liveNowMs) && (liveNowMs - timeMs) <= 15_000 && (liveNowMs - timeMs) >= -5000;
			const isFocused = focusedAlertId != null && String(alert.id) === String(focusedAlertId);

			return {
				id: alert.id || `${timeMs}-${alert.eventType || "alert"}`,
				timeMs,
				percent,
				timestampLabel: alert.timestamp || formatClockTime(timeMs),
				title: alert.message || alert.eventType || "Cảnh báo an ninh",
				severity: alert.severity || "WARNING",
				isRecent,
				isFocused,
				rawAlert: alert,
			};
		})
		.filter(Boolean)
		.sort((a, b) => a.timeMs - b.timeMs);
}
