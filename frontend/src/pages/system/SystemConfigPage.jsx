import React, { useState, useEffect, useRef } from "react";
import {
	Save,
	RotateCcw,
	History,
	CheckCircle2,
	AlertCircle,
	Clock,
	Shield,
	Bell,
	SlidersHorizontal,
	Info,
	Cpu,
} from "lucide-react";
import {
	getSystemConfigs,
	updateSystemConfig,
	getSystemConfigHistory,
} from "../../services/systemConfigService";
import { Button, Input, Card, Modal, PageHeader, ReasonTextarea } from "../../components/ui";
import "../../styles/SystemConfigPage.css";

export default function SystemConfigPage() {
	const [configs, setConfigs] = useState([]);
	const [editedValues, setEditedValues] = useState({});
	const [loading, setLoading] = useState(true);
	const [savingKey, setSavingKey] = useState(null);
	const [errorMsg, setErrorMsg] = useState(null);
	const [successMsg, setSuccessMsg] = useState(null);

	// Tab state: active groupKey (no 'ALL' tab)
	const [activeTab, setActiveTab] = useState("");

	// History modal state
	const [historyModalOpen, setHistoryModalOpen] = useState(false);
	const [historyConfigKey, setHistoryConfigKey] = useState("");
	const [historyConfigName, setHistoryConfigName] = useState("");
	const [historyLogs, setHistoryLogs] = useState([]);
	const [historyLoading, setHistoryLoading] = useState(false);

	// Confirm reason modal state
	const [confirmModalConfig, setConfirmModalConfig] = useState(null);
	const [confirmReason, setConfirmReason] = useState("");
	const [confirmReasonError, setConfirmReasonError] = useState("");
	const [confirmError, setConfirmError] = useState(null);
	const confirmReasonRef = useRef(null);

	useEffect(() => {
		fetchConfigs();
	}, []);

	const fetchConfigs = async () => {
		setLoading(true);
		setErrorMsg(null);
		try {
			const data = await getSystemConfigs();
			const list = data || [];
			setConfigs(list);

			const initialMap = {};
			list.forEach((c) => {
				initialMap[c.configKey] = c.configValue;
			});
			setEditedValues(initialMap);

			// Automatically select first available group tab
			const groupedKeys = Array.from(
				new Set(list.map((c) => c.configGroup || "OTHER")),
			);
			if (groupedKeys.length > 0) {
				setActiveTab((prev) =>
					groupedKeys.includes(prev) ? prev : groupedKeys[0],
				);
			}
		} catch (err) {
			console.error("Lỗi khi tải cấu hình hệ thống:", err);
			setErrorMsg(
				err.message ||
					"Không thể kết nối đến máy chủ để lấy cấu hình hệ thống.",
			);
		} finally {
			setLoading(false);
		}
	};

	const handleValueChange = (key, val) => {
		setEditedValues((prev) => ({
			...prev,
			[key]: val,
		}));
		setErrorMsg(null);
		setSuccessMsg(null);
	};

	const handleResetValue = (key, originalValue) => {
		setEditedValues((prev) => ({
			...prev,
			[key]: originalValue,
		}));
	};

	const handleOpenSaveModal = (config) => {
		setConfirmModalConfig(config);
		setConfirmReason("");
		setConfirmReasonError("");
		setConfirmError(null);
	};

	const handleConfirmSave = async () => {
		if (!confirmModalConfig) return;
		const key = confirmModalConfig.configKey;
		const value = editedValues[key];
		const trimmedReason = confirmReason.trim();

		if (trimmedReason.length < 10 || trimmedReason.length > 500) {
			setConfirmReasonError(`Lý do phải từ 10 đến 500 ký tự (hiện có ${trimmedReason.length}).`);
			confirmReasonRef.current?.focus();
			return;
		}

		setSavingKey(key);
		setConfirmError(null);
		setConfirmReasonError("");
		setErrorMsg(null);
		setSuccessMsg(null);

		try {
			const updated = await updateSystemConfig(key, value, trimmedReason);
			setSuccessMsg(
				`Cập nhật cấu hình "${confirmModalConfig.description || key}" thành công!`,
			);

			setConfigs((prev) =>
				prev.map((c) => (c.configKey === key ? updated : c)),
			);
			setEditedValues((prev) => ({
				...prev,
				[key]: updated.configValue,
			}));
			setConfirmModalConfig(null);
		} catch (err) {
			console.error("Lỗi khi cập nhật cấu hình:", err);
			const msg =
				err.message ||
				"Cập nhật cấu hình thất bại. Vui lòng kiểm tra lại giá trị.";
			setConfirmError(msg);
			setErrorMsg(msg);
		} finally {
			setSavingKey(null);
		}
	};

	const handleOpenHistory = async (config) => {
		setHistoryConfigKey(config.configKey);
		setHistoryConfigName(config.description || config.configKey);
		setHistoryModalOpen(true);
		setHistoryLoading(true);

		try {
			const res = await getSystemConfigHistory(config.configKey, {
				page: 0,
				size: 20,
			});
			setHistoryLogs(res?.content || []);
		} catch (err) {
			console.error("Lỗi tải lịch sử cấu hình:", err);
		} finally {
			setHistoryLoading(false);
		}
	};

	const formatDateTime = (ts) => {
		if (!ts) return "Chưa cập nhật";
		try {
			const d = new Date(ts);
			if (isNaN(d.getTime())) return ts;
			return d.toLocaleString("vi-VN", {
				hour: "2-digit",
				minute: "2-digit",
				second: "2-digit",
				day: "2-digit",
				month: "2-digit",
				year: "numeric",
			});
		} catch {
			return ts;
		}
	};

	// Group configurations by configGroup
	const groupedConfigs = configs.reduce((acc, cfg) => {
		const group = cfg.configGroup || "OTHER";
		if (!acc[group]) acc[group] = [];
		acc[group].push(cfg);
		return acc;
	}, {});

	const getGroupTitle = (groupKey) => {
		switch (groupKey) {
			case "ACCESS_REQUEST":
				return "Yêu cầu vào khu vực";
			case "NOTIFICATION":
				return "Thông báo In-App";
			case "AI_CONFIG":
				return "AI";
			case "ACCESS_LEVEL":
				return "Mức truy cập";
			case "SECURITY":
				return "An ninh";
			case "ACCOUNT":
				return "Tài khoản";
			default:
				return groupKey;
		}
	};

	const getGroupIcon = (groupKey) => {
		switch (groupKey) {
			case "ACCESS_REQUEST":
				return (
					<Shield
						size={18}
						className="syscfg-group-icon"
					/>
				);
			case "NOTIFICATION":
				return (
					<Bell
						size={18}
						className="syscfg-group-icon"
					/>
				);
			case "AI_CONFIG":
				return (
					<Cpu
						size={18}
						className="syscfg-group-icon"
					/>
				);
			default:
				return (
					<SlidersHorizontal
						size={18}
						className="syscfg-group-icon"
					/>
				);
		}
	};

	return (
		<div className="syscfg-container">
			{/* Header */}
			<PageHeader
				title="Cấu hình hệ thống"
				description="Quản lý các tham số nghiệp vụ toàn trường. Mọi thay đổi sẽ có hiệu lực ngay lập tức mà không cần khởi động lại hệ thống."
			/>

			{/* Notifications / Alerts */}
			{successMsg && (
				<div className="syscfg-alert syscfg-alert--success">
					<CheckCircle2
						size={18}
						className="syscfg-alert__icon"
					/>
					<span className="syscfg-alert__text">{successMsg}</span>
				</div>
			)}

			{errorMsg && (
				<div className="syscfg-alert syscfg-alert--error">
					<AlertCircle
						size={18}
						className="syscfg-alert__icon"
					/>
					<span className="syscfg-alert__text">{errorMsg}</span>
				</div>
			)}

			{/* Main Content */}
			{loading ? (
				<div className="syscfg-loading">
					<div className="syscfg-spinner" />
					<p>Đang tải cấu hình hệ thống...</p>
				</div>
			) : (
				<>
					{/* Tabs Navigation (No 'ALL' tab) */}
					<div className="syscfg-tabs-nav">
						{Object.entries(groupedConfigs).map(([groupKey, groupItems]) => (
							<button
								key={groupKey}
								type="button"
								className={`syscfg-tab-btn ${activeTab === groupKey ? "syscfg-tab-btn--active" : ""}`}
								onClick={() => setActiveTab(groupKey)}
							>
								{getGroupIcon(groupKey)}
								<span>{getGroupTitle(groupKey)}</span>
								<span className="syscfg-tab-badge">{groupItems.length}</span>
							</button>
						))}
					</div>

					<div className="syscfg-groups">
						{Object.entries(groupedConfigs)
							.filter(([groupKey]) => activeTab === groupKey)
							.map(([groupKey, groupItems]) => (
								<section
									key={groupKey}
									className="syscfg-group-section"
								>
									<div className="syscfg-items-grid">
										{groupItems.map((cfg) => {
											const currentValue =
												editedValues[cfg.configKey] ?? cfg.configValue;
											const isModified =
												String(currentValue) !== String(cfg.configValue);
											const isSaving = savingKey === cfg.configKey;

											return (
												<Card
													key={cfg.configKey}
													padding="md"
													className="syscfg-item-card"
												>
													<div className="syscfg-item">
														{/* Top: Description and Key */}
														<div className="syscfg-item__top">
															<div className="syscfg-item__meta">
																<h3 className="syscfg-item__description">
																	{cfg.description}
																</h3>
															</div>

															<Button
																variant="ghost"
																size="sm"
																icon={<History size={14} />}
																onClick={() => handleOpenHistory(cfg)}
																title="Xem lịch sử thay đổi tham số này"
															>
																Lịch sử
															</Button>
														</div>

														{/* Middle: Input control based on data type */}
														<div className="syscfg-item__control-row">
															{cfg.dataType === "BOOLEAN" ? (
																<div className="syscfg-toggle-wrapper">
																	<button
																		type="button"
																		className={`syscfg-toggle ${currentValue === "true" ? "syscfg-toggle--active" : ""}`}
																		onClick={() =>
																			handleValueChange(
																				cfg.configKey,
																				currentValue === "true"
																					? "false"
																					: "true",
																			)
																		}
																		disabled={!cfg.editable}
																	>
																		<span className="syscfg-toggle__switch" />
																	</button>
																	<span className="syscfg-toggle__label">
																		{currentValue === "true"
																			? "Cho phép"
																			: "Không cho phép (Cấm)"}
																	</span>
																</div>
															) : (
																<div className="syscfg-input-wrapper">
																	<Input
								type={
									cfg.unit === "HH:mm"
										? "time"
										:
									cfg.dataType === "INTEGER" ||
																			cfg.dataType === "DECIMAL"
																				? "number"
																				: "text"
																		}
																		value={currentValue}
																		onChange={(e) =>
																			handleValueChange(
																				cfg.configKey,
																				e.target.value,
																			)
																		}
																		disabled={!cfg.editable}
																		min={cfg.minValue}
																		max={cfg.maxValue}
																		className="syscfg-field-input"
																	/>
																	{cfg.unit && (
																		<span className="syscfg-input-unit">
																			{cfg.unit}
																		</span>
																	)}
																</div>
															)}

															{/* Action Buttons */}
															<div className="syscfg-item__actions">
																{isModified && (
																	<Button
																		variant="secondary"
																		size="sm"
																		icon={<RotateCcw size={14} />}
																		onClick={() =>
																			handleResetValue(
																				cfg.configKey,
																				cfg.configValue,
																			)
																		}
																		disabled={isSaving}
																		title="Khôi phục giá trị ban đầu"
																	>
																		Hoàn tác
																	</Button>
																)}

																<Button
																	variant="primary"
																	size="sm"
																	icon={<Save size={14} />}
																	loading={isSaving}
																	disabled={
																		!isModified || isSaving || !cfg.editable
																	}
																	onClick={() => handleOpenSaveModal(cfg)}
																>
																	Lưu
																</Button>
															</div>
														</div>

														{/* Rules Hint: Min / Max constraints */}
														{(cfg.minValue != null || cfg.maxValue != null) && (
															<div className="syscfg-item__hint">
																<Info size={12} />
																<span>
																	Giới hạn cho phép:{" "}
																	{cfg.minValue != null
																		? `tối thiểu ${cfg.minValue}`
																		: ""}
																	{cfg.minValue != null && cfg.maxValue != null
																		? " — "
																		: ""}
																	{cfg.maxValue != null
																		? `tối đa ${cfg.maxValue}`
																		: ""}{" "}
																	{cfg.unit || ""}
																</span>
															</div>
														)}

														{/* Footer: Last updated info */}
														<div className="syscfg-item__footer">
															<div className="syscfg-item__audit-text">
																<Clock size={12} />
																<span>
																	Cập nhật lần cuối:{" "}
																	{formatDateTime(cfg.updatedAt)}
																	{cfg.updatedByName && (
																		<>
																			{" "}
																			bởi <strong>{cfg.updatedByName}</strong>
																		</>
																	)}
																</span>
															</div>
														</div>
													</div>
												</Card>
											);
										})}
									</div>
								</section>
							))}
					</div>
				</>
			)}

			{/* Audit Change Log Modal */}
			<Modal
				isOpen={historyModalOpen}
				onClose={() => setHistoryModalOpen(false)}
				title="Nhật ký thay đổi cấu hình"
				subtitle={historyConfigName}
				size="lg"
			>
				{historyLoading ? (
					<div className="syscfg-modal-loading">
						<div className="syscfg-spinner" />
						<p>Đang tải nhật ký thay đổi...</p>
					</div>
				) : historyLogs.length === 0 ? (
					<div className="syscfg-modal-empty">
						<Clock
							size={32}
							className="syscfg-modal-empty__icon"
						/>
						<p className="syscfg-modal-empty__text">
							Chưa có lượt thay đổi nào được ghi nhận cho tham số này.
						</p>
					</div>
				) : (
					<div className="syscfg-history-list">
						<table className="syscfg-history-table">
							<thead>
								<tr>
									<th className="ui-col-time">Thời gian</th>
									<th>Người thực hiện</th>
									<th>Giá trị cũ</th>
									<th>Giá trị mới</th>
									<th>Lý do</th>
								</tr>
							</thead>
							<tbody>
								{historyLogs.map((log) => (
									<tr key={log.id}>
										<td className="syscfg-history-time">
											{formatDateTime(log.changedAt)}
										</td>
										<td>
											<div className="syscfg-history-user">
												<strong>{log.changedByName || "Quản trị viên"}</strong>
												{log.changedByEmail && (
													<span className="syscfg-history-email">
														{log.changedByEmail}
													</span>
												)}
											</div>
										</td>
										<td>
											<span className="syscfg-val-badge syscfg-val-badge--old">
												{log.oldValue ?? "(trống)"}
											</span>
										</td>
										<td>
											<span className="syscfg-val-badge syscfg-val-badge--new">
												{log.newValue}
											</span>
										</td>
										<td>
											<span style={{ fontSize: "12.5px", color: "var(--theme-text-primary, #0f172a)" }}>
												{log.reason || "—"}
											</span>
										</td>
									</tr>
								))}
							</tbody>
						</table>
					</div>
				)}
			</Modal>

			{/* Modal Nhập lý do khi cập nhật cấu hình */}
			{confirmModalConfig && (
				<Modal
					isOpen={Boolean(confirmModalConfig)}
					onClose={() => setConfirmModalConfig(null)}
					title="Xác nhận cập nhật cấu hình"
					subtitle={confirmModalConfig.description || confirmModalConfig.configKey}
					size="md"
					footer={
						<div style={{ display: "flex", justifyContent: "flex-end", gap: "8px" }}>
							<Button
								variant="secondary"
								onClick={() => setConfirmModalConfig(null)}
								disabled={savingKey != null}
							>
								Huỷ
							</Button>
							<Button
								variant="primary"
								onClick={handleConfirmSave}
								loading={savingKey === confirmModalConfig.configKey}
							>
								Xác nhận lưu
							</Button>
						</div>
					}
				>
					<div style={{ display: "flex", flexDirection: "column", gap: "14px" }}>
						{confirmError && (
							<div className="syscfg-alert syscfg-alert--error">
								<AlertCircle size={18} className="syscfg-alert__icon" />
								<span className="syscfg-alert__text">{confirmError}</span>
							</div>
						)}
						<div
							style={{
								background: "var(--theme-bg, #f8fafc)",
								padding: "12px",
								borderRadius: "8px",
								fontSize: "13.5px",
								border: "1px solid var(--theme-border, #e2e8f0)",
							}}
						>
							<div><strong>Tham số:</strong> {confirmModalConfig.configKey}</div>
							<div style={{ marginTop: "4px" }}>
								<strong>Giá trị hiện tại:</strong> {confirmModalConfig.configValue}
							</div>
							<div style={{ marginTop: "4px" }}>
								<strong>Giá trị mới:</strong> {editedValues[confirmModalConfig.configKey]}
							</div>
						</div>
						<div>
							<ReasonTextarea
								ref={confirmReasonRef}
								label="Lý do thay đổi cấu hình"
								required
								value={confirmReason}
								onChange={(e) => {
									const val = e.target.value;
									setConfirmReason(val);
									if (confirmReasonError && val.trim().length >= 10 && val.trim().length <= 500) {
										setConfirmReasonError("");
									}
								}}
								minLength={10}
								maxLength={500}
								placeholder="Nhập lý do thay đổi cấu hình (tối thiểu 10 ký tự, tối đa 500 ký tự)..."
								rows={3}
								error={confirmReasonError}
							/>
						</div>
					</div>
				</Modal>
			)}
		</div>
	);
}
