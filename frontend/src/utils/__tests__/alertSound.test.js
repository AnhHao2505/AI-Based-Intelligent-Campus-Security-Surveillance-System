import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	COOLDOWN_MS,
	GROUP_WINDOW_MS,
	PLAYED_TTL_MS,
	decideAlertSound,
	emptySoundState,
} from "../alertSound.js";

function run(steps) {
	let state = emptySoundState();
	return steps.map(([alert, now]) => {
		const r = decideAlertSound(state, alert, now);
		state = r.state;
		return r.play;
	});
}

describe("decideAlertSound", () => {
	it("cùng id gửi 2 lần (backend phát lại / 2 kết nối STOMP) -> chỉ kêu 1 lần", () => {
		const a = { keys: ["inc-1", "evt-1"], group: "cam-001|UNKNOWN_PERSON" };
		assert.deepEqual(run([[a, 1000], [a, 1010]]), [true, false]);
	});

	it("STEP1 -> BROADCAST gửi lại sau vài phút -> không kêu lại", () => {
		const a = { keys: ["inc-1"], group: "cam-001|UNKNOWN_PERSON" };
		assert.deepEqual(run([[a, 0], [a, 3 * 60 * 1000]]), [true, false]);
	});

	it("trùng eventId dù id khác -> coi là cùng sự cố", () => {
		assert.deepEqual(
			run([
				[{ keys: ["evt-9"] }, 0],
				[{ keys: ["inc-9", "evt-9"] }, 10_000],
			]),
			[true, false],
		);
	});

	it("cùng camera + cùng loại trong cửa sổ gộp -> kêu 1 lần", () => {
		const g = "cam-002|PERSON_INTRUSION";
		assert.deepEqual(
			run([
				[{ keys: ["a"], group: g }, 0],
				[{ keys: ["b"], group: g }, GROUP_WINDOW_MS - 1],
				[{ keys: ["c"], group: g }, GROUP_WINDOW_MS * 3],
			]),
			[true, false, true],
		);
	});

	it("2 sự cố khác camera dồn trong khoảng nghỉ -> kêu 1 lần, sau khoảng nghỉ kêu tiếp", () => {
		assert.deepEqual(
			run([
				[{ keys: ["a"], group: "cam-1|X" }, 0],
				[{ keys: ["b"], group: "cam-2|X" }, COOLDOWN_MS - 1],
				[{ keys: ["c"], group: "cam-3|X" }, COOLDOWN_MS * 2],
			]),
			[true, false, true],
		);
	});

	it("không có id nào -> không kêu", () => {
		assert.deepEqual(run([[{ keys: [null, undefined, ""] }, 0]]), [false]);
	});

	it("id cũ quá hạn lưu được xoá khỏi trạng thái", () => {
		const r1 = decideAlertSound(emptySoundState(), { keys: ["old"] }, 0);
		const r2 = decideAlertSound(r1.state, { keys: ["new"] }, PLAYED_TTL_MS + 1);
		assert.equal("old" in r2.state.keys, false);
		assert.equal(r2.play, true);
	});

	it("id số và id chuỗi cùng giá trị -> trùng", () => {
		assert.deepEqual(run([[{ keys: [42] }, 0], [{ keys: ["42"] }, 60_000]]), [true, false]);
	});
});
