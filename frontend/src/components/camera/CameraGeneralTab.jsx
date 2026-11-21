import React, { useMemo, useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import {
	MapPin,
	Save,
	Loader2,
	ExternalLink,
	Trash2,
	RotateCcw,
	Pencil,
	X,
	CheckCircle2,
	AlertCircle,
} from "lucide-react";
import { getLevelConfig } from "../../utils/areaHelpers";
import { formatLocation } from "../../utils/formatLocation";
import AreaSelectWithFilter from "../area/AreaSelectWithFilter";

export default function CameraGeneralTab({
	camera,
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
	isEditing: controlledIsEditing,
	onEditingChange = null,
	onCancelEdit = null,
}) {
	const navigate = useNavigate();
	const [internalIsEditing, setInternalIsEditing] = useState(false);

	const isEditing =
		controlledIsEditing !== undefined ? controlledIsEditing : internalIsEditing;

	const setEditing = (next) => {
		if (controlledIsEditing === undefined) {
			setInternalIsEditing(next);
		}
		onEditingChange?.(next);
	};

	// Tự động trở về Read Mode khi đổi sang camera khác
	useEffect(() => {
		setInternalIsEditing(false);
	}, [camera?.id]);

	const selectedArea = useMemo(
		() =>
			availableAreas.find((a) => String(a.id) === String(generalForm.areaId)) ||
			camera?.assignedArea ||
			camera?.assignedAreas?.[0] ||
			null,
		[availableAreas, generalForm.areaId, camera],
	);

	const selectedLevelCfg = selectedArea
		? getLevelConfig(selectedArea.areaLevel || selectedArea.areaAccessLevel)
		: null;

	const areaLocationSubtitle = selectedArea
		? formatLocation(selectedArea.building, selectedArea.floor)
		: "";

	const handleStartEdit = () => {
		setEditing(true);
	};

	const handleCancel = () => {
		if (onCancelEdit) {
			onCancelEdit();
		} else if (camera) {
			const fallbackAreaId =
				camera.areaId ||
				camera.assignedArea?.id ||
				camera.assignedAreas?.[0]?.id ||
				"";
			setGeneralForm({
				name: camera.name || "",
				areaId: fallbackAreaId,
			});
		}
		setEditing(false);
	};

	const handleFormSubmit = async (e) => {
		const res = await onSubmit?.(e);
		if (res !== false) {
			setEditing(false);
		}
	};

	// 1. CHẾ ĐỘ XEM (READ MODE) — Tinh gọn, rõ ràng, chống thao tác nhầm
	if (!isEditing) {
		return (
			<div className="cam-general-read">
				<div className="cam-general-read__top">
					<span className="cam-general-read__section-title">
						Thông tin định danh & vị trí
					</span>
					{isAdmin && (
						<button
							type="button"
							className="cam-general-read__edit-btn"
							onClick={handleStartEdit}
						>
							<Pencil size={13} />
							<span>Chỉnh sửa</span>
						</button>
					)}
				</div>

				<div className="cam-general-read__list">
					<div className="cam-general-read__item">
						<span className="cam-general-read__label">Tên camera</span>
						<div className="cam-general-read__value-row">
							<span className="cam-general-read__value-primary">
								{camera?.name || generalForm.name || "—"}
							</span>
							{camera?.cameraCode && (
								<span className="cam-general-read__code-pill">
									{camera.cameraCode}
								</span>
							)}
						</div>
					</div>

					<div className="cam-general-read__item">
						<span className="cam-general-read__label">Khu vực phụ trách</span>
						{selectedArea ? (
							<div className="cam-general-read__area-box">
								<div className="cam-general-read__area-main">
									<span className="cam-general-read__value-primary">
										{selectedArea.name}
									</span>
									{selectedLevelCfg && (
										<span
											className={`level-badge ${selectedLevelCfg.badgeClass || ""}`}
										>
											{selectedLevelCfg.badgeLabel || selectedLevelCfg.name}
										</span>
									)}
								</div>
								{areaLocationSubtitle && (
									<span className="cam-general-read__subtext">
										{areaLocationSubtitle}
									</span>
								)}
								{showAreaExternalLink && (
									<button
										type="button"
										onClick={() => navigate("/admin/areas")}
										className="cam-general-read__link-btn"
									>
										<ExternalLink size={12} />
										<span>Đến danh sách khu vực</span>
									</button>
								)}
							</div>
						) : (
							<span className="cam-general-read__subtext">
								Chưa gán khu vực
							</span>
						)}
					</div>

					{(onPickLocationOnMap || mapPlacement) && (
						<div className="cam-general-read__item">
							<span className="cam-general-read__label">
								Vị trí trên bản đồ 2D
							</span>
							{mapPlacement ? (
								<div className="cam-general-read__pin-status cam-general-read__pin-status--ok">
									<CheckCircle2 size={14} />
									<span>
										Đã ghim vị trí ({mapPlacement.building || "Khuôn viên"})
									</span>
								</div>
							) : (
								<div className="cam-general-read__pin-status cam-general-read__pin-status--warn">
									<AlertCircle size={14} />
									<span>Chưa ghim trên bản đồ</span>
								</div>
							)}
						</div>
					)}
				</div>
			</div>
		);
	}

	// 2. CHẾ ĐỘ CHỈNH SỬA (EDIT MODE)
	return (
		<form onSubmit={handleFormSubmit} className="tab-form">
			<div className="cam-general-edit__top">
				<span className="cam-general-read__section-title">
					Chỉnh sửa thông tin
				</span>
			</div>

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
						disabled={!isAdmin || saving}
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
						<MapPin size={15} className="text-blue" />
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
							disabled={!isAdmin || saving}
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
							Đã ghim vị trí ({mapPlacement.building || "Khuôn viên"}). Kéo icon
							trên bản đồ để dời.
						</div>
					)}
				</div>
			)}

			{isAdmin && (
				<div className="form-actions cam-general-edit__actions">
					<button
						type="button"
						className="btn-cancel-edit"
						onClick={handleCancel}
						disabled={saving}
					>
						<X size={15} />
						<span>Hủy</span>
					</button>
					<button type="submit" className="btn-save" disabled={saving}>
						{saving ? (
							<Loader2 className="animate-spin" size={16} />
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
