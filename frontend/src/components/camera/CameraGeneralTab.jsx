import React, { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import {
	MapPin,
	Save,
	Loader2,
	ExternalLink,
	Trash2,
	RotateCcw,
} from "lucide-react";
import { getLevelConfig } from "../../utils/areaHelpers";
import AreaSelectWithFilter from "../area/AreaSelectWithFilter";

export default function CameraGeneralTab({
	generalForm,
	setGeneralForm,
	availableAreas = [],
	saving,
	onSubmit,
	isAdmin = true,
	showAreaExternalLink = false,
	mapPlacement = null,
	isPickingLocationOnMap = false,
	onPickLocationOnMap = null,
	onRemoveMapLocation = null,
	canUndoRemovedMapLocation = false,
	onUndoRemoveMapLocation = null,
}) {
	const navigate = useNavigate();

	const selectedArea = useMemo(
		() => availableAreas.find((a) => String(a.id) === String(generalForm.areaId)),
		[availableAreas, generalForm.areaId],
	);
	const selectedLevelCfg = selectedArea
		? getLevelConfig(selectedArea.areaLevel || selectedArea.areaAccessLevel)
		: null;

	return (
		<form
			onSubmit={onSubmit}
			className="tab-form"
		>
			<div className="form-grid">
				<div className="form-group col-span-2">
					<label>Tên camera *</label>
					<input
						type="text"
						value={generalForm.name}
						onChange={(e) =>
							setGeneralForm({ ...generalForm, name: e.target.value })
						}
						placeholder="Nhập tên nhận diện của camera..."
						required
						disabled={!isAdmin}
					/>
				</div>

				<div className="form-group col-span-2">
					<label
						style={{
							display: "flex",
							alignItems: "center",
							gap: "0.4rem",
						}}
					>
						<MapPin
							size={15}
							className="text-blue"
						/>
						<span>Khu vực phụ trách *</span>
					</label>

					<div className="camera-general-area-row">
						<AreaSelectWithFilter
							className="camera-general-area-select"
							areas={availableAreas}
							value={generalForm.areaId || ""}
							onChange={(e) =>
								setGeneralForm({ ...generalForm, areaId: e.target.value })
							}
							placeholder="-- Chọn khu vực --"
							placeholderDisabled
							required
							disabled={!isAdmin}
						/>

						{showAreaExternalLink && (
							<button
								type="button"
								onClick={() => navigate("/admin/areas")}
								className="cam-pick-3d-btn"
							>
								<ExternalLink size={14} />
								<span>Đến danh sách khu vực</span>
							</button>
						)}
					</div>
					{selectedArea && selectedLevelCfg && (
						<div className="cam-map-card__hint" style={{ marginTop: "4px" }}>
							Cấp bảo mật:{" "}
							<strong style={{ color: selectedLevelCfg.color }}>
								{selectedLevelCfg.badgeLabel || selectedLevelCfg.name}
							</strong>
						</div>
					)}
				</div>
			</div>

			{onPickLocationOnMap && isAdmin && (
				<div className="cam-map-card">
					<div className="cam-map-card__header">
						<div className="cam-map-card__title">
							<MapPin size={14} />
							<span>Vị trí icon trên bản đồ 2D</span>
						</div>
						{mapPlacement && onRemoveMapLocation && (
							<button
								type="button"
								className="cam-map-card__remove"
								onClick={onRemoveMapLocation}
								title="Xóa icon khỏi bản đồ"
							>
								<Trash2 size={13} />
								<span>Xóa vị trí</span>
							</button>
						)}
					</div>

					{!mapPlacement ? (
						<>
							<button
								type="button"
								className={`cam-pick-3d-btn cam-map-card__pick-btn ${isPickingLocationOnMap ? "cam-pick-3d-btn--active" : ""}`}
								onClick={onPickLocationOnMap}
							>
								<MapPin size={14} />
								<span>
									{isPickingLocationOnMap
										? "Đang chọn trên bản đồ..."
										: "Đặt vị trí trên bản đồ"}
								</span>
							</button>
							{canUndoRemovedMapLocation && onUndoRemoveMapLocation && (
								<button
									type="button"
									className="cam-pick-3d-btn cam-map-card__pick-btn"
									onClick={onUndoRemoveMapLocation}
									style={{ marginTop: "6px" }}
								>
									<RotateCcw size={14} />
									<span>Hoàn tác vị trí vừa xóa</span>
								</button>
							)}
							<p className="cam-map-card__empty">
								Chưa ghim trên bản đồ. Bấm nút để chọn vị trí.
							</p>
						</>
					) : (
						<div className="cam-map-card__hint">
							Đã ghim vị trí ({mapPlacement.building || "Khuôn viên"}). Kéo icon trên bản đồ để dời.
						</div>
					)}
				</div>
			)}

			{isAdmin && (
				<div className="form-actions">
					<button
						type="submit"
						className="btn-save"
						disabled={saving}
					>
						{saving ? (
							<Loader2
								className="animate-spin"
								size={16}
							/>
						) : (
							<Save size={16} />
						)}
						<span>Lưu thông tin</span>
					</button>
				</div>
			)}
		</form>
	);
}
