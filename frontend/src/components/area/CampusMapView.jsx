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
	LocateFixed,
	Menu,
	Check,
} from "lucide-react";
import {
	getLevelConfig,
	getAccessLevelConfig,
	getErrorMessage,
	AREA_LEVEL_CONFIG,
} from "../../utils/areaHelpers";
import { createArea, updateArea } from "../../services/areaService";
import {
	DEFAULT_BUILDINGS_META,
	loadGeometryStore,
	buildVector3dLayers,
	buildRoute3dLayers,
	computeDigitizedRoute,
	matchesBuilding,
	resolveFloorOrder,
	splitGhostActiveGeoJSON,
} from "../../data/campusIndoor3dData";
import { Select } from "../ui";
import "../../styles/CampusMapView.css";

// FPT University HCMC Campus default center framing both moved Tòa HCM & Tòa Epsilon
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
	isCreatingArea = false,
	onCancelCreateArea,
	onAreaCreated,
	hideRail = false,
	isLeftSidebarCollapsed = false,
	onToggleLeftSidebar,
}) {
	const mapRef = useRef(null);
	const [railCollapsed, setRailCollapsed] = useState(false);
	const [mapZoom, setMapZoom] = useState(CAMPUS_CENTER.zoom);
	const compactLabels = mapZoom < LABEL_MIN_ZOOM;
	const [hoverCursor, setHoverCursor] = useState("grab");

	// ===== 3D Vector Geometry Store & Floor Slicing (NO PNG OVERLAY IN PRODUCTION) =====
	const [geometryStore, setGeometryStore] = useState(() => loadGeometryStore());
	const [activeBuilding3d, setActiveBuilding3d] = useState("ALL");
	const [activeFloorOrder3d, setActiveFloorOrder3d] = useState("ALL");
	const [activeRoute3d, setActiveRoute3d] = useState(null);

	const activeRouteEndpoints = useMemo(() => {
		const feats = activeRoute3d?.geojson?.features;
		if (!Array.isArray(feats) || feats.length === 0) return null;
		const start = feats[0]?.geometry?.coordinates?.[0];
		const lastCoords = feats[feats.length - 1]?.geometry?.coordinates;
		const end = lastCoords ? lastCoords[lastCoords.length - 1] : null;
		if (!start || !end) return null;
		return { start, end };
	}, [activeRoute3d]);

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

	const {
		slabsGeoJSON,
		wallsGeoJSON,
		roomsGeoJSON,
		roomLabelsGeoJSON,
		buildingLabelsGeoJSON,
	} = useMemo(() => {
		return buildVector3dLayers({
			store: geometryStore,
			selectedBuilding: activeBuilding3d,
			activeFloorOrder: activeFloorOrder3d,
			areas,
			selectedAreaId,
			viewRole: isAdmin ? "ADMIN" : "FACILITY_MANAGER",
			showOtherBuildingsInFocus: true,
		});
	}, [
		geometryStore,
		activeBuilding3d,
		activeFloorOrder3d,
		areas,
		selectedAreaId,
		isAdmin,
	]);

	const route3dLayers = useMemo(() => {
		return buildRoute3dLayers(activeRoute3d, {
			selectedBuilding: activeBuilding3d,
			activeFloorOrder: activeFloorOrder3d,
		});
	}, [activeRoute3d, activeBuilding3d, activeFloorOrder3d]);

	const slabsSplit = useMemo(() => splitGhostActiveGeoJSON(slabsGeoJSON), [slabsGeoJSON]);
	const wallsSplit = useMemo(() => splitGhostActiveGeoJSON(wallsGeoJSON), [wallsGeoJSON]);
	const roomsSplit = useMemo(() => splitGhostActiveGeoJSON(roomsGeoJSON), [roomsGeoJSON]);
	const routeRibbonsSplit = useMemo(
		() => splitGhostActiveGeoJSON(route3dLayers.ribbonsGeoJSON),
		[route3dLayers.ribbonsGeoJSON],
	);

	const handleSelect3dBuilding = useCallback(
		(bName, targetFloorOrder = "ALL", keepRoute = false) => {
			setActiveBuilding3d(bName);
			if (!keepRoute) {
				setActiveRoute3d(null);
			}
			if (bName === "ALL") {
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
				if (targetFloorOrder === "ALL") {
					setActiveFloorOrder3d("ALL");
				} else {
					const validFloor = bld.floors.some(
						(f) => f.floorOrder === Number(targetFloorOrder),
					);
					setActiveFloorOrder3d(validFloor ? Number(targetFloorOrder) : 0);
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
		[],
	);

	// Tự động resize map khi sidebar trái thu gọn / mở rộng
	useEffect(() => {
		const timer = setTimeout(() => {
			if (mapRef.current) {
				mapRef.current.resize();
			}
		}, 250);
		return () => clearTimeout(timer);
	}, [isLeftSidebarCollapsed]);

	// Chế độ tạo khu vực trên bản đồ (Map-First Creation)
	const [createDraftCoords, setCreateDraftCoords] = useState(null); // [lng, lat]
	const [createFormData, setCreateFormData] = useState({
		name: "",
		areaLevel: "PUBLIC",
		building: "",
		floor: "",
		floorId: null,
	});
	const [creatingAreaLoading, setCreatingAreaLoading] = useState(false);
	const [createFormError, setCreateFormError] = useState(null);

	// Chế độ đặt tọa độ trực tiếp trên bản đồ (chỉ ADMIN)
	const [editingAreaId, setEditingAreaId] = useState(null);
	const [draftCoords, setDraftCoords] = useState(null); // [lng, lat]
	const [savingCoords, setSavingCoords] = useState(false);
	const [locating, setLocating] = useState(false);
	const [coordError, setCoordError] = useState(null);
	const [coordSuccess, setCoordSuccess] = useState(null);
	const isEditingCoords = Boolean(editingAreaId);

	// Tự động điền dữ liệu tòa nhà / tầng ban đầu khi bật chế độ tạo (đồng bộ filter)
	useEffect(() => {
		if (isCreatingArea) {
			const initialBuilding =
				selectedBuilding && selectedBuilding !== "ALL"
					? selectedBuilding
					: buildingsList[0]?.name || "Tòa Alpha";
			const bObj = buildingsList.find((b) => b.name === initialBuilding);
			const firstFloors = bObj?.floors || [];
			const initialFloor =
				selectedFloor && selectedFloor !== "ALL"
					? selectedFloor
					: firstFloors[0]?.name || "Tầng Trệt";
			const initialFloorId =
				firstFloors.find((f) => f.name === initialFloor)?.id ||
				firstFloors[0]?.id ||
				null;

			setCreateFormData({
				name: "",
				areaLevel: "PUBLIC",
				building: initialBuilding,
				floor: initialFloor,
				floorId: initialFloorId,
			});
			setCreateDraftCoords(null);
			setCreateFormError(null);
			setRailCollapsed(false);
		}
	}, [isCreatingArea, buildingsList, selectedBuilding, selectedFloor]);

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

	const availableCreateFloors = useMemo(() => {
		const bObj = buildingsList.find((b) => b.name === createFormData.building);
		if (bObj?.floors?.length > 0) {
			return bObj.floors;
		}
		return [
			{ id: null, name: "Tầng Trệt" },
			{ id: null, name: "Tầng 1" },
		];
	}, [buildingsList, createFormData.building]);

	const handleCreateBuildingChange = (bName) => {
		const bObj = buildingsList.find((b) => b.name === bName);
		const floors = bObj?.floors || [];
		const defaultFloor = floors[0]?.name || "Tầng Trệt";
		const defaultFloorId = floors[0]?.id || null;
		setCreateFormData((prev) => ({
			...prev,
			building: bName,
			floor: defaultFloor,
			floorId: defaultFloorId,
		}));
	};

	const handleCreateFloorChange = (fName) => {
		const floorObj = availableCreateFloors.find((fl) => fl.name === fName);
		setCreateFormData((prev) => ({
			...prev,
			floor: fName,
			floorId: floorObj?.id || null,
		}));
	};

	const isCreateFormIncomplete = !createDraftCoords || !createFormData.name.trim();

	const handleCreateAreaSubmit = async (e) => {
		e?.preventDefault?.();
		if (!createDraftCoords) {
			setCreateFormError("Vui lòng nhấp vào bản đồ để chọn tọa độ.");
			return;
		}
		const nameTrimmed = createFormData.name.trim();
		if (!nameTrimmed) {
			setCreateFormError("Tên khu vực không được để trống.");
			return;
		}
		if (nameTrimmed.length > 150) {
			setCreateFormError("Tên khu vực tối đa 150 ký tự.");
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

	// Thoát chế độ chỉnh tọa độ nếu khu vực đang sửa bị lọc khỏi danh sách
	useEffect(() => {
		if (editingAreaId && !editingArea) {
			setEditingAreaId(null);
			setDraftCoords(null);
		}
	}, [editingAreaId, editingArea]);

	// Tự ẩn thông báo thành công
	useEffect(() => {
		if (!coordSuccess) return undefined;
		const t = setTimeout(() => setCoordSuccess(null), 3000);
		return () => clearTimeout(t);
	}, [coordSuccess]);

	// Tính toán danh sách khu vực có tọa độ hiển thị (tự dịch pixel trên màn hình khi trùng hoặc gần tọa độ CSDL)
	const { positionedAreas, unpositionedCount } = useMemo(() => {
		const groups = [];
		let unpositioned = 0;

		function getDistanceMeters(c1, c2) {
			const dLat = (c2[1] - c1[1]) * 111320;
			const dLng =
				(c2[0] - c1[0]) * (111320 * Math.cos((c1[1] * Math.PI) / 180));
			return Math.sqrt(dLat * dLat + dLng * dLng);
		}

		areas.forEach((area) => {
			const baseCoords = getValidCoordinates(area);
			if (!baseCoords) {
				unpositioned++;
				return;
			}

			const levelConfig = getLevelConfig(area.areaLevel || area.level);
			const decorated = {
				...area,
				baseCoords,
				color: levelConfig.color || "#3b82f6",
				levelConfig,
			};

			// Gom nhóm các khu vực trùng hoặc cách nhau dưới 12 mét
			let matchedGroup = groups.find(
				(g) => getDistanceMeters(g.centerCoords, baseCoords) < 12,
			);
			if (matchedGroup) {
				matchedGroup.items.push(decorated);
			} else {
				groups.push({
					centerCoords: baseCoords,
					items: [decorated],
				});
			}
		});

		// Tính toán độ lệch pixel (pixelOffset) đảm bảo các pin không bao giờ bị che khuất ở mọi mức zoom
		const result = [];
		groups.forEach((group) => {
			const n = group.items.length;
			if (n === 1) {
				result.push({
					...group.items[0],
					pixelOffset: [0, 0],
					isShifted: false,
					idx: 0,
				});
			} else if (n === 2) {
				// Tách ngang rõ ràng sang 2 bên (cách nhau 40px)
				result.push({
					...group.items[0],
					pixelOffset: [-20, 0],
					isShifted: true,
					idx: 0,
				});
				result.push({
					...group.items[1],
					pixelOffset: [20, 0],
					isShifted: true,
					idx: 1,
				});
			} else if (n === 3) {
				result.push({
					...group.items[0],
					pixelOffset: [-22, 8],
					isShifted: true,
					idx: 0,
				});
				result.push({
					...group.items[1],
					pixelOffset: [22, 8],
					isShifted: true,
					idx: 1,
				});
				result.push({
					...group.items[2],
					pixelOffset: [0, -22],
					isShifted: true,
					idx: 2,
				});
			} else if (n === 4) {
				result.push({
					...group.items[0],
					pixelOffset: [-22, -14],
					isShifted: true,
					idx: 0,
				});
				result.push({
					...group.items[1],
					pixelOffset: [22, -14],
					isShifted: true,
					idx: 1,
				});
				result.push({
					...group.items[2],
					pixelOffset: [-22, 14],
					isShifted: true,
					idx: 2,
				});
				result.push({
					...group.items[3],
					pixelOffset: [22, 14],
					isShifted: true,
					idx: 3,
				});
			} else {
				// Đa giác đều / vòng tròn mở rộng theo số lượng pin
				const radius = Math.min(50, 26 + n * 3);
				group.items.forEach((item, idx) => {
					const angle = (2 * Math.PI * idx) / n - Math.PI / 2;
					const dx = Math.round(radius * Math.cos(angle));
					const dy = Math.round(radius * 0.75 * Math.sin(angle));
					result.push({
						...item,
						pixelOffset: [dx, dy],
						isShifted: true,
						idx,
					});
				});
			}
		});

		return {
			positionedAreas: result,
			unpositionedCount: unpositioned,
		};
	}, [areas]);

	const displayedPositionedAreas = useMemo(() => {
		if (activeBuilding3d === "ALL" || activeFloorOrder3d === "ALL") {
			return positionedAreas;
		}
		return positionedAreas.filter((item) => {
			if (editingAreaId === item.id || selectedAreaId === item.id) return true;
			if (!item.building) return true;
			if (!matchesBuilding(item.building, activeBuilding3d)) return false;
			return (
				resolveFloorOrder(item.building, item.floor) ===
				Number(activeFloorOrder3d)
			);
		});
	}, [
		positionedAreas,
		activeBuilding3d,
		activeFloorOrder3d,
		editingAreaId,
		selectedAreaId,
	]);

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

	// ===== Đặt tọa độ trên bản đồ (ADMIN) =====
	const handleStartEditCoords = useCallback(() => {
		if (!isAdmin || !selectedArea) return;
		const current = getValidCoordinates(selectedArea);
		let initial = current;
		if (!initial && mapRef.current) {
			const c = mapRef.current.getCenter();
			initial = [c.lng, c.lat];
		}
		setEditingAreaId(selectedArea.id);
		setDraftCoords(
			initial || [CAMPUS_CENTER.longitude, CAMPUS_CENTER.latitude],
		);
		setCoordError(null);
		setCoordSuccess(null);
		if (current) handleFlyToCoords(current, 18);
	}, [isAdmin, selectedArea, handleFlyToCoords]);

	const handleCancelEditCoords = useCallback(() => {
		setEditingAreaId(null);
		setDraftCoords(null);
		setCoordError(null);
	}, []);

	const handleUseCurrentLocation = useCallback(() => {
		if (!navigator.geolocation) {
			setCoordError("Trình duyệt không hỗ trợ định vị GPS.");
			return;
		}
		setLocating(true);
		setCoordError(null);
		navigator.geolocation.getCurrentPosition(
			(pos) => {
				const coords = [pos.coords.longitude, pos.coords.latitude];
				setDraftCoords(coords);
				handleFlyToCoords(coords, 18);
				setLocating(false);
			},
			(err) => {
				setLocating(false);
				setCoordError(
					err?.code === 1
						? "Bạn đã từ chối quyền truy cập vị trí."
						: "Không lấy được vị trí hiện tại. Vui lòng thử lại.",
				);
			},
			{ enableHighAccuracy: true, timeout: 10000, maximumAge: 0 },
		);
	}, [handleFlyToCoords]);

	const handleSaveCoords = useCallback(async () => {
		if (!editingArea || !isValidLngLat(draftCoords)) {
			setCoordError("Tọa độ không hợp lệ (vĩ độ -90..90, kinh độ -180..180).");
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
			// Giữ nguyên tên, loại, tầng hiện tại — chỉ đổi tọa độ (BR-TC-13: gửi kèm version)
			const res = await updateArea(editingArea.id, {
				name: editingArea.name,
				areaLevel:
					typeof editingArea.areaLevel === "string"
						? editingArea.areaLevel
						: editingArea.areaLevel?.code,
				building: null,
				floor: null,
				floorId: null,
				centerLatitude: Number(lat.toFixed(7)),
				centerLongitude: Number(lng.toFixed(7)),
				version: editingArea.version,
			});
			onAreaUpdated?.(editingArea.id, res);
			setEditingAreaId(null);
			setDraftCoords(null);
			setCoordSuccess("Đã cập nhật tọa độ khu vực.");
		} catch (err) {
			setCoordError(getErrorMessage(err));
		} finally {
			setSavingCoords(false);
		}
	}, [editingArea, draftCoords, onAreaUpdated]);

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

			if (isCreatingArea && e?.lngLat) {
				const targetLng =
					roomProps?.centerLng != null
						? Number(Number(roomProps.centerLng).toFixed(6))
						: Number(e.lngLat.lng.toFixed(6));
				const targetLat =
					roomProps?.centerLat != null
						? Number(Number(roomProps.centerLat).toFixed(6))
						: Number(e.lngLat.lat.toFixed(6));
				setCreateDraftCoords([targetLng, targetLat]);
				if (roomProps?.building) {
					const matchedOpt = buildingsList.find((b) =>
						matchesBuilding(b.name, roomProps.building),
					);
					if (matchedOpt) {
						const fls = matchedOpt.floors || [];
						const matchedFl = fls.find(
							(fl) =>
								resolveFloorOrder(matchedOpt.name, fl.name) ===
								Number(roomProps.floorOrder),
						);
						setCreateFormData((prev) => ({
							...prev,
							building: matchedOpt.name,
							floor: matchedFl?.name || prev.floor,
							floorId: matchedFl?.id || prev.floorId,
						}));
					}
				}
				setCreateFormError(null);
				setRailCollapsed(false);
				return;
			}
			if (isEditingCoords && e?.lngLat) {
				const targetLng =
					roomProps?.centerLng != null
						? Number(roomProps.centerLng)
						: e.lngLat.lng;
				const targetLat =
					roomProps?.centerLat != null
						? Number(roomProps.centerLat)
						: e.lngLat.lat;
				setDraftCoords([targetLng, targetLat]);
				setCoordError(null);
				return;
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
				const targetFloor = hasSpecificAreaRoom
					? Number(roomProps.floorOrder ?? 0)
					: hitProps.floorOrder != null
						? Number(hitProps.floorOrder)
						: "ALL";
				handleSelect3dBuilding(clickedBuilding, targetFloor, hasSpecificAreaRoom);
				if (hasSpecificAreaRoom) {
					onSelectArea?.(roomProps.matchedAreaId);
					if (roomProps.centerLng != null && roomProps.centerLat != null) {
						const route = computeDigitizedRoute({
							store: geometryStore,
							startCoords: [106.80915, 10.84072],
							startBuilding: "OUTDOOR",
							startFloorOrder: 0,
							endCoords: [
								Number(roomProps.centerLng),
								Number(roomProps.centerLat),
							],
							endBuilding: clickedBuilding,
							endFloorOrder: Number(targetFloor) || 0,
						});
						setActiveRoute3d(route);
					}
				}
				return;
			}

			// Đang ở trong cùng tòa nhà: click vào phòng -> cắt đúng tầng (nếu đang Toàn tòa), chọn Area & dẫn đường 3D A*
			if (clickedRoomFeature && roomProps) {
				const roomFloor = Number(roomProps.floorOrder ?? 0);
				if (activeFloorOrder3d === "ALL") {
					setActiveFloorOrder3d(roomFloor);
				}
				if (roomProps.matchedAreaId) {
					onSelectArea?.(roomProps.matchedAreaId);
				}
				if (roomProps.centerLng != null && roomProps.centerLat != null) {
					const route = computeDigitizedRoute({
						store: geometryStore,
						startCoords: [106.80915, 10.84072],
						startBuilding: "OUTDOOR",
						startFloorOrder: 0,
						endCoords: [
							Number(roomProps.centerLng),
							Number(roomProps.centerLat),
						],
						endBuilding: roomProps.building,
						endFloorOrder: roomFloor,
					});
					setActiveRoute3d(route);
				}
				return;
			}

			// Click vào tầng ghost bên dưới (hoặc chọn 1 tầng khi đang Toàn tòa) -> chuyển tới tầng đó
			if (
				(activeFloorOrder3d === "ALL" ||
					(hitProps.isGhost && !hitProps.isContextBuilding)) &&
				hitProps.floorOrder != null
			) {
				setActiveFloorOrder3d(Number(hitProps.floorOrder));
				setActiveRoute3d(null);
			}
		},
		[
			isCreatingArea,
			isEditingCoords,
			buildingsList,
			activeBuilding3d,
			activeFloorOrder3d,
			handleSelect3dBuilding,
			onSelectArea,
			geometryStore,
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
									setActiveFloorOrder3d(val === "ALL" ? "ALL" : Number(val));
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
								? "Đã chọn vị trí · Kéo ghim để chỉnh lại"
								: "Nhấp vào bản đồ"}
						</span>
					</div>
				)}

				{/* Thanh công cụ đặt tọa độ (ADMIN) */}
				{isEditingCoords && editingArea && (
					<div className="campus-coord-editor">
						<div className="campus-coord-editor__head">
							<Crosshair size={14} />
							<span>
								Đặt vị trí: <strong>{editingArea.name}</strong>
							</span>
						</div>
						<p className="campus-coord-editor__hint">
							Nhấn lên bản đồ hoặc kéo ghim để chọn vị trí.
						</p>
						<div className="campus-coord-editor__values">
							<span>
								Vĩ độ{" "}
								<code>{draftCoords ? draftCoords[1].toFixed(6) : "—"}</code>
							</span>
							<span>
								Kinh độ{" "}
								<code>{draftCoords ? draftCoords[0].toFixed(6) : "—"}</code>
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
								id="campus-coord-locate-btn"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={handleUseCurrentLocation}
								disabled={locating || savingCoords}
								title="Dùng vị trí GPS hiện tại của thiết bị"
							>
								{locating ? (
									<Loader2
										size={13}
										className="animate-spin"
									/>
								) : (
									<LocateFixed size={13} />
								)}
								Vị trí của tôi
							</button>
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
								disabled={savingCoords || !isValidLngLat(draftCoords)}
							>
								{savingCoords ? (
									<Loader2
										size={13}
										className="animate-spin"
									/>
								) : (
									<Save size={13} />
								)}
								Lưu tọa độ
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
						onLoad={(e) => e?.target?.setLight?.(MAP_LIGHT)}
						onZoomEnd={(e) => setMapZoom(e.viewState.zoom)}
						onClick={handleMapClick}
						onMouseMove={(e) =>
							setHoverCursor(e?.features?.length > 0 ? "pointer" : "grab")
						}
						onMouseLeave={() => setHoverCursor("grab")}
						cursor={hoverCursor}
						interactiveLayerIds={INTERACTIVE_3D_LAYERS}
						light={MAP_LIGHT}
						mapStyle={OSM_STYLE}
						style={{ width: "100%", height: "100%" }}
						minZoom={1}
						maxZoom={22}
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
									"fill-extrusion-opacity": 0.16,
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
									"fill-extrusion-opacity": 0.2,
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
									"fill-extrusion-opacity": 0.18,
									"fill-extrusion-vertical-gradient": true,
								}}
							/>
						</Source>

						{/* Lớp Đế Sàn, Tường & Khối Phòng 3D Tầng Active */}
						<Source id="campus-3d-slabs-src" type="geojson" data={slabsSplit.active}>
							<Layer
								id="campus-3d-slabs-layer"
								type="fill-extrusion"
								paint={{
									"fill-extrusion-color": ["get", "color"],
									"fill-extrusion-base": ["get", "baseHeight"],
									"fill-extrusion-height": ["get", "extrudeHeight"],
									"fill-extrusion-opacity":
										activeBuilding3d === "ALL" ? 0.8 : 0.92,
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
									"fill-extrusion-opacity": 0.95,
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

						{/* 4. Lớp Tuyến Đường Dẫn Đường 3D Nổi Trên Sàn Tầng (Elevated fill-extrusion ribbons) */}
						{activeRoute3d?.geojson && (
							<>
								<Source
									id="campus-3d-route-ghost-src"
									type="geojson"
									data={routeRibbonsSplit.ghost}
								>
									<Layer
										id="campus-3d-route-ghost-layer"
										type="fill-extrusion"
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-opacity": 0.35,
										}}
									/>
								</Source>
								<Source
									id="campus-3d-route-glow-src"
									type="geojson"
									data={route3dLayers.glowGeoJSON}
								>
									<Layer
										id="campus-3d-route-glow-layer"
										type="fill-extrusion"
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-opacity": 0.42,
										}}
									/>
								</Source>
								<Source
									id="campus-3d-route-ribbon-src"
									type="geojson"
									data={routeRibbonsSplit.active}
								>
									<Layer
										id="campus-3d-route-layer"
										type="fill-extrusion"
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-opacity": 0.96,
										}}
									/>
								</Source>
								<Source
									id="campus-3d-route-stairs-src"
									type="geojson"
									data={route3dLayers.stairBeaconsGeoJSON}
								>
									<Layer
										id="campus-3d-route-stairs-ghost-layer"
										type="fill-extrusion"
										filter={["==", ["get", "isGhost"], true]}
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-opacity": 0.25,
										}}
									/>
									<Layer
										id="campus-3d-route-stairs-layer"
										type="fill-extrusion"
										filter={["!=", ["get", "isGhost"], true]}
										paint={{
											"fill-extrusion-color": ["get", "color"],
											"fill-extrusion-base": ["get", "baseHeight"],
											"fill-extrusion-height": ["get", "extrudeHeight"],
											"fill-extrusion-opacity": 0.9,
										}}
									/>
								</Source>
								{activeRouteEndpoints && (
									<>
										<Marker
											longitude={activeRouteEndpoints.start[0]}
											latitude={activeRouteEndpoints.start[1]}
											anchor="center"
										>
											<div
												className="campus-route-pedestrian-marker"
												title="Xuất phát"
											>
												<div className="campus-route-pedestrian-pulse" />
												<div className="campus-route-pedestrian-badge">
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
											longitude={activeRouteEndpoints.end[0]}
											latitude={activeRouteEndpoints.end[1]}
											anchor="bottom"
										>
											<div
												className="campus-route-dest-pin"
												title="Điểm đến"
											>
												<svg
													className="campus-route-dest-pin__svg"
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
												<div className="campus-route-dest-pin__shadow" />
											</div>
										</Marker>
									</>
								)}
							</>
						)}

						{/* 5. Lớp Nhãn Tên Khu Vực (Area Names Symbol Layer trên tầng đang chọn) */}
						<Source
							id="campus-3d-room-labels-src"
							type="geojson"
							data={roomLabelsGeoJSON}
						>
							<Layer
								id="campus-3d-room-labels-layer"
								type="symbol"
								minzoom={17.2}
								layout={{
									"text-field": ["get", "name"],
									"text-font": ["IBM Plex Sans", "Segoe UI", "sans-serif"],
									"text-size": [
										"interpolate",
										["linear"],
										["zoom"],
										17.2,
										10.5,
										19.5,
										13,
									],
									"text-anchor": "center",
									"text-max-width": 8,
									"text-padding": 3,
								}}
								paint={{
									"text-color": [
										"case",
										["get", "isSelected"],
										"#1e3a8a",
										"#0f172a",
									],
									"text-halo-color": "rgba(255, 255, 255, 0.94)",
									"text-halo-width": 1.8,
									"text-halo-blur": 0.4,
								}}
							/>
						</Source>

						{/* Nhãn Tòa Nhà (Overview & chuyển nhanh giữa các tòa trên bản đồ) */}
						{buildingLabelsGeoJSON.features.map((bFeat) => {
							const { id, building, name, floorCount, isFocused } =
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
										title={`Nhấn để xem chi tiết & chọn tầng ${name}`}
									>
										<Building2 size={12} />
										<span className="campus-bldg-pill__name">{name}</span>
										<span className="campus-bldg-pill__badge">
											{floorCount} tầng
										</span>
									</button>
								</Marker>
							);
						})}

						{/* Ghim nháp khi đang đặt tọa độ (kéo thả được) */}
						{isEditingCoords && isValidLngLat(draftCoords) && (
							<Marker
								longitude={draftCoords[0]}
								latitude={draftCoords[1]}
								anchor="bottom"
								draggable
								onDragEnd={(e) => {
									if (e?.lngLat) {
										setDraftCoords([e.lngLat.lng, e.lngLat.lat]);
									}
								}}
								style={{ zIndex: 50 }}
							>
								<div
									className="campus-draft-pin"
									title="Kéo để điều chỉnh vị trí"
								>
									<svg
										width="34"
										height="44"
										viewBox="0 0 24 32"
										aria-hidden="true"
									>
										<path
											d="M12 0C5.4 0 0 5.3 0 11.9 0 20.8 12 32 12 32s12-11.2 12-20.1C24 5.3 18.6 0 12 0z"
											fill="#ea4335"
											stroke="#b31412"
											strokeWidth="1"
										/>
										<circle
											cx="12"
											cy="11.5"
											r="4.2"
											fill="#7a0c0c"
										/>
									</svg>
								</div>
							</Marker>
						)}

						{/* Marker tọa độ nháp cho tạo khu vực mới (Map-First Creation) */}
						{isCreatingArea && createDraftCoords && (
							<Marker
								longitude={createDraftCoords[0]}
								latitude={createDraftCoords[1]}
								anchor="bottom"
								draggable
								onDragEnd={(e) =>
									setCreateDraftCoords([
										Number(e.lngLat.lng.toFixed(6)),
										Number(e.lngLat.lat.toFixed(6)),
									])
								}
								style={{ zIndex: 100 }}
							>
								<div
									className="campus-pin-marker campus-pin-marker--draft"
									title="Vị trí vùng mới (Kéo để chỉnh vị trí)"
								>
									<span className="campus-pin-marker__label">Vùng mới</span>
									<div className="campus-pin-marker__icon-wrap">
										<svg
											className="campus-pin-marker__svg"
											width="26"
											height="34"
											viewBox="0 0 24 32"
											aria-hidden="true"
										>
											<path
												d="M12 0C5.373 0 0 5.373 0 12c0 8.5 12 20 12 20s12-11.5 12-20c0-6.627-5.373-12-12-12z"
												fill="#f37021"
												stroke="#ffffff"
												strokeWidth="1.8"
											/>
											<circle
												cx="12"
												cy="11"
												r="4"
												fill="#ffffff"
											/>
										</svg>
									</div>
								</div>
							</Marker>
						)}

						{/* Google-Style Real Coordinate Markers with Auto-Shift for Overlaps */}
						{displayedPositionedAreas.map((item) => {
							const isSelected = selectedAreaId === item.id;

							return (
								<Marker
									key={item.id}
									longitude={item.baseCoords[0]}
									latitude={item.baseCoords[1]}
									offset={item.pixelOffset}
									anchor="bottom"
									onClick={(e) => {
										stopMapEvent(e);
										handleSelectAreaItem(item.id, item.baseCoords);
									}}
									style={{ zIndex: isSelected ? 50 : 10 + (item.idx || 0) }}
								>
									<div
										className={`campus-pin-marker ${isSelected ? "campus-pin-marker--selected" : ""}`}
										title={`${item.name} (${[item.building, item.floor].filter(Boolean).join(" · ") || ""})${item.isShifted ? " · Đã tự dịch vị trí do trùng toạ độ" : ""}`}
										aria-label={item.name}
									>
										<span className="campus-pin-marker__label">
											{item.name}
										</span>
										<div className="campus-pin-marker__icon-wrap">
											<svg
												className="campus-pin-marker__svg"
												width="24"
												height="32"
												viewBox="0 0 24 32"
												aria-hidden="true"
											>
												<path
													d="M12 0C5.373 0 0 5.373 0 12c0 8.5 12 20 12 20s12-11.5 12-20c0-6.627-5.373-12-12-12z"
													fill={item.color}
													stroke="#ffffff"
													strokeWidth="1.5"
												/>
												<circle
													cx="12"
													cy="11"
													r="4"
													fill="#ffffff"
												/>
											</svg>
										</div>
									</div>
								</Marker>
							);
						})}
					</MapGL>
				</div>

				{/* Floating Route Summary Pill */}
				{activeRoute3d && (
					<div className="campus-3d-slicer-dock">
						<div className="campus-3d-route-pill">
							<span>
								Lộ trình A*: <strong>{activeRoute3d.totalDistanceMeters}m</strong> (~
								{activeRoute3d.estimatedSeconds}s)
							</span>
							<button
								type="button"
								className="campus-3d-route-clear"
								onClick={() => setActiveRoute3d(null)}
								title="Đóng lộ trình"
							>
								<X size={12} />
							</button>
						</div>
					</div>
				)}

				{/* Floating Colour Legend */}
				<div className="campus-map-legend">
					{isAdmin ? (
						<>
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
						</>
					) : (
						<>
							<div className="campus-map-legend__item">
								<span
									className="campus-map-legend__dot"
									style={{ background: "#475569" }}
								/>
								<span>Khối phòng vận hành (3D Vector)</span>
							</div>
							<div className="campus-map-legend__item">
								<span
									className="campus-map-legend__dot"
									style={{ background: "#2563eb" }}
								/>
								<span>Phòng đang chọn</span>
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
								<span className="campus-rail-title">Thêm vùng mới</span>
								<button
									type="button"
									className="campus-rail-close-btn"
									onClick={() => {
										setCreateDraftCoords(null);
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
											Tọa độ: {createDraftCoords[1]}, {createDraftCoords[0]}
										</span>
									) : (
										<span>Chưa chọn tọa độ (nhấp lên bản đồ)</span>
									)}
								</div>

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

								<div className="area-form-group">
									<label className="area-form-label">Tòa nhà *</label>
									<Select
										className="ui-select-group--filter"
										value={createFormData.building}
										onChange={(e) => handleCreateBuildingChange(e.target.value)}
										options={buildingsList.map((b) => ({
											value: b.name,
											label: b.name,
										}))}
									/>
								</div>

								<div className="area-form-group">
									<label className="area-form-label">Tầng *</label>
									<Select
										className="ui-select-group--filter"
										value={createFormData.floor}
										onChange={(e) => handleCreateFloorChange(e.target.value)}
										options={availableCreateFloors.map((fl) => ({
											value: fl.name,
											label: fl.name,
										}))}
									/>
								</div>
							</div>

							<div className="campus-rail-footer">
								<button
									type="button"
									className={`area-btn-modal area-btn-modal--submit ${
										isCreateFormIncomplete ? "area-btn-modal--submit-blurred" : ""
									}`}
									onClick={handleCreateAreaSubmit}
									disabled={creatingAreaLoading}
									title={
										!createDraftCoords
											? "Vui lòng chọn tọa độ trên bản đồ"
											: !createFormData.name.trim()
												? "Vui lòng nhập tên khu vực"
												: "Xác nhận tạo khu vực mới"
									}
								>
									{creatingAreaLoading ? (
										<>
											<Loader2 size={14} className="animate-spin" />
											<span>Đang tạo...</span>
										</>
									) : (
										"Xác nhận tạo"
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
										Khu vực khuôn viên ({areas.length})
									</span>
									{unpositionedCount > 0 && (
										<span
											className="campus-rail-unpositioned-note"
											title="Các khu vực chưa được cập nhật tọa độ GPS trong CSDL"
										>
											{unpositionedCount} chưa định vị
										</span>
									)}
								</div>

								<div className="campus-rail-list">
									{areas.length === 0 ? (
										<div className="campus-detail-empty">
											Chưa có khu vực nào trong hệ thống
										</div>
									) : (
										areas.map((area) => {
											const isSelected = selectedAreaId === area.id;
											const levelKey =
												typeof area.areaLevel === "string"
													? area.areaLevel.toLowerCase()
													: (area.level?.code || "PUBLIC").toLowerCase();
											const validCoords = getValidCoordinates(area);
											const pos = positionedAreas.find((p) => p.id === area.id);
											const targetCoords = pos ? pos.baseCoords : validCoords;

											return (
												<div
													key={area.id}
													className={`campus-rail-item ${isSelected ? "campus-rail-item--selected" : ""}`}
													onClick={() =>
														handleSelectAreaItem(area.id, targetCoords)
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
														{!validCoords ?? (
															<span className="campus-tag-unpositioned">
																Chưa có GPS
															</span>
															/* Do not add level */
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
										<p>Chọn một khu vực trên bản đồ để xem chi tiết.</p>
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
													title="Cấp tối thiểu để được vào thẳng (cấp cố định theo loại khu vực)"
												>
													{
														getAccessLevelConfig(selectedArea.areaAccessLevel)
															.label
													}
												</span>
											</div>
											<div className="campus-detail-meta-row">
												<span className="campus-detail-meta-label">
													Tọa độ GPS
												</span>
												{selectedArea.centerLatitude != null &&
												selectedArea.centerLongitude != null ? (
													<span className="campus-detail-meta-val campus-detail-meta-val--coords">
														{Number(selectedArea.centerLatitude).toFixed(5)},{" "}
														{Number(selectedArea.centerLongitude).toFixed(5)}
													</span>
												) : (
													<span className="campus-detail-meta-val text-warning">
														Chưa có tọa độ
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
												{getValidCoordinates(selectedArea)
													? "Chỉnh vị trí trên bản đồ"
													: "Đặt vị trí trên bản đồ"}
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
