/**
 * Crisp @2x Canvas-drawn Portal Badge Icons ("portal-stairs", "portal-elevator")
 * for MapLibre GL WebGL `symbol` layers (supports synchronous loading with zero missing-image race conditions).
 */

function createBadgeCanvas(size = 64) {
	if (typeof document === "undefined") return null;
	const canvas = document.createElement("canvas");
	canvas.width = size;
	canvas.height = size;
	return canvas;
}

function drawRoundedRectPath(ctx, x, y, w, h, r) {
	ctx.beginPath();
	ctx.moveTo(x + r, y);
	ctx.arcTo(x + w, y, x + w, y + h, r);
	ctx.arcTo(x + w, y + h, x, y + h, r);
	ctx.arcTo(x, y + h, x, y, r);
	ctx.arcTo(x, y, x + w, y, r);
	ctx.closePath();
}

function renderPortalBadge(bgColor, drawGlyph, size = 64) {
	const canvas = createBadgeCanvas(size);
	if (!canvas) return null;
	const ctx = canvas.getContext("2d");
	if (!ctx) return null;

	ctx.clearRect(0, 0, size, size);

	// Outer subtle dark halo + crisp white frame + saturated tile fill
	ctx.shadowColor = "rgba(15, 23, 42, 0.45)";
	ctx.shadowBlur = 6;
	ctx.shadowOffsetY = 2;
	drawRoundedRectPath(ctx, 8, 8, 48, 48, 14);
	ctx.fillStyle = "#ffffff";
	ctx.fill();

	ctx.shadowColor = "transparent";
	drawRoundedRectPath(ctx, 11.5, 11.5, 41, 41, 11);
	ctx.fillStyle = bgColor;
	ctx.fill();

	drawGlyph(ctx);

	const imgData = ctx.getImageData(0, 0, size, size);
	return {
		width: size,
		height: size,
		data: new Uint8Array(imgData.data.buffer),
	};
}

function renderStairsIconData() {
	return renderPortalBadge("#9333ea", (ctx) => {
		// Stepped staircase glyph in crisp white
		ctx.strokeStyle = "#ffffff";
		ctx.lineWidth = 3.4;
		ctx.lineCap = "round";
		ctx.lineJoin = "round";
		ctx.beginPath();
		ctx.moveTo(19, 43);
		ctx.lineTo(26, 43);
		ctx.lineTo(26, 36);
		ctx.lineTo(33, 36);
		ctx.lineTo(33, 29);
		ctx.lineTo(40, 29);
		ctx.lineTo(40, 22);
		ctx.lineTo(45, 22);
		ctx.stroke();

		// Small diagonal directional step arrow top-left
		ctx.lineWidth = 2.6;
		ctx.beginPath();
		ctx.moveTo(19, 30);
		ctx.lineTo(27, 22);
		ctx.moveTo(22.5, 22);
		ctx.lineTo(27, 22);
		ctx.lineTo(27, 26.5);
		ctx.stroke();
	});
}

function renderElevatorIconData() {
	// Option C: Solid split elevator door leaves + bold Up/Down call triangles on sky-blue badge
	return renderPortalBadge("#0284c7", (ctx) => {
		ctx.fillStyle = "#ffffff";

		// Left solid split elevator door leaf panels
		drawRoundedRectPath(ctx, 17, 19, 9.2, 26, 2);
		ctx.fill();
		drawRoundedRectPath(ctx, 28, 19, 9.2, 26, 2);
		ctx.fill();

		// Right large exterior Up triangle
		ctx.beginPath();
		ctx.moveTo(45.2, 20.5);
		ctx.lineTo(49.4, 29.5);
		ctx.lineTo(41, 29.5);
		ctx.closePath();
		ctx.fill();

		// Right large exterior Down triangle
		ctx.beginPath();
		ctx.moveTo(41, 34.5);
		ctx.lineTo(49.4, 34.5);
		ctx.lineTo(45.2, 43.5);
		ctx.closePath();
		ctx.fill();
	});
}

const PORTAL_RENDERERS = {
	"portal-stairs": renderStairsIconData,
	"portal-elevator": renderElevatorIconData,
};

export function ensurePortalMapImage(map, id) {
	if (!map || typeof map.hasImage !== "function" || typeof map.addImage !== "function") {
		return;
	}
	const renderer = PORTAL_RENDERERS[id];
	if (!renderer || map.hasImage(id)) return;
	const imageData = renderer();
	if (imageData) {
		map.addImage(id, imageData, { pixelRatio: 2 });
	}
}

export function registerPortalMapImages(map) {
	if (!map) return;
	for (const id of Object.keys(PORTAL_RENDERERS)) {
		ensurePortalMapImage(map, id);
	}
}
