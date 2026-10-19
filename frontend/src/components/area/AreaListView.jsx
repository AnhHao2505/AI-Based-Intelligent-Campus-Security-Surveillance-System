import React from "react";
import {
	AlertCircle,
	Plus,
	Cctv,
	VideoOff,
	ShieldCheck,
	Users,
	Pencil,
	Ban,
} from "lucide-react";
import { getLevelConfig } from "../../utils/areaHelpers";
import { formatBuilding, formatFloor } from "../../utils/formatLocation";

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
	INTERNAL_CONFIDENTIAL: 2,
	CONFIDENTIAL_CONTACT_REQUIRED: 3,
	HIGHLY_CONFIDENTIAL: 4,
};

const LEVEL_SECTIONS = [
	{
		key: "LEVEL_1",
		levelNumber: 1,
		title: "Khu vực Công khai",
		badgeLabel: "Cấp 1",
		dotColor: "var(--theme-success)",
		badgeColor: "var(--theme-success-text)",
		badgeBg: "var(--theme-success-bg)",
		badgeBorder: "var(--theme-success-border)",
		filter: (a) => a.areaAccessLevel === 1 || a.areaLevel === "PUBLIC",
	},
	{
		key: "LEVEL_2",
		levelNumber: 2,
		title: "Khu vực Bảo mật nội bộ & Cần liên hệ",
		badgeLabel: "Cấp 2",
		dotColor: "var(--theme-info)",
		badgeColor: "var(--theme-info-text)",
		badgeBg: "var(--theme-info-bg)",
		badgeBorder: "var(--theme-info-border)",
		filter: (a) =>
			a.areaAccessLevel === 2 ||
			a.areaLevel === "INTERNAL_CONFIDENTIAL" ||
			a.areaLevel === "CONFIDENTIAL_CONTACT_REQUIRED",
	},
	{
		key: "LEVEL_3",
		levelNumber: 3,
		title: "Khu vực Tuyệt mật",
		badgeLabel: "Cấp 3",
		dotColor: "var(--theme-danger)",
		badgeColor: "var(--theme-danger-text)",
		badgeBg: "var(--theme-danger-bg)",
		badgeBorder: "var(--theme-danger-border)",
		filter: (a) =>
			a.areaAccessLevel === 3 || a.areaLevel === "HIGHLY_CONFIDENTIAL",
	},
];

export default function AreaListView({
	floorAreas,
	selectedFloor,
	selectedBuilding,
	selectedAreaId,
	cameraCounts,
	isAdmin,
	isFacilityManager,
	levelPresets,
	onSelectArea,
	onOpenCreateModal,
	onOpenCamerasModal,
	onOpenAccessRulesModal,
	onOpenAssignedPersonnelModal,
	onOpenEditModal,
	onOpenDeactivateModal,
}) {
	const renderAreaCard = (area) => {
		const isSelected = selectedAreaId === area.id;
		const levelKey =
			area.areaLevel || area.level?.code || area.level || "PUBLIC";
		const levelConfig = getLevelConfig(levelKey);

		return (
			<div
				key={area.id}
				className={`zone-card ${levelConfig.cardClass} ${
					isSelected ? "zone-card--selected" : ""
				}`}
				onClick={() => onSelectArea(area.id)}
			>
				<div className="zone-card__header">
					<div className="zone-card__badges">
						<span className={`level-badge ${levelConfig.badgeClass}`}>
							{levelConfig.badgeLabel}
						</span>
						{area.eventActive && area.openUntil && (
							<span
								className="zone-card__pill-event"
								title={`Chế độ sự kiện đang mở đến ${formatEventUntil(area.openUntil)}`}
								style={{
									display: "inline-flex",
									alignItems: "center",
									padding: "2px 8px",
									borderRadius: "12px",
									fontSize: "11px",
									fontWeight: 600,
									background: "rgba(147, 51, 234, 0.12)",
									color: "#9333ea",
									border: "1px solid rgba(147, 51, 234, 0.3)",
								}}
							>
								Đang mở sự kiện đến {formatEventUntil(area.openUntil)}
							</span>
						)}
						{area.upcomingScheduleCount > 0 && (
							<span
								className="zone-card__pill-schedule"
								title="Số lịch sự kiện đã đặt, chưa bắt đầu"
							>
								{area.upcomingScheduleCount} lịch sự kiện
							</span>
						)}
					</div>
				</div>

				<h3 className="zone-card__title" title={area.name}>
					{area.name}
				</h3>

				<div className="zone-card__footer">
					<div
						className="zone-card__camera-status"
						style={{ cursor: "pointer" }}
						onClick={(e) => {
							e.stopPropagation();
							onOpenCamerasModal(area);
						}}
						title="Xem danh sách camera"
					>
						{(() => {
							const count =
								cameraCounts[area.id] ?? area.cameraCount ?? 0;
							return count > 0 ? (
								<span className="zone-card__camera-status--has">
									<Cctv size={13} />
									<span>{count}</span>
								</span>
							) : (
								<span className="zone-card__camera-status--none">
									<VideoOff size={13} />
									<span>0</span>
								</span>
							);
						})()}
					</div>

					<div className="zone-card__quick-actions">
						<button
							type="button"
							className="zone-card__quick-btn"
							onClick={(e) => {
								e.stopPropagation();
								onOpenCamerasModal(area);
							}}
							title="Xem danh sách Camera"
							aria-label="Xem danh sách Camera"
						>
							<Cctv size={13} />
						</button>

						{(isAdmin || isFacilityManager) && (
							<button
								type="button"
								className="zone-card__quick-btn"
								onClick={(e) => {
									e.stopPropagation();
									onOpenAccessRulesModal(area);
								}}
								title={
									isAdmin
										? "Đổi cấp truy cập khu vực"
										: "Chế độ sự kiện và lịch"
								}
								aria-label={
									isAdmin
										? "Đổi cấp truy cập khu vực"
										: "Chế độ sự kiện và lịch"
								}
							>
								<ShieldCheck size={13} />
							</button>
						)}

						{isFacilityManager && levelKey !== "PUBLIC" && (
							<button
								type="button"
								className="zone-card__quick-btn"
								onClick={(e) => {
									e.stopPropagation();
									onOpenAssignedPersonnelModal(area);
								}}
								title="Danh sách nhân viên chỉ định"
								aria-label="Danh sách nhân viên chỉ định"
							>
								<Users size={13} />
							</button>
						)}

						{isAdmin && (
							<>
								<button
									type="button"
									className="zone-card__quick-btn"
									onClick={(e) => {
										e.stopPropagation();
										onSelectArea(area.id);
										onOpenEditModal(area);
									}}
									title="Sửa khu vực"
									aria-label="Sửa khu vực"
								>
									<Pencil size={13} />
								</button>
								<button
									type="button"
									className="zone-card__quick-btn zone-card__quick-btn--danger"
									onClick={(e) => {
										e.stopPropagation();
										onSelectArea(area.id);
										onOpenDeactivateModal(area);
									}}
									title="Vô hiệu hóa"
									aria-label="Vô hiệu hóa"
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
		<div className="zone-list-layout">
			{floorAreas.length === 0 ? (
				<div className="area-empty-state">
					<div className="area-empty-state__icon">
						<AlertCircle size={28} />
					</div>
					<div className="area-empty-state__title">
						Chưa có khu vực nào{" "}
						{selectedFloor === "ALL"
							? "trong"
							: `trên ${formatFloor(selectedFloor)}`}{" "}
						({formatBuilding(selectedBuilding)})
					</div>
					{isAdmin && (
						<button
							type="button"
							className="zone-toolbar__add-btn"
							style={{ marginTop: "12px" }}
							onClick={onOpenCreateModal}
						>
							<Plus size={16} />
							<span>Thêm khu vực đầu tiên</span>
						</button>
					)}
				</div>
			) : (
				<div className="area-level-sections-container">
					{LEVEL_SECTIONS.map((section) => {
						const areasInSection = floorAreas
							.filter(section.filter)
							.sort((a, b) => {
								const typeA = a.areaLevel || a.level?.code || a.level || "";
								const typeB = b.areaLevel || b.level?.code || b.level || "";
								const prioA = TYPE_PRIORITY[typeA] ?? 99;
								const prioB = TYPE_PRIORITY[typeB] ?? 99;
								if (prioA !== prioB) {
									return prioA - prioB;
								}
								return (a.name || "").localeCompare(b.name || "", "vi");
							});

						if (areasInSection.length === 0) return null;

						return (
							<section key={section.key} className="area-level-section">
								<div className="area-level-section__header">
									<div className="area-level-section__title-group">
										<span
											className="area-level-section__dot"
											style={{ backgroundColor: section.dotColor }}
										/>
										<h2 className="area-level-section__title">
											{section.title}
										</h2>
										<span
											className="area-level-section__badge"
											style={{
												color: section.badgeColor,
												backgroundColor: section.badgeBg,
												borderColor: section.badgeBorder,
											}}
										>
											{section.badgeLabel}
										</span>
										<span className="area-level-section__count">
											({areasInSection.length} khu vực)
										</span>
									</div>
								</div>

								<div className="area-grid">
									{areasInSection.map(renderAreaCard)}
								</div>
							</section>
						);
					})}

					{/* Fallback nếu có khu vực chưa xác định cấp độ */}
					{(() => {
						const otherAreas = floorAreas
							.filter((a) => !LEVEL_SECTIONS.some((sec) => sec.filter(a)))
							.sort((a, b) => (a.name || "").localeCompare(b.name || "", "vi"));

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
											({otherAreas.length} khu vực)
										</span>
									</div>
								</div>
								<div className="area-grid">
									{otherAreas.map(renderAreaCard)}
								</div>
							</section>
						);
					})()}
				</div>
			)}
		</div>
	);
}
