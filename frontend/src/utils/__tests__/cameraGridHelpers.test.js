import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	UNKNOWN_CAMERA_CODE_LABEL,
	computeVisibleGridSlice,
	extractAreaFilterOptions,
	extractAreaNameOptions,
	extractBuildingOptions,
	extractFloorOptions,
	filterCamerasByAreaKey,
	filterCamerasByHierarchy,
	findAdjacentCameras,
	formatCameraChipLabel,
	formatCameraCodeOrUnknown,
	formatCameraRoomFloorTitle,
	formatCameraRoomName,
	formatCameraSubLabel,
	formatCameraTileTitle,
	formatIncidentCameraLabel,
	formatShortFloorCode,
	resolveCameraCodeLabel,
	resolveGridConfig,
	sortCamerasStableByLocation,
} from "../cameraGridHelpers.js";

const makeCams = (n) =>
	Array.from({ length: n }, (_, i) => ({
		id: `id-${i + 1}`,
		code: `cam-00${i + 1}`,
		cameraCode: `CAM-00${i + 1}`,
		name: `Phòng ${i + 1}`,
		building: "Tòa Epsilon",
		floor: `Tầng ${(i % 3) + 1}`,
	}));

describe("cameraGridHelpers", () => {
	it("tiêu đề dùng tên khu vực/camera, nhãn phụ & chip dùng mã CAM-xxx", () => {
		const cam = {
			cameraCode: "cam-019",
			name: "KT Điện",
			building: "Tòa Epsilon",
			floor: "Tầng 1",
		};
		assert.equal(formatCameraTileTitle(cam), "KT Điện");
		assert.equal(formatCameraChipLabel(cam), "CAM-019");
		assert.equal(formatCameraSubLabel(cam), "CAM-019");
		assert.equal(formatCameraRoomName(cam), "KT Điện");
		assert.equal(
			formatCameraRoomName({
				cameraCode: "CAM-020",
				name: "Hành lang Đông Tầng 2",
				floor: "Tầng 2",
			}),
			"Hành lang Đông",
		);
	});

	it("tính đúng lưới Tự vừa (auto) và số ô trống", () => {
		assert.deepEqual(resolveGridConfig("auto", 5), {
			mode: "auto",
			cols: 3,
			rows: 2,
			pageSize: 6,
			totalSlots: 6,
		});
		const slice5 = computeVisibleGridSlice(makeCams(5), "auto", "", 0);
		assert.equal(slice5.visibleCameras.length, 5);
		assert.equal(slice5.emptySlotCount, 1);

		const slice4 = computeVisibleGridSlice(makeCams(4), "auto", "", 0);
		assert.equal(slice4.emptySlotCount, 0);
		assert.equal(slice4.config.cols, 2);
	});

	it("Spotlight 1+5 luôn giữ camera được chọn ở slot 0 và phân trang 5 cam phụ còn lại", () => {
		const cams = makeCams(8);
		const p0 = computeVisibleGridSlice(cams, "spotlight", "cam-004", 0);
		assert.equal(p0.visibleCameras[0].code, "cam-004");
		assert.equal(p0.visibleCameras.length, 6);
		assert.equal(p0.emptySlotCount, 0);
		assert.equal(p0.totalPages, 2);

		const p1 = computeVisibleGridSlice(cams, "spotlight", "cam-004", 1);
		assert.equal(p1.visibleCameras[0].code, "cam-004");
		assert.equal(p1.visibleCameras.length, 3);
		assert.equal(p1.emptySlotCount, 3);
	});

	it("lưới 3x3 với 5 camera tạo đúng 4 ô trống và hỗ trợ phân trang khi > 9", () => {
		const slice5 = computeVisibleGridSlice(makeCams(5), 9, "", 0);
		assert.equal(slice5.visibleCameras.length, 5);
		assert.equal(slice5.emptySlotCount, 4);

		const slice11p1 = computeVisibleGridSlice(makeCams(11), 9, "", 1);
		assert.equal(slice11p1.totalPages, 2);
		assert.equal(slice11p1.visibleCameras.length, 2);
		assert.equal(slice11p1.emptySlotCount, 7);
	});

	it("nhóm đúng Tòa/Tầng, giữ thứ tự ổn định và tính Camera lân cận theo tọa độ 2D", () => {
		const cams = [
			{
				code: "cam-t3-a",
				cameraCode: "CAM-001",
				name: "Hành lang Black",
				building: "Tòa Epsilon",
				mapFloorOrder: 3,
				mapLatitude: 10.8412,
				mapLongitude: 106.8098,
			},
			{
				code: "cam-t1-c",
				cameraCode: "CAM-002",
				name: "Camera góc cửa",
				building: "Tòa Epsilon",
				mapFloorOrder: 1,
				mapLatitude: 10.84122,
				mapLongitude: 106.80981,
			},
			{
				code: "cam-t3-far",
				cameraCode: "CAM-003",
				name: "KT Điện",
				building: "Tòa Epsilon",
				mapFloorOrder: 3,
				mapLatitude: 10.8415,
				mapLongitude: 106.8101,
			},
			{
				code: "cam-t3-near",
				cameraCode: "CAM-004",
				name: "Hành lang A",
				building: "Tòa Epsilon",
				mapFloorOrder: 3,
				mapLatitude: 10.84128,
				mapLongitude: 106.80984,
			},
		];

		const stable = sortCamerasStableByLocation(cams);
		assert.deepEqual(
			stable.map((c) => c.code),
			["cam-t1-c", "cam-t3-a", "cam-t3-far", "cam-t3-near"],
		);

		const options = extractAreaFilterOptions(stable);
		assert.equal(options.length, 2);
		assert.equal(options[0].label, "Tòa Epsilon · Tầng 1");
		assert.equal(options[1].label, "Tòa Epsilon · Tầng 3");
		assert.equal(options[1].count, 3);
		assert.equal(filterCamerasByAreaKey(stable, options[0].key).length, 1);

		const adjacentAll = findAdjacentCameras(cams[0], cams, 3);
		assert.equal(adjacentAll[0].camera.code, "cam-t3-near");
		assert.equal(adjacentAll[0].sameFloor, true);
		assert.match(adjacentAll[0].relationLabel, /^\d+m$/);
		assert.equal(adjacentAll[1].camera.code, "cam-t3-far");
		assert.equal(adjacentAll[2].camera.code, "cam-t1-c");
		assert.equal(adjacentAll[2].sameFloor, false);

		const adjacentSameFloorOnly = findAdjacentCameras(cams[0], cams, 3, {
			sameFloorOnly: true,
		});
		assert.equal(adjacentSameFloorOnly.length, 2);
		assert.ok(adjacentSameFloorOnly.every((a) => a.sameFloor));
		assert.equal(
			findAdjacentCameras(cams[1], cams, 3, { sameFloorOnly: true }).length,
			0,
		);
	});

	it("rút gọn ký hiệu tầng G/T1/T2 cạnh tên phòng và tách mã camera", () => {
		assert.equal(formatShortFloorCode({ floor: "Tầng Trệt" }), "G");
		assert.equal(formatShortFloorCode({ mapFloorOrder: 0 }), "G");
		assert.equal(formatShortFloorCode({ mapFloorOrder: 1 }), "T1");
		assert.equal(formatShortFloorCode({ floor: "Tầng 2" }), "T2");

		const cam = {
			code: "CAM-201",
			name: "Góc hành lang đông",
			areaName: "Hành lang Cánh Đông",
			floor: "Tầng 2",
			building: "Tòa Epsilon",
		};
		assert.equal(formatCameraRoomName(cam), "Hành lang Cánh Đông");
		assert.equal(formatCameraRoomFloorTitle(cam), "Hành lang Cánh Đông · T2");
		assert.equal(
			formatCameraTileTitle(cam),
			"Hành lang Cánh Đông · Góc hành lang đông",
		);
		assert.equal(formatCameraChipLabel(cam), "CAM-201");
		assert.equal(formatCameraSubLabel(cam), "CAM-201");
	});

	it("lọc phân cấp 3 dropdown Tòa / Tầng / Khu vực: gom Hành lang & giữ khu vực >= 1 camera", () => {
		const cams = [
			{
				code: "CAM-001",
				building: "Tòa Alpha",
				floor: "Tầng Trệt",
				mapFloorOrder: 0,
				areaName: "Sảnh chính",
				cameraType: "PUBLIC_AREA",
			},
			{
				code: "CAM-002",
				building: "Tòa Epsilon",
				floor: "Tầng 1",
				mapFloorOrder: 1,
				areaName: "KT Điện",
				cameraType: "RESTRICTED_AREA",
			},
			{
				code: "CAM-003",
				building: "Tòa Epsilon",
				floor: "Tầng 2",
				mapFloorOrder: 2,
				areaName: "Phòng thực hành 1",
				cameraType: "PUBLIC_AREA",
			},
			{
				code: "CAM-004",
				building: "Tòa Epsilon",
				floor: "Tầng 2",
				mapFloorOrder: 2,
				areaName: "Hành lang Cánh Đông",
				cameraType: "CORRIDOR",
			},
			{
				code: "CAM-005",
				building: "Tòa Epsilon",
				floor: "Tầng 2",
				mapFloorOrder: 2,
				areaName: "Hành lang Cánh Tây",
				cameraType: "CORRIDOR",
			},
			{
				code: "CAM-006",
				building: "Tòa Epsilon",
				floor: "Tầng 2",
				mapFloorOrder: 2,
				areaName: "Phòng họp",
				cameraType: "PUBLIC_AREA",
			},
			{
				code: "CAM-007",
				building: "Tòa Epsilon",
				floor: "Tầng 2",
				mapFloorOrder: 2,
				areaName: "Phòng họp",
				cameraType: "PUBLIC_AREA",
			},
		];

		const buildings = extractBuildingOptions(cams);
		assert.deepEqual(
			buildings.map((b) => [b.label, b.count]),
			[
				["Tòa Alpha", 1],
				["Tòa Epsilon", 6],
			],
		);

		const floorsEpsilon = extractFloorOptions(cams, "tòa epsilon");
		assert.deepEqual(
			floorsEpsilon.map((f) => [f.shortCode, f.count]),
			[
				["T1", 1],
				["T2", 5],
			],
		);

		// Giữ đầy đủ khu vực >= 1 camera; CORRIDOR gom chung thành "Hành lang (2)"
		const areasT2 = extractAreaNameOptions(cams, "tòa epsilon", "ord::2");
		assert.deepEqual(
			areasT2.map((a) => [a.key, a.label, a.count]),
			[
				["__corridor__", "Hành lang", 2],
				["phòng họp", "Phòng họp", 2],
				["phòng thực hành 1", "Phòng thực hành 1", 1],
			],
		);

		const filteredCorridors = filterCamerasByHierarchy(cams, {
			buildingKey: "tòa epsilon",
			floorKey: "ord::2",
			areaKey: "__corridor__",
		});
		assert.deepEqual(
			filteredCorridors.map((c) => c.code),
			["CAM-004", "CAM-005"],
		);
	});

	it("quy tắc cố định: tiêu đề hiện khu vực/tên camera, nhãn phụ & chip hiện cameraCode (thiếu thì Không xác định)", () => {
		const camWithAreaAndName = {
			id: "cam-db-5",
			code: "cam-005",
			cameraCode: "CAM-005",
			name: "CAM-001",
			areaName: "Văn Phòng",
			building: "Tòa Epsilon",
			floor: "Tầng 1",
		};
		assert.equal(resolveCameraCodeLabel(camWithAreaAndName), "CAM-005");
		assert.equal(formatCameraTileTitle(camWithAreaAndName), "Văn Phòng · CAM-001");
		assert.equal(formatCameraChipLabel(camWithAreaAndName), "CAM-005");
		assert.equal(formatCameraSubLabel(camWithAreaAndName), "CAM-005");

		const camOnlyName = {
			cameraCode: "CAM-005",
			name: "CAM-001",
		};
		assert.equal(formatCameraTileTitle(camOnlyName), "CAM-001");
		assert.equal(formatCameraChipLabel(camOnlyName), "CAM-005");
		assert.equal(formatCameraSubLabel(camOnlyName), "CAM-005");

		const missingCodeCam = {
			id: "cam-no-code",
			cameraCode: "   ",
			code: "",
			name: "CAM-001",
			areaName: "Văn Phòng",
		};
		assert.equal(resolveCameraCodeLabel(missingCodeCam), "");
		assert.equal(formatCameraCodeOrUnknown(missingCodeCam), UNKNOWN_CAMERA_CODE_LABEL);
		assert.equal(formatCameraTileTitle(missingCodeCam), "Văn Phòng · CAM-001");
		assert.equal(formatCameraChipLabel(missingCodeCam), "Không xác định");
		assert.equal(formatCameraSubLabel(missingCodeCam), "Không xác định");

		assert.equal(
			formatIncidentCameraLabel({ cameraCode: "cam-005", cameraName: "CAM-001" }),
			"CAM-005",
		);
		assert.equal(
			formatIncidentCameraLabel({ camera_code: "cam-005" }),
			"CAM-005",
		);
		assert.equal(
			formatIncidentCameraLabel({ cameraCode: "", camCodeClean: "" }),
			"Không xác định",
		);
		assert.equal(formatIncidentCameraLabel(null), "Không xác định");
	});
});
