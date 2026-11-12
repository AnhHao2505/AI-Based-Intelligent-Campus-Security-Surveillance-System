import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	BUILDING_ALL_FLOORS_WALL_HEIGHT,
	EXPLODED_FLOOR_STEP_OPTIONS,
	FLOOR_ELEVATION_STEP,
	FLOOR_SLAB_HEIGHT,
	buildVector3dLayers,
	createDefaultGeometryStore,
} from "../campusIndoor3dData.js";
import { resolveStudioSelectEntity } from "../../utils/studioToolDraftHelper.js";

function makeStudioProjector(pxPerMeter = 28, refLng = 106.81, refLat = 10.841) {
	const kx = 111320 * Math.cos((refLat * Math.PI) / 180);
	const ky = 110574;
	return ([lng, lat]) => ({
		x: (lng - refLng) * kx * pxPerMeter,
		y: -(lat - refLat) * ky * pxPerMeter,
	});
}

function metersToCoord(mx, my, refLng = 106.81, refLat = 10.841) {
	const kx = 111320 * Math.cos((refLat * Math.PI) / 180);
	const ky = 110574;
	return [
		Number((refLng + mx / kx).toFixed(7)),
		Number((refLat + my / ky).toFixed(7)),
	];
}

describe("Phase 6 - Exploded Floors (Situm 3-Step + Shaft Type B) & Studio Narrow Room Select", () => {
	describe("1. Exploded Multi-Floor Stack & Minimal Vertical Portal Shafts (Type B)", () => {
		it("Happy Path: scales floor elevations cleanly across 3.5 / 7.5 / 10.5m, omits plinths/roofs, and emits Type B portal shafts connecting multi-floor STAIRS/ELEVATOR groups", () => {
			assert.deepEqual(
				EXPLODED_FLOOR_STEP_OPTIONS.map((o) => o.step),
				[3.5, 7.5, 10.5],
			);

			const store = createDefaultGeometryStore();
			const vecWide = buildVector3dLayers({
				store,
				selectedBuilding: "ALL",
				activeFloorOrder: "ALL",
				floorElevationStep: 10.5,
			});

			assert.equal(vecWide.plinthsGeoJSON, undefined);
			assert.equal(vecWide.roofsGeoJSON, undefined);

			const floor2Slabs = vecWide.slabsGeoJSON.features.filter(
				(s) => s.properties.floorOrder === 2,
			);
			assert.ok(floor2Slabs.length > 0);
			for (const slab of floor2Slabs) {
				assert.ok(Math.abs(slab.properties.baseHeight - 21.0) < 0.01);
				assert.ok(
					Math.abs(slab.properties.extrudeHeight - (21.0 + FLOOR_SLAB_HEIGHT)) < 0.01,
				);
			}

			// In exploded ALL overview, facade walls switch to cutaway height so interior trays remain legible
			const explodedWalls = vecWide.wallsGeoJSON.features.filter(
				(w) => w.properties.wallKind === "PERIMETER",
			);
			assert.ok(explodedWalls.length > 0);
			for (const w of explodedWalls) {
				assert.ok(
					Math.abs(
						w.properties.extrudeHeight - w.properties.baseHeight - BUILDING_ALL_FLOORS_WALL_HEIGHT,
					) < 0.01,
				);
			}

			// Portal symbols in exploded ALL appear on upper floors too (no floor-0-only choke)
			const upperPortals = vecWide.portalSymbolsGeoJSON.features.filter(
				(p) => p.properties.floorOrder > 0,
			);
			assert.ok(upperPortals.length > 0, "Exploded ALL should render portals on upper floors");
			const floor2Portal = upperPortals.find((p) => p.properties.floorOrder === 2);
			assert.ok(
				Math.abs(floor2Portal.properties.heightOffset - (21.0 + FLOOR_SLAB_HEIGHT + 0.35)) < 0.05,
			);

			// Minimal Type B vertical shafts for stacked groups (e.g. HCM_STAIR_W 0->2, EPS_THANG_TH1 0->1+)
			assert.ok(
				Array.isArray(vecWide.portalShaftsGeoJSON?.features) &&
					vecWide.portalShaftsGeoJSON.features.length >= 4,
				"Expected Type B vertical portal shaft features when exploded > 3.5m",
			);
			const hcmStairWest = vecWide.portalShaftsGeoJSON.features.find(
				(f) =>
					f.properties.portalGroupId === "HCM_STAIR_W" &&
					f.properties.building === "Tòa Alpha",
			);
			assert.ok(hcmStairWest);
			assert.equal(hcmStairWest.properties.nodeType, "STAIRS");
			assert.ok(Math.abs(hcmStairWest.properties.baseHeight - FLOOR_SLAB_HEIGHT) < 0.01);
			assert.ok(
				Math.abs(hcmStairWest.properties.extrudeHeight - (21.0 + FLOOR_SLAB_HEIGHT)) < 0.01,
			);
		});

		it("Boundary / Validation: collapsed step (3.5m) suppresses Type B shafts, invalid step safely defaults to 3.5m, and focused Toàn tòa shows ELEVATOR cyan shafts", () => {
			const baseStore = createDefaultGeometryStore();
			const vecCompact = buildVector3dLayers({
				store: baseStore,
				selectedBuilding: "Tòa Alpha",
				activeFloorOrder: "ALL",
				floorElevationStep: 3.5,
			});
			assert.equal(vecCompact.portalShaftsGeoJSON?.features?.length || 0, 0);

			const vecInvalidStep = buildVector3dLayers({
				store: baseStore,
				selectedBuilding: "ALL",
				activeFloorOrder: "ALL",
				floorElevationStep: -99,
			});
			const f1Slab = vecInvalidStep.slabsGeoJSON.features.find(
				(s) => s.properties.floorOrder === 1,
			);
			assert.ok(Math.abs(f1Slab.properties.baseHeight - FLOOR_ELEVATION_STEP) < 0.01);

			// Add an ELEVATOR vertical group across Floor 0 & Floor 2 + a single-floor orphan portal
			const customStore = {
				...baseStore,
				navNodes: [
					...baseStore.navNodes,
					{
						id: "elev-0",
						type: "ELEVATOR",
						portalGroupId: "ALPHA_ELV_MAIN",
						building: "Tòa Alpha",
						floorOrder: 0,
						coords: metersToCoord(12, 8),
					},
					{
						id: "elev-2",
						type: "ELEVATOR",
						portalGroupId: "ALPHA_ELV_MAIN",
						building: "Tòa Alpha",
						floorOrder: 2,
						coords: metersToCoord(12, 8),
					},
					{
						id: "orphan-stair-1",
						type: "STAIRS",
						portalGroupId: "ORPHAN_SINGLE_FLOOR",
						building: "Tòa Alpha",
						floorOrder: 1,
						coords: metersToCoord(16, 8),
					},
				],
			};

			const vecMed = buildVector3dLayers({
				store: customStore,
				selectedBuilding: "Tòa Alpha",
				activeFloorOrder: "ALL",
				floorElevationStep: 7.5,
			});
			const elevShaft = vecMed.portalShaftsGeoJSON.features.find(
				(f) => f.properties.portalGroupId === "ALPHA_ELV_MAIN",
			);
			assert.ok(elevShaft);
			assert.equal(elevShaft.properties.nodeType, "ELEVATOR");
			assert.equal(elevShaft.properties.color, "#06b6d4");
			assert.ok(Math.abs(elevShaft.properties.extrudeHeight - (15.0 + FLOOR_SLAB_HEIGHT)) < 0.01);
			assert.equal(
				vecMed.portalShaftsGeoJSON.features.some(
					(f) => f.properties.portalGroupId === "ORPHAN_SINGLE_FLOOR",
				),
				false,
				"Single-floor portal should never emit a phantom shaft beam",
			);
		});

		it("Exception / Cross-Building Shaft Isolation: identical portalGroupId across distinct buildings never merges into one beam, and single-floor focus suppresses shafts while preserving label heightOffset", () => {
			const baseStore = createDefaultGeometryStore();
			const isolatedStore = {
				...baseStore,
				navNodes: [
					...baseStore.navNodes,
					{
						id: "shared-alpha-0",
						type: "STAIRS",
						portalGroupId: "SHARED_NAME",
						building: "Tòa Alpha",
						floorOrder: 0,
						coords: metersToCoord(5, 5),
					},
					{
						id: "shared-eps-2",
						type: "STAIRS",
						portalGroupId: "SHARED_NAME",
						building: "Tòa Epsilon",
						floorOrder: 2,
						coords: metersToCoord(200, 5),
					},
				],
			};

			const vecAll = buildVector3dLayers({
				store: isolatedStore,
				selectedBuilding: "ALL",
				activeFloorOrder: "ALL",
				floorElevationStep: 10.5,
			});
			assert.equal(
				vecAll.portalShaftsGeoJSON.features.some(
					(f) => f.properties.portalGroupId === "SHARED_NAME",
				),
				false,
				"Single floor per building with identical portalGroupId must remain isolated and not emit a bridge beam",
			);

			const vecSingle = buildVector3dLayers({
				store: isolatedStore,
				selectedBuilding: "Tòa Alpha",
				activeFloorOrder: 2,
				floorElevationStep: 10.5,
			});
			assert.equal(vecSingle.portalShaftsGeoJSON.features.length, 0);
			const f2Walls = vecSingle.wallsGeoJSON.features.filter((w) => !w.properties.isGhost);
			assert.ok(f2Walls.length > 0 && Math.abs(f2Walls[0].properties.baseHeight - (21.0 + FLOOR_SLAB_HEIGHT)) < 0.01);
		});
	});

	describe("2. Studio Narrow Room / Corridor Select Priority Fix", () => {
		// Narrow 1.5m x 4.0m utility room (like KT ĐIỆN 01-28) beside a wider 6m corridor
		const roomRing = [
			metersToCoord(0, 0),
			metersToCoord(1.5, 0),
			metersToCoord(1.5, 4),
			metersToCoord(0, 4),
			metersToCoord(0, 0),
		];
		const corridorRing = [
			metersToCoord(1.5, 0),
			metersToCoord(4.0, 0),
			metersToCoord(4.0, 8),
			metersToCoord(1.5, 8),
			metersToCoord(1.5, 0),
		];
		const walls = [
			{ id: "w-left", line: [metersToCoord(0, 0), metersToCoord(0, 4)] },
			{ id: "w-right", line: [metersToCoord(1.5, 0), metersToCoord(1.5, 4)] },
			{ id: "w-top", line: [metersToCoord(0, 4), metersToCoord(1.5, 4)] },
			{ id: "w-bot", line: [metersToCoord(0, 0), metersToCoord(1.5, 0)] },
		];
		const doors = [
			{ id: "d-elv", coords: metersToCoord(1.5, 2.0) },
		];
		const faces = [
			{ id: "face-corridor", role: "CORRIDOR", areaSqm: 20, ring: corridorRing, holes: [] },
			{ id: "face-elv", role: "ROOM", areaSqm: 6, ring: roomRing, holes: [] },
		];
		const fullVis = { navGraph: true, doors: true, walls: true, faces: true };
		const noLocks = { navGraph: false, doors: false, walls: false, faces: false };

		it("Happy Path: clicking center of narrow 1.5m room (0.75m from both walls) selects FACE instead of getting stolen by 1.2m wall or 0.9m door radius", () => {
			// Center [0.75m, 2.0m]: strictly 0.75m from w-left, w-right, AND d-elv!
			// Under legacy rules (<1.2m wall, <0.9m door), this always falsely selected DOOR or WALL.
			const clickCoord = metersToCoord(0.75, 2.0);
			const res = resolveStudioSelectEntity({
				rawLngLat: clickCoord,
				ptLngLat: clickCoord,
				features: [
					// Even if queryRenderedFeatures returned corridor before room, & hit-layer wall slightly touched edge
					{ layer: { id: "studio-faces-fill" }, properties: { id: "face-corridor", areaSqm: 20 } },
					{ layer: { id: "studio-faces-fill" }, properties: { id: "face-elv", areaSqm: 6 } },
					{ layer: { id: "studio-walls-hit-layer" }, properties: { id: "w-right" } },
				],
				isMulti: false,
				layerVisibility: fullVis,
				layerLocks: noLocks,
				walls,
				doors,
				faces,
				projectFn: makeStudioProjector(28),
			});

			assert.deepEqual(res, { type: "FACE", id: "face-elv" });
		});

		it("Boundary: clicking directly on a wall line (<= 3.5px) or oriented door box STILL selects WALL or DOOR; Shift multi-select always selects FACE", () => {
			const projectFn = makeStudioProjector(28);
			// 0.04m from left wall -> ~1.1px <= 3.5px threshold -> WALL wins
			const onWallClick = metersToCoord(0.04, 2.0);
			const wallRes = resolveStudioSelectEntity({
				rawLngLat: onWallClick,
				ptLngLat: onWallClick,
				features: [
					{ layer: { id: "studio-walls-line-layer" }, properties: { id: "w-left" } },
					{ layer: { id: "studio-faces-fill" }, properties: { id: "face-elv", areaSqm: 6 } },
				],
				isMulti: false,
				layerVisibility: fullVis,
				layerLocks: noLocks,
				walls,
				doors,
				faces,
				projectFn,
			});
			assert.deepEqual(wallRes, { type: "WALL", id: "w-left" });

			// Direct click on rendered oriented door polygon -> DOOR wins
			const onDoorClick = metersToCoord(1.48, 2.0);
			const doorRes = resolveStudioSelectEntity({
				rawLngLat: onDoorClick,
				ptLngLat: onDoorClick,
				features: [
					{ layer: { id: "studio-doors-fill-layer" }, properties: { id: "d-elv" } },
					{ layer: { id: "studio-faces-fill" }, properties: { id: "face-elv", areaSqm: 6 } },
				],
				isMulti: false,
				layerVisibility: fullVis,
				layerLocks: noLocks,
				walls,
				doors,
				faces,
				projectFn,
			});
			assert.deepEqual(doorRes, { type: "DOOR", id: "d-elv" });

			// Shift multi-select on door/wall still returns FACE
			const multiRes = resolveStudioSelectEntity({
				rawLngLat: onDoorClick,
				ptLngLat: onDoorClick,
				features: [
					{ layer: { id: "studio-doors-fill-layer" }, properties: { id: "d-elv" } },
					{ layer: { id: "studio-faces-fill" }, properties: { id: "face-elv", areaSqm: 6 } },
				],
				isMulti: true,
				layerVisibility: fullVis,
				layerLocks: noLocks,
				walls,
				doors,
				faces,
				projectFn,
			});
			assert.deepEqual(multiRes, { type: "FACE", id: "face-elv" });
		});

		it("Exception / Locks & Null Safety: skips locked walls/doors to select FACE and never throws on null inputs", () => {
			const clickOnWall = metersToCoord(0.04, 2.0);
			const lockedWallRes = resolveStudioSelectEntity({
				rawLngLat: clickOnWall,
				ptLngLat: clickOnWall,
				features: [
					{ layer: { id: "studio-walls-line-layer" }, properties: { id: "w-left" } },
					{ layer: { id: "studio-faces-fill" }, properties: { id: "face-elv", areaSqm: 6 } },
				],
				isMulti: false,
				layerVisibility: fullVis,
				layerLocks: { ...noLocks, walls: true, doors: true },
				walls,
				doors,
				faces,
				projectFn: makeStudioProjector(28),
			});
			assert.deepEqual(lockedWallRes, { type: "FACE", id: "face-elv" });

			assert.equal(
				resolveStudioSelectEntity({
					rawLngLat: null,
					ptLngLat: null,
					features: null,
					walls: null,
					doors: null,
					faces: null,
					projectFn: null,
				}),
				null,
			);
		});
	});
});
