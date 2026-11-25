import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
	SOC_ACK_STORAGE_KEY,
	SOC_CLEARED_STORAGE_KEY,
	clearSocStoredSets,
	filterOutClearedIncidents,
	getScopedSocStorageKey,
	readSocClearedBefore,
	readSocStoredSet,
	summarizeGuardRoster,
	summarizeIncidents,
	summarizeRecordings,
	writeSocClearedBefore,
	writeSocStoredSet,
} from "../dashboardHelpers.js";

describe("summarizeIncidents", () => {
	it("computes counts, sorts event types, and slices recent items descending by detectedAt", () => {
		const input = [
			{ id: "1", eventType: "UNKNOWN_PERSON", severity: "HIGH", status: "RESOLVED_VERIFIED", detectedAt: "2026-11-22T08:00:00Z" },
			{ id: "2", eventType: "SUSPICIOUS_MOVING_OBJECT", severity: "CRITICAL", status: "NEW", detectedAt: "2026-11-24T12:00:00Z" },
			{ id: "3", eventType: "SUSPICIOUS_MOVING_OBJECT", severity: "CRITICAL", status: "CLAIMED", detectedAt: "2026-11-23T10:00:00Z" },
		];

		const res = summarizeIncidents(input, 2);
		assert.equal(res.total, 3);
		assert.equal(res.criticalCount, 2);
		assert.equal(res.pendingCount, 2);
		assert.deepEqual(res.recent.map((r) => r.id), ["2", "3"]);
		assert.deepEqual(
			res.byEventType.map((e) => [e.code, e.count]),
			[["SUSPICIOUS_MOVING_OBJECT", 2], ["UNKNOWN_PERSON", 1]],
		);
	});
});

describe("summarizeRecordings", () => {
	it("splits security vs periodic recordings and orders newest first", () => {
		const input = [
			{ id: "r1", eventType: "NORMAL_RECORDING", startTime: "2026-11-20T01:00:00Z" },
			{ id: "r2", eventType: "UNKNOWN_PERSON", startTime: "2026-11-25T05:00:00Z" },
			{ id: "r3", eventType: "PERSON_INTRUSION", startTime: "2026-11-24T05:00:00Z" },
		];
		const res = summarizeRecordings(input, 2);
		assert.equal(res.total, 3);
		assert.equal(res.securityCount, 2);
		assert.equal(res.normalCount, 1);
		assert.deepEqual(res.recent.map((r) => r.id), ["r2", "r3"]);
	});
});

describe("summarizeGuardRoster", () => {
	it("excludes FACILITY_MANAGER entries and counts on-duty guards", () => {
		const res = summarizeGuardRoster([
			{ id: "fm", role: "FACILITY_MANAGER", onDuty: false },
			{ id: "g1", role: "GUARD", onDuty: true },
			{ id: "g2", role: "GUARD", onDuty: false },
		]);
		assert.equal(res.totalGuards, 2);
		assert.equal(res.onDutyCount, 1);
		assert.deepEqual(res.guards.map((g) => g.id), ["g1", "g2"]);
	});
});

describe("SOC cleared incidents per guard", () => {
	const makeStorage = () => {
		const store = new Map();
		return {
			getItem: (k) => (store.has(k) ? store.get(k) : null),
			setItem: (k, v) => store.set(k, v),
			removeItem: (k) => store.delete(k),
		};
	};

	it("scopes cleared incidents per guard and migrates legacy unscoped key", () => {
		const storage = makeStorage();
		storage.setItem(SOC_CLEARED_STORAGE_KEY, JSON.stringify(["inc-1", "evt-2"]));

		const anCleared = readSocStoredSet(SOC_CLEARED_STORAGE_KEY, "guard-an", storage);
		assert.deepEqual(Array.from(anCleared), ["inc-1", "evt-2"]);
		assert.equal(storage.getItem(SOC_CLEARED_STORAGE_KEY), null);
		assert.ok(storage.getItem(getScopedSocStorageKey(SOC_CLEARED_STORAGE_KEY, "guard-an")));

		const binhCleared = readSocStoredSet(SOC_CLEARED_STORAGE_KEY, "guard-binh", storage);
		assert.equal(binhCleared.size, 0);

		writeSocStoredSet(SOC_ACK_STORAGE_KEY, new Set(["inc-3"]), "guard-an", storage);
		clearSocStoredSets("guard-an", storage);
		assert.equal(readSocStoredSet(SOC_CLEARED_STORAGE_KEY, "guard-an", storage).size, 0);
		assert.equal(readSocStoredSet(SOC_ACK_STORAGE_KEY, "guard-an", storage).size, 0);
	});

	it("filters out incidents matched by either id or eventId", () => {
		const list = [
			{ id: "inc-1", eventId: "evt-1", status: "NEW" },
			{ id: "inc-2", eventId: "evt-2", status: "NEW" },
			{ id: "inc-3", eventId: "evt-3", status: "NEW" },
		];
		const filtered = filterOutClearedIncidents(list, new Set(["inc-1", "evt-2"]));
		assert.deepEqual(filtered.map((x) => x.id), ["inc-3"]);
	});

	it("filters out resolved incidents at or before clearedResolvedBeforeMs while keeping pending alerts and newer resolutions", () => {
		const storage = makeStorage();
		const clearedAt = new Date("2026-11-25T14:05:00Z").getTime();
		writeSocClearedBefore(clearedAt, "guard-an", storage);
		const readTs = readSocClearedBefore("guard-an", storage);
		assert.equal(readTs, clearedAt);

		const list = [
			{ id: "old-verified", status: "RESOLVED_VERIFIED", detectedAt: "2026-11-22T10:00:00Z", resolvedAt: "2026-11-22T10:05:00Z" },
			{ id: "just-dismissed", status: "RESOLVED_DISMISSED", detectedAt: "2026-11-25T14:03:00Z", resolvedAt: "2026-11-25T14:04:00Z" },
			{ id: "unack-pending", status: "NEW", detectedAt: "2026-11-25T14:00:00Z" },
			{ id: "newer-resolved", status: "RESOLVED_VERIFIED", detectedAt: "2026-11-25T14:04:00Z", resolvedAt: "2026-11-25T14:06:00Z" },
		];
		const filtered = filterOutClearedIncidents(list, new Set(), readTs);
		assert.deepEqual(filtered.map((x) => x.id), ["unack-pending", "newer-resolved"]);

		clearSocStoredSets("guard-an", storage);
		assert.equal(readSocClearedBefore("guard-an", storage), null);
	});
});
