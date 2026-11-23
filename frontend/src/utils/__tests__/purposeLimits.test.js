import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { purposeLimitState } from "../purposeLimits.js";

const LOADED = { min: 10, max: 500, loaded: true };

describe("purposeLimitState (việc 7: mục đích đơn truy cập theo REASON_MIN_LENGTH / REASON_MAX_LENGTH)", () => {
	it("đã đọc được giới hạn: dưới min báo lỗi, đúng min / max hợp lệ, maxLength = max", () => {
		const short = purposeLimitState("a".repeat(9), LOADED);
		assert.equal(short.enforced, true);
		assert.equal(short.maxLength, 500);
		assert.equal(short.error, "Mục đích phải từ 10 đến 500 ký tự (hiện có 9).");
		assert.equal(purposeLimitState("a".repeat(10), LOADED).error, null);
		assert.equal(purposeLimitState("a".repeat(500), LOADED).error, null);
		assert.equal(purposeLimitState("a".repeat(501), LOADED).error, "Mục đích phải từ 10 đến 500 ký tự (hiện có 501).");
	});

	it("đo sau trim; ô trống không báo lỗi độ dài (để kiểm bắt buộc riêng)", () => {
		assert.equal(purposeLimitState("   " + "a".repeat(9) + "   ", LOADED).error, "Mục đích phải từ 10 đến 500 ký tự (hiện có 9).");
		assert.equal(purposeLimitState("", LOADED).error, null);
		assert.equal(purposeLimitState(undefined, LOADED).error, null);
	});

	it("giới hạn đổi theo cấu hình (vd. 20 / 50): câu báo và bộ đếm dùng giá trị mới", () => {
		const state = purposeLimitState("m".repeat(60), { min: 20, max: 50, loaded: true });
		assert.equal(state.error, "Mục đích phải từ 20 đến 50 ký tự (hiện có 60).");
		assert.equal(state.maxLength, 50);
		assert.equal(state.hint, "Bắt buộc, từ 20 đến 50 ký tự");
		assert.equal(state.counter, "60/50 ký tự");
	});

	it("chưa đọc được giới hạn: không chặn ở trình duyệt (không maxLength, không lỗi), để backend kiểm", () => {
		for (const limits of [undefined, null, { min: 10, max: 500, loaded: false }]) {
			const state = purposeLimitState("a".repeat(3), limits);
			assert.equal(state.enforced, false);
			assert.equal(state.maxLength, undefined);
			assert.equal(state.error, null);
			assert.equal(state.counter, "3 ký tự");
		}
		assert.equal(purposeLimitState("a".repeat(2000), { loaded: false }).error, null);
	});
});
