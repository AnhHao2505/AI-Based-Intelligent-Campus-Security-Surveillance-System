import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	INITIAL_WARMUP_FAILURE_THRESHOLD,
	hasDecodedVideoFrame,
	resolveWhepFailureTransition,
	formatWhepHttpError,
} from "../webrtcState.js";

describe("webrtcState", () => {
	it("chỉ công nhận có khung hình thật khi videoWidth > 0 && videoHeight > 0", () => {
		assert.equal(hasDecodedVideoFrame(null), false);
		assert.equal(hasDecodedVideoFrame({ videoWidth: 0, videoHeight: 0, paused: false }), false);
		assert.equal(hasDecodedVideoFrame({ videoWidth: 1280, videoHeight: 0 }), false);
		assert.equal(hasDecodedVideoFrame({ videoWidth: 1280, videoHeight: 720 }), true);
	});

	it("giữ trạng thái connecting trong giai đoạn warm-up ban đầu rồi mới chuyển offline", () => {
		for (let i = 0; i < INITIAL_WARMUP_FAILURE_THRESHOLD; i++) {
			const res = resolveWhepFailureTransition({
				consecutiveFailures: i,
				hadLiveFrame: false,
			});
			assert.equal(res.status, "connecting");
			assert.equal(res.nextFailures, i + 1);
		}

		const afterWarmup = resolveWhepFailureTransition({
			consecutiveFailures: INITIAL_WARMUP_FAILURE_THRESHOLD,
			hadLiveFrame: false,
		});
		assert.equal(afterWarmup.status, "offline");
	});

	it("nếu luồng đã từng live rồi bị rớt thì báo offline ngay lập tức để thử kết nối lại", () => {
		const res = resolveWhepFailureTransition({
			consecutiveFailures: 0,
			hadLiveFrame: true,
		});
		assert.equal(res.status, "offline");
		assert.equal(res.nextFailures, 1);
	});

	it("diễn giải lỗi 404/400 từ MediaMTX WHEP dễ hiểu", () => {
		assert.match(formatWhepHttpError(404), /MediaMTX chưa nhận được luồng phát/);
		assert.match(formatWhepHttpError(401), /401/);
	});
});
