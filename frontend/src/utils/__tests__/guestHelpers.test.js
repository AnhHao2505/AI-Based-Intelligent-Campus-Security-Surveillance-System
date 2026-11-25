import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	canReviewGuestVisit,
	canHostReplacePhoto,
	validateGuestPhotoFile,
	readGuestPhotoMaxKb,
	guestPhotoLimitText,
	getErrorGuestIndex,
	guestHasPhoto,
	GUEST_FORM_LIMITS_INITIAL,
	guestFormLimitsReducer,
	createGuestFormLimitsLoader,
} from "../guestHelpers.js";

const NOW = new Date("2026-11-21T09:00:00+07:00");
const FUTURE = "2026-11-23T09:00:00+07:00";
const PAST = "2026-11-21T08:00:00+07:00";

describe("canReviewGuestVisit (C2: nút Duyệt / Từ chối trên dòng của FM)", () => {
	const pending = { status: "PENDING", startTime: FUTURE, hostCode: "SV-001" };

	it("hiện khi lượt Chờ duyệt, chưa tới giờ bắt đầu, người mời không phải FM đang đăng nhập", () => {
		assert.equal(canReviewGuestVisit(pending, "FM-001", NOW), true);
	});

	it("ẩn khi lượt không còn Chờ duyệt", () => {
		for (const status of ["APPROVED", "REJECTED", "CANCELLED", "REVOKED", "EXPIRED", "COMPLETED"]) {
			assert.equal(canReviewGuestVisit({ ...pending, status }, "FM-001", NOW), false, status);
		}
	});

	it("ẩn khi đã tới hoặc qua giờ bắt đầu", () => {
		assert.equal(canReviewGuestVisit({ ...pending, startTime: PAST }, "FM-001", NOW), false);
		assert.equal(canReviewGuestVisit({ ...pending, startTime: NOW.toISOString() }, "FM-001", NOW), false);
	});

	it("ẩn khi FM đang đăng nhập chính là người mời (không tự duyệt)", () => {
		assert.equal(canReviewGuestVisit({ ...pending, hostCode: "FM-001" }, "FM-001", NOW), false);
	});

	it("không có lượt -> ẩn", () => {
		assert.equal(canReviewGuestVisit(null, "FM-001", NOW), false);
	});
});

describe("canHostReplacePhoto (A5: host thay ảnh)", () => {
	const guest = { biometricStatus: "PHOTO_READY" };

	it("được thay khi lượt Chờ duyệt hoặc Đã duyệt mà chưa tới giờ bắt đầu", () => {
		assert.equal(canHostReplacePhoto({ status: "PENDING", startTime: FUTURE }, guest, NOW), true);
		assert.equal(canHostReplacePhoto({ status: "APPROVED", startTime: FUTURE }, guest, NOW), true);
	});

	it("không thay được khi đã tới giờ bắt đầu, lượt đã đóng, hoặc khách đã bị xoá dữ liệu", () => {
		assert.equal(canHostReplacePhoto({ status: "APPROVED", startTime: PAST }, guest, NOW), false);
		assert.equal(canHostReplacePhoto({ status: "REJECTED", startTime: FUTURE }, guest, NOW), false);
		assert.equal(canHostReplacePhoto({ status: "CANCELLED", startTime: FUTURE }, guest, NOW), false);
		assert.equal(
			canHostReplacePhoto({ status: "PENDING", startTime: FUTURE }, { biometricStatus: "DELETED" }, NOW),
			false,
		);
	});
});

describe("validateGuestPhotoFile (A1: ảnh JPG / PNG trong giới hạn)", () => {
	it("nhận JPG / PNG không quá giới hạn", () => {
		assert.equal(validateGuestPhotoFile({ type: "image/jpeg", size: 2048 * 1024 }, 2048), null);
		assert.equal(validateGuestPhotoFile({ type: "image/png", size: 10 }, 2048), null);
	});

	it("từ chối thiếu ảnh, sai định dạng, quá dung lượng (câu nêu giới hạn)", () => {
		assert.match(validateGuestPhotoFile(null, 2048), /Chưa chọn ảnh/);
		assert.match(validateGuestPhotoFile({ type: "image/gif", size: 10 }, 2048), /JPG hoặc PNG/);
		assert.match(validateGuestPhotoFile({ type: "image/jpeg", size: 2049 * 1024 }, 2048), /2048 KB/);
	});

	it("V141: câu lỗi nêu đúng giới hạn cấu hình đang dùng, không còn số 2048 viết cứng", () => {
		const msg = validateGuestPhotoFile({ type: "image/jpeg", size: 300 * 1024 + 1 }, 300);
		assert.match(msg, /Ảnh tối đa 300 KB/);
		assert.match(msg, /ảnh đang chọn 301 KB/);
		assert.doesNotMatch(msg, /2048/);
		assert.equal(validateGuestPhotoFile({ type: "image/jpeg", size: 300 * 1024 }, 300), null);
		assert.equal(validateGuestPhotoFile({ type: "image/png", size: 9000 * 1024 }, 10240), null);
	});

	it("V141: chưa đọc được giới hạn (null / không hợp lệ) -> không chặn dung lượng, vẫn kiểm thiếu ảnh và định dạng", () => {
		for (const maxKb of [null, undefined, NaN, 0, -1]) {
			assert.equal(validateGuestPhotoFile({ type: "image/jpeg", size: 50 * 1024 * 1024 }, maxKb), null, String(maxKb));
			assert.match(validateGuestPhotoFile({ type: "image/gif", size: 10 }, maxKb), /JPG hoặc PNG/);
			assert.match(validateGuestPhotoFile(null, maxKb), /Chưa chọn ảnh/);
		}
		// không truyền giới hạn: không còn mặc định đoán số
		assert.equal(validateGuestPhotoFile({ type: "image/jpeg", size: 50 * 1024 * 1024 }), null);
	});
});

describe("readGuestPhotoMaxKb (V141: giới hạn ảnh khách từ /eligibility)", () => {
	it("đọc photoMaxKb của /eligibility", () => {
		assert.equal(readGuestPhotoMaxKb({ canHost: true, photoMaxKb: 2048 }), 2048);
		assert.equal(readGuestPhotoMaxKb({ photoMaxKb: 100 }), 100);
		assert.equal(readGuestPhotoMaxKb({ photoMaxKb: "10240" }), 10240);
	});

	it("chưa tải được / thiếu / sai -> null (không đoán số)", () => {
		for (const eligibility of [null, undefined, {}, { photoMaxKb: null }, { photoMaxKb: 0 }, { photoMaxKb: -5 },
			{ photoMaxKb: 12.5 }, { photoMaxKb: "abc" }]) {
			assert.equal(readGuestPhotoMaxKb(eligibility), null, JSON.stringify(eligibility));
		}
	});
});

describe("guestPhotoLimitText (nhãn ô chọn ảnh khách)", () => {
	it("có giới hạn -> nêu số; chưa đọc được -> theo cấu hình hệ thống, không có số", () => {
		assert.equal(guestPhotoLimitText(2048), "tối đa 2048 KB");
		assert.equal(guestPhotoLimitText(100), "tối đa 100 KB");
		assert.equal(guestPhotoLimitText(null), "dung lượng tối đa theo cấu hình hệ thống");
		assert.doesNotMatch(guestPhotoLimitText(undefined), /\d/);
	});
});

describe("getErrorGuestIndex (A2: lỗi ảnh nêu đúng khách)", () => {
	it("đọc data.guestIndex trong body lỗi của backend", () => {
		assert.equal(getErrorGuestIndex({ data: { code: "ERR_GUEST_034", data: { guestIndex: 1 } } }), 1);
		assert.equal(getErrorGuestIndex({ data: { code: "ERR_GUEST_034", data: { guestIndex: 0 } } }), 0);
	});

	it("lỗi không gắn với khách nào -> null", () => {
		assert.equal(getErrorGuestIndex({ data: { code: "ERR_GUEST_014" } }), null);
		assert.equal(getErrorGuestIndex(new Error("mạng")), null);
	});
});

describe("guestHasPhoto", () => {
	it("chỉ PHOTO_READY / PHOTO_ONLY là còn ảnh", () => {
		assert.equal(guestHasPhoto({ biometricStatus: "PHOTO_READY" }), true);
		assert.equal(guestHasPhoto({ biometricStatus: "PHOTO_ONLY" }), true);
		assert.equal(guestHasPhoto({ biometricStatus: "NO_PHOTO" }), false);
		assert.equal(guestHasPhoto({ biometricStatus: "DELETED" }), false);
	});
});

/**
 * TC-F12-S07: form khách đọc lại giới hạn ảnh mỗi lần mở. Mô phỏng useGuestFormLimits: reducer thật + loader thật,
 * /eligibility giả (mỗi lần gọi trả một promise do test tự resolve / reject).
 */
function setupFormLimits() {
	let state = GUEST_FORM_LIMITS_INITIAL;
	const dispatch = (action) => {
		state = guestFormLimitsReducer(state, action);
	};
	const pending = [];
	const fetchEligibility = () =>
		new Promise((resolve, reject) => {
			pending.push({ resolve, reject });
		});
	const reload = createGuestFormLimitsLoader(fetchEligibility, dispatch);
	return { reload, pending, get state() { return state; } };
}

const PHOTO_233_KB = { type: "image/jpeg", size: 233 * 1024 };

describe("guestFormLimitsReducer / createGuestFormLimitsLoader (TC-F12-S07: đọc lại giới hạn mỗi lần mở form)", () => {
	it("mở form lần 2 sau khi Admin hạ GUEST_PHOTO_MAX_KB 2048 -> 100: dùng 100, chặn ảnh 233 KB", async () => {
		const f = setupFormLimits();

		const first = f.reload();
		f.pending[0].resolve({ canHost: true, consentNoticeVersion: "v1", photoMaxKb: 2048 });
		await first;
		assert.deepEqual(f.state, { status: "ready", photoMaxKb: 2048, consentVersion: "v1" });
		assert.equal(guestPhotoLimitText(f.state.photoMaxKb), "tối đa 2048 KB");
		assert.equal(validateGuestPhotoFile(PHOTO_233_KB, f.state.photoMaxKb), null);

		// đóng form, Admin đổi cấu hình, mở lại form (không tải lại trang)
		const second = f.reload();
		assert.equal(f.pending.length, 2, "mở lại form phải gọi lại /eligibility");
		f.pending[1].resolve({ canHost: true, consentNoticeVersion: "v1", photoMaxKb: 100 });
		assert.deepEqual(await second, { canHost: true, consentNoticeVersion: "v1", photoMaxKb: 100 });
		assert.equal(f.state.photoMaxKb, 100);
		assert.equal(guestPhotoLimitText(f.state.photoMaxKb), "tối đa 100 KB");
		assert.match(validateGuestPhotoFile(PHOTO_233_KB, f.state.photoMaxKb), /Ảnh tối đa 100 KB \(ảnh đang chọn 233 KB\)/);
	});

	it("đang tải lại: bỏ giá trị cũ (2048), không chặn dung lượng, khoá ô đồng ý tới khi đọc xong", async () => {
		const f = setupFormLimits();
		const first = f.reload();
		f.pending[0].resolve({ consentNoticeVersion: "v1", photoMaxKb: 2048 });
		await first;

		f.reload(); // lần mở thứ 2, /eligibility chưa trả về
		assert.deepEqual(f.state, { status: "loading", photoMaxKb: null, consentVersion: null });
		assert.equal(guestPhotoLimitText(f.state.photoMaxKb), "dung lượng tối đa theo cấu hình hệ thống");
		assert.equal(validateGuestPhotoFile({ type: "image/jpeg", size: 50 * 1024 * 1024 }, f.state.photoMaxKb), null);
		assert.match(validateGuestPhotoFile({ type: "image/gif", size: 10 }, f.state.photoMaxKb), /JPG hoặc PNG/);
	});

	it("tải lỗi: không chặn dung lượng (không đoán số, không giữ 2048 của lần trước), reload trả null", async () => {
		const f = setupFormLimits();
		const first = f.reload();
		f.pending[0].resolve({ consentNoticeVersion: "v1", photoMaxKb: 2048 });
		await first;

		const second = f.reload();
		f.pending[1].reject(new Error("500"));
		assert.equal(await second, null);
		assert.deepEqual(f.state, { status: "error", photoMaxKb: null, consentVersion: null });
		assert.equal(validateGuestPhotoFile(PHOTO_233_KB, f.state.photoMaxKb), null);
		assert.equal(validateGuestPhotoFile({ type: "image/png", size: 50 * 1024 * 1024 }, f.state.photoMaxKb), null);
	});

	it("/eligibility trả về không có photoMaxKb hợp lệ -> null, không chặn", async () => {
		const f = setupFormLimits();
		const first = f.reload();
		f.pending[0].resolve({ consentNoticeVersion: "v1", photoMaxKb: 0 });
		await first;
		assert.deepEqual(f.state, { status: "ready", photoMaxKb: null, consentVersion: "v1" });
		assert.equal(validateGuestPhotoFile(PHOTO_233_KB, f.state.photoMaxKb), null);

		const second = f.reload();
		f.pending[1].resolve(null);
		await second;
		assert.deepEqual(f.state, { status: "ready", photoMaxKb: null, consentVersion: null });
	});

	it("lần mở cũ trả về muộn không ghi đè giá trị của lần mở mới", async () => {
		const f = setupFormLimits();
		const stale = f.reload(); // lần 1, chậm
		const fresh = f.reload(); // lần 2
		f.pending[1].resolve({ consentNoticeVersion: "v2", photoMaxKb: 100 });
		await fresh;
		f.pending[0].resolve({ consentNoticeVersion: "v1", photoMaxKb: 2048 });
		assert.equal(await stale, null);
		assert.deepEqual(f.state, { status: "ready", photoMaxKb: 100, consentVersion: "v2" });
	});

	it("lần mở cũ lỗi muộn không xoá giá trị của lần mở mới", async () => {
		const f = setupFormLimits();
		const stale = f.reload();
		const fresh = f.reload();
		f.pending[1].resolve({ consentNoticeVersion: "v1", photoMaxKb: 100 });
		await fresh;
		f.pending[0].reject(new Error("timeout"));
		assert.equal(await stale, null);
		assert.deepEqual(f.state, { status: "ready", photoMaxKb: 100, consentVersion: "v1" });
	});

	it("phiên bản thông báo đồng ý cũng lấy theo lần mở mới nhất (cùng lời gọi /eligibility)", async () => {
		const f = setupFormLimits();
		const first = f.reload();
		f.pending[0].resolve({ consentNoticeVersion: "v1", photoMaxKb: 2048 });
		await first;
		const second = f.reload();
		f.pending[1].resolve({ consentNoticeVersion: "v2", photoMaxKb: 2048 });
		await second;
		assert.equal(f.state.consentVersion, "v2");
	});

	it("reducer: trạng thái đầu idle / null; action lạ giữ nguyên trạng thái", () => {
		assert.deepEqual(GUEST_FORM_LIMITS_INITIAL, { status: "idle", photoMaxKb: null, consentVersion: null });
		const ready = { status: "ready", photoMaxKb: 100, consentVersion: "v1" };
		assert.equal(guestFormLimitsReducer(ready, { type: "khác" }), ready);
		assert.equal(guestFormLimitsReducer(ready, undefined), ready);
	});
});
