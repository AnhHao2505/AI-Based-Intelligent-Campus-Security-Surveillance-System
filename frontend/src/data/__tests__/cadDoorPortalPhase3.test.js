import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	auditNavigationConnectivity,
	buildFloorWallGraphModels,
	buildOrientedDoorGeometry,
	buildVector3dLayers,
	computeDigitizedRoute,
	createDefaultGeometryStore,
	deduplicateFloorVertices,
	dragWeldedWallVertices,
	isDoorConnectedToGraph,
	isNodeOutdoor,
	isNodeVisibleOnFloor,
	isPointIndoorOnFloor,
	mergeOrSplitNavNodeOnDrop,
	resolveTestRouteClickContext,
	samplePointAlongRoute,
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

	describe("6. Multi-Floor Click Resolution, Cross-Building Outdoor Gateway & Real-Time Route Playback", () => {
		it("Q1 & Q3a: clicking outdoor ground [106.810623, 10.839819] or [106.811957, 10.840825] while viewing Floor 1 or Floor 2 strictly resolves to OUTDOOR floor 0", () => {
			const def = createDefaultGeometryStore();
			const models = buildFloorWallGraphModels(def);

			const clickStreetWhileOnFloor2 = resolveTestRouteClickContext({
				store: def,
				rawCoord: [106.810623, 10.839819],
				hitBuilding: null,
				hitFloor: null,
				scopeFloorNum: 2,
				activeBuilding: "Tòa HCM",
				isAllBuildings: false,
				precomputedModels: models,
			});
			assert.deepEqual(clickStreetWhileOnFloor2, {
				building: "OUTDOOR",
				floorOrder: 0,
				isOutdoor: true,
			});

			const clickFarEastWhileOnFloor2 = resolveTestRouteClickContext({
				store: def,
				rawCoord: [106.811957, 10.840825],
				hitBuilding: null,
				hitFloor: null,
				scopeFloorNum: 2,
				activeBuilding: "Tòa HCM",
				isAllBuildings: false,
				precomputedModels: models,
			});
			assert.deepEqual(clickFarEastWhileOnFloor2, {
				building: "OUTDOOR",
				floorOrder: 0,
				isOutdoor: true,
			});

			const clickInsideFloor1Room = resolveTestRouteClickContext({
				store: def,
				rawCoord: [106.8103, 10.8412],
				hitBuilding: "Tòa HCM",
				hitFloor: 1,
				scopeFloorNum: 1,
				activeBuilding: "Tòa HCM",
				isAllBuildings: false,
				precomputedModels: models,
			});
			assert.deepEqual(clickInsideFloor1Room, {
				building: "Tòa HCM",
				floorOrder: 1,
				isOutdoor: false,
			});
		});

		it("Q3b: routes bidirectionally between distant OUTDOOR street and Tòa HCM Floor 2 even when Tòa Epsilon outer door is closer", () => {
			const store = {
				version: 3,
				buildingBoundaries: [
					{
						id: "bnd-hcm-0",
						building: "Tòa HCM",
						floorOrder: 0,
						ring: [
							[106.8095, 10.841],
							[106.8104, 10.841],
							[106.8104, 10.8416],
							[106.8095, 10.8416],
							[106.8095, 10.841],
						],
					},
					{
						id: "bnd-eps-0",
						building: "Tòa Epsilon",
						floorOrder: 0,
						ring: [
							[106.811, 10.8401],
							[106.8116, 10.8401],
							[106.8116, 10.8406],
							[106.811, 10.8406],
							[106.811, 10.8401],
						],
					},
				],
				walls: [
					{
						id: "w-hcm-0",
						building: "Tòa HCM",
						floorOrder: 0,
						line: [
							[106.8095, 10.841],
							[106.8104, 10.841],
							[106.8104, 10.8416],
							[106.8095, 10.8416],
							[106.8095, 10.841],
						],
					},
					{
						id: "w-eps-0",
						building: "Tòa Epsilon",
						floorOrder: 0,
						line: [
							[106.811, 10.8401],
							[106.8116, 10.8401],
							[106.8116, 10.8406],
							[106.811, 10.8406],
							[106.811, 10.8401],
						],
					},
				],
				faceAssignments: [],
				doors: [
					{
						id: "door-hcm-south",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8101, 10.841],
						widthMeters: 2.0,
					},
					{
						id: "door-eps-south",
						building: "Tòa Epsilon",
						floorOrder: 0,
						coords: [106.8112, 10.8401],
						widthMeters: 2.0,
					},
				],
				navNodes: [
					{
						id: "hcm-0-cor",
						type: "NODE",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8101, 10.8412],
					},
					{
						id: "hcm-0-stair",
						type: "STAIRS",
						portalGroupId: "HCM_STAIR_W",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8098, 10.8413],
					},
					{
						id: "hcm-2-stair",
						type: "STAIRS",
						portalGroupId: "HCM_STAIR_W",
						building: "Tòa HCM",
						floorOrder: 2,
						coords: [106.8098, 10.8413],
					},
					{
						id: "eps-0-cor",
						type: "NODE",
						building: "Tòa Epsilon",
						floorOrder: 0,
						coords: [106.8112, 10.8403],
					},
				],
				navEdges: [["hcm-0-cor", "hcm-0-stair"]],
			};

			// [106.810623, 10.839819] is closer to Epsilon door (~70m) than HCM door (~143m)
			const dFwd = {};
			const routeFwd = computeDigitizedRoute({
				store,
				startCoords: [106.810623, 10.839819],
				startBuilding: "OUTDOOR",
				startFloorOrder: 0,
				endCoords: [106.8099, 10.84135],
				endBuilding: "Tòa HCM",
				endFloorOrder: 2,
				diagnosticsOut: dFwd,
			});
			assert.ok(routeFwd, `Expected forward route to succeed, got ${JSON.stringify(dFwd)}`);
			assert.equal(routeFwd.pathNodes[0].id, "__door_door-hcm-south__");
			assert.ok(routeFwd.floorsClimbed >= 2);

			const dRev = {};
			const routeRev = computeDigitizedRoute({
				store,
				startCoords: [106.8099, 10.84135],
				startBuilding: "Tòa HCM",
				startFloorOrder: 2,
				endCoords: [106.810623, 10.839819],
				endBuilding: "OUTDOOR",
				endFloorOrder: 0,
				diagnosticsOut: dRev,
			});
			assert.ok(routeRev, `Expected swapped reverse route to succeed, got ${JSON.stringify(dRev)}`);
			assert.ok(
				routeRev.pathNodes.some((n) => n.id === "__door_door-hcm-south__"),
			);
		});

		it("Q3c: OUTDOOR -> OUTDOOR between distant campus points routes purely outdoors without tunneling through Tòa Epsilon walls", () => {
			const store = {
				version: 3,
				buildingBoundaries: [
					{
						id: "bnd-eps-0",
						building: "Tòa Epsilon",
						floorOrder: 0,
						ring: [
							[106.811093, 10.840192],
							[106.811766, 10.840192],
							[106.811766, 10.840864],
							[106.811093, 10.840864],
							[106.811093, 10.840192],
						],
					},
				],
				walls: [
					{
						id: "w-eps-0",
						building: "Tòa Epsilon",
						floorOrder: 0,
						line: [
							[106.811093, 10.840192],
							[106.811766, 10.840192],
							[106.811766, 10.840864],
							[106.811093, 10.840864],
							[106.811093, 10.840192],
						],
					},
				],
				faceAssignments: [],
				doors: [
					{
						id: "door-eps-ent",
						building: "Tòa Epsilon",
						floorOrder: 0,
						coords: [106.811261, 10.840192],
						widthMeters: 1.8,
					},
				],
				navNodes: [
					{
						id: "eps-0-cor",
						type: "NODE",
						building: "Tòa Epsilon",
						floorOrder: 0,
						coords: [106.8113, 10.84035],
					},
				],
				navEdges: [],
			};

			const route = computeDigitizedRoute({
				store,
				startCoords: [106.810623, 10.839819],
				startBuilding: "OUTDOOR",
				startFloorOrder: 0,
				endCoords: [106.811957, 10.840825],
				endBuilding: "OUTDOOR",
				endFloorOrder: 0,
			});
			assert.ok(route, "Expected outdoor-to-outdoor campus walk to succeed");
			assert.ok(
				route.geojson.features.every((f) => f.properties?.isOutdoor === true),
				"All segments between two strictly exterior campus points must stay OUTDOOR",
			);
			assert.ok(
				!route.pathNodes.some((n) => n.id === "__door_door-eps-ent__"),
				"Must NOT detour inside an unrelated building door between two outdoor street clicks",
			);
		});

		it("Q3d: distinguishes MISSING_ENTRANCE_LINK vs MISSING_PORTAL_LINK accurately on multi-floor failures", () => {
			// Tòa HCM has connected STAIRS 0<->2, but ZERO Floor-0 doors or outdoor sidewalk links
			const storeNoDoor = {
				version: 3,
				buildingBoundaries: [
					{
						id: "bnd-hcm-0",
						building: "Tòa HCM",
						floorOrder: 0,
						ring: [
							[106.8095, 10.841],
							[106.8104, 10.841],
							[106.8104, 10.8416],
							[106.8095, 10.8416],
							[106.8095, 10.841],
						],
					},
				],
				walls: [
					{
						id: "w-hcm-0",
						building: "Tòa HCM",
						floorOrder: 0,
						line: [
							[106.8095, 10.841],
							[106.8104, 10.841],
							[106.8104, 10.8416],
							[106.8095, 10.8416],
							[106.8095, 10.841],
						],
					},
				],
				faceAssignments: [],
				doors: [],
				navNodes: [
					{
						id: "hcm-0-stair",
						type: "STAIRS",
						portalGroupId: "HCM_STAIR_W",
						building: "Tòa HCM",
						floorOrder: 0,
						coords: [106.8098, 10.8413],
					},
					{
						id: "hcm-2-stair",
						type: "STAIRS",
						portalGroupId: "HCM_STAIR_W",
						building: "Tòa HCM",
						floorOrder: 2,
						coords: [106.8098, 10.8413],
					},
				],
				navEdges: [],
			};
			const diagNoDoor = {};
			const resNoDoor = computeDigitizedRoute({
				store: storeNoDoor,
				startCoords: [106.810623, 10.839819],
				startBuilding: "OUTDOOR",
				startFloorOrder: 0,
				endCoords: [106.8098, 10.8413],
				endBuilding: "Tòa HCM",
				endFloorOrder: 2,
				diagnosticsOut: diagNoDoor,
			});
			assert.equal(resNoDoor, null);
			assert.equal(diagNoDoor.reasonCode, "MISSING_ENTRANCE_LINK");
		});

		it("Q1 Playback: samplePointAlongRoute interpolates outdoor -> floor 0 -> stairs -> upper floor with trailGeoJSON & boundaries", () => {
			assert.equal(samplePointAlongRoute(null, 0.5), null);
			const sampleRoute = {
				totalDistanceMeters: 50,
				floorsClimbed: 1,
				geojson: {
					type: "FeatureCollection",
					features: [
						{
							type: "Feature",
							properties: { floorOrder: 0, toFloorOrder: 0, building: "OUTDOOR", isOutdoor: true, isStair: false },
							geometry: { type: "LineString", coordinates: [[106.8101, 10.8405], [106.8101, 10.8407]] },
						},
						{
							type: "Feature",
							properties: { floorOrder: 0, toFloorOrder: 1, building: "Tòa HCM", isOutdoor: false, isStair: true },
							geometry: { type: "LineString", coordinates: [[106.8101, 10.8407], [106.8101, 10.8407]] },
						},
						{
							type: "Feature",
							properties: { floorOrder: 1, toFloorOrder: 1, building: "Tòa HCM", isOutdoor: false, isStair: false },
							geometry: { type: "LineString", coordinates: [[106.8101, 10.8407], [106.8103, 10.8407]] },
						},
					],
				},
			};

			const atStart = samplePointAlongRoute(sampleRoute, -0.2);
			assert.deepEqual(atStart.coords, [106.8101, 10.8405]);
			assert.equal(atStart.floorOrder, 0);
			assert.equal(atStart.isOutdoor, true);

			const atMidStair = samplePointAlongRoute(sampleRoute, 0.5);
			assert.ok(atMidStair.elevationMeters > 0.2 && atMidStair.elevationMeters < 3.6);
			assert.ok(Array.isArray(atMidStair.trailGeoJSON.features));
			assert.ok(atMidStair.trailGeoJSON.features.length >= 1);

			const atEnd = samplePointAlongRoute(sampleRoute, 1.5);
			assert.deepEqual(atEnd.coords, [106.8103, 10.8407]);
			assert.equal(atEnd.floorOrder, 1);
			assert.equal(atEnd.isOutdoor, false);
		});
	});

	describe("7. Door Icon Connection Status & Navigation Connectivity Validator (Mục 2)", () => {
		const buildConnectivityFixture = () => ({
			version: 3,
			buildingBoundaries: [
				{
					id: "bnd-hcm-0",
					building: "Tòa HCM",
					floorOrder: 0,
					ring: [
						[106.8095, 10.841],
						[106.8104, 10.841],
						[106.8104, 10.8416],
						[106.8095, 10.8416],
						[106.8095, 10.841],
					],
				},
				{
					id: "bnd-hcm-1",
					building: "Tòa HCM",
					floorOrder: 1,
					ring: [
						[106.8095, 10.841],
						[106.8104, 10.841],
						[106.8104, 10.8416],
						[106.8095, 10.8416],
						[106.8095, 10.841],
					],
				},
			],
			walls: [],
			faceAssignments: [],
			doors: [
				{
					id: "door-wired",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8099, 10.8412],
					widthMeters: 1.5,
				},
				{
					id: "door-unwired",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8102, 10.8412],
					widthMeters: 1.5,
				},
			],
			navNodes: [
				{
					id: "n-cor-0",
					type: "NODE",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8098, 10.8413],
				},
				{
					id: "n-door-0",
					type: "NODE",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8099, 10.8412], // snapped directly to door-wired
				},
				{
					id: "n-unwired-isolated",
					type: "NODE",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8102, 10.8412], // isolated dot at door-unwired, 0 edges
				},
				{
					id: "stair-0",
					type: "STAIRS",
					portalGroupId: "HCM_STAIR_1",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8097, 10.8413],
				},
				{
					// Synced vertically via portalGroupId, but has ZERO same-floor horizontal edge on Floor 1!
					id: "stair-1",
					type: "STAIRS",
					portalGroupId: "HCM_STAIR_1",
					building: "Tòa HCM",
					floorOrder: 1,
					coords: [106.8097, 10.8413],
				},
				{
					// Elevator properly wired horizontally on Floor 0
					id: "elev-0",
					type: "ELEVATOR",
					portalGroupId: "HCM_EL_1",
					building: "Tòa HCM",
					floorOrder: 0,
					coords: [106.8100, 10.8414],
				},
				{
					id: "elev-1",
					type: "ELEVATOR",
					portalGroupId: "HCM_EL_1",
					building: "Tòa HCM",
					floorOrder: 1,
					coords: [106.8100, 10.8414],
				},
				{
					id: "n-cor-1",
					type: "NODE",
					building: "Tòa HCM",
					floorOrder: 1,
					coords: [106.8101, 10.8414],
				},
			],
			navEdges: [
				["n-cor-0", "n-door-0"],
				["n-cor-0", "stair-0"],
				["n-cor-0", "elev-0"],
				["elev-1", "n-cor-1"],
			],
		});

		it("Happy Path vs Unlinked Detection: marks wired Door true, unwired Door false, and flags PORTAL_NO_FLOOR_EDGE & DOOR_NO_GRAPH_LINK", () => {
			const store = buildConnectivityFixture();

			const auditF0 = auditNavigationConnectivity({
				store,
				building: "Tòa HCM",
				floorOrder: 0,
			});
			assert.equal(auditF0.doorStatusById.get("door-wired"), true);
			assert.equal(auditF0.doorStatusById.get("door-unwired"), false);
			assert.equal(auditF0.portalHasFloorEdgeById.get("stair-0"), true);
			assert.equal(auditF0.portalHasFloorEdgeById.get("elev-0"), true);
			assert.ok(
				auditF0.issues.some(
					(i) => i.code === "DOOR_NO_GRAPH_LINK" && i.targetId === "door-unwired",
				),
				"Must warn about door-unwired lacking connected graph edges",
			);
			assert.ok(
				!auditF0.issues.some((i) => i.targetId === "door-wired"),
				"Wired door must NOT trigger warning",
			);

			const auditF1 = auditNavigationConnectivity({
				store,
				building: "Tòa HCM",
				floorOrder: 1,
			});
			// stair-1 has auto vertical edge to stair-0, so only checking same-floor horizontal edge catches it!
			assert.equal(auditF1.portalHasFloorEdgeById.get("stair-1"), false);
			assert.equal(auditF1.portalHasFloorEdgeById.get("elev-1"), true);
			assert.ok(
				auditF1.issues.some(
					(i) =>
						i.code === "PORTAL_NO_FLOOR_EDGE" &&
						i.level === "error" &&
						i.targetId === "stair-1",
				),
				"Must flag STAIRS on Floor 1 with only auto vertical edge and 0 horizontal corridor edges",
			);
			assert.ok(
				!auditF1.issues.some((i) => i.targetId === "elev-1"),
				"Connected ELEVATOR on Floor 1 must NOT trigger error",
			);
		});

		it("Boundary / Scope: handles ALL building/floor filter cleanly and respects 0.65m midpoint snap tolerance", () => {
			const store = buildConnectivityFixture();
			const auditAll = auditNavigationConnectivity({
				store,
				building: "ALL",
				floorOrder: "ALL",
			});
			assert.equal(auditAll.portalHasFloorEdgeById.get("stair-1"), false);
			assert.equal(auditAll.doorStatusById.get("door-wired"), true);

			// Isolated edge-less node within tolerance still counts as NOT connected
			assert.equal(
				isDoorConnectedToGraph(
					store.doors[1],
					store.navNodes,
					store.navEdges,
				),
				false,
			);
			// Different floor node at identical [lng, lat] does NOT falsely mark Door on Floor 0 wired
			const doorF1 = { ...store.doors[0], id: "d-other-fl", floorOrder: 1 };
			assert.equal(
				isDoorConnectedToGraph(doorF1, store.navNodes, store.navEdges),
				false,
			);
		});

		it("Exception / Empty Safe Guard: handles null or empty store without throwing", () => {
			const emptyAudit = auditNavigationConnectivity({
				store: null,
				building: "Tòa HCM",
				floorOrder: 0,
			});
			assert.deepEqual(emptyAudit.issues, []);
			assert.equal(emptyAudit.doorStatusById.size, 0);
			assert.equal(isDoorConnectedToGraph(null, [], []), false);
		});
	});
});
