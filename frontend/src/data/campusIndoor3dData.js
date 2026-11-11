/**
 * Pure Geometry Store & 3D Vector / A* Engine for Multi-Building & Multi-Floor Campus
 *
 * Separation of Concerns:
 * - FloorDigitizerStudio (Admin Dev Tool) writes pure geometry:
 *   1. floorCalibrations (PNG tracing blueprint bounds - ONLY rendered inside FloorDigitizerStudio)
 *   2. walls (Unified Wall Graph polylines -> Planar Face Extraction into ROOM / CORRIDOR / VOID with holes)
 *   3. doors (Single wall-mounted DOOR tool -> carves real 3D openings & connects two sides for A*)
 *   4. navNodes & navEdges (Unified NODE + Edge graph with geometric Outdoor inference + STAIRS/ELEVATOR portals)
 *
 * - CampusMapView & GuardMapScreen NEVER render the raw PNG images; they only render clean 3D Vector layers.
 */

export const STORAGE_KEY_3D_GEOMETRY = "icss_campus_3d_geometry_v3";
const LEGACY_STORAGE_KEYS = ["icss_campus_3d_geometry_v2"];

export const FLOOR_ELEVATION_STEP = 3.5;
export const FLOOR_SLAB_HEIGHT = 0.16;
export const ROOM_EXTRUSION_HEIGHT = 0.52;
export const ROOM_SELECTED_EXTRUSION_HEIGHT = 1.0;
export const BOUNDARY_WALL_HEIGHT = 2.85;
export const INTERIOR_WALL_HEIGHT = 1.35;

const HCM_CALIBRATED_BOUNDS = {
	west: 106.80897,
	east: 106.810973,
	north: 10.842423,
	south: 10.840455,
	corners: [
		[106.809714, 10.842423],
		[106.810973, 10.841175],
		[106.81025, 10.840455],
		[106.80897, 10.841661],
	],
};

const EPS_CALIBRATED_BOUNDS = {
	west: 106.810865,
	east: 106.812017,
	north: 10.841067,
	south: 10.839933,
	corners: [
		[106.811338, 10.841067],
		[106.812017, 10.840475],
		[106.811544, 10.839933],
		[106.810865, 10.840525],
	],
};

export const DEFAULT_BUILDINGS_META = [
	{
		id: "bldg-hcm",
		name: "Tòa HCM",
		aliases: ["Tòa HCM", "Tòa Alpha", "Tòa FPT HCM"],
		center: [106.809977, 10.841429],
		floors: [
			{
				id: "hcm-g",
				name: "Tầng Trệt",
				shortLabel: "Tầng G",
				floorOrder: 0,
				baseElevation: 0,
				ceilingElevation: 3.5,
				pngUrl: "/demo_floor_plans/tang%20G.png",
				defaultBounds: HCM_CALIBRATED_BOUNDS,
			},
			{
				id: "hcm-1",
				name: "Tầng 1",
				shortLabel: "Tầng 1",
				floorOrder: 1,
				baseElevation: 3.5,
				ceilingElevation: 7.0,
				pngUrl: "/demo_floor_plans/tang_1.png",
				defaultBounds: HCM_CALIBRATED_BOUNDS,
			},
			{
				id: "hcm-2",
				name: "Tầng 2",
				shortLabel: "Tầng 2",
				floorOrder: 2,
				baseElevation: 7.0,
				ceilingElevation: 10.5,
				pngUrl: "/demo_floor_plans/tang_3.png",
				defaultBounds: HCM_CALIBRATED_BOUNDS,
			},
		],
	},
	{
		id: "bldg-epsilon",
		name: "Tòa Epsilon",
		aliases: ["Tòa Epsilon", "Tòa Beta", "Tòa Tân Uyên"],
		center: [106.811441, 10.8405],
		floors: [
			{
				id: "eps-l1",
				name: "Tầng Trệt",
				shortLabel: "L1 (Trệt)",
				floorOrder: 0,
				baseElevation: 0,
				ceilingElevation: 3.5,
				pngUrl: "/demo_floor_plans/L1.png",
				defaultBounds: EPS_CALIBRATED_BOUNDS,
			},
			{
				id: "eps-lv2",
				name: "Tầng 1",
				shortLabel: "LV2 (Tầng 1)",
				floorOrder: 1,
				baseElevation: 3.5,
				ceilingElevation: 7.0,
				pngUrl: "/demo_floor_plans/LV2.png",
				defaultBounds: EPS_CALIBRATED_BOUNDS,
			},
		],
	},
];

export function matchesBuilding(b1, b2) {
	if (!b1 || !b2) return false;
	const s1 = String(b1).trim().toLowerCase();
	const s2 = String(b2).trim().toLowerCase();
	if (s1 === s2) return true;
	for (const b of DEFAULT_BUILDINGS_META) {
		const list = b.aliases.map((a) => a.toLowerCase());
		if (list.includes(s1) && list.includes(s2)) return true;
	}
	return false;
}

export function resolveCanonicalBuildingName(buildingName) {
	if (!buildingName) return "";
	for (const b of DEFAULT_BUILDINGS_META) {
		if (matchesBuilding(b.name, buildingName)) return b.name;
	}
	return String(buildingName).trim();
}

export function resolveFloorOrder(buildingName, floorName) {
	if (!floorName || floorName === "ALL") return 0;
	const fn = String(floorName).trim().toLowerCase();
	if (
		fn.includes("trệt") ||
		fn === "g" ||
		fn.includes("tầng g") ||
		fn.includes("ground") ||
		fn.includes("l1")
	) {
		return 0;
	}
	if (
		fn.includes("lv3") ||
		fn.includes("tầng 2") ||
		fn.includes("floor 2") ||
		fn.includes("lầu 2") ||
		fn === "2" ||
		fn.includes("tầng 3")
	) {
		return 2;
	}
	if (
		fn.includes("lv2") ||
		fn.includes("tầng 1") ||
		fn.includes("floor 1") ||
		fn.includes("lầu 1") ||
		fn === "1"
	) {
		return 1;
	}
	return 0;
}

function uvToCoord(bounds, u, v) {
	if (Array.isArray(bounds?.corners) && bounds.corners.length === 4) {
		const [nw, ne, se, sw] = bounds.corners;
		const lng =
			(1 - u) * (1 - v) * nw[0] +
			u * (1 - v) * ne[0] +
			u * v * se[0] +
			(1 - u) * v * sw[0];
		const lat =
			(1 - u) * (1 - v) * nw[1] +
			u * (1 - v) * ne[1] +
			u * v * se[1] +
			(1 - u) * v * sw[1];
		return [Number(lng.toFixed(6)), Number(lat.toFixed(6))];
	}
	return [
		Number((bounds.west + u * (bounds.east - bounds.west)).toFixed(6)),
		Number((bounds.north - v * (bounds.north - bounds.south)).toFixed(6)),
	];
}

function uvRectRing(bounds, u1, v1, u2, v2) {
	const p1 = uvToCoord(bounds, u1, v1);
	const p2 = uvToCoord(bounds, u2, v1);
	const p3 = uvToCoord(bounds, u2, v2);
	const p4 = uvToCoord(bounds, u1, v2);
	return [p1, p2, p3, p4, p1];
}

const HCM_B = HCM_CALIBRATED_BOUNDS;
const EPS_B = EPS_CALIBRATED_BOUNDS;

/**
 * Initial starter geometry aligned to calibrated campus footprints
 */
export function createDefaultGeometryStore() {
	const hcmGroundRing = [
		[106.809642, 10.842129],
		[106.809201, 10.84169],
		[106.810273, 10.840676],
		[106.81068, 10.841082],
		[106.810473, 10.84148],
		[106.809642, 10.842129],
	];

	const epsGroundRing = [
		[106.811427, 10.840864],
		[106.81155, 10.840756],
		[106.811344, 10.840524],
		[106.811436, 10.840444],
		[106.811641, 10.840678],
		[106.811766, 10.840567],
		[106.811433, 10.840192],
		[106.811093, 10.840482],
		[106.811427, 10.840864],
	];

	const buildingBoundaries = [
		// Tòa HCM outer boundary (all 3 floors aligned to moved Floor G footprint)
		...[0, 1, 2].map((floorOrder) => ({
			id: `bound-hcm-${floorOrder}`,
			building: "Tòa HCM",
			floorOrder,
			ring: hcmGroundRing.map((pt) => [...pt]),
		})),
		// Tòa Epsilon U-shape outer boundary (L1 & LV2 aligned to moved L1 footprint)
		...[0, 1].map((floorOrder) => ({
			id: `bound-eps-${floorOrder}`,
			building: "Tòa Epsilon",
			floorOrder,
			ring: epsGroundRing.map((pt) => [...pt]),
		})),
	];

	// Pure geometric room polygons (no Area business fields attached)
	const roomPolygons = [
		// Tòa HCM - Floor 0 (Tầng G - exact user-digitized rooms)
		{
			id: "room-1794307089747",
			building: "Tòa HCM",
			floorOrder: 0,
			ring: [
				[106.810388, 10.841053],
				[106.810277, 10.840935],
				[106.810334, 10.840886],
				[106.810448, 10.840998],
				[106.810388, 10.841053],
			],
		},
		{
			id: "room-1794307097719",
			building: "Tòa HCM",
			floorOrder: 0,
			ring: [
				[106.810448, 10.841111],
				[106.810388, 10.841053],
				[106.810448, 10.840998],
				[106.810507, 10.841056],
				[106.810448, 10.841111],
			],
		},
		{
			id: "room-1794307111205",
			building: "Tòa HCM",
			floorOrder: 0,
			ring: [
				[106.81052, 10.841038],
				[106.810582, 10.84098],
				[106.810411, 10.840802],
				[106.810346, 10.840866],
				[106.81052, 10.841038],
			],
		},
		{
			id: "room-1794307197893",
			building: "Tòa HCM",
			floorOrder: 0,
			ring: [
				[106.809747, 10.841289],
				[106.810144, 10.84091],
				[106.810083, 10.840854],
				[106.809687, 10.841229],
				[106.809747, 10.841289],
			],
		},
		{
			id: "room-1794307211587",
			building: "Tòa HCM",
			floorOrder: 0,
			ring: [
				[106.809263, 10.841755],
				[106.8094, 10.841615],
				[106.809344, 10.841555],
				[106.809201, 10.84169],
				[106.809263, 10.841755],
			],
		},
		{
			id: "room-1794307259741",
			building: "Tòa HCM",
			floorOrder: 0,
			ring: [
				[106.809519, 10.842023],
				[106.809698, 10.841836],
				[106.809467, 10.8416],
				[106.809283, 10.841775],
				[106.809519, 10.842023],
			],
		},
		{
			id: "room-1794307313261",
			building: "Tòa HCM",
			floorOrder: 0,
			ring: [
				[106.809911, 10.841922],
				[106.810223, 10.841682],
				[106.81017, 10.841613],
				[106.809849, 10.841853],
				[106.809911, 10.841922],
			],
		},
		// Tòa HCM - Floor 1 (Tầng 1)
		{
			id: "room-hcm-1-1",
			building: "Tòa HCM",
			floorOrder: 1,
			ring: uvRectRing(HCM_B, 0.082, 0.33, 0.225, 0.715),
		},
		{
			id: "room-hcm-1-2",
			building: "Tòa HCM",
			floorOrder: 1,
			ring: uvRectRing(HCM_B, 0.186, 0.748, 0.588, 0.822),
		},
		{
			id: "room-hcm-1-3",
			building: "Tòa HCM",
			floorOrder: 1,
			ring: uvRectRing(HCM_B, 0.808, 0.325, 0.855, 0.635),
		},
		{
			id: "room-hcm-1-4",
			building: "Tòa HCM",
			floorOrder: 1,
			ring: uvRectRing(HCM_B, 0.868, 0.325, 0.914, 0.482),
		},
		// Tòa HCM - Floor 2 (Tầng 2)
		{
			id: "room-hcm-2-1",
			building: "Tòa HCM",
			floorOrder: 2,
			ring: uvRectRing(HCM_B, 0.082, 0.33, 0.225, 0.715),
		},
		{
			id: "room-hcm-2-2",
			building: "Tòa HCM",
			floorOrder: 2,
			ring: uvRectRing(HCM_B, 0.185, 0.752, 0.495, 0.828),
		},
		{
			id: "room-hcm-2-3",
			building: "Tòa HCM",
			floorOrder: 2,
			ring: uvRectRing(HCM_B, 0.676, 0.752, 0.914, 0.828),
		},
		{
			id: "room-hcm-2-4",
			building: "Tòa HCM",
			floorOrder: 2,
			ring: uvRectRing(HCM_B, 0.808, 0.34, 0.914, 0.642),
		},
		// Tòa Epsilon - Floor 0 (L1)
		{
			id: "room-eps-0-1",
			building: "Tòa Epsilon",
			floorOrder: 0,
			ring: uvRectRing(EPS_B, 0.224, 0.72, 0.396, 0.84),
		},
		{
			id: "room-eps-0-2",
			building: "Tòa Epsilon",
			floorOrder: 0,
			ring: uvRectRing(EPS_B, 0.54, 0.72, 0.712, 0.84),
		},
		{
			id: "room-eps-0-3",
			building: "Tòa Epsilon",
			floorOrder: 0,
			ring: uvRectRing(EPS_B, 0.224, 0.335, 0.292, 0.665),
		},
		{
			id: "room-eps-0-4",
			building: "Tòa Epsilon",
			floorOrder: 0,
			ring: uvRectRing(EPS_B, 0.32, 0.268, 0.396, 0.478),
		},
		{
			id: "room-eps-0-5",
			building: "Tòa Epsilon",
			floorOrder: 0,
			ring: uvRectRing(EPS_B, 0.224, 0.138, 0.398, 0.232),
		},
		{
			id: "room-eps-0-6",
			building: "Tòa Epsilon",
			floorOrder: 0,
			ring: uvRectRing(EPS_B, 0.538, 0.138, 0.712, 0.232),
		},
		{
			id: "room-eps-0-7",
			building: "Tòa Epsilon",
			floorOrder: 0,
			ring: uvRectRing(EPS_B, 0.538, 0.268, 0.712, 0.562),
		},
		// Tòa Epsilon - Floor 1 (LV2)
		{
			id: "room-eps-1-1",
			building: "Tòa Epsilon",
			floorOrder: 1,
			ring: uvRectRing(EPS_B, 0.224, 0.735, 0.712, 0.842),
		},
		{
			id: "room-eps-1-2",
			building: "Tòa Epsilon",
			floorOrder: 1,
			ring: uvRectRing(EPS_B, 0.224, 0.335, 0.3, 0.692),
		},
		{
			id: "room-eps-1-3",
			building: "Tòa Epsilon",
			floorOrder: 1,
			ring: uvRectRing(EPS_B, 0.332, 0.215, 0.402, 0.455),
		},
		{
			id: "room-eps-1-4",
			building: "Tòa Epsilon",
			floorOrder: 1,
			ring: uvRectRing(EPS_B, 0.54, 0.14, 0.712, 0.685),
		},
	];

	const navNodes = [
		// Outdoor walkway nodes connecting moved Tòa HCM & Tòa Epsilon
		{
			id: "out-gate",
			type: "OUTDOOR",
			building: "OUTDOOR",
			floorOrder: 0,
			coords: [106.80945, 10.84105],
		},
		{
			id: "out-mid",
			type: "OUTDOOR",
			building: "OUTDOOR",
			floorOrder: 0,
			coords: [106.81005, 10.84072],
		},
		{
			id: "out-link",
			type: "OUTDOOR",
			building: "OUTDOOR",
			floorOrder: 0,
			coords: [106.81068, 10.84052],
		},
		{
			id: "out-eps",
			type: "OUTDOOR",
			building: "OUTDOOR",
			floorOrder: 0,
			coords: [106.81115, 10.84024],
		},

		// Tòa HCM - Floor 0 (user-digitized corridor loop + aligned entrances & stairs)
		{
			id: "node-1794307597801",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.809473, 10.841577],
		},
		{
			id: "node-1794307602171",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.80931, 10.841707],
		},
		{
			id: "node-1794307629249",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.809772, 10.841895],
		},
		{
			id: "node-1794307634367",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.809617, 10.842019],
		},
		{
			id: "node-1794307643227",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.80989, 10.841825],
		},
		{
			id: "node-1794307645265",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.809944, 10.841759],
		},
		{
			id: "node-1794307652919",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.810436, 10.841383],
		},
		{
			id: "node-1794307656813",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.810572, 10.841105],
		},
		{
			id: "node-1794307661173",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.810474, 10.84102],
		},
		{
			id: "node-1794307662821",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.810404, 10.840929],
		},
		{
			id: "node-1794307666981",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.810215, 10.840748],
		},
		{
			id: "node-1794307696551",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: [106.810175, 10.840903],
		},
		{
			id: "hcm-0-ent-s",
			type: "ENTRANCE",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: uvToCoord(HCM_B, 0.44, 0.82),
		},
		{
			id: "hcm-0-ent-e",
			type: "ENTRANCE",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: uvToCoord(HCM_B, 0.79, 0.72),
		},
		{
			id: "hcm-0-stair-w",
			type: "STAIRS",
			portalGroupId: "HCM_STAIR_W",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: uvToCoord(HCM_B, 0.22, 0.5),
		},
		{
			id: "hcm-0-stair-e",
			type: "STAIRS",
			portalGroupId: "HCM_STAIR_E",
			building: "Tòa HCM",
			floorOrder: 0,
			coords: uvToCoord(HCM_B, 0.86, 0.34),
		},

		// Tòa HCM - Floor 1
		{
			id: "hcm-1-stair-w",
			type: "STAIRS",
			portalGroupId: "HCM_STAIR_W",
			building: "Tòa HCM",
			floorOrder: 1,
			coords: uvToCoord(HCM_B, 0.22, 0.5),
		},
		{
			id: "hcm-1-stair-e",
			type: "STAIRS",
			portalGroupId: "HCM_STAIR_E",
			building: "Tòa HCM",
			floorOrder: 1,
			coords: uvToCoord(HCM_B, 0.86, 0.34),
		},
		{
			id: "hcm-1-cor-sw",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 1,
			coords: uvToCoord(HCM_B, 0.22, 0.72),
		},
		{
			id: "hcm-1-cor-sm",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 1,
			coords: uvToCoord(HCM_B, 0.44, 0.72),
		},
		{
			id: "hcm-1-cor-se",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 1,
			coords: uvToCoord(HCM_B, 0.79, 0.72),
		},
		{
			id: "hcm-1-cor-ne",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 1,
			coords: uvToCoord(HCM_B, 0.86, 0.52),
		},

		// Tòa HCM - Floor 2
		{
			id: "hcm-2-stair-w",
			type: "STAIRS",
			portalGroupId: "HCM_STAIR_W",
			building: "Tòa HCM",
			floorOrder: 2,
			coords: uvToCoord(HCM_B, 0.22, 0.5),
		},
		{
			id: "hcm-2-stair-e",
			type: "STAIRS",
			portalGroupId: "HCM_STAIR_E",
			building: "Tòa HCM",
			floorOrder: 2,
			coords: uvToCoord(HCM_B, 0.86, 0.34),
		},
		{
			id: "hcm-2-cor-sw",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 2,
			coords: uvToCoord(HCM_B, 0.22, 0.72),
		},
		{
			id: "hcm-2-cor-sm",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 2,
			coords: uvToCoord(HCM_B, 0.44, 0.72),
		},
		{
			id: "hcm-2-cor-se",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 2,
			coords: uvToCoord(HCM_B, 0.79, 0.72),
		},
		{
			id: "hcm-2-cor-ne",
			type: "CORRIDOR",
			building: "Tòa HCM",
			floorOrder: 2,
			coords: uvToCoord(HCM_B, 0.86, 0.52),
		},

		// Tòa Epsilon - Floor 0 (L1)
		{
			id: "eps-0-ent",
			type: "ENTRANCE",
			building: "Tòa Epsilon",
			floorOrder: 0,
			coords: uvToCoord(EPS_B, 0.468, 0.83),
		},
		{
			id: "eps-0-lobby",
			type: "CORRIDOR",
			building: "Tòa Epsilon",
			floorOrder: 0,
			coords: uvToCoord(EPS_B, 0.468, 0.695),
		},
		{
			id: "eps-0-cor-sw",
			type: "CORRIDOR",
			building: "Tòa Epsilon",
			floorOrder: 0,
			coords: uvToCoord(EPS_B, 0.308, 0.695),
		},
		{
			id: "eps-0-cor-se",
			type: "CORRIDOR",
			building: "Tòa Epsilon",
			floorOrder: 0,
			coords: uvToCoord(EPS_B, 0.618, 0.695),
		},
		{
			id: "eps-0-cor-nw",
			type: "CORRIDOR",
			building: "Tòa Epsilon",
			floorOrder: 0,
			coords: uvToCoord(EPS_B, 0.308, 0.245),
		},
		{
			id: "eps-0-cor-ne",
			type: "CORRIDOR",
			building: "Tòa Epsilon",
			floorOrder: 0,
			coords: uvToCoord(EPS_B, 0.618, 0.245),
		},
		{
			id: "eps-0-stair-th1",
			type: "STAIRS",
			portalGroupId: "EPS_THANG_TH1",
			building: "Tòa Epsilon",
			floorOrder: 0,
			coords: uvToCoord(EPS_B, 0.585, 0.605),
		},
		{
			id: "eps-0-stair-th2",
			type: "STAIRS",
			portalGroupId: "EPS_THANG_TH2",
			building: "Tòa Epsilon",
			floorOrder: 0,
			coords: uvToCoord(EPS_B, 0.285, 0.3),
		},

		// Tòa Epsilon - Floor 1 (LV2)
		{
			id: "eps-1-stair-th1",
			type: "STAIRS",
			portalGroupId: "EPS_THANG_TH1",
			building: "Tòa Epsilon",
			floorOrder: 1,
			coords: uvToCoord(EPS_B, 0.585, 0.605),
		},
		{
			id: "eps-1-stair-th2",
			type: "STAIRS",
			portalGroupId: "EPS_THANG_TH2",
			building: "Tòa Epsilon",
			floorOrder: 1,
			coords: uvToCoord(EPS_B, 0.285, 0.3),
		},
		{
			id: "eps-1-cor-sw",
			type: "CORRIDOR",
			building: "Tòa Epsilon",
			floorOrder: 1,
			coords: uvToCoord(EPS_B, 0.315, 0.71),
		},
		{
			id: "eps-1-cor-se",
			type: "CORRIDOR",
			building: "Tòa Epsilon",
			floorOrder: 1,
			coords: uvToCoord(EPS_B, 0.615, 0.71),
		},
		{
			id: "eps-1-cor-nw",
			type: "CORRIDOR",
			building: "Tòa Epsilon",
			floorOrder: 1,
			coords: uvToCoord(EPS_B, 0.315, 0.22),
		},
	];

	const navEdges = [
		["out-gate", "out-mid"],
		["out-mid", "hcm-0-ent-s"],
		["out-mid", "out-link"],
		["out-link", "hcm-0-ent-e"],
		["out-link", "out-eps"],
		["out-eps", "eps-0-ent"],

		// HCM Floor 0 (user corridor loop + connections to entrances & stairs)
		["node-1794307597801", "node-1794307602171"],
		["node-1794307597801", "node-1794307629249"],
		["node-1794307629249", "node-1794307634367"],
		["node-1794307629249", "node-1794307643227"],
		["node-1794307643227", "node-1794307645265"],
		["node-1794307645265", "node-1794307652919"],
		["node-1794307652919", "node-1794307656813"],
		["node-1794307656813", "node-1794307661173"],
		["node-1794307661173", "node-1794307662821"],
		["node-1794307662821", "node-1794307666981"],
		["node-1794307666981", "node-1794307696551"],
		["node-1794307696551", "node-1794307597801"],
		["hcm-0-stair-w", "node-1794307602171"],
		["hcm-0-stair-e", "node-1794307652919"],
		["hcm-0-ent-s", "node-1794307597801"],
		["hcm-0-ent-e", "node-1794307666981"],

		// HCM Floor 1
		["hcm-1-stair-w", "hcm-1-cor-sw"],
		["hcm-1-cor-sw", "hcm-1-cor-sm"],
		["hcm-1-cor-sm", "hcm-1-cor-se"],
		["hcm-1-cor-se", "hcm-1-cor-ne"],
		["hcm-1-cor-ne", "hcm-1-stair-e"],

		// HCM Floor 2
		["hcm-2-stair-w", "hcm-2-cor-sw"],
		["hcm-2-cor-sw", "hcm-2-cor-sm"],
		["hcm-2-cor-sm", "hcm-2-cor-se"],
		["hcm-2-cor-se", "hcm-2-cor-ne"],
		["hcm-2-cor-ne", "hcm-2-stair-e"],

		// Epsilon Floor 0
		["eps-0-ent", "eps-0-lobby"],
		["eps-0-lobby", "eps-0-cor-sw"],
		["eps-0-lobby", "eps-0-cor-se"],
		["eps-0-cor-sw", "eps-0-stair-th2"],
		["eps-0-stair-th2", "eps-0-cor-nw"],
		["eps-0-cor-se", "eps-0-stair-th1"],
		["eps-0-stair-th1", "eps-0-cor-ne"],

		// Epsilon Floor 1
		["eps-1-cor-sw", "eps-1-cor-se"],
		["eps-1-cor-sw", "eps-1-stair-th2"],
		["eps-1-stair-th2", "eps-1-cor-nw"],
		["eps-1-cor-se", "eps-1-stair-th1"],
	];

	const legacyV2 = {
		version: 2,
		floorCalibrations: {
			"Tòa HCM__0": { ...HCM_CALIBRATED_BOUNDS },
			"Tòa Epsilon__0": { ...EPS_CALIBRATED_BOUNDS },
		},
		buildingBoundaries,
		roomPolygons,
		customWalls: [],
		navNodes,
		navEdges,
	};

	return migrateStoreToWallGraph(legacyV2);
}

export function loadGeometryStore() {
	try {
		const raw = localStorage.getItem(STORAGE_KEY_3D_GEOMETRY);
		if (raw) {
			const parsed = JSON.parse(raw);
			if (parsed && (Array.isArray(parsed.walls) || Array.isArray(parsed.roomPolygons))) {
				return parsed.version >= 3 ? parsed : migrateStoreToWallGraph(parsed);
			}
		}
		for (const legacyKey of LEGACY_STORAGE_KEYS) {
			const oldRaw = localStorage.getItem(legacyKey);
			if (oldRaw) {
				const oldParsed = JSON.parse(oldRaw);
				if (oldParsed && Array.isArray(oldParsed.roomPolygons)) {
					return migrateStoreToWallGraph(oldParsed);
				}
			}
		}
	} catch (e) {
		console.warn("[3D Store] Failed to read localStorage, using defaults:", e);
	}
	return createDefaultGeometryStore();
}

export function saveGeometryStore(store) {
	try {
		localStorage.setItem(STORAGE_KEY_3D_GEOMETRY, JSON.stringify(store));
		window.dispatchEvent(new CustomEvent("icss-3d-geometry-updated"));
	} catch (e) {
		console.error("[3D Store] Failed to save geometry:", e);
	}
}

/**
 * Check if point [x, y] lies on or within tolerance of segment [ax, ay]-[bx, by].
 */
function isPointOnSegment(px, py, ax, ay, bx, by, eps = 1e-6) {
	const dx = bx - ax;
	const dy = by - ay;
	const lenSq = dx * dx + dy * dy;
	if (lenSq < 1e-14) {
		return Math.hypot(px - ax, py - ay) <= eps;
	}
	const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
	const projX = ax + t * dx;
	const projY = ay + t * dy;
	return Math.hypot(px - projX, py - projY) <= eps;
}

/**
 * Find the building boundary containing `coords` on `floorOrder`, or null if outside all boundaries.
 */
export function findBoundaryContainingPoint(boundaries, coords, floorOrder = 0) {
	if (!Array.isArray(boundaries) || !coords) return null;
	const targetOrder = Number(floorOrder) || 0;
	for (const b of boundaries) {
		if ((Number(b.floorOrder) || 0) !== targetOrder) continue;
		if (isPointInRing(coords, b.ring)) return b;
	}
	return null;
}

/**
 * Determine whether a navigation node is a vertical transition portal (STAIRS / ELEVATOR).
 */
export function isPortalNode(node) {
	return Boolean(node && (node.type === "STAIRS" || node.type === "ELEVATOR"));
}

/**
 * Geometric Outdoor inference: a Floor-0 node lying outside all drawn Floor-0 building boundaries
 * OR sitting inside a central `VOID` courtyard hole (e.g. Hồ Sen) is Outdoor.
 * Falls back safely when no Floor-0 boundaries exist yet for an arbitrary building.
 */
export function isNodeOutdoor(boundaries, node, precomputedModels = null) {
	if (!node || !Array.isArray(node.coords)) return false;
	if ((Number(node.floorOrder) || 0) !== 0) return false;
	const floor0Bounds = (boundaries || []).filter(
		(b) => (Number(b.floorOrder) || 0) === 0,
	);
	if (floor0Bounds.length === 0) {
		return node.type === "OUTDOOR" || node.building === "OUTDOOR";
	}
	const containingBound = findBoundaryContainingPoint(
		floor0Bounds,
		node.coords,
		0,
	);
	if (containingBound) {
		if (precomputedModels) {
			const cBldg =
				resolveCanonicalBuildingName(containingBound.building) ||
				containingBound.building;
			const model = precomputedModels.get(`${cBldg}__0`);
			if (
				model?.faces?.some(
					(f) =>
						f.role === "VOID" &&
						isPointInPolygonWithHoles(node.coords, f.ring, f.holes || []),
				)
			) {
				return true;
			}
		}
		return false;
	}
	if (node.building && node.building !== "OUTDOOR") {
		const hasBoundaryForBldg = floor0Bounds.some((b) =>
			matchesBuilding(b.building, node.building),
		);
		if (!hasBoundaryForBldg) return false;
	}
	return true;
}

/**
 * Infer effective building ownership ("OUTDOOR" vs canonical building name) from Floor-0 boundaries.
 */
export function resolveNodeBuilding(boundaries, node, precomputedModels = null) {
	if (!node) return "";
	if (isNodeOutdoor(boundaries, node, precomputedModels)) return "OUTDOOR";
	if ((Number(node.floorOrder) || 0) === 0) {
		const matched = findBoundaryContainingPoint(boundaries, node.coords, 0);
		if (matched?.building) return matched.building;
	}
	return node.building || "";
}

/**
 * Check whether a nav node belongs on the Studio canvas for (activeBuilding, activeFloorOrder).
 * Supports `"ALL"` wildcards for both building & floor while keeping outdoor courtyard nodes visible on Floor 0.
 */
export function isNodeVisibleOnFloor(
	boundaries,
	node,
	activeBuilding,
	activeFloorOrder,
	precomputedModels = null,
) {
	if (!node) return false;
	const nodeFloor = Number(node.floorOrder) || 0;
	const isAllFloors =
		activeFloorOrder === "ALL" ||
		activeFloorOrder === null ||
		activeFloorOrder === undefined;
	if (!isAllFloors && nodeFloor !== (Number(activeFloorOrder) || 0)) {
		return false;
	}
	const isAllBuildings = !activeBuilding || activeBuilding === "ALL";
	if (nodeFloor === 0 && isNodeOutdoor(boundaries, node, precomputedModels)) {
		return true;
	}
	if (isAllBuildings) return true;
	const ownerBldg = resolveNodeBuilding(boundaries, node, precomputedModels);
	return matchesBuilding(ownerBldg, activeBuilding);
}

/**
 * Build a 90-degree closed CCW polygon ring from opposite clicks `p0` and `p1`,
 * aligned parallel to the calibrated PNG `corners` ([NW, NE, SE, SW]) unless `axisAligned` is true.
 */
export function buildOrientedBoxRing(
	p0,
	p1,
	{ corners = null, axisAligned = false, minSideMeters = 0.25 } = {},
) {
	if (!Array.isArray(p0) || !Array.isArray(p1)) return null;
	const { toM, toDeg } = toLocalMetersFactory(p0);

	let uAxis = [1, 0];
	let vAxis = [0, 1];

	if (!axisAligned && Array.isArray(corners) && corners.length === 4) {
		const c0 = toM(corners[0]);
		const c1 = toM(corners[1]);
		const c2 = toM(corners[2]);
		const c3 = toM(corners[3]);
		const vx = ((c1[0] - c0[0]) + (c2[0] - c3[0])) / 2;
		const vy = ((c1[1] - c0[1]) + (c2[1] - c3[1])) / 2;
		const len = Math.hypot(vx, vy);
		if (len > 1e-4) {
			uAxis = [vx / len, vy / len];
			vAxis = [-uAxis[1], uAxis[0]];
		}
	}

	const d = toM(p1);
	const du = d[0] * uAxis[0] + d[1] * uAxis[1];
	const dv = d[0] * vAxis[0] + d[1] * vAxis[1];

	if (Math.abs(du) < minSideMeters || Math.abs(dv) < minSideMeters) {
		return null;
	}

	const ptsM = [
		[0, 0],
		[du * uAxis[0], du * uAxis[1]],
		[du * uAxis[0] + dv * vAxis[0], du * uAxis[1] + dv * vAxis[1]],
		[dv * vAxis[0], dv * vAxis[1]],
	];
	if (computeSignedArea2D(ptsM) < 0) {
		ptsM.reverse();
	}
	const ring = ptsM.map((m) => {
		const deg = toDeg(m);
		return [Number(deg[0].toFixed(6)), Number(deg[1].toFixed(6))];
	});
	ring.push([...ring[0]]);
	return ring;
}

/**
 * Screen-pixel CAD snap resolver following strict priority: VERTEX -> MIDPOINT -> EDGE.
 * Uses `projectFn([lng, lat]) => { x, y }` so trigger radii stay uniform in screen pixels across zooms.
 */
export function resolveBestSnapTarget({
	rawLngLat,
	projectFn,
	vertices = [],
	segments = [],
	excludeOwnerId = null,
	altKeyDown = false,
	vertexRadiusPx = 12,
	midpointRadiusPx = 10.5,
	edgeRadiusPx = 10,
}) {
	if (altKeyDown || !Array.isArray(rawLngLat) || typeof projectFn !== "function") {
		return null;
	}
	const rawPx = projectFn(rawLngLat);
	if (!rawPx || !Number.isFinite(rawPx.x) || !Number.isFinite(rawPx.y)) {
		return null;
	}

	// 1. VERTEX priority
	let bestVertex = null;
	let minVertexPx = vertexRadiusPx;
	for (const entry of vertices) {
		if (excludeOwnerId && entry.ownerId === excludeOwnerId) continue;
		const vPx = projectFn(entry.coord);
		if (!vPx || !Number.isFinite(vPx.x) || !Number.isFinite(vPx.y)) continue;
		const dPx = Math.hypot(vPx.x - rawPx.x, vPx.y - rawPx.y);
		if (dPx <= minVertexPx) {
			minVertexPx = dPx;
			bestVertex = entry;
		}
	}
	if (bestVertex) {
		return {
			coord: bestVertex.coord,
			type: "VERTEX",
			distPx: minVertexPx,
			ownerId: bestVertex.ownerId || null,
		};
	}

	// 2. MIDPOINT priority
	let bestMid = null;
	let minMidPx = midpointRadiusPx;
	for (const entry of segments) {
		if (excludeOwnerId && entry.ownerId === excludeOwnerId) continue;
		const [a, b] = entry.seg || [];
		if (!a || !b || Math.hypot(b[0] - a[0], b[1] - a[1]) < 1e-8) continue;
		const mid = [
			Number(((a[0] + b[0]) / 2).toFixed(7)),
			Number(((a[1] + b[1]) / 2).toFixed(7)),
		];
		const midPx = projectFn(mid);
		if (!midPx || !Number.isFinite(midPx.x) || !Number.isFinite(midPx.y)) {
			continue;
		}
		const dPx = Math.hypot(midPx.x - rawPx.x, midPx.y - rawPx.y);
		if (dPx <= minMidPx) {
			minMidPx = dPx;
			bestMid = { coord: mid, ownerId: entry.ownerId || null, edge: entry.edge || null };
		}
	}
	if (bestMid) {
		return {
			coord: bestMid.coord,
			type: "MIDPOINT",
			distPx: minMidPx,
			ownerId: bestMid.ownerId,
			edge: bestMid.edge,
		};
	}

	// 3. EDGE perpendicular projection priority
	let bestEdge = null;
	let minEdgePx = edgeRadiusPx;
	for (const entry of segments) {
		if (excludeOwnerId && entry.ownerId === excludeOwnerId) continue;
		const [a, b] = entry.seg || [];
		if (!a || !b) continue;
		const aPx = projectFn(a);
		const bPx = projectFn(b);
		if (
			!aPx ||
			!bPx ||
			!Number.isFinite(aPx.x) ||
			!Number.isFinite(bPx.x)
		) {
			continue;
		}
		const dx = bPx.x - aPx.x;
		const dy = bPx.y - aPx.y;
		const lenSq = dx * dx + dy * dy;
		if (lenSq < 1e-6) continue;
		const t = Math.max(
			0,
			Math.min(1, ((rawPx.x - aPx.x) * dx + (rawPx.y - aPx.y) * dy) / lenSq),
		);
		const projPx = { x: aPx.x + t * dx, y: aPx.y + t * dy };
		const dPx = Math.hypot(projPx.x - rawPx.x, projPx.y - rawPx.y);
		if (dPx <= minEdgePx) {
			minEdgePx = dPx;
			bestEdge = {
				coord: [
					Number((a[0] + t * (b[0] - a[0])).toFixed(7)),
					Number((a[1] + t * (b[1] - a[1])).toFixed(7)),
				],
				ownerId: entry.ownerId || null,
				edge: entry.edge || null,
			};
		}
	}
	if (bestEdge) {
		return {
			coord: bestEdge.coord,
			type: "EDGE",
			distPx: minEdgePx,
			ownerId: bestEdge.ownerId,
			edge: bestEdge.edge,
		};
	}

	return null;
}

/**
 * Strict ray-casting Point-in-Polygon check (returns false on boundary edges & outside).
 */
export function isPointInRingStrict(pt, ring) {
	if (!pt || !Array.isArray(ring) || ring.length < 3) return false;
	const [x, y] = pt;
	let inside = false;
	for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
		const xi = ring[i][0];
		const yi = ring[i][1];
		const xj = ring[j][0];
		const yj = ring[j][1];
		if (isPointOnSegment(x, y, xi, yi, xj, yj, 1e-7)) {
			return false;
		}
		const intersect =
			yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12) + xi;
		if (intersect) inside = !inside;
	}
	return inside;
}

/**
 * Hole-aware Point-in-Polygon check: inside `outerRing` AND NOT strictly inside any hole ring in `holes`.
 */
export function isPointInPolygonWithHoles(pt, outerRing, holes = []) {
	if (!isPointInRing(pt, outerRing)) return false;
	if (!Array.isArray(holes) || holes.length === 0) return true;
	return !holes.some((h) => isPointInRingStrict(pt, h));
}

/**
 * Compute an interior point guaranteed to fall strictly inside `outerRing` and outside any `holes`
 * via widest horizontal scanline chord (safe for U-shapes, L-shapes, and rings with VOID holes).
 */
export function getPolygonInteriorPoint(outerRing, holes = []) {
	if (!Array.isArray(outerRing) || outerRing.length < 3) {
		return [106.80988, 10.84113];
	}
	const allRings = [outerRing, ...(holes || [])];
	let minLat = Infinity;
	let maxLat = -Infinity;
	const latsSet = new Set();
	for (const p of outerRing) {
		if (p[1] < minLat) minLat = p[1];
		if (p[1] > maxLat) maxLat = p[1];
		latsSet.add(p[1]);
	}
	for (const h of holes || []) {
		for (const p of h) latsSet.add(p[1]);
	}

	const centroid = getRingCentroid(outerRing);
	if (
		(!holes || holes.length === 0) &&
		isPointInRingStrict(centroid, outerRing)
	) {
		return centroid;
	}

	const sortedLats = Array.from(latsSet).sort((a, b) => a - b);
	const sampleLats = [];
	for (let i = 0; i < sortedLats.length - 1; i++) {
		if (sortedLats[i + 1] - sortedLats[i] > 1e-9) {
			sampleLats.push((sortedLats[i] + sortedLats[i + 1]) / 2);
		}
	}
	for (let s = 1; s <= 12; s++) {
		sampleLats.push(minLat + ((maxLat - minLat) * s) / 13);
	}

	let bestPt = isPointInPolygonWithHoles(centroid, outerRing, holes)
		? centroid
		: null;
	let bestSpan = -1;

	for (const y of sampleLats) {
		const xs = [];
		for (const ring of allRings) {
			for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
				const yi = ring[i][1];
				const yj = ring[j][1];
				if ((yi > y) !== (yj > y)) {
					const xi = ring[i][0];
					const xj = ring[j][0];
					xs.push(xi + ((y - yi) * (xj - xi)) / (yj - yi));
				}
			}
		}
		xs.sort((a, b) => a - b);
		for (let k = 0; k + 1 < xs.length; k += 2) {
			const span = xs[k + 1] - xs[k];
			if (span <= bestSpan) continue;
			const cand = [
				Number(((xs[k] + xs[k + 1]) / 2).toFixed(7)),
				Number(y.toFixed(7)),
			];
			if (isPointInPolygonWithHoles(cand, outerRing, holes)) {
				bestSpan = span;
				bestPt = cand;
			}
		}
	}

	return bestPt || centroid;
}

/** Strict ray-casting in arbitrary local meter 2D array `[[x, y], ...]`. */
function isPointInRingStrict2D(pt, ring) {
	const [x, y] = pt;
	let inside = false;
	for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
		const xi = ring[i][0];
		const yi = ring[i][1];
		const xj = ring[j][0];
		const yj = ring[j][1];
		const dx = xj - xi;
		const dy = yj - yi;
		const l2 = dx * dx + dy * dy;
		if (l2 > 1e-12) {
			const t = Math.max(0, Math.min(1, ((x - xi) * dx + (y - yi) * dy) / l2));
			if (Math.hypot(x - (xi + t * dx), y - (yi + t * dy)) <= 0.015) {
				return false;
			}
		}
		const intersect =
			yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12) + xi;
		if (intersect) inside = !inside;
	}
	return inside;
}

function isPointInRingInclusive2D(pt, ring) {
	const [x, y] = pt;
	let inside = false;
	for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
		const xi = ring[i][0];
		const yi = ring[i][1];
		const xj = ring[j][0];
		const yj = ring[j][1];
		const dx = xj - xi;
		const dy = yj - yi;
		const l2 = dx * dx + dy * dy;
		if (l2 > 1e-12) {
			const t = Math.max(0, Math.min(1, ((x - xi) * dx + (y - yi) * dy) / l2));
			if (Math.hypot(x - (xi + t * dx), y - (yi + t * dy)) <= 0.025) {
				return true;
			}
		}
		const intersect =
			yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12) + xi;
		if (intersect) inside = !inside;
	}
	return inside;
}

function findInteriorPoint2D(outerRing, holes = []) {
	const allRings = [outerRing, ...holes];
	const lats = new Set();
	for (const r of allRings) for (const p of r) lats.add(p[1]);
	const sortedY = Array.from(lats).sort((a, b) => a - b);
	let bestPt = null;
	let bestSpan = -1;
	for (let i = 0; i < sortedY.length - 1; i++) {
		if (sortedY[i + 1] - sortedY[i] < 1e-4) continue;
		const y = (sortedY[i] + sortedY[i + 1]) / 2;
		const xs = [];
		for (const ring of allRings) {
			for (let a = 0, b = ring.length - 1; a < ring.length; b = a++) {
				const ya = ring[a][1];
				const yb = ring[b][1];
				if ((ya > y) !== (yb > y)) {
					xs.push(
						ring[a][0] + ((y - ya) * (ring[b][0] - ring[a][0])) / (yb - ya),
					);
				}
			}
		}
		xs.sort((a, b) => a - b);
		for (let k = 0; k + 1 < xs.length; k += 2) {
			const span = xs[k + 1] - xs[k];
			if (span > bestSpan) {
				const cand = [(xs[k] + xs[k + 1]) / 2, y];
				if (
					isPointInRingStrict2D(cand, outerRing) &&
					!holes.some((h) => isPointInRingStrict2D(cand, h))
				) {
					bestSpan = span;
					bestPt = cand;
				}
			}
		}
	}
	if (bestPt) return bestPt;
	let sx = 0;
	let sy = 0;
	for (const p of outerRing) {
		sx += p[0];
		sy += p[1];
	}
	return [sx / outerRing.length, sy / outerRing.length];
}

/**
 * Node raw local-meter segments by snapping close vertices, inserting T-junctions & X-intersections,
 * splitting collinear overlaps, and deduplicating edges.
 */
function nodeSegmentsInMeters(rawSegmentsM, snapTol = 0.08) {
	const verts = [];
	const addOrGetVertex = (x, y, tol = snapTol) => {
		let bestIdx = -1;
		let bestD = tol;
		for (let i = 0; i < verts.length; i++) {
			const d = Math.hypot(verts[i][0] - x, verts[i][1] - y);
			if (d <= bestD) {
				bestD = d;
				bestIdx = i;
			}
		}
		if (bestIdx >= 0) return verts[bestIdx];
		const pt = [x, y];
		verts.push(pt);
		return pt;
	};

	const segs = [];
	for (const [p1, p2] of rawSegmentsM) {
		if (!p1 || !p2) continue;
		const a = addOrGetVertex(p1[0], p1[1], snapTol);
		const b = addOrGetVertex(p2[0], p2[1], snapTol);
		if (a !== b && Math.hypot(b[0] - a[0], b[1] - a[1]) > 0.02) {
			segs.push([a, b]);
		}
	}

	// Snap vertex-to-segment near-touches (`snapTol`) before computing clean segment-segment intersections
	for (let i = 0; i < verts.length; i++) {
		const v = verts[i];
		for (const [a, b] of segs) {
			if (v === a || v === b) continue;
			const dx = b[0] - a[0];
			const dy = b[1] - a[1];
			const l2 = dx * dx + dy * dy;
			if (l2 < 1e-6) continue;
			const t = ((v[0] - a[0]) * dx + (v[1] - a[1]) * dy) / l2;
			if (t <= 0 || t >= 1) continue;
			const px = a[0] + t * dx;
			const py = a[1] + t * dy;
			if (Math.hypot(v[0] - px, v[1] - py) <= snapTol) {
				v[0] = px;
				v[1] = py;
			}
		}
	}

	// Segment-segment crossings
	for (let i = 0; i < segs.length; i++) {
		const [a, b] = segs[i];
		const rX = b[0] - a[0];
		const rY = b[1] - a[1];
		for (let j = i + 1; j < segs.length; j++) {
			const [c, d] = segs[j];
			const sX = d[0] - c[0];
			const sY = d[1] - c[1];
			const denom = rX * sY - rY * sX;
			if (Math.abs(denom) < 1e-8) continue;
			const qpx = c[0] - a[0];
			const qpy = c[1] - a[1];
			const t = (qpx * sY - qpy * sX) / denom;
			const u = (qpx * rY - qpy * rX) / denom;
			if (t >= -1e-4 && t <= 1 + 1e-4 && u >= -1e-4 && u <= 1 + 1e-4) {
				addOrGetVertex(a[0] + t * rX, a[1] + t * rY, Math.max(snapTol, 0.04));
			}
		}
	}

	// Gather vertices on every segment and split ascending by projection parameter `t`
	const edgeSet = new Set();
	const edges = [];
	const vIndexMap = new Map(verts.map((v, idx) => [v, idx]));

	for (const [a, b] of segs) {
		const dx = b[0] - a[0];
		const dy = b[1] - a[1];
		const len = Math.hypot(dx, dy);
		if (len < 0.02) continue;
		const l2 = len * len;
		const onSeg = [];
		for (let idx = 0; idx < verts.length; idx++) {
			const v = verts[idx];
			const t = ((v[0] - a[0]) * dx + (v[1] - a[1]) * dy) / l2;
			if (t < -0.05 / len || t > 1 + 0.05 / len) continue;
			const perp = Math.abs((v[0] - a[0]) * dy - (v[1] - a[1]) * dx) / len;
			if (perp <= Math.max(snapTol + 0.01, 0.03)) {
				onSeg.push({ idx, t });
			}
		}
		onSeg.sort((p, q) => p.t - q.t);
		for (let k = 0; k < onSeg.length - 1; k++) {
			const u = onSeg[k].idx;
			const v = onSeg[k + 1].idx;
			if (u === v) continue;
			if (
				Math.hypot(verts[v][0] - verts[u][0], verts[v][1] - verts[u][1]) < 0.02
			) {
				continue;
			}
			const key = u < v ? `${u}__${v}` : `${v}__${u}`;
			if (!edgeSet.has(key)) {
				edgeSet.add(key);
				edges.push([u, v]);
			}
		}
	}

	void vIndexMap;
	return { verts, edges };
}

/**
 * Walk minimal DCEL planar faces on noded vertices & edges in local meters,
 * identify perimeter outer envelope vs inner holes, and prune degree-1 whiskers cleanly during cycle walk.
 */
function extractPlanarCyclesInMeters(verts, edges) {
	const deg = new Array(verts.length).fill(0);
	const adjInc = Array.from({ length: verts.length }, () => []);
	edges.forEach(([u, v], eIdx) => {
		deg[u]++;
		deg[v]++;
		adjInc[u].push({ to: v, eIdx });
		adjInc[v].push({ to: u, eIdx });
	});

	// Iteratively peel degree-1 dead-end spurs on cycle extraction mask
	const activeEdge = new Array(edges.length).fill(true);
	const queue = [];
	const deadEndVerts = [];
	for (let i = 0; i < verts.length; i++) {
		if (deg[i] === 1) {
			queue.push(i);
			deadEndVerts.push(verts[i]);
		}
	}
	let qHead = 0;
	while (qHead < queue.length) {
		const u = queue[qHead++];
		for (const item of adjInc[u]) {
			if (!activeEdge[item.eIdx]) continue;
			activeEdge[item.eIdx] = false;
			deg[u]--;
			deg[item.to]--;
			if (deg[item.to] === 1) {
				queue.push(item.to);
			}
		}
	}

	const closedEdgeIndices = [];
	for (let i = 0; i < edges.length; i++) {
		if (activeEdge[i]) closedEdgeIndices.push(i);
	}

	// Half-edge table: heId = 2*k (u->v), 2*k+1 (v->u)
	const heData = [];
	const outStar = Array.from({ length: verts.length }, () => []);
	for (const eIdx of closedEdgeIndices) {
		const [u, v] = edges[eIdx];
		const hFwd = heData.length;
		heData.push({
			u,
			v,
			eIdx,
			angle: Math.atan2(verts[v][1] - verts[u][1], verts[v][0] - verts[u][0]),
			twin: hFwd + 1,
		});
		const hRev = heData.length;
		heData.push({
			u: v,
			v: u,
			eIdx,
			angle: Math.atan2(verts[u][1] - verts[v][1], verts[u][0] - verts[v][0]),
			twin: hFwd,
		});
		outStar[u].push(hFwd);
		outStar[v].push(hRev);
	}

	const heRankInOutStar = new Int32Array(heData.length);
	for (let v = 0; v < verts.length; v++) {
		outStar[v].sort((a, b) => heData[a].angle - heData[b].angle);
		outStar[v].forEach((heId, idx) => {
			heRankInOutStar[heId] = idx;
		});
	}

	const nextHe = new Int32Array(heData.length);
	for (let heId = 0; heId < heData.length; heId++) {
		const twinId = heData[heId].twin;
		const dest = heData[heId].v;
		const star = outStar[dest];
		const twinRank = heRankInOutStar[twinId];
		nextHe[heId] = star[(twinRank - 1 + star.length) % star.length];
	}

	const visited = new Uint8Array(heData.length);
	const ccwFaces = [];
	const cwLoops = [];
	const maxSteps = heData.length + 4;

	for (let startHe = 0; startHe < heData.length; startHe++) {
		if (visited[startHe]) continue;
		const cycleHes = [];
		const ringM = [];
		let curr = startHe;
		let guard = 0;
		while (!visited[curr] && guard++ < maxSteps) {
			visited[curr] = 1;
			cycleHes.push(curr);
			ringM.push(verts[heData[curr].u]);
			curr = nextHe[curr];
			if (curr === startHe) break;
		}
		if (curr !== startHe || ringM.length < 3) continue;
		const signedArea = computeSignedArea2D(ringM);
		if (signedArea > 0.25) {
			ccwFaces.push({
				ringM,
				holesM: [],
				area: signedArea,
				edgeSet: new Set(cycleHes.map((h) => heData[h].eIdx)),
			});
		} else if (signedArea < -0.25) {
			cwLoops.push({
				ringM,
				area: signedArea,
				hes: cycleHes,
			});
		}
	}

	ccwFaces.sort((a, b) => a.area - b.area);
	const perimeterEdgeIndices = new Set();
	const exteriorLoopsM = [];

	for (const cw of cwLoops) {
		// Treat CW cycle as positive polygon cleanly for finding an interior test point
		const testPoly = [...cw.ringM].reverse();
		const innerPt = findInteriorPoint2D(testPoly, []);
		const cwMag = Math.abs(cw.area);
		let enclosingFace = null;
		for (const f of ccwFaces) {
			if (f.area > cwMag + 0.1 && isPointInRingStrict2D(innerPt, f.ringM)) {
				enclosingFace = f;
				break;
			}
		}
		if (enclosingFace) {
			enclosingFace.holesM.push(cw.ringM);
		} else {
			exteriorLoopsM.push(testPoly);
			for (const h of cw.hes) {
				perimeterEdgeIndices.add(heData[h].eIdx);
			}
		}
	}

	return { ccwFaces, perimeterEdgeIndices, deadEndVerts, exteriorLoopsM };
}

/**
 * Clip a polygon ring (`subjRing`) strictly inside an arbitrary (convex or concave) `boundaryRing`.
 * Optional `snapToBoundaryMeters` snaps points slightly inside perimeter flush against the outer wall.
 */
export function clipPolygonToBoundary(
	subjRing,
	boundaryRing,
	{ snapToBoundaryMeters = 0 } = {},
) {
	if (
		!Array.isArray(subjRing) ||
		subjRing.length < 4 ||
		!Array.isArray(boundaryRing) ||
		boundaryRing.length < 4
	) {
		return null;
	}
	const { toM, toDeg } = toLocalMetersFactory(boundaryRing[0]);
	const bOpen = isClosedRingLocal(boundaryRing)
		? boundaryRing.slice(0, -1).map(toM)
		: boundaryRing.map(toM);
	const sRaw = isClosedRingLocal(subjRing)
		? subjRing.slice(0, -1).map(toM)
		: subjRing.map(toM);

	const sOpen = sRaw.map((p) => {
		if (snapToBoundaryMeters <= 0) return [...p];
		let bestPt = [...p];
		let bestD = snapToBoundaryMeters;
		for (let i = 0; i < bOpen.length; i++) {
			const a = bOpen[i];
			const b = bOpen[(i + 1) % bOpen.length];
			const dx = b[0] - a[0];
			const dy = b[1] - a[1];
			const l2 = dx * dx + dy * dy;
			if (l2 < 1e-8) continue;
			const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2));
			const proj = [a[0] + t * dx, a[1] + t * dy];
			const d = Math.hypot(p[0] - proj[0], p[1] - proj[1]);
			if (d <= bestD) {
				bestD = d;
				bestPt = proj;
			}
		}
		return bestPt;
	});

	const rawSegmentsM = [];
	for (let i = 0; i < bOpen.length; i++) {
		rawSegmentsM.push([bOpen[i], bOpen[(i + 1) % bOpen.length]]);
	}
	for (let i = 0; i < sOpen.length; i++) {
		rawSegmentsM.push([sOpen[i], sOpen[(i + 1) % sOpen.length]]);
	}

	const { verts, edges } = nodeSegmentsInMeters(rawSegmentsM, 0.04);
	const { ccwFaces } = extractPlanarCyclesInMeters(verts, edges);

	let bestFace = null;
	let bestArea = 0.25;
	for (const f of ccwFaces) {
		const pt = findInteriorPoint2D(f.ringM, f.holesM);
		if (
			isPointInRingInclusive2D(pt, sOpen) &&
			isPointInRingInclusive2D(pt, bOpen) &&
			f.area > bestArea
		) {
			bestArea = f.area;
			bestFace = f;
		}
	}
	if (!bestFace) return null;

	const geoRing = bestFace.ringM.map((m) => {
		const d = toDeg(m);
		return [Number(d[0].toFixed(6)), Number(d[1].toFixed(6))];
	});
	const cleaned = cleanRing(geoRing);
	return cleaned && cleaned.length >= 4 ? cleaned : null;
}

function isClosedRingLocal(ring) {
	if (!Array.isArray(ring) || ring.length < 2) return false;
	return (
		Math.abs(ring[0][0] - ring[ring.length - 1][0]) < 1e-8 &&
		Math.abs(ring[0][1] - ring[ring.length - 1][1]) < 1e-8
	);
}

/**
 * Mappedin Wall Graph -> Planar Face Extraction (handles T/X-junctions, nested VOID holes & Perimeter/Interior heights).
 */
export function extractPlanarFacesFromWalls(
	wallItems = [],
	faceAssignments = [],
	{ snapToleranceMeters = 0.08 } = {},
) {
	if (!Array.isArray(wallItems) || wallItems.length === 0) {
		return { wallSegments: [], faces: [], deadEndPoints: [] };
	}

	let origin = null;
	for (const w of wallItems) {
		if (Array.isArray(w.line) && w.line.length >= 2) {
			origin = w.line[0];
			break;
		}
	}
	if (!origin) {
		return { wallSegments: [], faces: [], deadEndPoints: [] };
	}

	const { toM, toDeg } = toLocalMetersFactory(origin);
	const rawSegmentsM = [];
	for (const w of wallItems) {
		const pts = w.line || [];
		for (let i = 0; i < pts.length - 1; i++) {
			rawSegmentsM.push([toM(pts[i]), toM(pts[i + 1])]);
		}
	}

	const { verts, edges } = nodeSegmentsInMeters(rawSegmentsM, snapToleranceMeters);
	const { ccwFaces, perimeterEdgeIndices, deadEndVerts, exteriorLoopsM } =
		extractPlanarCyclesInMeters(verts, edges);

	const toClosedDeg = (ringM, makeCW = false) => {
		const arr = [...ringM];
		const sa = computeSignedArea2D(arr);
		if (makeCW && sa > 0) arr.reverse();
		if (!makeCW && sa < 0) arr.reverse();
		const deg = arr.map((m) => {
			const c = toDeg(m);
			return [Number(c[0].toFixed(6)), Number(c[1].toFixed(6))];
		});
		if (deg.length > 0) deg.push([...deg[0]]);
		return deg;
	};

	const exteriorRings = (exteriorLoopsM || []).map((rm) => toClosedDeg(rm, false));

	const faces = ccwFaces.map((f, idx) => {
		const ring = toClosedDeg(f.ringM, false);
		const holes = f.holesM.map((hm) => toClosedDeg(hm, true));
		const matchedRole = (faceAssignments || []).find((fa) =>
			fa?.seedCoord
				? isPointInPolygonWithHoles(fa.seedCoord, ring, holes)
				: false,
		);
		const seedCoord = getPolygonInteriorPoint(ring, holes);
		const building = wallItems[0]?.building || "";
		const floorOrder = Number(wallItems[0]?.floorOrder) || 0;
		return {
			id: `face-${building}-${floorOrder}-${idx}-${Math.round(seedCoord[0] * 1e6)}`,
			role: matchedRole?.role || "CORRIDOR",
			ring,
			holes,
			seedCoord,
			areaSqm: Number(f.area.toFixed(2)),
			building,
			floorOrder,
		};
	});

	const wallSegments = edges.map(([u, v], eIdx) => {
		const ca = toDeg(verts[u]);
		const cb = toDeg(verts[v]);
		return {
			id: `seg-${eIdx}`,
			a: [Number(ca[0].toFixed(6)), Number(ca[1].toFixed(6))],
			b: [Number(cb[0].toFixed(6)), Number(cb[1].toFixed(6))],
			isPerimeter: perimeterEdgeIndices.has(eIdx),
			building: wallItems[0]?.building || "",
			floorOrder: Number(wallItems[0]?.floorOrder) || 0,
		};
	});

	const deadEndPoints = deadEndVerts.map((m) => {
		const c = toDeg(m);
		return [Number(c[0].toFixed(6)), Number(c[1].toFixed(6))];
	});

	return { wallSegments, faces, deadEndPoints, exteriorRings };
}

/**
 * Carve real doorway intervals (`widthMeters`, default 1.2m) out of unified Wall Graph segments
 * so 3D extrusion renders true open passageways without splitting the underlying editable polyline.
 */
export function buildWallsWithDoorCutouts(wallSegments = [], doors = []) {
	if (!Array.isArray(wallSegments) || wallSegments.length === 0) return [];
	if (!Array.isArray(doors) || doors.length === 0) {
		return wallSegments.map((seg) => ({
			...seg,
			startIsDoorJamb: false,
			endIsDoorJamb: false,
		}));
	}

	const result = [];
	for (const seg of wallSegments) {
		const { toM, toDeg } = toLocalMetersFactory(seg.a);
		const pA = [0, 0];
		const pB = toM(seg.b);
		const segLen = Math.hypot(pB[0] - pA[0], pB[1] - pA[1]);
		if (segLen < 0.05) continue;
		const lenSq = segLen * segLen;

		const cuts = [];
		for (const door of doors) {
			if (!Array.isArray(door?.coords)) continue;
			const dM = toM(door.coords);
			const t = (dM[0] * pB[0] + dM[1] * pB[1]) / lenSq;
			if (t < -0.1 || t > 1.1) continue;
			const projX = t * pB[0];
			const projY = t * pB[1];
			const dist = Math.hypot(dM[0] - projX, dM[1] - projY);
			if (dist <= 0.35) {
				const halfW = (Number(door.widthMeters) || 1.2) / 2;
				const dt = halfW / segLen;
				const t0 = Math.max(0, t - dt);
				const t1 = Math.min(1, t + dt);
				if (t1 - t0 > 0.01) {
					cuts.push([t0, t1]);
				}
			}
		}

		if (cuts.length === 0) {
			result.push({
				...seg,
				startIsDoorJamb: false,
				endIsDoorJamb: false,
			});
			continue;
		}

		cuts.sort((c1, c2) => c1[0] - c2[0]);
		const merged = [];
		for (const c of cuts) {
			const last = merged[merged.length - 1];
			if (last && c[0] <= last[1]) {
				last[1] = Math.max(last[1], c[1]);
			} else {
				merged.push([...c]);
			}
		}

		let cursor = 0;
		for (const [cStart, cEnd] of merged) {
			if (cStart - cursor > 0.03 / segLen) {
				const subA = toDeg([cursor * pB[0], cursor * pB[1]]);
				const subB = toDeg([cStart * pB[0], cStart * pB[1]]);
				result.push({
					...seg,
					a: cursor === 0 ? seg.a : [Number(subA[0].toFixed(6)), Number(subA[1].toFixed(6))],
					b: [Number(subB[0].toFixed(6)), Number(subB[1].toFixed(6))],
					startIsDoorJamb: cursor > 0,
					endIsDoorJamb: true,
				});
			}
			cursor = cEnd;
		}
		if (1 - cursor > 0.03 / segLen) {
			const subA = toDeg([cursor * pB[0], cursor * pB[1]]);
			result.push({
				...seg,
				a: [Number(subA[0].toFixed(6)), Number(subA[1].toFixed(6))],
				b: seg.b,
				startIsDoorJamb: true,
				endIsDoorJamb: false,
			});
		}
	}

	return result;
}

/**
 * Intersect a 2-point Slice segment against faces on the current floor so `[S]` acts directly as a Wall Graph divider macro.
 */
export function buildSliceWallSegment(faces = [], p1, p2) {
	if (!p1 || !p2) return null;
	const { toM, toDeg } = toLocalMetersFactory(p1);
	const aM = [0, 0];
	const bM = toM(p2);
	const dx = bM[0] - aM[0];
	const dy = bM[1] - aM[1];
	const len = Math.hypot(dx, dy);
	if (len < 0.2) return null;

	for (const face of faces) {
		const ringM = (isClosedRingLocal(face.ring)
			? face.ring.slice(0, -1)
			: face.ring
		).map(toM);
		if (ringM.length < 3) continue;

		const hits = [];
		for (let i = 0; i < ringM.length; i++) {
			const c = ringM[i];
			const d = ringM[(i + 1) % ringM.length];
			const sX = d[0] - c[0];
			const sY = d[1] - c[1];
			const denom = dx * sY - dy * sX;
			if (Math.abs(denom) < 1e-8) continue;
			const qpx = c[0];
			const qpy = c[1];
			const t = (qpx * sY - qpy * sX) / denom;
			const u = (qpx * dy - qpy * dx) / denom;
			if (u >= -1e-4 && u <= 1 + 1e-4 && t >= -0.3 && t <= 1.3) {
				if (!hits.some((h) => Math.abs(h.t - t) * len < 0.15)) {
					hits.push({ t, pt: [t * dx, t * dy] });
				}
			}
		}

		if (hits.length === 2) {
			hits.sort((h1, h2) => h1.t - h2.t);
			const mid = [
				(hits[0].pt[0] + hits[1].pt[0]) / 2,
				(hits[0].pt[1] + hits[1].pt[1]) / 2,
			];
			if (!isPointInRingInclusive2D(mid, ringM)) continue;
			const g0 = toDeg(hits[0].pt);
			const g1 = toDeg(hits[1].pt);
			const pStart = [Number(g0[0].toFixed(6)), Number(g0[1].toFixed(6))];
			const pEnd = [Number(g1[0].toFixed(6)), Number(g1[1].toFixed(6))];
			const half1 = toDeg([(hits[0].pt[0] + mid[0]) / 2, (hits[0].pt[1] + mid[1]) / 2]);
			const half2 = toDeg([(hits[1].pt[0] + mid[0]) / 2, (hits[1].pt[1] + mid[1]) / 2]);
			return {
				line: [pStart, pEnd],
				targetRole: face.role || "ROOM",
				seedA: [Number(half1[0].toFixed(6)), Number(half1[1].toFixed(6))],
				seedB: [Number(half2[0].toFixed(6)), Number(half2[1].toFixed(6))],
			};
		}
	}

	return null;
}

const wallGraphModelCache = new WeakMap();

/**
 * Derive all floor-level Wall Graph topological models & synchronized compatibility shapes cleanly.
 */
export function buildFloorWallGraphModels(store) {
	const data = migrateStoreToWallGraph(store || {});
	if (Array.isArray(data.walls)) {
		const cached = wallGraphModelCache.get(data.walls);
		if (
			cached &&
			cached.faceAssignments === data.faceAssignments &&
			cached.doors === data.doors &&
			cached.wallLen === data.walls.length
		) {
			return cached.result;
		}
	}
	const floorMap = new Map();

	for (const w of data.walls || []) {
		const bldg = resolveCanonicalBuildingName(w.building) || w.building || "";
		const fl = Number(w.floorOrder) || 0;
		const key = `${bldg}__${fl}`;
		if (!floorMap.has(key)) {
			floorMap.set(key, { building: bldg, floorOrder: fl, walls: [], faceAssignments: [], doors: [] });
		}
		floorMap.get(key).walls.push(w);
	}

	for (const fa of data.faceAssignments || []) {
		const bldg = resolveCanonicalBuildingName(fa.building) || fa.building || "";
		const fl = Number(fa.floorOrder) || 0;
		const key = `${bldg}__${fl}`;
		if (floorMap.has(key)) floorMap.get(key).faceAssignments.push(fa);
	}

	for (const d of data.doors || []) {
		const bldg = resolveCanonicalBuildingName(d.building) || d.building || "";
		const fl = Number(d.floorOrder) || 0;
		const key = `${bldg}__${fl}`;
		if (floorMap.has(key)) floorMap.get(key).doors.push(d);
	}

	const result = new Map();
	for (const [key, entry] of floorMap.entries()) {
		const extracted = extractPlanarFacesFromWalls(entry.walls, entry.faceAssignments);
		result.set(key, {
			building: entry.building,
			floorOrder: entry.floorOrder,
			wallSegments: extracted.wallSegments,
			faces: extracted.faces,
			deadEndPoints: extracted.deadEndPoints,
			exteriorRings: extracted.exteriorRings || [],
			doors: entry.doors,
		});
	}
	if (Array.isArray(data.walls)) {
		wallGraphModelCache.set(data.walls, {
			faceAssignments: data.faceAssignments,
			doors: data.doors,
			wallLen: data.walls.length,
			result,
		});
	}
	return result;
}

/**
 * Migrate any legacy v2 store (buildingBoundaries + roomPolygons + customWalls) into unified Wall Graph v3.
 * Auto-clips overflowing starter rooms onto buildingBoundaries and converts Floor-0 ENTRANCE nodes into DOORs.
 */
export function migrateStoreToWallGraph(rawStore) {
	if (!rawStore || typeof rawStore !== "object") {
		return createDefaultGeometryStore();
	}
	if (Number(rawStore.version) >= 3 && Array.isArray(rawStore.walls)) {
		return rawStore;
	}

	const boundaries = rawStore.buildingBoundaries || [];
	const oldRooms = rawStore.roomPolygons || [];
	const oldWalls = rawStore.customWalls || [];
	const oldNodes = rawStore.navNodes || [];
	const oldEdges = rawStore.navEdges || [];

	const walls = [];
	const faceAssignments = [];
	const doors = [...(rawStore.doors || [])];

	// Group floors across boundaries + rooms + customWalls
	const floorKeys = new Set();
	const keyFor = (b, fl) => `${resolveCanonicalBuildingName(b) || b}__${Number(fl) || 0}`;
	boundaries.forEach((b) => floorKeys.add(keyFor(b.building, b.floorOrder)));
	oldRooms.forEach((r) => floorKeys.add(keyFor(r.building, r.floorOrder)));
	oldWalls.forEach((w) => floorKeys.add(keyFor(w.building, w.floorOrder)));

	const clippedRooms = [];

	for (const fk of floorKeys) {
		const [bldg, flStr] = fk.split("__");
		const fl = Number(flStr) || 0;
		const floorBound = boundaries.find(
			(b) => matchesBuilding(b.building, bldg) && Number(b.floorOrder) === fl,
		);
		if (floorBound?.ring?.length >= 4) {
			walls.push({
				id: `wall-bound-${bldg}-${fl}`,
				building: bldg,
				floorOrder: fl,
				line: floorBound.ring.map((p) => [...p]),
			});
		}

		const floorRooms = oldRooms.filter(
			(r) => matchesBuilding(r.building, bldg) && Number(r.floorOrder) === fl,
		);
		for (const rm of floorRooms) {
			const cleanRmRing = floorBound
				? clipPolygonToBoundary(rm.ring, floorBound.ring, { snapToBoundaryMeters: 0.22 }) || rm.ring
				: rm.ring;
			if (!cleanRmRing || cleanRmRing.length < 4) continue;
			clippedRooms.push({ ...rm, building: bldg, floorOrder: fl, ring: cleanRmRing });
			const pts = isClosedRingLocal(cleanRmRing)
				? cleanRmRing.slice(0, -1)
				: cleanRmRing;
			for (let i = 0; i < pts.length; i++) {
				walls.push({
					id: `wall-${rm.id}-${i}`,
					building: bldg,
					floorOrder: fl,
					line: [[...pts[i]], [...pts[(i + 1) % pts.length]]],
				});
			}
			faceAssignments.push({
				id: `fa-${rm.id}`,
				building: bldg,
				floorOrder: fl,
				seedCoord: getPolygonInteriorPoint(cleanRmRing),
				role: "ROOM",
			});
		}

		const floorCustom = oldWalls.filter(
			(w) => matchesBuilding(w.building, bldg) && Number(w.floorOrder) === fl,
		);
		for (const cw of floorCustom) {
			if (Array.isArray(cw.line) && cw.line.length >= 2) {
				walls.push({
					id: cw.id || `wall-c-${Date.now()}`,
					building: bldg,
					floorOrder: fl,
					line: cw.line.map((p) => [...p]),
				});
			}
		}
	}

	// Deduplicate atomic segments cleanly per floor via planar noder so `walls` contains zero stacked duplicates
	const mergedWalls = [];
	const byFloor = new Map();
	for (const w of walls) {
		const fk = `${w.building}__${w.floorOrder}`;
		if (!byFloor.has(fk)) byFloor.set(fk, []);
		byFloor.get(fk).push(w);
	}
	for (const [, flWalls] of byFloor.entries()) {
		const ext = extractPlanarFacesFromWalls(flWalls, faceAssignments);
		ext.wallSegments.forEach((seg, idx) => {
			mergedWalls.push({
				id: `w-${seg.building}-${seg.floorOrder}-${idx}`,
				building: seg.building,
				floorOrder: seg.floorOrder,
				line: [seg.a, seg.b],
			});
		});
	}

	// Convert legacy Floor-0 ENTRANCE nodes onto nearest perimeter walls as starter DOORs
	const navNodes = oldNodes.map((n) => ({ ...n }));
	if (doors.length === 0) {
		const nodeMap = new Map(navNodes.map((n) => [n.id, n]));
		for (const ent of navNodes.filter((n) => n.type === "ENTRANCE")) {
			const bldg = resolveCanonicalBuildingName(ent.building) || ent.building;
			const flBound = boundaries.find(
				(b) => matchesBuilding(b.building, bldg) && Number(b.floorOrder) === 0,
			);
			if (!flBound?.ring) continue;

			// Find neighbor outdoor edge crossing boundary wall first for cleanest gate alignment
			let doorCoord = null;
			for (const [uId, vId] of oldEdges) {
				const otherId = uId === ent.id ? vId : vId === ent.id ? uId : null;
				if (!otherId) continue;
				const other = nodeMap.get(otherId);
				if (other && (other.type === "OUTDOOR" || other.building === "OUTDOOR")) {
					const { toM, toDeg } = toLocalMetersFactory(other.coords);
					const aM = [0, 0];
					const bM = toM(ent.coords);
					const bRingM = flBound.ring.map(toM);
					for (let i = 0; i < bRingM.length - 1; i++) {
						const c = bRingM[i];
						const d = bRingM[i + 1];
						const rX = bM[0] - aM[0];
						const rY = bM[1] - aM[1];
						const sX = d[0] - c[0];
						const sY = d[1] - c[1];
						const denom = rX * sY - rY * sX;
						if (Math.abs(denom) < 1e-8) continue;
						const t = (c[0] * sY - c[1] * sX) / denom;
						const u = (c[0] * rY - c[1] * rX) / denom;
						if (t >= 0 && t <= 1.25 && u >= 0 && u <= 1) {
							const deg = toDeg([t * rX, t * rY]);
							doorCoord = [Number(deg[0].toFixed(6)), Number(deg[1].toFixed(6))];
							break;
						}
					}
					if (doorCoord) break;
				}
			}
			if (!doorCoord) {
				// Orthogonal fallback projection onto floorBound
				const pts = flBound.ring;
				let bestD = Infinity;
				for (let i = 0; i < pts.length - 1; i++) {
					const a = pts[i];
					const b = pts[i + 1];
					const dx = b[0] - a[0];
					const dy = b[1] - a[1];
					const l2 = dx * dx + dy * dy;
					if (l2 < 1e-14) continue;
					const t = Math.max(0, Math.min(1, ((ent.coords[0] - a[0]) * dx + (ent.coords[1] - a[1]) * dy) / l2));
					const proj = [a[0] + t * dx, a[1] + t * dy];
					const d = Math.hypot(ent.coords[0] - proj[0], ent.coords[1] - proj[1]);
					if (d < bestD) {
						bestD = d;
						doorCoord = [Number(proj[0].toFixed(6)), Number(proj[1].toFixed(6))];
					}
				}
			}
			if (doorCoord) {
				doors.push({
					id: `door-${ent.id}`,
					building: bldg,
					floorOrder: 0,
					coords: doorCoord,
					widthMeters: 1.8,
				});
			}
		}
	}

	const nextStore = {
		version: 3,
		floorCalibrations: rawStore.floorCalibrations || {},
		walls: mergedWalls,
		faceAssignments,
		doors,
		buildingBoundaries: boundaries,
		roomPolygons: clippedRooms,
		navNodes,
		navEdges: oldEdges,
	};

	return synchronizeCompatibilityGeometry(nextStore);
}

/**
 * Derive compatibility arrays (`roomPolygons`, `buildingBoundaries`) directly from unified `walls` + `faceAssignments`
 * so downstream consumers keep synchronized polygon boundaries automatically after any Wall/Slice/Box/Face edit.
 */
export function synchronizeCompatibilityGeometry(store) {
	if (!store || !Array.isArray(store.walls)) return store;
	const models = buildFloorWallGraphModels({
		...store,
		version: 3,
	});
	const roomPolygons = [];
	const buildingBoundaries = [];

	for (const model of models.values()) {
		if (Array.isArray(model.exteriorRings) && model.exteriorRings.length > 0) {
			model.exteriorRings.forEach((extRing, eIdx) => {
				buildingBoundaries.push({
					id: `bound-${model.building}-${model.floorOrder}-${eIdx}`,
					building: model.building,
					floorOrder: model.floorOrder,
					ring: extRing,
				});
			});
		} else {
			const fallbackBounds = (store.buildingBoundaries || []).filter(
				(b) =>
					matchesBuilding(b.building, model.building) &&
					Number(b.floorOrder) === model.floorOrder,
			);
			buildingBoundaries.push(...fallbackBounds);
		}

		for (const face of model.faces) {
			if (face.role === "ROOM") {
				roomPolygons.push({
					id: face.id,
					building: model.building,
					floorOrder: model.floorOrder,
					ring: face.ring,
					holes: face.holes,
				});
			}
		}
	}

	return {
		...store,
		buildingBoundaries,
		roomPolygons,
	};
}

/**
 * Ray-casting Point-in-Polygon check (inclusive of boundary/edges): is [lng, lat] inside polygon ring?
 */
export function isPointInRing(pt, ring) {
	if (!pt || !Array.isArray(ring) || ring.length < 3) return false;
	const [x, y] = pt;
	let inside = false;
	for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
		const xi = ring[i][0];
		const yi = ring[i][1];
		const xj = ring[j][0];
		const yj = ring[j][1];
		if (isPointOnSegment(x, y, xi, yi, xj, yj)) {
			return true;
		}
		const intersect =
			yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12) + xi;
		if (intersect) inside = !inside;
	}
	return inside;
}

export function getRingCentroid(ring) {
	if (!Array.isArray(ring) || ring.length === 0) return [106.80988, 10.84113];
	const n =
		ring.length > 1 && ring[0][0] === ring[ring.length - 1][0]
			? ring.length - 1
			: ring.length;
	let sx = 0;
	let sy = 0;
	for (let i = 0; i < n; i++) {
		sx += ring[i][0];
		sy += ring[i][1];
	}
	return [Number((sx / n).toFixed(6)), Number((sy / n).toFixed(6))];
}

function toLocalMetersFactory(refPt) {
	const [lng0, lat0] = refPt;
	const kx = 111320 * Math.cos((lat0 * Math.PI) / 180);
	const ky = 110574;
	return {
		toM: ([lng, lat]) => [(lng - lng0) * kx, (lat - lat0) * ky],
		toDeg: ([mx, my]) => [
			Number((lng0 + mx / kx).toFixed(7)),
			Number((lat0 + my / ky).toFixed(7)),
		],
	};
}

function computeSignedArea2D(pts) {
	let area = 0;
	for (let i = 0; i < pts.length; i++) {
		const j = (i + 1) % pts.length;
		area += pts[i][0] * pts[j][1] - pts[j][0] * pts[i][1];
	}
	return area / 2;
}

/**
 * Build a watertight single GeoJSON Polygon ([outerRing, innerHole]) for a closed building boundary ring
 * using mitered corner joints in local meters (no notches or overlapping z-fighting at corners).
 */
export function buildMiteredBoundaryWallPolygon(ring, halfWidthMeters = 0.16) {
	if (!Array.isArray(ring) || ring.length < 4) return null;
	const { toM, toDeg } = toLocalMetersFactory(ring[0]);

	// Extract unique open vertices in meters
	const mPts = [];
	for (let i = 0; i < ring.length; i++) {
		const ptM = toM(ring[i]);
		const prev = mPts[mPts.length - 1];
		if (!prev || Math.hypot(ptM[0] - prev[0], ptM[1] - prev[1]) > 0.05) {
			mPts.push(ptM);
		}
	}
	if (mPts.length > 1) {
		const first = mPts[0];
		const last = mPts[mPts.length - 1];
		if (Math.hypot(first[0] - last[0], first[1] - last[1]) <= 0.05) {
			mPts.pop();
		}
	}
	const n = mPts.length;
	if (n < 3) return null;

	const leftPts = [];
	const rightPts = [];

	for (let i = 0; i < n; i++) {
		const prev = mPts[(i - 1 + n) % n];
		const curr = mPts[i];
		const next = mPts[(i + 1) % n];

		const d1x = curr[0] - prev[0];
		const d1y = curr[1] - prev[1];
		const len1 = Math.hypot(d1x, d1y);
		const d2x = next[0] - curr[0];
		const d2y = next[1] - curr[1];
		const len2 = Math.hypot(d2x, d2y);
		if (len1 < 1e-6 || len2 < 1e-6) continue;

		const n1 = [-d1y / len1, d1x / len1];
		const n2 = [-d2y / len2, d2x / len2];

		const mx = n1[0] + n2[0];
		const my = n1[1] + n2[1];
		const mLen = Math.hypot(mx, my);

		if (mLen > 1e-6) {
			const ux = mx / mLen;
			const uy = my / mLen;
			const dot = n1[0] * ux + n1[1] * uy;
			if (dot > 0.25) {
				const scale = Math.min(halfWidthMeters / dot, halfWidthMeters * 3);
				leftPts.push([curr[0] + ux * scale, curr[1] + uy * scale]);
				rightPts.push([curr[0] - ux * scale, curr[1] - uy * scale]);
				continue;
			}
		}

		const cross = d1x * d2y - d1y * d2x;
		const miterPt = (sign) => {
			if (mLen > 1e-6) {
				const ux = mx / mLen;
				const uy = my / mLen;
				const dot = Math.max(0.25, n1[0] * ux + n1[1] * uy);
				const scale = Math.min(halfWidthMeters / dot, halfWidthMeters * 2.5);
				return [curr[0] + sign * ux * scale, curr[1] + sign * uy * scale];
			}
			return [
				curr[0] + sign * n1[0] * halfWidthMeters,
				curr[1] + sign * n1[1] * halfWidthMeters,
			];
		};

		// Bevel outer side only; keep clamped miter on inner side to avoid bow-tie self-intersection
		if (cross >= 0) {
			leftPts.push(miterPt(1));
			rightPts.push([
				curr[0] - n1[0] * halfWidthMeters,
				curr[1] - n1[1] * halfWidthMeters,
			]);
			rightPts.push([
				curr[0] - n2[0] * halfWidthMeters,
				curr[1] - n2[1] * halfWidthMeters,
			]);
		} else {
			leftPts.push([
				curr[0] + n1[0] * halfWidthMeters,
				curr[1] + n1[1] * halfWidthMeters,
			]);
			leftPts.push([
				curr[0] + n2[0] * halfWidthMeters,
				curr[1] + n2[1] * halfWidthMeters,
			]);
			rightPts.push(miterPt(-1));
		}
	}

	if (leftPts.length < 3 || rightPts.length < 3) return null;

	const areaL = computeSignedArea2D(leftPts);
	const areaR = computeSignedArea2D(rightPts);
	let outer = Math.abs(areaL) >= Math.abs(areaR) ? leftPts : rightPts;
	let inner = Math.abs(areaL) >= Math.abs(areaR) ? rightPts : leftPts;

	// GeoJSON RFC 7946 winding: outer CCW (>0), inner hole CW (<0)
	if (computeSignedArea2D(outer) < 0) outer = [...outer].reverse();
	if (computeSignedArea2D(inner) > 0) inner = [...inner].reverse();

	const outerDeg = outer.map(toDeg);
	outerDeg.push([...outerDeg[0]]);
	const innerDeg = inner.map(toDeg);
	innerDeg.push([...innerDeg[0]]);

	return [outerDeg, innerDeg];
}

/**
 * Build a clean mitered polygon ribbon for an open polyline (custom walls or route segments),
 * with optional square end-cap extension so perpendicular wall/route joints connect flush.
 */
export function buildMiteredPolylinePolygon(
	line,
	halfWidthMeters = 0.11,
	capMeters = halfWidthMeters,
) {
	if (!Array.isArray(line) || line.length < 2) return null;
	const { toM, toDeg } = toLocalMetersFactory(line[0]);

	const mPts = [];
	for (let i = 0; i < line.length; i++) {
		const ptM = toM(line[i]);
		const prev = mPts[mPts.length - 1];
		if (!prev || Math.hypot(ptM[0] - prev[0], ptM[1] - prev[1]) > 0.02) {
			mPts.push(ptM);
		}
	}
	if (mPts.length < 2) return null;

	const leftPts = [];
	const rightPts = [];

	for (let i = 0; i < mPts.length; i++) {
		const curr = mPts[i];
		if (i === 0) {
			const next = mPts[1];
			const dx = next[0] - curr[0];
			const dy = next[1] - curr[1];
			const len = Math.hypot(dx, dy);
			if (len < 1e-6) continue;
			const ux = dx / len;
			const uy = dy / len;
			const nx = -uy;
			const ny = ux;
			const sx = curr[0] - ux * capMeters;
			const sy = curr[1] - uy * capMeters;
			leftPts.push([sx + nx * halfWidthMeters, sy + ny * halfWidthMeters]);
			rightPts.push([sx - nx * halfWidthMeters, sy - ny * halfWidthMeters]);
		} else if (i === mPts.length - 1) {
			const prev = mPts[i - 1];
			const dx = curr[0] - prev[0];
			const dy = curr[1] - prev[1];
			const len = Math.hypot(dx, dy);
			if (len < 1e-6) continue;
			const ux = dx / len;
			const uy = dy / len;
			const nx = -uy;
			const ny = ux;
			const ex = curr[0] + ux * capMeters;
			const ey = curr[1] + uy * capMeters;
			leftPts.push([ex + nx * halfWidthMeters, ey + ny * halfWidthMeters]);
			rightPts.push([ex - nx * halfWidthMeters, ey - ny * halfWidthMeters]);
		} else {
			const prev = mPts[i - 1];
			const next = mPts[i + 1];
			const d1x = curr[0] - prev[0];
			const d1y = curr[1] - prev[1];
			const len1 = Math.hypot(d1x, d1y);
			const d2x = next[0] - curr[0];
			const d2y = next[1] - curr[1];
			const len2 = Math.hypot(d2x, d2y);
			if (len1 < 1e-6 || len2 < 1e-6) continue;

			const n1 = [-d1y / len1, d1x / len1];
			const n2 = [-d2y / len2, d2x / len2];
			const mx = n1[0] + n2[0];
			const my = n1[1] + n2[1];
			const mLen = Math.hypot(mx, my);
			if (mLen > 1e-6) {
				const ux = mx / mLen;
				const uy = my / mLen;
				const dot = n1[0] * ux + n1[1] * uy;
				if (dot > 0.25) {
					const scale = Math.min(halfWidthMeters / dot, halfWidthMeters * 3);
					leftPts.push([curr[0] + ux * scale, curr[1] + uy * scale]);
					rightPts.push([curr[0] - ux * scale, curr[1] - uy * scale]);
					continue;
				}
			}
			const cross = d1x * d2y - d1y * d2x;
			if (cross >= 0) {
				leftPts.push([
					curr[0] + n1[0] * halfWidthMeters,
					curr[1] + n1[1] * halfWidthMeters,
				]);
				rightPts.push([
					curr[0] - n1[0] * halfWidthMeters,
					curr[1] - n1[1] * halfWidthMeters,
				]);
				rightPts.push([
					curr[0] - n2[0] * halfWidthMeters,
					curr[1] - n2[1] * halfWidthMeters,
				]);
			} else {
				leftPts.push([
					curr[0] + n1[0] * halfWidthMeters,
					curr[1] + n1[1] * halfWidthMeters,
				]);
				leftPts.push([
					curr[0] + n2[0] * halfWidthMeters,
					curr[1] + n2[1] * halfWidthMeters,
				]);
				rightPts.push([
					curr[0] - n1[0] * halfWidthMeters,
					curr[1] - n1[1] * halfWidthMeters,
				]);
			}
		}
	}

	const polyM = [...leftPts, ...rightPts.reverse()];
	if (polyM.length < 4) return null;
	if (computeSignedArea2D(polyM) < 0) polyM.reverse();
	const ringDeg = polyM.map(toDeg);
	ringDeg.push([...ringDeg[0]]);
	return [ringDeg];
}

/**
 * Convert a polyline / ring into thin 3D wall polygons (~24cm thick)
 */
export function buildBufferedWallPolygons(
	ringOrLine,
	halfWidthDeg = 0.0000014,
) {
	const polys = [];
	if (!Array.isArray(ringOrLine) || ringOrLine.length < 2) return polys;
	const halfWidthMeters =
		halfWidthDeg < 0.01 ? halfWidthDeg * 110000 : halfWidthDeg;
	const ribbon = buildMiteredPolylinePolygon(
		ringOrLine,
		halfWidthMeters,
		halfWidthMeters,
	);
	if (ribbon && ribbon[0]) {
		polys.push(ribbon[0]);
	}
	return polys;
}

/**
 * Split a 4-corner quad [p0(TL), p1(TR), p2(BR), p3(BL)] into N adjacent rooms along its length
 */
export function splitQuadIntoNRooms(quadRing, count) {
	if (!Array.isArray(quadRing) || quadRing.length < 4 || count < 1) return [];
	const [p0, p1, p2, p3] = quadRing;
	const lerp = (a, b, t) => [
		Number((a[0] + (b[0] - a[0]) * t).toFixed(6)),
		Number((a[1] + (b[1] - a[1]) * t).toFixed(6)),
	];
	const rings = [];
	for (let i = 0; i < count; i++) {
		const t1 = i / count;
		const t2 = (i + 1) / count;
		const tl = lerp(p0, p1, t1);
		const tr = lerp(p0, p1, t2);
		const br = lerp(p3, p2, t2);
		const bl = lerp(p3, p2, t1);
		rings.push([tl, tr, br, bl, tl]);
	}
	return rings;
}

/**
 * Approximate polygon area in square meters
 */
export function approximatePolygonAreaMeters(ring) {
	if (!Array.isArray(ring) || ring.length < 3) return 0;
	let area = 0;
	const n = ring.length;
	for (let i = 0; i < n; i++) {
		const j = (i + 1) % n;
		const xi = ring[i][0] * 109335;
		const yi = ring[i][1] * 110574;
		const xj = ring[j][0] * 109335;
		const yj = ring[j][1] * 110574;
		area += xi * yj - xj * yi;
	}
	return Math.abs(area) / 2;
}

/**
 * Sanitize polygon ring by eliminating duplicate consecutive vertices
 * and ensuring a closed ring (first vertex equals last vertex).
 */
export function cleanRing(ring) {
	if (!Array.isArray(ring) || ring.length < 3) return ring;
	const cleaned = [];
	for (let i = 0; i < ring.length; i++) {
		const pt = ring[i];
		const prev = cleaned[cleaned.length - 1];
		if (!prev || Math.hypot(pt[0] - prev[0], pt[1] - prev[1]) > 1e-7) {
			cleaned.push(pt);
		}
	}
	if (cleaned.length > 2) {
		const first = cleaned[0];
		const last = cleaned[cleaned.length - 1];
		if (Math.hypot(first[0] - last[0], first[1] - last[1]) > 1e-7) {
			cleaned.push([...first]);
		}
	}
	return cleaned;
}

/**
 * Mappedin-style room slice: cut a polygon ring into 2 distinct polygon rings
 * using a straight cut line defined by [p1, p2].
 * Returns [ringA, ringB] or null if cut does not cleanly divide the polygon.
 */
export function slicePolygonWithLine(ring, p1, p2) {
	if (!Array.isArray(ring) || ring.length < 4 || !p1 || !p2) return null;
	const [x1, y1] = p1;
	const [x2, y2] = p2;
	const dx = x2 - x1;
	const dy = y2 - y1;
	const lenSq = dx * dx + dy * dy;
	if (lenSq < 1e-12) return null;

	// Open ring (vertices excluding duplicate closing vertex)
	const pts = ring.slice(0, -1);
	const n = pts.length;
	if (n < 3) return null;

	// Line normal: (-dy, dx)
	// Signed distance of point Q from line passing through p1 & p2:
	const signedDist = (p) => (p[0] - x1) * -dy + (p[1] - y1) * dx;

	const eps = 1e-10;
	const dists = pts.map(signedDist);

	// Find intersection of each edge (pts[i], pts[(i+1)%n]) with the cut line
	const intersections = [];

	for (let i = 0; i < n; i++) {
		const j = (i + 1) % n;
		const dA = dists[i];
		const dB = dists[j];

		if ((dA > eps && dB < -eps) || (dA < -eps && dB > eps)) {
			const t = dA / (dA - dB);
			const ix = pts[i][0] + t * (pts[j][0] - pts[i][0]);
			const iy = pts[i][1] + t * (pts[j][1] - pts[i][1]);
			intersections.push({
				edgeIndex: i,
				point: [Number(ix.toFixed(6)), Number(iy.toFixed(6))],
			});
		} else if (Math.abs(dA) <= eps) {
			const prevIdx = (i - 1 + n) % n;
			const dPrev = dists[prevIdx];
			if (dPrev * dB < -eps * eps) {
				intersections.push({
					edgeIndex: i,
					point: [Number(pts[i][0].toFixed(6)), Number(pts[i][1].toFixed(6))],
				});
			}
		}
	}

	if (intersections.length !== 2) {
		return null;
	}

	const [int1, int2] = intersections;

	// Check if user's segment [p1, p2] relates to the chord [int1, int2]
	const dotP = (p) => ((p[0] - x1) * dx + (p[1] - y1) * dy) / lenSq;
	const uInt1 = dotP(int1.point);
	const uInt2 = dotP(int2.point);
	const uMin = Math.min(uInt1, uInt2);
	const uMax = Math.max(uInt1, uInt2);

	const segmentMin = -0.3;
	const segmentMax = 1.3;
	const overlaps = Math.max(segmentMin, uMin) <= Math.min(segmentMax, uMax);

	if (!overlaps) {
		return null;
	}

	const idx1 = int1.edgeIndex;
	const idx2 = int2.edgeIndex;

	// Poly A: int1 -> vertices (idx1+1 to idx2) -> int2 -> int1
	const rawRingA = [int1.point];
	let curr = (idx1 + 1) % n;
	while (curr !== (idx2 + 1) % n) {
		rawRingA.push(pts[curr]);
		curr = (curr + 1) % n;
	}
	rawRingA.push(int2.point);
	rawRingA.push(int1.point);

	// Poly B: int2 -> vertices (idx2+1 to idx1) -> int1 -> int2
	const rawRingB = [int2.point];
	curr = (idx2 + 1) % n;
	while (curr !== (idx1 + 1) % n) {
		rawRingB.push(pts[curr]);
		curr = (curr + 1) % n;
	}
	rawRingB.push(int1.point);
	rawRingB.push(int2.point);

	const ringA = cleanRing(rawRingA);
	const ringB = cleanRing(rawRingB);

	if (ringA.length < 4 || ringB.length < 4) return null;

	const areaA = approximatePolygonAreaMeters(ringA);
	const areaB = approximatePolygonAreaMeters(ringB);

	if (areaA < 0.5 || areaB < 0.5) return null;

	return [ringA, ringB];
}

function buildWallSegmentPolygon(
	a,
	b,
	halfWidthMeters = 0.14,
	startCapMeters = halfWidthMeters,
	endCapMeters = halfWidthMeters,
) {
	if (!a || !b) return null;
	const { toM, toDeg } = toLocalMetersFactory(a);
	const pB = toM(b);
	const dx = pB[0];
	const dy = pB[1];
	const len = Math.hypot(dx, dy);
	if (len < 0.04) return null;
	const ux = dx / len;
	const uy = dy / len;
	const nx = -uy * halfWidthMeters;
	const ny = ux * halfWidthMeters;
	const sx = -ux * startCapMeters;
	const sy = -uy * startCapMeters;
	const ex = dx + ux * endCapMeters;
	const ey = dy + uy * endCapMeters;
	const ptsM = [
		[sx + nx, sy + ny],
		[ex + nx, ey + ny],
		[ex - nx, ey - ny],
		[sx - nx, sy - ny],
	];
	if (computeSignedArea2D(ptsM) < 0) ptsM.reverse();
	const ring = ptsM.map(toDeg);
	ring.push([...ring[0]]);
	return [ring];
}

/**
 * Build clean 3D Vector GeoJSON layers from Unified Wall Graph + Planar Faces + Doors:
 * - Slabs with inner VOID holes (FloorSlab 0.16m)
 * - Exterior perimeter walls (2.85m) vs interior partition walls (1.35m), carved cleanly at every DOOR
 * - Room extrusions (0.52m, 1.0m selected) with hole-aware Area PIP matching
 * - Elevated symbol layers for PORTALs & Area names + selectable 2D Face/Door layers
 */
export function buildVector3dLayers({
	store,
	selectedBuilding = "ALL",
	activeFloorOrder = 0,
	areas = [],
	selectedAreaId = null,
	viewRole = "ADMIN", // "ADMIN" | "FACILITY_MANAGER" | "STUDIO"
	showOtherBuildingsInFocus = true,
}) {
	const rawStore = store || loadGeometryStore();
	const data = migrateStoreToWallGraph(rawStore);
	const floorModels = buildFloorWallGraphModels(data);

	const floorSlabs = [];
	const boundaryWalls = [];
	const roomFeatures = [];
	const faceFeatures = [];
	const doorFeatures = [];
	const roomLabelFeatures = [];
	const portalSymbolFeatures = [];

	const isOverviewAll = !selectedBuilding || selectedBuilding === "ALL";
	const isAllFloorsInFocused = !isOverviewAll && activeFloorOrder === "ALL";

	const classifyFeature = (bName, flOrder) => {
		const isTargetBuilding =
			isOverviewAll || matchesBuilding(bName, selectedBuilding);

		if (!isTargetBuilding) {
			if (!showOtherBuildingsInFocus) {
				return { include: false, isGhost: false, isContextBuilding: false };
			}
			return { include: true, isGhost: true, isContextBuilding: true };
		}

		if (isOverviewAll || isAllFloorsInFocused) {
			return { include: true, isGhost: false, isContextBuilding: false };
		}

		const targetOrder = Number(activeFloorOrder) || 0;
		if (flOrder > targetOrder) {
			return { include: false, isGhost: false, isContextBuilding: false };
		}
		if (flOrder < targetOrder) {
			return { include: true, isGhost: true, isContextBuilding: false };
		}
		return { include: true, isGhost: false, isContextBuilding: false };
	};

	const levelColors = {
		PUBLIC: "#10b981",
		CONFIDENTIAL: "#f59e0b",
		HIGHLY_CONFIDENTIAL: "#ef4444",
	};

	// 1. Unified Wall Graph -> Slabs (with VOID holes), Walls (2.85m outer / 1.35m inner + Door cutouts), Rooms & Doors
	for (const model of floorModels.values()) {
		const { include, isGhost, isContextBuilding } = classifyFeature(
			model.building,
			model.floorOrder,
		);
		if (!include) continue;

		const floorBase = (Number(model.floorOrder) || 0) * FLOOR_ELEVATION_STEP;
		const floorDoors = model.doors || [];

		// 1a. Extracted Planar Faces -> Slabs, 3D Room Pads & Selectable Face Overlays
		for (const face of model.faces) {
			faceFeatures.push({
				type: "Feature",
				id: face.id,
				properties: {
					id: face.id,
					role: face.role,
					building: model.building,
					floorOrder: model.floorOrder,
					seedLng: face.seedCoord[0],
					seedLat: face.seedCoord[1],
					areaSqm: face.areaSqm,
					isGhost,
					isContextBuilding,
				},
				geometry: {
					type: "Polygon",
					coordinates: [face.ring, ...(face.holes || [])],
				},
			});

			if (face.role === "VOID") {
				// VOID punches straight through (excluded from Floor Slab and Room Extrusions)
				continue;
			}

			// Emit Floor Slab (CORRIDOR carries nested holes so VOID cutouts carve through cleanly)
			floorSlabs.push({
				type: "Feature",
				id: `slab-${face.id}`,
				properties: {
					id: face.id,
					role: face.role,
					building: model.building,
					floorOrder: model.floorOrder,
					isGhost,
					isContextBuilding,
					color: isGhost ? "#cbd5e1" : "#e2e8f0",
					baseHeight: floorBase,
					extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT,
				},
				geometry: {
					type: "Polygon",
					coordinates: [face.ring, ...(face.holes || [])],
				},
			});

			if (face.role !== "ROOM") continue;

			// Auto-match Area using Hole-aware Point-in-Polygon
			const matchedArea = areas.find((a) => {
				if (a.centerLatitude == null || a.centerLongitude == null) return false;
				if (a.building && !matchesBuilding(a.building, model.building)) {
					return false;
				}
				const aOrder = resolveFloorOrder(a.building, a.floor);
				if (aOrder !== model.floorOrder) return false;
				const pt = [Number(a.centerLongitude), Number(a.centerLatitude)];
				return isPointInPolygonWithHoles(pt, face.ring, face.holes || []);
			});

			const isSelected = matchedArea && matchedArea.id === selectedAreaId;
			const areaLevelCode =
				typeof matchedArea?.areaLevel === "string"
					? matchedArea.areaLevel
					: matchedArea?.areaLevel?.code || null;

			let fillColor = "#94a3b8";
			if (isGhost) {
				fillColor = "#cbd5e1";
			} else if (viewRole === "STUDIO") {
				fillColor = "#38bdf8";
			} else if (viewRole === "FACILITY_MANAGER") {
				fillColor = isSelected ? "#f97316" : matchedArea ? "#475569" : "#94a3b8";
			} else if (isSelected) {
				fillColor = "#2563eb";
			} else if (areaLevelCode && levelColors[areaLevelCode]) {
				fillColor = levelColors[areaLevelCode];
			}

			const centroid = face.seedCoord || getPolygonInteriorPoint(face.ring, face.holes);
			const roomHeight = isSelected
				? ROOM_SELECTED_EXTRUSION_HEIGHT
				: ROOM_EXTRUSION_HEIGHT;

			roomFeatures.push({
				type: "Feature",
				id: face.id,
				properties: {
					id: face.id,
					building: model.building,
					floorOrder: model.floorOrder,
					matchedAreaId: matchedArea?.id || "",
					matchedAreaName: matchedArea?.name || "",
					isGhost,
					isContextBuilding,
					color: fillColor,
					baseHeight: floorBase + FLOOR_SLAB_HEIGHT,
					extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT + roomHeight,
					centerLng: centroid[0],
					centerLat: centroid[1],
				},
				geometry: {
					type: "Polygon",
					coordinates: [face.ring, ...(face.holes || [])],
				},
			});

			if (
				matchedArea?.name &&
				!isGhost &&
				!isOverviewAll &&
				!isAllFloorsInFocused
			) {
				roomLabelFeatures.push({
					type: "Feature",
					id: `label-${face.id}`,
					properties: {
						id: face.id,
						areaId: matchedArea.id,
						name: matchedArea.name,
						building: model.building,
						floorOrder: model.floorOrder,
						isSelected: Boolean(isSelected),
						heightOffset: Number(
							(floorBase + FLOOR_SLAB_HEIGHT + roomHeight + 0.16).toFixed(2),
						),
					},
					geometry: {
						type: "Point",
						coordinates: centroid,
					},
				});
			}
		}

		// 1b. Carved Wall Segments (Perimeter = 2.85m, Interior Partition = 1.35m)
		const carvedSegments = buildWallsWithDoorCutouts(
			model.wallSegments,
			floorDoors,
		);
		carvedSegments.forEach((seg, idx) => {
			const halfW = seg.isPerimeter ? 0.16 : 0.1;
			const wallHeight = seg.isPerimeter
				? BOUNDARY_WALL_HEIGHT
				: INTERIOR_WALL_HEIGHT;
			const capStart = seg.startIsDoorJamb ? 0.015 : halfW;
			const capEnd = seg.endIsDoorJamb ? 0.015 : halfW;
			const poly = buildWallSegmentPolygon(
				seg.a,
				seg.b,
				halfW,
				capStart,
				capEnd,
			);
			if (!poly) return;
			boundaryWalls.push({
				type: "Feature",
				id: `uwall-${model.building}-${model.floorOrder}-${idx}`,
				properties: {
					id: `uwall-${idx}`,
					wallKind: seg.isPerimeter ? "PERIMETER" : "INTERIOR",
					isPerimeter: seg.isPerimeter,
					building: model.building,
					floorOrder: model.floorOrder,
					isGhost,
					isContextBuilding,
					color: isGhost
						? "#cbd5e1"
						: seg.isPerimeter
							? "#64748b"
							: "#94a3b8",
					baseHeight: floorBase + FLOOR_SLAB_HEIGHT,
					extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT + wallHeight,
				},
				geometry: {
					type: "Polygon",
					coordinates: poly,
				},
			});
		});

		// 1c. Architectural Door 3D Threshold & Header Lintels + 2D Oriented Door Box Features
		floorDoors.forEach((d, dIdx) => {
			if (!Array.isArray(d.coords)) return;
			const geom = buildOrientedDoorGeometry(d, model.wallSegments);
			if (!geom) return;

			doorFeatures.push({
				type: "Feature",
				id: d.id,
				properties: {
					id: d.id,
					building: model.building,
					floorOrder: model.floorOrder,
					widthMeters: geom.widthMeters,
					isPerimeter: geom.isPerimeter,
					midLng: geom.midpoint[0],
					midLat: geom.midpoint[1],
					jambALng: geom.jambA[0],
					jambALat: geom.jambA[1],
					jambBLng: geom.jambB[0],
					jambBLat: geom.jambB[1],
					isGhost,
					heightOffset: Number((floorBase + FLOOR_SLAB_HEIGHT + 0.12).toFixed(2)),
				},
				geometry: {
					type: "Polygon",
					coordinates: [geom.boxPolygon],
				},
			});

			const doorFrameColor = isGhost ? "#e2e8f0" : "#f59e0b";

			// 3D Low Threshold Sill (0.06m high warm architectural threshold across doorway opening)
			boundaryWalls.push({
				type: "Feature",
				id: `udoor-sill-${model.building}-${model.floorOrder}-${dIdx}`,
				properties: {
					id: `udoor-sill-${d.id}`,
					wallKind: "DOOR_THRESHOLD",
					isPerimeter: geom.isPerimeter,
					building: model.building,
					floorOrder: model.floorOrder,
					isGhost,
					isContextBuilding,
					color: doorFrameColor,
					baseHeight: floorBase + FLOOR_SLAB_HEIGHT,
					extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT + 0.055,
				},
				geometry: {
					type: "Polygon",
					coordinates: [geom.boxPolygon],
				},
			});

			// 3D Upper Door Header / Lintel on 2.85m Perimeter Walls (from 2.10m to 2.85m), matching threshold color
			if (geom.isPerimeter) {
				const lintelPoly = buildWallSegmentPolygon(
					geom.jambA,
					geom.jambB,
					0.16,
					0.015,
					0.015,
				);
				if (lintelPoly) {
					boundaryWalls.push({
						type: "Feature",
						id: `udoor-lintel-${model.building}-${model.floorOrder}-${dIdx}`,
						properties: {
							id: `udoor-lintel-${d.id}`,
							wallKind: "DOOR_LINTEL",
							isPerimeter: true,
							building: model.building,
							floorOrder: model.floorOrder,
							isGhost,
							isContextBuilding,
							color: doorFrameColor,
							baseHeight: floorBase + FLOOR_SLAB_HEIGHT + 2.1,
							extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT + BOUNDARY_WALL_HEIGHT,
						},
						geometry: {
							type: "Polygon",
							coordinates: lintelPoly,
						},
					});
				}
			}
		});
	}

	// 2b. Vertical Portal Nodes (STAIRS / ELEVATOR) rendered via WebGL symbol layers at exact floor elevation
	for (const n of data.navNodes || []) {
		if (!isPortalNode(n) || !Array.isArray(n.coords)) continue;
		const { include, isGhost, isContextBuilding } = classifyFeature(
			n.building,
			Number(n.floorOrder) || 0,
		);
		if (!include) continue;
		if (!isOverviewAll && !isAllFloorsInFocused && (isGhost || isContextBuilding)) {
			continue;
		}
		if (isOverviewAll && (Number(n.floorOrder) || 0) !== 0) {
			continue;
		}
		const floorOrder = Number(n.floorOrder) || 0;
		const floorBase = floorOrder * FLOOR_ELEVATION_STEP;
		portalSymbolFeatures.push({
			type: "Feature",
			id: `portal-${n.id}`,
			properties: {
				id: n.id,
				nodeType: n.type,
				iconImage:
					n.type === "ELEVATOR" ? "portal-elevator" : "portal-stairs",
				portalGroupId: n.portalGroupId || "",
				building: resolveCanonicalBuildingName(n.building) || n.building || "",
				floorOrder,
				isGhost,
				heightOffset: Number((floorBase + FLOOR_SLAB_HEIGHT + 0.35).toFixed(2)),
			},
			geometry: {
				type: "Point",
				coordinates: n.coords,
			},
		});
	}

	// 3. Building overview label anchors (for clickable building tags / overview)
	const buildingAnchors = DEFAULT_BUILDINGS_META.map((bMeta) => {
		const bBounds = (data.buildingBoundaries || []).filter((b) =>
			matchesBuilding(b.building, bMeta.name),
		);
		let northLat = bMeta.center[1];
		let centerLng = bMeta.center[0];
		if (bBounds.length > 0 && Array.isArray(bBounds[0].ring)) {
			const ring = bBounds[0].ring;
			const centroid = getRingCentroid(ring);
			centerLng = centroid[0];
			northLat = Math.max(...ring.map((p) => p[1])) + 0.000045;
		}
		return {
			type: "Feature",
			id: `bldg-label-${bMeta.id}`,
			properties: {
				id: bMeta.id,
				building: bMeta.name,
				name: bMeta.name,
				floorCount: bMeta.floors.length,
				isFocused:
					!isOverviewAll && matchesBuilding(selectedBuilding, bMeta.name),
			},
			geometry: {
				type: "Point",
				coordinates: [centerLng, northLat],
			},
		};
	});

	return {
		slabsGeoJSON: { type: "FeatureCollection", features: floorSlabs },
		wallsGeoJSON: { type: "FeatureCollection", features: boundaryWalls },
		roomsGeoJSON: { type: "FeatureCollection", features: roomFeatures },
		facesGeoJSON: { type: "FeatureCollection", features: faceFeatures },
		doorsGeoJSON: { type: "FeatureCollection", features: doorFeatures },
		roomLabelsGeoJSON: {
			type: "FeatureCollection",
			features: roomLabelFeatures,
		},
		portalSymbolsGeoJSON: {
			type: "FeatureCollection",
			features: portalSymbolFeatures,
		},
		buildingLabelsGeoJSON: {
			type: "FeatureCollection",
			features: buildingAnchors,
		},
	};
}

function haversineMeters(c1, c2) {
	const R = 6371000;
	const toRad = (d) => (d * Math.PI) / 180;
	const dLat = toRad(c2[1] - c1[1]);
	const dLng = toRad(c2[0] - c1[0]);
	const a =
		Math.sin(dLat / 2) ** 2 +
		Math.cos(toRad(c1[1])) * Math.cos(toRad(c2[1])) * Math.sin(dLng / 2) ** 2;
	return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * Automatically build all edges including vertical portalGroupId edges (STAIRS / ELEVATOR across floors).
 * Scoped strictly per canonical building so identically named shafts across buildings never bridge across air.
 */
export function buildAllGraphEdges(store) {
	const nodes = store?.navNodes || [];
	const boundaries = store?.buildingBoundaries || [];
	const explicitEdges = [...(store?.navEdges || [])];

	// Group STAIRS / ELEVATOR nodes by `${canonicalBuilding}__${portalGroupId}`
	const groups = new Map();
	for (const n of nodes) {
		if (isPortalNode(n) && n.portalGroupId && n.portalGroupId.trim()) {
			const resolvedBldg = resolveNodeBuilding(boundaries, n);
			const bldgKey = (
				resolveCanonicalBuildingName(resolvedBldg) || "UNKNOWN"
			).toUpperCase();
			const key = `${bldgKey}__${n.portalGroupId.trim().toUpperCase()}`;
			if (!groups.has(key)) groups.set(key, []);
			groups.get(key).push(n);
		}
	}

	for (const groupNodes of groups.values()) {
		groupNodes.sort((a, b) => a.floorOrder - b.floorOrder);
		for (let i = 0; i < groupNodes.length - 1; i++) {
			const u = groupNodes[i].id;
			const v = groupNodes[i + 1].id;
			const exists = explicitEdges.some(
				([a, b]) => (a === u && b === v) || (a === v && b === u),
			);
			if (!exists) {
				explicitEdges.push([u, v]);
			}
		}
	}

	return explicitEdges;
}

/**
 * Check whether an intra-floor segment should sit at Outdoor courtyard elevation (`z = 0.06m`)
 * using pure Floor-0 boundary geometry rather than legacy node labels.
 */
function isSegmentOutdoorGeometry(
	boundaries,
	a,
	b,
	precomputedModels = null,
) {
	const aFloor = Number(a?.floorOrder) || 0;
	const bFloor = Number(b?.floorOrder) || 0;
	if (aFloor !== bFloor || aFloor !== 0) return false;
	const floor0Bounds = (boundaries || []).filter(
		(bnd) => (Number(bnd.floorOrder) || 0) === 0,
	);
	if (floor0Bounds.length === 0) {
		return (
			isNodeOutdoor(boundaries, a, precomputedModels) &&
			isNodeOutdoor(boundaries, b, precomputedModels)
		);
	}
	const mid = [
		(a.coords[0] + b.coords[0]) / 2,
		(a.coords[1] + b.coords[1]) / 2,
	];
	return (
		(isNodeOutdoor(boundaries, a, precomputedModels) &&
			isNodeOutdoor(boundaries, b, precomputedModels)) ||
		isNodeOutdoor(
			boundaries,
			{ floorOrder: 0, coords: mid },
			precomputedModels,
		) ||
		!findBoundaryContainingPoint(floor0Bounds, mid, 0)
	);
}

/**
 * Multi-Building & Multi-Floor A* Pathfinding on digitized graph
 * Supports wall-mounted DOOR two-side connectivity (ROOM <-> CORRIDOR, CORRIDOR <-> OUTDOOR)
 * while maintaining identical `floorDiff * 18` cost for both STAIRS & ELEVATOR (Q3c).
 */
export function computeDigitizedRoute({
	store,
	startCoords,
	startBuilding = "OUTDOOR",
	startFloorOrder = 0,
	endCoords,
	endBuilding = "Tòa HCM",
	endFloorOrder = 0,
	diagnosticsOut = null,
}) {
	const setDiag = (reasonCode, message) => {
		if (diagnosticsOut && typeof diagnosticsOut === "object") {
			diagnosticsOut.reasonCode = reasonCode;
			diagnosticsOut.message = message;
		}
	};

	const rawStore = store || loadGeometryStore();
	const data = migrateStoreToWallGraph(rawStore);
	const nodes = data.navNodes || [];
	const boundaries = data.buildingBoundaries || [];
	if (!startCoords || !endCoords) {
		setDiag("INVALID_COORDS", "Thiếu tọa độ điểm đi hoặc điểm đến.");
		return null;
	}
	if (nodes.length === 0) {
		setDiag("EMPTY_GRAPH", "Chưa có nút đồ thị đường đi [N] nào trên bản đồ.");
		return null;
	}

	const floorModels = buildFloorWallGraphModels(data);
	const allEdges = buildAllGraphEdges(data);
	const nodeMap = new Map(nodes.map((n) => [n.id, { ...n }]));
	const adj = new Map();
	for (const n of nodes) adj.set(n.id, []);

	for (const [uId, vId] of allEdges) {
		const u = nodeMap.get(uId);
		const v = nodeMap.get(vId);
		if (!u || !v) continue;
		const dist = haversineMeters(u.coords, v.coords);
		const floorDiff = Math.abs(u.floorOrder - v.floorOrder);
		const weight = dist + floorDiff * 18;
		adj.get(uId).push({ to: vId, weight });
		adj.get(vId).push({ to: uId, weight });
	}

	const floor0Bounds = boundaries.filter(
		(b) => (Number(b.floorOrder) || 0) === 0,
	);
	const findVoidCourtyardBuilding = (coords) => {
		if (!Array.isArray(coords) || floor0Bounds.length === 0) return null;
		const matched = findBoundaryContainingPoint(floor0Bounds, coords, 0);
		if (!matched?.building) return null;
		const cBldg =
			resolveCanonicalBuildingName(matched.building) || matched.building;
		const model0 = floorModels.get(`${cBldg}__0`);
		const insideVoid = model0?.faces?.some(
			(f) =>
				f.role === "VOID" &&
				isPointInPolygonWithHoles(coords, f.ring, f.holes || []),
		);
		return insideVoid ? cBldg : null;
	};

	const findContainingRoom = (coords, bldg, flOrder) => {
		if (!bldg || bldg === "OUTDOOR" || bldg === "ALL") return null;
		const cBldg = resolveCanonicalBuildingName(bldg) || bldg;
		const model = floorModels.get(`${cBldg}__${Number(flOrder) || 0}`);
		if (!model) return null;
		return (
			model.faces.find(
				(f) =>
					f.role === "ROOM" &&
					isPointInPolygonWithHoles(coords, f.ring, f.holes || []),
			) || null
		);
	};

	// Same-room check: if start and end sit strictly inside the exact same ROOM on the same floor,
	// route inside that room (via internal room navNodes if connected, else direct line) instead of bouncing out to a perimeter DOOR.
	const startRoom = findContainingRoom(
		startCoords,
		startBuilding,
		startFloorOrder,
	);
	const endRoom = findContainingRoom(endCoords, endBuilding, endFloorOrder);
	if (
		startRoom &&
		endRoom &&
		startRoom.id === endRoom.id &&
		Number(startFloorOrder) === Number(endFloorOrder) &&
		matchesBuilding(startBuilding, endBuilding)
	) {
		const sFloor = Number(startFloorOrder) || 0;
		const cBldg = resolveCanonicalBuildingName(startBuilding) || startBuilding;
		const roomInternalNodes = nodes.filter(
			(n) =>
				(Number(n.floorOrder) || 0) === sFloor &&
				!isPortalNode(n) &&
				isPointInPolygonWithHoles(n.coords, startRoom.ring, startRoom.holes || []),
		);
		let intraCoords = [startCoords, endCoords];
		let intraNodes = [
			{
				id: "__same_room_direct__",
				type: "NODE",
				building: cBldg,
				floorOrder: sFloor,
				coords: endCoords,
			},
		];
		if (roomInternalNodes.length >= 2) {
			const internalIds = new Set(roomInternalNodes.map((n) => n.id));
			let sInt = roomInternalNodes[0];
			let eInt = roomInternalNodes[0];
			let sMin = Infinity;
			let eMin = Infinity;
			for (const rn of roomInternalNodes) {
				const ds = haversineMeters(startCoords, rn.coords);
				if (ds < sMin) {
					sMin = ds;
					sInt = rn;
				}
				const de = haversineMeters(endCoords, rn.coords);
				if (de < eMin) {
					eMin = de;
					eInt = rn;
				}
			}
			// Mini Dijkstra strictly inside roomInternalNodes
			const rDist = new Map([[sInt.id, 0]]);
			const rPrev = new Map();
			const rVis = new Set();
			while (true) {
				let uId = null;
				let best = Infinity;
				for (const [id, d] of rDist.entries()) {
					if (!rVis.has(id) && d < best) {
						best = d;
						uId = id;
					}
				}
				if (!uId || uId === eInt.id) break;
				rVis.add(uId);
				for (const edge of adj.get(uId) || []) {
					if (!internalIds.has(edge.to) || rVis.has(edge.to)) continue;
					const alt = best + edge.weight;
					if (alt < (rDist.get(edge.to) ?? Infinity)) {
						rDist.set(edge.to, alt);
						rPrev.set(edge.to, uId);
					}
				}
			}
			const subPath = [];
			let currR = eInt.id;
			while (currR) {
				subPath.unshift(nodeMap.get(currR));
				if (currR === sInt.id) break;
				currR = rPrev.get(currR);
			}
			if (subPath[0]?.id === sInt.id && sInt.id !== eInt.id) {
				intraCoords = [startCoords, ...subPath.map((n) => n.coords), endCoords];
				intraNodes = subPath;
			}
		}
		let directMeters = 0;
		for (let k = 0; k < intraCoords.length - 1; k++) {
			directMeters += haversineMeters(intraCoords[k], intraCoords[k + 1]);
		}
		return {
			pathNodes: intraNodes,
			totalDistanceMeters: Math.round(directMeters),
			floorsClimbed: 0,
			estimatedSeconds: Math.max(1, Math.round(directMeters / 1.45)),
			geojson: {
				type: "FeatureCollection",
				features: [
					{
						type: "Feature",
						properties: {
							floorOrder: sFloor,
							building: cBldg,
							isOutdoor: false,
							isStair: false,
						},
						geometry: {
							type: "LineString",
							coordinates: intraCoords,
						},
					},
				],
			},
		};
	}

	const outerGatewayDoorIds = new Set();
	const outerGatewayDoorsByBldg = new Map(); // canonicalBuilding -> Set(doorNodeId)
	const courtyardGatewayDoorMap = new Map(); // canonicalBuilding -> Set(doorNodeId)

	const getFloorPool = (
		bldg,
		flOrder,
		coords = null,
		{
			excludeVirtualDoors = false,
			outdoorZone = null,
			preferOuterGatewayDoors = false,
			targetBuilding = null,
		} = {},
	) => {
		const fl = Number(flOrder) || 0;
		const floorNodes = nodes.filter((n) => (Number(n.floorOrder) || 0) === fl);
		if (bldg === "OUTDOOR") {
			if (fl !== 0) return [];
			const voidBldg =
				outdoorZone === "VOID"
					? null
					: coords
						? findVoidCourtyardBuilding(coords)
						: null;
			if (outdoorZone === "VOID" || voidBldg) {
				const targetBldg = outdoorZone === "VOID" ? null : voidBldg;
				const voidPool = floorNodes.filter((n) => {
					const nVoid = findVoidCourtyardBuilding(n.coords);
					return targetBldg ? nVoid === targetBldg : Boolean(nVoid);
				});
				if (!excludeVirtualDoors && targetBldg) {
					for (const dId of courtyardGatewayDoorMap.get(targetBldg) || []) {
						const dn = nodeMap.get(dId);
						if (dn && (adj.get(dId) || []).length > 0) voidPool.push(dn);
					}
				}
				return voidPool;
			}

			// Strictly exterior campus ground (exclude VOID courtyard holes & indoor buildings)
			const exteriorNodes = floorNodes.filter((n) => {
				if (floor0Bounds.length === 0) {
					return n.type === "OUTDOOR" || n.building === "OUTDOOR";
				}
				if (findBoundaryContainingPoint(floor0Bounds, n.coords, 0)) {
					return false;
				}
				return isNodeOutdoor(boundaries, n, floorModels);
			});
			if (excludeVirtualDoors) return exteriorNodes;
			if (exteriorNodes.length > 0 && !preferOuterGatewayDoors) {
				return exteriorNodes;
			}

			if (preferOuterGatewayDoors && targetBuilding) {
				const cTarget =
					resolveCanonicalBuildingName(targetBuilding) || targetBuilding;
				const scopedDoors = [];
				for (const dId of outerGatewayDoorsByBldg.get(cTarget) || []) {
					const dn = nodeMap.get(dId);
					if (dn && (adj.get(dId) || []).length > 0) {
						scopedDoors.push(dn);
					}
				}
				if (scopedDoors.length > 0) return scopedDoors;
			}

			const connectedOuterDoors = [];
			for (const dId of outerGatewayDoorIds) {
				const dn = nodeMap.get(dId);
				if (dn && (adj.get(dId) || []).length > 0) {
					connectedOuterDoors.push(dn);
				}
			}
			// Never leak indoor STAIRS/CORRIDOR nodes behind solid perimeter walls!
			return exteriorNodes.length > 0
				? [...exteriorNodes, ...connectedOuterDoors]
				: connectedOuterDoors;
		}
		if (bldg && bldg !== "ALL") {
			return floorNodes.filter(
				(n) =>
					!isNodeOutdoor(boundaries, n, floorModels) &&
					matchesBuilding(resolveNodeBuilding(boundaries, n, floorModels), bldg),
			);
		}
		return floorNodes.length > 0 ? floorNodes : nodes;
	};

	const connectCoordsToPool = (targetNodeId, coords, bldg, flOrder, poolOpts = {}) => {
		const pool = getFloorPool(bldg, flOrder, coords, poolOpts);
		if (pool.length === 0) return { node: null, edgePair: null };
		const poolIds = new Set(pool.map((n) => n.id));
		let bestNode = pool[0];
		let bestNodeDist = Infinity;
		for (const n of pool) {
			const d = haversineMeters(coords, n.coords);
			if (d < bestNodeDist) {
				bestNodeDist = d;
				bestNode = n;
			}
		}

		// Orthogonal T-junction projection onto closest intra-floor edge in pool
		const { toM } = toLocalMetersFactory(coords);
		let bestEdgeAttach = null;
		let bestProjDist = bestNodeDist * 0.95;
		for (const [uId, vId] of allEdges) {
			if (!poolIds.has(uId) || !poolIds.has(vId)) continue;
			const u = nodeMap.get(uId);
			const v = nodeMap.get(vId);
			if (!u || !v || u.floorOrder !== v.floorOrder) continue;
			const aM = toM(u.coords);
			const bM = toM(v.coords);
			const dx = bM[0] - aM[0];
			const dy = bM[1] - aM[1];
			const lenSq = dx * dx + dy * dy;
			if (lenSq < 1e-4) continue;
			const t = Math.max(
				0,
				Math.min(1, ((0 - aM[0]) * dx + (0 - aM[1]) * dy) / lenSq),
			);
			if (t <= 0.02 || t >= 0.98) continue;
			const projCoord = [
				Number((u.coords[0] + t * (v.coords[0] - u.coords[0])).toFixed(7)),
				Number((u.coords[1] + t * (v.coords[1] - u.coords[1])).toFixed(7)),
			];
			const dProj = haversineMeters(coords, projCoord);
			if (dProj < bestProjDist) {
				bestProjDist = dProj;
				bestEdgeAttach = { u, v, projCoord, dProj };
			}
		}

		if (bestEdgeAttach) {
			const { u, v, projCoord } = bestEdgeAttach;
			const vId = `__t_${targetNodeId}__`;
			const vNode = {
				id: vId,
				type: "NODE",
				building:
					bldg === "OUTDOOR"
						? "OUTDOOR"
						: resolveNodeBuilding(boundaries, u, floorModels),
				floorOrder: Number(u.floorOrder) || 0,
				coords: projCoord,
				isVirtual: true,
			};
			nodeMap.set(vId, vNode);
			adj.set(vId, []);
			const du = haversineMeters(projCoord, u.coords);
			const dv = haversineMeters(projCoord, v.coords);
			adj.get(vId).push({ to: u.id, weight: du });
			adj.get(u.id).push({ to: vId, weight: du });
			adj.get(vId).push({ to: v.id, weight: dv });
			adj.get(v.id).push({ to: vId, weight: dv });
			return { node: vNode, edgePair: [u.id, v.id], dist: bestEdgeAttach.dProj };
		}

		return { node: bestNode, edgePair: null, dist: bestNodeDist };
	};

	// Wire every DOOR into graph across the two faces flanking its wall segment
	const doorRoomLinks = new Map(); // faceId -> [doorGraphId...]
	for (const model of floorModels.values()) {
		for (const door of model.doors || []) {
			if (!Array.isArray(door.coords)) continue;
			const { toM, toDeg } = toLocalMetersFactory(door.coords);
			let bestSeg = null;
			let bestSegDist = 0.65;
			for (const seg of model.wallSegments) {
				const aM = toM(seg.a);
				const bM = toM(seg.b);
				const dx = bM[0] - aM[0];
				const dy = bM[1] - aM[1];
				const l2 = dx * dx + dy * dy;
				if (l2 < 1e-6) continue;
				const t = Math.max(0, Math.min(1, ((0 - aM[0]) * dx + (0 - aM[1]) * dy) / l2));
				const px = aM[0] + t * dx;
				const py = aM[1] + t * dy;
				const d = Math.hypot(px, py);
				if (d < bestSegDist) {
					bestSegDist = d;
					bestSeg = seg;
				}
			}
			if (!bestSeg) continue;

			const dNodeId = `__door_${door.id}__`;
			const dNode = {
				id: dNodeId,
				type: "DOOR",
				building: model.building,
				floorOrder: model.floorOrder,
				coords: door.coords,
				isDoorVirtual: true,
			};
			nodeMap.set(dNodeId, dNode);
			adj.set(dNodeId, []);

			// If user explicitly placed a navNode at the Door's midpoint (< 0.45m), wire it with zero-friction link
			for (const n of nodes) {
				if ((Number(n.floorOrder) || 0) !== model.floorOrder) continue;
				const dMid = haversineMeters(door.coords, n.coords);
				if (dMid <= 0.45) {
					adj.get(dNodeId).push({ to: n.id, weight: Math.max(0.05, dMid) });
					adj.get(n.id).push({ to: dNodeId, weight: Math.max(0.05, dMid) });
				}
			}

			const aM = toM(bestSeg.a);
			const bM = toM(bestSeg.b);
			const sdx = bM[0] - aM[0];
			const sdy = bM[1] - aM[1];
			const sLen = Math.hypot(sdx, sdy) || 1;
			const perp = [(-sdy / sLen) * 0.65, (sdx / sLen) * 0.65];
			const sideA = toDeg(perp);
			const sideB = toDeg([-perp[0], -perp[1]]);

			let linkedToCorridorOrOutdoor = false;
			for (const sidePt of [sideA, sideB]) {
				const sideFace = model.faces.find((f) =>
					isPointInPolygonWithHoles(sidePt, f.ring, f.holes || []),
				);
				if ((!sideFace || sideFace?.role === "VOID") && model.floorOrder === 0) {
					const sideInVoid =
						sideFace?.role === "VOID" ||
						Boolean(findVoidCourtyardBuilding(sidePt));
					const sideStrictlyOutside =
						!sideInVoid &&
						(floor0Bounds.length === 0 ||
							!findBoundaryContainingPoint(floor0Bounds, sidePt, 0));

					if (sideStrictlyOutside) {
						outerGatewayDoorIds.add(dNodeId);
						const cDoorBldg =
							resolveCanonicalBuildingName(model.building) || model.building;
						if (!outerGatewayDoorsByBldg.has(cDoorBldg)) {
							outerGatewayDoorsByBldg.set(cDoorBldg, new Set());
						}
						outerGatewayDoorsByBldg.get(cDoorBldg).add(dNodeId);
						const att = connectCoordsToPool(
							`${dNodeId}_out`,
							door.coords,
							"OUTDOOR",
							0,
							{ excludeVirtualDoors: true, outdoorZone: "EXTERIOR" },
						);
						if (att.node) {
							const w = haversineMeters(door.coords, att.node.coords);
							adj.get(dNodeId).push({ to: att.node.id, weight: w });
							adj.get(att.node.id).push({ to: dNodeId, weight: w });
							linkedToCorridorOrOutdoor = true;
						}
					} else if (sideInVoid) {
						const cBldg =
							resolveCanonicalBuildingName(model.building) || model.building;
						if (!courtyardGatewayDoorMap.has(cBldg)) {
							courtyardGatewayDoorMap.set(cBldg, new Set());
						}
						courtyardGatewayDoorMap.get(cBldg).add(dNodeId);
						const att = connectCoordsToPool(
							`${dNodeId}_void`,
							sidePt,
							"OUTDOOR",
							0,
							{ excludeVirtualDoors: true, outdoorZone: "VOID" },
						);
						if (att.node) {
							const w = haversineMeters(door.coords, att.node.coords);
							adj.get(dNodeId).push({ to: att.node.id, weight: w });
							adj.get(att.node.id).push({ to: dNodeId, weight: w });
							linkedToCorridorOrOutdoor = true;
						}
					}
				} else if (sideFace?.role === "ROOM") {
					if (!doorRoomLinks.has(sideFace.id)) {
						doorRoomLinks.set(sideFace.id, []);
					}
					doorRoomLinks.get(sideFace.id).push(dNodeId);
				} else if (sideFace?.role === "CORRIDOR") {
					const att = connectCoordsToPool(
						`${dNodeId}_cor`,
						door.coords,
						model.building,
						model.floorOrder,
					);
					if (att.node) {
						const w = haversineMeters(door.coords, att.node.coords);
						adj.get(dNodeId).push({ to: att.node.id, weight: w });
						adj.get(att.node.id).push({ to: dNodeId, weight: w });
						linkedToCorridorOrOutdoor = true;
					}
				}
			}

			// Resilient fallback: if neither side was explicitly tagged CORRIDOR yet, still bridge door midpoint to floor pool
			if (!linkedToCorridorOrOutdoor) {
				const att = connectCoordsToPool(
					`${dNodeId}_fallback`,
					door.coords,
					model.building,
					model.floorOrder,
				);
				if (att.node) {
					const w = haversineMeters(door.coords, att.node.coords);
					adj.get(dNodeId).push({ to: att.node.id, weight: w });
					adj.get(att.node.id).push({ to: dNodeId, weight: w });
				}
			}
		}
	}

	// Pass-through intra-room links if a ROOM has multiple doors (room-to-room traversal)
	for (const dIds of doorRoomLinks.values()) {
		for (let i = 0; i < dIds.length; i++) {
			for (let j = i + 1; j < dIds.length; j++) {
				const da = nodeMap.get(dIds[i]);
				const db = nodeMap.get(dIds[j]);
				const w = haversineMeters(da.coords, db.coords);
				adj.get(dIds[i]).push({ to: dIds[j], weight: w });
				adj.get(dIds[j]).push({ to: dIds[i], weight: w });
			}
		}
	}

	const attachEndpoint = (tempId, coords, bldg, flOrder, poolOpts = {}) => {
		if (bldg && bldg !== "OUTDOOR" && bldg !== "ALL") {
			const cBldg = resolveCanonicalBuildingName(bldg) || bldg;
			const model = floorModels.get(`${cBldg}__${Number(flOrder) || 0}`);
			if (model) {
				const insideRoom = model.faces.find(
					(f) =>
						f.role === "ROOM" &&
						isPointInPolygonWithHoles(coords, f.ring, f.holes || []),
				);
				const roomDoors = insideRoom ? doorRoomLinks.get(insideRoom.id) : null;
				if (roomDoors && roomDoors.length > 0) {
					// Strict doorway entry/exit: attach endpoint directly onto the room's wall DOOR(s)
					let bestDoor = nodeMap.get(roomDoors[0]);
					let bestDDist = Infinity;
					for (const dId of roomDoors) {
						const dn = nodeMap.get(dId);
						const dist = haversineMeters(coords, dn.coords);
						if (dist < bestDDist) {
							bestDDist = dist;
							bestDoor = dn;
						}
					}
					return { node: bestDoor, edgePair: null };
				}
			}
		}

		const att = connectCoordsToPool(tempId, coords, bldg, flOrder, poolOpts);
		return { node: att.node, edgePair: att.edgePair };
	};

	const runDijkstraPass = (sAttach, eAttach) => {
		const sNode = sAttach.node;
		const eNode = eAttach.node;
		if (!sNode || !eNode) return { sNode, eNode, pathNodes: [] };

		// Connect start & end directly if both T-junction projected onto the exact same corridor edge
		if (sAttach.edgePair && eAttach.edgePair) {
			const [su, sv] = sAttach.edgePair;
			const [eu, ev] = eAttach.edgePair;
			if ((su === eu && sv === ev) || (su === ev && sv === eu)) {
				const directDist = haversineMeters(sNode.coords, eNode.coords);
				adj.get(sNode.id).push({ to: eNode.id, weight: directDist });
				adj.get(eNode.id).push({ to: sNode.id, weight: directDist });
			}
		}

		const gScore = new Map(Array.from(nodeMap.keys(), (id) => [id, Infinity]));
		const prev = new Map();
		const visited = new Set();
		gScore.set(sNode.id, 0);

		while (visited.size < nodeMap.size) {
			let uId = null;
			let best = Infinity;
			for (const [id, s] of gScore.entries()) {
				if (!visited.has(id) && s < best) {
					best = s;
					uId = id;
				}
			}
			if (!uId || uId === eNode.id) break;
			visited.add(uId);

			for (const edge of adj.get(uId) || []) {
				if (visited.has(edge.to)) continue;
				const alt = best + edge.weight;
				if (alt < gScore.get(edge.to)) {
					gScore.set(edge.to, alt);
					prev.set(edge.to, uId);
				}
			}
		}

		const foundPath = [];
		let curr = eNode.id;
		while (curr) {
			foundPath.unshift(nodeMap.get(curr));
			if (curr === sNode.id) break;
			curr = prev.get(curr);
		}
		return { sNode, eNode, pathNodes: foundPath };
	};

	const segsCross2D = (a, b, c, d) => {
		const rx = b[0] - a[0];
		const ry = b[1] - a[1];
		const sx = d[0] - c[0];
		const sy = d[1] - c[1];
		const denom = rx * sy - ry * sx;
		if (Math.abs(denom) < 1e-14) return false;
		const qpx = c[0] - a[0];
		const qpy = c[1] - a[1];
		const t = (qpx * sy - qpy * sx) / denom;
		const u = (qpx * ry - qpy * rx) / denom;
		return t > 1e-5 && t < 1 - 1e-5 && u > 1e-5 && u < 1 - 1e-5;
	};

	const segmentCrossesRing = (a, b, ring = []) => {
		if (!Array.isArray(ring) || ring.length < 3) return false;
		for (let i = 0; i < ring.length - 1; i++) {
			if (segsCross2D(a, b, ring[i], ring[i + 1])) return true;
		}
		if (
			ring[0][0] !== ring[ring.length - 1][0] ||
			ring[0][1] !== ring[ring.length - 1][1]
		) {
			if (segsCross2D(a, b, ring[ring.length - 1], ring[0])) return true;
		}
		const mid = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
		return isPointInRing(mid, ring);
	};

	const buildExteriorCampusCoords = (a, b, bnds = []) => {
		const blocking = (bnds || []).filter((bnd) =>
			segmentCrossesRing(a, b, bnd.ring),
		);
		if (blocking.length === 0) return [a, b];
		// Detour cleanly outside the first blocking building footprint via its buffered exterior vertices
		const ring = blocking[0].ring || [];
		const openPts =
			ring.length >= 2 &&
			ring[0][0] === ring[ring.length - 1][0] &&
			ring[0][1] === ring[ring.length - 1][1]
				? ring.slice(0, -1)
				: ring;
		if (openPts.length < 3) return [a, b];
		const centroid = getRingCentroid(ring);
		const padded = openPts.map((pt) => {
			const dx = pt[0] - centroid[0];
			const dy = pt[1] - centroid[1];
			const len = Math.hypot(dx, dy) || 1;
			const pad = 0.000018; // ~2m outside perimeter wall
			return [
				Number((pt[0] + (dx / len) * pad).toFixed(7)),
				Number((pt[1] + (dy / len) * pad).toFixed(7)),
			];
		});
		let bestWaypoints = null;
		let bestLen = Infinity;
		const nearestIdx = (pt) => {
			let idx = 0;
			let dMin = Infinity;
			padded.forEach((v, k) => {
				const d = haversineMeters(pt, v);
				if (d < dMin) {
					dMin = d;
					idx = k;
				}
			});
			return idx;
		};
		const sIdx = nearestIdx(a);
		const eIdx = nearestIdx(b);
		const nLen = padded.length;
		for (const dir of [1, -1]) {
			const arc = [a];
			let cur = sIdx;
			let guard = 0;
			while (guard <= nLen) {
				arc.push(padded[cur]);
				if (cur === eIdx) break;
				cur = (cur + dir + nLen) % nLen;
				guard++;
			}
			arc.push(b);
			let distSum = 0;
			for (let k = 0; k < arc.length - 1; k++) {
				distSum += haversineMeters(arc[k], arc[k + 1]);
			}
			if (distSum < bestLen) {
				bestLen = distSum;
				bestWaypoints = arc;
			}
		}
		return bestWaypoints || [a, b];
	};

	const bothStrictlyExteriorOutdoor =
		startBuilding === "OUTDOOR" &&
		endBuilding === "OUTDOOR" &&
		!findVoidCourtyardBuilding(startCoords) &&
		!findVoidCourtyardBuilding(endCoords);

	const indoorCounterpartBldg =
		startBuilding === "OUTDOOR" &&
		endBuilding &&
		endBuilding !== "OUTDOOR" &&
		endBuilding !== "ALL"
			? endBuilding
			: endBuilding === "OUTDOOR" &&
				  startBuilding &&
				  startBuilding !== "OUTDOOR" &&
				  startBuilding !== "ALL"
				? startBuilding
				: null;

	const startPoolOpts = bothStrictlyExteriorOutdoor
		? { excludeVirtualDoors: true, outdoorZone: "EXTERIOR" }
		: {};
	const endPoolOpts = bothStrictlyExteriorOutdoor
		? { excludeVirtualDoors: true, outdoorZone: "EXTERIOR" }
		: {};

	let startAttach = attachEndpoint(
		"__virtual_start__",
		startCoords,
		startBuilding,
		startFloorOrder,
		startPoolOpts,
	);
	let endAttach = attachEndpoint(
		"__virtual_end__",
		endCoords,
		endBuilding,
		endFloorOrder,
		endPoolOpts,
	);
	let { sNode: startNode, eNode: endNode, pathNodes } = runDijkstraPass(
		startAttach,
		endAttach,
	);

	// Two strictly exterior campus clicks without a connecting sidewalk graph walk directly outdoors (avoiding buildings, never via unrelated doors)
	if (
		bothStrictlyExteriorOutdoor &&
		(pathNodes.length === 0 || pathNodes[0]?.id !== startNode?.id)
	) {
		const extCoords = buildExteriorCampusCoords(
			startCoords,
			endCoords,
			floor0Bounds,
		);
		let extMeters = 0;
		for (let i = 0; i < extCoords.length - 1; i++) {
			extMeters += haversineMeters(extCoords[i], extCoords[i + 1]);
		}
		return {
			pathNodes: [],
			totalDistanceMeters: Math.round(extMeters),
			floorsClimbed: 0,
			estimatedSeconds: Math.max(1, Math.round(extMeters / 1.45)),
			geojson: {
				type: "FeatureCollection",
				features: [
					{
						type: "Feature",
						properties: {
							floorOrder: 0,
							toFloorOrder: 0,
							building: "OUTDOOR",
							isOutdoor: true,
							isStair: false,
						},
						geometry: {
							type: "LineString",
							coordinates: extCoords,
						},
					},
				],
			},
		};
	}

	// Fallback if exterior sidewalk nodes were disconnected (or snapped onto another building's closer door instead of target building)
	if (
		(pathNodes.length === 0 || pathNodes[0]?.id !== startNode?.id) &&
		outerGatewayDoorIds.size > 0 &&
		(startBuilding === "OUTDOOR" || endBuilding === "OUTDOOR")
	) {
		const retryStart =
			startBuilding === "OUTDOOR"
				? attachEndpoint(
						"__virtual_start_gw__",
						startCoords,
						startBuilding,
						startFloorOrder,
						{
							preferOuterGatewayDoors: true,
							targetBuilding: indoorCounterpartBldg,
						},
					)
				: startAttach;
		const retryEnd =
			endBuilding === "OUTDOOR"
				? attachEndpoint(
						"__virtual_end_gw__",
						endCoords,
						endBuilding,
						endFloorOrder,
						{
							preferOuterGatewayDoors: true,
							targetBuilding: indoorCounterpartBldg,
						},
					)
				: endAttach;
		const retryRes = runDijkstraPass(retryStart, retryEnd);
		if (
			retryRes.pathNodes.length > 0 &&
			retryRes.pathNodes[0]?.id === retryRes.sNode?.id
		) {
			startNode = retryRes.sNode;
			endNode = retryRes.eNode;
			pathNodes = retryRes.pathNodes;
		}
	}

	if (!startNode || !endNode) {
		if (
			(startBuilding === "OUTDOOR" && !startNode) ||
			(endBuilding === "OUTDOOR" && !endNode)
		) {
			setDiag(
				"MISSING_ENTRANCE_LINK",
				"Không có nút Ngoài sân hoặc Cửa [D] trên tường bao Tầng G nối ra/vào tòa nhà.",
			);
		} else {
			setDiag(
				"NO_FLOOR_POOL",
				"Không tìm thấy nút đồ thị nào trên tầng được chọn để bắt đầu dẫn đường.",
			);
		}
		return null;
	}

	if (pathNodes.length === 0 || pathNodes[0]?.id !== startNode.id) {
		const canReachFloor0InBuilding = (fromNodeId, targetBldg) => {
			if (
				!fromNodeId ||
				!targetBldg ||
				targetBldg === "OUTDOOR" ||
				targetBldg === "ALL"
			) {
				return false;
			}
			const seen = new Set([fromNodeId]);
			const queue = [fromNodeId];
			while (queue.length > 0) {
				const curId = queue.shift();
				const curNode = nodeMap.get(curId);
				if (
					curNode &&
					!curNode.isVirtual &&
					(Number(curNode.floorOrder) || 0) === 0 &&
					!isNodeOutdoor(boundaries, curNode, floorModels) &&
					matchesBuilding(
						resolveNodeBuilding(boundaries, curNode, floorModels),
						targetBldg,
					)
				) {
					return true;
				}
				for (const edge of adj.get(curId) || []) {
					if (!seen.has(edge.to)) {
						seen.add(edge.to);
						queue.push(edge.to);
					}
				}
			}
			return false;
		};

		if (indoorCounterpartBldg) {
			const cTarget =
				resolveCanonicalBuildingName(indoorCounterpartBldg) ||
				indoorCounterpartBldg;
			const hasTargetOuterDoors =
				(outerGatewayDoorsByBldg.get(cTarget)?.size || 0) > 0;
			const indoorSideNode = startBuilding === "OUTDOOR" ? endNode : startNode;
			const reachesFloor0 =
				(Number(indoorSideNode?.floorOrder) || 0) === 0 ||
				canReachFloor0InBuilding(indoorSideNode?.id, indoorCounterpartBldg);

			if (!reachesFloor0 && Number(startFloorOrder) !== Number(endFloorOrder)) {
				setDiag(
					"MISSING_PORTAL_LINK",
					`Không tìm thấy đường giữa Tầng ${startFloorOrder} và Tầng ${endFloorOrder}: chưa có Cầu thang/Thang máy thông tầng hoặc chưa nối dây vào hành lang.`,
				);
			} else if (!hasTargetOuterDoors) {
				setDiag(
					"MISSING_ENTRANCE_LINK",
					`Không tìm thấy cửa vào ${cTarget}: hãy gắn Cửa [D] lên tường bao Tầng G hoặc nối dây [N] ra sân.`,
				);
			} else {
				setDiag(
					"MISSING_ENTRANCE_LINK",
					`Không tìm thấy đường vào ${cTarget} từ Ngoài sân: hãy kiểm tra dây nối [N] từ Cửa [D] Tầng G tới Cầu thang/Thang máy.`,
				);
			}
		} else if (Number(startFloorOrder) !== Number(endFloorOrder)) {
			setDiag(
				"MISSING_PORTAL_LINK",
				`Không tìm thấy đường giữa Tầng ${startFloorOrder} và Tầng ${endFloorOrder}: chưa có Cầu thang/Thang máy thông tầng hoặc chưa nối dây vào hành lang.`,
			);
		} else if (startBuilding !== endBuilding) {
			setDiag(
				"MISSING_ENTRANCE_LINK",
				"Không tìm thấy đường giữa Ngoài sân và Trong tòa nhà: hãy gắn Cửa [D] lên tường bao Tầng G hoặc nối dây [N] qua cửa chính.",
			);
		} else {
			setDiag(
				"DISCONNECTED_GRAPH",
				"Không tìm thấy đường: Điểm đi và Điểm đến nằm ở 2 cụm đồ thị rời nhau (hãy dùng công cụ [N] nối liền các đoạn hành lang bị đứt).",
			);
		}
		return null;
	}

	let totalMeters =
		haversineMeters(startCoords, startNode.coords) +
		haversineMeters(endNode.coords, endCoords);
	let floorsClimbed = 0;

	const startLegOutdoor = isSegmentOutdoorGeometry(
		boundaries,
		{ floorOrder: startFloorOrder, coords: startCoords, building: startBuilding, type: startBuilding === "OUTDOOR" ? "OUTDOOR" : "NODE" },
		startNode,
		floorModels,
	);
	const endLegOutdoor = isSegmentOutdoorGeometry(
		boundaries,
		endNode,
		{ floorOrder: endFloorOrder, coords: endCoords, building: endBuilding, type: endBuilding === "OUTDOOR" ? "OUTDOOR" : "NODE" },
		floorModels,
	);

	const features = [
		{
			type: "Feature",
			properties: {
				floorOrder: startFloorOrder,
				building: startLegOutdoor ? "OUTDOOR" : startBuilding,
				isOutdoor: startLegOutdoor,
				isStair: false,
			},
			geometry: {
				type: "LineString",
				coordinates: [startCoords, startNode.coords],
			},
		},
	];

	for (let i = 0; i < pathNodes.length - 1; i++) {
		const a = pathNodes[i];
		const b = pathNodes[i + 1];
		totalMeters += haversineMeters(a.coords, b.coords);
		floorsClimbed += Math.abs(b.floorOrder - a.floorOrder);
		const isStair = a.floorOrder !== b.floorOrder;
		const isOutdoor =
			!isStair && isSegmentOutdoorGeometry(boundaries, a, b, floorModels);
		const bldgA = resolveNodeBuilding(boundaries, a, floorModels);
		const bldgB = resolveNodeBuilding(boundaries, b, floorModels);
		const segBuilding = isOutdoor
			? "OUTDOOR"
			: bldgA && bldgA !== "OUTDOOR"
				? bldgA
				: bldgB || a.building || b.building;
		features.push({
			type: "Feature",
			properties: {
				floorOrder: isStair
					? Math.min(a.floorOrder, b.floorOrder)
					: b.floorOrder,
				toFloorOrder: isStair
					? Math.max(a.floorOrder, b.floorOrder)
					: b.floorOrder,
				building: segBuilding,
				isOutdoor,
				isStair,
			},
			geometry: {
				type: "LineString",
				coordinates: [a.coords, b.coords],
			},
		});
	}

	features.push({
		type: "Feature",
		properties: {
			floorOrder: endFloorOrder,
			building: endLegOutdoor ? "OUTDOOR" : endBuilding,
			isOutdoor: endLegOutdoor,
			isStair: false,
		},
		geometry: {
			type: "LineString",
			coordinates: [endNode.coords, endCoords],
		},
	});

	return {
		pathNodes,
		totalDistanceMeters: Math.round(totalMeters),
		floorsClimbed,
		estimatedSeconds: Math.round(totalMeters / 1.45 + floorsClimbed * 14),
		geojson: { type: "FeatureCollection", features },
	};
}

/**
 * Elevate A* route onto 3D extruded ribbons matching each floor's true elevation
 * (so indoor segments sit cleanly above the active floor slab instead of flat at z=0).
 */
export function buildRoute3dLayers(
	routeResult,
	{ selectedBuilding = "ALL", activeFloorOrder = 0 } = {},
) {
	const ribbons = [];
	const glowRibbons = [];
	const stairBeacons = [];

	const emptyResult = {
		ribbonsGeoJSON: { type: "FeatureCollection", features: ribbons },
		glowGeoJSON: { type: "FeatureCollection", features: glowRibbons },
		stairBeaconsGeoJSON: { type: "FeatureCollection", features: stairBeacons },
	};

	const features = routeResult?.geojson?.features;
	if (!Array.isArray(features) || features.length === 0) return emptyResult;

	const isOverviewAll = !selectedBuilding || selectedBuilding === "ALL";
	const isAllFloors = isOverviewAll || activeFloorOrder === "ALL";
	const targetFloor = Number(activeFloorOrder) || 0;

	const mergedChains = [];
	features.forEach((feat, idx) => {
		const coords = feat?.geometry?.coordinates;
		const props = feat?.properties || {};
		if (!Array.isArray(coords) || coords.length < 2) return;

		if (props.isStair) {
			const minF = Number(props.floorOrder) || 0;
			const maxF = Number(props.toFloorOrder ?? minF + 1);
			const sameBuilding =
				isOverviewAll ||
				!props.building ||
				matchesBuilding(props.building, selectedBuilding);
			if (!isAllFloors && sameBuilding && minF > targetFloor) return;
			const topF =
				isAllFloors || !sameBuilding ? maxF : Math.min(maxF, targetFloor);
			const pt = coords[1] || coords[0];
			const dLng = 0.0000032;
			const dLat = 0.0000032;
			const padRing = [
				[pt[0] - dLng, pt[1] - dLat],
				[pt[0] + dLng, pt[1] - dLat],
				[pt[0] + dLng, pt[1] + dLat],
				[pt[0] - dLng, pt[1] + dLat],
				[pt[0] - dLng, pt[1] - dLat],
			];
			stairBeacons.push({
				type: "Feature",
				id: `route-stair-${idx}`,
				properties: {
					floorOrder: minF,
					isGhost: !sameBuilding,
					color: sameBuilding ? "#a855f7" : "#c084fc",
					baseHeight: minF * FLOOR_ELEVATION_STEP + FLOOR_SLAB_HEIGHT,
					extrudeHeight: topF * FLOOR_ELEVATION_STEP + FLOOR_SLAB_HEIGHT + 0.42,
				},
				geometry: {
					type: "Polygon",
					coordinates: [padRing],
				},
			});
			return;
		}

		const segFloor = Number(props.floorOrder) || 0;
		const isOutdoor = Boolean(props.isOutdoor);
		const sameBuilding =
			isOverviewAll ||
			isOutdoor ||
			!props.building ||
			matchesBuilding(props.building, selectedBuilding);

		let isGhost = false;
		if (!isAllFloors) {
			if (!isOutdoor && sameBuilding && segFloor > targetFloor) {
				return;
			}
			if (isOutdoor) {
				isGhost = targetFloor > 0;
			} else if (!sameBuilding || segFloor < targetFloor) {
				isGhost = true;
			}
		}

		const prev = mergedChains[mergedChains.length - 1];
		const canMerge =
			prev &&
			prev.isOutdoor === isOutdoor &&
			prev.segFloor === segFloor &&
			prev.isGhost === isGhost &&
			matchesBuilding(
				prev.building || "OUTDOOR",
				props.building || "OUTDOOR",
			) &&
			Math.hypot(
				prev.coords[prev.coords.length - 1][0] - coords[0][0],
				prev.coords[prev.coords.length - 1][1] - coords[0][1],
			) < 1e-6;

		if (canMerge) {
			prev.coords.push(...coords.slice(1));
		} else {
			mergedChains.push({
				idx,
				segFloor,
				isOutdoor,
				isGhost,
				building: props.building || "OUTDOOR",
				coords: [...coords],
			});
		}
	});

	mergedChains.forEach((chain) => {
		const { idx, segFloor, isOutdoor, isGhost, coords } = chain;
		const floorBase = segFloor * FLOOR_ELEVATION_STEP;
		const baseHeight = isOutdoor
			? 0.06
			: floorBase + FLOOR_SLAB_HEIGHT + 0.05;
		const extrudeHeight = isOutdoor
			? 0.28
			: floorBase + FLOOR_SLAB_HEIGHT + 0.28;

		const corePoly = buildMiteredPolylinePolygon(coords, 0.24, 0.24);
		const haloPoly = buildMiteredPolylinePolygon(coords, 0.44, 0.36);

		if (corePoly) {
			ribbons.push({
				type: "Feature",
				id: `route-seg-${idx}`,
				properties: {
					floorOrder: segFloor,
					isGhost,
					color: isGhost ? "#fb923c" : "#f97316",
					baseHeight,
					extrudeHeight,
				},
				geometry: {
					type: "Polygon",
					coordinates: corePoly,
				},
			});
		}

		if (haloPoly && !isGhost) {
			glowRibbons.push({
				type: "Feature",
				id: `route-glow-${idx}`,
				properties: {
					floorOrder: segFloor,
					isGhost: false,
					color: "#38bdf8",
					baseHeight: Math.max(0.02, baseHeight - 0.03),
					extrudeHeight: baseHeight + 0.08,
				},
				geometry: {
					type: "Polygon",
					coordinates: haloPoly,
				},
			});
		}
	});

	return {
		ribbonsGeoJSON: { type: "FeatureCollection", features: ribbons },
		glowGeoJSON: { type: "FeatureCollection", features: glowRibbons },
		stairBeaconsGeoJSON: { type: "FeatureCollection", features: stairBeacons },
	};
}

export function splitGhostActiveGeoJSON(fc) {
	const ghost = [];
	const active = [];
	for (const f of fc?.features || []) {
		if (f?.properties?.isGhost) ghost.push(f);
		else active.push(f);
	}
	return {
		ghost: { type: "FeatureCollection", features: ghost },
		active: { type: "FeatureCollection", features: active },
	};
}

// ============================================================================
// PHASE 3: VERTEX WELDING, ORIENTED DOOR GEOMETRY, PORTAL SYNC & COURTYARD CHECK
// ============================================================================

/**
 * Deduplicate 2D corner vertices across all walls on a floor so shared T/X/L-junctions
 * render a single clean dot with accurate degree count instead of stacked overlapping dots.
 */
export function deduplicateFloorVertices(
	walls = [],
	{ selectedWallId = null, toleranceMeters = 0.03 } = {},
) {
	if (!Array.isArray(walls) || walls.length === 0) return [];
	let refPt = null;
	for (const w of walls) {
		if (Array.isArray(w.line) && w.line.length > 0 && Array.isArray(w.line[0])) {
			refPt = w.line[0];
			break;
		}
	}
	if (!refPt) return [];

	const { toM } = toLocalMetersFactory(refPt);
	const clusters = [];

	for (const w of walls) {
		if (!Array.isArray(w.line) || w.line.length === 0) continue;
		const isSel = w.id === selectedWallId;
		const pts = isClosedRingLocal(w.line) ? w.line.slice(0, -1) : w.line;
		pts.forEach((pt, idx) => {
			if (!Array.isArray(pt) || pt.length < 2) return;
			const ptM = toM(pt);
			let found = null;
			for (const c of clusters) {
				if (Math.hypot(c.ptM[0] - ptM[0], c.ptM[1] - ptM[1]) <= toleranceMeters) {
					found = c;
					break;
				}
			}
			if (found) {
				found.degree += 1;
				found.isSelected = found.isSelected || isSel;
				found.members.push({ wallId: w.id, index: idx });
			} else {
				clusters.push({
					coord: [Number(pt[0].toFixed(6)), Number(pt[1].toFixed(6))],
					ptM,
					degree: 1,
					isSelected: isSel,
					entityId: w.id,
					members: [{ wallId: w.id, index: idx }],
				});
			}
		});
	}

	return clusters.map(({ ptM: _ptM, ...rest }) => rest);
}

/**
 * Move all wall endpoints sharing the dragged vertex's original coordinate together as a welded junction.
 * Holding Alt (`detachSingle = true`) moves only the single targeted wall vertex.
 */
export function dragWeldedWallVertices(
	walls = [],
	{
		wallId,
		vertexIndex,
		newCoord,
		detachSingle = false,
		toleranceMeters = 0.04,
	} = {},
) {
	if (!Array.isArray(walls) || !wallId || !Array.isArray(newCoord)) return walls;
	const targetWall = walls.find((w) => w.id === wallId);
	if (!targetWall || !Array.isArray(targetWall.line)) return walls;
	const origCoord = targetWall.line[vertexIndex];
	if (!Array.isArray(origCoord)) return walls;

	const { toM } = toLocalMetersFactory(origCoord);
	const cleanNew = [Number(newCoord[0].toFixed(6)), Number(newCoord[1].toFixed(6))];

	return walls.map((w) => {
		if (!Array.isArray(w.line)) return w;
		if (w.building && targetWall.building && !matchesBuilding(w.building, targetWall.building)) {
			return w;
		}
		if (
			w.floorOrder != null &&
			targetWall.floorOrder != null &&
			Number(w.floorOrder) !== Number(targetWall.floorOrder)
		) {
			return w;
		}
		if (detachSingle && w.id !== wallId) return w;

		const wasClosed = isClosedRingLocal(w.line);
		let changed = false;
		const nextLine = w.line.map((pt, idx) => {
			if (w.id === wallId) {
				if (idx === vertexIndex) {
					changed = true;
					return cleanNew;
				}
				if (
					wasClosed &&
					((vertexIndex === 0 && idx === w.line.length - 1) ||
						(vertexIndex === w.line.length - 1 && idx === 0))
				) {
					changed = true;
					return cleanNew;
				}
				if (detachSingle) return pt;
			}
			const m = toM(pt);
			if (Math.hypot(m[0], m[1]) <= toleranceMeters) {
				changed = true;
				return cleanNew;
			}
			return pt;
		});

		return changed ? { ...w, line: nextLine } : w;
	});
}

/**
 * When a Nav Graph node is dragged & dropped onto another existing Nav Graph node (or onto an existing edge),
 * automatically merge the duplicate node IDs (rewiring edges & preventing self-loops) or split the edge into a T-junction.
 */
export function mergeOrSplitNavNodeOnDrop({
	navNodes = [],
	navEdges = [],
	draggedNodeId,
	finalCoords,
	targetNodeId = null,
	targetEdge = null,
	floor0Bounds = [],
}) {
	if (!draggedNodeId || !Array.isArray(finalCoords)) {
		return { navNodes, navEdges, mergedIntoId: null, splitEdge: false };
	}

	const draggedNode = navNodes.find((n) => n.id === draggedNodeId);
	if (!draggedNode) {
		return { navNodes, navEdges, mergedIntoId: null, splitEdge: false };
	}

	// Auto-detect overlapping node on the same floor within 0.25m if targetNodeId not explicitly passed
	let resolvedTargetId =
		targetNodeId && targetNodeId !== draggedNodeId ? targetNodeId : null;
	if (!resolvedTargetId) {
		const { toM } = toLocalMetersFactory(finalCoords);
		let bestD = 0.25;
		for (const n of navNodes) {
			if (n.id === draggedNodeId) continue;
			if ((Number(n.floorOrder) || 0) !== (Number(draggedNode.floorOrder) || 0)) {
				continue;
			}
			const m = toM(n.coords);
			const d = Math.hypot(m[0], m[1]);
			if (d <= bestD) {
				bestD = d;
				resolvedTargetId = n.id;
			}
		}
	}

	// Case A: Merge draggedNode into resolvedTargetId
	if (resolvedTargetId) {
		const nextNodes = navNodes.filter((n) => n.id !== draggedNodeId);
		const edgeKeySet = new Set();
		const nextEdges = [];
		for (const [u, v] of navEdges) {
			const nu = u === draggedNodeId ? resolvedTargetId : u;
			const nv = v === draggedNodeId ? resolvedTargetId : v;
			if (nu === nv) continue; // Eliminate self-loop
			const k = nu < nv ? `${nu}__${nv}` : `${nv}__${nu}`;
			if (!edgeKeySet.has(k)) {
				edgeKeySet.add(k);
				nextEdges.push([nu, nv]);
			}
		}
		return {
			navNodes: nextNodes,
			navEdges: nextEdges,
			mergedIntoId: resolvedTargetId,
			splitEdge: false,
		};
	}

	// Update dragged node coordinates (and outdoor/indoor inference on Floor 0)
	const updatedNodes = navNodes.map((n) => {
		if (n.id !== draggedNodeId) return n;
		if (
			!isPortalNode(n) &&
			(Number(n.floorOrder) || 0) === 0 &&
			Array.isArray(floor0Bounds) &&
			floor0Bounds.length > 0
		) {
			const contained = findBoundaryContainingPoint(floor0Bounds, finalCoords, 0);
			return {
				...n,
				coords: finalCoords,
				building: contained ? contained.building : "OUTDOOR",
			};
		}
		return { ...n, coords: finalCoords };
	});

	// Case B: Dropped onto an existing navEdge [eu, ev] -> split into [eu, dragged] + [dragged, ev]
	if (
		Array.isArray(targetEdge) &&
		targetEdge.length === 2 &&
		targetEdge[0] !== draggedNodeId &&
		targetEdge[1] !== draggedNodeId
	) {
		const [eu, ev] = targetEdge;
		const filteredEdges = navEdges.filter(
			([a, b]) => !((a === eu && b === ev) || (a === ev && b === eu)),
		);
		const addEdgeIfMissing = (list, a, b) => {
			if (a === b) return;
			const exists = list.some(
				([x, y]) => (x === a && y === b) || (x === b && y === a),
			);
			if (!exists) list.push([a, b]);
		};
		addEdgeIfMissing(filteredEdges, eu, draggedNodeId);
		addEdgeIfMissing(filteredEdges, draggedNodeId, ev);
		return {
			navNodes: updatedNodes,
			navEdges: filteredEdges,
			mergedIntoId: null,
			splitEdge: true,
		};
	}

	return {
		navNodes: updatedNodes,
		navEdges,
		mergedIntoId: null,
		splitEdge: false,
	};
}

/**
 * Compute an oriented rectangular polygon box + left/right jamb handles + midpoint for a wall-mounted Door
 * (matching Image 1's architectural door bar embedded along the wall segment).
 */
export function buildOrientedDoorGeometry(
	door,
	wallSegments = [],
	{ minWidthMeters = 0.5, maxWidthMeters = 10.0 } = {},
) {
	if (!door || !Array.isArray(door.coords)) return null;
	const { toM, toDeg } = toLocalMetersFactory(door.coords);

	let bestSeg = null;
	let bestDist = 0.85;
	let bestProjM = [0, 0];
	let bestSegLen = Infinity;
	let bestT = 0.5;

	for (const seg of wallSegments || []) {
		if (!Array.isArray(seg?.a) || !Array.isArray(seg?.b)) continue;
		const aM = toM(seg.a);
		const bM = toM(seg.b);
		const dx = bM[0] - aM[0];
		const dy = bM[1] - aM[1];
		const lenSq = dx * dx + dy * dy;
		if (lenSq < 0.04) continue;
		const segLen = Math.sqrt(lenSq);
		const t = Math.max(0, Math.min(1, ((0 - aM[0]) * dx + (0 - aM[1]) * dy) / lenSq));
		const px = aM[0] + t * dx;
		const py = aM[1] + t * dy;
		const d = Math.hypot(px, py);
		if (d < bestDist) {
			bestDist = d;
			bestSeg = seg;
			bestProjM = [px, py];
			bestSegLen = segLen;
			bestT = t;
		}
	}

	const rawW = Number(door.widthMeters) || 1.2;
	const maxAllowedW = Number.isFinite(bestSegLen)
		? Math.max(minWidthMeters, Math.min(maxWidthMeters, bestSegLen - 0.08))
		: maxWidthMeters;
	const widthMeters = Number(
		Math.max(minWidthMeters, Math.min(maxAllowedW, rawW)).toFixed(2),
	);
	const halfLen = widthMeters / 2;
	const halfThick = bestSeg?.isPerimeter ? 0.21 : 0.16;

	let ux = 1;
	let uy = 0;
	let midM = [0, 0];

	if (bestSeg) {
		const aM = toM(bestSeg.a);
		const bM = toM(bestSeg.b);
		const dx = bM[0] - aM[0];
		const dy = bM[1] - aM[1];
		const len = Math.hypot(dx, dy) || 1;
		ux = dx / len;
		uy = dy / len;

		// Keep jambs strictly within segment [aM, bM] if possible
		if (len > widthMeters + 0.04) {
			const minT = halfLen / len;
			const maxT = 1 - halfLen / len;
			const clampedT = Math.max(minT, Math.min(maxT, bestT));
			midM = [aM[0] + clampedT * dx, aM[1] + clampedT * dy];
		} else {
			midM = bestProjM;
		}
	}

	const nx = -uy;
	const ny = ux;

	const jambAM = [midM[0] - ux * halfLen, midM[1] - uy * halfLen];
	const jambBM = [midM[0] + ux * halfLen, midM[1] + uy * halfLen];

	const c1 = toDeg([jambAM[0] + nx * halfThick, jambAM[1] + ny * halfThick]);
	const c2 = toDeg([jambBM[0] + nx * halfThick, jambBM[1] + ny * halfThick]);
	const c3 = toDeg([jambBM[0] - nx * halfThick, jambBM[1] - ny * halfThick]);
	const c4 = toDeg([jambAM[0] - nx * halfThick, jambAM[1] - ny * halfThick]);

	const roundPt = (p) => [Number(p[0].toFixed(6)), Number(p[1].toFixed(6))];
	const p1 = roundPt(c1);
	const boxPolygon = [p1, roundPt(c2), roundPt(c3), roundPt(c4), [...p1]];

	return {
		id: door.id,
		widthMeters,
		isPerimeter: Boolean(bestSeg?.isPerimeter),
		hostSegmentId: bestSeg?.id || null,
		midpoint: bestSeg ? roundPt(toDeg(midM)) : door.coords,
		jambA: roundPt(toDeg(jambAM)),
		jambB: roundPt(toDeg(jambBM)),
		centerLine: [roundPt(toDeg(jambAM)), roundPt(toDeg(jambBM))],
		boxPolygon,
	};
}

/**
 * Synchronize a vertical portal (`STAIRS` or `ELEVATOR`) across a user-selected list of floors
 * in the same building: aligns all group nodes to the exact same `[lng, lat]` shaft coordinate,
 * creates missing floor portals, removes unchecked floor portals, and auto-wires each newly created
 * portal node to its floor's closest corridor node/edge so multi-floor routing works immediately.
 */
export function syncPortalAcrossBuildingFloors(
	store,
	{ sourceNodeId, targetFloorOrders = [] } = {},
) {
	if (!store || !sourceNodeId || !Array.isArray(targetFloorOrders)) return store;
	const nodes = store.navNodes || [];
	const edges = store.navEdges || [];
	const sourceNode = nodes.find((n) => n.id === sourceNodeId);
	if (!sourceNode || !isPortalNode(sourceNode)) return store;

	const bldg =
		resolveNodeBuilding(store.buildingBoundaries || [], sourceNode) ||
		resolveCanonicalBuildingName(sourceNode.building) ||
		sourceNode.building ||
		"Tòa HCM";
	const groupKey = (sourceNode.portalGroupId || "").trim().toUpperCase() ||
		`${bldg.includes("Epsilon") ? "EPS" : "HCM"}_${sourceNode.type}_${sourceNode.id.slice(-4).toUpperCase()}`;

	const targetSet = new Set(targetFloorOrders.map((f) => Number(f) || 0));
	// Always keep the source node's own floor unless explicitly emptied
	if (targetSet.size === 0) {
		targetSet.add(Number(sourceNode.floorOrder) || 0);
	}

	const isSameShaftNode = (n) => {
		if (!isPortalNode(n)) return false;
		if ((n.portalGroupId || "").trim().toUpperCase() !== groupKey) return false;
		const nb =
			resolveNodeBuilding(store.buildingBoundaries || [], n) ||
			resolveCanonicalBuildingName(n.building) ||
			n.building;
		return matchesBuilding(nb, bldg);
	};

	const removedNodeIds = new Set();
	const existingByFloor = new Map();
	const nextNodes = [];

	for (const n of nodes) {
		if (n.id === sourceNode.id || isSameShaftNode(n)) {
			const fl = Number(n.floorOrder) || 0;
			if (!targetSet.has(fl) || existingByFloor.has(fl)) {
				removedNodeIds.add(n.id);
				continue;
			}
			const aligned = {
				...n,
				type: sourceNode.type,
				building: bldg,
				floorOrder: fl,
				coords: [...sourceNode.coords],
				portalGroupId: groupKey,
			};
			existingByFloor.set(fl, aligned);
			nextNodes.push(aligned);
		} else {
			nextNodes.push(n);
		}
	}

	let nextEdges = edges.filter(
		([u, v]) => !removedNodeIds.has(u) && !removedNodeIds.has(v),
	);

	// Create missing floor portals and link each to the closest corridor node on that floor
	const sortedFloors = Array.from(targetSet).sort((a, b) => a - b);
	for (const fl of sortedFloors) {
		let portalOnFloor = existingByFloor.get(fl);
		if (!portalOnFloor) {
			const newId = `node-portal-${bldg.replace(/\s+/g, "").toLowerCase()}-f${fl}-${Date.now()}-${fl}`;
			portalOnFloor = {
				id: newId,
				type: sourceNode.type,
				building: bldg,
				floorOrder: fl,
				coords: [...sourceNode.coords],
				portalGroupId: groupKey,
			};
			nextNodes.push(portalOnFloor);
			existingByFloor.set(fl, portalOnFloor);
		}

		// Ensure portalOnFloor has at least 1 intra-floor connection if floor has other nodes
		const hasIntraFloorEdge = nextEdges.some(([u, v]) => {
			if (u !== portalOnFloor.id && v !== portalOnFloor.id) return false;
			const otherId = u === portalOnFloor.id ? v : u;
			const other = nextNodes.find((n) => n.id === otherId);
			return other && Number(other.floorOrder) === fl;
		});

		if (!hasIntraFloorEdge) {
			const floorModels = buildFloorWallGraphModels(store);
			const candidates = nextNodes.filter(
				(n) =>
					n.id !== portalOnFloor.id &&
					Number(n.floorOrder) === fl &&
					!isNodeOutdoor(store.buildingBoundaries || [], n, floorModels) &&
					matchesBuilding(
						resolveNodeBuilding(
							store.buildingBoundaries || [],
							n,
							floorModels,
						) || n.building,
						bldg,
					),
			);
			let bestCand = null;
			let bestDist = Infinity;
			for (const c of candidates) {
				const d = haversineMeters(portalOnFloor.coords, c.coords);
				if (d < bestDist) {
					bestDist = d;
					bestCand = c;
				}
			}
			if (bestCand && bestDist <= 35) {
				nextEdges.push([portalOnFloor.id, bestCand.id]);
			}
		}
	}

	return {
		...store,
		navNodes: nextNodes,
		navEdges: nextEdges,
	};
}

/**
 * Hole-aware check whether a point `[lng, lat]` is genuinely inside an indoor slab/room on `(building, floorOrder)`
 * (returns `false` when clicking inside central VOID / courtyard holes such as `Hồ Sen` on Floor 0).
 */
export function isPointIndoorOnFloor(
	store,
	building,
	floorOrder,
	pt,
	precomputedModels = null,
) {
	if (!pt || !Array.isArray(pt)) return false;
	const cBldg = resolveCanonicalBuildingName(building) || building;
	const fl = Number(floorOrder) || 0;
	const models = precomputedModels || buildFloorWallGraphModels(store);
	const model = models.get(`${cBldg}__${fl}`);

	if (model && Array.isArray(model.faces) && model.faces.length > 0) {
		const nonVoidMatch = model.faces.some(
			(f) =>
				f.role !== "VOID" &&
				isPointInPolygonWithHoles(pt, f.ring, f.holes || []),
		);
		if (nonVoidMatch) return true;
		const insideVoid = model.faces.some(
			(f) =>
				f.role === "VOID" &&
				isPointInPolygonWithHoles(pt, f.ring, f.holes || []),
		);
		if (insideVoid) return false;
	}

	const floorBounds = (store?.buildingBoundaries || []).filter(
		(b) => matchesBuilding(b.building, cBldg) && Number(b.floorOrder) === fl,
	);
	return Boolean(findBoundaryContainingPoint(floorBounds, pt, fl));
}

/**
 * Resolve true (building, floorOrder, isOutdoor) for a `[T]` route-test click on 2D/3D studio map.
 * Never classifies street/yard clicks outside building footprints as an indoor upper floor
 * merely because the Floor dropdown is currently set to Floor 1 or Floor 2.
 */
export function resolveTestRouteClickContext({
	store,
	rawCoord,
	hitBuilding = null,
	hitFloor = null,
	scopeFloorNum = null,
	activeBuilding = "Tòa HCM",
	isAllBuildings = false,
	precomputedModels = null,
}) {
	if (!rawCoord || !Array.isArray(rawCoord)) {
		return { building: "OUTDOOR", floorOrder: 0, isOutdoor: true };
	}
	const models = precomputedModels || buildFloorWallGraphModels(store || {});
	const allBounds = store?.buildingBoundaries || [];
	const floor0Bounds = allBounds.filter(
		(b) => (Number(b.floorOrder) || 0) === 0,
	);

	if (hitBuilding && hitBuilding !== "OUTDOOR") {
		const fl =
			hitFloor != null ? Number(hitFloor) || 0 : scopeFloorNum ?? 0;
		if (
			fl === 0 &&
			!isPointIndoorOnFloor(store, hitBuilding, 0, rawCoord, models)
		) {
			return { building: "OUTDOOR", floorOrder: 0, isOutdoor: true };
		}
		return { building: hitBuilding, floorOrder: fl, isOutdoor: false };
	}

	const candidateFloor =
		hitFloor != null ? Number(hitFloor) || 0 : scopeFloorNum ?? 0;
	const bndAtFloor = findBoundaryContainingPoint(
		allBounds,
		rawCoord,
		candidateFloor,
	);
	const bndFloor0 =
		floor0Bounds.length > 0
			? findBoundaryContainingPoint(floor0Bounds, rawCoord, 0)
			: null;

	// Outside every building footprint -> strictly OUTDOOR floor 0 regardless of active floor dropdown
	if (!bndAtFloor && !bndFloor0) {
		return { building: "OUTDOOR", floorOrder: 0, isOutdoor: true };
	}

	const bldg =
		bndAtFloor?.building ||
		bndFloor0?.building ||
		(isAllBuildings ? "OUTDOOR" : activeBuilding);
	if (!bldg || bldg === "OUTDOOR") {
		return { building: "OUTDOOR", floorOrder: 0, isOutdoor: true };
	}

	const fl = bndAtFloor ? candidateFloor : 0;
	if (!isPointIndoorOnFloor(store, bldg, fl, rawCoord, models)) {
		return { building: "OUTDOOR", floorOrder: 0, isOutdoor: true };
	}

	return { building: bldg, floorOrder: fl, isOutdoor: false };
}

/**
 * Sample an interpolated walker position, current floor, elevation, bearing, and traveled trail GeoJSON
 * at normalized playback progress `t ∈ [0, 1]` along a `computeDigitizedRoute` result.
 */
export function samplePointAlongRoute(routeResult, progress01 = 0) {
	const features = routeResult?.geojson?.features;
	if (!Array.isArray(features) || features.length === 0) return null;

	const tClamped = Math.max(0, Math.min(1, Number(progress01) || 0));
	const elevFor = (fl, isOut) =>
		isOut ? 0.3 : (Number(fl) || 0) * FLOOR_ELEVATION_STEP + FLOOR_SLAB_HEIGHT + 0.38;

	const steps = [];
	let trackedFloor = Number(features[0]?.properties?.floorOrder) || 0;

	for (const feat of features) {
		const coords = feat?.geometry?.coordinates;
		const props = feat?.properties || {};
		if (!Array.isArray(coords) || coords.length < 2) continue;

		const isStair = Boolean(props.isStair);
		const isOutdoor = Boolean(props.isOutdoor);
		const building = props.building || (isOutdoor ? "OUTDOOR" : "");

		let fromFloor = Number(props.floorOrder) || 0;
		let toFloor = Number(props.toFloorOrder ?? fromFloor);
		if (isStair) {
			const minF = Math.min(fromFloor, toFloor);
			const maxF = Math.max(fromFloor, toFloor);
			if (Math.abs(trackedFloor - maxF) < Math.abs(trackedFloor - minF)) {
				fromFloor = maxF;
				toFloor = minF;
			} else {
				fromFloor = minF;
				toFloor = maxF;
			}
		} else {
			toFloor = fromFloor;
		}
		trackedFloor = toFloor;

		const segCount = coords.length - 1;
		for (let i = 0; i < segCount; i++) {
			const a = coords[i];
			const b = coords[i + 1];
			const realMeters = haversineMeters(a, b);
			const f0 = fromFloor + (toFloor - fromFloor) * (i / segCount);
			const f1 = fromFloor + (toFloor - fromFloor) * ((i + 1) / segCount);
			const weightMeters = isStair
				? Math.max(realMeters, Math.max(1, Math.abs(f1 - f0)) * FLOOR_ELEVATION_STEP * 2.5)
				: Math.max(realMeters, 0.25);

			steps.push({
				a,
				b,
				fromFloor: f0,
				toFloor: f1,
				startZ: elevFor(f0, isOutdoor && !isStair),
				endZ: elevFor(f1, isOutdoor && !isStair),
				isOutdoor,
				isStair,
				building,
				realMeters,
				weightMeters,
			});
		}
	}

	if (steps.length === 0) return null;

	const totalWeight = steps.reduce((acc, s) => acc + s.weightMeters, 0) || 1;
	const targetWeight = tClamped * totalWeight;

	let accWeight = 0;
	let accRealMeters = 0;
	let localT = 1;
	const trailFeatures = [];

	for (let i = 0; i < steps.length; i++) {
		const s = steps[i];
		const nextAccWeight = accWeight + s.weightMeters;
		if (targetWeight <= nextAccWeight || i === steps.length - 1) {
			localT =
				s.weightMeters > 1e-6
					? Math.max(0, Math.min(1, (targetWeight - accWeight) / s.weightMeters))
					: 1;
			accRealMeters += s.realMeters * localT;
			const interpCoord = [
				Number((s.a[0] + (s.b[0] - s.a[0]) * localT).toFixed(7)),
				Number((s.a[1] + (s.b[1] - s.a[1]) * localT).toFixed(7)),
			];
			if (localT > 0.001) {
				trailFeatures.push({
					type: "Feature",
					properties: {
						floorOrder: Math.round(s.fromFloor),
						isOutdoor: s.isOutdoor,
						isStair: s.isStair,
					},
					geometry: {
						type: "LineString",
						coordinates: [s.a, interpCoord],
					},
				});
			}
			const floorFloat = s.fromFloor + (s.toFloor - s.fromFloor) * localT;
			const elevationMeters = Number(
				(s.startZ + (s.endZ - s.startZ) * localT).toFixed(2),
			);
			const dx = (s.b[0] - s.a[0]) * Math.cos(((s.a[1] || 10.84) * Math.PI) / 180);
			const dy = s.b[1] - s.a[1];
			const bearing =
				Math.hypot(dx, dy) > 1e-9
					? ((Math.atan2(dx, dy) * 180) / Math.PI + 360) % 360
					: 0;
			const floorInt = Math.round(floorFloat);
			const statusLabel = s.isStair
				? `Chuyển tầng ${Math.round(s.fromFloor) === 0 ? "G" : Math.round(s.fromFloor)} → ${Math.round(s.toFloor) === 0 ? "G" : Math.round(s.toFloor)}`
				: s.isOutdoor
					? "Ngoài sân"
					: `${s.building ? `${s.building} · ` : ""}Tầng ${floorInt === 0 ? "G" : floorInt}`;

			return {
				coords: interpCoord,
				floorOrder: floorInt,
				elevationMeters,
				bearing: Number(bearing.toFixed(1)),
				isOutdoor: s.isOutdoor,
				isStair: s.isStair,
				building: s.building,
				statusLabel,
				distanceCoveredMeters: Math.round(accRealMeters),
				totalDistanceMeters:
					routeResult.totalDistanceMeters ??
					Math.round(steps.reduce((acc, st) => acc + st.realMeters, 0)),
				progress: tClamped,
				trailGeoJSON: {
					type: "FeatureCollection",
					features: trailFeatures,
				},
			};
		}

		accWeight = nextAccWeight;
		accRealMeters += s.realMeters;
		trailFeatures.push({
			type: "Feature",
			properties: {
				floorOrder: Math.round(s.fromFloor),
				isOutdoor: s.isOutdoor,
				isStair: s.isStair,
			},
			geometry: {
				type: "LineString",
				coordinates: [s.a, s.b],
			},
		});
	}

	return null;
}

