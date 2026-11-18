import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	CAMERA_PIN_DRAG_THRESHOLD_PX,
	validateCameraPinPlacement,
} from "../cameraMapGeometry.js";

const ROOM_A = {
	type: "Feature",
	properties: {
		id: "room-a",
		building: "Tòa Epsilon",
		floorOrder: 2,
		matchedAreaId: "area-a",
		matchedAreaName: "Phòng Lab 301",
		isGhost: false,
	},
	geometry: {
		type: "Polygon",
		coordinates: [
			[
				[106.8100, 10.8400],
				[106.8102, 10.8400],
				[106.8102, 10.8402],
				[106.8100, 10.8402],
				[106.8100, 10.8400],
			],
		],
	},
};

const ROOM_B = {
	type: "Feature",
	properties: {
		id: "room-b",
		building: "Tòa Epsilon",
		floorOrder: 2,
		matchedAreaId: "area-b",
		matchedAreaName: "Phòng Lab 302",
		isGhost: false,
	},
	geometry: {
		type: "Polygon",
		coordinates: [
			[
				[106.8103, 10.8400],
				[106.8105, 10.8400],
				[106.8105, 10.8402],
				[106.8103, 10.8402],
				[106.8103, 10.8400],
			],
		],
	},
};

const STORE = {
	buildingBoundaries: [
		{
			building: "Tòa Epsilon",
			floorOrder: 2,
			ring: [
				[106.8099, 10.8399],
				[106.8106, 10.8399],
				[106.8106, 10.8404],
				[106.8099, 10.8404],
				[106.8099, 10.8399],
			],
		},
	],
};

const TARGET_CAM = {
	id: "cam-1",
	cameraCode: "CAM-001",
	status: "ACTIVE",
	areaId: "area-a",
	building: "Tòa Epsilon",
	floor: "Tầng 2",
};

const TARGET_AREA = {
	id: "area-a",
	name: "Phòng Lab 301",
	building: "Tòa Epsilon",
	floor: "Tầng 2",
};

describe("validateCameraPinPlacement (2-tier F-05 UI validation)", () => {
	it("chặn camera DECOMMISSIONED", () => {
		const res = validateCameraPinPlacement({
			lng: 106.8101,
			lat: 10.8401,
			targetCam: { ...TARGET_CAM, status: "DECOMMISSIONED" },
			targetArea: TARGET_AREA,
			activeBuilding: "Tòa Epsilon",
			activeFloorOrder: 2,
		});
		assert.equal(res.ok, false);
		assert.equal(res.reason, "decommissioned");
	});

	it("Tầng 1: chặn khi đang xem sai Tòa nhà hoặc sai Tầng", () => {
		const wrongBldg = validateCameraPinPlacement({
			lng: 106.8101,
			lat: 10.8401,
			targetCam: TARGET_CAM,
			targetArea: TARGET_AREA,
			activeBuilding: "Tòa Alpha",
			activeFloorOrder: 2,
		});
		assert.equal(wrongBldg.ok, false);
		assert.equal(wrongBldg.reason, "wrong-building");

		const wrongFloor = validateCameraPinPlacement({
			lng: 106.8101,
			lat: 10.8401,
			targetCam: TARGET_CAM,
			targetArea: TARGET_AREA,
			activeBuilding: "Tòa Epsilon",
			activeFloorOrder: 0,
		});
		assert.equal(wrongFloor.ok, false);
		assert.equal(wrongFloor.reason, "wrong-floor");
	});

	it("Tầng 2: chặn khi đặt vào phòng đã gán Area khác hoặc ngoài khuôn khổ phòng đích (hành lang/ngoài tòa)", () => {
		const otherRoom = validateCameraPinPlacement({
			lng: 106.8104,
			lat: 10.8401,
			targetCam: TARGET_CAM,
			targetArea: TARGET_AREA,
			activeBuilding: "Tòa Epsilon",
			activeFloorOrder: 2,
			roomFeatures: [ROOM_A, ROOM_B],
			store: STORE,
		});
		assert.equal(otherRoom.ok, false);
		assert.equal(otherRoom.reason, "other-area-room");

		const corridor = validateCameraPinPlacement({
			lng: 106.81025,
			lat: 10.8403,
			targetCam: TARGET_CAM,
			targetArea: TARGET_AREA,
			activeBuilding: "Tòa Epsilon",
			activeFloorOrder: 2,
			roomFeatures: [ROOM_A, ROOM_B],
			store: STORE,
		});
		assert.equal(corridor.ok, false);
		assert.equal(corridor.reason, "outside-target-room");

		const outside = validateCameraPinPlacement({
			lng: 106.8120,
			lat: 10.8420,
			targetCam: TARGET_CAM,
			targetArea: TARGET_AREA,
			activeBuilding: "Tòa Epsilon",
			activeFloorOrder: 2,
			roomFeatures: [ROOM_A, ROOM_B],
			store: STORE,
		});
		assert.equal(outside.ok, false);
		assert.equal(outside.reason, "outside-target-room");
	});

	it("Tầng 2: chỉ cho phép đặt trong khuôn khổ lòng phòng đích (target-room)", () => {
		const insideRoom = validateCameraPinPlacement({
			lng: 106.8101,
			lat: 10.8401,
			targetCam: TARGET_CAM,
			targetArea: TARGET_AREA,
			activeBuilding: "Tòa Epsilon",
			activeFloorOrder: 2,
			roomFeatures: [ROOM_A, ROOM_B],
			store: STORE,
		});
		assert.equal(insideRoom.ok, true);
		assert.equal(insideRoom.zone, "target-room");
		assert.ok(CAMERA_PIN_DRAG_THRESHOLD_PX >= 4);
	});
});
