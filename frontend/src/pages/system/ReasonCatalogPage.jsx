import React, { useState, useEffect, useCallback } from "react";
import {
	ListFilter,
	Plus,
	Edit2,
	PowerOff,
	Power,
	ShieldAlert,
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
import { Button, Input, Modal, Badge } from "../../components/ui";
import "../../styles/SystemConfigPage.css";

const ACTION_TYPES = [
	{ value: "EVENT_ENABLE", label: "Bật chế độ sự kiện" },
	{ value: "EVENT_DISABLE", label: "Tắt chế độ sự kiện" },
	{ value: "EVENT_EXTEND", label: "Điều chỉnh giờ kết thúc" },
];

export default function ReasonCatalogPage() {
	const [reasons, setReasons] = useState([]);
	const [loading, setLoading] = useState(true);
	const [filterAction, setFilterAction] = useState("");
	const [filterActive, setFilterActive] = useState("");
	const [errorMsg, setErrorMsg] = useState(null);
	const [successMsg, setSuccessMsg] = useState(null);

	// Create Modal
	const [createModalOpen, setCreateModalOpen] = useState(false);
	const [createForm, setCreateForm] = useState({
		actionType: "EVENT_ENABLE",
		code: "",
		label: "",
		sortOrder: 0,
	});
	const [creating, setCreating] = useState(false);

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

	const fetchReasons = useCallback(async () => {
		setLoading(true);
		setErrorMsg(null);
		try {
			const data = await getReasonCatalogs({
				actionType: filterAction || undefined,
				isActive: filterActive !== "" ? filterActive === "true" : undefined,
			});
			setReasons(data || []);
		} catch (err) {
			console.error("Lỗi khi tải danh mục lý do:", err);
			setErrorMsg(err.message || "Không thể tải danh mục lý do.");
		} finally {
			setLoading(false);
		}
	}, [filterAction, filterActive]);

	useEffect(() => {
		fetchReasons();
	}, [fetchReasons]);

	const handleOpenCreate = () => {
		setCreateForm({
			actionType: filterAction || "EVENT_ENABLE",
			code: "",
			label: "",
			sortOrder: reasons.length + 1,
		});
		setCreateModalOpen(true);
	};

	const handleCreateSubmit = async (e) => {
		e.preventDefault();
		if (!createForm.code?.trim() || !createForm.label?.trim()) {
			setErrorMsg("Vui lòng điền đầy đủ mã và nhãn lý do.");
			return;
		}
		setCreating(true);
		setErrorMsg(null);
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
			setErrorMsg(err.message || "Không thể tạo lý do mới.");
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
		setEditModalOpen(true);
	};

	const handleUpdateSubmit = async (e) => {
		e.preventDefault();
		if (!editForm.label?.trim()) {
			setErrorMsg("Nhãn lý do không được để trống.");
			return;
		}
		setUpdating(true);
		setErrorMsg(null);
		try {
			await updateReasonCatalog(editForm.id, {
				label: editForm.label.trim(),
				sortOrder: Number(editForm.sortOrder) || 0,
			});
			setSuccessMsg("Cập nhật lý do thành công.");
			setEditModalOpen(false);
			fetchReasons();
		} catch (err) {
			setErrorMsg(err.message || "Không thể cập nhật lý do.");
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
		}
	};

	const getActionLabel = (type) => {
		const found = ACTION_TYPES.find((a) => a.value === type);
		return found ? found.label : type;
	};

	return (
		<div className="syscfg-container">
			{/* Header */}
			<header className="syscfg-header">
				<div className="syscfg-header__title-group">
					<div className="syscfg-header__icon-box">
						<Tag size={22} />
					</div>
					<div>
						<h1 className="syscfg-header__title">Danh mục lý do chế độ sự kiện</h1>
						<p className="syscfg-header__subtitle">
							Quản lý các lý do chuẩn hóa cho thao tác Bật, Tắt và Điều chỉnh giờ kết thúc sự kiện khu vực.
							Mọi thay đổi đều được ghi vết kiểm toán.
						</p>
					</div>
				</div>
				<Button
					variant="primary"
					icon={Plus}
					onClick={handleOpenCreate}
				>
					Thêm lý do
				</Button>
			</header>

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
				<select
					value={filterAction}
					onChange={(e) => setFilterAction(e.target.value)}
					style={{
						padding: "6px 12px",
						borderRadius: "6px",
						border: "1px solid var(--theme-border, #cbd5e1)",
						background: "var(--theme-card-bg, #fff)",
						fontSize: "13px",
					}}
				>
					<option value="">Tất cả loại thao tác</option>
					{ACTION_TYPES.map((a) => (
						<option key={a.value} value={a.value}>
							{a.label}
						</option>
					))}
				</select>
				<select
					value={filterActive}
					onChange={(e) => setFilterActive(e.target.value)}
					style={{
						padding: "6px 12px",
						borderRadius: "6px",
						border: "1px solid var(--theme-border, #cbd5e1)",
						background: "var(--theme-card-bg, #fff)",
						fontSize: "13px",
					}}
				>
					<option value="">Tất cả trạng thái</option>
					<option value="true">Đang dùng</option>
					<option value="false">Ngừng dùng</option>
				</select>
			</div>

			{/* Table */}
			<div className="syscfg-card" style={{ padding: 0, overflow: "hidden" }}>
				<table style={{ width: "100%", borderCollapse: "collapse", fontSize: "13px" }}>
					<thead>
						<tr style={{ background: "rgba(0,0,0,0.03)", borderBottom: "1px solid var(--theme-border, #e2e8f0)", textAlign: "left" }}>
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
								<td colSpan={7} style={{ textAlign: "center", padding: "32px", color: "var(--theme-text-muted)" }}>
									Đang tải danh mục lý do...
								</td>
							</tr>
						) : reasons.length === 0 ? (
							<tr>
								<td colSpan={7} style={{ textAlign: "center", padding: "32px", color: "var(--theme-text-muted)" }}>
									Không tìm thấy lý do nào phù hợp bộ lọc.
								</td>
							</tr>
						) : (
							reasons.map((item) => (
								<tr key={item.id} style={{ borderBottom: "1px solid var(--theme-border, #f1f5f9)" }}>
									<td style={{ padding: "12px 16px" }}>
										<span style={{ fontWeight: 600, color: "var(--brand-blue, #2563eb)" }}>
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
											<Badge variant="warning">Mục Khác</Badge>
										) : (
											<Badge variant="default">Chuẩn</Badge>
										)}
									</td>
									<td style={{ padding: "12px 16px" }}>
										{item.isActive ? (
											<Badge variant="success">Đang dùng</Badge>
										) : (
											<Badge variant="secondary">Ngừng dùng</Badge>
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
											/>
											{item.isActive ? (
												<Button
													variant="ghost"
													size="sm"
													icon={PowerOff}
													disabled={item.isOther}
													onClick={() => handleToggleStatus(item)}
													title={item.isOther ? "Không thể ngừng dùng mục Khác" : "Ngừng dùng"}
													style={{ color: item.isOther ? "#cbd5e1" : "var(--theme-danger, #ef4444)" }}
												/>
											) : (
												<Button
													variant="ghost"
													size="sm"
													icon={Power}
													onClick={() => handleToggleStatus(item)}
													title="Kích hoạt lại"
													style={{ color: "var(--theme-success, #10b981)" }}
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
				onClose={() => setCreateModalOpen(false)}
				title="Thêm lý do sự kiện mới"
				icon={Tag}
			>
				<form onSubmit={handleCreateSubmit} style={{ display: "flex", flexDirection: "column", gap: "14px" }}>
					<div>
						<label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
							Loại thao tác <span style={{ color: "red" }}>*</span>
						</label>
						<select
							value={createForm.actionType}
							onChange={(e) => setCreateForm({ ...createForm, actionType: e.target.value })}
							style={{
								width: "100%",
								padding: "8px 12px",
								borderRadius: "6px",
								border: "1px solid var(--theme-border, #cbd5e1)",
								fontSize: "13.5px",
							}}
						>
							{ACTION_TYPES.map((a) => (
								<option key={a.value} value={a.value}>
									{a.label}
								</option>
							))}
						</select>
					</div>

					<div>
						<label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
							Mã lý do (code) <span style={{ color: "red" }}>*</span>
						</label>
						<Input
							value={createForm.code}
							onChange={(e) => setCreateForm({ ...createForm, code: e.target.value.toUpperCase() })}
							placeholder="VD: TECH_TALK"
							required
						/>
						<span style={{ fontSize: "11px", color: "var(--theme-text-muted)" }}>
							Chữ hoa, số, gạch dưới. Không thể sửa sau khi tạo.
						</span>
					</div>

					<div>
						<label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
							Nhãn hiển thị <span style={{ color: "red" }}>*</span>
						</label>
						<Input
							value={createForm.label}
							onChange={(e) => setCreateForm({ ...createForm, label: e.target.value })}
							placeholder="VD: Buổi chia sẻ kỹ thuật"
							required
						/>
					</div>

					<div>
						<label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
							Thứ tự sắp xếp
						</label>
						<Input
							type="number"
							value={createForm.sortOrder}
							onChange={(e) => setCreateForm({ ...createForm, sortOrder: parseInt(e.target.value, 10) || 0 })}
						/>
					</div>

					<div style={{ display: "flex", justifyContent: "flex-end", gap: "10px", marginTop: "12px" }}>
						<Button type="button" variant="secondary" onClick={() => setCreateModalOpen(false)}>
							Hủy
						</Button>
						<Button type="submit" variant="primary" loading={creating}>
							Tạo mới
						</Button>
					</div>
				</form>
			</Modal>

			{/* Edit Modal */}
			<Modal
				isOpen={editModalOpen}
				onClose={() => setEditModalOpen(false)}
				title="Sửa lý do sự kiện"
				icon={Edit2}
			>
				<form onSubmit={handleUpdateSubmit} style={{ display: "flex", flexDirection: "column", gap: "14px" }}>
					<div>
						<label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
							Mã lý do
						</label>
						<Input value={editForm.code} disabled />
					</div>

					<div>
						<label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
							Nhãn hiển thị <span style={{ color: "red" }}>*</span>
						</label>
						<Input
							value={editForm.label}
							onChange={(e) => setEditForm({ ...editForm, label: e.target.value })}
							required
						/>
					</div>

					<div>
						<label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
							Thứ tự sắp xếp
						</label>
						<Input
							type="number"
							value={editForm.sortOrder}
							onChange={(e) => setEditForm({ ...editForm, sortOrder: parseInt(e.target.value, 10) || 0 })}
						/>
					</div>

					<div style={{ display: "flex", justifyContent: "flex-end", gap: "10px", marginTop: "12px" }}>
						<Button type="button" variant="secondary" onClick={() => setEditModalOpen(false)}>
							Hủy
						</Button>
						<Button type="submit" variant="primary" loading={updating}>
							Lưu thay đổi
						</Button>
					</div>
				</form>
			</Modal>
		</div>
	);
}
