import { useState, useEffect } from "react";
import { X, Loader } from "lucide-react";
import { createCamera } from "../services/cameraService";
import { getAllAreas } from "../services/areaService";
import "../styles/cameraShared.css";
import "../styles/CameraCreateModal.css";
import { formatLocation } from "../utils/formatLocation";
import Select from "./ui/Select";

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
		areaId: defaultAreaId || "",
	});

	useEffect(() => {
		if (isOpen) {
			setFormData({ name: "", areaId: defaultAreaId || "" });
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
					if (!defaultAreaId && defaultBuilding && defaultBuilding !== "ALL") {
						const matched = items.find((a) => {
							const bOk =
								String(a.building || "").toLowerCase().includes(String(defaultBuilding).toLowerCase()) ||
								String(defaultBuilding).toLowerCase().includes(String(a.building || "").toLowerCase());
							const fOk =
								!defaultFloor ||
								defaultFloor === "ALL" ||
								String(a.floor || "").toLowerCase() === String(defaultFloor).toLowerCase();
							return bOk && fOk;
						});
						if (matched?.id) {
							setFormData((prev) => ({
								...prev,
								areaId: prev.areaId || matched.id,
							}));
						}
					}
				})
				.catch((err) => console.error("Failed to load areas:", err));
		}
	}, [isOpen, defaultAreaId, defaultBuilding, defaultFloor]);

	if (!isOpen) return null;

	const handleChange = (e) => {
		const { name, value } = e.target;
		setFormData((prev) => ({ ...prev, [name]: value }));
	};

	const handleSubmit = async (e) => {
		e.preventDefault();
		setLoading(true);
		setError(null);

		const payload = {
			name: formData.name.trim(),
			areaId: formData.areaId ? formData.areaId : null,
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
							<label htmlFor="name">
								Tên camera <span className="required">*</span>
							</label>
							<input
								type="text"
								id="name"
								name="name"
								value={formData.name}
								onChange={handleChange}
								placeholder="Ví dụ: Camera cổng chính A"
								required
								disabled={loading}
							/>
						</div>

						<div className="form-group col-span-2">
							<label htmlFor="areaId">
								Khu vực phân công <span className="required">*</span>
							</label>
							<Select
								id="areaId"
								name="areaId"
								value={formData.areaId}
								onChange={handleChange}
								required
								disabled={loading}
								options={[
									{ value: "", label: "-- Chọn khu vực --", disabled: true },
									...availableAreas.map((area) => ({
										value: area.id,
										label: `${area.name} ${
											formatLocation(area.building, area.floor, " - ")
												? `(${formatLocation(area.building, area.floor, " - ")})`
												: ""
										}`,
									})),
								]}
							/>
							<span
								style={{
									fontSize: "0.775rem",
									color: "var(--theme-text-muted)",
									marginTop: "0.25rem",
									display: "block",
								}}
							>
								Mỗi camera phải được gán vào một khu vực đang hoạt động. Bạn có thể thay đổi sau.
							</span>
						</div>

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
