import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	BOUNDARY_WALL_HEIGHT,
	BUILDING_ALL_FLOORS_WALL_HEIGHT,
	FLOOR_ELEVATION_STEP,
	FLOOR_SLAB_HEIGHT,
	INTERIOR_WALL_HEIGHT,
	OVERVIEW_WALL_HEIGHT,
	buildVector3dLayers,
	collectWeldedDragExclusion,
	createDefaultGeometryStore,
	resolveBestSnapTarget,
} from "../campusIndoor3dData.js";
import { resolveToolSwitchDraftAction } from "../../utils/studioToolDraftHelper.js";

function makeLinearProjector(scalePxPerDeg = 1_500_000) {
	return ([lng, lat]) => ({
		x: lng * scalePxPerDeg,
		y: -lat * scalePxPerDeg,
	});
}

describe("Phase 5 (Mục 3A + Bug 4A/4B) - BIM-Lite 3D Rendering & Studio CAD Stability", () => {
	describe("1. BIM-Lite 3-Mode Multi-Floor 3D Rendering (Option 3A)", () => {
		it("Happy Path (ALL Overview): eliminates 0.49m vertical slit with full-height 3.34m facade, removes obstructing plinths & roofs, and hides interior clutter", () => {
			const store = createDefaultGeometryStore();
			const vec = buildVector3dLayers({
				store,
				selectedBuilding: "ALL",
				activeFloorOrder: "ALL",
			});

			assert.ok(Math.abs(OVERVIEW_WALL_HEIGHT - (FLOOR_ELEVATION_STEP - FLOOR_SLAB_HEIGHT)) < 1e-6);
			const perimWalls = vec.wallsGeoJSON.features.filter(
				(f) => f.properties.wallKind === "PERIMETER",
			);
			const interiorWalls = vec.wallsGeoJSON.features.filter(
				(f) => f.properties.wallKind === "INTERIOR",
			);

			assert.ok(perimWalls.length > 0, "Expected exterior facade walls in ALL overview");
			for (const pw of perimWalls) {
				const expectedTop =
					(Number(pw.properties.floorOrder) + 1) * FLOOR_ELEVATION_STEP;
				const expectedBase =
					Number(pw.properties.floorOrder) * FLOOR_ELEVATION_STEP +
					FLOOR_SLAB_HEIGHT;
				assert.ok(
					Math.abs(pw.properties.baseHeight - expectedBase) < 0.01,
					`Base height mismatch on overview floor ${pw.properties.floorOrder}`,
				);
				assert.ok(
					Math.abs(pw.properties.extrudeHeight - expectedTop) < 0.01,
					`Expected overview perimeter wall to close gap cleanly up to ${expectedTop}m, got ${pw.properties.extrudeHeight}m`,
				);
			}
			assert.equal(
				interiorWalls.length,
				0,
				"Overview ALL should suppress interior clutter partitions",
			);
			assert.equal(
				vec.roofsGeoJSON,
				undefined,
				"Overview ALL should no longer emit obstructing roofsGeoJSON",
			);
			assert.equal(
				vec.plinthsGeoJSON,
				undefined,
				"Overview ALL should no longer emit obstructing plinthsGeoJSON",
			);
		});

		it("Boundary / Mode Differentiation: 'Toàn tòa' exposes clickable cutaway floors without roofs; Single-Floor preserves 2.85m/1.35m & hides ghost interior clutter", () => {
			const store = createDefaultGeometryStore();

			const buildingAll = buildVector3dLayers({
				store,
				selectedBuilding: "Tòa Alpha",
				activeFloorOrder: "ALL",
			});
			assert.equal(buildingAll.roofsGeoJSON, undefined);
			assert.ok(
				BUILDING_ALL_FLOORS_WALL_HEIGHT < OVERVIEW_WALL_HEIGHT,
				"Building all-floors mode should keep open cutaway slot above walls to inspect stacked plans",
			);
			const alphaFloorOrders = new Set(
				buildingAll.slabsGeoJSON.features.map((f) => f.properties.floorOrder),
			);
			assert.deepEqual([...alphaFloorOrders].sort((a, b) => a - b), [0, 1, 2]);
			for (const pw of buildingAll.wallsGeoJSON.features.filter(
				(w) => w.properties.wallKind === "PERIMETER",
			)) {
				assert.ok(
					Math.abs(
						pw.properties.extrudeHeight -
							pw.properties.baseHeight -
							BUILDING_ALL_FLOORS_WALL_HEIGHT,
					) < 0.01,
				);
			}

			// Single-floor focus on top floor (floor 2): active retains 2.85m/1.35m, ghost lower floors omit INTERIOR clutter
			const singleTop = buildVector3dLayers({
				store,
				selectedBuilding: "Tòa Alpha",
				activeFloorOrder: 2,
			});
			const activePerim = singleTop.wallsGeoJSON.features.filter(
				(w) => !w.properties.isGhost && w.properties.wallKind === "PERIMETER",
			);
			const activeInt = singleTop.wallsGeoJSON.features.filter(
				(w) => !w.properties.isGhost && w.properties.wallKind === "INTERIOR",
			);
			const ghostInt = singleTop.wallsGeoJSON.features.filter(
				(w) => w.properties.isGhost && w.properties.wallKind === "INTERIOR",
			);
			assert.ok(activePerim.length > 0 && activeInt.length > 0);
			for (const w of activePerim) {
				assert.ok(
					Math.abs(w.properties.extrudeHeight - w.properties.baseHeight - BOUNDARY_WALL_HEIGHT) < 0.01,
				);
			}
			for (const w of activeInt) {
				assert.ok(
					Math.abs(w.properties.extrudeHeight - w.properties.baseHeight - INTERIOR_WALL_HEIGHT) < 0.01,
				);
			}
			assert.equal(
				ghostInt.length,
				0,
				"Lower ghost floors should keep outer shell only and omit interior ghost walls",
			);
		});

		it("Door 3D Visibility (Interior & Perimeter): emits raised 3D DOOR_THRESHOLD, DOOR_JAMB posts & DOOR_LINTEL on both perimeter and interior walls", () => {
			const mToCoord = (mx, my) => [
				Number((106.81 + mx / 109335).toFixed(7)),
				Number((10.841 + my / 110574).toFixed(7)),
			];
			const doorStore = {
				version: 3,
				buildingBoundaries: [],
				walls: [
					{
						id: "w-outer",
						building: "Tòa Alpha",
						floorOrder: 0,
						line: [
							mToCoord(0, 0),
							mToCoord(20, 0),
							mToCoord(20, 12),
							mToCoord(0, 12),
							mToCoord(0, 0),
						],
					},
					{
						id: "w-inner",
						building: "Tòa Alpha",
						floorOrder: 0,
						line: [mToCoord(10, 0), mToCoord(10, 12)],
					},
				],
				faceAssignments: [
					{ id: "fa-1", building: "Tòa Alpha", floorOrder: 0, seedCoord: mToCoord(5, 6), role: "ROOM" },
				],
				doors: [
					{
						id: "d-outer",
						building: "Tòa Alpha",
						floorOrder: 0,
						coords: mToCoord(5, 0),
						widthMeters: 1.4,
					},
					{
						id: "d-inner",
						building: "Tòa Alpha",
						floorOrder: 0,
						coords: mToCoord(10, 6),
						widthMeters: 1.2,
					},
				],
				navNodes: [],
				navEdges: [],
			};

			const vec = buildVector3dLayers({
				store: doorStore,
				selectedBuilding: "Tòa Alpha",
				activeFloorOrder: 0,
				viewRole: "STUDIO",
			});

			const sills = vec.wallsGeoJSON.features.filter(
				(w) => w.properties.wallKind === "DOOR_THRESHOLD",
			);
			const lintels = vec.wallsGeoJSON.features.filter(
				(w) => w.properties.wallKind === "DOOR_LINTEL",
			);
			const jambs = vec.wallsGeoJSON.features.filter(
				(w) => w.properties.wallKind === "DOOR_JAMB",
			);

			assert.equal(sills.length, 2);
			assert.ok(
				sills.every((s) => s.properties.extrudeHeight - s.properties.baseHeight >= 0.12),
				"Door thresholds should sit visibly proud of slab height",
			);
			assert.ok(
				lintels.some((l) => !l.properties.isPerimeter),
				"Interior partition doorway should render a 3D header lintel above room extrusions",
			);
			assert.ok(
				jambs.length >= 4,
				"Both perimeter and interior doors should render 2 vertical 3D jamb posts each",
			);
		});
	});

	describe("2. Bug 4B — Welded Corner Drag Snap Exclusion", () => {
		const P0 = [106.8098, 10.8411];
		const P1 = [106.8099, 10.8411];
		const P2 = [106.8098, 10.8412];
		const P3 = [106.80983, 10.84113];
		const P4 = [106.80995, 10.84113];

		const walls = [
			{ id: "wall-A", building: "Tòa Alpha", floorOrder: 0, line: [P0, P1] },
			{ id: "wall-B", building: "Tòa Alpha", floorOrder: 0, line: [P2, P0] },
			{ id: "wall-C", building: "Tòa Alpha", floorOrder: 0, line: [P3, P4] },
		];
		const vertices = walls.flatMap((w) =>
			w.line.map((coord) => ({ coord, ownerId: w.id })),
		);
		const segments = walls.map((w) => ({
			seg: [w.line[0], w.line[1]],
			ownerId: w.id,
		}));
		const projectFn = makeLinearProjector();

		it("Happy Path: excludes all welded sibling walls at dragged vertex origin so cursor does not self-snap back within 1..10px, while still snapping to external wall-C", () => {
			const exclusion = collectWeldedDragExclusion({
				walls,
				wallId: "wall-A",
				vertexIndex: 0,
				detachSingle: false,
			});
			assert.equal(exclusion.excludeOwnerIds.has("wall-A"), true);
			assert.equal(exclusion.excludeOwnerIds.has("wall-B"), true);
			assert.equal(exclusion.excludeOwnerIds.has("wall-C"), false);

			// Drag cursor 4px East of P0 (previously snapped back onto wall-B's vertex/edge!)
			const degPerPx = 1 / 1_500_000;
			const dragged4Px = [
				Number((P0[0] + 4 * degPerPx).toFixed(7)),
				P0[1],
			];
			const snapNearOrigin = resolveBestSnapTarget({
				rawLngLat: dragged4Px,
				projectFn,
				vertices,
				segments,
				excludeOwnerIds: exclusion.excludeOwnerIds,
				excludeCoord: exclusion.excludeCoord,
			});
			assert.equal(
				snapNearOrigin,
				null,
				`Expected no self-snap back onto welded origin/edge at 4px, got ${JSON.stringify(snapNearOrigin)}`,
			);

			// Now drag near external wall-C vertex P3 (~3px away): MUST still magnetically snap onto P3!
			const p3Px = projectFn(P3);
			const nearExternalP3 = [
				Number((p3Px.x * degPerPx + 2 * degPerPx).toFixed(7)),
				Number((-p3Px.y * degPerPx).toFixed(7)),
			];
			const snapToExternal = resolveBestSnapTarget({
				rawLngLat: nearExternalP3,
				projectFn,
				vertices,
				segments,
				excludeOwnerIds: exclusion.excludeOwnerIds,
				excludeCoord: exclusion.excludeCoord,
			});
			assert.ok(snapToExternal, "Expected snap to unconnected wall-C");
			assert.equal(snapToExternal.type, "VERTEX");
			assert.equal(snapToExternal.ownerId, "wall-C");
		});

		it("Boundary (Alt / detachSingle): excludes only the detached wallId so vertex can snap back onto sibling wall-B to re-weld", () => {
			const exclusion = collectWeldedDragExclusion({
				walls,
				wallId: "wall-A",
				vertexIndex: 0,
				detachSingle: true,
			});
			assert.equal(exclusion.excludeOwnerIds.has("wall-A"), true);
			assert.equal(exclusion.excludeOwnerIds.has("wall-B"), false);
			assert.equal(exclusion.excludeCoord, null);

			const snapBackSibling = resolveBestSnapTarget({
				rawLngLat: [P0[0] + 0.000002, P0[1]],
				projectFn,
				vertices,
				segments,
				excludeOwnerIds: exclusion.excludeOwnerIds,
				excludeCoord: exclusion.excludeCoord,
			});
			assert.ok(snapBackSibling);
			assert.equal(snapBackSibling.ownerId, "wall-B");
		});

		it("Exception / Invalid Inputs: missing wall or invalid vertexIndex handles safely without throwing", () => {
			const missing = collectWeldedDragExclusion({
				walls,
				wallId: "does-not-exist",
				vertexIndex: 0,
			});
			assert.equal(missing.origCoord, null);
			assert.equal(missing.excludeOwnerIds.has("does-not-exist"), true);

			const outOfRange = collectWeldedDragExclusion({
				walls,
				wallId: "wall-A",
				vertexIndex: 99,
			});
			assert.equal(outOfRange.origCoord, null);
			assert.equal(outOfRange.excludeOwnerIds.has("wall-A"), true);
		});
	});

	describe("3. Bug 4A — Studio Tool Switch Auto-Commit Draft Policy", () => {
		const p1 = [106.8098, 10.8411];
		const p2 = [106.8099, 10.8411];
		const p3 = [106.8099, 10.8412];

		it("Happy Path: switching away from WALL_DRAW (>=2 pts) or ROOM_POLY (>=3 pts) auto-commits instead of discarding", () => {
			const wallToDoor = resolveToolSwitchDraftAction("WALL_DRAW", [p1, p2], "DOOR");
			assert.deepEqual(wallToDoor, {
				action: "COMMIT",
				shouldCommit: true,
				closeOverride: false,
				clearDraft: true,
			});

			const polyToSelect = resolveToolSwitchDraftAction(
				"ROOM_POLY",
				[p1, p2, p3],
				"SELECT",
			);
			assert.deepEqual(polyToSelect, {
				action: "COMMIT",
				shouldCommit: true,
				closeOverride: true,
				clearDraft: true,
			});
		});

		it("Boundary & Idempotency: clicking active tool again is NOOP preserving draft; under-minimum points discard cleanly", () => {
			const sameTool = resolveToolSwitchDraftAction(
				"WALL_DRAW",
				[p1, p2],
				"WALL_DRAW",
			);
			assert.equal(sameTool.action, "NOOP");
			assert.equal(sameTool.shouldCommit, false);
			assert.equal(sameTool.clearDraft, false);

			const shortWall = resolveToolSwitchDraftAction("WALL_DRAW", [p1], "SELECT");
			assert.equal(shortWall.action, "DISCARD");
			assert.equal(shortWall.shouldCommit, false);
			assert.equal(shortWall.clearDraft, true);

			const shortPoly = resolveToolSwitchDraftAction(
				"ROOM_POLY",
				[p1, p2],
				"SELECT",
			);
			assert.equal(shortPoly.action, "DISCARD");
			assert.equal(shortPoly.shouldCommit, false);
		});

		it("Exception / Invalid Inputs: safely handles null/undefined drafts and empty target tool", () => {
			assert.equal(resolveToolSwitchDraftAction("WALL_DRAW", null, "DOOR").action, "DISCARD");
			assert.equal(resolveToolSwitchDraftAction("SELECT", [p1], null).action, "NOOP");
		});
	});
});
