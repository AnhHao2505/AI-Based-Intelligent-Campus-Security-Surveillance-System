import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	buildOrientedDoorGeometry,
	computeDigitizedRoute,
	createDefaultGeometryStore,
	deduplicateFloorVertices,
	dragWeldedWallVertices,
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

		it("Boundary / Clamp: clamps excessive door width to host wall segment length and bounds minimum width to >= 0.5m", () => {
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
			const wideDoor = {
				id: "door-wide",
				building: "Tòa HCM",
				floorOrder: 0,
				coords: [106.80981, 10.8411],
				widthMeters: 9.0,
			};
			const geomWide = buildOrientedDoorGeometry(wideDoor, wallSegments);
			assert.ok(geomWide.widthMeters <= 2.2);

			const tinyDoor = {
				...wideDoor,
				widthMeters: 0.1,
			};
			const geomTiny = buildOrientedDoorGeometry(tinyDoor, wallSegments);
			assert.ok(geomTiny.widthMeters >= 0.5);
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
});
