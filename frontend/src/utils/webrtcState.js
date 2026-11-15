/**
 * Trạng thái và quy tắc chuyển đổi vòng đời kết nối WebRTC (WHEP).
 * Tách riêng khỏi component để đảm bảo retry không reset effect gây tự ngắt kết nối sống
 * và không đánh dấu LIVE giả khi videoWidth === 0.
 */

export const INITIAL_WARMUP_FAILURE_THRESHOLD = 3;

export function hasDecodedVideoFrame(videoEl) {
	return Boolean(
		videoEl &&
		Number(videoEl.videoWidth) > 0 &&
		Number(videoEl.videoHeight) > 0,
	);
}

export function resolveWhepFailureTransition({
	consecutiveFailures = 0,
	hadLiveFrame = false,
} = {}) {
	const nextFailures = consecutiveFailures + 1;
	const inWarmup = !hadLiveFrame && nextFailures <= INITIAL_WARMUP_FAILURE_THRESHOLD;
	return {
		nextFailures,
		status: inWarmup ? "connecting" : "offline",
		retryDelayMs: inWarmup ? 1500 : 2500,
	};
}

export function formatWhepHttpError(statusCode, rawBodyText = "") {
	if (statusCode === 404 || statusCode === 400) {
		return "MediaMTX chưa nhận được luồng phát từ camera";
	}
	if (statusCode === 401 || statusCode === 403) {
		return `Không có quyền truy cập luồng phát (${statusCode})`;
	}
	const trimmed = String(rawBodyText || "").trim();
	return trimmed ? `Lỗi luồng video (${statusCode}): ${trimmed}` : `Kênh video không phản hồi (${statusCode})`;
}
