import React, { useState, useEffect } from "react";
import {
	Sliders,
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
import { Button, Input, Card, Modal, Badge } from "../../components/ui";
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

	const handleSave = async (config) => {
		const key = config.configKey;
		const value = editedValues[key];

		setSavingKey(key);
		setErrorMsg(null);
		setSuccessMsg(null);

		try {
			const updated = await updateSystemConfig(key, value);
			setSuccessMsg(
				`Cập nhật cấu hình "${config.description || key}" thành công!`,
			);

			setConfigs((prev) =>
				prev.map((c) => (c.configKey === key ? updated : c)),
			);
			setEditedValues((prev) => ({
				...prev,
				[key]: updated.configValue,
			}));
		} catch (err) {
			console.error("Lỗi khi cập nhật cấu hình:", err);
			setErrorMsg(
				err.message ||
					"Cập nhật cấu hình thất bại. Vui lòng kiểm tra lại giá trị.",
			);
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
			<header className="syscfg-header">
				<div className="syscfg-header__title-group">
					<div className="syscfg-header__icon-box">
						<Sliders size={22} />
					</div>
					<div>
						<h1 className="syscfg-header__title">Cấu hình hệ thống</h1>
						<p className="syscfg-header__subtitle">
							Quản lý các tham số nghiệp vụ toàn trường. Mọi thay đổi sẽ có hiệu
							lực ngay lập tức mà không cần khởi động lại hệ thống.
						</p>
					</div>
				</div>
			</header>

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
																	onClick={() => handleSave(cfg)}
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
									<th>Thời gian</th>
									<th>Người thực hiện</th>
									<th>Giá trị cũ</th>
									<th>Giá trị mới</th>
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
									</tr>
								))}
							</tbody>
						</table>
					</div>
				)}
			</Modal>
		</div>
	);
}
