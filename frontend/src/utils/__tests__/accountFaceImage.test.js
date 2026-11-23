import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	USER_FACE_IMAGE_MAX_KB_KEY,
	readFaceImageMaxKb,
	validateFaceImageSize,
	faceImageLimitText,
} from "../accountFaceImage.js";

// V140: dòng cấu hình như GET /api/system-configurations trả về (min 100, max 1024)
const row = (configValue, extra = {}) => ({
	configKey: USER_FACE_IMAGE_MAX_KB_KEY,
	configValue,
	minValue: 100,
	maxValue: 1024,
	...extra,
});

describe("readFaceImageMaxKb (V140: đọc USER_FACE_IMAGE_MAX_KB)", () => {
	it("đọc đúng giá trị cấu hình, kể cả khi danh sách có khoá khác", () => {
		assert.equal(readFaceImageMaxKb([{ configKey: "USER_BULK_IMPORT_MAX_ROWS", configValue: "200" }, row("350")]), 350);
		assert.equal(readFaceImageMaxKb([row("500")]), 500);
		assert.equal(readFaceImageMaxKb([row(" 100 ")]), 100);
		assert.equal(readFaceImageMaxKb([row("1024")]), 1024);
	});

	it("không đọc được -> null (không đoán số): thiếu khoá, danh sách rỗng / null, giá trị không phải số nguyên dương", () => {
		assert.equal(readFaceImageMaxKb(null), null);
		assert.equal(readFaceImageMaxKb(undefined), null);
		assert.equal(readFaceImageMaxKb([]), null);
		assert.equal(readFaceImageMaxKb([{ configKey: "USER_BULK_IMPORT_MAX_ROWS", configValue: "200" }]), null);
		for (const bad of ["", "abc", "350.5", "-200", "0", null, undefined]) {
			assert.equal(readFaceImageMaxKb([row(bad, { minValue: null, maxValue: null })]), null, String(bad));
		}
	});

	it("ngoài min_value / max_value của dòng cấu hình (sửa thẳng trong DB) -> null, để backend dùng mặc định riêng", () => {
		assert.equal(readFaceImageMaxKb([row("99")]), null);
		assert.equal(readFaceImageMaxKb([row("1025")]), null);
		assert.equal(readFaceImageMaxKb([row("50", { minValue: null, maxValue: null })]), 50);
	});
});

describe("validateFaceImageSize (V140: chặn sớm theo giá trị cấu hình)", () => {
	it("không vượt giới hạn -> null; đúng bằng maxKb * 1024 byte vẫn hợp lệ (cùng mốc với backend)", () => {
		assert.equal(validateFaceImageSize({ size: 10 }, 350), null);
		assert.equal(validateFaceImageSize({ size: 350 * 1024 }, 350), null);
		assert.equal(validateFaceImageSize({ size: 500 * 1024 }, 500), null);
	});

	it("vượt giới hạn -> câu báo nêu đúng giá trị cấu hình và dung lượng ảnh, không còn số 350 viết cứng", () => {
		const msg = validateFaceImageSize({ size: 500 * 1024 + 1 }, 500);
		assert.match(msg, /vượt quá giới hạn tối đa 500 KB/);
		assert.match(msg, /\(500\.0 KB\)/);

		const small = validateFaceImageSize({ size: 150 * 1024 }, 100);
		assert.match(small, /giới hạn tối đa 100 KB/);
		assert.doesNotMatch(small, /350/);
	});

	it("chưa đọc được cấu hình (null / không hợp lệ) -> không chặn ở trình duyệt, kể cả ảnh rất lớn", () => {
		for (const maxKb of [null, undefined, NaN, 0, -1]) {
			assert.equal(validateFaceImageSize({ size: 50 * 1024 * 1024 }, maxKb), null, String(maxKb));
		}
	});

	it("chưa có file -> null (thiếu ảnh do form báo riêng)", () => {
		assert.equal(validateFaceImageSize(null, 350), null);
		assert.equal(validateFaceImageSize(undefined, 350), null);
	});
});

describe("faceImageLimitText (hướng dẫn nạp theo lô / ô chọn ảnh)", () => {
	it("có cấu hình -> nêu số; chưa đọc được -> nói theo cấu hình hệ thống, không đoán số", () => {
		assert.equal(faceImageLimitText(350), "tối đa 350 KB/ảnh");
		assert.equal(faceImageLimitText(1024), "tối đa 1024 KB/ảnh");
		assert.equal(faceImageLimitText(null), "dung lượng tối đa theo cấu hình hệ thống");
		assert.doesNotMatch(faceImageLimitText(null), /\d/);
	});
});
