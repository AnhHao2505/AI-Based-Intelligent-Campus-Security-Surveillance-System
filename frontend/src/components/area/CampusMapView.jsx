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
import {
	Compass,
	PanelRightClose,
	PanelRightOpen,
	Building2,
	Layers,
	X,
	MapPin,
	AlertCircle,
	Search,
	Crosshair,
	Save,
	Loader2,
	LocateFixed,
	Globe,
	Shield,
	UserCheck,
	Battery,
	Radio,
	PenTool,
	RotateCcw,
	BellRing,
	User,
	CheckCircle2,
	Plus,
} from "lucide-react";
import {
	getLevelConfig,
	getAccessLevelConfig,
	getErrorMessage,
	AREA_LEVEL_CONFIG,
} from "../../utils/areaHelpers";
import { updateArea } from "../../services/areaService";
import {
	getActiveGuardLocations,
	getCampusGeofence,
	updateCampusGeofence,
} from "../../services/guardLocationService";
import { triggerTestAlert } from "../../services/incidentService";
import "../../styles/CampusMapView.css";

// FPT University HCMC Campus default center (Saigon Hi-Tech Park, District 9)
const CAMPUS_CENTER = {
	longitude: 106.80988,
	latitude: 10.84113,
	zoom: 16.4,
	pitch: 30,
	bearing: -10,
};

// Danh sách vị trí bảo vệ thử nghiệm mặc định (luôn hiển thị trên bản đồ khuôn viên)
const DEFAULT_TEST_GUARDS = [
	{
		guardId: "sec-002-demo",
		guardName: "Bảo vệ Demo",
		guardEmail: "guard.demo@fpt.edu.vn",
		userCode: "SEC-002",
		teamName: "Đội Tuần Tra Cơ Động",
		latitude: 10.84113,
		longitude: 106.80988,
		accuracy: 5.0,
		batteryLevel: 92.0,
		isInsideGeofence: true,
		isFresh: true,
		updatedAt: new Date().toISOString(),
	},
	{
		guardId: "sec-003-ngoai",
		guardName: "Bảo vệ Ngoài Trường",
		guardEmail: "guard.ngoai@fpt.edu.vn",
		userCode: "SEC-003",
		teamName: "Đội Bảo Vệ Ca 1",
		latitude: 10.84190,
		longitude: 106.80800,
		accuracy: 8.0,
		batteryLevel: 75.0,
		isInsideGeofence: false,
		isFresh: true,
		updatedAt: new Date().toISOString(),
	},
];

function mergeGuardsWithDefaults(serverGuards) {
	const map = new Map();
	DEFAULT_TEST_GUARDS.forEach((g) => {
		map.set(g.userCode || g.guardEmail || g.guardId, { ...g });
	});
	if (Array.isArray(serverGuards)) {
		serverGuards.forEach((sg) => {
			if (sg && sg.latitude != null && sg.longitude != null) {
				const key = sg.userCode || sg.guardEmail || sg.guardId;
				map.set(key, { ...map.get(key), ...sg });
			}
		});
	}
	return Array.from(map.values());
}

// OpenStreetMap Standard Raster Style with multi-subdomain CDN for speed & reliability
const OSM_STYLE = {
	version: 8,
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
			id: "osm-tiles-layer",
			type: "raster",
			source: "osm-tiles",
			minzoom: 0,
			maxzoom: 19,
		},
	],
};

const LABEL_MIN_ZOOM = 18.2;
const SEARCH_RESULT_LIMIT = 8;

/** Chuẩn hoá chuỗi để tìm kiếm không dấu, không phân biệt hoa thường. */
function normalizeText(value) {
	return String(value ?? "")
		.normalize("NFD")
		.replace(/[\u0300-\u036f]/g, "")
		.replace(/đ/g, "d")
		.replace(/Đ/g, "D")
		.toLowerCase()
		.trim();
}

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
	onAreaUpdated,
}) {
	const mapRef = useRef(null);
	const [railCollapsed, setRailCollapsed] = useState(false);
	const [mapZoom, setMapZoom] = useState(CAMPUS_CENTER.zoom);
	const compactLabels = mapZoom < LABEL_MIN_ZOOM;

	// Tìm kiếm khu vực & địa điểm toàn cầu
	const [searchTerm, setSearchTerm] = useState("");
	const [searchOpen, setSearchOpen] = useState(false);
	const [searchActiveIdx, setSearchActiveIdx] = useState(0);
	const [globalResults, setGlobalResults] = useState([]);
	const [isSearchingGlobal, setIsSearchingGlobal] = useState(false);
	const [searchedPlace, setSearchedPlace] = useState(null);

	// Chế độ đặt tọa độ trực tiếp trên bản đồ (chỉ ADMIN)
	const [editingAreaId, setEditingAreaId] = useState(null);
	const [draftCoords, setDraftCoords] = useState(null); // [lng, lat]
	const [savingCoords, setSavingCoords] = useState(false);
	const [locating, setLocating] = useState(false);
	const [coordError, setCoordError] = useState(null);
	const [coordSuccess, setCoordSuccess] = useState(null);
	const isEditingCoords = Boolean(editingAreaId);

	// Vị trí thời gian thực của các bảo vệ và Geofence khuôn viên
	const [guards, setGuards] = useState(DEFAULT_TEST_GUARDS);
	const [selectedGuard, setSelectedGuard] = useState(null);
	const [campusGeofence, setCampusGeofence] = useState(null);
	const [showGeofence, setShowGeofence] = useState(true);

	// Chế độ vẽ Geofence khuôn viên (chỉ ADMIN)
	const [isDrawingGeofence, setIsDrawingGeofence] = useState(false);
	const [draftGeofencePolygon, setDraftGeofencePolygon] = useState([]);
	const [isSavingGeofence, setIsSavingGeofence] = useState(false);

	// Modal kiểm thử bắn cảnh báo sự cố tới các bảo vệ
	const [showTestAlertModal, setShowTestAlertModal] = useState(false);
	const [isTriggeringTestAlert, setIsTriggeringTestAlert] = useState(false);
	const [testAlertResult, setTestAlertResult] = useState(null);

	// Tải thông tin Geofence khuôn viên và cập nhật danh sách bảo vệ
	useEffect(() => {
		let isMounted = true;
		getCampusGeofence()
			.then((data) => {
				if (isMounted && data) setCampusGeofence(data);
			})
			.catch((err) => console.warn("Lỗi tải thông tin Geofence:", err));

		const loadGuards = () => {
			getActiveGuardLocations()
				.then((data) => {
					if (isMounted) setGuards(mergeGuardsWithDefaults(data));
				})
				.catch((err) => {
					console.warn("Lỗi tải vị trí bảo vệ:", err);
					if (isMounted) setGuards(DEFAULT_TEST_GUARDS);
				});
		};

		loadGuards();
		const interval = setInterval(loadGuards, 15000);
		return () => {
			isMounted = false;
			clearInterval(interval);
		};
	}, []);

	// Đa giác GeoJSON cho Geofence khuôn viên đã lưu
	const geofenceGeoJson = useMemo(() => {
		if (!campusGeofence?.polygon || campusGeofence.polygon.length < 3) return null;
		const coords = campusGeofence.polygon.map((p) => [p.longitude, p.latitude]);
		coords.push([campusGeofence.polygon[0].longitude, campusGeofence.polygon[0].latitude]);
		return {
			type: "Feature",
			geometry: {
				type: "Polygon",
				coordinates: [coords],
			},
			properties: {
				name: campusGeofence.campusName,
			},
		};
	}, [campusGeofence]);

	// Đa giác/Đường GeoJSON cho Geofence đang vẽ dở (nháp)
	const draftGeofenceGeoJson = useMemo(() => {
		if (!isDrawingGeofence || draftGeofencePolygon.length < 2) return null;
		const coords = draftGeofencePolygon.map((p) => [p.longitude, p.latitude]);
		if (draftGeofencePolygon.length >= 3) {
			coords.push([draftGeofencePolygon[0].longitude, draftGeofencePolygon[0].latitude]);
			return {
				type: "Feature",
				geometry: {
					type: "Polygon",
					coordinates: [coords],
				},
			};
		}
		return {
			type: "Feature",
			geometry: {
				type: "LineString",
				coordinates: coords,
			},
		};
	}, [isDrawingGeofence, draftGeofencePolygon]);

	// Khu vực đang chọn
	const selectedArea = useMemo(() => {
		return areas.find((a) => a.id === selectedAreaId) || null;
	}, [areas, selectedAreaId]);

	const editingArea = useMemo(() => {
		return areas.find((a) => a.id === editingAreaId) || null;
	}, [areas, editingAreaId]);

	// Kết quả tìm kiếm theo tên / tòa nhà / tầng (không dấu)
	const searchMatches = useMemo(() => {
		const term = normalizeText(searchTerm);
		if (!term) return areas;
		return areas.filter((a) =>
			[a.name, a.building, a.floor].some((v) =>
				normalizeText(v).includes(term),
			),
		);
	}, [areas, searchTerm]);

	const localSearchResults = useMemo(
		() =>
			searchTerm.trim() ? searchMatches.slice(0, SEARCH_RESULT_LIMIT) : [],
		[searchMatches, searchTerm],
	);

	// Tìm kiếm địa điểm toàn cầu (Photon / OpenStreetMap Geocoding)
	useEffect(() => {
		const q = searchTerm.trim();
		if (q.length < 2) {
			setGlobalResults([]);
			setIsSearchingGlobal(false);
			return;
		}

		const abortCtrl = new AbortController();
		setIsSearchingGlobal(true);

		const timer = setTimeout(async () => {
			try {
				const res = await fetch(
					`https://photon.komoot.io/api/?q=${encodeURIComponent(q)}&limit=5&lat=10.84113&lon=106.80988`,
					{ signal: abortCtrl.signal },
				);
				if (!res.ok) throw new Error("Photon query failed");
				const data = await res.json();
				const places = (data.features || []).map((f, idx) => {
					const p = f.properties || {};
					const name = p.name || p.street || p.city || q;
					const details = [p.street, p.district, p.city, p.state, p.country]
						.filter(Boolean)
						.join(", ");
					return {
						id: `global-${idx}-${f.geometry.coordinates.join(",")}`,
						name,
						fullAddress: details || name,
						coords: f.geometry.coordinates, // [lng, lat]
						isGlobal: true,
					};
				});
				setGlobalResults(places);
			} catch (err) {
				if (err.name !== "AbortError") {
					try {
						const nomRes = await fetch(
							`https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(q)}&limit=5`,
							{ signal: abortCtrl.signal },
						);
						const nomData = await nomRes.json();
						const places = (nomData || []).map((item, idx) => ({
							id: `nom-${idx}-${item.place_id}`,
							name: item.display_name.split(",")[0],
							fullAddress: item.display_name,
							coords: [parseFloat(item.lon), parseFloat(item.lat)],
							isGlobal: true,
						}));
						setGlobalResults(places);
					} catch {
						setGlobalResults([]);
					}
				}
			} finally {
				setIsSearchingGlobal(false);
			}
		}, 350);

		return () => {
			clearTimeout(timer);
			abortCtrl.abort();
		};
	}, [searchTerm]);

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

	// Về toàn cảnh khuôn viên
	const handleResetCampusView = useCallback(() => {
		if (mapRef.current) {
			mapRef.current.flyTo({
				center: [CAMPUS_CENTER.longitude, CAMPUS_CENTER.latitude],
				zoom: CAMPUS_CENTER.zoom,
				pitch: CAMPUS_CENTER.pitch,
				bearing: CAMPUS_CENTER.bearing,
				duration: 1000,
			});
		}
	}, []);

	// ===== Tìm kiếm =====
	const handlePickSearchResult = useCallback(
		(area) => {
			if (!area) return;
			const pos = positionedAreas.find((p) => p.id === area.id);
			const coords = pos ? pos.baseCoords : getValidCoordinates(area);
			if (coords) handleFlyToCoords(coords, 18);
			if (onSelectArea && area.id !== selectedAreaId) onSelectArea(area.id);
			setSearchOpen(false);
		},
		[handleFlyToCoords, onSelectArea, selectedAreaId, positionedAreas],
	);

	const handlePickGlobalResult = useCallback(
		(place) => {
			if (!place || !place.coords) return;
			handleFlyToCoords(place.coords, 16);
			setSearchedPlace(place);
			setSearchOpen(false);
			if (isEditingCoords) {
				setDraftCoords(place.coords);
			}
		},
		[handleFlyToCoords, isEditingCoords],
	);

	const allCombinedResults = useMemo(
		() => [...localSearchResults, ...globalResults],
		[localSearchResults, globalResults],
	);

	const handleSearchKeyDown = (e) => {
		if (e.key === "Escape") {
			setSearchTerm("");
			setSearchOpen(false);
			return;
		}
		if (allCombinedResults.length === 0) return;
		if (e.key === "ArrowDown") {
			e.preventDefault();
			setSearchActiveIdx((i) => (i + 1) % allCombinedResults.length);
		} else if (e.key === "ArrowUp") {
			e.preventDefault();
			setSearchActiveIdx(
				(i) => (i - 1 + allCombinedResults.length) % allCombinedResults.length,
			);
		} else if (e.key === "Enter") {
			e.preventDefault();
			const picked =
				allCombinedResults[
					Math.min(searchActiveIdx, allCombinedResults.length - 1)
				];
			if (picked) {
				if (picked.isGlobal) {
					handlePickGlobalResult(picked);
				} else {
					handlePickSearchResult(picked);
				}
			}
		}
	};

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

	const handleStartDrawingGeofence = useCallback(() => {
		if (campusGeofence?.polygon && campusGeofence.polygon.length > 0) {
			setDraftGeofencePolygon(
				campusGeofence.polygon.map((p) => ({
					latitude: p.latitude,
					longitude: p.longitude,
				})),
			);
		} else {
			setDraftGeofencePolygon([]);
		}
		setIsDrawingGeofence(true);
		setEditingAreaId(null);
		setDraftCoords(null);
		setCoordError(null);
	}, [campusGeofence]);

	const handleCancelDrawingGeofence = useCallback(() => {
		setIsDrawingGeofence(false);
		setDraftGeofencePolygon([]);
	}, []);

	const handleStartFreshGeofence = useCallback(() => {
		setDraftGeofencePolygon([]);
	}, []);

	const handleRemoveLastPoint = useCallback(() => {
		setDraftGeofencePolygon((prev) => prev.slice(0, -1));
	}, []);

	const handleResetDefaultGeofence = useCallback(() => {
		if (campusGeofence?.polygon) {
			setDraftGeofencePolygon(
				campusGeofence.polygon.map((p) => ({
					latitude: p.latitude,
					longitude: p.longitude,
				})),
			);
		} else {
			setDraftGeofencePolygon([]);
		}
	}, [campusGeofence]);

	const handleMoveGeofenceVertex = useCallback((idx, lng, lat) => {
		setDraftGeofencePolygon((prev) => {
			const next = [...prev];
			next[idx] = {
				latitude: Number(lat.toFixed(7)),
				longitude: Number(lng.toFixed(7)),
			};
			return next;
		});
	}, []);

	const handleSaveGeofence = useCallback(async () => {
		if (draftGeofencePolygon.length < 3) {
			alert("Geofence cần tối thiểu 3 điểm để tạo đa giác ranh giới khép kín.");
			return;
		}
		setIsSavingGeofence(true);
		try {
			const updated = await updateCampusGeofence({
				campusName: campusGeofence?.campusName || "FPT University HCMC Campus",
				description:
					campusGeofence?.description ||
					"Khuôn viên Đại học FPT TP.HCM (Khu Công nghệ cao, TP. Thủ Đức)",
				polygon: draftGeofencePolygon,
			});
			setCampusGeofence(updated);
			setIsDrawingGeofence(false);
			setDraftGeofencePolygon([]);
			setCoordSuccess("Đã lưu ranh giới Geofence khuôn viên thành công!");
			const guardsData = await getActiveGuardLocations();
			setGuards(mergeGuardsWithDefaults(guardsData));
		} catch (err) {
			alert(getErrorMessage(err));
		} finally {
			setIsSavingGeofence(false);
		}
	}, [draftGeofencePolygon, campusGeofence]);

	const handleTriggerTestAlert = useCallback(async () => {
		setIsTriggeringTestAlert(true);
		setTestAlertResult(null);
		try {
			const res = await triggerTestAlert({
				cameraCode: "CAM-01",
				eventType: "SECURITY_ALERT",
				details: "Sự cố thử nghiệm kiểm tra Geofence điều phối bảo vệ",
			});
			setTestAlertResult({
				success: true,
				message:
					"Đã kích hoạt sự cố thử nghiệm thành công! Cảnh báo đã được phát qua WebSocket tới các bảo vệ trong khuôn viên.",
				data: res,
			});
			const updatedGuards = await getActiveGuardLocations();
			setGuards(mergeGuardsWithDefaults(updatedGuards));
		} catch (err) {
			setTestAlertResult({
				success: false,
				message: getErrorMessage(err),
			});
		} finally {
			setIsTriggeringTestAlert(false);
		}
	}, []);

	const handleMapClick = useCallback(
		(e) => {
			if (isDrawingGeofence && e?.lngLat) {
				setDraftGeofencePolygon((prev) => [
					...prev,
					{
						latitude: Number(e.lngLat.lat.toFixed(7)),
						longitude: Number(e.lngLat.lng.toFixed(7)),
					},
				]);
				return;
			}
			if (isEditingCoords && e?.lngLat) {
				setDraftCoords([e.lngLat.lng, e.lngLat.lat]);
				setCoordError(null);
			}
		},
		[isDrawingGeofence, isEditingCoords],
	);

	return (
		<div
			className={`campus-map-layout ${railCollapsed ? "campus-map-layout--expanded" : ""}`}
		>
			{/* LEFT: MAIN MAPLIBRE CANVAS */}
			<div className="campus-map-card">
				{/* Floating Top Controls */}
				<div className="campus-map-floating-bar">
					{/* Ô tìm kiếm khu vực */}
					<div className="campus-map-search">
						<Search
							size={14}
							className="campus-map-search__icon"
						/>
						<input
							id="campus-map-search-input"
							type="text"
							className="campus-map-search__input"
							placeholder="Tìm khu vực, tòa nhà, tầng..."
							value={searchTerm}
							onChange={(e) => {
								setSearchTerm(e.target.value);
								setSearchOpen(true);
								setSearchActiveIdx(0);
							}}
							onFocus={() => setSearchOpen(true)}
							onBlur={() => setTimeout(() => setSearchOpen(false), 150)}
							onKeyDown={handleSearchKeyDown}
							autoComplete="off"
							aria-label="Tìm kiếm khu vực trên bản đồ"
						/>
						{searchTerm && (
							<button
								type="button"
								className="campus-map-search__clear"
								onClick={() => {
									setSearchTerm("");
									setSearchOpen(false);
								}}
								title="Xoá tìm kiếm"
							>
								<X size={12} />
							</button>
						)}

						{searchOpen && searchTerm.trim() && (
							<div className="campus-map-search__results">
								{/* Khu vực trong khuôn viên */}
								{localSearchResults.length > 0 && (
									<div className="campus-map-search__section">
										<div className="campus-map-search__section-title">
											<Building2 size={12} />
											<span>Khu vực khuôn viên</span>
										</div>
										{localSearchResults.map((area, idx) => {
											const hasCoords = Boolean(getValidCoordinates(area));
											return (
												<button
													type="button"
													key={area.id}
													className={`campus-map-search__item ${idx === searchActiveIdx ? "campus-map-search__item--active" : ""}`}
													onMouseDown={(e) => e.preventDefault()}
													onMouseEnter={() => setSearchActiveIdx(idx)}
													onClick={() => handlePickSearchResult(area)}
												>
													<MapPin
														size={13}
														className={
															hasCoords
																? "campus-map-search__pin"
																: "campus-map-search__pin campus-map-search__pin--muted"
														}
													/>
													<span className="campus-map-search__text">
														<span className="campus-map-search__name">
															{area.name}
														</span>
														<span className="campus-map-search__sub">
															{[area.building, area.floor]
																.filter(Boolean)
																.join(" · ") || "—"}
															{!hasCoords && " · Chưa có GPS"}
														</span>
													</span>
												</button>
											);
										})}
									</div>
								)}

								{/* Địa điểm toàn cầu / OpenStreetMap */}
								{(globalResults.length > 0 || isSearchingGlobal) && (
									<div className="campus-map-search__section">
										<div className="campus-map-search__section-title">
											<Globe size={12} />
											<span>Địa điểm thế giới (OpenStreetMap)</span>
											{isSearchingGlobal && (
												<Loader2
													size={11}
													className="animate-spin"
													style={{ marginLeft: "auto" }}
												/>
											)}
										</div>
										{globalResults.map((place, idx) => {
											const globalIdx = localSearchResults.length + idx;
											return (
												<button
													type="button"
													key={place.id}
													className={`campus-map-search__item ${globalIdx === searchActiveIdx ? "campus-map-search__item--active" : ""}`}
													onMouseDown={(e) => e.preventDefault()}
													onMouseEnter={() => setSearchActiveIdx(globalIdx)}
													onClick={() => handlePickGlobalResult(place)}
												>
													<Globe
														size={13}
														className="campus-map-search__pin text-primary"
													/>
													<span className="campus-map-search__text">
														<span className="campus-map-search__name">
															{place.name}
														</span>
														<span className="campus-map-search__sub">
															{place.fullAddress}
														</span>
													</span>
												</button>
											);
										})}
									</div>
								)}

								{localSearchResults.length === 0 &&
									globalResults.length === 0 &&
									!isSearchingGlobal && (
										<div className="campus-map-search__empty">
											Không tìm thấy khu vực hoặc địa điểm phù hợp
										</div>
									)}
							</div>
						)}
					</div>

					<button
						type="button"
						className="campus-map-btn-icon"
						onClick={handleResetCampusView}
						title="Về toàn cảnh khuôn viên"
					>
						<Compass size={14} />
					</button>

					{isAdmin && (
						<button
							type="button"
							className={`campus-map-btn-icon ${isDrawingGeofence ? "active" : ""}`}
							onClick={
								isDrawingGeofence
									? handleCancelDrawingGeofence
									: handleStartDrawingGeofence
							}
							title={
								isDrawingGeofence
									? "Hủy vẽ Geofence"
									: "Vẽ / Chỉnh sửa Geofence khuôn viên"
							}
						>
							<PenTool size={14} />
						</button>
					)}

					<button
						type="button"
						className="campus-test-alert-btn"
						onClick={() => setShowTestAlertModal(true)}
						title="Kiểm thử cảnh báo sự cố tới các bảo vệ theo Geofence"
					>
						<BellRing size={13} />
						<span>Test cảnh báo</span>
					</button>

					<button
						type="button"
						className="campus-map-btn-icon"
						onClick={() => setRailCollapsed(!railCollapsed)}
						title={railCollapsed ? "Mở danh sách khu vực" : "Thu gọn danh sách"}
					>
						{railCollapsed ? (
							<PanelRightOpen size={14} />
						) : (
							<PanelRightClose size={14} />
						)}
					</button>
				</div>

				{/* Thanh công cụ vẽ Geofence khuôn viên (ADMIN) */}
				{isDrawingGeofence && (
					<div className="campus-geofence-editor">
						<div className="campus-geofence-editor__info">
							<PenTool size={15} />
							<span>
								Vẽ Geofence khuôn viên:{" "}
								<strong>
									{draftGeofencePolygon.length === 0
										? "0 điểm (Nhấp trên bản đồ để bắt đầu vẽ)"
										: `${draftGeofencePolygon.length} điểm`}
								</strong>
								{draftGeofencePolygon.length > 0 &&
									draftGeofencePolygon.length < 3 &&
									" (Cần tối thiểu 3 điểm)"}
							</span>
						</div>
						<div className="campus-geofence-editor__actions">
							<button
								type="button"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={handleStartFreshGeofence}
								disabled={isSavingGeofence}
								title="Xóa toàn bộ các điểm để vẽ đa giác mới từ đầu"
							>
								<Plus size={13} />
								Vẽ mới
							</button>
							<button
								type="button"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={handleRemoveLastPoint}
								disabled={draftGeofencePolygon.length === 0 || isSavingGeofence}
								title="Xóa điểm vừa thêm"
							>
								Xóa điểm cuối
							</button>
							<button
								type="button"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={handleResetDefaultGeofence}
								disabled={isSavingGeofence}
								title="Khôi phục lại đa giác ban đầu"
							>
								<RotateCcw size={13} />
								Khôi phục
							</button>
							<button
								type="button"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={handleCancelDrawingGeofence}
								disabled={isSavingGeofence}
							>
								Hủy
							</button>
							<button
								type="button"
								className="campus-coord-btn campus-coord-btn--primary"
								onClick={handleSaveGeofence}
								disabled={draftGeofencePolygon.length < 3 || isSavingGeofence}
							>
								{isSavingGeofence ? (
									<Loader2 size={13} className="animate-spin" />
								) : (
									<Save size={13} />
								)}
								Lưu Geofence
							</button>
						</div>
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
								Hủy
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
						onZoomEnd={(e) => setMapZoom(e.viewState.zoom)}
						onClick={handleMapClick}
						mapStyle={OSM_STYLE}
						style={{ width: "100%", height: "100%" }}
						minZoom={1}
						maxZoom={20}
					>
						{/* Native Map Controls */}
						<NavigationControl
							position="top-right"
							showCompass
							showZoom
						/>
						<FullscreenControl position="top-right" />

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

						{/* Ghim địa điểm toàn cầu vừa tìm kiếm */}
						{searchedPlace && (
							<Marker
								longitude={searchedPlace.coords[0]}
								latitude={searchedPlace.coords[1]}
								anchor="bottom"
								style={{ zIndex: 30 }}
							>
								<div
									className="campus-pin-marker campus-pin-marker--global"
									title={`${searchedPlace.name} - ${searchedPlace.fullAddress}`}
								>
									<span className="campus-pin-marker__label">
										{searchedPlace.name}
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
												fill="#2563eb"
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
						)}

						{/* Đa giác Geofence nháp đang được vẽ */}
						{isDrawingGeofence && draftGeofenceGeoJson && (
							<Source id="draft-geofence" type="geojson" data={draftGeofenceGeoJson}>
								{draftGeofenceGeoJson.geometry.type === "Polygon" && (
									<Layer
										id="draft-geofence-fill"
										type="fill"
										paint={{
											"fill-color": "#3b82f6",
											"fill-opacity": 0.18,
										}}
									/>
								)}
								<Layer
									id="draft-geofence-line"
									type="line"
									paint={{
										"line-color": "#2563eb",
										"line-width": 2.5,
										"line-dasharray": [2, 1],
									}}
								/>
							</Source>
						)}

						{/* Điểm kéo thả (Vertex Handles) của Geofence khi Admin đang vẽ */}
						{isDrawingGeofence &&
							draftGeofencePolygon.map((pt, idx) => (
								<Marker
									key={`draft-vertex-${idx}`}
									longitude={pt.longitude}
									latitude={pt.latitude}
									anchor="center"
									draggable
									onDragEnd={(e) => {
										if (e?.lngLat) {
											handleMoveGeofenceVertex(idx, e.lngLat.lng, e.lngLat.lat);
										}
									}}
									style={{ zIndex: 50 }}
								>
									<div
										className="campus-geofence-handle"
										title={`Điểm ${idx + 1}: [${pt.longitude.toFixed(5)}, ${pt.latitude.toFixed(5)}] (Kéo để chỉnh sửa)`}
									>
										{idx + 1}
									</div>
								</Marker>
							))}

						{/* Campus Geofence Layer (Đa giác ranh giới duy nhất toàn khuôn viên trường) */}
						{!isDrawingGeofence && showGeofence && geofenceGeoJson && (
							<Source id="campus-geofence" type="geojson" data={geofenceGeoJson}>
								<Layer
									id="campus-geofence-fill"
									type="fill"
									paint={{
										"fill-color": "#3b82f6",
										"fill-opacity": 0.08,
									}}
								/>
								<Layer
									id="campus-geofence-line"
									type="line"
									paint={{
										"line-color": "#2563eb",
										"line-width": 2,
										"line-dasharray": [2, 2],
									}}
								/>
							</Source>
						)}

						{/* Ghim tròn chuẩn cho nhân viên bảo vệ (Round Guard Pins) */}
						{guards
							.filter((g) => g.latitude != null && g.longitude != null)
							.map((guard) => {
								const isSelected = selectedGuard?.guardId === guard.guardId;
								const isInside = guard.isInsideGeofence;
								return (
									<Marker
										key={guard.guardId}
										longitude={guard.longitude}
										latitude={guard.latitude}
										anchor="center"
										onClick={(e) => {
											stopMapEvent(e);
											setSelectedGuard(guard);
											onSelectArea?.(null);
										}}
										style={{ zIndex: isSelected ? 60 : 25 }}
									>
										<div
											className={`campus-guard-pin ${isInside ? "campus-guard-pin--inside" : "campus-guard-pin--outside"} ${isSelected ? "campus-guard-pin--selected" : ""}`}
											title={`${guard.guardName} (${guard.userCode || "Bảo vệ"}) · ${isInside ? "Trong khuôn viên" : "Ngoài khuôn viên"}`}
										>
											{isInside && <span className="campus-guard-pin__pulse" />}
											<div className="campus-guard-pin__circle">
												<Shield className="campus-guard-pin__icon" size={16} />
											</div>
											<span className="campus-guard-pin__tag">
												{guard.guardName?.split(" ").slice(-1)[0] || "BV"}
											</span>
										</div>
									</Marker>
								);
							})}

						{/* Google-Style Real Coordinate Markers with Auto-Shift for Overlaps */}
						{positionedAreas.map((item) => {
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
										setSelectedGuard(null);
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

				{/* Floating Colour Legend */}
				<div className="campus-map-legend">
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--public" />
						<span>{AREA_LEVEL_CONFIG.PUBLIC.badgeLabel}</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--internal" />
						<span>{AREA_LEVEL_CONFIG.INTERNAL_CONFIDENTIAL.badgeLabel}</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--contact" />
						<span>
							{AREA_LEVEL_CONFIG.CONFIDENTIAL_CONTACT_REQUIRED.badgeLabel}
						</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--private" />
						<span>{AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.badgeLabel}</span>
					</div>
					<div className="campus-map-legend__item">
						<User size={13} className="campus-map-legend__person-icon" />
						<span>Bảo vệ ({guards.filter((g) => g.isInsideGeofence).length} trong trường)</span>
					</div>
					<div className="campus-map-legend__item">
						<span className="campus-map-legend__dot campus-map-legend__dot--geofence" />
						<span>Geofence khuôn viên</span>
					</div>
				</div>
			</div>

			{/* RIGHT RAIL: AREAS LIST & DETAILS */}
			<div className="campus-rail">
				{/* Card 1: Area List */}
				<div className="campus-rail-card campus-rail-card--list">
					<div className="campus-rail-header">
						<span className="campus-rail-title">
							Khu vực khuôn viên (
							{searchTerm.trim()
								? `${searchMatches.length}/${areas.length}`
								: areas.length}
							)
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
						{searchMatches.length === 0 ? (
							<div className="campus-detail-empty">
								{areas.length === 0
									? "Chưa có khu vực nào trong hệ thống"
									: "Không có khu vực khớp từ khoá tìm kiếm"}
							</div>
						) : (
							searchMatches.map((area) => {
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
										onClick={() => handleSelectAreaItem(area.id, targetCoords)}
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
					className={`campus-rail-card campus-rail-card--detail ${!selectedArea && !selectedGuard ? "campus-rail-card--detail-empty" : ""}`}
				>
					{selectedGuard ? (
						<div className="campus-detail-content">
							<div className="campus-detail-header">
								<div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", width: "100%" }}>
									<div>
										<h3 className="campus-detail-title">{selectedGuard.guardName}</h3>
										<span style={{ fontSize: "12px", color: "var(--theme-text-muted)" }}>
											Mã: {selectedGuard.userCode || "N/A"} · Đội: {selectedGuard.teamName || "Chung"}
										</span>
									</div>
									<button
										type="button"
										className="campus-search-box__clear"
										style={{ position: "static", transform: "none" }}
										onClick={() => setSelectedGuard(null)}
										title="Đóng chi tiết bảo vệ"
									>
										<X size={14} />
									</button>
								</div>
							</div>

							<div className="campus-detail-meta" style={{ marginTop: "12px" }}>
								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">Trạng thái Geofence</span>
									<span
										className="campus-detail-meta-val"
										style={{
											color: selectedGuard.isInsideGeofence ? "#059669" : "#d97706",
											fontWeight: 700,
										}}
									>
										{selectedGuard.isInsideGeofence
											? "✓ Đang trong khuôn viên"
											: "⚠ Ngoài khuôn viên"}
									</span>
								</div>
								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">Tọa độ GPS</span>
									<span className="campus-detail-meta-val campus-detail-meta-val--coords">
										{Number(selectedGuard.latitude).toFixed(5)}, {Number(selectedGuard.longitude).toFixed(5)}
									</span>
								</div>
								{selectedGuard.batteryLevel != null && (
									<div className="campus-detail-meta-row">
										<span className="campus-detail-meta-label">Pin thiết bị</span>
										<span className="campus-detail-meta-val">
											{Math.round(selectedGuard.batteryLevel)}%
										</span>
									</div>
								)}
								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">Cập nhật GPS</span>
									<span className="campus-detail-meta-val">
										{selectedGuard.updatedAt
											? new Date(selectedGuard.updatedAt).toLocaleTimeString("vi-VN", {
													hour: "2-digit",
													minute: "2-digit",
													second: "2-digit",
												})
											: "Vừa xong"}
									</span>
								</div>
							</div>
						</div>
					) : !selectedArea ? (
						<div className="campus-detail-empty">
							<p>
								Chọn một khu vực hoặc bảo vệ trên bản đồ để xem chi tiết.
							</p>
						</div>
					) : (
						<div className="campus-detail-content">
							<div className="campus-detail-header">
								<h3 className="campus-detail-title">{selectedArea.name}</h3>
								{(selectedArea.building || selectedArea.floor) && (
									<div className="campus-detail-location">
										{selectedArea.building && (
											<span className="campus-detail-location__item">
												<Building2 size={12} />
												{selectedArea.building}
											</span>
										)}
										{selectedArea.building && selectedArea.floor && (
											<span className="campus-detail-location__sep">·</span>
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
									<span className="campus-detail-meta-label">Cấp truy cập</span>
									<span
										className="campus-detail-meta-val"
										title={
											selectedArea.explicitAuthorizationRequired
												? "Cấp tối thiểu để được gửi đơn xin vào (không cho vào tự do)"
												: "Cấp tối thiểu để được vào tự do"
										}
									>
										{getAccessLevelConfig(selectedArea.areaAccessLevel).label}
									</span>
								</div>
								<div className="campus-detail-meta-row">
									<span className="campus-detail-meta-label">Tọa độ GPS</span>
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
			</div>

			{/* MODAL KIỂM THỬ BẮN CẢNH BÁO SỰ CỐ TỚI BẢO VỆ */}
			{showTestAlertModal && (
				<>
					<div
						className="campus-test-alert-backdrop"
						onClick={() => setShowTestAlertModal(false)}
					/>
					<div className="campus-test-alert-modal">
						<div className="campus-test-alert-header">
							<div className="campus-test-alert-title">
								<BellRing size={18} />
								<span>Kiểm Thử Cảnh Báo Sự Cố Tới Bảo Vệ</span>
							</div>
							<button
								type="button"
								className="campus-search-box__clear"
								style={{ position: "static", transform: "none" }}
								onClick={() => setShowTestAlertModal(false)}
								title="Đóng modal"
							>
								<X size={16} />
							</button>
						</div>

						<p
							style={{
								fontSize: "12.5px",
								color: "var(--theme-text-muted)",
								margin: "0 0 14px",
								lineHeight: "1.5",
							}}
						>
							Hệ thống sẽ mô phỏng một sự cố an ninh và kích hoạt điều phối thông báo
							thời gian thực qua WebSocket. Chỉ các bảo vệ{" "}
							<strong>nằm trong Geofence khuôn viên</strong> và có{" "}
							<strong>GPS mới (&lt; 10 phút)</strong> mới được gửi cảnh báo.
						</p>

						<div className="campus-test-scenario-list">
							<div className="campus-test-scenario-item campus-test-scenario-item--received">
								<div className="campus-test-scenario-item__head">
									<span>1. Bảo vệ Demo (SEC-002)</span>
									<span className="campus-test-scenario-item__badge campus-test-scenario-item__badge--success">
										Nhận cảnh báo
									</span>
								</div>
								<div className="campus-test-scenario-item__desc">
									Tọa độ trong trường · GPS mới (&lt; 1 phút) · Thỏa mãn Geofence.
								</div>
							</div>

							<div className="campus-test-scenario-item campus-test-scenario-item--ignored">
								<div className="campus-test-scenario-item__head">
									<span>2. Bảo vệ Ngoài Trường (SEC-003)</span>
									<span className="campus-test-scenario-item__badge campus-test-scenario-item__badge--neutral">
										Bị loại trừ (Ngoài trường)
									</span>
								</div>
								<div className="campus-test-scenario-item__desc">
									Tọa độ Quận 1 (ngoài ranh giới Geofence) · Không gửi cảnh báo.
								</div>
							</div>

							<div className="campus-test-scenario-item campus-test-scenario-item--ignored">
								<div className="campus-test-scenario-item__head">
									<span>3. Bảo vệ GPS Quá Hạn (SEC-004)</span>
									<span className="campus-test-scenario-item__badge campus-test-scenario-item__badge--neutral">
										Bị loại trừ (GPS cũ)
									</span>
								</div>
								<div className="campus-test-scenario-item__desc">
									Tọa độ trong trường nhưng GPS đã 2 giờ trước (&gt; 10 phút) · Không gửi cảnh báo.
								</div>
							</div>
						</div>

						{testAlertResult && (
							<div
								style={{
									padding: "10px 12px",
									borderRadius: "8px",
									fontSize: "12px",
									marginBottom: "14px",
									display: "flex",
									alignItems: "center",
									gap: "8px",
									background: testAlertResult.success ? "#f0fdf4" : "#fef2f2",
									color: testAlertResult.success ? "#15803d" : "#b91c1c",
									border: `1px solid ${testAlertResult.success ? "#bbf7d0" : "#fecaca"}`,
								}}
							>
								{testAlertResult.success ? (
									<CheckCircle2 size={15} />
								) : (
									<AlertCircle size={15} />
								)}
								<span>{testAlertResult.message}</span>
							</div>
						)}

						<div
							style={{
								display: "flex",
								justifyContent: "flex-end",
								gap: "10px",
								marginTop: "16px",
							}}
						>
							<button
								type="button"
								className="campus-coord-btn campus-coord-btn--ghost"
								onClick={() => setShowTestAlertModal(false)}
							>
								Đóng
							</button>
							<button
								type="button"
								className="campus-test-alert-btn"
								onClick={handleTriggerTestAlert}
								disabled={isTriggeringTestAlert}
								style={{ padding: "8px 16px", fontSize: "13px" }}
							>
								{isTriggeringTestAlert ? (
									<Loader2 size={14} className="animate-spin" />
								) : (
									<BellRing size={14} />
								)}
								Kích hoạt cảnh báo thử nghiệm
							</button>
						</div>
					</div>
				</>
			)}
		</div>
	);
}
