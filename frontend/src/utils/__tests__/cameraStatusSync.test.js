import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	applyLiveCameraStatus,
	buildCameraStatsByArea,
	isStreamConfigDirty,
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

	it("đồng bộ liveOperationalStatus từ component cha xuống CameraInspectorDrawer", () => {
		const prev = {
			id: "cam-19",
			name: "Black",
			status: "ACTIVE",
			operationalStatus: "ONLINE",
		};
		const updated = applyLiveCameraStatus(prev, {
			operationalStatus: "OFFLINE",
			status: "ACTIVE",
		});
		assert.equal(updated.operationalStatus, "OFFLINE");
		assert.equal(
			applyLiveCameraStatus(updated, {
				operationalStatus: "OFFLINE",
				status: "ACTIVE",
			}),
			updated,
		);
	});

	it("nhận diện chính xác trạng thái isStreamConfigDirty khi form bị sửa đổi hoặc có mật khẩu mới", () => {
		const saved = {
			host: "192.168.1.100",
			port: 554,
			username: "admin",
			mainStreamPath: "/live/ch0",
		};

		// Giống hệt saved -> không dirty
		assert.equal(
			isStreamConfigDirty(
				{
					host: "192.168.1.100",
					port: "554",
					username: "admin",
					mainStreamPath: "/live/ch0",
					credentialRef: "",
				},
				saved,
			),
			false,
		);

		// Thay đổi host -> dirty
		assert.equal(
			isStreamConfigDirty(
				{
					host: "192.168.1.101",
					port: "554",
					username: "admin",
					mainStreamPath: "/live/ch0",
					credentialRef: "",
				},
				saved,
			),
			true,
		);

		// Nhập password mới -> dirty
		assert.equal(
			isStreamConfigDirty(
				{
					host: "192.168.1.100",
					port: "554",
					username: "admin",
					mainStreamPath: "/live/ch0",
					credentialRef: "newpass123",
				},
				saved,
			),
			true,
		);

		// Chưa từng có config trong DB, nếu form có dữ liệu -> dirty
		assert.equal(
			isStreamConfigDirty(
				{
					host: "192.168.1.50",
					port: "554",
					username: "",
					mainStreamPath: "",
					credentialRef: "",
				},
				null,
			),
			true,
		);

		// Chưa từng có config trong DB và form rỗng -> không dirty
		assert.equal(
			isStreamConfigDirty(
				{
					host: "",
					port: "",
					username: "",
					mainStreamPath: "",
					credentialRef: "",
				},
				null,
			),
			false,
		);
	});
});
