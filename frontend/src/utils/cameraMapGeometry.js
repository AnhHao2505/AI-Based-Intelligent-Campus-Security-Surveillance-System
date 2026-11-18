import {
	isPointInPolygonWithHoles,
	matchesBuilding,
	resolveFloorOrder,
} from "../data/campusIndoor3dData.js";

export const CAMERA_PIN_DRAG_THRESHOLD_PX = 5;

export function resolveCameraPinStatusKey(camera) {
	if (!camera) return "muted";
	if (camera.status === "DECOMMISSIONED") return "muted";
	return camera.operationalStatus === "ONLINE" ? "ok" : "warn";
}

function normalizeFloorMatch(a, b) {
	if (a == null || b == null) return true;
	return Number(a) === Number(b);
}

function normalizeBuildingKey(val) {
	if (!val) return "";
	return String(val)
		.trim()
		.toLowerCase()
		.normalize("NFD")
		.replace(/[\u0300-\u036f]/g, "")
		.replace(/^toa\s+/, "")
		.trim();
}

function normalizeBuildingMatch(a, b) {
	if (!a || !b) return true;
	return normalizeBuildingKey(a) === normalizeBuildingKey(b);
}

/**
 * Projects a MapLibre mouse/click event onto the horizontal plane at `floorHeightMeters`
 * above z=0, eliminating 3D pitch parallax offset on upper floors.
 */
export function projectEventToFloorLngLat(
	map,
	event,
	floorHeightMeters = 0,
	MercatorCoordinateClass,
) {
	if (!event?.lngLat) return null;
	const fallback = { lng: event.lngLat.lng, lat: event.lngLat.lat };
	const zMeters = Number(floorHeightMeters);
	if (!map || !Number.isFinite(zMeters) || Math.abs(zMeters) < 1e-4) {
		return fallback;
	}

	// MapLibre GL v6+ casts exact pixel ray onto horizontal elevation plane (matches symbol-height-offset)
	if (
		typeof map.transform?.screenPointToLocationAtElevation === "function" &&
		event?.point
	) {
		const atElevation = map.transform.screenPointToLocationAtElevation(
			event.point,
			zMeters,
		);
		if (
			atElevation &&
			Number.isFinite(atElevation.lng) &&
			Number.isFinite(atElevation.lat)
		) {
			return { lng: atElevation.lng, lat: atElevation.lat };
		}
	}

	if (
		!MercatorCoordinateClass?.fromLngLat ||
		typeof map.getFreeCameraOptions !== "function"
	) {
		return fallback;
	}

	const cameraPos = map.getFreeCameraOptions()?.position;
	if (
		!cameraPos ||
		!Number.isFinite(cameraPos.z) ||
		Math.abs(cameraPos.z) < 1e-12
	) {
		return fallback;
	}

	const groundMerc = MercatorCoordinateClass.fromLngLat(event.lngLat, 0);
	const targetMerc = MercatorCoordinateClass.fromLngLat(event.lngLat, zMeters);
	const targetZ = targetMerc.z;
	const cz = cameraPos.z;
	// Ray from cameraPos (cx, cy, cz) through groundMerc (gx, gy, 0):
	// z(t) = cz + t * (0 - cz) = targetZ => t = (cz - targetZ) / cz
	const t = (cz - targetZ) / cz;
	if (!Number.isFinite(t) || t <= 0 || t > 2) {
		return fallback;
	}

	const ix = cameraPos.x + t * (groundMerc.x - cameraPos.x);
	const iy = cameraPos.y + t * (groundMerc.y - cameraPos.y);
	const projected = new MercatorCoordinateClass(ix, iy, targetZ).toLngLat();
	return projected &&
		Number.isFinite(projected.lng) &&
		Number.isFinite(projected.lat)
		? { lng: projected.lng, lat: projected.lat }
		: fallback;
}

/**
 * Extracts a normalized { lng, lat, building, floorOrder } placement from a camera API DTO.
 */
export function extractCameraMapPlacement(cam) {
	if (!cam) return null;
	const lng = cam.mapLongitude;
	const lat = cam.mapLatitude;
	if (
		lng == null ||
		lat == null ||
		!Number.isFinite(Number(lng)) ||
		!Number.isFinite(Number(lat))
	) {
		return null;
	}
	return {
		cameraId: cam.id,
		lng: Number(lng),
		lat: Number(lat),
		building: cam.mapBuilding ?? cam.building ?? null,
		floorOrder:
			cam.mapFloorOrder != null && Number.isFinite(Number(cam.mapFloorOrder))
				? Number(cam.mapFloorOrder)
				: null,
	};
}

/**
 * Builds a cameraId -> placement map from API cameras merged with any optimistic drag overrides.
 */
export function buildCameraPlacementsMap(cameras = [], optimisticById = {}) {
	const result = {};
	for (const cam of cameras) {
		if (!cam?.id || cam.deletedAt) continue;
		const override = optimisticById?.[cam.id];
		if (override === null) continue;
		if (override) {
			result[cam.id] = override;
			continue;
		}
		const apiPlacement = extractCameraMapPlacement(cam);
		if (apiPlacement) {
			result[cam.id] = apiPlacement;
		}
	}
	return result;
}

/**
 * Builds GeoJSON pin features for 2D camera icons filtered to the active building & floorOrder.
 * Soft-deleted cameras are excluded.
 */
export function buildCameraMapPinGeoJson({
	cameras = [],
	placementsById = {},
	building = null,
	floorOrder = null,
	selectedCameraId = null,
	floorElevationStep = 3.5,
	floorSlabHeight = 0.16,
}) {
	const pinFeatures = [];
	const step = Number.isFinite(Number(floorElevationStep))
		? Number(floorElevationStep)
		: 3.5;
	const slab = Number.isFinite(Number(floorSlabHeight))
		? Number(floorSlabHeight)
		: 0.16;

	for (const cam of cameras) {
		if (!cam || cam.deletedAt) continue;
		const placement =
			placementsById?.[cam.id] !== undefined
				? placementsById[cam.id]
				: extractCameraMapPlacement(cam);
		if (
			!placement ||
			!Number.isFinite(Number(placement.lng)) ||
			!Number.isFinite(Number(placement.lat))
		) {
			continue;
		}
		if (!normalizeBuildingMatch(placement.building, building)) continue;
		if (!normalizeFloorMatch(placement.floorOrder, floorOrder)) continue;

		const lng = Number(placement.lng);
		const lat = Number(placement.lat);
		const isSelected = Boolean(selectedCameraId && cam.id === selectedCameraId);
		const statusKey = resolveCameraPinStatusKey(cam);
		const resolvedOrder = Number.isFinite(Number(placement.floorOrder))
			? Number(placement.floorOrder)
			: 0;
		const elevationBase = Number(
			(resolvedOrder * step + slab + 0.04).toFixed(2),
		);
		const pinElevation = Number((elevationBase + 0.28).toFixed(2));

		pinFeatures.push({
			type: "Feature",
			geometry: {
				type: "Point",
				coordinates: [lng, lat],
			},
			properties: {
				cameraId: cam.id,
				cameraCode: cam.cameraCode || "",
				cameraName: cam.cameraName || cam.name || "",
				statusKey,
				isSelected: isSelected ? 1 : 0,
				pinElevation,
				spriteId: `cam-pin::${statusKey}:${isSelected ? 1 : 0}`,
			},
		});
	}

	return {
		pinFeatures,
	};
}

/**
 * 2-tier validation for placing or dragging a camera pin on the 3D campus map:
 * - Tier 1 (Strict): building & floorOrder must match the camera's assigned Area.
 * - Tier 2 (Smart 3D boundary):
 *   - Blocks dropping inside a room belonging to a different Area.
 *   - Blocks dropping outside the assigned building/floor footprint.
 *   - Allows dropping inside the assigned room (`target-room`) or in the same floor's
 *     corridor/door buffer (`corridor-buffer`) with a soft advisory warning.
 */
export function validateCameraPinPlacement({
	lng,
	lat,
	targetCam = null,
	targetArea = null,
	activeBuilding = "ALL",
	activeFloorOrder = "ALL",
	clickedRoomProps = null,
	roomFeatures = null,
	store = null,
} = {}) {
	if (!Number.isFinite(Number(lng)) || !Number.isFinite(Number(lat))) {
		return {
			ok: false,
			reason: "invalid-coords",
			message: "Tọa độ bản đồ không hợp lệ.",
		};
	}

	if (targetCam?.status === "DECOMMISSIONED") {
		return {
			ok: false,
			reason: "decommissioned",
			message: "Camera đã ngừng hoạt động, không thể thay đổi vị trí.",
		};
	}

	const expectedBuilding =
		targetArea?.building ||
		targetCam?.building ||
		targetCam?.assignedArea?.building ||
		targetCam?.mapBuilding ||
		null;
	const expectedFloorName =
		targetArea?.floor ||
		targetCam?.floor ||
		targetCam?.assignedArea?.floor ||
		null;
	const expectedFloorOrder =
		expectedBuilding && expectedFloorName
			? resolveFloorOrder(expectedBuilding, expectedFloorName)
			: targetCam?.mapFloorOrder != null &&
				  Number.isFinite(Number(targetCam.mapFloorOrder))
				? Number(targetCam.mapFloorOrder)
				: null;
	const targetAreaId =
		targetArea?.id ||
		targetCam?.areaId ||
		targetCam?.assignedArea?.id ||
		null;

	// Tier 1: Strict building & floor alignment
	if (expectedBuilding) {
		if (
			activeBuilding &&
			activeBuilding !== "ALL" &&
			!matchesBuilding(activeBuilding, expectedBuilding)
		) {
			return {
				ok: false,
				reason: "wrong-building",
				message: `Camera thuộc ${expectedBuilding}${expectedFloorName ? ` · ${expectedFloorName}` : ""}. Vui lòng chuyển về đúng tòa nhà.`,
			};
		}
		if (
			activeFloorOrder !== "ALL" &&
			activeFloorOrder !== null &&
			activeFloorOrder !== undefined &&
			expectedFloorOrder !== null &&
			Number(activeFloorOrder) !== Number(expectedFloorOrder)
		) {
			return {
				ok: false,
				reason: "wrong-floor",
				message: `Camera thuộc ${expectedBuilding}${expectedFloorName ? ` · ${expectedFloorName}` : ""}. Không thể đặt ở tầng khác.`,
			};
		}
	}

	// Tier 2: Check room ownership & indoor building footprint
	let effectiveRoomProps = clickedRoomProps;
	if (!effectiveRoomProps && Array.isArray(roomFeatures) && roomFeatures.length > 0) {
		const pt = [Number(lng), Number(lat)];
		for (const feat of roomFeatures) {
			const props = feat?.properties;
			if (!props || props.isGhost) continue;
			if (
				expectedBuilding &&
				props.building &&
				!matchesBuilding(props.building, expectedBuilding)
			) {
				continue;
			}
			if (
				expectedFloorOrder !== null &&
				props.floorOrder != null &&
				Number(props.floorOrder) !== Number(expectedFloorOrder)
			) {
				continue;
			}
			const coords = feat?.geometry?.coordinates;
			if (!Array.isArray(coords) || coords.length === 0) continue;
			if (isPointInPolygonWithHoles(pt, coords[0], coords.slice(1))) {
				effectiveRoomProps = props;
				break;
			}
		}
	}

	if (effectiveRoomProps?.matchedAreaId) {
		if (
			targetAreaId &&
			String(effectiveRoomProps.matchedAreaId) !== String(targetAreaId)
		) {
			return {
				ok: false,
				reason: "other-area-room",
				message: `Không thể đặt pin vào phòng của khu vực "${effectiveRoomProps.matchedAreaName || "khác"}".`,
			};
		}
		if (
			targetAreaId &&
			String(effectiveRoomProps.matchedAreaId) === String(targetAreaId)
		) {
			return {
				ok: true,
				zone: "target-room",
				building: expectedBuilding,
				floorOrder: expectedFloorOrder,
			};
		}
	}

	if (targetAreaId && (Array.isArray(roomFeatures) || clickedRoomProps || store)) {
		const areaLabel =
			targetArea?.name ||
			targetCam?.areaName ||
			targetCam?.assignedArea?.name ||
			"được gán";
		return {
			ok: false,
			reason: "outside-target-room",
			message: `Chỉ có thể đặt pin trong khuôn khổ phòng "${areaLabel}".`,
		};
	}

	return {
		ok: true,
		zone: "target-room",
		building: expectedBuilding,
		floorOrder: expectedFloorOrder,
	};
}
