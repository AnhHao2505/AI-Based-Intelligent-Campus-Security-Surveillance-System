import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";
import {
	buildVector3dLayers,
	createDefaultGeometryStore,
} from "../campusIndoor3dData.js";
import * as campusCameraHelpers from "../../utils/campusCameraHelpers.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const srcDir = path.resolve(__dirname, "../..");

describe("Workstream 1 + FM Color Parity (TDD)", () => {
	describe("1. fetchAllPaginatedPages (Multi-page fetch respecting backend size=100 limit)", () => {
		it("Happy Path: collects all pages when totalPages > 1 and preserves item order", async () => {
			assert.equal(
				typeof campusCameraHelpers.fetchAllPaginatedPages,
				"function",
				"fetchAllPaginatedPages must be exported",
			);

			const calls = [];
			const totalItems = 225;
			const pageSize = 100;
			const items = Array.from({ length: totalItems }, (_, i) => ({
				id: `area-${i + 1}`,
				name: `Area ${i + 1}`,
			}));

			const result = await campusCameraHelpers.fetchAllPaginatedPages(
				async (page, size) => {
					calls.push({ page, size });
					const start = page * size;
					return {
						content: items.slice(start, start + size),
						totalPages: 3,
						totalElements: totalItems,
						last: page === 2,
					};
				},
				{ pageSize },
			);

			assert.equal(result.length, 225);
			assert.equal(result[0].id, "area-1");
			assert.equal(result[224].id, "area-225");
			assert.deepEqual(calls, [
				{ page: 0, size: 100 },
				{ page: 1, size: 100 },
				{ page: 2, size: 100 },
			]);
		});

		it("Boundary / Idempotency: stops early when last page has fewer than pageSize and enforces maxPages cap", async () => {
			const resultShort = await campusCameraHelpers.fetchAllPaginatedPages(
				async () => ({ content: [{ id: "only-1" }] }),
				{ pageSize: 100 },
			);
			assert.deepEqual(resultShort, [{ id: "only-1" }]);

			let count = 0;
			const capped = await campusCameraHelpers.fetchAllPaginatedPages(
				async (page) => {
					count++;
					return Array.from({ length: 5 }, (_, idx) => ({
						id: `p${page}-${idx}`,
					}));
				},
				{ pageSize: 5, maxPages: 2 },
			);
			assert.equal(count, 2);
			assert.equal(capped.length, 10);
		});

		it("Exception: rejects cleanly when page fetcher fails and validates fetcher type", async () => {
			await assert.rejects(
				() => campusCameraHelpers.fetchAllPaginatedPages(null),
				/fetchPage/i,
			);
			await assert.rejects(
				() =>
					campusCameraHelpers.fetchAllPaginatedPages(async () => {
						throw new Error("Network failure 503");
					}),
				/Network failure 503/,
			);
		});
	});

	describe("2. Facility Manager 3D Map Color Parity with Admin", () => {
		it("renders identical room colors for ADMIN and FACILITY_MANAGER across PUBLIC, CONFIDENTIAL, HIGHLY_CONFIDENTIAL, and selected rooms", () => {
			const store = createDefaultGeometryStore();
			const probe = buildVector3dLayers({
				store,
				selectedBuilding: "Tòa Epsilon",
				activeFloorOrder: 1,
				areas: [],
				viewRole: "ADMIN",
			});
			const roomFaces = probe.roomsGeoJSON.features.filter(
				(f) =>
					!f.properties?.isGhost &&
					String(f.properties?.role || "ROOM").toUpperCase() === "ROOM",
			);
			assert.ok(roomFaces.length >= 4, "Need at least 4 room blocks in default store");

			const roomPts = roomFaces.slice(0, 4).map((f) => {
				const ring = f.geometry.coordinates[0];
				const uniqueVerts = ring.length > 1 ? ring.slice(0, -1) : ring;
				const avg = uniqueVerts.reduce(
					(acc, pt) => [
						acc[0] + pt[0] / uniqueVerts.length,
						acc[1] + pt[1] / uniqueVerts.length,
					],
					[0, 0],
				);
				return {
					building: f.properties.building,
					floor: "Tầng 1",
					lng: avg[0],
					lat: avg[1],
				};
			});

			const levels = ["PUBLIC", "CONFIDENTIAL", "HIGHLY_CONFIDENTIAL", "PUBLIC"];
			const testAreas = roomPts.map((pt, i) => ({
				id: `area-${i}`,
				name: `Area ${i}`,
				areaLevel: levels[i],
				building: pt.building,
				floor: pt.floor,
				centerLongitude: pt.lng,
				centerLatitude: pt.lat,
			}));

			const adminLayers = buildVector3dLayers({
				store,
				selectedBuilding: "Tòa Epsilon",
				activeFloorOrder: 1,
				areas: testAreas,
				selectedAreaId: "area-0",
				viewRole: "ADMIN",
			});
			const fmLayers = buildVector3dLayers({
				store,
				selectedBuilding: "Tòa Epsilon",
				activeFloorOrder: 1,
				areas: testAreas,
				selectedAreaId: "area-0",
				viewRole: "FACILITY_MANAGER",
			});

			const adminColors = adminLayers.roomsGeoJSON.features.map((f) => ({
				id: f.properties.faceId,
				color: f.properties.color,
			}));
			const fmColors = fmLayers.roomsGeoJSON.features.map((f) => ({
				id: f.properties.faceId,
				color: f.properties.color,
			}));

			assert.deepEqual(fmColors, adminColors);
			// Ensure matched areas actually got non-default colors rather than all slate
			const matchedAdmin = adminLayers.roomsGeoJSON.features.filter(
				(f) => f.properties.matchedAreaId,
			);
			assert.ok(matchedAdmin.length >= 3, "At least 3 sample rooms should match test areas");
		});
	});

	describe("3. Workstream 1 A11y & CSS Scope Checks", () => {
		it("eliminates nested buttons inside role='button' rows and includes shared drawer CSS in frontend/src/styles/", () => {
			const areaListViewSrc = fs.readFileSync(
				path.join(srcDir, "components/area/AreaListView.jsx"),
				"utf8",
			);
			const camSidebarSrc = fs.readFileSync(
				path.join(srcDir, "components/area/CameraWorkspaceSidebar.jsx"),
				"utf8",
			);
			const drawerSrc = fs.readFileSync(
				path.join(srcDir, "components/camera/CameraInspectorDrawer.jsx"),
				"utf8",
			);
			const sharedCssPath = path.join(srcDir, "styles/cameraShared.css");

			assert.doesNotMatch(
				areaListViewSrc,
				/role="button"/,
				"AreaListView should not wrap action buttons inside role='button'",
			);
			assert.doesNotMatch(
				camSidebarSrc,
				/role="button"/,
				"CameraWorkspaceSidebar should not wrap action buttons inside role='button'",
			);
			assert.ok(
				fs.existsSync(sharedCssPath),
				"frontend/src/styles/cameraShared.css must exist",
			);
			assert.match(drawerSrc, /cameraShared\.css/);
		});
	});
});
