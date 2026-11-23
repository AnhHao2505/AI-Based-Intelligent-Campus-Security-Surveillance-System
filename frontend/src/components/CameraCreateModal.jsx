import { useState, useEffect, useMemo } from "react";
import { X, Loader } from "lucide-react";
import { createCamera } from "../services/cameraService";
import { getAllAreas } from "../services/areaService";
import {
	CAMERA_TYPE_OPTIONS,
	filterAreasForCameraType,
	normalizeCorridorCameraName,
	stripCorridorPrefix,
} from "../utils/campusCameraHelpers";
import { DEFAULT_BUILDINGS_META } from "../data/campusIndoor3dData";
import "../styles/cameraShared.css";
import "../styles/CameraCreateModal.css";
import AreaSelectWithFilter from "./area/AreaSelectWithFilter";

const VISIBLE_BUILDINGS = DEFAULT_BUILDINGS_META.filter(
	(b) => b.name !== "Tòa Alpha",
);

export default function CameraCreateModal({
	isOpen,
	onClose,
	onSuccess,
	onCreated,
	defaultAreaId = "",
	defaultBuilding = "ALL",
	defaultFloor = "ALL",
}) {
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState(null);
	const [availableAreas, setAvailableAreas] = useState([]);
	const [placeOnMapAfterCreate, setPlaceOnMapAfterCreate] = useState(true);
	const [formData, setFormData] = useState({
		name: "",
		cameraType: "RESTRICTED_AREA",
		areaId: defaultAreaId || "",
		mapBuilding: "Tòa Epsilon",
		mapFloorOrder: 0,
	});

	useEffect(() => {
		if (isOpen) {
			setFormData({
				name: "",
				cameraType: "RESTRICTED_AREA",
				areaId: defaultAreaId || "",
				mapBuilding: "Tòa Epsilon",
				mapFloorOrder: 0,
			});
			setPlaceOnMapAfterCreate(true);
			setError(null);
			getAllAreas({ isActive: true })
				.then((list) => {
					const items = Array.isArray(list) ? list : [];
					items.sort((a, b) => {
						const locA = `${a.building || ""} ${a.floor || ""} ${a.name || ""}`;
						const locB = `${b.building || ""} ${b.floor || ""} ${b.name || ""}`;
						return locA.localeCompare(locB, "vi");
					});
					setAvailableAreas(items);
					const restrictedItems = filterAreasForCameraType(
						items,
						"RESTRICTED_AREA",
					);
					if (!defaultAreaId && restrictedItems.length > 0) {
						setFormData((prev) => ({
							...prev,
							areaId: prev.areaId || restrictedItems[0].id,
						}));
					}
				})
				.catch((err) => console.error("Failed to load areas:", err));
		}
	}, [isOpen, defaultAreaId, defaultBuilding, defaultFloor]);

	const filteredAreas = useMemo(
		() => filterAreasForCameraType(availableAreas, formData.cameraType),
		[availableAreas, formData.cameraType],
	);

	if (!isOpen) return null;

	const isCorridorType = formData.cameraType === "CORRIDOR";

	const handleTypeChange = (nextType) => {
		const nextFiltered = filterAreasForCameraType(availableAreas, nextType);
		setFormData((prev) => ({
			...prev,
			cameraType: nextType,
			areaId: nextType === "CORRIDOR" ? "" : nextFiltered[0]?.id || "",
			name:
				nextType === "CORRIDOR"
					? normalizeCorridorCameraName(prev.name)
					: prev.name,
		}));
	};

	const handleChange = (e) => {
		const { name, value } = e.target;
		setFormData((prev) => ({ ...prev, [name]: value }));
	};

	const handleSubmit = async (e) => {
		e.preventDefault();
		setLoading(true);
		setError(null);

		const effectiveName = isCorridorType
			? normalizeCorridorCameraName(formData.name)
			: formData.name.trim();

		const payload = {
			name: effectiveName,
			cameraType: formData.cameraType,
			areaId: isCorridorType ? null : formData.areaId ? formData.areaId : null,
			...(isCorridorType
				? {
						mapBuilding: formData.mapBuilding || "Tòa Epsilon",
						mapFloorOrder: Number(formData.mapFloorOrder) || 0,
					}
				: {}),
		};

		try {
			const response = await createCamera(payload);
			onSuccess?.(response, { placeOnMap: placeOnMapAfterCreate });
			onCreated?.(response, { placeOnMap: placeOnMapAfterCreate });
			onClose();
		} catch (err) {
			console.error("Failed to create camera:", err);
			setError(
				err.message || "Lỗi khi tạo camera. Vui lòng kiểm tra lại thông tin.",
			);
		} finally {
			setLoading(false);
		}
	};

	const activeBldgMeta =
		VISIBLE_BUILDINGS.find((b) => b.name === formData.mapBuilding) ||
		VISIBLE_BUILDINGS[0];

	return (
		<div className="modal-overlay">
			<div className="modal-container">
				<div className="modal-header">
					<h2>Thêm camera mới</h2>
					<button
						className="modal-close"
						onClick={onClose}
						disabled={loading}
					>
						<X size={20} />
					</button>
				</div>

				<form
					onSubmit={handleSubmit}
					className="modal-form"
				>
					{error && <div className="modal-error">{error}</div>}

					<div className="form-grid">
						<div className="form-group col-span-2">
							<label>
								Loại vị trí đặt camera & Chế độ AI{" "}
								<span className="required">*</span>
							</label>
							<div className="cam-type-selector-grid">
								{CAMERA_TYPE_OPTIONS.map((opt) => (
									<button
										key={opt.value}
										type="button"
										className={`cam-type-option-btn ${
											formData.cameraType === opt.value
												? "cam-type-option-btn--active"
												: ""
										}`}
										onClick={() => handleTypeChange(opt.value)}
										disabled={loading}
									>
										<span className="cam-type-option-btn__title">
											{opt.label}
										</span>
										<span className="cam-type-option-btn__sub">
											{opt.aiModeLabel}
										</span>
									</button>
								))}
							</div>
						</div>

						<div className="form-group col-span-2">
							<label htmlFor="name">
								Tên camera <span className="required">*</span>
							</label>
							{isCorridorType ? (
								<div className="cam-corridor-prefix-input">
									<span className="cam-corridor-prefix-input__addon">
										Hành lang
									</span>
									<input
										type="text"
										id="name"
										name="name"
										value={stripCorridorPrefix(formData.name)}
										onChange={(e) => {
											const raw = e.target.value;
											setFormData((prev) => ({
												...prev,
												name: raw.trim()
													? `Hành lang ${stripCorridorPrefix(raw)}`
													: "",
											}));
										}}
										placeholder="Ví dụ: Cánh Đông Tầng 1"
										required
										disabled={loading}
									/>
								</div>
							) : (
								<input
									type="text"
									id="name"
									name="name"
									value={formData.name}
									onChange={handleChange}
									placeholder="Ví dụ: Camera Phòng Lab 301"
									required
									disabled={loading}
								/>
							)}
						</div>

						{!isCorridorType ? (
							<div className="form-group col-span-2">
								<label htmlFor="areaId">
									Khu vực phân công (
									{formData.cameraType === "PUBLIC_AREA"
										? "Cấp 1 · Công khai"
										: "Cấp 2 & 3 · Hạn chế"}
									) <span className="required">*</span>
								</label>
								<AreaSelectWithFilter
									id="areaId"
									name="areaId"
									areas={filteredAreas}
									value={formData.areaId}
									onChange={handleChange}
									showLevelFilter={formData.cameraType === "RESTRICTED_AREA"}
									showLevelTag={formData.cameraType === "RESTRICTED_AREA"}
									groupByLocation
									placeholder="-- Chọn khu vực --"
									placeholderDisabled
									required
									disabled={loading}
									defaultBuilding={defaultBuilding}
									defaultFloor={defaultFloor}
								/>
							</div>
						) : (
							<div className="form-group col-span-2">
								<label>
									Tòa nhà & Tầng hành lang <span className="required">*</span>
								</label>
								<div className="cam-corridor-floor-row">
									<select
										name="mapBuilding"
										value={formData.mapBuilding}
										onChange={handleChange}
										disabled={loading}
									>
										{VISIBLE_BUILDINGS.map((b) => (
											<option
												key={b.id}
												value={b.name}
											>
												{b.name}
											</option>
										))}
									</select>
									<select
										name="mapFloorOrder"
										value={String(formData.mapFloorOrder)}
										onChange={(e) =>
											setFormData((prev) => ({
												...prev,
												mapFloorOrder: Number(e.target.value),
											}))
										}
										disabled={loading}
									>
										{(activeBldgMeta?.floors || []).map((fl) => (
											<option
												key={fl.id}
												value={String(fl.floorOrder)}
											>
												{fl.name}
											</option>
										))}
									</select>
								</div>
							</div>
						)}

						<div className="form-group col-span-2">
							<label
								style={{
									display: "flex",
									alignItems: "center",
									gap: "0.5rem",
									cursor: "pointer",
									fontSize: "0.85rem",
									fontWeight: 600,
								}}
							>
								<input
									type="checkbox"
									checked={placeOnMapAfterCreate}
									onChange={(e) => setPlaceOnMapAfterCreate(e.target.checked)}
									style={{ width: "auto" }}
								/>
								<span>Đặt vị trí icon trên bản đồ ngay sau khi tạo</span>
							</label>
						</div>
					</div>

					<div className="modal-actions">
						<button
							type="button"
							className="btn-secondary"
							onClick={onClose}
							disabled={loading}
						>
							Huỷ
						</button>
						<button
							type="submit"
							className="btn-primary"
							disabled={loading}
						>
							{loading ? (
								<Loader
									className="animate-spin"
									size={16}
								/>
							) : (
								"Tạo camera"
							)}
						</button>
					</div>
				</form>
			</div>
		</div>
	);
}
