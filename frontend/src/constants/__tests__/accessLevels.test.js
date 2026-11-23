import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	NORMAL_USER_INITIAL_LEVELS,
	describeInitialLevel,
	pickInitialLevel,
} from "../accessLevels.js";

// BR-AL-33 / V136: cấp chọn sẵn trong form tạo người dùng thường từ ACCESS_LEVEL_DEFAULT_NORMAL_USER
describe("pickInitialLevel", () => {
	it("giá trị cấu hình hợp lệ (1, 2; chuỗi hoặc số) được chọn sẵn", () => {
		assert.deepEqual(pickInitialLevel("1"), { level: 1, invalid: false, configured: 1 });
		assert.deepEqual(pickInitialLevel("2"), { level: 2, invalid: false, configured: 2 });
		assert.deepEqual(pickInitialLevel(2), { level: 2, invalid: false, configured: 2 });
	});

	it("giá trị cũ ngoài khoảng (3, 0, -1, 99) -> cấp thấp nhất, đánh dấu invalid, giữ số đã cấu hình để báo", () => {
		for (const raw of ["3", "0", "-1", "99", 3]) {
			const pick = pickInitialLevel(raw);
			assert.equal(pick.level, NORMAL_USER_INITIAL_LEVELS[0]);
			assert.equal(pick.level, 1);
			assert.equal(pick.invalid, true);
			assert.equal(pick.configured, Number(raw));
		}
	});

	it("không đọc được cấu hình (null, undefined, chữ) -> cấp thấp nhất, configured null; không bao giờ trả rỗng", () => {
		for (const raw of [null, undefined, "", "abc"]) {
			const pick = pickInitialLevel(raw);
			assert.equal(pick.level, 1);
			assert.equal(pick.invalid, true);
			assert.equal(pick.configured, null);
		}
	});

	it("luôn trả một cấp nằm trong các lựa chọn của form", () => {
		for (const raw of ["1", "2", "3", null, "x", -5]) {
			assert.ok(NORMAL_USER_INITIAL_LEVELS.includes(pickInitialLevel(raw).level));
		}
	});
});

describe("describeInitialLevel", () => {
	it("quyền mời khách theo GUEST_HOST_MIN_LEVEL, không đoán khi chưa đọc được", () => {
		assert.match(describeInitialLevel(1, 2), /không mời khách/);
		assert.match(describeInitialLevel(2, 2), /được mời khách/);
		assert.match(describeInitialLevel(2, 3), /không mời khách/);
		assert.doesNotMatch(describeInitialLevel(2, null), /mời khách/);
	});
});
