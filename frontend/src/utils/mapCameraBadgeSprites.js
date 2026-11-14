/**
 * Dynamic @2x Canvas sprite generator for MapLibre WebGL symbol camera badges on 3D rooms.
 * Displays compact camera counts & live health status (online/offline/muted) cleanly
 * alongside room labels without overlapping or requiring external font glyphs.
 */

const CAMERA_BADGE_SPRITE_PREFIX = "cam-badge::";

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

export function ensureCameraBadgeSpriteImage(map, spriteId) {
	if (!map || !spriteId || !spriteId.startsWith(CAMERA_BADGE_SPRITE_PREFIX)) {
		return false;
	}
	if (map.hasImage?.(spriteId)) return true;
	const imgData = createCameraBadgeSpriteImageData(spriteId);
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
