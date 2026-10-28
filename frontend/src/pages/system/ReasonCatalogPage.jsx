import React, { useState, useEffect, useCallback } from "react";
import {
	ListFilter,
	Plus,
	Edit2,
	PowerOff,
	Power,
	CheckCircle2,
	AlertCircle,
	Tag,
} from "lucide-react";
import {
	getReasonCatalogs,
	createReasonCatalog,
	updateReasonCatalog,
	deactivateReasonCatalog,
	reactivateReasonCatalog,
} from "../../services/reasonCatalogService";
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
import "../../styles/SystemConfigPage.css";

const ACTION_TYPES = [
	{ value: "EVENT_ENABLE", label: "Bật chế độ sự kiện" },
	{ value: "EVENT_DISABLE", label: "Tắt chế độ sự kiện" },
	{ value: "EVENT_EXTEND", label: "Điều chỉnh giờ kết thúc" },
	// Step 5b (BR-ES-L1): nhóm lý do riêng cho lịch sự kiện
	{ value: "EVENT_SCHEDULE_CREATE", label: "Đặt lịch sự kiện" },
	{ value: "EVENT_SCHEDULE_UPDATE", label: "Sửa lịch sự kiện" },
	{ value: "EVENT_SCHEDULE_CANCEL", label: "Huỷ lịch sự kiện" },
];

// X-01a: không còn bật / điều chỉnh sự kiện thủ công -> ẩn hai nhóm này khỏi bộ lọc và form thêm mới.
// Dữ liệu cũ giữ nguyên, ACTION_TYPES vẫn đủ để hiện nhãn các dòng đã có.
const RETIRED_ACTION_TYPES = new Set(["EVENT_ENABLE", "EVENT_EXTEND"]);
const SELECTABLE_ACTION_TYPES = ACTION_TYPES.filter((a) => !RETIRED_ACTION_TYPES.has(a.value));

// Bộ lọc: dòng đầu value rỗng = không lọc (không dùng prop placeholder vì option đó bị disabled)
const ACTION_FILTER_OPTIONS = [{ value: "", label: "Tất cả loại thao tác" }, ...SELECTABLE_ACTION_TYPES];
const ACTIVE_FILTER_OPTIONS = [
	{ value: "", label: "Tất cả trạng thái" },
	{ value: "true", label: "Đang dùng" },
	{ value: "false", label: "Ngừng dùng" },
];

export default function ReasonCatalogPage() {
	const [reasons, setReasons] = useState([]);
	const [loading, setLoading] = useState(true);
	const [filterAction, setFilterAction] = useState("");
	const [filterActive, setFilterActive] = useState("");
	const [errorMsg, setErrorMsg] = useState(null);
	const [loadError, setLoadError] = useState(null);
	const [successMsg, setSuccessMsg] = useState(null);

	// Create Modal
	const [createModalOpen, setCreateModalOpen] = useState(false);
	const [createForm, setCreateForm] = useState({
		actionType: SELECTABLE_ACTION_TYPES[0].value,
		code: "",
		label: "",
		sortOrder: 0,
	});
	const [creating, setCreating] = useState(false);
	// B-01: lỗi gửi form hiện trong popup (banner của trang nằm sau lớp phủ). Trùng mã -> ngay dưới ô Mã lý do.
	const [createSubmitError, setCreateSubmitError] = useState(null);
	const [createCodeServerError, setCreateCodeServerError] = useState(null);
	// UI-AUDIT #19: ô bắt buộc báo lỗi ngay khi rời ô (onBlur) còn để trống
	const [createTouched, setCreateTouched] = useState({ code: false, label: false });
	const [editSubmitError, setEditSubmitError] = useState(null);
	const [editLabelTouched, setEditLabelTouched] = useState(false);

	// Edit Modal
	const [editModalOpen, setEditModalOpen] = useState(false);
	const [editForm, setEditForm] = useState({
		id: null,
		actionType: "",
		code: "",
		label: "",
		sortOrder: 0,
	});
	const [updating, setUpdating] = useState(false);
	// Xác nhận trước khi ngừng dùng một lý do
	const [deactivateTarget, setDeactivateTarget] = useState(null);
	const [deactivating, setDeactivating] = useState(false);

	const fetchReasons = useCallback(async () => {
		setLoading(true);
		setErrorMsg(null);
		setLoadError(null);
		try {
			const data = await getReasonCatalogs({
				actionType: filterAction || undefined,
				active: filterActive !== "" ? filterActive === "true" : undefined,
			});
			setReasons(data || []);
		} catch (err) {
			console.error("Lỗi khi tải danh mục lý do:", err);
			setLoadError(err.message || "Không thể tải danh mục lý do.");
		} finally {
			setLoading(false);
		}
	}, [filterAction, filterActive]);

	useEffect(() => {
		fetchReasons();
	}, [fetchReasons]);

	const handleOpenCreate = () => {
		setCreateForm({
			actionType: filterAction || SELECTABLE_ACTION_TYPES[0].value,
			code: "",
			label: "",
			sortOrder: reasons.length + 1,
		});
		setCreateSubmitError(null);
		setCreateCodeServerError(null);
		setCreateTouched({ code: false, label: false });
		setCreateModalOpen(true);
	};

	// UI-32: cùng quy tắc backend ReasonCatalogCreateRequest (mã ^[A-Z0-9_]+$ tối đa 50, nhãn tối đa 255)
	const createCode = (createForm.code || "").trim();
	const createLabel = (createForm.label || "").trim();
	const createCodeError = !createCode
		? null
		: !/^[A-Z0-9_]+$/.test(createCode)
			? "Mã lý do chỉ chứa chữ hoa, số và dấu gạch dưới"
			: createCode.length > 50
				? "Mã lý do tối đa 50 ký tự"
				: null;
	const createLabelError = createLabel.length > 255 ? "Nhãn lý do tối đa 255 ký tự" : null;
	const createCodeRequiredError = createTouched.code && !createCode ? "Vui lòng nhập mã lý do" : null;
	const createLabelRequiredError = createTouched.label && !createLabel ? "Vui lòng nhập nhãn hiển thị" : null;

	const handleCreateSubmit = async (e) => {
		e.preventDefault();
		if (!createForm.code?.trim() || !createForm.label?.trim()) {
			setCreateTouched({ code: true, label: true });
			setCreateSubmitError("Vui lòng điền đầy đủ mã và nhãn lý do.");
			return;
		}
		if (createCodeError || createLabelError) return;
		setCreating(true);
		setErrorMsg(null);
		setSuccessMsg(null);
		setCreateSubmitError(null);
		setCreateCodeServerError(null);
		try {
			await createReasonCatalog({
				actionType: createForm.actionType,
				code: createForm.code.trim().toUpperCase(),
				label: createForm.label.trim(),
				sortOrder: Number(createForm.sortOrder) || 0,
			});
			setSuccessMsg("Tạo mới lý do thành công.");
			setCreateModalOpen(false);
			fetchReasons();
		} catch (err) {
			const msg = err.message || "Không thể tạo lý do mới.";
			// BE (ReasonCatalogService) báo trùng mã bằng câu "Mã lý do '…' đã tồn tại cho loại thao tác …"
			if (/đã tồn tại/i.test(msg)) {
				setCreateCodeServerError(msg);
			} else {
				setCreateSubmitError(msg);
			}
		} finally {
			setCreating(false);
		}
	};

	const handleOpenEdit = (item) => {
		setEditForm({
			id: item.id,
			actionType: item.actionType,
			code: item.code,
			label: item.label,
			sortOrder: item.sortOrder ?? 0,
		});
		setEditSubmitError(null);
		setEditLabelTouched(false);
		setEditModalOpen(true);
	};

	const editLabelRequiredError = editLabelTouched && !(editForm.label || "").trim() ? "Vui lòng nhập nhãn hiển thị" : null;

	const handleUpdateSubmit = async (e) => {
		e.preventDefault();
		if (!editForm.label?.trim()) {
			setEditLabelTouched(true);
			setEditSubmitError("Nhãn lý do không được để trống.");
			return;
		}
		setUpdating(true);
		setErrorMsg(null);
		setSuccessMsg(null);
		setEditSubmitError(null);
		try {
			await updateReasonCatalog(editForm.id, {
				label: editForm.label.trim(),
				sortOrder: Number(editForm.sortOrder) || 0,
			});
			setSuccessMsg("Cập nhật lý do thành công.");
			setEditModalOpen(false);
			fetchReasons();
		} catch (err) {
			setEditSubmitError(err.message || "Không thể cập nhật lý do.");
		} finally {
			setUpdating(false);
		}
	};

	const handleToggleStatus = async (item) => {
		if (item.isOther && item.isActive) {
			setErrorMsg("Mục 'Khác' bắt buộc phải duy trì hoạt động.");
			return;
		}
		setErrorMsg(null);
		if (item.isActive) setDeactivating(true);
		try {
			if (item.isActive) {
				await deactivateReasonCatalog(item.id);
				setSuccessMsg(`Đã ngừng dùng lý do [${item.code}].`);
			} else {
				await reactivateReasonCatalog(item.id);
				setSuccessMsg(`Đã kích hoạt lại lý do [${item.code}].`);
			}
			fetchReasons();
		} catch (err) {
			setErrorMsg(err.message || "Lỗi khi thay đổi trạng thái lý do.");
		} finally {
			setDeactivating(false);
			setDeactivateTarget(null);
		}
	};

	const getActionLabel = (type) => {
		const found = ACTION_TYPES.find((a) => a.value === type);
		return found ? found.label : type;
	};

	return (
		<div className="syscfg-container">
			{/* Header */}
			<PageHeader
				title="Danh mục lý do"
				description="Quản lý các lý do chuẩn hóa cho thao tác bật, tắt và điều chỉnh giờ kết thúc sự kiện khu vực. Mọi thay đổi đều được ghi vết kiểm toán."
				actions={
					<Button
						variant="primary"
						icon={Plus}
						onClick={handleOpenCreate}
					>
						Thêm lý do
					</Button>
				}
			/>

			{/* Alerts */}
			{successMsg && (
				<div className="syscfg-alert syscfg-alert--success">
					<CheckCircle2 size={18} className="syscfg-alert__icon" />
					<span className="syscfg-alert__text">{successMsg}</span>
				</div>
			)}
			{errorMsg && (
				<div className="syscfg-alert syscfg-alert--danger">
					<AlertCircle size={18} className="syscfg-alert__icon" />
					<span className="syscfg-alert__text">{errorMsg}</span>
				</div>
			)}

			{/* Filters */}
			<div style={{ display: "flex", gap: "12px", marginBottom: "16px", flexWrap: "wrap", alignItems: "center" }}>
				<div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
					<ListFilter size={16} />
					<span style={{ fontSize: "13px", fontWeight: 600 }}>Lọc theo:</span>
				</div>
				<Select
					className="ui-select-group--filter"
					value={filterAction}
					onChange={(e) => setFilterAction(e.target.value)}
					aria-label="Lọc theo loại thao tác"
					options={ACTION_FILTER_OPTIONS}
				/>
				<Select
					className="ui-select-group--filter"
					value={filterActive}
					onChange={(e) => setFilterActive(e.target.value)}
					aria-label="Lọc theo trạng thái"
					options={ACTIVE_FILTER_OPTIONS}
				/>
			</div>

			{/* Table */}
			<div className="syscfg-card" style={{ padding: 0, overflowX: "auto" }}>
				<table className="reason-table" style={{ width: "100%", minWidth: "720px", borderCollapse: "collapse", fontSize: "13px" }}>
					<thead>
						<tr style={{ background: "var(--theme-bg-surface-elevated)", borderBottom: "1px solid var(--theme-border)", textAlign: "left" }}>
							<th style={{ padding: "12px 16px" }}>Loại thao tác</th>
							<th style={{ padding: "12px 16px" }}>Mã lý do</th>
							<th style={{ padding: "12px 16px" }}>Nhãn hiển thị</th>
							<th style={{ padding: "12px 16px", width: "90px" }}>Thứ tự</th>
							<th style={{ padding: "12px 16px", width: "110px" }}>Phân loại</th>
							<th style={{ padding: "12px 16px", width: "120px" }}>Trạng thái</th>
							<th style={{ padding: "12px 16px", textAlign: "right", width: "150px" }}>Thao tác</th>
						</tr>
					</thead>
					<tbody>
						{loading ? (
							<tr>
								<td colSpan={7}>
									<LoadingState size="sm" text="Đang tải danh mục lý do..." />
								</td>
							</tr>
						) : loadError ? (
							<tr>
								<td colSpan={7}>
									<ErrorState size="sm" message={loadError} onRetry={fetchReasons} />
								</td>
							</tr>
						) : reasons.length === 0 ? (
							<tr>
								<td colSpan={7}>
									<EmptyState
										size="sm"
										title="Không tìm thấy lý do nào phù hợp bộ lọc"
										description="Đổi bộ lọc hoặc bấm “Thêm lý do” để tạo lý do mới."
									/>
								</td>
							</tr>
						) : (
							reasons.map((item) => (
								<tr key={item.id} style={{ borderBottom: "1px solid var(--theme-border)" }}>
									<td style={{ padding: "12px 16px" }}>
										<span style={{ fontWeight: 600, color: "var(--theme-text-primary)" }}>
											{getActionLabel(item.actionType)}
										</span>
									</td>
									<td style={{ padding: "12px 16px", fontFamily: "monospace", fontWeight: 600 }}>
										{item.code}
									</td>
									<td style={{ padding: "12px 16px" }}>{item.label}</td>
									<td style={{ padding: "12px 16px" }}>{item.sortOrder}</td>
									<td style={{ padding: "12px 16px" }}>
										{item.isOther ? (
											<Badge variant="warning">Mục khác</Badge>
										) : (
											<Badge variant="neutral">Chuẩn</Badge>
										)}
									</td>
									<td style={{ padding: "12px 16px" }}>
										{item.isActive ? (
											<Badge variant="success">Đang dùng</Badge>
										) : (
											<Badge variant="neutral">Ngừng dùng</Badge>
										)}
									</td>
									<td style={{ padding: "12px 16px", textAlign: "right" }}>
										<div style={{ display: "flex", justifyContent: "flex-end", gap: "6px" }}>
											<Button
												variant="ghost"
												size="sm"
												icon={Edit2}
												onClick={() => handleOpenEdit(item)}
												title="Sửa nhãn và thứ tự"
												aria-label="Sửa nhãn và thứ tự"
											/>
											{item.isActive ? (
												<Button
													variant="ghost"
													size="sm"
													icon={PowerOff}
													disabled={item.isOther}
													onClick={() => setDeactivateTarget(item)}
													title={item.isOther ? "Không thể ngừng dùng mục Khác" : "Ngừng dùng"}
													aria-label={item.isOther ? "Không thể ngừng dùng mục Khác" : "Ngừng dùng"}
													style={{ color: item.isOther ? "var(--theme-text-disabled)" : "var(--theme-danger)" }}
												/>
											) : (
												<Button
													variant="ghost"
													size="sm"
													icon={Power}
													onClick={() => handleToggleStatus(item)}
													title="Kích hoạt lại"
													aria-label="Kích hoạt lại"
													style={{ color: "var(--theme-success)" }}
												/>
											)}
										</div>
									</td>
								</tr>
							))
						)}
					</tbody>
				</table>
			</div>

			{/* Create Modal */}
			<Modal
				isOpen={createModalOpen}
				initialFocus="field"
				onClose={() => setCreateModalOpen(false)}
				title="Thêm lý do sự kiện mới"
				icon={Tag}
			>
				<form onSubmit={handleCreateSubmit} style={{ display: "flex", flexDirection: "column", gap: "14px" }}>
					{createSubmitError && (
						<div className="syscfg-alert syscfg-alert--danger" role="alert">
							<AlertCircle size={18} className="syscfg-alert__icon" />
							<span className="syscfg-alert__text">{createSubmitError}</span>
						</div>
					)}
					<Select
						id="reason-create-action-type"
						label="Loại thao tác"
						value={createForm.actionType}
						onChange={(e) => setCreateForm({ ...createForm, actionType: e.target.value })}
						options={SELECTABLE_ACTION_TYPES}
						required
					/>

					{/* UI-AUDIT #19: label của components/ui/Input gắn htmlFor với ô nhập */}
					<Input
						id="reason-create-code"
						label="Mã lý do"
						value={createForm.code}
						onChange={(e) => {
							setCreateForm({ ...createForm, code: e.target.value.toUpperCase() });
							setCreateCodeServerError(null);
						}}
						onBlur={() => setCreateTouched((t) => ({ ...t, code: true }))}
						placeholder="VD: TECH_TALK"
						error={createCodeRequiredError || createCodeError || createCodeServerError}
						hint="Chữ hoa, số, gạch dưới. Không thể sửa sau khi tạo."
						required
					/>

					<Input
						id="reason-create-label"
						label="Nhãn hiển thị"
						value={createForm.label}
						onChange={(e) => setCreateForm({ ...createForm, label: e.target.value })}
						onBlur={() => setCreateTouched((t) => ({ ...t, label: true }))}
						placeholder="VD: Buổi chia sẻ kỹ thuật"
						error={createLabelRequiredError || createLabelError}
						required
					/>

					<Input
						id="reason-create-sort-order"
						label="Thứ tự sắp xếp"
						type="number"
						value={createForm.sortOrder}
						onChange={(e) => setCreateForm({ ...createForm, sortOrder: parseInt(e.target.value, 10) || 0 })}
					/>

					<div style={{ display: "flex", justifyContent: "flex-end", gap: "10px", marginTop: "12px" }}>
						<Button type="button" variant="secondary" onClick={() => setCreateModalOpen(false)}>
							Huỷ
						</Button>
						<Button
							type="submit"
							variant="primary"
							loading={creating}
							disabled={Boolean(createCodeError || createLabelError)}
						>
							Tạo mới
						</Button>
					</div>
				</form>
			</Modal>

			{/* Edit Modal */}
			<Modal
				isOpen={editModalOpen}
				initialFocus="field"
				onClose={() => setEditModalOpen(false)}
				title="Sửa lý do sự kiện"
				icon={Edit2}
			>
				<form onSubmit={handleUpdateSubmit} style={{ display: "flex", flexDirection: "column", gap: "14px" }}>
					{editSubmitError && (
						<div className="syscfg-alert syscfg-alert--danger" role="alert">
							<AlertCircle size={18} className="syscfg-alert__icon" />
							<span className="syscfg-alert__text">{editSubmitError}</span>
						</div>
					)}
					{/* UI-AUDIT #19: dùng label của components/ui/Input (htmlFor gắn với ô nhập) như modal tạo mới */}
					<Input id="reason-edit-code" label="Mã lý do" value={editForm.code} disabled />

					<Input
						id="reason-edit-label"
						label="Nhãn hiển thị"
						value={editForm.label}
						onChange={(e) => setEditForm({ ...editForm, label: e.target.value })}
						onBlur={() => setEditLabelTouched(true)}
						error={editLabelRequiredError}
						required
					/>

					<Input
						id="reason-edit-sort-order"
						label="Thứ tự sắp xếp"
						type="number"
						value={editForm.sortOrder}
						onChange={(e) => setEditForm({ ...editForm, sortOrder: parseInt(e.target.value, 10) || 0 })}
					/>

					<div style={{ display: "flex", justifyContent: "flex-end", gap: "10px", marginTop: "12px" }}>
						<Button type="button" variant="secondary" onClick={() => setEditModalOpen(false)}>
							Huỷ
						</Button>
						<Button type="submit" variant="primary" loading={updating}>
							Lưu thay đổi
						</Button>
					</div>
				</form>
			</Modal>

			<Modal
				isOpen={Boolean(deactivateTarget)}
				onClose={() => !deactivating && setDeactivateTarget(null)}
				title="Ngừng dùng lý do này?"
				subtitle="Lý do sẽ không còn hiện trong danh sách chọn. Các bản ghi cũ đã dùng lý do này vẫn giữ nguyên."
				icon={PowerOff}
				iconVariant="danger"
				size="sm"
				footer={
					<>
						<Button
							variant="secondary"
							onClick={() => setDeactivateTarget(null)}
							disabled={deactivating}
						>
							Huỷ
						</Button>
						<Button
							variant="danger"
							onClick={() => handleToggleStatus(deactivateTarget)}
							loading={deactivating}
						>
							Ngừng dùng
						</Button>
					</>
				}
			>
				{deactivateTarget && (
					<p style={{ margin: 0 }}>
						<strong>{deactivateTarget.code}</strong> — {deactivateTarget.label}
					</p>
				)}
			</Modal>
		</div>
	);
}
