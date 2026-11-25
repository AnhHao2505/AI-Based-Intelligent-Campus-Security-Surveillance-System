import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { mapCreateAccountError } from "../createAccountErrors.js";

// Error như apiClient ném: .status, .code (= body.code), .message, .data (= cả body lỗi)
const apiError = (status, code, message, data) => {
	const err = new Error(message);
	err.status = status;
	err.code = code;
	err.data = { httpCode: status, errorCode: code, code, message, ...(data ? { data } : {}) };
	return err;
};

const FALLBACK = "Không thể tạo tài khoản người dùng";

describe("mapCreateAccountError (B1: gán lỗi form tạo tài khoản theo mã, không so chữ)", () => {
	it("ERR_AC_014 -> ô cấp truy cập ban đầu", () => {
		const err = apiError(400, "ERR_AC_014", "Cấp truy cập ban đầu của người dùng thường chỉ được là 1 hoặc 2");
		assert.deepEqual(mapCreateAccountError(err, FALLBACK), { accessLevel: err.message });
	});

	it("mọi ERR_FACE_xxx -> ô ảnh, kể cả câu chứa chữ \"mã\" và lỗi 503 (ERR_FACE_008)", () => {
		const cases = [
			[409, "ERR_FACE_001", "Khuôn mặt này đã được đăng ký cho tài khoản SV-001"],
			[400, "ERR_FACE_003", "Kích thước ảnh vượt quá giới hạn tối đa 350KB (dung lượng file: 400.0 KB)"],
			[422, "ERR_FACE_007", "AI Service không thể trích xuất vector khuôn mặt cho mã: SV-002"],
			[503, "ERR_FACE_008", "Dịch vụ AI hiện không khả dụng. Vui lòng thử lại sau."],
		];
		for (const [status, code, message] of cases) {
			assert.deepEqual(mapCreateAccountError(apiError(status, code, message), FALLBACK), { faceImage: message }, code);
		}
	});

	it("409 trùng mã / email -> đúng ô theo data, trùng cả hai -> cả hai ô", () => {
		const codeOnly = apiError(409, "Conflict", "Mã người dùng đã tồn tại trong hệ thống", {
			userCode: "Mã người dùng đã tồn tại trong hệ thống",
		});
		assert.deepEqual(mapCreateAccountError(codeOnly, FALLBACK), { userCode: "Mã người dùng đã tồn tại trong hệ thống" });

		const emailOnly = apiError(409, "Conflict", "Email đã tồn tại trong hệ thống", {
			email: "Email đã tồn tại trong hệ thống",
		});
		assert.deepEqual(mapCreateAccountError(emailOnly, FALLBACK), { email: "Email đã tồn tại trong hệ thống" });

		const both = apiError(409, "Conflict", "Mã người dùng đã tồn tại trong hệ thống. Email đã tồn tại trong hệ thống", {
			userCode: "Mã người dùng đã tồn tại trong hệ thống",
			email: "Email đã tồn tại trong hệ thống",
		});
		assert.deepEqual(mapCreateAccountError(both, FALLBACK), {
			userCode: "Mã người dùng đã tồn tại trong hệ thống",
			email: "Email đã tồn tại trong hệ thống",
		});
	});

	it("400 VALIDATION_ERROR -> ô theo data, bỏ trường không có chỗ hiện lỗi (role, trường lạ)", () => {
		const err = apiError(400, "VALIDATION_ERROR", "Dữ liệu đầu vào không hợp lệ", {
			email: "Email không đúng định dạng",
			faceImage: "Ảnh khuôn mặt không được để trống",
			role: "Vai trò không được để trống",
			unknownField: "x",
		});
		assert.deepEqual(mapCreateAccountError(err, FALLBACK), {
			email: "Email không đúng định dạng",
			faceImage: "Ảnh khuôn mặt không được để trống",
		});
	});

	it("500 \"Lỗi hệ thống. Mã tra cứu\" -> vùng lỗi chung, không vào ô mã người dùng", () => {
		const err = apiError(500, "Internal Server Error", "Lỗi hệ thống. Mã tra cứu: 1a2b3c4d");
		assert.deepEqual(mapCreateAccountError(err, FALLBACK), { general: err.message });
	});

	it("lỗi không nhận ra -> vùng lỗi chung: 409 không có data, mã lạ, câu có chữ \"email\" / \"mã\", lỗi mạng", () => {
		const cases = [
			apiError(409, "Conflict", "Dữ liệu bị trùng lặp hoặc vi phạm ràng buộc toàn vẹn"),
			apiError(400, "Bad Request", "Vai trò không hợp lệ. Chỉ chấp nhận: FACILITY_MANAGER, GUARD, NORMAL_USER"),
			apiError(409, "ERR_SOMETHING_NEW", "Email và mã đều có vấn đề"),
			new Error("Không thể kết nối đến máy chủ."),
		];
		for (const err of cases) {
			assert.deepEqual(mapCreateAccountError(err, FALLBACK), { general: err.message }, err.message);
		}
	});

	it("VALIDATION_ERROR chỉ có trường không hiện được -> vùng lỗi chung", () => {
		const err = apiError(400, "VALIDATION_ERROR", "Dữ liệu đầu vào không hợp lệ", { role: "Vai trò không được để trống" });
		assert.deepEqual(mapCreateAccountError(err, FALLBACK), { general: err.message });
	});

	it("không có message -> câu dự phòng ở vùng lỗi chung", () => {
		assert.deepEqual(mapCreateAccountError(undefined, FALLBACK), { general: FALLBACK });
		assert.deepEqual(mapCreateAccountError(apiError(500, undefined, ""), FALLBACK), { general: FALLBACK });
	});
});
