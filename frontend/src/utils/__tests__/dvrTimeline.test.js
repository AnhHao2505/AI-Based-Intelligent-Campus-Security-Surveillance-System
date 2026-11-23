import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	computeDvrWindow,
	computeRecordedIntervalsPercent,
	formatRelativeBehindLive,
	mapAlertsToScrubberMarkers,
	resolvePlaybackSeekTarget,
} from "../dvrTimeline.js";

describe("dvrTimeline", () => {
	it("computes window from recorded segments and expands for older initialSeekTimeMs", () => {
		const liveNowMs = 1_700_000_000_000;
		const segStart = liveNowMs - 3 * 3600 * 1000; // 3 hours ago
		const segEnd = liveNowMs;

		const defaultWin = computeDvrWindow({
			segments: [{ startMs: segStart, endMs: segEnd }],
			liveNowMs,
			maxSpanMs: 3600 * 1000,
		});
		assert.equal(defaultWin.hasRecordedData, true);
		assert.equal(defaultWin.windowEndMs, liveNowMs);
		assert.equal(defaultWin.windowStartMs, liveNowMs - 3600 * 1000);

		const oldAlertMs = liveNowMs - 2 * 3600 * 1000; // 2 hours ago
		const expandedWin = computeDvrWindow({
			segments: [{ startMs: segStart, endMs: segEnd }],
			liveNowMs,
			initialSeekTimeMs: oldAlertMs,
			maxSpanMs: 3600 * 1000,
		});
		assert.ok(expandedWin.windowStartMs <= oldAlertMs - 10 * 60 * 1000);
		assert.ok(expandedWin.windowStartMs >= segStart);
	});

	it("maps real-time and historical alerts to accurate scrubber percentages with recent & focused flags", () => {
		const windowStartMs = 1_700_000_000_000;
		const windowEndMs = windowStartMs + 100_000;
		const liveNowMs = windowEndMs;

		const markers = mapAlertsToScrubberMarkers({
			alerts: [
				{ id: "old", rawTime: windowStartMs + 25_000, message: "Old Alert" },
				{ id: "fresh", rawTime: liveNowMs - 3_000, message: "Live Alert" },
				{ id: "out-of-range", rawTime: windowStartMs - 60_000, message: "Too Old" },
			],
			windowStartMs,
			windowEndMs,
			liveNowMs,
			focusedAlertId: "old",
		});

		assert.equal(markers.length, 2);
		assert.equal(markers[0].id, "old");
		assert.equal(markers[0].percent, 25);
		assert.equal(markers[0].isFocused, true);
		assert.equal(markers[0].isRecent, false);

		assert.equal(markers[1].id, "fresh");
		assert.equal(markers[1].percent, 97);
		assert.equal(markers[1].isFocused, false);
		assert.equal(markers[1].isRecent, true);
	});

	it("formats relative lag behind LIVE properly", () => {
		const now = 1_700_000_000_000;
		assert.equal(formatRelativeBehindLive(now - 1000, now), "TRỰC TIẾP");
		assert.equal(formatRelativeBehindLive(now - 75_000, now), "-01:15");
		assert.equal(formatRelativeBehindLive(now - 3665_000, now), "-1:01:05");
	});

	it("tính đúng các khoảng đã ghi hình và tự neo khi bấm vào khoảng trống sát mép LIVE", () => {
		const now = 1_700_000_100_000;
		const winStart = now - 100_000;
		const segments = [
			{ startMs: winStart + 10_000, endMs: winStart + 50_000 },
			{ startMs: winStart + 51_000, endMs: now - 12_000 }, // cách LIVE 12s (đang flush)
		];

		const intervals = computeRecordedIntervalsPercent({
			segments,
			windowStartMs: winStart,
			windowEndMs: now,
		});
		assert.equal(intervals.length, 1); // gộp do cách nhau <= 2s
		assert.equal(intervals[0].leftPercent, 10);
		assert.equal(intervals[0].widthPercent, 78);

		// Người dùng bấm -9s (sát mép LIVE chưa kịp flush) -> tự neo về cuối segment mới nhất thay vì lỗi 404
		const snap = resolvePlaybackSeekTarget({
			requestedMs: now - 9000,
			segments,
			liveNowMs: now,
		});
		assert.equal(snap.action, "seek");
		assert.equal(snap.snapped, true);
		assert.equal(snap.targetMs, now - 15_000);
	});
});
