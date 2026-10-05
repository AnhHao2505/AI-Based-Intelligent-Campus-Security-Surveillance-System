/**
 * Định dạng vị trí toà nhà / tầng dùng chung — tránh lặp tiền tố ("Tầng Tầng Trệt", "Tòa Tòa Beta").
 *
 * Tên toà nhà / tầng trong dữ liệu thường đã có tiền tố ("Tòa Alpha", "Tầng Trệt", "Tầng 1"),
 * nhưng vài nơi chỉ có mã ("G", "1", "Alpha"). Chỉ thêm tiền tố khi giá trị chưa có
 * (so sánh không phân biệt hoa thường, không phân biệt dấu: "Tòa" / "Toà" / "Toa").
 *
 * Các ca thử (frontend chưa có test runner — kiểm bằng node khi sửa file này):
 *   formatBuilding("Tòa Alpha")        -> "Tòa Alpha"
 *   formatBuilding("Toà Beta")         -> "Toà Beta"
 *   formatBuilding("toa gamma")        -> "toa gamma"
 *   formatBuilding("Beta")             -> "Tòa Beta"
 *   formatBuilding("Toan")             -> "Tòa Toan"        (không nhầm "Toan" là có tiền tố)
 *   formatBuilding(null) / ("  ")      -> ""
 *   formatFloor("Tầng Trệt")           -> "Tầng Trệt"
 *   formatFloor("tầng 1")              -> "tầng 1"
 *   formatFloor("1")                   -> "Tầng 1"
 *   formatFloor("G") / ("g") / ("Trệt") -> "Tầng Trệt"
 *   formatFloor(2)                     -> "Tầng 2"
 *   formatFloor(undefined)             -> ""
 *   formatLocation("Tòa Alpha", "Tầng Trệt")      -> "Tòa Alpha · Tầng Trệt"
 *   formatLocation("Alpha", "G", " - ")            -> "Tòa Alpha - Tầng Trệt"
 *   formatLocation(null, "Tầng 1")                 -> "Tầng 1"
 *   formatLocation("Tòa Beta", null)               -> "Tòa Beta"
 *   formatLocation(null, null)                     -> ""
 */

function toPlain(value) {
	return value
		.normalize("NFD")
		.replace(/[̀-ͯ]/g, "")
		.replace(/đ/g, "d")
		.replace(/Đ/g, "D")
		.toLowerCase();
}

function hasPrefix(value, prefixPlain) {
	const plain = toPlain(value);
	return plain === prefixPlain || plain.startsWith(`${prefixPlain} `);
}

function clean(value) {
	return value === null || value === undefined ? "" : String(value).trim();
}

/** Tên toà nhà có tiền tố "Tòa" (không thêm nếu đã có). Rỗng/null -> "". */
export function formatBuilding(building) {
	const value = clean(building);
	if (!value) return "";
	return hasPrefix(value, "toa") ? value : `Tòa ${value}`;
}

/** Tên tầng có tiền tố "Tầng"; "G" / "Trệt" -> "Tầng Trệt". Rỗng/null -> "". */
export function formatFloor(floor) {
	const value = clean(floor);
	if (!value) return "";
	const plain = toPlain(value);
	if (plain === "g" || plain === "tret") return "Tầng Trệt";
	return hasPrefix(value, "tang") ? value : `Tầng ${value}`;
}

/** "Tòa X · Tầng Y" — bỏ phần rỗng; trả "" nếu cả hai rỗng. */
export function formatLocation(building, floor, separator = " · ") {
	return [formatBuilding(building), formatFloor(floor)].filter(Boolean).join(separator);
}
