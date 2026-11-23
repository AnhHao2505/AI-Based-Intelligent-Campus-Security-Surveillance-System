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
