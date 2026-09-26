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
import { updateAreaAccessRules, updateAreaEventMode } from "../../services/areaService";
import "./AreaAccessRulesModal.css";

const ACCESS_LEVEL_OPTIONS = [
	{
		level: 1,
		title: "Cấp 1 — Mọi người",
		desc: "Sinh viên, giảng viên, nhân viên, cộng tác viên và quản trị viên.",
		icon: Users,
	},
	{
		level: 2,
		title: "Cấp 2 — Nhân viên vận hành",
		desc: "Bảo vệ, nhân sự hỗ trợ vận hành và quản lý cơ sở vật chất.",
		icon: Shield,
	},
	{
		level: 3,
		title: "Cấp 3 — Quản lý cấp cao",
		desc: "Khu vực nhạy cảm, phòng máy chủ, phòng ban lãnh đạo và vị trí có ràng buộc an ninh cao.",
		icon: Lock,
	},
];

export default function AreaAccessRulesModal({
	isOpen,
	onClose,
	area,
	onSuccess,
	levelPresets,
}) {
	const [accessLevel, setAccessLevel] = useState(1);
	const [explicitAuth, setExplicitAuth] = useState(false);
	const [eventModeEnabled, setEventModeEnabled] = useState(false);
	const [openUntil, setOpenUntil] = useState("");
	const [reason, setReason] = useState("");
	const [saving, setSaving] = useState(false);
	const [error, setError] = useState(null);

	const areaLevelKey = area?.areaLevel || area?.level?.code;
	const isEventModeApplicable =
		areaLevelKey === "INTERNAL_CONFIDENTIAL" ||
		areaLevelKey === "CONFIDENTIAL_CONTACT_REQUIRED";

	useEffect(() => {
		if (area) {
			setAccessLevel(area.areaAccessLevel ?? 1);
			setExplicitAuth(Boolean(area.explicitAuthorizationRequired));
			setEventModeEnabled(Boolean(area.openToMembers));
			if (area.openUntil) {
				const d = new Date(area.openUntil);
				const pad = (n) => String(n).padStart(2, "0");
				const localIso = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
				setOpenUntil(localIso);
			} else {
				setOpenUntil("");
			}
			setReason("");
			setError(null);
		}
	}, [area, isOpen]);

	if (!area) return null;

	const floorPart = area.floor
		? String(area.floor).startsWith("Tầng")
			? area.floor
			: `Tầng ${area.floor}`
		: null;
	const loc = [area.building, floorPart].filter(Boolean).join(" · ");
	const areaDisplay = loc ? `${area.name} (${loc})` : area.name;

	const preset = levelPresets?.[areaLevelKey];

	const handleSubmit = async (e) => {
		e?.preventDefault();
		setError(null);

		const trimmedReason = reason.trim();
		if (!trimmedReason) {
			setError("Lý do cập nhật là bắt buộc.");
			return;
		}
		if (trimmedReason.length > 500) {
			setError("Lý do cập nhật không được vượt quá 500 ký tự.");
			return;
		}

		const rulesChanged =
			Number(accessLevel) !== (area.areaAccessLevel ?? 1) ||
			Boolean(explicitAuth) !== Boolean(area.explicitAuthorizationRequired);

		let initialOpenUntilLocal = "";
		if (area.openUntil) {
			const d = new Date(area.openUntil);
			const pad = (n) => String(n).padStart(2, "0");
			initialOpenUntilLocal = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
		}

		const eventModeChanged =
			isEventModeApplicable &&
			(Boolean(eventModeEnabled) !== Boolean(area.openToMembers) ||
				(Boolean(eventModeEnabled) && openUntil !== initialOpenUntilLocal));

		if (!rulesChanged && !eventModeChanged) {
			toast.info("Không có thay đổi nào cần lưu.");
			onClose();
			return;
		}

		if (eventModeChanged && eventModeEnabled) {
			if (!openUntil) {
				setError("Thời điểm kết thúc sự kiện là bắt buộc khi bật chế độ sự kiện.");
				return;
			}
			const selectedTime = new Date(openUntil).getTime();
			if (selectedTime <= Date.now()) {
				setError("Thời điểm kết thúc sự kiện phải ở trong tương lai.");
				return;
			}
		}

		setSaving(true);

		try {
			let latestUpdated = null;
			if (rulesChanged) {
				const payload = {
					areaAccessLevel: Number(accessLevel),
					explicitAuthorizationRequired: Boolean(explicitAuth),
					reason: trimmedReason,
				};
				latestUpdated = await updateAreaAccessRules(area.id, payload);
			}

			if (eventModeChanged) {
				const eventPayload = {
					enabled: Boolean(eventModeEnabled),
					openUntil: eventModeEnabled ? new Date(openUntil).toISOString() : null,
					reason: trimmedReason,
				};
				latestUpdated = await updateAreaEventMode(area.id, eventPayload);
			}

			toast.success(`Đã cập nhật quy tắc khu vực ${area.name}`);
			onSuccess?.(latestUpdated || area);
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
			subtitle={`Cấu hình mức độ truy cập tự do và điều kiện cấp phép cụ thể cho: ${areaDisplay}`}
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

				{preset && (
					<div
						style={{
							padding: "10px 14px",
							borderRadius: "8px",
							background: "rgba(59, 130, 246, 0.08)",
							border: "1px solid rgba(59, 130, 246, 0.2)",
							marginBottom: "16px",
							fontSize: "12.5px",
							display: "flex",
							alignItems: "center",
							gap: "8px",
							color: "var(--theme-text-primary, #0f172a)",
						}}
					>
						<Info
							size={16}
							style={{ color: "var(--brand-blue, #3b82f6)", flexShrink: 0 }}
						/>
						<div>
							<strong>Giá trị mặc định của phân loại ({areaLevelKey}):</strong>{" "}
							Cấp {preset.areaAccessLevel} · Yêu cầu chỉ định:{" "}
							{preset.explicitAuthorizationRequired ? "Có" : "Không"}
						</div>
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
								Cấp độ không tự cho vào – cần được chỉ định hoặc có đơn được duyệt
							</span>
							<p className="access-rules-checkbox-desc">
								Khi bật, chỉ người được phân quyền hoặc có đơn phê duyệt hợp lệ
								mới được phép vào khu vực; mức độ truy cập tự do không được áp
								dụng.
							</p>
						</div>
					</label>
				</div>

				{/* Section 2b: Chế độ sự kiện (Event Mode / open_to_members) */}
				<div className="access-rules-group">
					<label className="access-rules-label">
						Chế độ sự kiện
					</label>
					{!isEventModeApplicable ? (
						<div
							style={{
								padding: "10px 14px",
								borderRadius: "8px",
								background: "rgba(100, 116, 139, 0.08)",
								border: "1px solid rgba(100, 116, 139, 0.2)",
								fontSize: "12.5px",
								color: "var(--theme-text-muted, #64748b)",
							}}
						>
							Chế độ sự kiện chỉ áp dụng cho khu vực <strong>Bảo mật nội bộ</strong> hoặc <strong>Bảo mật - liên hệ trước</strong> (không áp dụng cho khu vực Công khai và Tuyệt mật).
						</div>
					) : (
						<div
							style={{
								padding: "12px 14px",
								borderRadius: "10px",
								background: "var(--theme-bg-surface, #ffffff)",
								border: "1px solid var(--theme-border, rgba(255, 255, 255, 0.1))",
								display: "flex",
								flexDirection: "column",
								gap: "12px",
							}}
						>
							<label
								style={{
									display: "flex",
									alignItems: "center",
									justifyContent: "space-between",
									cursor: "pointer",
									userSelect: "none",
								}}
							>
								<div>
									<div style={{ fontWeight: 600, fontSize: "13.5px", color: "var(--theme-text-primary, #0f172a)" }}>
										Mở cửa cho thành viên trong thời gian sự kiện
									</div>
									<div style={{ fontSize: "12px", color: "var(--theme-text-muted, #64748b)", marginTop: "2px" }}>
										Khi bật, tất cả thành viên hợp lệ được phép vào khu vực cho đến thời điểm kết thúc mà không cần đơn truy cập riêng.
									</div>
								</div>
								<input
									type="checkbox"
									id="event-mode-toggle"
									checked={eventModeEnabled}
									onChange={(e) => setEventModeEnabled(e.target.checked)}
									style={{ width: "18px", height: "18px", cursor: "pointer", accentColor: "var(--brand-blue, #3b82f6)" }}
								/>
							</label>

							{eventModeEnabled && (
								<div style={{ borderTop: "1px dashed var(--theme-border, #cbd5e1)", paddingTop: "10px" }}>
									<label
										htmlFor="event-mode-open-until"
										style={{
											display: "block",
											marginBottom: "6px",
											fontSize: "12.5px",
											fontWeight: 600,
											color: "var(--theme-text-primary, #0f172a)",
										}}
									>
										Thời điểm kết thúc sự kiện <span style={{ color: "var(--theme-danger, #ef4444)" }}>*</span>
									</label>
									<input
										type="datetime-local"
										id="event-mode-open-until"
										value={openUntil}
										onChange={(e) => setOpenUntil(e.target.value)}
										min={new Date().toISOString().slice(0, 16)}
										required
										style={{
											width: "100%",
											padding: "8px 12px",
											borderRadius: "8px",
											border: "1px solid var(--theme-border, #cbd5e1)",
											background: "var(--theme-card-bg, #ffffff)",
											color: "var(--theme-text-primary, #0f172a)",
											fontSize: "13.5px",
											boxSizing: "border-box",
										}}
									/>
									<p style={{ fontSize: "11.5px", color: "var(--theme-text-muted, #64748b)", margin: "4px 0 0 0" }}>
										Sau thời điểm này, chế độ sự kiện sẽ tự động hết hiệu lực mà không cần can thiệp thủ công.
									</p>
								</div>
							)}
						</div>
					)}
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
