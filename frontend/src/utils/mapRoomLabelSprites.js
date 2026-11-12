/**
 * Dynamic @2x Canvas sprite generator for MapLibre WebGL symbol room labels (Mục 1B).
 * Renders crisp Vietnamese pill badges without requiring external PBF glyph servers,
 * while preserving symbol-height-anchor="ground" + symbol-height-offset on 3D roofs.
 */

const ROOM_LABEL_SPRITE_PREFIX = "room-label::";
const DEFAULT_MAX_LABEL_LENGTH = 24;

const LEVEL_ACCENT_COLORS = {
	PUBLIC: "#16a34a",
	CONFIDENTIAL: "#d97706",
	HIGHLY_CONFIDENTIAL: "#dc2626",
};

/**
 * Truncates long room names with a single trailing ellipsis so 3D labels remain compact.
 */
export function truncateLabelText(text, maxLength = DEFAULT_MAX_LABEL_LENGTH) {
	const raw = String(text ?? "").trim();
	if (!raw) return "";
	if (!Number.isFinite(maxLength) || maxLength <= 0) return "";
	if (raw.length <= maxLength) return raw;
	if (maxLength === 1) return "…";
	return `${raw.slice(0, maxLength - 1)}…`;
}

/**
 * Encodes label text, selection state, and AreaLevel into a deterministic MapLibre image id.
 */
export function buildRoomLabelSpriteId({
	name,
	isSelected = false,
	levelCode = "PUBLIC",
} = {}) {
	const cleanName = String(name ?? "").trim();
	const selFlag = isSelected ? "1" : "0";
	const normalizedLevel =
		String(levelCode || "PUBLIC")
			.trim()
			.toUpperCase() || "PUBLIC";
	return `${ROOM_LABEL_SPRITE_PREFIX}${selFlag}:${normalizedLevel}:${encodeURIComponent(cleanName)}`;
}

/**
 * Parses a sprite id created by buildRoomLabelSpriteId, or returns null for non-label images.
 */
export function parseRoomLabelSpriteId(spriteId) {
	if (
		typeof spriteId !== "string" ||
		!spriteId.startsWith(ROOM_LABEL_SPRITE_PREFIX)
	) {
		return null;
	}
	const payload = spriteId.slice(ROOM_LABEL_SPRITE_PREFIX.length);
	const firstColon = payload.indexOf(":");
	if (firstColon === -1) return null;
	const secondColon = payload.indexOf(":", firstColon + 1);
	if (secondColon === -1) return null;

	const selFlag = payload.slice(0, firstColon);
	const levelCode = payload.slice(firstColon + 1, secondColon) || "PUBLIC";
	const encodedName = payload.slice(secondColon + 1);
	try {
		return {
			name: decodeURIComponent(encodedName),
			isSelected: selFlag === "1",
			levelCode,
		};
	} catch {
		return {
			name: encodedName,
			isSelected: selFlag === "1",
			levelCode,
		};
	}
}

function drawRoundedPill(ctx, x, y, w, h, r) {
	const radius = Math.min(r, w / 2, h / 2);
	ctx.beginPath();
	ctx.moveTo(x + radius, y);
	ctx.lineTo(x + w - radius, y);
	ctx.quadraticCurveTo(x + w, y, x + w, y + radius);
	ctx.lineTo(x + w, y + h - radius);
	ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h);
	ctx.lineTo(x + radius, y + h);
	ctx.quadraticCurveTo(x, y + h, x, y + h - radius);
	ctx.lineTo(x, y + radius);
	ctx.quadraticCurveTo(x, y, x + radius, y);
	ctx.closePath();
}

/**
 * Renders a @2x RGBA ImageData buffer for MapLibre map.addImage(id, data, { pixelRatio: 2 }).
 */
export function createRoomLabelSpriteImageData(spriteId) {
	if (typeof document === "undefined") return null;
	const parsed = parseRoomLabelSpriteId(spriteId);
	if (!parsed || !parsed.name) return null;

	const SCALE = 2;
	const label = truncateLabelText(parsed.name, DEFAULT_MAX_LABEL_LENGTH);
	if (!label) return null;

	const measureCanvas = document.createElement("canvas");
	const measureCtx = measureCanvas.getContext("2d");
	if (!measureCtx) return null;

	const fontSpec =
		"600 11px 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif";
	measureCtx.font = fontSpec;
	const textWidth = Math.ceil(measureCtx.measureText(label).width);

	const dotRadius = 3;
	const dotGap = 6;
	const padX = 9;
	const shadowPad = 3;
	const pillHeight = 22;
	const pillWidth = Math.max(
		36,
		padX + dotRadius * 2 + dotGap + textWidth + padX,
	);

	const logicalWidth = pillWidth + shadowPad * 2;
	const logicalHeight = pillHeight + shadowPad * 2;

	const canvas = document.createElement("canvas");
	canvas.width = logicalWidth * SCALE;
	canvas.height = logicalHeight * SCALE;
	const ctx = canvas.getContext("2d");
	if (!ctx) return null;

	ctx.scale(SCALE, SCALE);
	ctx.clearRect(0, 0, logicalWidth, logicalHeight);

	const accent =
		LEVEL_ACCENT_COLORS[parsed.levelCode] || LEVEL_ACCENT_COLORS.PUBLIC;

	// Soft shadow behind pill for legibility over pale/colored 3D extrusions
	ctx.save();
	ctx.shadowColor = parsed.isSelected
		? "rgba(37, 99, 235, 0.35)"
		: "rgba(15, 23, 42, 0.18)";
	ctx.shadowBlur = 4;
	ctx.shadowOffsetY = 1.5;
	drawRoundedPill(ctx, shadowPad, shadowPad, pillWidth, pillHeight, pillHeight / 2);
	ctx.fillStyle = parsed.isSelected ? "#0f172a" : "rgba(255, 255, 255, 0.96)";
	ctx.fill();
	ctx.restore();

	// Crisp border
	drawRoundedPill(
		ctx,
		shadowPad + 0.5,
		shadowPad + 0.5,
		pillWidth - 1,
		pillHeight - 1,
		(pillHeight - 1) / 2,
	);
	ctx.lineWidth = parsed.isSelected ? 1.5 : 1;
	ctx.strokeStyle = parsed.isSelected
		? "#38bdf8"
		: "rgba(15, 23, 42, 0.16)";
	ctx.stroke();

	// Level accent dot
	const dotCx = shadowPad + padX + dotRadius;
	const centerY = shadowPad + pillHeight / 2;
	ctx.beginPath();
	ctx.arc(dotCx, centerY, dotRadius, 0, Math.PI * 2);
	ctx.fillStyle = accent;
	ctx.fill();

	// Room title
	ctx.font = fontSpec;
	ctx.fillStyle = parsed.isSelected ? "#ffffff" : "#0f172a";
	ctx.textBaseline = "middle";
	ctx.textAlign = "left";
	ctx.fillText(label, dotCx + dotRadius + dotGap, centerY + 0.5);

	const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
	return {
		width: canvas.width,
		height: canvas.height,
		data: new Uint8Array(img.data.buffer),
	};
}

/**
 * Registers a single room label sprite into MapLibre if not yet present.
 */
export function ensureRoomLabelSpriteImage(map, spriteId) {
	if (
		!map ||
		typeof map.hasImage !== "function" ||
		typeof map.addImage !== "function"
	) {
		return false;
	}
	if (!parseRoomLabelSpriteId(spriteId)) return false;
	if (map.hasImage(spriteId)) return true;

	const imageData = createRoomLabelSpriteImageData(spriteId);
	if (!imageData) return false;
	try {
		if (!map.hasImage(spriteId)) {
			map.addImage(spriteId, imageData, { pixelRatio: 2 });
		}
		return true;
	} catch {
		return map.hasImage(spriteId);
	}
}

/**
 * Registers sprites for all features in roomLabelsGeoJSON before symbol rendering.
 */
export function registerRoomLabelSprites(map, roomLabelsGeoJSON) {
	const features = roomLabelsGeoJSON?.features;
	if (!Array.isArray(features) || features.length === 0) return;
	for (const f of features) {
		const spriteId = f?.properties?.spriteId;
		if (spriteId) {
			ensureRoomLabelSpriteImage(map, spriteId);
		}
	}
}
