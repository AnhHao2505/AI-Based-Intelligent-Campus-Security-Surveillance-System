import React from "react";
import { useNavigate } from "react-router-dom";
import { MapPin, Save, Loader2, ExternalLink } from "lucide-react";
import { formatLocation } from "../../utils/formatLocation";
import Select from "../ui/Select";

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
						<span>Khu vực phụ trách *</span>
					</label>

					<div
						style={{
							display: "flex",
							gap: "0.75rem",
							alignItems: "center",
							flexWrap: "wrap",
						}}
					>
						<Select
							className="camera-general-area-select"
							value={generalForm.areaId || ""}
							onChange={(e) =>
								setGeneralForm({ ...generalForm, areaId: e.target.value })
							}
							required
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

						<button
							type="button"
							onClick={() => navigate("/admin/areas")}
							style={{
								background: "transparent",
								border: "1px solid var(--theme-border)",
								color: "var(--theme-info)",
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
							<span>Đến danh sách khu vực</span>
						</button>
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
