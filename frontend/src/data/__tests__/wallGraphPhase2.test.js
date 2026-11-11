import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	BOUNDARY_WALL_HEIGHT,
	INTERIOR_WALL_HEIGHT,
	buildVector3dLayers,
	buildWallsWithDoorCutouts,
	clipPolygonToBoundary,
	computeDigitizedRoute,
	createDefaultGeometryStore,
	extractPlanarFacesFromWalls,
	getPolygonInteriorPoint,
	isPointInPolygonWithHoles,
	isPointInRing,
	migrateStoreToWallGraph,
} from "../campusIndoor3dData.js";

describe("Wall Graph Phase 2 (Q3) - Hole-aware Polygon Helpers & Room Clipping", () => {
	it("isPointInPolygonWithHoles excludes points strictly inside inner courtyard hole while retaining outer ring", () => {
		const outer = [
			[106.809, 10.841],
			[106.810, 10.841],
			[106.810, 10.842],
			[106.809, 10.842],
			[106.809, 10.841],
		];
		const hole = [
			[106.8093, 10.8413],
			[106.8093, 10.8417],
			[106.8097, 10.8417],
			[106.8097, 10.8413],
			[106.8093, 10.8413],
		];

		// Inside corridor annulus
		assert.equal(isPointInPolygonWithHoles([106.80915, 10.8415], outer, [hole]), true);
		// Inside central VOID hole
		assert.equal(isPointInPolygonWithHoles([106.8095, 10.8415], outer, [hole]), false);
		// Outside outer boundary
		assert.equal(isPointInPolygonWithHoles([106.8085, 10.8415], outer, [hole]), false);

		// Interior anchor point must fall inside corridor and never inside VOID hole
		const interior = getPolygonInteriorPoint(outer, [hole]);
		assert.equal(isPointInPolygonWithHoles(interior, outer, [hole]), true);
	});

	it("Q3(b): clips all 10 protruding starter rooms strictly within concave/convex buildingBoundaries", () => {
		const store = createDefaultGeometryStore();
		assert.equal(store.roomPolygons.length, 26, "All 26 starter rooms must remain intact after clipping");

		for (const room of store.roomPolygons) {
			const bound = store.buildingBoundaries.find(
				(b) => b.building === room.building && b.floorOrder === room.floorOrder,
			);
			assert.ok(bound, `Boundary missing for ${room.building} F${room.floorOrder}`);
			const verts = room.ring.slice(0, -1);
			for (const pt of verts) {
				assert.equal(
					isPointInRing(pt, bound.ring),
					true,
					`Clipped room ${room.id} (${room.building} F${room.floorOrder}) still has vertex outside boundary`,
				);
			}
		}

		// Direct unit test for clipping against concave U-shaped Tòa Epsilon boundary
		const epsBound = store.buildingBoundaries.find(
			(b) => b.building === "Tòa Epsilon" && b.floorOrder === 0,
		);
		const protrudingRect = [
			[106.8112, 10.8406],
			[106.8116, 10.8406],
			[106.8116, 10.84035],
			[106.8112, 10.84035],
			[106.8112, 10.8406],
		];
		const clipped = clipPolygonToBoundary(protrudingRect, epsBound.ring);
		assert.ok(Array.isArray(clipped) && clipped.length >= 4);
		for (const pt of clipped.slice(0, -1)) {
			assert.equal(isPointInRing(pt, epsBound.ring), true);
		}
	});
});

describe("Wall Graph Phase 2 (Q3) - Planar Face Extraction, Holes & 2.85m vs 1.35m Heights", () => {
	it("Extracts ROOM, CORRIDOR & nested VOID hole faces and classifies perimeter (2.85m) vs interior (1.35m) walls", () => {
		// Build outer square 0..40m with 1 divider wall cutting off left wing + inner floating hole room
		const originLng = 106.81;
		const originLat = 10.841;
		const mToCoord = (mx, my) => [
			Number((originLng + mx / 109335).toFixed(7)),
			Number((originLat + my / 110574).toFixed(7)),
		];

		const wallItems = [
			{
				id: "w-outer",
				line: [
					mToCoord(0, 0),
					mToCoord(40, 0),
					mToCoord(40, 20),
					mToCoord(0, 20),
					mToCoord(0, 0),
				],
			},
			// T-junction divider wall splitting left 12m room from corridor
			{
				id: "w-div",
				line: [mToCoord(12, 0), mToCoord(12, 20)],
			},
			// Floating courtyard VOID island inside right corridor (20..30m x 5..15m)
			{
				id: "w-hole",
				line: [
					mToCoord(20, 5),
					mToCoord(30, 5),
					mToCoord(30, 15),
					mToCoord(20, 15),
					mToCoord(20, 5),
				],
			},
		];

		const extracted = extractPlanarFacesFromWalls(wallItems, [
			{ seedCoord: mToCoord(6, 10), role: "ROOM" },
			{ seedCoord: mToCoord(16, 10), role: "CORRIDOR" },
			{ seedCoord: mToCoord(25, 10), role: "VOID" },
		]);

		const roomFaces = extracted.faces.filter((f) => f.role === "ROOM");
		const corridorFaces = extracted.faces.filter((f) => f.role === "CORRIDOR");
		const voidFaces = extracted.faces.filter((f) => f.role === "VOID");

		assert.equal(roomFaces.length, 1);
		assert.equal(corridorFaces.length, 1);
		assert.equal(voidFaces.length, 1);
		assert.equal(
			corridorFaces[0].holes.length,
			1,
			"Corridor face must automatically contain the floating VOID courtyard ring as a hole",
		);
		assert.equal(
			isPointInPolygonWithHoles(
				mToCoord(25, 10),
				corridorFaces[0].ring,
				corridorFaces[0].holes,
			),
			false,
		);

		const perimSegs = extracted.wallSegments.filter((s) => s.isPerimeter);
		const innerSegs = extracted.wallSegments.filter((s) => !s.isPerimeter);
		assert.ok(perimSegs.length >= 4);
		assert.ok(innerSegs.length >= 1, "Divider and courtyard walls must be classified interior");
		assert.equal(BOUNDARY_WALL_HEIGHT, 2.85);
		assert.equal(INTERIOR_WALL_HEIGHT, 1.35);
	});

	it("Q3(a): buildVector3dLayers extrudes perimeter walls to 2.85m, interior walls to 1.35m, and punches VOID holes in slabs", () => {
		const originLng = 106.81;
		const originLat = 10.841;
		const mToCoord = (mx, my) => [
			Number((originLng + mx / 109335).toFixed(7)),
			Number((originLat + my / 110574).toFixed(7)),
		];

		const mockStore = {
			version: 3,
			walls: [
				{
					id: "w-outer",
					building: "Tòa Test",
					floorOrder: 0,
					line: [
						mToCoord(0, 0),
						mToCoord(30, 0),
						mToCoord(30, 20),
						mToCoord(0, 20),
						mToCoord(0, 0),
					],
				},
				{
					id: "w-div",
					building: "Tòa Test",
					floorOrder: 0,
					line: [mToCoord(10, 0), mToCoord(10, 20)],
				},
				{
					id: "w-atrium",
					building: "Tòa Test",
					floorOrder: 0,
					line: [
						mToCoord(16, 6),
						mToCoord(24, 6),
						mToCoord(24, 14),
						mToCoord(16, 14),
						mToCoord(16, 6),
					],
				},
			],
			faceAssignments: [
				{ id: "fa-r", building: "Tòa Test", floorOrder: 0, seedCoord: mToCoord(5, 10), role: "ROOM" },
				{ id: "fa-c", building: "Tòa Test", floorOrder: 0, seedCoord: mToCoord(13, 10), role: "CORRIDOR" },
				{ id: "fa-v", building: "Tòa Test", floorOrder: 0, seedCoord: mToCoord(20, 10), role: "VOID" },
			],
			doors: [],
			navNodes: [],
			navEdges: [],
		};

		const vec = buildVector3dLayers({
			store: mockStore,
			selectedBuilding: "Tòa Test",
			activeFloorOrder: 0,
		});

		const slabHeights = vec.slabsGeoJSON.features;
		assert.ok(slabHeights.length >= 1);
		const corridorSlab = slabHeights.find((f) => f.geometry.coordinates.length > 1);
		assert.ok(corridorSlab, "Floor slab must exclude VOID atrium hole via multi-ring polygon");

		const perimWalls = vec.wallsGeoJSON.features.filter((w) => w.properties.isPerimeter);
		const intWalls = vec.wallsGeoJSON.features.filter((w) => !w.properties.isPerimeter);
		assert.ok(perimWalls.length > 0 && intWalls.length > 0);
		for (const pw of perimWalls) {
			assert.ok(
				Math.abs((pw.properties.extrudeHeight - pw.properties.baseHeight) - 2.85) < 0.01,
				`Expected perimeter wall height 2.85m, got ${pw.properties.extrudeHeight - pw.properties.baseHeight}`,
			);
		}
		for (const iw of intWalls) {
			assert.ok(
				Math.abs((iw.properties.extrudeHeight - iw.properties.baseHeight) - 1.35) < 0.01,
				`Expected interior wall height 1.35m, got ${iw.properties.extrudeHeight - iw.properties.baseHeight}`,
			);
		}
	});
});

describe("Wall Graph Phase 2 (Q3) - DOOR Wall Cutout, Two-Side Routing & Shared floorDiff*18", () => {
	it("Carves 3D wall opening for DOOR and routes A* strictly through DOOR rather than straight across unbroken wall", () => {
		const originLng = 106.81;
		const originLat = 10.841;
		const mToCoord = (mx, my) => [
			Number((originLng + mx / 109335).toFixed(7)),
			Number((originLat + my / 110574).toFixed(7)),
		];

		// Room sits x=0..10m, y=0..14m. Door on shared wall x=10m near y=12m (far north end).
		// Main corridor node is at (14, 2) (south end). Destination is deep inside Room at (4, 2).
		// Without DOOR routing, straight line goes directly from (14,2) -> (4,2) across solid wall at y=2!
		// With DOOR on wall at (10,12), route MUST walk corridor north -> turn through (10,12) door -> (4,2).
		const walls = [
			{
				id: "w-outer",
				building: "Tòa Door",
				floorOrder: 0,
				line: [mToCoord(0, 0), mToCoord(20, 0), mToCoord(20, 14), mToCoord(0, 14), mToCoord(0, 0)],
			},
			{
				id: "w-div",
				building: "Tòa Door",
				floorOrder: 0,
				line: [mToCoord(10, 0), mToCoord(10, 14)],
			},
		];
		const doors = [
			{
				id: "door-room-1",
				building: "Tòa Door",
				floorOrder: 0,
				coords: mToCoord(10, 12),
				widthMeters: 1.4,
			},
		];

		// Verify 3D wall cutout splits the divider wall into 2 physical segments leaving doorway gap
		const extracted = extractPlanarFacesFromWalls(walls, [
			{ seedCoord: mToCoord(4, 7), role: "ROOM" },
			{ seedCoord: mToCoord(15, 7), role: "CORRIDOR" },
		]);
		const cutSegs = buildWallsWithDoorCutouts(extracted.wallSegments, doors);
		const divSegs = cutSegs.filter((s) => !s.isPerimeter);
		assert.equal(divSegs.length, 2, "Door on single interior wall must split wall into 2 segments with 1.4m gap");

		const store = {
			version: 3,
			walls,
			faceAssignments: [
				{ id: "f1", building: "Tòa Door", floorOrder: 0, seedCoord: mToCoord(4, 7), role: "ROOM" },
				{ id: "f2", building: "Tòa Door", floorOrder: 0, seedCoord: mToCoord(15, 7), role: "CORRIDOR" },
			],
			doors,
			navNodes: [
				{ id: "c-s", type: "NODE", building: "Tòa Door", floorOrder: 0, coords: mToCoord(14, 2) },
				{ id: "c-n", type: "NODE", building: "Tòa Door", floorOrder: 0, coords: mToCoord(14, 12) },
			],
			navEdges: [["c-s", "c-n"]],
		};

		const route = computeDigitizedRoute({
			store,
			startCoords: mToCoord(14, 2),
			startBuilding: "Tòa Door",
			startFloorOrder: 0,
			endCoords: mToCoord(4, 2),
			endBuilding: "Tòa Door",
			endFloorOrder: 0,
		});

		assert.ok(route);
		const passesDoor = route.pathNodes.some(
			(pn) => Math.hypot(pn.coords[0] - doors[0].coords[0], pn.coords[1] - doors[0].coords[1]) < 1e-5,
		);
		assert.equal(passesDoor, true, "A* path into room with attached DOOR must pass through door coordinates");
	});

	it("Q3(c): keeps exact shared floorDiff * 18 penalty for ELEVATOR and STAIRS", () => {
		const originLng = 106.81;
		const originLat = 10.841;
		const mToCoord = (mx, my) => [
			Number((originLng + mx / 109335).toFixed(7)),
			Number((originLat + my / 110574).toFixed(7)),
		];

		// Closer STAIRS path horizontally (total walk 20m) vs farther ELEVATOR path horizontally (total walk 35m).
		// Going up 2 floors: with shared floorDiff*18 (both add 36), STAIRS wins strictly because horizontal walk is shorter!
		const store = {
			version: 3,
			walls: [],
			faceAssignments: [],
			doors: [],
			navNodes: [
				{ id: "f0-mid", type: "NODE", building: "Tòa W", floorOrder: 0, coords: mToCoord(0, 0) },
				{ id: "st-0", type: "STAIRS", portalGroupId: "ST1", building: "Tòa W", floorOrder: 0, coords: mToCoord(10, 0) },
				{ id: "st-2", type: "STAIRS", portalGroupId: "ST1", building: "Tòa W", floorOrder: 2, coords: mToCoord(10, 0) },
				{ id: "el-0", type: "ELEVATOR", portalGroupId: "EL1", building: "Tòa W", floorOrder: 0, coords: mToCoord(-17.5, 0) },
				{ id: "el-2", type: "ELEVATOR", portalGroupId: "EL1", building: "Tòa W", floorOrder: 2, coords: mToCoord(-17.5, 0) },
				{ id: "f2-dest", type: "NODE", building: "Tòa W", floorOrder: 2, coords: mToCoord(0, 0) },
			],
			navEdges: [
				["f0-mid", "st-0"],
				["st-0", "st-2"],
				["st-2", "f2-dest"],
				["f0-mid", "el-0"],
				["el-0", "el-2"],
				["el-2", "f2-dest"],
			],
		};

		const route = computeDigitizedRoute({
			store,
			startCoords: mToCoord(0, 0),
			startBuilding: "Tòa W",
			startFloorOrder: 0,
			endCoords: mToCoord(0, 0),
			endBuilding: "Tòa W",
			endFloorOrder: 2,
		});
		assert.ok(route);
		const usedPortal = route.pathNodes.find((n) => n.type === "STAIRS" || n.type === "ELEVATOR");
		assert.equal(usedPortal?.type, "STAIRS", "Shared floorDiff * 18 must select shorter horizontal STAIRS route");
	});

	it("Migrates legacy v2 store cleanly into Wall Graph v3 without losing rooms, portals or entrances", () => {
		const v2Mock = {
			version: 2,
			buildingBoundaries: [
				{
					id: "b0",
					building: "Tòa HCM",
					floorOrder: 0,
					ring: createDefaultGeometryStore().buildingBoundaries[0].ring,
				},
			],
			roomPolygons: createDefaultGeometryStore().roomPolygons.slice(0, 3),
			customWalls: [],
			navNodes: [
				{ id: "ent-1", type: "ENTRANCE", building: "Tòa HCM", floorOrder: 0, coords: [106.810473, 10.84148] },
			],
			navEdges: [],
		};
		const migrated = migrateStoreToWallGraph(v2Mock);
		assert.equal(migrated.version, 3);
		assert.ok(migrated.walls.length > 0);
		assert.ok(migrated.doors.length >= 1, "Legacy ENTRANCE nodes on perimeter migrate into DOORS");
		assert.ok(migrated.faceAssignments.some((fa) => fa.role === "ROOM"));
	});
});
