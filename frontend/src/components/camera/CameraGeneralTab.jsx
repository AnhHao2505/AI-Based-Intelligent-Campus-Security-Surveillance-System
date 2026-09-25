import React from "react";
import { useNavigate } from "react-router-dom";
import { MapPin, Save, Loader2 } from "lucide-react";

export default function CameraGeneralTab({
	camera,
	generalForm,
	setGeneralForm,
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
						<span>Khu vực đang phụ trách</span>
					</label>

					<div
						style={{
							padding: "0.75rem 1rem",
							background: "var(--theme-bg-desc)",
							borderRadius: "8px",
							border: "1px solid var(--theme-border)",
							display: "flex",
							flexWrap: "wrap",
							gap: "0.5rem",
							minHeight: "42px",
							alignItems: "center",
						}}
					>
						{camera?.assignedAreas && camera.assignedAreas.length > 0 ? (
							<div
								style={{
									display: "flex",
									flexWrap: "wrap",
									alignItems: "center",
									gap: "0.5rem",
									width: "100%",
								}}
							>
								{camera.assignedAreas.map((area) => (
									<span
										key={area.id}
										className="location-tag"
										style={{
											fontSize: "0.85rem",
											padding: "0.3rem 0.75rem",
										}}
									>
										<strong>{area.name}</strong>
										{area.building
											? ` (${area.building}${area.floor ? ` - Tầng ${area.floor}` : ""})`
											: ""}
									</span>
								))}
								<button
									type="button"
									onClick={() => navigate("/admin/areas")}
									style={{
										marginLeft: "auto",
										background: "transparent",
										border: "1px solid var(--theme-border)",
										color: "#38bdf8",
										borderRadius: "6px",
										padding: "0.35rem 0.75rem",
										fontSize: "0.8rem",
										cursor: "pointer",
										display: "inline-flex",
										alignItems: "center",
										gap: "0.35rem",
									}}
								>
									<MapPin size={13} />
									<span>Danh sách khu vực ➔</span>
								</button>
							</div>
						) : (
							<div
								style={{
									display: "flex",
									alignItems: "center",
									justifyContent: "space-between",
									width: "100%",
									flexWrap: "wrap",
									gap: "0.5rem",
								}}
							>
								<span
									style={{
										color: "var(--theme-text-muted)",
										fontSize: "0.875rem",
									}}
								>
									Camera này chưa được gán vào khu vực nào.
								</span>
								<button
									type="button"
									onClick={() => navigate("/admin/areas")}
									style={{
										background: "rgba(56, 189, 248, 0.12)",
										border: "1px solid rgba(56, 189, 248, 0.3)",
										color: "#38bdf8",
										borderRadius: "6px",
										padding: "0.4rem 0.8rem",
										fontSize: "0.825rem",
										fontWeight: 600,
										cursor: "pointer",
										display: "inline-flex",
										alignItems: "center",
										gap: "0.4rem",
									}}
								>
									<MapPin size={14} />
									<span>Gán camera vào khu vực ➔</span>
								</button>
							</div>
						)}
					</div>
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
