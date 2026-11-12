import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	BOUNDARY_WALL_HEIGHT,
	FLOOR_ELEVATION_STEP,
	FLOOR_SLAB_HEIGHT,
	buildAllGraphEdges,
	buildOrientedBoxRing,
	buildRoute3dLayers,
	buildVector3dLayers,
	computeDigitizedRoute,
	createDefaultGeometryStore,
	isNodeOutdoor,
	resolveBestSnapTarget,
} from "../campusIndoor3dData.js";

/** Approximate angle in degrees (0..180) between segment [a, b] and horizontal East axis in local meters */
function edgeAzimuthDeg(a, b) {
	const midLat = ((a[1] + b[1]) / 2) * (Math.PI / 180);
	const kx = 111320 * Math.cos(midLat);
	const ky = 110574;
	const dx = (b[0] - a[0]) * kx;
	const dy = (b[1] - a[1]) * ky;
	return (Math.atan2(dy, dx) * 180) / Math.PI;
}

/** Corner interior angle in degrees between BA and BC in local meters */
function cornerAngleDeg(a, b, c) {
	const midLat = b[1] * (Math.PI / 180);
	const kx = 111320 * Math.cos(midLat);
	const ky = 110574;
	const u = [(a[0] - b[0]) * kx, (a[1] - b[1]) * ky];
	const v = [(c[0] - b[0]) * kx, (c[1] - b[1]) * ky];
	const lu = Math.hypot(u[0], u[1]);
	const lv = Math.hypot(v[0], v[1]);
	const dot = Math.max(-1, Math.min(1, (u[0] * v[0] + u[1] * v[1]) / (lu * lv)));
	return (Math.acos(dot) * 180) / Math.PI;
}

describe("CAD Phase 1 - Oriented 2-Click Box (Q2)", () => {
	const hcmCorners = createDefaultGeometryStore().floorCalibrations["Tòa Alpha__0"].corners;

	it("Happy Path: aligns 2-Click Box strictly with ~45 deg calibrated PNG orientation and 90 deg corners", () => {
		const p0 = [106.80985, 10.84145];
		const p1 = [106.81018, 10.84136];
		const ring = buildOrientedBoxRing(p0, p1, { corners: hcmCorners });

		assert.ok(Array.isArray(ring), "Expected a closed polygon ring");
		assert.equal(ring.length, 5, "Rectangle ring must contain 5 points (closed)");
		assert.deepEqual(ring[0], ring[4], "First and last ring vertices must close identically");

		const pngEdgeAngle = edgeAzimuthDeg(hcmCorners[0], hcmCorners[1]);
		const boxEdgeAngle = edgeAzimuthDeg(ring[0], ring[1]);
		const angularDiff = Math.min(
			Math.abs(((boxEdgeAngle - pngEdgeAngle + 540) % 180) - 90 + 90) % 90,
			Math.abs(((boxEdgeAngle - pngEdgeAngle) % 90 + 90) % 90),
		);
		assert.ok(
			angularDiff < 1.5,
			`Expected box edge to align with rotated PNG (~${pngEdgeAngle.toFixed(1)}°), got diff=${angularDiff.toFixed(2)}°`,
		);

		const angle0 = cornerAngleDeg(ring[3], ring[0], ring[1]);
		const angle1 = cornerAngleDeg(ring[0], ring[1], ring[2]);
		assert.ok(Math.abs(angle0 - 90) < 0.8, `Expected 90° corner, got ${angle0.toFixed(2)}°`);
		assert.ok(Math.abs(angle1 - 90) < 0.8, `Expected 90° corner, got ${angle1.toFixed(2)}°`);
	});

	it("Shift override / missing corners builds 0-degree horizontal-vertical box", () => {
		const p0 = [106.8098, 10.8414];
		const p1 = [106.8101, 10.8416];
		const ring = buildOrientedBoxRing(p0, p1, {
			corners: hcmCorners,
			axisAligned: true,
		});

		assert.equal(ring.length, 5);
		const lngs = new Set(ring.map((c) => c[0]));
		const lats = new Set(ring.map((c) => c[1]));
		assert.equal(lngs.size, 2, "Axis-aligned box must share exactly 2 distinct longitudes");
		assert.equal(lats.size, 2, "Axis-aligned box must share exactly 2 distinct latitudes");
	});

	it("Validation guard returns null for degenerate / zero-width clicks", () => {
		const p0 = [106.80985, 10.84145];
		assert.equal(
			buildOrientedBoxRing(p0, p0, { corners: hcmCorners }),
			null,
			"Identical corners must return null",
		);
		// Collinear click along PNG U axis only (~10m long, 0m wide)
		const dx = hcmCorners[1][0] - hcmCorners[0][0];
		const dy = hcmCorners[1][1] - hcmCorners[0][1];
		const pCollinear = [p0[0] + dx * 0.1, p0[1] + dy * 0.1];
		assert.equal(
			buildOrientedBoxRing(p0, pCollinear, { corners: hcmCorners }),
			null,
			"Zero-thickness sliver along PNG axis must return null",
		);
	});
});

describe("CAD Phase 1 - Screen-Pixel Snap Priority (VERTEX > MIDPOINT > EDGE)", () => {
	// Mock linear projection where 1e-5 deg = 10px -> easy pixel arithmetic
	const pxScale = 1_000_000;
	const origin = [106.81, 10.84];
	const projectFn = ([lng, lat]) => ({
		x: (lng - origin[0]) * pxScale,
		y: (origin[1] - lat) * pxScale,
	});
	// Segment from (0px, 0px) to (100px, 0px), Midpoint at (50px, 0px)
	const a = [origin[0], origin[1]];
	const b = [origin[0] + 100 / pxScale, origin[1]];
	const vertices = [
		{ coord: a, ownerId: "wall-1" },
		{ coord: b, ownerId: "wall-1" },
	];
	const segments = [{ seg: [a, b], ownerId: "wall-1" }];

	it("Respects strict priority VERTEX > MIDPOINT > EDGE within screen pixel threshold", () => {
		// Cursor 8px away from vertex `a` and 5px perpendicular from edge -> VERTEX wins
		const nearVertex = [a[0] + 8 / pxScale, a[1] + 5 / pxScale];
		const resV = resolveBestSnapTarget({
			rawLngLat: nearVertex,
			projectFn,
			vertices,
			segments,
		});
		assert.equal(resV?.type, "VERTEX");
		assert.deepEqual(resV.coord, a);

		// Cursor 7px away from midpoint (50px, 0) -> MIDPOINT wins over EDGE projection
		const nearMid = [origin[0] + 54 / pxScale, origin[1] - 4 / pxScale];
		const resM = resolveBestSnapTarget({
			rawLngLat: nearMid,
			projectFn,
			vertices,
			segments,
		});
		assert.equal(resM?.type, "MIDPOINT");
		assert.ok(Math.abs(resM.coord[0] - (a[0] + b[0]) / 2) < 1e-9);

		// Cursor at (25px, 6px) -> EDGE perpendicular projection at x = 25px, y = 0px
		const nearBody = [origin[0] + 25 / pxScale, origin[1] - 6 / pxScale];
		const resE = resolveBestSnapTarget({
			rawLngLat: nearBody,
			projectFn,
			vertices,
			segments,
		});
		assert.equal(resE?.type, "EDGE");
		assert.ok(Math.abs(resE.coord[0] - (origin[0] + 25 / pxScale)) < 1e-7);
		assert.ok(Math.abs(resE.coord[1] - origin[1]) < 1e-7);
	});

	it("Disables snap cleanly with altKeyDown or excludeOwnerId and obeys zoom pixel threshold", () => {
		const nearMid = [origin[0] + 50 / pxScale, origin[1] - 4 / pxScale];
		assert.equal(
			resolveBestSnapTarget({
				rawLngLat: nearMid,
				projectFn,
				vertices,
				segments,
				altKeyDown: true,
			}),
			null,
		);
		assert.equal(
			resolveBestSnapTarget({
				rawLngLat: nearMid,
				projectFn,
				vertices,
				segments,
				excludeOwnerId: "wall-1",
			}),
			null,
		);
		// Simulate zoomed out 4x (cursor now > 16px away -> no snap)
		const zoomedOutProj = ([lng, lat]) => ({
			x: (lng - origin[0]) * (pxScale * 4),
			y: (origin[1] - lat) * (pxScale * 4),
		});
		assert.equal(
			resolveBestSnapTarget({
				rawLngLat: nearMid,
				projectFn: zoomedOutProj,
				vertices,
				segments,
			}),
			null,
		);
	});
});

describe("CAD Phase 1 - Unified NODE + PORTAL Graph, Cross-Building Isolation & Geometric Outdoor Routing", () => {
	it("Main workflow: uniform NODE graph preserves exact Outdoor->Indoor->Multi-Floor A* route and 3D ribbon heights", () => {
		const baseStore = createDefaultGeometryStore();
		const baselineRoute = computeDigitizedRoute({
			store: baseStore,
			startCoords: [106.80915, 10.84072],
			startBuilding: "OUTDOOR",
			startFloorOrder: 0,
			endCoords: baseStore.roomPolygons.find((r) => r.building === "Tòa Alpha" && r.floorOrder === 2).ring[0],
			endBuilding: "Tòa Alpha",
			endFloorOrder: 2,
		});

		// Unify all CORRIDOR, OUTDOOR, ENTRANCE nodes into pure `type: "NODE"`
		const unifiedStore = {
			...baseStore,
			navNodes: baseStore.navNodes.map((n) =>
				n.type === "STAIRS" || n.type === "ELEVATOR"
					? n
					: { ...n, type: "NODE" },
			),
		};

		const unifiedRoute = computeDigitizedRoute({
			store: unifiedStore,
			startCoords: [106.80915, 10.84072],
			startBuilding: "OUTDOOR",
			startFloorOrder: 0,
			endCoords: baseStore.roomPolygons.find((r) => r.building === "Tòa Alpha" && r.floorOrder === 2).ring[0],
			endBuilding: "Tòa Alpha",
			endFloorOrder: 2,
		});

		assert.ok(baselineRoute && unifiedRoute);
		assert.equal(unifiedRoute.floorsClimbed, 2);
		assert.ok(
			Math.abs(unifiedRoute.totalDistanceMeters - baselineRoute.totalDistanceMeters) <= 2,
		);

		const layers3d = buildRoute3dLayers(unifiedRoute, {
			selectedBuilding: "Tòa Alpha",
			activeFloorOrder: 0,
		});
		const outdoorSegs = layers3d.ribbonsGeoJSON.features.filter(
			(f) => f.properties.baseHeight < FLOOR_SLAB_HEIGHT,
		);
		assert.ok(
			outdoorSegs.length > 0,
			"Expected geometric outdoor route ribbon segments resting near ground height (< slab top)",
		);
	});

	it("findClosestNode for startBuilding='OUTDOOR' does not lock onto closer indoor corridor node behind perimeter wall", () => {
		const store = createDefaultGeometryStore();
		const boundaries = store.buildingBoundaries;
		const outGate = store.navNodes.find((n) => n.id === "out-gate");
		const indoorSouth = store.navNodes.find((n) => n.id === "hcm-0-ent-s");

		assert.equal(isNodeOutdoor(boundaries, outGate), true);
		assert.equal(isNodeOutdoor(boundaries, indoorSouth), false);

		// Pick outdoor coordinate just 2m outside southern HCM wall (closer to indoor corridor than out-gate!)
		const hcm0Room004 = store.roomPolygons.find((r) => r.building === "Tòa Alpha" && r.floorOrder === 0);
		const wallStart = hcm0Room004.ring[2];
		const slightlyOutside = [wallStart[0], wallStart[1] - 0.00004];

		const route = computeDigitizedRoute({
			store,
			startCoords: slightlyOutside,
			startBuilding: "OUTDOOR",
			startFloorOrder: 0,
			endCoords: store.navNodes.find((n) => n.id === "eps-0-lobby").coords,
			endBuilding: "Tòa Epsilon",
			endFloorOrder: 0,
		});

		assert.ok(route);
		const graphFirstNode = route.pathNodes[0];
		assert.equal(
			isNodeOutdoor(boundaries, graphFirstNode),
			true,
			`Expected route starting OUTDOOR to attach to an outdoor node first, got ${graphFirstNode.id}`,
		);
	});

	it("Portal collision guard: two distinct buildings sharing portalGroupId='STAIR_SHARED' do not create cross-building edge", () => {
		const mockStore = {
			buildingBoundaries: [],
			roomPolygons: [],
			customWalls: [],
			navNodes: [
				{
					id: "a-0",
					type: "STAIRS",
					building: "Tòa Alpha",
					floorOrder: 0,
					portalGroupId: "STAIR_SHARED",
					coords: [106.8095, 10.8415],
				},
				{
					id: "a-1",
					type: "STAIRS",
					building: "Tòa Alpha",
					floorOrder: 1,
					portalGroupId: "STAIR_SHARED",
					coords: [106.8095, 10.8415],
				},
				{
					id: "b-0",
					type: "ELEVATOR",
					building: "Tòa Epsilon",
					floorOrder: 0,
					portalGroupId: "STAIR_SHARED",
					coords: [106.8115, 10.8405],
				},
			],
			navEdges: [],
		};

		const edges = buildAllGraphEdges(mockStore);
		assert.equal(edges.length, 1, "Only within-building vertical portal edge should be generated");
		assert.deepEqual(edges[0], ["a-0", "a-1"]);
	});

	it("3D Symbol elevation: portalSymbolsGeoJSON and roomLabelsGeoJSON compute floor-accurate heightOffset on upper floors", () => {
		const store = createDefaultGeometryStore();
		const targetFloor = 2;
		const hcmRoom2 = store.roomPolygons.find(
			(r) => r.building === "Tòa Alpha" && r.floorOrder === targetFloor,
		);
		const centroid = hcmRoom2.ring[0];
		const mockArea = {
			id: 901,
			name: "Phòng 201",
			building: "Tòa Alpha",
			floor: "Tầng 2",
			centerLongitude: (hcmRoom2.ring[0][0] + hcmRoom2.ring[2][0]) / 2,
			centerLatitude: (hcmRoom2.ring[0][1] + hcmRoom2.ring[2][1]) / 2,
			areaLevel: "PUBLIC",
		};

		const vec = buildVector3dLayers({
			store,
			selectedBuilding: "Tòa Alpha",
			activeFloorOrder: targetFloor,
			areas: [mockArea],
		});

		assert.ok(Array.isArray(vec.portalSymbolsGeoJSON?.features));
		assert.ok(vec.portalSymbolsGeoJSON.features.length > 0, "Expected STAIRS portal features on Tòa Alpha Floor 2");
		const expectedFloorTop = targetFloor * FLOOR_ELEVATION_STEP + FLOOR_SLAB_HEIGHT;
		for (const pf of vec.portalSymbolsGeoJSON.features) {
			if (!pf.properties.isGhost) {
				assert.ok(
					pf.properties.heightOffset >= expectedFloorTop,
					`Portal symbol heightOffset (${pf.properties.heightOffset}) must lift onto Floor 2 slab (>= ${expectedFloorTop}m)`,
				);
			}
		}

		assert.equal(vec.roomLabelsGeoJSON.features.length, 1);
		assert.ok(
			vec.roomLabelsGeoJSON.features[0].properties.heightOffset > expectedFloorTop,
			"Area label heightOffset must rise above extruded Floor 2 room height",
		);

		assert.ok(centroid);
		assert.ok(BOUNDARY_WALL_HEIGHT > 2);
	});

	it("NFR latency: computeDigitizedRoute + buildVector3dLayers execute under 50ms", () => {
		const store = createDefaultGeometryStore();
		const t0 = performance.now();
		for (let i = 0; i < 5; i++) {
			const r = computeDigitizedRoute({
				store,
				startCoords: [106.80915, 10.84072],
				startBuilding: "OUTDOOR",
				startFloorOrder: 0,
				endCoords: [106.811441, 10.8405],
				endBuilding: "Tòa Epsilon",
				endFloorOrder: 1,
			});
			buildRoute3dLayers(r, { selectedBuilding: "ALL", activeFloorOrder: "ALL" });
			buildVector3dLayers({ store, selectedBuilding: "Tòa Alpha", activeFloorOrder: 1 });
		}
		const elapsedAvg = (performance.now() - t0) / 5;
		assert.ok(
			elapsedAvg < 50,
			`Expected average routing + 3D layer build < 50ms, measured ${elapsedAvg.toFixed(2)}ms`,
		);
	});
});
