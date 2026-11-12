import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
	BOUNDARY_WALL_HEIGHT,
	BUILDING_ALL_FLOORS_WALL_HEIGHT,
	FLOOR_ELEVATION_STEP,
	FLOOR_SLAB_HEIGHT,
	INTERIOR_WALL_HEIGHT,
	OVERVIEW_WALL_HEIGHT,
	buildVector3dLayers,
	collectWeldedDragExclusion,
	createDefaultGeometryStore,
	resolveBestSnapTarget,
} from "../campusIndoor3dData.js";
import { resolveToolSwitchDraftAction } from "../../utils/studioToolDraftHelper.js";

function makeLinearProjector(scalePxPerDeg = 1_500_000) {
	return ([lng, lat]) => ({
		x: lng * scalePxPerDeg,
		y: -lat * scalePxPerDeg,
	});
}

describe("Phase 5 (Mục 3A + Bug 4A/4B) - BIM-Lite 3D Rendering & Studio CAD Stability", () => {
	describe("2. Bug 4B — Welded Corner Drag Snap Exclusion", () => {
		const P0 = [106.8098, 10.8411];
		const P1 = [106.8099, 10.8411];
		const P2 = [106.8098, 10.8412];
		const P3 = [106.80983, 10.84113];
		const P4 = [106.80995, 10.84113];

		const walls = [
			{ id: "wall-A", building: "Tòa Alpha", floorOrder: 0, line: [P0, P1] },
			{ id: "wall-B", building: "Tòa Alpha", floorOrder: 0, line: [P2, P0] },
			{ id: "wall-C", building: "Tòa Alpha", floorOrder: 0, line: [P3, P4] },
		];
		const vertices = walls.flatMap((w) =>
			w.line.map((coord) => ({ coord, ownerId: w.id })),
		);
		const segments = walls.map((w) => ({
			seg: [w.line[0], w.line[1]],
			ownerId: w.id,
		}));
		const projectFn = makeLinearProjector();

		it("Happy Path: excludes all welded sibling walls at dragged vertex origin so cursor does not self-snap back within 1..10px, while still snapping to external wall-C", () => {
			const exclusion = collectWeldedDragExclusion({
				walls,
				wallId: "wall-A",
				vertexIndex: 0,
				detachSingle: false,
			});
			assert.equal(exclusion.excludeOwnerIds.has("wall-A"), true);
			assert.equal(exclusion.excludeOwnerIds.has("wall-B"), true);
			assert.equal(exclusion.excludeOwnerIds.has("wall-C"), false);

			// Drag cursor 4px East of P0 (previously snapped back onto wall-B's vertex/edge!)
			const degPerPx = 1 / 1_500_000;
			const dragged4Px = [Number((P0[0] + 4 * degPerPx).toFixed(7)), P0[1]];
			const snapNearOrigin = resolveBestSnapTarget({
				rawLngLat: dragged4Px,
				projectFn,
				vertices,
				segments,
				excludeOwnerIds: exclusion.excludeOwnerIds,
				excludeCoord: exclusion.excludeCoord,
			});
			assert.equal(
				snapNearOrigin,
				null,
				`Expected no self-snap back onto welded origin/edge at 4px, got ${JSON.stringify(snapNearOrigin)}`,
			);

			// Now drag near external wall-C vertex P3 (~3px away): MUST still magnetically snap onto P3!
			const p3Px = projectFn(P3);
			const nearExternalP3 = [
				Number((p3Px.x * degPerPx + 2 * degPerPx).toFixed(7)),
				Number((-p3Px.y * degPerPx).toFixed(7)),
			];
			const snapToExternal = resolveBestSnapTarget({
				rawLngLat: nearExternalP3,
				projectFn,
				vertices,
				segments,
				excludeOwnerIds: exclusion.excludeOwnerIds,
				excludeCoord: exclusion.excludeCoord,
			});
			assert.ok(snapToExternal, "Expected snap to unconnected wall-C");
			assert.equal(snapToExternal.type, "VERTEX");
			assert.equal(snapToExternal.ownerId, "wall-C");
		});

		it("Boundary (Alt / detachSingle): excludes only the detached wallId so vertex can snap back onto sibling wall-B to re-weld", () => {
			const exclusion = collectWeldedDragExclusion({
				walls,
				wallId: "wall-A",
				vertexIndex: 0,
				detachSingle: true,
			});
			assert.equal(exclusion.excludeOwnerIds.has("wall-A"), true);
			assert.equal(exclusion.excludeOwnerIds.has("wall-B"), false);
			assert.equal(exclusion.excludeCoord, null);

			const snapBackSibling = resolveBestSnapTarget({
				rawLngLat: [P0[0] + 0.000002, P0[1]],
				projectFn,
				vertices,
				segments,
				excludeOwnerIds: exclusion.excludeOwnerIds,
				excludeCoord: exclusion.excludeCoord,
			});
			assert.ok(snapBackSibling);
			assert.equal(snapBackSibling.ownerId, "wall-B");
		});

		it("Exception / Invalid Inputs: missing wall or invalid vertexIndex handles safely without throwing", () => {
			const missing = collectWeldedDragExclusion({
				walls,
				wallId: "does-not-exist",
				vertexIndex: 0,
			});
			assert.equal(missing.origCoord, null);
			assert.equal(missing.excludeOwnerIds.has("does-not-exist"), true);

			const outOfRange = collectWeldedDragExclusion({
				walls,
				wallId: "wall-A",
				vertexIndex: 99,
			});
			assert.equal(outOfRange.origCoord, null);
			assert.equal(outOfRange.excludeOwnerIds.has("wall-A"), true);
		});
	});

	describe("3. Bug 4A — Studio Tool Switch Auto-Commit Draft Policy", () => {
		const p1 = [106.8098, 10.8411];
		const p2 = [106.8099, 10.8411];
		const p3 = [106.8099, 10.8412];

		it("Happy Path: switching away from WALL_DRAW (>=2 pts) or ROOM_POLY (>=3 pts) auto-commits instead of discarding", () => {
			const wallToDoor = resolveToolSwitchDraftAction(
				"WALL_DRAW",
				[p1, p2],
				"DOOR",
			);
			assert.deepEqual(wallToDoor, {
				action: "COMMIT",
				shouldCommit: true,
				closeOverride: false,
				clearDraft: true,
			});

			const polyToSelect = resolveToolSwitchDraftAction(
				"ROOM_POLY",
				[p1, p2, p3],
				"SELECT",
			);
			assert.deepEqual(polyToSelect, {
				action: "COMMIT",
				shouldCommit: true,
				closeOverride: true,
				clearDraft: true,
			});
		});

		it("Boundary & Idempotency: clicking active tool again is NOOP preserving draft; under-minimum points discard cleanly", () => {
			const sameTool = resolveToolSwitchDraftAction(
				"WALL_DRAW",
				[p1, p2],
				"WALL_DRAW",
			);
			assert.equal(sameTool.action, "NOOP");
			assert.equal(sameTool.shouldCommit, false);
			assert.equal(sameTool.clearDraft, false);

			const shortWall = resolveToolSwitchDraftAction(
				"WALL_DRAW",
				[p1],
				"SELECT",
			);
			assert.equal(shortWall.action, "DISCARD");
			assert.equal(shortWall.shouldCommit, false);
			assert.equal(shortWall.clearDraft, true);

			const shortPoly = resolveToolSwitchDraftAction(
				"ROOM_POLY",
				[p1, p2],
				"SELECT",
			);
			assert.equal(shortPoly.action, "DISCARD");
			assert.equal(shortPoly.shouldCommit, false);
		});

		it("Exception / Invalid Inputs: safely handles null/undefined drafts and empty target tool", () => {
			assert.equal(
				resolveToolSwitchDraftAction("WALL_DRAW", null, "DOOR").action,
				"DISCARD",
			);
			assert.equal(
				resolveToolSwitchDraftAction("SELECT", [p1], null).action,
				"NOOP",
			);
		});
	});
});
