import { useState, useEffect, useMemo, useCallback, useRef } from "react";
import { useSearchParams } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import {
	Plus,
	Pencil,
	Trash2,
	AlertCircle,
	X,
	Loader2,
	Layers,
	Cctv,
	VideoOff,
	Video,
	MapPin,
	Search,
	AlertTriangle,
	Info,
	ExternalLink,
	RotateCcw,
	Crosshair,
} from "lucide-react";
import { toast } from "sonner";
import AreaAssignedPersonnelModal from "../../components/area/AreaAssignedPersonnelModal";
import AreaListView from "../../components/area/AreaListView";
import CameraWorkspaceSidebar from "../../components/area/CameraWorkspaceSidebar";
import CampusMapView from "../../components/area/CampusMapView";
import FloorDigitizerStudio from "../../components/area/FloorDigitizerStudio";
import CameraInspectorDrawer from "../../components/camera/CameraInspectorDrawer";
import CameraCreateModal from "../../components/CameraCreateModal";
import ErrorBoundary from "../../components/common/ErrorBoundary";
import AreaTypeChangePreviewModal from "../../components/area/AreaTypeChangePreviewModal";
import {
	LoadingState,
	ErrorState,
	DialogPanel,
	Modal,
	Button,
} from "../../components/ui";
import ReasonTextarea from "../../components/ui/ReasonTextarea";
import "../../components/ui/Button.css";
import {
	getAllAreas,
	getDependencies,
	updateArea,
	deactivateArea,
	restoreArea,
	getAreaCameras,
	getTypeChangePreview,
} from "../../services/areaService";
import { getBuildings } from "../../services/buildingService";
import {
	fetchCameras,
	decommissionCamera,
	reactivateCamera,
	deleteCamera,
} from "../../services/cameraService";
import {
	buildCameraStatsByArea,
	extractCameraAreaInfo,
	fetchAllPaginatedPages,
} from "../../utils/campusCameraHelpers";
import {
	DEFAULT_BUILDINGS_META,
	matchesBuilding,
	resolveFloorOrder,
} from "../../data/campusIndoor3dData";
import {
	AREA_LEVEL_OPTIONS,
	getErrorMessage,
	normalizeAreaName,
	validateAreaName,
} from "../../utils/areaHelpers";
import "../../styles/AreaListPage.css";
import { useReasonLimits } from "../../hooks/useReasonLimits";

const toNullableCoord = (value) => {
	if (value === null || value === undefined || String(value).trim() === "") {
		return null;
	}
	const num = Number(value);
	return Number.isFinite(num) ? num : null;
};

// V83: 3 loại, cấp cố định theo loại (Công khai 1, Bảo mật 2, Bảo mật cao 3)
const AREA_LEVEL_CARDS = AREA_LEVEL_OPTIONS.map((cfg, i) => ({
	value: cfg.code,
	name: cfg.name,
	color: cfg.color,
	level: i + 1,
}));

const AREA_LEVEL_BTN_CLASS = {
	PUBLIC: "area-level-btn--public",
	CONFIDENTIAL: "area-level-btn--internal",
	HIGHLY_CONFIDENTIAL: "area-level-btn--private",
};

export default function AreaListPage() {
	const reasonLimits = useReasonLimits();
	const { user } = useAuth();
	const isAdmin = user?.role === "ADMIN";
	const [searchParams, setSearchParams] = useSearchParams();
	const isFacilityManager = user?.role === "FACILITY_MANAGER";

	// Split-View: Danh sách bên trái (Khu vực / Camera) + Bản đồ khuôn viên bên phải
	const [isLeftSidebarCollapsed, setIsLeftSidebarCollapsed] = useState(false);
	const [leftSidebarMode, setLeftSidebarMode] = useState(() =>
		searchParams.get("tab") === "cameras" ? "CAMERAS" : "AREAS",
	);
	const [isCreatingOnMap, setIsCreatingOnMap] = useState(false);
	const [rebindingAreaId, setRebindingAreaId] = useState(null);
	const [digitizerStudioOpen, setDigitizerStudioOpen] = useState(false);

	// Unified Camera Management states (Option 1 merge)
	const [cameras, setCameras] = useState([]);
	const [camerasLoading, setCamerasLoading] = useState(true);
	const [camerasError, setCamerasError] = useState(null);
	const [selectedCameraId, setSelectedCameraId] = useState(
		() => searchParams.get("cameraId") || null,
	);
	const [selectedCameraInitialTab, setSelectedCameraInitialTab] = useState(
		() => searchParams.get("cameraTab") || "general",
	);
	const [isCreateCameraOpen, setIsCreateCameraOpen] = useState(false);
	const [createCameraDefaultAreaId, setCreateCameraDefaultAreaId] =
		useState("");
	const [cameraActionLoadingId, setCameraActionLoadingId] = useState(null);
	const [sidebarDecommissionTarget, setSidebarDecommissionTarget] =
		useState(null);
	const [sidebarDeleteTarget, setSidebarDeleteTarget] = useState(null);
	const [pickingCameraArea, setPickingCameraArea] = useState(false);
	const [pickedAreaOverride, setPickedAreaOverride] = useState(null);

	const handleStartCreateOnMap = () => {
		setSelectedCameraId(null);
		setPickingCameraArea(false);
		setIsCreatingOnMap(true);
	};

	const handleAreaUpdatedOnMap = useCallback((areaId, updated) => {
		setAreas((prev) =>
			prev.map((a) =>
				a.id === areaId && updated && typeof updated === "object"
					? {
							...a,
							centerLatitude: updated.centerLatitude ?? a.centerLatitude,
							centerLongitude: updated.centerLongitude ?? a.centerLongitude,
							version: updated.version ?? a.version,
							building: updated.building ?? a.building,
							floor: updated.floor ?? a.floor,
							floorId: updated.floorId ?? a.floorId,
						}
					: a,
			),
		);
		setSelectedAreaId(areaId);
	}, []);

	// Data states
	const [areas, setAreas] = useState([]);
	const [buildingsList, setBuildingsList] = useState([]);
	const [loading, setLoading] = useState(true);
	const [pageError, setPageError] = useState(null);

	// Filters (default ALL so all 3D campus buildings render together on overview)
	const [selectedBuilding, setSelectedBuilding] = useState("ALL");
	const [selectedFloor, setSelectedFloor] = useState("ALL");
	const [selectedAreaId, setSelectedAreaId] = useState(null);
	const [cameraCounts, setCameraCounts] = useState({});

	// Modal states
	const [editModalOpen, setEditModalOpen] = useState(false);
	const [editReasonError, setEditReasonError] = useState(null);
	const editReasonRef = useRef(null);
	// BL2: xem trước đổi loại khu vực trước khi gửi PUT
	const [typePreview, setTypePreview] = useState(null);
	const [typePreviewConfirming, setTypePreviewConfirming] = useState(false);
	const [deactivateModalOpen, setDeactivateModalOpen] = useState(false);
	const [modalLoading, setModalLoading] = useState(false);
	const [modalError, setModalError] = useState(null);
	const [nameError, setNameError] = useState(null);
	const [dependencies, setDependencies] = useState(null);
	// Step 6 (BR-AD-01, 08): khu vực đang mở modal vô hiệu hóa + lý do. Giữ riêng với selectedArea vì sau 409 045
	// danh sách được tải lại và khu vực có thể không còn trong danh sách đang hoạt động.
	const [deactivateTarget, setDeactivateTarget] = useState(null);
	const [deactivateReason, setDeactivateReason] = useState("");
	const [deactivateReasonError, setDeactivateReasonError] = useState(null);
	const deactivateReasonRef = useRef(null);
	// Step 6 (BR-AD-07): bộ lọc "Đã vô hiệu hóa" + khôi phục — chỉ ADMIN
	const [showDeactivated, setShowDeactivated] = useState(false);
	const [deactivatedAreas, setDeactivatedAreas] = useState([]);
	const [deactivatedLoading, setDeactivatedLoading] = useState(false);
	const [deactivatedError, setDeactivatedError] = useState(null);
	const [restoreTarget, setRestoreTarget] = useState(null);
	const [restoreReason, setRestoreReason] = useState("");
	const [restoreReasonError, setRestoreReasonError] = useState(null);
	const restoreReasonRef = useRef(null);
	const [restoreError, setRestoreError] = useState(null);
	const [restoreLoading, setRestoreLoading] = useState(false);

	// Camera list modal states
	const [camerasModalOpen, setCamerasModalOpen] = useState(false);

	// UI-22: Escape đóng popup "Danh sách Camera"
	useEffect(() => {
		if (!camerasModalOpen) return undefined;
		const handleKeyDown = (e) => {
			if (e.key === "Escape") setCamerasModalOpen(false);
		};
		document.addEventListener("keydown", handleKeyDown);
		return () => document.removeEventListener("keydown", handleKeyDown);
	}, [camerasModalOpen]);
	const [camerasModalArea, setCamerasModalArea] = useState(null);
	const [areaCamerasList, setAreaCamerasList] = useState([]);
	const [loadingAreaCameras, setLoadingAreaCameras] = useState(false);
	const [areaCamerasError, setAreaCamerasError] = useState(null);

	// Search & camera inspection modal states
	const [cameraSearchQuery, setCameraSearchQuery] = useState("");

	// Assigned personnel modal states (Facility Manager & Admin)
	const [assignedPersonnelModalOpen, setAssignedPersonnelModalOpen] =
		useState(false);
	const [assignedPersonnelModalArea, setAssignedPersonnelModalArea] =
		useState(null);

	const handleOpenAssignedPersonnelModal = useCallback((area) => {
		if (!area) return;
		setAssignedPersonnelModalArea(area);
		setAssignedPersonnelModalOpen(true);
	}, []);

	const handleOpenCamerasModal = useCallback(async (area) => {
		if (!area) return;
		setCamerasModalArea(area);
		setCamerasModalOpen(true);
		setLoadingAreaCameras(true);
		setAreaCamerasError(null);
		setAreaCamerasList([]);
		setCameraSearchQuery("");

		try {
			const res = await getAreaCameras(area.id);
			const camList = res?.cameras || (Array.isArray(res) ? res : []);
			setAreaCamerasList(camList);
			// Sync card count immediately when modal opens
			setCameraCounts((prev) => ({ ...prev, [area.id]: camList.length }));
		} catch (err) {
			console.error("Error fetching area cameras:", err);
			setAreaCamerasError("Không thể tải danh sách camera của khu vực này.");
		} finally {
			setLoadingAreaCameras(false);
		}
	}, []);

	// Form states
	const [formData, setFormData] = useState({
		name: "",
		areaLevel: "PUBLIC",
		building: "Tòa Alpha",
		floor: "Tầng Trệt",
		floorId: null,
		centerLatitude: "",
		centerLongitude: "",
		reason: "",
	});

	const rowRefs = useRef({});

	// Fetch all data (multi-page respecting backend size <= 100 limit)
	const fetchData = useCallback(async (keepSelectedId = null) => {
		setLoading(true);
		setPageError(null);
		try {
			const [areaList, buildingsRes] = await Promise.all([
				getAllAreas({ isActive: true }),
				getBuildings().catch(() => []),
			]);

			setAreas(Array.isArray(areaList) ? areaList : []);
			setBuildingsList(Array.isArray(buildingsRes) ? buildingsRes : []);

			if (keepSelectedId) {
				setSelectedAreaId(keepSelectedId);
			}
		} catch (err) {
			console.error("Error loading area data:", err);
			setPageError(getErrorMessage(err));
		} finally {
			setLoading(false);
		}
	}, []);

	useEffect(() => {
		fetchData();
	}, [fetchData]);

	// Derived available buildings (bao gồm cả 2 tòa nhà 3D: Tòa Alpha 3 tầng & Tòa Epsilon 5 tầng)
	const availableBuildings = useMemo(() => {
		const mapByName = new Map();
		if (buildingsList.length > 0) {
			buildingsList.forEach((b) => {
				mapByName.set(b.name, {
					id: b.id,
					name: b.name,
					floors: b.floors || [],
				});
			});
		} else {
			areas.forEach((a) => {
				if (a.building && !mapByName.has(a.building)) {
					mapByName.set(a.building, { id: null, name: a.building, floors: [] });
				}
			});
		}

		DEFAULT_BUILDINGS_META.forEach((b3d) => {
			const hasAlias = Array.from(mapByName.keys()).some((existing) =>
				matchesBuilding(existing, b3d.name),
			);
			if (!hasAlias) {
				mapByName.set(b3d.name, {
					id: null,
					name: b3d.name,
					floors: b3d.floors.map((fl) => ({ id: null, name: fl.name })),
				});
			}
		});

		if (mapByName.size === 0) {
			return ["Tòa Alpha", "Tòa Epsilon"].map((name) => ({
				id: null,
				name,
				floors: [],
			}));
		}
		return Array.from(mapByName.values());
	}, [buildingsList, areas]);

	// Default building initialization
	useEffect(() => {
		if (!selectedBuilding && availableBuildings.length > 0) {
			const defaultB =
				availableBuildings.find((b) => b.name === "Tòa Alpha") ||
				availableBuildings[0];
			setSelectedBuilding(defaultB.name);
		}
	}, [availableBuildings, selectedBuilding]);

	// Derived available floors for current building (supports ALL buildings)
	const availableFloors = useMemo(() => {
		if (!selectedBuilding || selectedBuilding === "ALL") {
			const fSet = new Set();
			buildingsList.forEach((b) => {
				(b.floors || []).forEach((f) => {
					if (f?.name) fSet.add(String(f.name));
				});
			});
			areas.forEach((a) => {
				if (a.floor) fSet.add(a.floor);
			});
			DEFAULT_BUILDINGS_META.forEach((b3d) => {
				b3d.floors.forEach((fl) => {
					if (fl?.name) fSet.add(fl.name);
				});
			});
			if (fSet.size === 0) {
				fSet.add("Tầng Trệt");
				fSet.add("Tầng 1");
			}
			return Array.from(fSet)
				.sort((a, b) => {
					if (a.toLowerCase().includes("trệt")) return -1;
					if (b.toLowerCase().includes("trệt")) return 1;
					return a.localeCompare(b);
				})
				.map((fl) => ({
					name: fl,
					floorId: null,
				}));
		}

		const currentBuildingObj = availableBuildings.find(
			(b) =>
				(b.name || "").toUpperCase() ===
					(selectedBuilding || "").toUpperCase() ||
				matchesBuilding(b.name, selectedBuilding),
		);
		if (
			currentBuildingObj &&
			currentBuildingObj.floors &&
			currentBuildingObj.floors.length > 0
		) {
			return currentBuildingObj.floors.map((f) => ({
				name: f.name,
				floorId: f.id,
			}));
		}

		const fSet = new Set();
		areas
			.filter(
				(a) =>
					(a.building || "").toUpperCase() ===
						(selectedBuilding || "").toUpperCase() ||
					matchesBuilding(a.building, selectedBuilding),
			)
			.forEach((a) => {
				if (a.floor) fSet.add(a.floor);
			});

		if (fSet.size === 0) {
			return [
				{ name: "Tầng Trệt", floorId: null },
				{ name: "Tầng 1", floorId: null },
			];
		}

		return Array.from(fSet)
			.sort((a, b) => {
				if (a.toLowerCase().includes("trệt")) return -1;
				if (b.toLowerCase().includes("trệt")) return 1;
				return a.localeCompare(b);
			})
			.map((fl) => ({
				name: fl,
				floorId: null,
			}));
	}, [buildingsList, availableBuildings, areas, selectedBuilding]);

	// Default floor initialization
	useEffect(() => {
		if (
			availableFloors.length > 0 &&
			selectedFloor !== "ALL" &&
			!availableFloors.some((f) => f.name === selectedFloor)
		) {
			setSelectedFloor("ALL");
		}
	}, [availableFloors, selectedFloor]);

	// Filtered areas on current building and floor (for list view & map view, hỗ trợ alias Tòa HCM <-> Tòa Alpha, Tòa Epsilon <-> Tòa Beta)
	const floorAreas = useMemo(() => {
		return areas.filter((a) => {
			const matchBuilding =
				!selectedBuilding ||
				selectedBuilding === "ALL" ||
				(a.building || "").toUpperCase() ===
					(selectedBuilding || "").toUpperCase() ||
				matchesBuilding(a.building, selectedBuilding);
			const matchFloor =
				!selectedFloor ||
				selectedFloor === "ALL" ||
				(a.floor || "").toUpperCase() === (selectedFloor || "").toUpperCase() ||
				resolveFloorOrder(a.building, a.floor) ===
					resolveFloorOrder(selectedBuilding, selectedFloor);
			return matchBuilding && matchFloor;
		});
	}, [areas, selectedBuilding, selectedFloor]);

	// Selected area object
	const selectedArea = useMemo(() => {
		return areas.find((a) => a.id === selectedAreaId) || null;
	}, [areas, selectedAreaId]);

	// Area lookup map for camera resolution
	const areaById = useMemo(() => {
		const map = new Map();
		areas.forEach((a) => {
			if (a?.id != null) map.set(a.id, a);
		});
		return map;
	}, [areas]);

	// Unified Camera fetch & stats (Option 1 merge)
	const fetchAllCameras = useCallback(async () => {
		setCamerasLoading(true);
		setCamerasError(null);
		try {
			const list = await fetchAllPaginatedPages(
				(page, size) =>
					fetchCameras({
						page,
						size,
						sort: "cameraCode,asc",
					}),
				{ pageSize: 200 },
			);
			setCameras(Array.isArray(list) ? list : []);
		} catch (err) {
			console.error("Error fetching workspace cameras:", err);
			setCamerasError(getErrorMessage(err));
		} finally {
			setCamerasLoading(false);
		}
	}, []);

	useEffect(() => {
		fetchAllCameras();
	}, [fetchAllCameras]);

	const { statsByArea: cameraStatsByArea, countsByArea: derivedCameraCounts } =
		useMemo(() => buildCameraStatsByArea(cameras), [cameras]);

	const mergedCameraCounts = useMemo(() => {
		const counts = { ...cameraCounts };
		if (!camerasLoading && !camerasError) {
			areas.forEach((a) => {
				if (a?.id != null) {
					counts[a.id] = derivedCameraCounts[a.id] ?? 0;
				}
			});
		}
		Object.entries(derivedCameraCounts).forEach(([areaId, count]) => {
			counts[areaId] = count;
		});
		return counts;
	}, [areas, cameraCounts, derivedCameraCounts, camerasLoading, camerasError]);

	const syncCameraUrlParams = useCallback(
		(nextMode, nextCameraId, nextCameraTab) => {
			setSearchParams(
				(prev) => {
					const next = new URLSearchParams(prev);
					if (nextMode === "CAMERAS") {
						next.set("tab", "cameras");
					} else {
						next.delete("tab");
					}
					if (nextCameraId) {
						next.set("cameraId", String(nextCameraId));
						if (nextCameraTab && nextCameraTab !== "general") {
							next.set("cameraTab", nextCameraTab);
						} else {
							next.delete("cameraTab");
						}
					} else {
						next.delete("cameraId");
						next.delete("cameraTab");
					}
					return next;
				},
				{ replace: true },
			);
		},
		[setSearchParams],
	);

	const urlTab = searchParams.get("tab");
	const urlCameraId = searchParams.get("cameraId");
	const urlCameraTab = searchParams.get("cameraTab");
	const prevUrlRef = useRef({ tab: null, cameraId: null, cameraTab: null });
	useEffect(() => {
		const prev = prevUrlRef.current;
		if (urlTab !== prev.tab) {
			if (urlTab === "cameras") {
				setLeftSidebarMode("CAMERAS");
			} else if (prev.tab === "cameras" && !urlTab) {
				setLeftSidebarMode("AREAS");
			}
		}
		if (urlCameraId !== prev.cameraId || urlCameraTab !== prev.cameraTab) {
			if (urlCameraId) {
				setSelectedCameraId(urlCameraId);
				setSelectedCameraInitialTab(urlCameraTab || "general");
			} else if (prev.cameraId) {
				setSelectedCameraId(null);
			}
		}
		prevUrlRef.current = { tab: urlTab, cameraId: urlCameraId, cameraTab: urlCameraTab };
	}, [urlTab, urlCameraId, urlCameraTab]);

	const handleSwitchLeftSidebarMode = useCallback(
		(nextMode) => {
			setLeftSidebarMode(nextMode);
			syncCameraUrlParams(nextMode, selectedCameraId, selectedCameraInitialTab);
		},
		[selectedCameraId, selectedCameraInitialTab, syncCameraUrlParams],
	);

	const handleSyncMapFilter = useCallback((nextBuilding, nextFloor) => {
		setSelectedBuilding(nextBuilding || "ALL");
		setSelectedFloor(nextFloor || "ALL");
		setSelectedAreaId(null);
	}, []);

	// Area selection
	const handleSelectArea = useCallback((areaId) => {
		setSelectedAreaId(areaId);
		if (rowRefs.current[areaId]) {
			rowRefs.current[areaId].scrollIntoView({
				behavior: "smooth",
				block: "nearest",
			});
		}
	}, []);

	const handleFocusAreaOnMap = useCallback(
		(areaId) => {
			if (!areaId) return;
			const targetArea = areaById.get(areaId);
			if (targetArea) {
				if (targetArea.building) {
					setSelectedBuilding(targetArea.building);
				}
				if (targetArea.floor) {
					setSelectedFloor(targetArea.floor);
				}
			}
			setSelectedAreaId(areaId);
		},
		[areaById],
	);

	const handleSelectCamera = useCallback(
		(camera, preferredTab = "general") => {
			if (!camera) {
				setSelectedCameraId(null);
				setPickingCameraArea(false);
				setPickedAreaOverride(null);
				syncCameraUrlParams(leftSidebarMode, null, null);
				return;
			}
			setIsCreatingOnMap(false);
			setRebindingAreaId(null);
			setPickingCameraArea(false);
			setPickedAreaOverride(null);
			setSelectedCameraId(camera.id);
			setSelectedCameraInitialTab(preferredTab || "general");

			const areaInfo = extractCameraAreaInfo(camera, areaById);
			const matchedArea = areaInfo.areaId
				? areaById.get(areaInfo.areaId)
				: null;
			if (matchedArea) {
				if (matchedArea.building) setSelectedBuilding(matchedArea.building);
				if (matchedArea.floor) setSelectedFloor(matchedArea.floor);
				setSelectedAreaId(matchedArea.id);
			}
			syncCameraUrlParams(leftSidebarMode, camera.id, preferredTab || "general");
		},
		[areaById, leftSidebarMode, syncCameraUrlParams],
	);

	const handleCloseCameraInspector = useCallback(() => {
		setSelectedCameraId(null);
		setPickingCameraArea(false);
		setPickedAreaOverride(null);
		syncCameraUrlParams(leftSidebarMode, null, null);
	}, [leftSidebarMode, syncCameraUrlParams]);

	const handleSelectCameraBadge = useCallback(
		(areaId, areaCameras = []) => {
			handleSelectArea(areaId);
			if (Array.isArray(areaCameras) && areaCameras.length === 1) {
				handleSelectCamera(areaCameras[0], "general");
				return;
			}
			const matchedArea = areaById.get(areaId);
			if (matchedArea) {
				handleOpenCamerasModal(matchedArea);
			}
		},
		[areaById, handleOpenCamerasModal, handleSelectArea, handleSelectCamera],
	);

	const handleOpenCreateCameraModal = useCallback(
		(defaultAreaId = "") => {
			setCreateCameraDefaultAreaId(
				defaultAreaId
					? String(defaultAreaId)
					: selectedAreaId
						? String(selectedAreaId)
						: "",
			);
			setIsCreateCameraOpen(true);
		},
		[selectedAreaId],
	);

	const handleToggleSidebarCameraStatus = useCallback(
		async (cam) => {
			if (!isAdmin || !cam?.id) return;
			const isActive = cam.status === "ACTIVE" || !cam.status;
			if (isActive) {
				setSidebarDecommissionTarget(cam);
				return;
			}
			setCameraActionLoadingId(cam.id);
			try {
				await reactivateCamera(cam.id);
				toast.success(
					`Đã kích hoạt lại camera "${cam.name || cam.cameraCode}"`,
				);
				await fetchAllCameras();
			} catch (err) {
				toast.error(
					err?.response?.data?.message ||
						"Không thể kích hoạt lại camera này",
				);
			} finally {
				setCameraActionLoadingId(null);
			}
		},
		[fetchAllCameras, isAdmin],
	);

	const handleConfirmSidebarDecommission = useCallback(async () => {
		if (!sidebarDecommissionTarget?.id) return;
		const target = sidebarDecommissionTarget;
		setCameraActionLoadingId(target.id);
		try {
			await decommissionCamera(target.id);
			toast.success(
				`Đã ngưng hoạt động camera "${target.name || target.cameraCode}"`,
			);
			setSidebarDecommissionTarget(null);
			await fetchAllCameras();
		} catch (err) {
			toast.error(
				err?.response?.data?.message ||
					"Không thể ngưng hoạt động camera này",
			);
		} finally {
			setCameraActionLoadingId(null);
		}
	}, [fetchAllCameras, sidebarDecommissionTarget]);

	const handleConfirmSidebarDelete = useCallback(async () => {
		if (!sidebarDeleteTarget?.id) return;
		const target = sidebarDeleteTarget;
		setCameraActionLoadingId(target.id);
		try {
			await deleteCamera(target.id);
			toast.success(`Đã xóa camera "${target.name || target.cameraCode}"`);
			if (String(selectedCameraId || "") === String(target.id)) {
				handleCloseCameraInspector();
			}
			setSidebarDeleteTarget(null);
			await fetchAllCameras();
		} catch (err) {
			toast.error(
				err?.response?.data?.message || "Không thể xóa camera này",
			);
		} finally {
			setCameraActionLoadingId(null);
		}
	}, [
		fetchAllCameras,
		handleCloseCameraInspector,
		selectedCameraId,
		sidebarDeleteTarget,
	]);

	const handleAreaPickedForCamera = useCallback((pickedArea) => {
		if (!pickedArea?.id) return;
		setPickedAreaOverride({
			id: String(pickedArea.id),
			name: pickedArea.name || "",
		});
		setPickingCameraArea(false);
		toast.success(`Đã chọn khu vực "${pickedArea.name}" cho camera.`);
	}, []);

	const handleOpenEditModal = (targetArea = null) => {
		const areaToEdit = targetArea || selectedArea;
		if (!areaToEdit) return;
		setSelectedAreaId(areaToEdit.id);
		const bName = areaToEdit.building || "Tòa Alpha";
		const bObj = availableBuildings.find((b) => b.name === bName);
		const flName = areaToEdit.floor || "Tầng Trệt";
		const flObj = bObj?.floors?.find((f) => f.name === flName);

		setFormData({
			id: areaToEdit.id,

			name: areaToEdit.name,
			areaLevel:
				areaToEdit.areaLevel ||
				(typeof areaToEdit.level === "object"
					? areaToEdit.level?.code
					: areaToEdit.level) ||
				"PUBLIC",
			areaAccessLevel:
				areaToEdit.areaAccessLevel ?? areaToEdit.accessLevel ?? 1,
			building: bName,
			floor: flName,
			floorId:
				areaToEdit.floorEntity?.id || areaToEdit.floorId || flObj?.id || null,
			centerLatitude: toNullableCoord(areaToEdit.centerLatitude),
			centerLongitude: toNullableCoord(areaToEdit.centerLongitude),
			reason: "",
			// Step 5b: loại lúc mở để biết có đổi loại không (bắt lý do), version gửi kèm PUT (BR-TC-13)
			originalAreaLevel: areaToEdit.areaLevel || null,
			version: areaToEdit.version ?? null,
		});
		setModalError(null);
		setNameError(null);
		setEditReasonError(null);
		setEditModalOpen(true);
	};

	const handleEditSubmit = async (e) => {
		e.preventDefault();
		const nameValidationError = validateAreaName(formData.name);
		if (nameValidationError) {
			setNameError(nameValidationError);
			return;
		}
		setModalError(null);

		const targetId = formData.id || selectedArea?.id;
		if (!targetId) return;

		// Step 5b (BR-TC-02): đổi loại bắt buộc lý do, độ dài theo cấu hình (useReasonLimits)
		const isTypeChange =
			Boolean(formData.originalAreaLevel) &&
			formData.areaLevel !== formData.originalAreaLevel;
		const trimmedReason = (formData.reason || "").trim();
		if (isTypeChange && !reasonLimits.isValid(trimmedReason)) {
			setEditReasonError(reasonLimits.error(trimmedReason));
			editReasonRef.current?.focus();
			return;
		}
		setEditReasonError(null);

		// Phương án U1: Form Sửa gửi nguyên vẹn cặp tọa độ hiện có (kể cả null/null cho khu vực chưa gắn block 3D)
		const payload = {
			name: normalizeAreaName(formData.name),
			areaLevel: formData.areaLevel,
			building: formData.building ? formData.building.trim() : null,
			floor: formData.floor ? formData.floor.trim() : null,
			floorId: formData.floorId || null,
			centerLatitude: toNullableCoord(formData.centerLatitude),
			centerLongitude: toNullableCoord(formData.centerLongitude),
			version: formData.version,
			...(isTypeChange ? { reason: trimmedReason } : {}),
		};

		if (!isTypeChange) {
			setModalLoading(true);
			try {
				await saveAreaUpdate(targetId, payload);
			} finally {
				setModalLoading(false);
			}
			return;
		}

		// BL2: đổi loại -> xem trước, KHÔNG gửi PUT cho tới khi ADMIN xác nhận
		setModalLoading(true);
		try {
			const preview = await getTypeChangePreview(targetId, formData.areaLevel);
			setTypePreview({ preview, targetId, payload });
		} catch (err) {
			console.error("Type change preview failed:", err);
			setModalError(getErrorMessage(err));
		} finally {
			setModalLoading(false);
		}
	};

	/** PUT /api/areas/{id} — luồng lưu dùng chung cho sửa thường và sau khi xác nhận đổi loại. */
	const saveAreaUpdate = async (targetId, payload) => {
		try {
			const updated = await updateArea(targetId, payload);
			setEditModalOpen(false);
			await fetchData(updated.id);
			return true;
		} catch (err) {
			console.error("Update area failed:", err);
			if (err?.code === "ERR_AREA_045") {
				// Người khác vừa cập nhật khu vực: tải lại danh sách để lần mở sau có dữ liệu + version mới
				setModalError(
					"Khu vực đã được người khác cập nhật. Vui lòng đóng và mở lại để xem dữ liệu mới nhất.",
				);
				await fetchData(targetId);
			} else {
				setModalError(getErrorMessage(err));
			}
			return false;
		}
	};

	/** Xác nhận trong modal xem trước: backend vẫn đánh giá lại sau khi khoá (BR-TC-03b). */
	const handleConfirmTypeChange = async () => {
		if (!typePreview) return;
		setTypePreviewConfirming(true);
		try {
			// Thành công: form sửa đóng. Lỗi PUT (048/049/042/045/...): câu lỗi nguyên văn hiện ở form sửa (045 đã tải lại).
			await saveAreaUpdate(typePreview.targetId, typePreview.payload);
			setTypePreview(null);
		} finally {
			setTypePreviewConfirming(false);
		}
	};

	const handleOpenDeactivateModal = async (targetArea = null) => {
		const areaToDeactivate = targetArea || selectedArea;
		if (!areaToDeactivate) return;
		setSelectedAreaId(areaToDeactivate.id);
		setDeactivateTarget(areaToDeactivate);
		setDeactivateReason("");
		setModalError(null);
		setDependencies(null);
		setDeactivateModalOpen(true);
		setModalLoading(true);

		try {
			// BR-AD-08: xem trước chỉ đọc — blockers + số AP / đơn / lượt khách sẽ bị xử lý + version
			const depRes = await getDependencies(areaToDeactivate.id);
			setDependencies(depRes);
		} catch (err) {
			console.error("Failed to get area dependencies:", err);
			setModalError(getErrorMessage(err));
		} finally {
			setModalLoading(false);
		}
	};

	const closeDeactivateModal = () => {
		setDeactivateModalOpen(false);
		setDeactivateTarget(null);
		setDeactivateReason("");
		setDeactivateReasonError(null);
	};

	const deactivateBlocked =
		!dependencies || (dependencies.blockers || []).length > 0;

	const handleDeactivateSubmit = async () => {
		if (!deactivateTarget || !dependencies) return;
		const trimmed = deactivateReason.trim();
		if (!reasonLimits.isValid(trimmed)) {
			setDeactivateReasonError(reasonLimits.error(trimmed));
			deactivateReasonRef.current?.focus();
			return;
		}
		const targetId = deactivateTarget.id;
		setModalLoading(true);
		setModalError(null);
		setDeactivateReasonError(null);
		try {
			await deactivateArea(targetId, {
				reason: trimmed,
				version: dependencies.version,
			});
			closeDeactivateModal();
			setSelectedAreaId(null);
			await fetchData();
			if (showDeactivated) await fetchDeactivatedAreas();
		} catch (err) {
			console.error("Deactivate area failed:", err);
			if (err?.code === "ERR_AREA_045") {
				// Người khác vừa cập nhật khu vực: tải lại danh sách + xem trước để có dữ liệu và version mới
				setModalError(
					"Khu vực đã được người khác cập nhật. Đã tải lại dữ liệu mới nhất, vui lòng kiểm tra lại.",
				);
				await fetchData(targetId);
				try {
					setDependencies(await getDependencies(targetId));
				} catch {
					setDependencies(null);
					setModalError(
						"Khu vực đã được người khác cập nhật và không còn ở trạng thái đang hoạt động. Vui lòng đóng cửa sổ này.",
					);
				}
			} else {
				setModalError(getErrorMessage(err));
			}
		} finally {
			setModalLoading(false);
		}
	};

	// ------------------------------------------------------------------ Step 6: đã vô hiệu hóa + khôi phục

	const fetchDeactivatedAreas = useCallback(async () => {
		setDeactivatedLoading(true);
		setDeactivatedError(null);
		try {
			const list = await getAllAreas({ isActive: false });
			setDeactivatedAreas(Array.isArray(list) ? list : []);
		} catch (err) {
			console.error("Failed to load deactivated areas:", err);
			setDeactivatedError(getErrorMessage(err));
		} finally {
			setDeactivatedLoading(false);
		}
	}, []);

	useEffect(() => {
		if (isAdmin && showDeactivated) {
			fetchDeactivatedAreas();
		}
	}, [isAdmin, showDeactivated, fetchDeactivatedAreas]);

	const openRestoreModal = (area) => {
		setRestoreTarget(area);
		setRestoreReason("");
		setRestoreReasonError(null);
		setRestoreError(null);
	};

	const handleRestoreSubmit = async () => {
		if (!restoreTarget) return;
		const trimmed = restoreReason.trim();
		if (!reasonLimits.isValid(trimmed)) {
			setRestoreReasonError(reasonLimits.error(trimmed));
			restoreReasonRef.current?.focus();
			return;
		}
		setRestoreLoading(true);
		setRestoreError(null);
		setRestoreReasonError(null);
		try {
			const restored = await restoreArea(restoreTarget.id, {
				reason: trimmed,
				version: restoreTarget.version,
			});
			setRestoreTarget(null);
			setRestoreReason("");
			setRestoreReasonError(null);
			await Promise.all([fetchDeactivatedAreas(), fetchData()]);
			if (
				restored?.centerLatitude == null ||
				restored?.centerLongitude == null
			) {
				toast.info(
					"Khu vực đã khôi phục nhưng cần chọn lại block 3D mới để hiển thị trên bản đồ.",
					{ duration: 5000 },
				);
			}
		} catch (err) {
			console.error("Restore area failed:", err);
			if (err?.code === "ERR_AREA_045") {
				setRestoreError(
					"Khu vực đã được người khác cập nhật. Đã tải lại danh sách, vui lòng mở lại để khôi phục.",
				);
				await fetchDeactivatedAreas();
			} else {
				setRestoreError(getErrorMessage(err));
			}
		} finally {
			setRestoreLoading(false);
		}
	};

	return (
		<div className="zone-page">
			{/* ============================================================ */}
			{/* 1. TOOLBAR: Action buttons (ADMIN ONLY)                      */}
			{/* ============================================================ */}
			{isAdmin && (
				<div className="zone-toolbar">
					<div className="zone-toolbar__spacer" />
					<div className="zone-toolbar__actions">
						<button
							type="button"
							className="zone-toolbar__studio-btn"
							onClick={() => setDigitizerStudioOpen(true)}
							title="Mở Studio 3D"
						>
							<Layers
								size={16}
								strokeWidth={2.2}
							/>
							<span>Studio 3D</span>
						</button>

						<button
							type="button"
							className="zone-toolbar__add-btn"
							onClick={handleStartCreateOnMap}
							title="Thêm khu vực từ block 3D"
						>
							<Plus
								size={16}
								strokeWidth={2.5}
							/>
							<span>Thêm khu vực</span>
						</button>

						<button
							type="button"
							className="zone-toolbar__studio-btn"
							onClick={() => handleOpenCreateCameraModal()}
							title="Thêm thiết bị camera mới"
						>
							<Video
								size={16}
								strokeWidth={2.2}
							/>
							<span>+ Thêm camera</span>
						</button>
					</div>
				</div>
			)}

			{/* MAIN CONTENT: SPLIT-VIEW (MAP + AREA/CAMERA LIST) FOR BOTH ADMIN & FM */}
			<div
				className={`zone-split-layout ${isLeftSidebarCollapsed ? "zone-split-layout--sidebar-collapsed" : ""}`}
			>
				<aside
					className={`zone-split-sidebar ${isLeftSidebarCollapsed ? "zone-split-sidebar--collapsed" : ""}`}
				>
					<div
						className="zone-mode-switcher"
						role="tablist"
						aria-label="Chế độ quản lý bên trái"
					>
						<button
							type="button"
							role="tab"
							aria-selected={leftSidebarMode === "AREAS"}
							className={`zone-mode-switcher__btn ${leftSidebarMode === "AREAS" ? "zone-mode-switcher__btn--active" : ""}`}
							onClick={() => handleSwitchLeftSidebarMode("AREAS")}
						>
							<MapPin size={14} />
							<span>Khu vực</span>
							<span className="zone-mode-switcher__count">{areas.length}</span>
						</button>
						<button
							type="button"
							role="tab"
							aria-selected={leftSidebarMode === "CAMERAS"}
							className={`zone-mode-switcher__btn ${leftSidebarMode === "CAMERAS" ? "zone-mode-switcher__btn--active" : ""}`}
							onClick={() => handleSwitchLeftSidebarMode("CAMERAS")}
						>
							<Video size={14} />
							<span>Camera</span>
							<span className="zone-mode-switcher__count">{cameras.length}</span>
						</button>
					</div>

					{leftSidebarMode === "AREAS" && loading && (
						<LoadingState text="Đang tải dữ liệu khu vực..." />
					)}

					{leftSidebarMode === "AREAS" && !loading && pageError && (
						<ErrorState
							message={pageError}
							onRetry={() => fetchData(selectedAreaId)}
						/>
					)}

					{leftSidebarMode === "AREAS" && !loading && !pageError && (
						<AreaListView
							floorAreas={floorAreas}
							selectedAreaId={selectedAreaId}
							cameraCounts={mergedCameraCounts}
							cameraStatsByArea={cameraStatsByArea}
							isAdmin={isAdmin}
							isFacilityManager={isFacilityManager}
							onSelectArea={(id) => handleSelectArea(id, false)}
							onOpenCreateModal={handleStartCreateOnMap}
							onOpenCamerasModal={handleOpenCamerasModal}
							onOpenAssignedPersonnelModal={handleOpenAssignedPersonnelModal}
							onOpenEditModal={handleOpenEditModal}
							onOpenDeactivateModal={handleOpenDeactivateModal}
							onStartRebind3d={
								isAdmin
									? (area) => {
											setSelectedAreaId(area.id);
											setRebindingAreaId(area.id);
										}
									: undefined
							}
							showDeactivated={showDeactivated}
							onToggleShowDeactivated={setShowDeactivated}
							deactivatedAreas={deactivatedAreas}
							deactivatedLoading={deactivatedLoading}
							deactivatedError={deactivatedError}
							onFetchDeactivated={fetchDeactivatedAreas}
							onOpenRestoreModal={openRestoreModal}
						/>
					)}

					{leftSidebarMode === "CAMERAS" && (
						<CameraWorkspaceSidebar
							cameras={cameras}
							areas={areas}
							loading={camerasLoading}
							error={camerasError}
							onRetry={fetchAllCameras}
							selectedCameraId={selectedCameraId}
							onSelectCamera={(cam) => handleSelectCamera(cam, "general")}
							selectedBuilding={selectedBuilding}
							selectedFloor={selectedFloor}
							isAdmin={isAdmin}
							onOpenCreateCamera={() => handleOpenCreateCameraModal()}
							onToggleCameraStatus={handleToggleSidebarCameraStatus}
							onDeleteCamera={(cam) => setSidebarDeleteTarget(cam)}
							actionLoadingId={cameraActionLoadingId}
						/>
					)}
				</aside>

				<section className="zone-split-map">
					<ErrorBoundary>
						<CampusMapView
							areas={areas}
							selectedAreaId={selectedAreaId}
							cameraCounts={mergedCameraCounts}
							cameraStatsByArea={cameraStatsByArea}
							cameras={cameras}
							selectedCameraId={selectedCameraId}
							onSelectCameraBadge={handleSelectCameraBadge}
							pickingCameraArea={pickingCameraArea}
							onCancelPickCameraArea={() => setPickingCameraArea(false)}
							onAreaPickedForCamera={handleAreaPickedForCamera}
							rightDrawerContent={
								selectedCameraId ? (
									<CameraInspectorDrawer
										cameraId={selectedCameraId}
										initialTab={selectedCameraInitialTab}
										availableAreas={areas}
										isAdmin={isAdmin}
										onClose={handleCloseCameraInspector}
										onCameraMutated={fetchAllCameras}
										onCameraDeleted={() => {
											handleCloseCameraInspector();
											fetchAllCameras();
										}}
										onFocusAreaOnMap={handleFocusAreaOnMap}
										onRequestPickAreaOnMap={() => setPickingCameraArea(true)}
										isPickingAreaOnMap={pickingCameraArea}
										pickedAreaOverride={pickedAreaOverride}
										onConsumePickedAreaOverride={() =>
											setPickedAreaOverride(null)
										}
									/>
								) : null
							}
							isAdmin={isAdmin}
							isFacilityManager={isFacilityManager}
							onSelectArea={(id) => handleSelectArea(id, false)}
							onAreaUpdated={handleAreaUpdatedOnMap}
							buildingsList={availableBuildings}
							selectedBuilding={selectedBuilding}
							selectedFloor={selectedFloor}
							onSyncMapFilter={handleSyncMapFilter}
							isCreatingArea={isAdmin ? isCreatingOnMap : false}
							onStartCreateFromRoom={
								isAdmin ? () => setIsCreatingOnMap(true) : undefined
							}
							onCancelCreateArea={() => setIsCreatingOnMap(false)}
							rebindAreaRequestId={rebindingAreaId}
							onRebindAreaDone={() => setRebindingAreaId(null)}
							onAreaCreated={async (newArea) => {
								setIsCreatingOnMap(false);
								await fetchData(newArea.id);
							}}
							hideRail={!selectedCameraId}
							isLeftSidebarCollapsed={isLeftSidebarCollapsed}
							onToggleLeftSidebar={() =>
								setIsLeftSidebarCollapsed((prev) => !prev)
							}
						/>
					</ErrorBoundary>
				</section>
			</div>

			{/* ============================================================ */}
			{/* 4. MODALS (CREATE, EDIT, DEACTIVATE)                         */}
			{/* ============================================================ */}

			{/* EDIT ZONE MODAL */}
			{editModalOpen && selectedArea && (
				// Popup có form nhập liệu → không đóng khi bấm ra ngoài; đóng bằng X / Huỷ / Esc (Esc bị chặn khi đang gửi).
				<div className="area-modal-backdrop">
					<DialogPanel
						className="area-modal"
						labelledBy="area-modal-title-edit"
						onClose={() => !modalLoading && setEditModalOpen(false)}
					>
						<div className="area-modal__header">
							<div className="area-modal__header-left">
								<div className="area-modal__icon-badge">
									<Pencil size={16} />
								</div>
								<div className="area-modal__header-text">
									<h3
										className="area-modal__title"
										id="area-modal-title-edit"
									>
										Chỉnh sửa khu vực
									</h3>
								</div>
							</div>
							<button
								type="button"
								className="area-modal__close-btn"
								onClick={() => setEditModalOpen(false)}
								aria-label="Đóng"
							>
								<X size={16} />
							</button>
						</div>

						<form onSubmit={handleEditSubmit}>
							<div className="area-modal__body">
								{modalError && (
									<div className="zone-modal-alert">
										<AlertCircle size={15} />
										<span>{modalError}</span>
									</div>
								)}

								<div className="area-form-group">
									<label
										htmlFor="edit-name"
										className="area-form-label"
									>
										Tên khu vực <span className="required">*</span>
									</label>
									<input
										id="edit-name"
										type="text"
										required
										className={`area-form-input ${nameError ? "area-form-input--error" : ""}`}
										value={formData.name}
										onChange={(e) => {
											setFormData({ ...formData, name: e.target.value });
											if (nameError)
												setNameError(validateAreaName(e.target.value));
										}}
										onBlur={(e) =>
											setNameError(validateAreaName(e.target.value))
										}
									/>
									{nameError && (
										<div className="area-form-error">{nameError}</div>
									)}
								</div>

								<div className="area-form-group">
									<label className="area-form-label">
										Loại khu vực <span className="required">*</span>
									</label>
									<div className="area-level-selector">
										{AREA_LEVEL_CARDS.map((card) => {
											const isSelected = formData.areaLevel === card.value;
											const levelClass = AREA_LEVEL_BTN_CLASS[card.value];

											return (
												<button
													key={card.value}
													type="button"
													className={`area-level-btn ${levelClass} ${isSelected ? "is-selected" : ""}`}
													onClick={() =>
														setFormData({ ...formData, areaLevel: card.value })
													}
												>
													<span
														className="area-level-btn__dot"
														style={{ backgroundColor: card.color }}
													/>
													<span className="area-level-btn__name">
														{card.name}
													</span>
													<span className="area-level-btn__sub">
														Cấp {card.level}
													</span>
												</button>
											);
										})}
									</div>
								</div>

								{/* Step 5b (BR-TC-02): đổi loại -> bắt buộc lý do (độ dài theo cấu hình), ghi vào audit CHANGE_TYPE */}
								{formData.originalAreaLevel &&
									formData.areaLevel !== formData.originalAreaLevel && (
										<div className="area-form-group">
											<ReasonTextarea
												ref={editReasonRef}
												id="edit-type-change-reason"
												label="Lý do đổi loại"
												placeholder={`Lý do đổi loại (${reasonLimits.range} ký tự)...`}
												value={formData.reason || ""}
												onChange={(e) => {
													const val = e.target.value;
													setFormData({ ...formData, reason: val });
													if (editReasonError && reasonLimits.isValid(val)) {
														setEditReasonError(null);
													}
												}}
												error={editReasonError}
												hint="Đổi loại sẽ cập nhật cấp truy cập mặc định."
												required
											/>
										</div>
									)}

								<div className="area-form-group">
									<label className="area-form-label">Vị trí 3D</label>
									<div className="area-readonly-location-bar">
										<div className="area-readonly-location-bar__info">
											<strong>
												{formData.building || "—"} · {formData.floor || "—"}
											</strong>
											{(formData.centerLatitude == null ||
												formData.centerLongitude == null) && (
												<span className="area-location-status--missing">
													Chưa gắn block 3D
												</span>
											)}
										</div>
										{isAdmin && formData.id && (
											<button
												type="button"
												className="area-rebind-3d-btn"
												onClick={() => {
													const targetId = formData.id;
													setEditModalOpen(false);
													setSelectedAreaId(targetId);
													setRebindingAreaId(targetId);
												}}
											>
												<Crosshair size={13} />
												{formData.centerLatitude == null ||
												formData.centerLongitude == null
													? "Chọn block 3D"
													: "Đổi block 3D"}
											</button>
										)}
									</div>
								</div>
							</div>

							<div className="area-modal__footer">
								<button
									type="button"
									className="area-btn-modal area-btn-modal--cancel"
									onClick={() => setEditModalOpen(false)}
									disabled={modalLoading}
								>
									Huỷ
								</button>
								<button
									type="submit"
									className="area-btn-modal area-btn-modal--submit"
									disabled={modalLoading}
								>
									{modalLoading ? "Đang lưu..." : "Lưu thay đổi"}
								</button>
							</div>
						</form>
					</DialogPanel>
				</div>
			)}
			{/* BL2: render sau form sửa để nằm trên (cùng z-index 1000) */}
			<AreaTypeChangePreviewModal
				isOpen={Boolean(typePreview)}
				areaName={selectedArea?.name}
				preview={typePreview?.preview}
				confirming={typePreviewConfirming}
				onConfirm={handleConfirmTypeChange}
				onClose={() => setTypePreview(null)}
			/>

			{/* DEACTIVATE ZONE MODAL — Step 6 (BR-AD-01, 08): xem trước + lý do + version */}
			{deactivateModalOpen && deactivateTarget && (
				// Popup có form nhập liệu → không đóng khi bấm ra ngoài; đóng bằng X / Huỷ / Esc (Esc bị chặn khi đang gửi).
				<div className="area-modal-backdrop">
					<DialogPanel
						className="area-modal"
						labelledBy="area-modal-title-deactivate"
						onClose={() => !modalLoading && closeDeactivateModal()}
					>
						<div className="area-modal__header">
							<div className="area-modal__header-left">
								<div className="area-modal__icon-badge area-modal__icon-badge--danger">
									<Trash2 size={16} />
								</div>
								<div className="area-modal__header-text">
									<h3
										className="area-modal__title"
										id="area-modal-title-deactivate"
									>
										Vô hiệu hóa khu vực
									</h3>
								</div>
							</div>
							<button
								type="button"
								className="area-modal__close-btn"
								onClick={closeDeactivateModal}
								aria-label="Đóng"
							>
								<X size={16} />
							</button>
						</div>

						<div className="area-modal__body">
							{modalError && (
								<div className="zone-modal-alert">
									<AlertCircle size={15} />
									<span>{modalError}</span>
								</div>
							)}

							<p className="area-modal__lead">
								Vô hiệu hóa <strong>{deactivateTarget.name}</strong>?
							</p>

							{modalLoading && !dependencies && (
								<div className="zone-page__loading">
									<Loader2
										className="animate-spin"
										size={20}
									/>
									<span>Đang kiểm tra phụ thuộc...</span>
								</div>
							)}

							{dependencies && (dependencies.blockers || []).length > 0 && (
								<div
									className="area-deactivate-blockers"
									role="alert"
								>
									<div className="area-deactivate-blockers__title">
										<AlertTriangle size={15} />
										<span>Chưa thể vô hiệu hóa:</span>
									</div>
									<ul>
										{dependencies.blockers.map((b) => (
											<li key={b.errorCode}>{b.message}</li>
										))}
									</ul>
								</div>
							)}

							{dependencies && (
								<div className="area-dependencies-box">
									<div className="area-dependencies-box__title">
										Tác động đi kèm:
									</div>
									<ul>
										<li>
											Thu hồi nhân sự chỉ định:{" "}
											<strong>{dependencies.apToRevoke ?? 0}</strong>
										</li>
										<li>
											Huỷ đơn truy cập:{" "}
											<strong>{dependencies.requestsToCancel ?? 0}</strong>
										</li>
										<li>
											Huỷ lượt khách chờ duyệt:{" "}
											<strong>{dependencies.guestVisitsToCancel ?? 0}</strong>
										</li>
										<li>
											Thu hồi lượt khách đã duyệt:{" "}
											<strong>{dependencies.guestVisitsToRevoke ?? 0}</strong>
										</li>
									</ul>
								</div>
							)}

							{dependencies && (
								<div className="area-form-group">
									<ReasonTextarea
										ref={deactivateReasonRef}
										id="deactivate-reason"
										label="Lý do vô hiệu hóa"
										placeholder={`Lý do vô hiệu hóa (${reasonLimits.range} ký tự)...`}
										value={deactivateReason}
										onChange={(e) => {
											const val = e.target.value;
											setDeactivateReason(val);
											if (deactivateReasonError && reasonLimits.isValid(val)) {
												setDeactivateReasonError(null);
											}
										}}
										error={deactivateReasonError}
										disabled={modalLoading}
										required
									/>
								</div>
							)}
						</div>

						<div className="area-modal__footer">
							<button
								type="button"
								className="area-btn-modal area-btn-modal--cancel"
								onClick={closeDeactivateModal}
								disabled={modalLoading}
							>
								Huỷ
							</button>
							<button
								type="button"
								className="area-btn-modal area-btn-modal--danger"
								onClick={handleDeactivateSubmit}
								disabled={modalLoading || deactivateBlocked}
							>
								{modalLoading && dependencies ? "Đang xử lý..." : "Vô hiệu hóa"}
							</button>
						</div>
					</DialogPanel>
				</div>
			)}

			{/* RESTORE ZONE MODAL — Step 6 (BR-AD-07) */}
			{restoreTarget && (
				// Popup có form nhập liệu → không đóng khi bấm ra ngoài; đóng bằng X / Huỷ / Esc (Esc bị chặn khi đang gửi).
				<div className="area-modal-backdrop">
					<DialogPanel
						className="area-modal"
						labelledBy="area-modal-title-restore"
						onClose={() => {
							if (!restoreLoading) {
								setRestoreTarget(null);
								setRestoreReasonError(null);
							}
						}}
					>
						<div className="area-modal__header">
							<div className="area-modal__header-left">
								<div className="area-modal__icon-badge">
									<RotateCcw size={16} />
								</div>
								<div className="area-modal__header-text">
									<h3
										className="area-modal__title"
										id="area-modal-title-restore"
									>
										Khôi phục khu vực
									</h3>
								</div>
							</div>
							<button
								type="button"
								className="area-modal__close-btn"
								onClick={() => {
									setRestoreTarget(null);
									setRestoreReasonError(null);
								}}
								disabled={restoreLoading}
								aria-label="Đóng"
							>
								<X size={16} />
							</button>
						</div>

						<div className="area-modal__body">
							{restoreError && (
								<div className="zone-modal-alert">
									<AlertCircle size={15} />
									<span>{restoreError}</span>
								</div>
							)}
							<p className="area-modal__lead">
								Khôi phục <strong>{restoreTarget.name}</strong> (
								{restoreTarget.building || "—"} · {restoreTarget.floor || "—"})?
							</p>
							<div className="area-dependencies-box">
								<div className="area-dependencies-box__title">Lưu ý</div>
								<ul>
									<li>
										Nhân sự, đơn, lượt khách cũ và camera không tự khôi phục.
									</li>
								</ul>
							</div>
							<div className="area-form-group">
								<ReasonTextarea
									ref={restoreReasonRef}
									id="restore-reason"
									label="Lý do khôi phục"
									placeholder={`Lý do khôi phục (${reasonLimits.range} ký tự)...`}
									value={restoreReason}
									onChange={(e) => {
										const val = e.target.value;
										setRestoreReason(val);
										if (restoreReasonError && reasonLimits.isValid(val)) {
											setRestoreReasonError(null);
										}
									}}
									error={restoreReasonError}
									disabled={restoreLoading}
									required
								/>
							</div>
						</div>

						<div className="area-modal__footer">
							<button
								type="button"
								className="area-btn-modal area-btn-modal--cancel"
								onClick={() => {
									setRestoreTarget(null);
									setRestoreReasonError(null);
								}}
								disabled={restoreLoading}
							>
								Huỷ
							</button>
							<button
								type="button"
								className="area-btn-modal area-btn-modal--submit"
								onClick={handleRestoreSubmit}
								disabled={restoreLoading}
							>
								{restoreLoading ? "Đang khôi phục..." : "Khôi phục"}
							</button>
						</div>
					</DialogPanel>
				</div>
			)}

			{/* CAMERAS INSPECTION MODAL (Tra cứu danh sách camera thuộc khu vực) */}
			{camerasModalOpen && camerasModalArea && (
				<div
					className="area-modal-backdrop"
					onClick={() => setCamerasModalOpen(false)}
				>
					<div
						className="area-modal area-modal--medium"
						onClick={(e) => e.stopPropagation()}
					>
						<div className="area-modal__header">
							<div className="area-modal__header-left">
								<div className="area-modal__icon-badge">
									<Cctv size={18} />
								</div>
								<div className="area-modal__header-text">
									<h3 className="area-modal__title">Danh sách Camera</h3>
								</div>
							</div>
							<button
								type="button"
								className="area-modal__close-btn"
								onClick={() => setCamerasModalOpen(false)}
								aria-label="Đóng"
							>
								<X size={16} />
							</button>
						</div>

						<div
							className="area-modal__body"
							style={{ maxHeight: "480px", overflowY: "auto" }}
						>
							{/* Search Bar for filtering cameras in this area */}
							<div className="area-camera-search-box">
								<Search
									size={15}
									className="area-camera-search-icon"
								/>
								<input
									type="text"
									className="area-camera-search-input"
									placeholder="Tìm camera theo tên, mã camera hoặc IP..."
									value={cameraSearchQuery}
									onChange={(e) => setCameraSearchQuery(e.target.value)}
								/>
								{cameraSearchQuery && (
									<button
										type="button"
										className="area-camera-search-clear"
										onClick={() => setCameraSearchQuery("")}
									>
										<X size={13} />
									</button>
								)}
							</div>

							{loadingAreaCameras ? (
								<div
									style={{
										display: "flex",
										alignItems: "center",
										justifyContent: "center",
										padding: "40px 0",
										gap: "10px",
										color: "var(--theme-text-muted)",
									}}
								>
									<Loader2
										size={22}
										className="spin-icon"
									/>
									<span>Đang tải danh sách camera của khu vực...</span>
								</div>
							) : areaCamerasError ? (
								<div className="zone-modal-alert">
									<AlertCircle size={16} />
									<span>{areaCamerasError}</span>
								</div>
							) : areaCamerasList.length === 0 ? (
								<div className="area-camera-empty">
									<VideoOff
										size={40}
										style={{
											color: "var(--theme-text-muted)",
											marginBottom: "10px",
										}}
									/>
									<p
										style={{
											margin: 0,
											fontWeight: 700,
											fontSize: "14px",
											color: "var(--theme-text-primary)",
										}}
									>
										Chưa có camera nào thuộc khu vực này
									</p>
								</div>
							) : (
								<div className="area-camera-list">
									{areaCamerasList
										.filter((cam) => {
											if (!cameraSearchQuery) return true;
											const q = cameraSearchQuery.toLowerCase();
											return (
												(cam.name || "").toLowerCase().includes(q) ||
												(cam.cameraCode || cam.code || "")
													.toLowerCase()
													.includes(q) ||
												(cam.ipAddress || "").toLowerCase().includes(q)
											);
										})
										.map((cam) => {
											const opStatus =
												cam.operationalStatus || cam.status || "ONLINE";
											const isOnline =
												opStatus === "ONLINE" || opStatus === "ACTIVE";

											return (
												<div
													key={cam.id || cam.cameraCode}
													className="area-camera-item"
												>
													<div className="area-camera-item__icon">
														<Cctv size={16} />
													</div>
													<div className="area-camera-item__info">
														<div className="area-camera-item__name">
															{cam.name || cam.cameraCode}
														</div>
														<div className="area-camera-item__code">
															Mã: {cam.cameraCode || cam.code || "—"}
															{cam.ipAddress && ` • IP: ${cam.ipAddress}`}
														</div>
													</div>
													<div className="area-camera-item__right-group">
														<span
															className={`area-camera-status-pill ${isOnline ? "area-camera-status-pill--online" : "area-camera-status-pill--offline"}`}
														>
															{opStatus}
														</span>

														<button
															type="button"
															className="area-camera-detail-btn"
															onClick={() => {
																setCamerasModalOpen(false);
																handleSelectCamera(cam, "surveillance");
															}}
															title="Xem chi tiết camera và cấu hình ROI"
														>
															<span>Xem chi tiết</span>
															<ExternalLink size={13} />
														</button>
													</div>
												</div>
											);
										})}
								</div>
							)}
						</div>

						<div
							className="area-modal__footer"
							style={{
								display: "flex",
								justifyContent: "space-between",
								alignItems: "center",
								flexWrap: "wrap",
								gap: "0.75rem",
							}}
						>
							<div
								style={{
									display: "flex",
									alignItems: "center",
									gap: "0.4rem",
									fontSize: "0.775rem",
									color: "var(--theme-text-muted)",
								}}
							>
								<Info
									size={14}
									style={{ color: "var(--theme-info)", flexShrink: 0 }}
								/>
								<span>
									{isFacilityManager
										? "Danh sách các thiết bị camera giám sát thuộc khu vực này."
										: "Gán, thêm hoặc mở inspector camera trực tiếp trong không gian 3D."}
								</span>
							</div>

							<div
								style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}
							>
								{isAdmin && camerasModalArea?.id && (
									<button
										type="button"
										className="area-camera-modal-mgmt-link"
										onClick={() => {
											const targetAreaId = camerasModalArea.id;
											setCamerasModalOpen(false);
											handleOpenCreateCameraModal(targetAreaId);
										}}
									>
										<Plus size={14} />
										<span>Thêm camera</span>
									</button>
								)}
								<button
									type="button"
									className="area-camera-modal-mgmt-link"
									onClick={() => {
										setCamerasModalOpen(false);
										handleSwitchLeftSidebarMode("CAMERAS");
									}}
								>
									<Video size={14} />
									<span>Mở tab Camera</span>
								</button>
							</div>
						</div>
					</div>
				</div>
			)}

			{/* Area Assigned Personnel Modal (FM & ADMIN) */}
			<AreaAssignedPersonnelModal
				isOpen={assignedPersonnelModalOpen}
				onClose={() => setAssignedPersonnelModalOpen(false)}
				area={assignedPersonnelModalArea}
				isFacilityManager={isFacilityManager}
			/>

			{/* Create Camera Modal in-workspace (ADMIN ONLY) */}
			{isAdmin && (
				<CameraCreateModal
					isOpen={isCreateCameraOpen}
					onClose={() => setIsCreateCameraOpen(false)}
					onSuccess={async (created) => {
						await fetchAllCameras();
						if (created?.id) {
							handleSelectCamera(created, "general");
						}
					}}
					defaultAreaId={createCameraDefaultAreaId}
				/>
			)}

			{/* Sidebar quick-action confirm modal: Decommission Camera */}
			{sidebarDecommissionTarget && (
				<Modal
					isOpen={Boolean(sidebarDecommissionTarget)}
					onClose={() => setSidebarDecommissionTarget(null)}
					title="Ngưng hoạt động camera"
					maxWidth="420px"
				>
					<div className="cam-confirm-body">
						<p>
							Bạn có chắc chắn muốn ngưng hoạt động camera{" "}
							<strong>
								{sidebarDecommissionTarget.name ||
									sidebarDecommissionTarget.cameraCode}
							</strong>
							? Camera sẽ tạm ngừng stream và giám sát AI.
						</p>
						<div className="cam-confirm-actions">
							<Button
								variant="ghost"
								onClick={() => setSidebarDecommissionTarget(null)}
							>
								Hủy
							</Button>
							<Button
								variant="danger"
								disabled={Boolean(cameraActionLoadingId)}
								onClick={handleConfirmSidebarDecommission}
							>
								Ngưng hoạt động
							</Button>
						</div>
					</div>
				</Modal>
			)}

			{/* Sidebar quick-action confirm modal: Delete Camera */}
			{sidebarDeleteTarget && (
				<Modal
					isOpen={Boolean(sidebarDeleteTarget)}
					onClose={() => setSidebarDeleteTarget(null)}
					title="Xóa camera vĩnh viễn"
					maxWidth="420px"
				>
					<div className="cam-confirm-body">
						<p>
							Bạn có chắc chắn muốn xóa camera{" "}
							<strong>
								{sidebarDeleteTarget.name || sidebarDeleteTarget.cameraCode}
							</strong>
							? Hành động này không thể hoàn tác.
						</p>
						<div className="cam-confirm-actions">
							<Button
								variant="ghost"
								onClick={() => setSidebarDeleteTarget(null)}
							>
								Hủy
							</Button>
							<Button
								variant="danger"
								disabled={Boolean(cameraActionLoadingId)}
								onClick={handleConfirmSidebarDelete}
							>
								Xóa camera
							</Button>
						</div>
					</div>
				</Modal>
			)}

			{/* Standalone 3D Floor Plan Digitizer Studio (ADMIN Dev Tool ONLY) */}
			{isAdmin && digitizerStudioOpen && (
				<FloorDigitizerStudio
					isOpen={digitizerStudioOpen}
					onClose={() => setDigitizerStudioOpen(false)}
				/>
			)}
		</div>
	);
}
