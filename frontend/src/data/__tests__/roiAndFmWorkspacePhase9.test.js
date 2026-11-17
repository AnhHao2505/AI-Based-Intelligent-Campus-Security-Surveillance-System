import { describe, it } from "node:test";
import assert from "node:assert/strict";

import { resolveRoiModalSnapshot, buildRoiUpdatePayload } from "../../utils/roiUtils.js";
import { resolveCameraInspectorAccess } from "../../utils/campusCameraHelpers.js";

describe("Hạng mục 3: resolveRoiModalSnapshot (Live Canvas / 16:9 Grid, không dùng ảnh ref)", () => {
	it("Happy Path: khi player live và captureFrame() trả về base64 -> dùng khung hình live đó", () => {
		const fakePlayer = {
			captureFrame: () => ({
				base64: "data:image/jpeg;base64,/9j/liveframe",
				width: 1280,
				height: 720,
			}),
		};
		const res = resolveRoiModalSnapshot("live", fakePlayer);
		assert.deepEqual(res, {
			data: "data:image/jpeg;base64,/9j/liveframe",
			width: 1280,
			height: 720,
		});
	});

	it("Boundary (Offline): khi player chưa live -> trả về data=null, 1920x1080 để mở thẳng lưới 16:9", () => {
		const res = resolveRoiModalSnapshot("offline", null);
		assert.deepEqual(res, {
			data: null,
			width: 1920,
			height: 1080,
		});
	});

	it("Exception Safety: khi captureFrame() ném lỗi hoặc trả null -> vẫn fallback an toàn sang data=null (16:9)", () => {
		const brokenPlayer = {
			captureFrame: () => {
				throw new Error("Tainted canvas");
			},
		};
		const res = resolveRoiModalSnapshot("live", brokenPlayer);
		assert.deepEqual(res, {
			data: null,
			width: 1920,
			height: 1080,
		});
	});
});

describe("Hạng mục 4: resolveCameraInspectorAccess (Ẩn cameraDetails cho FACILITY_MANAGER)", () => {
	it("ADMIN: được phép mở tab cameras và giữ selectedCameraId từ URL", () => {
		const res = resolveCameraInspectorAccess({
			isAdmin: true,
			requestedTab: "cameras",
			requestedCameraId: "cam-123",
		});
		assert.deepEqual(res, {
			showCameraTab: true,
			sidebarMode: "cameras",
			selectedCameraId: "cam-123",
		});
	});

	it("FACILITY_MANAGER: ẩn tab cameras và chặn selectedCameraId kể cả khi URL có ?tab=cameras&cameraId=...", () => {
		const res = resolveCameraInspectorAccess({
			isAdmin: false,
			requestedTab: "cameras",
			requestedCameraId: "cam-123",
		});
		assert.deepEqual(res, {
			showCameraTab: false,
			sidebarMode: "areas",
			selectedCameraId: null,
		});
	});

	it("Boundary (ADMIN mặc định): khi không truyền requestedTab/requestedCameraId -> mở tab areas và selectedCameraId=null", () => {
		const res = resolveCameraInspectorAccess({
			isAdmin: true,
			requestedTab: undefined,
			requestedCameraId: "",
		});
		assert.deepEqual(res, {
			showCameraTab: true,
			sidebarMode: "areas",
			selectedCameraId: null,
		});
	});
});

describe("buildRoiUpdatePayload (Null/Undefined snapshotMeta resilience)", () => {
	it("Happy Path: với roiGeometry và snapshotMeta=null -> không throw TypeError destructure", () => {
		const roi = { polygons: [{ label: "Zone 1", vertices: [] }] };
		assert.deepEqual(buildRoiUpdatePayload(roi, null), {
			roiGeometry: roi,
			snapshot_base64: undefined,
			snapshot_width: undefined,
			snapshot_height: undefined,
		});
	});

	it("Boundary: khi không truyền snapshotMeta (undefined) -> trả về payload hợp lệ", () => {
		const roi = { polygons: [] };
		assert.deepEqual(buildRoiUpdatePayload(roi), {
			roiGeometry: roi,
			snapshot_base64: undefined,
			snapshot_width: undefined,
			snapshot_height: undefined,
		});
	});

	it("Happy Path: khi truyền snapshotMeta có kích thước -> giữ nguyên giá trị", () => {
		const roi = { polygons: [] };
		assert.deepEqual(
			buildRoiUpdatePayload(roi, {
				snapshotBase64: null,
				snapshotWidth: 1280,
				snapshotHeight: 720,
			}),
			{
				roiGeometry: roi,
				snapshot_base64: null,
				snapshot_width: 1280,
				snapshot_height: 720,
			},
		);
	});
});

describe("Camera module stale code regression guards", () => {
	it("does not reference removed loadLogs, latencyMs, or captureSnapshot in camera UI/service", async () => {
		const { readFileSync } = await import("node:fs");
		const drawer = readFileSync(
			new URL("../../components/camera/CameraInspectorDrawer.jsx", import.meta.url),
			"utf8",
		);
		const detailPage = readFileSync(
			new URL("../../pages/cameras/CameraDetailPage.jsx", import.meta.url),
			"utf8",
		);
		const streamTab = readFileSync(
			new URL("../../components/camera/CameraStreamTab.jsx", import.meta.url),
			"utf8",
		);
		const service = readFileSync(
			new URL("../../services/cameraService.js", import.meta.url),
			"utf8",
		);

		assert.equal(drawer.includes("loadLogs("), false);
		assert.equal(drawer.includes("latencyMs"), false);
		assert.equal(detailPage.includes("loadLogs("), false);
		assert.equal(detailPage.includes("latencyMs"), false);
		assert.equal(streamTab.includes("đo độ trễ mạng"), false);
		assert.equal(service.includes("captureSnapshot"), false);
	});
});
