import React, { useMemo, useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import {
	MapPin,
	Save,
	Loader2,
	ExternalLink,
	Pencil,
	X,
	CheckCircle2,
	AlertCircle,
	ShieldCheck,
} from "lucide-react";
import { getLevelConfig } from "../../utils/areaHelpers";
import { formatLocation } from "../../utils/formatLocation";
import {
	CAMERA_TYPE_OPTIONS,
	filterAreasForCameraType,
	normalizeCorridorCameraName,
	resolveCameraTypeConfig,
	stripCorridorPrefix,
} from "../../utils/campusCameraHelpers";
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
	isEditing: controlledIsEditing,
	onEditingChange = null,
	onCancelEdit = null,
	hasUpdatedPinInEdit = false,
	onEditScopeChange = null,
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

	const activeCameraType =
		generalForm.cameraType ||
		camera?.cameraType ||
		(camera?.assignedArea?.areaLevel === "PUBLIC"
			? "PUBLIC_AREA"
			: "RESTRICTED_AREA");
	const typeConfig = resolveCameraTypeConfig(activeCameraType);
	const isCorridorType = activeCameraType === "CORRIDOR";

	const originalCameraType =
		camera?.cameraType ||
		(camera?.assignedArea?.areaLevel === "PUBLIC"
			? "PUBLIC_AREA"
			: "RESTRICTED_AREA");
	const originalAreaId = String(
		camera?.areaId ||
			camera?.assignedArea?.id ||
			camera?.assignedAreas?.[0]?.id ||
			"",
	);
	const hasLocationScopeChanged =
		activeCameraType !== originalCameraType ||
		(!isCorridorType && String(generalForm.areaId || "") !== originalAreaId);
	const requiresNewPinOnMap =
		Boolean(onPickLocationOnMap) &&
		hasLocationScopeChanged &&
		!hasUpdatedPinInEdit;
	const canChangeLocationScope = Boolean(onPickLocationOnMap);

	const filteredAreas = useMemo(
		() => filterAreasForCameraType(availableAreas, activeCameraType),
		[availableAreas, activeCameraType],
	);

	const selectedArea = useMemo(() => {
		if (isCorridorType) return null;
		return (
			availableAreas.find((a) => String(a.id) === String(generalForm.areaId)) ||
			camera?.assignedArea ||
			camera?.assignedAreas?.[0] ||
			null
		);
	}, [availableAreas, generalForm.areaId, camera, isCorridorType]);

	const selectedLevelCfg = selectedArea
		? getLevelConfig(selectedArea.areaLevel || selectedArea.areaAccessLevel)
		: null;

	const resolvedBuilding =
		selectedArea?.building ||
		mapPlacement?.building ||
		camera?.mapBuilding ||
		camera?.building ||
		generalForm.mapBuilding ||
		null;
	const resolvedFloorOrder =
		mapPlacement?.floorOrder ??
		camera?.mapFloorOrder ??
		generalForm.mapFloorOrder ??
		null;
	const resolvedFloorLabel =
		selectedArea?.floor ||
		camera?.floor ||
		(resolvedFloorOrder != null && Number.isFinite(Number(resolvedFloorOrder))
			? Number(resolvedFloorOrder) === 0
				? "Tầng Trệt"
				: `Tầng ${Number(resolvedFloorOrder)}`
			: null);

	const areaLocationSubtitle = formatLocation(
		resolvedBuilding,
		resolvedFloorLabel,
	);

	const handleStartEdit = () => {
		setEditing(true);
	};

	const handleCancel = () => {
		if (camera) {
			const fallbackAreaId =
				camera.areaId ||
				camera.assignedArea?.id ||
				camera.assignedAreas?.[0]?.id ||
				"";
			setGeneralForm({
				name: camera.name || "",
				cameraType:
					camera.cameraType ||
					(camera.assignedArea?.areaLevel === "PUBLIC"
						? "PUBLIC_AREA"
						: "RESTRICTED_AREA"),
				areaId: fallbackAreaId,
				mapBuilding: camera.mapBuilding || "",
				mapFloorOrder: camera.mapFloorOrder ?? 0,
			});
		}
		onCancelEdit?.();
		setEditing(false);
	};

	const handleCameraTypeChange = (nextType) => {
		if (!canChangeLocationScope) return;
		const nextFiltered = filterAreasForCameraType(availableAreas, nextType);
		const currentAreaStillValid = nextFiltered.some(
			(a) => String(a.id) === String(generalForm.areaId),
		);
		const nextAreaId =
			nextType === "CORRIDOR"
				? ""
				: currentAreaStillValid
					? generalForm.areaId
					: nextFiltered[0]?.id || "";
		const nextName =
			nextType === "CORRIDOR"
				? normalizeCorridorCameraName(generalForm.name || "")
				: generalForm.name;
		setGeneralForm({
			...generalForm,
			cameraType: nextType,
			areaId: nextAreaId,
			name: nextName,
		});
		const nextScopeChanged =
			nextType !== originalCameraType ||
			(nextType !== "CORRIDOR" && String(nextAreaId || "") !== originalAreaId);
		onEditScopeChange?.({
			cameraType: nextType,
			areaId: nextType === "CORRIDOR" ? null : nextAreaId || null,
			scopeChanged: nextScopeChanged,
		});
	};

	const handleFormSubmit = async (e) => {
		if (requiresNewPinOnMap) {
			e.preventDefault();
			onPickLocationOnMap?.();
			return;
		}
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
						<span className="cam-general-read__label">
							Loại vị trí & Chế độ AI
						</span>
						<div className="cam-general-read__value-row">
							<span
								className={`cam-type-badge cam-type-badge--${activeCameraType.toLowerCase()}`}
							>
								<ShieldCheck size={13} />
								<span>{typeConfig.badgeLabel}</span>
							</span>
						</div>
					</div>

					<div className="cam-general-read__item">
						<span className="cam-general-read__label">
							{isCorridorType ? "Vị trí hành lang" : "Khu vực phụ trách"}
						</span>
						{isCorridorType ? (
							<div className="cam-general-read__area-box">
								<div className="cam-general-read__area-main">
									<span className="cam-general-read__value-primary">
										Hành lang chung (Không gán phòng)
									</span>
								</div>
								{areaLocationSubtitle && (
									<span className="cam-general-read__subtext">
										{areaLocationSubtitle}
									</span>
								)}
							</div>
						) : selectedArea ? (
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
										Đã ghim vị trí (
										{areaLocationSubtitle ||
											mapPlacement.building ||
											"Khuôn viên"}
										)
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
	const corridorSuffix = isCorridorType
		? stripCorridorPrefix(generalForm.name)
		: generalForm.name;

	return (
		<form
			onSubmit={handleFormSubmit}
			className="tab-form"
		>
			<div className="cam-general-edit__top">
				<span className="cam-general-read__section-title">
					Chỉnh sửa thông tin
				</span>
			</div>

			<div className="form-grid">
				<div className="form-group col-span-2">
					<label>Loại vị trí đặt camera & Chế độ AI *</label>
					<div className="cam-type-selector-grid">
						{CAMERA_TYPE_OPTIONS.map((opt) => {
							const isSelected = activeCameraType === opt.value;
							return (
								<button
									key={opt.value}
									type="button"
									className={`cam-type-option-btn ${isSelected ? "cam-type-option-btn--active" : ""}`}
									onClick={() => handleCameraTypeChange(opt.value)}
									disabled={!isAdmin || saving || !canChangeLocationScope}
								>
									<span className="cam-type-option-btn__title">
										{opt.label}
									</span>
									<span className="cam-type-option-btn__sub">
										{opt.aiModeLabel}
									</span>
								</button>
							);
						})}
					</div>
				</div>

				<div className="form-group col-span-2">
					<label>Tên camera *</label>
					{isCorridorType ? (
						<div className="cam-corridor-prefix-input">
							<span className="cam-corridor-prefix-input__addon">
								Hành lang
							</span>
							<input
								type="text"
								value={corridorSuffix}
								onChange={(e) => {
									const raw = e.target.value;
									setGeneralForm({
										...generalForm,
										name: raw.trim()
											? `Hành lang ${stripCorridorPrefix(raw)}`
											: "",
									});
								}}
								placeholder="Ví dụ: Cánh Đông Tầng 1..."
								required
								disabled={!isAdmin || saving}
							/>
						</div>
					) : (
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
					)}
				</div>

				{!isCorridorType ? (
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
							<span>
								Khu vực phụ trách (
								{activeCameraType === "PUBLIC_AREA"
									? "Cấp 1 · Công khai"
									: "Cấp 2 & 3 · Hạn chế"}
								) *
							</span>
						</label>

						<div className="camera-general-area-row">
							<AreaSelectWithFilter
								className="camera-general-area-select"
								areas={filteredAreas}
								value={generalForm.areaId || ""}
								onChange={(e) => {
									const nextAreaId = e.target.value;
									setGeneralForm({ ...generalForm, areaId: nextAreaId });
									const nextScopeChanged =
										activeCameraType !== originalCameraType ||
										String(nextAreaId || "") !== originalAreaId;
									onEditScopeChange?.({
										cameraType: activeCameraType,
										areaId: nextAreaId || null,
										scopeChanged: nextScopeChanged,
									});
								}}
								placeholder="-- Chọn khu vực --"
								placeholderDisabled
								required
								disabled={!isAdmin || saving || !canChangeLocationScope}
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
							<div
								className="cam-map-card__hint"
								style={{ marginTop: "4px" }}
							>
								Cấp bảo mật:{" "}
								<strong style={{ color: selectedLevelCfg.color }}>
									{selectedLevelCfg.badgeLabel || selectedLevelCfg.name}
								</strong>
							</div>
						)}
					</div>
				) : (
					<div className="form-group col-span-2">
						<label>Tòa nhà & Tầng hành lang</label>
						<div className="cam-corridor-location-box">
							<MapPin size={14} />
							<span>
								{areaLocationSubtitle ||
									"Ghim vị trí trên sàn hành lang bản đồ 2D để xác định tòa & tầng"}
							</span>
						</div>
					</div>
				)}
			</div>

			{!canChangeLocationScope && isAdmin && (
				<div
					className="cam-map-card__hint"
					style={{ marginBottom: "10px", color: "#b45309" }}
				>
					Để đổi khu vực hoặc loại vị trí, vui lòng mở camera trên{" "}
					<strong>Bản đồ 2D (Quản lý Khu vực)</strong> để ghim lại vị trí mới.
				</div>
			)}

			{onPickLocationOnMap && isAdmin && (
				<div className="cam-map-card">
					<button
						type="button"
						className={`cam-pick-3d-btn cam-map-card__pick-btn ${isPickingLocationOnMap ? "cam-pick-3d-btn--active" : ""}`}
						onClick={onPickLocationOnMap}
					>
						<MapPin size={14} />
						<span>
							{isPickingLocationOnMap
								? "Đang chọn trên bản đồ..."
								: requiresNewPinOnMap
									? "Bắt buộc chọn lại vị trí trên bản đồ"
									: mapPlacement
										? "Đặt lại vị trí trên bản đồ"
										: "Đặt vị trí trên bản đồ"}
						</span>
					</button>

					{requiresNewPinOnMap ? (
						<p
							className="cam-map-card__empty"
							style={{ color: "#b45309", fontWeight: 500 }}
						>
							Bạn đã thay đổi khu vực / loại vị trí. Bắt buộc click chọn vị trí
							ghim mới trên bản đồ 2D trước khi lưu.
						</p>
					) : hasLocationScopeChanged && hasUpdatedPinInEdit ? (
						<div
							className="cam-map-card__hint"
							style={{ marginTop: "6px", color: "#15803d", fontWeight: 500 }}
						>
							Đã ghim vị trí mới cho khu vực đã chọn — bấm{" "}
							<strong>Lưu thông tin</strong> để hoàn tất.
						</div>
					) : !mapPlacement ? (
						<p className="cam-map-card__empty">
							Chưa ghim trên bản đồ. Bấm nút hoặc chấm trên bản đồ để ghim.
						</p>
					) : (
						<div
							className="cam-map-card__hint"
							style={{ marginTop: "6px" }}
						>
							Đã ghim vị trí (
							{areaLocationSubtitle || mapPlacement.building || "Khuôn viên"}).
							Kéo icon trên bản đồ để dời.
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
					<button
						type="submit"
						className="btn-save"
						disabled={saving || requiresNewPinOnMap}
						title={
							requiresNewPinOnMap
								? "Vui lòng chọn lại vị trí ghim trên bản đồ cho khu vực mới"
								: undefined
						}
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
