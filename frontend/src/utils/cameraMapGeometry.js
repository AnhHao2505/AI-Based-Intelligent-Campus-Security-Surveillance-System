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
