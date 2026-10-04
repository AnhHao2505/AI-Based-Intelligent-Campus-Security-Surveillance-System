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
import { getLevelConfig, getAccessLevelConfig } from "../../utils/areaHelpers";

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
							: `trên ${selectedFloor === "G" ? "Tầng Trệt" : `Tầng ${selectedFloor}`}`}{" "}
						(Tòa {selectedBuilding})
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
				<div className="area-grid">
					{floorAreas.map((area) => {
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
										<span
											className={
												getAccessLevelConfig(area.areaAccessLevel).className
											}
											title={
												area.explicitAuthorizationRequired
													? "Cấp tối thiểu để được gửi đơn xin vào (không cho vào tự do)"
													: "Cấp tối thiểu để được vào tự do"
											}
										>
											{getAccessLevelConfig(area.areaAccessLevel).label}
										</span>
										{area.explicitAuthorizationRequired && (
											<span
												className="zone-card__pill-explicit"
												title="Khu vực bắt buộc chỉ định nhân sự đích danh"
											>
												Chỉ định
											</span>
										)}
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

								<h3 className="zone-card__title">{area.name}</h3>

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
													<span>Camera: {count}</span>
												</span>
											) : (
												<span className="zone-card__camera-status--none">
													<VideoOff size={13} />
													<span>Camera: 0</span>
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

										{isFacilityManager && (
											<button
												type="button"
												className="zone-card__quick-btn"
												onClick={(e) => {
													e.stopPropagation();
													onOpenAccessRulesModal(area);
												}}
												title="Cấu hình quy tắc truy cập"
												aria-label="Cấu hình quy tắc truy cập"
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
													title="Vô hiệu hoá"
													aria-label="Vô hiệu hoá"
												>
													<Ban size={13} />
												</button>
											</>
										)}
									</div>
								</div>
							</div>
						);
					})}
				</div>
			)}
		</div>
	);
}
