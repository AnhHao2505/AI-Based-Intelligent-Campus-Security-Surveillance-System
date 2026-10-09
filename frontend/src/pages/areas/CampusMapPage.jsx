import React, { useState, useEffect, useMemo, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import { Compass, Building2, AlertCircle, Loader2, Layers } from "lucide-react";
import CampusMapView from "../../components/area/CampusMapView";
import ErrorBoundary from "../../components/common/ErrorBoundary";
import PageHeader from "../../components/ui/PageHeader";
import "../../components/ui/Button.css";
import { getAreas, getAreaCameras } from "../../services/areaService";
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
				(b) => (b.name || "").toUpperCase() === selectedBuilding.toUpperCase(),
			);
			if (bObj?.floors?.length > 0) {
				bObj.floors.forEach((f) => {
					if (f?.name) fSet.add(String(f.name));
				});
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
				(b.floors || []).forEach((f) => {
					if (f?.name) fSet.add(String(f.name));
				});
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
			const la = String(a).toLowerCase();
			const lb = String(b).toLowerCase();
			if (la.includes("trệt")) return -1;
			if (lb.includes("trệt")) return 1;
			return la.localeCompare(lb);
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

	// Cập nhật cục bộ sau khi lưu tọa độ (không nạp lại để giữ khung nhìn bản đồ)
	const handleAreaUpdated = useCallback((areaId, updated) => {
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
						}
					: a,
			),
		);
		setSelectedAreaId(areaId);
	}, []);

	return (
		<div className="zone-page">
			{/* Page Header */}
			<PageHeader
				title="Bản đồ khuôn viên"
				description="Bản đồ an ninh toàn diện và giám sát khu vực theo toạ độ địa lý."
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
							setSelectedFloor("ALL");
							setSelectedAreaId(null);
						}}
						title="Lọc khu vực theo tòa nhà"
					>
						<option value="ALL">Tất cả tòa nhà</option>
						{buildingsList.map((b) => (
							<option
								key={b.id || b.name}
								value={b.name}
							>
								{b.name}
							</option>
						))}
					</select>
				</div>

				{/* Floor Options Selector */}
				<div className="zone-toolbar__building">
					<Layers
						size={16}
						className="zone-toolbar__building-icon"
					/>
					<select
						className="zone-toolbar__building-select"
						value={selectedFloor}
						onChange={(e) => {
							setSelectedFloor(e.target.value);
							setSelectedAreaId(null);
						}}
						title="Lọc khu vực theo tầng"
					>
						<option value="ALL">Tất cả tầng</option>
						{availableFloors.map((fl) => (
							<option
								key={fl}
								value={fl}
							>
								{fl}
							</option>
						))}
					</select>
				</div>
				<div className="zone-toolbar__spacer" />
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
				<ErrorBoundary>
					<CampusMapView
						areas={filteredAreas}
						selectedAreaId={selectedAreaId}
						cameraCounts={cameraCounts}
						isAdmin={isAdmin}
						isFacilityManager={isFacilityManager}
						onSelectArea={handleSelectArea}
						onAreaUpdated={handleAreaUpdated}
					/>
				</ErrorBoundary>
			)}
			<p>
				Lưu ý, các ghim vị trí trên bản đồ chỉ mang tính chất tương đối, nó có
				thể bị lệch khi view để đảm bảo các ghim vị trí không chồng lên nhau,
				nhưng tọa độ tuyệt đối bạn đã lưu cho khu vực vẫn sẽ được dùng để điều
				hướng bảo vệ tới khu vực xảy ra sự cố
			</p>
		</div>
	);
}
