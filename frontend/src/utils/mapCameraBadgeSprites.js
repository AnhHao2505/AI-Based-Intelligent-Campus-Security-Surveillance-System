/**
 * Dynamic @2x Canvas sprite generator for MapLibre WebGL symbol camera badges on 3D rooms.
 * Displays compact camera counts & live health status (online/offline/muted) cleanly
 * alongside room labels without overlapping or requiring external font glyphs.
 */

const CAMERA_BADGE_SPRITE_PREFIX = "cam-badge::";
const CAMERA_PIN_SPRITE_PREFIX = "cam-pin::";
const FOV_HANDLE_SPRITE_PREFIX = "fov-handle::";

export function parseCameraPinSpriteId(spriteId) {
	if (
		typeof spriteId !== "string" ||
		!spriteId.startsWith(CAMERA_PIN_SPRITE_PREFIX)
	) {
		return null;
	}
	const parts = spriteId.slice(CAMERA_PIN_SPRITE_PREFIX.length).split(":");
	return {
		statusKey: parts[0] || "muted",
		isSelected: parts[1] === "1",
	};
}

export function parseCameraBadgeSpriteId(spriteId) {
	if (
		typeof spriteId !== "string" ||
		!spriteId.startsWith(CAMERA_BADGE_SPRITE_PREFIX)
	) {
		return null;
	}
	const parts = spriteId.slice(CAMERA_BADGE_SPRITE_PREFIX.length).split(":");
	if (parts.length < 3) return null;
	return {
		statusKey: parts[0] || "muted",
		isSelected: parts[1] === "1",
		count: Number.parseInt(parts[2], 10) || 0,
	};
}

function drawRoundedRect(ctx, x, y, w, h, r) {
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

export function createCameraBadgeSpriteImageData(spriteId) {
	if (typeof document === "undefined") return null;
	const parsed = parseCameraBadgeSpriteId(spriteId);
	if (!parsed || parsed.count <= 0) return null;

	const SCALE = 2;
	const countText = String(parsed.count);
	const measureCanvas = document.createElement("canvas");
	const measureCtx = measureCanvas.getContext("2d");
	if (!measureCtx) return null;

	measureCtx.font =
		"700 10.5px 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif";
	const textWidth = Math.ceil(measureCtx.measureText(countText).width);

	const padX = 7;
	const iconW = 11;
	const gap = 4;
	const shadowPad = 3;
	const pillH = 20;
	const pillW = Math.max(32, padX + iconW + gap + textWidth + padX);
	const logicalW = pillW + shadowPad * 2;
	const logicalH = pillH + shadowPad * 2;

	const canvas = document.createElement("canvas");
	canvas.width = logicalW * SCALE;
	canvas.height = logicalH * SCALE;
	const ctx = canvas.getContext("2d");
	if (!ctx) return null;

	ctx.scale(SCALE, SCALE);
	ctx.clearRect(0, 0, logicalW, logicalH);

	const palette =
		parsed.statusKey === "warn"
			? {
					bg: parsed.isSelected ? "#991b1b" : "#fef2f2",
					border: "#dc2626",
					fg: parsed.isSelected ? "#ffffff" : "#b91c1c",
				}
			: parsed.statusKey === "ok"
				? {
						bg: parsed.isSelected ? "#065f46" : "#ecfdf5",
						border: "#10b981",
						fg: parsed.isSelected ? "#ffffff" : "#047857",
					}
				: {
						bg: parsed.isSelected ? "#334155" : "#f8fafc",
						border: "#64748b",
						fg: parsed.isSelected ? "#ffffff" : "#475569",
					};

	ctx.save();
	ctx.shadowColor = "rgba(15, 23, 42, 0.22)";
	ctx.shadowBlur = 4;
	ctx.shadowOffsetY = 1.5;
	drawRoundedRect(ctx, shadowPad, shadowPad, pillW, pillH, pillH / 2);
	ctx.fillStyle = palette.bg;
	ctx.fill();
	ctx.restore();

	drawRoundedRect(
		ctx,
		shadowPad + 0.5,
		shadowPad + 0.5,
		pillW - 1,
		pillH - 1,
		(pillH - 1) / 2,
	);
	ctx.lineWidth = parsed.isSelected ? 1.6 : 1.1;
	ctx.strokeStyle = palette.border;
	ctx.stroke();

	// Minimal crisp CCTV camera glyph (body + lens cone)
	const ix = shadowPad + padX;
	const iy = shadowPad + pillH / 2;
	ctx.fillStyle = palette.fg;
	drawRoundedRect(ctx, ix, iy - 3.5, 7, 7, 1.5);
	ctx.fill();
	ctx.beginPath();
	ctx.moveTo(ix + 7.5, iy - 1.5);
	ctx.lineTo(ix + 10.5, iy - 3.2);
	ctx.lineTo(ix + 10.5, iy + 3.2);
	ctx.lineTo(ix + 7.5, iy + 1.5);
	ctx.closePath();
	ctx.fill();

	// Camera count
	ctx.font =
		"700 10.5px 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif";
	ctx.textBaseline = "middle";
	ctx.fillStyle = palette.fg;
	ctx.fillText(countText, ix + iconW + gap, iy + 0.5);

	return ctx.getImageData(0, 0, logicalW * SCALE, logicalH * SCALE);
}

export function createCameraPinSpriteImageData(spriteId) {
	if (typeof document === "undefined") return null;
	const parsed = parseCameraPinSpriteId(spriteId);
	if (!parsed) return null;

	const SCALE = 2;
	const boxSize = 26;
	const shadowPad = 4;
	const logicalSize = boxSize + shadowPad * 2;

	const canvas = document.createElement("canvas");
	canvas.width = logicalSize * SCALE;
	canvas.height = logicalSize * SCALE;
	const ctx = canvas.getContext("2d");
	if (!ctx) return null;

	ctx.scale(SCALE, SCALE);
	ctx.clearRect(0, 0, logicalSize, logicalSize);

	const bg = parsed.isSelected ? "#034ea2" : "#ffffff";
	const border = parsed.isSelected ? "#f37021" : "#034ea2";
	const fg = parsed.isSelected ? "#ffffff" : "#034ea2";
	const dotColor =
		parsed.statusKey === "ok"
			? "#10b981"
			: parsed.statusKey === "warn"
				? "#ef4444"
				: "#94a3b8";

	// Card shadow & background
	ctx.save();
	ctx.shadowColor = "rgba(15, 23, 42, 0.28)";
	ctx.shadowBlur = 5;
	ctx.shadowOffsetY = 1.5;
	drawRoundedRect(ctx, shadowPad, shadowPad, boxSize, boxSize, 6);
	ctx.fillStyle = bg;
	ctx.fill();
	ctx.restore();

	// Crisp border
	drawRoundedRect(
		ctx,
		shadowPad + 0.75,
		shadowPad + 0.75,
		boxSize - 1.5,
		boxSize - 1.5,
		5.5,
	);
	ctx.lineWidth = parsed.isSelected ? 2 : 1.5;
	ctx.strokeStyle = border;
	ctx.stroke();

	// CCTV camera glyph centered (inspired by Ảnh 1)
	const cx = shadowPad + boxSize / 2;
	const cy = shadowPad + boxSize / 2;
	ctx.fillStyle = fg;
	drawRoundedRect(ctx, cx - 7, cy - 4.5, 9.5, 8, 2);
	ctx.fill();
	ctx.beginPath();
	ctx.moveTo(cx + 3, cy - 2);
	ctx.lineTo(cx + 7.5, cy - 4.5);
	ctx.lineTo(cx + 7.5, cy + 3.5);
	ctx.lineTo(cx + 3, cy + 1);
	ctx.closePath();
	ctx.fill();

	// Small mount stem
	ctx.strokeStyle = fg;
	ctx.lineWidth = 1.5;
	ctx.beginPath();
	ctx.moveTo(cx - 2.5, cy + 3.5);
	ctx.lineTo(cx - 2.5, cy + 6.5);
	ctx.lineTo(cx + 1.5, cy + 6.5);
	ctx.stroke();

	// Status indicator dot on top-right corner
	const dotX = shadowPad + boxSize - 3;
	const dotY = shadowPad + 3;
	ctx.beginPath();
	ctx.arc(dotX, dotY, 4.2, 0, Math.PI * 2);
	ctx.fillStyle = "#ffffff";
	ctx.fill();
	ctx.beginPath();
	ctx.arc(dotX, dotY, 3, 0, Math.PI * 2);
	ctx.fillStyle = dotColor;
	ctx.fill();

	return ctx.getImageData(0, 0, logicalSize * SCALE, logicalSize * SCALE);
}

export function createFovHandleSpriteImageData() {
	if (typeof document === "undefined") return null;
	const SCALE = 2;
	const logicalSize = 22;
	const canvas = document.createElement("canvas");
	canvas.width = logicalSize * SCALE;
	canvas.height = logicalSize * SCALE;
	const ctx = canvas.getContext("2d");
	if (!ctx) return null;

	ctx.scale(SCALE, SCALE);
	ctx.clearRect(0, 0, logicalSize, logicalSize);

	const c = logicalSize / 2;
	ctx.save();
	ctx.shadowColor = "rgba(15, 23, 42, 0.28)";
	ctx.shadowBlur = 4;
	ctx.shadowOffsetY = 1;
	ctx.beginPath();
	ctx.arc(c, c, 7.5, 0, Math.PI * 2);
	ctx.fillStyle = "#ffffff";
	ctx.fill();
	ctx.restore();

	ctx.beginPath();
	ctx.arc(c, c, 5.5, 0, Math.PI * 2);
	ctx.fillStyle = "#10b981";
	ctx.fill();
	ctx.lineWidth = 1.5;
	ctx.strokeStyle = "#047857";
	ctx.stroke();

	return ctx.getImageData(0, 0, logicalSize * SCALE, logicalSize * SCALE);
}

export function ensureCameraBadgeSpriteImage(map, spriteId) {
	if (!map || !spriteId) return false;
	const isPin = spriteId.startsWith(CAMERA_PIN_SPRITE_PREFIX);
	const isBadge = spriteId.startsWith(CAMERA_BADGE_SPRITE_PREFIX);
	const isHandle = spriteId.startsWith(FOV_HANDLE_SPRITE_PREFIX);
	if (!isPin && !isBadge && !isHandle) return false;

	if (map.hasImage?.(spriteId)) return true;
	const imgData = isHandle
		? createFovHandleSpriteImageData()
		: isPin
			? createCameraPinSpriteImageData(spriteId)
			: createCameraBadgeSpriteImageData(spriteId);
	if (!imgData) return false;
	try {
		map.addImage(spriteId, imgData, { pixelRatio: 2 });
		return true;
	} catch {
		return false;
	}
}

export function registerCameraBadgeSprites(map, geojson) {
	if (!map || !geojson?.features) return;
	for (const feat of geojson.features) {
		const spriteId = feat?.properties?.spriteId;
		if (spriteId) {
			ensureCameraBadgeSpriteImage(map, spriteId);
		}
	}
}
