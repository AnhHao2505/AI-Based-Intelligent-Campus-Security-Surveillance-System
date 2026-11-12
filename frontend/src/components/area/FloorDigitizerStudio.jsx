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
	ChevronRight,
	DoorOpen,
	Play,
	Pause,
	Navigation,
} from "lucide-react";
import {
	DEFAULT_BUILDINGS_META,
	FLOOR_ELEVATION_STEP,
	FLOOR_SLAB_HEIGHT,
	loadGeometryStore,
	saveGeometryStore,
	createDefaultGeometryStore,
	auditNavigationConnectivity,
	buildVector3dLayers,
	buildRoute3dLayers,
	buildAllGraphEdges,
	buildFloorWallGraphModels,
	buildOrientedBoxRing,
	buildOrientedDoorGeometry,
	buildSliceWallSegment,
	collectWeldedDragExclusion,
	deduplicateFloorVertices,
	dragWeldedWallVertices,
	findBoundaryContainingPoint,
	generateNextPortalGroupId,
	isNodeOutdoor,
	isNodeVisibleOnFloor,
	isPointIndoorOnFloor,
	isPortalNode,
	lerpBearingDeg,
	matchesBuilding,
	mergeOrSplitNavNodeOnDrop,
	resolveBestSnapTarget,
	resolveCanonicalBuildingName,
	resolveNodeBuilding,
	resolveTestRouteClickContext,
	samplePointAlongRoute,
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
import {
	resolveToolSwitchDraftAction,
	resolveStudioSelectEntity,
} from "../../utils/studioToolDraftHelper";
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

/**
 * Memoized draggable MapLibre handle whose lng/lat props remain fixed to store coords during drag,
 * preventing react-map-gl Marker.setLngLat() from fighting native pointer drag on every frame.
 */
const StaticDragMarker = React.memo(
	function StaticDragMarker({
		longitude,
		latitude,
		className,
		title,
		onDragStart,
		onDrag,
		onDragEnd,
	}) {
		const handlersRef = useRef({ onDragStart, onDrag, onDragEnd });
		handlersRef.current = { onDragStart, onDrag, onDragEnd };

		const handleClick = useCallback((e) => {
			e?.originalEvent?.stopPropagation?.();
		}, []);
		const handleDragStart = useCallback((e) => {
			handlersRef.current.onDragStart?.(e);
		}, []);
		const handleDrag = useCallback((e) => {
			handlersRef.current.onDrag?.(e);
		}, []);
		const handleDragEnd = useCallback((e) => {
			handlersRef.current.onDragEnd?.(e);
		}, []);

		return (
			<Marker
				longitude={longitude}
				latitude={latitude}
				anchor="center"
				draggable
				onClick={handleClick}
				onDragStart={handleDragStart}
				onDrag={handleDrag}
				onDragEnd={handleDragEnd}
			>
				<div className={className} title={title} />
			</Marker>
		);
	},
	(prev, next) =>
		prev.longitude === next.longitude &&
		prev.latitude === next.latitude &&
		prev.className === next.className &&
		prev.title === next.title,
);

export default function FloorDigitizerStudio({ isOpen, onClose, onSaved }) {
	const mapRef = useRef(null);
	const fileInputRef = useRef(null);

	// Core Geometry Store & Dirty Tracking
	const [store, setStoreState] = useState(() => loadGeometryStore());
	const storeRef = useRef(store);
	storeRef.current = store;
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
	const [activeBuilding, setActiveBuilding] = useState("Tòa Alpha");
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
	const [liveVertexDrag, setLiveVertexDrag] = useState(null); // { kind: "WALL" | "DOOR_MOVE" | "DOOR_WIDTH", id, index, coords, detachSingle, widthMeters }
	const [isDraggingVertex, setIsDraggingVertex] = useState(false);
	const draggingVertexRef = useRef(null);
	const dragRafRef = useRef(null);
	const pendingDragFrameRef = useRef(null);
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
	const [portalGroupId, setPortalGroupId] = useState("HCM_STAIR_1");
	const [chainLastNodeId, setChainLastNodeId] = useState(null);

	// Live A* Route Tester & Real-Time Playback Simulation
	const [testRouteStart, setTestRouteStart] = useState(null);
	const [testRouteResult, setTestRouteResult] = useState(null);
	const [routePlaying, setRoutePlaying] = useState(false);
	const [routeProgress, setRouteProgress] = useState(0);
	const [followRouteHeading, setFollowRouteHeading] = useState(true);
	const [mapViewportBearing, setMapViewportBearing] = useState(0);
	const routeProgressRef = useRef(0);
	const routePlayingRef = useRef(false);
	routePlayingRef.current = routePlaying;
	const followRouteHeadingRef = useRef(true);
	followRouteHeadingRef.current = followRouteHeading;
	const camFollowRef = useRef(null);
	const [toastMsg, setToastMsg] = useState(null);
	const toastTimerRef = useRef(null);

	// Collapsible Inspector & Scene Tree sections
	const [overviewExpanded, setOverviewExpanded] = useState({
		rooms: false,
		doors: false,
		nodes: false,
	});
	const [treeExpanded, setTreeExpanded] = useState({
		faces: true,
		doors: false,
		portals: true,
	});

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

	const isAllBuildings = activeBuilding === "ALL";
	const isAllFloors = activeFloorOrder === "ALL";
	const isAllScope = isAllBuildings || isAllFloors;
	const scopeFloorNum = isAllFloors ? null : Number(activeFloorOrder) || 0;

	const activeCanonicalBuilding = useMemo(
		() =>
			isAllBuildings
				? "ALL"
				: resolveCanonicalBuildingName(activeBuilding) || activeBuilding,
		[isAllBuildings, activeBuilding],
	);

	const currentBuildingMeta = useMemo(
		() =>
			DEFAULT_BUILDINGS_META.find((b) => b.name === activeBuilding) ||
			DEFAULT_BUILDINGS_META[0],
		[activeBuilding],
	);

	const buildingFloorOptions = useMemo(() => {
		if (!isAllBuildings) return currentBuildingMeta.floors || [];
		const byOrder = new Map();
		for (const b of DEFAULT_BUILDINGS_META) {
			for (const fl of b.floors || []) {
				if (!byOrder.has(fl.floorOrder)) byOrder.set(fl.floorOrder, fl);
			}
		}
		return Array.from(byOrder.values()).sort(
			(a, b) => a.floorOrder - b.floorOrder,
		);
	}, [isAllBuildings, currentBuildingMeta]);

	const currentFloorMeta = useMemo(
		() =>
			currentBuildingMeta.floors.find(
				(f) => f.floorOrder === (scopeFloorNum ?? 0),
			) || currentBuildingMeta.floors[0],
		[currentBuildingMeta, scopeFloorNum],
	);

	// Helper: resolve 4-corner calibrated PNG bounds for any (buildingName, floorOrder)
	const resolveFloorPngCorners = useCallback(
		(bldgName, flOrder, flMeta) => {
			const cBldg = resolveCanonicalBuildingName(bldgName) || bldgName;
			const key = `${cBldg}__${flOrder}`;
			const meta = DEFAULT_BUILDINGS_META.find((b) => matchesBuilding(b.name, cBldg));
			const aliasKey = meta?.aliases
				?.map((a) => `${a}__${flOrder}`)
				?.find((k) => store.floorCalibrations?.[k]);
			const cal = store.floorCalibrations?.[key] || (aliasKey ? store.floorCalibrations?.[aliasKey] : null);
			if (cal?.corners && cal.corners.length === 4) {
				return cal.corners;
			}
			if (flMeta?.defaultBounds?.corners?.length === 4) {
				return flMeta.defaultBounds.corners;
			}
			const b = cal ||
				flMeta?.defaultBounds || {
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
		},
		[store.floorCalibrations],
	);

	// PNG 4-Corner Calibration Bounds: [NW, NE, SE, SW]
	const currentPngCorners = useMemo(
		() =>
			resolveFloorPngCorners(
				isAllBuildings ? currentBuildingMeta.name : activeBuilding,
				scopeFloorNum ?? 0,
				currentFloorMeta,
			),
		[
			resolveFloorPngCorners,
			isAllBuildings,
			currentBuildingMeta,
			activeBuilding,
			scopeFloorNum,
			currentFloorMeta,
		],
	);

	// Visible blueprint PNG overlays (supports "ALL" buildings & floors safely by showing base Floor G or selected floor)
	const visiblePngOverlays = useMemo(() => {
		if (!layerVisibility.png) return [];
		if (!isAllBuildings && !isAllFloors) {
			if (!currentFloorMeta?.pngUrl) return [];
			return [
				{
					id: `${activeCanonicalBuilding}__${scopeFloorNum}`,
					url: currentFloorMeta.pngUrl,
					corners: liveCorners && liveCorners.length === 4 ? liveCorners : currentPngCorners,
				},
			];
		}
		const targetFl = scopeFloorNum ?? 0;
		const targetBuildings = isAllBuildings
			? DEFAULT_BUILDINGS_META
			: [currentBuildingMeta];
		const list = [];
		for (const bMeta of targetBuildings) {
			const flMeta =
				bMeta.floors.find((f) => f.floorOrder === targetFl) || bMeta.floors[0];
			if (!flMeta?.pngUrl) continue;
			list.push({
				id: `${bMeta.id}__${flMeta.floorOrder}`,
				url: flMeta.pngUrl,
				corners: resolveFloorPngCorners(bMeta.name, flMeta.floorOrder, flMeta),
			});
		}
		return list;
	}, [
		layerVisibility.png,
		isAllBuildings,
		isAllFloors,
		activeCanonicalBuilding,
		scopeFloorNum,
		currentFloorMeta,
		currentBuildingMeta,
		currentPngCorners,
		liveCorners,
		resolveFloorPngCorners,
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

	// Effective store only patches real-time NavNode drag; Wall/Door handle drags render lightweight 2D overlays without triggering DCEL rebuilds per frame
	const effectiveStore = useMemo(() => {
		if (!liveNavNodeDrag) return store;
		return {
			...store,
			navNodes: (store.navNodes || []).map((n) =>
				n.id === liveNavNodeDrag.id
					? { ...n, coords: liveNavNodeDrag.coords }
					: n,
			),
		};
	}, [store, liveNavNodeDrag]);

	const matchesScope = useCallback(
		(bldg, flOrder) =>
			(isAllBuildings || matchesBuilding(bldg, activeBuilding)) &&
			(isAllFloors || Number(flOrder) === scopeFloorNum),
		[isAllBuildings, activeBuilding, isAllFloors, scopeFloorNum],
	);

	// Derived Mappedin Wall Graph floor models (Planar Face Extraction, Wall Segments, Dead-ends & Doors)
	const floorWallModels = useMemo(
		() => buildFloorWallGraphModels(effectiveStore),
		[effectiveStore],
	);

	const matchingFloorModels = useMemo(() => {
		if (!isAllBuildings && !isAllFloors) {
			const m = floorWallModels.get(
				`${activeCanonicalBuilding}__${scopeFloorNum}`,
			);
			return m ? [m] : [];
		}
		return Array.from(floorWallModels.values()).filter((m) =>
			matchesScope(m.building, m.floorOrder),
		);
	}, [
		floorWallModels,
		isAllBuildings,
		isAllFloors,
		activeCanonicalBuilding,
		scopeFloorNum,
		matchesScope,
	]);

	const currentFloorModel = useMemo(() => {
		if (!isAllBuildings && !isAllFloors) {
			return (
				floorWallModels.get(`${activeCanonicalBuilding}__${scopeFloorNum}`) ||
				null
			);
		}
		if (matchingFloorModels.length === 0) return null;
		return {
			building: activeBuilding,
			floorOrder: activeFloorOrder,
			faces: matchingFloorModels.flatMap((m) =>
				(m.faces || []).map((f) => ({
					...f,
					building: f.building || m.building,
					floorOrder: f.floorOrder ?? m.floorOrder,
				})),
			),
			wallSegments: matchingFloorModels.flatMap((m) => m.wallSegments || []),
			deadEndPoints: matchingFloorModels.flatMap((m) => m.deadEndPoints || []),
			exteriorRings: matchingFloorModels.flatMap((m) => m.exteriorRings || []),
		};
	}, [
		floorWallModels,
		isAllBuildings,
		isAllFloors,
		activeCanonicalBuilding,
		scopeFloorNum,
		matchingFloorModels,
		activeBuilding,
		activeFloorOrder,
	]);

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
					floorWallModels,
				),
			),
		[
			effectiveStore.navNodes,
			effectiveStore.buildingBoundaries,
			activeBuilding,
			activeFloorOrder,
			floorWallModels,
		],
	);

	const currentFloorWalls = useMemo(
		() =>
			(effectiveStore.walls || []).filter((w) =>
				matchesScope(w.building, w.floorOrder),
			),
		[effectiveStore.walls, matchesScope],
	);

	const currentFloorDoors = useMemo(
		() =>
			(effectiveStore.doors || []).filter((d) =>
				matchesScope(d.building, d.floorOrder),
			),
		[effectiveStore.doors, matchesScope],
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

	// Fly to active building (or full campus midpoint when "ALL" selected); paused while chase-cam follows active route heading
	useEffect(() => {
		if (!isOpen || !mapRef.current) return;
		if (routePlayingRef.current && followRouteHeadingRef.current) return;
		mapRef.current.flyTo({
			center: isAllBuildings ? [106.81065, 10.84095] : currentBuildingMeta.center,
			zoom: isAllBuildings ? 17.3 : 18.6,
			pitch: is3dPreviewRef.current ? 52 : 0,
			bearing: is3dPreviewRef.current ? -12 : 0,
			duration: 600,
		});
	}, [activeBuilding, isAllBuildings, isOpen, currentBuildingMeta]);

	const previewWalls = useMemo(() => {
		if (liveVertexDrag?.kind === "WALL") {
			return dragWeldedWallVertices(currentFloorWalls, {
				wallId: liveVertexDrag.id,
				vertexIndex: liveVertexDrag.index,
				newCoord: liveVertexDrag.coords,
				detachSingle: Boolean(liveVertexDrag.detachSingle),
			});
		}
		return currentFloorWalls;
	}, [currentFloorWalls, liveVertexDrag]);

	const previewDoors = useMemo(() => {
		if (liveVertexDrag?.kind === "DOOR_MOVE") {
			return currentFloorDoors.map((d) =>
				d.id === liveVertexDrag.id ? { ...d, coords: liveVertexDrag.coords } : d,
			);
		}
		if (liveVertexDrag?.kind === "DOOR_WIDTH") {
			return currentFloorDoors.map((d) =>
				d.id === liveVertexDrag.id
					? { ...d, widthMeters: liveVertexDrag.widthMeters }
					: d,
			);
		}
		return currentFloorDoors;
	}, [currentFloorDoors, liveVertexDrag]);

	// Cancel pending drag rAF on unmount
	useEffect(() => {
		return () => {
			if (dragRafRef.current != null) {
				cancelAnimationFrame(dragRafRef.current);
				dragRafRef.current = null;
			}
		};
	}, []);

	// Clear stale floor/building selections ONLY when switching building or floor (keep auto-committed drafts intact across tool switches)
	const activeToolRef = useRef(activeTool);
	activeToolRef.current = activeTool;
	useEffect(() => {
		setDraftPoints([]);
		setChainLastNodeId(null);
		selectEntity(null);
		setLiveNavNodeDrag(null);
		liveNavNodeDragRef.current = null;
		setLiveVertexDrag(null);
		draggingVertexRef.current = null;
		setIsDraggingVertex(false);
		if (activeToolRef.current !== "TEST_ROUTE") {
			setTestRouteStart(null);
		}
	}, [activeBuilding, activeFloorOrder, selectEntity]);

	// Tool switch transient cleanup without discarding committed walls or in-flight auto-commit selection
	useEffect(() => {
		setChainLastNodeId(null);
		setLiveVertexDrag(null);
		draggingVertexRef.current = null;
		setIsDraggingVertex(false);
		if (activeTool !== "TEST_ROUTE") {
			setTestRouteStart(null);
		}
	}, [activeTool]);

	const playbackSample = useMemo(() => {
		if (!testRouteResult?.geojson) return null;
		return samplePointAlongRoute(testRouteResult, routeProgress);
	}, [testRouteResult, routeProgress]);

	// Reset playback whenever computed route changes or is cleared
	useEffect(() => {
		setRoutePlaying(false);
		setRouteProgress(0);
		routeProgressRef.current = 0;
		camFollowRef.current = null;
	}, [testRouteResult]);

	// Align camera immediately when user toggles follow-heading ON during playback
	useEffect(() => {
		if (!followRouteHeading || !routePlaying || !mapRef.current) {
			if (!followRouteHeading) camFollowRef.current = null;
			return;
		}
		const map = mapRef.current;
		map.stop?.();
		const c = map.getCenter?.();
		const sample = samplePointAlongRoute(testRouteResult, routeProgressRef.current);
		camFollowRef.current = {
			lng: c ? c.lng : sample?.coords?.[0] ?? 106.81065,
			lat: c ? c.lat : sample?.coords?.[1] ?? 10.84095,
			bearing: map.getBearing?.() ?? 0,
			zoom: Math.max(map.getZoom?.() ?? 18.6, 19.3),
			lastNow: null,
		};
	}, [followRouteHeading, routePlaying, testRouteResult]);

	// Real-time RAF pedestrian playback along computed A* path with smooth heading chase-cam
	useEffect(() => {
		if (!routePlaying || !testRouteResult?.geojson) return;
		const totalDist = testRouteResult.totalDistanceMeters || 50;
		const floorSteps = testRouteResult.floorsClimbed || 0;
		const durationMs = Math.max(
			3800,
			Math.min(14000, totalDist * 55 + floorSteps * 1400),
		);
		const startProgress =
			routeProgressRef.current >= 0.995 ? 0 : routeProgressRef.current;
		if (startProgress === 0) {
			setRouteProgress(0);
			routeProgressRef.current = 0;
		}
		if (followRouteHeadingRef.current && mapRef.current) {
			const map = mapRef.current;
			map.stop?.();
			const c = map.getCenter?.();
			const initSample = samplePointAlongRoute(testRouteResult, startProgress);
			camFollowRef.current = {
				lng: c ? c.lng : initSample?.coords?.[0] ?? 106.81065,
				lat: c ? c.lat : initSample?.coords?.[1] ?? 10.84095,
				bearing: map.getBearing?.() ?? initSample?.bearing ?? 0,
				zoom: Math.max(map.getZoom?.() ?? 18.6, 19.3),
				lastNow: null,
			};
		}

		let startTime = null;
		let rafId = null;

		const tick = (now) => {
			if (startTime === null) startTime = now;
			const elapsed = now - startTime;
			const nextT = Math.min(1, startProgress + elapsed / durationMs);
			routeProgressRef.current = nextT;
			setRouteProgress(nextT);

			if (followRouteHeadingRef.current && mapRef.current) {
				const sample = samplePointAlongRoute(testRouteResult, nextT);
				if (sample) {
					const map = mapRef.current;
					if (!camFollowRef.current) {
						const c = map.getCenter?.();
						camFollowRef.current = {
							lng: c ? c.lng : sample.coords[0],
							lat: c ? c.lat : sample.coords[1],
							bearing: map.getBearing?.() ?? sample.bearing ?? 0,
							zoom: Math.max(map.getZoom?.() ?? 18.6, 19.3),
							lastNow: now,
						};
					}
					const cam = camFollowRef.current;
					const dt =
						cam.lastNow != null ? Math.min(80, Math.max(8, now - cam.lastNow)) : 16;
					cam.lastNow = now;
					const alphaPos = 1 - Math.exp(-dt / 150);
					const alphaRot = 1 - Math.exp(-dt / 220);
					const alphaZoom = 1 - Math.exp(-dt / 320);

					cam.lng += (sample.coords[0] - cam.lng) * alphaPos;
					cam.lat += (sample.coords[1] - cam.lat) * alphaPos;
					cam.bearing = lerpBearingDeg(cam.bearing, sample.bearing, alphaRot);
					const desiredZoom = Math.max(map.getZoom?.() ?? 18.6, 19.3);
					cam.zoom += (desiredZoom - cam.zoom) * alphaZoom;

					map.jumpTo({
						center: [cam.lng, cam.lat],
						bearing: cam.bearing,
						pitch: is3dPreviewRef.current ? 58 : 0,
						zoom: cam.zoom,
					});
				}
			}

			if (nextT < 1) {
				rafId = requestAnimationFrame(tick);
			} else {
				setRoutePlaying(false);
				if (mapRef.current) {
					setMapViewportBearing(Math.round(mapRef.current.getBearing?.() ?? 0));
				}
			}
		};
		rafId = requestAnimationFrame(tick);
		return () => {
			if (rafId !== null) cancelAnimationFrame(rafId);
		};
	}, [routePlaying, testRouteResult]);

	// Automatically follow active building/floor during live playback when scoped to single floor
	useEffect(() => {
		if (!routePlaying || !playbackSample) return;
		if (
			!isAllBuildings &&
			playbackSample.building &&
			playbackSample.building !== "OUTDOOR" &&
			!matchesBuilding(playbackSample.building, activeBuilding)
		) {
			const cBldg =
				resolveCanonicalBuildingName(playbackSample.building) ||
				playbackSample.building;
			if (DEFAULT_BUILDINGS_META.some((b) => b.name === cBldg)) {
				setActiveBuilding(cBldg);
			}
		}
		if (
			!isAllFloors &&
			playbackSample.floorOrder != null &&
			Number(activeFloorOrder) !== Number(playbackSample.floorOrder)
		) {
			setActiveFloorOrder(Number(playbackSample.floorOrder));
		}
	}, [
		routePlaying,
		playbackSample,
		isAllBuildings,
		isAllFloors,
		activeBuilding,
		activeFloorOrder,
	]);

	// Keep default portalGroupId fresh & collision-free when switching building or STAIRS vs ELEVATOR
	useEffect(() => {
		if (activeNodeType !== "STAIRS" && activeNodeType !== "ELEVATOR") return;
		const st = storeRef.current;
		const targetBldg =
			activeBuilding === "ALL" ? DEFAULT_BUILDINGS_META[0].name : activeBuilding;
		setPortalGroupId(
			generateNextPortalGroupId(
				st?.navNodes || [],
				st?.buildingBoundaries || [],
				targetBldg,
				activeNodeType,
			),
		);
	}, [activeBuilding, activeNodeType]);

	// Collect vertices & segments strictly from currently visible Wall Graph & Nav Graph layers in scope
	const existingGeometryForSnapping = useMemo(() => {
		const vertices = [];
		const segments = [];
		const wallSegments = [];
		const boundaries = store.buildingBoundaries || [];

		if (layerVisibility.walls) {
			for (const w of store.walls || []) {
				if (
					matchesScope(w.building, w.floorOrder) &&
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
				if (matchesScope(d.building, d.floorOrder) && Array.isArray(d.coords)) {
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
					isNodeVisibleOnFloor(
						boundaries,
						n,
						activeBuilding,
						activeFloorOrder,
						floorWallModels,
					)
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
				!isAllFloors &&
				activeTool === "NAV_GRAPH" &&
				(activeNodeType === "STAIRS" || activeNodeType === "ELEVATOR")
			) {
				for (const n of store.navNodes || []) {
					if (!isPortalNode(n) || n.type !== activeNodeType) continue;
					if (Number(n.floorOrder) === scopeFloorNum) continue;
					const nb =
						resolveNodeBuilding(boundaries, n, floorWallModels) ||
						resolveCanonicalBuildingName(n.building) ||
						n.building;
					if (!isAllBuildings && !matchesBuilding(nb, activeBuilding)) continue;
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
		floorWallModels,
		matchesScope,
		isAllBuildings,
		isAllFloors,
		scopeFloorNum,
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
	// Accepts legacy string excludeOwnerId OR { excludeOwnerId, excludeOwnerIds, excludeCoord, excludeCoordRadiusMeters }.
	const resolveSnappedCoord = useCallback(
		(rawLngLat, excludeOpts = null) => {
			if (
				altKeyDown ||
				activeTool === "CALIBRATE_PNG" ||
				activeTool === "TEST_ROUTE"
			) {
				return null;
			}
			const normalizedExclude =
				excludeOpts && typeof excludeOpts === "object"
					? excludeOpts
					: { excludeOwnerId: excludeOpts };
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
				excludeOwnerId: normalizedExclude.excludeOwnerId || null,
				excludeOwnerIds: normalizedExclude.excludeOwnerIds || null,
				excludeCoord: normalizedExclude.excludeCoord || null,
				excludeCoordRadiusMeters:
					normalizedExclude.excludeCoordRadiusMeters ?? 0.04,
				altKeyDown,
			});
		},
		[altKeyDown, activeTool, existingGeometryForSnapping],
	);

	// Assign Role ("ROOM" | "CORRIDOR" | "VOID") to an extracted Planar Face
	const assignFaceRole = useCallback(
		(face, nextRole) => {
			if (!face || !nextRole) return;
			const targetBldg =
				face.building || (isAllBuildings ? currentBuildingMeta.name : activeBuilding);
			const targetFl = face.floorOrder ?? scopeFloorNum ?? 0;
			const seed = face.seedCoord || getPolygonInteriorPoint(face.ring, face.holes);
			setStore((prev) => {
				const filtered = (prev.faceAssignments || []).filter((fa) => {
					if (
						!matchesBuilding(fa.building, targetBldg) ||
						Number(fa.floorOrder) !== Number(targetFl) ||
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
									building: targetBldg,
									floorOrder: Number(targetFl),
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
					? "Phòng"
					: nextRole === "VOID"
						? "Giếng trời / Sân"
						: "Hành lang";
			showToast(`Đã đổi thành ${roleLabel}.`);
		},
		[
			isAllBuildings,
			currentBuildingMeta,
			activeBuilding,
			scopeFloorNum,
			setStore,
			showToast,
		],
	);

	// Commit Wall Graph Polyline Draft (ROOM_POLY [P] or WALL_DRAW [W])
	const finishPolygonDraft = useCallback(
		(closeOverride = null) => {
			if (isAllScope) {
				showToast("Chọn 1 tòa và 1 tầng cụ thể để vẽ tường.");
				setDraftPoints([]);
				return;
			}
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
						floorOrder: scopeFloorNum,
						line,
					},
				];
				const nextAssignments = [...(prev.faceAssignments || [])];
				if (activeTool === "ROOM_POLY" && shouldClose) {
					nextAssignments.push({
						id: `fa-${Date.now()}`,
						building: activeBuilding,
						floorOrder: scopeFloorNum,
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
				showToast("Đã tạo phòng.");
			} else if (shouldClose) {
				showToast("Đã khép kín vùng.");
			} else {
				showToast("Đã thêm tường.");
			}
			setDraftPoints([]);
		},
		[
			isAllScope,
			draftPoints,
			activeTool,
			activeBuilding,
			scopeFloorNum,
			selectEntity,
			setStore,
			showToast,
		],
	);

	// Switch CAD tool safely: auto-commits multi-point WALL_DRAW (>=2 pts) / ROOM_POLY (>=3 pts) instead of wiping draft
	const switchTool = useCallback(
		(nextTool) => {
			const decision = resolveToolSwitchDraftAction(
				activeTool,
				draftPoints,
				nextTool,
			);
			if (decision.action === "NOOP") return;
			if (decision.shouldCommit) {
				finishPolygonDraft(decision.closeOverride);
			} else if (decision.clearDraft) {
				setDraftPoints([]);
			}
			setActiveTool(nextTool);
		},
		[activeTool, draftPoints, finishPolygonDraft],
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
				if (!fa.seedCoord) return true;
				return !targets.some(
					(f) =>
						matchesBuilding(
							fa.building,
							f.building || (isAllBuildings ? fa.building : activeBuilding),
						) &&
						Number(fa.floorOrder) === Number(f.floorOrder ?? scopeFloorNum) &&
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
		showToast(`Đã gỡ vai trò ${count} vùng.`);
	}, [
		selectedRoomIds,
		currentFloorFaces,
		isAllBuildings,
		activeBuilding,
		scopeFloorNum,
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
				title: "Thay đổi chưa lưu",
				message: "Thay đổi chưa lưu. Vẫn thoát?",
				confirmLabel: "Thoát",
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
		a.download = `campus_indoor_3d_${(isAllBuildings ? "all" : activeBuilding).toLowerCase().replace(/\s+/g, "_")}.json`;
		a.click();
		URL.revokeObjectURL(url);
		showToast("Đã xuất file JSON.");
		setMoreMenuOpen(false);
	}, [store, isAllBuildings, activeBuilding, showToast]);

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
						showToast("Đã nhập dữ liệu JSON.");
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
		if (isAllScope) {
			showToast("Chọn 1 tòa và 1 tầng cụ thể để sao chép.");
			return;
		}
		const nextOrder =
			((scopeFloorNum ?? 0) + 1) % currentBuildingMeta.floors.length;
		const srcWalls = (store.walls || []).filter(
			(w) =>
				matchesBuilding(w.building, activeBuilding) &&
				Number(w.floorOrder) === scopeFloorNum,
		);
		const srcAssigns = (store.faceAssignments || []).filter(
			(fa) =>
				matchesBuilding(fa.building, activeBuilding) &&
				Number(fa.floorOrder) === scopeFloorNum,
		);
		const srcDoors = (store.doors || []).filter(
			(d) =>
				matchesBuilding(d.building, activeBuilding) &&
				Number(d.floorOrder) === scopeFloorNum,
		);
		if (srcWalls.length === 0) {
			showToast("Tầng này chưa có tường.");
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
		showToast(`Đã sao chép ${clonedWalls.length} tường lên tầng kế tiếp.`);
	}, [
		isAllScope,
		store.walls,
		store.faceAssignments,
		store.doors,
		activeBuilding,
		scopeFloorNum,
		currentBuildingMeta,
		setStore,
		showToast,
	]);

	// In-App Clear all walls, face roles & doors on active floor
	const handleClearCurrentFloorRooms = useCallback(() => {
		if (isAllScope) {
			showToast("Chọn 1 tòa và 1 tầng cụ thể để xóa tầng.");
			return;
		}
		setConfirmModal({
			isOpen: true,
			title: "Xóa toàn bộ tầng này?",
			message: `Xóa tất cả tường, vùng và cửa tại ${currentFloorMeta.name} (${activeBuilding})?`,
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
									Number(w.floorOrder) === scopeFloorNum
								),
						),
						faceAssignments: (prev.faceAssignments || []).filter(
							(fa) =>
								!(
									matchesBuilding(fa.building, activeBuilding) &&
									Number(fa.floorOrder) === scopeFloorNum
								),
						),
						doors: (prev.doors || []).filter(
							(d) =>
								!(
									matchesBuilding(d.building, activeBuilding) &&
									Number(d.floorOrder) === scopeFloorNum
								),
						),
					}),
				);
				selectEntity(null);
				setConfirmModal((m) => ({ ...m, isOpen: false }));
				showToast("Đã làm trống tầng hiện tại.");
			},
		});
	}, [
		isAllScope,
		activeBuilding,
		scopeFloorNum,
		currentFloorMeta,
		selectEntity,
		setStore,
		showToast,
	]);

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
					switchTool("SELECT");
				} else if (k === "b") {
					switchTool("ROOM_BOX");
				} else if (k === "p") {
					switchTool("ROOM_POLY");
				} else if (k === "s") {
					switchTool("ROOM_SLICE");
				} else if (k === "w" || k === "o") {
					switchTool("WALL_DRAW");
				} else if (k === "d") {
					switchTool("DOOR");
				} else if (k === "n") {
					switchTool("NAV_GRAPH");
				} else if (k === "t") {
					switchTool("TEST_ROUTE");
				} else if (k === "c") {
					switchTool("CALIBRATE_PNG");
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
		switchTool,
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
							floorWallModels,
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
			floorWallModels,
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

			// Do not let Map mousemove clobber snap state while dragging a Marker (pointerEvents="none" bubbles through MapLibre Marker)
			if (draggingVertexRef.current) {
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

	// Double-click completes WALL_DRAW (>=2 pts) or closes ROOM_POLY (>=3 pts) without zooming the viewport
	const handleMapDblClick = useCallback(
		(e) => {
			if (
				(activeTool === "WALL_DRAW" && draftPoints.length >= 2) ||
				(activeTool === "ROOM_POLY" && draftPoints.length >= 3)
			) {
				e?.preventDefault?.();
				finishPolygonDraft(activeTool === "ROOM_POLY");
			}
		},
		[activeTool, draftPoints.length, finishPolygonDraft],
	);

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
				Number(e.lngLat.lng.toFixed(7)),
				Number(e.lngLat.lat.toFixed(7)),
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
				const mapInst = mapRef.current?.getMap?.();
				const projectFn = mapInst?.project ? (c) => mapInst.project(c) : null;
				const picked = resolveStudioSelectEntity({
					rawLngLat: raw,
					ptLngLat: pt,
					features: e.features || [],
					isMulti,
					layerVisibility,
					layerLocks,
					walls: currentFloorWalls,
					doors: currentFloorDoors,
					faces: currentFloorFaces,
					projectFn,
				});

				if (picked?.type === "NODE") {
					selectEntity("NODE", picked.id);
					setActiveInspectorTab("inspector");
					return;
				}
				if (picked?.type === "DOOR") {
					selectEntity("DOOR", picked.id);
					setActiveInspectorTab("inspector");
					return;
				}
				if (picked?.type === "WALL") {
					selectEntity("WALL", picked.id);
					setActiveInspectorTab("inspector");
					return;
				}
				if (picked?.type === "FACE") {
					if (isMulti) {
						setSelectedRoomIds((prev) =>
							prev.includes(picked.id)
								? prev.filter((id) => id !== picked.id)
								: [...prev, picked.id],
						);
						setSelectedWallId(null);
						setSelectedDoorId(null);
						setSelectedNavNodeId(null);
					} else {
						selectEntity("FACE", picked.id);
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
				if (isAllScope) {
					showToast("Chọn 1 tòa và 1 tầng cụ thể để vẽ phòng.");
					setDraftPoints([]);
					return;
				}
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
						showToast("Khung phòng quá hẹp.");
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
									Number(fa.floorOrder) === scopeFloorNum &&
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
									floorOrder: scopeFloorNum,
									line: quadRing,
								},
							],
							faceAssignments: [
								...filteredAssignments,
								{
									id: `fa-box-${Date.now()}`,
									building: activeBuilding,
									floorOrder: scopeFloorNum,
									seedCoord: boxSeed,
									role: "ROOM",
								},
							],
						});
					});
					selectEntity("WALL", newWallId);
					showToast(axisAligned ? "Đã tạo phòng (0°)." : "Đã tạo phòng.");
					setDraftPoints([]);
				}
				return;
			}

			// 3. Mappedin-style Face Slice Macro -> inserts dividing Wall dividing closed face into 2 sub-faces
			if (activeTool === "ROOM_SLICE") {
				if (isAllScope) {
					showToast("Chọn 1 tòa và 1 tầng cụ thể để chia vùng.");
					setDraftPoints([]);
					return;
				}
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
										Number(fa.floorOrder) === scopeFloorNum &&
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
												floorOrder: scopeFloorNum,
												seedCoord: sliceRes.seedA,
												role: targetFace.role,
											},
											{
												id: `fa-slice-b-${ts}`,
												building: activeBuilding,
												floorOrder: scopeFloorNum,
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
										floorOrder: scopeFloorNum,
										line: sliceRes.line,
									},
								],
								faceAssignments: [...retainedAssignments, ...extraAssigns],
							});
						});
						selectEntity("WALL", sliceWallId);
						showToast("Đã chia đôi vùng.");
					} else {
						showToast("Đường cắt chưa đi qua vùng nào.");
					}
					setDraftPoints([]);
				}
				return;
			}

			// 4. Unified Wall Graph Draw [W] (closed loop or multi-segment partition) & Polygon Room Macro [P]
			if (activeTool === "WALL_DRAW" || activeTool === "ROOM_POLY") {
				if (isAllScope) {
					showToast("Chọn 1 tòa và 1 tầng cụ thể để vẽ tường.");
					setDraftPoints([]);
					return;
				}
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

			// 5. Wall-mounted Door Tool [D]: Snaps strictly onto Wall Graph segments (carves passage)
			if (activeTool === "DOOR") {
				if (isAllScope) {
					showToast("Chọn 1 tòa và 1 tầng cụ thể để gắn cửa.");
					return;
				}
				if (layerLocks.doors) {
					showToast("Lớp Cửa đang bị khóa.");
					return;
				}
				if (!snapResult) {
					showToast("Click lên tường để gắn cửa.");
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
								floorOrder: scopeFloorNum,
								coords: snapResult.coord,
								widthMeters: 1.2,
							},
						],
					}),
				);
				selectEntity("DOOR", newDoorId);
				showToast("Đã gắn Cửa 1.2m.");
				return;
			}

			// 6. Navigation Graph Tool (Unified NODE + Edge & STAIRS/ELEVATOR Portals)
			if (activeTool === "NAV_GRAPH") {
				if (isAllFloors) {
					showToast("Chọn 1 tầng cụ thể để vẽ đồ thị.");
					return;
				}
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
						scopeFloorNum,
						floorWallModels,
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

				const chainPrevNode = chainLastNodeId
					? (store.navNodes || []).find((n) => n.id === chainLastNodeId) || null
					: null;

				if (existingNode) {
					const skipPortalToPortal =
						isPortalNode(chainPrevNode) && isPortalNode(existingNode);
					if (
						chainLastNodeId &&
						chainLastNodeId !== existingNode.id &&
						!skipPortalToPortal
					) {
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
				const containedFloor0 =
					scopeFloorNum === 0
						? findBoundaryContainingPoint(floor0Bounds, pt, 0)
						: null;
				const targetBldgForNode =
					containedFloor0?.building ||
					(isAllBuildings ? currentBuildingMeta.name : activeBuilding);
				const isOutdoorPt =
					!isPortal &&
					scopeFloorNum === 0 &&
					floor0Bounds.length > 0 &&
					!isPointIndoorOnFloor(store, targetBldgForNode, 0, pt, floorWallModels);

				// Auto-inherit portalGroupId strictly for same-type Ghost Portal; otherwise prevent shaft collisions
				let resolvedPortalGroupId =
					portalGroupId.trim().toUpperCase() ||
					generateNextPortalGroupId(
						store.navNodes || [],
						boundaries,
						targetBldgForNode,
						activeNodeType,
					);
				let snappedGhostPortal = false;
				if (isPortal) {
					const ghostMatch = (store.navNodes || []).find(
						(n) =>
							isPortalNode(n) &&
							n.type === activeNodeType &&
							Number(n.floorOrder) !== scopeFloorNum &&
							matchesBuilding(
								resolveNodeBuilding(boundaries, n, floorWallModels) || n.building,
								targetBldgForNode,
							) &&
							Math.hypot(n.coords[0] - pt[0], n.coords[1] - pt[1]) < 0.000025,
					);
					if (ghostMatch?.portalGroupId) {
						resolvedPortalGroupId = ghostMatch.portalGroupId.trim().toUpperCase();
						snappedGhostPortal = true;
					} else {
						const groupAlreadyUsedOnSameFloorOrElsewhere = (
							store.navNodes || []
						).some((n) => {
							if (!isPortalNode(n) || n.type !== activeNodeType) return false;
							if (
								(n.portalGroupId || "").trim().toUpperCase() !==
								resolvedPortalGroupId
							) {
								return false;
							}
							const nb =
								resolveNodeBuilding(boundaries, n, floorWallModels) ||
								n.building;
							if (!matchesBuilding(nb, targetBldgForNode)) return false;
							if (Number(n.floorOrder) === scopeFloorNum) return true;
							return Math.hypot(n.coords[0] - pt[0], n.coords[1] - pt[1]) >= 0.000025;
						});
						if (groupAlreadyUsedOnSameFloorOrElsewhere) {
							resolvedPortalGroupId = generateNextPortalGroupId(
								store.navNodes || [],
								boundaries,
								targetBldgForNode,
								activeNodeType,
							);
						}
					}
				}

				const newNodeId = `node-${Date.now()}`;
				const newNode = {
					id: newNodeId,
					type: activeNodeType,
					building: isOutdoorPt ? "OUTDOOR" : targetBldgForNode,
					floorOrder: scopeFloorNum,
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

					// Never auto-wire horizontal chain edge between two STAIRS/ELEVATOR portals
					const canChainFromPrev =
						Boolean(chainLastNodeId) &&
						!(isPortal && isPortalNode(chainPrevNode));
					if (canChainFromPrev) {
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

				if (isPortal) {
					setChainLastNodeId(null);
					if (!snappedGhostPortal) {
						setPortalGroupId(
							generateNextPortalGroupId(
								[...(store.navNodes || []), newNode],
								boundaries,
								targetBldgForNode,
								activeNodeType,
							),
						);
					}
				} else {
					setChainLastNodeId(newNodeId);
				}
				return;
			}

			// 7. Live A* Route Tester (works cleanly across single floor OR "ALL" scope)
			if (activeTool === "TEST_ROUTE") {
				const mapInst = mapRef.current?.getMap?.();
				let hitBuilding = null;
				let hitFloor = null;
				if (mapInst && e?.point) {
					const activeCandidateLayers = [
						"studio-rooms-extrusion",
						"studio-slab-layer",
						"studio-faces-fill",
					].filter((id) => Boolean(mapInst.getLayer?.(id)));
					if (activeCandidateLayers.length > 0) {
						const hits = mapInst.queryRenderedFeatures(e.point, {
							layers: activeCandidateLayers,
						});
						const props = hits?.[0]?.properties;
						if (props?.building) hitBuilding = props.building;
						if (props?.floorOrder != null) hitFloor = Number(props.floorOrder);
					}
					if (!hitBuilding && is3dPreview) {
						const ghostCandidateLayers = [
							"studio-ghost-rooms-layer",
							"studio-ghost-slabs-layer",
						].filter((id) => Boolean(mapInst.getLayer?.(id)));
						if (ghostCandidateLayers.length > 0) {
							const gHits = mapInst.queryRenderedFeatures(e.point, {
								layers: ghostCandidateLayers,
							});
							const gProps = gHits?.[0]?.properties;
							if (gProps?.building) hitBuilding = gProps.building;
							if (gProps?.floorOrder != null) {
								hitFloor = Number(gProps.floorOrder);
							}
						}
					}
				}

				const clickCtx = resolveTestRouteClickContext({
					store,
					rawCoord: raw,
					hitBuilding,
					hitFloor,
					scopeFloorNum,
					activeBuilding,
					isAllBuildings,
					precomputedModels: floorWallModels,
				});
				const clickIsOutdoor = clickCtx.isOutdoor;
				const clickBuilding = clickCtx.building;
				const clickFloor = clickCtx.floorOrder;

				if (!testRouteStart) {
					setTestRouteStart({
						coords: raw,
						building: clickBuilding,
						floorOrder: clickFloor,
					});
					showToast(
						`Điểm đi: ${clickIsOutdoor ? "Ngoài sân" : `${clickBuilding} (T${clickFloor === 0 ? "G" : clickFloor})`}. Click điểm đến.`,
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
						endFloorOrder: clickFloor,
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
								"Không tìm thấy đường giữa 2 điểm này (kiểm tra [N] hoặc [D]).",
						);
					}
				}
			}
		},
		[
			activeTool,
			is3dPreview,
			isAllScope,
			isAllBuildings,
			isAllFloors,
			scopeFloorNum,
			currentBuildingMeta,
			layerLocks,
			layerVisibility,
			draftPoints,
			activeBuilding,
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

	const setDragPanEnabled = useCallback(
		(enabled) => {
			const mapInst = mapRef.current?.getMap?.();
			if (!mapInst?.dragPan) return;
			if (enabled) {
				if (!(activeTool === "SELECT" && shiftKeyDown)) {
					mapInst.dragPan.enable?.();
				}
			} else {
				mapInst.dragPan.disable?.();
			}
		},
		[activeTool, shiftKeyDown],
	);

	const scheduleVertexDragFrame = useCallback((nextDrag, nextSnap) => {
		pendingDragFrameRef.current = { nextDrag, nextSnap };
		if (dragRafRef.current != null) return;
		dragRafRef.current = requestAnimationFrame(() => {
			dragRafRef.current = null;
			const frame = pendingDragFrameRef.current;
			pendingDragFrameRef.current = null;
			if (!frame || !draggingVertexRef.current) return;
			setLiveVertexDrag(frame.nextDrag);
			if (frame.nextSnap) {
				setSnappedCoord(frame.nextSnap.coord);
				setSnapType(frame.nextSnap.type);
				setHoverCoord(frame.nextSnap.coord);
			} else {
				setSnappedCoord(null);
				setSnapType(null);
				setHoverCoord(frame.nextDrag?.coords || null);
			}
		});
	}, []);

	const handleVertexDragStart = useCallback(
		(kind, id, index, _lngLat, origEvt = null) => {
			if (!id) return;
			const detachSingle = Boolean(origEvt?.altKey || altKeyDown);
			const exclusion =
				kind === "WALL"
					? collectWeldedDragExclusion({
							walls: storeRef.current?.walls || [],
							wallId: id,
							vertexIndex: index,
							detachSingle,
						})
					: { origCoord: null, excludeOwnerIds: new Set([id]), excludeCoord: null };
			draggingVertexRef.current = { kind, id, index, detachSingle, exclusion };
			setIsDraggingVertex(true);
			setDragPanEnabled(false);
		},
		[altKeyDown, setDragPanEnabled],
	);

	// Live 60fps rAF vertex/endpoint dragging with welded exclusion & single Undo commit on dragEnd
	const handleVertexDragLive = useCallback(
		(kind, id, index, lngLat, origEvt = null) => {
			if (!id || !lngLat) return;
			const detachSingle = Boolean(origEvt?.altKey || altKeyDown);
			let activeDrag = draggingVertexRef.current;
			if (
				!activeDrag ||
				activeDrag.id !== id ||
				activeDrag.index !== index ||
				activeDrag.detachSingle !== detachSingle
			) {
				const exclusion =
					kind === "WALL"
						? collectWeldedDragExclusion({
								walls: storeRef.current?.walls || [],
								wallId: id,
								vertexIndex: index,
								detachSingle,
							})
						: { origCoord: null, excludeOwnerIds: new Set([id]), excludeCoord: null };
				activeDrag = { kind, id, index, detachSingle, exclusion };
				draggingVertexRef.current = activeDrag;
				setIsDraggingVertex(true);
				setDragPanEnabled(false);
			}
			const raw = [lngLat.lng, lngLat.lat];
			const snapResult = resolveSnappedCoord(raw, activeDrag.exclusion);
			const pt = snapResult ? snapResult.coord : raw;
			scheduleVertexDragFrame(
				{ kind, id, index, coords: pt, detachSingle },
				snapResult,
			);
		},
		[resolveSnappedCoord, altKeyDown, scheduleVertexDragFrame, setDragPanEnabled],
	);

	const handleVertexDragEnd = useCallback(
		(kind, id, index, lngLat, origEvt = null) => {
			if (dragRafRef.current != null) {
				cancelAnimationFrame(dragRafRef.current);
				dragRafRef.current = null;
			}
			pendingDragFrameRef.current = null;
			draggingVertexRef.current = null;
			setIsDraggingVertex(false);
			setDragPanEnabled(true);

			if (!id || !lngLat) {
				setLiveVertexDrag(null);
				setSnappedCoord(null);
				setSnapType(null);
				return;
			}
			const detachSingle = Boolean(origEvt?.altKey || altKeyDown);
			const exclusion =
				kind === "WALL"
					? collectWeldedDragExclusion({
							walls: storeRef.current?.walls || [],
							wallId: id,
							vertexIndex: index,
							detachSingle,
						})
					: { excludeOwnerId: id };
			const raw = [
				Number(lngLat.lng.toFixed(7)),
				Number(lngLat.lat.toFixed(7)),
			];
			const snapResult = resolveSnappedCoord(raw, exclusion);
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
		[resolveSnappedCoord, altKeyDown, setStore, setDragPanEnabled],
	);

	// Live 60fps Door Midpoint Slide & Jamb Width Resize along host wall segment
	const handleDoorDragStart = useCallback(
		(kind, doorId) => {
			draggingVertexRef.current = { kind, id: doorId };
			setIsDraggingVertex(true);
			setDragPanEnabled(false);
		},
		[setDragPanEnabled],
	);

	const handleDoorMoveDragLive = useCallback(
		(doorId, lngLat) => {
			if (!doorId || !lngLat) return;
			if (!draggingVertexRef.current) {
				draggingVertexRef.current = { kind: "DOOR_MOVE", id: doorId };
				setIsDraggingVertex(true);
				setDragPanEnabled(false);
			}
			const raw = [lngLat.lng, lngLat.lat];
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
			scheduleVertexDragFrame(
				{ kind: "DOOR_MOVE", id: doorId, coords: nextCoord },
				snap,
			);
		},
		[existingGeometryForSnapping.wallSegments, scheduleVertexDragFrame, setDragPanEnabled],
	);

	const handleDoorMoveDragEnd = useCallback(
		(doorId, lngLat) => {
			if (dragRafRef.current != null) {
				cancelAnimationFrame(dragRafRef.current);
				dragRafRef.current = null;
			}
			pendingDragFrameRef.current = null;
			draggingVertexRef.current = null;
			setIsDraggingVertex(false);
			setDragPanEnabled(true);
			setSnappedCoord(null);
			setSnapType(null);
			if (!doorId || !lngLat) {
				setLiveVertexDrag(null);
				return;
			}
			const raw = [
				Number(lngLat.lng.toFixed(7)),
				Number(lngLat.lat.toFixed(7)),
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
		[existingGeometryForSnapping.wallSegments, setStore, showToast, setDragPanEnabled],
	);

	const handleDoorJambDragLive = useCallback(
		(door, lngLat) => {
			if (!door || !lngLat) return;
			if (!draggingVertexRef.current) {
				draggingVertexRef.current = { kind: "DOOR_WIDTH", id: door.id };
				setIsDraggingVertex(true);
				setDragPanEnabled(false);
			}
			const raw = [lngLat.lng, lngLat.lat];
			const halfDist = approximateDistanceMeters(door.coords, raw);
			const nextW = Number(
				Math.max(0.5, Math.min(10.0, halfDist * 2)).toFixed(2),
			);
			scheduleVertexDragFrame(
				{ kind: "DOOR_WIDTH", id: door.id, widthMeters: nextW },
				null,
			);
		},
		[scheduleVertexDragFrame, setDragPanEnabled],
	);

	const handleDoorJambDragEnd = useCallback(
		(door, lngLat) => {
			if (dragRafRef.current != null) {
				cancelAnimationFrame(dragRafRef.current);
				dragRafRef.current = null;
			}
			pendingDragFrameRef.current = null;
			draggingVertexRef.current = null;
			setIsDraggingVertex(false);
			setDragPanEnabled(true);
			if (!door || !lngLat) {
				setLiveVertexDrag(null);
				return;
			}
			const raw = [
				Number(lngLat.lng.toFixed(7)),
				Number(lngLat.lat.toFixed(7)),
			];
			const halfDist = approximateDistanceMeters(door.coords, raw);
			const nextW = Number(
				Math.max(0.5, Math.min(10.0, halfDist * 2)).toFixed(2),
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
		[setStore, showToast, setDragPanEnabled],
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

	// Build 3D vector preview layers (only show lower ghost floors & context buildings in 3D Preview or ALL view)
	const vector3d = useMemo(
		() =>
			buildVector3dLayers({
				store: effectiveStore,
				selectedBuilding: activeBuilding,
				activeFloorOrder,
				viewRole: "STUDIO",
				showOtherBuildingsInFocus: is3dPreview || isAllBuildings,
			}),
		[effectiveStore, activeBuilding, activeFloorOrder, is3dPreview, isAllBuildings],
	);

	const vector3dSplit = useMemo(
		() => ({
			slabs: splitGhostActiveGeoJSON(vector3d.slabsGeoJSON),
			walls: splitGhostActiveGeoJSON(vector3d.wallsGeoJSON),
			rooms: splitGhostActiveGeoJSON(vector3d.roomsGeoJSON),
		}),
		[vector3d],
	);

	// 2D Unified Wall Polylines for crisp selection & 60fps drag preview (uses previewWalls)
	const studioWallLinesGeoJSON = useMemo(() => {
		const features = [];
		if (layerVisibility.walls) {
			for (const w of previewWalls) {
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
	}, [layerVisibility.walls, previewWalls, selectedWallId]);

	// 2D Extracted Planar Faces GeoJSON (ROOM / CORRIDOR / VOID) on active scope
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
					building: f.building || activeBuilding,
					floorOrder: f.floorOrder ?? scopeFloorNum ?? 0,
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
	}, [
		layerVisibility.faces,
		currentFloorFaces,
		activeBuilding,
		scopeFloorNum,
		selectedRoomIds,
	]);

	// Live connectivity audit for Door midpoint icons & Portal same-floor horizontal links
	const connectivityAudit = useMemo(
		() =>
			auditNavigationConnectivity({
				store: effectiveStore,
				building: activeBuilding,
				floorOrder: activeFloorOrder,
				precomputedModels: floorWallModels,
			}),
		[effectiveStore, activeBuilding, activeFloorOrder, floorWallModels],
	);

	// 2D Wall-Mounted Doors GeoJSON in scope (Oriented Rectangular Door Frame Polygon + Midpoint Node + Live Ghost Preview)
	const studioDoorsGeoJSON = useMemo(() => {
		if (!layerVisibility.doors) {
			return { type: "FeatureCollection", features: [] };
		}
		const features = [];

		for (const d of previewDoors) {
			if (!Array.isArray(d.coords)) continue;
			const doorBldg = resolveCanonicalBuildingName(d.building) || d.building;
			const flOrder = Number(d.floorOrder) || 0;
			const doorModel = floorWallModels.get(`${doorBldg}__${flOrder}`);
			const doorSegs =
				doorModel?.wallSegments || currentFloorModel?.wallSegments || [];
			const geom = buildOrientedDoorGeometry(d, doorSegs);
			if (!geom) continue;
			const isSel = d.id === selectedDoorId;
			const isConnected = Boolean(connectivityAudit.doorStatusById.get(d.id));

			// Oriented Rectangular Door Frame Box
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

			// Midpoint Graph Node Anchor
			features.push({
				type: "Feature",
				id: `${d.id}-mid`,
				properties: {
					id: d.id,
					featureKind: "DOOR_MIDPOINT",
					isConnected,
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
		if (activeTool === "DOOR" && snappedCoord && !isAllScope) {
			const wallSegs = currentFloorModel?.wallSegments || [];
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
						isConnected: false,
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
		previewDoors,
		floorWallModels,
		currentFloorModel,
		selectedDoorId,
		connectivityAudit,
		activeTool,
		snappedCoord,
		isAllScope,
	]);

	// 3D Hover Preview box while attaching Door [D] in Studio 3D mode
	const studioDoor3dPreviewGeoJSON = useMemo(() => {
		if (
			!is3dPreview ||
			!layerVisibility.doors ||
			activeTool !== "DOOR" ||
			!snappedCoord ||
			isAllScope
		) {
			return { type: "FeatureCollection", features: [] };
		}
		const wallSegs = currentFloorModel?.wallSegments || [];
		const ghostGeom = buildOrientedDoorGeometry(
			{ id: "door-3d-preview", coords: snappedCoord, widthMeters: 1.2 },
			wallSegs,
		);
		if (!ghostGeom) return { type: "FeatureCollection", features: [] };
		const floorBase = (Number(scopeFloorNum) || 0) * FLOOR_ELEVATION_STEP;
		return {
			type: "FeatureCollection",
			features: [
				{
					type: "Feature",
					id: "door-3d-preview-feat",
					properties: {
						baseHeight: floorBase + FLOOR_SLAB_HEIGHT,
						extrudeHeight:
							floorBase +
							FLOOR_SLAB_HEIGHT +
							(ghostGeom.isPerimeter ? 2.2 : 1.35),
					},
					geometry: {
						type: "Polygon",
						coordinates: [ghostGeom.boxPolygon],
					},
				},
			],
		};
	}, [
		is3dPreview,
		layerVisibility.doors,
		activeTool,
		snappedCoord,
		isAllScope,
		currentFloorModel,
		scopeFloorNum,
	]);

	// 2D Deduplicated Corner/Junction Dots & Open Dead-end Dots across active walls (renders at zoom >= 16.5 for Mappedin snap clarity)
	const studioVerticesGeoJSON = useMemo(() => {
		const features = [];
		if (layerVisibility.walls) {
			const uniqueVerts = deduplicateFloorVertices(previewWalls, {
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
	}, [layerVisibility.walls, previewWalls, currentFloorModel, selectedWallId]);

	// 3D Test Route Ribbons for Studio 3D Preview
	const studioRoute3dLayers = useMemo(
		() =>
			buildRoute3dLayers(testRouteResult, {
				selectedBuilding: activeBuilding,
				activeFloorOrder,
			}),
		[testRouteResult, activeBuilding, activeFloorOrder],
	);

	// Real-time 3D directional walker chevron synced to true floor elevation & travel bearing
	const playbackWalker3dGeoJSON = useMemo(() => {
		if (!playbackSample || (!routePlaying && routeProgress <= 0)) {
			return { type: "FeatureCollection", features: [] };
		}
		const [lng, lat] = playbackSample.coords;
		const rad = ((Number(playbackSample.bearing) || 0) * Math.PI) / 180;
		const cosB = Math.cos(rad);
		const sinB = Math.sin(rad);
		const cosLat = Math.max(0.2, Math.cos((lat * Math.PI) / 180));
		const metersPerDegLng = 111320 * cosLat;
		const metersPerDegLat = 111320;
		const toCoord = (fwdM, rightM) => {
			const eastM = fwdM * sinB + rightM * cosB;
			const northM = fwdM * cosB - rightM * sinB;
			return [
				Number((lng + eastM / metersPerDegLng).toFixed(7)),
				Number((lat + northM / metersPerDegLat).toFixed(7)),
			];
		};
		const ring = [
			toCoord(0.95, 0),
			toCoord(-0.65, -0.72),
			toCoord(-0.28, 0),
			toCoord(-0.65, 0.72),
			toCoord(0.95, 0),
		];
		return {
			type: "FeatureCollection",
			features: [
				{
					type: "Feature",
					properties: {
						baseHeight: playbackSample.elevationMeters,
						extrudeHeight: playbackSample.elevationMeters + 1.45,
						color: playbackSample.isStair ? "#a855f7" : "#06b6d4",
					},
					geometry: { type: "Polygon", coordinates: [ring] },
				},
			],
		};
	}, [playbackSample, routePlaying, routeProgress]);

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

	// Build Nav Graph GeoJSON for current scope (+ outdoor on Floor 0), with symbol portal layer, connected floor badges & ghost portals
	const navGraphLayers = useMemo(() => {
		const boundaries = effectiveStore.buildingBoundaries || [];
		const allEdges = buildAllGraphEdges(effectiveStore);
		const nodeMap = new Map(
			(effectiveStore.navNodes || []).map((n) => [n.id, n]),
		);
		const visibleNodes = (effectiveStore.navNodes || []).filter((n) =>
			isNodeVisibleOnFloor(
				boundaries,
				n,
				activeBuilding,
				activeFloorOrder,
				floorWallModels,
			),
		);
		const visibleIds = new Set(visibleNodes.map((n) => n.id));
		const edgeFeatures = [];

		for (const [uId, vId] of allEdges) {
			const u = nodeMap.get(uId);
			const v = nodeMap.get(vId);
			if (!u || !v) continue;
			const isVertical = u.floorOrder !== v.floorOrder;
			// Do NOT render cross-floor vertical edges as flat diagonal 2D lines unless in 3D preview
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

		// Map (canonicalBuilding__type__portalGroupId) -> sorted floor labels for 2D/3D badge display
		const portalFloorsByGroup = new Map();
		for (const n of effectiveStore.navNodes || []) {
			if (!isPortalNode(n) || !n.portalGroupId) continue;
			const nb =
				resolveCanonicalBuildingName(
					resolveNodeBuilding(boundaries, n, floorWallModels) || n.building,
				) || n.building;
			if (!isAllBuildings && !matchesBuilding(nb, activeBuilding)) continue;
			const gk = `${nb}__${n.type}__${n.portalGroupId.trim().toUpperCase()}`;
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
			const outdoor = isNodeOutdoor(boundaries, n, floorWallModels);
			const floorOrder = Number(n.floorOrder) || 0;
			const heightOffset = Number(
				(floorOrder * FLOOR_ELEVATION_STEP + FLOOR_SLAB_HEIGHT + 0.35).toFixed(2),
			);

			if (isPortalNode(n)) {
				const nb =
					resolveCanonicalBuildingName(
						resolveNodeBuilding(boundaries, n, floorWallModels) || n.building,
					) || n.building;
				const gk = `${nb}__${n.type}__${(n.portalGroupId || "").trim().toUpperCase()}`;
				const floorsSet = portalFloorsByGroup.get(gk);
				const sortedFl = floorsSet
					? Array.from(floorsSet).sort((a, b) => a - b)
					: [floorOrder];
				const stackBadge =
					sortedFl.length > 1
						? `↕ ${sortedFl.map(formatFloorShort).join("·")}`
						: `Tầng ${formatFloorShort(floorOrder)}`;

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

		// Ghost Portals from other floors of the same building & same portal type
		const ghostPortalFeatures = [];
		if (
			!isAllFloors &&
			activeTool === "NAV_GRAPH" &&
			(activeNodeType === "STAIRS" || activeNodeType === "ELEVATOR")
		) {
			const seenGroups = new Set(
				visibleNodes
					.filter(
						(n) => isPortalNode(n) && n.type === activeNodeType && n.portalGroupId,
					)
					.map((n) => `${n.type}__${n.portalGroupId.trim().toUpperCase()}`),
			);
			for (const n of effectiveStore.navNodes || []) {
				if (
					!isPortalNode(n) ||
					n.type !== activeNodeType ||
					Number(n.floorOrder) === scopeFloorNum
				) {
					continue;
				}
				const nb =
					resolveNodeBuilding(boundaries, n, floorWallModels) ||
					resolveCanonicalBuildingName(n.building) ||
					n.building;
				if (!isAllBuildings && !matchesBuilding(nb, activeBuilding)) continue;
				const gk = `${n.type}__${(n.portalGroupId || "").trim().toUpperCase()}`;
				if (n.portalGroupId && seenGroups.has(gk)) continue;
				if (n.portalGroupId) seenGroups.add(gk);
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
		isAllBuildings,
		isAllFloors,
		scopeFloorNum,
		floorWallModels,
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
						floorWallModels,
					),
			) || null
		);
	}, [
		effectiveStore.navNodes,
		effectiveStore.buildingBoundaries,
		selectedNavNodeId,
		activeBuilding,
		activeFloorOrder,
		floorWallModels,
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
		const doorBldg =
			resolveCanonicalBuildingName(singleSelectedDoor.building) ||
			singleSelectedDoor.building;
		const doorSegs =
			floorWallModels.get(
				`${doorBldg}__${Number(singleSelectedDoor.floorOrder) || 0}`,
			)?.wallSegments ||
			currentFloorModel?.wallSegments ||
			[];
		return buildOrientedDoorGeometry(singleSelectedDoor, doorSegs);
	}, [singleSelectedDoor, floorWallModels, currentFloorModel]);

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

		// 1. Isolated Nav Nodes in scope
		for (const n of currentFloorNodes) {
			if (!connectedNodeIds.has(n.id)) {
				const metaLabel = NODE_DISPLAY_META[n.type]?.label || n.type;
				issues.push({
					id: `iso-${n.id}`,
					level: "warning",
					type: "NODE",
					targetId: n.id,
					coords: n.coords,
					title: `Nút lẻ: ${metaLabel}`,
					desc: "Nút chưa nối vào mạng đường đi.",
				});
			}
		}

		// 2. Unmatched Portals for STAIRS / ELEVATOR (strictly scoped per canonical building & portal type)
		const portalKey = (n) => {
			const bldg = resolveCanonicalBuildingName(
				resolveNodeBuilding(boundaries, n, floorWallModels) || n.building,
			);
			return `${bldg.toUpperCase()}__${n.type}__${n.portalGroupId.trim().toUpperCase()}`;
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
					const ownerBldg =
						resolveNodeBuilding(boundaries, n, floorWallModels) || n.building;
					issues.push({
						id: `portal-${n.id}`,
						level: "error",
						type: "NODE",
						targetId: n.id,
						coords: n.coords,
						title: `Thang chưa nối tầng (${n.portalGroupId})`,
						desc: `Thiếu tầng đối ứng trong ${ownerBldg}.`,
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
				title: "Đầu tường hở",
				desc: "Đầu tường chưa khép vào góc tường khác.",
			});
		});

		// 4. Doors not sitting on any wall segment (> 0.55m)
		for (const d of currentFloorDoors) {
			if (!Array.isArray(d.coords)) continue;
			const doorBldg = resolveCanonicalBuildingName(d.building) || d.building;
			const doorSegs =
				floorWallModels.get(`${doorBldg}__${Number(d.floorOrder) || 0}`)
					?.wallSegments || [];
			const nearWall = doorSegs.some(
				(ws) => distancePointToSegmentMeters(d.coords, ws.a, ws.b) <= 0.55,
			);
			if (!nearWall) {
				issues.push({
					id: `door-orphan-${d.id}`,
					level: "error",
					type: "DOOR",
					targetId: d.id,
					coords: d.coords,
					title: "Cửa lệch tường",
					desc: "Cửa chưa nằm trên đoạn tường nào.",
				});
			}
		}

		// 5. ROOM faces missing a wall-mounted DOOR (advisory for A* doorway routing)
		for (const rm of currentFloorRooms) {
			const hasDoor = currentFloorDoors.some((d) => {
				if (!Array.isArray(d.coords)) return false;
				if (rm.building && !matchesBuilding(d.building, rm.building)) return false;
				if (
					rm.floorOrder != null &&
					Number(d.floorOrder) !== Number(rm.floorOrder)
				) {
					return false;
				}
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
					title: "Phòng chưa có cửa [D]",
					desc: "Đặt 1 cửa [D] trên vách để dẫn đường vào phòng.",
				});
			}
		}

		// 6. Door & Portal Edge Connectivity Audit (same-floor horizontal links for Stairs/Elevators + edge wiring for Doors)
		const connAudit = auditNavigationConnectivity({
			store,
			building: activeBuilding,
			floorOrder: activeFloorOrder,
			precomputedModels: floorWallModels,
		});
		for (const connIssue of connAudit.issues) {
			issues.push(connIssue);
		}

		return issues;
	}, [
		store,
		activeBuilding,
		activeFloorOrder,
		floorWallModels,
		currentFloorNodes,
		currentFloorModel,
		currentFloorDoors,
		currentFloorRooms,
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
								setActiveFloorOrder((prev) => (prev === "ALL" ? "ALL" : 0));
								setDraftPoints([]);
								setChainLastNodeId(null);
							}}
						>
							<option value="ALL">Toàn khuôn viên</option>
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
								const val = e.target.value;
								setActiveFloorOrder(val === "ALL" ? "ALL" : Number(val));
								setDraftPoints([]);
								setChainLastNodeId(null);
							}}
						>
							<option value="ALL">Tất cả tầng</option>
							{buildingFloorOptions.map((fl) => (
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
						onClick={() => switchTool("SELECT")}
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
						onClick={() => switchTool("ROOM_BOX")}
						title="Tạo phòng [B]"
						aria-label="Tạo phòng [B]"
					>
						<Square size={17} />
						<span className="digitizer-rail-key">B</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "ROOM_POLY" ? "is-active" : ""}`}
						onClick={() => switchTool("ROOM_POLY")}
						title="Đa giác [P]"
						aria-label="Đa giác [P]"
					>
						<Pentagon size={17} />
						<span className="digitizer-rail-key">P</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "ROOM_SLICE" ? "is-active" : ""}`}
						onClick={() => switchTool("ROOM_SLICE")}
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
						onClick={() => switchTool("WALL_DRAW")}
						title="Vẽ tường (khép kín / vách ngăn) [W]"
						aria-label="Vẽ tường [W]"
					>
						<Ruler size={17} />
						<span className="digitizer-rail-key">W</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "DOOR" ? "is-active" : ""}`}
						onClick={() => switchTool("DOOR")}
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
						onClick={() => switchTool("NAV_GRAPH")}
						title="Đồ thị [N]"
						aria-label="Đồ thị [N]"
					>
						<Route size={17} />
						<span className="digitizer-rail-key">N</span>
					</button>

					<button
						type="button"
						className={`digitizer-rail-btn ${activeTool === "TEST_ROUTE" ? "is-active" : ""}`}
						onClick={() => switchTool("TEST_ROUTE")}
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
						onClick={() => switchTool("CALIBRATE_PNG")}
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
									? `Đã chọn ${selectedRoomIds.length} vùng · Đổi loại vùng ở bảng phải`
									: singleSelectedNavNode
										? `${NODE_DISPLAY_META[singleSelectedNavNode.type]?.label || singleSelectedNavNode.type} · Kéo để di chuyển · Del: xóa`
										: singleSelectedDoor
											? `Cửa ${singleSelectedDoor.widthMeters || 1.2}m · Kéo mép chỉnh rộng · Kéo tâm trượt · Del: xóa`
											: singleSelectedWall
												? "Tường · Kéo góc chỉnh chung (Alt: tách) · Del: xóa"
												: "Chọn vùng, tường, cửa hoặc nút [Shift+Kéo: chọn nhiều]")}

							{activeTool === "ROOM_BOX" &&
								(isAllScope
									? "Chọn 1 tòa và 1 tầng cụ thể để vẽ phòng"
									: draftPoints.length === 0
										? "Click 2 góc để tạo phòng [Shift: ngang 0°]"
										: shiftKeyDown
											? "Click góc thứ 2 (Ngang 0°) [Esc: hủy]"
											: "Click góc thứ 2 [Shift: ngang 0° · Esc: hủy]")}

							{activeTool === "ROOM_SLICE" &&
								(isAllScope
									? "Chọn 1 tòa và 1 tầng cụ thể để chia vùng"
									: draftPoints.length === 0
										? "Click 2 điểm băng qua vùng cần chia [S]"
										: "Click điểm cuối xuyên qua vùng [Esc: hủy]")}

							{activeTool === "ROOM_POLY" &&
								(isAllScope
									? "Chọn 1 tòa và 1 tầng cụ thể để vẽ phòng"
									: `Đa giác phòng: ${draftPoints.length} điểm · Enter hoặc click điểm đầu để khép kín`)}

							{activeTool === "WALL_DRAW" &&
								(isAllScope
									? "Chọn 1 tòa và 1 tầng cụ thể để vẽ tường"
									: `Vẽ tường: ${draftPoints.length} điểm · Click điểm đầu: khép vùng · Enter: kết thúc`)}

							{activeTool === "DOOR" &&
								(isAllScope
									? "Chọn 1 tòa và 1 tầng cụ thể để gắn cửa"
									: "Click lên tường để gắn Cửa [D]")}

							{activeTool === "NAV_GRAPH" &&
								(isAllFloors
									? "Chọn 1 tầng cụ thể để vẽ đồ thị"
									: `Đồ thị (${NODE_DISPLAY_META[activeNodeType]?.label || activeNodeType}): Click đặt & nối nút (bắt vào tâm cửa) [Esc: ngắt]`)}

							{activeTool === "TEST_ROUTE" &&
								(!testRouteStart
									? "Click điểm xuất phát [T]"
									: "Click điểm đến")}

							{activeTool === "CALIBRATE_PNG" &&
								(isAllScope
									? "Chọn 1 tòa và 1 tầng cụ thể để căn bản vẽ"
									: "Kéo 4 góc để co giãn · Chốt trên để xoay · Chốt tâm để dịch chuyển")}
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

					{/* Live A* Route Result Card & Real-Time Playback Simulator */}
					{testRouteResult?.geojson && (
						<div className="digitizer-route-card">
							<div className="digitizer-route-card__top">
								<div className="digitizer-route-card__metrics">
									<div>
										<div className="digitizer-route-card__val">
											{testRouteResult.totalDistanceMeters}m
										</div>
										<div className="digitizer-route-card__label">
											Tổng cự ly
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
								</div>

								<div className="digitizer-route-card__controls">
									<button
										type="button"
										className={`digitizer-route-play-btn ${routePlaying ? "is-playing" : ""}`}
										onClick={() => setRoutePlaying((p) => !p)}
										title={
											routePlaying
												? "Tạm dừng mô phỏng"
												: "Chạy giả lập người đi theo tuyến đường"
										}
									>
										{routePlaying ? <Pause size={13} /> : <Play size={13} />}
										<span>{routePlaying ? "Tạm dừng" : "Mô phỏng"}</span>
									</button>
									<button
										type="button"
										className={`digitizer-route-card__icon-btn ${followRouteHeading ? "is-active" : ""}`}
										onClick={() => setFollowRouteHeading((p) => !p)}
										title={
											followRouteHeading
												? "Đang tự xoay & bám theo hướng di chuyển (Nhấn để tắt)"
												: "Bật tự xoay bản đồ theo hướng di chuyển"
										}
										aria-label="Tự xoay theo hướng di chuyển"
										aria-pressed={followRouteHeading}
									>
										<Navigation size={13} />
									</button>
									<button
										type="button"
										className="digitizer-route-card__icon-btn"
										onClick={() => {
											setRoutePlaying(false);
											setRouteProgress(0);
											routeProgressRef.current = 0;
											camFollowRef.current = null;
											if (followRouteHeading && mapRef.current) {
												const s0 = samplePointAlongRoute(testRouteResult, 0);
												if (s0) {
													mapRef.current.easeTo({
														center: s0.coords,
														bearing: s0.bearing,
														pitch: is3dPreviewRef.current ? 58 : 0,
														duration: 320,
													});
												}
											}
										}}
										title="Đưa về điểm xuất phát"
										aria-label="Đưa mô phỏng về điểm xuất phát"
									>
										<RotateCcw size={13} />
									</button>
									<button
										type="button"
										className="digitizer-route-card__close"
										onClick={() => {
											setRoutePlaying(false);
											camFollowRef.current = null;
											setTestRouteResult(null);
										}}
										aria-label="Đóng kết quả tuyến đường"
									>
										<X size={14} />
									</button>
								</div>
							</div>

							<div className="digitizer-route-playback-bar">
								<input
									type="range"
									min="0"
									max="1"
									step="0.005"
									value={routeProgress}
									onChange={(e) => {
										const nextVal = Number(e.target.value);
										setRoutePlaying(false);
										routeProgressRef.current = nextVal;
										setRouteProgress(nextVal);
										if (followRouteHeading && mapRef.current) {
											const s = samplePointAlongRoute(testRouteResult, nextVal);
											if (s) {
												mapRef.current.easeTo({
													center: s.coords,
													bearing: s.bearing,
													pitch: is3dPreviewRef.current ? 58 : 0,
													duration: 180,
												});
											}
										}
									}}
									className="digitizer-route-scrubber"
									aria-label="Tua tiến độ mô phỏng tuyến đường"
								/>
								<div className="digitizer-route-playback-meta">
									<span className="digitizer-route-playback-status">
										{playbackSample?.statusLabel || "Sẵn sàng"}
									</span>
									<span className="digitizer-route-playback-dist">
										{playbackSample?.distanceCoveredMeters ?? 0} /{" "}
										{testRouteResult.totalDistanceMeters}m
									</span>
								</div>
							</div>
						</div>
					)}

					{/* Bottom Telemetry Strip */}
					<div className="digitizer-telemetry-bar">
						<div className="digitizer-telemetry-item">
							{hoverCoord && (
								<strong>
									{hoverCoord[0].toFixed(6)}, {hoverCoord[1].toFixed(6)}
								</strong>
							)}
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
									{snapType === "VERTEX"
										? "Bắt đỉnh"
										: snapType === "MIDPOINT"
											? "Trung điểm"
											: "Trên cạnh"}{" "}
									[Alt: Tắt]
								</span>
							)}
						</div>

						<div className="digitizer-telemetry-item">
							<strong>{currentFloorRooms.length} Phòng</strong>
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
						onDblClick={handleMapDblClick}
						onMouseMove={handleMapMouseMove}
						onMouseDown={handleMapMouseDown}
						onMouseUp={handleMapMouseUp}
						onDragStart={(e) => {
							if (routePlaying && e?.originalEvent && !draggingNavNodeRef.current) {
								setFollowRouteHeading(false);
							}
						}}
						onRotateStart={(e) => {
							if (routePlaying && e?.originalEvent) {
								setFollowRouteHeading(false);
							}
						}}
						onRotateEnd={() => {
							if (mapRef.current) {
								setMapViewportBearing(Math.round(mapRef.current.getBearing?.() ?? 0));
							}
						}}
						cursor={studioCursor}
						interactiveLayerIds={studioInteractiveLayerIds}
						light={STUDIO_LIGHT}
						mapStyle={OSM_STYLE}
						style={{ width: "100%", height: "100%" }}
						minZoom={14}
						maxZoom={22}
						boxZoom={false}
						doubleClickZoom={
							activeTool !== "WALL_DRAW" && activeTool !== "ROOM_POLY"
						}
						dragPan={
							!isDraggingNavNode &&
							!isDraggingVertex &&
							!(activeTool === "SELECT" && shiftKeyDown)
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

						{/* 1. Blueprint PNG Tracing Overlay(s) */}
						{visiblePngOverlays.map((ov) => (
							<Source
								key={ov.id}
								id={`studio-png-blueprint-${ov.id}`}
								type="image"
								url={ov.url}
								coordinates={ov.corners}
							>
								<Layer
									id={`studio-png-blueprint-layer-${ov.id}`}
									type="raster"
									paint={{
										"raster-opacity": pngOpacity,
										"raster-fade-duration": 0,
									}}
								/>
							</Source>
						))}

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
												0.16,
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
										"line-width": ["case", ["get", "isSelected"], 2.4, 1.5],
										"line-opacity": 0.85,
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
										"line-width": 8,
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
											["get", "isConnected"],
											"#10b981",
											activeTool === "NAV_GRAPH"
												? "#f59e0b"
												: "#e11d48",
										],
										"circle-stroke-width": 1.6,
										"circle-stroke-color": "#ffffff",
									}}
								/>
							</Source>
						)}

						{/* 5b-bis. Live 3D Door Hover Preview when attaching doors in 3D view */}
						{is3dPreview && layerVisibility.doors && (
							<Source
								id="studio-door-3d-preview-src"
								type="geojson"
								data={studioDoor3dPreviewGeoJSON}
							>
								<Layer
									id="studio-door-3d-preview-layer"
									type="fill-extrusion"
									paint={{
										"fill-extrusion-color": "#fb7185",
										"fill-extrusion-base": ["get", "baseHeight"],
										"fill-extrusion-height": ["get", "extrudeHeight"],
										"fill-extrusion-opacity": 0.72,
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
												["get", "isPortal"],
												0,
												["get", "isSelected"],
												10,
												["get", "isChainHead"],
												8,
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
												["get", "isPortal"],
												0,
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

						{testRouteResult?.geojson &&
							!is3dPreview &&
							playbackSample?.trailGeoJSON?.features?.length > 0 &&
							(routePlaying || routeProgress > 0) && (
								<Source
									id="studio-test-route-trail-src"
									type="geojson"
									data={playbackSample.trailGeoJSON}
								>
									<Layer
										id="studio-test-route-trail-layer"
										type="line"
										layout={{ "line-cap": "round", "line-join": "round" }}
										paint={{ "line-color": "#06b6d4", "line-width": 5.5 }}
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
								{playbackWalker3dGeoJSON.features.length > 0 && (
									<Source
										id="studio-walker-3d-src"
										type="geojson"
										data={playbackWalker3dGeoJSON}
									>
										<Layer
											id="studio-walker-3d-layer"
											type="fill-extrusion"
											paint={{
												"fill-extrusion-color": ["get", "color"],
												"fill-extrusion-base": ["get", "baseHeight"],
												"fill-extrusion-height": ["get", "extrudeHeight"],
												"fill-extrusion-opacity": 0.96,
											}}
										/>
									</Source>
								)}
							</>
						)}

						{/* 7.5 Animated Real-Time Route Walker Marker (2D heading-aware anchor) */}
						{testRouteResult?.geojson &&
							!is3dPreview &&
							playbackSample &&
							(routePlaying || routeProgress > 0) && (
								<Marker
									longitude={playbackSample.coords[0]}
									latitude={playbackSample.coords[1]}
									anchor="center"
									style={NON_INTERACTIVE_MARKER_STYLE}
								>
									<div className="digitizer-playback-walker">
										<div
											className="digitizer-playback-walker__heading"
											style={{
												transform: `rotate(${(Number(playbackSample.bearing) || 0) - (routePlaying && followRouteHeading ? (camFollowRef.current?.bearing ?? mapViewportBearing) : mapViewportBearing)}deg)`,
											}}
										>
											<span className="digitizer-playback-walker__cone" />
											<span className="digitizer-playback-walker__arrow" />
										</div>
										<div className="digitizer-playback-walker__pulse" />
										<div className="digitizer-playback-walker__dot">
											<Footprints size={12} />
										</div>
										<span className="digitizer-playback-walker__badge">
											{playbackSample.isOutdoor
												? "Sân"
												: `T${playbackSample.floorOrder === 0 ? "G" : playbackSample.floorOrder}`}
										</span>
									</div>
								</Marker>
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
								<StaticDragMarker
									key={`wv-${singleSelectedWall.id}-${idx}`}
									longitude={pt[0]}
									latitude={pt[1]}
									className="digitizer-vertex-handle digitizer-vertex-handle--wall"
									title="Kéo để di chuyển góc tường (Giữ Alt: tách rời 1 đầu tường riêng)"
									onDragStart={(e) =>
										handleVertexDragStart(
											"WALL",
											singleSelectedWall.id,
											idx,
											e.lngLat,
											e.originalEvent,
										)
									}
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
								/>
							))}

						{/* 9b. Interactive Door Handles (Midpoint Slide + Left/Right Jamb Width Resize) */}
						{singleSelectedDoor &&
							singleSelectedDoorGeom &&
							activeTool === "SELECT" &&
							!layerLocks.doors && (
								<>
									<StaticDragMarker
										key={`door-mid-${singleSelectedDoor.id}`}
										longitude={singleSelectedDoorGeom.midpoint[0]}
										latitude={singleSelectedDoorGeom.midpoint[1]}
										className="digitizer-door-handle digitizer-door-handle--mid"
										title="Kéo tâm để trượt Cửa dọc theo tường (Node trung điểm A*)"
										onDragStart={() =>
											handleDoorDragStart("DOOR_MOVE", singleSelectedDoor.id)
										}
										onDrag={(e) =>
											handleDoorMoveDragLive(singleSelectedDoor.id, e.lngLat)
										}
										onDragEnd={(e) =>
											handleDoorMoveDragEnd(singleSelectedDoor.id, e.lngLat)
										}
									/>

									{[
										{ key: "jambA", pt: singleSelectedDoorGeom.jambA },
										{ key: "jambB", pt: singleSelectedDoorGeom.jambB },
									].map((jb) => (
										<StaticDragMarker
											key={`door-${jb.key}-${singleSelectedDoor.id}`}
											longitude={jb.pt[0]}
											latitude={jb.pt[1]}
											className="digitizer-door-handle digitizer-door-handle--jamb"
											title={`Kéo mép cửa để chỉnh độ rộng (${singleSelectedDoorGeom.widthMeters}m)`}
											onDragStart={() =>
												handleDoorDragStart("DOOR_WIDTH", singleSelectedDoor.id)
											}
											onDrag={(e) =>
												handleDoorJambDragLive(singleSelectedDoor, e.lngLat)
											}
											onDragEnd={(e) =>
												handleDoorJambDragEnd(singleSelectedDoor, e.lngLat)
											}
										/>
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
						{activeTool === "CALIBRATE_PNG" && !isAllScope && (
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
							<span>Lớp & Vùng</span>
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
												? "Vùng chọn"
												: `Đã chọn ${selectedRoomIds.length} vùng`}
										</span>
									</div>

									{selectedRoomIds.length === 1 && singleSelectedFace && (
										<>
											<div className="digitizer-field">
												<label className="digitizer-field-label">
													Loại vùng:
												</label>
												<div className="digitizer-chip-row">
													{[
														{ id: "ROOM", label: "Phòng", accent: "#0284c7" },
														{ id: "CORRIDOR", label: "Hành lang", accent: "#10b981" },
														{ id: "VOID", label: "Giếng trời / Sân", accent: "#ef4444" },
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
													<label>Khoét rỗng:</label>
													<span className="digitizer-prop-val">
														{singleSelectedFace.holes.length} vùng lồng
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
											<span>Gỡ vai trò</span>
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
											Nút đồ thị
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
												floorWallModels,
											)
												? "Ngoài sân (Tầng G)"
												: resolveNodeBuilding(
														store.buildingBoundaries,
														singleSelectedNavNode,
														floorWallModels,
													) ||
													singleSelectedNavNode.building ||
													activeBuilding}
										</span>
									</div>

									{(singleSelectedNavNode.type === "STAIRS" ||
										singleSelectedNavNode.type === "ELEVATOR") && (
										<>
											<div className="digitizer-field">
												<label className="digitizer-field-label">
													Thông tầng:
												</label>
												{(() => {
													const portalBldg =
														resolveNodeBuilding(
															store.buildingBoundaries || [],
															singleSelectedNavNode,
															floorWallModels,
														) ||
														singleSelectedNavNode.building ||
														currentBuildingMeta.name;
													const portalBldgMeta =
														DEFAULT_BUILDINGS_META.find((b) =>
															matchesBuilding(b.name, portalBldg),
														) || currentBuildingMeta;
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
																	n.type === singleSelectedNavNode.type &&
																	(n.portalGroupId || "")
																		.trim()
																		.toUpperCase() === gk &&
																	matchesBuilding(
																		resolveNodeBuilding(
																			store.buildingBoundaries || [],
																			n,
																			floorWallModels,
																		) || n.building,
																		portalBldg,
																	),
															)
															.map((n) => Number(n.floorOrder) || 0),
													);
													connectedFloors.add(
														Number(singleSelectedNavNode.floorOrder) || 0,
													);
													const allFloors = portalBldgMeta.floors || [];

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
																						? `Đã gỡ ${flMeta.shortLabel} khỏi trục.`
																						: `Đã đồng bộ lên ${flMeta.shortLabel}.`,
																				);
																			}}
																			title={
																				isCurrent
																					? "Tầng hiện tại"
																					: "Bật/tắt tầng kết nối"
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
																	showToast("Đã đồng bộ tất cả tầng.");
																}}
															>
																<ArrowUpDown size={12} />
																<span>Đồng bộ tất cả tầng</span>
															</button>
														</>
													);
												})()}
											</div>

											<details className="digitizer-portal-advanced">
												<summary>
													Mã trục nâng cao ({singleSelectedNavNode.portalGroupId || "Chưa đặt"})
												</summary>
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
											</details>
										</>
									)}

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
											Đoạn tường
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
										<label>Số điểm:</label>
										<span className="digitizer-prop-val">
											{(singleSelectedWall.line || []).length}
										</span>
									</div>

									<p className="digitizer-prop-hint">
										Kéo đầu mút để nối · Giữ Alt để tách · + để bẻ góc.
									</p>

									<div className="digitizer-btn-row">
										<button
											type="button"
											className="digitizer-sub-btn digitizer-sub-btn--danger"
											onClick={() => deleteWallById(singleSelectedWall.id)}
										>
											<Trash2 size={12} />
											<span>Xóa tường</span>
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
											Cửa
										</span>
									</div>

									<div className="digitizer-field">
										<label className="digitizer-field-label">
											Bề rộng (m):
										</label>
										<div className="digitizer-door-width-controls">
											<input
												type="range"
												min="0.5"
												max="10"
												step="0.05"
												value={Number(singleSelectedDoor.widthMeters) || 1.2}
												onChange={(e) => {
													const nextW = Number(
														Math.max(
															0.5,
															Math.min(10.0, Number(e.target.value) || 1.2),
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
												max="10"
												step="0.05"
												value={Number(singleSelectedDoor.widthMeters) || 1.2}
												onChange={(e) => {
													const rawVal = Number(e.target.value);
													if (!Number.isFinite(rawVal)) return;
													const nextW = Number(
														Math.max(0.5, Math.min(10.0, rawVal)).toFixed(2),
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
									</div>

									<p className="digitizer-prop-hint">
										Kéo 2 chốt mép để chỉnh rộng · Dùng [N] nối vào tâm cửa.
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
							{activeTool === "NAV_GRAPH" && (
								<div className="digitizer-prop-card">
									<span className="digitizer-prop-title">
										<Route size={13} />
										Vẽ đồ thị & Thang
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
										<details className="digitizer-portal-advanced">
											<summary>
												Mã trục nâng cao ({portalGroupId || "Mặc định"})
											</summary>
											<div className="digitizer-field">
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
										</details>
									)}

									{chainLastNodeId && (
										<button
											type="button"
											className="digitizer-sub-btn digitizer-sub-btn--danger"
											onClick={() => setChainLastNodeId(null)}
										>
											<X size={12} />
											<span>Ngắt chuỗi nối (Esc)</span>
										</button>
									)}
								</div>
							)}

							{/* Case E: Default Floor Overview */}
							<div className="digitizer-prop-card">
								<span className="digitizer-prop-title">
									<Building2 size={13} />
									{isAllScope
										? "Tổng quan phạm vi"
										: `Tổng quan ${currentFloorMeta.name}`}
								</span>
								<div className="digitizer-prop-row">
									<label>Khung bao:</label>
									<span className="digitizer-prop-val">
										{hasBuildingBoundary ? "Đã khép kín" : "Chưa khép kín"}
									</span>
								</div>
								<div className="digitizer-collapse-group">
									<button
										type="button"
										className="digitizer-prop-row digitizer-prop-row--collapsible"
										onClick={() =>
											setOverviewExpanded((prev) => ({
												...prev,
												rooms: !prev.rooms,
											}))
										}
										aria-expanded={overviewExpanded.rooms}
									>
										<span className="digitizer-collapse-label">
											{overviewExpanded.rooms ? (
												<ChevronDown size={13} />
											) : (
												<ChevronRight size={13} />
											)}
											<label>Phòng & Vùng:</label>
										</span>
										<span className="digitizer-prop-val">
											{currentFloorRooms.length} phòng /{" "}
											{currentFloorFaces.length} vùng
										</span>
									</button>
									{overviewExpanded.rooms && (
										<div className="digitizer-collapse-body">
											{currentFloorFaces.length === 0 ? (
												<span className="digitizer-tree-empty">
													Chưa có vùng nào
												</span>
											) : (
												currentFloorFaces.map((fc, idx) => {
													const roleName =
														fc.role === "ROOM"
															? "Phòng"
															: fc.role === "VOID"
																? "Giếng trời"
																: "Hành lang";
													return (
														<button
															key={fc.id}
															type="button"
															className={`digitizer-collapse-item ${selectedRoomIds.includes(fc.id) ? "is-selected" : ""}`}
															onClick={() => {
																selectEntity("FACE", fc.id);
																setActiveTool("SELECT");
															}}
														>
															<span>
																{roleName} #{idx + 1}
															</span>
															<span className="digitizer-collapse-meta">
																{(
																	fc.areaSqm ??
																	approximatePolygonAreaMeters(fc.ring)
																).toFixed(0)}{" "}
																m²
															</span>
														</button>
													);
												})
											)}
										</div>
									)}
								</div>

								<div className="digitizer-collapse-group">
									<button
										type="button"
										className="digitizer-prop-row digitizer-prop-row--collapsible"
										onClick={() =>
											setOverviewExpanded((prev) => ({
												...prev,
												doors: !prev.doors,
											}))
										}
										aria-expanded={overviewExpanded.doors}
									>
										<span className="digitizer-collapse-label">
											{overviewExpanded.doors ? (
												<ChevronDown size={13} />
											) : (
												<ChevronRight size={13} />
											)}
											<label>Cửa:</label>
										</span>
										<span className="digitizer-prop-val">
											{currentFloorDoors.length}
										</span>
									</button>
									{overviewExpanded.doors && (
										<div className="digitizer-collapse-body">
											{currentFloorDoors.length === 0 ? (
												<span className="digitizer-tree-empty">
													Chưa gắn cửa nào
												</span>
											) : (
												currentFloorDoors.map((dr, idx) => (
													<button
														key={dr.id}
														type="button"
														className={`digitizer-collapse-item ${selectedDoorId === dr.id ? "is-selected" : ""}`}
														onClick={() => {
															selectEntity("DOOR", dr.id);
															setActiveTool("SELECT");
														}}
													>
														<span>Cửa #{idx + 1}</span>
														<span className="digitizer-collapse-meta">
															{dr.widthMeters || 1.2}m
														</span>
													</button>
												))
											)}
										</div>
									)}
								</div>

								<div className="digitizer-collapse-group">
									<button
										type="button"
										className="digitizer-prop-row digitizer-prop-row--collapsible"
										onClick={() =>
											setOverviewExpanded((prev) => ({
												...prev,
												nodes: !prev.nodes,
											}))
										}
										aria-expanded={overviewExpanded.nodes}
									>
										<span className="digitizer-collapse-label">
											{overviewExpanded.nodes ? (
												<ChevronDown size={13} />
											) : (
												<ChevronRight size={13} />
											)}
											<label>Nút đồ thị:</label>
										</span>
										<span className="digitizer-prop-val">
											{currentFloorNodes.length}
										</span>
									</button>
									{overviewExpanded.nodes && (
										<div className="digitizer-collapse-body">
											{currentFloorNodes.length === 0 ? (
												<span className="digitizer-tree-empty">
													Chưa có nút đồ thị nào
												</span>
											) : (
												currentFloorNodes.map((nd, idx) => {
													const typeMeta = NODE_DISPLAY_META[nd.type] || {
														label: nd.type,
													};
													return (
														<button
															key={nd.id}
															type="button"
															className={`digitizer-collapse-item ${selectedNavNodeId === nd.id ? "is-selected" : ""}`}
															onClick={() => {
																selectEntity("NODE", nd.id);
																setActiveTool("SELECT");
															}}
														>
															<span>
																{typeMeta.label} #{idx + 1}
															</span>
															<span className="digitizer-collapse-meta">
																{nd.portalGroupId ||
																	`T${Number(nd.floorOrder) === 0 ? "G" : nd.floorOrder}`}
															</span>
														</button>
													);
												})
											)}
										</div>
									)}
								</div>

								{!isAllScope && (
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
								)}
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
									Lớp bản đồ
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
											<label>Độ mờ PNG:</label>
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
									{ key: "walls", label: "Tường" },
									{ key: "faces", label: "Vùng phòng" },
									{ key: "doors", label: "Cửa" },
									{ key: "navGraph", label: "Đồ thị" },
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
									<button
										type="button"
										className="digitizer-tree-toggle-btn"
										onClick={() =>
											setTreeExpanded((prev) => ({
												...prev,
												faces: !prev.faces,
											}))
										}
										aria-expanded={treeExpanded.faces}
									>
										{treeExpanded.faces ? (
											<ChevronDown size={13} />
										) : (
											<ChevronRight size={13} />
										)}
										<span>Phòng & Vùng ({currentFloorFaces.length})</span>
									</button>
									<div className="digitizer-tree-header-actions">
										<button
											type="button"
											className="digitizer-tree-micro-btn"
											onClick={() =>
												setSelectedRoomIds(currentFloorRooms.map((r) => r.id))
											}
											title="Chọn tất cả vùng Phòng"
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

								{treeExpanded.faces && (
								<div className="digitizer-tree-list">
									{currentFloorFaces.length === 0 ? (
										<span className="digitizer-tree-empty">
											Chưa có vùng nào (vẽ [B], [P] hoặc [W])
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
														? "Giếng trời"
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
								)}
							</div>

							{/* Scene Tree: Wall-Mounted Doors */}
							<div className="digitizer-tree-group">
								<div className="digitizer-tree-header">
									<button
										type="button"
										className="digitizer-tree-toggle-btn"
										onClick={() =>
											setTreeExpanded((prev) => ({
												...prev,
												doors: !prev.doors,
											}))
										}
										aria-expanded={treeExpanded.doors}
									>
										{treeExpanded.doors ? (
											<ChevronDown size={13} />
										) : (
											<ChevronRight size={13} />
										)}
										<span>Cửa trên tường</span>
									</button>
									<span className="digitizer-inspector-badge">
										{currentFloorDoors.length}
									</span>
								</div>

								{treeExpanded.doors && (
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
								)}
							</div>

							{/* Scene Tree: Vertical Portals Only (Cầu thang & Thang máy) */}
							{(() => {
								const portalNodes = currentFloorNodes.filter(isPortalNode);
								return (
									<div className="digitizer-tree-group">
										<div className="digitizer-tree-header">
											<button
												type="button"
												className="digitizer-tree-toggle-btn"
												onClick={() =>
													setTreeExpanded((prev) => ({
														...prev,
														portals: !prev.portals,
													}))
												}
												aria-expanded={treeExpanded.portals}
											>
												{treeExpanded.portals ? (
													<ChevronDown size={13} />
												) : (
													<ChevronRight size={13} />
												)}
												<span>Cầu thang & Thang máy</span>
											</button>
											<span className="digitizer-inspector-badge">
												{portalNodes.length}
											</span>
										</div>

										{treeExpanded.portals && (
										<div className="digitizer-tree-list">
											{portalNodes.length === 0 ? (
												<span className="digitizer-tree-empty">
													Chưa có cầu thang / thang máy
												</span>
											) : (
												portalNodes.map((nd) => {
													const typeMeta = NODE_DISPLAY_META[nd.type] || {
														label: nd.type,
														color: "#a855f7",
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
																title="Xóa điểm này"
																aria-label={`Xóa ${typeMeta.label}`}
															>
																<Trash2 size={12} />
															</button>
														</div>
													);
												})
											)}
										</div>
										)}
									</div>
								);
							})()}
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
									Tắt bắt dính tạm thời
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
									Chọn nhiều vùng cùng lúc
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
