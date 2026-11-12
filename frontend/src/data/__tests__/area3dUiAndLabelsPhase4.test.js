import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	buildVector3dLayers,
	createDefaultGeometryStore,
	mapSidebarSelectionTo3d,
	map3dSelectionToSidebar,
} from "../campusIndoor3dData.js";
import {
	buildRoomLabelSpriteId,
	parseRoomLabelSpriteId,
	truncateLabelText,
} from "../../utils/mapRoomLabelSprites.js";

describe("Area 3D UI & Canvas Sprite Labels (Phase 4)", () => {
	it("1B Happy Path & Round-trip: buildRoomLabelSpriteId encodes/decodes Vietnamese labels, level, and selection state", () => {
		const id = buildRoomLabelSpriteId({
			name: "Phòng Lab An Ninh / Tầng 2",
			isSelected: true,
			levelCode: "HIGHLY_CONFIDENTIAL",
		});

		assert.ok(id.startsWith("room-label::"));
		const parsed = parseRoomLabelSpriteId(id);
		assert.deepEqual(parsed, {
			name: "Phòng Lab An Ninh / Tầng 2",
			isSelected: true,
			levelCode: "HIGHLY_CONFIDENTIAL",
		});
		assert.equal(parseRoomLabelSpriteId("portal-stairs"), null);
	});

	it("1B Boundary: truncateLabelText keeps short labels intact and bounds long names cleanly", () => {
		assert.equal(truncateLabelText("Phòng B", 24), "Phòng B");
		const longName = "Phòng Thực Hành An Toàn Thông Tin Nâng Cao FPT";
		const truncated = truncateLabelText(longName, 24);
		assert.equal(truncated.length, 24);
		assert.ok(truncated.endsWith("…"));
		assert.equal(truncateLabelText("", 24), "");
	});

	it("1B + 3A Vector Layers: roomLabelsGeoJSON emits spriteId & areaLevelCode, buildingLabelsGeoJSON counts areas with alias support", () => {
		const store = createDefaultGeometryStore();
		const alphaRoom = store.roomPolygons.find(
			(r) => r.building === "Tòa Alpha" && r.floorOrder === 0,
		);
		assert.ok(alphaRoom);
		const centroidLng = (alphaRoom.ring[0][0] + alphaRoom.ring[2][0]) / 2;
		const centroidLat = (alphaRoom.ring[0][1] + alphaRoom.ring[2][1]) / 2;

		const areas = [
			{
				id: "area-1",
				name: "Phòng B",
				building: "Tòa Alpha",
				floor: "Tầng Trệt",
				areaLevel: "CONFIDENTIAL",
				centerLongitude: centroidLng,
				centerLatitude: centroidLat,
			},
			{
				id: "area-2",
				name: "Phòng Alias HCM",
				building: "Tòa HCM",
				floor: "Tầng 1",
				areaLevel: "PUBLIC",
				centerLongitude: null,
				centerLatitude: null,
			},
			{
				id: "area-3",
				name: "Phòng Epsilon",
				building: "Tòa Espilon",
				floor: "Tầng 1",
				areaLevel: "HIGHLY_CONFIDENTIAL",
				centerLongitude: null,
				centerLatitude: null,
			},
		];

		const vecFocus = buildVector3dLayers({
			store,
			selectedBuilding: "Tòa Alpha",
			activeFloorOrder: 0,
			areas,
			selectedAreaId: "area-1",
		});

		assert.equal(vecFocus.roomLabelsGeoJSON.features.length, 1);
		const lblProps = vecFocus.roomLabelsGeoJSON.features[0].properties;
		assert.equal(lblProps.areaLevelCode, "CONFIDENTIAL");
		assert.equal(lblProps.isSelected, true);
		assert.ok(lblProps.spriteId.startsWith("room-label::"));
		assert.deepEqual(parseRoomLabelSpriteId(lblProps.spriteId), {
			name: "Phòng B",
			isSelected: true,
			levelCode: "CONFIDENTIAL",
		});

		const alphaPill = vecFocus.buildingLabelsGeoJSON.features.find(
			(f) => f.properties.building === "Tòa Alpha",
		);
		const epsilonPill = vecFocus.buildingLabelsGeoJSON.features.find(
			(f) => f.properties.building === "Tòa Epsilon",
		);
		assert.equal(alphaPill?.properties.areaCount, 2, "Matches Tòa Alpha + alias Tòa HCM");
		assert.equal(epsilonPill?.properties.areaCount, 1, "Matches Tòa Epsilon + alias Tòa Espilon");
	});

	it("3A Sync Helpers: bidirectional mapping between Sidebar filters and 3D slicer state handles ALL, aliases, and floors", () => {
		assert.deepEqual(
			mapSidebarSelectionTo3d({ selectedBuilding: "ALL", selectedFloor: "ALL" }),
			{ building: "ALL", floorOrder: "ALL" },
		);
		assert.deepEqual(
			mapSidebarSelectionTo3d({ selectedBuilding: "Tòa HCM", selectedFloor: "Tầng 2" }),
			{ building: "Tòa Alpha", floorOrder: 2 },
		);
		assert.deepEqual(
			mapSidebarSelectionTo3d({ selectedBuilding: "Tòa Epsilon", selectedFloor: "ALL" }),
			{ building: "Tòa Epsilon", floorOrder: "ALL" },
		);

		assert.deepEqual(
			map3dSelectionToSidebar({ buildingName: "Tòa Epsilon", floorOrder: 4 }),
			{ building: "Tòa Epsilon", floor: "Tầng 4" },
		);
		assert.deepEqual(
			map3dSelectionToSidebar({ buildingName: "Tòa Alpha", floorOrder: 0 }),
			{ building: "Tòa Alpha", floor: "Tầng Trệt" },
		);
		assert.deepEqual(
			map3dSelectionToSidebar({ buildingName: "ALL", floorOrder: "ALL" }),
			{ building: "ALL", floor: "ALL" },
		);
	});
});
