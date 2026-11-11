import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	buildFloorWallGraphModels,
	buildOrientedDoorGeometry,
	buildVector3dLayers,
	computeDigitizedRoute,
	createDefaultGeometryStore,
	deduplicateFloorVertices,
	dragWeldedWallVertices,
	isNodeOutdoor,
	isNodeVisibleOnFloor,
	isPointIndoorOnFloor,
	mergeOrSplitNavNodeOnDrop,
	syncPortalAcrossBuildingFloors,
} from "../campusIndoor3dData.js";

describe("Phase 3: Vertex Welding, Oriented Door Geometry, Portal Sync & Route Diagnostics", () => {
	describe("1. Vertex Deduplication & Magnetic Welded Wall Junction Drag", () => {
		it("Happy Path: deduplicates shared corner vertices across walls and moves all welded endpoints together on drag", () => {
			const walls = [
				{
					id: "w-1",
					building: "Tòa HCM",
					floorOrder: 0,
					line: [
						[106.8098, 10.8411],
						[106.8099, 10.8411],
					],
				},
				{
					id: "w-2",
					building: "Tòa HCM",
					floorOrder: 0,
					line: [
						[106.8099, 10.8411],
						[106.8099, 10.8412],
					],
				},
			];

			const uniqueVerts = deduplicateFloorVertices(walls, {
				selectedWallId: "w-1",
			});
			// 3 distinct corners instead of 4 raw endpoints
			assert.equal(uniqueVerts.length, 3);
			const shared = uniqueVerts.find(
				(v) =>
					Math.abs(v.coord[0] - 106.8099) < 1e-6 &&
					Math.abs(v.coord[1] - 10.8411) < 1e-6,
			);
			assert.ok(shared);
			assert.equal(shared.degree, 2);
			assert.equal(shared.isSelected, true);

			// Dragging w-1 index 1 to a new coordinate moves BOTH w-1[1] and w-2[0]
			const nextCoord = [106.80995, 10.84115];
			const updatedWalls = dragWeldedWallVertices(walls, {
				wallId: "w-1",
				vertexIndex: 1,
				newCoord: nextCoord,
				detachSingle: false,
			});
			assert.deepEqual(updatedWalls[0].line[1], nextCoord);
			assert.deepEqual(updatedWalls[1].line[0], nextCoord);
		});

		it("Boundary / Idempotency: holding Alt (detachSingle=true) detaches only the selected wall endpoint without moving neighbors", () => {
			const walls = [
				{
					id: "w-1",
					building: "Tòa HCM",
					floorOrder: 0,
					line: [
						[106.8098, 10.8411],
						[106.8099, 10.8411],
					],
				},
				{
					id: "w-2",
					building: "Tòa HCM",
					floorOrder: 0,
					line: [
						[106.8099, 10.8411],
						[106.8099, 10.8412],
					],
				},
			];
			const nextCoord = [106.80995, 10.84115];
			const updatedWalls = dragWeldedWallVertices(walls, {
				wallId: "w-1",
				vertexIndex: 1,
				newCoord: nextCoord,
				detachSingle: true,
			});
			assert.deepEqual(updatedWalls[0].line[1], nextCoord);
			assert.deepEqual(updatedWalls[1].line[0], [106.8099, 10.8411]);
		});

		it("Exception / Domain Guard: mergeOrSplitNavNodeOnDrop merges overlapping nodes, rewires edges, and prevents self-loops", () => {
			const navNodes = [
				{
					id: "n-1",
					type: "NODE",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8098, 10.8411],
				},
				{
					id: "n-2",
					type: "NODE",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.80985, 10.8411],
				},
				{
					id: "n-3",
					type: "NODE",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8099, 10.8411],
				},
			];
			const navEdges = [
				["n-1", "n-2"],
				["n-2", "n-3"],
			];

			// Drag n-2 onto n-1
			const res = mergeOrSplitNavNodeOnDrop({
				navNodes,
				navEdges,
				draggedNodeId: "n-2",
				finalCoords: [106.8098, 10.8411],
				targetNodeId: "n-1",
			});

			assert.equal(res.mergedIntoId, "n-1");
			assert.deepEqual(
				res.navNodes.map((n) => n.id),
				["n-1", "n-3"],
			);
			// Self-loop [n-1, n-1] removed, and [n-2, n-3] rewired to [n-1, n-3]
			assert.deepEqual(res.navEdges, [["n-1", "n-3"]]);
		});
	});

	describe("2. Oriented Door Box Geometry, Flexible Jambs & Midpoint Graph Node", () => {
		it("Happy Path: builds an oriented rectangular polygon aligned with host wall angle and computes left/right jamb coordinates", () => {
			const wallSegments = [
				{
					id: "seg-1",
					a: [106.8098, 10.8411],
					b: [106.81, 10.8413],
					isPerimeter: false,
					building: "Tòa HCM",
					floorOrder: 0,
				},
			];
			const door = {
				id: "door-1",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.8099, 10.8412],
				widthMeters: 1.6,
			};

			const geom = buildOrientedDoorGeometry(door, wallSegments);
			assert.ok(geom);
			assert.equal(geom.boxPolygon.length, 5); // Closed 4-corner oriented rectangle
			assert.equal(geom.midpoint.length, 2);
			assert.equal(geom.jambA.length, 2);
			assert.equal(geom.jambB.length, 2);
			assert.ok(Math.abs(geom.widthMeters - 1.6) < 0.05);
			// Diagonal wall (~45 deg) means jambA and jambB differ in BOTH lng and lat
			assert.ok(Math.abs(geom.jambB[0] - geom.jambA[0]) > 0.000005);
			assert.ok(Math.abs(geom.jambB[1] - geom.jambA[1]) > 0.000005);
		});

		it("Boundary / Clamp: supports 0.5m–10.0m flexible door width on wide walls while clamping oversized or undersized values", () => {
			const longWall = [
				{
					id: "seg-long",
					a: [106.8098, 10.8411],
					b: [106.80995, 10.8411], // ~16.4m wall
					isPerimeter: true,
					building: "Tòa HCM",
					floorOrder: 0,
				},
			];
			const wideDoorOk = {
				id: "door-8_5",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.809875, 10.8411],
				widthMeters: 8.5,
			};
			const geomWideOk = buildOrientedDoorGeometry(wideDoorOk, longWall);
			assert.ok(Math.abs(geomWideOk.widthMeters - 8.5) < 0.05);

			const hugeDoor = {
				...wideDoorOk,
				widthMeters: 14.0,
			};
			const geomClamped10 = buildOrientedDoorGeometry(hugeDoor, longWall);
			assert.equal(geomClamped10.widthMeters, 10);

			const wallSegments = [
				{
					id: "seg-short",
					a: [106.8098, 10.8411],
					b: [106.80982, 10.8411], // ~2.18m wall
					isPerimeter: false,
					building: "Tòa HCM",
					floorOrder: 0,
				},
			];
			const shortWallDoor = {
				id: "door-short-clamp",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.80981, 10.8411],
				widthMeters: 9.0,
			};
			const geomWide = buildOrientedDoorGeometry(shortWallDoor, wallSegments);
			assert.ok(geomWide.widthMeters <= 2.2);

			const tinyDoor = {
				...shortWallDoor,
				widthMeters: 0.1,
			};
			const geomTiny = buildOrientedDoorGeometry(tinyDoor, wallSegments);
			assert.ok(geomTiny.widthMeters >= 0.5);
		});

		it("3D Architectural Consistency: renders upper door lintel with the exact same color as the lower door threshold", () => {
			const customStore = {
				version: 3,
				walls: [
					{
						id: "w-rect",
						building: "Tòa HCM",
						floorOrder: 0,
						line: [
							[106.8098, 10.8411],
							[106.80995, 10.8411],
							[106.80995, 10.8412],
							[106.8098, 10.8412],
							[106.8098, 10.8411],
						],
					},
				],
				faceAssignments: [],
				doors: [
					{
						id: "door-perim",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.809875, 10.8411],
						widthMeters: 2.0,
					},
				],
				navNodes: [],
				navEdges: [],
			};
			const layers = buildVector3dLayers({
				store: customStore,
				selectedBuilding: "Tòa HCM",
				activeFloorOrder: 0,
				viewRole: "STUDIO",
			});
			const wallFeats = layers.wallsGeoJSON.features || [];
			const sill = wallFeats.find((f) => f.properties.wallKind === "DOOR_THRESHOLD");
			const lintel = wallFeats.find((f) => f.properties.wallKind === "DOOR_LINTEL");
			assert.ok(sill);
			assert.ok(lintel);
			assert.equal(lintel.properties.color, sill.properties.color);
		});

		it("Exception / Fallback: returns a valid horizontal door box even when no wallSegments are nearby", () => {
			const orphanDoor = {
				id: "door-orphan",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.8099, 10.8412],
				widthMeters: 1.2,
			};
			const geom = buildOrientedDoorGeometry(orphanDoor, []);
			assert.ok(geom);
			assert.equal(geom.boxPolygon.length, 5);
			assert.deepEqual(geom.midpoint, orphanDoor.coords);
		});
	});

	describe("3. Multi-Floor Portal Synchronization (1-Click Stack Sync)", () => {
		it("Happy Path: creates and aligns portal nodes at identical [lng, lat] across target floors and links each to its floor corridor graph", () => {
			const baseStore = createDefaultGeometryStore();
			const portalNode = {
				id: "portal-g",
				type: "ELEVATOR",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.80988, 10.84115],
				portalGroupId: "HCM_ELEV_SYNC_TEST",
			};
			const storeWithPortal = {
				...baseStore,
				navNodes: [...baseStore.navNodes, portalNode],
			};

			const synced = syncPortalAcrossBuildingFloors(storeWithPortal, {
				sourceNodeId: "portal-g",
				targetFloorOrders: [0, 1, 2],
			});

			const groupNodes = synced.navNodes.filter(
				(n) => n.portalGroupId === "HCM_ELEV_SYNC_TEST",
			);
			assert.equal(groupNodes.length, 3);
			assert.deepEqual(
				groupNodes.map((n) => n.floorOrder).sort(),
				[0, 1, 2],
			);
			for (const gn of groupNodes) {
				assert.deepEqual(gn.coords, portalNode.coords);
				assert.equal(gn.type, "ELEVATOR");
			}
		});

		it("Idempotency: re-running syncPortalAcrossBuildingFloors updates existing group nodes in place without creating duplicates", () => {
			const baseStore = createDefaultGeometryStore();
			const portalNode = {
				id: "portal-g",
				type: "STAIRS",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.80988, 10.84115],
				portalGroupId: "HCM_STAIR_IDEMP",
			};
			const first = syncPortalAcrossBuildingFloors(
				{ ...baseStore, navNodes: [...baseStore.navNodes, portalNode] },
				{ sourceNodeId: "portal-g", targetFloorOrders: [0, 1, 2] },
			);
			const second = syncPortalAcrossBuildingFloors(first, {
				sourceNodeId: "portal-g",
				targetFloorOrders: [0, 1, 2],
			});
			const groupNodes = second.navNodes.filter(
				(n) => n.portalGroupId === "HCM_STAIR_IDEMP",
			);
			assert.equal(groupNodes.length, 3);
		});

		it("Exception / Uncheck Floor: removing a floor from targetFloorOrders cleanly deletes that floor's portal and its incident edges", () => {
			const baseStore = createDefaultGeometryStore();
			const portalNode = {
				id: "portal-g",
				type: "STAIRS",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.80988, 10.84115],
				portalGroupId: "HCM_STAIR_REM",
			};
			const synced3 = syncPortalAcrossBuildingFloors(
				{ ...baseStore, navNodes: [...baseStore.navNodes, portalNode] },
				{ sourceNodeId: "portal-g", targetFloorOrders: [0, 1, 2] },
			);
			const synced2 = syncPortalAcrossBuildingFloors(synced3, {
				sourceNodeId: "portal-g",
				targetFloorOrders: [0, 1],
			});
			const groupNodes = synced2.navNodes.filter(
				(n) => n.portalGroupId === "HCM_STAIR_REM",
			);
			assert.deepEqual(
				groupNodes.map((n) => n.floorOrder).sort(),
				[0, 1],
			);
		});
	});

	describe("4. Courtyard Hole Awareness & Route Diagnostics", () => {
		it("Happy Path: classifies central VOID / courtyard hole (Hồ Sen) on Floor 0 as OUTDOOR rather than indoor", () => {
			const store = createDefaultGeometryStore();
			const voidFace = [
				[106.8098, 10.8411],
				[106.8099, 10.8411],
				[106.8099, 10.8412],
				[106.8098, 10.8412],
				[106.8098, 10.8411],
			];
			const customStore = {
				...store,
				faceAssignments: [
					...(store.faceAssignments || []),
					{
						id: "fa-void-test",
						building: "Tòa HCM",
						floorOrder: 0,
						seedCoord: [106.80985, 10.84115],
						role: "VOID",
					},
				],
				walls: [
					...(store.walls || []),
					{
						id: "w-void-test",
						building: "Tòa HCM",
						floorOrder: 0,
						line: voidFace,
					},
				],
			};
			assert.equal(
				isPointIndoorOnFloor(customStore, "Tòa HCM", 0, [106.80985, 10.84115]),
				false,
			);
		});

		it("Exception / Diagnostic Reason: computeDigitizedRoute attaches diagnostic failureReason when graph components are disconnected", () => {
			const isolatedStore = {
				version: 3,
				walls: [],
				faceAssignments: [],
				doors: [],
				buildingBoundaries: [],
				roomPolygons: [],
				navNodes: [
					{
						id: "island-a",
						type: "NODE",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8095, 10.841],
					},
					{
						id: "island-b",
						type: "NODE",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8105, 10.842],
					},
				],
				navEdges: [], // Zero edges connecting island-a and island-b
			};

			const diag = {};
			const res = computeDigitizedRoute({
				store: isolatedStore,
				startCoords: [106.8095, 10.841],
				startBuilding: "Tòa HCM",
				startFloorOrder: 0,
				endCoords: [106.8105, 10.842],
				endBuilding: "Tòa HCM",
				endFloorOrder: 0,
				diagnosticsOut: diag,
			});
			assert.equal(res, null);
			assert.equal(diag.reasonCode, "DISCONNECTED_GRAPH");
			assert.ok(diag.message.includes("cụm đồ thị"));
		});
	});

	describe("5. Regression Fixes: Exterior Wall Crossing, Courtyard VOID Isolation, Same-Room Routing & ALL Filter", () => {
		const outerSquareWalls = (building) => [
			{
				id: "w-outer",
				building,
				floorOrder: 0,
				line: [
					[106.809, 10.841],
					[106.81, 10.841],
					[106.81, 10.842],
					[106.809, 10.842],
					[106.809, 10.841],
				],
			},
			{
				id: "w-void",
				building,
				floorOrder: 0,
				line: [
					[106.8094, 10.8414],
					[106.8096, 10.8414],
					[106.8096, 10.8416],
					[106.8094, 10.8416],
					[106.8094, 10.8414],
				],
			},
		];

		it("Bug 1a: classifies Floor-0 node inside a VOID courtyard face as Outdoor when precomputedModels is supplied", () => {
			const store = {
				version: 3,
				buildingBoundaries: [
					{
						id: "bnd-hcm-0",
						building: "Tòa HCM",
						floorOrder: 0,
						ring: [
							[106.809, 10.841],
							[106.81, 10.841],
							[106.81, 10.842],
							[106.809, 10.842],
							[106.809, 10.841],
						],
					},
				],
				walls: outerSquareWalls("Tòa HCM"),
				faceAssignments: [
					{
						id: "fa-void",
						building: "Tòa HCM",
						floorOrder: 0,
						seedCoord: [106.8095, 10.8415],
						role: "VOID",
					},
				],
				doors: [],
				navNodes: [],
				navEdges: [],
			};
			const models = buildFloorWallGraphModels(store);
			const courtyardNode = {
				id: "court-1",
				type: "OUTDOOR",
				building: "OUTDOOR",
				floorOrder: 0,
				coords: [106.8095, 10.8415],
			};
			const corridorNode = {
				id: "cor-1",
				type: "NODE",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.8092, 10.8412],
			};

			assert.equal(
				isNodeOutdoor(store.buildingBoundaries, courtyardNode, models),
				true,
			);
			assert.equal(
				isNodeOutdoor(store.buildingBoundaries, corridorNode, models),
				false,
			);
		});

		it("Bug 1b: starting OUTDOOR outside building enters strictly via outer perimeter DOOR even when indoor STAIRS is closer and courtyard VOID nodes exist", () => {
			const store = {
				version: 3,
				buildingBoundaries: [
					{
						id: "bnd-hcm-0",
						building: "Tòa HCM",
						floorOrder: 0,
						ring: [
							[106.809, 10.841],
							[106.81, 10.841],
							[106.81, 10.842],
							[106.809, 10.842],
							[106.809, 10.841],
						],
					},
				],
				walls: outerSquareWalls("Tòa HCM"),
				faceAssignments: [
					{
						id: "fa-void",
						building: "Tòa HCM",
						floorOrder: 0,
						seedCoord: [106.8095, 10.8415],
						role: "VOID",
					},
					{
						id: "fa-cor",
						building: "Tòa HCM",
						floorOrder: 0,
						seedCoord: [106.8092, 10.8412],
						role: "CORRIDOR",
					},
				],
				// Outer perimeter DOOR on the EAST wall ([106.81, 10.8415])
				doors: [
					{
						id: "door-east-gate",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.81, 10.8415],
						widthMeters: 2.0,
					},
				],
				// ZERO external sidewalk nodes; SW indoor STAIRS sits close to SW street click
				navNodes: [
					{
						id: "n-stair-sw",
						type: "STAIRS",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.80908, 10.84108],
					},
					{
						id: "n-cor-east",
						type: "NODE",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.80985, 10.8415],
					},
					{
						id: "n-courtyard-void",
						type: "OUTDOOR",
						building: "OUTDOOR",
						floorOrder: 0,
						coords: [106.8095, 10.8415],
					},
				],
				navEdges: [
					["n-stair-sw", "n-cor-east"],
					["n-courtyard-void", "n-cor-east"],
				],
			};

			// Click outside SW wall (much closer to n-stair-sw than to door-east-gate!)
			const route = computeDigitizedRoute({
				store,
				startCoords: [106.8089, 10.8409],
				startBuilding: "OUTDOOR",
				startFloorOrder: 0,
				endCoords: [106.80985, 10.8415],
				endBuilding: "Tòa HCM",
				endFloorOrder: 0,
			});

			assert.ok(route, "Expected route to enter via outer perimeter DOOR");
			assert.equal(route.pathNodes[0].id, "__door_door-east-gate__");
			assert.ok(
				!route.pathNodes.some((n) => n.id === "n-stair-sw"),
				"Must NEVER jump across solid outer wall onto indoor STAIRS",
			);
			assert.ok(
				!route.pathNodes.some((n) => n.id === "n-courtyard-void"),
				"Must NEVER jump from outside street over roof into central VOID courtyard",
			);
		});

		it("Bug 1c: starting OUTDOOR outside building with zero exterior nodes and zero outer doors fails cleanly with MISSING_ENTRANCE_LINK", () => {
			const store = {
				version: 3,
				buildingBoundaries: [
					{
						id: "bnd-hcm-0",
						building: "Tòa HCM",
						floorOrder: 0,
						ring: [
							[106.809, 10.841],
							[106.81, 10.841],
							[106.81, 10.842],
							[106.809, 10.842],
							[106.809, 10.841],
						],
					},
				],
				walls: outerSquareWalls("Tòa HCM"),
				faceAssignments: [],
				doors: [],
				navNodes: [
					{
						id: "n-stair-only",
						type: "STAIRS",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8091, 10.8411],
					},
				],
				navEdges: [],
			};
			const diag = {};
			const res = computeDigitizedRoute({
				store,
				startCoords: [106.8088, 10.8408],
				startBuilding: "OUTDOOR",
				startFloorOrder: 0,
				endCoords: [106.8091, 10.8411],
				endBuilding: "Tòa HCM",
				endFloorOrder: 0,
				diagnosticsOut: diag,
			});
			assert.equal(res, null);
			assert.equal(diag.reasonCode, "MISSING_ENTRANCE_LINK");
		});

		it("Bug 2: both start & end inside the exact same ROOM route directly inside the room without bouncing out to perimeter DOOR", () => {
			const store = {
				version: 3,
				buildingBoundaries: [
					{
						id: "bnd-lib",
						building: "Tòa HCM",
						floorOrder: 0,
						ring: [
							[106.809, 10.841],
							[106.81, 10.841],
							[106.81, 10.842],
							[106.809, 10.842],
							[106.809, 10.841],
						],
					},
				],
				walls: [
					{
						id: "w-lib",
						building: "Tòa HCM",
						floorOrder: 0,
						line: [
							[106.8092, 10.8412],
							[106.8096, 10.8412],
							[106.8096, 10.8416],
							[106.8092, 10.8416],
							[106.8092, 10.8412],
						],
					},
				],
				faceAssignments: [
					{
						id: "fa-lib",
						building: "Tòa HCM",
						floorOrder: 0,
						seedCoord: [106.8094, 10.8414],
						role: "ROOM",
						label: "Thư Viện",
					},
				],
				doors: [
					{
						id: "door-lib-bottom",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8094, 10.8412],
						widthMeters: 1.5,
					},
				],
				navNodes: [
					{
						id: "cor-outside-lib",
						type: "NODE",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8094, 10.8411],
					},
				],
				navEdges: [],
			};

			// Two points near the top half of Thư Viện (~11m apart), far from bottom door
			const route = computeDigitizedRoute({
				store,
				startCoords: [106.8093, 10.8415],
				startBuilding: "Tòa HCM",
				startFloorOrder: 0,
				endCoords: [106.8094, 10.84155],
				endBuilding: "Tòa HCM",
				endFloorOrder: 0,
			});

			assert.ok(route, "Expected direct intra-room route");
			assert.ok(
				!route.pathNodes.some((n) => n.id === "__door_door-lib-bottom__"),
				"Must NOT bounce out to bottom room door when start & end are inside the same room",
			);
			assert.ok(
				route.totalDistanceMeters <= 16,
				`Expected short intra-room walk (~12m), got ${route.totalDistanceMeters}m`,
			);
		});

		it("Scope Filter: isNodeVisibleOnFloor supports activeBuilding='ALL' and activeFloorOrder='ALL'", () => {
			const boundaries = [];
			const nHcm0 = { id: "a", building: "Tòa HCM", floorOrder: 0, type: "NODE", coords: [106.8095, 10.8415] };
			const nEps2 = { id: "b", building: "Tòa Epsilon", floorOrder: 2, type: "STAIRS", coords: [106.8114, 10.8405] };

			assert.equal(isNodeVisibleOnFloor(boundaries, nHcm0, "ALL", "ALL"), true);
			assert.equal(isNodeVisibleOnFloor(boundaries, nEps2, "ALL", "ALL"), true);
			assert.equal(isNodeVisibleOnFloor(boundaries, nEps2, "Tòa HCM", "ALL"), false);
			assert.equal(isNodeVisibleOnFloor(boundaries, nEps2, "Tòa Epsilon", "ALL"), true);
			assert.equal(isNodeVisibleOnFloor(boundaries, nEps2, "ALL", 0), false);
			assert.equal(isNodeVisibleOnFloor(boundaries, nEps2, "ALL", 2), true);
		});
	});
});
