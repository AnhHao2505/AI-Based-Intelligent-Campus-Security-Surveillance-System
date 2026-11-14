import { useMemo, useState } from "react";
import {
	Search,
	X,
	Video,
	Power,
	PowerOff,
	Trash2,
	Plus,
	MapPin,
	Filter,
	Loader2,
} from "lucide-react";
import { LoadingState, ErrorState } from "../ui";
import { formatLocation } from "../../utils/formatLocation";
import {
	extractCameraAreaInfo,
	filterWorkspaceCameras,
} from "../../utils/campusCameraHelpers";

export default function CameraWorkspaceSidebar({
	cameras = [],
	areas = [],
	loading = false,
	error = null,
	onRetry,
	selectedCameraId = null,
	onSelectCamera,
	selectedBuilding = "ALL",
	selectedFloor = "ALL",
	isAdmin = false,
	onOpenCreateCamera,
	onToggleCameraStatus,
	onDeleteCamera,
	actionLoadingId = null,
}) {
	const [search, setSearch] = useState("");
	const [opStatusFilter, setOpStatusFilter] = useState("ALL");
	const [statusFilter, setStatusFilter] = useState("ALL");
	const [scopeToMapFilter, setScopeToMapFilter] = useState(false);

	const hasMapScope =
		selectedBuilding !== "ALL" || selectedFloor !== "ALL";

	const areaById = useMemo(() => {
		const map = new Map();
		areas.forEach((a) => {
			if (a?.id) map.set(a.id, a);
		});
		return map;
	}, [areas]);

	const filteredCameras = useMemo(
		() =>
			filterWorkspaceCameras({
				cameras,
				areas,
				search,
				statusFilter,
				opStatusFilter,
				selectedBuilding,
				selectedFloor,
				scopeToMapFilter: hasMapScope && scopeToMapFilter,
			}),
		[
			cameras,
			areas,
			search,
			statusFilter,
			opStatusFilter,
			selectedBuilding,
			selectedFloor,
			hasMapScope,
			scopeToMapFilter,
		],
	);

	const counts = useMemo(() => {
		let online = 0;
		let offline = 0;
		let inactive = 0;
		cameras.forEach((cam) => {
			if (cam.status === "DECOMMISSIONED") {
				inactive += 1;
			} else if (cam.operationalStatus === "ONLINE") {
				online += 1;
			} else {
				offline += 1;
			}
		});
		return { all: cameras.length, online, offline, inactive };
	}, [cameras]);

	return (
		<div className="zone-sidebar-wrapper">
			{/* Search Bar */}
			<div className="zone-sidebar-search-box">
				<Search size={13} className="zone-sidebar-search-icon" />
				<input
					type="text"
					className="zone-sidebar-search-input"
					placeholder="Tìm mã CAM, tên hoặc khu vực..."
					value={search}
					onChange={(e) => setSearch(e.target.value)}
					aria-label="Tìm camera trong danh sách"
				/>
				{search && (
					<button
						type="button"
						className="zone-sidebar-search-clear"
						onClick={() => setSearch("")}
						aria-label="Xóa từ khóa tìm kiếm"
					>
						<X size={12} />
					</button>
				)}
			</div>

			{/* Connection & Lifecycle Filters */}
			<div
				className="zone-level-filter-bar"
				role="toolbar"
				aria-label="Lọc trạng thái camera"
			>
				<button
					type="button"
					className={`zone-level-pill ${opStatusFilter === "ALL" && statusFilter === "ALL" ? "zone-level-pill--active" : ""}`}
					onClick={() => {
						setOpStatusFilter("ALL");
						setStatusFilter("ALL");
					}}
				>
					<span className="zone-level-pill__label">Tất cả</span>
					<span className="zone-level-pill__count">{counts.all}</span>
				</button>
				<button
					type="button"
					className={`zone-level-pill ${opStatusFilter === "ONLINE" ? "zone-level-pill--active" : ""}`}
					onClick={() => {
						setStatusFilter("ALL");
						setOpStatusFilter(opStatusFilter === "ONLINE" ? "ALL" : "ONLINE");
					}}
				>
					<span
						className="zone-level-pill__dot"
						style={{ backgroundColor: "#10b981" }}
					/>
					<span className="zone-level-pill__label">Online</span>
					<span className="zone-level-pill__count">{counts.online}</span>
				</button>
				<button
					type="button"
					className={`zone-level-pill ${opStatusFilter === "OFFLINE" ? "zone-level-pill--active" : ""}`}
					onClick={() => {
						setStatusFilter("ALL");
						setOpStatusFilter(opStatusFilter === "OFFLINE" ? "ALL" : "OFFLINE");
					}}
				>
					<span
						className="zone-level-pill__dot"
						style={{ backgroundColor: "#ef4444" }}
					/>
					<span className="zone-level-pill__label">Offline</span>
					<span className="zone-level-pill__count">{counts.offline}</span>
				</button>
				{counts.inactive > 0 && (
					<button
						type="button"
						className={`zone-level-pill ${statusFilter === "DECOMMISSIONED" ? "zone-level-pill--active" : ""}`}
						onClick={() => {
							setOpStatusFilter("ALL");
							setStatusFilter(
								statusFilter === "DECOMMISSIONED" ? "ALL" : "DECOMMISSIONED",
							);
						}}
					>
						<span
							className="zone-level-pill__dot"
							style={{ backgroundColor: "#64748b" }}
						/>
						<span className="zone-level-pill__label">Đã tắt</span>
						<span className="zone-level-pill__count">{counts.inactive}</span>
					</button>
				)}
			</div>

			{/* Map Filter Scope Toggle when Building/Floor is filtered on 3D map */}
			{hasMapScope && (
				<div className="cam-sidebar-scope-bar">
					<button
						type="button"
						className={`cam-sidebar-scope-chip ${scopeToMapFilter ? "cam-sidebar-scope-chip--active" : ""}`}
						onClick={() => setScopeToMapFilter((v) => !v)}
					>
						<Filter size={11} />
						<span>
							{scopeToMapFilter
								? `Đang lọc theo: ${selectedBuilding === "ALL" ? "" : selectedBuilding}${selectedFloor !== "ALL" ? ` · ${selectedFloor}` : ""}`
								: "Đang hiện toàn trường — Bấm để lọc theo tầng bản đồ"}
						</span>
					</button>
				</div>
			)}

			{/* Camera List Content */}
			<div className="zone-sidebar-content">
				{loading && <LoadingState size="sm" text="Đang tải danh sách camera..." />}

				{!loading && error && (
					<ErrorState size="sm" message={error} onRetry={onRetry} />
				)}

				{!loading && !error && filteredCameras.length === 0 && (
					<div className="area-empty-state">
						<div className="area-empty-state__icon">
							<Video size={22} />
						</div>
						<div className="area-empty-state__title">
							{search.trim() || opStatusFilter !== "ALL" || statusFilter !== "ALL"
								? "Không tìm thấy camera khớp bộ lọc"
								: "Chưa có camera nào trong danh sách"}
						</div>
						{isAdmin && onOpenCreateCamera && !search.trim() && (
							<button
								type="button"
								className="zone-toolbar__add-btn"
								style={{ marginTop: "10px" }}
								onClick={() => onOpenCreateCamera()}
							>
								<Plus size={14} />
								<span>Thêm camera mới</span>
							</button>
						)}
					</div>
				)}

				{!loading && !error && filteredCameras.length > 0 && (
					<div className="area-compact-list">
						{filteredCameras.map((cam) => {
							const isSelected = selectedCameraId === cam.id;
							const isDecommissioned = cam.status === "DECOMMISSIONED";
							const isOnline =
								!isDecommissioned && cam.operationalStatus === "ONLINE";
							const info = extractCameraAreaInfo(cam);
							const resolvedArea = info.areaId ? areaById.get(info.areaId) : null;
							const areaName = resolvedArea?.name || info.areaName;
							const locStr = formatLocation(
								resolvedArea?.building || info.building,
								resolvedArea?.floor || info.floor,
							);
							const isBusy = actionLoadingId === cam.id;

							return (
								<div
									key={cam.id}
									className={`zone-item-row cam-sidebar-row ${isSelected ? "zone-item-row--selected" : ""}`}
								>
									<button
										type="button"
										className="zone-item-row__hit-area"
										aria-label={`Xem camera ${cam.name || cam.cameraCode}`}
										aria-pressed={isSelected}
										onClick={() => onSelectCamera?.(cam)}
									/>
									<div className="zone-item-row__info">
										<div className="zone-item-row__title-wrap">
											<span
												className={`cam-status-dot ${
													isDecommissioned
														? "cam-status-dot--inactive"
														: isOnline
															? "cam-status-dot--online"
															: "cam-status-dot--offline"
												}`}
												title={
													isDecommissioned
														? "Đã tắt (INACTIVE)"
														: isOnline
															? "Online"
															: "Offline"
												}
											/>
											<span className="zone-item-row__title" title={cam.name}>
												{cam.name}
											</span>
											<span className="cam-sidebar-code-chip">
												{cam.cameraCode}
											</span>
										</div>
										<div
											className="zone-item-row__location"
											title={
												areaName
													? `${areaName}${locStr ? ` · ${locStr}` : ""}`
													: "Chưa gán khu vực"
											}
										>
											<MapPin size={10} style={{ display: "inline", marginRight: 3 }} />
											{areaName
												? `${areaName}${locStr ? ` · ${locStr}` : ""}`
												: "Chưa gán khu vực"}
										</div>
									</div>

									<div
										className="zone-item-row__meta"
										onClick={(e) => e.stopPropagation()}
									>
										{isAdmin && (
											<div className="zone-item-row__actions">
												<button
													type="button"
													className="zone-item-row__action-btn"
													onClick={() => onToggleCameraStatus?.(cam)}
													disabled={isBusy}
													title={isDecommissioned ? "Kích hoạt lại" : "Tắt camera"}
													aria-label={
														isDecommissioned
															? `Kích hoạt lại ${cam.name}`
															: `Tắt ${cam.name}`
													}
												>
													{isBusy ? (
														<Loader2 size={12} className="animate-spin" />
													) : isDecommissioned ? (
														<Power size={13} />
													) : (
														<PowerOff size={13} />
													)}
												</button>
												<button
													type="button"
													className="zone-item-row__action-btn zone-item-row__action-btn--danger"
													onClick={() => onDeleteCamera?.(cam)}
													disabled={isBusy}
													title="Xóa camera"
													aria-label={`Xóa camera ${cam.name}`}
												>
													<Trash2 size={13} />
												</button>
											</div>
										)}
									</div>
								</div>
							);
						})}
					</div>
				)}
			</div>
		</div>
	);
}
