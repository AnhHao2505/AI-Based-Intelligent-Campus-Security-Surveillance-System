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
	ArrowUpDown,
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
	DoorOpen,
} from "lucide-react";
import {
	BOUNDARY_WALL_HEIGHT,
	DEFAULT_BUILDINGS_META,
	FLOOR_ELEVATION_STEP,
	FLOOR_SLAB_HEIGHT,
	INTERIOR_WALL_HEIGHT,
	loadGeometryStore,
	saveGeometryStore,
	createDefaultGeometryStore,
	buildVector3dLayers,
	buildRoute3dLayers,
	buildAllGraphEdges,
	buildFloorWallGraphModels,
	buildOrientedBoxRing,
	buildOrientedDoorGeometry,
	buildSliceWallSegment,
	deduplicateFloorVertices,
	dragWeldedWallVertices,
	findBoundaryContainingPoint,
	isNodeOutdoor,
	isNodeVisibleOnFloor,
	isPointIndoorOnFloor,
	isPortalNode,
	matchesBuilding,
	mergeOrSplitNavNodeOnDrop,
	resolveBestSnapTarget,
	resolveCanonicalBuildingName,
	resolveNodeBuilding,
	syncPortalAcrossBuildingFloors,
	approximatePolygonAreaMeters,
	getPolygonInteriorPoint,
	isPointInPolygonWithHoles,
	getRingCentroid,
	computeDigitizedRoute,
	migrateStoreToWallGraph,
	splitGhostActiveGeoJSON,
	synchronizeCompatibilityGeometry,
} from "../../data/campusIndoor3dData";
import {
	registerPortalMapImages,
	ensurePortalMapImage,
} from "../../utils/mapPortalIcons";
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
		id: "NODE",
		label: "Nút lối đi (Node)",
		sublabel: "Trong nhà / Sân",
		color: "#0ea5e9",
		icon: Footprints,
	},
	{
		id: "STAIRS",
		label: "Cầu thang",
		sublabel: "Portal Icon",
		color: "#a855f7",
		icon: ArrowUpDown,
	},
	{
		id: "ELEVATOR",
		label: "Thang máy",
		sublabel: "Portal Icon",
		color: "#0284c7",
		icon: Layers,
	},
];

const NODE_DISPLAY_META = {
	NODE: { label: "Nút lối đi", color: "#0ea5e9" },
	CORRIDOR: { label: "Nút lối đi (Hành lang)", color: "#0ea5e9" },
	ENTRANCE: { label: "Nút lối đi (Cổng)", color: "#0ea5e9" },
	OUTDOOR: { label: "Nút lối đi (Sân)", color: "#10b981" },
	DOOR: { label: "Nút cửa (legacy)", color: "#f97316" },
	STAIRS: { label: "Cầu thang bộ", color: "#a855f7" },
	ELEVATOR: { label: "Thang máy", color: "#0284c7" },
};

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
	// "SELECT" | "ROOM_BOX" | "ROOM_POLY" | "ROOM_SLICE" | "WALL_DRAW" | "DOOR" | "NAV_GRAPH" | "TEST_ROUTE" | "CALIBRATE_PNG"
	const [activeTool, setActiveTool] = useState("SELECT");

	// Inspector Tabs: "inspector" | "layers" | "validator"
	const [activeInspectorTab, setActiveInspectorTab] = useState("inspector");

	// Layer Visibility & Locks (Unified Mappedin Wall Graph)
	const [layerVisibility, setLayerVisibility] = useState({
		png: true,
		walls: true,
		faces: true,
		doors: true,
		navGraph: true,
	});
	const [layerLocks, setLayerLocks] = useState({
		png: false,
		walls: false,
		faces: false,
		doors: false,
		navGraph: false,
	});
	const [pngOpacity, setPngOpacity] = useState(0.85);

	// Selected Geometry IDs (`selectedRoomIds` holds selected Planar Face IDs across ROOM / CORRIDOR / VOID)
	const [selectedRoomIds, setSelectedRoomIds] = useState([]);
	const [selectedNavNodeId, setSelectedNavNodeId] = useState(null);
	const [selectedWallId, setSelectedWallId] = useState(null);
	const [selectedDoorId, setSelectedDoorId] = useState(null);

	const selectEntity = useCallback((kind, payload = null) => {
		setSelectedRoomIds(
			kind === "FACE"
				? Array.isArray(payload)
					? payload
					: payload
						? [payload]
						: []
				: [],
		);
		setSelectedWallId(kind === "WALL" ? payload : null);
		setSelectedDoorId(kind === "DOOR" ? payload : null);
		setSelectedNavNodeId(kind === "NODE" ? payload : null);
	}, []);

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
	const [liveVertexDrag, setLiveVertexDrag] = useState(null); // { kind: "WALL", id, index, coords }
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
	const [activeNodeType, setActiveNodeType] = useState("NODE");
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
		if (currentFloorMeta?.defaultBounds?.corners?.length === 4) {
			return currentFloorMeta.defaultBounds.corners;
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
		if (liveVertexDrag?.kind === "WALL") {
			const { id, index, coords, detachSingle } = liveVertexDrag;
			next = {
				...next,
				walls: dragWeldedWallVertices(next.walls || [], {
					wallId: id,
					vertexIndex: index,
					newCoord: coords,
					detachSingle: Boolean(detachSingle),
				}),
			};
		} else if (liveVertexDrag?.kind === "DOOR_MOVE") {
			const { id, coords } = liveVertexDrag;
			next = {
				...next,
				doors: (next.doors || []).map((d) =>
					d.id === id ? { ...d, coords } : d,
				),
			};
		} else if (liveVertexDrag?.kind === "DOOR_WIDTH") {
			const { id, widthMeters } = liveVertexDrag;
			next = {
				...next,
				doors: (next.doors || []).map((d) =>
					d.id === id ? { ...d, widthMeters } : d,
				),
			};
		}
		return next;
	}, [store, liveNavNodeDrag, liveVertexDrag]);

	const activeCanonicalBuilding = useMemo(
		() => resolveCanonicalBuildingName(activeBuilding) || activeBuilding,
		[activeBuilding],
	);

	// Derived Mappedin Wall Graph floor models (Planar Face Extraction, Wall Segments, Dead-ends & Doors)
	const floorWallModels = useMemo(
		() => buildFloorWallGraphModels(effectiveStore),
		[effectiveStore],
	);

	const currentFloorModel = useMemo(
		() =>
			floorWallModels.get(`${activeCanonicalBuilding}__${activeFloorOrder}`) ||
			null,
		[floorWallModels, activeCanonicalBuilding, activeFloorOrder],
	);

	const currentFloorFaces = useMemo(
		() => currentFloorModel?.faces || [],
		[currentFloorModel],
	);

	// Current floor ROOM faces (extracted from closed wall loops)
	const currentFloorRooms = useMemo(
		() => currentFloorFaces.filter((f) => f.role === "ROOM"),
		[currentFloorFaces],
	);

	const currentFloorNodes = useMemo(
		() =>
			(effectiveStore.navNodes || []).filter((n) =>
				isNodeVisibleOnFloor(
					effectiveStore.buildingBoundaries,
					n,
					activeBuilding,
					activeFloorOrder,
				),
			),
		[
			effectiveStore.navNodes,
			effectiveStore.buildingBoundaries,
			activeBuilding,
			activeFloorOrder,
		],
	);

	const currentFloorWalls = useMemo(
		() =>
			(effectiveStore.walls || []).filter(
				(w) =>
					matchesBuilding(w.building, activeBuilding) &&
					Number(w.floorOrder) === activeFloorOrder,
			),
		[effectiveStore.walls, activeBuilding, activeFloorOrder],
	);

	const currentFloorDoors = useMemo(
		() =>
			(effectiveStore.doors || []).filter(
				(d) =>
					matchesBuilding(d.building, activeBuilding) &&
					Number(d.floorOrder) === activeFloorOrder,
			),
		[effectiveStore.doors, activeBuilding, activeFloorOrder],
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
		selectEntity(null);
		setLiveNavNodeDrag(null);
		liveNavNodeDragRef.current = null;
		setLiveVertexDrag(null);
		setTestRouteStart(null);
	}, [activeBuilding, activeFloorOrder, selectEntity]);

	// Collect vertices & segments strictly from currently visible Wall Graph & Nav Graph layers on this floor
	const existingGeometryForSnapping = useMemo(() => {
		const vertices = [];
		const segments = [];
		const wallSegments = [];
		const boundaries = store.buildingBoundaries || [];

		if (layerVisibility.walls) {
			for (const w of store.walls || []) {
				if (
					matchesBuilding(w.building, activeBuilding) &&
					Number(w.floorOrder) === activeFloorOrder &&
					Array.isArray(w.line) &&
					w.line.length >= 2
				) {
					const pts = isClosedRing(w.line) ? w.line.slice(0, -1) : w.line;
					pts.forEach((pt) => vertices.push({ coord: pt, ownerId: w.id }));
					for (let i = 0; i < w.line.length - 1; i++) {
						const segEntry = { seg: [w.line[i], w.line[i + 1]], ownerId: w.id };
						segments.push(segEntry);
						wallSegments.push(segEntry);
					}
				}
			}
			// Include noded sub-segments (so T-junction midpoints & doorway snap targets stay razor sharp)
			for (const ws of currentFloorModel?.wallSegments || []) {
				wallSegments.push({ seg: [ws.a, ws.b], ownerId: ws.id });
			}
		}

		if (layerVisibility.doors) {
			for (const d of store.doors || []) {
				if (
					matchesBuilding(d.building, activeBuilding) &&
					Number(d.floorOrder) === activeFloorOrder &&
					Array.isArray(d.coords)
				) {
					vertices.push({
						coord: d.coords,
						ownerId: d.id,
						isDoorMidpoint: true,
					});
				}
			}
		}

		if (layerVisibility.navGraph) {
			const visibleNodeMap = new Map();
			for (const n of store.navNodes || []) {
				if (
					isNodeVisibleOnFloor(boundaries, n, activeBuilding, activeFloorOrder)
				) {
					vertices.push({
						coord: n.coords,
						ownerId: n.id,
						isNavNode: true,
					});
					visibleNodeMap.set(n.id, n);
				}
			}
			// Ghost Portals from other floors of the same building for 1-click vertical shaft alignment
			if (
				activeTool === "NAV_GRAPH" &&
				(activeNodeType === "STAIRS" || activeNodeType === "ELEVATOR")
			) {
				for (const n of store.navNodes || []) {
					if (!isPortalNode(n)) continue;
					if (Number(n.floorOrder) === activeFloorOrder) continue;
					const nb =
						resolveNodeBuilding(boundaries, n) ||
						resolveCanonicalBuildingName(n.building) ||
						n.building;
					if (!matchesBuilding(nb, activeBuilding)) continue;
					vertices.push({
						coord: n.coords,
						ownerId: `ghost-${n.id}`,
						isGhostPortal: true,
						portalGroupId: n.portalGroupId || "",
						portalType: n.type,
					});
				}
			}
			// Allow snapping & T-junction splitting onto existing navEdges while drawing or dragging NAV_GRAPH
			if (activeTool === "NAV_GRAPH" || activeTool === "SELECT") {
				for (const edge of store.navEdges || []) {
					const [uId, vId] = edge;
					const u = visibleNodeMap.get(uId);
					const v = visibleNodeMap.get(vId);
					if (u && v && u.floorOrder === v.floorOrder) {
						segments.push({
							seg: [u.coords, v.coords],
							ownerId: `navedge-${uId}-${vId}`,
							edge: [uId, vId],
						});
					}
				}
			}
		}

		return { vertices, segments, wallSegments };
	}, [
		store.walls,
		store.doors,
		store.buildingBoundaries,
		store.navNodes,
		store.navEdges,
		currentFloorModel,
		activeBuilding,
		activeFloorOrder,
		activeTool,
		activeNodeType,
		layerVisibility.walls,
		layerVisibility.doors,
		layerVisibility.navGraph,
	]);

	// Resolve snapped coordinate using screen-pixel radius (VERTEX -> MIDPOINT -> EDGE).
	// Suppressed during CALIBRATE_PNG, TEST_ROUTE, or while holding Alt; DOOR snaps strictly to wall segments.
	const resolveSnappedCoord = useCallback(
		(rawLngLat, excludeOwnerId = null) => {
			if (
				altKeyDown ||
				activeTool === "CALIBRATE_PNG" ||
				activeTool === "TEST_ROUTE"
			) {
				return null;
			}
			const mapInst = mapRef.current?.getMap?.();
			const projectFn = mapInst?.project
				? (c) => mapInst.project(c)
				: ([lng, lat]) => ({ x: lng * 600000, y: -lat * 600000 });
			if (activeTool === "DOOR") {
				return resolveBestSnapTarget({
					rawLngLat,
					projectFn,
					vertices: [],
					segments: existingGeometryForSnapping.wallSegments,
					altKeyDown,
					midpointRadiusPx: 14,
					edgeRadiusPx: 14,
				});
			}
			return resolveBestSnapTarget({
				rawLngLat,
				projectFn,
				vertices: existingGeometryForSnapping.vertices,
				segments: existingGeometryForSnapping.segments,
				excludeOwnerId,
				altKeyDown,
			});
		},
		[altKeyDown, activeTool, existingGeometryForSnapping],
	);

	// Assign Role ("ROOM" | "CORRIDOR" | "VOID") to an extracted Planar Face
	const assignFaceRole = useCallback(
		(face, nextRole) => {
			if (!face || !nextRole) return;
			const seed = face.seedCoord || getPolygonInteriorPoint(face.ring, face.holes);
			setStore((prev) => {
				const filtered = (prev.faceAssignments || []).filter((fa) => {
					if (
						!matchesBuilding(fa.building, activeBuilding) ||
						Number(fa.floorOrder) !== activeFloorOrder ||
						!fa.seedCoord
					) {
						return true;
					}
					return !isPointInPolygonWithHoles(fa.seedCoord, face.ring, face.holes || []);
				});
				const nextAssignments =
					nextRole === "CORRIDOR"
						? filtered
						: [
								...filtered,
								{
									id: `fa-${Date.now()}`,
									building: activeBuilding,
									floorOrder: activeFloorOrder,
									seedCoord: seed,
									role: nextRole,
								},
							];
				return synchronizeCompatibilityGeometry({
					...prev,
					version: 3,
					faceAssignments: nextAssignments,
				});
			});
			const roleLabel =
				nextRole === "ROOM"
					? "Phòng (3D)"
					: nextRole === "VOID"
						? "Khoét rỗng (Void)"
						: "Hành lang";
			showToast(`Đã chuyển vùng thành ${roleLabel}.`);
		},
		[activeBuilding, activeFloorOrder, setStore, showToast],
	);

	// Commit Wall Graph Polyline Draft (ROOM_POLY [P] or WALL_DRAW [W])
	const finishPolygonDraft = useCallback(
		(closeOverride = null) => {
			const minPts = activeTool === "WALL_DRAW" ? 2 : 3;
			if (draftPoints.length < minPts) return;

			const shouldClose =
				activeTool === "ROOM_POLY"
					? true
					: Boolean(closeOverride && draftPoints.length >= 3);
			const line = shouldClose
				? [...draftPoints, draftPoints[0]]
				: [...draftPoints];
			const newWallId = `wall-${Date.now()}`;

			setStore((prev) => {
				const nextWalls = [
					...(prev.walls || []),
					{
						id: newWallId,
						building: activeBuilding,
						floorOrder: activeFloorOrder,
						line,
					},
				];
				const nextAssignments = [...(prev.faceAssignments || [])];
				if (activeTool === "ROOM_POLY" && shouldClose) {
					nextAssignments.push({
						id: `fa-${Date.now()}`,
						building: activeBuilding,
						floorOrder: activeFloorOrder,
						seedCoord: getPolygonInteriorPoint(line),
						role: "ROOM",
					});
				}
				return synchronizeCompatibilityGeometry({
					...prev,
					version: 3,
					walls: nextWalls,
					faceAssignments: nextAssignments,
				});
			});

			selectEntity("WALL", newWallId);
			if (activeTool === "ROOM_POLY") {
				showToast("Đã dựng khung tường & gán vùng Phòng 3D!");
			} else if (shouldClose) {
				showToast("Đã khép kín mạng tường (tạo vùng mới thành công)!");
			} else {
				showToast("Đã thêm đoạn tường mới.");
			}
			setDraftPoints([]);
		},
		[
			draftPoints,
			activeTool,
			activeBuilding,
			activeFloorOrder,
			selectEntity,
			setStore,
			showToast,
		],
	);

	// Demote selected ROOM/VOID face back to CORRIDOR without deleting surrounding walls
	const deleteRoomById = useCallback(
		(faceIdToReset) => {
			if (!faceIdToReset) return;
			const target = currentFloorFaces.find((f) => f.id === faceIdToReset);
			if (!target) return;
			assignFaceRole(target, "CORRIDOR");
			setSelectedRoomIds((prev) => prev.filter((id) => id !== faceIdToReset));
		},
		[currentFloorFaces, assignFaceRole],
	);

	// Multi-select face demotion to CORRIDOR
	const deleteSelectedRooms = useCallback(() => {
		if (selectedRoomIds.length === 0) return;
		const targets = currentFloorFaces.filter((f) =>
			selectedRoomIds.includes(f.id),
		);
		if (targets.length === 0) return;
		setStore((prev) => {
			const nextAssignments = (prev.faceAssignments || []).filter((fa) => {
				if (
					!matchesBuilding(fa.building, activeBuilding) ||
					Number(fa.floorOrder) !== activeFloorOrder ||
					!fa.seedCoord
				) {
					return true;
				}
				return !targets.some((f) =>
					isPointInPolygonWithHoles(fa.seedCoord, f.ring, f.holes || []),
				);
			});
			return synchronizeCompatibilityGeometry({
				...prev,
				version: 3,
				faceAssignments: nextAssignments,
			});
		});
		const count = targets.length;
		setSelectedRoomIds([]);
		showToast(`Đã chuyển ${count} vùng chọn về Hành lang (chọn Tường + Del nếu muốn xóa tường).`);
	}, [
		selectedRoomIds,
		currentFloorFaces,
		activeBuilding,
		activeFloorOrder,
		setStore,
		showToast,
	]);

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
			setStore((prev) =>
				synchronizeCompatibilityGeometry({
					...prev,
					version: 3,
					walls: (prev.walls || []).filter((w) => w.id !== wallIdToDelete),
				}),
			);
			if (selectedWallId === wallIdToDelete) setSelectedWallId(null);
			showToast("Đã xóa đoạn tường & cập nhật lại các vùng khép kín.");
		},
		[selectedWallId, setStore, showToast],
	);

	const deleteDoorById = useCallback(
		(doorIdToDelete) => {
			if (!doorIdToDelete) return;
			setStore((prev) =>
				synchronizeCompatibilityGeometry({
					...prev,
					version: 3,
					doors: (prev.doors || []).filter((d) => d.id !== doorIdToDelete),
				}),
			);
			if (selectedDoorId === doorIdToDelete) setSelectedDoorId(null);
			showToast("Đã xóa cửa trên tường.");
		},
		[selectedDoorId, setStore, showToast],
	);

	// Save Studio
	const handleSaveStudio = useCallback(() => {
		const synced = synchronizeCompatibilityGeometry(store);
		saveGeometryStore(synced);
		setStoreState(synced);
		setLastSavedJson(JSON.stringify(synced));
		onSaved?.(synced);
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

	// Import GeoJSON (auto-migrates legacy v2 JSON cleanly into Wall Graph v3)
	const handleImportGeoJSON = useCallback(
		(e) => {
			const file = e.target.files?.[0];
			if (!file) return;
			const reader = new FileReader();
			reader.onload = (evt) => {
				try {
					const parsed = JSON.parse(evt.target.result);
					if (parsed && typeof parsed === "object") {
						setStore(migrateStoreToWallGraph(parsed));
						selectEntity(null);
						showToast("Đã nhập dữ liệu hình học & chuẩn hóa Wall Graph thành công!");
					}
				} catch {
					showToast("File JSON không hợp lệ.");
				}
			};
			reader.readAsText(file);
			e.target.value = "";
			setMoreMenuOpen(false);
		},
		[setStore, selectEntity, showToast],
	);

	// Clone all walls, face roles & doors on current floor to next floor
	const handleCloneRoomsToNextFloor = useCallback(() => {
		const nextOrder =
			(activeFloorOrder + 1) % currentBuildingMeta.floors.length;
		const srcWalls = (store.walls || []).filter(
			(w) =>
				matchesBuilding(w.building, activeBuilding) &&
				Number(w.floorOrder) === activeFloorOrder,
		);
		const srcAssigns = (store.faceAssignments || []).filter(
			(fa) =>
				matchesBuilding(fa.building, activeBuilding) &&
				Number(fa.floorOrder) === activeFloorOrder,
		);
		const srcDoors = (store.doors || []).filter(
			(d) =>
				matchesBuilding(d.building, activeBuilding) &&
				Number(d.floorOrder) === activeFloorOrder,
		);
		if (srcWalls.length === 0) {
			showToast("Tầng hiện tại chưa có tường/phòng nào để sao chép.");
			return;
		}
		const ts = Date.now();
		const clonedWalls = srcWalls.map((w, i) => ({
			id: `wall-clone-${ts}-${i}`,
			building: activeBuilding,
			floorOrder: nextOrder,
			line: (w.line || []).map((pt) => [...pt]),
		}));
		const clonedAssigns = srcAssigns.map((fa, i) => ({
			id: `fa-clone-${ts}-${i}`,
			building: activeBuilding,
			floorOrder: nextOrder,
			seedCoord: [...fa.seedCoord],
			role: fa.role,
		}));
		const clonedDoors = srcDoors.map((d, i) => ({
			...d,
			id: `door-clone-${ts}-${i}`,
			building: activeBuilding,
			floorOrder: nextOrder,
			coords: [...d.coords],
		}));
		setStore((prev) =>
			synchronizeCompatibilityGeometry({
				...prev,
				version: 3,
				walls: [...(prev.walls || []), ...clonedWalls],
				faceAssignments: [...(prev.faceAssignments || []), ...clonedAssigns],
				doors: [...(prev.doors || []), ...clonedDoors],
			}),
		);
		showToast(`Đã sao chép ${clonedWalls.length} tường & cấu trúc tầng sang tầng kế tiếp!`);
	}, [
		store.walls,
		store.faceAssignments,
		store.doors,
		activeBuilding,
		activeFloorOrder,
		currentBuildingMeta,
		setStore,
		showToast,
	]);

	// In-App Clear all walls, face roles & doors on active floor
	const handleClearCurrentFloorRooms = useCallback(() => {
		setConfirmModal({
			isOpen: true,
			title: "Xóa hình học tầng này?",
			message: `Hành động này sẽ xóa tất cả tường, vùng phòng và cửa đã vẽ tại ${currentFloorMeta.name} của ${activeBuilding}. Thao tác này có thể hoàn tác bằng Ctrl+Z.`,
			confirmLabel: "Xóa sạch tầng",
			confirmVariant: "danger",
			onConfirm: () => {
				setStore((prev) =>
					synchronizeCompatibilityGeometry({
						...prev,
						version: 3,
						walls: (prev.walls || []).filter(
							(w) =>
								!(
									matchesBuilding(w.building, activeBuilding) &&
									Number(w.floorOrder) === activeFloorOrder
								),
						),
						faceAssignments: (prev.faceAssignments || []).filter(
							(fa) =>
								!(
									matchesBuilding(fa.building, activeBuilding) &&
									Number(fa.floorOrder) === activeFloorOrder
								),
						),
						doors: (prev.doors || []).filter(
							(d) =>
								!(
									matchesBuilding(d.building, activeBuilding) &&
									Number(d.floorOrder) === activeFloorOrder
								),
						),
					}),
				);
				selectEntity(null);
				setConfirmModal((m) => ({ ...m, isOpen: false }));
				showToast("Đã làm trống toàn bộ hình học tầng hiện tại.");
			},
		});
	}, [activeBuilding, activeFloorOrder, currentFloorMeta, selectEntity, setStore, showToast]);

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
				selectEntity(null);
				setConfirmModal((m) => ({ ...m, isOpen: false }));
				setMoreMenuOpen(false);
				showToast("Đã khôi phục bộ hình học mẫu ban đầu.");
			},
		});
	}, [selectEntity, setStore, showToast]);

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
					selectEntity(null);
					setTestRouteStart(null);
				}
				return;
			}

			// Enter closes ROOM_POLY (>= 3 pts) or commits WALL_DRAW (>= 2 pts open)
			if (
				e.key === "Enter" &&
				((activeTool === "ROOM_POLY" && draftPoints.length >= 3) ||
					(activeTool === "WALL_DRAW" && draftPoints.length >= 2))
			) {
				finishPolygonDraft(activeTool === "ROOM_POLY");
				return;
			}

			// Delete / Backspace
			if (e.key === "Delete" || e.key === "Backspace") {
				if (selectedRoomIds.length > 0) {
					deleteSelectedRooms();
				} else if (selectedNavNodeId) {
					deleteNavNodeById(selectedNavNodeId);
				} else if (selectedDoorId) {
					deleteDoorById(selectedDoorId);
				} else if (selectedWallId) {
					deleteWallById(selectedWallId);
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
				} else if (k === "w" || k === "o") {
					setActiveTool("WALL_DRAW");
					setDraftPoints([]);
				} else if (k === "d") {
					setActiveTool("DOOR");
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

		const onWindowBlur = () => {
			setAltKeyDown(false);
			setShiftKeyDown(false);
		};

		window.addEventListener("keydown", onKeyDown);
		window.addEventListener("keyup", onKeyUp);
		window.addEventListener("blur", onWindowBlur);
		return () => {
			window.removeEventListener("keydown", onKeyDown);
			window.removeEventListener("keyup", onKeyUp);
			window.removeEventListener("blur", onWindowBlur);
		};
	}, [
		isOpen,
		activeTool,
		draftPoints,
		selectedRoomIds,
		selectedNavNodeId,
		selectedWallId,
		selectedDoorId,
		showShortcutsModal,
		confirmModal.isOpen,
		finishPolygonDraft,
		handleUndo,
		handleRedo,
		handleSaveStudio,
		selectEntity,
		deleteSelectedRooms,
		deleteNavNodeById,
		deleteWallById,
		deleteDoorById,
	]);

	// Ensure stale snap indicator clears when entering tools that suppress snapping
	useEffect(() => {
		if (activeTool === "CALIBRATE_PNG" || activeTool === "TEST_ROUTE") {
			setSnappedCoord(null);
			setSnapType(null);
		}
	}, [activeTool]);

	// Commit Nav Graph Node drag cleanly (single Undo step, auto-weld overlapping nodes or split edges) & restore map dragPan
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
			const targetNodeId = finalDrag.snapTargetNodeId || null;
			const targetEdge = finalDrag.snapTargetEdge || null;
			let mergedIntoId = null;
			let didSplitEdge = false;

			setStore((prev) => {
				const floor0Bounds = (prev.buildingBoundaries || []).filter(
					(b) => (Number(b.floorOrder) || 0) === 0,
				);
				const merged = mergeOrSplitNavNodeOnDrop({
					navNodes: prev.navNodes || [],
					navEdges: prev.navEdges || [],
					draggedNodeId: drag.id,
					finalCoords,
					targetNodeId,
					targetEdge,
					floor0Bounds,
				});
				mergedIntoId = merged.mergedIntoId;
				didSplitEdge = merged.splitEdge;
				return {
					...prev,
					navNodes: merged.navNodes,
					navEdges: merged.navEdges,
				};
			});

			const survivingId = mergedIntoId || drag.id;
			if (activeTool === "SELECT") {
				selectEntity("NODE", survivingId);
				setActiveInspectorTab("inspector");
			}
			lastNavDragEndAtRef.current = Date.now();
			if (mergedIntoId) {
				showToast("Đã tự động gộp 2 nút đồ thị trùng vị trí.");
			} else if (didSplitEdge) {
				showToast("Đã chèn nút vào cạnh đồ thị (T-junction).");
			} else {
				showToast("Đã di chuyển nút đồ thị.");
			}
		}
		return true;
	}, [activeTool, selectEntity, setStore, showToast]);

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

				const hitFaces = currentFloorFaces.filter((f) => {
					const hasVertexInside = (f.ring || []).some(
						([x, y]) =>
							x >= minLng && x <= maxLng && y >= minLat && y <= maxLat,
					);
					if (hasVertexInside) return true;
					const [cx, cy] = f.seedCoord || getRingCentroid(f.ring);
					return cx >= minLng && cx <= maxLng && cy >= minLat && cy <= maxLat;
				});

				if (hitFaces.length > 0) {
					const hitIds = hitFaces.map((f) => f.id);
					setSelectedRoomIds((prev) => Array.from(new Set([...prev, ...hitIds])));
					setSelectedWallId(null);
					setSelectedDoorId(null);
					setSelectedNavNodeId(null);
					showToast(`Đã chọn ${hitFaces.length} vùng.`);
				}
			}
		}
		return true;
	}, [currentFloorFaces, showToast]);

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
				const nodeQueryLayers = [
					"studio-nav-nodes-layer",
					"studio-nav-portals-layer",
				].filter((id) => Boolean(mapInst?.getLayer?.(id)));
				if (mapInst && nodeQueryLayers.length > 0) {
					const feats = mapInst.queryRenderedFeatures(
						[
							[e.point.x - 10, e.point.y - 10],
							[e.point.x + 10, e.point.y + 10],
						],
						{ layers: nodeQueryLayers },
					);
					const hitId = feats?.[0]?.properties?.id;
					if (hitId) {
						hitNode = (store.navNodes || []).find((n) => n.id === hitId) || null;
					}
				}
				if (!hitNode && mapInst) {
					const visibleNodes = (store.navNodes || []).filter((n) =>
						isNodeVisibleOnFloor(
							store.buildingBoundaries,
							n,
							activeBuilding,
							activeFloorOrder,
						),
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
			store.buildingBoundaries,
			activeBuilding,
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
					const snapTargetNodeId =
						snapResult?.type === "VERTEX" &&
						(store.navNodes || []).some((n) => n.id === snapResult.ownerId)
							? snapResult.ownerId
							: null;
					const snapTargetEdge = snapResult?.edge || null;
					const nextDrag = {
						id: drag.id,
						coords: nextCoord,
						snapTargetNodeId,
						snapTargetEdge,
					};
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
						(f) =>
							f?.layer?.id === "studio-nav-nodes-layer" ||
							f?.layer?.id === "studio-nav-portals-layer",
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
					(f) =>
						f?.layer?.id === "studio-nav-nodes-layer" ||
						f?.layer?.id === "studio-nav-portals-layer",
				);
				setStudioCursor(overNode ? "move" : "crosshair");
			} else if (activeTool === "CALIBRATE_PNG") {
				setStudioCursor("grab");
			} else {
				setStudioCursor("crosshair");
			}
		},
		[resolveSnappedCoord, activeTool, shiftKeyDown, store.navNodes],
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

			// 1. SELECT Tool: NavNode -> Door -> Wall -> Planar Face (Shift/Ctrl for multi-select faces)
			if (activeTool === "SELECT") {
				const isMulti = Boolean(
					e.originalEvent?.shiftKey ||
					e.originalEvent?.ctrlKey ||
					e.originalEvent?.metaKey,
				);
				const features = e.features || [];

				const nodeFeat =
					layerVisibility.navGraph && !layerLocks.navGraph
						? features.find(
								(f) =>
									f?.layer?.id === "studio-nav-nodes-layer" ||
									f?.layer?.id === "studio-nav-portals-layer",
							)
						: null;
				const doorFeat =
					layerVisibility.doors && !layerLocks.doors
						? features.find(
								(f) =>
									f?.layer?.id === "studio-doors-fill-layer" ||
									f?.layer?.id === "studio-doors-outline-layer" ||
									f?.layer?.id === "studio-doors-layer",
							)
						: null;
				const wallFeat =
					layerVisibility.walls && !layerLocks.walls
						? features.find(
								(f) =>
									f?.layer?.id === "studio-walls-hit-layer" ||
									f?.layer?.id === "studio-walls-line-layer",
							)
						: null;
				const faceFeat =
					layerVisibility.faces && !layerLocks.faces
						? features.find(
								(f) =>
									f?.layer?.id === "studio-faces-fill" ||
									f?.layer?.id === "studio-faces-outline" ||
									f?.layer?.id === "studio-rooms-extrusion",
							)
						: null;

				// Priority 1: Nav Node
				if (nodeFeat && !isMulti) {
					const nId = nodeFeat.properties?.id;
					if (nId) {
						selectEntity("NODE", nId);
						setActiveInspectorTab("inspector");
						return;
					}
				}

				// Priority 2: Wall-mounted Door (hit feature or < 0.9m proximity)
				if (!isMulti && layerVisibility.doors && !layerLocks.doors) {
					let dId = doorFeat?.properties?.id || null;
					if (!dId) {
						let bestDoor = null;
						let bestDoorDist = 0.9;
						for (const d of currentFloorDoors) {
							if (!Array.isArray(d.coords)) continue;
							const dm = approximateDistanceMeters(raw, d.coords);
							if (dm < bestDoorDist) {
								bestDoorDist = dm;
								bestDoor = d;
							}
						}
						dId = bestDoor?.id || null;
					}
					if (dId) {
						selectEntity("DOOR", dId);
						setActiveInspectorTab("inspector");
						return;
					}
				}

				// Priority 3: Wall segment / polyline
				if (!isMulti && layerVisibility.walls && !layerLocks.walls) {
					let wId = wallFeat?.properties?.id || null;
					if (!wId) {
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
						wId = bestWall?.id || null;
					}
					if (wId) {
						selectEntity("WALL", wId);
						setActiveInspectorTab("inspector");
						return;
					}
				}

				// Priority 4: Extracted Planar Face (ROOM / CORRIDOR / VOID)
				let fId = faceFeat?.properties?.id || null;
				if (!fId && layerVisibility.faces && !layerLocks.faces) {
					const matchingFaces = currentFloorFaces
						.filter(
							(f) =>
								isPointInPolygonWithHoles(raw, f.ring, f.holes || []) ||
								isPointInPolygonWithHoles(pt, f.ring, f.holes || []),
						)
						.sort((a, b) => (a.areaSqm || 0) - (b.areaSqm || 0));
					if (matchingFaces[0]) fId = matchingFaces[0].id;
				}

				if (fId && !layerLocks.faces) {
					if (isMulti) {
						setSelectedRoomIds((prev) =>
							prev.includes(fId)
								? prev.filter((id) => id !== fId)
								: [...prev, fId],
						);
						setSelectedWallId(null);
						setSelectedDoorId(null);
						setSelectedNavNodeId(null);
					} else {
						selectEntity("FACE", fId);
					}
					setActiveInspectorTab("inspector");
					return;
				}

				if (!isMulti) {
					selectEntity(null);
				}
				return;
			}

			// 2. 2-Click Room Box Macro -> draws 4 connected walls + assigns ROOM face
			if (activeTool === "ROOM_BOX") {
				if (layerLocks.walls || layerLocks.faces) {
					showToast("Lớp tường hoặc vùng đang bị khóa.");
					return;
				}
				if (draftPoints.length === 0) {
					setDraftPoints([pt]);
				} else {
					const p0 = draftPoints[0];
					const axisAligned = Boolean(e.originalEvent?.shiftKey || shiftKeyDown);
					const quadRing = buildOrientedBoxRing(p0, pt, {
						corners: currentPngCorners,
						axisAligned,
						minSideMeters: 0.25,
					});
					if (!quadRing) {
						showToast("Khung phòng quá hẹp, vui lòng chọn 2 góc cách xa hơn.");
						setDraftPoints([]);
						return;
					}

					const newWallId = `wall-box-${Date.now()}`;
					const boxSeed = getPolygonInteriorPoint(quadRing);
					setStore((prev) => {
						const filteredAssignments = (prev.faceAssignments || []).filter(
							(fa) =>
								!(
									matchesBuilding(fa.building, activeBuilding) &&
									Number(fa.floorOrder) === activeFloorOrder &&
									fa.seedCoord &&
									isPointInPolygonWithHoles(fa.seedCoord, quadRing, [])
								),
						);
						return synchronizeCompatibilityGeometry({
							...prev,
							version: 3,
							walls: [
								...(prev.walls || []),
								{
									id: newWallId,
									building: activeBuilding,
									floorOrder: activeFloorOrder,
									line: quadRing,
								},
							],
							faceAssignments: [
								...filteredAssignments,
								{
									id: `fa-box-${Date.now()}`,
									building: activeBuilding,
									floorOrder: activeFloorOrder,
									seedCoord: boxSeed,
									role: "ROOM",
								},
							],
						});
					});
					selectEntity("WALL", newWallId);
					showToast(
						axisAligned
							? "Đã dựng hộp tường ngang (0°) & gán Phòng."
							: "Đã dựng hộp tường theo góc mặt bằng & gán Phòng.",
					);
					setDraftPoints([]);
				}
				return;
			}

			// 3. Mappedin-style Face Slice Macro -> inserts dividing Wall dividing closed face into 2 sub-faces
			if (activeTool === "ROOM_SLICE") {
				if (layerLocks.walls || layerLocks.faces) {
					showToast("Lớp tường hoặc vùng đang bị khóa.");
					return;
				}
				if (draftPoints.length === 0) {
					setDraftPoints([pt]);
				} else {
					const p0 = draftPoints[0];
					const p1 = pt;
					const singleSelectedFace =
						selectedRoomIds.length === 1
							? currentFloorFaces.find((f) => f.id === selectedRoomIds[0])
							: null;
					const candidateFaces = singleSelectedFace
						? [
								singleSelectedFace,
								...currentFloorFaces.filter(
									(f) => f.id !== singleSelectedFace.id && f.role !== "VOID",
								),
							]
						: currentFloorFaces.filter((f) => f.role !== "VOID");

					let targetFace = null;
					let sliceRes = null;
					for (const face of candidateFaces) {
						const res = buildSliceWallSegment([face], p0, p1);
						if (res) {
							targetFace = face;
							sliceRes = res;
							break;
						}
					}

					if (targetFace && sliceRes) {
						const sliceWallId = `wall-slice-${Date.now()}`;
						const ts = Date.now();
						setStore((prev) => {
							const retainedAssignments = (prev.faceAssignments || []).filter(
								(fa) =>
									!(
										matchesBuilding(fa.building, activeBuilding) &&
										Number(fa.floorOrder) === activeFloorOrder &&
										fa.seedCoord &&
										isPointInPolygonWithHoles(
											fa.seedCoord,
											targetFace.ring,
											targetFace.holes || [],
										)
									),
							);
							const extraAssigns =
								targetFace.role === "CORRIDOR"
									? []
									: [
											{
												id: `fa-slice-a-${ts}`,
												building: activeBuilding,
												floorOrder: activeFloorOrder,
												seedCoord: sliceRes.seedA,
												role: targetFace.role,
											},
											{
												id: `fa-slice-b-${ts}`,
												building: activeBuilding,
												floorOrder: activeFloorOrder,
												seedCoord: sliceRes.seedB,
												role: targetFace.role,
											},
										];
							return synchronizeCompatibilityGeometry({
								...prev,
								version: 3,
								walls: [
									...(prev.walls || []),
									{
										id: sliceWallId,
										building: activeBuilding,
										floorOrder: activeFloorOrder,
										line: sliceRes.line,
									},
								],
								faceAssignments: [...retainedAssignments, ...extraAssigns],
							});
						});
						selectEntity("WALL", sliceWallId);
						showToast("Đã dựng vách chia đôi vùng (bảo toàn vai trò & tự cắt đỉnh).");
					} else {
						showToast("Đường cắt chưa đi xuyên qua vùng khép kín nào.");
					}
					setDraftPoints([]);
				}
				return;
			}

			// 4. Unified Wall Graph Draw [W] (closed loop or multi-segment partition) & Polygon Room Macro [P]
			if (activeTool === "WALL_DRAW" || activeTool === "ROOM_POLY") {
				if (layerLocks.walls || (activeTool === "ROOM_POLY" && layerLocks.faces)) {
					showToast("Lớp tường hoặc vùng đang bị khóa.");
					return;
				}
				const mapInst = mapRef.current?.getMap?.();
				const hitSameScreenPt = (targetPt, radiusPx = 14) => {
					if (mapInst?.project) {
						const pClick = mapInst.project(pt);
						const pTarget = mapInst.project(targetPt);
						return Math.hypot(pClick.x - pTarget.x, pClick.y - pTarget.y) <= radiusPx;
					}
					return (
						Math.hypot(pt[0] - targetPt[0], pt[1] - targetPt[1]) < 0.000022
					);
				};

				if (draftPoints.length >= 3 && hitSameScreenPt(draftPoints[0], 14)) {
					finishPolygonDraft(true);
					return;
				}
				if (
					activeTool === "WALL_DRAW" &&
					draftPoints.length >= 2 &&
					hitSameScreenPt(draftPoints[draftPoints.length - 1], 10)
				) {
					finishPolygonDraft(false);
					return;
				}
				setDraftPoints((prev) => [...prev, pt]);
				return;
			}

			// 5. Wall-mounted Door Tool [D]: Snaps strictly onto Wall Graph segments (carves 1.2m passage)
			if (activeTool === "DOOR") {
				if (layerLocks.doors) {
					showToast("Lớp Cửa đang bị khóa.");
					return;
				}
				if (!snapResult) {
					showToast("Click trực tiếp lên một đoạn tường để gắn Cửa [D].");
					return;
				}
				const newDoorId = `door-${Date.now()}`;
				setStore((prev) =>
					synchronizeCompatibilityGeometry({
						...prev,
						version: 3,
						doors: [
							...(prev.doors || []),
							{
								id: newDoorId,
								building: activeBuilding,
								floorOrder: activeFloorOrder,
								coords: snapResult.coord,
								widthMeters: 1.2,
							},
						],
					}),
				);
				selectEntity("DOOR", newDoorId);
				showToast("Đã gắn Cửa 1.2m lên tường (tự khoét 3D & thông luồng A*).");
				return;
			}

			// 5. Navigation Graph Tool (Unified NODE + Edge & STAIRS/ELEVATOR Portals)
			if (activeTool === "NAV_GRAPH") {
				if (layerLocks.navGraph) {
					showToast("Lớp đồ thị đang bị khóa.");
					return;
				}
				const boundaries = store.buildingBoundaries || [];
				const visibleNodes = (store.navNodes || []).filter((n) =>
					isNodeVisibleOnFloor(
						boundaries,
						n,
						activeBuilding,
						activeFloorOrder,
					),
				);

				const mapInst = mapRef.current?.getMap?.();
				let existingNode = null;
				if (mapInst?.project && e?.point) {
					let bestPx = 12;
					for (const n of visibleNodes) {
						const nPx = mapInst.project(n.coords);
						const dPx = Math.hypot(nPx.x - e.point.x, nPx.y - e.point.y);
						if (dPx <= bestPx) {
							bestPx = dPx;
							existingNode = n;
						}
					}
				} else {
					for (const n of visibleNodes) {
						if (Math.hypot(n.coords[0] - pt[0], n.coords[1] - pt[1]) < 0.000022) {
							existingNode = n;
							break;
						}
					}
				}

				if (existingNode) {
					if (chainLastNodeId && chainLastNodeId !== existingNode.id) {
						setStore((prev) => {
							const exists = (prev.navEdges || []).some(
								([a, b]) =>
									(a === chainLastNodeId && b === existingNode.id) ||
									(a === existingNode.id && b === chainLastNodeId),
							);
							if (exists) return prev;
							return {
								...prev,
								navEdges: [
									...(prev.navEdges || []),
									[chainLastNodeId, existingNode.id],
								],
							};
						});
						showToast("Đã nối nút.");
					}
					setChainLastNodeId(existingNode.id);
					return;
				}

				const floor0Bounds = boundaries.filter(
					(b) => (Number(b.floorOrder) || 0) === 0,
				);
				const isPortal =
					activeNodeType === "STAIRS" || activeNodeType === "ELEVATOR";
				const isOutdoorPt =
					!isPortal &&
					activeFloorOrder === 0 &&
					floor0Bounds.length > 0 &&
					!isPointIndoorOnFloor(store, activeBuilding, 0, pt, floorWallModels);
				const containedFloor0 =
					activeFloorOrder === 0
						? findBoundaryContainingPoint(floor0Bounds, pt, 0)
						: null;

				// Auto-inherit portalGroupId when snapping onto a Ghost Portal from another floor
				let resolvedPortalGroupId =
					portalGroupId.trim().toUpperCase() || "PORTAL_1";
				if (isPortal) {
					const ghostMatch = (store.navNodes || []).find(
						(n) =>
							isPortalNode(n) &&
							Number(n.floorOrder) !== activeFloorOrder &&
							matchesBuilding(
								resolveNodeBuilding(boundaries, n) || n.building,
								activeBuilding,
							) &&
							Math.hypot(n.coords[0] - pt[0], n.coords[1] - pt[1]) < 0.000018,
					);
					if (ghostMatch?.portalGroupId) {
						resolvedPortalGroupId = ghostMatch.portalGroupId.trim().toUpperCase();
						setPortalGroupId(resolvedPortalGroupId);
					}
				}

				const newNodeId = `node-${Date.now()}`;
				const newNode = {
					id: newNodeId,
					type: activeNodeType,
					building: isOutdoorPt
						? "OUTDOOR"
						: containedFloor0?.building || activeBuilding,
					floorOrder: activeFloorOrder,
					coords: pt,
					...(isPortal
						? {
								portalGroupId: resolvedPortalGroupId,
							}
						: {}),
				};

				const snappedNavEdge = snapResult?.edge || null;

				setStore((prev) => {
					const nextNodes = [...(prev.navNodes || []), newNode];
					let nextEdges = [...(prev.navEdges || [])];

					// Auto-split edge into clean T-junction when snapped directly onto an existing navEdge
					if (snappedNavEdge) {
						const [eu, ev] = snappedNavEdge;
						nextEdges = nextEdges.filter(
							([a, b]) => !((a === eu && b === ev) || (a === ev && b === eu)),
						);
						nextEdges.push([eu, newNodeId], [newNodeId, ev]);
					}

					if (chainLastNodeId) {
						const alreadyConnected = nextEdges.some(
							([a, b]) =>
								(a === chainLastNodeId && b === newNodeId) ||
								(a === newNodeId && b === chainLastNodeId),
						);
						if (!alreadyConnected) {
							nextEdges.push([chainLastNodeId, newNodeId]);
						}
					}

					return {
						...prev,
						navNodes: nextNodes,
						navEdges: nextEdges,
					};
				});

				setChainLastNodeId(newNodeId);
				return;
			}

			// 6. Live A* Route Tester (with courtyard VOID hole awareness & diagnostic error toast)
			if (activeTool === "TEST_ROUTE") {
				const floor0Bounds = (store.buildingBoundaries || []).filter(
					(b) => (Number(b.floorOrder) || 0) === 0,
				);
				const clickIsOutdoor =
					activeFloorOrder === 0 &&
					floor0Bounds.length > 0 &&
					!isPointIndoorOnFloor(
						store,
						activeBuilding,
						0,
						raw,
						floorWallModels,
					);
				const clickBuilding = clickIsOutdoor ? "OUTDOOR" : activeBuilding;

				if (!testRouteStart) {
					setTestRouteStart({
						coords: raw,
						building: clickBuilding,
						floorOrder: activeFloorOrder,
					});
					showToast(
						`Đã chọn điểm đi (${clickIsOutdoor ? "Ngoài sân" : activeBuilding}). Click tiếp điểm đến.`,
					);
				} else {
					const diag = {};
					const res = computeDigitizedRoute({
						store,
						startCoords: testRouteStart.coords,
						startBuilding: testRouteStart.building,
						startFloorOrder: testRouteStart.floorOrder,
						endCoords: raw,
						endBuilding: clickBuilding,
						endFloorOrder: activeFloorOrder,
						diagnosticsOut: diag,
					});
					setTestRouteResult(res);
					setTestRouteStart(null);
					if (res) {
						showToast(
							`Tìm thấy đường: ${res.totalDistanceMeters}m (${res.floorsClimbed} tầng)`,
						);
					} else {
						showToast(
							diag.message ||
								"Không tìm thấy đường đi giữa 2 điểm này (kiểm tra lại dây nối đồ thị [N] hoặc Cửa [D]).",
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
			currentFloorFaces,
			currentFloorWalls,
			currentFloorDoors,
			currentPngCorners,
			shiftKeyDown,
			activeNodeType,
			portalGroupId,
			chainLastNodeId,
			testRouteStart,
			store,
			floorWallModels,
			resolveSnappedCoord,
			finishPolygonDraft,
			selectEntity,
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

	// Live 60fps vertex/endpoint dragging with magnetic welded junction snap & single Undo commit on dragEnd
	const handleVertexDragLive = useCallback(
		(kind, id, index, lngLat, origEvt = null) => {
			if (!id || !lngLat) return;
			const detachSingle = Boolean(origEvt?.altKey || altKeyDown);
			const raw = [
				Number(lngLat.lng.toFixed(6)),
				Number(lngLat.lat.toFixed(6)),
			];
			const snapResult = resolveSnappedCoord(raw, id);
			const pt = snapResult ? snapResult.coord : raw;
			setLiveVertexDrag({ kind, id, index, coords: pt, detachSingle });
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
		[resolveSnappedCoord, altKeyDown],
	);

	const handleVertexDragEnd = useCallback(
		(kind, id, index, lngLat, origEvt = null) => {
			if (!id || !lngLat) return;
			const detachSingle = Boolean(origEvt?.altKey || altKeyDown);
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
				if (kind === "WALL") {
					return synchronizeCompatibilityGeometry({
						...prev,
						version: 3,
						walls: dragWeldedWallVertices(prev.walls || [], {
							wallId: id,
							vertexIndex: index,
							newCoord: newPt,
							detachSingle,
						}),
					});
				}
				return prev;
			});
		},
		[resolveSnappedCoord, altKeyDown, setStore],
	);

	// Live 60fps Door Midpoint Slide & Jamb Width Resize along host wall segment
	const handleDoorMoveDragLive = useCallback(
		(doorId, lngLat) => {
			if (!doorId || !lngLat) return;
			const raw = [
				Number(lngLat.lng.toFixed(6)),
				Number(lngLat.lat.toFixed(6)),
			];
			const snap = resolveBestSnapTarget({
				rawLngLat: raw,
				projectFn: mapRef.current?.getMap?.()?.project
					? (c) => mapRef.current.getMap().project(c)
					: ([lng, lat]) => ({ x: lng * 600000, y: -lat * 600000 }),
				vertices: [],
				segments: existingGeometryForSnapping.wallSegments,
				altKeyDown: false,
				midpointRadiusPx: 14,
				edgeRadiusPx: 26,
			});
			const nextCoord = snap ? snap.coord : raw;
			setLiveVertexDrag({ kind: "DOOR_MOVE", id: doorId, coords: nextCoord });
		},
		[existingGeometryForSnapping.wallSegments],
	);

	const handleDoorMoveDragEnd = useCallback(
		(doorId, lngLat) => {
			if (!doorId || !lngLat) return;
			const raw = [
				Number(lngLat.lng.toFixed(6)),
				Number(lngLat.lat.toFixed(6)),
			];
			const snap = resolveBestSnapTarget({
				rawLngLat: raw,
				projectFn: mapRef.current?.getMap?.()?.project
					? (c) => mapRef.current.getMap().project(c)
					: ([lng, lat]) => ({ x: lng * 600000, y: -lat * 600000 }),
				vertices: [],
				segments: existingGeometryForSnapping.wallSegments,
				altKeyDown: false,
				midpointRadiusPx: 14,
				edgeRadiusPx: 26,
			});
			const nextCoord = snap ? snap.coord : raw;
			setLiveVertexDrag(null);
			setStore((prev) =>
				synchronizeCompatibilityGeometry({
					...prev,
					version: 3,
					doors: (prev.doors || []).map((d) =>
						d.id === doorId ? { ...d, coords: nextCoord } : d,
					),
				}),
			);
			showToast("Đã trượt vị trí Cửa dọc theo tường.");
		},
		[existingGeometryForSnapping.wallSegments, setStore, showToast],
	);

	const handleDoorJambDragLive = useCallback(
		(door, lngLat) => {
			if (!door || !lngLat) return;
			const raw = [
				Number(lngLat.lng.toFixed(6)),
				Number(lngLat.lat.toFixed(6)),
			];
			const halfDist = approximateDistanceMeters(door.coords, raw);
			const nextW = Number(
				Math.max(0.5, Math.min(5.0, halfDist * 2)).toFixed(2),
			);
			setLiveVertexDrag({
				kind: "DOOR_WIDTH",
				id: door.id,
				widthMeters: nextW,
			});
		},
		[],
	);

	const handleDoorJambDragEnd = useCallback(
		(door, lngLat) => {
			if (!door || !lngLat) return;
			const raw = [
				Number(lngLat.lng.toFixed(6)),
				Number(lngLat.lat.toFixed(6)),
			];
			const halfDist = approximateDistanceMeters(door.coords, raw);
			const nextW = Number(
				Math.max(0.5, Math.min(5.0, halfDist * 2)).toFixed(2),
			);
			setLiveVertexDrag(null);
			setStore((prev) =>
				synchronizeCompatibilityGeometry({
					...prev,
					version: 3,
					doors: (prev.doors || []).map((d) =>
						d.id === door.id ? { ...d, widthMeters: nextW } : d,
					),
				}),
			);
			showToast(`Đã chỉnh bề rộng Cửa thành ${nextW}m.`);
		},
		[setStore, showToast],
	);

	// Insert a new bend/joint vertex at edge midpoint on selected wall polyline
	const handleInsertVertexOnEdge = useCallback(
		(kind, id, insertAfterIndex, midpoint) => {
			if (!id || !midpoint) return;
			setStore((prev) => {
				if (kind === "WALL") {
					return synchronizeCompatibilityGeometry({
						...prev,
						version: 3,
						walls: (prev.walls || []).map((w) => {
							if (w.id !== id || !Array.isArray(w.line)) return w;
							const nextLine = [...w.line];
							nextLine.splice(insertAfterIndex + 1, 0, midpoint);
							return { ...w, line: nextLine };
						}),
					});
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

	// 2D Unified Wall Polylines for crisp selection & dragging
	const studioWallLinesGeoJSON = useMemo(() => {
		const features = [];
		if (layerVisibility.walls) {
			for (const w of currentFloorWalls) {
				if (!Array.isArray(w.line) || w.line.length < 2) continue;
				const closed = isClosedRing(w.line);
				features.push({
					type: "Feature",
					id: w.id,
					properties: {
						id: w.id,
						wallKind: closed ? "PERIMETER" : "PARTITION",
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
	}, [layerVisibility.walls, currentFloorWalls, selectedWallId]);

	// 2D Extracted Planar Faces GeoJSON (ROOM / CORRIDOR / VOID) on active floor
	const studioFacesGeoJSON = useMemo(() => {
		if (!layerVisibility.faces) {
			return { type: "FeatureCollection", features: [] };
		}
		return {
			type: "FeatureCollection",
			features: currentFloorFaces.map((f) => ({
				type: "Feature",
				id: f.id,
				properties: {
					id: f.id,
					role: f.role,
					areaSqm: f.areaSqm,
					isSelected: selectedRoomIds.includes(f.id),
				},
				geometry: {
					type: "Polygon",
					coordinates: [f.ring, ...(f.holes || [])],
				},
			})),
		};
	}, [layerVisibility.faces, currentFloorFaces, selectedRoomIds]);

	// 2D Wall-Mounted Doors GeoJSON on active floor (Oriented Rectangular Door Frame Polygon + Midpoint Node + Live Ghost Preview)
	const studioDoorsGeoJSON = useMemo(() => {
		if (!layerVisibility.doors) {
			return { type: "FeatureCollection", features: [] };
		}
		const wallSegs = currentFloorModel?.wallSegments || [];
		const features = [];

		for (const d of currentFloorDoors) {
			if (!Array.isArray(d.coords)) continue;
			const geom = buildOrientedDoorGeometry(d, wallSegs);
			if (!geom) continue;
			const isSel = d.id === selectedDoorId;

			// Oriented Rectangular Door Frame Box (as in Image 1)
			features.push({
				type: "Feature",
				id: d.id,
				properties: {
					id: d.id,
					featureKind: "DOOR_BOX",
					widthMeters: geom.widthMeters,
					isSelected: isSel,
					isPreview: false,
				},
				geometry: {
					type: "Polygon",
					coordinates: [geom.boxPolygon],
				},
			});

			// Inner Threshold Leaf Line
			features.push({
				type: "Feature",
				id: `${d.id}-leaf`,
				properties: {
					id: d.id,
					featureKind: "DOOR_LEAF",
					widthMeters: geom.widthMeters,
					isSelected: isSel,
					isPreview: false,
				},
				geometry: {
					type: "LineString",
					coordinates: geom.centerLine,
				},
			});

			// Midpoint Graph Node Anchor (highlighted in NAV_GRAPH / DOOR / when selected)
			features.push({
				type: "Feature",
				id: `${d.id}-mid`,
				properties: {
					id: d.id,
					featureKind: "DOOR_MIDPOINT",
					widthMeters: geom.widthMeters,
					isSelected: isSel,
					isPreview: false,
				},
				geometry: {
					type: "Point",
					coordinates: geom.midpoint,
				},
			});
		}

		// Live Ghost Door Box Preview while hovering over a wall with [D] tool
		if (activeTool === "DOOR" && snappedCoord) {
			const ghostGeom = buildOrientedDoorGeometry(
				{ id: "door-preview", coords: snappedCoord, widthMeters: 1.2 },
				wallSegs,
			);
			if (ghostGeom) {
				features.push({
					type: "Feature",
					id: "door-preview-box",
					properties: {
						id: "door-preview",
						featureKind: "DOOR_BOX",
						widthMeters: ghostGeom.widthMeters,
						isSelected: false,
						isPreview: true,
					},
					geometry: {
						type: "Polygon",
						coordinates: [ghostGeom.boxPolygon],
					},
				});
				features.push({
					type: "Feature",
					id: "door-preview-mid",
					properties: {
						id: "door-preview",
						featureKind: "DOOR_MIDPOINT",
						widthMeters: ghostGeom.widthMeters,
						isSelected: false,
						isPreview: true,
					},
					geometry: {
						type: "Point",
						coordinates: ghostGeom.midpoint,
					},
				});
			}
		}

		return { type: "FeatureCollection", features };
	}, [
		layerVisibility.doors,
		currentFloorDoors,
		currentFloorModel,
		selectedDoorId,
		activeTool,
		snappedCoord,
	]);

	// 2D Deduplicated Corner/Junction Dots & Open Dead-end Dots across active walls (renders at zoom >= 16.5 for Mappedin snap clarity)
	const studioVerticesGeoJSON = useMemo(() => {
		const features = [];
		if (layerVisibility.walls) {
			const uniqueVerts = deduplicateFloorVertices(currentFloorWalls, {
				selectedWallId,
			});
			for (const uv of uniqueVerts) {
				features.push({
					type: "Feature",
					properties: {
						entityId: uv.entityId,
						kind: "WALL",
						degree: uv.degree,
						isSelected: uv.isSelected,
					},
					geometry: { type: "Point", coordinates: uv.coord },
				});
			}
			// Highlight open wall dead-ends cleanly so user spots incomplete loops immediately
			for (const de of currentFloorModel?.deadEndPoints || []) {
				features.push({
					type: "Feature",
					properties: {
						entityId: "dead-end",
						kind: "DEAD_END",
						degree: 1,
						isSelected: false,
					},
					geometry: { type: "Point", coordinates: de },
				});
			}
		}
		return { type: "FeatureCollection", features };
	}, [layerVisibility.walls, currentFloorWalls, currentFloorModel, selectedWallId]);

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
			ids.push("studio-nav-nodes-layer", "studio-nav-portals-layer");
		}
		if (activeTool === "SELECT") {
			if (layerVisibility.doors && !layerLocks.doors) {
				ids.push(
					"studio-doors-fill-layer",
					"studio-doors-outline-layer",
					"studio-doors-layer",
				);
			}
			if (is3dPreview) {
				if (layerVisibility.walls && !layerLocks.walls) {
					ids.push("studio-walls-layer");
				}
				if (layerVisibility.faces && !layerLocks.faces) {
					ids.push("studio-rooms-extrusion");
				}
			} else {
				if (layerVisibility.walls && !layerLocks.walls) {
					ids.push("studio-walls-hit-layer", "studio-walls-line-layer");
				}
				if (layerVisibility.faces && !layerLocks.faces) {
					ids.push("studio-faces-fill", "studio-faces-outline");
				}
			}
		}
		return ids;
	}, [activeTool, is3dPreview, layerVisibility, layerLocks]);

	// Build Nav Graph GeoJSON for current building/floor (+ outdoor on Floor 0), with symbol portal layer, connected floor badges & ghost portals
	const navGraphLayers = useMemo(() => {
		const boundaries = effectiveStore.buildingBoundaries || [];
		const allEdges = buildAllGraphEdges(effectiveStore);
		const nodeMap = new Map(
			(effectiveStore.navNodes || []).map((n) => [n.id, n]),
		);
		const visibleNodes = (effectiveStore.navNodes || []).filter((n) =>
			isNodeVisibleOnFloor(boundaries, n, activeBuilding, activeFloorOrder),
		);
		const visibleIds = new Set(visibleNodes.map((n) => n.id));
		const edgeFeatures = [];

		for (const [uId, vId] of allEdges) {
			const u = nodeMap.get(uId);
			const v = nodeMap.get(vId);
			if (!u || !v) continue;
			const isVertical = u.floorOrder !== v.floorOrder;
			// Do NOT render cross-floor vertical edges as diagonal 2D lines across the flat 2D floor plan (Image 2 fix)
			if (isVertical && !is3dPreview) continue;
			if (isVertical) {
				if (!visibleIds.has(u.id) && !visibleIds.has(v.id)) continue;
			} else if (!visibleIds.has(u.id) && !visibleIds.has(v.id)) {
				continue;
			}
			edgeFeatures.push({
				type: "Feature",
				properties: {
					isVertical,
				},
				geometry: {
					type: "LineString",
					coordinates: [u.coords, v.coords],
				},
			});
		}

		// Map portalGroupId -> sorted floor labels in this building for 2D/3D badge display
		const portalFloorsByGroup = new Map();
		for (const n of effectiveStore.navNodes || []) {
			if (!isPortalNode(n) || !n.portalGroupId) continue;
			const nb =
				resolveNodeBuilding(boundaries, n) ||
				resolveCanonicalBuildingName(n.building) ||
				n.building;
			if (!matchesBuilding(nb, activeBuilding)) continue;
			const gk = n.portalGroupId.trim().toUpperCase();
			if (!portalFloorsByGroup.has(gk)) portalFloorsByGroup.set(gk, new Set());
			portalFloorsByGroup.get(gk).add(Number(n.floorOrder) || 0);
		}

		const formatFloorShort = (fl) => (fl === 0 ? "G" : `${fl}`);

		const nodeFeatures = [];
		const portalFeatures = [];
		for (const n of visibleNodes) {
			const isSel =
				n.id === selectedNavNodeId || n.id === liveNavNodeDrag?.id;
			const isHead = n.id === chainLastNodeId;
			const outdoor = isNodeOutdoor(boundaries, n);
			const floorOrder = Number(n.floorOrder) || 0;
			const heightOffset = Number(
				(floorOrder * FLOOR_ELEVATION_STEP + FLOOR_SLAB_HEIGHT + 0.35).toFixed(2),
			);

			if (isPortalNode(n)) {
				const gk = (n.portalGroupId || "").trim().toUpperCase();
				const floorsSet = portalFloorsByGroup.get(gk);
				const sortedFl = floorsSet
					? Array.from(floorsSet).sort((a, b) => a - b)
					: [floorOrder];
				const stackBadge =
					sortedFl.length > 1
						? `↕ ${sortedFl.map(formatFloorShort).join("·")}`
						: `Tầng ${formatFloorShort(floorOrder)} (chưa nối tầng)`;

				portalFeatures.push({
					type: "Feature",
					properties: {
						id: n.id,
						nodeType: n.type,
						iconImage:
							n.type === "ELEVATOR" ? "portal-elevator" : "portal-stairs",
						portalGroupId: n.portalGroupId || "",
						stackBadge,
						isMultiFloorSynced: sortedFl.length > 1,
						isSelected: isSel,
						isChainHead: isHead,
						heightOffset,
					},
					geometry: {
						type: "Point",
						coordinates: n.coords,
					},
				});
			}

			nodeFeatures.push({
				type: "Feature",
				properties: {
					id: n.id,
					nodeType: outdoor ? "OUTDOOR" : n.type,
					isOutdoor: outdoor,
					isPortal: isPortalNode(n),
					portalGroupId: n.portalGroupId || "",
					isChainHead: isHead,
					isSelected: isSel,
				},
				geometry: {
					type: "Point",
					coordinates: n.coords,
				},
			});
		}

		// Ghost Portals from other floors of the same building (visible when placing STAIRS/ELEVATOR in NAV_GRAPH)
		const ghostPortalFeatures = [];
		if (
			activeTool === "NAV_GRAPH" &&
			(activeNodeType === "STAIRS" || activeNodeType === "ELEVATOR")
		) {
			const seenGroups = new Set(
				visibleNodes
					.filter((n) => isPortalNode(n) && n.portalGroupId)
					.map((n) => n.portalGroupId.trim().toUpperCase()),
			);
			for (const n of effectiveStore.navNodes || []) {
				if (!isPortalNode(n) || Number(n.floorOrder) === activeFloorOrder) {
					continue;
				}
				const nb =
					resolveNodeBuilding(boundaries, n) ||
					resolveCanonicalBuildingName(n.building) ||
					n.building;
				if (!matchesBuilding(nb, activeBuilding)) continue;
				const gk = (n.portalGroupId || "").trim().toUpperCase();
				if (gk && seenGroups.has(gk)) continue;
				if (gk) seenGroups.add(gk);
				ghostPortalFeatures.push({
					type: "Feature",
					properties: {
						id: `ghost-${n.id}`,
						nodeType: n.type,
						portalGroupId: n.portalGroupId || "",
						fromFloorLabel: `Trục ${n.portalGroupId || n.type} (Tầng ${formatFloorShort(Number(n.floorOrder) || 0)})`,
					},
					geometry: {
						type: "Point",
						coordinates: n.coords,
					},
				});
			}
		}

		return {
			edges: { type: "FeatureCollection", features: edgeFeatures },
			nodes: { type: "FeatureCollection", features: nodeFeatures },
			portals: { type: "FeatureCollection", features: portalFeatures },
			ghostPortals: {
				type: "FeatureCollection",
				features: ghostPortalFeatures,
			},
		};
	}, [
		effectiveStore,
		activeBuilding,
		activeFloorOrder,
		activeTool,
		activeNodeType,
		is3dPreview,
		chainLastNodeId,
		selectedNavNodeId,
		liveNavNodeDrag?.id,
	]);

	// Draft preview GeoJSON while drawing (rotated 2-Click Box preview + draft corner circles + close-loop highlight)
	const draftPreviewGeoJSON = useMemo(() => {
		if (draftPoints.length === 0) {
			return { type: "FeatureCollection", features: [] };
		}
		const features = [];
		const canCloseLoop =
			draftPoints.length >= 3 &&
			(activeTool === "ROOM_POLY" || activeTool === "WALL_DRAW");

		draftPoints.forEach((pt, idx) => {
			features.push({
				type: "Feature",
				properties: {
					isDraftVertex: true,
					isStartVertex: idx === 0,
					canCloseLoop: idx === 0 && canCloseLoop,
				},
				geometry: { type: "Point", coordinates: pt },
			});
		});

		if (hoverCoord) {
			if (activeTool === "ROOM_BOX") {
				const ring = buildOrientedBoxRing(draftPoints[0], hoverCoord, {
					corners: activeCorners,
					axisAligned: shiftKeyDown,
					minSideMeters: 0.05,
				});
				if (ring) {
					features.push({
						type: "Feature",
						geometry: { type: "Polygon", coordinates: [ring] },
					});
				}
			} else {
				features.push({
					type: "Feature",
					geometry: {
						type: "LineString",
						coordinates: [...draftPoints, hoverCoord],
					},
				});
			}
		} else if (draftPoints.length >= 2) {
			features.push({
				type: "Feature",
				geometry: {
					type: "LineString",
					coordinates: draftPoints,
				},
			});
		}

		return { type: "FeatureCollection", features };
	}, [draftPoints, hoverCoord, activeTool, activeCorners, shiftKeyDown]);

	const hasBuildingBoundary = useMemo(
		() => Boolean(currentFloorModel?.exteriorRings?.length),
		[currentFloorModel],
	);

	// Primary selected objects for contextual inspector (backed by effectiveStore, scoped to active floor)
	const singleSelectedFace = useMemo(() => {
		if (selectedRoomIds.length !== 1) return null;
		return (
			currentFloorFaces.find((f) => f.id === selectedRoomIds[0]) || null
		);
	}, [currentFloorFaces, selectedRoomIds]);

	const singleSelectedNavNode = useMemo(() => {
		if (!selectedNavNodeId) return null;
		return (
			(effectiveStore.navNodes || []).find(
				(n) =>
					n.id === selectedNavNodeId &&
					isNodeVisibleOnFloor(
						effectiveStore.buildingBoundaries,
						n,
						activeBuilding,
						activeFloorOrder,
					),
			) || null
		);
	}, [
		effectiveStore.navNodes,
		effectiveStore.buildingBoundaries,
		selectedNavNodeId,
		activeBuilding,
		activeFloorOrder,
	]);

	const singleSelectedWall = useMemo(() => {
		if (!selectedWallId) return null;
		return currentFloorWalls.find((w) => w.id === selectedWallId) || null;
	}, [currentFloorWalls, selectedWallId]);

	const singleSelectedDoor = useMemo(() => {
		if (!selectedDoorId) return null;
		return currentFloorDoors.find((d) => d.id === selectedDoorId) || null;
	}, [currentFloorDoors, selectedDoorId]);

	const singleSelectedDoorGeom = useMemo(() => {
		if (!singleSelectedDoor) return null;
		return buildOrientedDoorGeometry(
			singleSelectedDoor,
			currentFloorModel?.wallSegments || [],
		);
	}, [singleSelectedDoor, currentFloorModel]);

	// Geometry Validator Engine (Nav Graph + Unified Wall Graph + Door Connectivity)
	const validationIssues = useMemo(() => {
		const issues = [];
		const boundaries = store.buildingBoundaries || [];
		const allEdges = buildAllGraphEdges(store);
		const connectedNodeIds = new Set();
		for (const [a, b] of allEdges) {
			connectedNodeIds.add(a);
			connectedNodeIds.add(b);
		}

		// 1. Isolated Nav Nodes on current floor
		for (const n of currentFloorNodes) {
			if (!connectedNodeIds.has(n.id)) {
				const metaLabel = NODE_DISPLAY_META[n.type]?.label || n.type;
				issues.push({
					id: `iso-${n.id}`,
					level: "warning",
					type: "NODE",
					targetId: n.id,
					coords: n.coords,
					title: `Nút mồ côi: ${metaLabel}`,
					desc: `Nút tại [${n.coords[0].toFixed(5)}, ${n.coords[1].toFixed(5)}] chưa được nối vào đồ thị.`,
				});
			}
		}

		// 2. Unmatched Portals for STAIRS / ELEVATOR (strictly scoped per canonical building)
		const portalKey = (n) => {
			const bldg = resolveCanonicalBuildingName(
				resolveNodeBuilding(boundaries, n) || n.building,
			);
			return `${bldg.toUpperCase()}__${n.portalGroupId.trim().toUpperCase()}`;
		};
		const portalsByGroup = new Map();
		for (const n of store.navNodes || []) {
			if (isPortalNode(n) && n.portalGroupId?.trim()) {
				const key = portalKey(n);
				const list = portalsByGroup.get(key) || [];
				list.push(n);
				portalsByGroup.set(key, list);
			}
		}
		for (const n of currentFloorNodes) {
			if (isPortalNode(n) && n.portalGroupId?.trim()) {
				const group = portalsByGroup.get(portalKey(n)) || [];
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

		// 3. Wall Graph dead-end dangling walls
		(currentFloorModel?.deadEndPoints || []).forEach((pt, idx) => {
			issues.push({
				id: `deadend-${idx}-${pt[0]}`,
				level: "warning",
				type: "WALL_PT",
				coords: pt,
				title: "Đầu tường hở (chưa khép kín)",
				desc: "Đầu mút tường chưa chạm vào đoạn tường khác để tạo thành vùng khép kín.",
			});
		});

		// 4. Doors not sitting on any wall segment (> 0.55m)
		const floorWallSegs = currentFloorModel?.wallSegments || [];
		for (const d of currentFloorDoors) {
			if (!Array.isArray(d.coords)) continue;
			const nearWall = floorWallSegs.some(
				(ws) => distancePointToSegmentMeters(d.coords, ws.a, ws.b) <= 0.55,
			);
			if (!nearWall) {
				issues.push({
					id: `door-orphan-${d.id}`,
					level: "error",
					type: "DOOR",
					targetId: d.id,
					coords: d.coords,
					title: "Cửa bị lệch khỏi tường",
					desc: "Cửa chưa bám lên đoạn tường nào trên tầng hiện tại — A* và đục 3D sẽ bỏ qua.",
				});
			}
		}

		// 5. ROOM faces missing a wall-mounted DOOR (advisory for A* doorway routing)
		for (const rm of currentFloorRooms) {
			const hasDoor = currentFloorDoors.some((d) => {
				if (!Array.isArray(d.coords)) return false;
				for (let i = 0; i < (rm.ring?.length || 0) - 1; i++) {
					if (
						distancePointToSegmentMeters(d.coords, rm.ring[i], rm.ring[i + 1]) <=
						0.65
					) {
						return true;
					}
				}
				return false;
			});
			if (!hasDoor) {
				issues.push({
					id: `room-nodoor-${rm.id}`,
					level: "warning",
					type: "ROOM",
					targetId: rm.id,
					coords: rm.seedCoord || rm.ring?.[0],
					title: "Phòng chưa gắn cửa [D]",
					desc: "Nên đặt ít nhất 1 cửa [D] trên vách phòng để lộ trình A* đi đúng qua lối cửa.",
				});
			}
		}

		return issues;
	}, [
		store,
		currentFloorNodes,
		currentFloorModel,
		currentFloorDoors,
		currentFloorRooms,
		activeBuilding,
	]);

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
						className={`digitizer-rail-btn ${activeTool === "WALL_DRAW" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("WALL_DRAW");
							setDraftPoints([]);
						}}
						title="Vẽ tường (khép kín / vách ngăn) [W]"
						aria-label="Vẽ tường [W]"
					>
						<Ruler size={17} />
						<span className="digitizer-rail-key">W</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "DOOR" ? "is-active" : ""}`}
						onClick={() => {
							setActiveTool("DOOR");
							setDraftPoints([]);
						}}
						title="Gắn cửa lên tường [D]"
						aria-label="Gắn cửa [D]"
					>
						<DoorOpen size={17} />
						<span className="digitizer-rail-key">D</span>
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
							{activeTool === "WALL_DRAW" && <Ruler size={14} />}
							{activeTool === "DOOR" && <DoorOpen size={14} />}
							{activeTool === "NAV_GRAPH" && <Route size={14} />}
							{activeTool === "TEST_ROUTE" && <Footprints size={14} />}
							{activeTool === "CALIBRATE_PNG" && <Crosshair size={14} />}
						</span>

						<span className="digitizer-hud__text">
							{activeTool === "SELECT" &&
								(selectedRoomIds.length > 0
									? `Đã chọn ${selectedRoomIds.length} vùng [Đổi vai trò ROOM/CORRIDOR/VOID bên phải · Shift: chọn thêm]`
									: singleSelectedNavNode
										? `${NODE_DISPLAY_META[singleSelectedNavNode.type]?.label || singleSelectedNavNode.type} [Kéo để di chuyển / gộp nút · Del: xóa]`
										: singleSelectedDoor
											? `Cửa gắn tường (${singleSelectedDoor.widthMeters || 1.2}m) [Kéo 2 chốt mép để chỉnh rộng · Kéo tâm để trượt · Del: xóa]`
											: singleSelectedWall
												? `Đoạn tường [Kéo góc để di chuyển mối hàn (Giữ Alt: tách rời) · + để bẻ góc · Del: xóa]`
												: "Chọn đối tượng [Click vùng để đổi Phòng/Hành lang/Void · Click tường hoặc cửa để sửa]")}

							{activeTool === "ROOM_BOX" &&
								(draftPoints.length === 0
									? "Click điểm 1 để dựng hộp tường Phòng [B · Giữ Shift: ngang 0°]"
									: shiftKeyDown
										? "Click điểm 2 (Chế độ ngang 0°) [Esc: hủy]"
										: "Click điểm 2 để hoàn tất [Giữ Shift: ngang 0° · Esc: hủy]")}

							{activeTool === "ROOM_SLICE" &&
								(draftPoints.length === 0
									? "Click điểm 1 để kéo vách chia đôi vùng [S]"
									: "Click điểm 2 xuyên qua vùng cần chia [Esc: hủy]")}

							{activeTool === "ROOM_POLY" &&
								`Đa giác Phòng: ${draftPoints.length} điểm [Click điểm đầu / Enter: khép kín, Esc: hủy]`}

							{activeTool === "WALL_DRAW" &&
								`Vẽ tường: ${draftPoints.length} điểm [Click điểm đầu (≥3): khép kín · Click điểm cuối / Enter (≥2): kết thúc · Esc: hủy]`}

							{activeTool === "DOOR" &&
								"Rê chuột dọc tường & Click để gắn Cửa (hộp chữ nhật đục tường 3D & trung điểm làm Node A*) [D]"}

							{activeTool === "NAV_GRAPH" &&
								`Đồ thị (${NODE_DISPLAY_META[activeNodeType]?.label || activeNodeType}): Click đặt / nối Trung điểm Cửa · Kéo trùng nút để tự gộp [Esc: ngắt]`}

							{activeTool === "TEST_ROUTE" &&
								(!testRouteStart
									? "Click điểm xuất phát [T]"
									: "Click điểm đến để tìm đường")}

							{activeTool === "CALIBRATE_PNG" &&
								"Kéo 4 góc để co giãn · Chốt trên để xoay · Chốt tâm để di chuyển"}
						</span>

						{((draftPoints.length >= 3 && activeTool === "ROOM_POLY") ||
							(draftPoints.length >= 2 && activeTool === "WALL_DRAW")) && (
							<button
								type="button"
								className="digitizer-hud__btn digitizer-hud__btn--confirm"
								onClick={() => finishPolygonDraft(activeTool === "ROOM_POLY")}
							>
								<Check size={12} />
								<span>
									{activeTool === "ROOM_POLY" ? "Khép kín (Enter)" : "Xong tường (Enter)"}
								</span>
							</button>
						)}

						{selectedRoomIds.length > 0 && activeTool === "SELECT" && (
							<button
								type="button"
								className="digitizer-hud__btn digitizer-hud__btn--danger"
								onClick={deleteSelectedRooms}
							>
								<Trash2 size={12} />
								<span>Bỏ vùng Phòng ({selectedRoomIds.length})</span>
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
										color:
											snapType === "VERTEX"
												? "#10b981"
												: snapType === "MIDPOINT"
													? "#f59e0b"
													: "#38bdf8",
									}}
								>
									Snap{" "}
									{snapType === "VERTEX"
										? "Đỉnh"
										: snapType === "MIDPOINT"
											? "Trung điểm"
											: "Cạnh"}{" "}
									[Alt: Tắt]
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
							<strong>{currentFloorWalls.length} Tường</strong>
							<span>·</span>
							<strong>{currentFloorDoors.length} Cửa</strong>
							<span>·</span>
							<strong>{currentFloorNodes.length} Nút</strong>
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
						onLoad={(e) => {
							const map = e?.target;
							if (!map) return;
							registerPortalMapImages(map);
							map.on?.("styleimagemissing", (ev) =>
								ensurePortalMapImage(map, ev.id),
							);
							map.setLight?.(STUDIO_LIGHT);
						}}
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
						{is3dPreview && (layerVisibility.walls || layerVisibility.faces) && (
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
						{is3dPreview && layerVisibility.walls && (
							<Source
								id="studio-ghost-walls-src"
								type="geojson"
								data={vector3dSplit.walls.ghost}
							>
								<Layer
									id="studio-ghost-walls-layer"
									type="fill-extrusion"
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
						{is3dPreview && layerVisibility.faces && (
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

						{/* 3. Active Floor Slab (carved VOID holes automatically in 3D) */}
						{(layerVisibility.walls || layerVisibility.faces) && (
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
											"fill-extrusion-opacity": 0.65,
										}}
									/>
								) : (
									<Layer
										key="studio-slab-fill"
										id="studio-slab-fill"
										type="fill"
										paint={{
											"fill-color": "#cbd5e1",
											"fill-opacity": 0.12,
										}}
									/>
								)}
							</Source>
						)}

						{/* 4. Extracted Planar Faces in 2D (ROOM / CORRIDOR / VOID) */}
						{!is3dPreview && layerVisibility.faces && (
							<Source
								id="studio-faces-src"
								type="geojson"
								data={studioFacesGeoJSON}
							>
								<Layer
									id="studio-faces-fill"
									type="fill"
									paint={{
										"fill-color": [
											"case",
											["get", "isSelected"],
											"#f97316",
											[
												"match",
												["get", "role"],
												"ROOM",
												"#0284c7",
												"VOID",
												"#ef4444",
												"#10b981",
											],
										],
										"fill-opacity": [
											"case",
											["get", "isSelected"],
											0.34,
											[
												"match",
												["get", "role"],
												"ROOM",
												0.24,
												"VOID",
												0.2,
												0.09,
											],
										],
									}}
								/>
								<Layer
									id="studio-faces-outline"
									type="line"
									paint={{
										"line-color": [
											"case",
											["get", "isSelected"],
											"#fb923c",
											[
												"match",
												["get", "role"],
												"ROOM",
												"#38bdf8",
												"VOID",
												"#f87171",
												"#34d399",
											],
										],
										"line-width": ["case", ["get", "isSelected"], 2.4, 1.2],
										"line-opacity": 0.78,
									}}
								/>
							</Source>
						)}

						{/* 4b. Unified Wall Polylines (2D editing & hit target) */}
						{!is3dPreview && layerVisibility.walls && (
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
											["==", ["get", "wallKind"], "PERIMETER"],
											"#cbd5e1",
											"#38bdf8",
										],
										"line-width": [
											"case",
											["get", "isSelected"],
											4.5,
											["==", ["get", "wallKind"], "PERIMETER"],
											3.0,
											2.4,
										],
										"line-opacity": 0.96,
									}}
								/>
							</Source>
						)}

						{/* 4c. 2D Wall Vertices & Open Dead-end Dots (minzoom 16.5 for Mappedin snap UX) */}
						{!is3dPreview && layerVisibility.walls && (
							<Source
								id="studio-vertices-src"
								type="geojson"
								data={studioVerticesGeoJSON}
							>
								<Layer
									id="studio-vertices-layer"
									type="circle"
									minzoom={16.5}
									paint={{
										"circle-radius": [
											"interpolate",
											["linear"],
											["zoom"],
											16.5,
											[
												"case",
												["get", "isSelected"],
												3.8,
												["==", ["get", "kind"], "DEAD_END"],
												4.4,
												2.4,
											],
											19.5,
											[
												"case",
												["get", "isSelected"],
												5.4,
												["==", ["get", "kind"], "DEAD_END"],
												6.0,
												3.8,
											],
										],
										"circle-opacity":
											activeTool === "NAV_GRAPH" || activeTool === "TEST_ROUTE"
												? 0.52
												: [
														"interpolate",
														["linear"],
														["zoom"],
														16.5,
														0.55,
														18.5,
														0.96,
													],
										"circle-color": [
											"case",
											["get", "isSelected"],
											"#f97316",
											["==", ["get", "kind"], "DEAD_END"],
											"#ef4444",
											"#38bdf8",
										],
										"circle-stroke-width": 1.3,
										"circle-stroke-color": "#0f172a",
									}}
								/>
							</Source>
						)}

						{/* 4d. 3D Extruded Walls (2.85m Perimeter / 1.35m Partition, 100% Opaque, Carved Doorways) */}
						{is3dPreview && layerVisibility.walls && (
							<Source
								id="studio-walls-src"
								type="geojson"
								data={vector3dSplit.walls.active}
							>
								<Layer
									id="studio-walls-layer"
									type="fill-extrusion"
									paint={{
										"fill-extrusion-color": ["coalesce", ["get", "color"], "#64748b"],
										"fill-extrusion-height": ["get", "extrudeHeight"],
										"fill-extrusion-base": ["get", "baseHeight"],
										"fill-extrusion-opacity": 1.0,
										"fill-extrusion-vertical-gradient": true,
									}}
								/>
							</Source>
						)}

						{/* 5. Active Floor 3D Rooms */}
						{is3dPreview && layerVisibility.faces && (
							<Source
								id="studio-rooms-src"
								type="geojson"
								data={vector3dSplit.rooms.active}
							>
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
							</Source>
						)}

						{/* 5b. Wall-Mounted Doors Overlay (Oriented Architectural Door Box as in Image 1 + Midpoint Graph Node) */}
						{layerVisibility.doors && (
							<Source
								id="studio-doors-src"
								type="geojson"
								data={studioDoorsGeoJSON}
							>
								<Layer
									id="studio-doors-fill-layer"
									type="fill"
									filter={["==", ["get", "featureKind"], "DOOR_BOX"]}
									paint={{
										"fill-color": [
											"case",
											["get", "isSelected"],
											"#fef3c7",
											"#fff8f6",
										],
										"fill-opacity": [
											"case",
											["get", "isPreview"],
											0.68,
											0.96,
										],
									}}
								/>
								<Layer
									id="studio-doors-outline-layer"
									type="line"
									filter={["==", ["get", "featureKind"], "DOOR_BOX"]}
									paint={{
										"line-color": [
											"case",
											["get", "isSelected"],
											"#f59e0b",
											["get", "isPreview"],
											"#fb7185",
											"#e11d48",
										],
										"line-width": [
											"case",
											["get", "isSelected"],
											3.2,
											2.6,
										],
									}}
								/>
								<Layer
									id="studio-doors-leaf-layer"
									type="line"
									filter={["==", ["get", "featureKind"], "DOOR_LEAF"]}
									paint={{
										"line-color": [
											"case",
											["get", "isSelected"],
											"#d97706",
											"#be123c",
										],
										"line-width": 1.4,
										"line-dasharray": [2, 1.5],
									}}
								/>
								<Layer
									id="studio-doors-layer"
									type="circle"
									filter={["==", ["get", "featureKind"], "DOOR_MIDPOINT"]}
									paint={{
										"circle-radius": [
											"case",
											["get", "isSelected"],
											5.2,
											activeTool === "NAV_GRAPH" || activeTool === "DOOR"
												? 4.6
												: 3.4,
										],
										"circle-color": [
											"case",
											["get", "isSelected"],
											"#f59e0b",
											activeTool === "NAV_GRAPH"
												? "#10b981"
												: "#e11d48",
										],
										"circle-stroke-width": 1.6,
										"circle-stroke-color": "#ffffff",
									}}
								/>
							</Source>
						)}

						{/* 5. Draft Preview while drawing (Polygon/Line + Draft Vertex Circles + Loop-Close Highlight) */}
						<Source
							id="studio-draft-src"
							type="geojson"
							data={draftPreviewGeoJSON}
						>
							<Layer
								id="studio-draft-fill"
								type="fill"
								filter={["==", ["geometry-type"], "Polygon"]}
								paint={{ "fill-color": "#f97316", "fill-opacity": 0.25 }}
							/>
							<Layer
								id="studio-draft-line"
								type="line"
								filter={["==", ["geometry-type"], "LineString"]}
								paint={{
									"line-color": "#f97316",
									"line-width": 2.5,
									"line-dasharray": [2, 1],
								}}
							/>
							<Layer
								id="studio-draft-points-layer"
								type="circle"
								filter={["==", ["geometry-type"], "Point"]}
								paint={{
									"circle-radius": [
										"case",
										["get", "canCloseLoop"],
										8,
										["get", "isStartVertex"],
										5.5,
										4.2,
									],
									"circle-color": [
										"case",
										["get", "canCloseLoop"],
										"#10b981",
										"#f97316",
									],
									"circle-stroke-width": [
										"case",
										["get", "canCloseLoop"],
										2.5,
										1.6,
									],
									"circle-stroke-color": "#ffffff",
								}}
							/>
						</Source>

						{/* 6. Navigation Graph Edges, Nodes & Symbol Portals (STAIRS/ELEVATOR) */}
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
												10,
												["get", "isChainHead"],
												8,
												["get", "isPortal"],
												0,
												5.5,
											],
											"circle-color": [
												"case",
												["get", "isOutdoor"],
												"#10b981",
												[
													"match",
													["get", "nodeType"],
													"DOOR",
													"#f97316",
													"STAIRS",
													"#a855f7",
													"ELEVATOR",
													"#0284c7",
													"#0ea5e9",
												],
											],
											"circle-stroke-width": [
												"case",
												["get", "isSelected"],
												3,
												["get", "isPortal"],
												0,
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

								<Source
									id="studio-nav-portals-src"
									type="geojson"
									data={navGraphLayers.portals}
								>
									<Layer
										id="studio-nav-portals-layer"
										type="symbol"
										layout={{
											"icon-image": ["get", "iconImage"],
											"icon-size": [
												"case",
												["get", "isSelected"],
												0.95,
												0.78,
											],
											"icon-allow-overlap": true,
											"icon-ignore-placement": true,
											"text-field": ["coalesce", ["get", "stackBadge"], ""],
											"text-font": ["Open Sans Bold", "Arial Unicode MS Bold"],
											"text-size": 10.5,
											"text-offset": [0, 1.85],
											"text-anchor": "top",
											"text-optional": true,
											"symbol-height-anchor": "ground",
											"symbol-height-offset": is3dPreview
												? ["coalesce", ["get", "heightOffset"], 0.35]
												: 0,
										}}
										paint={{
											"text-color": [
												"case",
												["get", "isMultiFloorSynced"],
												"#e0f2fe",
												"#fed7aa",
											],
											"text-halo-color": "rgba(15, 23, 42, 0.94)",
											"text-halo-width": 1.8,
										}}
									/>
								</Source>

								{/* Ghost Portal Reference from adjacent floors when placing STAIRS/ELEVATOR */}
								{navGraphLayers.ghostPortals?.features?.length > 0 && (
									<Source
										id="studio-nav-ghost-portals-src"
										type="geojson"
										data={navGraphLayers.ghostPortals}
									>
										<Layer
											id="studio-nav-ghost-portals-layer"
											type="circle"
											paint={{
												"circle-radius": 11,
												"circle-color": "rgba(168, 85, 247, 0.16)",
												"circle-stroke-width": 2,
												"circle-stroke-color": "#c084fc",
											}}
										/>
									</Source>
								)}
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

						{/* 8. Magnetic Snap Indicator Ring (VERTEX -> MIDPOINT -> EDGE) */}
						{snappedCoord && (
							<Marker
								longitude={snappedCoord[0]}
								latitude={snappedCoord[1]}
								anchor="center"
								style={NON_INTERACTIVE_MARKER_STYLE}
							>
								<div
									className={`digitizer-snap-ring ${
										snapType === "MIDPOINT"
											? "digitizer-snap-ring--midpoint"
											: snapType === "EDGE"
												? "digitizer-snap-ring--edge"
												: ""
									}`}
									title={`Bắt dính vào ${
										snapType === "VERTEX"
											? "Đỉnh góc"
											: snapType === "MIDPOINT"
												? "Trung điểm cạnh"
												: "Cạnh tường"
									}`}
								/>
							</Marker>
						)}

						{/* 9. Draggable Corner Vertices & Midpoint Inserts for Selected Wall */}
						{singleSelectedWall &&
							activeTool === "SELECT" &&
							!layerLocks.walls &&
							(isClosedRing(singleSelectedWall.line)
								? singleSelectedWall.line.slice(0, -1)
								: singleSelectedWall.line || []
							).map((pt, idx) => (
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
											e.originalEvent,
										)
									}
									onDragEnd={(e) =>
										handleVertexDragEnd(
											"WALL",
											singleSelectedWall.id,
											idx,
											e.lngLat,
											e.originalEvent,
										)
									}
								>
									<div
										className="digitizer-vertex-handle digitizer-vertex-handle--wall"
										title="Kéo để di chuyển góc tường (Giữ Alt: tách rời 1 đầu tường riêng)"
									/>
								</Marker>
							))}

						{/* 9b. Interactive Door Handles (Midpoint Slide + Left/Right Jamb Width Resize) */}
						{singleSelectedDoor &&
							singleSelectedDoorGeom &&
							activeTool === "SELECT" &&
							!layerLocks.doors && (
								<>
									<Marker
										key={`door-mid-${singleSelectedDoor.id}`}
										longitude={singleSelectedDoorGeom.midpoint[0]}
										latitude={singleSelectedDoorGeom.midpoint[1]}
										anchor="center"
										draggable
										onClick={(e) => e?.originalEvent?.stopPropagation?.()}
										onDrag={(e) =>
											handleDoorMoveDragLive(singleSelectedDoor.id, e.lngLat)
										}
										onDragEnd={(e) =>
											handleDoorMoveDragEnd(singleSelectedDoor.id, e.lngLat)
										}
									>
										<div
											className="digitizer-door-handle digitizer-door-handle--mid"
											title="Kéo tâm để trượt Cửa dọc theo tường (Node trung điểm A*)"
										/>
									</Marker>

									{[
										{ key: "jambA", pt: singleSelectedDoorGeom.jambA },
										{ key: "jambB", pt: singleSelectedDoorGeom.jambB },
									].map((jb) => (
										<Marker
											key={`door-${jb.key}-${singleSelectedDoor.id}`}
											longitude={jb.pt[0]}
											latitude={jb.pt[1]}
											anchor="center"
											draggable
											onClick={(e) => e?.originalEvent?.stopPropagation?.()}
											onDrag={(e) =>
												handleDoorJambDragLive(singleSelectedDoor, e.lngLat)
											}
											onDragEnd={(e) =>
												handleDoorJambDragEnd(singleSelectedDoor, e.lngLat)
											}
										>
											<div
												className="digitizer-door-handle digitizer-door-handle--jamb"
												title={`Kéo mép cửa để chỉnh độ rộng (${singleSelectedDoorGeom.widthMeters}m)`}
											/>
										</Marker>
									))}
								</>
							)}

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
								{currentFloorFaces.length}
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
							{/* Case A: Selected Planar Face(s) */}
							{selectedRoomIds.length > 0 && (
								<div className="digitizer-prop-card">
									<div className="digitizer-prop-header">
										<span className="digitizer-prop-title">
											<Square size={13} />
											{selectedRoomIds.length === 1
												? "Vùng khép kín (Planar Face)"
												: `Đã chọn ${selectedRoomIds.length} vùng`}
										</span>
									</div>

									{selectedRoomIds.length === 1 && singleSelectedFace && (
										<>
											<div className="digitizer-field">
												<label className="digitizer-field-label">
													Vai trò vùng (Mappedin Role):
												</label>
												<div className="digitizer-chip-row">
													{[
														{ id: "ROOM", label: "Phòng (3D)", accent: "#0284c7" },
														{ id: "CORRIDOR", label: "Hành lang", accent: "#10b981" },
														{ id: "VOID", label: "Khoét rỗng", accent: "#ef4444" },
													].map((roleOpt) => (
														<button
															key={roleOpt.id}
															type="button"
															style={{ "--role-accent": roleOpt.accent }}
															className={`digitizer-role-btn ${singleSelectedFace.role === roleOpt.id ? "is-active" : ""}`}
															onClick={() =>
																assignFaceRole(singleSelectedFace, roleOpt.id)
															}
														>
															{roleOpt.label}
														</button>
													))}
												</div>
											</div>
											<div className="digitizer-prop-row">
												<label>Diện tích:</label>
												<span className="digitizer-prop-val">
													{(
														singleSelectedFace.areaSqm ??
														approximatePolygonAreaMeters(singleSelectedFace.ring)
													).toFixed(1)}{" "}
													m²
												</span>
											</div>
											<div className="digitizer-prop-row">
												<label>Chu vi:</label>
												<span className="digitizer-prop-val">
													{approximateRingPerimeterMeters(
														singleSelectedFace.ring,
													).toFixed(1)}{" "}
													m
												</span>
											</div>
											{(singleSelectedFace.holes?.length || 0) > 0 && (
												<div className="digitizer-prop-row">
													<label>Lỗ khoét (Void):</label>
													<span className="digitizer-prop-val">
														{singleSelectedFace.holes.length} lỗ lồng
													</span>
												</div>
											)}
										</>
									)}

									{selectedRoomIds.length > 1 && (
										<>
											<div className="digitizer-prop-row">
												<label>Số lượng:</label>
												<span className="digitizer-prop-val">
													{selectedRoomIds.length} vùng
												</span>
											</div>
											<div className="digitizer-prop-row">
												<label>Tổng diện tích:</label>
												<span className="digitizer-prop-val">
													{currentFloorFaces
														.filter((f) => selectedRoomIds.includes(f.id))
														.reduce(
															(acc, f) =>
																acc +
																(f.areaSqm ?? approximatePolygonAreaMeters(f.ring)),
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
											<span>Về Hành lang</span>
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
											value={
												isPortalNode(singleSelectedNavNode)
													? singleSelectedNavNode.type
													: "NODE"
											}
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
										<label>Không gian:</label>
										<span className="digitizer-prop-val">
											{isNodeOutdoor(
												store.buildingBoundaries,
												singleSelectedNavNode,
											)
												? "Ngoài sân trường (Tầng 0)"
												: `Trong nhà · ${resolveNodeBuilding(store.buildingBoundaries, singleSelectedNavNode) || activeBuilding}`}
										</span>
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
										<>
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

											<div className="digitizer-field">
												<label className="digitizer-field-label">
													Tầng kết nối thẳng trục ({activeBuilding}):
												</label>
												{(() => {
													const gk = (
														singleSelectedNavNode.portalGroupId || ""
													)
														.trim()
														.toUpperCase();
													const connectedFloors = new Set(
														(store.navNodes || [])
															.filter(
																(n) =>
																	isPortalNode(n) &&
																	(n.portalGroupId || "")
																		.trim()
																		.toUpperCase() === gk &&
																	matchesBuilding(
																		resolveNodeBuilding(
																			store.buildingBoundaries || [],
																			n,
																		) || n.building,
																		activeBuilding,
																	),
															)
															.map((n) => Number(n.floorOrder) || 0),
													);
													connectedFloors.add(
														Number(singleSelectedNavNode.floorOrder) || 0,
													);
													const allFloors = currentBuildingMeta.floors || [];

													return (
														<>
															<div className="digitizer-chip-row">
																{allFloors.map((flMeta) => {
																	const isChecked = connectedFloors.has(
																		flMeta.floorOrder,
																	);
																	const isCurrent =
																		flMeta.floorOrder ===
																		Number(singleSelectedNavNode.floorOrder);
																	return (
																		<button
																			key={flMeta.floorOrder}
																			type="button"
																			style={{ "--role-accent": "#a855f7" }}
																			className={`digitizer-role-btn ${isChecked ? "is-active" : ""}`}
																			onClick={() => {
																				if (isCurrent) return;
																				const nextSet = new Set(connectedFloors);
																				if (isChecked) {
																					nextSet.delete(flMeta.floorOrder);
																				} else {
																					nextSet.add(flMeta.floorOrder);
																				}
																				setStore((prev) =>
																					syncPortalAcrossBuildingFloors(prev, {
																						sourceNodeId:
																							singleSelectedNavNode.id,
																						targetFloorOrders:
																							Array.from(nextSet),
																					}),
																				);
																				showToast(
																					isChecked
																						? `Đã gỡ kết nối ${flMeta.name} khỏi trục thang.`
																						: `Đã tạo & căn thẳng trục thang sang ${flMeta.name}.`,
																				);
																			}}
																			title={
																				isCurrent
																					? "Tầng hiện tại của nút đang chọn"
																					: "Bật/tắt để tự động tạo & căn thẳng trục thang trên tầng này"
																			}
																		>
																			{isChecked ? "✓ " : "+ "}
																			{flMeta.shortLabel}
																		</button>
																	);
																})}
															</div>
															<button
																type="button"
																className="digitizer-sub-btn"
																style={{ marginTop: 6, width: "100%" }}
																onClick={() => {
																	const allFlOrders = allFloors.map(
																		(f) => f.floorOrder,
																	);
																	setStore((prev) =>
																		syncPortalAcrossBuildingFloors(prev, {
																			sourceNodeId: singleSelectedNavNode.id,
																			targetFloorOrders: allFlOrders,
																		}),
																	);
																	showToast(
																		`Đã đồng bộ thẳng trục thang xuyên tất cả ${allFlOrders.length} tầng (${activeBuilding})!`,
																	);
																}}
															>
																<ArrowUpDown size={12} />
																<span>Đồng bộ thẳng trục lên mọi tầng</span>
															</button>
														</>
													);
												})()}
											</div>
										</>
									)}

									<p className="digitizer-prop-hint">
										Giữ chuột và kéo trực tiếp nút tròn hoặc biểu tượng thang để di chuyển (hỗ trợ Pixel Snap &amp; Undo).
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

							{/* Case C2: Selected Wall-Mounted Door */}
							{singleSelectedDoor && (
								<div className="digitizer-prop-card">
									<div className="digitizer-prop-header">
										<span className="digitizer-prop-title">
											<DoorOpen size={13} />
											Cửa trên tường ({singleSelectedDoor.id})
										</span>
									</div>

									<div className="digitizer-field">
										<label className="digitizer-field-label">
											Bề rộng cửa linh hoạt (0.5m – 5.0m):
										</label>
										<div className="digitizer-door-width-controls">
											<input
												type="range"
												min="0.5"
												max="5.0"
												step="0.05"
												value={Number(singleSelectedDoor.widthMeters) || 1.2}
												onChange={(e) => {
													const nextW = Number(
														Math.max(
															0.5,
															Math.min(5.0, Number(e.target.value) || 1.2),
														).toFixed(2),
													);
													setStore((prev) =>
														synchronizeCompatibilityGeometry({
															...prev,
															version: 3,
															doors: (prev.doors || []).map((d) =>
																d.id === singleSelectedDoor.id
																	? { ...d, widthMeters: nextW }
																	: d,
															),
														}),
													);
												}}
												className="digitizer-door-slider"
												aria-label="Thanh trượt bề rộng cửa"
											/>
											<input
												type="number"
												min="0.5"
												max="5.0"
												step="0.05"
												value={Number(singleSelectedDoor.widthMeters) || 1.2}
												onChange={(e) => {
													const rawVal = Number(e.target.value);
													if (!Number.isFinite(rawVal)) return;
													const nextW = Number(
														Math.max(0.5, Math.min(5.0, rawVal)).toFixed(2),
													);
													setStore((prev) =>
														synchronizeCompatibilityGeometry({
															...prev,
															version: 3,
															doors: (prev.doors || []).map((d) =>
																d.id === singleSelectedDoor.id
																	? { ...d, widthMeters: nextW }
																	: d,
															),
														}),
													);
												}}
												className="digitizer-field-input digitizer-door-number-input"
												aria-label="Nhập số bề rộng cửa (m)"
											/>
										</div>
										<div
											className="digitizer-chip-row"
											style={{ gridTemplateColumns: "repeat(4, 1fr)", marginTop: 6 }}
										>
											{[0.9, 1.2, 1.8, 2.4].map((wVal) => (
												<button
													key={wVal}
													type="button"
													style={{ "--role-accent": "#e11d48" }}
													className={`digitizer-role-btn ${Math.abs((Number(singleSelectedDoor.widthMeters) || 1.2) - wVal) < 0.03 ? "is-active" : ""}`}
													onClick={() => {
														setStore((prev) =>
															synchronizeCompatibilityGeometry({
																...prev,
																version: 3,
																doors: (prev.doors || []).map((d) =>
																	d.id === singleSelectedDoor.id
																		? { ...d, widthMeters: wVal }
																		: d,
																),
															}),
														);
														showToast(`Đã đổi bề rộng cửa thành ${wVal}m.`);
													}}
												>
													{wVal}m
												</button>
											))}
										</div>
									</div>

									<div className="digitizer-prop-row">
										<label>Trung điểm (Node A*):</label>
										<span className="digitizer-prop-val">
											{singleSelectedDoor.coords[0].toFixed(5)},{" "}
											{singleSelectedDoor.coords[1].toFixed(5)}
										</span>
									</div>

									<p className="digitizer-prop-hint">
										Kéo 2 chốt vuông ở mép cửa trên bản đồ để co giãn độ rộng trực tiếp, hoặc dùng công cụ [N] bắt dính vào trung điểm cửa để nối dây đồ thị.
									</p>

									<div className="digitizer-btn-row">
										<button
											type="button"
											className="digitizer-sub-btn digitizer-sub-btn--danger"
											onClick={() => deleteDoorById(singleSelectedDoor.id)}
										>
											<Trash2 size={12} />
											<span>Xóa cửa</span>
										</button>
										<button
											type="button"
											className="digitizer-sub-btn"
											onClick={() => setSelectedDoorId(null)}
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
										Công cụ Node + Edge &amp; Portal
									</span>

									<div className="digitizer-node-types-grid">
										{NODE_TYPES.map((nt) => (
											<button
												key={nt.id}
												type="button"
												style={{ "--node-color": nt.color }}
												className={`digitizer-node-type-btn ${activeNodeType === nt.id ? "is-active" : ""}`}
												onClick={() => setActiveNodeType(nt.id)}
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
												{(activeBuilding.includes("Epsilon")
													? ["EPS_THANG_TH1", "EPS_THANG_TH2", "EPS_ELEV_1"]
													: ["HCM_STAIR_W", "HCM_STAIR_E", "HCM_ELEV_1"]
												).map((pg) => (
													<button
														key={pg}
														type="button"
														className={`digitizer-quick-chip ${portalGroupId === pg ? "is-active" : ""}`}
														onClick={() => setPortalGroupId(pg)}
													>
														{pg}
													</button>
												))}
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
									<label>Khung bao:</label>
									<span className="digitizer-prop-val">
										{hasBuildingBoundary ? "Đã khép kín" : "Chưa khép kín"}
									</span>
								</div>
								<div className="digitizer-prop-row">
									<label>Phòng / Vùng:</label>
									<span className="digitizer-prop-val">
										{currentFloorRooms.length} phòng / {currentFloorFaces.length} vùng
									</span>
								</div>
								<div className="digitizer-prop-row">
									<label>Tường / Cửa:</label>
									<span className="digitizer-prop-val">
										{currentFloorWalls.length} tường · {currentFloorDoors.length} cửa
									</span>
								</div>
								<div className="digitizer-prop-row">
									<label>Chiều cao 3D:</label>
									<span className="digitizer-prop-val">
										Bao {BOUNDARY_WALL_HEIGHT}m · Vách {INTERIOR_WALL_HEIGHT}m
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
										title="Sao chép tường, vai trò vùng và cửa sang tầng kế tiếp"
									>
										<Copy size={12} />
										<span>Sao chép lên tầng trên</span>
									</button>
									<button
										type="button"
										className="digitizer-sub-btn digitizer-sub-btn--danger"
										onClick={handleClearCurrentFloorRooms}
										title="Xóa toàn bộ tường, vùng và cửa tầng hiện tại"
									>
										<Trash2 size={12} />
										<span>Xóa sạch tầng</span>
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

								{[
									{ key: "walls", label: "Mạng tường (2D/3D)" },
									{ key: "faces", label: "Vùng & Phòng (Faces)" },
									{ key: "doors", label: "Cửa trên tường (Doors)" },
									{ key: "navGraph", label: "Đồ thị đường" },
								].map(({ key, label }) => (
									<div key={key} className="digitizer-layer-row">
										<span>{label}</span>
										<div className="digitizer-layer-actions">
											<button
												type="button"
												className={`digitizer-layer-icon-btn ${layerVisibility[key] ? "is-active" : ""}`}
												onClick={() =>
													setLayerVisibility((l) => ({
														...l,
														[key]: !l[key],
													}))
												}
												aria-label={`Bật tắt lớp ${label}`}
											>
												{layerVisibility[key] ? (
													<Eye size={13} />
												) : (
													<EyeOff size={13} />
												)}
											</button>
											<button
												type="button"
												className={`digitizer-layer-icon-btn ${layerLocks[key] ? "is-active" : ""}`}
												onClick={() =>
													setLayerLocks((l) => ({
														...l,
														[key]: !l[key],
													}))
												}
												aria-label={`Khóa lớp ${label}`}
											>
												{layerLocks[key] ? (
													<Lock size={13} />
												) : (
													<Unlock size={13} />
												)}
											</button>
										</div>
									</div>
								))}
							</div>

							{/* Scene Tree: Extracted Planar Faces List with Multi-Select */}
							<div className="digitizer-tree-group">
								<div className="digitizer-tree-header">
									<span>Vùng khép kín ({currentFloorFaces.length})</span>
									<div className="digitizer-tree-header-actions">
										<button
											type="button"
											className="digitizer-tree-micro-btn"
											onClick={() =>
												setSelectedRoomIds(currentFloorRooms.map((r) => r.id))
											}
											title="Chọn tất cả vùng Phòng trên tầng này"
										>
											Chọn Phòng
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
									{currentFloorFaces.length === 0 ? (
										<span className="digitizer-tree-empty">
											Chưa có vùng khép kín nào (vẽ tường khép vòng [W] hoặc [B]/[P])
										</span>
									) : (
										currentFloorFaces.map((fc, idx) => {
											const isSelected = selectedRoomIds.includes(fc.id);
											const roleColor =
												fc.role === "ROOM"
													? "#38bdf8"
													: fc.role === "VOID"
														? "#f87171"
														: "#34d399";
											const roleName =
												fc.role === "ROOM"
													? "Phòng"
													: fc.role === "VOID"
														? "Void"
														: "Hành lang";
											return (
												<div
													key={fc.id}
													className={`digitizer-tree-item-btn ${isSelected ? "is-selected" : ""}`}
												>
													<button
														type="button"
														className={`digitizer-tree-chk ${isSelected ? "is-checked" : ""}`}
														onClick={() =>
															setSelectedRoomIds((prev) =>
																prev.includes(fc.id)
																	? prev.filter((id) => id !== fc.id)
																	: [...prev, fc.id],
															)
														}
														title={isSelected ? "Bỏ chọn vùng" : "Chọn thêm vùng"}
														aria-label={`${isSelected ? "Bỏ chọn" : "Chọn"} ${roleName} ${idx + 1}`}
													>
														{isSelected && <Check size={10} strokeWidth={3} />}
													</button>
													<button
														type="button"
														className="digitizer-tree-item-select"
														onClick={(e) => {
															if (e.shiftKey) {
																setSelectedRoomIds((prev) =>
																	prev.includes(fc.id)
																		? prev.filter((id) => id !== fc.id)
																		: [...prev, fc.id],
																);
															} else {
																selectEntity("FACE", fc.id);
															}
															setActiveTool("SELECT");
															setActiveInspectorTab("inspector");
														}}
													>
														<Square
															size={12}
															color={isSelected ? "#f97316" : roleColor}
														/>
														<span className="digitizer-tree-item-title">
															{roleName} #{idx + 1} (
															{(
																fc.areaSqm ?? approximatePolygonAreaMeters(fc.ring)
															).toFixed(0)}{" "}
															m²)
														</span>
													</button>
													{fc.role !== "CORRIDOR" && (
														<button
															type="button"
															className="digitizer-tree-delete-btn"
															onClick={() => deleteRoomById(fc.id)}
															title="Đặt lại về Hành lang"
															aria-label={`Đặt ${roleName} ${idx + 1} về Hành lang`}
														>
															<Trash2 size={12} />
														</button>
													)}
												</div>
											);
										})
									)}
								</div>
							</div>

							{/* Scene Tree: Wall-Mounted Doors */}
							<div className="digitizer-tree-group">
								<div className="digitizer-tree-header">
									<span>Cửa trên tường</span>
									<span className="digitizer-inspector-badge">
										{currentFloorDoors.length}
									</span>
								</div>

								<div className="digitizer-tree-list">
									{currentFloorDoors.length === 0 ? (
										<span className="digitizer-tree-empty">
											Chưa gắn cửa nào (dùng công cụ [D])
										</span>
									) : (
										currentFloorDoors.map((dr, idx) => {
											const isSelected = selectedDoorId === dr.id;
											return (
												<div
													key={dr.id}
													className={`digitizer-tree-item-btn ${isSelected ? "is-selected" : ""}`}
												>
													<button
														type="button"
														className="digitizer-tree-item-select"
														onClick={() => {
															selectEntity("DOOR", dr.id);
															setActiveTool("SELECT");
															setActiveInspectorTab("inspector");
														}}
													>
														<DoorOpen
															size={12}
															color={isSelected ? "#f97316" : "#10b981"}
														/>
														<span className="digitizer-tree-item-title">
															Cửa #{idx + 1} ({dr.widthMeters || 1.2}m)
														</span>
													</button>
													<button
														type="button"
														className="digitizer-tree-delete-btn"
														onClick={() => deleteDoorById(dr.id)}
														title="Xóa cửa này"
														aria-label={`Xóa cửa ${idx + 1}`}
													>
														<Trash2 size={12} />
													</button>
												</div>
											);
										})
									)}
								</div>
							</div>

							{/* Scene Tree: Unified Wall Graph Polylines */}
							<div className="digitizer-tree-group">
								<div className="digitizer-tree-header">
									<span>Mạng tường</span>
									<span className="digitizer-inspector-badge">
										{currentFloorWalls.length}
									</span>
								</div>

								<div className="digitizer-tree-list">
									{currentFloorWalls.length === 0 ? (
										<span className="digitizer-tree-empty">
											Chưa có đoạn tường nào
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
															selectEntity("WALL", w.id);
															setActiveTool("SELECT");
															setActiveInspectorTab("inspector");
														}}
													>
														<Ruler
															size={12}
															color={isSelected ? "#f59e0b" : "#cbd5e1"}
														/>
														<span className="digitizer-tree-item-title">
															Tường #{idx + 1} ({lenMeters.toFixed(1)} m)
														</span>
													</button>
													<button
														type="button"
														className="digitizer-tree-delete-btn"
														onClick={() => deleteWallById(w.id)}
														title="Xóa đoạn tường này"
														aria-label={`Xóa tường ${idx + 1}`}
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
											const outdoor = isNodeOutdoor(
												store.buildingBoundaries,
												nd,
											);
											const typeMeta = outdoor
												? NODE_DISPLAY_META.OUTDOOR
												: NODE_DISPLAY_META[nd.type] || {
														label: nd.type,
														color: "#94a3b8",
													};
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
															selectEntity("NODE", nd.id);
															setActiveTool("SELECT");
															setActiveInspectorTab("inspector");
														}}
													>
														<span
															className="digitizer-node-dot"
															style={{
																background: typeMeta.color,
															}}
														/>
														<span className="digitizer-tree-item-title">
															{typeMeta.label}{" "}
															{nd.portalGroupId ? `(${nd.portalGroupId})` : ""}
														</span>
													</button>
													<button
														type="button"
														className="digitizer-tree-delete-btn"
														onClick={() => deleteNavNodeById(nd.id)}
														title="Xóa nút này"
														aria-label={`Xóa nút ${typeMeta.label}`}
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
												selectEntity("NODE", issue.targetId);
											else if (issue.type === "DOOR")
												selectEntity("DOOR", issue.targetId);
											else if (issue.type === "ROOM")
												selectEntity("FACE", issue.targetId);
											else selectEntity(null);
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
									<kbd className="digitizer-hud__kbd">W</kbd> Vẽ tường ·{" "}
									<kbd className="digitizer-hud__kbd">D</kbd> Gắn cửa ·{" "}
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
									Hoàn tất tường / đóng đa giác
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
									Vẽ hộp ngang 0° khi dùng [B]
								</span>
								<div>
									Giữ <kbd className="digitizer-hud__kbd">Shift</kbd> + Click điểm 2
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
