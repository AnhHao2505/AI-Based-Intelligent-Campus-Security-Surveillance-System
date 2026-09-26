import { useState, useEffect, useMemo, useCallback, useRef } from "react";
import { useSearchParams, useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import {
	Building2,
	Map as MapIcon,
	List as ListIcon,
	Plus,
	Pencil,
	Trash2,
	Check,
	EyeOff,
	AlertCircle,
	X,
	Loader2,
	Undo2,
	Layers,
	Cctv,
	VideoOff,
	MapPinPlus,
	Search,
	AlertTriangle,
	CheckCircle2,
	ShieldCheck,
	Users,
	Compass,
	Info,
	ExternalLink,
} from "lucide-react";
import AreaAccessRulesModal from "../../components/area/AreaAccessRulesModal";
import AreaAssignedPersonnelModal from "../../components/area/AreaAssignedPersonnelModal";
import AreaMapView from "../../components/area/AreaMapView";
import AreaListView from "../../components/area/AreaListView";
import { getLevelPresets } from "../../services/accessControlService";
import {
	getAreas,
	getDependencies,
	createArea,
	updateArea,
	deactivateArea,
	getFloorPlans,
	saveAreaGeometry,
	deleteAreaGeometry,
	getAreaCameras,
	updateAreaCameras,
} from "../../services/areaService";
import { getBuildings } from "../../services/buildingService";
import {
	AREA_LEVEL_CONFIG,
	getLevelPolygonClass,
	getErrorMessage,
	normalizeAreaName,
	validateAreaName,
} from "../../utils/areaHelpers";
import "../../styles/AreaListPage.css";

const EPS = 0.0005;
const round6 = (n) => Math.round(n * 1e6) / 1e6;

const GEOMETRY_ERROR_MESSAGES = {
	ERR_AREA_002: "Không tìm thấy khu vực được yêu cầu.",
	ERR_AREA_003: "Cấp độ an ninh không hợp lệ hoặc đã ngừng sử dụng.",
	ERR_AREA_004:
		"Mã khu vực chỉ gồm chữ in hoa, số và dấu gạch ngang, dài 3–50 ký tự.",
	ERR_AREA_005: "Tên khu vực bắt buộc, tối đa 150 ký tự.",
	ERR_AREA_006: "Toạ độ bản đồ phải có đủ cả X và Y.",
	ERR_AREA_007: "Không được thay đổi mã khu vực sau khi tạo.",
	ERR_AREA_008: "Lý do giải trình không được để trống khi hạ cấp an ninh.",
	ERR_AREA_009: "Không thể vô hiệu hóa: còn camera đang gán.",
	ERR_AREA_010: "Không thể vô hiệu hóa: còn quyền truy cập.",
	ERR_AREA_011: "Hình đa giác phải có ít nhất 3 đỉnh.",
	ERR_AREA_012: "Toạ độ các đỉnh phải nằm trong khoảng chuẩn hoá [0.0, 1.0].",
	ERR_AREA_013: "Hình bị chồng lấn với khu vực khác trên cùng tầng.",
	ERR_AREA_014:
		"Không thể thay đổi toà nhà hoặc tầng khi khu vực đang có toạ độ đa giác.",
	ERR_AREA_015: "Khu vực này chưa có thông tin toà nhà và tầng.",
	ERR_AREA_016: "Hình phải có ít nhất 3 đỉnh phân biệt (không trùng nhau).",
	ERR_AREA_017: "Khu vực đã ngừng hoạt động hoặc đã bị xoá.",
};

const AREA_LEVEL_CARDS = [
	{
		value: "PUBLIC",
		name: AREA_LEVEL_CONFIG.PUBLIC.name,
		color: AREA_LEVEL_CONFIG.PUBLIC.color,
	},
	{
		value: "INTERNAL_CONFIDENTIAL",
		name: AREA_LEVEL_CONFIG.INTERNAL_CONFIDENTIAL.name,
		color: AREA_LEVEL_CONFIG.INTERNAL_CONFIDENTIAL.color,
	},
	{
		value: "CONFIDENTIAL_CONTACT_REQUIRED",
		name: AREA_LEVEL_CONFIG.CONFIDENTIAL_CONTACT_REQUIRED.name,
		color: AREA_LEVEL_CONFIG.CONFIDENTIAL_CONTACT_REQUIRED.color,
	},
	{
		value: "HIGHLY_CONFIDENTIAL",
		name: AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.name,
		color: AREA_LEVEL_CONFIG.HIGHLY_CONFIDENTIAL.color,
	},
];

export default function AreaListPage() {
	const { user } = useAuth();
	const isAdmin = user?.role === "ADMIN";
	const isFacilityManager = user?.role === "FACILITY_MANAGER";

	const [searchParams, setSearchParams] = useSearchParams();
	const navigate = useNavigate();
	const rawView = searchParams.get("view");
	const viewMode = rawView === "map" ? "map" : "list";

	const handleToggleView = (mode) => {
		setSearchParams((prev) => {
			const next = new URLSearchParams(prev);
			next.set("view", mode);
			return next;
		});
	};

	// Data states
	const [areas, setAreas] = useState([]);
	const [floorPlans, setFloorPlans] = useState([]);
	const [buildingsList, setBuildingsList] = useState([]);
	const [loading, setLoading] = useState(true);
	const [pageError, setPageError] = useState(null);
	const [imageError, setImageError] = useState(false);

	// Filters
	const [selectedBuilding, setSelectedBuilding] = useState("");
	const [selectedFloor, setSelectedFloor] = useState("");
	const [selectedAreaId, setSelectedAreaId] = useState(null);
	const [cameraCounts, setCameraCounts] = useState({});

	// Drawing states
	const [drawingAreaId, setDrawingAreaId] = useState(null);
	const [draftVertices, setDraftVertices] = useState([]);
	const [drawError, setDrawError] = useState(null);
	const [savingGeometry, setSavingGeometry] = useState(false);
	const [confirmDeleteId, setConfirmDeleteId] = useState(null);
	const [deletingGeometryId, setDeletingGeometryId] = useState(null);

	// Level Presets (ADMIN / FM)
	const [levelPresets, setLevelPresets] = useState(null);

	useEffect(() => {
		getLevelPresets()
			.then((presets) => {
				if (Array.isArray(presets)) {
					const map = {};
					presets.forEach((p) => {
						map[p.areaLevel] = p;
					});
					setLevelPresets(map);
				}
			})
			.catch((err) => {
				console.warn("Could not load level presets:", err);
				setLevelPresets(null);
			});
	}, []);

	const getPresetSubtitle = (areaLevelValue) => {
		const preset = levelPresets?.[areaLevelValue];
		if (!preset) return null;
		return `Mặc định: Level ${preset.areaAccessLevel} · Chỉ định: ${preset.explicitAuthorizationRequired ? "có" : "không"}`;
	};

	// Modal states
	const [createModalOpen, setCreateModalOpen] = useState(false);
	const [editModalOpen, setEditModalOpen] = useState(false);
	const [deactivateModalOpen, setDeactivateModalOpen] = useState(false);
	const [modalLoading, setModalLoading] = useState(false);
	const [modalError, setModalError] = useState(null);
	const [nameError, setNameError] = useState(null);
	const [dependencies, setDependencies] = useState(null);

	// Camera list modal states
	const [camerasModalOpen, setCamerasModalOpen] = useState(false);
	const [camerasModalArea, setCamerasModalArea] = useState(null);
	const [areaCamerasList, setAreaCamerasList] = useState([]);
	const [loadingAreaCameras, setLoadingAreaCameras] = useState(false);
	const [areaCamerasError, setAreaCamerasError] = useState(null);

	// Search & camera inspection modal states
	const [cameraSearchQuery, setCameraSearchQuery] = useState("");
	const [cameraNotification, setCameraNotification] = useState(null);

	// Access rules modal states (Facility Manager)
	const [accessRulesModalOpen, setAccessRulesModalOpen] = useState(false);
	const [accessRulesModalArea, setAccessRulesModalArea] = useState(null);

	const handleOpenAccessRulesModal = useCallback((area) => {
		if (!area) return;
		setAccessRulesModalArea(area);
		setAccessRulesModalOpen(true);
	}, []);

	const handleAccessRulesSuccess = useCallback((updatedArea) => {
		if (!updatedArea) return;
		setAreas((prev) =>
			prev.map((a) => (a.id === updatedArea.id ? { ...a, ...updatedArea } : a)),
		);
		setAccessRulesModalArea((prev) =>
			prev?.id === updatedArea.id ? { ...prev, ...updatedArea } : prev,
		);
	}, []);

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
		setCameraNotification(null);

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
		building: "FPT_AROUND",
		floor: "G",
		floorId: null,
		reason: "",
	});

	const rowRefs = useRef({});

	// Fetch all data
	const fetchData = useCallback(async (keepSelectedId = null) => {
		setLoading(true);
		setPageError(null);
		setImageError(false);
		try {
			const [areasRes, plansRes, buildingsRes] = await Promise.all([
				getAreas({ size: 100, isActive: true }),
				getFloorPlans().catch(() => []),
				getBuildings().catch(() => []),
			]);

			const areaList = areasRes?.content || areasRes || [];
			setAreas(Array.isArray(areaList) ? areaList : []);

			const activePlans = (plansRes || []).filter(
				(fp) => fp.isActive !== false,
			);
			setFloorPlans(activePlans);
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

	const handleRemoveCameraFromArea = useCallback(
		async (camId) => {
			if (!camerasModalArea) return;
			setCameraNotification(null);

			try {
				const newIds = areaCamerasList
					.filter((c) => c.id !== camId)
					.map((c) => c.id);
				await updateAreaCameras(camerasModalArea.id, newIds);

				setCameraNotification({
					type: "success",
					message: "Đã hủy gán camera khỏi khu vực.",
				});

				const res = await getAreaCameras(camerasModalArea.id);
				const updatedList = res?.cameras || (Array.isArray(res) ? res : []);
				setAreaCamerasList(updatedList);
				// Update card count immediately
				setCameraCounts((prev) => ({
					...prev,
					[camerasModalArea.id]: updatedList.length,
				}));
				fetchData();
			} catch (err) {
				console.error("Error removing camera from area:", err);
				setCameraNotification({
					type: "error",
					message: getErrorMessage(err) || "Lỗi khi hủy gán camera.",
				});
			}
		},
		[camerasModalArea, areaCamerasList, fetchData],
	);

	useEffect(() => {
		fetchData();
	}, [fetchData]);

	// Derived available buildings
	const availableBuildings = useMemo(() => {
		if (buildingsList.length > 0) {
			return buildingsList.map((b) => ({
				code: b.code,
				name: b.name,
				floors: b.floors || [],
			}));
		}
		const bSet = new Set();
		floorPlans.forEach((fp) => {
			if (fp.building) bSet.add(fp.building);
		});
		areas.forEach((a) => {
			if (a.building) bSet.add(a.building);
		});
		const list = Array.from(bSet).sort();
		return (list.length > 0 ? list : ["FPT_AROUND"]).map((code) => ({
			code,
			name: code,
			floors: [],
		}));
	}, [buildingsList, floorPlans, areas]);

	// Default building initialization
	useEffect(() => {
		if (!selectedBuilding && availableBuildings.length > 0) {
			const defaultB =
				availableBuildings.find((b) => b.code === "FPT_AROUND") ||
				availableBuildings[0];
			setSelectedBuilding(defaultB.code);
		}
	}, [availableBuildings, selectedBuilding]);

	// Derived available floors for current building
	const availableFloors = useMemo(() => {
		const currentBuildingObj = availableBuildings.find(
			(b) => b.code === selectedBuilding,
		);
		if (
			currentBuildingObj &&
			currentBuildingObj.floors &&
			currentBuildingObj.floors.length > 0
		) {
			return currentBuildingObj.floors.map((f) => ({
				code: f.floorCode,
				name: f.name || `Tầng ${f.floorCode}`,
				floorId: f.id,
			}));
		}
		const fSet = new Set();
		floorPlans
			.filter((fp) => fp.building === selectedBuilding)
			.forEach((fp) => {
				if (fp.floor) fSet.add(fp.floor);
			});
		areas
			.filter((a) => a.building === selectedBuilding)
			.forEach((a) => {
				if (a.floor) fSet.add(a.floor);
			});

		return Array.from(fSet)
			.sort((a, b) => {
				const isNumA = /^\d+$/.test(a);
				const isNumB = /^\d+$/.test(b);
				if (!isNumA && isNumB) return -1;
				if (isNumA && !isNumB) return 1;
				if (!isNumA && !isNumB) return a.localeCompare(b);
				return parseInt(a, 10) - parseInt(b, 10);
			})
			.map((fl) => ({
				code: fl,
				name: `Tầng ${fl}`,
				floorId: null,
			}));
	}, [availableBuildings, floorPlans, areas, selectedBuilding]);

	// Default floor initialization
	useEffect(() => {
		if (
			availableFloors.length > 0 &&
			!availableFloors.some((f) => f.code === selectedFloor)
		) {
			setSelectedFloor(availableFloors[0].code);
		}
	}, [availableFloors, selectedFloor]);

	// Modal floors for current form building
	const modalFloors = useMemo(() => {
		const bObj = availableBuildings.find((b) => b.code === formData.building);
		if (bObj && bObj.floors && bObj.floors.length > 0) {
			return bObj.floors;
		}
		return [{ floorCode: "G", name: "Tầng Trệt" }];
	}, [availableBuildings, formData.building]);

	// Current floor plan matching building & floor
	const selectedPlan = useMemo(() => {
		return (
			floorPlans.find(
				(fp) => fp.building === selectedBuilding && fp.floor === selectedFloor,
			) || null
		);
	}, [floorPlans, selectedBuilding, selectedFloor]);

	// Filtered areas on current building and floor (for list view)
	const floorAreas = useMemo(() => {
		return areas.filter(
			(a) => a.building === selectedBuilding && a.floor === selectedFloor,
		);
	}, [areas, selectedBuilding, selectedFloor]);

	// Selected area object
	const selectedArea = useMemo(() => {
		return areas.find((a) => a.id === selectedAreaId) || null;
	}, [areas, selectedAreaId]);

	// Automatically pre-fetch camera counts for all loaded areas so count is ready before clicking any card
	useEffect(() => {
		if (!areas || areas.length === 0) return;
		let active = true;

		const missingAreas = areas.filter((a) => cameraCounts[a.id] === undefined);
		if (missingAreas.length === 0) return;

		Promise.all(
			missingAreas.map(async (a) => {
				try {
					const res = await getAreaCameras(a.id);
					const count =
						res?.cameras?.length ?? (Array.isArray(res) ? res.length : 0);
					return { id: a.id, count };
				} catch {
					return { id: a.id, count: 0 };
				}
			}),
		).then((results) => {
			if (active) {
				const countsMap = {};
				results.forEach(({ id, count }) => {
					countsMap[id] = count;
				});
				setCameraCounts((prev) => ({ ...prev, ...countsMap }));
			}
		});

		return () => {
			active = false;
		};
	}, [areas, cameraCounts]);

	// Handle switching building or floor
	const handleSelectBuilding = (b) => {
		setSelectedBuilding(b);
		setSelectedAreaId(null);
		if (drawingAreaId !== null) cancelDrawing();
		setConfirmDeleteId(null);
		setImageError(false);
	};

	const handleSelectFloor = (fl) => {
		setSelectedFloor(fl);
		setSelectedAreaId(null);
		if (drawingAreaId !== null) cancelDrawing();
		setConfirmDeleteId(null);
		setImageError(false);
	};

	// Bidirectional selection
	const handleSelectArea = (areaId, fromMap = false) => {
		setSelectedAreaId(areaId);
		setConfirmDeleteId(null);
		if (fromMap && rowRefs.current[areaId]) {
			rowRefs.current[areaId].scrollIntoView({
				behavior: "smooth",
				block: "nearest",
			});
		}
	};

	// Polygon drawing logic
	const startDrawing = (areaId) => {
		setDrawingAreaId(areaId);
		setDraftVertices([]);
		setDrawError(null);
		setConfirmDeleteId(null);
	};

	const cancelDrawing = () => {
		setDrawingAreaId(null);
		setDraftVertices([]);
		setDrawError(null);
	};

	const handleUndoVertex = () => {
		setDraftVertices((prev) => prev.slice(0, -1));
	};

	const finishDrawing = async () => {
		if (savingGeometry || drawingAreaId === null || draftVertices.length < 3)
			return;
		setSavingGeometry(true);
		setDrawError(null);
		try {
			await saveAreaGeometry(drawingAreaId, draftVertices);
			const targetId = drawingAreaId;
			cancelDrawing();
			await fetchData(targetId);
		} catch (err) {
			const msg =
				GEOMETRY_ERROR_MESSAGES[err.code] ||
				err.message ||
				"Không lưu được hình. Vui lòng thử lại.";
			setDrawError(msg);
		} finally {
			setSavingGeometry(false);
		}
	};

	const handleDeleteGeometry = async (areaId) => {
		if (deletingGeometryId !== null) return;
		setDeletingGeometryId(areaId);
		try {
			await deleteAreaGeometry(areaId);
			setConfirmDeleteId(null);
			await fetchData(areaId);
		} catch (err) {
			console.error("Failed to delete area geometry:", err);
			setPageError("Không xoá được hình đa giác. Vui lòng thử lại.");
		} finally {
			setDeletingGeometryId(null);
		}
	};

	const handleSvgClick = (event) => {
		if (savingGeometry || drawingAreaId === null || !selectedPlan) return;
		const svg = event.currentTarget;
		const ctm = svg.getScreenCTM();
		if (!ctm) return;
		const pt = svg.createSVGPoint();
		pt.x = event.clientX;
		pt.y = event.clientY;
		const local = pt.matrixTransform(ctm.inverse());
		const nx = local.x / selectedPlan.originalWidth;
		const ny = local.y / selectedPlan.originalHeight;
		if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return;
		const roundedX = round6(nx);
		const roundedY = round6(ny);
		const isDuplicate = draftVertices.some(
			(v) => Math.abs(v.x - roundedX) < EPS && Math.abs(v.y - roundedY) < EPS,
		);
		if (isDuplicate) return;
		setDraftVertices((prev) => [...prev, { x: roundedX, y: roundedY }]);
	};

	// Polygons for current floor plan
	const mapPolygons = useMemo(() => {
		if (!selectedPlan) return [];
		return areas.filter(
			(a) =>
				a.building === selectedBuilding &&
				a.floor === selectedFloor &&
				a.geometry &&
				Array.isArray(a.geometry.vertices) &&
				a.geometry.vertices.length >= 3,
		);
	}, [areas, selectedBuilding, selectedFloor, selectedPlan]);

	// Sorted list for Card 1 (areas in current floor first, then others dimmed)
	const sortedAreasForRail = useMemo(() => {
		return [...areas].sort((a, b) => {
			const aInScope =
				a.building === selectedBuilding && a.floor === selectedFloor;
			const bInScope =
				b.building === selectedBuilding && b.floor === selectedFloor;
			if (aInScope && !bInScope) return -1;
			if (!aInScope && bInScope) return 1;
			return (a.name || "").localeCompare(b.name || "");
		});
	}, [areas, selectedBuilding, selectedFloor]);

	const totalAreasCount = areas.length;
	const noGeometryCount = useMemo(() => {
		return areas.filter(
			(a) =>
				!a.hasGeometry &&
				!(
					a.geometry &&
					Array.isArray(a.geometry.vertices) &&
					a.geometry.vertices.length >= 3
				),
		).length;
	}, [areas]);

	// Modal Handlers
	const handleOpenCreateModal = () => {
		const currentB =
			selectedBuilding || availableBuildings[0]?.code || "FPT_AROUND";
		const bObj = availableBuildings.find((b) => b.code === currentB);
		const floorsForB = bObj?.floors || [];
		const currentF = selectedFloor || floorsForB[0]?.floorCode || "G";
		const currentFloorId =
			floorsForB.find((f) => f.floorCode === currentF)?.id || null;

		setFormData({
			name: "",
			areaLevel: "PUBLIC",
			building: currentB,
			floor: currentF,
			floorId: currentFloorId,
			reason: "",
		});
		setModalError(null);
		setNameError(null);
		setCreateModalOpen(true);
	};

	const handleCreateSubmit = async (e) => {
		e.preventDefault();
		const nameValidationError = validateAreaName(formData.name);
		if (nameValidationError) {
			setNameError(nameValidationError);
			return;
		}
		setModalError(null);
		setModalLoading(true);
		try {
			const payload = {
				name: normalizeAreaName(formData.name),
				areaLevel: formData.areaLevel,
				building: formData.building ? formData.building.trim() : null,
				floor: formData.floor ? formData.floor.trim() : null,
				floorId: formData.floorId || null,
			};

			const created = await createArea(payload);
			setCreateModalOpen(false);
			await fetchData(created.id);
		} catch (err) {
			console.error("Create area failed:", err);
			setModalError(getErrorMessage(err));
		} finally {
			setModalLoading(false);
		}
	};

	const handleOpenEditModal = (targetArea = null) => {
		const areaToEdit = targetArea || selectedArea;
		if (!areaToEdit) return;
		setSelectedAreaId(areaToEdit.id);
		const bCode = areaToEdit.building || "FPT_AROUND";
		const bObj = availableBuildings.find((b) => b.code === bCode);
		const flCode = areaToEdit.floor || "G";
		const flObj = bObj?.floors?.find((f) => f.floorCode === flCode);

		setFormData({
			id: areaToEdit.id,

			name: areaToEdit.name,
			areaLevel:
				areaToEdit.areaLevel ||
				(typeof areaToEdit.level === "object"
					? areaToEdit.level?.code
					: areaToEdit.level) ||
				"PUBLIC",
			building: bCode,
			floor: flCode,
			floorId:
				areaToEdit.floorEntity?.id || areaToEdit.floorId || flObj?.id || null,
			reason: "",
		});
		setModalError(null);
		setNameError(null);
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

		setModalLoading(true);
		try {
			const payload = {
				name: normalizeAreaName(formData.name),
				areaLevel: formData.areaLevel,
				building: formData.building ? formData.building.trim() : null,
				floor: formData.floor ? formData.floor.trim() : null,
				floorId: formData.floorId || null,
			};

			const updated = await updateArea(targetId, payload);
			setEditModalOpen(false);
			await fetchData(updated.id);
		} catch (err) {
			console.error("Update area failed:", err);
			setModalError(getErrorMessage(err));
		} finally {
			setModalLoading(false);
		}
	};

	const handleOpenDeactivateModal = async (targetArea = null) => {
		const areaToDeactivate = targetArea || selectedArea;
		if (!areaToDeactivate) return;
		setSelectedAreaId(areaToDeactivate.id);
		setFormData((prev) => ({ ...prev, id: areaToDeactivate.id }));
		setModalError(null);
		setDependencies(null);
		setDeactivateModalOpen(true);
		setModalLoading(true);

		try {
			const depRes = await getDependencies(areaToDeactivate.id);
			setDependencies(depRes);
		} catch (err) {
			console.error("Failed to get area dependencies:", err);
			setModalError(getErrorMessage(err));
		} finally {
			setModalLoading(false);
		}
	};

	const handleDeactivateSubmit = async () => {
		const targetId = formData.id || selectedArea?.id;
		if (!targetId) return;
		setModalLoading(true);
		setModalError(null);
		try {
			await deactivateArea(targetId);
			setDeactivateModalOpen(false);
			setSelectedAreaId(null);
			await fetchData();
		} catch (err) {
			console.error("Deactivate area failed:", err);
			setModalError(getErrorMessage(err));
		} finally {
			setModalLoading(false);
		}
	};

	const isSelectedAreaInCurrentScope =
		selectedArea &&
		selectedArea.building === selectedBuilding &&
		selectedArea.floor === selectedFloor;

	const selectedAreaHasGeometry =
		selectedArea &&
		(selectedArea.hasGeometry ||
			(selectedArea.geometry &&
				Array.isArray(selectedArea.geometry.vertices) &&
				selectedArea.geometry.vertices.length >= 3));

	return (
		<div className="zone-page">
			{/* Inline Page Error Banner */}
			{pageError && (
				<div className="zone-alert zone-alert--error">
					<AlertCircle size={18} />
					<span>{pageError}</span>
					<button
						type="button"
						className="zone-alert__close"
						onClick={() => setPageError(null)}
						title="Đóng thông báo"
					>
						<X size={16} />
					</button>
				</div>
			)}

			{/* Role Header Banner: Strict separation between Admin Configure vs FM Manage */}
			<div
				style={{
					display: "flex",
					justifyContent: "space-between",
					alignItems: "center",
					marginBottom: "12px",
					padding: "10px 16px",
					background: "var(--theme-bg-surface)",
					border: "1px solid var(--theme-border)",
					borderRadius: "10px",
				}}
			>
				<div>
					<div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
						<MapPinPlus
							size={18}
							color="var(--theme-primary)"
						/>
						<h2
							style={{
								fontSize: "16px",
								fontWeight: 700,
								margin: 0,
								color: "var(--theme-text-primary)",
							}}
						>
							{isAdmin
								? "Cấu hình Vùng hạn chế"
								: "Quản lý Vùng an ninh & Quyền truy cập"}
						</h2>
					</div>
					<p
						style={{
							margin: "2px 0 0 0",
							fontSize: "12px",
							color: "var(--theme-text-muted)",
						}}
					>
						{isAdmin
							? "Thêm mới phân khu, cấu hình cấp độ bảo mật, liên kết camera và thiết lập hạ tầng an ninh."
							: "Quản lý danh sách nhân sự được chỉ định, tra cứu phân quyền và vận hành phân khu."}
					</p>
				</div>

				<a
					href="/admin/map"
					style={{
						display: "inline-flex",
						alignItems: "center",
						gap: "6px",
						padding: "6px 14px",
						fontSize: "12.5px",
						fontWeight: 500,
						background: "var(--theme-primary-light)",
						border: "1px solid var(--theme-primary-border)",
						borderRadius: "6px",
						color: "var(--theme-primary)",
						textDecoration: "none",
					}}
				>
					<Compass size={15} />
					<span>Xem trên Bản đồ An ninh</span>
				</a>
			</div>

			{/* ============================================================ */}
			{/* 1. TOOLBAR: Building, Floor tabs, Spacer, Toggle, Add button */}
			{/* ============================================================ */}
			<div className="zone-toolbar">
				{/* Building Selector */}
				<div className="zone-toolbar__building">
					<Building2
						size={16}
						className="zone-toolbar__building-icon"
					/>
					<select
						className="zone-toolbar__building-select"
						value={selectedBuilding}
						onChange={(e) => handleSelectBuilding(e.target.value)}
						title="Chọn toà nhà"
					>
						{availableBuildings.map((b) => (
							<option
								key={b.code}
								value={b.code}
							>
								{b.name}
							</option>
						))}
					</select>
				</div>

				{/* Floor Tabs */}
				<div className="zone-toolbar__tabs">
					{availableFloors.map((fl) => {
						const isActive = selectedFloor === fl.code;
						return (
							<button
								key={fl.code}
								type="button"
								className={`zone-toolbar__tab ${isActive ? "zone-toolbar__tab--active" : ""}`}
								onClick={() => handleSelectFloor(fl.code)}
							>
								{fl.name}
							</button>
						);
					})}
				</div>

				<div className="zone-toolbar__spacer" />

				{/* View Toggle + Add Area Button Group */}
				<div className="zone-toolbar__actions">
					<div className="zone-view-toggle">
						<button
							type="button"
							className={`zone-view-toggle__btn ${viewMode === "list" ? "zone-view-toggle__btn--active" : ""}`}
							onClick={() => handleToggleView("list")}
							title="Danh sách phân khu an ninh"
						>
							<ListIcon size={15} />
							<span>Danh sách</span>
						</button>
						<button
							type="button"
							className={`zone-view-toggle__btn ${viewMode === "map" ? "zone-view-toggle__btn--active" : ""}`}
							onClick={() => handleToggleView("map")}
							title="Sơ đồ mặt bằng chi tiết các tầng"
						>
							<MapIcon size={15} />
							<span>Sơ đồ tầng</span>
						</button>
					</div>

					{isAdmin && (
						<button
							type="button"
							className="zone-toolbar__add-btn"
							onClick={handleOpenCreateModal}
						>
							<Plus
								size={16}
								strokeWidth={2.5}
							/>
							<span>Thêm vùng</span>
						</button>
					)}
				</div>
			</div>

			{/* Loading state */}
			{loading && (
				<div className="zone-page__loading">
					<Loader2
						className="animate-spin"
						size={32}
					/>
					<span>Đang nạp dữ liệu khu vực...</span>
				</div>
			)}

			{/* ============================================================ */}
			{/* 2. MAIN CONTENT (MAP VIEW OR LIST VIEW)                      */}
			{/* ============================================================ */}
			{!loading && viewMode === "map" && (
				<AreaMapView
					areas={areas}
					selectedBuilding={selectedBuilding}
					selectedFloor={selectedFloor}
					selectedPlan={selectedPlan}
					imageError={imageError}
					setImageError={setImageError}
					drawingAreaId={drawingAreaId}
					draftVertices={draftVertices}
					savingGeometry={savingGeometry}
					drawError={drawError}
					setDrawError={setDrawError}
					mapPolygons={mapPolygons}
					selectedAreaId={selectedAreaId}
					selectedArea={selectedArea}
					cameraCounts={cameraCounts}
					sortedAreasForRail={sortedAreasForRail}
					totalAreasCount={totalAreasCount}
					noGeometryCount={noGeometryCount}
					confirmDeleteId={confirmDeleteId}
					deletingGeometryId={deletingGeometryId}
					isFacilityManager={isFacilityManager}
					isAdmin={isAdmin}
					isSelectedAreaInCurrentScope={isSelectedAreaInCurrentScope}
					selectedAreaHasGeometry={selectedAreaHasGeometry}
					rowRefs={rowRefs}
					onSelectArea={handleSelectArea}
					onUndoVertex={handleUndoVertex}
					onFinishDrawing={finishDrawing}
					onCancelDrawing={cancelDrawing}
					onSvgClick={handleSvgClick}
					onToggleView={handleToggleView}
					onStartDrawing={startDrawing}
					onDeleteGeometry={handleDeleteGeometry}
					setConfirmDeleteId={setConfirmDeleteId}
					onOpenAssignedPersonnelModal={handleOpenAssignedPersonnelModal}
					onOpenAccessRulesModal={handleOpenAccessRulesModal}
					onOpenEditModal={handleOpenEditModal}
					getLevelPolygonClass={getLevelPolygonClass}
					levelPresets={levelPresets}
				/>
			)}

			{!loading && viewMode === "list" && (
				<AreaListView
					floorAreas={floorAreas}
					selectedFloor={selectedFloor}
					selectedBuilding={selectedBuilding}
					selectedAreaId={selectedAreaId}
					cameraCounts={cameraCounts}
					isAdmin={isAdmin}
					isFacilityManager={isFacilityManager}
					levelPresets={levelPresets}
					onSelectArea={(id) => handleSelectArea(id, false)}
					onOpenCreateModal={handleOpenCreateModal}
					onOpenCamerasModal={handleOpenCamerasModal}
					onOpenAccessRulesModal={handleOpenAccessRulesModal}
					onOpenAssignedPersonnelModal={handleOpenAssignedPersonnelModal}
					onOpenEditModal={handleOpenEditModal}
					onOpenDeactivateModal={handleOpenDeactivateModal}
				/>
			)}

			{/* ============================================================ */}
			{/* 4. MODALS (CREATE, EDIT, DEACTIVATE)                         */}
			{/* ============================================================ */}

			{/* CREATE ZONE MODAL */}
			{createModalOpen && (
				<div
					className="area-modal-backdrop"
					onClick={() => setCreateModalOpen(false)}
				>
					<div
						className="area-modal"
						onClick={(e) => e.stopPropagation()}
					>
						<div className="area-modal__header">
							<div className="area-modal__header-left">
								<div className="area-modal__icon-badge">
									<MapPinPlus size={16} />
								</div>
								<div className="area-modal__header-text">
									<h3 className="area-modal__title">Thêm vùng mới</h3>
									<p className="area-modal__subtitle">
										Tạo khu vực giám sát trong toà nhà
									</p>
								</div>
							</div>
							<button
								type="button"
								className="area-modal__close-btn"
								onClick={() => setCreateModalOpen(false)}
								aria-label="Đóng"
							>
								<X size={16} />
							</button>
						</div>

						<form onSubmit={handleCreateSubmit}>
							<div className="area-modal__body">
								{modalError && (
									<div className="zone-modal-alert">
										<AlertCircle size={15} />
										<span>{modalError}</span>
									</div>
								)}

								{/* 3. Tên khu vực (bắt buộc) */}
								<div className="area-form-group">
									<label
										htmlFor="create-name"
										className="area-form-label"
									>
										Tên khu vực <span className="required">*</span>
									</label>
									<input
										id="create-name"
										type="text"
										required
										className={`area-form-input ${nameError ? "area-form-input--error" : ""}`}
										placeholder="Cổng chính toà nhà"
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

								{/* 3c. Cấp độ an ninh (bắt buộc) - 3 thẻ chọn */}
								<div className="area-form-group">
									<label className="area-form-label">
										Cấp độ an ninh <span className="required">*</span>
									</label>
									<div className="area-level-selector">
										{AREA_LEVEL_CARDS.map((card) => {
											const isSelected = formData.areaLevel === card.value;
											const levelClass =
												card.value === "PUBLIC"
													? "area-level-btn--public"
													: card.value === "INTERNAL_CONFIDENTIAL"
														? "area-level-btn--internal"
														: card.value === "CONFIDENTIAL_CONTACT_REQUIRED"
															? "area-level-btn--contact"
															: "area-level-btn--private";
											const subText = getPresetSubtitle(card.value);

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
													{subText && (
														<span className="area-level-btn__sub">
															{subText}
														</span>
													)}
												</button>
											);
										})}
									</div>
								</div>

								{/* 3d. Toà nhà và Tầng - phân cấp chuẩn */}
								<div className="area-form-row">
									<div className="area-form-group">
										<label
											htmlFor="create-building"
											className="area-form-label"
										>
											Toà nhà / Phân khu <span className="required">*</span>
										</label>
										<select
											id="create-building"
											className="area-form-input"
											value={formData.building}
											onChange={(e) => {
												const newB = e.target.value;
												const bObj = availableBuildings.find(
													(b) => b.code === newB,
												);
												const firstFloor = bObj?.floors?.[0];
												setFormData({
													...formData,
													building: newB,
													floor: firstFloor ? firstFloor.floorCode : "G",
													floorId: firstFloor ? firstFloor.id : null,
												});
											}}
										>
											{availableBuildings.map((b) => (
												<option
													key={b.code}
													value={b.code}
												>
													{b.name} ({b.code})
												</option>
											))}
										</select>
									</div>

									<div className="area-form-group">
										<label
											htmlFor="create-floor"
											className="area-form-label"
										>
											Tầng & Sơ đồ <span className="required">*</span>
										</label>
										<select
											id="create-floor"
											className="area-form-input"
											value={formData.floor}
											onChange={(e) => {
												const newF = e.target.value;
												const flObj = modalFloors.find(
													(f) => f.floorCode === newF,
												);
												setFormData({
													...formData,
													floor: newF,
													floorId: flObj ? flObj.id : null,
												});
											}}
										>
											{modalFloors.map((fl) => (
												<option
													key={fl.floorCode}
													value={fl.floorCode}
												>
													{fl.name}
												</option>
											))}
										</select>
									</div>
								</div>
							</div>

							{/* 4. Phần chân: 2 nút chia đôi chiều rộng, gap 9px */}
							<div className="area-modal__footer">
								<button
									type="button"
									className="area-btn-modal area-btn-modal--cancel"
									onClick={() => setCreateModalOpen(false)}
									disabled={modalLoading}
								>
									Huỷ
								</button>
								<button
									type="submit"
									className="area-btn-modal area-btn-modal--submit"
									disabled={modalLoading}
								>
									{modalLoading ? "Đang tạo..." : "Tạo khu vực"}
								</button>
							</div>
						</form>
					</div>
				</div>
			)}

			{/* EDIT ZONE MODAL */}
			{editModalOpen && selectedArea && (
				<div
					className="area-modal-backdrop"
					onClick={() => setEditModalOpen(false)}
				>
					<div
						className="area-modal"
						onClick={(e) => e.stopPropagation()}
					>
						<div className="area-modal__header">
							<div className="area-modal__header-left">
								<div className="area-modal__icon-badge">
									<Pencil size={16} />
								</div>
								<div className="area-modal__header-text">
									<h3 className="area-modal__title">Chỉnh sửa khu vực</h3>
									<p className="area-modal__subtitle">
										Cập nhật thông tin: {selectedArea.name}
									</p>
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
										Cấp độ an ninh <span className="required">*</span>
									</label>
									<div className="area-level-selector">
										{AREA_LEVEL_CARDS.map((card) => {
											const isSelected = formData.areaLevel === card.value;
											const levelClass =
												card.value === "PUBLIC"
													? "area-level-btn--public"
													: card.value === "INTERNAL_CONFIDENTIAL"
														? "area-level-btn--internal"
														: card.value === "CONFIDENTIAL_CONTACT_REQUIRED"
															? "area-level-btn--contact"
															: "area-level-btn--private";
											const subText = getPresetSubtitle(card.value);

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
													{subText && (
														<span className="area-level-btn__sub">
															{subText}
														</span>
													)}
												</button>
											);
										})}
									</div>
								</div>

								<div className="area-form-row">
									<div className="area-form-group">
										<label
											htmlFor="edit-building"
											className="area-form-label"
										>
											Toà nhà / Phân khu <span className="required">*</span>
										</label>
										<select
											id="edit-building"
											className="area-form-input"
											value={formData.building}
											onChange={(e) => {
												const newB = e.target.value;
												const bObj = availableBuildings.find(
													(b) => b.code === newB,
												);
												const firstFloor = bObj?.floors?.[0];
												setFormData({
													...formData,
													building: newB,
													floor: firstFloor ? firstFloor.floorCode : "G",
													floorId: firstFloor ? firstFloor.id : null,
												});
											}}
										>
											{availableBuildings.map((b) => (
												<option
													key={b.code}
													value={b.code}
												>
													{b.name} ({b.code})
												</option>
											))}
										</select>
									</div>

									<div className="area-form-group">
										<label
											htmlFor="edit-floor"
											className="area-form-label"
										>
											Tầng & Sơ đồ <span className="required">*</span>
										</label>
										<select
											id="edit-floor"
											className="area-form-input"
											value={formData.floor}
											onChange={(e) => {
												const newF = e.target.value;
												const flObj = modalFloors.find(
													(f) => f.floorCode === newF,
												);
												setFormData({
													...formData,
													floor: newF,
													floorId: flObj ? flObj.id : null,
												});
											}}
										>
											{modalFloors.map((fl) => (
												<option
													key={fl.floorCode}
													value={fl.floorCode}
												>
													{fl.name}
												</option>
											))}
										</select>
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
					</div>
				</div>
			)}

			{/* DEACTIVATE ZONE MODAL */}
			{deactivateModalOpen && selectedArea && (
				<div
					className="area-modal-backdrop"
					onClick={() => setDeactivateModalOpen(false)}
				>
					<div
						className="area-modal"
						onClick={(e) => e.stopPropagation()}
					>
						<div className="area-modal__header">
							<div className="area-modal__header-left">
								<div className="area-modal__icon-badge area-modal__icon-badge--danger">
									<Trash2 size={16} />
								</div>
								<div className="area-modal__header-text">
									<h3 className="area-modal__title">Vô hiệu hoá khu vực</h3>
									<p className="area-modal__subtitle">
										Xác nhận ngừng kích hoạt khu vực này
									</p>
								</div>
							</div>
							<button
								type="button"
								className="area-modal__close-btn"
								onClick={() => setDeactivateModalOpen(false)}
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
								Bạn có chắc chắn muốn vô hiệu hoá khu vực{" "}
								<strong>{selectedArea.name}</strong>?
							</p>

							{dependencies && (
								<div className="area-dependencies-box">
									<div className="area-dependencies-box__title">
										Phụ thuộc liên quan (sẽ bị ảnh hưởng):
									</div>
									<ul>
										<li>
											Camera đang gán:{" "}
											<strong>{dependencies.assignedCamerasCount || 0}</strong>
										</li>
										<li>
											Lượt yêu cầu truy cập còn hiệu lực:{" "}
											<strong>
												{dependencies.activeAccessRequestsCount || 0}
											</strong>
										</li>
									</ul>
								</div>
							)}
						</div>

						<div className="area-modal__footer">
							<button
								type="button"
								className="area-btn-modal area-btn-modal--cancel"
								onClick={() => setDeactivateModalOpen(false)}
								disabled={modalLoading}
							>
								Huỷ
							</button>
							<button
								type="button"
								className="area-btn-modal area-btn-modal--danger"
								onClick={handleDeactivateSubmit}
								disabled={modalLoading}
							>
								{modalLoading ? "Đang vô hiệu hoá..." : "Xác nhận vô hiệu hoá"}
							</button>
						</div>
					</div>
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
									<h3 className="area-modal__title">
										Danh sách Camera — {camerasModalArea.name}
									</h3>
									<p className="area-modal__subtitle">
										Vị trí:{" "}
										<strong>
											{camerasModalArea.building || "—"} -{" "}
											{camerasModalArea.floor || "—"}
										</strong>{" "}
										• <span>{areaCamerasList.length} camera thuộc khu vực</span>
									</p>
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
							{/* Notification Alert Banner */}
							{cameraNotification && (
								<div
									className={`area-modal-notification area-modal-notification--${cameraNotification.type}`}
								>
									{cameraNotification.type === "error" ? (
										<AlertTriangle
											size={16}
											className="area-modal-notification__icon"
										/>
									) : (
										<CheckCircle2
											size={16}
											className="area-modal-notification__icon"
										/>
									)}
									<span className="area-modal-notification__text">
										{cameraNotification.message}
									</span>
									<button
										type="button"
										className="area-modal-notification__close"
										onClick={() => setCameraNotification(null)}
									>
										<X size={13} />
									</button>
								</div>
							)}

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

														{!isFacilityManager && (
															<button
																type="button"
																className="area-camera-detail-btn"
																onClick={() => {
																	setCamerasModalOpen(false);
																	navigate(
																		`/cameras/${cam.id}?tab=surveillance`,
																	);
																}}
																title="Xem chi tiết camera và cấu hình ROI"
															>
																<span>Xem chi tiết</span>
																<ExternalLink size={13} />
															</button>
														)}

														{isAdmin && (
															<button
																type="button"
																className="area-camera-item__remove-btn"
																onClick={() => {
																	if (
																		window.confirm(
																			`Bạn có chắc muốn hủy gán camera "${cam.name || cam.cameraCode}" khỏi khu vực này?`,
																		)
																	) {
																		handleRemoveCameraFromArea(cam.id);
																	}
																}}
																title="Hủy gán camera khỏi khu vực"
															>
																<Trash2 size={13} />
															</button>
														)}
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
									style={{ color: "#38bdf8", flexShrink: 0 }}
								/>
								<span>
									{isFacilityManager
										? "Danh sách các thiết bị camera giám sát thuộc khu vực này."
										: "Gán hoặc chuyển đổi khu vực được quản lý tại trang Quản lý Camera."}
								</span>
							</div>

							<div
								style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}
							>
								{!isFacilityManager && (
									<button
										type="button"
										className="area-camera-modal-mgmt-link"
										onClick={() => {
											setCamerasModalOpen(false);
											navigate("/cameras");
										}}
									>
										<ExternalLink size={14} />
										<span>Quản lý Camera</span>
									</button>
								)}
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

			{/* Area Access Rules Modal (FM only) */}
			<AreaAccessRulesModal
				isOpen={accessRulesModalOpen}
				onClose={() => setAccessRulesModalOpen(false)}
				area={accessRulesModalArea}
				onSuccess={handleAccessRulesSuccess}
				levelPresets={levelPresets}
			/>
		</div>
	);
}
