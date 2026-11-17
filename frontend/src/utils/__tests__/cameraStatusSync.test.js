import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	buildCameraStatsByArea,
	mergeCameraStatusUpdate,
} from "../campusCameraHelpers.js";

describe("cameraStatusSync", () => {
	it("đồng bộ operationalStatus & status từ CameraInspectorDrawer vào danh sách cameras sidebar", () => {
		const initial = [
			{
				id: "cam-19",
				cameraCode: "CAM-019",
				name: "Black",
				areaId: "area-kt",
				status: "ACTIVE",
				operationalStatus: "ONLINE",
			},
			{
				id: "cam-20",
				cameraCode: "CAM-020",
				name: "Camera cổng chính A",
				areaId: "area-kt",
				status: "ACTIVE",
				operationalStatus: "OFFLINE",
			},
		];

		const next = mergeCameraStatusUpdate(initial, {
			id: "cam-19",
			name: "Black",
			status: "ACTIVE",
			operationalStatus: "OFFLINE",
		});

		assert.equal(next[0].operationalStatus, "OFFLINE");
		const { statsByArea } = buildCameraStatsByArea(next);
		assert.equal(statsByArea["area-kt"].onlineCount, 0);
		assert.equal(statsByArea["area-kt"].offlineCount, 2);
	});

	it("giữ nguyên reference mảng nếu không có thay đổi thực sự để tránh re-render dư thừa", () => {
		const initial = [
			{
				id: "cam-19",
				name: "Black",
				status: "ACTIVE",
				operationalStatus: "OFFLINE",
			},
		];
		const next = mergeCameraStatusUpdate(initial, {
			id: "cam-19",
			name: "Black",
			status: "ACTIVE",
			operationalStatus: "OFFLINE",
		});
		assert.equal(next, initial);
	});
});
