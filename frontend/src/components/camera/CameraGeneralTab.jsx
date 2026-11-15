import React, { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import {
	MapPin,
	Save,
	Loader2,
	ExternalLink,
	Trash2,
} from "lucide-react";
import { formatLocation } from "../../utils/formatLocation";
import { getLevelConfig } from "../../utils/areaHelpers";
import Select from "../ui/Select";

export default function CameraGeneralTab({
	generalForm,
	setGeneralForm,
	availableAreas = [],
	saving,
	onSubmit,
	isAdmin = true,
	showAreaExternalLink = false,
	fovPlacement = null,
	mapPlacement = null,
	isPickingLocationOnMap = false,
	onPickLocationOnMap = null,
	onUpdateFov = null,
	onRemoveFov = null,
	onRemoveMapLocation = null,
}) {
	const navigate = useNavigate();
	const activePlacement = mapPlacement || fovPlacement;
	const handleRemoveLocation = onRemoveMapLocation || onRemoveFov;

	const sortedAreaOptions = useMemo(() => {
		const sorted = [...availableAreas].sort((a, b) => {
			const locA = `${a.building || ""} ${a.floor || ""} ${a.name || ""}`;
			const locB = `${b.building || ""} ${b.floor || ""} ${b.name || ""}`;
			return locA.localeCompare(locB, "vi");
		});
		return [
			{ value: "", label: "-- Chọn khu vực --", disabled: true },
			...sorted.map((area) => {
				const loc = formatLocation(area.building, area.floor, " · ");
				return {
					value: area.id,
					label: loc ? `${loc} — ${area.name}` : area.name,
				};
			}),
		];
	}, [availableAreas]);

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
						<Select
							className="camera-general-area-select"
							value={generalForm.areaId || ""}
							onChange={(e) =>
								setGeneralForm({ ...generalForm, areaId: e.target.value })
							}
							required
							disabled={!isAdmin}
							options={sortedAreaOptions}
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
						<div className="cam-fov-card__hint" style={{ marginTop: "4px" }}>
							Cấp bảo mật:{" "}
							<strong style={{ color: selectedLevelCfg.color }}>
								{selectedLevelCfg.badgeLabel || selectedLevelCfg.name}
							</strong>
						</div>
					)}
				</div>
			</div>

			{onPickLocationOnMap && isAdmin && (
				<div className="cam-fov-card">
					<div className="cam-fov-card__header">
						<div className="cam-fov-card__title">
							<MapPin size={14} />
							<span>Vị trí icon trên bản đồ 2D</span>
						</div>
						{activePlacement && handleRemoveLocation && (
							<button
								type="button"
								className="cam-fov-card__remove"
								onClick={handleRemoveLocation}
								title="Xóa icon khỏi bản đồ"
							>
								<Trash2 size={13} />
								<span>Xóa vị trí</span>
							</button>
						)}
					</div>

					{!activePlacement ? (
						<>
							<button
								type="button"
								className={`cam-pick-3d-btn cam-fov-card__pick-btn ${isPickingLocationOnMap ? "cam-pick-3d-btn--active" : ""}`}
								onClick={onPickLocationOnMap}
							>
								<MapPin size={14} />
								<span>
									{isPickingLocationOnMap
										? "Đang chọn trên bản đồ..."
										: "Đặt vị trí trên bản đồ"}
								</span>
							</button>
							<p className="cam-fov-card__empty">
								Chưa ghim trên bản đồ. Bấm nút để chọn vị trí.
							</p>
						</>
					) : (
						<div className="cam-fov-card__hint">
							Đã ghim vị trí ({activePlacement.building || "Khuôn viên"}). Kéo icon trên bản đồ để dời.
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
