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
	draftPin = null,
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

	if (
		draftPin &&
		Number.isFinite(Number(draftPin.lng)) &&
		Number.isFinite(Number(draftPin.lat)) &&
		normalizeBuildingMatch(draftPin.building, building) &&
		normalizeFloorMatch(draftPin.floorOrder, floorOrder)
	) {
		const resolvedOrder = Number.isFinite(Number(draftPin.floorOrder))
			? Number(draftPin.floorOrder)
			: 0;
		const elevationBase = Number(
			(resolvedOrder * step + slab + 0.04).toFixed(2),
		);
		const pinElevation = Number((elevationBase + 0.28).toFixed(2));
		pinFeatures.push({
			type: "Feature",
			geometry: {
				type: "Point",
				coordinates: [Number(draftPin.lng), Number(draftPin.lat)],
			},
			properties: {
				cameraId: "__draft_create_pin__",
				cameraCode: "MỚI",
				cameraName: draftPin.name || "Camera mới",
				statusKey: "ok",
				isSelected: 1,
				pinElevation,
				spriteId: "cam-pin::ok:1",
			},
		});
	}

	return {
		pinFeatures,
	};
}

/**
 * 2-tier validation for placing or dragging a camera pin on the campus map:
 * - Tier 1 (Strict): building & floorOrder must match the camera's assigned Area (or selected Building/Floor for CORRIDOR).
 * - Tier 2 (Boundary by cameraType):
 *   - RESTRICTED_AREA / PUBLIC_AREA: must be inside the assigned room (`target-room`).
 *   - CORRIDOR: must be inside the floor slab / building footprint and OUTSIDE all `ROOM` polygons.
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
	slabFeatures = null,
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

	const cameraType = String(targetCam?.cameraType || "").toUpperCase();
	const isCorridorCamera = cameraType === "CORRIDOR";

	const expectedBuilding = isCorridorCamera
		? targetCam?.mapBuilding ||
		  targetCam?.building ||
		  (activeBuilding && activeBuilding !== "ALL" ? activeBuilding : null)
		: targetArea?.building ||
		  targetCam?.building ||
		  targetCam?.assignedArea?.building ||
		  targetCam?.mapBuilding ||
		  null;
	const expectedFloorName = isCorridorCamera
		? targetCam?.floor || null
		: targetArea?.floor ||
		  targetCam?.floor ||
		  targetCam?.assignedArea?.floor ||
		  null;
	const expectedFloorOrder =
		targetCam?.mapFloorOrder != null &&
		Number.isFinite(Number(targetCam.mapFloorOrder)) &&
		isCorridorCamera
			? Number(targetCam.mapFloorOrder)
			: expectedBuilding && expectedFloorName
				? resolveFloorOrder(expectedBuilding, expectedFloorName)
				: targetCam?.mapFloorOrder != null &&
					  Number.isFinite(Number(targetCam.mapFloorOrder))
					? Number(targetCam.mapFloorOrder)
					: isCorridorCamera &&
						  activeFloorOrder !== "ALL" &&
						  activeFloorOrder != null &&
						  Number.isFinite(Number(activeFloorOrder))
						? Number(activeFloorOrder)
						: null;
	const targetAreaId = isCorridorCamera
		? null
		: targetArea?.id ||
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
				message: `Đang xem tòa nhà ${activeBuilding}, nhưng camera thuộc ${expectedBuilding}${expectedFloorName ? ` · ${expectedFloorName}` : ""}. Vui lòng chuyển sang tòa nhà ${expectedBuilding}.`,
			};
		}
		if (
			activeFloorOrder !== "ALL" &&
			activeFloorOrder !== null &&
			activeFloorOrder !== undefined &&
			expectedFloorOrder !== null &&
			Number(activeFloorOrder) !== Number(expectedFloorOrder)
		) {
			const expectedFloorLabel = expectedFloorName || `Tầng ${expectedFloorOrder}`;
			return {
				ok: false,
				reason: "wrong-floor",
				message: `Đang xem Tầng ${activeFloorOrder}, nhưng camera thuộc ${expectedBuilding} · ${expectedFloorLabel}. Vui lòng chuyển về đúng tầng.`,
			};
		}
	}

	// Tier 2: Check room ownership & indoor building footprint
	let effectiveRoomProps =
		clickedRoomProps &&
		String(clickedRoomProps.role || "ROOM").toUpperCase() === "ROOM"
			? clickedRoomProps
			: null;
	if (!effectiveRoomProps && Array.isArray(roomFeatures) && roomFeatures.length > 0) {
		const pt = [Number(lng), Number(lat)];
		for (const feat of roomFeatures) {
			const props = feat?.properties;
			if (!props || props.isGhost) continue;
			if (String(props.role || "ROOM").toUpperCase() !== "ROOM") continue;
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

	if (isCorridorCamera) {
		if (effectiveRoomProps) {
			const roomLabel =
				effectiveRoomProps.matchedAreaName ||
				effectiveRoomProps.name ||
				effectiveRoomProps.roomName ||
				"";
			return {
				ok: false,
				reason: "inside-room-for-corridor",
				message: `Không thể đặt camera hành lang vào bên trong phòng${roomLabel ? ` "${roomLabel}"` : ""}. Vui lòng đặt trên sàn hành lang (vùng ngoài các phòng).`,
			};
		}
		const pt = [Number(lng), Number(lat)];
		if (Array.isArray(slabFeatures) && slabFeatures.length > 0) {
			let inSlab = false;
			for (const slab of slabFeatures) {
				const sp = slab?.properties;
				if (!sp || sp.isGhost) continue;
				if (
					expectedBuilding &&
					sp.building &&
					!matchesBuilding(sp.building, expectedBuilding)
				) {
					continue;
				}
				if (
					expectedFloorOrder !== null &&
					sp.floorOrder != null &&
					Number(sp.floorOrder) !== Number(expectedFloorOrder)
				) {
					continue;
				}
				const coords = slab?.geometry?.coordinates;
				if (!Array.isArray(coords) || coords.length === 0) continue;
				if (isPointInPolygonWithHoles(pt, coords[0], coords.slice(1))) {
					inSlab = true;
					break;
				}
			}
			if (!inSlab) {
				const floorLabel =
					expectedFloorName ||
					(expectedFloorOrder !== null ? `Tầng ${expectedFloorOrder}` : "");
				return {
					ok: false,
					reason: "outside-corridor-slab",
					message: `Vị trí vừa chọn nằm ngoài phạm vi sàn${expectedBuilding ? ` của ${expectedBuilding}` : ""}${floorLabel ? ` (${floorLabel})` : ""}. Vui lòng đặt camera hành lang trên phần sàn bên trong tòa nhà.`,
				};
			}
		} else if (
			Array.isArray(store?.buildingBoundaries) &&
			store.buildingBoundaries.length > 0
		) {
			let inBoundary = false;
			for (const bnd of store.buildingBoundaries) {
				if (
					expectedBuilding &&
					bnd.building &&
					!matchesBuilding(bnd.building, expectedBuilding)
				) {
					continue;
				}
				if (
					expectedFloorOrder !== null &&
					bnd.floorOrder != null &&
					Number(bnd.floorOrder) !== Number(expectedFloorOrder)
				) {
					continue;
				}
				if (Array.isArray(bnd.ring) && isPointInPolygonWithHoles(pt, bnd.ring, [])) {
					inBoundary = true;
					break;
				}
			}
			if (!inBoundary) {
				const floorLabel =
					expectedFloorName ||
					(expectedFloorOrder !== null ? `Tầng ${expectedFloorOrder}` : "");
				return {
					ok: false,
					reason: "outside-corridor-slab",
					message: `Vị trí vừa chọn nằm ngoài phạm vi sàn${expectedBuilding ? ` của ${expectedBuilding}` : ""}${floorLabel ? ` (${floorLabel})` : ""}. Vui lòng đặt camera hành lang trên phần sàn bên trong tòa nhà.`,
				};
			}
		}

		return {
			ok: true,
			zone: "corridor",
			building: expectedBuilding,
			floorOrder: expectedFloorOrder,
		};
	}

	const areaLabel =
		targetArea?.name ||
		targetCam?.areaName ||
		targetCam?.assignedArea?.name ||
		"được gán";

	if (effectiveRoomProps?.matchedAreaId) {
		if (
			targetAreaId &&
			String(effectiveRoomProps.matchedAreaId) !== String(targetAreaId)
		) {
			const otherAreaLabel = effectiveRoomProps.matchedAreaName || "khác";
			return {
				ok: false,
				reason: "other-area-room",
				message: `Vị trí vừa chọn thuộc phòng "${otherAreaLabel}", nhưng camera thuộc khu vực "${areaLabel}". Vui lòng đặt pin vào đúng phòng "${areaLabel}".`,
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
		if (effectiveRoomProps) {
			const unassignedRoomLabel =
				effectiveRoomProps.name || effectiveRoomProps.roomName || "này";
			return {
				ok: false,
				reason: "outside-target-room",
				message: `Phòng "${unassignedRoomLabel}" chưa được gán cho khu vực "${areaLabel}". Vui lòng đặt pin vào đúng phòng "${areaLabel}".`,
			};
		}
		return {
			ok: false,
			reason: "outside-target-room",
			message: `Vị trí vừa chọn nằm ngoài phòng "${areaLabel}" (trên hành lang hoặc ngoài tòa nhà). Vui lòng đặt pin vào bên trong khuôn khổ phòng "${areaLabel}".`,
		};
	}

	return {
		ok: true,
		zone: "target-room",
		building: expectedBuilding,
		floorOrder: expectedFloorOrder,
	};
}
