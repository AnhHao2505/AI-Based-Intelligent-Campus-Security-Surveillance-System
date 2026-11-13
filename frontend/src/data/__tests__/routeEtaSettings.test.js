import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	DEFAULT_FLOOR_CHANGE_SECONDS,
	DEFAULT_WALKING_SPEED_MPS,
	computeDigitizedRoute,
	createDefaultGeometryStore,
} from "../campusIndoor3dData.js";

// P-MAP-2 (V123, MM-05): thời gian dự kiến của lộ trình lấy tốc độ đi bộ và thời gian đổi tầng từ cấu hình
// (WALKING_SPEED_MPS, FLOOR_CHANGE_SECONDS); thiếu / sai thì dùng mặc định 1,45 m/s và 14 giây.
function routeToEpsilonFloor4(extra = {}) {
	const store = createDefaultGeometryStore();
	const outdoor = store.navNodes.find((n) => n.building === "OUTDOOR");
	const room = store.roomPolygons.find((r) => r.building === "Tòa Epsilon" && r.floorOrder === 4);
	return computeDigitizedRoute({
		store,
		startCoords: outdoor.coords,
		startBuilding: "OUTDOOR",
		startFloorOrder: 0,
		endCoords: room.ring[0],
		endBuilding: "Tòa Epsilon",
		endFloorOrder: 4,
		...extra,
	});
}

describe("computeDigitizedRoute — tham số ước tính thời gian (route-settings)", () => {
	it("mặc định giữ 1,45 m/s và 14 giây / tầng như trước", () => {
		assert.equal(DEFAULT_WALKING_SPEED_MPS, 1.45);
		assert.equal(DEFAULT_FLOOR_CHANGE_SECONDS, 14);
		const r = routeToEpsilonFloor4();
		const explicit = routeToEpsilonFloor4({ walkingSpeedMps: 1.45, floorChangeSeconds: 14 });
		assert.ok(r && r.floorsClimbed === 4);
		assert.equal(r.estimatedSeconds, explicit.estimatedSeconds);
		const approx = r.totalDistanceMeters / 1.45 + r.floorsClimbed * 14;
		assert.ok(Math.abs(r.estimatedSeconds - approx) <= 1, `${r.estimatedSeconds} vs ${approx}`);
	});

	it("đổi tốc độ / thời gian đổi tầng thì thời gian dự kiến đổi theo, đường đi không đổi", () => {
		const base = routeToEpsilonFloor4({ walkingSpeedMps: 1.0, floorChangeSeconds: 0 });
		const slowFloors = routeToEpsilonFloor4({ walkingSpeedMps: 1.0, floorChangeSeconds: 100 });
		assert.deepEqual(
			slowFloors.pathNodes.map((n) => n.id),
			base.pathNodes.map((n) => n.id),
		);
		assert.ok(Math.abs(slowFloors.estimatedSeconds - base.estimatedSeconds - base.floorsClimbed * 100) <= 1);
		const fast = routeToEpsilonFloor4({ walkingSpeedMps: 2.0, floorChangeSeconds: 0 });
		assert.ok(Math.abs(fast.estimatedSeconds * 2 - base.estimatedSeconds) <= 2);
	});

	it("giá trị không hợp lệ (0, âm, không phải số) → dùng mặc định", () => {
		const def = routeToEpsilonFloor4();
		for (const bad of [{ walkingSpeedMps: 0 }, { walkingSpeedMps: -1 }, { walkingSpeedMps: "abc" }, { floorChangeSeconds: -5 }]) {
			assert.equal(routeToEpsilonFloor4(bad).estimatedSeconds, def.estimatedSeconds, JSON.stringify(bad));
		}
	});
});
