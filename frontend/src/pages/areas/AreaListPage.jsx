import { useState, useEffect, useMemo, useCallback, useRef } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import {
	Building2,
	Plus,
	Pencil,
	Trash2,
	AlertCircle,
	X,
	Loader2,
	Layers,
	Cctv,
	VideoOff,
	MapPinPlus,
	Search,
	AlertTriangle,
	CheckCircle2,
	Compass,
	Info,
	ExternalLink,
	RotateCcw,
	Archive,
} from "lucide-react";
import AreaAccessRulesModal from "../../components/area/AreaAccessRulesModal";
import AreaAssignedPersonnelModal from "../../components/area/AreaAssignedPersonnelModal";
import AreaListView from "../../components/area/AreaListView";
import AreaTypeChangePreviewModal from "../../components/area/AreaTypeChangePreviewModal";
import PageHeader from "../../components/ui/PageHeader";
import ReasonTextarea from "../../components/ui/ReasonTextarea";
import "../../components/ui/Button.css";
import { getLevelPresets } from "../../services/accessControlService";
import {
	getAreas,
	getDependencies,
	createArea,
	updateArea,
	deactivateArea,
	restoreArea,
	getAreaCameras,
	updateAreaCameras,
	getTypeChangePreview,
} from "../../services/areaService";
import { getBuildings } from "../../services/buildingService";
import {
	AREA_LEVEL_CONFIG,
	getErrorMessage,
	normalizeAreaName,
	validateAreaName,
} from "../../utils/areaHelpers";
import "../../styles/AreaListPage.css";

// BR-AR-COORD-01..03: toạ độ tuỳ chọn — trống cả hai = "chưa định vị" (null); chỉ nhập một ô thì từ chối
const COORDINATE_PAIR_ERROR = "Phải nhập đủ cả vĩ độ và kinh độ, hoặc để trống cả hai.";
const isBlankCoordinate = (value) => String(value ?? "").trim() === "";

const validateCenterCoordinates = (latitude, longitude) => {
	const latBlank = isBlankCoordinate(latitude);
	const lngBlank = isBlankCoordinate(longitude);
	if (latBlank && lngBlank) {
		return { centerLatitude: null, centerLongitude: null };
	}
	if (latBlank || lngBlank) {
		return { error: COORDINATE_PAIR_ERROR };
	}
	const centerLatitude = Number(String(latitude).trim());
	const centerLongitude = Number(String(longitude).trim());
	if (
		!Number.isFinite(centerLatitude) ||
		centerLatitude < -90 ||
		centerLatitude > 90
	) {
		return { error: "Vĩ độ phải nằm trong khoảng -90 đến 90." };
	}
	if (
		!Number.isFinite(centerLongitude) ||
		centerLongitude < -180 ||
		centerLongitude > 180
	) {
		return { error: "Kinh độ phải nằm trong khoảng -180 đến 180." };
	}

	return { centerLatitude, centerLongitude };
};

// UI-11: lỗi tại chỗ cho 1 ô toạ độ (giới hạn toán học, cùng câu với validateCenterCoordinates); ô trống không báo
const coordinateFieldError = (value, min, max, label) => {
	if (value === "" || value === null || value === undefined) return null;
	const n = Number(value);
	if (!Number.isFinite(n) || n < min || n > max) {
		return `${label} phải nằm trong khoảng ${min} đến ${max}.`;
	}
	return null;
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
	const navigate = useNavigate();
	const isFacilityManager = user?.role === "FACILITY_MANAGER";

	// Data states
	const [areas, setAreas] = useState([]);
	const [buildingsList, setBuildingsList] = useState([]);
	const [loading, setLoading] = useState(true);
	const [pageError, setPageError] = useState(null);

	// Filters
	const [selectedBuilding, setSelectedBuilding] = useState("");
	const [selectedFloor, setSelectedFloor] = useState("ALL");
	const [selectedAreaId, setSelectedAreaId] = useState(null);
	const [cameraCounts, setCameraCounts] = useState({});

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
		return `Cấp mặc định của loại: ${preset.areaAccessLevel} · Chỉ định: ${preset.explicitAuthorizationRequired ? "có" : "không"}`;
	};

	// Modal states
	const [createModalOpen, setCreateModalOpen] = useState(false);
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
	// Step 6 (BR-AD-01, 08): khu vực đang mở modal vô hiệu hoá + lý do. Giữ riêng với selectedArea vì sau 409 045
	// danh sách được tải lại và khu vực có thể không còn trong danh sách đang hoạt động.
	const [deactivateTarget, setDeactivateTarget] = useState(null);
	const [deactivateReason, setDeactivateReason] = useState("");
	const [deactivateReasonError, setDeactivateReasonError] = useState(null);
	const deactivateReasonRef = useRef(null);
	// Step 6 (BR-AD-07): bộ lọc "Đã vô hiệu hoá" + khôi phục — chỉ ADMIN
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
		building: "Tòa Alpha",
		floor: "Tầng Trệt",
		floorId: null,
		centerLatitude: "",
		centerLongitude: "",
		reason: "",
	});

	const rowRefs = useRef({});

	// Fetch all data
	const fetchData = useCallback(async (keepSelectedId = null) => {
		setLoading(true);
		setPageError(null);
		try {
			const [areasRes, buildingsRes] = await Promise.all([
				getAreas({ size: 100, isActive: true }),
				getBuildings().catch(() => []),
			]);

			const areaList = areasRes?.content || areasRes || [];
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
				id: b.id,
				name: b.name,
				floors: b.floors || [],
			}));
		}
		const bSet = new Set();
		areas.forEach((a) => {
			if (a.building) bSet.add(a.building);
		});
		const list = Array.from(bSet).sort();
		return (list.length > 0 ? list : ["Tòa Alpha", "Tòa Beta"]).map((name) => ({
			id: null,
			name,
			floors: [],
		}));
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

	// Derived available floors for current building
	const availableFloors = useMemo(() => {
		const currentBuildingObj = availableBuildings.find(
			(b) =>
				(b.name || "").toUpperCase() === (selectedBuilding || "").toUpperCase(),
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
					(selectedBuilding || "").toUpperCase(),
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
	}, [availableBuildings, areas, selectedBuilding]);

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

	// Modal floors for current form building
	const modalFloors = useMemo(() => {
		const bObj = availableBuildings.find(
			(b) =>
				(b.name || "").toUpperCase() ===
				(formData.building || "").toUpperCase(),
		);
		if (bObj && bObj.floors && bObj.floors.length > 0) {
			return bObj.floors;
		}
		return [
			{ id: null, name: "Tầng Trệt" },
			{ id: null, name: "Tầng 1" },
		];
	}, [availableBuildings, formData.building]);

	// Filtered areas on current building and floor (for list view)
	const floorAreas = useMemo(() => {
		return areas.filter((a) => {
			const matchBuilding =
				!selectedBuilding ||
				(a.building || "").toUpperCase() ===
					(selectedBuilding || "").toUpperCase();
			const matchFloor =
				!selectedFloor ||
				selectedFloor === "ALL" ||
				(a.floor || "").toUpperCase() === (selectedFloor || "").toUpperCase();
			return matchBuilding && matchFloor;
		});
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
		setSelectedFloor("ALL");
		setSelectedAreaId(null);
	};

	const handleSelectFloor = (fl) => {
		setSelectedFloor(fl);
		setSelectedAreaId(null);
	};

	// Area selection
	const handleSelectArea = (areaId) => {
		setSelectedAreaId(areaId);
		if (rowRefs.current[areaId]) {
			rowRefs.current[areaId].scrollIntoView({
				behavior: "smooth",
				block: "nearest",
			});
		}
	};

	// Modal Handlers
	const handleOpenCreateModal = () => {
		const currentB =
			selectedBuilding || availableBuildings[0]?.name || "Tòa Alpha";
		const bObj = availableBuildings.find((b) => b.name === currentB);
		const floorsForB = bObj?.floors || [];
		const currentF =
			selectedFloor && selectedFloor !== "ALL"
				? selectedFloor
				: floorsForB[0]?.name || "Tầng Trệt";
		const currentFloorId =
			floorsForB.find((f) => f.name === currentF)?.id || null;

		setFormData({
			name: "",
			areaLevel: "PUBLIC",
			building: currentB,
			floor: currentF,
			floorId: currentFloorId,
			centerLatitude: "",
			centerLongitude: "",
			reason: "",
		});
		setModalError(null);
		setNameError(null);
		setCreateModalOpen(true);
	};

	// Lỗi khoảng giá trị của từng ô; BR-AR-COORD-02 (chỉ nhập một ô) hiện dưới ô đang trống
	const latitudeError =
		coordinateFieldError(formData.centerLatitude, -90, 90, "Vĩ độ") ||
		(isBlankCoordinate(formData.centerLatitude) && !isBlankCoordinate(formData.centerLongitude)
			? COORDINATE_PAIR_ERROR
			: null);
	const longitudeError =
		coordinateFieldError(formData.centerLongitude, -180, 180, "Kinh độ") ||
		(isBlankCoordinate(formData.centerLongitude) && !isBlankCoordinate(formData.centerLatitude)
			? COORDINATE_PAIR_ERROR
			: null);

	const handleCreateSubmit = async (e) => {
		e.preventDefault();
		const nameValidationError = validateAreaName(formData.name);
		if (nameValidationError) {
			setNameError(nameValidationError);
			return;
		}
		setModalError(null);
		const coordinates = validateCenterCoordinates(
			formData.centerLatitude,
			formData.centerLongitude,
		);
		if (coordinates.error) {
			setModalError(coordinates.error);
			return;
		}
		setModalLoading(true);
		try {
			const payload = {
				name: normalizeAreaName(formData.name),
				areaLevel: formData.areaLevel,
				building: formData.building ? formData.building.trim() : null,
				floor: formData.floor ? formData.floor.trim() : null,
				floorId: formData.floorId || null,
				centerLatitude: coordinates.centerLatitude,
				centerLongitude: coordinates.centerLongitude,
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
			centerLatitude: areaToEdit.centerLatitude ?? "",
			centerLongitude: areaToEdit.centerLongitude ?? "",
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
		const coordinates = validateCenterCoordinates(
			formData.centerLatitude,
			formData.centerLongitude,
		);
		if (coordinates.error) {
			setModalError(coordinates.error);
			return;
		}

		const targetId = formData.id || selectedArea?.id;
		if (!targetId) return;

		// Step 5b (BR-TC-02): đổi loại bắt buộc lý do 10–500 ký tự
		const isTypeChange =
			Boolean(formData.originalAreaLevel) &&
			formData.areaLevel !== formData.originalAreaLevel;
		const trimmedReason = (formData.reason || "").trim();
		if (isTypeChange && (trimmedReason.length < 10 || trimmedReason.length > 500)) {
			setEditReasonError(`Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmedReason.length}).`);
			editReasonRef.current?.focus();
			return;
		}
		setEditReasonError(null);

		const payload = {
			name: normalizeAreaName(formData.name),
			areaLevel: formData.areaLevel,
			building: formData.building ? formData.building.trim() : null,
			floor: formData.floor ? formData.floor.trim() : null,
			floorId: formData.floorId || null,
			centerLatitude: coordinates.centerLatitude,
			centerLongitude: coordinates.centerLongitude,
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

	const deactivateBlocked = !dependencies || (dependencies.blockers || []).length > 0;

	const handleDeactivateSubmit = async () => {
		if (!deactivateTarget || !dependencies) return;
		const trimmed = deactivateReason.trim();
		if (trimmed.length < 10 || trimmed.length > 500) {
			setDeactivateReasonError(`Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmed.length}).`);
			deactivateReasonRef.current?.focus();
			return;
		}
		const targetId = deactivateTarget.id;
		setModalLoading(true);
		setModalError(null);
		setDeactivateReasonError(null);
		try {
			await deactivateArea(targetId, { reason: trimmed, version: dependencies.version });
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

	// ------------------------------------------------------------------ Step 6: đã vô hiệu hoá + khôi phục

	const fetchDeactivatedAreas = useCallback(async () => {
		setDeactivatedLoading(true);
		setDeactivatedError(null);
		try {
			const res = await getAreas({ size: 100, isActive: false });
			const list = res?.content || res || [];
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
		if (trimmed.length < 10 || trimmed.length > 500) {
			setRestoreReasonError(`Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmed.length}).`);
			restoreReasonRef.current?.focus();
			return;
		}
		setRestoreLoading(true);
		setRestoreError(null);
		setRestoreReasonError(null);
		try {
			await restoreArea(restoreTarget.id, { reason: trimmed, version: restoreTarget.version });
			setRestoreTarget(null);
			setRestoreReason("");
			setRestoreReasonError(null);
			await Promise.all([fetchDeactivatedAreas(), fetchData()]);
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

			<PageHeader
				title="Quản lý khu vực"
				description={
					isAdmin
						? "Thêm mới phân khu, cấu hình cấp độ bảo mật, liên kết camera và thiết lập hạ tầng an ninh."
						: "Quản lý danh sách nhân sự được chỉ định, tra cứu phân quyền và vận hành phân khu."
				}
				actions={
					// Route /admin/map chỉ cho ADMIN (App.jsx) → FM không thấy lối tắt dẫn tới trang bị chặn
					isAdmin ? (
						<Link
							to="/admin/map"
							className="ui-btn ui-btn--secondary ui-btn--md"
						>
							<Compass size={16} />
							<span>Xem trên bản đồ an ninh</span>
						</Link>
					) : null
				}
			/>

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
						title="Chọn tòa nhà"
					>
						{availableBuildings.map((b) => (
							<option
								key={b.id || b.name}
								value={b.name}
							>
								{b.name}
							</option>
						))}
					</select>
				</div>

				{/* Floor Selector (Options) */}
				<div className="zone-toolbar__building">
					<Layers
						size={16}
						className="zone-toolbar__building-icon"
					/>
					<select
						className="zone-toolbar__building-select"
						value={selectedFloor}
						onChange={(e) => handleSelectFloor(e.target.value)}
						title="Chọn tầng"
					>
						<option value="ALL">Tất cả tầng</option>
						{availableFloors.map((fl) => (
							<option
								key={fl.floorId || fl.name}
								value={fl.name}
							>
								{fl.name}
							</option>
						))}
					</select>
				</div>

				<div className="zone-toolbar__spacer" />

				{/* Add Area Button Group */}
				<div className="zone-toolbar__actions">
					{isAdmin && (
						<div
							className="zone-view-toggle"
							role="group"
							aria-label="Lọc trạng thái khu vực"
						>
							<button
								type="button"
								className={`zone-view-toggle__btn ${!showDeactivated ? "zone-view-toggle__btn--active" : ""}`}
								onClick={() => setShowDeactivated(false)}
								title="Khu vực đang hoạt động"
							>
								<Layers size={15} />
								<span>Đang hoạt động</span>
							</button>
							<button
								type="button"
								className={`zone-view-toggle__btn ${showDeactivated ? "zone-view-toggle__btn--active" : ""}`}
								onClick={() => setShowDeactivated(true)}
								title="Khu vực đã vô hiệu hóa"
							>
								<Archive size={15} />
								<span>Đã vô hiệu hóa</span>
							</button>
						</div>
					)}

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
			{/* Step 6 (BR-AD-07): danh sách khu vực đã vô hiệu hoá + khôi phục — chỉ ADMIN */}
			{isAdmin && showDeactivated && (
				<section
					className="zone-deactivated"
					aria-label="Khu vực đã vô hiệu hóa"
				>
					<div className="zone-deactivated__header">
						<div>
							<h2 className="zone-deactivated__title">
								Khu vực đã vô hiệu hóa
							</h2>
							<p className="zone-deactivated__subtitle">
								Khôi phục chỉ mở lại khu vực. Nhân sự chỉ định, đơn truy cập,
								lượt khách và camera đã gỡ không tự hồi phục.
							</p>
						</div>
						<span className="zone-deactivated__count">
							{deactivatedAreas.length} khu vực
						</span>
					</div>

					{deactivatedLoading && (
						<div className="zone-page__loading">
							<Loader2
								className="animate-spin"
								size={24}
							/>
							<span>Đang tải khu vực đã vô hiệu hóa...</span>
						</div>
					)}
					{!deactivatedLoading && deactivatedError && (
						<div className="zone-modal-alert">
							<AlertCircle size={15} />
							<span>{deactivatedError}</span>
						</div>
					)}
					{!deactivatedLoading &&
						!deactivatedError &&
						deactivatedAreas.length === 0 && (
							<p className="zone-deactivated__empty">
								Không có khu vực nào đã vô hiệu hóa.
							</p>
						)}
					{!deactivatedLoading &&
						!deactivatedError &&
						deactivatedAreas.length > 0 && (
							<table className="zone-deactivated__table">
								<thead>
									<tr>
										<th>Tên khu vực</th>
										<th>Tòa nhà / Tầng</th>
										<th>Loại</th>
										<th aria-label="Thao tác" />
									</tr>
								</thead>
								<tbody>
									{deactivatedAreas.map((a) => (
										<tr key={a.id}>
											<td className="zone-deactivated__name">{a.name}</td>
											<td>
												{a.building || "—"} / {a.floor || "—"}
											</td>
											<td>
												{AREA_LEVEL_CONFIG[a.areaLevel]?.name || a.areaLevel}
											</td>
											<td className="zone-deactivated__actions">
												<button
													type="button"
													className="area-btn-modal area-btn-modal--cancel zone-deactivated__restore-btn"
													onClick={() => openRestoreModal(a)}
												>
													<RotateCcw size={14} />
													<span>Khôi phục</span>
												</button>
											</td>
										</tr>
									))}
								</tbody>
							</table>
						)}
				</section>
			)}

			{!loading && !showDeactivated && (
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
										Tạo khu vực giám sát trong tòa nhà
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
										placeholder="Cổng chính tòa nhà"
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
										Loại khu vực <span className="required">*</span>
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
											Tòa nhà / Phân khu <span className="required">*</span>
										</label>
										<select
											id="create-building"
											className="area-form-input"
											value={formData.building}
											onChange={(e) => {
												const newB = e.target.value;
												const bObj = availableBuildings.find(
													(b) => b.name === newB,
												);
												const firstFloor = bObj?.floors?.[0];
												setFormData({
													...formData,
													building: newB,
													floor: firstFloor ? firstFloor.name : "Tầng Trệt",
													floorId: firstFloor ? firstFloor.id : null,
												});
											}}
										>
											{availableBuildings.map((b) => (
												<option
													key={b.id || b.name}
													value={b.name}
												>
													{b.name}
												</option>
											))}
										</select>
									</div>

									<div className="area-form-group">
										<label
											htmlFor="create-floor"
											className="area-form-label"
										>
											Tầng <span className="required">*</span>
										</label>
										<select
											id="create-floor"
											className="area-form-input"
											value={formData.floor}
											onChange={(e) => {
												const newF = e.target.value;
												const flObj = modalFloors.find((f) => f.name === newF);
												setFormData({
													...formData,
													floor: newF,
													floorId: flObj ? flObj.id : null,
												});
											}}
										>
											{modalFloors.map((fl) => (
												<option
													key={fl.id || fl.name}
													value={fl.name}
												>
													{fl.name}
												</option>
											))}
										</select>
									</div>
								</div>
								<div className="area-form-row">
									<div className="area-form-group">
										<label
											htmlFor="create-center-latitude"
											className="area-form-label"
										>
											Vĩ độ
										</label>
										<input
											id="create-center-latitude"
											type="number"
											step="any"
											min="-90"
											max="90"
											className={`area-form-input ${latitudeError ? "area-form-input--error" : ""}`}
											aria-invalid={Boolean(latitudeError)}
											placeholder="Ví dụ: 10.8411"
											value={formData.centerLatitude}
											onChange={(e) =>
												setFormData({
													...formData,
													centerLatitude: e.target.value,
												})
											}
										/>
										{latitudeError && <div className="area-form-error">{latitudeError}</div>}
									</div>
									<div className="area-form-group">
										<label
											htmlFor="create-center-longitude"
											className="area-form-label"
										>
											Kinh độ
										</label>
										<input
											id="create-center-longitude"
											type="number"
											step="any"
											min="-180"
											max="180"
											className={`area-form-input ${longitudeError ? "area-form-input--error" : ""}`}
											aria-invalid={Boolean(longitudeError)}
											placeholder="Ví dụ: 106.8090"
											value={formData.centerLongitude}
											onChange={(e) =>
												setFormData({
													...formData,
													centerLongitude: e.target.value,
												})
											}
										/>
										{longitudeError && <div className="area-form-error">{longitudeError}</div>}
									</div>
								</div>
								<div className="area-form-hint">Để trống cả hai nếu chưa xác định vị trí — khu vực sẽ ở trạng thái "Chưa định vị" và không hiện trên bản đồ.</div>
							</div>

							{/* 4. Phần chân: 2 nút chia đôi chiều rộng, gap 9px */}
							<div className="area-modal__footer">
								<button
									type="button"
									className="area-btn-modal area-btn-modal--cancel"
									onClick={() => setCreateModalOpen(false)}
									disabled={modalLoading}
								>
									Hủy
								</button>
								<button
									type="submit"
									className="area-btn-modal area-btn-modal--submit"
									disabled={modalLoading || Boolean(latitudeError || longitudeError)}
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
									<div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "6px" }}>
										<label className="area-form-label" style={{ marginBottom: 0 }}>
											Loại khu vực <span className="required">*</span>
										</label>
										<span style={{ fontSize: "12.5px", color: "var(--theme-text-secondary, #475569)" }}>
											Cấp hiện tại của khu vực: <strong>{formData.areaAccessLevel ?? 1}</strong>
											{(() => {
												const origPreset = levelPresets?.[formData.originalAreaLevel || formData.areaLevel];
												const presetLvl = origPreset?.areaAccessLevel;
												if (formData.areaAccessLevel != null && presetLvl != null && Number(formData.areaAccessLevel) !== Number(presetLvl)) {
													return <span style={{ color: "var(--brand-warning, #d97706)", marginLeft: "4px" }}>(khác mặc định của loại: {presetLvl})</span>;
												}
												return null;
											})()}
										</span>
									</div>
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

								{/* Step 5b (BR-TC-02): đổi loại -> bắt buộc lý do 10–500 ký tự, ghi vào audit CHANGE_TYPE */}
								{formData.originalAreaLevel && formData.areaLevel !== formData.originalAreaLevel && (
									<div className="area-form-group">
										<ReasonTextarea
											ref={editReasonRef}
											id="edit-type-change-reason"
											label="Lý do đổi loại khu vực"
											placeholder="Nêu lý do đổi loại (10–500 ký tự)..."
											value={formData.reason || ""}
											onChange={(e) => {
												const val = e.target.value;
												setFormData({ ...formData, reason: val });
												if (editReasonError && val.trim().length >= 10 && val.trim().length <= 500) {
													setEditReasonError(null);
												}
											}}
											error={editReasonError}
											hint="Đổi loại sẽ áp cấp truy cập theo mặc định của loại mới và có thể hủy các đơn truy cập không còn phù hợp."
											min={10}
											max={500}
											required
										/>
									</div>
								)}

								<div className="area-form-row">
									<div className="area-form-group">
										<label
											htmlFor="edit-building"
											className="area-form-label"
										>
											Tòa nhà / Phân khu <span className="required">*</span>
										</label>
										<select
											id="edit-building"
											className="area-form-input"
											value={formData.building}
											onChange={(e) => {
												const newB = e.target.value;
												const bObj = availableBuildings.find(
													(b) => b.name === newB,
												);
												const firstFloor = bObj?.floors?.[0];
												setFormData({
													...formData,
													building: newB,
													floor: firstFloor ? firstFloor.name : "Tầng Trệt",
													floorId: firstFloor ? firstFloor.id : null,
												});
											}}
										>
											{availableBuildings.map((b) => (
												<option
													key={b.id || b.name}
													value={b.name}
												>
													{b.name}
												</option>
											))}
										</select>
									</div>

									<div className="area-form-group">
										<label
											htmlFor="edit-floor"
											className="area-form-label"
										>
											Tầng <span className="required">*</span>
										</label>
										<select
											id="edit-floor"
											className="area-form-input"
											value={formData.floor}
											onChange={(e) => {
												const newF = e.target.value;
												const flObj = modalFloors.find((f) => f.name === newF);
												setFormData({
													...formData,
													floor: newF,
													floorId: flObj ? flObj.id : null,
												});
											}}
										>
											{modalFloors.map((fl) => (
												<option
													key={fl.id || fl.name}
													value={fl.name}
												>
													{fl.name}
												</option>
											))}
										</select>
									</div>
								</div>
								<div className="area-form-row">
									<div className="area-form-group">
										<label
											htmlFor="edit-center-latitude"
											className="area-form-label"
										>
											Vĩ độ
										</label>
										<input
											id="edit-center-latitude"
											type="number"
											step="any"
											min="-90"
											max="90"
											className={`area-form-input ${latitudeError ? "area-form-input--error" : ""}`}
											aria-invalid={Boolean(latitudeError)}
											placeholder="Ví dụ: 10.8411"
											value={formData.centerLatitude}
											onChange={(e) =>
												setFormData({
													...formData,
													centerLatitude: e.target.value,
												})
											}
										/>
										{latitudeError && <div className="area-form-error">{latitudeError}</div>}
									</div>
									<div className="area-form-group">
										<label
											htmlFor="edit-center-longitude"
											className="area-form-label"
										>
											Kinh độ
										</label>
										<input
											id="edit-center-longitude"
											type="number"
											step="any"
											min="-180"
											max="180"
											className={`area-form-input ${longitudeError ? "area-form-input--error" : ""}`}
											aria-invalid={Boolean(longitudeError)}
											placeholder="Ví dụ: 106.8090"
											value={formData.centerLongitude}
											onChange={(e) =>
												setFormData({
													...formData,
													centerLongitude: e.target.value,
												})
											}
										/>
										{longitudeError && <div className="area-form-error">{longitudeError}</div>}
									</div>
								</div>
								<div className="area-form-hint">Để trống cả hai nếu chưa xác định vị trí — khu vực sẽ ở trạng thái "Chưa định vị" và không hiện trên bản đồ.</div>
							</div>

							<div className="area-modal__footer">
								<button
									type="button"
									className="area-btn-modal area-btn-modal--cancel"
									onClick={() => setEditModalOpen(false)}
									disabled={modalLoading}
								>
									Hủy
								</button>
								<button
									type="submit"
									className="area-btn-modal area-btn-modal--submit"
									disabled={modalLoading || Boolean(latitudeError || longitudeError)}
								>
									{modalLoading ? "Đang lưu..." : "Lưu thay đổi"}
								</button>
							</div>
						</form>
					</div>
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
				<div
					className="area-modal-backdrop"
					onClick={closeDeactivateModal}
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
									<h3 className="area-modal__title">Vô hiệu hóa khu vực</h3>
									<p className="area-modal__subtitle">
										Khu vực ngừng hoạt động, không còn nhận đơn hay lượt khách
									</p>
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
								Vô hiệu hóa khu vực <strong>{deactivateTarget.name}</strong>?
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
										<span>Chưa thể vô hiệu hóa — cần xử lý trước:</span>
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
										Hệ thống sẽ tự xử lý khi vô hiệu hóa:
									</div>
									<ul>
										<li>
											Nhân sự chỉ định bị thu hồi:{" "}
											<strong>{dependencies.apToRevoke ?? 0}</strong>
										</li>
										<li>
											Đơn truy cập bị hủy:{" "}
											<strong>{dependencies.requestsToCancel ?? 0}</strong>
										</li>
										<li>
											Lượt khách chờ duyệt bị hủy:{" "}
											<strong>{dependencies.guestVisitsToCancel ?? 0}</strong>
										</li>
										<li>
											Lượt khách đã duyệt bị thu hồi (xóa ảnh khuôn mặt):{" "}
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
										placeholder="Nêu lý do vô hiệu hóa (10–500 ký tự)..."
										value={deactivateReason}
										onChange={(e) => {
											const val = e.target.value;
											setDeactivateReason(val);
											if (deactivateReasonError && val.trim().length >= 10 && val.trim().length <= 500) {
												setDeactivateReasonError(null);
											}
										}}
										error={deactivateReasonError}
										min={10}
										max={500}
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
								Hủy
							</button>
							<button
								type="button"
								className="area-btn-modal area-btn-modal--danger"
								onClick={handleDeactivateSubmit}
								disabled={modalLoading || deactivateBlocked}
							>
								{modalLoading && dependencies
									? "Đang vô hiệu hóa..."
									: "Xác nhận vô hiệu hóa"}
							</button>
						</div>
					</div>
				</div>
			)}

			{/* RESTORE ZONE MODAL — Step 6 (BR-AD-07) */}
			{restoreTarget && (
				<div
					className="area-modal-backdrop"
					onClick={() => {
						if (!restoreLoading) {
							setRestoreTarget(null);
							setRestoreReasonError(null);
						}
					}}
				>
					<div
						className="area-modal"
						onClick={(e) => e.stopPropagation()}
					>
						<div className="area-modal__header">
							<div className="area-modal__header-left">
								<div className="area-modal__icon-badge">
									<RotateCcw size={16} />
								</div>
								<div className="area-modal__header-text">
									<h3 className="area-modal__title">Khôi phục khu vực</h3>
									<p className="area-modal__subtitle">
										Mở lại khu vực đã vô hiệu hóa
									</p>
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
								Khôi phục khu vực <strong>{restoreTarget.name}</strong> (
								{restoreTarget.building || "—"} / {restoreTarget.floor || "—"})?
							</p>
							<div className="area-dependencies-box">
								<div className="area-dependencies-box__title">Lưu ý</div>
								<ul>
									<li>
										Nhân sự chỉ định, đơn truy cập và lượt khách đã bị hủy / thu
										hồi KHÔNG tự hồi phục.
									</li>
									<li>Camera cần được gán lại nếu cần.</li>
								</ul>
							</div>
							<div className="area-form-group">
								<ReasonTextarea
									ref={restoreReasonRef}
									id="restore-reason"
									label="Lý do khôi phục"
									placeholder="Nêu lý do khôi phục (10–500 ký tự)..."
									value={restoreReason}
									onChange={(e) => {
										const val = e.target.value;
										setRestoreReason(val);
										if (restoreReasonError && val.trim().length >= 10 && val.trim().length <= 500) {
											setRestoreReasonError(null);
										}
									}}
									error={restoreReasonError}
									min={10}
									max={500}
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
								Hủy
							</button>
							<button
								type="button"
								className="area-btn-modal area-btn-modal--submit"
								onClick={handleRestoreSubmit}
								disabled={restoreLoading}
							>
								{restoreLoading ? "Đang khôi phục..." : "Xác nhận khôi phục"}
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
										: "Có thể hủy gán camera khỏi khu vực ngay tại đây. Gán camera mới hoặc chuyển sang khu vực khác thực hiện tại trang Quản lý Camera."}
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
