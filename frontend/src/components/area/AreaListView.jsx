import React, { useState, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import {
	Building2,
	Cctv,
	VideoOff,
	Users,
	Pencil,
	Ban,
	Layers,
	Archive,
	Search,
	X,
	RotateCcw,
	AlertCircle,
} from "lucide-react";
import { LoadingState, EmptyState, ErrorState } from "../ui";
import { getLevelConfig } from "../../utils/areaHelpers";
import { formatLocation } from "../../utils/formatLocation";

const formatEventUntil = (openUntil) => {
	if (!openUntil) return "";
	const d = new Date(openUntil);
	const timeStr = d.toLocaleTimeString("vi-VN", {
		hour: "2-digit",
		minute: "2-digit",
	});
	const today = new Date();
	const isToday =
		d.getDate() === today.getDate() &&
		d.getMonth() === today.getMonth() &&
		d.getFullYear() === today.getFullYear();
	return isToday
		? timeStr
		: `${timeStr} (${d.toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit" })})`;
};

const TYPE_PRIORITY = {
	PUBLIC: 1,
	CONFIDENTIAL: 2,
	HIGHLY_CONFIDENTIAL: 3,
};

const LEVEL_SECTIONS = [
	{
		key: "LEVEL_1",
		levelNumber: 1,
		title: "Khu vực Công khai",
		badgeLabel: "Cấp 1",
		dotColor: "#10b981",
		filter: (a) => a.areaAccessLevel === 1 || a.areaLevel === "PUBLIC",
	},
	{
		key: "LEVEL_2",
		levelNumber: 2,
		title: "Khu vực Bảo mật",
		badgeLabel: "Cấp 2",
		dotColor: "#0284c7",
		filter: (a) => a.areaAccessLevel === 2 || a.areaLevel === "CONFIDENTIAL",
	},
	{
		key: "LEVEL_3",
		levelNumber: 3,
		title: "Khu vực Tuyệt mật",
		badgeLabel: "Cấp 3",
		dotColor: "#e11d48",
		filter: (a) =>
			a.areaAccessLevel === 3 || a.areaLevel === "HIGHLY_CONFIDENTIAL",
	},
];

export default function AreaListView({
	floorAreas = [],
	selectedFloor,
	selectedBuilding,
	selectedAreaId,
	cameraCounts = {},
	isAdmin = false,
	isFacilityManager = false,
	onSelectArea,
	onOpenCreateModal,
	onOpenCamerasModal,
	onOpenAssignedPersonnelModal,
	onOpenEditModal,
	onOpenDeactivateModal,
	showDeactivated = false,
	onToggleShowDeactivated,
	deactivatedAreas = [],
	deactivatedLoading = false,
	deactivatedError = null,
	onFetchDeactivated,
	onOpenRestoreModal,
}) {
	const navigate = useNavigate();
	const [searchFilter, setSearchFilter] = useState("");

	// Lọc danh sách khu vực hoạt động theo ô tìm kiếm
	const filteredActiveAreas = useMemo(() => {
		if (!searchFilter.trim()) return floorAreas;
		const q = searchFilter.toLowerCase().trim();
		return floorAreas.filter(
			(a) =>
				(a.name || "").toLowerCase().includes(q) ||
				(a.building || "").toLowerCase().includes(q) ||
				(a.floor || "").toLowerCase().includes(q),
		);
	}, [floorAreas, searchFilter]);

	// Lọc danh sách khu vực vô hiệu hóa theo ô tìm kiếm
	const filteredDeactivatedAreas = useMemo(() => {
		if (!searchFilter.trim()) return deactivatedAreas;
		const q = searchFilter.toLowerCase().trim();
		return deactivatedAreas.filter(
			(a) =>
				(a.name || "").toLowerCase().includes(q) ||
				(a.building || "").toLowerCase().includes(q) ||
				(a.floor || "").toLowerCase().includes(q),
		);
	}, [deactivatedAreas, searchFilter]);

	// Render một thẻ khu vực chuẩn High-Density
	const renderCompactCard = (area) => {
		const isSelected = selectedAreaId === area.id;
		const levelKey =
			area.areaLevel || area.level?.code || area.level || "PUBLIC";
		const levelConfig = getLevelConfig(levelKey);
		const camCount = cameraCounts[area.id] ?? area.cameraCount ?? 0;
		const locationStr = formatLocation(area.building, area.floor);

		return (
			<div
				key={area.id}
				role="button"
				tabIndex={0}
				aria-label={`Khu vực ${area.name}, ${levelConfig.name}`}
				className={`zone-card-compact ${isSelected ? "zone-card-compact--selected" : ""}`}
				onClick={() => onSelectArea(area.id)}
				onKeyDown={(e) => {
					if (e.key === "Enter" || e.key === " ") {
						e.preventDefault();
						onSelectArea(area.id);
					}
				}}
			>
				{/* Header: Level Badge & Status Pills */}
				<div className="zone-card-compact__header">
					<div className="zone-card-compact__badges">
						<span className={`level-badge ${levelConfig.badgeClass}`}>
							{levelConfig.badgeLabel}
						</span>
						{area.eventActive && area.openUntil && (
							<span
								className="zone-card__pill-event"
								title={`Chế độ sự kiện đang mở đến ${formatEventUntil(area.openUntil)}`}
							>
								Sự kiện
							</span>
						)}
						{area.upcomingScheduleCount > 0 && (
							<button
								type="button"
								className="zone-card__pill-schedule"
								title="Số lịch sự kiện đã đặt — bấm để xem ở Quản lý sự kiện"
								onClick={(e) => {
									e.stopPropagation();
									navigate(`/admin/events?areaId=${area.id}&status=SCHEDULED`);
								}}
							>
								{area.upcomingScheduleCount} lịch
							</button>
						)}
					</div>
				</div>

				{/* Body: Tên khu vực & Vị trí tòa nhà/tầng */}
				<div className="zone-card-compact__body">
					<h3 className="zone-card-compact__title" title={area.name}>
						{area.name}
					</h3>
					<div
						className="zone-card-compact__location"
						title={locationStr || "Chưa gán tòa nhà / tầng"}
					>
						<Building2 size={12} className="zone-card-compact__loc-icon" />
						<span>{locationStr || "Chưa gán tòa/tầng"}</span>
					</div>
				</div>

				{/* Footer: Telemetry Camera & Quick Actions */}
				<div className="zone-card-compact__footer">
					<div
						className="zone-card-compact__telemetry"
						onClick={(e) => {
							e.stopPropagation();
							onOpenCamerasModal(area);
						}}
						title="Xem danh sách camera thuộc khu vực"
					>
						<span
							className={`zone-card-compact__cam-pill ${
								camCount > 0 ? "zone-card-compact__cam-pill--active" : ""
							}`}
						>
							{camCount > 0 ? <Cctv size={12} /> : <VideoOff size={12} />}
							<span>{camCount} cam</span>
						</span>
					</div>

					<div className="zone-card-compact__quick-actions">
						{/* Nút xem camera: cả Admin và FM đều có */}
						<button
							type="button"
							className="zone-card-compact__action-btn"
							onClick={(e) => {
								e.stopPropagation();
								onOpenCamerasModal(area);
							}}
							title="Xem camera trong khu vực"
							aria-label={`Xem camera của ${area.name}`}
						>
							<Cctv size={14} />
						</button>

						{/* Nút gán nhân sự: Dành cho Facility Manager ở khu vực Cấp 2 & Cấp 3 */}
						{isFacilityManager && levelKey !== "PUBLIC" && (
							<button
								type="button"
								className="zone-card-compact__action-btn"
								onClick={(e) => {
									e.stopPropagation();
									onOpenAssignedPersonnelModal(area);
								}}
								title="Danh sách nhân sự chỉ định"
								aria-label={`Danh sách nhân sự chỉ định của ${area.name}`}
							>
								<Users size={14} />
							</button>
						)}

						{/* Nút Sửa & Vô hiệu hóa: Dành riêng cho ADMIN */}
						{isAdmin && (
							<>
								<button
									type="button"
									className="zone-card-compact__action-btn"
									onClick={(e) => {
										e.stopPropagation();
										onSelectArea(area.id);
										onOpenEditModal(area);
									}}
									title="Sửa khu vực"
									aria-label={`Sửa khu vực ${area.name}`}
								>
									<Pencil size={13} />
								</button>
								<button
									type="button"
									className="zone-card-compact__action-btn zone-card-compact__action-btn--danger"
									onClick={(e) => {
										e.stopPropagation();
										onSelectArea(area.id);
										onOpenDeactivateModal(area);
									}}
									title="Vô hiệu hóa khu vực"
									aria-label={`Vô hiệu hóa khu vực ${area.name}`}
								>
									<Ban size={13} />
								</button>
							</>
						)}
					</div>
				</div>
			</div>
		);
	};

	return (
		<div className="zone-sidebar-wrapper">
			{/* 1. Header Sidebar: Tabs trạng thái (Admin) hoặc Tiêu đề (FM) */}
			<div className="zone-sidebar-header">
				{isAdmin && onToggleShowDeactivated ? (
					<div
						className="zone-sidebar-tabs"
						role="tablist"
						aria-label="Lọc trạng thái khu vực"
					>
						<button
							type="button"
							role="tab"
							aria-selected={!showDeactivated}
							className={`zone-sidebar-tab ${
								!showDeactivated ? "zone-sidebar-tab--active" : ""
							}`}
							onClick={() => onToggleShowDeactivated(false)}
						>
							<Layers size={13} />
							<span>Hoạt động</span>
							<span className="zone-sidebar-tab__badge">
								{floorAreas.length}
							</span>
						</button>
						<button
							type="button"
							role="tab"
							aria-selected={showDeactivated}
							className={`zone-sidebar-tab ${
								showDeactivated ? "zone-sidebar-tab--active" : ""
							}`}
							onClick={() => onToggleShowDeactivated(true)}
						>
							<Archive size={13} />
							<span>Vô hiệu hóa</span>
							{deactivatedAreas?.length > 0 && (
								<span className="zone-sidebar-tab__badge zone-sidebar-tab__badge--muted">
									{deactivatedAreas.length}
								</span>
							)}
						</button>
					</div>
				) : (
					<div className="zone-sidebar-title-simple">
						<Layers size={14} className="zone-sidebar-title-icon" />
						<span className="zone-sidebar-title-text">Khu vực hoạt động</span>
						<span className="zone-sidebar-tab__badge">
							{floorAreas.length}
						</span>
					</div>
				)}
			</div>

			{/* 2. Search / Quick Filter Bar */}
			<div className="zone-sidebar-search-box">
				<Search size={13} className="zone-sidebar-search-icon" />
				<input
					type="text"
					className="zone-sidebar-search-input"
					placeholder="Lọc khu vực trong danh sách..."
					value={searchFilter}
					onChange={(e) => setSearchFilter(e.target.value)}
					aria-label="Lọc khu vực trong danh sách"
				/>
				{searchFilter && (
					<button
						type="button"
						className="zone-sidebar-search-clear"
						onClick={() => setSearchFilter("")}
						aria-label="Xóa bộ lọc"
					>
						<X size={12} />
					</button>
				)}
			</div>

			{/* 3. Nội dung danh sách: Đang hoạt động hoặc Đã vô hiệu hóa */}
			<div className="zone-sidebar-content">
				{showDeactivated && isAdmin ? (
					/* Danh sách khu vực đã vô hiệu hóa */
					<div className="zone-deactivated-list">
						{deactivatedLoading && (
							<LoadingState size="sm" text="Đang tải danh sách..." />
						)}
						{!deactivatedLoading && deactivatedError && (
							<ErrorState
								size="sm"
								message={deactivatedError}
								onRetry={onFetchDeactivated}
							/>
						)}
						{!deactivatedLoading &&
							!deactivatedError &&
							filteredDeactivatedAreas.length === 0 && (
								<EmptyState
									size="sm"
									title={
										searchFilter.trim()
											? "Không có khu vực vô hiệu hóa nào khớp từ khóa"
											: "Không có khu vực nào đã vô hiệu hóa"
									}
								/>
							)}
						{!deactivatedLoading &&
							!deactivatedError &&
							filteredDeactivatedAreas.length > 0 && (
								<div className="zone-deactivated-items">
									{filteredDeactivatedAreas.map((a) => {
										const levelConfig = getLevelConfig(
											a.areaLevel || a.level?.code || a.level || "PUBLIC",
										);
										return (
											<div key={a.id} className="zone-deactivated-item">
												<div className="zone-deactivated-item__info">
													<div className="zone-deactivated-item__name">
														{a.name}
													</div>
													<div className="zone-deactivated-item__meta">
														{formatLocation(a.building, a.floor) || "—"} ·{" "}
														{levelConfig.name}
													</div>
												</div>
												<button
													type="button"
													className="zone-card-compact__action-btn"
													onClick={() => onOpenRestoreModal(a)}
													title="Khôi phục khu vực"
													aria-label={`Khôi phục khu vực ${a.name}`}
												>
													<RotateCcw size={13} />
												</button>
											</div>
										);
									})}
								</div>
							)}
					</div>
				) : (
					/* Danh sách khu vực đang hoạt động */
					<>
						{filteredActiveAreas.length === 0 ? (
							<div className="area-empty-state">
								<div className="area-empty-state__icon">
									<AlertCircle size={24} />
								</div>
								<div className="area-empty-state__title">
									{searchFilter.trim()
										? "Không tìm thấy khu vực khớp từ khóa"
										: "Chưa có khu vực nào phù hợp bộ lọc"}
								</div>
								{isAdmin && !searchFilter.trim() && (
									<button
										type="button"
										className="zone-toolbar__add-btn"
										style={{ marginTop: "10px" }}
										onClick={onOpenCreateModal}
									>
										<span>Thêm khu vực đầu tiên</span>
									</button>
								)}
							</div>
						) : (
							<div className="area-level-sections-container">
								{LEVEL_SECTIONS.map((section) => {
									const areasInSection = filteredActiveAreas
										.filter(section.filter)
										.sort((a, b) => {
											const typeA =
												a.areaLevel || a.level?.code || a.level || "";
											const typeB =
												b.areaLevel || b.level?.code || b.level || "";
											const prioA = TYPE_PRIORITY[typeA] ?? 99;
											const prioB = TYPE_PRIORITY[typeB] ?? 99;
											if (prioA !== prioB) return prioA - prioB;
											return (a.name || "").localeCompare(b.name || "", "vi");
										});

									if (areasInSection.length === 0) return null;

									return (
										<section
											key={section.key}
											className="area-level-section"
										>
											<div className="area-level-section__header">
												<div className="area-level-section__title-group">
													<span
														className="area-level-section__dot"
														style={{ backgroundColor: section.dotColor }}
													/>
													<h2 className="area-level-section__title">
														{section.title}
													</h2>
													<span className="area-level-section__count">
														({areasInSection.length})
													</span>
												</div>
											</div>

											<div className="area-compact-list">
												{areasInSection.map(renderCompactCard)}
											</div>
										</section>
									);
								})}

								{/* Fallback nếu có khu vực chưa xác định cấp độ */}
								{(() => {
									const otherAreas = filteredActiveAreas
										.filter((a) => !LEVEL_SECTIONS.some((sec) => sec.filter(a)))
										.sort((a, b) =>
											(a.name || "").localeCompare(b.name || "", "vi"),
										);

									if (otherAreas.length === 0) return null;

									return (
										<section className="area-level-section">
											<div className="area-level-section__header">
												<div className="area-level-section__title-group">
													<span
														className="area-level-section__dot"
														style={{ backgroundColor: "#94a3b8" }}
													/>
													<h2 className="area-level-section__title">
														Khu vực khác
													</h2>
													<span className="area-level-section__count">
														({otherAreas.length})
													</span>
												</div>
											</div>
											<div className="area-compact-list">
												{otherAreas.map(renderCompactCard)}
											</div>
										</section>
									);
								})()}
							</div>
						)}
					</>
				)}
			</div>
		</div>
	);
}
