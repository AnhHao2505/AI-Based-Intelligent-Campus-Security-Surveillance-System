import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	applyTabOrder,
	buildHeadbarStorageKey,
	readStoredTabOrder,
	swapTabKeys,
	writeStoredTabOrder,
} from "../headbarOrder.js";

describe("swapTabKeys", () => {
	it("swaps two arbitrary tabs without shifting intermediate tabs", () => {
		const initial = ["/dashboard", "/guard", "/recordings", "/notifications"];
		assert.deepEqual(
			swapTabKeys(initial, "/dashboard", "/notifications"),
			["/notifications", "/guard", "/recordings", "/dashboard"],
		);
		assert.deepEqual(
			swapTabKeys(initial, "/guard", "/recordings"),
			["/dashboard", "/recordings", "/guard", "/notifications"],
		);
	});

	it("returns unchanged copy when keys are identical or missing", () => {
		const initial = ["/a", "/b", "/c"];
		assert.deepEqual(swapTabKeys(initial, "/a", "/a"), ["/a", "/b", "/c"]);
		assert.deepEqual(swapTabKeys(initial, "/a", "/missing"), ["/a", "/b", "/c"]);
	});
});

describe("applyTabOrder", () => {
	const items = [
		{ to: "/dashboard", label: "Tổng quan" },
		{ to: "/guard", label: "Trung tâm Giám sát" },
		{ to: "/recordings", label: "Kho lưu trữ" },
		{ to: "/notifications", label: "Thông báo" },
	];

	it("applies saved order, drops unknown tabs, and appends newly available tabs", () => {
		const saved = ["/notifications", "/removed-tab", "/dashboard"];
		const result = applyTabOrder(items, saved);
		assert.deepEqual(
			result.map((item) => item.to),
			["/notifications", "/dashboard", "/guard", "/recordings"],
		);
	});

	it("falls back to default item order when savedOrder is empty", () => {
		assert.deepEqual(
			applyTabOrder(items, []).map((item) => item.to),
			["/dashboard", "/guard", "/recordings", "/notifications"],
		);
	});
});

describe("headbar storage helpers", () => {
	it("reads and writes scoped tab order cleanly", () => {
		const map = new Map();
		const fakeStorage = {
			getItem: (k) => (map.has(k) ? map.get(k) : null),
			setItem: (k, v) => map.set(k, v),
		};

		const key = buildHeadbarStorageKey("user-1", "GUARD");
		assert.equal(key, "headbar_tab_order:user-1");

		writeStoredTabOrder(key, ["/recordings", "/guard"], fakeStorage);
		assert.deepEqual(readStoredTabOrder(key, fakeStorage), ["/recordings", "/guard"]);

		fakeStorage.setItem(key, "{invalid-json");
		assert.deepEqual(readStoredTabOrder(key, fakeStorage), []);
	});
});
