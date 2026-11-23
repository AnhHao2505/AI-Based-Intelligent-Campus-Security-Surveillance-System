import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	GUEST_PURPOSE_LIMITS_INITIAL,
	readGuestPurposeLimits,
	guestPurposeLimitsReducer,
	createGuestPurposeLimitsLoader,
	guestPurposeState,
} from "../guestPurposeLimits.js";

/**
 * Mô phỏng useGuestPurposeLimits: reducer thật + loader thật, API guest-purpose-limits giả
 * (mỗi lần gọi trả một promise do test tự resolve / reject).
 */
function setupPurposeLimits() {
	let state = GUEST_PURPOSE_LIMITS_INITIAL;
	const dispatch = (action) => {
		state = guestPurposeLimitsReducer(state, action);
	};
	const pending = [];
	const fetchLimits = () =>
		new Promise((resolve, reject) => {
			pending.push({ resolve, reject });
		});
	const reload = createGuestPurposeLimitsLoader(fetchLimits, dispatch);
	return { reload, pending, get state() { return state; } };
}

const P = (n) => "m".repeat(n);

describe("guestPurposeLimits: đọc lại giới hạn mục đích mỗi lần mở form tạo lượt khách", () => {
	it("mở lần 2 sau khi Admin đổi 10/500 -> 20/50: dùng 20/50 (mục đích 60 ký tự bị chặn)", async () => {
		const f = setupPurposeLimits();
		const first = f.reload();
		f.pending[0].resolve({ minLength: 10, maxLength: 500 });
		await first;
		assert.deepEqual(f.state, { status: "ready", min: 10, max: 500 });
		const s1 = guestPurposeState(P(60), f.state);
		assert.equal(s1.error, false);
		assert.equal(s1.blocker, null);
		assert.equal(s1.counter, "60/500 ký tự (tối thiểu 10)");

		const second = f.reload();
		assert.equal(f.pending.length, 2, "mở lại form phải gọi lại API");
		f.pending[1].resolve({ minLength: 20, maxLength: 50 });
		await second;
		assert.deepEqual(f.state, { status: "ready", min: 20, max: 50 });
		const s2 = guestPurposeState(P(60), f.state);
		assert.equal(s2.error, true);
		assert.equal(s2.blocker, "mục đích 20–50 ký tự (hiện có 60)");
		assert.equal(s2.counter, "60/50 ký tự (tối thiểu 20)");
		assert.equal(guestPurposeState(P(15), f.state).blocker, "mục đích 20–50 ký tự (hiện có 15)");
	});

	it("đang tải lại: bỏ giới hạn cũ, không báo lỗi độ dài, bộ đếm chỉ hiện số ký tự", async () => {
		const f = setupPurposeLimits();
		const first = f.reload();
		f.pending[0].resolve({ minLength: 20, maxLength: 50 });
		await first;

		f.reload(); // lần mở 2, API chưa trả về
		assert.deepEqual(f.state, { status: "loading", min: null, max: null });
		for (const n of [1, 9, 60, 501, 5000]) {
			const s = guestPurposeState(P(n), f.state);
			assert.equal(s.enforced, false, String(n));
			assert.equal(s.error, false, String(n));
			assert.equal(s.blocker, null, String(n));
			assert.equal(s.counter, `${n} ký tự`);
		}
	});

	it("tải lỗi: không dùng số đoán 10/500, không chặn độ dài; ô trống vẫn khoá (mục đích bắt buộc)", async () => {
		const f = setupPurposeLimits();
		const first = f.reload();
		f.pending[0].reject(new Error("500"));
		await first;
		assert.deepEqual(f.state, { status: "error", min: null, max: null });
		assert.equal(guestPurposeState(P(5), f.state).blocker, null);
		assert.equal(guestPurposeState(P(5), f.state).error, false);
		assert.equal(guestPurposeState(P(600), f.state).blocker, null);
		assert.equal(guestPurposeState(P(600), f.state).counter, "600 ký tự");
		assert.equal(guestPurposeState("   ", f.state).blocker, "nhập mục đích");
		assert.equal(guestPurposeState("", f.state).error, false);
	});

	it("trong giới hạn thì không báo lỗi; đúng min / max hợp lệ; đo sau trim", async () => {
		const f = setupPurposeLimits();
		const first = f.reload();
		f.pending[0].resolve({ minLength: 20, maxLength: 50 });
		await first;
		for (const n of [20, 35, 50]) {
			const s = guestPurposeState(P(n), f.state);
			assert.equal(s.error, false, String(n));
			assert.equal(s.blocker, null, String(n));
		}
		assert.equal(guestPurposeState(P(19), f.state).error, true);
		assert.equal(guestPurposeState(P(51), f.state).error, true);
		const trimmed = guestPurposeState(`   ${P(19)}   `, f.state);
		assert.equal(trimmed.length, 19);
		assert.equal(trimmed.error, true);
	});

	it("đã đọc được giới hạn: ô trống không tô lỗi nhưng vẫn khoá Gửi duyệt", async () => {
		const s = guestPurposeState("", { status: "ready", min: 10, max: 500 });
		assert.equal(s.error, false);
		assert.equal(s.blocker, "mục đích 10–500 ký tự (hiện có 0)");
	});

	it("lần mở cũ trả về muộn không ghi đè giới hạn của lần mở mới", async () => {
		const f = setupPurposeLimits();
		const stale = f.reload();
		const fresh = f.reload();
		f.pending[1].resolve({ minLength: 20, maxLength: 50 });
		await fresh;
		f.pending[0].resolve({ minLength: 10, maxLength: 500 });
		await stale;
		assert.deepEqual(f.state, { status: "ready", min: 20, max: 50 });
	});

	it("lần mở cũ lỗi muộn không xoá giới hạn của lần mở mới", async () => {
		const f = setupPurposeLimits();
		const stale = f.reload();
		const fresh = f.reload();
		f.pending[1].resolve({ minLength: 20, maxLength: 50 });
		await fresh;
		f.pending[0].reject(new Error("timeout"));
		await stale;
		assert.deepEqual(f.state, { status: "ready", min: 20, max: 50 });
	});

	it("dữ liệu API không hợp lệ -> coi như chưa đọc được, không chặn", async () => {
		for (const data of [null, {}, { minLength: 10 }, { minLength: "a", maxLength: 500 }, { minLength: 0, maxLength: 500 }, { minLength: 60, maxLength: 50 }]) {
			const f = setupPurposeLimits();
			const p = f.reload();
			f.pending[0].resolve(data);
			await p;
			assert.equal(f.state.status, "error", JSON.stringify(data));
			assert.equal(guestPurposeState(P(5), f.state).blocker, null, JSON.stringify(data));
		}
	});

	it("readGuestPurposeLimits / reducer: giá trị hợp lệ, trạng thái đầu, action lạ", () => {
		assert.deepEqual(readGuestPurposeLimits({ minLength: 20, maxLength: 50 }), { min: 20, max: 50 });
		assert.deepEqual(readGuestPurposeLimits({ minLength: "15", maxLength: "15" }), { min: 15, max: 15 });
		assert.deepEqual(GUEST_PURPOSE_LIMITS_INITIAL, { status: "idle", min: null, max: null });
		assert.equal(guestPurposeState(P(3), GUEST_PURPOSE_LIMITS_INITIAL).enforced, false);
		const ready = { status: "ready", min: 20, max: 50 };
		assert.equal(guestPurposeLimitsReducer(ready, { type: "khác" }), ready);
		assert.equal(guestPurposeLimitsReducer(ready, undefined), ready);
	});
});
