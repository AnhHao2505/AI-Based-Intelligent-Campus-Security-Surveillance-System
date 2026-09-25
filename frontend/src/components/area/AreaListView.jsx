import React from "react";
import {
	AlertCircle,
	Plus,
	Cctv,
	VideoOff,
	ShieldCheck,
	Users,
	Pencil,
	Trash2,
} from "lucide-react";
import { getLevelConfig, AREA_LEVEL_CONFIG } from "../../utils/areaHelpers";

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
						Chưa có khu vực nào trên Tầng {selectedFloor} (Tòa{" "}
						{selectedBuilding})
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
											className="zone-card__pill-level"
											title="Cấp độ người dùng tối thiểu để vào tự do"
										>
											Level {area.areaAccessLevel ?? 1}
										</span>
										{area.explicitAuthorizationRequired && (
											<span
												className="zone-card__pill-explicit"
												title="Khu vực bắt buộc chỉ định nhân sự đích danh"
											>
												Chỉ định
											</span>
										)}
										{area.differsFromPreset && (
											<span
												className="zone-card__pill-differs"
												title="Quy tắc truy cập của khu vực này khác với giá trị mặc định của loại khu vực"
												style={{
													display: "inline-flex",
													alignItems: "center",
													padding: "2px 8px",
													borderRadius: "12px",
													fontSize: "11px",
													fontWeight: 600,
													background: "rgba(234, 88, 12, 0.12)",
													color: "var(--theme-warning, #ea580c)",
													border: "1px solid rgba(234, 88, 12, 0.3)",
												}}
											>
												Khác mặc định
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
												>
													<Trash2 size={13} />
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
			{/* Quy định Cấp độ An ninh & Danh sách nhân viên (4 Loại phòng) */}
			<div className="zone-list-info-banner">
				<div className="zone-list-info-banner__header">
					<div className="zone-list-info-banner__header-icon">
						<ShieldCheck size={20} />
					</div>
					<div>
						<h4 className="zone-list-info-banner__title">
							Quy định quyền truy cập
						</h4>
						<p className="zone-list-info-banner__subtitle">
							Chi tiết về điều kiện ra vào
						</p>
					</div>
				</div>

				<div className="zone-list-info-banner__grid">
					{[
						"PUBLIC",
						"INTERNAL_CONFIDENTIAL",
						"CONFIDENTIAL_CONTACT_REQUIRED",
						"HIGHLY_CONFIDENTIAL",
					].map((key) => {
						const config = AREA_LEVEL_CONFIG[key] || getLevelConfig(key);
						const preset = levelPresets?.[key];
						const cardModifier =
							key === "PUBLIC"
								? "zone-list-info-banner__card--public"
								: key === "INTERNAL_CONFIDENTIAL"
									? "zone-list-info-banner__card--internal"
									: key === "CONFIDENTIAL_CONTACT_REQUIRED"
										? "zone-list-info-banner__card--contact"
										: "zone-list-info-banner__card--private";

						const hasLevel = preset && preset.areaAccessLevel != null;
						const levelLabel = hasLevel
							? `Level ${preset.areaAccessLevel}${preset.explicitAuthorizationRequired ? " · Chỉ định" : "+"}`
							: null;

						let explicitTag = null;
						if (key === "PUBLIC") {
							explicitTag = (
								<span className="zone-list-info-banner__personnel-tag zone-list-info-banner__personnel-tag--none">
									Không áp dụng
								</span>
							);
						} else if (!preset) {
							explicitTag = (
								<span className="zone-list-info-banner__personnel-tag zone-list-info-banner__personnel-tag--none">
									—
								</span>
							);
						} else if (preset.explicitAuthorizationRequired) {
							explicitTag = (
								<span className="zone-list-info-banner__personnel-tag zone-list-info-banner__personnel-tag--has">
									<Users size={11} /> Bắt buộc chỉ định
								</span>
							);
						} else {
							explicitTag = (
								<span className="zone-list-info-banner__personnel-tag zone-list-info-banner__personnel-tag--none">
									Không bắt buộc
								</span>
							);
						}

						return (
							<div
								key={key}
								className={`zone-list-info-banner__card ${cardModifier}`}
							>
								<div className="zone-list-info-banner__card-header">
									<span
										className={`zone-list-info-banner__card-badge level-badge ${config.badgeClass}`}
									>
										{config.badgeLabel}
									</span>
									{levelLabel && (
										<span className="zone-list-info-banner__card-level">
											{levelLabel}
										</span>
									)}
								</div>
								<div className="zone-list-info-banner__card-desc">
									{config.description}
								</div>
								<div className="zone-list-info-banner__card-personnel">
									<span>Chỉ định đích danh:</span>
									{explicitTag}
								</div>
							</div>
						);
					})}
				</div>

				<div className="zone-list-info-banner__footer">
					<Users
						size={14}
						className="zone-list-info-banner__footer-icon"
					/>
					<span>
						<strong>Lưu ý về nút quản lý Nhân sự chỉ định:</strong> Biểu tượng
						nút Nhân sự (
						<Users
							size={12}
							style={{ display: "inline", margin: "0 2px" }}
						/>
						) hiển thị trên thẻ của mọi phòng trừ loại{" "}
						<strong>{AREA_LEVEL_CONFIG.PUBLIC.name} (PUBLIC)</strong> để cấp quyền ra vào cho nhân
						sự.
					</span>
				</div>
			</div>
		</div>
	);
}
