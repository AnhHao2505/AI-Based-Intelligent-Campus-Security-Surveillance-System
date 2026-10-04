import React, {
	useState,
	useEffect,
	useMemo,
	useCallback,
} from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import {
	Compass,
	Building2,
	AlertCircle,
	Loader2,
	Layers,
} from "lucide-react";
import CampusMapView from "../../components/area/CampusMapView";
import PageHeader from "../../components/ui/PageHeader";
import "../../components/ui/Button.css";
import {
	getAreas,
	getAreaCameras,
} from "../../services/areaService";
import { getBuildings } from "../../services/buildingService";
import { getErrorMessage } from "../../utils/areaHelpers";
import "../../styles/AreaListPage.css";

export default function CampusMapPage() {
	const { user } = useAuth();
	const isAdmin = user?.role === "ADMIN";
	const isFacilityManager = user?.role === "FACILITY_MANAGER";
	const navigate = useNavigate();

	// Data states
	const [areas, setAreas] = useState([]);
	const [buildingsList, setBuildingsList] = useState([]);
	const [loading, setLoading] = useState(true);
	const [pageError, setPageError] = useState(null);
	const [selectedBuilding, setSelectedBuilding] = useState("ALL");
	const [selectedFloor, setSelectedFloor] = useState("ALL");
	const [selectedAreaId, setSelectedAreaId] = useState(null);
	const [cameraCounts, setCameraCounts] = useState({});

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
			console.error("Error loading map data:", err);
			setPageError(getErrorMessage(err));
		} finally {
			setLoading(false);
		}
	}, []);

	useEffect(() => {
		fetchData();
	}, [fetchData]);

	// Load camera counts
	useEffect(() => {
		let isCancelled = false;
		async function loadAllCounts() {
			if (areas.length === 0) return;
			const counts = {};
			await Promise.all(
				areas.map(async (a) => {
					try {
						const res = await getAreaCameras(a.id);
						const list = res?.content || res || [];
						counts[a.id] = Array.isArray(list) ? list.length : 0;
					} catch {
						counts[a.id] = 0;
					}
				}),
			);
			if (!isCancelled) {
				setCameraCounts(counts);
			}
		}
		loadAllCounts();
		return () => {
			isCancelled = true;
		};
	}, [areas]);

	// Available floors for current building selection
	const availableFloors = useMemo(() => {
		const fSet = new Set();
		if (selectedBuilding !== "ALL") {
			const bObj = buildingsList.find(
				(b) =>
					(b.name || "").toUpperCase() === selectedBuilding.toUpperCase(),
			);
			if (bObj?.floors?.length > 0) {
				bObj.floors.forEach((f) => fSet.add(f.name));
			} else {
				areas
					.filter(
						(a) =>
							(a.building || "").toUpperCase() ===
							selectedBuilding.toUpperCase(),
					)
					.forEach((a) => {
						if (a.floor) fSet.add(a.floor);
					});
			}
		} else {
			buildingsList.forEach((b) => {
				(b.floors || []).forEach((f) => fSet.add(f.name));
			});
			areas.forEach((a) => {
				if (a.floor) fSet.add(a.floor);
			});
		}

		if (fSet.size === 0) {
			fSet.add("Tầng Trệt");
			fSet.add("Tầng 1");
		}

		return Array.from(fSet).sort((a, b) => {
			if (a.toLowerCase().includes("trệt")) return -1;
			if (b.toLowerCase().includes("trệt")) return 1;
			return a.localeCompare(b);
		});
	}, [buildingsList, areas, selectedBuilding]);

	// Filtered areas by building and floor
	const filteredAreas = useMemo(() => {
		return areas.filter((a) => {
			const matchBuilding =
				selectedBuilding === "ALL" ||
				(a.building || "").toUpperCase() === selectedBuilding.toUpperCase();
			const matchFloor =
				selectedFloor === "ALL" ||
				(a.floor || "").toUpperCase() === selectedFloor.toUpperCase();
			return matchBuilding && matchFloor;
		});
	}, [areas, selectedBuilding, selectedFloor]);

	const handleSelectArea = (areaId) => {
		setSelectedAreaId((prev) => (prev === areaId ? null : areaId));
	};

	return (
		<div className="zone-page">
			{/* Page Header */}
			<PageHeader
				title="Bản đồ khuôn viên"
				subtitle="Bản đồ an ninh toàn diện và giám sát khu vực theo toạ độ địa lý."
			/>

			{/* Global Error Banner */}
			{pageError && (
				<div className="zone-page__alert zone-page__alert--danger">
					<AlertCircle size={18} />
					<span>{pageError}</span>
				</div>
			)}

			{/* Toolbar */}
			<div className="zone-toolbar">
				{/* Building Selector */}
				<div className="zone-toolbar__group">
					<Building2 size={16} className="text-secondary" />
					<select
						className="zone-select"
						value={selectedBuilding}
						onChange={(e) => {
							setSelectedBuilding(e.target.value);
							setSelectedFloor("ALL");
							setSelectedAreaId(null);
						}}
						title="Lọc khu vực theo tòa nhà"
					>
						<option value="ALL">Tất cả tòa nhà</option>
						{buildingsList.map((b) => (
							<option key={b.id || b.name} value={b.name}>
								{b.name}
							</option>
						))}
					</select>
				</div>

				{/* Floor Options Selector */}
				<div className="zone-toolbar__group">
					<Layers size={16} className="text-secondary" />
					<select
						className="zone-select"
						value={selectedFloor}
						onChange={(e) => {
							setSelectedFloor(e.target.value);
							setSelectedAreaId(null);
						}}
						title="Lọc khu vực theo tầng"
					>
						<option value="ALL">Tất cả tầng</option>
						{availableFloors.map((fl) => (
							<option key={fl} value={fl}>
								{fl}
							</option>
						))}
					</select>
				</div>

				<div className="zone-toolbar__spacer" />

				<div className="zone-toolbar__actions">
					<div className="zone-view-toggle">
						<button
							type="button"
							className="zone-view-toggle__btn zone-view-toggle__btn--active"
							title="Bản đồ địa lý toàn cảnh khuôn viên ngoài trời (OpenStreetMap Standard)"
						>
							<Compass size={15} />
							<span>Khuôn viên (Bản đồ số)</span>
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

			{/* OUTDOOR CAMPUS MAP */}
			{!loading && (
				<CampusMapView
					areas={filteredAreas}
					selectedAreaId={selectedAreaId}
					cameraCounts={cameraCounts}
					isAdmin={isAdmin}
					isFacilityManager={isFacilityManager}
					onSelectArea={handleSelectArea}
				/>
			)}
		</div>
	);
}
