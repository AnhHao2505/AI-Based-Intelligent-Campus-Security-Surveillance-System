import React, { useEffect, useRef, useState } from "react";
import { AlertCircle, CalendarPlus, Pencil, XCircle } from "lucide-react";
import { toast } from "sonner";
import { Button, Input, Modal, ReasonTextarea, Select } from "../ui";
import {
	createEventSchedule,
	updateEventSchedule,
	cancelEventSchedule,
} from "../../services/areaService";
import { getActiveReasons } from "../../services/reasonCatalogService";
import { formatDisplayDateTime } from "../../utils/areaHelpers";
import { useReasonLimits } from "../../hooks/useReasonLimits";

// Nhóm lý do riêng cho từng thao tác với lịch (Step 5b, BR-ES-L1/L2)
const REASON_ACTION = {
	create: "EVENT_SCHEDULE_CREATE",
	edit: "EVENT_SCHEDULE_UPDATE",
	cancel: "EVENT_SCHEDULE_CANCEL",
};

/** V87: độ dài tối đa tên sự kiện = cột area_event_schedules.title */
const TITLE_MAX = 150;

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

const EMPTY_FORM = { title: "", startAt: "", endAt: "", areaId: "", reasonCode: "", note: "" };

/**
 * Form lịch sự kiện của màn Quản lý sự kiện (chỉ FACILITY_MANAGER).
 * - create: Tên → Bắt đầu → Kết thúc → Khu vực (Bảo mật đang hoạt động) → Ghi chú (không bắt buộc) → Loại sự kiện (EVENT_SCHEDULE_CREATE)
 * - edit: lịch SCHEDULED — đổi tên / giờ, lý do sửa (EVENT_SCHEDULE_UPDATE) + ghi chú lý do bắt buộc; loại và ghi chú gốc giữ nguyên
 * - cancel: lịch SCHEDULED — lý do huỷ (EVENT_SCHEDULE_CANCEL) + ghi chú bắt buộc (BR-ES-25)
 * Mọi giới hạn nghiệp vụ (số lịch, thời lượng, đặt trước, ngân sách, chồng giờ) do backend quyết; lỗi hiện đúng câu backend trả.
 */
export default function EventScheduleFormModal({ mode, schedule, areas, onClose, onSaved }) {
	const reasonLimits = useReasonLimits();
	const isOpen = Boolean(mode);
	const [form, setForm] = useState(EMPTY_FORM);
	const [reasons, setReasons] = useState([]);
	const [loadingReasons, setLoadingReasons] = useState(false);
	const [errors, setErrors] = useState({});
	const [formError, setFormError] = useState(null);
	const [submitting, setSubmitting] = useState(false);
	const noteRef = useRef(null);

	// Mở form -> điền sẵn
	useEffect(() => {
		if (!mode) return;
		setErrors({});
		setFormError(null);
		if (mode === "edit" && schedule) {
			setForm({
				title: schedule.title || "",
				startAt: toLocalInput(schedule.startAt),
				endAt: toLocalInput(schedule.endAt),
				areaId: schedule.areaId || "",
				reasonCode: "",
				// BE giữ ghi chú gốc khi sửa — ô này là lý do lần sửa, để trống
				note: "",
			});
		} else if (mode === "cancel" && schedule) {
			setForm({ ...EMPTY_FORM, areaId: schedule.areaId || "" });
		} else {
			setForm(EMPTY_FORM);
		}
	}, [mode, schedule]);

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
					reasonCode: list.some((r) => r.code === prev.reasonCode) ? prev.reasonCode : list[0]?.code || "",
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

	const setField = (key, value) => {
		setForm((p) => ({ ...p, [key]: value }));
		if (errors[key]) setErrors((p) => ({ ...p, [key]: null }));
	};

	const validate = () => {
		const next = {};
		if (mode !== "cancel") {
			const title = form.title.trim();
			if (!title) next.title = "Vui lòng nhập tên sự kiện.";
			else if (title.length > TITLE_MAX) next.title = `Tên sự kiện tối đa ${TITLE_MAX} ký tự.`;
			if (!form.startAt) next.startAt = "Vui lòng chọn giờ bắt đầu.";
			else if (new Date(form.startAt).getTime() <= Date.now()) next.startAt = "Giờ bắt đầu phải ở trong tương lai.";
			if (!form.endAt) next.endAt = "Vui lòng chọn giờ kết thúc.";
			else if (form.startAt && new Date(form.endAt).getTime() <= new Date(form.startAt).getTime())
				next.endAt = "Giờ kết thúc phải sau giờ bắt đầu.";
			if (!form.areaId) next.areaId = "Vui lòng chọn khu vực.";
		}
		if (!form.reasonCode) next.reasonCode = "Vui lòng chọn từ danh mục.";
		const noteRequired = mode !== "create";
		if (noteRequired || form.note.trim()) {
			const noteError = reasonLimits.error(form.note, mode === "edit" ? "Lý do sửa lịch" : mode === "cancel" ? "Lý do huỷ" : "Ghi chú");
			if (noteError) next.note = noteError;
		}
		return next;
	};

	const handleSubmit = async () => {
		const next = validate();
		if (Object.keys(next).length > 0) {
			setErrors(next);
			if (next.note && Object.keys(next).length === 1) noteRef.current?.focus();
			return;
		}
		setFormError(null);
		setSubmitting(true);
		const note = form.note.trim();
		try {
			if (mode === "create") {
				const created = await createEventSchedule(form.areaId, {
					title: form.title.trim(),
					startAt: toOffsetIso(form.startAt),
					endAt: toOffsetIso(form.endAt),
					reasonCode: form.reasonCode,
					note: note || null,
				});
				toast.success(`Đã tạo sự kiện "${created?.title || form.title.trim()}"`);
			} else if (mode === "edit") {
				await updateEventSchedule(schedule.areaId, schedule.id, {
					title: form.title.trim(),
					startAt: toOffsetIso(form.startAt),
					endAt: toOffsetIso(form.endAt),
					reasonCode: form.reasonCode,
					note,
				});
				toast.success("Đã cập nhật sự kiện");
			} else if (mode === "cancel") {
				await cancelEventSchedule(schedule.areaId, schedule.id, { reasonCode: form.reasonCode, note });
				toast.success("Đã huỷ sự kiện");
			}
			onSaved?.();
		} catch (err) {
			console.error("Lỗi thao tác lịch sự kiện:", err);
			setFormError(err?.message || "Không thể thực hiện thao tác với lịch sự kiện.");
			// 409 (đã bị người khác đổi trạng thái...) -> danh sách cần tải lại
			if (err?.status === 409) onSaved?.({ keepOpen: true });
		} finally {
			setSubmitting(false);
		}
	};

	if (!isOpen) return null;

	const nowLocal = toLocalInput(new Date().toISOString());
	const titleText = mode === "create" ? "Tạo sự kiện" : mode === "edit" ? "Sửa sự kiện" : "Huỷ sự kiện";
	const icon = mode === "create" ? CalendarPlus : mode === "edit" ? Pencil : XCircle;
	const reasonLabel = mode === "create" ? "Loại sự kiện" : mode === "edit" ? "Lý do sửa" : "Lý do huỷ";
	const areaOptions = [
		{ value: "", label: "-- Chọn khu vực Bảo mật --", disabled: true },
		...areas.map((a) => ({ value: a.id, label: a.name })),
	];
	// Sửa: khu của lịch có thể không còn trong danh sách khu Bảo mật đang hoạt động -> vẫn hiện tên
	if (mode === "edit" && schedule && !areas.some((a) => a.id === schedule.areaId)) {
		areaOptions.push({ value: schedule.areaId, label: schedule.areaName || "Khu vực" });
	}

	return (
		<Modal
			isOpen
			onClose={submitting ? () => {} : onClose}
			title={titleText}
			subtitle={
				mode !== "create" && schedule
					? `${schedule.title} · ${schedule.areaName} · ${formatDisplayDateTime(schedule.startAt)} → ${formatDisplayDateTime(schedule.endAt)}`
					: undefined
			}
			icon={icon}
			iconVariant={mode === "cancel" ? "danger" : "brand"}
			size="md"
			initialFocus="field"
			className="evm-form-modal"
			footer={
				<>
					<Button variant="secondary" onClick={onClose} disabled={submitting}>
						Đóng
					</Button>
					<Button
						variant={mode === "cancel" ? "danger" : "primary"}
						onClick={handleSubmit}
						loading={submitting}
						disabled={submitting || loadingReasons}
					>
						{mode === "create" ? "Tạo sự kiện" : mode === "edit" ? "Lưu thay đổi" : "Xác nhận huỷ"}
					</Button>
				</>
			}
		>
			<div className="evm-form" onKeyDown={(e) => e.key === "Enter" && e.target.tagName !== "TEXTAREA" && e.preventDefault()}>
				{formError && (
					<div className="evm-alert evm-alert--error" role="alert">
						<AlertCircle size={16} />
						<span>{formError}</span>
					</div>
				)}

				{mode !== "cancel" && (
					<>
						<Input
							id="evm-title"
							label="Tên sự kiện"
							value={form.title}
							onChange={(e) => setField("title", e.target.value)}
							placeholder="VD: Hội thảo khoa học sinh viên 2026"
							maxLength={TITLE_MAX}
							error={errors.title}
							hint={`${form.title.trim().length}/${TITLE_MAX} ký tự`}
							required
						/>
						<div className="evm-form__row">
							<Input
								id="evm-start"
								label="Bắt đầu"
								type="datetime-local"
								value={form.startAt}
								min={nowLocal}
								onChange={(e) => setField("startAt", e.target.value)}
								error={errors.startAt}
								required
							/>
							<Input
								id="evm-end"
								label="Kết thúc"
								type="datetime-local"
								value={form.endAt}
								min={form.startAt || nowLocal}
								onChange={(e) => setField("endAt", e.target.value)}
								error={errors.endAt}
								required
							/>
						</div>
						<Select
							id="evm-area"
							label="Khu vực"
							value={form.areaId}
							onChange={(e) => setField("areaId", e.target.value)}
							options={areaOptions}
							error={errors.areaId}
							hint={mode === "edit" ? "Sự kiện gắn với một khu, không đổi khu khi sửa" : "Chỉ khu Bảo mật đang hoạt động"}
							disabled={mode === "edit"}
							required
						/>
					</>
				)}

				{mode === "create" && (
					<ReasonTextarea
						ref={noteRef}
						id="evm-note"
						label="Ghi chú"
						required={false}
						placeholder={`Không bắt buộc — nếu nhập: ${reasonLimits.range} ký tự`}
						value={form.note}
						onChange={(e) => setField("note", e.target.value)}
						error={errors.note}
						rows={2}
					/>
				)}

				{loadingReasons ? (
					<div className="evm-muted">Đang tải danh mục...</div>
				) : (
					<Select
						id="evm-reason"
						label={reasonLabel}
						value={form.reasonCode}
						onChange={(e) => setField("reasonCode", e.target.value)}
						options={[
							...(reasons.length === 0 ? [{ value: "", label: "Chưa có mục khả dụng" }] : []),
							...reasons.map((r) => ({ value: r.code, label: r.label })),
						]}
						error={errors.reasonCode}
						hint={mode === "edit" && schedule?.reasonLabel ? `Loại sự kiện giữ nguyên: ${schedule.reasonLabel}` : undefined}
						required
					/>
				)}

				{mode !== "create" && (
					<ReasonTextarea
						ref={noteRef}
						id="evm-note"
						label={mode === "edit" ? "Lý do sửa lịch" : "Ghi chú lý do huỷ"}
						placeholder={`Nêu rõ lý do (${reasonLimits.range} ký tự)...`}
						value={form.note}
						onChange={(e) => setField("note", e.target.value)}
						error={errors.note}
						rows={2}
					/>
				)}
			</div>
		</Modal>
	);
}
