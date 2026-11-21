import {
	FLOOR_ELEVATION_STEP,
	FLOOR_SLAB_HEIGHT,
	ROOM_EXTRUSION_HEIGHT,
	getPolygonInteriorPoint,
	isPointInPolygonWithHoles,
	matchesBuilding,
	resolveFloorOrder,
} from "../data/campusIndoor3dData.js";

const CAMERA_BADGE_SPRITE_PREFIX = "cam-badge::";

export const CAMERA_TYPE_OPTIONS = [
	{
		value: "RESTRICTED_AREA",
		label: "Phòng hạn chế",
		aiModeLabel: "AI 24/7",
		badgeLabel: "Phòng hạn chế · AI 24/7",
		description: "Giám sát khu vực Cấp 2 (Bảo mật) & Cấp 3 (Bảo mật cao) 24/7",
		requiresArea: true,
	},
	{
		value: "PUBLIC_AREA",
		label: "Phòng công cộng",
		aiModeLabel: "AI giờ giới nghiêm",
		badgeLabel: "Khu vực công cộng · AI giờ giới nghiêm",
		description:
			"Giám sát khu vực Cấp 1 (Công khai), chỉ bật AI sau giờ làm việc",
		requiresArea: true,
	},
	{
		value: "CORRIDOR",
		label: "Hành lang",
		aiModeLabel: "AI giờ giới nghiêm",
		badgeLabel: "Hành lang · AI giờ giới nghiêm",
		description:
			"Đặt trên hành lang tòa nhà (không gán phòng), chỉ bật AI sau giờ làm việc",
		requiresArea: false,
	},
];

export function resolveCameraTypeConfig(cameraType) {
	const code = String(cameraType || "RESTRICTED_AREA").toUpperCase();
	return (
		CAMERA_TYPE_OPTIONS.find((opt) => opt.value === code) ||
		CAMERA_TYPE_OPTIONS[0]
	);
}

/**
 * Filters available areas according to the selected cameraType:
 * - RESTRICTED_AREA -> CONFIDENTIAL, HIGHLY_CONFIDENTIAL (Level 2 & 3)
 * - PUBLIC_AREA -> PUBLIC (Level 1)
 * - CORRIDOR -> [] (does not assign an Area)
 */
export function filterAreasForCameraType(
	areas = [],
	cameraType = "RESTRICTED_AREA",
) {
	if (!Array.isArray(areas)) return [];
	const type = String(cameraType || "RESTRICTED_AREA").toUpperCase();
	if (type === "CORRIDOR") return [];
	return areas.filter((a) => {
		if (!a) return false;
		const rawLevel =
			typeof a.areaLevel === "string"
				? a.areaLevel
				: a.areaLevel?.code || a.level?.code || a.level || "";
		const code = String(rawLevel).toUpperCase();
		const accessLvl = Number(a.areaAccessLevel ?? a.accessLevel ?? 0);
		const isPublic = code === "PUBLIC" || (!code && accessLvl === 1);
		if (type === "PUBLIC_AREA") {
			return isPublic;
		}
		return !isPublic;
	});
}

/**
 * Frontend helper ensuring CORRIDOR camera names created on UI always have the "Hành lang " prefix.
 */
export function normalizeCorridorCameraName(rawName = "") {
	const trimmed = String(rawName || "").trim();
	if (!trimmed) return "";
	if (/^hành\s+lang\b/i.test(trimmed)) {
		const suffix = trimmed.replace(/^hành\s+lang\s*/i, "").trim();
		return suffix ? `Hành lang ${suffix}` : "Hành lang";
	}
	return `Hành lang ${trimmed}`;
}

export function stripCorridorPrefix(rawName = "") {
	return String(rawName || "")
		.replace(/^hành\s+lang\s*/i, "")
		.trim();
}

/**
 * Extracts normalized area assignment metadata from heterogeneous backend camera DTOs.
 */
export function extractCameraAreaInfo(cam) {
	if (!cam || typeof cam !== "object") {
		return {
			areaId: null,
			areaName: null,
			building: null,
			floor: null,
		};
	}
	const primaryArea =
		cam.assignedArea ||
		(Array.isArray(cam.assignedAreas) && cam.assignedAreas.length > 0
			? cam.assignedAreas[0]
			: null);

	const fallbackFloor =
		cam.floor ||
		primaryArea?.floor ||
		(cam.mapFloorOrder != null && Number.isFinite(Number(cam.mapFloorOrder))
			? Number(cam.mapFloorOrder) === 0
				? "Tầng Trệt"
				: `Tầng ${Number(cam.mapFloorOrder)}`
			: null);

	return {
		areaId: cam.areaId || primaryArea?.id || null,
		areaName: cam.areaName || primaryArea?.name || null,
		building: cam.building || primaryArea?.building || cam.mapBuilding || null,
		floor: fallbackFloor,
	};
}

/**
 * Aggregates per-area camera statistics (total, active, online, offline, decommissioned).
 */
export function buildCameraStatsByArea(cameras = []) {
	const statsByArea = {};
	const countsByArea = {};
	if (!Array.isArray(cameras)) {
		return { statsByArea, countsByArea };
	}

	for (const cam of cameras) {
		if (!cam || typeof cam !== "object") continue;
		const { areaId } = extractCameraAreaInfo(cam);
		if (!areaId) continue;

		if (!statsByArea[areaId]) {
			statsByArea[areaId] = {
				total: 0,
				activeCount: 0,
				onlineCount: 0,
				offlineCount: 0,
				decommissionedCount: 0,
				cameras: [],
			};
		}

		const bucket = statsByArea[areaId];
		bucket.total += 1;
		countsByArea[areaId] = bucket.total;
		bucket.cameras.push(cam);

		const isDecommissioned = cam.status === "DECOMMISSIONED";
		if (isDecommissioned) {
			bucket.decommissionedCount += 1;
		} else {
			bucket.activeCount += 1;
			if (cam.operationalStatus === "ONLINE") {
				bucket.onlineCount += 1;
			} else {
				bucket.offlineCount += 1;
			}
		}
	}

	return { statsByArea, countsByArea };
}

/**
 * Filters and sorts cameras for the unified Campus + Camera workspace sidebar.
 */
export function filterWorkspaceCameras({
	cameras = [],
	areas = [],
	search = "",
	statusFilter = "ALL",
	opStatusFilter = "ALL",
	selectedBuilding = "ALL",
	selectedFloor = "ALL",
	scopeToMapFilter = false,
} = {}) {
	if (!Array.isArray(cameras)) return [];

	const areaById = new Map();
	if (Array.isArray(areas)) {
		for (const area of areas) {
			if (area?.id) areaById.set(area.id, area);
		}
	}

	const q = String(search || "")
		.trim()
		.toLowerCase();

	const matchesFilter = cameras.filter((cam) => {
		if (!cam || typeof cam !== "object") return false;

		if (statusFilter && statusFilter !== "ALL") {
			const status = cam.status || "ACTIVE";
			if (status !== statusFilter) return false;
		}

		if (opStatusFilter && opStatusFilter !== "ALL") {
			const op = cam.operationalStatus || "OFFLINE";
			if (op !== opStatusFilter) return false;
		}

		const info = extractCameraAreaInfo(cam);
		const resolvedArea = info.areaId ? areaById.get(info.areaId) : null;
		const buildingName = resolvedArea?.building || info.building || "";
		const floorName = resolvedArea?.floor || info.floor || "";
		const areaName = resolvedArea?.name || info.areaName || "";

		if (scopeToMapFilter) {
			if (selectedBuilding && selectedBuilding !== "ALL") {
				if (!buildingName || !matchesBuilding(buildingName, selectedBuilding)) {
					return false;
				}
			}
			if (selectedFloor && selectedFloor !== "ALL") {
				if (!floorName) return false;
				const targetOrder = resolveFloorOrder(
					selectedBuilding && selectedBuilding !== "ALL"
						? selectedBuilding
						: buildingName,
					selectedFloor,
				);
				const camFloorOrder = resolveFloorOrder(buildingName, floorName);
				if (targetOrder !== null && camFloorOrder !== null) {
					if (targetOrder !== camFloorOrder) return false;
				} else if (floorName !== selectedFloor) {
					return false;
				}
			}
		}

		if (q) {
			const haystack = [
				cam.name,
				cam.cameraCode,
				cam.code,
				cam.ipAddress,
				areaName,
				buildingName,
				floorName,
			]
				.filter(Boolean)
				.join(" ")
				.toLowerCase();
			if (!haystack.includes(q)) return false;
		}

		return true;
	});

	return [...matchesFilter].sort((a, b) => {
		const codeCmp = String(a.cameraCode || "").localeCompare(
			String(b.cameraCode || ""),
			"en",
		);
		if (codeCmp !== 0) return codeCmp;
		return String(a.name || "").localeCompare(String(b.name || ""), "vi");
	});
}

/**
 * Toggles collapse state for a security level section in AreaListView immutably.
 */
export function toggleCollapsedSectionMap(prevMap, sectionKey) {
	const base = prevMap && typeof prevMap === "object" ? { ...prevMap } : {};
	if (!sectionKey || typeof sectionKey !== "string") return base;
	base[sectionKey] = !base[sectionKey];
	return base;
}

/**
 * Deterministic MapLibre sprite id for compact 3D room camera badges.
 */
export function buildCameraBadgeSpriteId({
	total = 0,
	onlineCount = 0,
	offlineCount = 0,
	isSelected = false,
} = {}) {
	const statusKey =
		offlineCount > 0 ? "warn" : onlineCount > 0 ? "ok" : "muted";
	const selFlag = isSelected ? "1" : "0";
	return `${CAMERA_BADGE_SPRITE_PREFIX}${statusKey}:${selFlag}:${Number(total) || 0}`;
}

/**
 * Builds floor-elevated GeoJSON Point features showing camera counts & health per Area on 3D floors.
 */
export function buildCameraMapMarkersGeoJSON({
	cameras = [],
	areas = [],
	store = null,
	selectedBuilding = "ALL",
	activeFloorOrder = "ALL",
	selectedAreaId = null,
	selectedCameraId = null,
	floorElevationStep = FLOOR_ELEVATION_STEP,
} = {}) {
	const empty = { type: "FeatureCollection", features: [] };
	if (
		!Array.isArray(areas) ||
		!Array.isArray(cameras) ||
		cameras.length === 0
	) {
		return empty;
	}

	const { statsByArea } = buildCameraStatsByArea(cameras);
	const roomPolygons = Array.isArray(store?.roomPolygons)
		? store.roomPolygons
		: [];
	const step = Number.isFinite(Number(floorElevationStep))
		? Number(floorElevationStep)
		: FLOOR_ELEVATION_STEP;

	const features = [];
	for (const area of areas) {
		if (!area?.id) continue;
		const stats = statsByArea[area.id];
		if (!stats || stats.total === 0) continue;

		if (
			selectedBuilding &&
			selectedBuilding !== "ALL" &&
			!matchesBuilding(area.building, selectedBuilding)
		) {
			continue;
		}

		const areaFloorOrder = resolveFloorOrder(area.building, area.floor);
		if (
			activeFloorOrder !== "ALL" &&
			activeFloorOrder !== null &&
			activeFloorOrder !== undefined
		) {
			if (areaFloorOrder !== Number(activeFloorOrder)) continue;
		}

		const lat = Number(area.centerLatitude);
		const lng = Number(area.centerLongitude);
		if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;

		let anchorLng = lng;
		let anchorLat = lat;
		const matchedRoom = roomPolygons.find(
			(room) =>
				matchesBuilding(room.building, area.building) &&
				Number(room.floorOrder) === areaFloorOrder &&
				Array.isArray(room.ring) &&
				isPointInPolygonWithHoles([lng, lat], room.ring, room.holes || []),
		);
		if (matchedRoom?.ring) {
			const interior = getPolygonInteriorPoint(
				matchedRoom.ring,
				matchedRoom.holes || [],
			);
			if (Array.isArray(interior) && interior.length >= 2) {
				anchorLng = interior[0];
				anchorLat = interior[1];
			}
		}

		// Slight southward offset only if it remains inside the matched room polygon
		const candidateLat = anchorLat - 0.000014;
		const staysInsideRoom =
			!matchedRoom?.ring ||
			isPointInPolygonWithHoles(
				[anchorLng, candidateLat],
				matchedRoom.ring,
				matchedRoom.holes || [],
			);
		const badgeLng = anchorLng;
		const badgeLat = staysInsideRoom ? candidateLat : anchorLat;
		const containsSelectedCamera = Boolean(
			selectedCameraId &&
			stats.cameras.some((cam) => cam.id === selectedCameraId),
		);
		const isSelected = selectedAreaId === area.id || containsSelectedCamera;
		const hasOffline = stats.offlineCount > 0;
		const resolvedOrder = areaFloorOrder ?? 0;
		const heightOffset = Number(
			(
				resolvedOrder * step +
				FLOOR_SLAB_HEIGHT +
				ROOM_EXTRUSION_HEIGHT +
				0.32
			).toFixed(2),
		);

		features.push({
			type: "Feature",
			geometry: {
				type: "Point",
				coordinates: [badgeLng, badgeLat],
			},
			properties: {
				areaId: area.id,
				areaName: area.name || "",
				building: area.building || "",
				floor: area.floor || "",
				floorOrder: resolvedOrder,
				total: stats.total,
				onlineCount: stats.onlineCount,
				offlineCount: stats.offlineCount,
				hasOffline,
				isSelected,
				heightOffset,
				spriteId: buildCameraBadgeSpriteId({
					total: stats.total,
					onlineCount: stats.onlineCount,
					offlineCount: stats.offlineCount,
					isSelected,
				}),
			},
		});
	}

	return {
		type: "FeatureCollection",
		features,
	};
}

/**
 * Fetch all pages from a Spring Boot Pageable endpoint sequentially while respecting
 * backend page-size limits (e.g., AreaController caps size at 100).
 */
export async function fetchAllPaginatedPages(
	fetchPage,
	{ pageSize = 100, maxPages = 50 } = {},
) {
	if (typeof fetchPage !== "function") {
		throw new TypeError("fetchPage must be a function");
	}
	const safeSize =
		Number.isFinite(pageSize) && pageSize > 0 ? Math.floor(pageSize) : 100;
	const safeMaxPages =
		Number.isFinite(maxPages) && maxPages > 0 ? Math.floor(maxPages) : 50;

	const allItems = [];
	for (let page = 0; page < safeMaxPages; page++) {
		const res = await fetchPage(page, safeSize);
		const items = Array.isArray(res)
			? res
			: Array.isArray(res?.content)
				? res.content
				: Array.isArray(res?.areas)
					? res.areas
					: [];

		allItems.push(...items);

		const totalPages = Number(res?.totalPages);
		if (res?.last === true) break;
		if (Number.isFinite(totalPages) && totalPages >= 0) {
			if (page + 1 >= totalPages) break;
		} else if (items.length < safeSize) {
			break;
		}
		if (items.length === 0) break;
	}

	return allItems;
}

/**
 * Kiểm soát quyền truy cập tab Camera & CameraInspectorDrawer theo vai trò.
 * Chỉ ADMIN mới được mở tab Camera và CameraInspectorDrawer.
 */
export function resolveCameraInspectorAccess({
	isAdmin,
	requestedTab,
	requestedCameraId,
}) {
	if (!isAdmin) {
		return {
			showCameraTab: false,
			sidebarMode: "areas",
			selectedCameraId: null,
		};
	}
	return {
		showCameraTab: true,
		sidebarMode: requestedTab === "cameras" ? "cameras" : "areas",
		selectedCameraId: requestedCameraId || null,
	};
}

/**
 * Đồng bộ trạng thái vận hành (operationalStatus, status, name) của một camera
 * vào danh sách cameras trong state mà không cần refetch toàn bộ danh sách.
 */
export function mergeCameraStatusUpdate(cameras, updatedCamera) {
	if (!Array.isArray(cameras) || !updatedCamera?.id) {
		return cameras;
	}
	const idx = cameras.findIndex((c) => c && c.id === updatedCamera.id);
	if (idx === -1) {
		return cameras;
	}
	const current = cameras[idx];
	const nextOp = updatedCamera.operationalStatus ?? current.operationalStatus;
	const nextStatus = updatedCamera.status ?? current.status;
	const nextName = updatedCamera.name ?? current.name;

	if (
		current.operationalStatus === nextOp &&
		current.status === nextStatus &&
		current.name === nextName
	) {
		return cameras;
	}

	const next = cameras.slice();
	next[idx] = {
		...current,
		operationalStatus: nextOp,
		status: nextStatus,
		name: nextName,
	};
	return next;
}

/**
 * Đồng bộ operationalStatus / status mới nhất từ component cha xuống state chi tiết của Drawer.
 * Trả về chính reference `camera` nếu không có thay đổi để tránh re-render thừa.
 */
export function applyLiveCameraStatus(
	camera,
	{ operationalStatus, status } = {},
) {
	if (!camera) return camera;
	const nextOp = operationalStatus || camera.operationalStatus;
	const nextStatus = status || camera.status;
	if (camera.operationalStatus === nextOp && camera.status === nextStatus) {
		return camera;
	}
	return {
		...camera,
		operationalStatus: nextOp,
		status: nextStatus,
	};
}

/**
 * Kiểm tra xem cấu hình RTSP hiện tại trên form có thay đổi so với cấu hình đã lưu trong DB hay không.
 */
export function isStreamConfigDirty(currentForm, savedConfig) {
	if (!currentForm) return false;
	const curHost = (currentForm.host || "").trim();
	const curPort =
		currentForm.port !== "" &&
		currentForm.port !== undefined &&
		currentForm.port !== null
			? String(currentForm.port).trim()
			: "554";
	const curUsername = (currentForm.username || "").trim();
	const curPath = (currentForm.mainStreamPath || "").trim();
	const hasNewPassword = Boolean(currentForm.credentialRef?.trim());

	if (!savedConfig) {
		return Boolean(
			curHost ||
			(currentForm.port && String(currentForm.port).trim() !== "554") ||
			curUsername ||
			hasNewPassword ||
			curPath,
		);
	}

	const origHost = (savedConfig.host || "").trim();
	const origPort =
		savedConfig.port !== null && savedConfig.port !== undefined
			? String(savedConfig.port).trim()
			: "554";
	const origUsername = (savedConfig.username || "").trim();
	const origPath = (savedConfig.mainStreamPath || "").trim();

	return (
		curHost !== origHost ||
		curPort !== origPort ||
		curUsername !== origUsername ||
		curPath !== origPath ||
		hasNewPassword
	);
}
