import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";
import { createDefaultGeometryStore } from "../campusIndoor3dData.js";
import {
	buildCameraMapMarkersGeoJSON,
	buildCameraStatsByArea,
	extractCameraAreaInfo,
	filterWorkspaceCameras,
	toggleCollapsedSectionMap,
} from "../../utils/campusCameraHelpers.js";

describe("Phase 8 - Unified Campus & Camera Workspace (Option 1) + Collapsible Sections", () => {
	const sampleAreas = [
		{
			id: "area-alpha-g",
			name: "Phòng B",
			building: "Tòa Alpha",
			floor: "Tầng Trệt",
			areaLevel: "PUBLIC",
			centerLongitude: 106.80992,
			centerLatitude: 10.84145,
		},
		{
			id: "area-eps-1",
			name: "Phòng thực hành 1",
			building: "Tòa Epsilon",
			floor: "Tầng 1",
			areaLevel: "PUBLIC",
			centerLongitude: 106.81144,
			centerLatitude: 10.8405,
		},
		{
			id: "area-eps-3",
			name: "KT Điện",
			building: "Tòa Epsilon",
			floor: "Tầng 1",
			areaLevel: "HIGHLY_CONFIDENTIAL",
			centerLongitude: 106.81135,
			centerLatitude: 10.84042,
		},
	];

	const sampleCameras = [
		{
			id: "cam-1",
			cameraCode: "CAM-019",
			name: "ADAD",
			status: "ACTIVE",
			operationalStatus: "OFFLINE",
			assignedArea: {
				id: "area-alpha-g",
				name: "Phòng B",
				building: "Tòa Alpha",
				floor: "Tầng Trệt",
			},
		},
		{
			id: "cam-2",
			cameraCode: "CAM-002",
			name: "Cam Hành lang Lab",
			status: "ACTIVE",
			operationalStatus: "ONLINE",
			assignedAreas: [
				{
					id: "area-eps-1",
					name: "Phòng thực hành 1",
					building: "Tòa Epsilon",
					floor: "Tầng 1",
				},
			],
		},
		{
			id: "cam-3",
			cameraCode: "CAM-003",
			name: "Cam Dự phòng",
			status: "DECOMMISSIONED",
			operationalStatus: "OFFLINE",
			areaId: "area-eps-1",
			areaName: "Phòng thực hành 1",
		},
		{
			id: "cam-unassigned",
			cameraCode: "CAM-099",
			name: "Cam Kho",
			status: "ACTIVE",
			operationalStatus: "OFFLINE",
		},
	];

	describe("1. extractCameraAreaInfo & buildCameraStatsByArea", () => {
		it("Happy Path: normalizes assignedArea, assignedAreas[0], and areaId formats and aggregates counts & online/offline stats", () => {
			const info1 = extractCameraAreaInfo(sampleCameras[0]);
			assert.equal(info1.areaId, "area-alpha-g");
			assert.equal(info1.areaName, "Phòng B");
			assert.equal(info1.building, "Tòa Alpha");

			const info2 = extractCameraAreaInfo(sampleCameras[1]);
			assert.equal(info2.areaId, "area-eps-1");

			const { statsByArea, countsByArea } = buildCameraStatsByArea(sampleCameras);
			assert.equal(countsByArea["area-alpha-g"], 1);
			assert.equal(countsByArea["area-eps-1"], 2);
			assert.deepEqual(
				{
					total: statsByArea["area-eps-1"].total,
					activeCount: statsByArea["area-eps-1"].activeCount,
					onlineCount: statsByArea["area-eps-1"].onlineCount,
					offlineCount: statsByArea["area-eps-1"].offlineCount,
					decommissionedCount: statsByArea["area-eps-1"].decommissionedCount,
				},
				{
					total: 2,
					activeCount: 1,
					onlineCount: 1,
					offlineCount: 0,
					decommissionedCount: 1,
				},
			);
			assert.equal(statsByArea["area-alpha-g"].offlineCount, 1);
		});

		it("Exception / Null Resilience: handles null, empty arrays, and unassigned cameras safely", () => {
			assert.deepEqual(extractCameraAreaInfo(null), {
				areaId: null,
				areaName: null,
				building: null,
				floor: null,
			});
			const { statsByArea, countsByArea } = buildCameraStatsByArea([
				null,
				undefined,
				sampleCameras[3],
			]);
			assert.deepEqual(statsByArea, {});
			assert.deepEqual(countsByArea, {});
		});
	});

	describe("2. filterWorkspaceCameras (Search, Status, Connection & Map Building/Floor Sync)", () => {
		it("Happy Path: filters by search keyword, status, operationalStatus, and sorts by cameraCode ascending", () => {
			const allSorted = filterWorkspaceCameras({
				cameras: sampleCameras,
				areas: sampleAreas,
			});
			assert.deepEqual(
				allSorted.map((c) => c.cameraCode),
				["CAM-002", "CAM-003", "CAM-019", "CAM-099"],
			);

			const onlineOnly = filterWorkspaceCameras({
				cameras: sampleCameras,
				areas: sampleAreas,
				opStatusFilter: "ONLINE",
			});
			assert.deepEqual(
				onlineOnly.map((c) => c.cameraCode),
				["CAM-002"],
			);

			const byAreaKeyword = filterWorkspaceCameras({
				cameras: sampleCameras,
				areas: sampleAreas,
				search: "Phòng B",
			});
			assert.deepEqual(
				byAreaKeyword.map((c) => c.cameraCode),
				["CAM-019"],
			);
		});

		it("Boundary: scopeToMapFilter restricts cameras to active building & floor via area metadata", () => {
			const epsFloor1 = filterWorkspaceCameras({
				cameras: sampleCameras,
				areas: sampleAreas,
				selectedBuilding: "Tòa Epsilon",
				selectedFloor: "Tầng 1",
				scopeToMapFilter: true,
			});
			assert.deepEqual(
				epsFloor1.map((c) => c.cameraCode),
				["CAM-002", "CAM-003"],
			);

			const alphaAll = filterWorkspaceCameras({
				cameras: sampleCameras,
				areas: sampleAreas,
				selectedBuilding: "Tòa Alpha",
				selectedFloor: "ALL",
				scopeToMapFilter: true,
			});
			assert.deepEqual(
				alphaAll.map((c) => c.cameraCode),
				["CAM-019"],
			);
		});
	});

	describe("3. toggleCollapsedSectionMap (Collapsible Area Level Sections)", () => {
		it("Happy Path & Idempotency: toggles section collapse state cleanly without mutating previous map", () => {
			const initial = {};
			const step1 = toggleCollapsedSectionMap(initial, "LEVEL_1");
			assert.equal(step1.LEVEL_1, true);
			assert.equal(initial.LEVEL_1, undefined);

			const step2 = toggleCollapsedSectionMap(step1, "LEVEL_1");
			assert.equal(step2.LEVEL_1, false);
		});

		it("Exception / Invalid Inputs: safely ignores empty section keys and null state", () => {
			assert.deepEqual(toggleCollapsedSectionMap(null, ""), {});
			assert.deepEqual(toggleCollapsedSectionMap(null, "LEVEL_2"), {
				LEVEL_2: true,
			});
		});
	});

	describe("4. buildCameraMapMarkersGeoJSON (3D Floor Camera Badges)", () => {
		it("Happy Path: produces floor-elevated GeoJSON Point badges only for visible areas containing cameras", () => {
			const store = createDefaultGeometryStore();
			const geojson = buildCameraMapMarkersGeoJSON({
				cameras: sampleCameras,
				areas: sampleAreas,
				store,
				selectedBuilding: "Tòa Epsilon",
				activeFloorOrder: 1,
				selectedAreaId: "area-eps-1",
				selectedCameraId: null,
			});

			assert.equal(geojson.type, "FeatureCollection");
			assert.equal(geojson.features.length, 1);
			const feat = geojson.features[0];
			assert.equal(feat.properties.areaId, "area-eps-1");
			assert.equal(feat.properties.total, 2);
			assert.equal(feat.properties.onlineCount, 1);
			assert.equal(feat.properties.hasOffline, false);
			assert.ok(feat.properties.heightOffset > 3.5);

			const alphaGeojson = buildCameraMapMarkersGeoJSON({
				cameras: sampleCameras,
				areas: sampleAreas,
				store,
				selectedBuilding: "Tòa Alpha",
				activeFloorOrder: 0,
			});
			assert.equal(alphaGeojson.features.length, 1);
			assert.equal(alphaGeojson.features[0].properties.hasOffline, true);
		});
	});

	describe("5. Sidebar, Routing & CSS Rule Verification", () => {
		it("removes standalone Camera menu from Sidebar and configures visible per-area action buttons in CSS", () => {
			const __dirname = path.dirname(fileURLToPath(import.meta.url));
			const srcDir = path.resolve(__dirname, "../..");
			const sidebarSrc = fs.readFileSync(
				path.join(srcDir, "components/layout/Sidebar.jsx"),
				"utf8",
			);
			const areaCssSrc = fs.readFileSync(
				path.join(srcDir, "styles/AreaListPage.css"),
				"utf8",
			);

			assert.doesNotMatch(sidebarSrc, /to:\s*"\/cameras"/);
			assert.match(sidebarSrc, /Quản lý khuôn viên/);

			// .zone-item-row__actions must be permanently visible (opacity: 1, not hidden at 0)
			const actionsMatch = areaCssSrc.match(
				/\.zone-item-row__actions\s*\{([^}]*)\}/,
			);
			assert.ok(actionsMatch, ".zone-item-row__actions rule must exist");
			assert.doesNotMatch(actionsMatch[1], /opacity:\s*0\s*;/);
		});
	});
});
