/**
 * Pure Geometry Store & 3D Vector / A* Engine for Multi-Building & Multi-Floor Campus
 *
 * Separation of Concerns:
 * - FloorDigitizerStudio (Admin Dev Tool) writes pure geometry:
 *   1. floorCalibrations (PNG tracing blueprint bounds - ONLY rendered inside FloorDigitizerStudio)
 *   2. buildingBoundaries (Outer polygon per building/floor -> auto-renders Floor Slab + Boundary Walls)
 *   3. roomPolygons (Pure polygon geometry per room -> auto-matched with Admin's Area pins on CampusMapView via Point-in-Polygon)
 *   4. customWalls (Optional interior partition line segments -> auto-buffered into 20cm 3D walls)
 *   5. navNodes & navEdges (5 node types: OUTDOOR, ENTRANCE, CORRIDOR, DOOR, STAIRS/ELEVATOR + portalGroupId)
 *
 * - CampusMapView & GuardMapScreen NEVER render the raw PNG images; they only render clean 3D Vector layers.
 */

export const STORAGE_KEY_3D_GEOMETRY = "icss_campus_3d_geometry_v2";

export const FLOOR_ELEVATION_STEP = 3.5;
export const FLOOR_SLAB_HEIGHT = 0.16;
export const ROOM_EXTRUSION_HEIGHT = 0.52;
export const ROOM_SELECTED_EXTRUSION_HEIGHT = 1.0;
export const BOUNDARY_WALL_HEIGHT = 2.85;

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

	return {
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
}

export function loadGeometryStore() {
	try {
		const raw = localStorage.getItem(STORAGE_KEY_3D_GEOMETRY);
		if (raw) {
			const parsed = JSON.parse(raw);
			if (parsed && Array.isArray(parsed.roomPolygons)) {
				return parsed;
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

/**
 * Build clean 3D Vector GeoJSON layers (Floor Slab + Boundary/Interior Walls + Room Pads + Labels)
 * Features:
 * - Real elevation per floor (floorOrder * FLOOR_ELEVATION_STEP = 3.5m)
 * - Ghost lower floors (isGhost: true) when slicing upper floors
 * - Overview mode (selectedBuilding === "ALL"): stacks all floors of all buildings
 * - Separate room pad height (0.52m, 1.0m selected) vs wall height (2.85m)
 * - Clean mitered wall corners & proper properties.id / properties.wallKind on every wall
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
	const data = store || loadGeometryStore();
	const floorSlabs = [];
	const boundaryWalls = [];
	const roomFeatures = [];
	const roomLabelFeatures = [];

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

	// 1. Building Outer Boundaries -> Floor Slab (Corridor Base) + Seamless Mitered Boundary Walls
	for (const b of data.buildingBoundaries || []) {
		const { include, isGhost, isContextBuilding } = classifyFeature(
			b.building,
			b.floorOrder,
		);
		if (!include) continue;

		const floorBase = (Number(b.floorOrder) || 0) * FLOOR_ELEVATION_STEP;

		floorSlabs.push({
			type: "Feature",
			id: `slab-${b.id}`,
			properties: {
				id: b.id,
				building: b.building,
				floorOrder: b.floorOrder,
				isGhost,
				isContextBuilding,
				color: isGhost ? "#cbd5e1" : "#e2e8f0",
				baseHeight: floorBase,
				extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT,
			},
			geometry: {
				type: "Polygon",
				coordinates: [b.ring],
			},
		});

		const miteredPoly = buildMiteredBoundaryWallPolygon(b.ring, 0.16);
		if (miteredPoly) {
			boundaryWalls.push({
				type: "Feature",
				id: `bwall-${b.id}`,
				properties: {
					id: b.id,
					sourceId: b.id,
					wallKind: "BOUNDARY",
					building: b.building,
					floorOrder: b.floorOrder,
					isGhost,
					isContextBuilding,
					color: isGhost ? "#94a3b8" : "#64748b",
					baseHeight: floorBase + FLOOR_SLAB_HEIGHT,
					extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT + BOUNDARY_WALL_HEIGHT,
				},
				geometry: {
					type: "Polygon",
					coordinates: miteredPoly,
				},
			});
		} else {
			const wallQuads = buildBufferedWallPolygons(b.ring, 0.0000015);
			wallQuads.forEach((q, idx) => {
				boundaryWalls.push({
					type: "Feature",
					id: `bwall-${b.id}-${idx}`,
					properties: {
						id: b.id,
						sourceId: b.id,
						wallKind: "BOUNDARY",
						building: b.building,
						floorOrder: b.floorOrder,
						isGhost,
						isContextBuilding,
						color: isGhost ? "#94a3b8" : "#64748b",
						baseHeight: floorBase + FLOOR_SLAB_HEIGHT,
						extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT + BOUNDARY_WALL_HEIGHT,
					},
					geometry: {
						type: "Polygon",
						coordinates: [q],
					},
				});
			});
		}
	}

	// Custom Interior Walls (mitered ribbon with square end-caps)
	for (const w of data.customWalls || []) {
		const { include, isGhost, isContextBuilding } = classifyFeature(
			w.building,
			w.floorOrder,
		);
		if (!include) continue;

		const floorBase = (Number(w.floorOrder) || 0) * FLOOR_ELEVATION_STEP;
		const ribbon = buildMiteredPolylinePolygon(w.line, 0.11, 0.11);
		const wallPolys = ribbon
			? [ribbon[0]]
			: buildBufferedWallPolygons(w.line, 0.0000012);

		wallPolys.forEach((q, idx) => {
			boundaryWalls.push({
				type: "Feature",
				id: `cwall-${w.id}-${idx}`,
				properties: {
					id: w.id,
					sourceId: w.id,
					wallKind: "CUSTOM_WALL",
					building: w.building,
					floorOrder: w.floorOrder,
					isGhost,
					isContextBuilding,
					color: isGhost ? "#cbd5e1" : "#94a3b8",
					baseHeight: floorBase + FLOOR_SLAB_HEIGHT,
					extrudeHeight: floorBase + FLOOR_SLAB_HEIGHT + BOUNDARY_WALL_HEIGHT,
				},
				geometry: {
					type: "Polygon",
					coordinates: [q],
				},
			});
		});
	}

	// 2. Room Polygons -> Point-in-Polygon matching with Admin's Area entities
	const levelColors = {
		PUBLIC: "#10b981",
		CONFIDENTIAL: "#f59e0b",
		HIGHLY_CONFIDENTIAL: "#ef4444",
	};

	for (const room of data.roomPolygons || []) {
		const { include, isGhost, isContextBuilding } = classifyFeature(
			room.building,
			room.floorOrder,
		);
		if (!include) continue;

		const floorBase = (Number(room.floorOrder) || 0) * FLOOR_ELEVATION_STEP;

		// Find if any active Area pin sits inside this room polygon on the same building & floorOrder
		const matchedArea = areas.find((a) => {
			if (a.centerLatitude == null || a.centerLongitude == null) return false;
			if (a.building && !matchesBuilding(a.building, room.building)) {
				return false;
			}
			const aOrder = resolveFloorOrder(a.building, a.floor);
			if (aOrder !== room.floorOrder) return false;
			const pt = [Number(a.centerLongitude), Number(a.centerLatitude)];
			return isPointInRing(pt, room.ring);
		});

		const isSelected = matchedArea && matchedArea.id === selectedAreaId;
		const areaLevelCode =
			typeof matchedArea?.areaLevel === "string"
				? matchedArea.areaLevel
				: matchedArea?.areaLevel?.code || null;

		let fillColor = "#94a3b8"; // Unassigned pure geometry room
		if (isGhost) {
			fillColor = "#cbd5e1";
		} else if (viewRole === "STUDIO") {
			fillColor = "#38bdf8";
		} else if (viewRole === "FACILITY_MANAGER") {
			// FM mode: calm operational slate-blue, highlight selected/active area
			fillColor = isSelected ? "#f97316" : matchedArea ? "#475569" : "#94a3b8";
		} else {
			// ADMIN mode: color by matched Area's security level
			if (isSelected) {
				fillColor = "#2563eb";
			} else if (areaLevelCode && levelColors[areaLevelCode]) {
				fillColor = levelColors[areaLevelCode];
			} else {
				fillColor = "#94a3b8";
			}
		}

		const centroid = getRingCentroid(room.ring);
		const roomHeight = isSelected
			? ROOM_SELECTED_EXTRUSION_HEIGHT
			: ROOM_EXTRUSION_HEIGHT;

		roomFeatures.push({
			type: "Feature",
			id: room.id,
			properties: {
				id: room.id,
				building: room.building,
				floorOrder: room.floorOrder,
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
				coordinates: [room.ring],
			},
		});

		// Emit room Area name labels on the active focused floor (avoid multi-floor label collision in ALL overview)
		if (
			matchedArea?.name &&
			!isGhost &&
			!isOverviewAll &&
			!isAllFloorsInFocused
		) {
			roomLabelFeatures.push({
				type: "Feature",
				id: `label-${room.id}`,
				properties: {
					id: room.id,
					areaId: matchedArea.id,
					name: matchedArea.name,
					building: room.building,
					floorOrder: room.floorOrder,
					isSelected: Boolean(isSelected),
				},
				geometry: {
					type: "Point",
					coordinates: centroid,
				},
			});
		}
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
		roomLabelsGeoJSON: {
			type: "FeatureCollection",
			features: roomLabelFeatures,
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
 * Automatically build all edges including vertical portalGroupId edges (STAIRS / ELEVATOR across floors)
 */
export function buildAllGraphEdges(store) {
	const nodes = store?.navNodes || [];
	const explicitEdges = [...(store?.navEdges || [])];

	// Group STAIRS / ELEVATOR nodes by portalGroupId
	const groups = new Map();
	for (const n of nodes) {
		if (
			(n.type === "STAIRS" || n.type === "ELEVATOR") &&
			n.portalGroupId &&
			n.portalGroupId.trim()
		) {
			const key = n.portalGroupId.trim().toUpperCase();
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
 * Multi-Building & Multi-Floor A* Pathfinding on digitized graph
 */
export function computeDigitizedRoute({
	store,
	startCoords,
	startBuilding = "OUTDOOR",
	startFloorOrder = 0,
	endCoords,
	endBuilding = "Tòa HCM",
	endFloorOrder = 0,
}) {
	const data = store || loadGeometryStore();
	const nodes = data.navNodes || [];
	if (!startCoords || !endCoords || nodes.length === 0) return null;

	const allEdges = buildAllGraphEdges(data);
	const nodeMap = new Map(nodes.map((n) => [n.id, n]));
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

	const findClosestNode = (coords, bldg, flOrder) => {
		let pool = nodes.filter((n) => n.floorOrder === flOrder);
		if (bldg && bldg !== "OUTDOOR" && bldg !== "ALL") {
			const bp = pool.filter((n) => matchesBuilding(n.building, bldg));
			if (bp.length > 0) pool = bp;
		}
		if (pool.length === 0) pool = nodes;
		let best = pool[0];
		let bestD = Infinity;
		for (const n of pool) {
			const d = haversineMeters(coords, n.coords);
			if (d < bestD) {
				bestD = d;
				best = n;
			}
		}
		return best;
	};

	const startNode = findClosestNode(
		startCoords,
		startBuilding,
		startFloorOrder,
	);
	const endNode = findClosestNode(endCoords, endBuilding, endFloorOrder);

	const gScore = new Map(nodes.map((n) => [n.id, Infinity]));
	const prev = new Map();
	const visited = new Set();
	gScore.set(startNode.id, 0);

	while (visited.size < nodes.length) {
		let uId = null;
		let best = Infinity;
		for (const [id, s] of gScore.entries()) {
			if (!visited.has(id) && s < best) {
				best = s;
				uId = id;
			}
		}
		if (!uId || uId === endNode.id) break;
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

	const pathNodes = [];
	let curr = endNode.id;
	while (curr) {
		pathNodes.unshift(nodeMap.get(curr));
		if (curr === startNode.id) break;
		curr = prev.get(curr);
	}
	if (pathNodes.length === 0 || pathNodes[0]?.id !== startNode.id) return null;

	let totalMeters =
		haversineMeters(startCoords, startNode.coords) +
		haversineMeters(endNode.coords, endCoords);
	let floorsClimbed = 0;

	const features = [
		{
			type: "Feature",
			properties: {
				floorOrder: startFloorOrder,
				building: startBuilding,
				isOutdoor: startBuilding === "OUTDOOR",
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
		const isOutdoor = a.type === "OUTDOOR" && b.type === "OUTDOOR";
		const segBuilding = isOutdoor
			? "OUTDOOR"
			: a.building !== "OUTDOOR"
				? a.building
				: b.building;
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
			building: endBuilding,
			isOutdoor: endBuilding === "OUTDOOR",
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
