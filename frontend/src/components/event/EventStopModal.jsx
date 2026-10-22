import React, { useEffect, useState } from "react";
import { AlertCircle, OctagonX } from "lucide-react";
import { toast } from "sonner";
import { Button, Modal, ReasonTextarea, Select } from "../ui";
import { getAreaById, updateAreaEventMode } from "../../services/areaService";
import { getActiveReasons } from "../../services/reasonCatalogService";
import { useReasonLimits } from "../../hooks/useReasonLimits";

/**
 * Nút "Dừng" của lịch đang diễn ra (Q7): PATCH /api/areas/{id}/event-mode action DISABLE.
 * Lý do bắt buộc (nhóm EVENT_DISABLE), ghi chú không bắt buộc. Backend đóng phiên, lịch thành ENDED_EARLY, khu hết mở.
 * version của khu đọc lại ngay khi mở popup (BR-TC-13, chống ghi đè).
 */
export default function EventStopModal({ schedule, onClose, onStopped }) {
	const reasonLimits = useReasonLimits();
	const [reasons, setReasons] = useState([]);
	const [reasonCode, setReasonCode] = useState("");
	const [note, setNote] = useState("");
	const [noteError, setNoteError] = useState(null);
	const [reasonError, setReasonError] = useState(null);
	const [version, setVersion] = useState(null);
	const [loading, setLoading] = useState(false);
	const [error, setError] = useState(null);
	const [submitting, setSubmitting] = useState(false);

	useEffect(() => {
		if (!schedule) return;
		let isMounted = true;
		setNote("");
		setNoteError(null);
		setReasonError(null);
		setError(null);
		setLoading(true);
		Promise.all([getActiveReasons("EVENT_DISABLE"), getAreaById(schedule.areaId)])
			.then(([list, area]) => {
				if (!isMounted) return;
				const items = Array.isArray(list) ? list : [];
				setReasons(items);
				setReasonCode(items[0]?.code || "");
				setVersion(area?.version ?? null);
			})
			.catch((err) => {
				console.error("Lỗi khi chuẩn bị dừng sự kiện:", err);
				if (isMounted) setError(err?.message || "Không thể tải dữ liệu để dừng sự kiện.");
			})
			.finally(() => {
				if (isMounted) setLoading(false);
			});
		return () => {
			isMounted = false;
		};
	}, [schedule]);

	if (!schedule) return null;

	const handleStop = async () => {
		let invalid = false;
		if (!reasonCode) {
			setReasonError("Vui lòng chọn lý do dừng.");
			invalid = true;
		}
		const noteMsg = note.trim() ? reasonLimits.error(note, "Ghi chú") : null;
		if (noteMsg) {
			setNoteError(noteMsg);
			invalid = true;
		}
		if (invalid) return;
		setSubmitting(true);
		setError(null);
		try {
			await updateAreaEventMode(schedule.areaId, {
				action: "DISABLE",
				reasonCode,
				note: note.trim() || null,
				version,
			});
			toast.success(`Đã dừng sự kiện "${schedule.title}"`);
			onStopped?.();
		} catch (err) {
			console.error("Lỗi khi dừng sự kiện:", err);
			setError(err?.message || "Không thể dừng sự kiện.");
			if (err?.status === 409) onStopped?.({ keepOpen: true });
		} finally {
			setSubmitting(false);
		}
	};

	return (
		<Modal
			isOpen
			onClose={submitting ? () => {} : onClose}
			title="Dừng sự kiện"
			icon={OctagonX}
			iconVariant="danger"
			size="md"
			className="evm-form-modal"
			footer={
				<>
					<Button variant="secondary" onClick={onClose} disabled={submitting}>
						Đóng
					</Button>
					<Button variant="danger" onClick={handleStop} loading={submitting} disabled={submitting || loading}>
						Dừng sự kiện
					</Button>
				</>
			}
		>
			<div className="evm-form">
				<p className="evm-confirm-text">
					Dừng sự kiện <strong>{schedule.title}</strong> tại khu <strong>{schedule.areaName}</strong>? Người dùng cấp 1
					sẽ không vào được khu này nữa.
				</p>
				{error && (
					<div className="evm-alert evm-alert--error" role="alert">
						<AlertCircle size={16} />
						<span>{error}</span>
					</div>
				)}
				{loading ? (
					<div className="evm-muted">Đang tải danh mục lý do...</div>
				) : (
					<Select
						id="evm-stop-reason"
						label="Lý do dừng"
						value={reasonCode}
						onChange={(e) => {
							setReasonCode(e.target.value);
							setReasonError(null);
						}}
						options={[
							...(reasons.length === 0 ? [{ value: "", label: "Chưa có lý do khả dụng" }] : []),
							...reasons.map((r) => ({ value: r.code, label: r.label })),
						]}
						error={reasonError}
						required
					/>
				)}
				<ReasonTextarea
					id="evm-stop-note"
					label="Ghi chú"
					required={false}
					placeholder={`Không bắt buộc — nếu nhập: ${reasonLimits.range} ký tự`}
					value={note}
					onChange={(e) => {
						setNote(e.target.value);
						setNoteError(null);
					}}
					error={noteError}
					rows={2}
				/>
			</div>
		</Modal>
	);
}
