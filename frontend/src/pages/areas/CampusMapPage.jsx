import React, {
	useState,
	useEffect,
	useMemo,
	useCallback,
	useRef,
} from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import {
	Compass,
	Map as MapIcon,
	Layers,
	Building2,
	List as ListIcon,
	AlertCircle,
	X,
	Loader2,
	Shield,
	Settings,
} from "lucide-react";
import CampusMapView from "../../components/area/CampusMapView";
import AreaMapView from "../../components/area/AreaMapView";
import {
	getAreas,
	getFloorPlans,
	saveAreaGeometry,
	deleteAreaGeometry,
	getAreaCameras,
} from "../../services/areaService";
import { getBuildings } from "../../services/buildingService";
import { getLevelPolygonClass, getErrorMessage } from "../../utils/areaHelpers";
import "../../styles/AreaListPage.css";

const EPS = 0.0005;
const round6 = (n) => Math.round(n * 1e6) / 1e6;

export default function CampusMapPage() {
	const { user } = useAuth();
	const isAdmin = user?.role === "ADMIN";
	const isFacilityManager = user?.role === "FACILITY_MANAGER";
	const navigate = useNavigate();

	const [searchParams, setSearchParams] = useSearchParams();
	// 'outdoor' (Campus GPS) or 'indoor' (Floor Plan SVG)
	const mapType = searchParams.get("type") === "indoor" ? "indoor" : "outdoor";

	const handleToggleMapType = (type) => {
		setSearchParams((prev) => {
			const next = new URLSearchParams(prev);
			next.set("type", type);
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

	// Filters for indoor floor plan
	const [selectedBuilding, setSelectedBuilding] = useState("");
	const [selectedFloor, setSelectedFloor] = useState("");
	const [selectedAreaId, setSelectedAreaId] = useState(null);
	const [cameraCounts, setCameraCounts] = useState({});

	// Drawing states for indoor SVG
	const [drawingAreaId, setDrawingAreaId] = useState(null);
	const [draftVertices, setDraftVertices] = useState([]);
	const [drawError, setDrawError] = useState(null);
	const [savingGeometry, setSavingGeometry] = useState(false);
	const [confirmDeleteId, setConfirmDeleteId] = useState(null);
	const [deletingGeometryId, setDeletingGeometryId] = useState(null);

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
			console.error("Error loading map data:", err);
			setPageError(getErrorMessage(err));
		} finally {
			setLoading(false);
		}
	}, []);

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

	// Derived available floors
	const availableFloors = useMemo(() => {
		const currentBuildingObj = availableBuildings.find(
			(b) => b.code === selectedBuilding,
		);
		if (currentBuildingObj?.floors?.length > 0) {
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
			.sort((a, b) => a.localeCompare(b))
			.map((fl) => ({
				code: fl,
				name: `Tầng ${fl}`,
				floorId: null,
			}));
	}, [availableBuildings, floorPlans, areas, selectedBuilding]);

	useEffect(() => {
		if (
			availableFloors.length > 0 &&
			!availableFloors.some((f) => f.code === selectedFloor)
		) {
			setSelectedFloor(availableFloors[0].code);
		}
	}, [availableFloors, selectedFloor]);

	const selectedPlan = useMemo(() => {
		return (
			floorPlans.find(
				(fp) => fp.building === selectedBuilding && fp.floor === selectedFloor,
			) || null
		);
	}, [floorPlans, selectedBuilding, selectedFloor]);

	const selectedArea = useMemo(() => {
		return areas.find((a) => a.id === selectedAreaId) || null;
	}, [areas, selectedAreaId]);

	// Pre-fetch camera counts
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

	const handleSelectArea = (areaId) => {
		setSelectedAreaId(areaId);
		setConfirmDeleteId(null);
	};

	// Drawing handlers for Indoor SVG
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
			setDrawError(err.message || "Không lưu được hình đa giác.");
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
			setPageError("Không xoá được hình đa giác.");
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

	const mapPolygons = useMemo(() => {
		if (!selectedPlan) return [];
		return areas.filter(
			(a) =>
				a.building === selectedBuilding &&
				a.floor === selectedFloor &&
				a.geometry?.vertices?.length >= 3,
		);
	}, [areas, selectedBuilding, selectedFloor, selectedPlan]);

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
		return areas.filter((a) => !(a.geometry?.vertices?.length >= 3)).length;
	}, [areas]);

	const isSelectedAreaInCurrentScope =
		selectedArea &&
		selectedArea.building === selectedBuilding &&
		selectedArea.floor === selectedFloor;

	const selectedAreaHasGeometry =
		selectedArea && selectedArea.geometry?.vertices?.length >= 3;

	return (
		<div className="zone-page">
			{/* Page Error Banner */}
			{pageError && (
				<div className="zone-alert zone-alert--error">
					<AlertCircle size={18} />
					<span>{pageError}</span>
					<button
						type="button"
						className="zone-alert__close"
						onClick={() => setPageError(null)}
					>
						<X size={16} />
					</button>
				</div>
			)}

			{/* Role Banner: Explicit separation of Admin vs FM */}
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
						<Compass
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
							Quản lý Bản đồ An ninh
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
							? "Cấu hình toạ độ khuôn viên ngoài trời và vẽ đa giác sơ đồ mặt bằng các tầng."
							: "Theo dõi vị trí các phân khu an ninh và nhân sự được chỉ định."}
					</p>
				</div>

				<button
					type="button"
					onClick={() => navigate("/admin/areas")}
					style={{
						display: "inline-flex",
						alignItems: "center",
						gap: "6px",
						padding: "6px 12px",
						fontSize: "12.5px",
						fontWeight: 500,
						background: "transparent",
						border: "1px solid var(--theme-border)",
						borderRadius: "6px",
						color: "var(--theme-text-primary)",
						cursor: "pointer",
					}}
				>
					<ListIcon size={14} />
					<span>Sang Quản lý Vùng</span>
				</button>
			</div>

			{/* Map Sub-Toolbar: Switch between Outdoor GPS and Indoor Floor Plan */}
			<div className="zone-toolbar">
				{mapType === "indoor" && (
					<>
						<div className="zone-toolbar__building">
							<Building2
								size={16}
								className="zone-toolbar__building-icon"
							/>
							<select
								className="zone-toolbar__building-select"
								value={selectedBuilding}
								onChange={(e) => {
									setSelectedBuilding(e.target.value);
									setSelectedAreaId(null);
								}}
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

						<div className="zone-toolbar__tabs">
							{availableFloors.map((fl) => (
								<button
									key={fl.code}
									type="button"
									className={`zone-toolbar__tab ${selectedFloor === fl.code ? "zone-toolbar__tab--active" : ""}`}
									onClick={() => {
										setSelectedFloor(fl.code);
										setSelectedAreaId(null);
									}}
								>
									{fl.name}
								</button>
							))}
						</div>
					</>
				)}

				<div className="zone-toolbar__spacer" />

				<div className="zone-toolbar__actions">
					<div className="zone-view-toggle">
						<button
							type="button"
							className={`zone-view-toggle__btn ${mapType === "outdoor" ? "zone-view-toggle__btn--active" : ""}`}
							onClick={() => handleToggleMapType("outdoor")}
							title="Bản đồ địa lý toàn cảnh khuôn viên ngoài trời (OpenStreetMap Standard)"
						>
							<Compass size={15} />
							<span>Khuôn viên (Ngoài trời)</span>
						</button>
						<button
							type="button"
							className={`zone-view-toggle__btn ${mapType === "indoor" ? "zone-view-toggle__btn--active" : ""}`}
							onClick={() => handleToggleMapType("indoor")}
							title="Sơ đồ mặt bằng chi tiết các tầng trong nhà"
						>
							<MapIcon size={15} />
							<span>Sơ đồ tầng (Trong nhà)</span>
						</button>
					</div>
				</div>
			</div>

			{/* Loading */}
			{loading && (
				<div className="zone-page__loading">
					<Loader2
						className="animate-spin"
						size={32}
					/>
					<span>Đang nạp dữ liệu bản đồ...</span>
				</div>
			)}

			{/* 1. OUTDOOR MAP: CampusMapView with OpenStreetMap Standard */}
			{!loading && mapType === "outdoor" && (
				<CampusMapView
					areas={areas}
					selectedAreaId={selectedAreaId}
					cameraCounts={cameraCounts}
					isAdmin={isAdmin}
					isFacilityManager={isFacilityManager}
					onSelectArea={handleSelectArea}
				/>
			)}

			{/* 2. INDOOR MAP: AreaMapView (Floor Plan SVG) */}
			{!loading && mapType === "indoor" && (
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
					onToggleView={() => navigate("/admin/areas")}
					onStartDrawing={startDrawing}
					onDeleteGeometry={handleDeleteGeometry}
					setConfirmDeleteId={setConfirmDeleteId}
					onOpenAssignedPersonnelModal={null}
					onOpenEditModal={() => navigate("/admin/areas")}
					getLevelPolygonClass={getLevelPolygonClass}
				/>
			)}
		</div>
	);
}
