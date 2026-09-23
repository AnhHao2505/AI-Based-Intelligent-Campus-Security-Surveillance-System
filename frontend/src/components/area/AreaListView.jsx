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
import { getLevelConfig } from "../../utils/areaHelpers";

export default function AreaListView({
	floorAreas,
	selectedFloor,
	selectedBuilding,
	selectedAreaId,
	cameraCounts,
	isAdmin,
	isFacilityManager,
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
									</div>
								</div>

								<h3 className="zone-card__title">{area.name}</h3>
								<div className="zone-card__code">{area.code}</div>

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

										{isFacilityManager &&
											(levelKey === "CONFIDENTIAL_CONTACT_REQUIRED" ||
												levelKey === "HIGHLY_CONFIDENTIAL") && (
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
														onOpenEditModal();
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
														onOpenDeactivateModal();
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
							Quy định Quyền Truy Cập
						</h4>
						<p className="zone-list-info-banner__subtitle">
							Chi tiết về điều kiện ra vào
						</p>
					</div>
				</div>

				<div className="zone-list-info-banner__grid">
					{/* 1. PUBLIC */}
					<div className="zone-list-info-banner__card zone-list-info-banner__card--public">
						<div className="zone-list-info-banner__card-header">
							<span className="zone-list-info-banner__card-badge level-badge level-badge--public">
								Công khai
							</span>
							<span className="zone-list-info-banner__card-level">
								Level 1+
							</span>
						</div>
						<div className="zone-list-info-banner__card-desc">
							Khu vực tự do ra vào cho tất cả người dùng hệ thống (Level 1, 2,
							3).
						</div>
						<div className="zone-list-info-banner__card-personnel">
							<span>Danh sách chỉ định:</span>
							<span className="zone-list-info-banner__personnel-tag zone-list-info-banner__personnel-tag--none">
								Không áp dụng
							</span>
						</div>
					</div>

					{/* 2. INTERNAL_CONFIDENTIAL */}
					<div className="zone-list-info-banner__card zone-list-info-banner__card--internal">
						<div className="zone-list-info-banner__card-header">
							<span className="zone-list-info-banner__card-badge level-badge level-badge--internal">
								Bảo mật nội bộ
							</span>
							<span className="zone-list-info-banner__card-level">
								Level 2+
							</span>
						</div>
						<div className="zone-list-info-banner__card-desc">
							Dành cho nhân sự, giảng viên, cán bộ campus (từ Level 2 trở lên)
							tự do truy cập.
						</div>
						<div className="zone-list-info-banner__card-personnel">
							<span>Danh sách chỉ định:</span>
							<span className="zone-list-info-banner__personnel-tag zone-list-info-banner__personnel-tag--none">
								Không áp dụng
							</span>
						</div>
					</div>

					{/* 3. CONFIDENTIAL_CONTACT_REQUIRED */}
					<div className="zone-list-info-banner__card zone-list-info-banner__card--contact">
						<div className="zone-list-info-banner__card-header">
							<span className="zone-list-info-banner__card-badge level-badge level-badge--contact">
								Liên hệ trước
							</span>
							<span className="zone-list-info-banner__card-level">
								Level 2+ (Chỉ định)
							</span>
						</div>
						<div className="zone-list-info-banner__card-desc">
							Người dùng Level 2 cần gửi đơn đăng ký hoặc liên hệ người quản lý
							trước khi vào. Level 3 tự do ra vào.
						</div>
						<div className="zone-list-info-banner__card-personnel">
							<span>Danh sách chỉ định:</span>
							<span className="zone-list-info-banner__personnel-tag zone-list-info-banner__personnel-tag--has">
								<Users size={11} /> Có áp dụng
							</span>
						</div>
					</div>

					{/* 4. HIGHLY_CONFIDENTIAL */}
					<div className="zone-list-info-banner__card zone-list-info-banner__card--private">
						<div className="zone-list-info-banner__card-header">
							<span className="zone-list-info-banner__card-badge level-badge level-badge--private">
								Bảo mật cao
							</span>
							<span className="zone-list-info-banner__card-level">
								Level 3 / Chỉ định
							</span>
						</div>
						<div className="zone-list-info-banner__card-desc">
							Khu vực nghiêm ngặt. Chỉ dành cho nhân sự quản lý Level 3 hoặc cá
							nhân được chỉ định trực tiếp.
						</div>
						<div className="zone-list-info-banner__card-personnel">
							<span>Danh sách chỉ định:</span>
							<span className="zone-list-info-banner__personnel-tag zone-list-info-banner__personnel-tag--has">
								<Users size={11} /> Có Áp dụng
							</span>
						</div>
					</div>
				</div>

				<div className="zone-list-info-banner__footer">
					<Users
						size={14}
						className="zone-list-info-banner__footer-icon"
					/>
					<span>
						<strong>Lưu ý về nút quản lý Nhân viên chỉ định:</strong> Biểu tượng
						nút Nhân viên (
						<Users
							size={12}
							style={{ display: "inline", margin: "0 2px" }}
						/>
						) hiển thị trên thẻ của các phòng thuộc loại{" "}
						<strong>Bảo mật - liên hệ trước</strong> và{" "}
						<strong>Bảo mật cao</strong> để cấp quyền ra vào cố định cho nhân
						sự.
					</span>
				</div>
			</div>
		</div>
	);
}
