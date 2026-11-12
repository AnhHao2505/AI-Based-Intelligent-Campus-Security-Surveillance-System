import { isPointInPolygonWithHoles } from "../data/campusIndoor3dData.js";

/**
 * Euclidean distance in pixels from point P to segment AB in screen space.
 */
function distancePointToSegmentPixels(p, a, b) {
	if (!p || !a || !b) return Infinity;
	const vx = b.x - a.x;
	const vy = b.y - a.y;
	const lenSq = vx * vx + vy * vy;
	if (lenSq <= 1e-6) return Math.hypot(p.x - a.x, p.y - a.y);
	const t = Math.max(0, Math.min(1, ((p.x - a.x) * vx + (p.y - a.y) * vy) / lenSq));
	return Math.hypot(p.x - (a.x + t * vx), p.y - (a.y + t * vy));
}

function resolveBestEnclosingFaceId({
	rawLngLat,
	ptLngLat,
	features,
	faces,
}) {
	if (Array.isArray(faces) && faces.length > 0 && Array.isArray(rawLngLat)) {
		const matchingFaces = faces
			.filter(
				(f) =>
					f?.id &&
					(isPointInPolygonWithHoles(rawLngLat, f.ring, f.holes || []) ||
						(Array.isArray(ptLngLat) &&
							isPointInPolygonWithHoles(ptLngLat, f.ring, f.holes || []))),
			)
			.sort((a, b) => (a.areaSqm || 0) - (b.areaSqm || 0));
		if (matchingFaces[0]?.id) {
			return matchingFaces[0].id;
		}
	}

	const faceFeats = (Array.isArray(features) ? features : [])
		.filter(
			(f) =>
				f?.properties?.id &&
				(f?.layer?.id === "studio-faces-fill" ||
					f?.layer?.id === "studio-faces-outline" ||
					f?.layer?.id === "studio-rooms-extrusion"),
		)
		.sort(
			(a, b) =>
				(Number(a?.properties?.areaSqm) || Infinity) -
				(Number(b?.properties?.areaSqm) || Infinity),
		);
	return faceFeats[0]?.properties?.id || null;
}

/**
 * Determine whether switching from currentTool to nextTool should auto-commit
 * an in-progress multi-point CAD draft (WALL_DRAW >= 2 pts, ROOM_POLY >= 3 pts),
 * ignore a same-tool click (NOOP), or discard an incomplete draft.
 */
export function resolveToolSwitchDraftAction(currentTool, draftPoints, nextTool) {
	if (!nextTool || currentTool === nextTool) {
		return {
			action: "NOOP",
			shouldCommit: false,
			closeOverride: false,
			clearDraft: false,
		};
	}
	const count = Array.isArray(draftPoints) ? draftPoints.length : 0;
	if (currentTool === "WALL_DRAW" && count >= 2) {
		return {
			action: "COMMIT",
			shouldCommit: true,
			closeOverride: false,
			clearDraft: true,
		};
	}
	if (currentTool === "ROOM_POLY" && count >= 3) {
		return {
			action: "COMMIT",
			shouldCommit: true,
			closeOverride: true,
			clearDraft: true,
		};
	}
	return {
		action: "DISCARD",
		shouldCommit: false,
		closeOverride: false,
		clearDraft: true,
	};
}

/**
 * Resolve Studio SELECT click priority using zoom-safe screen-pixel thresholds:
 * - Multi-select (Shift/Ctrl/Meta) always targets enclosing Planar Face (smallest area).
 * - Narrow rooms/corridors (< 2.4m wide) inside an enclosing face are never stolen
 *   by broad 1.2m wall or 0.9m door world-meter fallbacks.
 */
export function resolveStudioSelectEntity({
	rawLngLat,
	ptLngLat = rawLngLat,
	features = [],
	isMulti = false,
	layerVisibility = {},
	layerLocks = {},
	walls = [],
	doors = [],
	faces = [],
	projectFn = null,
} = {}) {
	if (!Array.isArray(rawLngLat) && (!Array.isArray(features) || features.length === 0)) {
		return null;
	}

	const safeFeatures = Array.isArray(features) ? features : [];
	const safeWalls = Array.isArray(walls) ? walls : [];
	const safeDoors = Array.isArray(doors) ? doors : [];
	const safeFaces = Array.isArray(faces) ? faces : [];

	const canSelectNodes = Boolean(layerVisibility?.navGraph && !layerLocks?.navGraph);
	const canSelectDoors = Boolean(layerVisibility?.doors && !layerLocks?.doors);
	const canSelectWalls = Boolean(layerVisibility?.walls && !layerLocks?.walls);
	const canSelectFaces = Boolean(layerVisibility?.faces && !layerLocks?.faces);

	const clickPx =
		typeof projectFn === "function" && Array.isArray(rawLngLat)
			? projectFn(rawLngLat)
			: null;

	// Priority 1: Nav Node (single select only)
	if (!isMulti && canSelectNodes) {
		const nodeFeat = safeFeatures.find(
			(f) =>
				f?.layer?.id === "studio-nav-nodes-layer" ||
				f?.layer?.id === "studio-nav-portals-layer",
		);
		if (nodeFeat?.properties?.id) {
			return { type: "NODE", id: nodeFeat.properties.id };
		}
	}

	// Pre-resolve smallest enclosing planar face so interior clicks inside narrow rooms stay protected
	const resolvedFaceId = canSelectFaces
		? resolveBestEnclosingFaceId({
				rawLngLat,
				ptLngLat,
				features: safeFeatures,
				faces: safeFaces,
			})
		: null;

	if (isMulti) {
		return resolvedFaceId ? { type: "FACE", id: resolvedFaceId } : null;
	}

	// Priority 2: Door (direct oriented box hit OR tight screen-pixel proximity)
	if (canSelectDoors) {
		const doorFeat = safeFeatures.find(
			(f) =>
				f?.layer?.id === "studio-doors-fill-layer" ||
				f?.layer?.id === "studio-doors-outline-layer" ||
				f?.layer?.id === "studio-doors-layer",
		);
		if (doorFeat?.properties?.id) {
			return { type: "DOOR", id: doorFeat.properties.id };
		}
		if (clickPx) {
			const maxDoorPx = resolvedFaceId ? 5 : 10;
			let bestDoorId = null;
			let bestDoorPx = maxDoorPx;
			for (const d of safeDoors) {
				if (!Array.isArray(d?.coords) || !d?.id) continue;
				const dp = projectFn(d.coords);
				if (!dp) continue;
				const distPx = Math.hypot(dp.x - clickPx.x, dp.y - clickPx.y);
				if (distPx <= bestDoorPx) {
					bestDoorPx = distPx;
					bestDoorId = d.id;
				}
			}
			if (bestDoorId) {
				return { type: "DOOR", id: bestDoorId };
			}
		}
	}

	// Priority 3: Wall segment (strictly on-stroke <= 3.5px inside a face, <= 7px outside)
	if (canSelectWalls) {
		if (clickPx) {
			const maxWallPx = resolvedFaceId ? 3.5 : 7.0;
			let bestWallId = null;
			let bestWallPx = maxWallPx;
			for (const w of safeWalls) {
				const line = w?.line;
				if (!w?.id || !Array.isArray(line) || line.length < 2) continue;
				for (let i = 0; i < line.length - 1; i++) {
					const a = projectFn(line[i]);
					const b = projectFn(line[i + 1]);
					const distPx = distancePointToSegmentPixels(clickPx, a, b);
					if (distPx <= bestWallPx) {
						bestWallPx = distPx;
						bestWallId = w.id;
					}
				}
			}
			if (bestWallId) {
				return { type: "WALL", id: bestWallId };
			}
		} else {
			const wallFeat = safeFeatures.find((f) =>
				resolvedFaceId
					? f?.layer?.id === "studio-walls-line-layer"
					: f?.layer?.id === "studio-walls-hit-layer" ||
						f?.layer?.id === "studio-walls-line-layer",
			);
			if (wallFeat?.properties?.id) {
				return { type: "WALL", id: wallFeat.properties.id };
			}
		}
	}

	// Priority 4: Planar Face (ROOM / CORRIDOR / VOID)
	if (resolvedFaceId) {
		return { type: "FACE", id: resolvedFaceId };
	}

	return null;
}
