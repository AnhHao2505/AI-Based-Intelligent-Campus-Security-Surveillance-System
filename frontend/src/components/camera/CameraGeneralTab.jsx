import React from "react";
import { useNavigate } from "react-router-dom";
import { MapPin, Save, Loader2, ExternalLink } from "lucide-react";

export default function CameraGeneralTab({
	camera,
	generalForm,
	setGeneralForm,
	availableAreas = [],
	saving,
	onSubmit,
}) {
	const navigate = useNavigate();

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
						<span>Khu vực phụ trách (Mỗi camera thuộc tối đa 1 khu vực)</span>
					</label>

					<div
						style={{
							display: "flex",
							gap: "0.75rem",
							alignItems: "center",
							flexWrap: "wrap",
						}}
					>
						<select
							value={generalForm.areaId || ""}
							onChange={(e) =>
								setGeneralForm({ ...generalForm, areaId: e.target.value })
							}
							style={{
								flex: "1 1 300px",
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

						<button
							type="button"
							onClick={() => navigate("/admin/areas")}
							style={{
								background: "transparent",
								border: "1px solid var(--theme-border)",
								color: "#38bdf8",
								borderRadius: "8px",
								padding: "0.6rem 1rem",
								fontSize: "0.85rem",
								cursor: "pointer",
								display: "inline-flex",
								alignItems: "center",
								gap: "0.4rem",
								whiteSpace: "nowrap",
							}}
						>
							<ExternalLink size={14} />
							<span>Quản lý khu vực</span>
						</button>
					</div>

					<p
						style={{
							fontSize: "0.8rem",
							color: "var(--theme-text-muted)",
							marginTop: "0.4rem",
						}}
					>
						💡 Theo thiết kế mới, mỗi camera chỉ thuộc về 1 khu vực duy nhất.
						Các vùng ROI đa giác sẽ tự động giám sát 3 sự cố an ninh (Người lạ,
						Xâm nhập, Ngoài giờ) theo quy định của khu vực này.
					</p>
				</div>
			</div>

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
		</form>
	);
}
