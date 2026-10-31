import React, { useState, useEffect, useCallback } from "react";
import { Edit2, CheckCircle2, AlertCircle, ShieldAlert, Send, MapPin } from "lucide-react";
import {
	getSeverityRules,
	updateSeverityRule,
	getDispatchPolicies,
	updateDispatchPolicy,
	getDutyPostTypes,
	updateDutyPostType,
} from "../../services/incidentPolicyService";
import {
	Button,
	Input,
	Select,
	Modal,
	Badge,
	PageHeader,
	LoadingState,
	EmptyState,
	ErrorState,
} from "../../components/ui";
import { getLevelConfig } from "../../utils/areaHelpers";
import "../../styles/SystemConfigPage.css";

// Nhãn loại sự cố, cùng cách gọi với thông báo leo thang (IncidentEscalationJob.eventTypeLabel) và app bảo vệ
const EVENT_TYPE_LABELS = {
	UNKNOWN_PERSON: "Người lạ chưa xác minh",
	INTRUSION: "Xâm nhập ngoài giờ",
	UNAUTHORIZED_ACCESS: "Không đủ thẩm quyền",
	ACCESS_OUT_OF_TIME: "Quá thời hạn thẩm quyền",
};

const TIME_WINDOW_LABELS = {
	IN_HOURS: "Trong giờ",
	AFTER_HOURS: "Ngoài giờ",
	ANY: "Mọi lúc",
};

const SEVERITY_META = {
	MEDIUM: { label: "Trung bình", variant: "neutral" },
	HIGH: { label: "Cao", variant: "warning" },
	CRITICAL: { label: "Nghiêm trọng", variant: "danger" },
};
const SEVERITY_OPTIONS = Object.entries(SEVERITY_META).map(([value, m]) => ({ value, label: m.label }));

const YES_NO_OPTIONS = [
	{ value: "true", label: "Có" },
	{ value: "false", label: "Không" },
];

const TH_STYLE = { padding: "12px 16px" };
const TD_STYLE = { padding: "12px 16px" };

function SeverityBadge({ severity }) {
	const meta = SEVERITY_META[severity];
	return meta ? <Badge variant={meta.variant}>{meta.label}</Badge> : <Badge variant="neutral">{severity || "—"}</Badge>;
}

function formatUpdated(item) {
	if (!item.updatedAt) return "—";
	const time = new Date(item.updatedAt).toLocaleString("vi-VN");
	return item.updatedByName ? `${time} · ${item.updatedByName}` : time;
}

/** Bảng có trạng thái tải / lỗi / rỗng dùng chung cho 3 danh mục */
function PolicyTable({ columns, loading, error, onRetry, rows, emptyTitle, renderRow, minWidth }) {
	return (
		<div className="syscfg-card" style={{ padding: 0, overflowX: "auto", marginBottom: "24px" }}>
			<table style={{ width: "100%", minWidth, borderCollapse: "collapse", fontSize: "13px" }}>
				<thead>
					<tr style={{ background: "var(--theme-bg-surface-elevated)", borderBottom: "1px solid var(--theme-border)", textAlign: "left" }}>
						{columns.map((c) => (
							<th key={c.key} style={{ ...TH_STYLE, ...(c.style || {}) }}>{c.label}</th>
						))}
					</tr>
				</thead>
				<tbody>
					{loading ? (
						<tr><td colSpan={columns.length}><LoadingState size="sm" text="Đang tải..." /></td></tr>
					) : error ? (
						<tr><td colSpan={columns.length}><ErrorState size="sm" message={error} onRetry={onRetry} /></td></tr>
					) : rows.length === 0 ? (
						<tr><td colSpan={columns.length}><EmptyState size="sm" title={emptyTitle} /></td></tr>
					) : (
						rows.map(renderRow)
					)}
				</tbody>
			</table>
		</div>
	);
}

function SectionTitle({ icon: Icon, title, description }) {
	return (
		<div style={{ display: "flex", alignItems: "flex-start", gap: "10px", margin: "8px 0 12px" }}>
			<Icon size={18} style={{ color: "var(--theme-primary)", marginTop: "2px" }} />
			<div>
				<h3 style={{ margin: 0, fontSize: "15px", fontWeight: 700, color: "var(--theme-text-primary)" }}>{title}</h3>
				<p style={{ margin: "4px 0 0", fontSize: "13px", color: "var(--theme-text-secondary)" }}>{description}</p>
			</div>
		</div>
	);
}

export default function IncidentPolicyPage() {
	const [rules, setRules] = useState([]);
	const [policies, setPolicies] = useState([]);
	const [posts, setPosts] = useState([]);
	const [loading, setLoading] = useState(true);
	const [loadError, setLoadError] = useState(null);
	const [successMsg, setSuccessMsg] = useState(null);

	// Một modal sửa dùng chung: kind = "rule" | "policy" | "post"
	const [editing, setEditing] = useState(null);
	const [form, setForm] = useState({});
	const [saving, setSaving] = useState(false);
	const [submitError, setSubmitError] = useState(null);

	const fetchAll = useCallback(async () => {
		setLoading(true);
		setLoadError(null);
		try {
			const [r, p, d] = await Promise.all([getSeverityRules(), getDispatchPolicies(), getDutyPostTypes()]);
			setRules(r || []);
			setPolicies(p || []);
			setPosts(d || []);
		} catch (err) {
			console.error("Lỗi khi tải chính sách sự cố:", err);
			setLoadError(err.message || "Không thể tải chính sách sự cố.");
		} finally {
			setLoading(false);
		}
	}, []);

	useEffect(() => {
		fetchAll();
	}, [fetchAll]);

	const openEdit = (kind, item) => {
		if (kind === "rule") {
			setForm({ severity: item.severity });
		} else if (kind === "policy") {
			setForm({
				step1Size: item.step1Size,
				step1TimeoutSeconds: item.step1TimeoutSeconds,
				escalateFmMinutes: item.escalateFmMinutes,
				notifyFmImmediately: String(item.notifyFmImmediately),
			});
		} else {
			setForm({
				label: item.label,
				dispatchable: String(item.dispatchable),
				isActive: String(item.isActive),
				sortOrder: item.sortOrder ?? 0,
			});
		}
		setSubmitError(null);
		setEditing({ kind, item });
	};

	const closeEdit = () => {
		if (!saving) setEditing(null);
	};

	const handleSubmit = async (e) => {
		e.preventDefault();
		const { kind, item } = editing;
		setSaving(true);
		setSubmitError(null);
		setSuccessMsg(null);
		try {
			if (kind === "rule") {
				await updateSeverityRule(item.id, { severity: form.severity, version: item.version });
				setSuccessMsg("Đã cập nhật luật mức độ sự cố.");
			} else if (kind === "policy") {
				await updateDispatchPolicy(item.severity, {
					step1Size: Number(form.step1Size),
					step1TimeoutSeconds: Number(form.step1TimeoutSeconds),
					escalateFmMinutes: Number(form.escalateFmMinutes),
					notifyFmImmediately: form.notifyFmImmediately === "true",
					version: item.version,
				});
				setSuccessMsg(`Đã cập nhật chính sách mức ${SEVERITY_META[item.severity]?.label || item.severity}.`);
			} else {
				if (!form.label?.trim()) {
					setSubmitError("Tên vị trí trực không được để trống.");
					setSaving(false);
					return;
				}
				await updateDutyPostType(item.code, {
					label: form.label.trim(),
					dispatchable: form.dispatchable === "true",
					isActive: form.isActive === "true",
					sortOrder: Number(form.sortOrder) || 0,
					version: item.version,
				});
				setSuccessMsg(`Đã cập nhật vị trí trực [${item.code}].`);
			}
			setEditing(null);
			fetchAll();
		} catch (err) {
			// 409 (người khác vừa sửa): tải lại để lấy version mới, giữ popup và báo lỗi
			setSubmitError(err.message || "Không thể lưu thay đổi.");
			if (err.status === 409) fetchAll();
		} finally {
			setSaving(false);
		}
	};

	const ruleColumns = [
		{ key: "eventType", label: "Loại sự cố" },
		{ key: "area", label: "Loại khu" },
		{ key: "window", label: "Khung giờ" },
		{ key: "severity", label: "Mức độ", style: { width: "140px" } },
		{ key: "updated", label: "Cập nhật lần cuối" },
		{ key: "actions", label: "", style: { width: "70px" } },
	];
	const policyColumns = [
		{ key: "severity", label: "Mức độ", style: { width: "140px" } },
		{ key: "size", label: "Số bảo vệ mời đợt 1" },
		{ key: "timeout", label: "Thời gian chờ đợt 1" },
		{ key: "escalate", label: "Báo Quản lý CSVC sau" },
		{ key: "notify", label: "Báo Quản lý CSVC ngay" },
		{ key: "actions", label: "", style: { width: "70px" } },
	];
	const postColumns = [
		{ key: "code", label: "Mã" },
		{ key: "label", label: "Tên vị trí" },
		{ key: "dispatchable", label: "Được mời ở đợt 1" },
		{ key: "sortOrder", label: "Thứ tự", style: { width: "90px" } },
		{ key: "status", label: "Trạng thái", style: { width: "120px" } },
		{ key: "actions", label: "", style: { width: "70px" } },
	];

	const editButton = (kind, item, title) => (
		<Button variant="ghost" size="sm" icon={Edit2} onClick={() => openEdit(kind, item)} title={title} aria-label={title} />
	);

	const modalTitle = editing?.kind === "rule"
		? "Sửa mức độ sự cố"
		: editing?.kind === "policy"
			? `Chính sách mức ${SEVERITY_META[editing.item.severity]?.label || editing.item.severity}`
			: "Sửa vị trí trực";

	return (
		<div className="syscfg-container">
			<PageHeader
				title="Chính sách sự cố"
				description="Luật xác định mức độ sự cố, chính sách mời bảo vệ theo mức và danh mục vị trí trực. Thay đổi áp dụng ngay cho sự cố mới, không đổi sự cố đã có, và được ghi vết kiểm toán."
			/>

			{successMsg && (
				<div className="syscfg-alert syscfg-alert--success">
					<CheckCircle2 size={18} className="syscfg-alert__icon" />
					<span className="syscfg-alert__text">{successMsg}</span>
				</div>
			)}

			<SectionTitle
				icon={ShieldAlert}
				title="Luật mức độ sự cố"
				description="Mức độ gán cho sự cố mới theo loại sự cố, loại khu và khung giờ (ngoài giờ = AI_AFTER_HOUR_START đến AI_AFTER_HOUR_END). Không có luật khớp thì dùng INCIDENT_SEVERITY_DEFAULT ở Cấu hình hệ thống."
			/>
			<PolicyTable
				columns={ruleColumns}
				loading={loading}
				error={loadError}
				onRetry={fetchAll}
				rows={rules}
				emptyTitle="Chưa có luật mức độ nào"
				minWidth="760px"
				renderRow={(r) => (
					<tr key={r.id} style={{ borderBottom: "1px solid var(--theme-border)" }}>
						<td style={TD_STYLE}>
							<div style={{ fontWeight: 600, color: "var(--theme-text-primary)" }}>{EVENT_TYPE_LABELS[r.eventType] || r.eventType}</div>
							<div style={{ fontFamily: "monospace", fontSize: "12px", color: "var(--theme-text-muted)" }}>{r.eventType}</div>
						</td>
						<td style={TD_STYLE}>{getLevelConfig(r.areaLevelCode)?.name || r.areaLevelCode}</td>
						<td style={TD_STYLE}>{TIME_WINDOW_LABELS[r.timeWindow] || r.timeWindow}</td>
						<td style={TD_STYLE}><SeverityBadge severity={r.severity} /></td>
						<td style={{ ...TD_STYLE, color: "var(--theme-text-secondary)" }}>{formatUpdated(r)}</td>
						<td style={{ ...TD_STYLE, textAlign: "right" }}>{editButton("rule", r, "Sửa mức độ")}</td>
					</tr>
				)}
			/>

			<SectionTitle
				icon={Send}
				title="Chính sách phân phối theo mức"
				description="Số bảo vệ được mời trước (0 = phát cảnh báo cho tất cả ngay), thời gian chờ lời mời và thời điểm báo Quản lý CSVC. Chỉ dùng khi bật DISPATCH_ENABLED ở Cấu hình hệ thống."
			/>
			<PolicyTable
				columns={policyColumns}
				loading={loading}
				error={loadError}
				onRetry={fetchAll}
				rows={policies}
				emptyTitle="Chưa có chính sách nào"
				minWidth="720px"
				renderRow={(p) => (
					<tr key={p.severity} style={{ borderBottom: "1px solid var(--theme-border)" }}>
						<td style={TD_STYLE}><SeverityBadge severity={p.severity} /></td>
						<td style={TD_STYLE}>{p.step1Size === 0 ? "0 (phát cho tất cả ngay)" : `${p.step1Size} người`}</td>
						<td style={TD_STYLE}>{p.step1TimeoutSeconds} giây</td>
						<td style={TD_STYLE}>{p.escalateFmMinutes} phút</td>
						<td style={TD_STYLE}>
							{p.notifyFmImmediately ? <Badge variant="warning">Có</Badge> : <Badge variant="neutral">Không</Badge>}
						</td>
						<td style={{ ...TD_STYLE, textAlign: "right" }}>{editButton("policy", p, "Sửa chính sách")}</td>
					</tr>
				)}
			/>

			<SectionTitle
				icon={MapPin}
				title="Danh mục vị trí trực"
				description="Quản lý CSVC chọn vị trí trực khi xếp ca (mặc định Tuần tra). Bảo vệ ở vị trí không được mời chỉ được mời khi không còn ai khác."
			/>
			<PolicyTable
				columns={postColumns}
				loading={loading}
				error={loadError}
				onRetry={fetchAll}
				rows={posts}
				emptyTitle="Chưa có vị trí trực nào"
				minWidth="640px"
				renderRow={(d) => (
					<tr key={d.code} style={{ borderBottom: "1px solid var(--theme-border)" }}>
						<td style={{ ...TD_STYLE, fontFamily: "monospace", fontWeight: 600 }}>{d.code}</td>
						<td style={TD_STYLE}>{d.label}</td>
						<td style={TD_STYLE}>
							{d.dispatchable ? <Badge variant="success">Có</Badge> : <Badge variant="neutral">Không</Badge>}
						</td>
						<td style={TD_STYLE}>{d.sortOrder}</td>
						<td style={TD_STYLE}>
							{d.isActive ? <Badge variant="success">Đang dùng</Badge> : <Badge variant="neutral">Ngừng dùng</Badge>}
						</td>
						<td style={{ ...TD_STYLE, textAlign: "right" }}>{editButton("post", d, "Sửa vị trí trực")}</td>
					</tr>
				)}
			/>

			<Modal isOpen={Boolean(editing)} initialFocus="field" onClose={closeEdit} title={modalTitle} icon={Edit2}>
				{editing && (
					<form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: "14px" }}>
						{submitError && (
							<div className="syscfg-alert syscfg-alert--error" role="alert">
								<AlertCircle size={18} className="syscfg-alert__icon" />
								<span className="syscfg-alert__text">{submitError}</span>
							</div>
						)}

						{editing.kind === "rule" && (
							<>
								<p style={{ margin: 0, fontSize: "13px", color: "var(--theme-text-secondary)" }}>
									{EVENT_TYPE_LABELS[editing.item.eventType] || editing.item.eventType} ·{" "}
									{getLevelConfig(editing.item.areaLevelCode)?.name || editing.item.areaLevelCode} ·{" "}
									{TIME_WINDOW_LABELS[editing.item.timeWindow] || editing.item.timeWindow}
								</p>
								<Select
									id="ipol-rule-severity"
									label="Mức độ"
									value={form.severity}
									onChange={(e) => setForm({ ...form, severity: e.target.value })}
									options={SEVERITY_OPTIONS}
									required
								/>
							</>
						)}

						{editing.kind === "policy" && (
							<>
								<Input
									id="ipol-policy-size"
									label="Số bảo vệ mời ở đợt 1"
									type="number"
									value={form.step1Size}
									onChange={(e) => setForm({ ...form, step1Size: e.target.value })}
									hint="0 = phát cảnh báo cho tất cả bảo vệ ngay, không mời trước."
									required
								/>
								<Input
									id="ipol-policy-timeout"
									label="Thời gian chờ đợt 1 (giây)"
									type="number"
									value={form.step1TimeoutSeconds}
									onChange={(e) => setForm({ ...form, step1TimeoutSeconds: e.target.value })}
									required
								/>
								<Input
									id="ipol-policy-escalate"
									label="Báo Quản lý CSVC sau (phút, tính từ lúc tạo sự cố)"
									type="number"
									value={form.escalateFmMinutes}
									onChange={(e) => setForm({ ...form, escalateFmMinutes: e.target.value })}
									required
								/>
								<Select
									id="ipol-policy-notify"
									label="Báo Quản lý CSVC ngay khi có sự cố"
									value={form.notifyFmImmediately}
									onChange={(e) => setForm({ ...form, notifyFmImmediately: e.target.value })}
									options={YES_NO_OPTIONS}
								/>
							</>
						)}

						{editing.kind === "post" && (
							<>
								<Input id="ipol-post-code" label="Mã vị trí" value={editing.item.code} disabled />
								<Input
									id="ipol-post-label"
									label="Tên vị trí"
									value={form.label}
									onChange={(e) => setForm({ ...form, label: e.target.value })}
									required
								/>
								<Select
									id="ipol-post-dispatchable"
									label="Được mời ở đợt 1"
									value={form.dispatchable}
									onChange={(e) => setForm({ ...form, dispatchable: e.target.value })}
									options={YES_NO_OPTIONS}
								/>
								<Select
									id="ipol-post-active"
									label="Trạng thái"
									value={form.isActive}
									onChange={(e) => setForm({ ...form, isActive: e.target.value })}
									options={[
										{ value: "true", label: "Đang dùng" },
										{ value: "false", label: "Ngừng dùng" },
									]}
									disabled={editing.item.code === "PATROL"}
									hint={editing.item.code === "PATROL" ? "Vị trí mặc định của ca, không thể ngừng dùng." : undefined}
								/>
								<Input
									id="ipol-post-sort-order"
									label="Thứ tự sắp xếp"
									type="number"
									value={form.sortOrder}
									onChange={(e) => setForm({ ...form, sortOrder: parseInt(e.target.value, 10) || 0 })}
								/>
							</>
						)}

						<div style={{ display: "flex", justifyContent: "flex-end", gap: "10px", marginTop: "12px" }}>
							<Button type="button" variant="secondary" onClick={closeEdit} disabled={saving}>
								Huỷ
							</Button>
							<Button type="submit" variant="primary" loading={saving}>
								Lưu thay đổi
							</Button>
						</div>
					</form>
				)}
			</Modal>
		</div>
	);
}
