/**
 * Nhãn hiển thị sự cố an ninh dùng chung trên Dashboard.
 */
export const INCIDENT_EVENT_LABELS = {
	UNAUTHORIZED_PERSON: "Người không có quyền",
	PERSON_INTRUSION: "Xâm nhập ngoài giờ",
	SUSPICIOUS_MOVING_OBJECT: "Vật thể chuyển động bất thường",
	UNKNOWN_PERSON: "Người lạ mặt",
	UNAUTHORIZED_ACCESS: "Truy cập trái phép",
};

export const INCIDENT_SEVERITY_META = {
	CRITICAL: { label: "Nghiêm trọng", tone: "danger" },
	HIGH: { label: "Cao", tone: "warning" },
	MEDIUM: { label: "Trung bình", tone: "amber" },
	LOW: { label: "Thông tin", tone: "neutral" },
};

export const INCIDENT_STATUS_META = {
	NEW: { label: "Chờ xử lý", tone: "danger" },
	CLAIMED: { label: "Đang xử lý", tone: "warning" },
	RESOLVED_VERIFIED: { label: "Đã xác minh", tone: "success" },
	RESOLVED_DISMISSED: { label: "Báo động giả", tone: "neutral" },
};

export const OPERATIONAL_STATUS_META = {
	ONLINE: { label: "Trực tuyến", tone: "success" },
	LIVE: { label: "Đang phát", tone: "success" },
	CONNECTED: { label: "Đã kết nối", tone: "success" },
	OFFLINE: { label: "Ngoại tuyến", tone: "neutral" },
	DISCONNECTED: { label: "Mất kết nối", tone: "danger" },
	ERROR: { label: "Lỗi tín hiệu", tone: "danger" },
};

/**
 * Sắp xếp sự cố mới nhất theo detectedAt giảm dần và tính tổng hợp theo loại / mức độ / trạng thái.
 *
 * @param {Array<object>|null|undefined} incidents
 * @param {number} [limit=8]
 */
export function summarizeIncidents(incidents, limit = 8) {
	const list = Array.isArray(incidents) ? incidents : [];
	const sorted = [...list].sort((a, b) => {
		const ta = a?.detectedAt ? new Date(a.detectedAt).getTime() : 0;
		const tb = b?.detectedAt ? new Date(b.detectedAt).getTime() : 0;
		return tb - ta;
	});

	const byEventTypeMap = new Map();
	let criticalCount = 0;
	let pendingCount = 0;

	for (const inc of list) {
		if (inc?.severity === "CRITICAL") criticalCount += 1;
		if (inc?.status === "NEW" || inc?.status === "CLAIMED") pendingCount += 1;
		const typeKey = inc?.eventType || "UNKNOWN";
		byEventTypeMap.set(typeKey, (byEventTypeMap.get(typeKey) ?? 0) + 1);
	}

	const byEventType = Array.from(byEventTypeMap.entries())
		.map(([code, count]) => ({
			code,
			label: INCIDENT_EVENT_LABELS[code] || code,
			count,
		}))
		.sort((a, b) => b.count - a.count);

	return {
		total: list.length,
		criticalCount,
		pendingCount,
		byEventType,
		recent: sorted.slice(0, Math.max(0, limit)),
	};
}

/**
 * Tổng hợp bản ghi video từ danh sách /api/cameras/recordings.
 *
 * @param {Array<object>|null|undefined} recordings
 * @param {number} [limit=6]
 */
export function summarizeRecordings(recordings, limit = 6) {
	const list = Array.isArray(recordings) ? recordings : [];
	const sorted = [...list].sort((a, b) => {
		const ta = a?.startTime ? new Date(a.startTime).getTime() : 0;
		const tb = b?.startTime ? new Date(b.startTime).getTime() : 0;
		return tb - ta;
	});

	let securityCount = 0;
	let normalCount = 0;
	for (const rec of list) {
		if (rec?.eventType && rec.eventType !== "NORMAL_RECORDING") {
			securityCount += 1;
		} else {
			normalCount += 1;
		}
	}

	return {
		total: list.length,
		securityCount,
		normalCount,
		recent: sorted.slice(0, Math.max(0, limit)),
	};
}

/**
 * Lọc danh sách thành viên bảo vệ (bỏ Facility Manager) và thống kê trạng thái trực ca.
 *
 * @param {Array<object>|null|undefined} members
 */
export function summarizeGuardRoster(members) {
	const list = Array.isArray(members) ? members : [];
	const guards = list.filter((m) => m?.role !== "FACILITY_MANAGER");
	const onDuty = guards.filter((m) => Boolean(m?.onDuty));
	return {
		totalGuards: guards.length,
		onDutyCount: onDuty.length,
		guards,
	};
}

export const SOC_ACK_STORAGE_KEY = "soc_acknowledged_incident_ids";
export const SOC_CLEARED_STORAGE_KEY = "soc_cleared_incident_ids";
export const SOC_CLEARED_BEFORE_STORAGE_KEY = "soc_cleared_resolved_before_ms";
export const SOC_CLEARED_UPDATED_EVENT = "soc-cleared-incidents-updated";

export function isIncidentPendingStatus(status) {
	if (!status) return true;
	const s = String(status).toUpperCase();
	return !s.startsWith("RESOLVED") && s !== "DISMISSED" && s !== "IGNORED";
}

export function getScopedSocStorageKey(baseKey, userId) {
	return userId ? `${baseKey}:${userId}` : baseKey;
}

/**
 * Đọc tập ID sự cố đã ghi nhận / dọn dẹp của riêng bảo vệ đang đăng nhập.
 * Nếu trình duyệt còn khóa cũ chưa gắn userId, chuyển sang khóa riêng cho bảo vệ này.
 */
export function readSocStoredSet(
	baseKey,
	userId,
	storage = typeof localStorage !== "undefined" ? localStorage : null,
) {
	if (!storage || !baseKey) return new Set();
	const scopedKey = getScopedSocStorageKey(baseKey, userId);
	try {
		let raw = storage.getItem(scopedKey);
		if (!raw && userId) {
			const legacyRaw = storage.getItem(baseKey);
			if (legacyRaw) {
				storage.setItem(scopedKey, legacyRaw);
				storage.removeItem(baseKey);
				raw = legacyRaw;
			}
		}
		if (!raw) return new Set();
		const arr = JSON.parse(raw);
		return Array.isArray(arr) ? new Set(arr.map(String)) : new Set();
	} catch {
		return new Set();
	}
}

export function readSocClearedBefore(
	userId,
	storage = typeof localStorage !== "undefined" ? localStorage : null,
) {
	if (!storage) return null;
	try {
		const raw = storage.getItem(getScopedSocStorageKey(SOC_CLEARED_BEFORE_STORAGE_KEY, userId));
		if (!raw) return null;
		const parsed = Number(raw);
		return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
	} catch {
		return null;
	}
}

export function writeSocClearedBefore(
	timestampMs,
	userId,
	storage = typeof localStorage !== "undefined" ? localStorage : null,
) {
	if (!storage || !Number.isFinite(timestampMs) || timestampMs <= 0) return;
	try {
		storage.setItem(
			getScopedSocStorageKey(SOC_CLEARED_BEFORE_STORAGE_KEY, userId),
			String(timestampMs),
		);
		if (typeof window !== "undefined") {
			window.dispatchEvent(new CustomEvent(SOC_CLEARED_UPDATED_EVENT));
		}
	} catch {
		// Ignore storage write failures
	}
}

export function writeSocStoredSet(
	baseKey,
	set,
	userId,
	storage = typeof localStorage !== "undefined" ? localStorage : null,
) {
	if (!storage || !baseKey) return;
	const scopedKey = getScopedSocStorageKey(baseKey, userId);
	try {
		const arr = Array.from(set).slice(-5000);
		storage.setItem(scopedKey, JSON.stringify(arr));
		if (baseKey === SOC_CLEARED_STORAGE_KEY && typeof window !== "undefined") {
			window.dispatchEvent(new CustomEvent(SOC_CLEARED_UPDATED_EVENT));
		}
	} catch {
		// Ignore storage write failures
	}
}

export function clearSocStoredSets(
	userId,
	storage = typeof localStorage !== "undefined" ? localStorage : null,
) {
	if (!storage) return;
	try {
		storage.removeItem(getScopedSocStorageKey(SOC_CLEARED_STORAGE_KEY, userId));
		storage.removeItem(getScopedSocStorageKey(SOC_ACK_STORAGE_KEY, userId));
		storage.removeItem(getScopedSocStorageKey(SOC_CLEARED_BEFORE_STORAGE_KEY, userId));
		if (userId) {
			storage.removeItem(SOC_CLEARED_STORAGE_KEY);
			storage.removeItem(SOC_ACK_STORAGE_KEY);
			storage.removeItem(SOC_CLEARED_BEFORE_STORAGE_KEY);
		}
		if (typeof window !== "undefined") {
			window.dispatchEvent(new CustomEvent(SOC_CLEARED_UPDATED_EVENT));
		}
	} catch {
		// Ignore storage clear failures
	}
}

/**
 * Loại bỏ các sự cố đã bị bảo vệ ẩn qua nút "Dọn dẹp" (khớp theo id/eventId,
 * hoặc các sự cố đã đóng trước thời điểm bấm "Dọn dẹp").
 *
 * @param {Array<object>|null|undefined} incidents
 * @param {Set<string>|null|undefined} clearedSet
 * @param {number|null|undefined} clearedResolvedBeforeMs
 */
export function filterOutClearedIncidents(incidents, clearedSet, clearedResolvedBeforeMs = null) {
	if (!Array.isArray(incidents)) return incidents;
	const hasIds = Boolean(clearedSet && clearedSet.size > 0);
	const hasBefore = Number.isFinite(clearedResolvedBeforeMs) && clearedResolvedBeforeMs > 0;
	if (!hasIds && !hasBefore) return incidents;

	return incidents.filter((inc) => {
		const id = inc?.id != null ? String(inc.id) : null;
		const eventId = inc?.eventId != null
			? String(inc.eventId)
			: inc?.event_id != null
				? String(inc.event_id)
				: null;
		if (hasIds && ((id && clearedSet.has(id)) || (eventId && clearedSet.has(eventId)))) {
			return false;
		}
		if (hasBefore && !isIncidentPendingStatus(inc?.status)) {
			const tsRaw = inc?.resolvedAt || inc?.detectedAt;
			const ts = tsRaw ? new Date(tsRaw).getTime() : NaN;
			if (Number.isFinite(ts) && ts <= clearedResolvedBeforeMs) {
				return false;
			}
		}
		return true;
	});
}
