import React, {
	useState,
	useMemo,
	useRef,
	useCallback,
	useEffect,
} from "react";
// Đặt tên MapGL để KHÔNG che khuất đối tượng Map chuẩn của JavaScript (new Map()).
import MapGL, {
	NavigationControl,
	FullscreenControl,
	Marker,
	Source,
	Layer,
} from "react-map-gl/maplibre";
import { MercatorCoordinate } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import "../../utils/initMapLibreWorker";
import {
	Compass,
	PanelRightClose,
	PanelRightOpen,
	Building2,
	Layers,
	ChevronDown,
	X,
	MapPin,
	AlertCircle,
	Crosshair,
	Save,
	Loader2,
	Menu,
	Check,
} from "lucide-react";
import {
	getLevelConfig,
	getAccessLevelConfig,
	getErrorMessage,
	AREA_LEVEL_CONFIG,
	validateAreaName,
} from "../../utils/areaHelpers";
import { createArea, updateArea } from "../../services/areaService";
import { createCamera } from "../../services/cameraService";
import AreaSelectWithFilter from "./AreaSelectWithFilter";
import {
	DEFAULT_BUILDINGS_META,
	FLOOR_ELEVATION_STEP,
	FLOOR_SLAB_HEIGHT,
	OPENFREEMAP_STYLE_URL,
	loadGeometryStore,
	buildVector3dLayers,
	extractAreaLevelCode,
	resolveRoomBlockSelection,
	matchesBuilding,
	resolveFloorOrder,
	splitGhostActiveGeoJSON,
	mapSidebarSelectionTo3d,
	map3dSelectionToSidebar,
} from "../../data/campusIndoor3dData";
import {
	registerPortalMapImages,
	ensurePortalMapImage,
} from "../../utils/mapPortalIcons";
import {
	registerRoomLabelSprites,
	ensureRoomLabelSpriteImage,
} from "../../utils/mapRoomLabelSprites";
import {
	registerCameraBadgeSprites,
	ensureCameraBadgeSpriteImage,
} from "../../utils/mapCameraBadgeSprites";
import {
	CAMERA_PIN_DRAG_THRESHOLD_PX,
	buildCameraMapPinGeoJson,
	projectEventToFloorLngLat,
	validateCameraPinPlacement,
} from "../../utils/cameraMapGeometry";
import {
	CAMERA_TYPE_OPTIONS,
	buildCameraMapMarkersGeoJSON,
	extractCameraAreaInfo,
	filterAreasForCameraType,
	normalizeCorridorCameraName,
	stripCorridorPrefix,
} from "../../utils/campusCameraHelpers";
import "../../styles/CampusMapView.css";

// Tòa Epsilon default center (2D Flat CAD Mode — pitch: 0)
const CAMPUS_CENTER = {
	longitude: 106.811441,
	latitude: 10.8405,
	zoom: 18.4,
	pitch: 0,
	bearing: -12,
};

const VISIBLE_BUILDINGS_META = DEFAULT_BUILDINGS_META.filter(
	(b) => !matchesBuilding(b.name, "Tòa Alpha"),
);

const MAP_LIGHT = {
	anchor: "viewport",
	color: "#ffffff",
	intensity: 0.45,
	position: [1.5, 205, 42],
};

const INTERACTIVE_3D_LAYERS = [
	"campus-2d-camera-pins-layer",
	"campus-3d-rooms-layer",
	"campus-3d-slabs-layer",
	"campus-3d-walls-layer",
	"campus-3d-ghost-rooms-layer",
	"campus-3d-ghost-slabs-layer",
	"campus-3d-ghost-walls-layer",
];

const LABEL_MIN_ZOOM = 18.2;

const AREA_LEVEL_CARDS = [
	{
		value: "PUBLIC",
		name: "Công khai",
		level: 1,
		color: "var(--theme-success)",
	},
	{
		value: "CONFIDENTIAL",
		name: "Bảo mật",
		level: 2,
		color: "var(--theme-warning)",
	},
	{
		value: "HIGHLY_CONFIDENTIAL",
		name: "Bảo mật cao",
		level: 3,
		color: "var(--theme-danger)",
	},
];

const AREA_LEVEL_BTN_CLASS = {
	PUBLIC: "area-level-btn--public",
	CONFIDENTIAL: "area-level-btn--internal",
	HIGHLY_CONFIDENTIAL: "area-level-btn--private",
};

/** Kiểm tra cặp [lng, lat] hợp lệ theo ràng buộc của AreaUpdateRequest. */
function isValidLngLat(coords) {
	if (!Array.isArray(coords) || coords.length !== 2) return false;
	const [lng, lat] = coords;
	return (
		Number.isFinite(lng) &&
		Number.isFinite(lat) &&
		lat >= -90 &&
		lat <= 90 &&
		lng >= -180 &&
		lng <= 180
	);
}

function stopMapEvent(e) {
	e?.originalEvent?.stopPropagation?.();
}

/**
 * Trích xuất tọa độ thực tế từ CSDL.
 * Tuyệt đối không dùng heuristic tên gọi, không bịa tọa độ giả định.
 */
export function getValidCoordinates(area) {
	if (!area || area.centerLatitude == null || area.centerLongitude == null) {
		return null;
	}
	const lat = Number(area.centerLatitude);
	const lng = Number(area.centerLongitude);
	if (
		Number.isFinite(lat) &&
		Number.isFinite(lng) &&
		lat >= -90 &&
		lat <= 90 &&
		lng >= -180 &&
		lng <= 180 &&
		(lat !== 0 || lng !== 0)
	) {
		return [lng, lat];
	}
	return null;
}

export default function CampusMapView({
	areas = [],
	selectedAreaId,
	cameraCounts = {},
	cameras = [],
	cameraStatsByArea = {},
	selectedCameraId = null,
	onSelectArea,
	onSelectCameraBadge = null,
	pickingCameraArea = false,
	onCancelPickCameraArea = null,
	onAreaPickedForCamera = null,
	cameraMapPlacements = {},
	pickingCameraLocation = false,
	pickingCameraTargetOverride = null,
	onCancelPickCameraLocation = null,
	onCameraLocationPicked = null,
	onCameraPlacementRejected = null,
	onSelectCameraPin = null,
	onUpdateCameraMapLocation = null,
	onCommitCameraLocation = null,
	canDragCameraPin = true,
	rightDrawerContent = null,
	isAdmin = false,
	isFacilityManager = false,
	onAreaUpdated,
	buildingsList = [],
	selectedBuilding = "ALL",
	selectedFloor = "ALL",
	onSyncMapFilter = null,
	isCreatingArea = false,
	onStartCreateFromRoom,
	onCancelCreateArea,
	onAreaCreated,
	isCreatingCamera = false,
	createCameraDefaultAreaId = "",
	onCancelCreateCamera = null,
	onCameraCreated = null,
	rebindAreaRequestId = null,
	onRebindAreaDone,
	hideRail = false,
	isLeftSidebarCollapsed = false,
	onToggleLeftSidebar,
}) {
	const mapRef = useRef(null);
	const [railCollapsed, setRailCollapsed] = useState(false);
	const [mapZoom, setMapZoom] = useState(CAMPUS_CENTER.zoom);
	const compactLabels = mapZoom < LABEL_MIN_ZOOM;
	const [hoverCursor, setHoverCursor] = useState("grab");
	const [hoveredRoom, setHoveredRoom] = useState(null);
	const pendingRoomSeedRef = useRef(null);

	// ===== 2D Flat Vector Geometry Store & Floor Slicing (NO PNG OVERLAY IN PRODUCTION) =====
	const [geometryStore, setGeometryStore] = useState(() => loadGeometryStore());
	const [activeBuilding3d, setActiveBuilding3d] = useState("Tòa Epsilon");
	const [activeFloorOrder3d, setActiveFloorOrder3d] = useState(0);
	const [floorGapStep] = useState(FLOOR_ELEVATION_STEP);

	// Chế độ tạo camera trực tiếp ở Right-Rail + chấm ghim trên bản đồ 2D (Hướng A)
	const [createCamForm, setCreateCamForm] = useState({
		name: "",
		cameraType: "RESTRICTED_AREA",
		areaId: "",
		mapBuilding: "Tòa Epsilon",
		mapFloorOrder: 0,
	});
	const [createCamPinDraft, setCreateCamPinDraft] = useState(null); // { lng, lat, building, floorOrder }
	const [creatingCamLoading, setCreatingCamLoading] = useState(false);
	const [createCamError, setCreateCamError] = useState(null);

	useEffect(() => {
		const refreshGeometry = () => setGeometryStore(loadGeometryStore());
		window.addEventListener("icss-3d-geometry-updated", refreshGeometry);
		return () =>
			window.removeEventListener("icss-3d-geometry-updated", refreshGeometry);
	}, []);

	const activeBuildingMeta3d = useMemo(
		() =>
			DEFAULT_BUILDINGS_META.find((b) =>
				matchesBuilding(activeBuilding3d, b.name),
			) || null,
		[activeBuilding3d],
	);

	const allDbFloors = useMemo(
		() =>
			buildingsList.flatMap((b) =>
				(b.floors || []).map((fl) => ({
					...fl,
					buildingName: fl.buildingName || fl.building?.name || b.name,
				})),
			),
		[buildingsList],
	);

	// Chế độ tạo khu vực trên bản đồ (Map-First Creation from 3D room block)
	const [createDraftCoords, setCreateDraftCoords] = useState(null); // [lng, lat]
	const [createDraftRoomFaceId, setCreateDraftRoomFaceId] = useState(null);
	const [createFormData, setCreateFormData] = useState({
		name: "",
		areaLevel: "PUBLIC",
		building: "",
		floor: "",
		floorId: null,
	});
	const [creatingAreaLoading, setCreatingAreaLoading] = useState(false);
	const [createFormError, setCreateFormError] = useState(null);

	// Chế độ đổi block phòng 3D (chỉ ADMIN)
	const [editingAreaId, setEditingAreaId] = useState(null);
	const [draftCoords, setDraftCoords] = useState(null); // [lng, lat]
	const [draftRoomMeta, setDraftRoomMeta] = useState(null);
	const [savingCoords, setSavingCoords] = useState(false);
	const [coordError, setCoordError] = useState(null);
	const [coordSuccess, setCoordSuccess] = useState(null);
	const isEditingCoords = Boolean(editingAreaId);

	const activeCreatingPreview = useMemo(() => {
		if (isCreatingArea && createDraftRoomFaceId) {
			return {
				faceId: createDraftRoomFaceId,
				name: createFormData.name,
				areaLevel: createFormData.areaLevel,
			};
		}
		if (isEditingCoords && draftRoomMeta?.faceId) {
			const targetEditing = areas.find((a) => a.id === editingAreaId);
			return {
				faceId: draftRoomMeta.faceId,
				name: targetEditing?.name || "Vị trí mới",
				areaLevel: extractAreaLevelCode(targetEditing) || "CONFIDENTIAL",
			};
		}
		return null;
	}, [
		isCreatingArea,
		createDraftRoomFaceId,
		createFormData.name,
		createFormData.areaLevel,
		isEditingCoords,
		draftRoomMeta,
		areas,
		editingAreaId,
	]);

	const pickingTargetCamera = useMemo(() => {
		const base = selectedCameraId
			? cameras.find((c) => c.id === selectedCameraId) || null
			: null;
		if (!base) return null;
		if (!pickingCameraTargetOverride) return base;
		const nextType = pickingCameraTargetOverride.cameraType || base.cameraType;
		const isCorr = String(nextType || "").toUpperCase() === "CORRIDOR";
		const nextAreaId = isCorr
			? null
			: (pickingCameraTargetOverride.areaId ?? null);
		const nextArea = nextAreaId
			? areas.find((a) => String(a.id) === String(nextAreaId)) || null
			: null;
		return {
			...base,
			cameraType: nextType,
			areaId: nextAreaId,
			assignedArea: nextArea,
			assignedAreas: nextArea ? [nextArea] : [],
			mapBuilding: nextArea?.building || base.mapBuilding,
			mapFloorOrder: nextArea?.floor
				? resolveFloorOrder(nextArea.building, nextArea.floor)
				: base.mapFloorOrder,
		};
	}, [areas, cameras, pickingCameraTargetOverride, selectedCameraId]);

	const pickingTargetAreaInfo = useMemo(
		() =>
			pickingTargetCamera ? extractCameraAreaInfo(pickingTargetCamera) : null,
		[pickingTargetCamera],
	);

	const pickingTargetArea = useMemo(() => {
		const aId = pickingTargetAreaInfo?.areaId;
		return aId ? areas.find((a) => a.id === aId) || null : null;
	}, [areas, pickingTargetAreaInfo]);

	const createCamSelectedArea = useMemo(() => {
		if (!isCreatingCamera || createCamForm.cameraType === "CORRIDOR") {
			return null;
		}
		return (
			areas.find((a) => String(a.id) === String(createCamForm.areaId)) || null
		);
	}, [isCreatingCamera, createCamForm.cameraType, createCamForm.areaId, areas]);

	const effectiveSelectedAreaId = useMemo(
		() =>
			isCreatingCamera && createCamSelectedArea?.id
				? createCamSelectedArea.id
				: pickingCameraLocation && pickingTargetAreaInfo?.areaId
					? pickingTargetAreaInfo.areaId
					: selectedAreaId,
		[
			isCreatingCamera,
			createCamSelectedArea,
			pickingCameraLocation,
			pickingTargetAreaInfo,
			selectedAreaId,
		],
	);

	const effectiveBuilding3d =
		activeBuilding3d === "ALL" || matchesBuilding(activeBuilding3d, "Tòa Alpha")
			? "Tòa Epsilon"
			: activeBuilding3d;

	// Ở chế độ phẳng 2D (pitch: 0), luôn hiển thị 1 mặt sàn tại một thời điểm (mặc định Tầng Trệt = 0 khi chọn ALL)
	const renderedFloorOrder2d =
		activeFloorOrder3d === "ALL" || activeFloorOrder3d == null
			? 0
			: Number(activeFloorOrder3d);

	const {
		slabsGeoJSON,
		wallsGeoJSON,
		roomsGeoJSON,
		doorsGeoJSON,
		roomLabelsGeoJSON,
		portalSymbolsGeoJSON,
		buildingLabelsGeoJSON,
	} = useMemo(() => {
		const layers = buildVector3dLayers({
			store: geometryStore,
			selectedBuilding: effectiveBuilding3d,
			activeFloorOrder: renderedFloorOrder2d,
			areas,
			selectedAreaId: effectiveSelectedAreaId,
			creatingPreview: activeCreatingPreview,
			viewRole: isAdmin ? "ADMIN" : "FACILITY_MANAGER",
			showOtherBuildingsInFocus: false,
			floorElevationStep: floorGapStep,
		});
		return {
			...layers,
			buildingLabelsGeoJSON: {
				...layers.buildingLabelsGeoJSON,
				features: (layers.buildingLabelsGeoJSON?.features || []).filter(
					(f) => !matchesBuilding(f.properties?.building, "Tòa Alpha"),
				),
			},
		};
	}, [
		geometryStore,
		effectiveBuilding3d,
		renderedFloorOrder2d,
		areas,
		effectiveSelectedAreaId,
		activeCreatingPreview,
		isAdmin,
		floorGapStep,
	]);

	const cameraBadgesGeoJSON = useMemo(
		() =>
			buildCameraMapMarkersGeoJSON({
				cameras,
				areas,
				store: geometryStore,
				selectedBuilding: effectiveBuilding3d,
				activeFloorOrder: renderedFloorOrder2d,
				selectedAreaId: effectiveSelectedAreaId,
				selectedCameraId,
				floorElevationStep: floorGapStep,
			}),
		[
			cameras,
			areas,
			geometryStore,
			effectiveBuilding3d,
			renderedFloorOrder2d,
			effectiveSelectedAreaId,
			selectedCameraId,
			floorGapStep,
		],
	);

	const draggingPinRef = useRef(null);
	const justDraggedPinRef = useRef(false);

	useEffect(() => {
		if (
			!pickingCameraLocation ||
			typeof onCancelPickCameraLocation !== "function"
		) {
			return undefined;
		}
		const handleKeyDown = (ev) => {
			if (ev.key === "Escape") {
				onCancelPickCameraLocation();
			}
		};
		window.addEventListener("keydown", handleKeyDown);
		return () => window.removeEventListener("keydown", handleKeyDown);
	}, [pickingCameraLocation, onCancelPickCameraLocation]);

	const cameraMapLayers = useMemo(
		() =>
			buildCameraMapPinGeoJson({
				cameras,
				placementsById: cameraMapPlacements,
				building: effectiveBuilding3d,
				floorOrder: renderedFloorOrder2d,
				selectedCameraId,
				floorElevationStep: floorGapStep,
				floorSlabHeight: FLOOR_SLAB_HEIGHT,
				draftPin:
					isCreatingCamera && createCamPinDraft
						? {
								...createCamPinDraft,
								name:
									createCamForm.cameraType === "CORRIDOR"
										? normalizeCorridorCameraName(createCamForm.name) ||
											"Hành lang mới"
										: createCamForm.name || "Camera mới",
							}
						: null,
			}),
		[
			cameras,
			cameraMapPlacements,
			effectiveBuilding3d,
			renderedFloorOrder2d,
			selectedCameraId,
			floorGapStep,
			isCreatingCamera,
			createCamPinDraft,
			createCamForm.cameraType,
			createCamForm.name,
		],
	);

	const cameraPinsGeoJSON = useMemo(
		() => ({
			type: "FeatureCollection",
			features: cameraMapLayers.pinFeatures,
		}),
		[cameraMapLayers.pinFeatures],
	);

	useEffect(() => {
		const map = mapRef.current?.getMap?.() || mapRef.current;
		if (!map) return;
		registerCameraBadgeSprites(map, cameraBadgesGeoJSON);
		registerCameraBadgeSprites(map, cameraPinsGeoJSON);
	}, [cameraBadgesGeoJSON, cameraPinsGeoJSON]);

	// Mục 1B: đồng bộ ảnh Canvas Sprite nhãn phòng ngay khi roomLabelsGeoJSON thay đổi
	useEffect(() => {
		const map = mapRef.current?.getMap?.() || mapRef.current;
		if (map) {
			registerRoomLabelSprites(map, roomLabelsGeoJSON);
		}
	}, [roomLabelsGeoJSON]);

	const slabsSplit = useMemo(
		() => splitGhostActiveGeoJSON(slabsGeoJSON),
		[slabsGeoJSON],
	);
	const wallsSplit = useMemo(
		() => splitGhostActiveGeoJSON(wallsGeoJSON),
		[wallsGeoJSON],
	);
	const roomsSplit = useMemo(
		() => splitGhostActiveGeoJSON(roomsGeoJSON),
		[roomsGeoJSON],
	);
	const selectedRoomGeoJSON = useMemo(() => {
		const selected = (roomsSplit.active?.features || []).filter(
			(f) => f.properties?.isSelected === true,
		);
		return { type: "FeatureCollection", features: selected };
	}, [roomsSplit.active]);
	const doorsActiveGeoJSON = useMemo(() => {
		const activeDoors = (doorsGeoJSON?.features || []).filter(
			(f) => !f.properties?.isGhost,
		);
		return { type: "FeatureCollection", features: activeDoors };
	}, [doorsGeoJSON]);
	const doorSwingGeoJSON = useMemo(() => {
		const swingFeatures = [];
		for (const f of doorsActiveGeoJSON.features) {
			const p = f.properties || {};
			if (
				!Number.isFinite(p.jambALng) ||
				!Number.isFinite(p.jambALat) ||
				!Number.isFinite(p.jambBLng) ||
				!Number.isFinite(p.jambBLat)
			) {
				continue;
			}
			const cosLat = Math.max(
				0.2,
				Math.cos(((p.midLat ?? p.jambALat) * Math.PI) / 180),
			);
			const dx = p.jambBLng - p.jambALng;
			const dy = p.jambBLat - p.jambALat;
			const mx = dx * cosLat;
			const my = dy;
			const perpLng = -my / cosLat;
			const perpLat = mx;

			// 90-degree open door leaf line
			swingFeatures.push({
				type: "Feature",
				properties: { kind: "leaf", isPerimeter: Boolean(p.isPerimeter) },
				geometry: {
					type: "LineString",
					coordinates: [
						[p.jambALng, p.jambALat],
						[p.jambALng + perpLng, p.jambALat + perpLat],
					],
				},
			});

			// Quarter-circle door swing arc
			const arcCoords = [];
			const steps = 10;
			for (let i = 0; i <= steps; i++) {
				const angle = (i / steps) * (Math.PI / 2);
				arcCoords.push([
					p.jambALng + dx * Math.cos(angle) + perpLng * Math.sin(angle),
					p.jambALat + dy * Math.cos(angle) + perpLat * Math.sin(angle),
				]);
			}
			swingFeatures.push({
				type: "Feature",
				properties: { kind: "arc", isPerimeter: Boolean(p.isPerimeter) },
				geometry: {
					type: "LineString",
					coordinates: arcCoords,
				},
			});
		}
		return { type: "FeatureCollection", features: swingFeatures };
	}, [doorsActiveGeoJSON]);

	// Refs tránh feedback-loop khi đồng bộ 2 chiều giữa Sidebar và Slicer 3D
	const slicerStateRef = useRef({ building: "ALL", floorOrder: "ALL" });
	slicerStateRef.current = {
		building: activeBuilding3d,
		floorOrder: activeFloorOrder3d,
	};
	const sidebarFilterRef = useRef({
		building: selectedBuilding,
		floor: selectedFloor,
	});
	sidebarFilterRef.current = {
		building: selectedBuilding,
		floor: selectedFloor,
	};

	const notifyParentMapFilter = useCallback(
		(buildingName, floorOrderVal) => {
			if (
				typeof onSyncMapFilter !== "function" ||
				isCreatingArea ||
				isEditingCoords
			) {
				return;
			}
			const mapped = map3dSelectionToSidebar({
				buildingName,
				floorOrder: floorOrderVal,
				buildings: buildingsList,
			});
			const cur = sidebarFilterRef.current;
			if (mapped.building !== cur.building || mapped.floor !== cur.floor) {
				onSyncMapFilter(mapped.building, mapped.floor);
			}
		},
		[onSyncMapFilter, buildingsList, isCreatingArea, isEditingCoords],
	);

	const handleSelect3dBuilding = useCallback(
		(bName, targetFloorOrder = 0, { syncParent = true, fly = true } = {}) => {
			const effectiveBName =
				bName === "ALL" || matchesBuilding(bName, "Tòa Alpha")
					? "Tòa Epsilon"
					: bName;
			setActiveBuilding3d(bName === "ALL" ? "Tòa Epsilon" : bName);
			const bld =
				DEFAULT_BUILDINGS_META.find((b) =>
					matchesBuilding(effectiveBName, b.name),
				) || VISIBLE_BUILDINGS_META[0];
			if (bld) {
				let resolvedFl = 0;
				if (targetFloorOrder !== "ALL" && targetFloorOrder != null) {
					const validFloor = bld.floors.some(
						(f) => f.floorOrder === Number(targetFloorOrder),
					);
					resolvedFl = validFloor ? Number(targetFloorOrder) : 0;
				}
				setActiveFloorOrder3d(resolvedFl);
				if (syncParent) {
					notifyParentMapFilter(
						bName === "ALL" ? "ALL" : bld.name,
						targetFloorOrder === "ALL" ? "ALL" : resolvedFl,
					);
				}
				if (fly && mapRef.current) {
					mapRef.current.flyTo({
						center: bld.center,
						zoom: 18.5,
						pitch: 0,
						bearing: -12,
						duration: 600,
					});
				}
			}
		},
		[notifyParentMapFilter],
	);

	// Mục 3A: Đồng bộ 2 chiều từ bộ lọc Sidebar -> Slicer 3D trên bản đồ (chống lặp vòng)
	const prevSyncedSidebarRef = useRef({
		building: selectedBuilding,
		floor: selectedFloor,
	});
	useEffect(() => {
		const prev = prevSyncedSidebarRef.current;
		if (prev.building === selectedBuilding && prev.floor === selectedFloor) {
			return;
		}
		prevSyncedSidebarRef.current = {
			building: selectedBuilding,
			floor: selectedFloor,
		};
		if (isCreatingArea || isEditingCoords) return;

		const next3d = mapSidebarSelectionTo3d({
			selectedBuilding,
			selectedFloor,
		});
		const cur = slicerStateRef.current;
		const sameBuilding =
			next3d.building === "ALL"
				? cur.building === "ALL"
				: matchesBuilding(cur.building, next3d.building);
		const sameFloor = next3d.floorOrder === cur.floorOrder;
		if (sameBuilding && sameFloor) return;

		handleSelect3dBuilding(next3d.building, next3d.floorOrder, {
			syncParent: false,
			fly: !selectedAreaId && !selectedCameraId,
		});
	}, [
		selectedBuilding,
		selectedFloor,
		selectedAreaId,
		selectedCameraId,
		isCreatingArea,
		isEditingCoords,
		handleSelect3dBuilding,
	]);

	const hasRightDrawer = Boolean(rightDrawerContent);

	// Tự động resize map khi sidebar trái hoặc drawer phải thu gọn / mở rộng
	useEffect(() => {
		const timer = setTimeout(() => {
			if (mapRef.current) {
				mapRef.current.resize();
			}
		}, 250);
		return () => clearTimeout(timer);
	}, [isLeftSidebarCollapsed, hasRightDrawer]);

	// Tự động điền dữ liệu tòa nhà / tầng ban đầu khi mở chế độ tạo (chỉ chạy khi chuyển false -> true, tránh ghi đè khi đổi tầng/phòng)
	const prevIsCreatingRef = useRef(false);
	useEffect(() => {
		if (!isCreatingArea) {
			prevIsCreatingRef.current = false;
			setCreateDraftRoomFaceId(null);
			return;
		}
		const seeded = pendingRoomSeedRef.current;
		if (seeded) {
			pendingRoomSeedRef.current = null;
			prevIsCreatingRef.current = true;
			setActiveBuilding3d(seeded.building);
			setActiveFloorOrder3d(seeded.floorOrder);
			setCreateDraftRoomFaceId(seeded.faceId);
			setCreateDraftCoords([seeded.centerLongitude, seeded.centerLatitude]);
			setCreateFormData({
				name: "",
				areaLevel: "PUBLIC",
				building: seeded.building,
				floor: seeded.floor,
				floorId: seeded.floorId ?? null,
			});
			setCreateFormError(null);
			setRailCollapsed(false);
			return;
		}
		if (prevIsCreatingRef.current) {
			return;
		}
		prevIsCreatingRef.current = true;
		const initialBuilding =
			activeBuilding3d && activeBuilding3d !== "ALL"
				? activeBuilding3d
				: selectedBuilding && selectedBuilding !== "ALL"
					? selectedBuilding
					: buildingsList[0]?.name || "Tòa Alpha";
		const bObj = buildingsList.find((b) =>
			matchesBuilding(b.name, initialBuilding),
		);
		const firstFloors = bObj?.floors || [];
		const matchedActiveFloor =
			activeFloorOrder3d !== "ALL"
				? firstFloors.find(
						(f) =>
							(f.floorOrder != null
								? Number(f.floorOrder)
								: resolveFloorOrder(bObj?.name || initialBuilding, f.name)) ===
							Number(activeFloorOrder3d),
					)
				: null;
		const initialFloor =
			matchedActiveFloor?.name ||
			(selectedFloor && selectedFloor !== "ALL"
				? selectedFloor
				: firstFloors[0]?.name || "Tầng Trệt");
		const initialFloorId =
			matchedActiveFloor?.id ||
			firstFloors.find((f) => f.name === initialFloor)?.id ||
			firstFloors[0]?.id ||
			null;

		setCreateFormData({
			name: "",
			areaLevel: "PUBLIC",
			building: bObj?.name || initialBuilding,
			floor: initialFloor,
			floorId: initialFloorId,
		});
		setCreateDraftCoords(null);
		setCreateDraftRoomFaceId(null);
		setCreateFormError(null);
		setRailCollapsed(false);
	}, [
		isCreatingArea,
		buildingsList,
		selectedBuilding,
		selectedFloor,
		activeBuilding3d,
		activeFloorOrder3d,
	]);

	// Hỗ trợ mở nhanh chế độ "Đổi block phòng 3D" từ nút trong Modal Sửa của AreaListPage
	useEffect(() => {
		if (!isAdmin || !rebindAreaRequestId) return;
		if (editingAreaId === rebindAreaRequestId) return;
		const target = areas.find((a) => a.id === rebindAreaRequestId);
		if (!target) return;
		const matchedBld = DEFAULT_BUILDINGS_META.find((b) =>
			matchesBuilding(target.building, b.name),
		);
		if (matchedBld) {
			setActiveBuilding3d(matchedBld.name);
			setActiveFloorOrder3d(resolveFloorOrder(matchedBld.name, target.floor));
		}
		setEditingAreaId(target.id);
		setDraftCoords(getValidCoordinates(target));
		setDraftRoomMeta(null);
		setCoordError(null);
		setCoordSuccess(null);
		const cur = getValidCoordinates(target);
		if (cur && mapRef.current) {
			mapRef.current.flyTo({
				center: cur,
				zoom: 20.6,
				pitch: 0,
				duration: 600,
			});
		}
	}, [isAdmin, rebindAreaRequestId, editingAreaId, areas]);

	// Khởi tạo dữ liệu form tạo camera ở Right-Rail khi chuyển sang chế độ Thêm Camera
	const prevIsCreatingCamRef = useRef(false);
	useEffect(() => {
		if (!isCreatingCamera) {
			prevIsCreatingCamRef.current = false;
			setCreateCamPinDraft(null);
			setCreateCamError(null);
			return;
		}
		if (prevIsCreatingCamRef.current) return;
		prevIsCreatingCamRef.current = true;

		let initialType = "RESTRICTED_AREA";
		let initialArea = null;
		if (createCameraDefaultAreaId) {
			initialArea =
				areas.find((a) => String(a.id) === String(createCameraDefaultAreaId)) ||
				null;
			if (initialArea) {
				const rawLvl =
					typeof initialArea.areaLevel === "string"
						? initialArea.areaLevel
						: initialArea.areaLevel?.code || "";
				initialType =
					String(rawLvl).toUpperCase() === "PUBLIC"
						? "PUBLIC_AREA"
						: "RESTRICTED_AREA";
			}
		}
		if (!initialArea) {
			const filtered = filterAreasForCameraType(areas, initialType);
			initialArea =
				filtered.find(
					(a) =>
						matchesBuilding(a.building, effectiveBuilding3d) &&
						resolveFloorOrder(a.building, a.floor) === renderedFloorOrder2d,
				) ||
				filtered[0] ||
				null;
		}

		const targetBuilding = initialArea?.building || effectiveBuilding3d;
		const targetFloorOrder = initialArea
			? resolveFloorOrder(initialArea.building, initialArea.floor)
			: renderedFloorOrder2d;

		setActiveBuilding3d(targetBuilding);
		setActiveFloorOrder3d(targetFloorOrder);
		setCreateCamForm({
			name: "",
			cameraType: initialType,
			areaId: initialArea?.id || "",
			mapBuilding: targetBuilding,
			mapFloorOrder: targetFloorOrder,
		});
		setCreateCamPinDraft(null);
		setCreateCamError(null);
		setRailCollapsed(false);
	}, [
		isCreatingCamera,
		createCameraDefaultAreaId,
		areas,
		effectiveBuilding3d,
		renderedFloorOrder2d,
	]);

	// Khi chọn 1 Area từ danh sách bên trái, tự động mở đúng tòa nhà và cắt đúng tầng của Area đó.
	// Không flyTo lại khi người dùng vừa click trực tiếp khối phòng trên bản đồ 3D.
	const skipNextAreaFlyRef = useRef(false);
	const prevSyncedAreaIdRef = useRef(selectedAreaId);
	useEffect(() => {
		if (!selectedAreaId || prevSyncedAreaIdRef.current === selectedAreaId) {
			prevSyncedAreaIdRef.current = selectedAreaId;
			return;
		}
		prevSyncedAreaIdRef.current = selectedAreaId;
		if (skipNextAreaFlyRef.current) {
			skipNextAreaFlyRef.current = false;
			return;
		}
		if (isCreatingArea || isCreatingCamera || isEditingCoords) return;
		const area = areas.find((a) => a.id === selectedAreaId);
		if (!area) return;
		const matchedBld = DEFAULT_BUILDINGS_META.find((b) =>
			matchesBuilding(area.building, b.name),
		);
		if (matchedBld) {
			const flOrder = resolveFloorOrder(matchedBld.name, area.floor);
			setActiveBuilding3d(matchedBld.name);
			setActiveFloorOrder3d(flOrder);
			const coords = getValidCoordinates(area);
			if (mapRef.current) {
				mapRef.current.flyTo({
					center: coords || matchedBld.center,
					zoom: coords ? 20.6 : 18.7,
					pitch: 0,
					bearing: -12,
					duration: 700,
				});
			}
		}
	}, [
		selectedAreaId,
		areas,
		isCreatingArea,
		isCreatingCamera,
		isEditingCoords,
	]);

	// Khi chọn 1 Camera từ danh sách bên trái, tự động mở đúng tòa + tầng và zoom vào vị trí ghim camera
	const skipNextCameraFlyRef = useRef(false);
	const prevSyncedCameraIdRef = useRef(selectedCameraId);
	useEffect(() => {
		if (!selectedCameraId || prevSyncedCameraIdRef.current === selectedCameraId) {
			prevSyncedCameraIdRef.current = selectedCameraId;
			return;
		}
		prevSyncedCameraIdRef.current = selectedCameraId;
		if (skipNextCameraFlyRef.current) {
			skipNextCameraFlyRef.current = false;
			return;
		}
		if (isCreatingArea || isCreatingCamera || isEditingCoords) return;
		const cam = cameras.find((c) => c.id === selectedCameraId);
		if (!cam) return;

		const placement = cameraMapPlacements?.[selectedCameraId];
		const area = cam.areaId
			? areas.find((a) => String(a.id) === String(cam.areaId))
			: null;
		const targetBuilding =
			placement?.building || cam.mapBuilding || area?.building || null;
		const matchedBld = targetBuilding
			? DEFAULT_BUILDINGS_META.find((b) =>
					matchesBuilding(targetBuilding, b.name),
				)
			: null;

		const lng = Number(placement?.lng ?? cam.mapLongitude);
		const lat = Number(placement?.lat ?? cam.mapLatitude);
		const hasPinCoords =
			Number.isFinite(lng) &&
			Number.isFinite(lat) &&
			!(lng === 0 && lat === 0);
		const areaCoords = area ? getValidCoordinates(area) : null;
		const targetCenter = hasPinCoords
			? [lng, lat]
			: areaCoords || matchedBld?.center || null;

		if (matchedBld) {
			const flOrder =
				placement?.floorOrder ??
				cam.mapFloorOrder ??
				(area ? resolveFloorOrder(matchedBld.name, area.floor) : 0);
			setActiveBuilding3d(matchedBld.name);
			setActiveFloorOrder3d(flOrder);
		}
		if (targetCenter && mapRef.current) {
			mapRef.current.flyTo({
				center: targetCenter,
				zoom: hasPinCoords || areaCoords ? 20.6 : 18.7,
				pitch: 0,
				bearing: -12,
				duration: 700,
			});
		}
	}, [
		selectedCameraId,
		cameras,
		cameraMapPlacements,
		areas,
		isCreatingArea,
		isCreatingCamera,
		isEditingCoords,
	]);

	const filteredCreateCamAreas = useMemo(
		() => filterAreasForCameraType(areas, createCamForm.cameraType),
		[areas, createCamForm.cameraType],
	);

	const handleCreateCamTypeChange = (nextType) => {
		const nextFiltered = filterAreasForCameraType(areas, nextType);
		const nextArea =
			nextType === "CORRIDOR"
				? null
				: nextFiltered.find(
						(a) =>
							matchesBuilding(a.building, effectiveBuilding3d) &&
							resolveFloorOrder(a.building, a.floor) === renderedFloorOrder2d,
					) ||
					nextFiltered[0] ||
					null;
		const nextBldg = nextArea?.building || effectiveBuilding3d;
		const nextFlOrder = nextArea
			? resolveFloorOrder(nextArea.building, nextArea.floor)
			: renderedFloorOrder2d;
		if (nextArea) {
			setActiveBuilding3d(nextBldg);
			setActiveFloorOrder3d(nextFlOrder);
			const coords = getValidCoordinates(nextArea);
			if (coords && mapRef.current) {
				mapRef.current.flyTo({
					center: coords,
					zoom: 20.6,
					pitch: 0,
					duration: 500,
				});
			}
		}
		setCreateCamForm((prev) => ({
			...prev,
			cameraType: nextType,
			areaId: nextArea?.id || "",
			mapBuilding: nextBldg,
			mapFloorOrder: nextFlOrder,
		}));
		setCreateCamPinDraft(null);
		setCreateCamError(null);
	};

	const handleCreateCamAreaChange = (nextAreaId) => {
		const targetArea = areas.find((a) => String(a.id) === String(nextAreaId));
		const nextBldg = targetArea?.building || effectiveBuilding3d;
		const nextFlOrder = targetArea
			? resolveFloorOrder(targetArea.building, targetArea.floor)
			: renderedFloorOrder2d;
		if (targetArea) {
			setActiveBuilding3d(nextBldg);
			setActiveFloorOrder3d(nextFlOrder);
			const coords = getValidCoordinates(targetArea);
			if (coords && mapRef.current) {
				mapRef.current.flyTo({
					center: coords,
					zoom: 20.6,
					pitch: 0,
					duration: 500,
				});
			}
		}
		setCreateCamForm((prev) => ({
			...prev,
			areaId: nextAreaId,
			mapBuilding: nextBldg,
			mapFloorOrder: nextFlOrder,
		}));
		setCreateCamPinDraft(null);
		setCreateCamError(null);
	};

	const handleCreateCamFilterChange = ({ building, floor }) => {
		const matchedBld =
			building && building !== "ALL"
				? DEFAULT_BUILDINGS_META.find((b) => matchesBuilding(building, b.name))
				: null;
		if (matchedBld) {
			const flOrder =
				floor && floor !== "ALL"
					? resolveFloorOrder(matchedBld.name, floor)
					: renderedFloorOrder2d;
			setActiveBuilding3d(matchedBld.name);
			setActiveFloorOrder3d(flOrder);
			if (mapRef.current) {
				mapRef.current.flyTo({
					center: matchedBld.center,
					zoom: 18.6,
					pitch: 0,
					duration: 500,
				});
			}
		}
		const currentArea = areas.find(
			(a) => String(a.id) === String(createCamForm.areaId),
		);
		const matchesNewFilter =
			currentArea &&
			(building === "ALL" || matchesBuilding(currentArea.building, building)) &&
			(floor === "ALL" || currentArea.floor === floor);
		if (!matchesNewFilter && createCamForm.areaId) {
			setCreateCamForm((prev) => ({
				...prev,
				areaId: "",
				mapBuilding: matchedBld?.name || prev.mapBuilding,
				mapFloorOrder:
					matchedBld && floor && floor !== "ALL"
						? resolveFloorOrder(matchedBld.name, floor)
						: prev.mapFloorOrder,
			}));
			setCreateCamPinDraft(null);
		}
	};

	const isCreateCamIncomplete = useMemo(() => {
		const isCorr = createCamForm.cameraType === "CORRIDOR";
		const effectiveName = isCorr
			? stripCorridorPrefix(createCamForm.name)
			: (createCamForm.name || "").trim();
		if (!effectiveName) return true;
		if (!isCorr && !createCamForm.areaId) return true;
		if (!createCamPinDraft) return true;
		return false;
	}, [createCamForm, createCamPinDraft]);

	const handleCreateCameraSubmit = async (e) => {
		e?.preventDefault?.();
		const isCorr = createCamForm.cameraType === "CORRIDOR";
		const effectiveName = isCorr
			? normalizeCorridorCameraName(createCamForm.name)
			: (createCamForm.name || "").trim();
		if (!effectiveName || (isCorr && !stripCorridorPrefix(effectiveName))) {
			setCreateCamError("Vui lòng nhập tên camera.");
			return;
		}
		if (!isCorr && !createCamForm.areaId) {
			setCreateCamError("Vui lòng chọn khu vực phụ trách cho camera.");
			return;
		}
		if (!createCamPinDraft) {
			setCreateCamError(
				isCorr
					? "Vui lòng nhấp vào vùng hành lang trên bản đồ 2D để ghim vị trí camera."
					: "Vui lòng nhấp vào bên trong phòng đã chọn trên bản đồ 2D để ghim vị trí camera.",
			);
			return;
		}

		setCreatingCamLoading(true);
		setCreateCamError(null);
		try {
			const payload = {
				name: effectiveName,
				cameraType: createCamForm.cameraType,
				areaId: isCorr ? null : createCamForm.areaId,
				mapLongitude: createCamPinDraft.lng,
				mapLatitude: createCamPinDraft.lat,
				mapBuilding: createCamPinDraft.building || createCamForm.mapBuilding,
				mapFloorOrder:
					createCamPinDraft.floorOrder ?? createCamForm.mapFloorOrder,
			};
			const created = await createCamera(payload);
			setCreateCamPinDraft(null);
			onCameraCreated?.(created);
		} catch (err) {
			setCreateCamError(getErrorMessage(err));
		} finally {
			setCreatingCamLoading(false);
		}
	};

	const isCreateFormIncomplete =
		!createDraftCoords ||
		!createFormData.name.trim() ||
		!createFormData.floorId;

	const handleCreateAreaSubmit = async (e) => {
		e?.preventDefault?.();
		if (!createDraftCoords || !createDraftRoomFaceId) {
			setCreateFormError("Vui lòng chọn trực tiếp một block phòng 3D trống.");
			return;
		}
		if (!createFormData.floorId) {
			setCreateFormError(
				"Tầng này hiện chỉ phục vụ mô phỏng 3D — chưa có dữ liệu tầng trong CSDL để lưu Khu vực mới.",
			);
			return;
		}
		const nameTrimmed = createFormData.name.trim();
		if (!nameTrimmed) {
			setCreateFormError("Tên khu vực không được để trống.");
			return;
		}
		// BE-09: cùng quy tắc backend (AreaValidator, 3–100 ký tự sau chuẩn hoá)
		const nameError = validateAreaName(nameTrimmed);
		if (nameError) {
			setCreateFormError(nameError);
			return;
		}

		setCreatingAreaLoading(true);
		setCreateFormError(null);
		try {
			const payload = {
				name: nameTrimmed,
				areaLevel: createFormData.areaLevel,
				building: createFormData.building,
				floor: createFormData.floor,
				floorId: createFormData.floorId,
				centerLatitude: createDraftCoords[1],
				centerLongitude: createDraftCoords[0],
			};
			const res = await createArea(payload);
			setCoordSuccess(`Đã tạo khu vực "${nameTrimmed}" thành công.`);
			setCreateDraftCoords(null);
			setCreateDraftRoomFaceId(null);
			onAreaCreated?.(res);
		} catch (err) {
			console.error("Create area failed:", err);
			setCreateFormError(getErrorMessage(err));
		} finally {
			setCreatingAreaLoading(false);
		}
	};

	const selectedArea = useMemo(() => {
		return areas.find((a) => a.id === selectedAreaId) || null;
	}, [areas, selectedAreaId]);

	const editingArea = useMemo(() => {
		return areas.find((a) => a.id === editingAreaId) || null;
	}, [areas, editingAreaId]);

	// Thoát chế độ đổi block phòng 3D nếu khu vực đang sửa bị lọc khỏi danh sách
	useEffect(() => {
		if (editingAreaId && !editingArea) {
			setEditingAreaId(null);
			setDraftCoords(null);
			setDraftRoomMeta(null);
			onRebindAreaDone?.();
		}
	}, [editingAreaId, editingArea, onRebindAreaDone]);

	// Tự ẩn thông báo thành công
	useEffect(() => {
		if (!coordSuccess) return undefined;
		const t = setTimeout(() => setCoordSuccess(null), 3000);
		return () => clearTimeout(t);
	}, [coordSuccess]);

	// Mục 3A: thống kê khu vực chưa gắn block 3D (đã bỏ render pin giọt nước khỏi bản đồ 3D)
	const unpositionedCount = useMemo(
		() => areas.filter((a) => !getValidCoordinates(a)).length,
		[areas],
	);

	// Di chuyển khung nhìn bản đồ về tọa độ
	const handleFlyToCoords = useCallback((coords, zoomLevel = 17.5) => {
		if (mapRef.current && coords) {
			mapRef.current.flyTo({
				center: coords,
				zoom: zoomLevel,
				duration: 800,
			});
		}
	}, []);

	// Chọn một khu vực cụ thể
	const handleSelectAreaItem = useCallback(
		(areaId, coords) => {
			if (coords) {
				handleFlyToCoords(coords);
			}
			if (onSelectArea) {
				onSelectArea(areaId);
			}
		},
		[handleFlyToCoords, onSelectArea],
	);

	// Về toàn cảnh khuôn viên (render toàn bộ tòa nhà)
	const handleResetCampusView = useCallback(() => {
		handleSelect3dBuilding("ALL");
	}, [handleSelect3dBuilding]);

	// ===== Đổi block phòng 3D (ADMIN) =====
	const handleStartEditCoords = useCallback(() => {
		if (!isAdmin || !selectedArea) return;
		const matchedBld = DEFAULT_BUILDINGS_META.find((b) =>
			matchesBuilding(selectedArea.building, b.name),
		);
		if (matchedBld) {
			setActiveBuilding3d(matchedBld.name);
			setActiveFloorOrder3d(
				resolveFloorOrder(matchedBld.name, selectedArea.floor),
			);
		}
		const current = getValidCoordinates(selectedArea);
		setEditingAreaId(selectedArea.id);
		setDraftCoords(current);
		setDraftRoomMeta(null);
		setCoordError(null);
		setCoordSuccess(null);
		if (current) handleFlyToCoords(current, 18.7);
	}, [isAdmin, selectedArea, handleFlyToCoords]);

	const handleCancelEditCoords = useCallback(() => {
		setEditingAreaId(null);
		setDraftCoords(null);
		setDraftRoomMeta(null);
		setCoordError(null);
		onRebindAreaDone?.();
	}, [onRebindAreaDone]);

	const handleSaveCoords = useCallback(async () => {
		if (!editingArea) return;
		if (!draftRoomMeta) {
			setCoordError("Vui lòng nhấp trực tiếp vào một block phòng 3D trống.");
			return;
		}
		if (!draftRoomMeta.floorId) {
			setCoordError(
				draftRoomMeta.warning ||
					"Tầng này hiện chỉ phục vụ mô phỏng 3D — chưa có dữ liệu tầng trong CSDL.",
			);
			return;
		}
		if (!isValidLngLat(draftCoords)) {
			setCoordError("Tọa độ block phòng 3D không hợp lệ.");
			return;
		}
		if (editingArea.version == null) {
			setCoordError("Thiếu phiên bản dữ liệu khu vực. Vui lòng tải lại trang.");
			return;
		}
		const [lng, lat] = draftCoords;
		setSavingCoords(true);
		setCoordError(null);
		try {
			// Đồng bộ building/floor/floorId theo block phòng 3D vừa chọn (BR-TC-13: gửi kèm version)
			const res = await updateArea(editingArea.id, {
				name: editingArea.name,
				areaLevel:
					typeof editingArea.areaLevel === "string"
						? editingArea.areaLevel
						: editingArea.areaLevel?.code,
				building: draftRoomMeta.building,
				floor: draftRoomMeta.floor,
				floorId: draftRoomMeta.floorId,
				centerLatitude: Number(lat.toFixed(7)),
				centerLongitude: Number(lng.toFixed(7)),
				version: editingArea.version,
			});
			onAreaUpdated?.(editingArea.id, res);
			setEditingAreaId(null);
			setDraftCoords(null);
			setDraftRoomMeta(null);
			setCoordSuccess(
				`Đã gắn "${editingArea.name}" sang block ${draftRoomMeta.building} · ${draftRoomMeta.floor}.`,
			);
			onRebindAreaDone?.();
		} catch (err) {
			setCoordError(getErrorMessage(err));
		} finally {
			setSavingCoords(false);
		}
	}, [
		editingArea,
		draftRoomMeta,
		draftCoords,
		onAreaUpdated,
		onRebindAreaDone,
	]);

	const handleMapMouseDown = useCallback(
		(e) => {
			if (!isAdmin || pickingCameraLocation || !canDragCameraPin) return;
			const map = mapRef.current?.getMap?.() || mapRef.current;
			const features = e?.features || [];
			const pinFeat = features.find(
				(f) => f.layer?.id === "campus-2d-camera-pins-layer",
			);
			if (pinFeat?.properties?.cameraId) {
				const camId = pinFeat.properties.cameraId;
				if (selectedCameraId && String(camId) !== String(selectedCameraId)) {
					return;
				}
				const targetCam = cameras.find((c) => c.id === camId) || null;
				if (
					targetCam?.status === "DECOMMISSIONED" ||
					pinFeat.properties.statusKey === "muted"
				) {
					setHoverCursor("not-allowed");
					if (e?.point) {
						setHoveredRoom({
							x: e.point.x,
							y: e.point.y,
							isOccupied: true,
							isInvalid: true,
							title: `${pinFeat.properties.cameraName || "Camera"} (${pinFeat.properties.cameraCode || ""})`,
							subtitle: "Camera đã ngừng hoạt động, không thể dời vị trí",
						});
					}
					return;
				}
				const placement = cameraMapPlacements?.[camId];
				if (!placement) return;
				const effectiveTargetCam =
					camId === selectedCameraId && pickingTargetCamera
						? pickingTargetCamera
						: targetCam;
				const camAreaInfo = effectiveTargetCam
					? extractCameraAreaInfo(effectiveTargetCam)
					: null;
				const camArea = camAreaInfo?.areaId
					? areas.find((a) => a.id === camAreaInfo.areaId) || null
					: null;
				e?.preventDefault?.();
				draggingPinRef.current = {
					mode: "move",
					cameraId: camId,
					targetCam: effectiveTargetCam,
					targetArea: camArea,
					building:
						effectiveTargetCam?.mapBuilding ?? placement.building ?? null,
					floorOrder:
						effectiveTargetCam?.mapFloorOrder ?? placement.floorOrder ?? null,
					origin: { lng: Number(placement.lng), lat: Number(placement.lat) },
					startPoint: e?.point
						? { x: Number(e.point.x), y: Number(e.point.y) }
						: null,
					hasDragged: false,
					isInvalidPlacement: false,
					invalidMessage: null,
					validationResult: null,
					latestCoords: null,
				};
				map?.dragPan?.disable?.();
				setHoverCursor("grabbing");
			}
		},
		[
			areas,
			cameraMapPlacements,
			cameras,
			canDragCameraPin,
			isAdmin,
			pickingCameraLocation,
			pickingTargetCamera,
			selectedCameraId,
		],
	);

	const handleMapMouseUp = useCallback(() => {
		const drag = draggingPinRef.current;
		if (!drag) return;
		draggingPinRef.current = null;
		const map = mapRef.current?.getMap?.() || mapRef.current;
		map?.dragPan?.enable?.();
		setHoverCursor("grab");
		setHoveredRoom(null);

		if (drag.mode === "move" && drag.hasDragged && drag.latestCoords) {
			justDraggedPinRef.current = true;
			setTimeout(() => {
				justDraggedPinRef.current = false;
			}, 0);

			if (drag.isInvalidPlacement) {
				onUpdateCameraMapLocation?.(drag.cameraId, {
					lng: drag.origin.lng,
					lat: drag.origin.lat,
				});
				onCameraPlacementRejected?.(
					drag.invalidMessage ||
						"Vị trí dời camera không hợp lệ, đã trả về vị trí cũ.",
				);
				return;
			}

			onCommitCameraLocation?.(drag.cameraId, {
				lng: drag.latestCoords.lng,
				lat: drag.latestCoords.lat,
				building: drag.building,
				floorOrder: drag.floorOrder,
			});
		}
	}, [
		onCameraPlacementRejected,
		onCommitCameraLocation,
		onUpdateCameraMapLocation,
	]);

	const handleMapMouseMove = useCallback(
		(e) => {
			if (draggingPinRef.current && e?.lngLat) {
				const drag = draggingPinRef.current;
				if (!drag.hasDragged && drag.startPoint && e?.point) {
					const dist = Math.hypot(
						Number(e.point.x) - drag.startPoint.x,
						Number(e.point.y) - drag.startPoint.y,
					);
					if (dist < CAMERA_PIN_DRAG_THRESHOLD_PX) {
						return;
					}
					drag.hasDragged = true;
				} else if (!drag.hasDragged) {
					drag.hasDragged = true;
				}

				const map = mapRef.current?.getMap?.() || mapRef.current;
				const resolvedOrder = Number.isFinite(Number(drag.floorOrder))
					? Number(drag.floorOrder)
					: 0;
				const pinHeightMeters =
					resolvedOrder * floorGapStep + FLOOR_SLAB_HEIGHT + 0.32;
				const projected = projectEventToFloorLngLat(
					map,
					e,
					pinHeightMeters,
					MercatorCoordinate,
				) || { lng: e.lngLat.lng, lat: e.lngLat.lat };
				drag.latestCoords = projected;

				const validation = validateCameraPinPlacement({
					lng: projected.lng,
					lat: projected.lat,
					targetCam: drag.targetCam,
					targetArea: drag.targetArea,
					activeBuilding: effectiveBuilding3d,
					activeFloorOrder: renderedFloorOrder2d,
					roomFeatures: roomsSplit.active?.features,
					slabFeatures: slabsSplit.active?.features,
					store: geometryStore,
				});
				drag.isInvalidPlacement = !validation.ok;
				drag.invalidMessage = validation.ok ? null : validation.message;
				drag.validationResult = validation;

				onUpdateCameraMapLocation?.(drag.cameraId, {
					lng: projected.lng,
					lat: projected.lat,
				});
				setHoverCursor(validation.ok ? "grabbing" : "not-allowed");
				if (e?.point) {
					setHoveredRoom({
						x: e.point.x,
						y: e.point.y,
						isOccupied: true,
						isInvalid: !validation.ok,
						title: validation.ok
							? "Đang dời vị trí camera"
							: "Vị trí không hợp lệ",
						subtitle: validation.ok
							? "Thả chuột để lưu vị trí mới"
							: validation.message,
					});
				}
				return;
			}
			const features = e?.features || [];
			if (pickingCameraLocation || isCreatingCamera) {
				setHoverCursor("crosshair");
				return;
			}
			setHoverCursor(features.length > 0 ? "pointer" : "grab");
			const camPinFeat = features.find(
				(f) => f.layer?.id === "campus-2d-camera-pins-layer",
			);
			if (camPinFeat?.properties && e?.point) {
				const cp = camPinFeat.properties;
				const isDecomm = cp.statusKey === "muted";
				const isDraggableNow =
					isAdmin &&
					canDragCameraPin &&
					(!selectedCameraId ||
						String(cp.cameraId) === String(selectedCameraId));
				setHoverCursor(
					isAdmin
						? isDecomm
							? "not-allowed"
							: isDraggableNow
								? "grab"
								: "pointer"
						: "pointer",
				);
				setHoveredRoom({
					x: e.point.x,
					y: e.point.y,
					isOccupied: true,
					isInvalid: isAdmin && isDecomm,
					title: `${cp.cameraName || "Camera"} (${cp.cameraCode || ""})`,
					subtitle: isAdmin
						? isDecomm
							? "Camera đã ngừng hoạt động, không thể dời vị trí"
							: isDraggableNow
								? "Giữ & kéo để dời vị trí · Bấm để xem cấu hình"
								: "Bấm để xem thông tin camera"
						: cp.statusKey === "ok"
							? "ONLINE"
							: "OFFLINE",
				});
				return;
			}
			const roomFeat = features.find(
				(f) =>
					f.layer?.id === "campus-3d-rooms-layer" && !f.properties?.isGhost,
			);
			if (roomFeat?.properties && e?.point) {
				const p = roomFeat.properties;
				if (String(p.role || "ROOM").toUpperCase() === "CORRIDOR") {
					setHoveredRoom(null);
					return;
				}
				const areaLevelCode = p.areaLevelCode || "";
				const levelCfg = areaLevelCode ? getLevelConfig(areaLevelCode) : null;
				const emptySub = isEditingCoords
					? "Nhấp để chuyển khu vực tới block này"
					: pickingCameraArea
						? "Phòng chưa gắn khu vực"
						: isAdmin
							? "Nhấp để tạo Khu vực"
							: "Chưa gán khu vực";
				let occupiedSub =
					levelCfg?.badgeLabel || levelCfg?.name || "Đã gán Khu vực";
				if (p.matchedAreaId) {
					const st = cameraStatsByArea?.[p.matchedAreaId];
					const totalCams = st?.total ?? cameraCounts?.[p.matchedAreaId] ?? 0;
					if (pickingCameraArea) {
						occupiedSub = "Nhấp để gán camera vào khu vực này";
					} else if (totalCams > 0) {
						const offlineTxt =
							st?.offlineCount > 0 ? ` · ${st.offlineCount} Offline` : "";
						occupiedSub = `${occupiedSub} · ${totalCams} Camera${offlineTxt}`;
					}
				}
				setHoveredRoom({
					x: e.point.x,
					y: e.point.y,
					isOccupied: Boolean(p.matchedAreaId),
					title: p.matchedAreaName || "Phòng trống",
					subtitle: p.matchedAreaId ? occupiedSub : emptySub,
				});
				return;
			}
			// Khi xem "Toàn tòa", hover sàn/vách tầng sẽ gợi ý nhấp cắt vào tầng đó
			if (
				activeBuilding3d !== "ALL" &&
				activeFloorOrder3d === "ALL" &&
				e?.point
			) {
				const floorFeat = features.find(
					(f) =>
						(f.layer?.id === "campus-3d-slabs-layer" ||
							f.layer?.id === "campus-3d-walls-layer") &&
						!f.properties?.isGhost &&
						f.properties?.floorOrder != null,
				);
				if (floorFeat?.properties) {
					const flOrder = Number(floorFeat.properties.floorOrder);
					const bldMeta = DEFAULT_BUILDINGS_META.find((b) =>
						matchesBuilding(floorFeat.properties.building, b.name),
					);
					const flLabel =
						bldMeta?.floors?.find((fl) => fl.floorOrder === flOrder)?.label ||
						`Tầng ${flOrder}`;
					setHoveredRoom({
						x: e.point.x,
						y: e.point.y,
						isOccupied: false,
						title: `${bldMeta?.name || floorFeat.properties.building} · ${flLabel}`,
						subtitle: "Nhấp để cắt vào tầng này",
					});
					return;
				}
			}
			setHoveredRoom(null);
		},
		[
			isAdmin,
			isEditingCoords,
			pickingCameraArea,
			pickingCameraLocation,
			canDragCameraPin,
			selectedCameraId,
			onUpdateCameraMapLocation,
			cameraStatsByArea,
			cameraCounts,
			activeBuilding3d,
			activeFloorOrder3d,
			floorGapStep,
		],
	);

	// Interactive 3D Map Click: Create/Rebind chỉ nhận block ROOM trống; Click block ROOM trống ở chế độ xem (ADMIN) tự mở Tạo khu vực
	const handleMapClick = useCallback(
		(e) => {
			const features = e?.features || [];
			const clickedCamPin = features.find(
				(f) => f.layer?.id === "campus-2d-camera-pins-layer",
			);
			const clickedCamBadge = features.find(
				(f) => f.layer?.id === "campus-3d-camera-badges-layer",
			);
			const clickedRoomFeature = features.find(
				(f) => f.layer?.id === "campus-3d-rooms-layer",
			);
			const clickedActiveBuildingFeature =
				clickedRoomFeature ||
				features.find(
					(f) =>
						f.layer?.id === "campus-3d-walls-layer" ||
						f.layer?.id === "campus-3d-slabs-layer",
				);
			const clickedGhostFeature = features.find(
				(f) =>
					f.layer?.id === "campus-3d-ghost-rooms-layer" ||
					f.layer?.id === "campus-3d-ghost-walls-layer" ||
					f.layer?.id === "campus-3d-ghost-slabs-layer",
			);
			const roomProps = clickedRoomFeature?.properties;

			if (pickingCameraLocation && isAdmin) {
				if (!e?.lngLat) return;
				const lngLat = { lng: e.lngLat.lng, lat: e.lngLat.lat };
				const pickedBuilding = effectiveBuilding3d;
				const pickedFloorOrder = renderedFloorOrder2d;

				const validation = validateCameraPinPlacement({
					lng: lngLat.lng,
					lat: lngLat.lat,
					targetCam: pickingTargetCamera,
					targetArea: pickingTargetArea,
					activeBuilding: effectiveBuilding3d,
					activeFloorOrder: renderedFloorOrder2d,
					clickedRoomProps: roomProps || null,
					roomFeatures: roomsSplit.active?.features,
					slabFeatures: slabsSplit.active?.features,
					store: geometryStore,
				});
				if (!validation.ok) {
					onCameraPlacementRejected?.(validation.message);
					return;
				}

				onCameraLocationPicked?.({
					lng: lngLat.lng,
					lat: lngLat.lat,
					building: validation.building || pickedBuilding,
					floorOrder:
						validation.floorOrder != null
							? validation.floorOrder
							: pickedFloorOrder,
				});
				return;
			}

			if (isCreatingCamera && isAdmin) {
				if (!e?.lngLat) return;
				const lngLat = { lng: e.lngLat.lng, lat: e.lngLat.lat };
				const isCorr = createCamForm.cameraType === "CORRIDOR";
				if (!isCorr && !createCamSelectedArea) {
					setCreateCamError(
						"Vui lòng chọn Khu vực phụ trách ở cột bên phải trước khi ghim vị trí.",
					);
					return;
				}
				const draftTargetCam = {
					cameraType: createCamForm.cameraType,
					areaId: isCorr ? null : createCamForm.areaId,
					mapBuilding: isCorr
						? effectiveBuilding3d
						: createCamSelectedArea?.building || effectiveBuilding3d,
					mapFloorOrder: isCorr
						? renderedFloorOrder2d
						: resolveFloorOrder(
								createCamSelectedArea?.building || effectiveBuilding3d,
								createCamSelectedArea?.floor,
							),
				};
				const validation = validateCameraPinPlacement({
					lng: lngLat.lng,
					lat: lngLat.lat,
					targetCam: draftTargetCam,
					targetArea: createCamSelectedArea,
					activeBuilding: effectiveBuilding3d,
					activeFloorOrder: renderedFloorOrder2d,
					clickedRoomProps: roomProps || null,
					roomFeatures: roomsSplit.active?.features,
					slabFeatures: slabsSplit.active?.features,
					store: geometryStore,
				});
				if (!validation.ok) {
					setCreateCamError(validation.message);
					onCameraPlacementRejected?.(validation.message);
					return;
				}
				setCreateCamPinDraft({
					lng: Number(lngLat.lng.toFixed(7)),
					lat: Number(lngLat.lat.toFixed(7)),
					building: validation.building || effectiveBuilding3d,
					floorOrder:
						validation.floorOrder != null
							? validation.floorOrder
							: renderedFloorOrder2d,
				});
				setCreateCamForm((prev) => ({
					...prev,
					mapBuilding: validation.building || effectiveBuilding3d,
					mapFloorOrder:
						validation.floorOrder != null
							? validation.floorOrder
							: renderedFloorOrder2d,
				}));
				setCreateCamError(null);
				return;
			}

			if (justDraggedPinRef.current) {
				return;
			}

			if (clickedCamPin?.properties?.cameraId && onSelectCameraPin) {
				skipNextCameraFlyRef.current = true;
				onSelectCameraPin(clickedCamPin.properties.cameraId);
				return;
			}

			if (isCreatingArea) {
				if (!clickedRoomFeature || !roomProps) {
					setCreateFormError(
						"Chỉ có thể chọn trực tiếp một block phòng 3D trống trên bản đồ.",
					);
					setRailCollapsed(false);
					return;
				}
				const selection = resolveRoomBlockSelection({
					roomProps,
					floors: allDbFloors,
				});
				if (!selection.ok) {
					setCreateFormError(selection.message);
					setRailCollapsed(false);
					return;
				}
				setActiveBuilding3d(selection.building);
				setActiveFloorOrder3d(selection.floorOrder);
				setCreateDraftRoomFaceId(selection.faceId);
				setCreateDraftCoords([
					selection.centerLongitude,
					selection.centerLatitude,
				]);
				setCreateFormData((prev) => ({
					...prev,
					building: selection.building,
					floor: selection.floor,
					floorId: selection.floorId ?? null,
				}));
				setCreateFormError(null);
				setRailCollapsed(false);
				return;
			}
			if (isEditingCoords) {
				if (
					!clickedRoomFeature &&
					clickedGhostFeature?.properties?.floorOrder != null &&
					!clickedGhostFeature.properties?.isContextBuilding &&
					activeBuilding3d !== "ALL" &&
					matchesBuilding(
						clickedGhostFeature.properties?.building,
						activeBuilding3d,
					)
				) {
					setActiveFloorOrder3d(
						Number(clickedGhostFeature.properties.floorOrder),
					);
					setCoordError(null);
					return;
				}
				if (!clickedRoomFeature || !roomProps) {
					setCoordError(
						"Chỉ có thể chọn trực tiếp một block phòng 3D trống trên bản đồ.",
					);
					return;
				}
				const selection = resolveRoomBlockSelection({
					roomProps,
					floors: allDbFloors,
					excludeAreaId: editingAreaId,
				});
				if (!selection.ok) {
					setCoordError(selection.message);
					return;
				}
				setDraftCoords([selection.centerLongitude, selection.centerLatitude]);
				setDraftRoomMeta(selection);
				setCoordError(selection.hasDbFloor ? null : selection.warning);
				return;
			}

			// Zero-friction Create: Admin nhấp thẳng block ROOM trống trên tầng đang mở -> mở panel Tạo khu vực với tọa độ/tòa/tầng đã điền sẵn
			if (
				isAdmin &&
				typeof onStartCreateFromRoom === "function" &&
				clickedRoomFeature &&
				roomProps &&
				!roomProps.isGhost &&
				!roomProps.matchedAreaId &&
				String(roomProps.role || "ROOM").toUpperCase() === "ROOM"
			) {
				const selection = resolveRoomBlockSelection({
					roomProps,
					floors: allDbFloors,
				});
				if (selection.ok) {
					pendingRoomSeedRef.current = selection;
					setActiveBuilding3d(selection.building);
					setActiveFloorOrder3d(selection.floorOrder);
					onStartCreateFromRoom();
					return;
				}
			}

			const hitFeature = clickedActiveBuildingFeature || clickedGhostFeature;
			const hitProps = hitFeature?.properties;
			if (!hitProps?.building) return;

			const clickedBuilding = hitProps.building;
			const isDifferentBuilding =
				activeBuilding3d === "ALL" ||
				!matchesBuilding(activeBuilding3d, clickedBuilding);

			if (isDifferentBuilding) {
				const hasSpecificAreaRoom =
					clickedRoomFeature && Boolean(roomProps?.matchedAreaId);
				// Từ ALL click vào tòa nhà mở chế độ Toàn tòa để bóc tách từng tầng (trừ khi click thẳng 1 phòng Area cụ thể)
				const targetFloor = hasSpecificAreaRoom
					? Number(roomProps.floorOrder ?? 0)
					: activeBuilding3d === "ALL"
						? "ALL"
						: hitProps.floorOrder != null
							? Number(hitProps.floorOrder)
							: "ALL";
				const currentZoom = mapRef.current?.getZoom?.() ?? CAMPUS_CENTER.zoom;
				const shouldFly = !(clickedRoomFeature && currentZoom >= 18);
				handleSelect3dBuilding(clickedBuilding, targetFloor, {
					fly: shouldFly,
				});
				if (hasSpecificAreaRoom) {
					skipNextAreaFlyRef.current = true;
					onSelectArea?.(roomProps.matchedAreaId);
				}
				return;
			}

			// Đang ở trong cùng tòa nhà: click vào phòng -> cắt đúng tầng (nếu đang Toàn tòa) & chọn Area tương ứng ở sidebar
			if (clickedRoomFeature && roomProps) {
				const roomFloor = Number(roomProps.floorOrder ?? 0);
				if (activeFloorOrder3d === "ALL") {
					setActiveFloorOrder3d(roomFloor);
					notifyParentMapFilter(
						roomProps.building || activeBuilding3d,
						roomFloor,
					);
				}
				if (roomProps.matchedAreaId) {
					skipNextAreaFlyRef.current = true;
					onSelectArea?.(roomProps.matchedAreaId);
				}
				return;
			}

			// Click vào tầng ghost bên dưới (hoặc chọn 1 tầng khi đang Toàn tòa) -> chuyển tới tầng đó
			if (
				(activeFloorOrder3d === "ALL" ||
					(hitProps.isGhost && !hitProps.isContextBuilding)) &&
				hitProps.floorOrder != null
			) {
				const nextFl = Number(hitProps.floorOrder);
				setActiveFloorOrder3d(nextFl);
				notifyParentMapFilter(hitProps.building || activeBuilding3d, nextFl);
			}
		},
		[
			isCreatingArea,
			isCreatingCamera,
			createCamForm.cameraType,
			createCamForm.areaId,
			createCamSelectedArea,
			isEditingCoords,
			pickingCameraArea,
			pickingCameraLocation,
			pickingTargetCamera,
			pickingTargetArea,
			effectiveBuilding3d,
			renderedFloorOrder2d,
			roomsSplit.active,
			slabsSplit.active,
			geometryStore,
			onCameraLocationPicked,
			onCameraPlacementRejected,
			onSelectCameraPin,
			areas,
			cameras,
			cameraStatsByArea,
			onAreaPickedForCamera,
			onSelectCameraBadge,
			isAdmin,
			editingAreaId,
			floorGapStep,
			allDbFloors,
			activeBuilding3d,
			activeFloorOrder3d,
			handleSelect3dBuilding,
			notifyParentMapFilter,
			onStartCreateFromRoom,
			onSelectArea,
		],
	);

	return (
		<div
			className={`campus-map-layout ${railCollapsed || (hideRail && !isCreatingArea && !isCreatingCamera && !hasRightDrawer) ? "campus-map-layout--expanded" : ""} ${isCreatingArea || isCreatingCamera ? "campus-map-layout--create" : ""} ${hasRightDrawer && !isCreatingArea && !isCreatingCamera ? "campus-map-layout--camera-inspector" : ""}`}
		>
			{/* LEFT: MAIN MAPLIBRE CANVAS */}
			<div className="campus-map-card">
				{/* Floating Top Controls (Sidebar Toggle + 2D Building/Floor Select-Options + Compass) */}
				<div className="campus-map-floating-bar">
					{onToggleLeftSidebar && (
						<button
							type="button"
							className="campus-map-collapse-btn"
							onClick={onToggleLeftSidebar}
							title={
								isLeftSidebarCollapsed
									? "Mở danh sách khu vực"
									: "Thu gọn danh sách khu vực (Mở rộng bản đồ)"
							}
							aria-label="Thu gọn/Mở rộng danh sách khu vực"
						>
							<Menu size={16} />
						</button>
					)}

					<div className="campus-3d-slicer-select-wrap">
						<Building2
							size={13}
							className="campus-3d-slicer-select-icon"
						/>
						<select
							className="campus-3d-slicer-select"
							aria-label="Chọn tòa nhà trên bản đồ 2D"
							value={
								VISIBLE_BUILDINGS_META.find((b) =>
									matchesBuilding(effectiveBuilding3d, b.name),
								)?.name || "Tòa Epsilon"
							}
							onChange={(e) =>
								handleSelect3dBuilding(e.target.value, renderedFloorOrder2d)
							}
						>
							{VISIBLE_BUILDINGS_META.map((b) => (
								<option
									key={b.id}
									value={b.name}
								>
									{b.name}
								</option>
							))}
						</select>
						<ChevronDown
							size={13}
							className="campus-3d-slicer-select-arrow"
						/>
					</div>

					{(activeBuildingMeta3d || VISIBLE_BUILDINGS_META[0]) && (
						<div className="campus-3d-slicer-select-wrap">
							<Layers
								size={13}
								className="campus-3d-slicer-select-icon"
							/>
							<select
								className="campus-3d-slicer-select"
								aria-label={`Chọn tầng của ${(activeBuildingMeta3d || VISIBLE_BUILDINGS_META[0]).name}`}
								value={String(renderedFloorOrder2d)}
								onChange={(e) => {
									const nextFl = Number(e.target.value);
									setActiveFloorOrder3d(nextFl);
									if (
										isCreatingCamera &&
										createCamForm.cameraType === "CORRIDOR"
									) {
										setCreateCamForm((prev) => ({
											...prev,
											mapFloorOrder: nextFl,
										}));
										setCreateCamPinDraft(null);
									}
									notifyParentMapFilter(effectiveBuilding3d, nextFl);
								}}
							>
								{(activeBuildingMeta3d || VISIBLE_BUILDINGS_META[0]).floors.map(
									(fl) => (
										<option
											key={fl.id}
											value={String(fl.floorOrder)}
										>
											{fl.shortLabel}
										</option>
									),
								)}
							</select>
							<ChevronDown
								size={13}
								className="campus-3d-slicer-select-arrow"
							/>
						</div>
					)}

					<button
						type="button"
						className="campus-map-btn-icon"
						onClick={handleResetCampusView}
						title="Về toàn cảnh khuôn viên"
						aria-label="Về toàn cảnh khuôn viên"
					>
						<Compass size={14} />
					</button>

					{!hideRail && (
						<button
							type="button"
							className="campus-map-btn-icon"
							onClick={() => setRailCollapsed(!railCollapsed)}
							title={
								railCollapsed ? "Mở danh sách khu vực" : "Thu gọn danh sách"
							}
							aria-label={
								railCollapsed ? "Mở danh sách khu vực" : "Thu gọn danh sách"
							}
						>
							{railCollapsed ? (
								<PanelRightOpen size={14} />
							) : (
								<PanelRightClose size={14} />
							)}
						</button>
					)}
				</div>

				{/* Chip chỉ dẫn tạo khu vực hoặc tạo camera trên bản đồ 2D */}
				{isCreatingArea && (
					<div
						className="campus-create-guide-pill"
						role="status"
						aria-live="polite"
					>
						<span className="campus-create-guide-pill__icon">
							{createDraftCoords ? (
								<Check
									size={13}
									strokeWidth={2.5}
								/>
							) : (
								<Crosshair
									size={13}
									strokeWidth={2.5}
								/>
							)}
						</span>
						<span className="campus-create-guide-pill__text">
							{createDraftCoords
								? "Đã chọn phòng · Nhấn phòng trống khác để đổi"
								: "Chọn phòng trống trên bản đồ 2D"}
						</span>
					</div>
				)}

				{isCreatingCamera && (
					<div
						className="campus-create-guide-pill"
						role="status"
						aria-live="polite"
					>
						<span className="campus-create-guide-pill__icon">
							{createCamPinDraft ? (
								<Check
									size={13}
									strokeWidth={2.5}
								/>
							) : (
								<Crosshair
									size={13}
									strokeWidth={2.5}
								/>
							)}
						</span>
						<span className="campus-create-guide-pill__text">
							{createCamPinDraft
								? "Đã ghim vị trí camera · Nhấp điểm khác để đổi vị trí"
								: createCamForm.cameraType === "CORRIDOR"
									? "Nhấp vào sàn hành lang (ngoài các phòng) để ghim vị trí camera"
									: "Nhấp vào bên trong phòng được chọn để ghim vị trí camera"}
						</span>
					</div>
				)}

				{/* Thanh công cụ đổi block phòng 3D (ADMIN) */}
				{isEditingCoords && editingArea && (
					<div className="campus-coord-editor">
						<div className="campus-coord-editor__head">
							<Crosshair size={14} />
							<span>
								Đổi block 3D: <strong>{editingArea.name}</strong>
							</span>
						</div>
						<div className="campus-coord-editor__values">
							<span>
								Vị trí{" "}
								<code>
									{draftRoomMeta
										? `${draftRoomMeta.building} · ${draftRoomMeta.floor}`
										: "Chưa chọn block trống"}
								</code>
							</span>
						</div>
						{coordError && (
							<div className="campus-coord-editor__error">
								<AlertCircle size={12} />
								<span>{coordError}</span>
							</div>
						)}
						<div className="campus-coord-editor__actions">
							<button
								type="button"
								id="campus-coord-cancel-btn"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={handleCancelEditCoords}
								disabled={savingCoords}
							>
								Huỷ
							</button>
							<button
								type="button"
								id="campus-coord-save-btn"
								className="campus-coord-btn campus-coord-btn--primary"
								onClick={handleSaveCoords}
								disabled={
									savingCoords ||
									!draftRoomMeta?.floorId ||
									!isValidLngLat(draftCoords)
								}
							>
								{savingCoords ? (
									<Loader2
										size={13}
										className="animate-spin"
									/>
								) : (
									<Save size={13} />
								)}
								Lưu vị trí
							</button>
						</div>
					</div>
				)}

				{coordSuccess && (
					<div className="campus-coord-toast">{coordSuccess}</div>
				)}

				{pickingCameraArea && (
					<div
						className="campus-coord-editor"
						role="status"
					>
						<div className="campus-coord-editor__info">
							<Crosshair
								size={14}
								className="text-blue"
							/>
							<span className="campus-coord-editor__title">
								Chọn khu vực 3D cho Camera
							</span>
							<span className="campus-coord-editor__coords">
								Click vào một khối phòng 3D đã có khu vực trên bản đồ
							</span>
						</div>
						<div className="campus-coord-editor__actions">
							<button
								type="button"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={onCancelPickCameraArea}
							>
								Huỷ chọn
							</button>
						</div>
					</div>
				)}

				{pickingCameraLocation && (
					<div
						className="campus-draw-bar"
						role="status"
					>
						<div className="campus-draw-bar__info">
							<Crosshair size={14} />
							<span>
								Đang đặt vị trí cho{" "}
								<strong>
									{pickingTargetCamera?.cameraCode ||
										pickingTargetCamera?.name ||
										"camera"}
								</strong>
							</span>
							{pickingTargetAreaInfo?.building && (
								<>
									<span className="campus-draw-bar__sep">·</span>
									<span>
										{pickingTargetAreaInfo.building}
										{pickingTargetAreaInfo.floor
											? ` · ${pickingTargetAreaInfo.floor}`
											: ""}
									</span>
								</>
							)}
							<span className="campus-draw-bar__sep">·</span>
							<span className="campus-draw-bar__hint">
								Nhấn vào phòng được gán để ghim · Esc để hủy
							</span>
						</div>
						<div className="campus-draw-bar__actions">
							<button
								type="button"
								className="campus-draw-btn campus-draw-btn--cancel"
								onClick={onCancelPickCameraLocation}
							>
								Hủy
							</button>
						</div>
					</div>
				)}

				{/* Map Viewport */}
				<div
					className={`campus-map-viewport ${compactLabels ? "campus-map-viewport--compact" : ""} ${isEditingCoords || pickingCameraArea || pickingCameraLocation ? "campus-map-viewport--editing" : ""}`}
				>
					<MapGL
						ref={mapRef}
						initialViewState={CAMPUS_CENTER}
						onLoad={(e) => {
							const map = e?.target;
							if (!map) return;
							registerPortalMapImages(map);
							registerRoomLabelSprites(map, roomLabelsGeoJSON);
							registerCameraBadgeSprites(map, cameraBadgesGeoJSON);
							registerCameraBadgeSprites(map, cameraPinsGeoJSON);
							map.on?.("styleimagemissing", (ev) => {
								ensurePortalMapImage(map, ev.id);
								ensureRoomLabelSpriteImage(map, ev.id);
								ensureCameraBadgeSpriteImage(map, ev.id);
							});
							map.setLight?.(MAP_LIGHT);
						}}
						onZoomEnd={(e) => setMapZoom(e.viewState.zoom)}
						onClick={handleMapClick}
						onMouseDown={handleMapMouseDown}
						onMouseUp={handleMapMouseUp}
						onMouseMove={handleMapMouseMove}
						onMouseLeave={() => {
							handleMapMouseUp();
							setHoverCursor("grab");
							setHoveredRoom(null);
						}}
						cursor={hoverCursor}
						interactiveLayerIds={INTERACTIVE_3D_LAYERS}
						light={MAP_LIGHT}
						mapStyle={OPENFREEMAP_STYLE_URL}
						style={{ width: "100%", height: "100%" }}
						minZoom={1}
						maxZoom={22}
						maxPitch={0}
						dragRotate
						touchPitch={false}
						boxZoom={false}
					>
						{/* Native Map Controls */}
						<NavigationControl
							position="top-right"
							showCompass
							showZoom
						/>
						<FullscreenControl position="top-right" />

						{/* Lớp Đế Sàn Hành Lang, Khối Phòng & Tường 2D Phẳng (CAD Flat Mode) */}
						<Source
							id="campus-3d-slabs-src"
							type="geojson"
							data={slabsSplit.active}
						>
							<Layer
								id="campus-3d-slabs-layer"
								type="fill"
								paint={{
									"fill-color": "#f1f5f9",
									"fill-opacity": 0.96,
								}}
							/>
							<Layer
								id="campus-3d-slabs-outline-layer"
								type="line"
								paint={{
									"line-color": "#94a3b8",
									"line-width": 1.5,
								}}
							/>
						</Source>
						<Source
							id="campus-3d-rooms-src"
							type="geojson"
							data={roomsSplit.active}
						>
							<Layer
								id="campus-3d-rooms-layer"
								type="fill"
								paint={{
									"fill-color": ["get", "color"],
									"fill-opacity": effectiveSelectedAreaId
										? ["case", ["get", "isSelected"], 0.95, 0.58]
										: 0.86,
								}}
							/>
							<Layer
								id="campus-3d-rooms-outline-layer"
								type="line"
								paint={{
									"line-color": "#ffffff",
									"line-width": 1.5,
									"line-opacity": 0.92,
								}}
							/>
						</Source>
						<Source
							id="campus-3d-walls-src"
							type="geojson"
							data={wallsSplit.active}
						>
							<Layer
								id="campus-3d-walls-layer"
								type="fill"
								filter={["!=", ["get", "wallKind"], "DOOR_THRESHOLD"]}
								paint={{
									"fill-color": "#334155",
									"fill-opacity": 0.95,
								}}
							/>
						</Source>

						{/* Cửa 2D kiểu bản vẽ kiến trúc CAD (khoảng hở ngưỡng cửa + cánh cửa & cung mở) */}
						<Source
							id="campus-2d-doors-src"
							type="geojson"
							data={doorsActiveGeoJSON}
						>
							<Layer
								id="campus-2d-doors-threshold-fill"
								type="fill"
								minzoom={16}
								paint={{
									"fill-color": "#fef3c7",
									"fill-opacity": 0.98,
								}}
							/>
							<Layer
								id="campus-2d-doors-threshold-outline"
								type="line"
								minzoom={16}
								paint={{
									"line-color": "#d97706",
									"line-width": 1.6,
									"line-opacity": 0.95,
								}}
							/>
						</Source>

						<Source
							id="campus-2d-door-swing-src"
							type="geojson"
							data={doorSwingGeoJSON}
						>
							<Layer
								id="campus-2d-door-swing-arc"
								type="line"
								minzoom={17.6}
								filter={["==", ["get", "kind"], "arc"]}
								paint={{
									"line-color": "#d97706",
									"line-width": 1.2,
									"line-dasharray": [2, 2],
									"line-opacity": 0.85,
								}}
							/>
							<Layer
								id="campus-2d-door-leaf-line"
								type="line"
								minzoom={17.6}
								filter={["==", ["get", "kind"], "leaf"]}
								paint={{
									"line-color": "#92400e",
									"line-width": 2.2,
									"line-opacity": 0.98,
								}}
							/>
						</Source>

						{/* Viền focus 2 lớp cho khu vực đang được chọn (giữ nguyên màu cấp độ bên trong) */}
						<Source
							id="campus-2d-selected-room-src"
							type="geojson"
							data={selectedRoomGeoJSON}
						>
							<Layer
								id="campus-2d-room-selected-halo"
								type="line"
								layout={{
									"line-join": "round",
									"line-cap": "round",
								}}
								paint={{
									"line-color": "#ffffff",
									"line-width": 6,
									"line-opacity": 0.96,
								}}
							/>
							<Layer
								id="campus-2d-room-selected-outline"
								type="line"
								layout={{
									"line-join": "round",
									"line-cap": "round",
								}}
								paint={{
									"line-color": "#1d4ed8",
									"line-width": 3.2,
									"line-opacity": 1,
								}}
							/>
						</Source>

						{/* Lớp Nhãn Tên Khu Vực & Biểu Tượng Cầu Thang / Thang Máy (2D Flat) */}
						<Source
							id="campus-3d-portals-src"
							type="geojson"
							data={portalSymbolsGeoJSON}
						>
							<Layer
								id="campus-3d-portals-layer"
								type="symbol"
								minzoom={16.5}
								layout={{
									"icon-image": ["get", "iconImage"],
									"icon-size": [
										"interpolate",
										["linear"],
										["zoom"],
										16.5,
										0.55,
										19.5,
										0.88,
									],
									"icon-allow-overlap": true,
									"icon-ignore-placement": false,
								}}
								paint={{
									"icon-opacity": 0.96,
								}}
							/>
						</Source>

						<Source
							id="campus-3d-room-labels-src"
							type="geojson"
							data={roomLabelsGeoJSON}
						>
							<Layer
								id="campus-3d-room-labels-layer"
								type="symbol"
								minzoom={16.4}
								layout={{
									"icon-image": ["get", "spriteId"],
									"icon-size": [
										"interpolate",
										["linear"],
										["zoom"],
										16.4,
										0.78,
										18.2,
										0.94,
										19.8,
										1.05,
									],
									"icon-allow-overlap": false,
									"icon-ignore-placement": false,
									"icon-padding": 3,
									"symbol-sort-key": ["case", ["get", "isSelected"], 0, 10],
								}}
								paint={{
									"icon-opacity": 0.98,
								}}
							/>
						</Source>

						{/* 2D Free-placed Camera Pin Icons */}
						<Source
							id="campus-2d-camera-pins-src"
							type="geojson"
							data={cameraPinsGeoJSON}
						>
							<Layer
								id="campus-2d-camera-selected-halo"
								type="circle"
								filter={["==", ["get", "isSelected"], 1]}
								paint={{
									"circle-radius": 26,
									"circle-color": "#2563eb",
									"circle-opacity": 0.16,
									"circle-stroke-width": 2,
									"circle-stroke-color": "#2563eb",
									"circle-stroke-opacity": 0.92,
								}}
							/>
							<Layer
								id="campus-2d-camera-selected-ring"
								type="circle"
								filter={["==", ["get", "isSelected"], 1]}
								paint={{
									"circle-radius": 20,
									"circle-color": "rgba(255,255,255,0)",
									"circle-stroke-width": 2.4,
									"circle-stroke-color": "#ffffff",
									"circle-stroke-opacity": 0.98,
								}}
							/>
							<Layer
								id="campus-2d-camera-pins-layer"
								type="symbol"
								layout={{
									"icon-image": ["get", "spriteId"],
									"icon-size": [
										"interpolate",
										["linear"],
										["zoom"],
										15.5,
										["case", ["==", ["get", "isSelected"], 1], 0.88, 0.72],
										18,
										["case", ["==", ["get", "isSelected"], 1], 1.1, 0.92],
										20,
										["case", ["==", ["get", "isSelected"], 1], 1.26, 1.05],
									],
									"icon-allow-overlap": true,
									"icon-ignore-placement": true,
									"symbol-sort-key": [
										"case",
										["==", ["get", "isSelected"], 1],
										0,
										10,
									],
								}}
								paint={{
									"icon-opacity": selectedCameraId
										? ["case", ["==", ["get", "isSelected"], 1], 1, 0.58]
										: 1,
								}}
							/>
							<Layer
								id="campus-2d-camera-selected-label"
								type="symbol"
								filter={["==", ["get", "isSelected"], 1]}
								layout={{
									"text-field": ["get", "cameraName"],
									"text-size": 12,
									"text-font": ["Open Sans Bold", "Arial Unicode MS Bold"],
									"text-offset": [0, 2.3],
									"text-anchor": "top",
									"text-allow-overlap": true,
									"text-ignore-placement": true,
								}}
								paint={{
									"text-color": "#0f172a",
									"text-halo-color": "#ffffff",
									"text-halo-width": 2.2,
									"text-halo-blur": 0.4,
								}}
							/>
						</Source>

						{/* Nhãn Tòa Nhà (Overview & chuyển nhanh giữa các tòa trên bản đồ) */}
						{buildingLabelsGeoJSON.features.map((bFeat) => {
							const { id, building, name, floorCount, areaCount, isFocused } =
								bFeat.properties;
							if (activeBuilding3d !== "ALL" && isFocused) return null;
							const [lng, lat] = bFeat.geometry.coordinates;
							return (
								<Marker
									key={id}
									longitude={lng}
									latitude={lat}
									anchor="bottom"
									onClick={(e) => {
										stopMapEvent(e);
										handleSelect3dBuilding(building, "ALL");
									}}
									style={{ zIndex: 22 }}
								>
									<button
										type="button"
										className="campus-bldg-pill"
										title={`Xem ${name}`}
									>
										<Building2 size={12} />
										<span className="campus-bldg-pill__name">{name}</span>
										<span className="campus-bldg-pill__badge">
											{floorCount} tầng · {areaCount ?? 0} khu vực
										</span>
									</button>
								</Marker>
							);
						})}
					</MapGL>

					{hoveredRoom && (
						<div
							className={`campus-room-hover-tooltip ${
								hoveredRoom.isInvalid
									? "campus-room-hover-tooltip--invalid"
									: hoveredRoom.isOccupied
										? "campus-room-hover-tooltip--occupied"
										: "campus-room-hover-tooltip--empty"
							}`}
							style={{
								left: `${Math.round(hoveredRoom.x)}px`,
								top: `${Math.round(hoveredRoom.y)}px`,
							}}
							role="status"
						>
							<strong className="campus-room-hover-tooltip__title">
								{hoveredRoom.title}
							</strong>
							<span className="campus-room-hover-tooltip__sub">
								{hoveredRoom.subtitle}
							</span>
						</div>
					)}
				</div>

				{/* Floating Colour Legend */}
				<div className="campus-map-legend">
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--public" />
						<span>{AREA_LEVEL_CONFIG.PUBLIC.badgeLabel}</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--internal" />
						<span>{AREA_LEVEL_CONFIG.CONFIDENTIAL.badgeLabel}</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--private" />
						<span>{AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.badgeLabel}</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--empty-room" />
						<span>Phòng trống</span>
					</div>
				</div>
			</div>

			{/* RIGHT RAIL: AREAS LIST & DETAILS, CREATE PANEL OR CAMERA INSPECTOR */}
			{(!hideRail || isCreatingArea || isCreatingCamera || hasRightDrawer) && (
				<div className="campus-rail">
					{!isCreatingArea && !isCreatingCamera && hasRightDrawer ? (
						rightDrawerContent
					) : isCreatingCamera ? (
						<div className="campus-rail-card campus-rail-card--create">
							<div className="campus-rail-header">
								<span className="campus-rail-title">Thêm camera mới</span>
								<button
									type="button"
									className="campus-rail-close-btn"
									onClick={() => {
										setCreateCamPinDraft(null);
										setCreateCamError(null);
										onCancelCreateCamera?.();
									}}
									aria-label="Đóng"
								>
									<X size={14} />
								</button>
							</div>

							{createCamError && (
								<div className="zone-modal-alert">
									<AlertCircle size={15} />
									<span>{createCamError}</span>
								</div>
							)}

							<div className="campus-create-form-body">
								<div className="area-form-group">
									<label className="area-form-label">
										1. Loại vị trí đặt camera & Chế độ AI *
									</label>
									<div className="cam-type-selector-grid">
										{CAMERA_TYPE_OPTIONS.map((opt) => {
											const isSelected = createCamForm.cameraType === opt.value;
											return (
												<button
													key={opt.value}
													type="button"
													className={`cam-type-option-btn ${
														isSelected ? "cam-type-option-btn--active" : ""
													}`}
													onClick={() => handleCreateCamTypeChange(opt.value)}
													disabled={creatingCamLoading}
												>
													<span className="cam-type-option-btn__title">
														{opt.label}
													</span>
													<span className="cam-type-option-btn__sub">
														{opt.aiModeLabel}
													</span>
												</button>
											);
										})}
									</div>
								</div>

								<div className="area-form-group">
									<label className="area-form-label">2. Tên camera *</label>
									{createCamForm.cameraType === "CORRIDOR" ? (
										<div className="cam-corridor-prefix-input">
											<span className="cam-corridor-prefix-input__addon">
												Hành lang
											</span>
											<input
												type="text"
												className="area-form-input"
												placeholder="Ví dụ: Cánh Đông Tầng 1"
												value={stripCorridorPrefix(createCamForm.name)}
												onChange={(e) => {
													const raw = e.target.value;
													setCreateCamForm((prev) => ({
														...prev,
														name: raw.trim()
															? `Hành lang ${stripCorridorPrefix(raw)}`
															: "",
													}));
													if (createCamError) setCreateCamError(null);
												}}
												disabled={creatingCamLoading}
											/>
										</div>
									) : (
										<input
											type="text"
											className="area-form-input"
											placeholder="Ví dụ: Camera Phòng Lab 301"
											value={createCamForm.name}
											onChange={(e) => {
												setCreateCamForm((prev) => ({
													...prev,
													name: e.target.value,
												}));
												if (createCamError) setCreateCamError(null);
											}}
											disabled={creatingCamLoading}
										/>
									)}
								</div>

								{createCamForm.cameraType !== "CORRIDOR" ? (
									<div className="area-form-group">
										<label className="area-form-label">
											3. Khu vực phụ trách (
											{createCamForm.cameraType === "PUBLIC_AREA"
												? "Cấp 1 · Công khai"
												: "Cấp 2 & 3 · Hạn chế"}
											) *
										</label>
										<AreaSelectWithFilter
											areas={filteredCreateCamAreas}
											value={createCamForm.areaId || ""}
											onChange={(e) =>
												handleCreateCamAreaChange(e.target.value)
											}
											onFilterChange={handleCreateCamFilterChange}
											placeholder="-- Chọn khu vực --"
											placeholderDisabled
											required
											disabled={creatingCamLoading}
										/>
									</div>
								) : (
									<div className="area-form-group">
										<label className="area-form-label">
											3. Tòa nhà & Tầng hành lang *
										</label>
										<div className="cam-corridor-floor-row">
											<select
												className="area-form-input"
												value={effectiveBuilding3d}
												onChange={(e) => {
													const nextB = e.target.value;
													handleSelect3dBuilding(nextB, renderedFloorOrder2d);
													setCreateCamForm((prev) => ({
														...prev,
														mapBuilding: nextB,
													}));
													setCreateCamPinDraft(null);
												}}
												disabled={creatingCamLoading}
											>
												{VISIBLE_BUILDINGS_META.map((b) => (
													<option
														key={b.id}
														value={b.name}
													>
														{b.name}
													</option>
												))}
											</select>
											<select
												className="area-form-input"
												value={String(renderedFloorOrder2d)}
												onChange={(e) => {
													const nextFl = Number(e.target.value);
													setActiveFloorOrder3d(nextFl);
													setCreateCamForm((prev) => ({
														...prev,
														mapFloorOrder: nextFl,
													}));
													setCreateCamPinDraft(null);
												}}
												disabled={creatingCamLoading}
											>
												{(
													activeBuildingMeta3d || VISIBLE_BUILDINGS_META[0]
												).floors.map((fl) => (
													<option
														key={fl.id}
														value={String(fl.floorOrder)}
													>
														{fl.name}
													</option>
												))}
											</select>
										</div>
									</div>
								)}

								<div className="area-form-group">
									<label className="area-form-label">
										4. Vị trí ghim trên bản đồ 2D *
									</label>
									<div
										className={`campus-create-coord-badge ${
											!createCamPinDraft
												? "campus-create-coord-badge--empty"
												: ""
										}`}
									>
										<MapPin size={14} />
										{createCamPinDraft ? (
											<span>
												Đã ghim vị trí · {createCamPinDraft.building} ·{" "}
												{createCamPinDraft.floorOrder === 0
													? "Tầng Trệt"
													: `Tầng ${createCamPinDraft.floorOrder}`}
											</span>
										) : (
											<span>
												{createCamForm.cameraType === "CORRIDOR"
													? "Nhấp vào sàn hành lang trên bản đồ 2D để ghim *"
													: "Nhấp vào bên trong phòng đã chọn trên bản đồ 2D để ghim *"}
											</span>
										)}
									</div>
								</div>
							</div>

							<div className="campus-rail-footer">
								<button
									type="button"
									className={`area-btn-modal area-btn-modal--submit ${
										isCreateCamIncomplete
											? "area-btn-modal--submit-blurred"
											: ""
									}`}
									onClick={handleCreateCameraSubmit}
									disabled={creatingCamLoading || isCreateCamIncomplete}
									title={
										!createCamPinDraft
											? "Chấm vị trí ghim camera trên bản đồ 2D trước khi tạo"
											: "Tạo camera"
									}
								>
									{creatingCamLoading ? (
										<>
											<Loader2
												size={14}
												className="animate-spin"
											/>
											<span>Đang tạo...</span>
										</>
									) : (
										"Tạo camera"
									)}
								</button>
							</div>
						</div>
					) : isCreatingArea ? (
						<div className="campus-rail-card campus-rail-card--create">
							<div className="campus-rail-header">
								<span className="campus-rail-title">Thêm khu vực</span>
								<button
									type="button"
									className="campus-rail-close-btn"
									onClick={() => {
										setCreateDraftCoords(null);
										setCreateDraftRoomFaceId(null);
										setCreateFormError(null);
										onCancelCreateArea?.();
									}}
									aria-label="Đóng"
								>
									<X size={14} />
								</button>
							</div>

							{createFormError && (
								<div className="zone-modal-alert">
									<AlertCircle size={15} />
									<span>{createFormError}</span>
								</div>
							)}

							<div className="campus-create-form-body">
								<div
									className={`campus-create-coord-badge ${
										!createDraftCoords ? "campus-create-coord-badge--empty" : ""
									}`}
								>
									<MapPin size={14} />
									{createDraftCoords ? (
										<span>
											{createFormData.building} · {createFormData.floor}
										</span>
									) : (
										<span>Chọn block phòng 3D trống</span>
									)}
								</div>

								{createDraftCoords && !createFormData.floorId && (
									<div
										className="campus-create-floor-warning"
										role="alert"
									>
										<AlertCircle size={15} />
										<span>Tầng chưa có trong CSDL — chỉ dùng để xem 3D.</span>
									</div>
								)}

								<div className="area-form-group">
									<label className="area-form-label">Tên khu vực *</label>
									<input
										type="text"
										className="area-form-input"
										placeholder="Ví dụ: Phòng Lab Công nghệ A"
										value={createFormData.name}
										onChange={(e) => {
											setCreateFormData((prev) => ({
												...prev,
												name: e.target.value,
											}));
											if (createFormError) setCreateFormError(null);
										}}
									/>
								</div>

								<div className="area-form-group">
									<label className="area-form-label">Cấp độ an ninh *</label>
									<div className="area-level-grid">
										{AREA_LEVEL_CARDS.map((lvl) => (
											<button
												key={lvl.value}
												type="button"
												className={`area-level-btn ${
													createFormData.areaLevel === lvl.value
														? "area-level-btn--active"
														: ""
												} ${AREA_LEVEL_BTN_CLASS[lvl.value] || ""}`}
												onClick={() =>
													setCreateFormData((prev) => ({
														...prev,
														areaLevel: lvl.value,
													}))
												}
											>
												<div className="area-level-btn__header">
													<span className="area-level-btn__name">
														{lvl.name}
													</span>
													<span className="area-level-btn__badge">
														Cấp {lvl.level}
													</span>
												</div>
											</button>
										))}
									</div>
								</div>
							</div>

							<div className="campus-rail-footer">
								<button
									type="button"
									className={`area-btn-modal area-btn-modal--submit ${
										isCreateFormIncomplete
											? "area-btn-modal--submit-blurred"
											: ""
									}`}
									onClick={handleCreateAreaSubmit}
									disabled={creatingAreaLoading || isCreateFormIncomplete}
									title={
										!createDraftCoords
											? "Chọn block phòng 3D trống trên bản đồ"
											: !createFormData.floorId
												? "Tầng chưa có trong CSDL"
												: !createFormData.name.trim()
													? "Nhập tên khu vực"
													: "Tạo khu vực"
									}
								>
									{creatingAreaLoading ? (
										<>
											<Loader2
												size={14}
												className="animate-spin"
											/>
											<span>Đang tạo...</span>
										</>
									) : (
										"Tạo khu vực"
									)}
								</button>
							</div>
						</div>
					) : (
						<>
							{/* Card 1: Area List */}
							<div className="campus-rail-card campus-rail-card--list">
								<div className="campus-rail-header">
									<span className="campus-rail-title">
										Khu vực ({areas.length})
									</span>
									{unpositionedCount > 0 && (
										<span
											className="campus-rail-unpositioned-note"
											title="Khu vực chưa gắn block phòng 3D"
										>
											{unpositionedCount} chưa gắn 3D
										</span>
									)}
								</div>

								<div className="campus-rail-list">
									{areas.length === 0 ? (
										<div className="campus-detail-empty">Chưa có khu vực</div>
									) : (
										areas.map((area) => {
											const isSelected = selectedAreaId === area.id;
											const levelKey =
												typeof area.areaLevel === "string"
													? area.areaLevel.toLowerCase()
													: (area.level?.code || "PUBLIC").toLowerCase();
											const validCoords = getValidCoordinates(area);

											return (
												<div
													key={area.id}
													className={`campus-rail-item ${isSelected ? "campus-rail-item--selected" : ""}`}
													onClick={() =>
														handleSelectAreaItem(area.id, validCoords)
													}
												>
													<div className="campus-rail-item__left">
														<span
															className={`campus-level-dot campus-level-dot--${levelKey}`}
														/>
														<span className="campus-rail-item__name">
															{area.name}
														</span>
													</div>

													<div className="campus-rail-item__right">
														{!validCoords && (
															<span className="campus-tag-unpositioned">
																Chưa gắn block 3D
															</span>
														)}
													</div>
												</div>
											);
										})
									)}
								</div>
							</div>

							{/* Card 2: Selected Area or Guard Detail */}
							<div
								className={`campus-rail-card campus-rail-card--detail ${!selectedArea ? "campus-rail-card--detail-empty" : ""}`}
							>
								{!selectedArea ? (
									<div className="campus-detail-empty">
										<p>Chọn khu vực trên bản đồ để xem chi tiết.</p>
									</div>
								) : (
									<div className="campus-detail-content">
										<div className="campus-detail-header">
											<h3 className="campus-detail-title">
												{selectedArea.name}
											</h3>
											{(selectedArea.building || selectedArea.floor) && (
												<div className="campus-detail-location">
													{selectedArea.building && (
														<span className="campus-detail-location__item">
															<Building2 size={12} />
															{selectedArea.building}
														</span>
													)}
													{selectedArea.building && selectedArea.floor && (
														<span className="campus-detail-location__sep">
															·
														</span>
													)}
													{selectedArea.floor && (
														<span className="campus-detail-location__item">
															<Layers size={12} />
															{selectedArea.floor}
														</span>
													)}
												</div>
											)}
										</div>

										<div className="campus-detail-meta">
											<div className="campus-detail-meta-row">
												<span className="campus-detail-meta-label">Loại</span>
												<span className="campus-detail-meta-val">
													{
														getLevelConfig(
															selectedArea.areaLevel || selectedArea.level,
														).name
													}
												</span>
											</div>
											<div className="campus-detail-meta-row">
												<span className="campus-detail-meta-label">
													Cấp truy cập
												</span>
												<span
													className="campus-detail-meta-val"
													title="Cấp truy cập cố định theo loại"
												>
													{
														getAccessLevelConfig(selectedArea.areaAccessLevel)
															.label
													}
												</span>
											</div>
											<div className="campus-detail-meta-row">
												<span className="campus-detail-meta-label">
													Vị trí 3D
												</span>
												{selectedArea.centerLatitude != null &&
												selectedArea.centerLongitude != null ? (
													<span className="campus-detail-meta-val">
														Đã gắn block
													</span>
												) : (
													<span className="campus-detail-meta-val text-warning">
														Chưa gắn block 3D
													</span>
												)}
											</div>
											{/* Do not add camera count */}
										</div>

										{isAdmin && editingAreaId !== selectedArea.id && (
											<button
												type="button"
												id="campus-coord-edit-btn"
												className="campus-coord-btn campus-coord-btn--primary campus-coord-btn--block"
												onClick={handleStartEditCoords}
											>
												<Crosshair size={13} />
												{selectedArea.centerLatitude != null &&
												selectedArea.centerLongitude != null
													? "Đổi block 3D"
													: "Chọn block 3D"}
											</button>
										)}
									</div>
								)}
							</div>
						</>
					)}
				</div>
			)}
		</div>
	);
}
