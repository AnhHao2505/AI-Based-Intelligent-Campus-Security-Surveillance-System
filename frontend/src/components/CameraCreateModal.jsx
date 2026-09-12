import { useState } from "react";
import { X, Loader } from "lucide-react";
import { createCamera } from "../services/cameraService";
import "../styles/CameraCreateModal.css";

export default function CameraCreateModal({ isOpen, onClose, onSuccess }) {
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState(null);
	const [formData, setFormData] = useState({
		name: "",
		installedAt: "",
	});

	if (!isOpen) return null;

	const handleChange = (e) => {
		const { name, value } = e.target;
		setFormData((prev) => ({ ...prev, [name]: value }));
	};

	const handleSubmit = async (e) => {
		e.preventDefault();
		setLoading(true);
		setError(null);

		// Prepare payload matching CreateCameraRequest (Spec 2.1)
		const payload = {
			name: formData.name.trim(),
			installedAt: formData.installedAt
				? new Date(formData.installedAt).toISOString()
				: undefined,
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
					<h2>Thêm Camera Mới</h2>
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
								Tên Camera <span className="required">*</span>
							</label>
							<input
								type="text"
								id="name"
								name="name"
								value={formData.name}
								onChange={handleChange}
								placeholder="Ví dụ: Camera Cổng Chính A"
								required
								disabled={loading}
							/>
						</div>

						<div className="form-group col-span-2">
							<label htmlFor="installedAt">Thời điểm lắp đặt</label>
							<input
								type="datetime-local"
								id="installedAt"
								name="installedAt"
								value={formData.installedAt}
								onChange={handleChange}
								disabled={loading}
							/>
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
