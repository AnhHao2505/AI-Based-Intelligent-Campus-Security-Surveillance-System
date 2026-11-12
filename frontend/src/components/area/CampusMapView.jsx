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
import {
	DEFAULT_BUILDINGS_META,
	EXPLODED_FLOOR_STEP_OPTIONS,
	FLOOR_ELEVATION_STEP,
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
import "../../styles/CampusMapView.css";

// FPT University HCMC Campus default center framing both Tòa Alpha & Tòa Epsilon
const CAMPUS_CENTER = {
	longitude: 106.81068,
	latitude: 10.84098,
	zoom: 17.35,
	pitch: 52,
	bearing: -12,
};

const MAP_LIGHT = {
	anchor: "viewport",
	color: "#ffffff",
	intensity: 0.45,
	position: [1.5, 205, 42],
};

const INTERACTIVE_3D_LAYERS = [
	"campus-3d-rooms-layer",
	"campus-3d-slabs-layer",
	"campus-3d-walls-layer",
	"campus-3d-ghost-rooms-layer",
	"campus-3d-ghost-slabs-layer",
	"campus-3d-ghost-walls-layer",
];

// OpenStreetMap Standard Raster Style with multi-subdomain CDN for speed & reliability
const OSM_STYLE = {
	version: 8,
	light: MAP_LIGHT,
	sources: {
		"osm-tiles": {
			type: "raster",
			tiles: [
				"https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
				"https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
				"https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
			],
			tileSize: 256,
			attribution: "© OpenStreetMap contributors",
			maxzoom: 19,
		},
	},
	layers: [
		{
			id: "osm-background",
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
	onSelectArea,
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

	// ===== 3D Vector Geometry Store & Floor Slicing (NO PNG OVERLAY IN PRODUCTION) =====
	const [geometryStore, setGeometryStore] = useState(() => loadGeometryStore());
	const [activeBuilding3d, setActiveBuilding3d] = useState("ALL");
	const [activeFloorOrder3d, setActiveFloorOrder3d] = useState("ALL");
	const [floorGapStep, setFloorGapStep] = useState(FLOOR_ELEVATION_STEP);
	const isMultiFloorView =
		activeBuilding3d === "ALL" || activeFloorOrder3d === "ALL";

	useEffect(() => {
		const refreshGeometry = () => setGeometryStore(loadGeometryStore());
		window.addEventListener("icss-3d-geometry-updated", refreshGeometry);
		return () => window.removeEventListener("icss-3d-geometry-updated", refreshGeometry);
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

	const {
		slabsGeoJSON,
		wallsGeoJSON,
		roomsGeoJSON,
		roomLabelsGeoJSON,
		portalSymbolsGeoJSON,
		portalShaftsGeoJSON,
		buildingLabelsGeoJSON,
	} = useMemo(() => {
		return buildVector3dLayers({
			store: geometryStore,
			selectedBuilding: activeBuilding3d,
			activeFloorOrder: activeFloorOrder3d,
			areas,
			selectedAreaId,
			creatingPreview: activeCreatingPreview,
			viewRole: isAdmin ? "ADMIN" : "FACILITY_MANAGER",
			showOtherBuildingsInFocus: true,
			floorElevationStep: floorGapStep,
		});
	}, [
		geometryStore,
		activeBuilding3d,
		activeFloorOrder3d,
		areas,
		selectedAreaId,
		activeCreatingPreview,
		isAdmin,
		floorGapStep,
	]);

	const handleChangeFloorGapStep = useCallback(
		(nextStep) => {
			setFloorGapStep(nextStep);
			const map = mapRef.current?.getMap?.() || mapRef.current;
			if (!map?.easeTo) return;
			const curPitch = map.getPitch?.() ?? CAMPUS_CENTER.pitch;
			const curZoom = map.getZoom?.() ?? CAMPUS_CENTER.zoom;
			const isExpanded = nextStep > FLOOR_ELEVATION_STEP + 0.05;
			map.easeTo({
				pitch: isExpanded ? Math.max(curPitch, 58) : 52,
				zoom:
					activeBuilding3d === "ALL" && isExpanded
						? Math.min(curZoom, 17.15)
						: curZoom,
				duration: 450,
			});
		},
		[activeBuilding3d],
	);

	// Mục 1B: đồng bộ ảnh Canvas Sprite nhãn phòng ngay khi roomLabelsGeoJSON thay đổi
	useEffect(() => {
		const map = mapRef.current?.getMap?.() || mapRef.current;
		if (map) {
			registerRoomLabelSprites(map, roomLabelsGeoJSON);
		}
	}, [roomLabelsGeoJSON]);

	const slabsSplit = useMemo(() => splitGhostActiveGeoJSON(slabsGeoJSON), [slabsGeoJSON]);
	const wallsSplit = useMemo(() => splitGhostActiveGeoJSON(wallsGeoJSON), [wallsGeoJSON]);
	const roomsSplit = useMemo(() => splitGhostActiveGeoJSON(roomsGeoJSON), [roomsGeoJSON]);

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
		(bName, targetFloorOrder = "ALL", { syncParent = true } = {}) => {
			setActiveBuilding3d(bName);
			if (bName === "ALL") {
				setActiveFloorOrder3d("ALL");
				if (syncParent) {
					notifyParentMapFilter("ALL", "ALL");
				}
				if (mapRef.current) {
					mapRef.current.flyTo({
						center: [CAMPUS_CENTER.longitude, CAMPUS_CENTER.latitude],
						zoom: CAMPUS_CENTER.zoom,
						pitch: CAMPUS_CENTER.pitch,
						bearing: CAMPUS_CENTER.bearing,
						duration: 750,
					});
				}
				return;
			}
			const bld = DEFAULT_BUILDINGS_META.find((b) =>
				matchesBuilding(bName, b.name),
			);
			if (bld) {
				let resolvedFl = "ALL";
				if (targetFloorOrder !== "ALL") {
					const validFloor = bld.floors.some(
						(f) => f.floorOrder === Number(targetFloorOrder),
					);
					resolvedFl = validFloor ? Number(targetFloorOrder) : 0;
				}
				setActiveFloorOrder3d(resolvedFl);
				if (syncParent) {
					notifyParentMapFilter(bld.name, resolvedFl);
				}
				if (mapRef.current) {
					mapRef.current.flyTo({
						center: bld.center,
						zoom: 18.7,
						pitch: 55,
						bearing: -12,
						duration: 750,
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
		});
	}, [
		selectedBuilding,
		selectedFloor,
		isCreatingArea,
		isEditingCoords,
		handleSelect3dBuilding,
	]);

	// Tự động resize map khi sidebar trái thu gọn / mở rộng
	useEffect(() => {
		const timer = setTimeout(() => {
			if (mapRef.current) {
				mapRef.current.resize();
			}
		}, 250);
		return () => clearTimeout(timer);
	}, [isLeftSidebarCollapsed]);

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
		const bObj = buildingsList.find((b) => matchesBuilding(b.name, initialBuilding));
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
			mapRef.current.flyTo({ center: cur, zoom: 18.7, pitch: 55, duration: 600 });
		}
	}, [isAdmin, rebindAreaRequestId, editingAreaId, areas]);

	// Khi chọn 1 Area từ danh sách bên trái, tự động mở đúng tòa nhà và cắt đúng tầng của Area đó
	const prevSyncedAreaIdRef = useRef(selectedAreaId);
	useEffect(() => {
		if (!selectedAreaId || prevSyncedAreaIdRef.current === selectedAreaId) {
			prevSyncedAreaIdRef.current = selectedAreaId;
			return;
		}
		prevSyncedAreaIdRef.current = selectedAreaId;
		if (isCreatingArea || isEditingCoords) return;
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
					zoom: 18.7,
					pitch: 55,
					bearing: -12,
					duration: 700,
				});
			}
		}
	}, [selectedAreaId, areas, isCreatingArea, isEditingCoords]);

	const isCreateFormIncomplete =
		!createDraftCoords || !createFormData.name.trim() || !createFormData.floorId;

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
			setActiveFloorOrder3d(resolveFloorOrder(matchedBld.name, selectedArea.floor));
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
	}, [editingArea, draftRoomMeta, draftCoords, onAreaUpdated, onRebindAreaDone]);

	const handleMapMouseMove = useCallback(
		(e) => {
			const features = e?.features || [];
			setHoverCursor(features.length > 0 ? "pointer" : "grab");
			const roomFeat = features.find(
				(f) => f.layer?.id === "campus-3d-rooms-layer" && !f.properties?.isGhost,
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
					: isAdmin
						? "Nhấp để tạo Khu vực"
						: "Chưa gán khu vực";
				setHoveredRoom({
					x: e.point.x,
					y: e.point.y,
					isOccupied: Boolean(p.matchedAreaId),
					title: p.matchedAreaName || "Phòng trống",
					subtitle: p.matchedAreaId
						? levelCfg?.badgeLabel || levelCfg?.name || "Đã gán Khu vực"
						: emptySub,
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
		[isAdmin, isEditingCoords, activeBuilding3d, activeFloorOrder3d],
	);

	// Interactive 3D Map Click: Create/Rebind chỉ nhận block ROOM trống; Click block ROOM trống ở chế độ xem (ADMIN) tự mở Tạo khu vực
	const handleMapClick = useCallback(
		(e) => {
			const features = e?.features || [];
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
					matchesBuilding(clickedGhostFeature.properties?.building, activeBuilding3d)
				) {
					setActiveFloorOrder3d(Number(clickedGhostFeature.properties.floorOrder));
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
				handleSelect3dBuilding(clickedBuilding, targetFloor);
				if (hasSpecificAreaRoom) {
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
				notifyParentMapFilter(
					hitProps.building || activeBuilding3d,
					nextFl,
				);
			}
		},
		[
			isCreatingArea,
			isEditingCoords,
			isAdmin,
			editingAreaId,
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
			className={`campus-map-layout ${railCollapsed || (hideRail && !isCreatingArea) ? "campus-map-layout--expanded" : ""} ${isCreatingArea ? "campus-map-layout--create" : ""}`}
		>
			{/* LEFT: MAIN MAPLIBRE CANVAS */}
			<div className="campus-map-card">
				{/* Floating Top Controls (Sidebar Toggle + 3D Building/Floor Select-Options + Compass) */}
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
						<Building2 size={13} className="campus-3d-slicer-select-icon" />
						<select
							className="campus-3d-slicer-select"
							aria-label="Chọn tòa nhà 3D trên bản đồ"
							value={
								DEFAULT_BUILDINGS_META.find((b) =>
									matchesBuilding(activeBuilding3d, b.name),
								)?.name || "ALL"
							}
							onChange={(e) => handleSelect3dBuilding(e.target.value)}
						>
							<option value="ALL">Toàn khuôn viên</option>
							{DEFAULT_BUILDINGS_META.map((b) => (
								<option key={b.id} value={b.name}>
									{b.name}
								</option>
							))}
						</select>
						<ChevronDown size={13} className="campus-3d-slicer-select-arrow" />
					</div>

					{activeBuildingMeta3d && (
						<div className="campus-3d-slicer-select-wrap">
							<Layers size={13} className="campus-3d-slicer-select-icon" />
							<select
								className="campus-3d-slicer-select"
								aria-label={`Chọn tầng của ${activeBuildingMeta3d.name}`}
								value={
									activeFloorOrder3d === "ALL"
										? "ALL"
										: String(activeFloorOrder3d)
								}
								onChange={(e) => {
									const val = e.target.value;
									const nextFl = val === "ALL" ? "ALL" : Number(val);
									setActiveFloorOrder3d(nextFl);
									notifyParentMapFilter(activeBuilding3d, nextFl);
								}}
							>
								<option value="ALL">Toàn tòa</option>
								{activeBuildingMeta3d.floors.map((fl) => (
									<option key={fl.id} value={String(fl.floorOrder)}>
										{fl.shortLabel}
									</option>
								))}
							</select>
							<ChevronDown size={13} className="campus-3d-slicer-select-arrow" />
						</div>
					)}

					{isMultiFloorView && (
						<div
							className="campus-3d-gap-control"
							role="group"
							aria-label="Giãn cách tầng 3D"
							title="Giãn cách chiều cao giữa các tầng"
						>
							{EXPLODED_FLOOR_STEP_OPTIONS.map((opt) => {
								const isOptActive = Math.abs(floorGapStep - opt.step) < 0.05;
								return (
									<button
										key={opt.id}
										type="button"
										className={`campus-3d-gap-btn ${isOptActive ? "campus-3d-gap-btn--active" : ""}`}
										aria-pressed={isOptActive}
										onClick={() => handleChangeFloorGapStep(opt.step)}
										title={`${opt.label} (${opt.step}m/tầng)`}
									>
										{opt.label}
									</button>
								);
							})}
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
							title={railCollapsed ? "Mở danh sách khu vực" : "Thu gọn danh sách"}
							aria-label={railCollapsed ? "Mở danh sách khu vực" : "Thu gọn danh sách"}
						>
							{railCollapsed ? (
								<PanelRightOpen size={14} />
							) : (
								<PanelRightClose size={14} />
							)}
						</button>
					)}
				</div>

				{/* Chip chỉ dẫn tạo khu vực gọn gàng trên bản đồ */}
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
								? "Đã chọn block · Nhấn block trống khác để đổi"
								: "Chọn block phòng 3D trống"}
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
								disabled={savingCoords || !draftRoomMeta?.floorId || !isValidLngLat(draftCoords)}
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

				{/* Map Viewport */}
				<div
					className={`campus-map-viewport ${compactLabels ? "campus-map-viewport--compact" : ""} ${isEditingCoords ? "campus-map-viewport--editing" : ""}`}
				>
					<MapGL
						ref={mapRef}
						initialViewState={CAMPUS_CENTER}
						onLoad={(e) => {
							const map = e?.target;
							if (!map) return;
							registerPortalMapImages(map);
							registerRoomLabelSprites(map, roomLabelsGeoJSON);
							map.on?.("styleimagemissing", (ev) => {
								ensurePortalMapImage(map, ev.id);
								ensureRoomLabelSpriteImage(map, ev.id);
							});
							map.setLight?.(MAP_LIGHT);
						}}
						onZoomEnd={(e) => setMapZoom(e.viewState.zoom)}
						onClick={handleMapClick}
						onMouseMove={handleMapMouseMove}
						onMouseLeave={() => {
							setHoverCursor("grab");
							setHoveredRoom(null);
						}}
						cursor={hoverCursor}
						interactiveLayerIds={INTERACTIVE_3D_LAYERS}
						light={MAP_LIGHT}
						mapStyle={OSM_STYLE}
						style={{ width: "100%", height: "100%" }}
						minZoom={1}
						maxZoom={22}
						maxPitch={72}
						boxZoom={false}
					>
						{/* Native Map Controls */}
						<NavigationControl
							position="top-right"
							showCompass
							showZoom
						/>
						<FullscreenControl position="top-right" />

						{/* 1-3. Ghost Lower Floors trước, Active Floors sau (mỗi Layer nằm trực tiếp trong Source riêng để tránh race khi load style) */}
						<Source id="campus-3d-ghost-slabs-src" type="geojson" data={slabsSplit.ghost}>
							<Layer
								id="campus-3d-ghost-slabs-layer"
								type="fill-extrusion"
								paint={{
									"fill-extrusion-color": ["get", "color"],
									"fill-extrusion-base": ["get", "baseHeight"],
									"fill-extrusion-height": ["get", "extrudeHeight"],
									"fill-extrusion-opacity": 0.14,
									"fill-extrusion-vertical-gradient": true,
								}}
							/>
						</Source>
						<Source id="campus-3d-ghost-walls-src" type="geojson" data={wallsSplit.ghost}>
							<Layer
								id="campus-3d-ghost-walls-layer"
								type="fill-extrusion"
								paint={{
									"fill-extrusion-color": ["get", "color"],
									"fill-extrusion-base": ["get", "baseHeight"],
									"fill-extrusion-height": ["get", "extrudeHeight"],
									"fill-extrusion-opacity": 0.16,
									"fill-extrusion-vertical-gradient": true,
								}}
							/>
						</Source>
						<Source id="campus-3d-ghost-rooms-src" type="geojson" data={roomsSplit.ghost}>
							<Layer
								id="campus-3d-ghost-rooms-layer"
								type="fill-extrusion"
								paint={{
									"fill-extrusion-color": ["get", "color"],
									"fill-extrusion-base": ["get", "baseHeight"],
									"fill-extrusion-height": ["get", "extrudeHeight"],
									"fill-extrusion-opacity": 0.12,
									"fill-extrusion-vertical-gradient": true,
								}}
							/>
						</Source>

						{/* Lớp Đế Sàn, Tường, Mái Tổng Quan & Khối Phòng 3D Tầng Active */}
						<Source id="campus-3d-slabs-src" type="geojson" data={slabsSplit.active}>
							<Layer
								id="campus-3d-slabs-layer"
								type="fill-extrusion"
								paint={{
									"fill-extrusion-color": ["get", "color"],
									"fill-extrusion-base": ["get", "baseHeight"],
									"fill-extrusion-height": ["get", "extrudeHeight"],
									"fill-extrusion-opacity":
										activeBuilding3d === "ALL" ? 0.98 : 0.96,
									"fill-extrusion-vertical-gradient": true,
								}}
							/>
						</Source>
						<Source id="campus-3d-walls-src" type="geojson" data={wallsSplit.active}>
							<Layer
								id="campus-3d-walls-layer"
								type="fill-extrusion"
								paint={{
									"fill-extrusion-color": ["get", "color"],
									"fill-extrusion-base": ["get", "baseHeight"],
									"fill-extrusion-height": ["get", "extrudeHeight"],
									"fill-extrusion-opacity": 1.0,
									"fill-extrusion-vertical-gradient": true,
								}}
							/>
						</Source>
						<Source id="campus-3d-rooms-src" type="geojson" data={roomsSplit.active}>
							<Layer
								id="campus-3d-rooms-layer"
								type="fill-extrusion"
								paint={{
									"fill-extrusion-color": ["get", "color"],
									"fill-extrusion-base": ["get", "baseHeight"],
									"fill-extrusion-height": ["get", "extrudeHeight"],
									"fill-extrusion-opacity": 0.9,
									"fill-extrusion-vertical-gradient": true,
								}}
							/>
						</Source>

						{/* 4. Trục thang máy / thang bộ Kiểu B (đường tâm đứng mảnh khi Giãn tầng > 3.5m) */}
						<Source
							id="campus-3d-portal-shafts-src"
							type="geojson"
							data={portalShaftsGeoJSON}
						>
							<Layer
								id="campus-3d-portal-shafts-layer"
								type="fill-extrusion"
								paint={{
									"fill-extrusion-color": ["get", "color"],
									"fill-extrusion-base": ["get", "baseHeight"],
									"fill-extrusion-height": ["get", "extrudeHeight"],
									"fill-extrusion-opacity": 0.78,
									"fill-extrusion-vertical-gradient": false,
								}}
							/>
						</Source>

						{/* 5. Lớp Nhãn Tên Khu Vực & Biểu Tượng Cầu Thang / Thang Máy (Nâng theo cao độ tầng 3D) */}
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
									"symbol-height-anchor": "ground",
									"symbol-height-offset": [
										"coalesce",
										["get", "heightOffset"],
										0.5,
									],
								}}
								paint={{
									"icon-opacity": [
										"case",
										["get", "isGhost"],
										0.35,
										0.96,
									],
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
									"symbol-sort-key": [
										"case",
										["get", "isSelected"],
										0,
										10,
									],
									"symbol-height-anchor": "ground",
									"symbol-height-offset": [
										"coalesce",
										["get", "heightOffset"],
										1.0,
									],
								}}
								paint={{
									"icon-opacity": 0.98,
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
								hoveredRoom.isOccupied
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
					{isAdmin ? (
						<>
							<div className="campus-map-legend__item">
								<span className="campus-map-legend__dot campus-map-legend__dot--public" />
								<span>{AREA_LEVEL_CONFIG.PUBLIC.badgeLabel}</span>
							</div>
							<div className="campus-map-legend__item">
								<span className="campus-map-legend__dot campus-map-legend__dot--contact" />
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
						</>
					) : (
						<>
							<div className="campus-map-legend__item">
								<span
									className="campus-map-legend__dot"
									style={{ background: "#475569" }}
								/>
								<span>Khu vực hoạt động</span>
							</div>
							<div className="campus-map-legend__item">
								<span
									className="campus-map-legend__dot"
									style={{ background: "#2563eb" }}
								/>
								<span>Đang chọn</span>
							</div>
						</>
					)}
				</div>
			</div>

			{/* RIGHT RAIL: AREAS LIST & DETAILS OR CREATE PANEL */}
			{(!hideRail || isCreatingArea) && (
				<div className="campus-rail">
					{isCreatingArea ? (
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
									<div className="campus-create-floor-warning" role="alert">
										<AlertCircle size={15} />
										<span>
											Tầng chưa có trong CSDL — chỉ dùng để xem 3D.
										</span>
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
										isCreateFormIncomplete ? "area-btn-modal--submit-blurred" : ""
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
											<Loader2 size={14} className="animate-spin" />
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
										<div className="campus-detail-empty">
											Chưa có khu vực
										</div>
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
