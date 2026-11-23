import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	computeVisibleGridSlice,
	extractAreaFilterOptions,
	filterCamerasByAreaKey,
	findAdjacentCameras,
	formatCameraChipLabel,
	formatCameraTileTitle,
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
	it("ẩn hoàn toàn mã CAM-xxx khỏi chip và tiêu đề OSD", () => {
		const cam = {
			cameraCode: "CAM-019",
			name: "KT Điện",
			building: "Tòa Epsilon",
			floor: "Tầng 1",
		};
		assert.equal(formatCameraChipLabel(cam), "KT Điện");
		assert.equal(formatCameraTileTitle(cam), "KT Điện · Tầng 1");
		assert.equal(
			formatCameraTileTitle({ cameraCode: "CAM-020", name: "Hành lang Đông Tầng 2", floor: "Tầng 2" }),
			"Hành lang Đông Tầng 2",
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

	it("Spotlight 1+5 luôn ghim camera được chọn vào slot 0 và phân trang 5 cam phụ còn lại", () => {
		const cams = makeCams(8);
		const p0 = computeVisibleGridSlice(cams, "spotlight", "cam-004", 0);
		assert.equal(p0.visibleCameras[0].code, "cam-004");
		assert.equal(p0.visibleCameras.length, 6);
		assert.equal(p0.emptySlotCount, 0);
		assert.equal(p0.totalPages, 2);

		const p1 = computeVisibleGridSlice(cams, "spotlight", "cam-004", 1);
		assert.equal(p1.visibleCameras[0].code, "cam-004");
		assert.equal(p1.visibleCameras.length, 3); // spotlight + 2 remaining
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
		assert.equal(adjacentAll[0].camera.code, "cam-t3-near"); // ~10m cùng tầng 3
		assert.equal(adjacentAll[0].sameFloor, true);
		assert.match(adjacentAll[0].relationLabel, /^\d+m$/);
		assert.equal(adjacentAll[1].camera.code, "cam-t3-far"); // xa hơn cùng tầng 3
		assert.equal(adjacentAll[2].camera.code, "cam-t1-c"); // khác tầng cùng tòa
		assert.equal(adjacentAll[2].sameFloor, false);

		// sameFloorOnly chỉ trả về đúng các cam cùng tầng (tầng 1 chỉ có 1 cam -> rỗng, không lấy tầng 3/2)
		const adjacentSameFloorOnly = findAdjacentCameras(cams[0], cams, 3, { sameFloorOnly: true });
		assert.equal(adjacentSameFloorOnly.length, 2);
		assert.ok(adjacentSameFloorOnly.every((a) => a.sameFloor));
		assert.equal(findAdjacentCameras(cams[1], cams, 3, { sameFloorOnly: true }).length, 0);

		// Spotlight ưu tiên lân cận đưa cam gần nhất lên đầu các ô phụ
		const sliceAdj = computeVisibleGridSlice(stable, "spotlight", "cam-t3-a", 0, {
			adjacentPriorityCodes: adjacentAll.map((a) => a.camera.code),
		});
		assert.equal(sliceAdj.visibleCameras[0].code, "cam-t3-a");
		assert.equal(sliceAdj.visibleCameras[1].code, "cam-t3-near");
	});
});
