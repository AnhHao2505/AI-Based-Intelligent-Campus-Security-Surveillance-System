// Âm thanh cảnh báo sự cố của trang Trung tâm Giám sát (SOC).
//
// Một sự cố chỉ kêu MỘT lần, kể cả khi:
// - backend phát lại cùng sự cố (vd. STEP1 -> BROADCAST gửi lại /topic/security-alerts);
// - React StrictMode (dev) tạo 2 kết nối STOMP cùng nhận một message;
// - người trực mở trang SOC ở nhiều tab.
//
// Cách làm theo các hệ thống cảnh báo lớn:
// - Khử trùng theo id sự cố (như Alertmanager khử trùng theo fingerprint): đã kêu cho id/eventId thì không kêu lại.
// - Gom cảnh báo dồn dập (như group_wait / group_interval): cùng camera + cùng loại trong GROUP_WINDOW_MS chỉ kêu
//   một lần; mọi cảnh báo cách nhau ít hơn COOLDOWN_MS cũng chỉ kêu một lần.
// - Nhiều tab: tab nào giữ được Web Lock thì kiểm tra + ghi vào localStorage rồi mới kêu, tab còn lại thấy đã ghi thì
//   im. Tab chưa được trình duyệt cho phát âm thanh (chưa có thao tác người dùng) không giành lượt, để tab khác kêu.
// - Một AudioContext dùng chung cho cả trang (MDN khuyên vậy), mở khoá bằng resume() ở lần click / gõ phím đầu tiên
//   theo autoplay policy của Chrome.

export const PLAYED_TTL_MS = 30 * 60 * 1000;
export const COOLDOWN_MS = 1500;
// Bằng cửa sổ gộp thẻ sự cố "cùng camera + cùng loại trong 4 giây" ở GuardDashboardPage
export const GROUP_WINDOW_MS = 4000;
const MAX_PLAYED_KEYS = 300;
const STORAGE_KEY = "icss.soc.alertSound.v1";
const LOCK_NAME = "icss-soc-alert-sound";
const RESUME_WAIT_MS = 200;

export function emptySoundState() {
	return { keys: {}, groups: {}, lastAt: null };
}

function pruneMap(map, now, ttl) {
	const entries = Object.entries(map || {}).filter(
		([, at]) => typeof at === "number" && now - at < ttl,
	);
	entries.sort((a, b) => b[1] - a[1]);
	return Object.fromEntries(entries.slice(0, MAX_PLAYED_KEYS));
}

/**
 * Quyết định có kêu hay không và trả về trạng thái mới. Hàm thuần, không đụng trình duyệt.
 * Đã thấy id / eventId -> không kêu, không đổi gì. Sự cố mới -> luôn ghi lại id, nhưng chỉ kêu khi
 * qua được cửa sổ gộp theo nhóm và khoảng nghỉ chung.
 */
export function decideAlertSound(state, { keys, group }, now) {
	const prev = state || emptySoundState();
	const cleanKeys = [...new Set((keys || []).filter((k) => k != null && k !== "").map(String))];
	const next = {
		keys: pruneMap(prev.keys, now, PLAYED_TTL_MS),
		groups: pruneMap(prev.groups, now, GROUP_WINDOW_MS),
		lastAt: typeof prev.lastAt === "number" ? prev.lastAt : null,
	};
	if (cleanKeys.length === 0 || cleanKeys.some((k) => k in next.keys)) {
		return { play: false, state: next };
	}
	for (const k of cleanKeys) next.keys[k] = now;

	const groupKey = group ? String(group) : null;
	const groupBusy = groupKey != null && groupKey in next.groups;
	const cooling = next.lastAt != null && now - next.lastAt < COOLDOWN_MS;
	if (groupKey != null) next.groups[groupKey] = now;
	if (groupBusy || cooling) {
		return { play: false, state: next };
	}
	next.lastAt = now;
	return { play: true, state: next };
}

// ───────────────────────── Phần chạy trên trình duyệt ─────────────────────────

let audioCtx = null;
// Trạng thái của riêng tab này: chặn trùng ngay cả khi localStorage bị khoá (chế độ ẩn danh, chặn site data)
let tabState = emptySoundState();

function getAudioContext() {
	if (audioCtx) return audioCtx;
	const Ctor = typeof window !== "undefined" && (window.AudioContext || window.webkitAudioContext);
	if (!Ctor) return null;
	try {
		audioCtx = new Ctor();
	} catch (e) {
		console.warn("Không tạo được AudioContext:", e);
		audioCtx = null;
	}
	return audioCtx;
}

/** Gọi trong một thao tác người dùng (click, gõ phím) để trình duyệt cho phép phát âm thanh. */
export function unlockAlertSound() {
	const ctx = getAudioContext();
	if (ctx && ctx.state === "suspended") {
		ctx.resume().catch(() => {});
	}
}

/** Gắn mở khoá âm thanh vào lần click / gõ phím đầu tiên trên trang. Trả về hàm gỡ. */
export function installAlertSoundUnlock() {
	if (typeof window === "undefined") return () => {};
	const events = ["pointerdown", "keydown", "touchstart"];
	const handler = () => {
		unlockAlertSound();
		if (audioCtx && audioCtx.state === "running") remove();
	};
	function remove() {
		for (const ev of events) window.removeEventListener(ev, handler, true);
	}
	for (const ev of events) window.addEventListener(ev, handler, true);
	return remove;
}

function readSharedState() {
	try {
		const raw = window.localStorage.getItem(STORAGE_KEY);
		return raw ? JSON.parse(raw) : emptySoundState();
	} catch {
		return null;
	}
}

function writeSharedState(state) {
	try {
		window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
	} catch {
		// Không ghi được thì vẫn còn trạng thái trong tab
	}
}

// Còi ngắn quét tần số 850Hz -> 380Hz (giữ nguyên âm sắc cũ), phát trên AudioContext dùng chung
function synthesizeSiren(ctx) {
	const t0 = ctx.currentTime;
	const osc = ctx.createOscillator();
	const gain = ctx.createGain();
	osc.type = "sawtooth";
	osc.frequency.setValueAtTime(850, t0);
	osc.frequency.exponentialRampToValueAtTime(380, t0 + 0.35);
	gain.gain.setValueAtTime(0.3, t0);
	gain.gain.linearRampToValueAtTime(0.01, t0 + 0.35);
	osc.connect(gain);
	gain.connect(ctx.destination);
	osc.onended = () => {
		osc.disconnect();
		gain.disconnect();
	};
	osc.start(t0);
	osc.stop(t0 + 0.35);
}

async function ensureRunning(ctx) {
	if (ctx.state === "running") return true;
	try {
		await Promise.race([
			ctx.resume(),
			new Promise((resolve) => setTimeout(resolve, RESUME_WAIT_MS)),
		]);
	} catch {
		// bị chặn autoplay
	}
	return ctx.state === "running";
}

/**
 * Kêu một lần cho một sự cố mới.
 * @param {{ keys: Array<string|number|null|undefined>, group?: string }} alert
 *   keys: id sự cố và eventId (bất kỳ cái nào đã kêu thì bỏ qua); group: vd. "cam-001|UNKNOWN_PERSON".
 */
export async function playIncidentAlert(alert) {
	const now = Date.now();
	const local = decideAlertSound(tabState, alert, now);
	tabState = local.state;
	if (!local.play) return false;

	const ctx = getAudioContext();
	if (!ctx || !(await ensureRunning(ctx))) {
		// Tab này chưa được phép phát -> không giành lượt trong localStorage, nhường tab khác
		return false;
	}

	const claimAndPlay = () => {
		const shared = readSharedState();
		if (shared) {
			const decision = decideAlertSound(shared, alert, Date.now());
			writeSharedState(decision.state);
			if (!decision.play) return false;
		}
		try {
			synthesizeSiren(ctx);
			return true;
		} catch (e) {
			console.warn("Không phát được âm thanh cảnh báo:", e);
			return false;
		}
	};

	if (typeof navigator !== "undefined" && navigator.locks?.request) {
		try {
			return await navigator.locks.request(LOCK_NAME, claimAndPlay);
		} catch {
			return claimAndPlay();
		}
	}
	return claimAndPlay();
}
