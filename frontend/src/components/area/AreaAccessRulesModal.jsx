import React, { useState, useEffect, useRef } from "react";
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
import EventScheduleSection from "./EventScheduleSection";
import { getAreaById, updateAreaAccessRules, updateAreaEventMode } from "../../services/areaService";
import { getActiveReasons } from "../../services/reasonCatalogService";
import { formatDisplayDateTime, getLevelConfig } from "../../utils/areaHelpers";
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

// Step 5b (BR-EV-A1): ý định gửi lên server thay cho trường enabled cũ
const EVENT_ACTION_TO_API = {
	EVENT_ENABLE: "ENABLE",
	EVENT_EXTEND: "ADJUST",
	EVENT_DISABLE: "DISABLE",
};

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

	// Event Mode Reason Catalog & Note state
	const [eventReasons, setEventReasons] = useState([]);
	const [eventReasonCode, setEventReasonCode] = useState("");
	const [eventNote, setEventNote] = useState("");
	const [loadingReasons, setLoadingReasons] = useState(false);

	// Step 5b (BR-TC-13): version khu vực gửi kèm mọi lần lưu; cập nhật theo response và sau thao tác lịch
	const [areaVersion, setAreaVersion] = useState(null);

	// Mở modal: tải lại khu vực để form và version là dữ liệu mới nhất (không dùng bản có thể đã cũ của danh sách)
	useEffect(() => {
		if (!isOpen || !area?.id) return;
		let alive = true;
		getAreaById(area.id)
			.then((res) => {
				if (!alive) return;
				const fresh = res?.data || res;
				if (fresh?.id) {
					setAreaVersion(fresh.version ?? null);
					onSuccess?.(fresh);
				}
			})
			.catch((err) => console.error("Lỗi khi tải khu vực lúc mở modal quy tắc:", err));
		return () => {
			alive = false;
		};
	}, [isOpen, area?.id, onSuccess]);

	// Thao tác lịch không cập nhật `area` ngay (sẽ reset form quy tắc đang sửa);
	// chỉ lấy version mới, còn tải lại khu vực (badge số lịch) sau khi đóng modal.
	const schedulesChangedRef = useRef(false);
	const handleClose = () => {
		const changedAreaId = schedulesChangedRef.current ? area?.id : null;
		schedulesChangedRef.current = false;
		onClose();
		if (changedAreaId) {
			getAreaById(changedAreaId)
				.then((fresh) => onSuccess?.(fresh?.data || fresh))
				.catch((err) => console.error("Lỗi khi tải lại khu vực sau thao tác lịch:", err));
		}
	};

	const areaLevelKey = area?.areaLevel || area?.level?.code;
	const isEventModeApplicable =
		areaLevelKey === "INTERNAL_CONFIDENTIAL" ||
		areaLevelKey === "CONFIDENTIAL_CONTACT_REQUIRED";

	const isEventExpired = Boolean(area?.openToMembers) && !Boolean(area?.eventActive);

	let initialOpenUntilLocal = "";
	if (area?.openUntil) {
		const d = new Date(area.openUntil);
		const pad = (n) => String(n).padStart(2, "0");
		initialOpenUntilLocal = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
	}

	const rulesChanged =
		Number(accessLevel) !== (area?.areaAccessLevel ?? 1) ||
		Boolean(explicitAuth) !== Boolean(area?.explicitAuthorizationRequired);

	const isEventOpenChanged = Boolean(eventModeEnabled) !== Boolean(area?.eventActive);
	const isTimeChanged = Boolean(eventModeEnabled) && openUntil !== initialOpenUntilLocal;
	const eventModeChanged = isEventModeApplicable && (isEventOpenChanged || isTimeChanged);

	let currentEventAction = null;
	if (eventModeChanged) {
		if (!area?.eventActive && eventModeEnabled) {
			currentEventAction = "EVENT_ENABLE";
		} else if (area?.eventActive && !eventModeEnabled) {
			currentEventAction = "EVENT_DISABLE";
		} else if (area?.eventActive && eventModeEnabled && isTimeChanged) {
			currentEventAction = "EVENT_EXTEND";
		}
	}

	useEffect(() => {
		if (area) {
			setAreaVersion(area.version ?? null);
			setAccessLevel(area.areaAccessLevel ?? 1);
			setExplicitAuth(Boolean(area.explicitAuthorizationRequired));
			setEventModeEnabled(Boolean(area.eventActive));
			if (area.openUntil) {
				const d = new Date(area.openUntil);
				const pad = (n) => String(n).padStart(2, "0");
				const localIso = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
				setOpenUntil(localIso);
			} else {
				setOpenUntil("");
			}
			setReason("");
			setEventNote("");
			setError(null);
		}
	}, [area, isOpen]);

	useEffect(() => {
		if (!currentEventAction) {
			setEventReasons([]);
			setEventReasonCode("");
			return;
		}
		let isMounted = true;
		setLoadingReasons(true);
		getActiveReasons(currentEventAction)
			.then((data) => {
				if (!isMounted) return;
				const list = Array.isArray(data) ? data : [];
				setEventReasons(list);
				if (list.length > 0) {
					setEventReasonCode((prev) => (list.some((r) => r.code === prev) ? prev : list[0].code));
				} else {
					setEventReasonCode("");
				}
			})
			.catch((err) => {
				console.error("Lỗi khi tải danh mục lý do sự kiện:", err);
			})
			.finally(() => {
				if (isMounted) setLoadingReasons(false);
			});
		return () => {
			isMounted = false;
		};
	}, [currentEventAction]);

	if (!area) return null;

	const floorPart = area.floor
		? String(area.floor).startsWith("Tầng")
			? area.floor
			: `Tầng ${area.floor}`
		: null;
	const loc = [area.building, floorPart].filter(Boolean).join(" · ");
	const areaDisplay = [area.name, loc].filter(Boolean).join(" · ");

	const preset = levelPresets?.[areaLevelKey];

	const handleSubmit = async (e) => {
		e?.preventDefault();
		setError(null);

		if (!rulesChanged && !eventModeChanged) {
			toast.info("Không có thay đổi nào cần lưu.");
			handleClose();
			return;
		}

		const trimmedRuleReason = reason.trim();
		if (rulesChanged) {
			if (!trimmedRuleReason) {
				setError("Lý do cập nhật quy tắc khu vực là bắt buộc.");
				return;
			}
			if (trimmedRuleReason.length < 10) {
				setError("Lý do phải có từ 10 đến 500 ký tự.");
				return;
			}
			if (trimmedRuleReason.length > 500) {
				setError("Lý do phải có từ 10 đến 500 ký tự.");
				return;
			}
		}

		let trimmedEventNote = "";
		if (eventModeChanged) {
			if (eventModeEnabled) {
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
			if (!eventReasonCode) {
				setError("Vui lòng chọn lý do sự kiện từ danh mục.");
				return;
			}
			trimmedEventNote = eventNote.trim();
			if (!trimmedEventNote) {
				setError("Ghi chú thao tác sự kiện là bắt buộc.");
				return;
			}
			if (trimmedEventNote.length < 10) {
				setError("Ghi chú thao tác sự kiện phải có ít nhất 10 ký tự.");
				return;
			}
			if (trimmedEventNote.length > 500) {
				setError("Ghi chú thao tác sự kiện không được vượt quá 500 ký tự.");
				return;
			}
		}

		setSaving(true);

		try {
			let latestUpdated = null;
			let version = areaVersion;
			if (rulesChanged) {
				const payload = {
					areaAccessLevel: Number(accessLevel),
					explicitAuthorizationRequired: Boolean(explicitAuth),
					reason: trimmedRuleReason,
					version,
				};
				latestUpdated = await updateAreaAccessRules(area.id, payload);
				// Lần lưu sau dùng version trong response (lưu quy tắc đã làm version tăng)
				version = latestUpdated?.version ?? version;
				setAreaVersion(version);
			}

			if (eventModeChanged) {
				const action = EVENT_ACTION_TO_API[currentEventAction];
				const eventPayload = {
					action,
					openUntil: action !== "DISABLE" ? new Date(openUntil).toISOString() : null,
					reasonCode: eventReasonCode,
					note: trimmedEventNote,
					version,
				};
				latestUpdated = await updateAreaEventMode(area.id, eventPayload);
				setAreaVersion(latestUpdated?.version ?? version);
			}

			toast.success(`Đã cập nhật quy tắc khu vực ${area.name}`);
			schedulesChangedRef.current = false;
			onSuccess?.(latestUpdated || area);
			onClose();
		} catch (err) {
			console.error("Lỗi khi cập nhật quy tắc truy cập khu vực:", err);
			if (err?.code === "ERR_AREA_045") {
				// Người khác vừa cập nhật khu vực: tải lại dữ liệu mới nhất, giữ modal mở để FM xem lại rồi lưu lại
				const staleMsg = "Khu vực đã được người khác cập nhật. Đã tải lại dữ liệu mới nhất, vui lòng kiểm tra và lưu lại.";
				setError(staleMsg);
				toast.error(staleMsg);
				try {
					const reloaded = await getAreaById(area.id);
					const freshData = reloaded?.data || reloaded;
					if (freshData?.id) {
						onSuccess?.(freshData);
						setAreaVersion(freshData.version ?? null);
					}
				} catch (fetchErr) {
					console.error("Lỗi khi tải lại khu vực sau 409 ERR_AREA_045:", fetchErr);
				}
				return;
			}
			const msg =
				err?.message ||
				"Không thể cập nhật quy tắc truy cập. Vui lòng thử lại.";
			setError(msg);
			toast.error(msg);
			const status = err?.status || err?.response?.status;
			if (status === 409 || err?.code === "ERR_AREA_030") {
				try {
					const reloaded = await getAreaById(area.id);
					const freshData = reloaded?.data || reloaded;
					schedulesChangedRef.current = false;
					onSuccess?.(freshData);
					onClose();
				} catch (fetchErr) {
					console.error("Lỗi khi tải lại dữ liệu khu vực sau 409:", fetchErr);
					setError("Xung đột trạng thái: " + msg + " (Không thể tự động tải lại dữ liệu mới, vui lòng đóng và mở lại).");
				}
			}
		} finally {
			setSaving(false);
		}
	};

	const footer = (
		<div className="access-rules-modal__footer">
			<Button
				variant="secondary"
				onClick={handleClose}
				disabled={saving}
				type="button"
			>
				Hủy
			</Button>
			<Button
				variant="primary"
				onClick={handleSubmit}
				loading={saving}
				disabled={saving || (rulesChanged && reason.trim().length < 10)}
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
			onClose={handleClose}
			title="Quy tắc truy cập khu vực"
			subtitle={areaDisplay}
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
							<strong>Giá trị mặc định của loại {getLevelConfig(areaLevelKey).name}:</strong>{" "}
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
							{isEventExpired && (
								<div
									style={{
										padding: "8px 12px",
										borderRadius: "8px",
										background: "var(--theme-info-bg)",
										border: "1px solid var(--theme-info-border)",
										fontSize: "12.5px",
										display: "flex",
										alignItems: "center",
										gap: "8px",
										color: "var(--theme-info-text)",
									}}
								>
									<Info size={15} style={{ flexShrink: 0 }} />
									<span>
										Sự kiện đã kết thúc lúc{" "}
										{area.openUntil ? formatDisplayDateTime(area.openUntil) : "—"}{" "}
										(coi như đang tắt).
									</span>
								</div>
							)}

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
									{area?.eventActive && area?.eventStartedAt && (
										<div
											style={{
												marginBottom: "10px",
												fontSize: "12.5px",
												lineHeight: 1.6,
												color: "var(--theme-text-secondary, #475569)",
											}}
										>
											<div>
												<strong style={{ color: "var(--theme-text-primary, #0f172a)" }}>Bắt đầu lúc:</strong>{" "}
												{formatDisplayDateTime(area.eventStartedAt)}
												{area.eventStartedByName ? ` — bởi ${area.eventStartedByName}` : ""}
											</div>
											{area.eventLastAdjustedAt && (
												<div>
													<strong style={{ color: "var(--theme-text-primary, #0f172a)" }}>
														Điều chỉnh giờ kết thúc lần cuối:
													</strong>{" "}
													{formatDisplayDateTime(area.eventLastAdjustedAt)}
													{area.eventLastAdjustedByName ? ` — bởi ${area.eventLastAdjustedByName}` : ""}
												</div>
											)}
										</div>
									)}
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

							{/* Standardized Reason & Note for Event Mode */}
							{eventModeChanged && (
								<div
									style={{
										borderTop: "1px dashed var(--theme-border, #cbd5e1)",
										paddingTop: "12px",
										display: "flex",
										flexDirection: "column",
										gap: "10px",
									}}
								>
									<div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
										<span
											style={{
												fontSize: "11.5px",
												fontWeight: 700,
												textTransform: "uppercase",
												letterSpacing: "0.5px",
												color: "var(--brand-blue, #2563eb)",
											}}
										>
											Thao tác:
										</span>
										<span style={{ fontSize: "12.5px", fontWeight: 600, color: "var(--theme-text-primary, #0f172a)" }}>
											{currentEventAction === "EVENT_ENABLE" && "Bật chế độ sự kiện"}
											{currentEventAction === "EVENT_DISABLE" && "Tắt chế độ sự kiện"}
											{currentEventAction === "EVENT_EXTEND" && "Điều chỉnh giờ kết thúc"}
										</span>
									</div>

									<div>
										<label
											htmlFor="event-mode-reason-code"
											style={{
												display: "block",
												marginBottom: "4px",
												fontSize: "12.5px",
												fontWeight: 600,
												color: "var(--theme-text-primary, #0f172a)",
											}}
										>
											Lý do sự kiện <span style={{ color: "var(--theme-danger, #ef4444)" }}>*</span>
										</label>
										{loadingReasons ? (
											<div style={{ fontSize: "12px", color: "var(--theme-text-muted, #64748b)" }}>
												Đang tải danh mục lý do...
											</div>
										) : (
											<select
												id="event-mode-reason-code"
												value={eventReasonCode}
												onChange={(e) => setEventReasonCode(e.target.value)}
												style={{
													width: "100%",
													padding: "8px 12px",
													borderRadius: "8px",
													border: "1px solid var(--theme-border, #cbd5e1)",
													background: "var(--theme-card-bg, #ffffff)",
													fontSize: "13px",
													color: "var(--theme-text-primary, #0f172a)",
												}}
											>
												{eventReasons.map((r) => (
													<option key={r.code} value={r.code}>
														{r.label}
													</option>
												))}
											</select>
										)}
									</div>

									<div>
										<div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "4px" }}>
											<label
												htmlFor="event-mode-note"
												style={{
													fontSize: "12.5px",
													fontWeight: 600,
													color: "var(--theme-text-primary, #0f172a)",
												}}
											>
												Ghi chú thao tác sự kiện <span style={{ color: "var(--theme-danger, #ef4444)" }}>*</span>
											</label>
											<span
												style={{
													fontSize: "11.5px",
													fontWeight: 600,
													color:
														eventNote.trim().length >= 10 && eventNote.trim().length <= 500
															? "var(--theme-text-muted, #64748b)"
															: "var(--theme-danger, #ef4444)",
												}}
											>
												{eventNote.trim().length}/500 (tối thiểu 10 ký tự)
											</span>
										</div>
										<textarea
											id="event-mode-note"
											rows={2}
											value={eventNote}
											onChange={(e) => setEventNote(e.target.value)}
											placeholder="Nêu tên sự kiện hoặc đơn vị tổ chức (10–500 ký tự)"
											maxLength={500}
											style={{
												width: "100%",
												padding: "8px 12px",
												borderRadius: "8px",
												border: "1px solid var(--theme-border, #cbd5e1)",
												background: "var(--theme-card-bg, #ffffff)",
												fontSize: "13px",
												color: "var(--theme-text-primary, #0f172a)",
												lineHeight: "1.4",
												resize: "vertical",
												boxSizing: "border-box",
											}}
										/>
									</div>
								</div>
							)}

							{/* Lịch sự kiện (U3b) — thao tác độc lập với nút Lưu thay đổi */}
							<EventScheduleSection
								area={area}
								onSchedulesChanged={() => {
									schedulesChangedRef.current = true;
									// Thao tác lịch có thể làm version tăng (dọn phiên hết hạn): chỉ lấy version mới, không reset form
									getAreaById(area.id)
										.then((res) => {
											const fresh = res?.data || res;
											if (fresh?.id) setAreaVersion(fresh.version ?? null);
										})
										.catch((err) => console.error("Lỗi khi tải version khu vực sau thao tác lịch:", err));
								}}
							/>
						</div>
					)}
				</div>

				{/* Section 3: Lý do cập nhật quy tắc (Chỉ hiển thị khi có thay đổi Cấp độ hoặc Đích danh) */}
				{rulesChanged && (
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
							Lý do cập nhật quy tắc khu vực <span style={{ color: "var(--theme-danger, #ef4444)" }}>*</span>
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
							<span>Tối thiểu 10 ký tự, tối đa 500 ký tự</span>
							<span>{reason.length}/500 ký tự</span>
						</div>
					</div>
				)}
			</form>
		</Modal>
	);
}
