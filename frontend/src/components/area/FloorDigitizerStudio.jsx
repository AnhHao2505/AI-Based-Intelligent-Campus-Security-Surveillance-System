import React, {
	useState,
	useMemo,
	useRef,
	useCallback,
	useEffect,
} from "react";
import MapGL, {
	NavigationControl,
	Marker,
	Source,
	Layer,
} from "react-map-gl/maplibre";
import "maplibre-gl/dist/maplibre-gl.css";
import "../../utils/initMapLibreWorker";
import {
	X,
	Building2,
	Layers,
	Box,
	Eye,
	EyeOff,
	Square,
	Pentagon,
	Scissors,
	Route,
	Save,
	Download,
	RotateCcw,
	Trash2,
	Copy,
	Check,
	MousePointer,
	Maximize2,
	HelpCircle,
	Footprints,
	DoorOpen,
	ArrowUpDown,
	LogIn,
	Trees,
	SlidersHorizontal,
	Crosshair,
	CheckCircle2,
	Ruler,
	Undo2,
	Redo2,
	MoreVertical,
	AlertTriangle,
	Lock,
	Unlock,
	ShieldCheck,
	Upload,
	Move,
	ChevronDown,
} from "lucide-react";
import {
	DEFAULT_BUILDINGS_META,
	loadGeometryStore,
	saveGeometryStore,
	createDefaultGeometryStore,
	buildVector3dLayers,
	buildRoute3dLayers,
	buildAllGraphEdges,
	slicePolygonWithLine,
	approximatePolygonAreaMeters,
	isPointInRing,
	getRingCentroid,
	computeDigitizedRoute,
	splitGhostActiveGeoJSON,
} from "../../data/campusIndoor3dData";
import "../../styles/FloorDigitizerStudio.css";

const STUDIO_LIGHT = {
	anchor: "viewport",
	color: "#ffffff",
	intensity: 0.45,
	position: [1.5, 205, 42],
};

const NON_INTERACTIVE_MARKER_STYLE = { pointerEvents: "none" };

const OSM_STYLE = {
	version: 8,
	light: STUDIO_LIGHT,
	sources: {
		"osm-tiles": {
			type: "raster",
			tiles: [
				"https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
				"https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
			],
			tileSize: 256,
			maxzoom: 19,
		},
	},
	layers: [
		{
			id: "osm-bg",
			type: "background",
			paint: {
				"background-color": "#f1f5f9",
			},
		},
		{
			id: "osm-tiles-layer",
			type: "raster",
			source: "osm-tiles",
			minzoom: 0,
			maxzoom: 24,
		},
	],
};

const NODE_TYPES = [
	{
		id: "CORRIDOR",
		label: "Hành lang",
		sublabel: "Corridor",
		color: "#0ea5e9",
		icon: Footprints,
	},
	{
		id: "DOOR",
		label: "Cửa phòng",
		sublabel: "Auto-Snap",
		color: "#f97316",
		icon: DoorOpen,
	},
	{
		id: "STAIRS",
		label: "Cầu thang",
		sublabel: "Trục dọc",
		color: "#a855f7",
		icon: ArrowUpDown,
	},
	{
		id: "ELEVATOR",
		label: "Thang máy",
		sublabel: "Elevator",
		color: "#ec4899",
		icon: Layers,
	},
	{
		id: "ENTRANCE",
		label: "Cổng tòa",
		sublabel: "Entrance",
		color: "#eab308",
		icon: LogIn,
	},
	{
		id: "OUTDOOR",
		label: "Sân trường",
		sublabel: "Outdoor",
		color: "#10b981",
		icon: Trees,
	},
];

/** Project point P onto segment AB for auto-snapping */
function projectPointOnSegment(p, a, b) {
	const [px, py] = p;
	const [ax, ay] = a;
	const [bx, by] = b;
	const dx = bx - ax;
	const dy = by - ay;
	const lenSq = dx * dx + dy * dy;
	if (lenSq < 1e-12) return a;
	const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
	return [Number((ax + t * dx).toFixed(6)), Number((ay + t * dy).toFixed(6))];
}

/** Distance in approximate meters between two points */
function approximateDistanceMeters(p1, p2) {
	const dx = (p2[0] - p1[0]) * 109335;
	const dy = (p2[1] - p1[1]) * 110574;
	return Math.hypot(dx, dy);
}

/** Distance in meters from point P to segment AB */
function distancePointToSegmentMeters(p, a, b) {
	const proj = projectPointOnSegment(p, a, b);
	return approximateDistanceMeters(p, proj);
}

/** Approximate closed ring perimeter in meters */
function approximateRingPerimeterMeters(ring) {
	if (!Array.isArray(ring) || ring.length < 2) return 0;
	let total = 0;
	for (let i = 0; i < ring.length - 1; i++) {
		total += approximateDistanceMeters(ring[i], ring[i + 1]);
	}
	return total;
}

function isClosedRing(ring) {
	if (!Array.isArray(ring) || ring.length < 2) return false;
	const first = ring[0];
	const last = ring[ring.length - 1];
	return (
		Math.abs(first[0] - last[0]) < 1e-9 &&
		Math.abs(first[1] - last[1]) < 1e-9
	);
}

export default function FloorDigitizerStudio({ isOpen, onClose, onSaved }) {
	const mapRef = useRef(null);
	const fileInputRef = useRef(null);

	// Core Geometry Store & Dirty Tracking
	const [store, setStoreState] = useState(() => loadGeometryStore());
	const [lastSavedJson, setLastSavedJson] = useState(() =>
		JSON.stringify(loadGeometryStore()),
	);

	// History Stack for Undo / Redo
	const [historyPast, setHistoryPast] = useState([]);
	const [historyFuture, setHistoryFuture] = useState([]);

	// Helper to mutate store with undo history tracking
	const setStore = useCallback((updater) => {
		setStoreState((prev) => {
			const next = typeof updater === "function" ? updater(prev) : updater;
			setHistoryPast((past) => [...past.slice(-49), prev]);
			setHistoryFuture([]);
			return next;
		});
	}, []);

	// Undo / Redo Handlers
	const handleUndo = useCallback(() => {
		if (historyPast.length === 0) return;
		const previous = historyPast[historyPast.length - 1];
		setHistoryPast((past) => past.slice(0, -1));
		setHistoryFuture((future) => [store, ...future.slice(0, 49)]);
		setStoreState(previous);
	}, [historyPast, store]);

	const handleRedo = useCallback(() => {
		if (historyFuture.length === 0) return;
		const next = historyFuture[0];
		setHistoryFuture((future) => future.slice(1));
		setHistoryPast((past) => [...past.slice(-49), store]);
		setStoreState(next);
	}, [historyFuture, store]);

	// Dirty state calculation
	const isDirty = useMemo(() => {
		return JSON.stringify(store) !== lastSavedJson;
	}, [store, lastSavedJson]);

	// Active Building & Floor
	const [activeBuilding, setActiveBuilding] = useState("Tòa HCM");
	const [activeFloorOrder, setActiveFloorOrder] = useState(0);

	// 2D Top-down (0 deg) vs 3D Preview (52 deg)
	const [is3dPreview, setIs3dPreview] = useState(false);

	// Active Rail Tool:
	// "SELECT" | "ROOM_BOX" | "ROOM_POLY" | "ROOM_SLICE" | "BOUNDARY_POLY" | "CUSTOM_WALL" | "NAV_GRAPH" | "TEST_ROUTE" | "CALIBRATE_PNG"
	const [activeTool, setActiveTool] = useState("SELECT");

	// Inspector Tabs: "inspector" | "layers" | "validator"
	const [activeInspectorTab, setActiveInspectorTab] = useState("inspector");

	// Layer Visibility & Locks
	const [layerVisibility, setLayerVisibility] = useState({
		png: true,
		boundaries: true,
		rooms: true,
		walls: true,
		navGraph: true,
	});
	const [layerLocks, setLayerLocks] = useState({
		png: false,
		boundaries: false,
		rooms: false,
		walls: false,
		navGraph: false,
	});
	const [pngOpacity, setPngOpacity] = useState(0.85);

	// Selected Geometry IDs
	const [selectedRoomIds, setSelectedRoomIds] = useState([]);
	const [selectedNavNodeId, setSelectedNavNodeId] = useState(null);
	const [selectedWallId, setSelectedWallId] = useState(null);
	const [selectedBoundaryId, setSelectedBoundaryId] = useState(null);

	// Multi-select Marquee Box Selection
	const [shiftKeyDown, setShiftKeyDown] = useState(false);
	const [marqueeStart, setMarqueeStart] = useState(null);
	const [marqueeCurrent, setMarqueeCurrent] = useState(null);
	const marqueeStartRef = useRef(null);
	const marqueeCurrentRef = useRef(null);
	const lastMarqueeEndAtRef = useRef(0);

	// Draggable Nav Graph Nodes & Live Vertex Editing (60fps without polluting Undo stack)
	const [liveNavNodeDrag, setLiveNavNodeDrag] = useState(null);
	const liveNavNodeDragRef = useRef(null);
	const [isDraggingNavNode, setIsDraggingNavNode] = useState(false);
	const draggingNavNodeRef = useRef(null);
	const lastNavDragEndAtRef = useRef(0);
	const [liveVertexDrag, setLiveVertexDrag] = useState(null); // { kind: "ROOM"|"BOUNDARY"|"WALL", id, index, coords }
	const [studioCursor, setStudioCursor] = useState("grab");

	// Draft points for multi-click tools
	const [draftPoints, setDraftPoints] = useState([]);
	const [hoverCoord, setHoverCoord] = useState(null);
	const [snappedCoord, setSnappedCoord] = useState(null);
	const [snapType, setSnapType] = useState(null); // "VERTEX" | "EDGE" | null
	const [altKeyDown, setAltKeyDown] = useState(false);

	// Live PNG calibration corners during active drag
	const [liveCorners, setLiveCorners] = useState(null);

	// Nav Graph states
	const [activeNodeType, setActiveNodeType] = useState("CORRIDOR");
	const [portalGroupId, setPortalGroupId] = useState("HCM_STAIR_W");
	const [chainLastNodeId, setChainLastNodeId] = useState(null);

	// Live A* Route Tester
	const [testRouteStart, setTestRouteStart] = useState(null);
	const [testRouteResult, setTestRouteResult] = useState(null);
	const [toastMsg, setToastMsg] = useState(null);
	const toastTimerRef = useRef(null);

	// Modals
	const [showShortcutsModal, setShowShortcutsModal] = useState(false);
	const [moreMenuOpen, setMoreMenuOpen] = useState(false);
	const [confirmModal, setConfirmModal] = useState({
		isOpen: false,
		title: "",
		message: "",
		confirmLabel: "Xác nhận",
		confirmVariant: "danger",
		onConfirm: () => {},
	});

	const showToast = useCallback((msg) => {
		setToastMsg(msg);
		if (toastTimerRef.current) {
			clearTimeout(toastTimerRef.current);
		}
		toastTimerRef.current = setTimeout(() => {
			toastTimerRef.current = null;
			setToastMsg(null);
		}, 2800);
	}, []);

	useEffect(() => {
		return () => {
			if (toastTimerRef.current) {
				clearTimeout(toastTimerRef.current);
			}
		};
	}, []);

	const currentBuildingMeta = useMemo(
		() =>
			DEFAULT_BUILDINGS_META.find((b) => b.name === activeBuilding) ||
			DEFAULT_BUILDINGS_META[0],
		[activeBuilding],
	);

	const currentFloorMeta = useMemo(
		() =>
			currentBuildingMeta.floors.find(
				(f) => f.floorOrder === activeFloorOrder,
			) || currentBuildingMeta.floors[0],
		[currentBuildingMeta, activeFloorOrder],
	);

	// PNG 4-Corner Calibration Bounds: [NW, NE, SE, SW]
	const currentPngCorners = useMemo(() => {
		const key = `${activeBuilding}__${activeFloorOrder}`;
		const cal = store.floorCalibrations?.[key];
		if (cal?.corners && cal.corners.length === 4) {
			return cal.corners;
		}
		const b = cal ||
			currentFloorMeta?.defaultBounds || {
				west: 106.80895,
				east: 106.81015,
				north: 10.84156,
				south: 10.84088,
			};
		return [
			[b.west, b.north], // NW
			[b.east, b.north], // NE
			[b.east, b.south], // SE
			[b.west, b.south], // SW
		];
	}, [
		store.floorCalibrations,
		activeBuilding,
		activeFloorOrder,
		currentFloorMeta,
	]);

	// Effective corners during drag or idle
	const activeCorners = useMemo(() => {
		if (liveCorners && liveCorners.length === 4) return liveCorners;
		return currentPngCorners;
	}, [liveCorners, currentPngCorners]);

	// Rotation center and top handle coordinate for PNG calibration
	const pngRotationMeta = useMemo(() => {
		const [c0, c1, c2, c3] = activeCorners;
		const centerLng = (c0[0] + c1[0] + c2[0] + c3[0]) / 4;
		const centerLat = (c0[1] + c1[1] + c2[1] + c3[1]) / 4;
		const topMidLng = (c0[0] + c1[0]) / 2;
		const topMidLat = (c0[1] + c1[1]) / 2;
		const offsetLat = (topMidLat - centerLat) * 0.35 || 0.00015;
		const handleLng = topMidLng;
		const handleLat = topMidLat + offsetLat;
		return {
			center: [centerLng, centerLat],
			handle: [handleLng, handleLat],
		};
	}, [activeCorners]);

	// PNG Calibration Bounding Frame & Rotation Stalk GeoJSON
	const pngCalibrationGeoJSON = useMemo(() => {
		if (activeTool !== "CALIBRATE_PNG") return null;
		const [c0, c1, c2, c3] = activeCorners;
		const topMid = [(c0[0] + c1[0]) / 2, (c0[1] + c1[1]) / 2];
		return {
			type: "FeatureCollection",
			features: [
				{
					type: "Feature",
					properties: { type: "frame" },
					geometry: {
						type: "LineString",
						coordinates: [c0, c1, c2, c3, c0],
					},
				},
				{
					type: "Feature",
					properties: { type: "stem" },
					geometry: {
						type: "LineString",
						coordinates: [topMid, pngRotationMeta.handle],
					},
				},
			],
		};
	}, [activeTool, activeCorners, pngRotationMeta]);

	// Marquee Selection Box GeoJSON
	const marqueeGeoJSON = useMemo(() => {
		if (!marqueeStart || !marqueeCurrent) return null;
		const minLng = Math.min(marqueeStart[0], marqueeCurrent[0]);
		const maxLng = Math.max(marqueeStart[0], marqueeCurrent[0]);
		const minLat = Math.min(marqueeStart[1], marqueeCurrent[1]);
		const maxLat = Math.max(marqueeStart[1], marqueeCurrent[1]);
		return {
			type: "FeatureCollection",
			features: [
				{
					type: "Feature",
					geometry: {
						type: "Polygon",
						coordinates: [
							[
								[minLng, maxLat],
								[maxLng, maxLat],
								[maxLng, minLat],
								[minLng, minLat],
								[minLng, maxLat],
							],
						],
					},
				},
			],
		};
	}, [marqueeStart, marqueeCurrent]);

	// Route test start & destination endpoints for pedestrian and teardrop pin markers
	const routeEndpoints = useMemo(() => {
		if (!testRouteResult?.geojson?.features?.length) return null;
		const feats = testRouteResult.geojson.features;
		const firstCoord = feats[0]?.geometry?.coordinates?.[0];
		const lastFeat = feats[feats.length - 1];
		const lastCoords = lastFeat?.geometry?.coordinates;
		const endCoord = lastCoords ? lastCoords[lastCoords.length - 1] : null;
		if (!firstCoord || !endCoord) return null;
		return { start: firstCoord, end: endCoord };
	}, [testRouteResult]);

	// Effective store with 60fps live drag preview (avoids polluting Undo history mid-drag)
	const effectiveStore = useMemo(() => {
		if (!liveNavNodeDrag && !liveVertexDrag) return store;
		let next = store;
		if (liveNavNodeDrag) {
			next = {
				...next,
				navNodes: (next.navNodes || []).map((n) =>
					n.id === liveNavNodeDrag.id
						? { ...n, coords: liveNavNodeDrag.coords }
						: n,
				),
			};
		}
		if (liveVertexDrag) {
			const { kind, id, index, coords } = liveVertexDrag;
			if (kind === "ROOM") {
				next = {
					...next,
					roomPolygons: (next.roomPolygons || []).map((r) => {
						if (r.id !== id) return r;
						const wasClosed = isClosedRing(r.ring);
						const nextRing = [...r.ring];
						nextRing[index] = coords;
						if (wasClosed && index === 0) {
							nextRing[nextRing.length - 1] = coords;
						}
						return { ...r, ring: nextRing };
					}),
				};
			} else if (kind === "BOUNDARY") {
				next = {
					...next,
					buildingBoundaries: (next.buildingBoundaries || []).map((b) => {
						if (b.id !== id) return b;
						const wasClosed = isClosedRing(b.ring);
						const nextRing = [...b.ring];
						nextRing[index] = coords;
						if (wasClosed && index === 0) {
							nextRing[nextRing.length - 1] = coords;
						}
						return { ...b, ring: nextRing };
					}),
				};
			} else if (kind === "WALL") {
				next = {
					...next,
					customWalls: (next.customWalls || []).map((w) => {
						if (w.id !== id) return w;
						const nextLine = [...w.line];
						nextLine[index] = coords;
						return { ...w, line: nextLine };
					}),
				};
			}
		}
		return next;
	}, [store, liveNavNodeDrag, liveVertexDrag]);

	// Current floor entities (backed by effectiveStore for smooth live previews)
	const currentFloorRooms = useMemo(
		() =>
			(effectiveStore.roomPolygons || []).filter(
				(r) =>
					r.building === activeBuilding && r.floorOrder === activeFloorOrder,
			),
		[effectiveStore.roomPolygons, activeBuilding, activeFloorOrder],
	);

	const currentFloorNodes = useMemo(
		() =>
			(effectiveStore.navNodes || []).filter(
				(n) =>
					n.building === activeBuilding && n.floorOrder === activeFloorOrder,
			),
		[effectiveStore.navNodes, activeBuilding, activeFloorOrder],
	);

	const currentFloorWalls = useMemo(
		() =>
			(effectiveStore.customWalls || []).filter(
				(w) =>
					w.building === activeBuilding && w.floorOrder === activeFloorOrder,
			),
		[effectiveStore.customWalls, activeBuilding, activeFloorOrder],
	);

	const currentFloorBoundaries = useMemo(
		() =>
			(effectiveStore.buildingBoundaries || []).filter(
				(b) =>
					b.building === activeBuilding && b.floorOrder === activeFloorOrder,
			),
		[effectiveStore.buildingBoundaries, activeBuilding, activeFloorOrder],
	);

	// Switch camera pitch when toggling 2D (0°) vs 3D (52°)
	const is3dPreviewRef = useRef(is3dPreview);
	is3dPreviewRef.current = is3dPreview;
	const handleToggle2d3d = useCallback((next3d) => {
		setIs3dPreview(next3d);
		if (mapRef.current) {
			mapRef.current.easeTo({
				pitch: next3d ? 52 : 0,
				bearing: next3d ? -12 : 0,
				duration: 450,
			});
		}
	}, []);

	// Fly to active building when changed
	useEffect(() => {
		if (!isOpen || !mapRef.current) return;
		mapRef.current.flyTo({
			center: currentBuildingMeta.center,
			zoom: 18.6,
			pitch: is3dPreviewRef.current ? 52 : 0,
			bearing: is3dPreviewRef.current ? -12 : 0,
			duration: 600,
		});
	}, [activeBuilding, isOpen, currentBuildingMeta]);

	// Clear stale floor/building selections whenever switching building or floor
	useEffect(() => {
		setDraftPoints([]);
		setChainLastNodeId(null);
		setSelectedRoomIds([]);
		setSelectedNavNodeId(null);
		setSelectedWallId(null);
		setSelectedBoundaryId(null);
		setLiveNavNodeDrag(null);
		liveNavNodeDragRef.current = null;
		setLiveVertexDrag(null);
		setTestRouteStart(null);
	}, [activeBuilding, activeFloorOrder]);

	// Collect vertices & segments for dual-mode Snapping (Vertex & Edge)
	const existingGeometryForSnapping = useMemo(() => {
		const vertices = [];
		const segments = [];

		for (const r of store.roomPolygons || []) {
			if (r.building === activeBuilding && r.floorOrder === activeFloorOrder) {
				const ring = r.ring || [];
				for (let i = 0; i < ring.length - 1; i++) {
					vertices.push({ coord: ring[i], ownerId: r.id });
					segments.push({ seg: [ring[i], ring[i + 1]], ownerId: r.id });
				}
				if (!isClosedRing(ring) && ring.length > 0) {
					vertices.push({ coord: ring[ring.length - 1], ownerId: r.id });
				}
			}
		}

		for (const b of store.buildingBoundaries || []) {
			if (b.building === activeBuilding && b.floorOrder === activeFloorOrder) {
				const ring = b.ring || [];
				for (let i = 0; i < ring.length - 1; i++) {
					vertices.push({ coord: ring[i], ownerId: b.id });
					segments.push({ seg: [ring[i], ring[i + 1]], ownerId: b.id });
				}
				if (!isClosedRing(ring) && ring.length > 0) {
					vertices.push({ coord: ring[ring.length - 1], ownerId: b.id });
				}
			}
		}

		for (const w of store.customWalls || []) {
			if (w.building === activeBuilding && w.floorOrder === activeFloorOrder) {
				if (w.line && w.line.length >= 2) {
					for (let i = 0; i < w.line.length; i++) {
						vertices.push({ coord: w.line[i], ownerId: w.id });
						if (i < w.line.length - 1) {
							segments.push({ seg: [w.line[i], w.line[i + 1]], ownerId: w.id });
						}
					}
				}
			}
		}

		for (const n of store.navNodes || []) {
			if (n.floorOrder === activeFloorOrder || n.type === "OUTDOOR") {
				vertices.push({ coord: n.coords, ownerId: n.id });
			}
		}

		return { vertices, segments };
	}, [store, activeBuilding, activeFloorOrder]);

	// Resolve snapped coordinate: Vertex has priority over Edge. Alt key disables snapping.
	const resolveSnappedCoord = useCallback(
		(rawLngLat, excludeOwnerId = null) => {
			if (altKeyDown) return null;
			const VERTEX_THRESHOLD = 0.00002; // ~2.0 meters
			const EDGE_THRESHOLD = 0.000015; // ~1.5 meters

			let bestVertex = null;
			let minVertexDist = VERTEX_THRESHOLD;
			for (const entry of existingGeometryForSnapping.vertices) {
				if (excludeOwnerId && entry.ownerId === excludeOwnerId) continue;
				const v = entry.coord;
				const d = Math.hypot(v[0] - rawLngLat[0], v[1] - rawLngLat[1]);
				if (d < minVertexDist) {
					minVertexDist = d;
					bestVertex = v;
				}
			}
			if (bestVertex) {
				return { coord: bestVertex, type: "VERTEX" };
			}

			let bestEdgePoint = null;
			let minEdgeDist = EDGE_THRESHOLD;
			for (const entry of existingGeometryForSnapping.segments) {
				if (excludeOwnerId && entry.ownerId === excludeOwnerId) continue;
				const [a, b] = entry.seg;
				const proj = projectPointOnSegment(rawLngLat, a, b);
				const d = Math.hypot(proj[0] - rawLngLat[0], proj[1] - rawLngLat[1]);
				if (d < minEdgeDist) {
					minEdgeDist = d;
					bestEdgePoint = proj;
				}
			}
			if (bestEdgePoint) {
				return { coord: bestEdgePoint, type: "EDGE" };
			}

			return null;
		},
		[altKeyDown, existingGeometryForSnapping],
	);

	// Finish Multi-Point Polygon Draft
	const finishPolygonDraft = useCallback(() => {
		if (draftPoints.length < 3) return;
		const ring = [...draftPoints, draftPoints[0]];
		if (activeTool === "BOUNDARY_POLY") {
			const newBoundId = `bound-${Date.now()}`;
			setStore((prev) => {
				const filtered = (prev.buildingBoundaries || []).filter(
					(b) =>
						!(
							b.building === activeBuilding && b.floorOrder === activeFloorOrder
						),
				);
				return {
					...prev,
					buildingBoundaries: [
						...filtered,
						{
							id: newBoundId,
							building: activeBuilding,
							floorOrder: activeFloorOrder,
							ring,
						},
					],
				};
			});
			setSelectedBoundaryId(newBoundId);
			showToast("Đã cập nhật Khung Tòa Nhà (Sàn hành lang + Tường bao 3D)!");
		} else if (activeTool === "ROOM_POLY") {
			const newId = `room-${Date.now()}`;
			setStore((prev) => ({
				...prev,
				roomPolygons: [
					...(prev.roomPolygons || []),
					{
						id: newId,
						building: activeBuilding,
						floorOrder: activeFloorOrder,
						ring,
					},
				],
			}));
			setSelectedRoomIds([newId]);
			showToast("Đã tạo đa giác phòng 3D!");
		}
		setDraftPoints([]);
	}, [
		draftPoints,
		activeTool,
		activeBuilding,
		activeFloorOrder,
		setStore,
		showToast,
	]);

	// P0 Bug Fixed: deleteRoomById receives id directly as parameter
	const deleteRoomById = useCallback(
		(roomIdToDelete) => {
			if (!roomIdToDelete) return;
			setStore((prev) => ({
				...prev,
				roomPolygons: (prev.roomPolygons || []).filter(
					(r) => r.id !== roomIdToDelete,
				),
			}));
			setSelectedRoomIds((prev) => prev.filter((id) => id !== roomIdToDelete));
			showToast("Đã xóa đa giác phòng.");
		},
		[setStore, showToast],
	);

	// Multi-select room deletion
	const deleteSelectedRooms = useCallback(() => {
		if (selectedRoomIds.length === 0) return;
		setStore((prev) => ({
			...prev,
			roomPolygons: (prev.roomPolygons || []).filter(
				(r) => !selectedRoomIds.includes(r.id),
			),
		}));
		const count = selectedRoomIds.length;
		setSelectedRoomIds([]);
		showToast(`Đã xóa ${count} khối phòng đã chọn.`);
	}, [selectedRoomIds, setStore, showToast]);

	const deleteNavNodeById = useCallback(
		(nodeIdToDelete) => {
			if (!nodeIdToDelete) return;
			setStore((prev) => ({
				...prev,
				navNodes: (prev.navNodes || []).filter((n) => n.id !== nodeIdToDelete),
				navEdges: (prev.navEdges || []).filter(
					([a, b]) => a !== nodeIdToDelete && b !== nodeIdToDelete,
				),
			}));
			if (selectedNavNodeId === nodeIdToDelete) setSelectedNavNodeId(null);
			if (chainLastNodeId === nodeIdToDelete) setChainLastNodeId(null);
			showToast("Đã xóa nút đồ thị & các cạnh liên quan.");
		},
		[selectedNavNodeId, chainLastNodeId, setStore, showToast],
	);

	const deleteWallById = useCallback(
		(wallIdToDelete) => {
			if (!wallIdToDelete) return;
			setStore((prev) => ({
				...prev,
				customWalls: (prev.customWalls || []).filter(
					(w) => w.id !== wallIdToDelete,
				),
			}));
			if (selectedWallId === wallIdToDelete) setSelectedWallId(null);
			showToast("Đã xóa vách tường.");
		},
		[selectedWallId, setStore, showToast],
	);

	const deleteBoundaryById = useCallback(
		(boundaryIdToDelete) => {
			if (!boundaryIdToDelete) return;
			setStore((prev) => ({
				...prev,
				buildingBoundaries: (prev.buildingBoundaries || []).filter(
					(b) => b.id !== boundaryIdToDelete,
				),
			}));
			if (selectedBoundaryId === boundaryIdToDelete) setSelectedBoundaryId(null);
			showToast("Đã xóa khung tường bao tầng.");
		},
		[selectedBoundaryId, setStore, showToast],
	);

	// Save Studio
	const handleSaveStudio = useCallback(() => {
		saveGeometryStore(store);
		setLastSavedJson(JSON.stringify(store));
		onSaved?.(store);
		showToast("Đã lưu toàn bộ hình học 3D & đồ thị đường đi!");
	}, [store, onSaved, showToast]);

	// Browser beforeunload guard when dirty
	useEffect(() => {
		const handleBeforeUnload = (e) => {
			if (isDirty) {
				e.preventDefault();
				e.returnValue = "";
			}
		};
		window.addEventListener("beforeunload", handleBeforeUnload);
		return () => window.removeEventListener("beforeunload", handleBeforeUnload);
	}, [isDirty]);

	// Safe Close with Unsaved Changes Guard
	const handleSafeClose = useCallback(() => {
		if (isDirty) {
			setConfirmModal({
				isOpen: true,
				title: "Thay đổi chưa được lưu",
				message:
					"Bạn có các thao tác số hóa chưa được lưu vào hệ thống. Bạn có muốn thoát mà không lưu không?",
				confirmLabel: "Bỏ qua & Thoát",
				confirmVariant: "danger",
				onConfirm: () => {
					setConfirmModal((m) => ({ ...m, isOpen: false }));
					onClose?.();
				},
			});
		} else {
			onClose?.();
		}
	}, [isDirty, onClose]);

	// Export GeoJSON
	const handleExportGeoJSON = useCallback(() => {
		const blob = new Blob([JSON.stringify(store, null, 2)], {
			type: "application/json",
		});
		const url = URL.createObjectURL(blob);
		const a = document.createElement("a");
		a.href = url;
		a.download = `campus_indoor_3d_${activeBuilding.toLowerCase().replace(/\s+/g, "_")}.json`;
		a.click();
		URL.revokeObjectURL(url);
		showToast("Đã tải xuống file GeoJSON dữ liệu không gian!");
		setMoreMenuOpen(false);
	}, [store, activeBuilding, showToast]);

	// Import GeoJSON
	const handleImportGeoJSON = useCallback(
		(e) => {
			const file = e.target.files?.[0];
			if (!file) return;
			const reader = new FileReader();
			reader.onload = (evt) => {
				try {
					const parsed = JSON.parse(evt.target.result);
					if (parsed && typeof parsed === "object") {
						setStore(parsed);
						showToast("Đã nhập dữ liệu hình học thành công!");
					}
				} catch {
					showToast("File JSON không hợp lệ.");
				}
			};
			reader.readAsText(file);
			e.target.value = "";
			setMoreMenuOpen(false);
		},
		[setStore, showToast],
	);

	// Clone all rooms to next floor
	const handleCloneRoomsToNextFloor = useCallback(() => {
		const nextOrder =
			(activeFloorOrder + 1) % currentBuildingMeta.floors.length;
		const sourceRooms = (store.roomPolygons || []).filter(
			(r) => r.building === activeBuilding && r.floorOrder === activeFloorOrder,
		);
		if (sourceRooms.length === 0) {
			showToast("Tầng hiện tại chưa có khối phòng nào để sao chép.");
			return;
		}
		const cloned = sourceRooms.map((r, i) => ({
			id: `room-clone-${Date.now()}-${i}`,
			building: activeBuilding,
			floorOrder: nextOrder,
			ring: r.ring.map((pt) => [...pt]),
		}));
		setStore((prev) => ({
			...prev,
			roomPolygons: [...(prev.roomPolygons || []), ...cloned],
		}));
		showToast(`Đã sao chép ${cloned.length} đa giác phòng sang tầng kế tiếp!`);
	}, [
		store.roomPolygons,
		activeBuilding,
		activeFloorOrder,
		currentBuildingMeta,
		setStore,
		showToast,
	]);

	// In-App Clear floor rooms
	const handleClearCurrentFloorRooms = useCallback(() => {
		setConfirmModal({
			isOpen: true,
			title: "Xóa toàn bộ phòng tầng này?",
			message: `Hành động này sẽ xóa sạch tất cả khối phòng đã vẽ tại ${currentFloorMeta.name} của ${activeBuilding}. Thao tác này có thể hoàn tác bằng Ctrl+Z.`,
			confirmLabel: "Xóa sạch phòng",
			confirmVariant: "danger",
			onConfirm: () => {
				setStore((prev) => ({
					...prev,
					roomPolygons: (prev.roomPolygons || []).filter(
						(r) =>
							!(
								r.building === activeBuilding &&
								r.floorOrder === activeFloorOrder
							),
					),
				}));
				setSelectedRoomIds([]);
				setConfirmModal((m) => ({ ...m, isOpen: false }));
				showToast("Đã làm trống các phòng của tầng hiện tại.");
			},
		});
	}, [activeBuilding, activeFloorOrder, currentFloorMeta, setStore, showToast]);

	// In-App Reset default template
	const handleResetDefaultTemplate = useCallback(() => {
		setConfirmModal({
			isOpen: true,
			title: "Khôi phục mẫu mặc định?",
			message:
				"Hệ thống sẽ tải lại bộ hình học mẫu gốc của FPT University HCMC. Bạn có thể dùng Ctrl+Z để quay lại nếu cần.",
			confirmLabel: "Khôi phục mẫu",
			confirmVariant: "danger",
			onConfirm: () => {
				const def = createDefaultGeometryStore();
				setStore(def);
				setSelectedRoomIds([]);
				setSelectedNavNodeId(null);
				setSelectedWallId(null);
				setSelectedBoundaryId(null);
				setConfirmModal((m) => ({ ...m, isOpen: false }));
				setMoreMenuOpen(false);
				showToast("Đã khôi phục bộ hình học mẫu ban đầu.");
			},
		});
	}, [setStore, showToast]);

	// Global Keyboard Shortcuts (1-Letter Tool Hotkeys, Undo/Redo, Delete, Alt Snap Bypass)
	useEffect(() => {
		if (!isOpen) return undefined;

		const onKeyDown = (e) => {
			if (e.key === "Alt") setAltKeyDown(true);
			if (e.key === "Shift") setShiftKeyDown(true);

			if (
				e.target &&
				["INPUT", "SELECT", "TEXTAREA"].includes(e.target.tagName)
			) {
				return;
			}

			// Undo / Redo
			if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") {
				e.preventDefault();
				if (e.shiftKey) {
					handleRedo();
				} else {
					handleUndo();
				}
				return;
			}
			if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "y") {
				e.preventDefault();
				handleRedo();
				return;
			}

			// Save
			if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
				e.preventDefault();
				handleSaveStudio();
				return;
			}

			// Escape
			if (e.key === "Escape") {
				if (showShortcutsModal) {
					setShowShortcutsModal(false);
				} else if (confirmModal.isOpen) {
					setConfirmModal((m) => ({ ...m, isOpen: false }));
				} else {
					setDraftPoints([]);
					setChainLastNodeId(null);
					setSelectedRoomIds([]);
					setSelectedNavNodeId(null);
					setSelectedWallId(null);
					setSelectedBoundaryId(null);
					setTestRouteStart(null);
				}
				return;
			}

			// Enter
			if (e.key === "Enter" && draftPoints.length >= 3) {
				finishPolygonDraft();
				return;
			}

			// Delete / Backspace
			if (e.key === "Delete" || e.key === "Backspace") {
				if (selectedRoomIds.length > 0) {
					deleteSelectedRooms();
				} else if (selectedNavNodeId) {
					deleteNavNodeById(selectedNavNodeId);
				} else if (selectedWallId) {
					deleteWallById(selectedWallId);
				} else if (selectedBoundaryId) {
					deleteBoundaryById(selectedBoundaryId);
				}
				return;
			}

			// Shortcuts Modal Toggle (?)
			if (e.key === "?") {
				setShowShortcutsModal((prev) => !prev);
				return;
			}

			// Single-Letter Tool Hotkeys
			const k = e.key.toLowerCase();
			if (!e.ctrlKey && !e.metaKey && !e.altKey) {
				if (k === "v") {
					setActiveTool("SELECT");
					setDraftPoints([]);
				} else if (k === "b") {
					setActiveTool("ROOM_BOX");
					setDraftPoints([]);
				} else if (k === "p") {
					setActiveTool("ROOM_POLY");
					setDraftPoints([]);
				} else if (k === "s") {
					setActiveTool("ROOM_SLICE");
					setDraftPoints([]);
				} else if (k === "o") {
					setActiveTool("BOUNDARY_POLY");
					setDraftPoints([]);
				} else if (k === "w") {
					setActiveTool("CUSTOM_WALL");
					setDraftPoints([]);
				} else if (k === "n") {
					setActiveTool("NAV_GRAPH");
					setDraftPoints([]);
				} else if (k === "t") {
					setActiveTool("TEST_ROUTE");
					setTestRouteStart(null);
				} else if (k === "c") {
					setActiveTool("CALIBRATE_PNG");
					setDraftPoints([]);
				}
			}
		};

		const onKeyUp = (e) => {
			if (e.key === "Alt") setAltKeyDown(false);
			if (e.key === "Shift") setShiftKeyDown(false);
		};

		window.addEventListener("keydown", onKeyDown);
		window.addEventListener("keyup", onKeyUp);
		return () => {
			window.removeEventListener("keydown", onKeyDown);
			window.removeEventListener("keyup", onKeyUp);
		};
	}, [
		isOpen,
		draftPoints,
		selectedRoomIds,
		selectedNavNodeId,
		selectedWallId,
		selectedBoundaryId,
		showShortcutsModal,
		confirmModal.isOpen,
		finishPolygonDraft,
		handleUndo,
		handleRedo,
		handleSaveStudio,
		deleteSelectedRooms,
		deleteNavNodeById,
		deleteWallById,
		deleteBoundaryById,
	]);

	// Commit Nav Graph Node drag cleanly (single Undo step) & always restore map dragPan
	const finishNavNodeDrag = useCallback(() => {
		const drag = draggingNavNodeRef.current;
		if (!drag) return false;
		draggingNavNodeRef.current = null;
		mapRef.current?.getMap?.()?.dragPan?.enable?.();
		setIsDraggingNavNode(false);

		const finalDrag = liveNavNodeDragRef.current;
		liveNavNodeDragRef.current = null;
		setLiveNavNodeDrag(null);
		setStudioCursor("grab");

		if (drag.hasDragged && finalDrag?.coords) {
			const finalCoords = finalDrag.coords;
			setStore((prev) => ({
				...prev,
				navNodes: (prev.navNodes || []).map((n) =>
					n.id === drag.id ? { ...n, coords: finalCoords } : n,
				),
			}));
			if (activeTool === "SELECT") {
				setSelectedNavNodeId(drag.id);
				setSelectedRoomIds([]);
				setSelectedWallId(null);
				setSelectedBoundaryId(null);
				setActiveInspectorTab("inspector");
			}
			lastNavDragEndAtRef.current = Date.now();
			showToast("Đã di chuyển nút đồ thị.");
		}
		return true;
	}, [activeTool, setStore, showToast]);

	const finishMarqueeSelection = useCallback(() => {
		const mStart = marqueeStartRef.current;
		const mCurr = marqueeCurrentRef.current;
		if (!mStart) return false;
		marqueeStartRef.current = null;
		marqueeCurrentRef.current = null;
		setMarqueeStart(null);
		setMarqueeCurrent(null);

		if (mCurr) {
			const d = Math.hypot(mCurr[0] - mStart[0], mCurr[1] - mStart[1]);
			if (d > 0.000015) {
				lastMarqueeEndAtRef.current = Date.now();
				const minLng = Math.min(mStart[0], mCurr[0]);
				const maxLng = Math.max(mStart[0], mCurr[0]);
				const minLat = Math.min(mStart[1], mCurr[1]);
				const maxLat = Math.max(mStart[1], mCurr[1]);

				const hitRooms = currentFloorRooms.filter((r) => {
					const hasVertexInside = r.ring.some(
						([x, y]) =>
							x >= minLng && x <= maxLng && y >= minLat && y <= maxLat,
					);
					if (hasVertexInside) return true;
					const [cx, cy] = getRingCentroid(r.ring);
					return cx >= minLng && cx <= maxLng && cy >= minLat && cy <= maxLat;
				});

				if (hitRooms.length > 0) {
					const hitIds = hitRooms.map((r) => r.id);
					setSelectedRoomIds((prev) => Array.from(new Set([...prev, ...hitIds])));
					showToast(`Đã chọn ${hitRooms.length} phòng.`);
				}
			}
		}
		return true;
	}, [currentFloorRooms, showToast]);

	useEffect(() => {
		if (!isOpen) return undefined;
		const onWinMouseUp = () => {
			if (draggingNavNodeRef.current) {
				finishNavNodeDrag();
			}
			if (marqueeStartRef.current) {
				finishMarqueeSelection();
			}
		};
		window.addEventListener("mouseup", onWinMouseUp);
		return () => window.removeEventListener("mouseup", onWinMouseUp);
	}, [isOpen, finishNavNodeDrag, finishMarqueeSelection]);

	// Mouse Down on Map: Drag Nav Graph Nodes directly OR Marquee Box Selection
	const handleMapMouseDown = useCallback(
		(e) => {
			if (e?.originalEvent?.button != null && e.originalEvent.button !== 0) {
				return;
			}
			if (e?.originalEvent?.target?.closest?.(".maplibregl-marker")) {
				return;
			}
			if (
				(activeTool === "SELECT" || activeTool === "NAV_GRAPH") &&
				!layerLocks.navGraph &&
				layerVisibility.navGraph &&
				!shiftKeyDown &&
				e?.point
			) {
				const mapInst = mapRef.current?.getMap?.();
				let hitNode = null;
				if (mapInst && mapInst.getLayer?.("studio-nav-nodes-layer")) {
					const feats = mapInst.queryRenderedFeatures(
						[
							[e.point.x - 9, e.point.y - 9],
							[e.point.x + 9, e.point.y + 9],
						],
						{ layers: ["studio-nav-nodes-layer"] },
					);
					const hitId = feats?.[0]?.properties?.id;
					if (hitId) {
						hitNode = (store.navNodes || []).find((n) => n.id === hitId) || null;
					}
				}
				if (!hitNode && mapInst) {
					const visibleNodes = (store.navNodes || []).filter(
						(n) => n.floorOrder === activeFloorOrder || n.type === "OUTDOOR",
					);
					let bestPx = 12;
					for (const n of visibleNodes) {
						const px = mapInst.project(n.coords);
						const distPx = Math.hypot(px.x - e.point.x, px.y - e.point.y);
						if (distPx < bestPx) {
							bestPx = distPx;
							hitNode = n;
						}
					}
				}
				if (hitNode) {
					e.preventDefault?.();
					e.originalEvent?.stopPropagation?.();
					e.target?.dragPan?.disable?.();
					mapInst?.dragPan?.disable?.();
					draggingNavNodeRef.current = {
						id: hitNode.id,
						startPoint: [e.point.x, e.point.y],
						startCoords: hitNode.coords,
						hasDragged: false,
					};
					setIsDraggingNavNode(true);
					setStudioCursor("grabbing");
					return;
				}
			}

			if (activeTool === "SELECT" && shiftKeyDown && e?.lngLat) {
				const pt = [
					Number(e.lngLat.lng.toFixed(6)),
					Number(e.lngLat.lat.toFixed(6)),
				];
				marqueeStartRef.current = pt;
				marqueeCurrentRef.current = pt;
				setMarqueeStart(pt);
				setMarqueeCurrent(pt);
			}
		},
		[
			activeTool,
			layerLocks.navGraph,
			layerVisibility.navGraph,
			shiftKeyDown,
			store.navNodes,
			activeFloorOrder,
		],
	);

	// Mouse Move on Map for Nav Node Dragging, Snapping, Cursor Tracking, and Marquee Box
	const handleMapMouseMove = useCallback(
		(e) => {
			if (!e?.lngLat) return;
			const raw = [
				Number(e.lngLat.lng.toFixed(6)),
				Number(e.lngLat.lat.toFixed(6)),
			];

			if (draggingNavNodeRef.current) {
				const drag = draggingNavNodeRef.current;
				if (e.point) {
					const pxMoved = Math.hypot(
						e.point.x - drag.startPoint[0],
						e.point.y - drag.startPoint[1],
					);
					if (pxMoved > 3) {
						drag.hasDragged = true;
					}
				} else {
					drag.hasDragged = true;
				}
				if (drag.hasDragged) {
					const snapResult = resolveSnappedCoord(raw, drag.id);
					const nextCoord = snapResult ? snapResult.coord : raw;
					const nextDrag = { id: drag.id, coords: nextCoord };
					liveNavNodeDragRef.current = nextDrag;
					setLiveNavNodeDrag(nextDrag);
					if (snapResult) {
						setSnappedCoord(snapResult.coord);
						setSnapType(snapResult.type);
						setHoverCoord(snapResult.coord);
					} else {
						setSnappedCoord(null);
						setSnapType(null);
						setHoverCoord(raw);
					}
				}
				return;
			}

			if (marqueeStartRef.current) {
				marqueeCurrentRef.current = raw;
				setMarqueeCurrent(raw);
			}
			const snapResult = resolveSnappedCoord(raw);
			if (snapResult) {
				setSnappedCoord(snapResult.coord);
				setSnapType(snapResult.type);
				setHoverCoord(snapResult.coord);
			} else {
				setSnappedCoord(null);
				setSnapType(null);
				setHoverCoord(raw);
			}

			// Hover cursor affordance
			if (activeTool === "SELECT") {
				if (shiftKeyDown) {
					setStudioCursor("crosshair");
				} else {
					const feats = e.features || [];
					const overNode = feats.some(
						(f) => f?.layer?.id === "studio-nav-nodes-layer",
					);
					if (overNode) {
						setStudioCursor("move");
					} else if (feats.length > 0) {
						setStudioCursor("pointer");
					} else {
						setStudioCursor("grab");
					}
				}
			} else if (activeTool === "NAV_GRAPH") {
				const feats = e.features || [];
				const overNode = feats.some(
					(f) => f?.layer?.id === "studio-nav-nodes-layer",
				);
				setStudioCursor(overNode ? "move" : "crosshair");
			} else if (activeTool === "CALIBRATE_PNG") {
				setStudioCursor("grab");
			} else {
				setStudioCursor("crosshair");
			}
		},
		[resolveSnappedCoord, activeTool, shiftKeyDown],
	);

	// Mouse Up on Map to commit Nav Node Drag or Marquee Box Selection
	const handleMapMouseUp = useCallback(() => {
		if (finishNavNodeDrag()) {
			return;
		}
		finishMarqueeSelection();
	}, [finishNavNodeDrag, finishMarqueeSelection]);

	// Map Click Handler for All Tools
	const handleMapClick = useCallback(
		(e) => {
			if (!e?.lngLat) return;
			const now = Date.now();
			if (now - lastNavDragEndAtRef.current < 80) {
				return;
			}
			if (now - lastMarqueeEndAtRef.current < 80) {
				return;
			}
			const raw = [
				Number(e.lngLat.lng.toFixed(6)),
				Number(e.lngLat.lat.toFixed(6)),
			];
			const snapResult = resolveSnappedCoord(raw);
			const pt = snapResult ? snapResult.coord : raw;

			// 1. SELECT Tool: Click nav node, wall/boundary polyline, or room (Shift+Click / Ctrl+Click for multi-select)
			if (activeTool === "SELECT") {
				const isMulti = Boolean(
					e.originalEvent?.shiftKey ||
					e.originalEvent?.ctrlKey ||
					e.originalEvent?.metaKey,
				);
				const features = e.features || [];

				const nodeFeat =
					layerVisibility.navGraph && !layerLocks.navGraph
						? features.find((f) => f?.layer?.id === "studio-nav-nodes-layer")
						: null;
				const wallFeat = features.find((f) => {
					const isWallLayer =
						f?.layer?.id === "studio-walls-hit-layer" ||
						f?.layer?.id === "studio-walls-line-layer" ||
						f?.layer?.id === "studio-walls-layer";
					if (!isWallLayer) return false;
					const kind = f?.properties?.wallKind;
					if (kind === "BOUNDARY") {
						return layerVisibility.boundaries && !layerLocks.boundaries;
					}
					return layerVisibility.walls && !layerLocks.walls;
				});
				const roomFeat =
					layerVisibility.rooms && !layerLocks.rooms
						? features.find(
								(f) =>
									f?.layer?.id === "studio-rooms-extrusion" ||
									f?.layer?.id === "studio-rooms-fill" ||
									f?.layer?.id === "studio-rooms-outline",
							)
						: null;

				// Priority 1: Nav Node
				if (nodeFeat && !isMulti) {
					const nId = nodeFeat.properties?.id;
					if (nId) {
						setSelectedNavNodeId(nId);
						setSelectedRoomIds([]);
						setSelectedWallId(null);
						setSelectedBoundaryId(null);
						setActiveInspectorTab("inspector");
						return;
					}
				}

				// Priority 2: Custom Wall or Building Boundary Wall
				if (!isMulti && wallFeat) {
					const wId = wallFeat.properties?.id;
					const wKind = wallFeat.properties?.wallKind;
					if (wId && wKind === "BOUNDARY" && !layerLocks.boundaries) {
						setSelectedBoundaryId(wId);
						setSelectedWallId(null);
						setSelectedRoomIds([]);
						setSelectedNavNodeId(null);
						setActiveInspectorTab("inspector");
						return;
					}
					if (wId && wKind !== "BOUNDARY" && !layerLocks.walls) {
						setSelectedWallId(wId);
						setSelectedBoundaryId(null);
						setSelectedRoomIds([]);
						setSelectedNavNodeId(null);
						setActiveInspectorTab("inspector");
						return;
					}
				}

				// Distance fallback for 2D walls & boundary edges (< 1.2m) before room fallback
				if (!isMulti) {
					if (layerVisibility.walls && !layerLocks.walls) {
						let bestWall = null;
						let bestWallDist = 1.2;
						for (const w of currentFloorWalls) {
							for (let i = 0; i < (w.line?.length || 0) - 1; i++) {
								const dm = distancePointToSegmentMeters(
									raw,
									w.line[i],
									w.line[i + 1],
								);
								if (dm < bestWallDist) {
									bestWallDist = dm;
									bestWall = w;
								}
							}
						}
						if (bestWall) {
							setSelectedWallId(bestWall.id);
							setSelectedBoundaryId(null);
							setSelectedRoomIds([]);
							setSelectedNavNodeId(null);
							setActiveInspectorTab("inspector");
							return;
						}
					}

					if (layerVisibility.boundaries && !layerLocks.boundaries) {
						let bestBound = null;
						let bestBoundDist = 1.2;
						for (const b of currentFloorBoundaries) {
							for (let i = 0; i < (b.ring?.length || 0) - 1; i++) {
								const dm = distancePointToSegmentMeters(
									raw,
									b.ring[i],
									b.ring[i + 1],
								);
								if (dm < bestBoundDist) {
									bestBoundDist = dm;
									bestBound = b;
								}
							}
						}
						if (bestBound) {
							setSelectedBoundaryId(bestBound.id);
							setSelectedWallId(null);
							setSelectedRoomIds([]);
							setSelectedNavNodeId(null);
							setActiveInspectorTab("inspector");
							return;
						}
					}
				}

				// Priority 3: Room Polygon
				let rId = roomFeat?.properties?.id;
				if (!rId && layerVisibility.rooms && !layerLocks.rooms) {
					const clickedRoom = currentFloorRooms.find(
						(r) => isPointInRing(raw, r.ring) || isPointInRing(pt, r.ring),
					);
					if (clickedRoom) rId = clickedRoom.id;
				}

				if (rId && !layerLocks.rooms) {
					if (isMulti) {
						setSelectedRoomIds((prev) =>
							prev.includes(rId)
								? prev.filter((id) => id !== rId)
								: [...prev, rId],
						);
					} else {
						setSelectedRoomIds([rId]);
						setSelectedNavNodeId(null);
						setSelectedWallId(null);
						setSelectedBoundaryId(null);
					}
					setActiveInspectorTab("inspector");
					return;
				}

				if (!isMulti) {
					setSelectedRoomIds([]);
					setSelectedNavNodeId(null);
					setSelectedWallId(null);
					setSelectedBoundaryId(null);
				}
				return;
			}

			// 2. 2-Click Room Box
			if (activeTool === "ROOM_BOX") {
				if (layerLocks.rooms) {
					showToast("Lớp phòng đang bị khóa.");
					return;
				}
				if (draftPoints.length === 0) {
					setDraftPoints([pt]);
				} else {
					const p0 = draftPoints[0];
					const west = Math.min(p0[0], pt[0]);
					const east = Math.max(p0[0], pt[0]);
					const south = Math.min(p0[1], pt[1]);
					const north = Math.max(p0[1], pt[1]);
					const quadRing = [
						[west, north],
						[east, north],
						[east, south],
						[west, south],
						[west, north],
					];

					const newId = `room-${Date.now()}`;
					setStore((prev) => ({
						...prev,
						roomPolygons: [
							...(prev.roomPolygons || []),
							{
								id: newId,
								building: activeBuilding,
								floorOrder: activeFloorOrder,
								ring: quadRing,
							},
						],
					}));
					setSelectedRoomIds([newId]);
					showToast("Đã tạo phòng.");
					setDraftPoints([]);
				}
				return;
			}

			// 3. Mappedin-style Room Slice (2 clicks cut a room polygon into 2)
			if (activeTool === "ROOM_SLICE") {
				if (layerLocks.rooms) {
					showToast("Lớp phòng đang bị khóa.");
					return;
				}
				if (draftPoints.length === 0) {
					setDraftPoints([pt]);
				} else {
					const p0 = draftPoints[0];
					const p1 = pt;

					// Prioritize single selected room if user has 1 room selected
					let targetRoom = null;
					let splitResult = null;

					if (selectedRoomIds.length === 1) {
						const sel = currentFloorRooms.find(
							(r) => r.id === selectedRoomIds[0],
						);
						if (sel) {
							const res = slicePolygonWithLine(sel.ring, p0, p1);
							if (res) {
								targetRoom = sel;
								splitResult = res;
							}
						}
					}

					// Otherwise search all current floor rooms
					if (!splitResult) {
						for (const rm of currentFloorRooms) {
							const res = slicePolygonWithLine(rm.ring, p0, p1);
							if (res) {
								targetRoom = rm;
								splitResult = res;
								break;
							}
						}
					}

					if (targetRoom && splitResult) {
						const [ringA, ringB] = splitResult;
						const idA = `room-${Date.now()}-1`;
						const idB = `room-${Date.now()}-2`;
						const newRoomA = {
							id: idA,
							building: activeBuilding,
							floorOrder: activeFloorOrder,
							ring: ringA,
						};
						const newRoomB = {
							id: idB,
							building: activeBuilding,
							floorOrder: activeFloorOrder,
							ring: ringB,
						};

						setStore((prev) => ({
							...prev,
							roomPolygons: [
								...(prev.roomPolygons || []).filter(
									(r) => r.id !== targetRoom.id,
								),
								newRoomA,
								newRoomB,
							],
						}));
						setSelectedRoomIds([idA, idB]);
						showToast("Đã cắt phòng.");
					} else {
						showToast("Đường cắt không cắt qua phòng.");
					}
					setDraftPoints([]);
				}
				return;
			}

			// 3. Multi-point Polygon (Building Boundary or Room Polygon)
			if (activeTool === "BOUNDARY_POLY" || activeTool === "ROOM_POLY") {
				if (activeTool === "BOUNDARY_POLY" && layerLocks.boundaries) {
					showToast("Lớp khung tòa nhà đang bị khóa.");
					return;
				}
				if (activeTool === "ROOM_POLY" && layerLocks.rooms) {
					showToast("Lớp phòng đang bị khóa.");
					return;
				}
				if (
					draftPoints.length >= 3 &&
					Math.hypot(pt[0] - draftPoints[0][0], pt[1] - draftPoints[0][1]) <
						0.000022
				) {
					finishPolygonDraft();
					return;
				}
				setDraftPoints((prev) => [...prev, pt]);
				return;
			}

			// 4. Custom Wall Segment (2 clicks)
			if (activeTool === "CUSTOM_WALL") {
				if (layerLocks.walls) {
					showToast("Lớp vách tường đang bị khóa.");
					return;
				}
				if (draftPoints.length === 0) {
					setDraftPoints([pt]);
				} else {
					const p0 = draftPoints[0];
					const newWallId = `wall-${Date.now()}`;
					setStore((prev) => ({
						...prev,
						customWalls: [
							...(prev.customWalls || []),
							{
								id: newWallId,
								building: activeBuilding,
								floorOrder: activeFloorOrder,
								line: [p0, pt],
							},
						],
					}));
					setSelectedWallId(newWallId);
					setSelectedBoundaryId(null);
					setSelectedRoomIds([]);
					setSelectedNavNodeId(null);
					setDraftPoints([]);
					showToast("Đã thêm vách ngăn.");
				}
				return;
			}

			// 5. Navigation Graph Tool
			if (activeTool === "NAV_GRAPH") {
				if (layerLocks.navGraph) {
					showToast("Lớp đồ thị đang bị khóa.");
					return;
				}
				const nodesOnFloor = (store.navNodes || []).filter(
					(n) =>
						n.floorOrder ===
						(activeNodeType === "OUTDOOR" ? 0 : activeFloorOrder),
				);

				let existingNode = null;
				for (const n of nodesOnFloor) {
					if (
						Math.hypot(n.coords[0] - raw[0], n.coords[1] - raw[1]) < 0.000022
					) {
						existingNode = n;
						break;
					}
				}

				if (existingNode) {
					if (chainLastNodeId && chainLastNodeId !== existingNode.id) {
						setStore((prev) => ({
							...prev,
							navEdges: [
								...(prev.navEdges || []),
								[chainLastNodeId, existingNode.id],
							],
						}));
						showToast("Đã nối nút.");
					}
					setChainLastNodeId(existingNode.id);
					return;
				}

				const newNodeId = `node-${Date.now()}`;
				const newNode = {
					id: newNodeId,
					type: activeNodeType,
					building: activeNodeType === "OUTDOOR" ? "OUTDOOR" : activeBuilding,
					floorOrder: activeNodeType === "OUTDOOR" ? 0 : activeFloorOrder,
					coords: raw,
					...(activeNodeType === "STAIRS" || activeNodeType === "ELEVATOR"
						? { portalGroupId: portalGroupId.trim().toUpperCase() }
						: {}),
				};

				setStore((prev) => {
					const nextNodes = [...(prev.navNodes || []), newNode];
					const nextEdges = [...(prev.navEdges || [])];

					if (activeNodeType === "DOOR") {
						const corridors = nextNodes.filter(
							(n) =>
								n.type === "CORRIDOR" &&
								n.building === activeBuilding &&
								n.floorOrder === activeFloorOrder,
						);
						if (corridors.length > 0) {
							let nearestCor = corridors[0];
							let minD = Infinity;
							for (const c of corridors) {
								const d = Math.hypot(
									c.coords[0] - raw[0],
									c.coords[1] - raw[1],
								);
								if (d < minD) {
									minD = d;
									nearestCor = c;
								}
							}
							nextEdges.push([newNodeId, nearestCor.id]);
						}
					} else if (chainLastNodeId) {
						nextEdges.push([chainLastNodeId, newNodeId]);
					}

					return {
						...prev,
						navNodes: nextNodes,
						navEdges: nextEdges,
					};
				});

				if (activeNodeType === "DOOR") {
					showToast("Đã thêm cửa.");
				} else {
					setChainLastNodeId(newNodeId);
				}
				return;
			}

			// 6. Live A* Route Tester
			if (activeTool === "TEST_ROUTE") {
				if (!testRouteStart) {
					setTestRouteStart({
						coords: raw,
						building: activeBuilding,
						floorOrder: activeFloorOrder,
					});
					showToast("Đã chọn điểm đi. Click tiếp điểm đến.");
				} else {
					const res = computeDigitizedRoute({
						store,
						startCoords: testRouteStart.coords,
						startBuilding: testRouteStart.building,
						startFloorOrder: testRouteStart.floorOrder,
						endCoords: raw,
						endBuilding: activeBuilding,
						endFloorOrder: activeFloorOrder,
					});
					setTestRouteResult(res);
					setTestRouteStart(null);
					if (res) {
						showToast(
							`Tìm thấy đường: ${res.totalDistanceMeters}m (${res.floorsClimbed} tầng)`,
						);
					}
				}
			}
		},
		[
			activeTool,
			layerLocks,
			layerVisibility,
			draftPoints,
			activeBuilding,
			activeFloorOrder,
			selectedRoomIds,
			currentFloorRooms,
			currentFloorWalls,
			currentFloorBoundaries,
			activeNodeType,
			portalGroupId,
			chainLastNodeId,
			testRouteStart,
			store,
			resolveSnappedCoord,
			finishPolygonDraft,
			setStore,
			showToast,
		],
	);

	// Dragging individual PNG corners with real-time live preview
	const handleCornerDragLive = useCallback((cornerIndex, lngLat) => {
		const newPt = [Number(lngLat.lng.toFixed(6)), Number(lngLat.lat.toFixed(6))];
		setLiveCorners((prev) => {
			const base = prev || currentPngCorners;
			return base.map((c, i) => (i === cornerIndex ? newPt : c));
		});
	}, [currentPngCorners]);

	const handleCornerDragEnd = useCallback(
		(cornerIndex, lngLat) => {
			const key = `${activeBuilding}__${activeFloorOrder}`;
			const newPt = [
				Number(lngLat.lng.toFixed(6)),
				Number(lngLat.lat.toFixed(6)),
			];
			const base = liveCorners || currentPngCorners;
			const nextCorners = base.map((c, i) => (i === cornerIndex ? newPt : c));
			setStore((prev) => ({
				...prev,
				floorCalibrations: {
					...(prev.floorCalibrations || {}),
					[key]: {
						west: Math.min(...nextCorners.map((c) => c[0])),
						east: Math.max(...nextCorners.map((c) => c[0])),
						north: Math.max(...nextCorners.map((c) => c[1])),
						south: Math.min(...nextCorners.map((c) => c[1])),
						corners: nextCorners,
					},
				},
			}));
			setLiveCorners(null);
		},
		[activeBuilding, activeFloorOrder, currentPngCorners, liveCorners, setStore],
	);

	// Dragging PNG center handle to pan/translate the entire floor plan
	const handleCenterDragLive = useCallback(
		(lngLat) => {
			const base = liveCorners || currentPngCorners;
			const cx = (base[0][0] + base[1][0] + base[2][0] + base[3][0]) / 4;
			const cy = (base[0][1] + base[1][1] + base[2][1] + base[3][1]) / 4;
			const targetLng = Number(lngLat.lng.toFixed(6));
			const targetLat = Number(lngLat.lat.toFixed(6));
			const dLng = targetLng - cx;
			const dLat = targetLat - cy;
			setLiveCorners(
				base.map(([x, y]) => [
					Number((x + dLng).toFixed(6)),
					Number((y + dLat).toFixed(6)),
				]),
			);
		},
		[currentPngCorners, liveCorners],
	);

	const handleCenterDragEnd = useCallback(
		(lngLat) => {
			const key = `${activeBuilding}__${activeFloorOrder}`;
			const base = liveCorners || currentPngCorners;
			const cx = (base[0][0] + base[1][0] + base[2][0] + base[3][0]) / 4;
			const cy = (base[0][1] + base[1][1] + base[2][1] + base[3][1]) / 4;
			const targetLng = Number(lngLat.lng.toFixed(6));
			const targetLat = Number(lngLat.lat.toFixed(6));
			const dLng = targetLng - cx;
			const dLat = targetLat - cy;
			const nextCorners = base.map(([x, y]) => [
				Number((x + dLng).toFixed(6)),
				Number((y + dLat).toFixed(6)),
			]);
			setStore((prev) => ({
				...prev,
				floorCalibrations: {
					...(prev.floorCalibrations || {}),
					[key]: {
						west: Math.min(...nextCorners.map((c) => c[0])),
						east: Math.max(...nextCorners.map((c) => c[0])),
						north: Math.max(...nextCorners.map((c) => c[1])),
						south: Math.min(...nextCorners.map((c) => c[1])),
						corners: nextCorners,
					},
				},
			}));
			setLiveCorners(null);
		},
		[activeBuilding, activeFloorOrder, currentPngCorners, liveCorners, setStore],
	);

	// Dragging PNG rotation handle with real-time live preview
	const computeRotatedCorners = useCallback(
		(lngLat, baseCorners) => {
			const [cx, cy] = pngRotationMeta.center;
			const targetLng = Number(lngLat.lng.toFixed(6));
			const targetLat = Number(lngLat.lat.toFixed(6));
			const originalAngle = Math.atan2(
				pngRotationMeta.handle[1] - cy,
				pngRotationMeta.handle[0] - cx,
			);
			const newAngle = Math.atan2(targetLat - cy, targetLng - cx);
			const deltaTheta = newAngle - originalAngle;
			const cosT = Math.cos(deltaTheta);
			const sinT = Math.sin(deltaTheta);
			return baseCorners.map(([px, py]) => {
				const dx = px - cx;
				const dy = py - cy;
				const rx = dx * cosT - dy * sinT;
				const ry = dx * sinT + dy * cosT;
				return [Number((cx + rx).toFixed(6)), Number((cy + ry).toFixed(6))];
			});
		},
		[pngRotationMeta],
	);

	const handleRotateDragLive = useCallback(
		(lngLat) => {
			const base = currentPngCorners;
			const rotated = computeRotatedCorners(lngLat, base);
			setLiveCorners(rotated);
		},
		[computeRotatedCorners, currentPngCorners],
	);

	const handleRotateDragEnd = useCallback(
		(lngLat) => {
			const key = `${activeBuilding}__${activeFloorOrder}`;
			const base = currentPngCorners;
			const rotated = computeRotatedCorners(lngLat, base);
			setStore((prev) => ({
				...prev,
				floorCalibrations: {
					...(prev.floorCalibrations || {}),
					[key]: {
						west: Math.min(...rotated.map((c) => c[0])),
						east: Math.max(...rotated.map((c) => c[0])),
						north: Math.max(...rotated.map((c) => c[1])),
						south: Math.min(...rotated.map((c) => c[1])),
						corners: rotated,
					},
				},
			}));
			setLiveCorners(null);
		},
		[
			activeBuilding,
			activeFloorOrder,
			computeRotatedCorners,
			currentPngCorners,
			setStore,
		],
	);

	// Live 60fps vertex/endpoint dragging with magnetic snap & single Undo commit on dragEnd
	const handleVertexDragLive = useCallback(
		(kind, id, index, lngLat) => {
			if (!id || !lngLat) return;
			const raw = [
				Number(lngLat.lng.toFixed(6)),
				Number(lngLat.lat.toFixed(6)),
			];
			const snapResult = resolveSnappedCoord(raw, id);
			const pt = snapResult ? snapResult.coord : raw;
			setLiveVertexDrag({ kind, id, index, coords: pt });
			if (snapResult) {
				setSnappedCoord(snapResult.coord);
				setSnapType(snapResult.type);
				setHoverCoord(snapResult.coord);
			} else {
				setSnappedCoord(null);
				setSnapType(null);
				setHoverCoord(raw);
			}
		},
		[resolveSnappedCoord],
	);

	const handleVertexDragEnd = useCallback(
		(kind, id, index, lngLat) => {
			if (!id || !lngLat) return;
			const raw = [
				Number(lngLat.lng.toFixed(6)),
				Number(lngLat.lat.toFixed(6)),
			];
			const snapResult = resolveSnappedCoord(raw, id);
			const newPt = snapResult ? snapResult.coord : raw;
			setLiveVertexDrag(null);
			setSnappedCoord(null);
			setSnapType(null);

			setStore((prev) => {
				if (kind === "ROOM") {
					return {
						...prev,
						roomPolygons: (prev.roomPolygons || []).map((r) => {
							if (r.id !== id) return r;
							const wasClosed = isClosedRing(r.ring);
							const nextRing = [...r.ring];
							nextRing[index] = newPt;
							if (wasClosed && index === 0) {
								nextRing[nextRing.length - 1] = newPt;
							}
							return { ...r, ring: nextRing };
						}),
					};
				}
				if (kind === "BOUNDARY") {
					return {
						...prev,
						buildingBoundaries: (prev.buildingBoundaries || []).map((b) => {
							if (b.id !== id) return b;
							const wasClosed = isClosedRing(b.ring);
							const nextRing = [...b.ring];
							nextRing[index] = newPt;
							if (wasClosed && index === 0) {
								nextRing[nextRing.length - 1] = newPt;
							}
							return { ...b, ring: nextRing };
						}),
					};
				}
				if (kind === "WALL") {
					return {
						...prev,
						customWalls: (prev.customWalls || []).map((w) => {
							if (w.id !== id) return w;
							const nextLine = [...w.line];
							nextLine[index] = newPt;
							return { ...w, line: nextLine };
						}),
					};
				}
				return prev;
			});
		},
		[resolveSnappedCoord, setStore],
	);

	// Insert a new corner/joint vertex at edge midpoint for selected boundary or custom wall
	const handleInsertVertexOnEdge = useCallback(
		(kind, id, insertAfterIndex, midpoint) => {
			if (!id || !midpoint) return;
			setStore((prev) => {
				if (kind === "BOUNDARY") {
					return {
						...prev,
						buildingBoundaries: (prev.buildingBoundaries || []).map((b) => {
							if (b.id !== id || !Array.isArray(b.ring)) return b;
							const nextRing = [...b.ring];
							nextRing.splice(insertAfterIndex + 1, 0, midpoint);
							return { ...b, ring: nextRing };
						}),
					};
				}
				if (kind === "WALL") {
					return {
						...prev,
						customWalls: (prev.customWalls || []).map((w) => {
							if (w.id !== id || !Array.isArray(w.line)) return w;
							const nextLine = [...w.line];
							nextLine.splice(insertAfterIndex + 1, 0, midpoint);
							return { ...w, line: nextLine };
						}),
					};
				}
				return prev;
			});
			showToast("Đã thêm chốt góc mới trên cạnh tường.");
		},
		[setStore, showToast],
	);

	// Build 3D vector preview layers (only show lower ghost floors & context buildings in 3D Preview)
	const vector3d = useMemo(
		() =>
			buildVector3dLayers({
				store: effectiveStore,
				selectedBuilding: activeBuilding,
				activeFloorOrder,
				viewRole: "STUDIO",
				showOtherBuildingsInFocus: is3dPreview,
			}),
		[effectiveStore, activeBuilding, activeFloorOrder, is3dPreview],
	);

	const vector3dSplit = useMemo(
		() => ({
			slabs: splitGhostActiveGeoJSON(vector3d.slabsGeoJSON),
			walls: splitGhostActiveGeoJSON(vector3d.wallsGeoJSON),
			rooms: splitGhostActiveGeoJSON(vector3d.roomsGeoJSON),
		}),
		[vector3d],
	);

	// 2D Polyline GeoJSON for Walls (Boundary walls + Custom interior walls) so 2D editing is crisp & accurate
	const studioWallLinesGeoJSON = useMemo(() => {
		const features = [];
		if (layerVisibility.boundaries) {
			for (const b of currentFloorBoundaries) {
				if (!Array.isArray(b.ring) || b.ring.length < 2) continue;
				features.push({
					type: "Feature",
					id: b.id,
					properties: {
						id: b.id,
						wallKind: "BOUNDARY",
						isSelected: b.id === selectedBoundaryId,
					},
					geometry: {
						type: "LineString",
						coordinates: b.ring,
					},
				});
			}
		}
		if (layerVisibility.walls) {
			for (const w of currentFloorWalls) {
				if (!Array.isArray(w.line) || w.line.length < 2) continue;
				features.push({
					type: "Feature",
					id: w.id,
					properties: {
						id: w.id,
						wallKind: "CUSTOM_WALL",
						isSelected: w.id === selectedWallId,
					},
					geometry: {
						type: "LineString",
						coordinates: w.line,
					},
				});
			}
		}
		return { type: "FeatureCollection", features };
	}, [
		layerVisibility.boundaries,
		layerVisibility.walls,
		currentFloorBoundaries,
		currentFloorWalls,
		selectedBoundaryId,
		selectedWallId,
	]);

	// 2D Wall Endpoint Dots (helps visualize & grab wall corners / endpoints in 2D)
	const studioWallEndpointsGeoJSON = useMemo(() => {
		const features = [];
		if (layerVisibility.walls) {
			for (const w of currentFloorWalls) {
				const isSel = w.id === selectedWallId;
				for (const pt of w.line || []) {
					features.push({
						type: "Feature",
						properties: {
							wallId: w.id,
							wallKind: "CUSTOM_WALL",
							isSelected: isSel,
						},
						geometry: { type: "Point", coordinates: pt },
					});
				}
			}
		}
		if (layerVisibility.boundaries) {
			for (const b of currentFloorBoundaries) {
				const isSel = b.id === selectedBoundaryId;
				const ring = b.ring || [];
				const pts = isClosedRing(ring) ? ring.slice(0, -1) : ring;
				for (const pt of pts) {
					features.push({
						type: "Feature",
						properties: {
							wallId: b.id,
							wallKind: "BOUNDARY",
							isSelected: isSel,
						},
						geometry: { type: "Point", coordinates: pt },
					});
				}
			}
		}
		return { type: "FeatureCollection", features };
	}, [
		layerVisibility.walls,
		layerVisibility.boundaries,
		currentFloorWalls,
		currentFloorBoundaries,
		selectedWallId,
		selectedBoundaryId,
	]);

	// 3D Test Route Ribbons for Studio 3D Preview
	const studioRoute3dLayers = useMemo(
		() =>
			buildRoute3dLayers(testRouteResult, {
				selectedBuilding: activeBuilding,
				activeFloorOrder,
			}),
		[testRouteResult, activeBuilding, activeFloorOrder],
	);

	// Interactive layer IDs strictly matching currently mounted, visible, unlocked layers
	const studioInteractiveLayerIds = useMemo(() => {
		if (activeTool !== "SELECT" && activeTool !== "NAV_GRAPH") return undefined;
		const ids = [];
		if (layerVisibility.navGraph && !layerLocks.navGraph) {
			ids.push("studio-nav-nodes-layer");
		}
		if (activeTool === "SELECT") {
			if (is3dPreview) {
				if (
					(layerVisibility.walls || layerVisibility.boundaries) &&
					(!layerLocks.walls || !layerLocks.boundaries)
				) {
					ids.push("studio-walls-layer");
				}
				if (layerVisibility.rooms && !layerLocks.rooms) {
					ids.push("studio-rooms-extrusion");
				}
			} else {
				if (
					(layerVisibility.walls || layerVisibility.boundaries) &&
					(!layerLocks.walls || !layerLocks.boundaries)
				) {
					ids.push("studio-walls-hit-layer", "studio-walls-line-layer");
				}
				if (layerVisibility.rooms && !layerLocks.rooms) {
					ids.push("studio-rooms-fill", "studio-rooms-outline");
				}
			}
		}
		return ids;
	}, [activeTool, is3dPreview, layerVisibility, layerLocks]);

	// Build Nav Graph GeoJSON for current floor + outdoor (uses effectiveStore for live drag)
	const navGraphLayers = useMemo(() => {
		const allEdges = buildAllGraphEdges(effectiveStore);
		const nodeMap = new Map(
			(effectiveStore.navNodes || []).map((n) => [n.id, n]),
		);
		const edgeFeatures = [];

		for (const [uId, vId] of allEdges) {
			const u = nodeMap.get(uId);
			const v = nodeMap.get(vId);
			if (!u || !v) continue;
			if (
				u.floorOrder !== activeFloorOrder &&
				v.floorOrder !== activeFloorOrder &&
				u.type !== "OUTDOOR"
			) {
				continue;
			}
			edgeFeatures.push({
				type: "Feature",
				properties: {
					isVertical: u.floorOrder !== v.floorOrder,
				},
				geometry: {
					type: "LineString",
					coordinates: [u.coords, v.coords],
				},
			});
		}

		const nodeFeatures = (effectiveStore.navNodes || [])
			.filter((n) => n.floorOrder === activeFloorOrder || n.type === "OUTDOOR")
			.map((n) => ({
				type: "Feature",
				properties: {
					id: n.id,
					nodeType: n.type,
					portalGroupId: n.portalGroupId || "",
					isChainHead: n.id === chainLastNodeId,
					isSelected:
						n.id === selectedNavNodeId || n.id === liveNavNodeDrag?.id,
				},
				geometry: {
					type: "Point",
					coordinates: n.coords,
				},
			}));

		return {
			edges: { type: "FeatureCollection", features: edgeFeatures },
			nodes: { type: "FeatureCollection", features: nodeFeatures },
		};
	}, [
		effectiveStore,
		activeFloorOrder,
		chainLastNodeId,
		selectedNavNodeId,
		liveNavNodeDrag?.id,
	]);

	// Draft preview GeoJSON while drawing
	const draftPreviewGeoJSON = useMemo(() => {
		if (draftPoints.length === 0 || !hoverCoord) {
			return { type: "FeatureCollection", features: [] };
		}
		if (activeTool === "ROOM_BOX") {
			const p0 = draftPoints[0];
			const west = Math.min(p0[0], hoverCoord[0]);
			const east = Math.max(p0[0], hoverCoord[0]);
			const south = Math.min(p0[1], hoverCoord[1]);
			const north = Math.max(p0[1], hoverCoord[1]);
			const ring = [
				[west, north],
				[east, north],
				[east, south],
				[west, south],
				[west, north],
			];
			return {
				type: "FeatureCollection",
				features: [
					{
						type: "Feature",
						geometry: { type: "Polygon", coordinates: [ring] },
					},
				],
			};
		}
		return {
			type: "FeatureCollection",
			features: [
				{
					type: "Feature",
					geometry: {
						type: "LineString",
						coordinates: [...draftPoints, hoverCoord],
					},
				},
			],
		};
	}, [draftPoints, hoverCoord, activeTool]);

	const hasBuildingBoundary = useMemo(
		() =>
			(store.buildingBoundaries || []).some(
				(b) =>
					b.building === activeBuilding && b.floorOrder === activeFloorOrder,
			),
		[store.buildingBoundaries, activeBuilding, activeFloorOrder],
	);

	// Primary selected objects for contextual inspector (backed by effectiveStore, scoped to active floor)
	const singleSelectedRoom = useMemo(() => {
		if (selectedRoomIds.length !== 1) return null;
		return (
			currentFloorRooms.find((r) => r.id === selectedRoomIds[0]) || null
		);
	}, [currentFloorRooms, selectedRoomIds]);

	const singleSelectedNavNode = useMemo(() => {
		if (!selectedNavNodeId) return null;
		return (
			(effectiveStore.navNodes || []).find(
				(n) =>
					n.id === selectedNavNodeId &&
					(n.floorOrder === activeFloorOrder || n.type === "OUTDOOR"),
			) || null
		);
	}, [effectiveStore.navNodes, selectedNavNodeId, activeFloorOrder]);

	const singleSelectedWall = useMemo(() => {
		if (!selectedWallId) return null;
		return currentFloorWalls.find((w) => w.id === selectedWallId) || null;
	}, [currentFloorWalls, selectedWallId]);

	const singleSelectedBoundary = useMemo(() => {
		if (!selectedBoundaryId) return null;
		return (
			currentFloorBoundaries.find((b) => b.id === selectedBoundaryId) || null
		);
	}, [currentFloorBoundaries, selectedBoundaryId]);

	// Geometry Validator Engine
	const validationIssues = useMemo(() => {
		const issues = [];
		const allEdges = buildAllGraphEdges(store);
		const connectedNodeIds = new Set();
		for (const [a, b] of allEdges) {
			connectedNodeIds.add(a);
			connectedNodeIds.add(b);
		}

		// 1. Isolated Nav Nodes on current floor
		for (const n of currentFloorNodes) {
			if (!connectedNodeIds.has(n.id)) {
				issues.push({
					id: `iso-${n.id}`,
					level: "warning",
					type: "NODE",
					targetId: n.id,
					coords: n.coords,
					title: `Nút mồ côi: ${n.type}`,
					desc: `Nút tại [${n.coords[0].toFixed(5)}, ${n.coords[1].toFixed(5)}] chưa được nối vào đồ thị.`,
				});
			}
		}

		// 2. Unmatched Portals for STAIRS / ELEVATOR
		const portalsByGroup = new Map();
		for (const n of store.navNodes || []) {
			if ((n.type === "STAIRS" || n.type === "ELEVATOR") && n.portalGroupId) {
				const key = `${n.building}__${n.portalGroupId}`;
				const list = portalsByGroup.get(key) || [];
				list.push(n);
				portalsByGroup.set(key, list);
			}
		}
		for (const n of currentFloorNodes) {
			if ((n.type === "STAIRS" || n.type === "ELEVATOR") && n.portalGroupId) {
				const key = `${n.building}__${n.portalGroupId}`;
				const group = portalsByGroup.get(key) || [];
				const otherFloors = group.filter(
					(other) => other.floorOrder !== n.floorOrder,
				);
				if (otherFloors.length === 0) {
					issues.push({
						id: `portal-${n.id}`,
						level: "error",
						type: "NODE",
						targetId: n.id,
						coords: n.coords,
						title: `Thang thiếu tầng đối ứng (${n.portalGroupId})`,
						desc: `Chưa có nút thang ${n.portalGroupId} ở các tầng khác của ${activeBuilding}.`,
					});
				}
			}
		}

		// 3. DOOR not connected to CORRIDOR
		for (const n of currentFloorNodes) {
			if (n.type === "DOOR") {
				const neighborIds = [];
				for (const [u, v] of allEdges) {
					if (u === n.id) neighborIds.push(v);
					if (v === n.id) neighborIds.push(u);
				}
				const nodeMap = new Map((store.navNodes || []).map((x) => [x.id, x]));
				const hasCorridorNeighbor = neighborIds.some(
					(nbId) => nodeMap.get(nbId)?.type === "CORRIDOR",
				);
				if (!hasCorridorNeighbor) {
					issues.push({
						id: `door-${n.id}`,
						level: "warning",
						type: "NODE",
						targetId: n.id,
						coords: n.coords,
						title: "Cửa phòng chưa nối vào Hành lang",
						desc: "Nút Cửa cần được nối vào ít nhất một nút Hành lang (CORRIDOR).",
					});
				}
			}
		}

		// 4. Room polygon validation (< 4 points in ring)
		for (const r of currentFloorRooms) {
			if (!r.ring || r.ring.length < 4) {
				issues.push({
					id: `room-ring-${r.id}`,
					level: "error",
					type: "ROOM",
					targetId: r.id,
					coords: r.ring?.[0] || [106.8095, 10.8412],
					title: `Phòng ${r.id} bị lỗi đỉnh`,
					desc: "Đa giác phòng chưa đủ 3 đỉnh góc khép kín.",
				});
			}
		}

		return issues;
	}, [store, currentFloorNodes, currentFloorRooms, activeBuilding]);

	if (!isOpen) return null;

	return (
		<div
			className="digitizer-studio-overlay"
			role="dialog"
			aria-modal="true"
			aria-label="Dev Digitizer Studio"
		>
			{/* HIDDEN FILE INPUT FOR IMPORT GEOJSON */}
			<input
				type="file"
				ref={fileInputRef}
				accept=".json,application/json"
				style={{ display: "none" }}
				onChange={handleImportGeoJSON}
			/>

			{/* ==========================================================================
			   TOP STUDIO COMMAND HEADER
			   ========================================================================== */}
			<header className="digitizer-studio-header">
				{/* Left Brand & Status */}
				<div className="digitizer-brand">
					<div className="digitizer-brand__info">
						<h1 className="digitizer-brand__title">
							Floor Digitizer
						</h1>
						<div className="digitizer-brand__meta">
							<span>{activeBuilding}</span>
							<span>/</span>
							<span>{currentFloorMeta.name}</span>
							<span>/</span>
							<span
								className={`digitizer-brand__status ${isDirty ? "is-dirty" : "is-saved"}`}
							>
								{isDirty ? "• Chưa lưu" : "Đã lưu"}
							</span>
						</div>
					</div>
				</div>

				{/* Center Controls (Building & Floor Select-Options, 2D/3D, Undo/Redo) */}
				<div className="digitizer-header-center">
					{/* Building Select-Options */}
					<div className="digitizer-header-select-wrap">
						<Building2 size={13} className="digitizer-header-select-icon" />
						<select
							className="digitizer-header-select"
							aria-label="Chọn Tòa Nhà"
							value={activeBuilding}
							onChange={(e) => {
								setActiveBuilding(e.target.value);
								setActiveFloorOrder(0);
								setDraftPoints([]);
								setChainLastNodeId(null);
							}}
						>
							{DEFAULT_BUILDINGS_META.map((b) => (
								<option key={b.id} value={b.name}>
									{b.name}
								</option>
							))}
						</select>
						<ChevronDown size={13} className="digitizer-header-select-arrow" />
					</div>

					{/* Floor Select-Options */}
					<div className="digitizer-header-select-wrap">
						<Layers size={13} className="digitizer-header-select-icon" />
						<select
							className="digitizer-header-select"
							aria-label="Chọn Tầng"
							value={String(activeFloorOrder)}
							onChange={(e) => {
								setActiveFloorOrder(Number(e.target.value));
								setDraftPoints([]);
								setChainLastNodeId(null);
							}}
						>
							{currentBuildingMeta.floors.map((fl) => (
								<option key={fl.id} value={String(fl.floorOrder)}>
									{fl.shortLabel}
								</option>
							))}
						</select>
						<ChevronDown size={13} className="digitizer-header-select-arrow" />
					</div>

					{/* 2D vs 3D Quick View Switch */}
					<div
						className="digitizer-seg-group"
						role="tablist"
						aria-label="Chế độ góc nhìn"
					>
						<button
							type="button"
							role="tab"
							aria-selected={!is3dPreview}
							className={`digitizer-seg-btn ${!is3dPreview ? "is-active--blue" : ""}`}
							onClick={() => handleToggle2d3d(false)}
							title="Góc nhìn 2D 0°"
						>
							<Maximize2 size={13} />
							<span>2D</span>
						</button>
						<button
							type="button"
							role="tab"
							aria-selected={is3dPreview}
							className={`digitizer-seg-btn ${is3dPreview ? "is-active--blue" : ""}`}
							onClick={() => handleToggle2d3d(true)}
							title="Góc nhìn 3D 52°"
						>
							<Box size={13} />
							<span>3D</span>
						</button>
					</div>

					{/* Undo / Redo */}
					<div
						className="digitizer-history-group"
						aria-label="Lịch sử thao tác"
					>
						<button
							type="button"
							className="digitizer-history-btn"
							onClick={handleUndo}
							disabled={historyPast.length === 0}
							title="Hoàn tác (Ctrl+Z)"
							aria-label="Hoàn tác"
						>
							<Undo2 size={14} />
						</button>
						<button
							type="button"
							className="digitizer-history-btn"
							onClick={handleRedo}
							disabled={historyFuture.length === 0}
							title="Làm lại (Ctrl+Shift+Z hoặc Ctrl+Y)"
							aria-label="Làm lại"
						>
							<Redo2 size={14} />
						</button>
					</div>
				</div>

				{/* Right Actions */}
				<div className="digitizer-header-actions">
					<button
						type="button"
						className="digitizer-action-btn--icon-only"
						onClick={() => setShowShortcutsModal(true)}
						title="Phím tắt [?]"
						aria-label="Phím tắt"
					>
						<HelpCircle size={15} />
					</button>

					<button
						type="button"
						className="digitizer-action-btn digitizer-action-btn--primary"
						onClick={handleSaveStudio}
						title="Lưu (Ctrl+S)"
					>
						<Save size={13} />
						<span>Lưu</span>
					</button>

					{/* More Actions Dropdown */}
					<button
						type="button"
						className="digitizer-action-btn--icon-only"
						onClick={() => setMoreMenuOpen((v) => !v)}
						title="Thao tác khác"
						aria-label="Thao tác khác"
					>
						<MoreVertical size={15} />
					</button>

					{moreMenuOpen && (
						<div className="digitizer-menu-dropdown">
							<button
								type="button"
								className="digitizer-menu-item"
								onClick={handleExportGeoJSON}
							>
								<Download size={13} />
								<span>Xuất GeoJSON</span>
							</button>
							<button
								type="button"
								className="digitizer-menu-item"
								onClick={() => fileInputRef.current?.click()}
							>
								<Upload size={13} />
								<span>Nhập GeoJSON</span>
							</button>
							<button
								type="button"
								className="digitizer-menu-item digitizer-menu-item--danger"
								onClick={handleResetDefaultTemplate}
							>
								<RotateCcw size={13} />
								<span>Đặt lại mặc định</span>
							</button>
						</div>
					)}

					<button
						type="button"
						className="digitizer-close-btn"
						onClick={handleSafeClose}
						aria-label="Đóng Studio"
						title="Đóng (Esc)"
					>
						<X size={16} />
					</button>
				</div>
			</header>

			{/* ==========================================================================
			   MAIN STUDIO BODY: RAIL (52px) · CANVAS (1fr) · INSPECTOR (320px)
			   ========================================================================== */}
			<div className="digitizer-studio-body">
				{/* 1. LEFT VERTICAL TOOL RAIL (52px) */}
				<nav
					className="digitizer-tool-rail"
					aria-label="Thanh công cụ"
				>
					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "SELECT" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("SELECT");
							setDraftPoints([]);
						}}
						title="Chọn [V]"
						aria-label="Chọn [V]"
					>
						<MousePointer size={17} />
						<span className="digitizer-rail-key">V</span>
					</button>

					<div className="digitizer-rail-divider" />

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "ROOM_BOX" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("ROOM_BOX");
							setDraftPoints([]);
						}}
						title="Tạo phòng [B]"
						aria-label="Tạo phòng [B]"
					>
						<Square size={17} />
						<span className="digitizer-rail-key">B</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "ROOM_POLY" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("ROOM_POLY");
							setDraftPoints([]);
						}}
						title="Đa giác [P]"
						aria-label="Đa giác [P]"
					>
						<Pentagon size={17} />
						<span className="digitizer-rail-key">P</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "ROOM_SLICE" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("ROOM_SLICE");
							setDraftPoints([]);
						}}
						title="Cắt phòng [S]"
						aria-label="Cắt phòng [S]"
					>
						<Scissors size={17} />
						<span className="digitizer-rail-key">S</span>
					</button>

					<div className="digitizer-rail-divider" />

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "BOUNDARY_POLY" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("BOUNDARY_POLY");
							setDraftPoints([]);
						}}
						title="Tường bao [O]"
						aria-label="Tường bao [O]"
					>
						<Building2 size={17} />
						<span className="digitizer-rail-key">O</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "CUSTOM_WALL" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("CUSTOM_WALL");
							setDraftPoints([]);
						}}
						title="Vách ngăn [W]"
						aria-label="Vách ngăn [W]"
					>
						<Ruler size={17} />
						<span className="digitizer-rail-key">W</span>
					</button>

					<div className="digitizer-rail-divider" />

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "NAV_GRAPH" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("NAV_GRAPH");
							setDraftPoints([]);
						}}
						title="Đồ thị [N]"
						aria-label="Đồ thị [N]"
					>
						<Route size={17} />
						<span className="digitizer-rail-key">N</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "TEST_ROUTE" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("TEST_ROUTE");
							setTestRouteStart(null);
						}}
						title="Tìm đường [T]"
						aria-label="Tìm đường [T]"
					>
						<Footprints size={17} />
						<span className="digitizer-rail-key">T</span>
					</button>

					<div className="digitizer-rail-divider" />

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "CALIBRATE_PNG" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("CALIBRATE_PNG");
							setDraftPoints([]);
						}}
						title="Căn chỉnh ảnh [C]"
						aria-label="Căn chỉnh ảnh [C]"
					>
						<Crosshair size={17} />
						<span className="digitizer-rail-key">C</span>
					</button>
				</nav>

				{/* 2. CENTER GIS MAP CANVAS */}
				<main className="digitizer-canvas-wrap">
					{/* Top Floating Instruction HUD */}
					<div className="digitizer-hud">
						<span className="digitizer-hud__icon">
							{activeTool === "SELECT" && <MousePointer size={14} />}
							{activeTool === "ROOM_BOX" && <Square size={14} />}
							{activeTool === "ROOM_POLY" && <Pentagon size={14} />}
							{activeTool === "ROOM_SLICE" && <Scissors size={14} />}
							{activeTool === "BOUNDARY_POLY" && <Building2 size={14} />}
							{activeTool === "CUSTOM_WALL" && <Ruler size={14} />}
							{activeTool === "NAV_GRAPH" && <Route size={14} />}
							{activeTool === "TEST_ROUTE" && <Footprints size={14} />}
							{activeTool === "CALIBRATE_PNG" && <Crosshair size={14} />}
						</span>

						<span className="digitizer-hud__text">
							{activeTool === "SELECT" &&
								(selectedRoomIds.length > 0
									? `Đã chọn ${selectedRoomIds.length} phòng [Kéo đỉnh chỉnh góc · Shift: chọn thêm · Del: xóa]`
									: singleSelectedNavNode
										? `Nút ${singleSelectedNavNode.type} [Kéo nút tròn để di chuyển · Del: xóa]`
										: singleSelectedWall
											? `Vách ngăn [Kéo 2 đầu mút để chỉnh · Del: xóa]`
											: singleSelectedBoundary
												? `Tường bao tầng [Kéo chốt góc để chỉnh · Del: xóa]`
												: "Chọn đối tượng [Kéo nút tròn để di chuyển · Click tường/phòng để sửa]")}

							{activeTool === "ROOM_BOX" &&
								(draftPoints.length === 0
									? "Click điểm 1 để tạo phòng [B]"
									: "Click điểm 2 để hoàn tất [Esc: hủy]")}

							{activeTool === "ROOM_SLICE" &&
								(draftPoints.length === 0
									? "Click điểm 1 của đường cắt [S]"
									: "Click điểm 2 để cắt phòng [Esc: hủy]")}

							{activeTool === "ROOM_POLY" &&
								`Vẽ đa giác: ${draftPoints.length} điểm [Enter: khép kín, Esc: hủy]`}

							{activeTool === "BOUNDARY_POLY" &&
								`Tường bao: ${draftPoints.length} điểm [Enter: khép kín, Esc: hủy]`}

							{activeTool === "CUSTOM_WALL" &&
								(draftPoints.length === 0
									? "Click điểm đầu vách [W]"
									: "Click điểm cuối vách [Esc: hủy]")}

							{activeTool === "NAV_GRAPH" &&
								`Đồ thị (${activeNodeType}): Click đặt nút · Kéo nút tròn để di chuyển [Esc: ngắt]`}

							{activeTool === "TEST_ROUTE" &&
								(!testRouteStart
									? "Click điểm xuất phát [T]"
									: "Click điểm đến để tìm đường")}

							{activeTool === "CALIBRATE_PNG" &&
								"Kéo 4 góc để co giãn · Chốt trên để xoay · Chốt tâm để di chuyển"}
						</span>

						{draftPoints.length >= 3 &&
							(activeTool === "ROOM_POLY" ||
								activeTool === "BOUNDARY_POLY") && (
								<button
									type="button"
									className="digitizer-hud__btn digitizer-hud__btn--confirm"
									onClick={finishPolygonDraft}
								>
									<Check size={12} />
									<span>Khép kín (Enter)</span>
								</button>
							)}

						{selectedRoomIds.length > 0 && activeTool === "SELECT" && (
							<button
								type="button"
								className="digitizer-hud__btn digitizer-hud__btn--danger"
								onClick={deleteSelectedRooms}
							>
								<Trash2 size={12} />
								<span>Xóa ({selectedRoomIds.length})</span>
							</button>
						)}
					</div>

					{/* Toast Notification */}
					{toastMsg && (
						<div
							className="digitizer-toast"
							role="status"
						>
							<CheckCircle2
								size={14}
								color="#10b981"
							/>
							<span>{toastMsg}</span>
						</div>
					)}

					{/* Live A* Route Result Card */}
					{testRouteResult?.geojson && (
						<div className="digitizer-route-card">
							<div>
								<div className="digitizer-route-card__val">
									{testRouteResult.totalDistanceMeters}m
								</div>
								<div className="digitizer-route-card__label">
									Tổng cự ly di chuyển
								</div>
							</div>
							<div>
								<div className="digitizer-route-card__val">
									{testRouteResult.floorsClimbed > 0
										? `${testRouteResult.floorsClimbed} tầng`
										: "Cùng tầng"}
								</div>
								<div className="digitizer-route-card__label">
									Chuyển trục dọc
								</div>
							</div>
							<button
								type="button"
								className="digitizer-route-card__close"
								onClick={() => setTestRouteResult(null)}
								aria-label="Đóng kết quả tuyến đường"
							>
								<X size={14} />
							</button>
						</div>
					)}

					{/* Bottom Telemetry Strip */}
					<div className="digitizer-telemetry-bar">
						<div className="digitizer-telemetry-item">
							<span>Tọa độ:</span>
							<strong>
								{hoverCoord
									? `${hoverCoord[0].toFixed(6)}, ${hoverCoord[1].toFixed(6)}`
									: "106.809xxx, 10.841xxx"}
							</strong>
							{snapType && (
								<span
									className="digitizer-telemetry-chip"
									style={{
										color: snapType === "VERTEX" ? "#10b981" : "#38bdf8",
									}}
								>
									Snap {snapType === "VERTEX" ? "Đỉnh" : "Cạnh"} [Alt: Tắt]
								</span>
							)}
						</div>

						<div className="digitizer-telemetry-item">
							<span className="digitizer-telemetry-chip">
								{activeBuilding} · {currentFloorMeta.shortLabel}
							</span>
							<span className="digitizer-telemetry-chip">
								Cao độ: {currentFloorMeta.baseElevation}m -{" "}
								{currentFloorMeta.ceilingElevation}m
							</span>
						</div>

						<div className="digitizer-telemetry-item">
							<strong>{currentFloorRooms.length} Phòng</strong>
							<span>·</span>
							<strong>{currentFloorNodes.length} Nút</strong>
							<span>·</span>
							<strong>{currentFloorWalls.length} Vách</strong>
						</div>
					</div>

					<MapGL
						ref={mapRef}
						initialViewState={{
							longitude: currentBuildingMeta.center[0],
							latitude: currentBuildingMeta.center[1],
							zoom: 18.6,
							pitch: 0,
							bearing: 0,
						}}
						onLoad={(e) => e?.target?.setLight?.(STUDIO_LIGHT)}
						onClick={handleMapClick}
						onMouseMove={handleMapMouseMove}
						onMouseDown={handleMapMouseDown}
						onMouseUp={handleMapMouseUp}
						cursor={studioCursor}
						interactiveLayerIds={studioInteractiveLayerIds}
						light={STUDIO_LIGHT}
						mapStyle={OSM_STYLE}
						style={{ width: "100%", height: "100%" }}
						minZoom={14}
						maxZoom={22}
						boxZoom={false}
						dragPan={
							!isDraggingNavNode && !(activeTool === "SELECT" && shiftKeyDown)
						}
					>
						<NavigationControl
							position="top-right"
							showCompass
							showZoom
						/>

						{/* Marquee Drag Box Selection */}
						{marqueeGeoJSON && (
							<Source
								id="studio-marquee-src"
								type="geojson"
								data={marqueeGeoJSON}
							>
								<Layer
									id="studio-marquee-fill"
									type="fill"
									paint={{
										"fill-color": "#f97316",
										"fill-opacity": 0.18,
									}}
								/>
								<Layer
									id="studio-marquee-line"
									type="line"
									paint={{
										"line-color": "#f97316",
										"line-width": 1.5,
										"line-dasharray": [3, 2],
									}}
								/>
							</Source>
						)}

						{/* 1. Blueprint PNG Tracing Overlay */}
						{layerVisibility.png && currentFloorMeta?.pngUrl && (
							<Source
								id="studio-png-blueprint"
								type="image"
								url={currentFloorMeta.pngUrl}
								coordinates={activeCorners}
							>
								<Layer
									id="studio-png-blueprint-layer"
									type="raster"
									paint={{
										"raster-opacity": pngOpacity,
										"raster-fade-duration": 0,
									}}
								/>
							</Source>
						)}

						{/* 2. Ghost Lower Floors (3D Preview only) */}
						{is3dPreview && layerVisibility.boundaries && (
							<Source
								id="studio-ghost-slab-src"
								type="geojson"
								data={vector3dSplit.slabs.ghost}
							>
								<Layer
									id="studio-ghost-slabs-layer"
									type="fill-extrusion"
									paint={{
										"fill-extrusion-color": "#94a3b8",
										"fill-extrusion-height": ["get", "extrudeHeight"],
										"fill-extrusion-base": ["get", "baseHeight"],
										"fill-extrusion-opacity": 0.16,
									}}
								/>
							</Source>
						)}
						{is3dPreview &&
							(layerVisibility.walls || layerVisibility.boundaries) && (
								<Source
									id="studio-ghost-walls-src"
									type="geojson"
									data={vector3dSplit.walls.ghost}
								>
									<Layer
										id="studio-ghost-walls-layer"
										type="fill-extrusion"
										filter={[
											"all",
											...(layerVisibility.boundaries
												? []
												: [["!=", ["get", "wallKind"], "BOUNDARY"]]),
											...(layerVisibility.walls
												? []
												: [["!=", ["get", "wallKind"], "CUSTOM_WALL"]]),
										]}
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-opacity": 0.18,
											"fill-extrusion-vertical-gradient": true,
										}}
									/>
								</Source>
							)}
						{is3dPreview && layerVisibility.rooms && (
							<Source
								id="studio-ghost-rooms-src"
								type="geojson"
								data={vector3dSplit.rooms.ghost}
							>
								<Layer
									id="studio-ghost-rooms-layer"
									type="fill-extrusion"
									paint={{
										"fill-extrusion-color": "#94a3b8",
										"fill-extrusion-height": ["get", "extrudeHeight"],
										"fill-extrusion-base": ["get", "baseHeight"],
										"fill-extrusion-opacity": 0.18,
										"fill-extrusion-vertical-gradient": true,
									}}
								/>
							</Source>
						)}

						{/* 3. Active Floor Slab */}
						{layerVisibility.boundaries && (
							<Source
								id="studio-slab-src"
								type="geojson"
								data={vector3dSplit.slabs.active}
							>
								{is3dPreview ? (
									<Layer
										key="studio-slab-layer"
										id="studio-slab-layer"
										type="fill-extrusion"
										paint={{
											"fill-extrusion-color": "#cbd5e1",
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-opacity": 0.6,
										}}
									/>
								) : (
									<Layer
										key="studio-slab-fill"
										id="studio-slab-fill"
										type="fill"
										paint={{
											"fill-color": "#cbd5e1",
											"fill-opacity": 0.14,
										}}
									/>
								)}
							</Source>
						)}

						{/* 4. Building Outer Walls & Custom Interior Walls */}
						{!is3dPreview &&
							(layerVisibility.walls || layerVisibility.boundaries) && (
								<>
									<Source
										id="studio-wall-lines-src"
										type="geojson"
										data={studioWallLinesGeoJSON}
									>
										<Layer
											id="studio-walls-hit-layer"
											type="line"
											layout={{ "line-cap": "round", "line-join": "round" }}
											paint={{
												"line-color": "#000000",
												"line-width": 14,
												"line-opacity": 0.01,
											}}
										/>
										<Layer
											id="studio-walls-line-layer"
											type="line"
											layout={{ "line-cap": "round", "line-join": "round" }}
											paint={{
												"line-color": [
													"case",
													["get", "isSelected"],
													"#f97316",
													["==", ["get", "wallKind"], "CUSTOM_WALL"],
													"#38bdf8",
													"#64748b",
												],
												"line-width": [
													"case",
													["get", "isSelected"],
													4.5,
													["==", ["get", "wallKind"], "CUSTOM_WALL"],
													3,
													2.5,
												],
												"line-opacity": 0.94,
											}}
										/>
									</Source>
									<Source
										id="studio-wall-endpoints-src"
										type="geojson"
										data={studioWallEndpointsGeoJSON}
									>
										<Layer
											id="studio-wall-endpoints-layer"
											type="circle"
											paint={{
												"circle-radius": [
													"case",
													["get", "isSelected"],
													4.5,
													3,
												],
												"circle-color": [
													"case",
													["get", "isSelected"],
													"#f97316",
													["==", ["get", "wallKind"], "CUSTOM_WALL"],
													"#38bdf8",
													"#94a3b8",
												],
												"circle-stroke-width": 1.5,
												"circle-stroke-color": "#0f172a",
											}}
										/>
									</Source>
								</>
							)}

						{is3dPreview &&
							(layerVisibility.walls || layerVisibility.boundaries) && (
								<Source
									id="studio-walls-src"
									type="geojson"
									data={vector3dSplit.walls.active}
								>
									<Layer
										id="studio-walls-layer"
										type="fill-extrusion"
										filter={[
											"all",
											...(layerVisibility.boundaries
												? []
												: [["!=", ["get", "wallKind"], "BOUNDARY"]]),
											...(layerVisibility.walls
												? []
												: [["!=", ["get", "wallKind"], "CUSTOM_WALL"]]),
										]}
										paint={{
											"fill-extrusion-color": [
												"case",
												[
													"any",
													["==", ["get", "id"], selectedWallId || ""],
													["==", ["get", "id"], selectedBoundaryId || ""],
												],
												"#f97316",
												"#475569",
											],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-opacity": 0.88,
											"fill-extrusion-vertical-gradient": true,
										}}
									/>
								</Source>
							)}

						{/* 5. Active Floor Rooms */}
						{layerVisibility.rooms && (
							<Source
								id="studio-rooms-src"
								type="geojson"
								data={vector3dSplit.rooms.active}
							>
								{is3dPreview && (
									<Layer
										key="studio-rooms-extrusion"
										id="studio-rooms-extrusion"
										type="fill-extrusion"
										paint={{
											"fill-extrusion-color": [
												"case",
												["in", ["get", "id"], ["literal", selectedRoomIds]],
												"#f97316",
												"#0284c7",
											],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-opacity": 0.72,
											"fill-extrusion-vertical-gradient": true,
										}}
									/>
								)}
								{!is3dPreview && (
									<Layer
										key="studio-rooms-fill"
										id="studio-rooms-fill"
										type="fill"
										paint={{
											"fill-color": [
												"case",
												["in", ["get", "id"], ["literal", selectedRoomIds]],
												"#f97316",
												"#0284c7",
											],
											"fill-opacity": 0.25,
										}}
									/>
								)}
								{!is3dPreview && (
									<Layer
										key="studio-rooms-outline"
										id="studio-rooms-outline"
										type="line"
										paint={{
											"line-color": [
												"case",
												["in", ["get", "id"], ["literal", selectedRoomIds]],
												"#fb923c",
												"#38bdf8",
											],
											"line-width": 2,
										}}
									/>
								)}
							</Source>
						)}

						{/* 5. Draft Preview while drawing */}
						<Source
							id="studio-draft-src"
							type="geojson"
							data={draftPreviewGeoJSON}
						>
							<Layer
								id="studio-draft-fill"
								type="fill"
								paint={{ "fill-color": "#f97316", "fill-opacity": 0.25 }}
							/>
							<Layer
								id="studio-draft-line"
								type="line"
								paint={{
									"line-color": "#f97316",
									"line-width": 2.5,
									"line-dasharray": [2, 1],
								}}
							/>
						</Source>

						{/* 6. Navigation Graph Edges & Nodes */}
						{layerVisibility.navGraph && (
							<>
								<Source
									id="studio-nav-edges-src"
									type="geojson"
									data={navGraphLayers.edges}
								>
									<Layer
										id="studio-nav-edges-layer"
										type="line"
										paint={{
											"line-color": [
												"case",
												["get", "isVertical"],
												"#a855f7",
												"#10b981",
											],
											"line-width": 3,
										}}
									/>
								</Source>

								<Source
									id="studio-nav-nodes-src"
									type="geojson"
									data={navGraphLayers.nodes}
								>
									<Layer
										id="studio-nav-nodes-layer"
										type="circle"
										paint={{
											"circle-radius": [
												"case",
												["get", "isSelected"],
												9,
												["get", "isChainHead"],
												8,
												5.5,
											],
											"circle-color": [
												"match",
												["get", "nodeType"],
												"CORRIDOR",
												"#0ea5e9",
												"DOOR",
												"#f97316",
												"STAIRS",
												"#a855f7",
												"ELEVATOR",
												"#ec4899",
												"ENTRANCE",
												"#eab308",
												"#10b981",
											],
											"circle-stroke-width": [
												"case",
												["get", "isSelected"],
												3,
												2,
											],
											"circle-stroke-color": [
												"case",
												["get", "isSelected"],
												"#f37021",
												"#ffffff",
											],
										}}
									/>
								</Source>
							</>
						)}

						{/* 7. Live A* Route Test Path (2D Line in 2D mode; Elevated 3D Ribbon in 3D mode) */}
						{testRouteResult?.geojson && !is3dPreview && (
							<Source
								id="studio-test-route-src"
								type="geojson"
								data={testRouteResult.geojson}
							>
								<Layer
									id="studio-test-route-layer"
									type="line"
									paint={{ "line-color": "#f97316", "line-width": 5 }}
								/>
							</Source>
						)}

						{testRouteResult?.geojson && is3dPreview && (
							<>
								<Source
									id="studio-test-route-ribbon-src"
									type="geojson"
									data={studioRoute3dLayers.ribbonsGeoJSON}
								>
									<Layer
										id="studio-test-route-ghost-3d"
										type="fill-extrusion"
										filter={["==", ["get", "isGhost"], true]}
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-opacity": 0.35,
										}}
									/>
									<Layer
										id="studio-test-route-3d"
										type="fill-extrusion"
										filter={["!=", ["get", "isGhost"], true]}
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-opacity": 0.96,
										}}
									/>
								</Source>
								<Source
									id="studio-test-route-stairs-src"
									type="geojson"
									data={studioRoute3dLayers.stairBeaconsGeoJSON}
								>
									<Layer
										id="studio-test-route-stairs-3d"
										type="fill-extrusion"
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-opacity": 0.9,
										}}
									/>
								</Source>
							</>
						)}

						{/* 7a. Test Route: 1st Click Start Point (Pedestrian Marker) */}
						{testRouteStart && (
							<Marker
								longitude={testRouteStart.coords[0]}
								latitude={testRouteStart.coords[1]}
								anchor="center"
								style={NON_INTERACTIVE_MARKER_STYLE}
							>
								<div className="digitizer-route-pedestrian-marker" title="Xuất phát">
									<div className="digitizer-route-pedestrian-pulse" />
									<div className="digitizer-route-pedestrian-badge">
										<svg
											width="14"
											height="14"
											viewBox="0 0 24 24"
											fill="none"
											stroke="currentColor"
											strokeWidth="2.5"
											strokeLinecap="round"
											strokeLinejoin="round"
										>
											<circle cx="12" cy="4" r="2" />
											<path d="m9 20 3-6 3 2 2 4" />
											<path d="m6 12 3-3 4 2 4-3" />
											<path d="m10 14-2 7" />
										</svg>
									</div>
								</div>
							</Marker>
						)}

						{/* 7b. Test Route: Result Endpoints (Start Pedestrian + Destination Teardrop Pin) */}
						{testRouteResult && routeEndpoints && (
							<>
								<Marker
									longitude={routeEndpoints.start[0]}
									latitude={routeEndpoints.start[1]}
									anchor="center"
									style={NON_INTERACTIVE_MARKER_STYLE}
								>
									<div className="digitizer-route-pedestrian-marker" title="Xuất phát">
										<div className="digitizer-route-pedestrian-pulse" />
										<div className="digitizer-route-pedestrian-badge">
											<svg
												width="14"
												height="14"
												viewBox="0 0 24 24"
												fill="none"
												stroke="currentColor"
												strokeWidth="2.5"
												strokeLinecap="round"
												strokeLinejoin="round"
											>
												<circle cx="12" cy="4" r="2" />
												<path d="m9 20 3-6 3 2 2 4" />
												<path d="m6 12 3-3 4 2 4-3" />
												<path d="m10 14-2 7" />
											</svg>
										</div>
									</div>
								</Marker>

								<Marker
									longitude={routeEndpoints.end[0]}
									latitude={routeEndpoints.end[1]}
									anchor="bottom"
									style={NON_INTERACTIVE_MARKER_STYLE}
								>
									<div className="digitizer-route-dest-pin" title="Điểm đến">
										<svg
											className="digitizer-route-dest-pin__svg"
											width="26"
											height="34"
											viewBox="0 0 24 32"
											fill="none"
										>
											<path
												d="M12 0C5.37 0 0 5.37 0 12c0 8.5 12 20 12 20s12-11.5 12-20c0-6.63-5.37-12-12-12z"
												fill="#ef4444"
												stroke="#ffffff"
												strokeWidth="1.5"
											/>
											<circle cx="12" cy="11" r="4.5" fill="#ffffff" />
										</svg>
										<div className="digitizer-route-dest-pin__shadow" />
									</div>
								</Marker>
							</>
						)}

						{/* 7c. Room Slice Tool: 1st Click Start Marker */}
						{activeTool === "ROOM_SLICE" && draftPoints.length === 1 && (
							<Marker
								longitude={draftPoints[0][0]}
								latitude={draftPoints[0][1]}
								anchor="center"
								style={NON_INTERACTIVE_MARKER_STYLE}
							>
								<div
									className="digitizer-slice-start-marker"
									title="Điểm bắt đầu đường cắt"
								>
									<span className="digitizer-slice-start-dot" />
								</div>
							</Marker>
						)}

						{/* 8. Magnetic Snap Indicator Ring */}
						{snappedCoord && (
							<Marker
								longitude={snappedCoord[0]}
								latitude={snappedCoord[1]}
								anchor="center"
								style={NON_INTERACTIVE_MARKER_STYLE}
							>
								<div
									className={`digitizer-snap-ring ${snapType === "EDGE" ? "digitizer-snap-ring--edge" : ""}`}
									title={`Bắt dính vào ${snapType === "VERTEX" ? "Đỉnh góc" : "Cạnh tường"}`}
								/>
							</Marker>
						)}

						{/* 9. Draggable Vertices for Selected Room / Boundary / Custom Wall */}
						{singleSelectedRoom &&
							activeTool === "SELECT" &&
							!layerLocks.rooms &&
							(isClosedRing(singleSelectedRoom.ring)
								? singleSelectedRoom.ring.slice(0, -1)
								: singleSelectedRoom.ring
							).map((pt, idx) => (
								<Marker
									key={`rv-${singleSelectedRoom.id}-${idx}`}
									longitude={pt[0]}
									latitude={pt[1]}
									anchor="center"
									draggable
									onClick={(e) => e?.originalEvent?.stopPropagation?.()}
									onDrag={(e) =>
										handleVertexDragLive(
											"ROOM",
											singleSelectedRoom.id,
											idx,
											e.lngLat,
										)
									}
									onDragEnd={(e) =>
										handleVertexDragEnd(
											"ROOM",
											singleSelectedRoom.id,
											idx,
											e.lngLat,
										)
									}
								>
									<div
										className="digitizer-vertex-handle"
										title="Kéo để chỉnh đỉnh phòng"
									/>
								</Marker>
							))}

						{singleSelectedBoundary &&
							activeTool === "SELECT" &&
							!layerLocks.boundaries &&
							(isClosedRing(singleSelectedBoundary.ring)
								? singleSelectedBoundary.ring.slice(0, -1)
								: singleSelectedBoundary.ring
							).map((pt, idx) => (
								<Marker
									key={`bv-${singleSelectedBoundary.id}-${idx}`}
									longitude={pt[0]}
									latitude={pt[1]}
									anchor="center"
									draggable
									onClick={(e) => e?.originalEvent?.stopPropagation?.()}
									onDrag={(e) =>
										handleVertexDragLive(
											"BOUNDARY",
											singleSelectedBoundary.id,
											idx,
											e.lngLat,
										)
									}
									onDragEnd={(e) =>
										handleVertexDragEnd(
											"BOUNDARY",
											singleSelectedBoundary.id,
											idx,
											e.lngLat,
										)
									}
								>
									<div
										className="digitizer-vertex-handle digitizer-vertex-handle--boundary"
										title="Kéo để chỉnh góc tường bao"
									/>
								</Marker>
							))}

						{singleSelectedBoundary &&
							activeTool === "SELECT" &&
							!is3dPreview &&
							!layerLocks.boundaries &&
							!liveVertexDrag &&
							(isClosedRing(singleSelectedBoundary.ring)
								? singleSelectedBoundary.ring.slice(0, -1)
								: singleSelectedBoundary.ring || []
							).map((pt, idx, arr) => {
								const nextPt = arr[(idx + 1) % arr.length];
								if (!nextPt) return null;
								const mid = [
									Number(((pt[0] + nextPt[0]) / 2).toFixed(6)),
									Number(((pt[1] + nextPt[1]) / 2).toFixed(6)),
								];
									return (
										<Marker
											key={`bm-${singleSelectedBoundary.id}-${idx}`}
											longitude={mid[0]}
											latitude={mid[1]}
											anchor="center"
											onClick={(e) => e?.originalEvent?.stopPropagation?.()}
										>
											<button
												type="button"
												className="digitizer-midpoint-handle"
												onClick={(e) => {
													e.stopPropagation();
													handleInsertVertexOnEdge(
														"BOUNDARY",
														singleSelectedBoundary.id,
														idx,
														mid,
													);
												}}
												title="Nhấn để thêm chốt góc trên cạnh tường bao"
												aria-label="Thêm chốt góc tường bao"
											>
												+
											</button>
										</Marker>
									);
								})}

						{singleSelectedWall &&
							activeTool === "SELECT" &&
							!layerLocks.walls &&
							singleSelectedWall.line.map((pt, idx) => (
								<Marker
									key={`wv-${singleSelectedWall.id}-${idx}`}
									longitude={pt[0]}
									latitude={pt[1]}
									anchor="center"
									draggable
									onClick={(e) => e?.originalEvent?.stopPropagation?.()}
									onDrag={(e) =>
										handleVertexDragLive(
											"WALL",
											singleSelectedWall.id,
											idx,
											e.lngLat,
										)
									}
									onDragEnd={(e) =>
										handleVertexDragEnd(
											"WALL",
											singleSelectedWall.id,
											idx,
											e.lngLat,
										)
									}
								>
									<div
										className="digitizer-vertex-handle digitizer-vertex-handle--wall"
										title="Kéo để chỉnh đầu mút vách ngăn"
									/>
								</Marker>
							))}

						{singleSelectedWall &&
							activeTool === "SELECT" &&
							!is3dPreview &&
							!layerLocks.walls &&
							!liveVertexDrag &&
							(singleSelectedWall.line || []).slice(0, -1).map((pt, idx) => {
								const nextPt = singleSelectedWall.line[idx + 1];
								if (!nextPt) return null;
								const mid = [
									Number(((pt[0] + nextPt[0]) / 2).toFixed(6)),
									Number(((pt[1] + nextPt[1]) / 2).toFixed(6)),
								];
								return (
									<Marker
										key={`wm-${singleSelectedWall.id}-${idx}`}
										longitude={mid[0]}
										latitude={mid[1]}
										anchor="center"
										onClick={(e) => e?.originalEvent?.stopPropagation?.()}
									>
										<button
											type="button"
											className="digitizer-midpoint-handle digitizer-midpoint-handle--wall"
											onClick={(e) => {
												e.stopPropagation();
												handleInsertVertexOnEdge(
													"WALL",
													singleSelectedWall.id,
													idx,
													mid,
												);
											}}
											title="Nhấn để thêm điểm gập khúc trên vách ngăn"
											aria-label="Thêm điểm gập khúc vách ngăn"
										>
											+
										</button>
									</Marker>
								);
							})}

						{/* 10. Minimalist CAD PNG Calibration Handles & Frame */}
						{activeTool === "CALIBRATE_PNG" && (
							<>
								{pngCalibrationGeoJSON && (
									<Source
										id="studio-png-cal-frame-src"
										type="geojson"
										data={pngCalibrationGeoJSON}
									>
										<Layer
											id="studio-png-cal-frame-line"
											type="line"
											paint={{
												"line-color": "#38bdf8",
												"line-width": 1.5,
												"line-dasharray": [4, 3],
											}}
										/>
									</Source>
								)}

								{activeCorners.map((pt, i) => {
									const cornerLabels = ["NW", "NE", "SE", "SW"];
									const cornerCursors = [
										"nwse-resize",
										"nesw-resize",
										"nwse-resize",
										"nesw-resize",
									];
									return (
										<Marker
											key={`png-cor-${i}`}
											longitude={pt[0]}
											latitude={pt[1]}
											anchor="center"
											draggable
											onClick={(e) => e?.originalEvent?.stopPropagation?.()}
											onDrag={(e) => handleCornerDragLive(i, e.lngLat)}
											onDragEnd={(e) => handleCornerDragEnd(i, e.lngLat)}
										>
											<div
												className="digitizer-cad-corner-handle"
												style={{ cursor: cornerCursors[i] }}
												title={`Góc ${cornerLabels[i]} · Kéo co giãn ảnh`}
												aria-label={`Góc ${cornerLabels[i]}`}
											>
												<span className="digitizer-cad-corner-handle__dot" />
											</div>
										</Marker>
									);
								})}

								{/* Center Move Handle (Mappedin-style Pan Anchor) */}
								<Marker
									longitude={pngRotationMeta.center[0]}
									latitude={pngRotationMeta.center[1]}
									draggable
									onClick={(e) => e?.originalEvent?.stopPropagation?.()}
									onDrag={(e) => handleCenterDragLive(e.lngLat)}
									onDragEnd={(e) => handleCenterDragEnd(e.lngLat)}
									anchor="center"
								>
									<div
										className="digitizer-cad-center-handle"
										title="Kéo để di chuyển toàn bộ bản vẽ"
										aria-label="Di chuyển ảnh"
									>
										<Move size={13} strokeWidth={2.4} />
									</div>
								</Marker>

								{/* Rotation Stalk Handle */}
								<Marker
									longitude={pngRotationMeta.handle[0]}
									latitude={pngRotationMeta.handle[1]}
									anchor="center"
									draggable
									onClick={(e) => e?.originalEvent?.stopPropagation?.()}
									onDrag={(e) => handleRotateDragLive(e.lngLat)}
									onDragEnd={(e) => handleRotateDragEnd(e.lngLat)}
								>
									<div
										className="digitizer-cad-rotate-handle"
										title="Kéo để xoay bản vẽ"
										aria-label="Xoay ảnh"
									>
										<RotateCcw size={12} strokeWidth={2.5} />
									</div>
								</Marker>
							</>
						)}
					</MapGL>
				</main>

				{/* 3. RIGHT CONTEXTUAL INSPECTOR (320px) */}
				<aside
					className="digitizer-inspector"
					aria-label="Bảng thuộc tính"
				>
					{/* Inspector Top Tabs */}
					<div
						className="digitizer-inspector-tabs"
						role="tablist"
					>
						<button
							type="button"
							role="tab"
							aria-selected={activeInspectorTab === "inspector"}
							className={`digitizer-inspector-tab ${activeInspectorTab === "inspector" ? "is-active" : ""}`}
							onClick={() => setActiveInspectorTab("inspector")}
						>
							<SlidersHorizontal size={12} />
							<span>Thuộc tính</span>
						</button>

						<button
							type="button"
							role="tab"
							aria-selected={activeInspectorTab === "layers"}
							className={`digitizer-inspector-tab ${activeInspectorTab === "layers" ? "is-active" : ""}`}
							onClick={() => setActiveInspectorTab("layers")}
						>
							<Layers size={12} />
							<span>Thao tác</span>
							<span className="digitizer-inspector-badge">
								{currentFloorRooms.length}
							</span>
						</button>

						<button
							type="button"
							role="tab"
							aria-selected={activeInspectorTab === "validator"}
							className={`digitizer-inspector-tab ${activeInspectorTab === "validator" ? "is-active" : ""}`}
							onClick={() => setActiveInspectorTab("validator")}
						>
							<ShieldCheck size={12} />
							<span>Kiểm tra</span>
							{validationIssues.length > 0 && (
								<span className="digitizer-inspector-badge digitizer-inspector-badge--warning">
									{validationIssues.length}
								</span>
							)}
						</button>
					</div>

					{/* TAB 1: CONTEXTUAL INSPECTOR */}
					{activeInspectorTab === "inspector" && (
						<div className="digitizer-inspector-content">
							{/* Case A: Selected Room(s) */}
							{selectedRoomIds.length > 0 && (
								<div className="digitizer-prop-card">
									<div className="digitizer-prop-header">
										<span className="digitizer-prop-title">
											<Square size={13} />
											{selectedRoomIds.length === 1
												? "Phòng đang chọn"
												: `Đã chọn ${selectedRoomIds.length} phòng`}
										</span>
									</div>

									{selectedRoomIds.length === 1 && singleSelectedRoom && (
										<>
											<div className="digitizer-prop-row">
												<label>Mã:</label>
												<span className="digitizer-prop-val">
													{singleSelectedRoom.id}
												</span>
											</div>
											<div className="digitizer-prop-row">
												<label>Số đỉnh:</label>
												<span className="digitizer-prop-val">
													{singleSelectedRoom.ring.length - 1}
												</span>
											</div>
											<div className="digitizer-prop-row">
												<label>Diện tích:</label>
												<span className="digitizer-prop-val">
													{approximatePolygonAreaMeters(
														singleSelectedRoom.ring,
													).toFixed(1)}{" "}
													m²
												</span>
											</div>
										</>
									)}

									{selectedRoomIds.length > 1 && (
										<>
											<div className="digitizer-prop-row">
												<label>Số lượng:</label>
												<span className="digitizer-prop-val">
													{selectedRoomIds.length} phòng
												</span>
											</div>
											<div className="digitizer-prop-row">
												<label>Tổng diện tích:</label>
												<span className="digitizer-prop-val">
													{currentFloorRooms
														.filter((r) => selectedRoomIds.includes(r.id))
														.reduce(
															(acc, r) =>
																acc + approximatePolygonAreaMeters(r.ring),
															0,
														)
														.toFixed(1)}{" "}
													m²
												</span>
											</div>
										</>
									)}

									<div className="digitizer-btn-row">
										<button
											type="button"
											className="digitizer-sub-btn digitizer-sub-btn--danger"
											onClick={deleteSelectedRooms}
										>
											<Trash2 size={12} />
											<span>
												{selectedRoomIds.length > 1
													? `Xóa ${selectedRoomIds.length} phòng`
													: "Xóa phòng"}
											</span>
										</button>
										<button
											type="button"
											className="digitizer-sub-btn"
											onClick={() => setSelectedRoomIds([])}
										>
											<X size={12} />
											<span>Bỏ chọn</span>
										</button>
									</div>
								</div>
							)}

							{/* Case B: Selected Nav Node */}
							{singleSelectedNavNode && (
								<div className="digitizer-prop-card">
									<div className="digitizer-prop-header">
										<span className="digitizer-prop-title">
											<Route size={13} />
											Nút đồ thị ({singleSelectedNavNode.id})
										</span>
									</div>

									<div className="digitizer-field">
										<label className="digitizer-field-label">Loại nút:</label>
										<select
											className="digitizer-field-input"
											value={singleSelectedNavNode.type}
											onChange={(e) => {
												const nextType = e.target.value;
												setStore((prev) => ({
													...prev,
													navNodes: (prev.navNodes || []).map((n) =>
														n.id === singleSelectedNavNode.id
															? { ...n, type: nextType }
															: n,
													),
												}));
											}}
										>
											{NODE_TYPES.map((nt) => (
												<option
													key={nt.id}
													value={nt.id}
												>
													{nt.label}
												</option>
											))}
										</select>
									</div>

									<div className="digitizer-prop-row">
										<label>Tọa độ:</label>
										<span className="digitizer-prop-val">
											{singleSelectedNavNode.coords[0].toFixed(5)},{" "}
											{singleSelectedNavNode.coords[1].toFixed(5)}
										</span>
									</div>

									{(singleSelectedNavNode.type === "STAIRS" ||
										singleSelectedNavNode.type === "ELEVATOR") && (
										<div className="digitizer-field">
											<label className="digitizer-field-label">
												Trục xuyên tầng (portalGroupId):
											</label>
											<input
												type="text"
												className="digitizer-field-input"
												value={singleSelectedNavNode.portalGroupId || ""}
												onChange={(e) => {
													const val = e.target.value.trim().toUpperCase();
													setStore((prev) => ({
														...prev,
														navNodes: (prev.navNodes || []).map((n) =>
															n.id === singleSelectedNavNode.id
																? { ...n, portalGroupId: val }
																: n,
														),
													}));
												}}
												placeholder="VD: HCM_STAIR_W"
											/>
										</div>
									)}

									<p className="digitizer-prop-hint">
										Giữ chuột và kéo trực tiếp hình tròn trên bản đồ để di chuyển nút (hỗ trợ Snap &amp; Undo).
									</p>

									<div className="digitizer-btn-row">
										<button
											type="button"
											className="digitizer-sub-btn digitizer-sub-btn--danger"
											onClick={() =>
												deleteNavNodeById(singleSelectedNavNode.id)
											}
										>
											<Trash2 size={12} />
											<span>Xóa nút</span>
										</button>
										<button
											type="button"
											className="digitizer-sub-btn"
											onClick={() => setSelectedNavNodeId(null)}
										>
											<X size={12} />
											<span>Bỏ chọn</span>
										</button>
									</div>
								</div>
							)}

							{/* Case C: Selected Wall */}
							{singleSelectedWall && (
								<div className="digitizer-prop-card">
									<div className="digitizer-prop-header">
										<span className="digitizer-prop-title">
											<Ruler size={13} />
											Vách tường ({singleSelectedWall.id})
										</span>
									</div>

									<div className="digitizer-prop-row">
										<label>Chiều dài:</label>
										<span className="digitizer-prop-val">
											{(() => {
												const line = singleSelectedWall.line || [];
												let lenMeters = 0;
												for (let i = 0; i < line.length - 1; i++) {
													lenMeters += approximateDistanceMeters(
														line[i],
														line[i + 1],
													);
												}
												return lenMeters.toFixed(1);
											})()}{" "}
											m
										</span>
									</div>

									<div className="digitizer-prop-row">
										<label>Số chốt:</label>
										<span className="digitizer-prop-val">
											{(singleSelectedWall.line || []).length} điểm
										</span>
									</div>

									{singleSelectedWall.line?.length >= 2 && (
										<>
											<div className="digitizer-prop-row">
												<label>Điểm đầu:</label>
												<span className="digitizer-prop-val">
													{singleSelectedWall.line[0][0].toFixed(5)},{" "}
													{singleSelectedWall.line[0][1].toFixed(5)}
												</span>
											</div>
											<div className="digitizer-prop-row">
												<label>Điểm cuối:</label>
												<span className="digitizer-prop-val">
													{singleSelectedWall.line[
														singleSelectedWall.line.length - 1
													][0].toFixed(5)}
													,{" "}
													{singleSelectedWall.line[
														singleSelectedWall.line.length - 1
													][1].toFixed(5)}
												</span>
											</div>
										</>
									)}

									<p className="digitizer-prop-hint">
										Kéo các chốt tròn vàng cam để chỉnh đầu mút, nhấn + trên cạnh để thêm điểm gập khúc, hoặc Delete để xóa.
									</p>

									<div className="digitizer-btn-row">
										<button
											type="button"
											className="digitizer-sub-btn digitizer-sub-btn--danger"
											onClick={() => deleteWallById(singleSelectedWall.id)}
										>
											<Trash2 size={12} />
											<span>Xóa vách</span>
										</button>
										<button
											type="button"
											className="digitizer-sub-btn"
											onClick={() => setSelectedWallId(null)}
										>
											<X size={12} />
											<span>Bỏ chọn</span>
										</button>
									</div>
								</div>
							)}

							{/* Case C2: Selected Building Boundary Wall */}
							{singleSelectedBoundary && (
								<div className="digitizer-prop-card">
									<div className="digitizer-prop-header">
										<span className="digitizer-prop-title">
											<Pentagon size={13} />
											Tường bao tầng ({singleSelectedBoundary.id})
										</span>
									</div>

									<div className="digitizer-prop-row">
										<label>Số đỉnh:</label>
										<span className="digitizer-prop-val">
											{isClosedRing(singleSelectedBoundary.ring)
												? Math.max(0, singleSelectedBoundary.ring.length - 1)
												: singleSelectedBoundary.ring?.length || 0}
										</span>
									</div>

									<div className="digitizer-prop-row">
										<label>Chu vi:</label>
										<span className="digitizer-prop-val">
											{approximateRingPerimeterMeters(
												singleSelectedBoundary.ring,
											).toFixed(1)}{" "}
											m
										</span>
									</div>

									<div className="digitizer-prop-row">
										<label>Diện tích sàn:</label>
										<span className="digitizer-prop-val">
											{approximatePolygonAreaMeters(
												singleSelectedBoundary.ring,
											).toFixed(1)}{" "}
											m²
										</span>
									</div>

									<p className="digitizer-prop-hint">
										Kéo các chốt vuông màu cam ở góc để chỉnh khung bao, nhấn + trên cạnh để thêm góc, hoặc Delete để xóa.
									</p>

									<div className="digitizer-btn-row">
										<button
											type="button"
											className="digitizer-sub-btn digitizer-sub-btn--danger"
											onClick={() =>
												deleteBoundaryById(singleSelectedBoundary.id)
											}
										>
											<Trash2 size={12} />
											<span>Xóa tường bao</span>
										</button>
										<button
											type="button"
											className="digitizer-sub-btn"
											onClick={() => setSelectedBoundaryId(null)}
										>
											<X size={12} />
											<span>Bỏ chọn</span>
										</button>
									</div>
								</div>
							)}

							{/* Case D: Active Tool Specific Inspector Settings */}
							{activeTool === "ROOM_SLICE" && (
								<div className="digitizer-prop-card">
									<span className="digitizer-prop-title">
										<Scissors size={13} />
										Cắt phòng [S]
									</span>
									<div className="digitizer-prop-row">
										<span>Thao tác:</span>
										<span className="digitizer-prop-val">2 điểm cắt</span>
									</div>
									<div className="digitizer-prop-row">
										<span>Phương thức:</span>
										<span className="digitizer-prop-val">Đường cắt 2 điểm</span>
									</div>
								</div>
							)}

							{activeTool === "NAV_GRAPH" && (
								<div className="digitizer-prop-card">
									<span className="digitizer-prop-title">
										<Route size={13} />
										Loại nút khi đặt mới
									</span>

									<div className="digitizer-node-types-grid">
										{NODE_TYPES.map((nt) => (
											<button
												key={nt.id}
												type="button"
												style={{ "--node-color": nt.color }}
												className={`digitizer-node-type-btn ${activeNodeType === nt.id ? "is-active" : ""}`}
												onClick={() => {
													setActiveNodeType(nt.id);
													if (nt.id === "DOOR") setChainLastNodeId(null);
												}}
											>
												<span
													className="digitizer-node-dot"
													style={{ background: nt.color }}
												/>
												<span>{nt.label}</span>
											</button>
										))}
									</div>

									{(activeNodeType === "STAIRS" ||
										activeNodeType === "ELEVATOR") && (
										<div className="digitizer-field">
											<label className="digitizer-field-label">
												Trục xuyên tầng (portalGroupId):
											</label>
											<input
												type="text"
												value={portalGroupId}
												onChange={(e) => setPortalGroupId(e.target.value)}
												placeholder="VD: HCM_STAIR_W"
												className="digitizer-field-input"
											/>
											<div className="digitizer-quick-chips">
												{["HCM_STAIR_W", "HCM_STAIR_E", "EPS_THANG_1"].map(
													(pg) => (
														<button
															key={pg}
															type="button"
															className={`digitizer-quick-chip ${portalGroupId === pg ? "is-active" : ""}`}
															onClick={() => setPortalGroupId(pg)}
														>
															{pg}
														</button>
													),
												)}
											</div>
										</div>
									)}

									{chainLastNodeId && (
										<button
											type="button"
											className="digitizer-sub-btn digitizer-sub-btn--danger"
											onClick={() => setChainLastNodeId(null)}
										>
											<X size={12} />
											<span>Hủy chuỗi nối (Esc)</span>
										</button>
									)}
								</div>
							)}

							{/* Case E: Default Floor Overview */}
							<div className="digitizer-prop-card">
								<span className="digitizer-prop-title">
									<Building2 size={13} />
									Tổng quan {currentFloorMeta.name}
								</span>
								<div className="digitizer-prop-row">
									<label>Tòa nhà:</label>
									<span className="digitizer-prop-val">{activeBuilding}</span>
								</div>
								<div className="digitizer-prop-row">
									<label>Khung tòa:</label>
									<span className="digitizer-prop-val">
										{hasBuildingBoundary ? "Đã vẽ" : "Chưa vẽ"}
									</span>
								</div>
								<div className="digitizer-prop-row">
									<label>Phòng:</label>
									<span className="digitizer-prop-val">
										{currentFloorRooms.length} phòng
									</span>
								</div>
								<div className="digitizer-prop-row">
									<label>Vách ngăn:</label>
									<span className="digitizer-prop-val">
										{currentFloorWalls.length} vách
									</span>
								</div>
								<div className="digitizer-prop-row">
									<label>Nút đồ thị:</label>
									<span className="digitizer-prop-val">
										{currentFloorNodes.length} nút
									</span>
								</div>

								<div
									className="digitizer-btn-row"
									style={{ marginTop: 6 }}
								>
									<button
										type="button"
										className="digitizer-sub-btn"
										onClick={handleCloneRoomsToNextFloor}
										title="Sao chép toàn bộ khối phòng tầng này sang tầng kế tiếp"
									>
										<Copy size={12} />
										<span>Sao chép lên tầng trên</span>
									</button>
									<button
										type="button"
										className="digitizer-sub-btn digitizer-sub-btn--danger"
										onClick={handleClearCurrentFloorRooms}
										title="Xóa toàn bộ khối phòng tầng hiện tại"
									>
										<Trash2 size={12} />
										<span>Xóa tất cả phòng</span>
									</button>
								</div>
							</div>
						</div>
					)}

					{/* TAB 2: LAYERS & SCENE TREE */}
					{activeInspectorTab === "layers" && (
						<div className="digitizer-inspector-content">
							{/* Layer Visibility & Lock Toggles */}
							<div className="digitizer-prop-card">
								<span className="digitizer-prop-title">
									<Layers size={13} />
									Quản lý lớp hiển thị & Khóa
								</span>

								{/* PNG Blueprint */}
								<div className="digitizer-layer-row">
									<span>Bản vẽ PNG</span>
									<div className="digitizer-layer-actions">
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerVisibility.png ? "is-active" : ""}`}
											onClick={() =>
												setLayerVisibility((l) => ({ ...l, png: !l.png }))
											}
											title={
												layerVisibility.png ? "Ẩn lớp PNG" : "Hiện lớp PNG"
											}
											aria-label="Bật tắt lớp PNG"
										>
											{layerVisibility.png ? (
												<Eye size={13} />
											) : (
												<EyeOff size={13} />
											)}
										</button>
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerLocks.png ? "is-active" : ""}`}
											onClick={() =>
												setLayerLocks((l) => ({ ...l, png: !l.png }))
											}
											title={
												layerLocks.png ? "Mở khóa lớp PNG" : "Khóa lớp PNG"
											}
											aria-label="Khóa lớp PNG"
										>
											{layerLocks.png ? (
												<Lock size={13} />
											) : (
												<Unlock size={13} />
											)}
										</button>
									</div>
								</div>

								{layerVisibility.png && (
									<div
										className="digitizer-field"
										style={{ padding: "0 4px" }}
									>
										<div className="digitizer-prop-row">
											<label>Độ rõ nét:</label>
											<span className="digitizer-prop-val">
												{Math.round(pngOpacity * 100)}%
											</span>
										</div>
										<input
											type="range"
											min="0.2"
											max="1"
											step="0.05"
											value={pngOpacity}
											onChange={(e) => setPngOpacity(Number(e.target.value))}
											style={{
												width: "100%",
												accentColor: "#f37021",
												height: 4,
											}}
										/>
									</div>
								)}

								{/* Building Boundary Layer */}
								<div className="digitizer-layer-row">
									<span>Tường bao & Sàn</span>
									<div className="digitizer-layer-actions">
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerVisibility.boundaries ? "is-active" : ""}`}
											onClick={() =>
												setLayerVisibility((l) => ({
													...l,
													boundaries: !l.boundaries,
												}))
											}
											aria-label="Bật tắt lớp Tường bao"
										>
											{layerVisibility.boundaries ? (
												<Eye size={13} />
											) : (
												<EyeOff size={13} />
											)}
										</button>
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerLocks.boundaries ? "is-active" : ""}`}
											onClick={() =>
												setLayerLocks((l) => ({
													...l,
													boundaries: !l.boundaries,
												}))
											}
											aria-label="Khóa lớp Tường bao"
										>
											{layerLocks.boundaries ? (
												<Lock size={13} />
											) : (
												<Unlock size={13} />
											)}
										</button>
									</div>
								</div>

								{/* Room Polygons Layer */}
								<div className="digitizer-layer-row">
									<span>Phòng 3D</span>
									<div className="digitizer-layer-actions">
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerVisibility.rooms ? "is-active" : ""}`}
											onClick={() =>
												setLayerVisibility((l) => ({ ...l, rooms: !l.rooms }))
											}
											aria-label="Bật tắt lớp Phòng"
										>
											{layerVisibility.rooms ? (
												<Eye size={13} />
											) : (
												<EyeOff size={13} />
											)}
										</button>
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerLocks.rooms ? "is-active" : ""}`}
											onClick={() =>
												setLayerLocks((l) => ({ ...l, rooms: !l.rooms }))
											}
											aria-label="Khóa lớp Phòng"
										>
											{layerLocks.rooms ? (
												<Lock size={13} />
											) : (
												<Unlock size={13} />
											)}
										</button>
									</div>
								</div>

								{/* Custom Walls Layer */}
								<div className="digitizer-layer-row">
									<span>Vách ngăn</span>
									<div className="digitizer-layer-actions">
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerVisibility.walls ? "is-active" : ""}`}
											onClick={() =>
												setLayerVisibility((l) => ({ ...l, walls: !l.walls }))
											}
											aria-label="Bật tắt lớp Vách ngăn"
										>
											{layerVisibility.walls ? (
												<Eye size={13} />
											) : (
												<EyeOff size={13} />
											)}
										</button>
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerLocks.walls ? "is-active" : ""}`}
											onClick={() =>
												setLayerLocks((l) => ({ ...l, walls: !l.walls }))
											}
											aria-label="Khóa lớp Vách ngăn"
										>
											{layerLocks.walls ? (
												<Lock size={13} />
											) : (
												<Unlock size={13} />
											)}
										</button>
									</div>
								</div>

								{/* Navigation Graph Layer */}
								<div className="digitizer-layer-row">
									<span>Đồ thị đường</span>
									<div className="digitizer-layer-actions">
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerVisibility.navGraph ? "is-active" : ""}`}
											onClick={() =>
												setLayerVisibility((l) => ({
													...l,
													navGraph: !l.navGraph,
												}))
											}
											aria-label="Bật tắt lớp Đồ thị"
										>
											{layerVisibility.navGraph ? (
												<Eye size={13} />
											) : (
												<EyeOff size={13} />
											)}
										</button>
										<button
											type="button"
											className={`digitizer-layer-icon-btn ${layerLocks.navGraph ? "is-active" : ""}`}
											onClick={() =>
												setLayerLocks((l) => ({ ...l, navGraph: !l.navGraph }))
											}
											aria-label="Khóa lớp Đồ thị"
										>
											{layerLocks.navGraph ? (
												<Lock size={13} />
											) : (
												<Unlock size={13} />
											)}
										</button>
									</div>
								</div>
							</div>

							{/* Scene Tree: Rooms List with Multi-Select */}
							<div className="digitizer-tree-group">
								<div className="digitizer-tree-header">
									<span>Phòng ({currentFloorRooms.length})</span>
									<div className="digitizer-tree-header-actions">
										<button
											type="button"
											className="digitizer-tree-micro-btn"
											onClick={() =>
												setSelectedRoomIds(currentFloorRooms.map((r) => r.id))
											}
											title="Chọn tất cả phòng trên tầng này"
										>
											Tất cả
										</button>
										{selectedRoomIds.length > 0 && (
											<button
												type="button"
												className="digitizer-tree-micro-btn"
												onClick={() => setSelectedRoomIds([])}
												title="Bỏ chọn tất cả"
											>
												Bỏ
											</button>
										)}
									</div>
								</div>

								<div className="digitizer-tree-list">
									{currentFloorRooms.length === 0 ? (
										<span className="digitizer-tree-empty">
											Chưa có phòng nào trên tầng này
										</span>
									) : (
										currentFloorRooms.map((rm, idx) => {
											const isSelected = selectedRoomIds.includes(rm.id);
											return (
												<div
													key={rm.id}
													className={`digitizer-tree-item-btn ${isSelected ? "is-selected" : ""}`}
												>
													<button
														type="button"
														className={`digitizer-tree-chk ${isSelected ? "is-checked" : ""}`}
														onClick={() =>
															setSelectedRoomIds((prev) =>
																prev.includes(rm.id)
																	? prev.filter((id) => id !== rm.id)
																	: [...prev, rm.id],
															)
														}
														title={isSelected ? "Bỏ chọn phòng" : "Chọn thêm phòng"}
														aria-label={
															isSelected
																? `Bỏ chọn phòng ${idx + 1}`
																: `Chọn phòng ${idx + 1}`
														}
													>
														{isSelected && <Check size={10} strokeWidth={3} />}
													</button>
													<button
														type="button"
														className="digitizer-tree-item-select"
														onClick={(e) => {
															if (e.shiftKey) {
																setSelectedRoomIds((prev) =>
																	prev.includes(rm.id)
																		? prev.filter((id) => id !== rm.id)
																		: [...prev, rm.id],
																);
															} else {
																setSelectedRoomIds([rm.id]);
															}
															setSelectedNavNodeId(null);
															setSelectedWallId(null);
															setSelectedBoundaryId(null);
															setActiveTool("SELECT");
														}}
													>
														<Square
															size={12}
															color={isSelected ? "#f97316" : "#38bdf8"}
														/>
														<span className="digitizer-tree-item-title">
															Phòng #{idx + 1} (
															{approximatePolygonAreaMeters(rm.ring).toFixed(0)}{" "}
															m²)
														</span>
													</button>
													<button
														type="button"
														className="digitizer-tree-delete-btn"
														onClick={() => deleteRoomById(rm.id)}
														title="Xóa phòng này"
														aria-label={`Xóa phòng ${idx + 1}`}
													>
														<Trash2 size={12} />
													</button>
												</div>
											);
										})
									)}
								</div>
							</div>

							{/* Scene Tree: Building Boundary Walls */}
							<div className="digitizer-tree-group">
								<div className="digitizer-tree-header">
									<span>Tường bao tầng</span>
									<span className="digitizer-inspector-badge">
										{currentFloorBoundaries.length}
									</span>
								</div>

								<div className="digitizer-tree-list">
									{currentFloorBoundaries.length === 0 ? (
										<span className="digitizer-tree-empty">
											Chưa có tường bao nào
										</span>
									) : (
										currentFloorBoundaries.map((bd, idx) => {
											const isSelected = selectedBoundaryId === bd.id;
											return (
												<div
													key={bd.id}
													className={`digitizer-tree-item-btn ${isSelected ? "is-selected" : ""}`}
												>
													<button
														type="button"
														className="digitizer-tree-item-select"
														onClick={() => {
															setSelectedBoundaryId(bd.id);
															setSelectedWallId(null);
															setSelectedNavNodeId(null);
															setSelectedRoomIds([]);
															setActiveTool("SELECT");
															setActiveInspectorTab("inspector");
														}}
													>
														<Pentagon
															size={12}
															color={isSelected ? "#f97316" : "#94a3b8"}
														/>
														<span className="digitizer-tree-item-title">
															Tường bao #{idx + 1} (
															{approximateRingPerimeterMeters(bd.ring).toFixed(
																0,
															)}
															m)
														</span>
													</button>
													<button
														type="button"
														className="digitizer-tree-delete-btn"
														onClick={() => deleteBoundaryById(bd.id)}
														title="Xóa tường bao này"
														aria-label={`Xóa tường bao ${idx + 1}`}
													>
														<Trash2 size={12} />
													</button>
												</div>
											);
										})
									)}
								</div>
							</div>

							{/* Scene Tree: Interior Partition Walls */}
							<div className="digitizer-tree-group">
								<div className="digitizer-tree-header">
									<span>Vách ngăn</span>
									<span className="digitizer-inspector-badge">
										{currentFloorWalls.length}
									</span>
								</div>

								<div className="digitizer-tree-list">
									{currentFloorWalls.length === 0 ? (
										<span className="digitizer-tree-empty">
											Chưa có vách ngăn nào
										</span>
									) : (
										currentFloorWalls.map((w, idx) => {
											const isSelected = selectedWallId === w.id;
											const line = w.line || [];
											let lenMeters = 0;
											for (let i = 0; i < line.length - 1; i++) {
												lenMeters += approximateDistanceMeters(
													line[i],
													line[i + 1],
												);
											}
											return (
												<div
													key={w.id}
													className={`digitizer-tree-item-btn ${isSelected ? "is-selected" : ""}`}
												>
													<button
														type="button"
														className="digitizer-tree-item-select"
														onClick={() => {
															setSelectedWallId(w.id);
															setSelectedBoundaryId(null);
															setSelectedNavNodeId(null);
															setSelectedRoomIds([]);
															setActiveTool("SELECT");
															setActiveInspectorTab("inspector");
														}}
													>
														<Ruler
															size={12}
															color={isSelected ? "#f59e0b" : "#cbd5e1"}
														/>
														<span className="digitizer-tree-item-title">
															Vách #{idx + 1} ({lenMeters.toFixed(1)} m)
														</span>
													</button>
													<button
														type="button"
														className="digitizer-tree-delete-btn"
														onClick={() => deleteWallById(w.id)}
														title="Xóa vách này"
														aria-label={`Xóa vách ${idx + 1}`}
													>
														<Trash2 size={12} />
													</button>
												</div>
											);
										})
									)}
								</div>
							</div>

							{/* Scene Tree: Nav Nodes List */}
							<div className="digitizer-tree-group">
								<div className="digitizer-tree-header">
									<span>Nút đồ thị</span>
									<span className="digitizer-inspector-badge">
										{currentFloorNodes.length}
									</span>
								</div>

								<div className="digitizer-tree-list">
									{currentFloorNodes.length === 0 ? (
										<span className="digitizer-tree-empty">
											Chưa có nút nào
										</span>
									) : (
										currentFloorNodes.slice(0, 15).map((nd) => {
											const typeMeta = NODE_TYPES.find((t) => t.id === nd.type);
											const isSelected = selectedNavNodeId === nd.id;
											return (
												<div
													key={nd.id}
													className={`digitizer-tree-item-btn ${isSelected ? "is-selected" : ""}`}
												>
													<button
														type="button"
														className="digitizer-tree-item-select"
														onClick={() => {
															setSelectedNavNodeId(nd.id);
															setSelectedWallId(null);
															setSelectedBoundaryId(null);
															setSelectedRoomIds([]);
															setActiveTool("SELECT");
															setActiveInspectorTab("inspector");
														}}
													>
														<span
															className="digitizer-node-dot"
															style={{
																background: typeMeta?.color || "#94a3b8",
															}}
														/>
														<span className="digitizer-tree-item-title">
															{typeMeta?.label || nd.type}{" "}
															{nd.portalGroupId ? `(${nd.portalGroupId})` : ""}
														</span>
													</button>
													<button
														type="button"
														className="digitizer-tree-delete-btn"
														onClick={() => deleteNavNodeById(nd.id)}
														title="Xóa nút này"
														aria-label={`Xóa nút ${typeMeta?.label || nd.type}`}
													>
														<Trash2 size={12} />
													</button>
												</div>
											);
										})
									)}
									{currentFloorNodes.length > 15 && (
										<span className="digitizer-tree-empty">
											+ {currentFloorNodes.length - 15} nút khác
										</span>
									)}
								</div>
							</div>
						</div>
					)}

					{/* TAB 3: VALIDATOR (GEOMETRY AUDIT) */}
					{activeInspectorTab === "validator" && (
						<div className="digitizer-inspector-content">
							<div className="digitizer-validator-summary">
								<span className="digitizer-prop-title">
									<ShieldCheck size={13} />
									Kiểm tra hình học
								</span>
								<span
									className="digitizer-inspector-badge"
									style={{
										color:
											validationIssues.length === 0 ? "#10b981" : "#f59e0b",
									}}
								>
									{validationIssues.length === 0
										? "Hợp lệ 100%"
										: `${validationIssues.length} cảnh báo`}
								</span>
							</div>

							{validationIssues.length === 0 ? (
								<div
									className="digitizer-prop-card"
									style={{ textAlign: "center", padding: "16px 12px" }}
								>
									<CheckCircle2
										size={24}
										color="#10b981"
										style={{ margin: "0 auto 6px" }}
									/>
									<div className="digitizer-validator-msg">
										Hình học hợp lệ
									</div>
									<div className="digitizer-validator-sub">
										Không phát hiện nút mồ côi hoặc đa giác bất thường.
									</div>
								</div>
							) : (
								validationIssues.map((issue) => (
									<button
										key={issue.id}
										type="button"
										className={`digitizer-validator-card ${issue.level === "error" ? "digitizer-validator-card--error" : "digitizer-validator-card--warning"}`}
										onClick={() => {
											if (mapRef.current && issue.coords) {
												mapRef.current.flyTo({
													center: issue.coords,
													zoom: 19.5,
													duration: 400,
												});
											}
											if (issue.type === "NODE")
												setSelectedNavNodeId(issue.targetId);
											if (issue.type === "ROOM")
												setSelectedRoomIds([issue.targetId]);
											setActiveTool("SELECT");
										}}
									>
										<div className="digitizer-validator-msg">{issue.title}</div>
										<div className="digitizer-validator-sub">{issue.desc}</div>
									</button>
								))
							)}
						</div>
					)}
				</aside>
			</div>

			{/* ==========================================================================
			   IN-APP CONFIRMATION MODAL
			   ========================================================================== */}
			{confirmModal.isOpen && (
				<div
					className="digitizer-modal-backdrop"
					onClick={() => setConfirmModal((m) => ({ ...m, isOpen: false }))}
				>
					<div
						className="digitizer-modal"
						onClick={(e) => e.stopPropagation()}
						role="dialog"
						aria-modal="true"
						aria-label={confirmModal.title}
					>
						<div className="digitizer-modal__header">
							<h3 className="digitizer-modal__title">
								<AlertTriangle
									size={17}
									color={
										confirmModal.confirmVariant === "danger"
											? "#ef4444"
											: "#f59e0b"
									}
								/>
								<span>{confirmModal.title}</span>
							</h3>
							<button
								type="button"
								className="digitizer-close-btn"
								onClick={() =>
									setConfirmModal((m) => ({ ...m, isOpen: false }))
								}
								aria-label="Đóng"
							>
								<X size={15} />
							</button>
						</div>

						<div className="digitizer-modal__body">{confirmModal.message}</div>

						<div className="digitizer-modal__footer">
							<button
								type="button"
								className="digitizer-sub-btn"
								onClick={() =>
									setConfirmModal((m) => ({ ...m, isOpen: false }))
								}
							>
								Hủy bỏ
							</button>
							<button
								type="button"
								className={
									confirmModal.confirmVariant === "danger"
										? "digitizer-sub-btn digitizer-sub-btn--danger"
										: "digitizer-action-btn digitizer-action-btn--primary"
								}
								onClick={confirmModal.onConfirm}
							>
								{confirmModal.confirmLabel}
							</button>
						</div>
					</div>
				</div>
			)}

			{/* ==========================================================================
			   SHORTCUTS REFERENCE MODAL
			   ========================================================================== */}
			{showShortcutsModal && (
				<div
					className="digitizer-modal-backdrop"
					onClick={() => setShowShortcutsModal(false)}
				>
					<div
						className="digitizer-modal"
						onClick={(e) => e.stopPropagation()}
						role="dialog"
						aria-modal="true"
						aria-label="Bảng phím tắt nhanh"
					>
						<div className="digitizer-modal__header">
							<h3 className="digitizer-modal__title">
								<HelpCircle
									size={17}
									color="#f37021"
								/>
								<span>Phím tắt & Thao tác CAD</span>
							</h3>
							<button
								type="button"
								className="digitizer-close-btn"
								onClick={() => setShowShortcutsModal(false)}
								aria-label="Đóng phím tắt"
							>
								<X size={15} />
							</button>
						</div>

						<div className="digitizer-shortcuts-grid">
							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">
									Công cụ số hóa
								</span>
								<div>
									<kbd className="digitizer-hud__kbd">V</kbd> Chọn ·{" "}
									<kbd className="digitizer-hud__kbd">B</kbd> Tạo phòng ·{" "}
									<kbd className="digitizer-hud__kbd">P</kbd> Đa giác ·{" "}
									<kbd className="digitizer-hud__kbd">S</kbd> Cắt phòng
								</div>
							</div>

							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">
									Cấu trúc & Đồ thị
								</span>
								<div>
									<kbd className="digitizer-hud__kbd">O</kbd> Tường bao ·{" "}
									<kbd className="digitizer-hud__kbd">W</kbd> Vách ngăn ·{" "}
									<kbd className="digitizer-hud__kbd">N</kbd> Đồ thị ·{" "}
									<kbd className="digitizer-hud__kbd">T</kbd> Tìm đường
								</div>
							</div>

							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">
									Hoàn tác / Làm lại
								</span>
								<div>
									<kbd className="digitizer-hud__kbd">Ctrl</kbd>+
									<kbd className="digitizer-hud__kbd">Z</kbd> /{" "}
									<kbd className="digitizer-hud__kbd">Ctrl</kbd>+
									<kbd className="digitizer-hud__kbd">Shift</kbd>+
									<kbd className="digitizer-hud__kbd">Z</kbd>
								</div>
							</div>

							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">Lưu thiết kế</span>
								<div>
									<kbd className="digitizer-hud__kbd">Ctrl</kbd>+
									<kbd className="digitizer-hud__kbd">S</kbd>
								</div>
							</div>

							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">
									Đóng đa giác (≥ 3 điểm)
								</span>
								<div>
									<kbd className="digitizer-hud__kbd">Enter</kbd>
								</div>
							</div>

							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">
									Xóa đối tượng chọn
								</span>
								<div>
									<kbd className="digitizer-hud__kbd">Delete</kbd> /{" "}
									<kbd className="digitizer-hud__kbd">Backspace</kbd>
								</div>
							</div>

							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">
									Hủy thao tác / Bỏ chọn
								</span>
								<div>
									<kbd className="digitizer-hud__kbd">Esc</kbd>
								</div>
							</div>

							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">
									Tắt bắt dính (Snap)
								</span>
								<div>
									Giữ phím <kbd className="digitizer-hud__kbd">Alt</kbd>
								</div>
							</div>

							<div className="digitizer-shortcut-row">
								<span className="digitizer-shortcut-desc">
									Chọn nhiều (Multi-Select)
								</span>
								<div>
									Giữ <kbd className="digitizer-hud__kbd">Shift</kbd> /{" "}
									<kbd className="digitizer-hud__kbd">Ctrl</kbd> + Click hoặc kéo chuột
								</div>
							</div>
						</div>
					</div>
				</div>
			)}
		</div>
	);
}
