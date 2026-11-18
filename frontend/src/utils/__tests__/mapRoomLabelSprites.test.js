import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { AREA_LEVEL_CONFIG } from "../areaHelpers.js";
import { roomLabelAccentColor } from "../mapRoomLabelSprites.js";

describe("mapRoomLabelSprites — màu chấm cấp trên nhãn tên phòng", () => {
	it("PUBLIC / CONFIDENTIAL / HIGHLY_CONFIDENTIAL lấy đúng AREA_LEVEL_CONFIG[...].color (cùng nguồn với ghim và chú giải)", () => {
		for (const level of ["PUBLIC", "CONFIDENTIAL", "HIGHLY_CONFIDENTIAL"]) {
			assert.equal(roomLabelAccentColor(level), AREA_LEVEL_CONFIG[level].color, level);
		}
	});

	it("cấp lạ hoặc thiếu rơi về màu PUBLIC", () => {
		for (const level of ["KHONG_CO", "", undefined, null]) {
			assert.equal(roomLabelAccentColor(level), AREA_LEVEL_CONFIG.PUBLIC.color, String(level));
		}
	});
});
