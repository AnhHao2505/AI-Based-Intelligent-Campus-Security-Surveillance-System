#!/usr/bin/env node
/**
 * P-MAP-0: xuất dữ liệu bản đồ khuôn viên từ code web sang asset cho app mobile (mobile/assets/campus/).
 *
 * Dùng ĐÚNG hàm web trong src/data/campusIndoor3dData.js (không viết lại thuật toán):
 *   - createDefaultGeometryStore (bản sao seed DEFAULT_CAMPUS_GEOMETRY_V3)
 *   - buildVector3dLayers (khối 3D theo toà / tầng)
 *   - buildOrGetCachedRouteBase (đồ thị tìm đường có trọng số) — hàm này KHÔNG export; script chép nguyên văn
 *     module sang thư mục tạm và chỉ thêm một dòng export ở cuối (không sửa file web). Xem đánh dấu [SHIM] bên dưới.
 *   - computeDigitizedRoute (kết quả ca chuẩn)
 *
 * Tất định: không ghi thời điểm sinh file, sắp xếp cố định, làm tròn số cố định. Chạy hai lần phải ra file giống hệt.
 *
 * Cách dùng:
 *   npm run export:campus-map                         # ghi vào ../mobile/assets/campus
 *   node scripts/export-campus-map.mjs --out <thư mục> # ghi vào thư mục khác (CI so sánh)
 *   node scripts/export-campus-map.mjs --report        # in chẩn đoán đồ thị + đo thời gian (không ghi file)
 */
import { Buffer } from "node:buffer";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND_DIR = path.resolve(SCRIPT_DIR, "..");
const REPO_DIR = path.resolve(FRONTEND_DIR, "..");
const WEB_MODULE = path.join(FRONTEND_DIR, "src/data/campusIndoor3dData.js");
const AREA_SEED_SQL = path.join(
	REPO_DIR,
	"backend/src/main/resources/db/migration/V120_1__seed_epsilon_areas.sql",
);
const DEFAULT_OUT = path.join(REPO_DIR, "mobile/assets/campus");

const SCHEMA_VERSION = 1;
const COORD_DIGITS = 7; // ~1 cm
const NUM_DIGITS = 3; // mét / chi phí / chiều cao

const args = process.argv.slice(2);
const outIdx = args.indexOf("--out");
const OUT_DIR = outIdx >= 0 ? path.resolve(args[outIdx + 1]) : DEFAULT_OUT;
const REPORT_ONLY = args.includes("--report");

// ---------------------------------------------------------------------------------------------------------------
// [SHIM] Nạp module web. buildOrGetCachedRouteBase (dựng đồ thị tìm đường) và haversineMeters (khoảng cách mét theo
// kinh / vĩ độ) không được export. Không sửa file web: chép nguyên văn sang thư mục tạm, đổi 2 đường dẫn import
// tương đối thành tuyệt đối, thêm đúng 1 dòng export ở cuối. Nếu web đổi tên / bỏ hai hàm này thì dừng với lỗi rõ.
// ---------------------------------------------------------------------------------------------------------------
async function loadWebModule() {
	const src = fs.readFileSync(WEB_MODULE, "utf8");
	for (const fn of ["function buildOrGetCachedRouteBase(", "function haversineMeters("]) {
		if (!src.includes(fn)) {
			throw new Error(`[export-campus-map] Không tìm thấy "${fn}" trong ${WEB_MODULE} — web đã đổi tên hàm, cập nhật script.`);
		}
	}
	const rewrites = {
		'"../utils/mapRoomLabelSprites.js"': JSON.stringify(
			pathToFileURL(path.join(FRONTEND_DIR, "src/utils/mapRoomLabelSprites.js")).href,
		),
		'"./defaultCampusGeometryV3.js"': JSON.stringify(
			pathToFileURL(path.join(FRONTEND_DIR, "src/data/defaultCampusGeometryV3.js")).href,
		),
	};
	let patched = src;
	for (const [from, to] of Object.entries(rewrites)) {
		if (!patched.includes(from)) {
			throw new Error(`[export-campus-map] Không thấy import ${from} trong module web — cập nhật script.`);
		}
		patched = patched.replace(from, to);
	}
	patched += "\nexport { buildOrGetCachedRouteBase as __buildOrGetCachedRouteBase, haversineMeters as __haversineMeters };\n";
	const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "icss-campus-export-"));
	const tmpFile = path.join(tmpDir, "campusIndoor3dData.export.mjs");
	fs.writeFileSync(tmpFile, patched);
	try {
		return await import(pathToFileURL(tmpFile).href);
	} finally {
		fs.rmSync(tmpDir, { recursive: true, force: true });
	}
}

// ---------------------------------------------------------------------------------------------------------------
// Tiện ích tất định
// ---------------------------------------------------------------------------------------------------------------
const round = (v, d) => {
	if (typeof v !== "number" || !Number.isFinite(v)) return v;
	const r = Number(v.toFixed(d));
	return Object.is(r, -0) ? 0 : r;
};
const roundCoords = (c) =>
	Array.isArray(c) && typeof c[0] === "number" ? c.map((x) => round(x, COORD_DIGITS)) : c.map(roundCoords);
const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const stableJson = (obj) => `${JSON.stringify(obj, null, 1)}\n`;
const sha256 = (s) => createHash("sha256").update(s).digest("hex");
const slug = (s) =>
	String(s)
		.normalize("NFD")
		.replace(/[̀-ͯ]/g, "")
		.replace(/đ/g, "d")
		.replace(/Đ/g, "D")
		.toLowerCase()
		.replace(/[^a-z0-9]+/g, "-")
		.replace(/^-|-$/g, "");

// ---------------------------------------------------------------------------------------------------------------
// a + b. Khối 3D theo toà / tầng (buildVector3dLayers)
// ---------------------------------------------------------------------------------------------------------------
const LAYER_SOURCES = [
	["slabsGeoJSON", "slab"],
	["wallsGeoJSON", "wall"],
	["roomsGeoJSON", "room"],
	["doorsGeoJSON", "door"],
	["portalSymbolsGeoJSON", "portal"],
];
const KEEP_PROPS = [
	"id",
	"role",
	"wallKind",
	"isPerimeter",
	"nodeType",
	"portalGroupId",
	"widthMeters",
	"color",
	"baseHeight",
	"extrudeHeight",
	"heightOffset",
];

function buildFloorLayers(web, store, building, floorOrder) {
	const layers = web.buildVector3dLayers({
		store,
		selectedBuilding: building,
		activeFloorOrder: floorOrder,
		areas: [], // khu vực lấy từ API lúc chạy app; asset chỉ chứa hình học
		viewRole: "FACILITY_MANAGER",
		showOtherBuildingsInFocus: false,
	});
	const features = [];
	for (const [key, layer] of LAYER_SOURCES) {
		for (const f of layers[key]?.features || []) {
			const p = f.properties || {};
			if (p.isGhost || p.isContextBuilding) continue;
			if (!web.matchesBuilding(p.building, building)) continue;
			if ((Number(p.floorOrder) || 0) !== floorOrder) continue;
			const props = { layer, building, floorOrder };
			for (const k of KEEP_PROPS) {
				if (p[k] === undefined || p[k] === null || p[k] === "") continue;
				props[k] = typeof p[k] === "number" ? round(p[k], NUM_DIGITS) : p[k];
			}
			features.push({
				type: "Feature",
				properties: props,
				geometry: { type: f.geometry.type, coordinates: roundCoords(f.geometry.coordinates) },
			});
		}
	}
	features.sort(
		(a, b) =>
			cmp(LAYER_SOURCES.findIndex((l) => l[1] === a.properties.layer), LAYER_SOURCES.findIndex((l) => l[1] === b.properties.layer)) ||
			cmp(String(a.properties.id), String(b.properties.id)) ||
			cmp(JSON.stringify(a.geometry.coordinates), JSON.stringify(b.geometry.coordinates)),
	);
	return { type: "FeatureCollection", features };
}

// ---------------------------------------------------------------------------------------------------------------
// c. Đồ thị (buildOrGetCachedRouteBase): nút + cạnh có chi phí đúng như web dùng để tìm đường
// ---------------------------------------------------------------------------------------------------------------
function buildGraph(web, base) {
	const nodeOut = (n) => {
		const meta = base.getNodeMeta(n);
		const rawBldg = n.building || "";
		const isOutdoor = Boolean(meta?.isOut);
		const building = isOutdoor
			? "OUTDOOR"
			: web.resolveCanonicalBuildingName(meta?.bldg || rawBldg) || rawBldg;
		const out = {
			id: n.id,
			lon: round(n.coords[0], COORD_DIGITS),
			lat: round(n.coords[1], COORD_DIGITS),
			building,
			floorOrder: Number(n.floorOrder) || 0,
			type: n.type || "NODE",
			isOutdoor,
		};
		if (n.portalGroupId) out.portalGroupId = n.portalGroupId;
		if (n.isDoorVirtual || n.isVirtual) out.virtual = true;
		return out;
	};
	const nodes = [...base.nodeMap.values()].map(nodeOut).sort((a, b) => cmp(a.id, b.id));
	const edgeMap = new Map();
	for (const [uId, list] of base.adj.entries()) {
		for (const { to, weight } of list) {
			const [a, b] = uId < to ? [uId, to] : [to, uId];
			const key = `${a}\u0000${b}`;
			const u = base.nodeMap.get(a);
			const v = base.nodeMap.get(b);
			const cost = round(weight, NUM_DIGITS);
			const prev = edgeMap.get(key);
			if (!prev || cost < prev.cost) {
				edgeMap.set(key, {
					a,
					b,
					lengthMeters: round(web.__haversineMeters(u.coords, v.coords), NUM_DIGITS),
					cost,
				});
			}
		}
	}
	const edges = [...edgeMap.values()].sort((x, y) => cmp(x.a, y.a) || cmp(x.b, y.b));
	return { nodes, edges, edgeMap };
}

// ---------------------------------------------------------------------------------------------------------------
// d. Ca chuẩn (computeDigitizedRoute)
// ---------------------------------------------------------------------------------------------------------------
function readSeedAreas(web) {
	const sql = fs.readFileSync(AREA_SEED_SQL, "utf8");
	const re = /\('([^']+)',\s*'([^']+)',\s*'([A-Z_]+)',\s*(\d+),\s*([\d.]+),\s*([\d.]+)\)/g;
	const areas = [];
	for (const m of sql.matchAll(re)) {
		areas.push({
			floorName: m[1],
			name: m[2],
			building: "Tòa Epsilon",
			floorOrder: web.resolveFloorOrder("Tòa Epsilon", m[1]),
			lat: Number(m[5]),
			lon: Number(m[6]),
		});
	}
	if (areas.length === 0) throw new Error(`[export-campus-map] Không đọc được khu vực nào từ ${AREA_SEED_SQL}`);
	return areas.sort((a, b) => cmp(a.floorOrder, b.floorOrder) || cmp(a.name, b.name));
}

function pickEvenly(list, count) {
	if (list.length <= count) return list.slice();
	const out = [];
	for (let i = 0; i < count; i++) out.push(list[Math.floor((i * list.length) / count)]);
	return out;
}

function endpointOf(node) {
	return {
		lon: node.lon,
		lat: node.lat,
		building: node.isOutdoor ? "OUTDOOR" : node.building,
		floorOrder: node.floorOrder,
		nodeId: node.id,
	};
}

function buildCases(graphNodes, areas) {
	const real = graphNodes.filter((n) => !n.virtual && n.type !== "DOOR");
	const indoor = real.filter((n) => !n.isOutdoor);
	const outdoor = real.filter((n) => n.isOutdoor);
	const byBF = new Map();
	for (const n of indoor) {
		const k = `${n.building}__${n.floorOrder}`;
		if (!byBF.has(k)) byBF.set(k, []);
		byBF.get(k).push(n);
	}
	const keys = [...byBF.keys()].sort();
	const cases = [];
	const add = (category, start, end, note) => cases.push({ category, start, end, ...(note ? { note } : {}) });

	// 1. Cùng tầng: mỗi toà/tầng một cặp (nút đầu ↔ nút cuối theo id)
	for (const k of keys) {
		const list = byBF.get(k);
		if (list.length >= 2) add("SAME_FLOOR", endpointOf(list[0]), endpointOf(list[list.length - 1]));
	}
	// 2. Khác tầng cùng toà: tầng thấp nhất ↔ từng tầng khác
	const buildings = [...new Set(indoor.map((n) => n.building))].sort();
	for (const b of buildings) {
		const floors = keys.filter((k) => k.startsWith(`${b}__`)).map((k) => Number(k.split("__")[1])).sort((x, y) => x - y);
		const low = byBF.get(`${b}__${floors[0]}`);
		for (const f of floors.slice(1)) {
			const hi = byBF.get(`${b}__${f}`);
			add("CROSS_FLOOR", endpointOf(low[Math.floor(low.length / 2)]), endpointOf(hi[Math.floor(hi.length / 2)]));
		}
	}
	// 3. Khác toà: mỗi tầng toà A ↔ tầng cùng chỉ số (hoặc cao nhất) toà B
	if (buildings.length >= 2) {
		const [b1, b2] = buildings;
		const f1 = keys.filter((k) => k.startsWith(`${b1}__`));
		const f2 = keys.filter((k) => k.startsWith(`${b2}__`));
		for (let i = 0; i < Math.max(f1.length, f2.length); i++) {
			const a = byBF.get(f1[Math.min(i, f1.length - 1)]);
			const z = byBF.get(f2[Math.min(i, f2.length - 1)]);
			add("CROSS_BUILDING", endpointOf(a[0]), endpointOf(z[z.length - 1]));
		}
	}
	// 4. Ngoài sân → phòng có khu vực (khu vực seed V120_1, Tòa Epsilon): rải đều 12 khu, xuất phát từ nút ngoài sân
	const outdoorStart = outdoor[0];
	for (const area of pickEvenly(areas, 12)) {
		add(
			"OUTDOOR_TO_AREA",
			endpointOf(outdoorStart),
			{ lon: round(area.lon, COORD_DIGITS), lat: round(area.lat, COORD_DIGITS), building: area.building, floorOrder: area.floorOrder, areaName: area.name },
		);
	}
	// 5. Ngoài sân → ngoài sân
	if (outdoor.length >= 2) add("OUTDOOR_TO_OUTDOOR", endpointOf(outdoor[0]), endpointOf(outdoor[outdoor.length - 1]));
	// 6. Không tới được: toạ độ thiếu (mã INVALID_COORDS), đích là toà không có trong đồ thị
	add("UNREACHABLE", endpointOf(outdoor[0]), { lon: null, lat: null, building: "Tòa Epsilon", floorOrder: 0 }, "Thiếu toạ độ đích");
	return cases.map((c, i) => ({ id: `GR-${String(i + 1).padStart(3, "0")}`, ...c }));
}

function routeCase(web, store, c, graph) {
	const diag = {};
	const res = web.computeDigitizedRoute({
		store,
		startCoords: c.start.lon == null ? null : [c.start.lon, c.start.lat],
		startBuilding: c.start.building,
		startFloorOrder: c.start.floorOrder,
		endCoords: c.end.lon == null ? null : [c.end.lon, c.end.lat],
		endBuilding: c.end.building,
		endFloorOrder: c.end.floorOrder,
		diagnosticsOut: diag,
	});
	if (!res) {
		return { reachable: false, reasonCode: diag.reasonCode || "UNKNOWN", message: diag.message || "" };
	}
	const ids = res.pathNodes.map((n) => n.id);
	// Tổng chi phí theo trọng số web: cạnh có trong đồ thị nền dùng đúng cost đã xuất; cạnh gắn điểm đi / đến (nút ảo
	// sinh lúc gọi) dùng công thức nền của web: haversineMeters + |Δtầng| × 18 (campusIndoor3dData.js:3412-3416).
	let totalCost = 0;
	for (let i = 0; i < res.pathNodes.length - 1; i++) {
		const u = res.pathNodes[i];
		const v = res.pathNodes[i + 1];
		const [a, b] = u.id < v.id ? [u.id, v.id] : [v.id, u.id];
		const e = graph.edgeMap.get(`${a}\u0000${b}`);
		totalCost += e
			? e.cost
			: web.__haversineMeters(u.coords, v.coords) + Math.abs((Number(u.floorOrder) || 0) - (Number(v.floorOrder) || 0)) * 18;
	}
	return {
		reachable: true,
		pathNodeIds: ids,
		totalCost: round(totalCost, NUM_DIGITS),
		totalDistanceMeters: res.totalDistanceMeters,
		floorsClimbed: res.floorsClimbed,
		estimatedSeconds: res.estimatedSeconds,
	};
}

// ---------------------------------------------------------------------------------------------------------------
// Chẩn đoán + đo thời gian (--report)
// ---------------------------------------------------------------------------------------------------------------
function components(graph) {
	const adj = new Map(graph.nodes.map((n) => [n.id, []]));
	for (const e of graph.edges) {
		adj.get(e.a).push(e.b);
		adj.get(e.b).push(e.a);
	}
	const seen = new Set();
	const comps = [];
	for (const n of graph.nodes) {
		if (seen.has(n.id)) continue;
		const stack = [n.id];
		const comp = [];
		seen.add(n.id);
		while (stack.length) {
			const id = stack.pop();
			comp.push(id);
			for (const t of adj.get(id)) {
				if (!seen.has(t)) {
					seen.add(t);
					stack.push(t);
				}
			}
		}
		comps.push(comp);
	}
	return comps.sort((a, b) => b.length - a.length);
}

function report(web, store, graph, areas, cases) {
	const byId = new Map(graph.nodes.map((n) => [n.id, n]));
	const comps = components(graph);
	const describe = (comp) => {
		const groups = {};
		for (const id of comp) {
			const n = byId.get(id);
			const k = `${n.building}/F${n.floorOrder}${n.type !== "NODE" ? "/" + n.type : ""}`;
			groups[k] = (groups[k] || 0) + 1;
		}
		return groups;
	};
	console.log(`Thành phần liên thông: ${comps.length}; kích thước: ${JSON.stringify(comps.map((c) => c.length))}`);
	comps.slice(1).forEach((c, i) => console.log(`  #${i + 2} (${c.length} nút): ${JSON.stringify(describe(c))} ví dụ ${c.slice(0, 3).join(", ")}`));
	const outdoorStart = graph.nodes.find((n) => n.isOutdoor && !n.virtual);
	const fails = [];
	for (const a of areas) {
		const r = routeCase(web, store, { start: endpointOf(outdoorStart), end: { lon: a.lon, lat: a.lat, building: a.building, floorOrder: a.floorOrder } }, { edgeMap: new Map() });
		if (!r.reachable) fails.push(`${a.name} (F${a.floorOrder}): ${r.reasonCode} — ${r.message}`);
	}
	console.log(`Khu vực seed V120_1: ${areas.length}; không tới được từ ngoài sân: ${fails.length}`);
	fails.forEach((f) => console.log(`  - ${f}`));
	const audit = web.auditNavigationConnectivity({ store });
	const issueCount = {};
	for (const it of audit.issues || []) issueCount[it.code || it.type || "?"] = (issueCount[it.code || it.type || "?"] || 0) + 1;
	console.log(`auditNavigationConnectivity issues: ${JSON.stringify(issueCount)}`);

	// Lần dựng đồ thị đầu tiên (cache nguội): mỗi bản sao seed có mảng walls mới nên cache theo walls không trúng
	const cold = [];
	for (let r = 0; r < 5; r++) {
		const fresh = web.createDefaultGeometryStore();
		const t0 = process.hrtime.bigint();
		web.__buildOrGetCachedRouteBase(fresh);
		cold.push(Number(process.hrtime.bigint() - t0) / 1e6);
	}
	console.log(`buildOrGetCachedRouteBase (cache nguội, 5 lần): ${cold.map((x) => x.toFixed(1)).join(", ")} ms`);

	const RUNS = 20;
	const times = [];
	for (let r = 0; r < RUNS; r++) {
		for (const c of cases) {
			const t0 = process.hrtime.bigint();
			web.computeDigitizedRoute({
				store,
				startCoords: c.start.lon == null ? null : [c.start.lon, c.start.lat],
				startBuilding: c.start.building,
				startFloorOrder: c.start.floorOrder,
				endCoords: c.end.lon == null ? null : [c.end.lon, c.end.lat],
				endBuilding: c.end.building,
				endFloorOrder: c.end.floorOrder,
			});
			times.push(Number(process.hrtime.bigint() - t0) / 1e6);
		}
	}
	const avg = times.reduce((s, x) => s + x, 0) / times.length;
	const sorted = times.slice().sort((a, b) => a - b);
	console.log(`computeDigitizedRoute: ${times.length} lần (${cases.length} ca × ${RUNS}); TB ${avg.toFixed(3)} ms; trung vị ${sorted[Math.floor(sorted.length / 2)].toFixed(3)} ms; p95 ${sorted[Math.floor(sorted.length * 0.95)].toFixed(3)} ms; lớn nhất ${sorted[sorted.length - 1].toFixed(3)} ms; Node ${process.version}; ${os.cpus()[0]?.model} ${os.platform()}-${os.arch()}`);
}

// ---------------------------------------------------------------------------------------------------------------
async function main() {
	const web = await loadWebModule();
	const seedVersion = web.DEFAULT_GEOMETRY_SEED_VERSION;

	// Đồ thị nền dựng trên một bản sao seed riêng, trước mọi lần tìm đường (lần gọi đầu trả đúng đồ thị chưa bị gắn
	// điểm đi / đến; các lần sau web trả bản sao từ cache).
	const graphStore = web.createDefaultGeometryStore();
	const base = web.__buildOrGetCachedRouteBase(graphStore);
	const graph = buildGraph(web, base);

	const routeStore = web.createDefaultGeometryStore();
	const areas = readSeedAreas(web);
	const cases = buildCases(graph.nodes, areas);

	if (REPORT_ONLY) {
		report(web, routeStore, graph, areas, cases);
		return;
	}

	const files = new Map();
	const buildings = [];
	const layerStore = web.createDefaultGeometryStore();
	for (const meta of web.DEFAULT_BUILDINGS_META) {
		const floors = [];
		for (const fl of meta.floors) {
			const floorOrder = Number(fl.floorOrder) || 0;
			const rel = `layers/${slug(meta.name)}__f${floorOrder}.geojson`;
			const fc = buildFloorLayers(web, layerStore, meta.name, floorOrder);
			files.set(rel, stableJson(fc));
			floors.push({ floorOrder, file: rel, features: fc.features.length });
		}
		buildings.push({ name: meta.name, aliases: [...meta.aliases].sort(), floors });
	}

	files.set(
		"graph.json",
		stableJson({
			schemaVersion: SCHEMA_VERSION,
			seedVersion,
			costFormula:
				"cost = haversineMeters(u,v) + |floorOrder(u) - floorOrder(v)| * 18 (campusIndoor3dData.js:3412-3416); cạnh nối cửa ảo: max(0.05, haversine) (:3635); lengthMeters = haversineMeters(u,v) (mặt phẳng, không tính chiều cao)",
			nodes: graph.nodes,
			edges: graph.edges,
		}),
	);

	const goldenCases = cases.map((c) => ({ ...c, expected: routeCase(web, routeStore, c, graph) }));
	files.set(
		"golden-routes.json",
		stableJson({
			schemaVersion: SCHEMA_VERSION,
			seedVersion,
			source: "computeDigitizedRoute (frontend/src/data/campusIndoor3dData.js:3799)",
			areaSource: "backend/src/main/resources/db/migration/V120_1__seed_epsilon_areas.sql",
			cases: goldenCases,
		}),
	);

	const manifest = {
		schemaVersion: SCHEMA_VERSION,
		seedVersion,
		generator: "frontend/scripts/export-campus-map.mjs",
		precision: { coordinateDigits: COORD_DIGITS, numberDigits: NUM_DIGITS },
		graph: { file: "graph.json", nodes: graph.nodes.length, edges: graph.edges.length },
		goldenRoutes: { file: "golden-routes.json", cases: goldenCases.length },
		buildings,
		files: [...files.keys()].sort().map((f) => ({ path: f, bytes: Buffer.byteLength(files.get(f)), sha256: sha256(files.get(f)) })),
	};
	files.set("manifest.json", stableJson(manifest));

	fs.mkdirSync(path.join(OUT_DIR, "layers"), { recursive: true });
	for (const [rel, content] of [...files.entries()].sort((a, b) => cmp(a[0], b[0]))) {
		fs.writeFileSync(path.join(OUT_DIR, rel), content);
	}
	console.log(
		`[export-campus-map] seed ${seedVersion}: ${graph.nodes.length} nút, ${graph.edges.length} cạnh, ${goldenCases.length} ca chuẩn, ${files.size} file → ${path.relative(REPO_DIR, OUT_DIR) || OUT_DIR}`,
	);
}

main().catch((err) => {
	console.error(err?.stack || err);
	process.exit(1);
});
