import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	filterAlertsByDateRange,
	formatDateFilterSummary,
	getAlertTimestampMs,
	parseLocalDateBoundaryMs,
} from "../alertFilters.js";

describe("parseLocalDateBoundaryMs", () => {
	it("trả về đầu ngày và cuối ngày theo giờ địa phương", () => {
		const start = parseLocalDateBoundaryMs("2026-11-25", "start");
		const end = parseLocalDateBoundaryMs("2026-11-25", "end");
		assert.ok(typeof start === "number" && typeof end === "number");
		const dStart = new Date(start);
		const dEnd = new Date(end);
		assert.equal(dStart.getHours(), 0);
		assert.equal(dStart.getMinutes(), 0);
		assert.equal(dStart.getSeconds(), 0);
		assert.equal(dStart.getMilliseconds(), 0);
		assert.equal(dEnd.getHours(), 23);
		assert.equal(dEnd.getMinutes(), 59);
		assert.equal(dEnd.getSeconds(), 59);
		assert.equal(dEnd.getMilliseconds(), 999);
	});

	it("trả về null với chuỗi rỗng hoặc ngày không tồn tại trên lịch", () => {
		assert.equal(parseLocalDateBoundaryMs("", "start"), null);
		assert.equal(parseLocalDateBoundaryMs(null, "start"), null);
		assert.equal(parseLocalDateBoundaryMs("2026-02-31", "start"), null);
		assert.equal(parseLocalDateBoundaryMs("invalid", "end"), null);
	});
});

describe("filterAlertsByDateRange", () => {
	const makeAlert = (id, y, m, d, hh = 12, mm = 0, ss = 0, ms = 0) => ({
		id,
		rawTime: new Date(y, m - 1, d, hh, mm, ss, ms).getTime(),
	});

	const sample = [
		makeAlert("a1", 2026, 11, 20, 23, 59, 59, 999),
		makeAlert("a2", 2026, 11, 21, 0, 0, 0, 0),
		makeAlert("a3", 2026, 11, 23, 14, 30, 0, 0),
		makeAlert("a4", 2026, 11, 25, 23, 59, 59, 999),
		makeAlert("a5", 2026, 11, 26, 0, 0, 0, 0),
	];

	it("giữ nguyên toàn bộ danh sách khi không chọn ngày", () => {
		assert.deepEqual(
			filterAlertsByDateRange(sample, "", "").map((a) => a.id),
			["a1", "a2", "a3", "a4", "a5"],
		);
	});

	it("lọc đúng khi chỉ chọn Từ ngày (bao gồm mốc 00:00:00.000)", () => {
		assert.deepEqual(
			filterAlertsByDateRange(sample, "2026-11-21", "").map((a) => a.id),
			["a2", "a3", "a4", "a5"],
		);
	});

	it("lọc đúng khi chỉ chọn Đến ngày (bao gồm mốc 23:59:59.999)", () => {
		assert.deepEqual(
			filterAlertsByDateRange(sample, "", "2026-11-25").map((a) => a.id),
			["a1", "a2", "a3", "a4"],
		);
	});

	it("lọc đúng khoảng Từ ngày - Đến ngày (cùng ngày và nhiều ngày)", () => {
		assert.deepEqual(
			filterAlertsByDateRange(sample, "2026-11-21", "2026-11-25").map((a) => a.id),
			["a2", "a3", "a4"],
		);
		assert.deepEqual(
			filterAlertsByDateRange(sample, "2026-11-23", "2026-11-23").map((a) => a.id),
			["a3"],
		);
	});

	it("hỗ trợ fallback sang detectedAt khi không có rawTime và loại bỏ bản ghi thiếu timestamp", () => {
		const isoMidday = new Date(2026, 10, 22, 10, 0, 0).toISOString();
		const mixed = [
			{ id: "iso", detectedAt: isoMidday },
			{ id: "missing" },
		];
		assert.deepEqual(
			filterAlertsByDateRange(mixed, "2026-11-22", "2026-11-22").map((a) => a.id),
			["iso"],
		);
		assert.equal(getAlertTimestampMs({ id: "missing" }), null);
	});
});

describe("formatDateFilterSummary", () => {
	it("định dạng nhãn tiếng Việt cho 1 ngày, khoảng ngày và một đầu", () => {
		assert.equal(formatDateFilterSummary("2026-11-25", "2026-11-25"), "trong ngày 25/11/2026");
		assert.equal(formatDateFilterSummary("2026-11-21", "2026-11-25"), "từ 21/11/2026 đến 25/11/2026");
		assert.equal(formatDateFilterSummary("2026-11-21", ""), "từ ngày 21/11/2026");
		assert.equal(formatDateFilterSummary("", "2026-11-25"), "đến ngày 25/11/2026");
		assert.equal(formatDateFilterSummary("", ""), "");
	});
});
