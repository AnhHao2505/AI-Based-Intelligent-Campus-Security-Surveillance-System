import React, { useState, useEffect } from "react";
import {
	ShieldCheck,
	AlertCircle,
	Info,
	Lock,
	Users,
	Shield,
} from "lucide-react";
import { toast } from "sonner";
import Modal from "../ui/Modal";
import Button from "../ui/Button";
import { updateAreaAccessRules } from "../../services/areaService";
import "./AreaAccessRulesModal.css";

const ACCESS_LEVEL_OPTIONS = [
	{
		level: 1,
		title: "1 — Mọi người",
		desc: "Sinh viên, giảng viên, nhân viên, cộng tác viên và quản trị viên.",
		icon: Users,
	},
	{
		level: 2,
		title: "2 — Nhân viên vận hành",
		desc: "Bảo vệ, nhân sự hỗ trợ vận hành và quản lý cơ sở vật chất.",
		icon: Shield,
	},
	{
		level: 3,
		title: "3 — Quản lý cấp cao",
		desc: "Khu vực nhạy cảm, phòng máy chủ, phòng ban lãnh đạo và vị trí có ràng buộc an ninh cao.",
		icon: Lock,
	},
];

export default function AreaAccessRulesModal({
	isOpen,
	onClose,
	area,
	onSuccess,
}) {
	const [accessLevel, setAccessLevel] = useState(1);
	const [explicitAuth, setExplicitAuth] = useState(false);
	const [reason, setReason] = useState("");
	const [saving, setSaving] = useState(false);
	const [error, setError] = useState(null);

	useEffect(() => {
		if (area) {
			setAccessLevel(area.areaAccessLevel ?? 1);
			setExplicitAuth(Boolean(area.explicitAuthorizationRequired));
			setReason("");
			setError(null);
		}
	}, [area, isOpen]);

	if (!area) return null;

	const handleSubmit = async (e) => {
		e?.preventDefault();
		setError(null);

		const trimmedReason = reason.trim();
		if (!trimmedReason) {
			setError("Lý do cập nhật quy tắc là bắt buộc.");
			return;
		}
		if (trimmedReason.length > 500) {
			setError("Lý do cập nhật không được vượt quá 500 ký tự.");
			return;
		}

		setSaving(true);

		try {
			const payload = {
				areaAccessLevel: Number(accessLevel),
				explicitAuthorizationRequired: Boolean(explicitAuth),
				reason: trimmedReason,
			};

			const updated = await updateAreaAccessRules(area.id, payload);
			toast.success(`Đã cập nhật quy tắc truy cập của khu vực ${area.name}`);
			onSuccess?.(updated);
			onClose();
		} catch (err) {
			console.error("Lỗi khi cập nhật quy tắc truy cập khu vực:", err);
			const msg =
				err?.message ||
				"Không thể cập nhật quy tắc truy cập. Vui lòng thử lại.";
			setError(msg);
			toast.error(msg);
		} finally {
			setSaving(false);
		}
	};

	const footer = (
		<div className="access-rules-modal__footer">
			<Button
				variant="secondary"
				onClick={onClose}
				disabled={saving}
				type="button"
			>
				Hủy
			</Button>
			<Button
				variant="primary"
				onClick={handleSubmit}
				loading={saving}
				icon={ShieldCheck}
				type="button"
			>
				Lưu thay đổi
			</Button>
		</div>
	);

	return (
		<Modal
			isOpen={isOpen}
			onClose={onClose}
			title="Quy tắc truy cập khu vực"
			subtitle={`Cấu hình mức độ truy cập tự do và điều kiện cấp phép cụ thể cho: ${area.name} (${area.code})`}
			icon={ShieldCheck}
			iconVariant="brand"
			size="md"
			footer={footer}
		>
			<form
				onSubmit={handleSubmit}
				className="access-rules-form"
			>
				{error && (
					<div className="access-rules-alert access-rules-alert--error">
						<AlertCircle size={16} />
						<span>{error}</span>
					</div>
				)}

				{/* Section 1: areaAccessLevel */}
				<div className="access-rules-group">
					<label className="access-rules-label">
						Mức độ bảo mật của khu vực
					</label>
					<p className="access-rules-hint">
						Người dùng có cấp độ quyền hạn lớn hơn hoặc bằng mức này sẽ được phép
						truy cập tự do khi chế độ xác thực cụ thể không được kích hoạt.
					</p>

					<div className="access-rules-options">
						{ACCESS_LEVEL_OPTIONS.map((opt) => {
							const isSelected = accessLevel === opt.level;
							const IconComponent = opt.icon;
							return (
								<label
									key={opt.level}
									className={`access-rules-option ${isSelected ? "access-rules-option--selected" : ""}`}
								>
									<input
										type="radio"
										name="areaAccessLevel"
										value={opt.level}
										checked={isSelected}
										onChange={() => setAccessLevel(opt.level)}
										className="access-rules-option__radio"
									/>
									<div className="access-rules-option__content">
										<div className="access-rules-option__header">
											<IconComponent
												size={16}
												className="access-rules-option__icon"
											/>
											<span className="access-rules-option__title">
												{opt.title}
											</span>
										</div>
										<span className="access-rules-option__desc">
											{opt.desc}
										</span>
									</div>
								</label>
							);
						})}
					</div>
				</div>

				{/* Section 2: explicitAuthorizationRequired */}
				<div className="access-rules-group">
					<label className="access-rules-checkbox-card">
						<div className="access-rules-checkbox-wrap">
							<input
								type="checkbox"
								id="explicit-auth-toggle"
								checked={explicitAuth}
								onChange={(e) => setExplicitAuth(e.target.checked)}
								className="access-rules-checkbox"
							/>
						</div>
						<div className="access-rules-checkbox-info">
							<span className="access-rules-checkbox-title">
								Yêu cầu phê duyệt trước khi truy cập
							</span>
							<p className="access-rules-checkbox-desc">
								Khi bật, chỉ người được phân quyền hoặc có đơn phê duyệt hợp lệ
								mới được phép vào khu vực; mức độ truy cập tự do không được áp
								dụng.
							</p>
						</div>
					</label>
				</div>

				{/* Section 3: Lý do cập nhật (Bắt buộc) */}
				<div className="access-rules-group" style={{ marginTop: "16px" }}>
					<label
						htmlFor="access-rules-reason"
						style={{
							display: "block",
							marginBottom: "6px",
							fontSize: "13.5px",
							fontWeight: 600,
							color: "var(--theme-text-primary, #0f172a)",
						}}
					>
						Lý do cập nhật <span style={{ color: "var(--theme-danger, #ef4444)" }}>*</span>
					</label>
					<textarea
						id="access-rules-reason"
						rows={3}
						style={{
							width: "100%",
							padding: "8px 12px",
							borderRadius: "8px",
							border: "1px solid var(--theme-border, #cbd5e1)",
							background: "var(--theme-card-bg, #ffffff)",
							color: "var(--theme-text-primary, #0f172a)",
							fontSize: "13.5px",
							lineHeight: "1.5",
							resize: "vertical",
							boxSizing: "border-box",
						}}
						placeholder="Nhập lý do điều chỉnh quy tắc truy cập khu vực (tối đa 500 ký tự)..."
						value={reason}
						onChange={(e) => setReason(e.target.value)}
						maxLength={500}
						required
					/>
					<div
						style={{
							display: "flex",
							justifyContent: "space-between",
							fontSize: "12px",
							color: "var(--theme-text-muted, #64748b)",
							marginTop: "4px",
						}}
					>
						<span>Bắt buộc theo quy định kiểm toán truy cập</span>
						<span>{reason.length}/500</span>
					</div>
				</div>
			</form>
		</Modal>
	);
}
