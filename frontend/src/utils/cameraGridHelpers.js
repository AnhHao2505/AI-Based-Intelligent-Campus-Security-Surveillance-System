/**
 * Pure helpers for Guard SOC Livestream Grid (layouts: 1, spotlight 1+5, 2x2, 3x3, 4x4, auto-fit).
 */

export const UNKNOWN_CAMERA_CODE_LABEL = "Không xác định";

function looksLikeCameraCode(value) {
	const trimmed = String(value || "").trim();
	if (!trimmed) return false;
	return /^cam[-_\s]*[a-z0-9_-]*\d+[a-z0-9_-]*$/i.test(trimmed);
}

export function formatCameraLocationSubtitle(cam) {
	if (!cam || typeof cam !== "object") return "";
	const building = cam.building || cam.mapBuilding || "";
	const floor =
		cam.floor ||
		(cam.mapFloorOrder != null && Number.isFinite(Number(cam.mapFloorOrder))
			? Number(cam.mapFloorOrder) === 0
				? "Tầng Trệt"
				: `Tầng ${Number(cam.mapFloorOrder)}`
			: "");
	return [building, floor].filter(Boolean).join(" · ");
}

export function resolveCameraCodeLabel(camOrCode) {
	if (typeof camOrCode === "string") {
		return camOrCode.trim().toUpperCase();
	}
	if (!camOrCode || typeof camOrCode !== "object") return "";
	return String(
		camOrCode.cameraCode ||
			camOrCode.camera_code ||
			camOrCode.code ||
			camOrCode.camCodeClean ||
			"",
	)
		.trim()
		.toUpperCase();
}

export function formatCameraCodeOrUnknown(camOrCode) {
	return resolveCameraCodeLabel(camOrCode) || UNKNOWN_CAMERA_CODE_LABEL;
}

export function formatIncidentCameraLabel(alert) {
	return formatCameraCodeOrUnknown(alert);
}

export function formatCameraTileTitle(cam) {
	if (!cam || typeof cam !== "object") return UNKNOWN_CAMERA_CODE_LABEL;
	const areaName = (cam.areaName || "").trim();
	const rawCamName = (cam.name || "").trim();
	const codeLabel = resolveCameraCodeLabel(cam);
	const safeCamName = looksLikeCameraCode(rawCamName)
		? codeLabel || UNKNOWN_CAMERA_CODE_LABEL
		: rawCamName;
	if (areaName && safeCamName && areaName.toLowerCase() !== safeCamName.toLowerCase()) {
		return `${areaName} · ${safeCamName}`;
	}
	const baseName = safeCamName || areaName || codeLabel || UNKNOWN_CAMERA_CODE_LABEL;
	const floor =
		cam.floor ||
		(cam.mapFloorOrder != null && Number.isFinite(Number(cam.mapFloorOrder))
			? Number(cam.mapFloorOrder) === 0
				? "Tầng Trệt"
				: `Tầng ${Number(cam.mapFloorOrder)}`
			: "");
	if (!floor || baseName.toLowerCase().includes(floor.toLowerCase())) {
		return baseName;
	}
	return `${baseName} · ${floor}`;
}

export function formatShortFloorCode(cam) {
	if (!cam || typeof cam !== "object") return "";
	if (cam.mapFloorOrder != null && cam.mapFloorOrder !== "" && Number.isFinite(Number(cam.mapFloorOrder))) {
		const n = Number(cam.mapFloorOrder);
		return n === 0 ? "G" : `T${n}`;
	}
	const raw = String(cam.floor || "").trim().toLowerCase();
	if (!raw) return "";
	if (raw.includes("trệt") || raw === "g" || raw === "0") return "G";
	const match = raw.match(/(\d+)/);
	return match ? `T${match[1]}` : "";
}

export function formatCameraRoomName(cam) {
	if (!cam || typeof cam !== "object") return UNKNOWN_CAMERA_CODE_LABEL;
	const areaName = (cam.areaName || "").trim();
	const rawCamName = (cam.name || "").trim();
	const safeCamName = looksLikeCameraCode(rawCamName) ? "" : rawCamName;
	const base = areaName || safeCamName || formatCameraCodeOrUnknown(cam);
	const cleaned = base
		.replace(/\s*(?:·|-)?\s*(?:Tầng\s*(?:Trệt|\d+)|T\d+)\s*$/i, "")
		.trim();
	return cleaned || base;
}

export function formatCameraRoomFloorTitle(cam) {
	if (!cam || typeof cam !== "object") return UNKNOWN_CAMERA_CODE_LABEL;
	const base = formatCameraRoomName(cam);
	const floorCode = formatShortFloorCode(cam);
	return floorCode ? `${base} · ${floorCode}` : base;
}

export function formatCameraSubLabel(cam) {
	return formatCameraCodeOrUnknown(cam);
}

export function formatCameraChipLabel(cam) {
	return formatCameraCodeOrUnknown(cam);
}

export function resolveCameraBuilding(cam) {
	if (!cam || typeof cam !== "object") return "";
	return String(cam.building || cam.mapBuilding || "").trim();
}

export function resolveCameraFloorInfo(cam) {
	if (!cam || typeof cam !== "object") {
		return { floorOrder: Number.MAX_SAFE_INTEGER, floorLabel: "", building: "", areaKey: "UNASSIGNED", areaLabel: "Khác" };
	}
	const building = resolveCameraBuilding(cam);
	const rawOrder = cam.mapFloorOrder;
	const hasNumericOrder = rawOrder != null && rawOrder !== "" && Number.isFinite(Number(rawOrder));
	const floorOrder = hasNumericOrder ? Number(rawOrder) : Number.MAX_SAFE_INTEGER;
	const floorLabel = String(
		cam.floor ||
			(hasNumericOrder ? (Number(rawOrder) === 0 ? "Tầng Trệt" : `Tầng ${Number(rawOrder)}`) : ""),
	).trim();

	const areaLabel = [building, floorLabel].filter(Boolean).join(" · ") || "Chưa gán vị trí";
	const areaKey = `${building.toLowerCase()}::${floorLabel.toLowerCase()}`;
	return {
		building,
		floorOrder,
		floorLabel,
		areaKey,
		areaLabel,
	};
}

export function resolveFloorFilterKey(cam) {
	const info = resolveCameraFloorInfo(cam);
	if (info.floorOrder < Number.MAX_SAFE_INTEGER) {
		return `ord::${info.floorOrder}`;
	}
	return info.floorLabel ? `lbl::${info.floorLabel.toLowerCase()}` : "unassigned";
}

export function extractBuildingOptions(cameras = []) {
	if (!Array.isArray(cameras) || cameras.length === 0) return [];
	const groups = new Map();
	for (const cam of cameras) {
		const building = resolveCameraBuilding(cam);
		const key = building ? building.toLowerCase() : "unassigned";
		const label = building || "Chưa gán tòa";
		if (!groups.has(key)) {
			groups.set(key, { key, label, count: 0 });
		}
		groups.get(key).count += 1;
	}
	return Array.from(groups.values()).sort((a, b) =>
		a.label.localeCompare(b.label, "vi", { numeric: true, sensitivity: "base" }),
	);
}

export function extractFloorOptions(cameras = [], buildingKey = "ALL") {
	if (!Array.isArray(cameras) || cameras.length === 0) return [];
	const groups = new Map();
	for (const cam of cameras) {
		if (buildingKey && buildingKey !== "ALL") {
			const bKey = resolveCameraBuilding(cam).toLowerCase() || "unassigned";
			if (bKey !== buildingKey) continue;
		}
		const info = resolveCameraFloorInfo(cam);
		const key = resolveFloorFilterKey(cam);
		const shortCode = formatShortFloorCode(cam);
		const label = info.floorLabel || (shortCode ? `Tầng ${shortCode}` : "Chưa gán tầng");
		if (!groups.has(key)) {
			groups.set(key, {
				key,
				label,
				shortCode,
				floorOrder: info.floorOrder,
				count: 0,
			});
		}
		groups.get(key).count += 1;
	}
	return Array.from(groups.values()).sort((a, b) => {
		if (a.floorOrder !== b.floorOrder) return a.floorOrder - b.floorOrder;
		return a.label.localeCompare(b.label, "vi", { numeric: true, sensitivity: "base" });
	});
}

export function isCorridorCamera(cam) {
	if (!cam || typeof cam !== "object") return false;
	if (String(cam.cameraType || "").trim().toUpperCase() === "CORRIDOR") {
		return true;
	}
	const areaName = String(cam.areaName || "").trim();
	const rawName = String(cam.name || "").trim();
	return !areaName && /^hành\s*lang\b/i.test(rawName);
}

export function extractAreaNameOptions(cameras = [], buildingKey = "ALL", floorKey = "ALL") {
	if (!Array.isArray(cameras) || cameras.length === 0) return [];
	const groups = new Map();
	for (const cam of cameras) {
		if (buildingKey && buildingKey !== "ALL") {
			const bKey = resolveCameraBuilding(cam).toLowerCase() || "unassigned";
			if (bKey !== buildingKey) continue;
		}
		if (floorKey && floorKey !== "ALL" && resolveFloorFilterKey(cam) !== floorKey) {
			continue;
		}
		const corridor = isCorridorCamera(cam);
		const label = corridor ? "Hành lang" : formatCameraRoomName(cam);
		const key = corridor ? "__corridor__" : label.toLowerCase();
		if (!groups.has(key)) {
			groups.set(key, { key, label, count: 0 });
		}
		groups.get(key).count += 1;
	}
	return Array.from(groups.values()).sort((a, b) =>
		a.label.localeCompare(b.label, "vi", { numeric: true, sensitivity: "base" }),
	);
}

export function filterCamerasByHierarchy(
	cameras = [],
	{ buildingKey = "ALL", floorKey = "ALL", areaKey = "ALL" } = {},
) {
	if (!Array.isArray(cameras)) return [];
	return cameras.filter((cam) => {
		if (buildingKey && buildingKey !== "ALL") {
			const bKey = resolveCameraBuilding(cam).toLowerCase() || "unassigned";
			if (bKey !== buildingKey) return false;
		}
		if (floorKey && floorKey !== "ALL" && resolveFloorFilterKey(cam) !== floorKey) {
			return false;
		}
		if (areaKey && areaKey !== "ALL") {
			if (areaKey === "__corridor__") {
				return isCorridorCamera(cam);
			}
			if (isCorridorCamera(cam)) return false;
			const aKey = formatCameraRoomName(cam).toLowerCase();
			if (aKey !== areaKey) return false;
		}
		return true;
	});
}

export function extractAreaFilterOptions(cameras = []) {
	if (!Array.isArray(cameras) || cameras.length === 0) return [];
	const groups = new Map();
	for (const cam of cameras) {
		const info = resolveCameraFloorInfo(cam);
		if (!groups.has(info.areaKey)) {
			groups.set(info.areaKey, {
				key: info.areaKey,
				label: info.areaLabel,
				building: info.building,
				floorOrder: info.floorOrder,
				floorLabel: info.floorLabel,
				count: 0,
			});
		}
		groups.get(info.areaKey).count += 1;
	}
	return Array.from(groups.values()).sort((a, b) => {
		const bCmp = (a.building || "zzz").localeCompare(b.building || "zzz", "vi", {
			numeric: true,
			sensitivity: "base",
		});
		if (bCmp !== 0) return bCmp;
		if (a.floorOrder !== b.floorOrder) return a.floorOrder - b.floorOrder;
		return a.label.localeCompare(b.label, "vi", { numeric: true, sensitivity: "base" });
	});
}

export function filterCamerasByAreaKey(cameras = [], areaKey = "ALL") {
	if (!Array.isArray(cameras)) return [];
	if (!areaKey || areaKey === "ALL") return cameras;
	return cameras.filter((cam) => resolveCameraFloorInfo(cam).areaKey === areaKey);
}

export function sortCamerasStableByLocation(cameras = []) {
	if (!Array.isArray(cameras)) return [];
	return [...cameras].sort((a, b) => {
		const infoA = resolveCameraFloorInfo(a);
		const infoB = resolveCameraFloorInfo(b);
		const bCmp = (infoA.building || "zzz").localeCompare(infoB.building || "zzz", "vi", {
			numeric: true,
			sensitivity: "base",
		});
		if (bCmp !== 0) return bCmp;
		if (infoA.floorOrder !== infoB.floorOrder) return infoA.floorOrder - infoB.floorOrder;
		return (a.cameraCode || a.name || "").localeCompare(b.cameraCode || b.name || "", "vi", {
			numeric: true,
			sensitivity: "base",
		});
	});
}

export function calculateGeoDistanceMeters(lat1, lon1, lat2, lon2) {
	const aLat = Number(lat1);
	const aLon = Number(lon1);
	const bLat = Number(lat2);
	const bLon = Number(lon2);
	if (
		!Number.isFinite(aLat) ||
		!Number.isFinite(aLon) ||
		!Number.isFinite(bLat) ||
		!Number.isFinite(bLon) ||
		(aLat === 0 && aLon === 0) ||
		(bLat === 0 && bLon === 0)
	) {
		return null;
	}
	const toRad = (deg) => (deg * Math.PI) / 180;
	const R = 6371000;
	const dLat = toRad(bLat - aLat);
	const dLon = toRad(bLon - aLon);
	const h =
		Math.sin(dLat / 2) ** 2 +
		Math.cos(toRad(aLat)) * Math.cos(toRad(bLat)) * Math.sin(dLon / 2) ** 2;
	return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

export function findAdjacentCameras(targetCamera, allCameras = [], limit = 4, options = {}) {
	if (!targetCamera || !Array.isArray(allCameras)) return [];
	const sameFloorOnly = Boolean(options?.sameFloorOnly);
	const targetCode = targetCamera.code || targetCamera.cameraCode;
	const targetInfo = resolveCameraFloorInfo(targetCamera);

	const candidates = allCameras
		.filter((cam) => cam && (cam.code || cam.cameraCode) !== targetCode)
		.map((cam) => {
			const info = resolveCameraFloorInfo(cam);
			const sameBuilding =
				(!targetInfo.building && !info.building) ||
				(Boolean(targetInfo.building && info.building) &&
					targetInfo.building.toLowerCase() === info.building.toLowerCase());
			const sameFloor =
				sameBuilding &&
				((Number.isFinite(targetInfo.floorOrder) &&
					targetInfo.floorOrder < Number.MAX_SAFE_INTEGER &&
					targetInfo.floorOrder === info.floorOrder) ||
					(Boolean(targetInfo.floorLabel && info.floorLabel) &&
						targetInfo.floorLabel.toLowerCase() === info.floorLabel.toLowerCase()));
			const dist = calculateGeoDistanceMeters(
				targetCamera.mapLatitude,
				targetCamera.mapLongitude,
				cam.mapLatitude,
				cam.mapLongitude,
			);
			const floorDiff =
				Number.isFinite(targetInfo.floorOrder) &&
				targetInfo.floorOrder < Number.MAX_SAFE_INTEGER &&
				Number.isFinite(info.floorOrder) &&
				info.floorOrder < Number.MAX_SAFE_INTEGER
					? Math.abs(targetInfo.floorOrder - info.floorOrder)
					: 99;

			// Tier 0: cùng tầng & cùng tòa; Tier 1: cùng tòa khác tầng; Tier 2: tòa khác
			const tier = sameFloor ? 0 : sameBuilding ? 1 : 2;
			const distanceMeters = dist != null ? Math.round(dist) : null;
			const relationLabel = sameFloor
				? distanceMeters != null && distanceMeters <= 500
					? `${distanceMeters}m`
					: "Cùng tầng"
				: info.floorLabel || info.areaLabel;

			return {
				camera: cam,
				tier,
				floorDiff,
				distanceMeters,
				sameFloor,
				sameBuilding,
				relationLabel,
			};
		})
		.filter((entry) => {
			if (sameFloorOnly) return entry.sameFloor;
			return entry.tier <= 1 || !targetInfo.building;
		});

	candidates.sort((a, b) => {
		if (a.tier !== b.tier) return a.tier - b.tier;
		if (a.tier === 1 && a.floorDiff !== b.floorDiff) return a.floorDiff - b.floorDiff;
		if (a.distanceMeters != null && b.distanceMeters != null && a.distanceMeters !== b.distanceMeters) {
			return a.distanceMeters - b.distanceMeters;
		}
		if (a.distanceMeters != null && b.distanceMeters == null) return -1;
		if (a.distanceMeters == null && b.distanceMeters != null) return 1;
		return formatCameraChipLabel(a.camera).localeCompare(formatCameraChipLabel(b.camera), "vi", {
			numeric: true,
			sensitivity: "base",
		});
	});

	return candidates.slice(0, Math.max(0, limit));
}

export function resolveGridConfig(layoutMode, totalCameras = 0) {
	const count = Math.max(0, Number(totalCameras) || 0);
	if (layoutMode === "spotlight") {
		return { mode: "spotlight", cols: 3, rows: 3, pageSize: 6, totalSlots: 6 };
	}
	if (layoutMode === "auto") {
		if (count <= 1) return { mode: "auto", cols: 1, rows: 1, pageSize: 1, totalSlots: 1 };
		if (count === 2) return { mode: "auto", cols: 2, rows: 1, pageSize: 2, totalSlots: 2 };
		if (count <= 4) return { mode: "auto", cols: 2, rows: 2, pageSize: 4, totalSlots: 4 };
		if (count <= 6) return { mode: "auto", cols: 3, rows: 2, pageSize: 6, totalSlots: 6 };
		if (count <= 9) return { mode: "auto", cols: 3, rows: 3, pageSize: 9, totalSlots: 9 };
		return { mode: "auto", cols: 4, rows: 4, pageSize: 16, totalSlots: 16 };
	}
	const numeric = Number(layoutMode);
	if (numeric === 4) return { mode: "4", cols: 2, rows: 2, pageSize: 4, totalSlots: 4 };
	if (numeric === 9) return { mode: "9", cols: 3, rows: 3, pageSize: 9, totalSlots: 9 };
	if (numeric === 16) return { mode: "16", cols: 4, rows: 4, pageSize: 16, totalSlots: 16 };
	return { mode: "1", cols: 1, rows: 1, pageSize: 1, totalSlots: 1 };
}

export function computeVisibleGridSlice(
	sortedCameras = [],
	layoutMode = 1,
	selectedCameraCode = "",
	pageIndex = 0,
	options = {},
) {
	const list = Array.isArray(sortedCameras) ? sortedCameras.filter(Boolean) : [];
	const config = resolveGridConfig(layoutMode, list.length);
	const adjacentPriorityCodes = Array.isArray(options?.adjacentPriorityCodes)
		? options.adjacentPriorityCodes
		: null;
	const pinnedSet = new Set(
		Array.isArray(options?.pinnedCodes) ? options.pinnedCodes : [],
	);

	if (list.length === 0) {
		return {
			config,
			visibleCameras: [],
			emptySlotCount: 0,
			totalPages: 1,
			currentPage: 0,
		};
	}

	if (config.mode === "1") {
		const idx = list.findIndex((c) => c.code === selectedCameraCode);
		const safeIdx = idx >= 0 ? idx : 0;
		const totalPages = list.length;
		const currentPage = Math.min(Math.max(0, pageIndex), totalPages - 1);
		const picked = idx >= 0 ? list[safeIdx] : list[currentPage];
		return {
			config,
			visibleCameras: picked ? [picked] : [],
			emptySlotCount: 0,
			totalPages,
			currentPage: idx >= 0 ? safeIdx : currentPage,
		};
	}

	if (config.mode === "spotlight") {
		const spotlightCam =
			list.find((c) => c.code === selectedCameraCode) || list[0];
		let others = list.filter((c) => c.code !== spotlightCam.code);
		const priorityMap =
			adjacentPriorityCodes && adjacentPriorityCodes.length > 0
				? new Map(adjacentPriorityCodes.map((code, idx) => [code, idx]))
				: null;
		if (pinnedSet.size > 0 || priorityMap) {
			others = [...others].sort((a, b) => {
				const aPinned = pinnedSet.has(a.code) ? 0 : 1;
				const bPinned = pinnedSet.has(b.code) ? 0 : 1;
				if (aPinned !== bPinned) return aPinned - bPinned;
				if (priorityMap) {
					const pa = priorityMap.has(a.code)
						? priorityMap.get(a.code)
						: Number.MAX_SAFE_INTEGER;
					const pb = priorityMap.has(b.code)
						? priorityMap.get(b.code)
						: Number.MAX_SAFE_INTEGER;
					return pa - pb;
				}
				return 0;
			});
		}
		const totalPages = Math.max(1, Math.ceil(others.length / 5));
		const currentPage = Math.min(Math.max(0, pageIndex), totalPages - 1);
		const start = currentPage * 5;
		const visibleCameras = [spotlightCam, ...others.slice(start, start + 5)];
		const emptySlotCount = Math.max(0, config.totalSlots - visibleCameras.length);
		return {
			config,
			visibleCameras,
			emptySlotCount,
			totalPages,
			currentPage,
		};
	}

	const orderedList =
		pinnedSet.size > 0
			? [
					...list.filter((c) => pinnedSet.has(c.code)),
					...list.filter((c) => !pinnedSet.has(c.code)),
				]
			: list;
	const totalPages = Math.max(1, Math.ceil(orderedList.length / config.pageSize));
	const currentPage = Math.min(Math.max(0, pageIndex), totalPages - 1);
	const start = currentPage * config.pageSize;
	const visibleCameras = orderedList.slice(start, start + config.pageSize);
	const emptySlotCount = Math.max(0, config.totalSlots - visibleCameras.length);
	return {
		config,
		visibleCameras,
		emptySlotCount,
		totalPages,
		currentPage,
	};
}
