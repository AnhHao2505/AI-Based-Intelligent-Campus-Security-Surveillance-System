import { useState, useEffect } from "react";
import { X, Loader } from "lucide-react";
import { createCamera } from "../services/cameraService";
import { getAreas } from "../services/areaService";
import "../styles/CameraCreateModal.css";

export default function CameraCreateModal({ isOpen, onClose, onSuccess }) {
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState(null);
	const [availableAreas, setAvailableAreas] = useState([]);
	const [formData, setFormData] = useState({
		name: "",
		areaId: "",
	});

	useEffect(() => {
		if (isOpen) {
			setFormData({ name: "", areaId: "" });
			setError(null);
			getAreas({ size: 200, isActive: true })
				.then((res) => {
					const list = res?.content || res?.areas || (Array.isArray(res) ? res : []);
					setAvailableAreas(list);
				})
				.catch((err) => console.error("Failed to load areas:", err));
		}
	}, [isOpen]);

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
			onSuccess(response);
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
								Khu vực phân công (Tùy chọn)
							</label>
							<select
								id="areaId"
								name="areaId"
								value={formData.areaId}
								onChange={handleChange}
								disabled={loading}
								style={{
									width: "100%",
									padding: "0.6rem 0.85rem",
									borderRadius: "8px",
									border: "1px solid var(--theme-border)",
									background: "var(--theme-bg-input, var(--theme-bg-surface))",
									color: "var(--theme-text-primary)",
									fontSize: "0.875rem",
								}}
							>
								<option value="">-- Chưa gán khu vực (Camera tự do) --</option>
								{availableAreas.map((area) => (
									<option
										key={area.id}
										value={area.id}
									>
										{area.name}{" "}
										{area.building
											? `(${area.building}${area.floor ? ` - Tầng ${area.floor}` : ""})`
											: ""}
									</option>
								))}
							</select>
							<span
								style={{
									fontSize: "0.775rem",
									color: "var(--theme-text-muted)",
									marginTop: "0.25rem",
									display: "block",
								}}
							>
								Mỗi camera chỉ thuộc tối đa 1 khu vực. Bạn có thể thay đổi sau.
							</span>
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
