import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import {
	DEFAULT_GEOMETRY_SEED_VERSION,
	STORAGE_KEY_3D_GEOMETRY,
	auditNavigationConnectivity,
	buildVector3dLayers,
	computeDigitizedRoute,
	createDefaultGeometryStore,
	isNodeOutdoor,
	loadGeometryStore,
	matchesBuilding,
	saveGeometryStore,
} from "../campusIndoor3dData.js";

function createMockLocalStorage(initialEntries = {}) {
	const map = new Map(Object.entries(initialEntries));
	return {
		getItem(key) {
			return map.has(key) ? map.get(key) : null;
		},
		setItem(key, val) {
			map.set(key, String(val));
		},
		removeItem(key) {
			map.delete(key);
		},
		clear() {
			map.clear();
		},
	};
}

describe("Phase 7 (Option A) - Embedded Finalized Campus Geometry Seed & Synced Storage", () => {
	const originalLocalStorage = globalThis.localStorage;
	const originalWindow = globalThis.window;

	afterEach(() => {
		if (originalLocalStorage === undefined) {
			delete globalThis.localStorage;
		} else {
			globalThis.localStorage = originalLocalStorage;
		}
		if (originalWindow === undefined) {
			delete globalThis.window;
		} else {
			globalThis.window = originalWindow;
		}
	});

	it("Happy Path: createDefaultGeometryStore returns embedded finalized epsilon (1) seed with 5 floors for Tòa Epsilon, 3 floors for Tòa Alpha, canonical calibrations, and deep-clone isolation", () => {
		const store = createDefaultGeometryStore();

		assert.equal(store.version, 3);
		assert.equal(store.seedVersion, DEFAULT_GEOMETRY_SEED_VERSION);
		assert.ok(typeof DEFAULT_GEOMETRY_SEED_VERSION === "string" && DEFAULT_GEOMETRY_SEED_VERSION.length > 0);
		assert.equal(store.floorCalibrations?.["Tòa Alpha__0"]?.corners?.length, 4);
		assert.equal(store.floorCalibrations?.["Tòa Epsilon__0"]?.corners?.length, 4);

		// Verify finalized full-campus digitized geometry counts from epsilon (1)
		assert.equal(store.walls.length, 404);
		assert.equal(store.faceAssignments.length, 170);
		assert.equal(store.doors.length, 240);
		assert.equal(store.buildingBoundaries.length, 8);
		assert.equal(store.roomPolygons.length, 162);
		assert.equal(store.navNodes.length, 433);
		assert.equal(store.navEdges.length, 466);

		// Confirm Tòa Epsilon floors 0..4 and Tòa Alpha floors 0..2 all have rooms & boundaries
		const epsFloors = [
			...new Set(
				store.roomPolygons
					.filter((r) => matchesBuilding(r.building, "Tòa Epsilon"))
					.map((r) => r.floorOrder),
			),
		].sort((a, b) => a - b);
		assert.deepEqual(epsFloors, [0, 1, 2, 3, 4]);

		const alphaFloors = [
			...new Set(
				store.roomPolygons
					.filter((r) => matchesBuilding(r.building, "Tòa Alpha"))
					.map((r) => r.floorOrder),
			),
		].sort((a, b) => a - b);
		assert.deepEqual(alphaFloors, [0, 1, 2]);

		// Zero connectivity errors in embedded seed
		const audit = auditNavigationConnectivity(store);
		assert.equal(audit.issues.length, 0);

		// Mutating returned arrays must not affect subsequent calls
		store.walls.pop();
		store.roomPolygons.pop();
		const fresh = createDefaultGeometryStore();
		assert.equal(fresh.walls.length, 404);
		assert.equal(fresh.roomPolygons.length, 162);
	});

	it("Validation / Seed Rollout & Persistence: stale v2/v3 localStorage keys do NOT overwrite the bumped shared repo seed, while saves on active key persist & sanitize portal edges", () => {
		assert.notEqual(
			STORAGE_KEY_3D_GEOMETRY,
			"icss_campus_3d_geometry_v3",
			"Storage key must bump past stale pre-seed v3 key so teammates pull the embedded map automatically",
		);

		const staleStore = {
			version: 3,
			walls: [{ id: "stale-only", building: "Tòa Epsilon", floorOrder: 0, line: [[106.81, 10.84], [106.811, 10.84]] }],
			roomPolygons: [],
		};
		globalThis.localStorage = createMockLocalStorage({
			icss_campus_3d_geometry_v2: JSON.stringify(staleStore),
			icss_campus_3d_geometry_v3: JSON.stringify(staleStore),
		});
		const dispatched = [];
		globalThis.window = {
			dispatchEvent(evt) {
				dispatched.push(evt.type);
			},
		};

		// Loading with only stale v2/v3 in browser must ignore stale cache and return full embedded seed
		const loaded = loadGeometryStore();
		assert.equal(loaded.walls.length, 404);
		assert.equal(loaded.roomPolygons.length, 162);

		// Saving an edit to the active key must reload cleanly on next visit and sanitize forbidden portal-to-portal edges
		const customStore = {
			...loaded,
			navNodes: [
				...loaded.navNodes,
				{ id: "p-a", type: "STAIRS", building: "Tòa Epsilon", floorOrder: 0, coords: [106.8114, 10.8405] },
				{ id: "p-b", type: "ELEVATOR", building: "Tòa Epsilon", floorOrder: 0, coords: [106.81145, 10.8405] },
			],
			navEdges: [...loaded.navEdges, ["p-a", "p-b"]],
		};
		saveGeometryStore(customStore);
		assert.equal(dispatched.includes("icss-3d-geometry-updated"), true);

		const reloaded = loadGeometryStore();
		assert.equal(reloaded.navNodes.some((n) => n.id === "p-a"), true);
		assert.equal(
			reloaded.navEdges.some(
				([u, v]) => (u === "p-a" && v === "p-b") || (u === "p-b" && v === "p-a"),
			),
			false,
			"Portal-to-portal edge must be stripped cleanly on loadGeometryStore()",
		);
	});

	it("Exception / Corrupt Cache Fallback: malformed JSON or invalid store under STORAGE_KEY_3D_GEOMETRY falls back safely to embedded seed", () => {
		globalThis.localStorage = createMockLocalStorage({
			[STORAGE_KEY_3D_GEOMETRY]: "{broken-json:::",
		});
		const fromCorrupt = loadGeometryStore();
		assert.equal(fromCorrupt.walls.length, 404);
		assert.equal(fromCorrupt.roomPolygons.length, 162);

		globalThis.localStorage = createMockLocalStorage({
			[STORAGE_KEY_3D_GEOMETRY]: JSON.stringify({ version: 3 }),
		});
		const fromIncomplete = loadGeometryStore();
		assert.equal(fromIncomplete.walls.length, 404);
		assert.equal(fromIncomplete.roomPolygons.length, 162);
	});

	it("Main Workflow: embedded seed supports end-to-end Outdoor -> Tòa Epsilon F4 route and continuous multi-floor Type B portal shafts across F0..F4", () => {
		const store = createDefaultGeometryStore();
		const outdoorNode = store.navNodes.find((n) =>
			isNodeOutdoor(store.buildingBoundaries, n),
		);
		const epsFloor4Room = store.roomPolygons.find(
			(r) => matchesBuilding(r.building, "Tòa Epsilon") && r.floorOrder === 4,
		);
		assert.ok(outdoorNode && epsFloor4Room);

		const routeToEps4 = computeDigitizedRoute({
			store,
			startCoords: outdoorNode.coords,
			startBuilding: "OUTDOOR",
			startFloorOrder: 0,
			endCoords: epsFloor4Room.ring[0],
			endBuilding: "Tòa Epsilon",
			endFloorOrder: 4,
		});
		assert.ok(routeToEps4);
		assert.equal(routeToEps4.floorsClimbed, 4);
		assert.ok(routeToEps4.totalDistanceMeters > 0);

		const vecExploded = buildVector3dLayers({
			store,
			selectedBuilding: "Tòa Epsilon",
			activeFloorOrder: "ALL",
			floorElevationStep: 10.5,
		});
		assert.ok(vecExploded.portalShaftsGeoJSON.features.length >= 4);
		for (const shaft of vecExploded.portalShaftsGeoJSON.features) {
			assert.ok(
				shaft.properties.extrudeHeight >= 42.0,
				`Expected Tòa Epsilon Type B shaft ${shaft.properties.portalGroupId} to reach Floor 4 (>= 42m), got ${shaft.properties.extrudeHeight}`,
			);
		}
	});
});
