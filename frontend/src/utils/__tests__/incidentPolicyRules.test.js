import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { escalationAfterStep1Error } from "../incidentPolicyRules.js";

// Ràng buộc chéo chính sách phân phối sự cố (ERR_INCPOL_009): phút báo Quản lý CSVC × 60 > thời gian chờ đợt 1 khi có đợt 1
describe("escalationAfterStep1Error", () => {
	it("vi phạm (đợt 1 120 giây, báo sau 1 phút, mời 2) → câu lỗi nêu cả hai giá trị, trùng backend", () => {
		assert.equal(
			escalationAfterStep1Error({ step1Size: 2, step1TimeoutSeconds: 120, escalateFmMinutes: 1 }),
			"Thời gian chờ trước khi báo Quản lý CSVC (1 phút = 60 giây) phải lớn hơn thời gian chờ đợt 1 (120 giây)",
		);
	});

	it("biên bằng nhau (1 phút = 60 giây) → vẫn lỗi", () => {
		assert.ok(escalationAfterStep1Error({ step1Size: 1, step1TimeoutSeconds: 60, escalateFmMinutes: 1 }));
	});

	it("hợp lệ (31 giây / 1 phút) và giá trị dạng chuỗi từ ô nhập → null", () => {
		assert.equal(escalationAfterStep1Error({ step1Size: 1, step1TimeoutSeconds: 31, escalateFmMinutes: 1 }), null);
		assert.equal(escalationAfterStep1Error({ step1Size: "2", step1TimeoutSeconds: "30", escalateFmMinutes: "5" }), null);
	});

	it("số bảo vệ mời = 0 → không kiểm (600 giây / 1 phút vẫn null)", () => {
		assert.equal(escalationAfterStep1Error({ step1Size: 0, step1TimeoutSeconds: 600, escalateFmMinutes: 1 }), null);
		assert.equal(escalationAfterStep1Error({ step1Size: "0", step1TimeoutSeconds: "600", escalateFmMinutes: "1" }), null);
	});

	it("ô để trống / không phải số → null (để backend báo lỗi phạm vi)", () => {
		assert.equal(escalationAfterStep1Error({ step1Size: 2, step1TimeoutSeconds: "abc", escalateFmMinutes: 1 }), null);
	});
});
