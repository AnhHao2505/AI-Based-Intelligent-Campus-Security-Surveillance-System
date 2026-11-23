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

/**
 * Chuyển danh sách segment đã ghi thành các dải % [left, width] trên thanh tua
 * để hiển thị rõ vùng đã ghi hình vs vùng trống/đang lưu.
 */
export function computeRecordedIntervalsPercent({
	segments = [],
	windowStartMs,
	windowEndMs,
}) {
	if (!Array.isArray(segments) || segments.length === 0) return [];
	const spanMs = Math.max(1000, windowEndMs - windowStartMs);

	const clamped = segments
		.filter((s) => Number.isFinite(s?.startMs) && Number.isFinite(s?.endMs) && s.endMs > s.startMs)
		.map((s) => ({
			start: Math.max(windowStartMs, s.startMs),
			end: Math.min(windowEndMs, s.endMs),
		}))
		.filter((s) => s.end > s.start)
		.sort((a, b) => a.start - b.start);

	if (clamped.length === 0) return [];

	// Gộp các đoạn liền kề/chồng lấn (sai số <= 2s) để thanh không bị chớp đứt khúc nhỏ
	const merged = [clamped[0]];
	for (let i = 1; i < clamped.length; i += 1) {
		const prev = merged[merged.length - 1];
		const curr = clamped[i];
		if (curr.start <= prev.end + 2000) {
			prev.end = Math.max(prev.end, curr.end);
		} else {
			merged.push({ ...curr });
		}
	}

	return merged.map((seg) => ({
		leftPercent: Math.min(100, Math.max(0, ((seg.start - windowStartMs) / spanMs) * 100)),
		widthPercent: Math.min(100, Math.max(0.4, ((seg.end - seg.start) / spanMs) * 100)),
	}));
}

/**
 * Chuẩn hóa đích tua khi người dùng bấm lùi/kéo thanh thời gian:
 * tránh báo lỗi đen màn hình khi vừa bấm lùi sát mép trực tiếp lúc segment mới chưa kịp flush.
 */
export function resolvePlaybackSeekTarget({
	requestedMs,
	segments = [],
	liveNowMs,
	liveEdgeThresholdMs = 2500,
}) {
	if (!Number.isFinite(requestedMs) || requestedMs <= 0) {
		return { action: "unavailable", notice: "Mốc thời gian không hợp lệ." };
	}
	const safeLiveMs = Number.isFinite(liveNowMs) && liveNowMs > 0 ? liveNowMs : Date.now();
	if (requestedMs >= safeLiveMs - liveEdgeThresholdMs) {
		return { action: "live", targetMs: safeLiveMs, snapped: false, notice: "" };
	}

	const validSegments = (Array.isArray(segments) ? segments : [])
		.filter((s) => Number.isFinite(s?.startMs) && Number.isFinite(s?.endMs) && s.endMs > s.startMs)
		.sort((a, b) => a.startMs - b.startMs);

	if (validSegments.length === 0) {
		return {
			action: "unavailable",
			reason: "Camera vừa kết nối hoặc chưa lưu xong đoạn video đầu tiên để xem lại.",
		};
	}

	// Khớp trực tiếp bên trong đoạn đã ghi
	const directMatch = validSegments.find(
		(s) => requestedMs >= s.startMs && requestedMs <= s.endMs,
	);
	if (directMatch) {
		const clamped = Math.max(directMatch.startMs, Math.min(directMatch.endMs - 2000, requestedMs));
		return {
			action: "seek",
			targetMs: Math.max(directMatch.startMs, clamped),
			snapped: false,
			notice: "",
		};
	}

	const latestSeg = validSegments[validSegments.length - 1];
	// Bấm sát mép LIVE (khoảng trống 3-45s chưa flush xuống đĩa) -> neo về đoạn ghi gần nhất
	if (requestedMs > latestSeg.endMs && safeLiveMs - latestSeg.endMs <= 60_000) {
		const fallbackMs = Math.max(latestSeg.startMs, latestSeg.endMs - 3000);
		return {
			action: "seek",
			targetMs: fallbackMs,
			snapped: true,
			notice: `Bản ghi mới đang lưu — đã chuyển về đoạn gần nhất (${formatClockTime(fallbackMs)})`,
		};
	}

	// Tìm đoạn gần nhất sau hoặc trước mốc được chọn để không bao giờ kẹt ở màn hình LIVE
	let nearestSeg = null;
	let minDist = Infinity;
	for (const seg of validSegments) {
		const dist = requestedMs < seg.startMs ? seg.startMs - requestedMs : requestedMs - seg.endMs;
		if (dist < minDist) {
			minDist = dist;
			nearestSeg = seg;
		}
	}

	if (nearestSeg) {
		const targetMs =
			requestedMs < nearestSeg.startMs
				? nearestSeg.startMs
				: Math.max(nearestSeg.startMs, nearestSeg.endMs - 3000);
		return {
			action: "seek",
			targetMs,
			snapped: true,
			notice: `Khoảng này chưa có bản ghi — đã nhảy tới đoạn gần nhất (${formatClockTime(targetMs)})`,
		};
	}

	return {
		action: "unavailable",
		reason: "Chưa có bản ghi nào được lưu cho camera này.",
	};
}
