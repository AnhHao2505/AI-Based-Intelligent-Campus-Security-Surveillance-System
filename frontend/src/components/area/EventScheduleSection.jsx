import React, { useState, useEffect, useCallback } from "react";
import { CalendarClock, CalendarPlus, Pencil, XCircle, AlertCircle, RotateCcw } from "lucide-react";
import { toast } from "sonner";
import Button from "../ui/Button";
import Badge from "../ui/Badge";
import { useAuth } from "../../context/AuthContext";
import {
	getEventSchedules,
	createEventSchedule,
	updateEventSchedule,
	cancelEventSchedule,
} from "../../services/areaService";
import { getActiveReasons } from "../../services/reasonCatalogService";
import { formatDisplayDateTime, getScheduleStatusView } from "../../utils/areaHelpers";
import "./EventScheduleSection.css";

// Nhóm lý do riêng cho từng thao tác với lịch (Step 5b, BR-ES-L1/L2)
const REASON_ACTION = {
	create: "EVENT_SCHEDULE_CREATE",
	edit: "EVENT_SCHEDULE_UPDATE",
	cancel: "EVENT_SCHEDULE_CANCEL",
};

const pad = (n) => String(n).padStart(2, "0");

/** ISO từ backend -> giá trị cho input datetime-local theo giờ máy */
function toLocalInput(iso) {
	if (!iso) return "";
	const d = new Date(iso);
	if (isNaN(d.getTime())) return "";
	return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Giá trị datetime-local -> ISO-8601 kèm offset giờ máy, ví dụ 2026-09-28T08:00:00+07:00 */
function toOffsetIso(localValue) {
	const d = new Date(localValue);
	const offsetMin = -d.getTimezoneOffset();
	const sign = offsetMin >= 0 ? "+" : "-";
	const abs = Math.abs(offsetMin);
	return `${localValue}:00${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

const EMPTY_FORM = { startAt: "", endAt: "", reasonCode: "", note: "" };

/**
 * Mục "Lịch sự kiện" trong modal quy tắc truy cập.
 * FACILITY_MANAGER được đặt / sửa / huỷ lịch; vai trò khác chỉ xem.
 * Mọi giới hạn nghiệp vụ (số lịch, thời lượng, đặt trước, ngân sách, chồng giờ) do backend quyết.
 */
export default function EventScheduleSection({ area, onSchedulesChanged }) {
	const { user } = useAuth();
	const canManage = user?.role === "FACILITY_MANAGER";
	const areaId = area?.id;

	const [schedules, setSchedules] = useState([]);
	const [loading, setLoading] = useState(false);
	const [loadError, setLoadError] = useState(null);
	const [showPast, setShowPast] = useState(false);

	// mode: null | "create" | "edit" | "cancel"
	const [mode, setMode] = useState(null);
	const [target, setTarget] = useState(null);
	const [form, setForm] = useState(EMPTY_FORM);
	const [reasons, setReasons] = useState([]);
	const [loadingReasons, setLoadingReasons] = useState(false);
	const [formError, setFormError] = useState(null);
	const [submitting, setSubmitting] = useState(false);

	const loadSchedules = useCallback(async () => {
		if (!areaId) return;
		setLoading(true);
		setLoadError(null);
		try {
			const data = await getEventSchedules(areaId, showPast ? undefined : "SCHEDULED");
			setSchedules(Array.isArray(data) ? data : []);
		} catch (err) {
			console.error("Lỗi khi tải lịch sự kiện:", err);
			setSchedules([]);
			setLoadError(err?.message || "Không thể tải lịch sự kiện.");
		} finally {
			setLoading(false);
		}
	}, [areaId, showPast]);

	useEffect(() => {
		loadSchedules();
	}, [loadSchedules]);

	// Đổi khu vực -> đóng form đang mở
	useEffect(() => {
		setMode(null);
		setTarget(null);
		setForm(EMPTY_FORM);
		setFormError(null);
	}, [areaId]);

	// Tải danh mục lý do theo thao tác đang mở
	useEffect(() => {
		if (!mode) {
			setReasons([]);
			return;
		}
		let isMounted = true;
		setLoadingReasons(true);
		getActiveReasons(REASON_ACTION[mode])
			.then((data) => {
				if (!isMounted) return;
				const list = Array.isArray(data) ? data : [];
				setReasons(list);
				setForm((prev) => ({
					...prev,
					reasonCode: list.some((r) => r.code === prev.reasonCode)
						? prev.reasonCode
						: list[0]?.code || "",
				}));
			})
			.catch((err) => {
				console.error("Lỗi khi tải danh mục lý do lịch sự kiện:", err);
				if (isMounted) setFormError(err?.message || "Không thể tải danh mục lý do.");
			})
			.finally(() => {
				if (isMounted) setLoadingReasons(false);
			});
		return () => {
			isMounted = false;
		};
	}, [mode]);

	const openForm = (nextMode, schedule = null) => {
		setMode(nextMode);
		setTarget(schedule);
		setFormError(null);
		if (nextMode === "edit" && schedule) {
			setForm({
				startAt: toLocalInput(schedule.startAt),
				endAt: toLocalInput(schedule.endAt),
				reasonCode: schedule.reasonCode || "",
				note: schedule.note || "",
			});
		} else {
			setForm(EMPTY_FORM);
		}
	};

	const closeForm = () => {
		setMode(null);
		setTarget(null);
		setForm(EMPTY_FORM);
		setFormError(null);
	};


	const validate = () => {
		if (mode !== "cancel") {
			if (!form.startAt || !form.endAt) return "Vui lòng chọn giờ bắt đầu và giờ kết thúc.";
			const start = new Date(form.startAt).getTime();
			const end = new Date(form.endAt).getTime();
			if (start <= Date.now()) return "Giờ bắt đầu phải ở trong tương lai.";
			if (end <= start) return "Giờ kết thúc phải sau giờ bắt đầu.";
		}
		if (!form.reasonCode) return "Vui lòng chọn lý do từ danh mục.";
		const note = form.note.trim();
		if (note.length < 10 || note.length > 500) return "Ghi chú phải có từ 10 đến 500 ký tự.";
		return null;
	};

	const handleSubmit = async () => {
		const msg = validate();
		if (msg) {
			setFormError(msg);
			return;
		}
		setFormError(null);
		setSubmitting(true);
		const note = form.note.trim();
		try {
			if (mode === "create") {
				await createEventSchedule(areaId, {
					startAt: toOffsetIso(form.startAt),
					endAt: toOffsetIso(form.endAt),
					reasonCode: form.reasonCode,
					note,
				});
				toast.success("Đã đặt lịch sự kiện");
			} else if (mode === "edit") {
				await updateEventSchedule(areaId, target.id, {
					startAt: toOffsetIso(form.startAt),
					endAt: toOffsetIso(form.endAt),
					reasonCode: form.reasonCode,
					note,
				});
				toast.success("Đã cập nhật lịch sự kiện");
			} else if (mode === "cancel") {
				await cancelEventSchedule(areaId, target.id, { reasonCode: form.reasonCode, note });
				toast.success("Đã huỷ lịch sự kiện");
			}
			closeForm();
			await loadSchedules();
			// Không cập nhật khu vực ở đây: modal cha tải lại khi đóng, để không reset form quy tắc đang sửa
			onSchedulesChanged?.();
		} catch (err) {
			console.error("Lỗi thao tác lịch sự kiện:", err);
			const errMsg = err?.message || "Không thể thực hiện thao tác với lịch sự kiện.";
			setFormError(errMsg);
			toast.error(errMsg);
			if (err?.status === 409) {
				await loadSchedules();
			}
		} finally {
			setSubmitting(false);
		}
	};

	// Không để Enter trong ô nhập gửi form quy tắc bên ngoài
	const preventEnterSubmit = (e) => {
		if (e.key === "Enter" && e.target.tagName !== "TEXTAREA") e.preventDefault();
	};

	const nowLocal = toLocalInput(new Date().toISOString());
	const noteLength = form.note.trim().length;
	const noteValid = noteLength >= 10 && noteLength <= 500;

	const renderForm = () => {
		const title =
			mode === "create" ? "Đặt lịch sự kiện" : mode === "edit" ? "Sửa lịch sự kiện" : "Huỷ lịch sự kiện";
		return (
			<div className="evs-form" onKeyDown={preventEnterSubmit}>
				<div className="evs-form__title">{title}</div>
				{mode === "cancel" && target && (
					<div className="evs-form__hint">
						Lịch {formatDisplayDateTime(target.startAt)} → {formatDisplayDateTime(target.endAt)}
					</div>
				)}
				{formError && (
					<div className="evs-alert evs-alert--error">
						<AlertCircle size={14} />
						<span>{formError}</span>
					</div>
				)}
				{mode !== "cancel" && (
					<div className="evs-form__row">
						<label className="evs-field">
							<span className="evs-field__label">
								Bắt đầu <span className="evs-required">*</span>
							</span>
							<input
								type="datetime-local"
								className="evs-input"
								value={form.startAt}
								min={nowLocal}
								onChange={(e) => setForm((p) => ({ ...p, startAt: e.target.value }))}
							/>
						</label>
						<label className="evs-field">
							<span className="evs-field__label">
								Kết thúc <span className="evs-required">*</span>
							</span>
							<input
								type="datetime-local"
								className="evs-input"
								value={form.endAt}
								min={form.startAt || nowLocal}
								onChange={(e) => setForm((p) => ({ ...p, endAt: e.target.value }))}
							/>
						</label>
					</div>
				)}
				<label className="evs-field">
					<span className="evs-field__label">
						{mode === "cancel" ? "Lý do huỷ" : "Lý do"} <span className="evs-required">*</span>
					</span>
					{loadingReasons ? (
						<span className="evs-muted">Đang tải danh mục lý do...</span>
					) : (
						<select
							className="evs-input"
							value={form.reasonCode}
							onChange={(e) => setForm((p) => ({ ...p, reasonCode: e.target.value }))}
						>
							{reasons.length === 0 && <option value="">Chưa có lý do khả dụng</option>}
							{reasons.map((r) => (
								<option key={r.code} value={r.code}>
									{r.label}
								</option>
							))}
						</select>
					)}
				</label>
				<label className="evs-field">
					<span className="evs-field__label evs-field__label--split">
						<span>
							Ghi chú <span className="evs-required">*</span>
						</span>
						<span className={noteValid ? "evs-counter" : "evs-counter evs-counter--invalid"}>
							{noteLength}/500 (tối thiểu 10 ký tự)
						</span>
					</span>
					<textarea
						className="evs-input evs-textarea"
						rows={2}
						maxLength={500}
						value={form.note}
						placeholder="Nêu tên sự kiện hoặc đơn vị tổ chức (10–500 ký tự)"
						onChange={(e) => setForm((p) => ({ ...p, note: e.target.value }))}
					/>
				</label>
				<div className="evs-form__actions">
					<Button variant="secondary" size="sm" onClick={closeForm} disabled={submitting}>
						Đóng
					</Button>
					<Button
						variant={mode === "cancel" ? "danger" : "primary"}
						size="sm"
						onClick={handleSubmit}
						loading={submitting}
						disabled={submitting || loadingReasons}
					>
						{mode === "create" ? "Đặt lịch" : mode === "edit" ? "Lưu lịch" : "Xác nhận huỷ"}
					</Button>
				</div>
			</div>
		);
	};

	const renderScheduleItem = (s) => {
		const status = getScheduleStatusView(s.status);
		const isEditing = target?.id === s.id && (mode === "edit" || mode === "cancel");
		return (
			<li key={s.id} className="evs-item">
				<div className="evs-item__head">
					<span className="evs-item__time">
						{formatDisplayDateTime(s.startAt)} → {formatDisplayDateTime(s.endAt)}
					</span>
					<Badge variant={status.variant}>{status.label}</Badge>
				</div>
				<div className="evs-item__meta">
					<div>
						<span className="evs-item__key">Lý do:</span> {s.reasonLabel || s.reasonCode || "—"}
						{s.note ? ` – ${s.note}` : ""}
					</div>
					<div>
						<span className="evs-item__key">Người đặt:</span> {s.createdByName || "—"}
						{s.createdAt ? ` · ${formatDisplayDateTime(s.createdAt)}` : ""}
					</div>
					{s.updatedByName && s.updatedAt && (
						<div>
							<span className="evs-item__key">Sửa lần cuối:</span> {s.updatedByName} ·{" "}
							{formatDisplayDateTime(s.updatedAt)}
						</div>
					)}
					{s.status === "CANCELLED" && (
						<div>
							<span className="evs-item__key">Huỷ bởi:</span> {s.cancelledByName || "—"}
							{s.cancelledAt ? ` · ${formatDisplayDateTime(s.cancelledAt)}` : ""}
							{s.cancelReasonLabel ? ` · ${s.cancelReasonLabel}` : ""}
							{s.cancelNote ? ` – ${s.cancelNote}` : ""}
						</div>
					)}
					{s.status === "FAILED" && (
						<div className="evs-item__fail">
							<span className="evs-item__key">Thất bại:</span> {s.failReason || "—"}
							{s.failedAt ? ` · ${formatDisplayDateTime(s.failedAt)}` : ""}
						</div>
					)}
				</div>
				{canManage && s.status === "SCHEDULED" && !isEditing && (
					<div className="evs-item__actions">
						<Button variant="ghost" size="sm" icon={Pencil} onClick={() => openForm("edit", s)} disabled={submitting}>
							Sửa
						</Button>
						<Button variant="ghost" size="sm" icon={XCircle} onClick={() => openForm("cancel", s)} disabled={submitting}>
							Huỷ
						</Button>
					</div>
				)}
				{isEditing && renderForm()}
			</li>
		);
	};

	return (
		<div className="evs-section">
			<div className="evs-header">
				<span className="evs-header__title">
					<CalendarClock size={15} />
					Lịch sự kiện
				</span>
				<label className="evs-toggle">
					<input type="checkbox" checked={showPast} onChange={(e) => setShowPast(e.target.checked)} />
					<span>Hiện cả lịch đã qua</span>
				</label>
			</div>

			{loading ? (
				<div className="evs-muted">Đang tải lịch sự kiện...</div>
			) : loadError ? (
				<div className="evs-alert evs-alert--error">
					<AlertCircle size={14} />
					<span>{loadError}</span>
					<Button variant="ghost" size="sm" icon={RotateCcw} onClick={loadSchedules}>
						Thử lại
					</Button>
				</div>
			) : schedules.length === 0 ? (
				<div className="evs-muted">
					{showPast ? "Khu vực chưa có lịch sự kiện nào." : "Chưa có lịch sự kiện sắp tới."}
				</div>
			) : (
				<ul className="evs-list">{schedules.map(renderScheduleItem)}</ul>
			)}

			{canManage && mode === "create" && renderForm()}
			{canManage && !mode && (
				<div>
					<Button variant="secondary" size="sm" icon={CalendarPlus} onClick={() => openForm("create")}>
						Đặt lịch
					</Button>
				</div>
			)}
		</div>
	);
}
