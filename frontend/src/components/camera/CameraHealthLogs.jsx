import React from "react";
import {
	Activity,
	Clock,
	Zap,
	Video,
	AlertCircle,
	Loader2,
	ChevronLeft,
	ChevronRight,
	ShieldCheck,
	RefreshCw,
} from "lucide-react";
import "../../styles/CameraHealthLogs.css";

export default function CameraHealthLogs({
	logs = [],
	loading = false,
	page = 0,
	totalPages = 0,
	onPageChange,
	onRefresh,
}) {
	const formatTime = (isoString) => {
		if (!isoString) return "";
		try {
			return new Date(isoString).toLocaleString("vi-VN", {
				hour: "2-digit",
				minute: "2-digit",
				second: "2-digit",
				day: "2-digit",
				month: "2-digit",
				year: "numeric",
			});
		} catch {
			return isoString;
		}
	};

	const getLatencyClass = (latency) => {
		if (latency === null || latency === undefined) return "";
		if (latency < 100) return "log-metric-chip--latency-fast";
		if (latency <= 300) return "log-metric-chip--latency-medium";
		return "log-metric-chip--latency-slow";
	};

	return (
		<div className="health-logs-card">
			{/* Header */}
			<div className="health-logs-header">
				<div className="health-logs-title-group">
					<div className="health-logs-icon-wrapper">
						<Activity size={18} />
					</div>
					<div>
						<h3 className="health-logs-title">Nhật ký kết nối</h3>
					</div>
				</div>

				<div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
					{onRefresh && (
						<button
							type="button"
							onClick={onRefresh}
							disabled={loading}
							className="pagination-nav-btn"
							title="Làm mới nhật ký"
							style={{ width: "28px", height: "28px" }}
						>
							<RefreshCw
								size={13}
								className={loading ? "animate-spin" : ""}
							/>
						</button>
					)}
				</div>
			</div>

			{/* Body */}
			<div className="health-logs-body">
				{loading ? (
					<div className="logs-loading">
						<Loader2
							size={24}
							className="logs-loading-spinner"
						/>
						<span>Đang tải nhật ký kết nối...</span>
					</div>
				) : logs.length === 0 ? (
					<div className="logs-empty-state">
						<div className="logs-empty-icon">
							<ShieldCheck size={22} />
						</div>
						<h4 className="logs-empty-title">Chưa có nhật ký kết nối</h4>
						<p className="logs-empty-desc">
							Hệ thống sẽ tự động lưu lại thông số độ trễ, FPS và lỗi kết nối
							mỗi khi kiểm tra luồng camera.
						</p>
					</div>
				) : (
					<div className="logs-list">
						{logs.map((log) => {
							const isOnline = log.status === "ONLINE";
							return (
								<div
									key={log.id}
									className={`log-card ${isOnline ? "log-card--online" : "log-card--offline"}`}
								>
									<div className="log-card-header">
										<span
											className={`log-status-pill ${
												isOnline
													? "log-status-pill--online"
													: "log-status-pill--offline"
											}`}
										>
											<span
												className={`log-status-dot ${
													isOnline
														? "log-status-dot--online"
														: "log-status-dot--offline"
												}`}
											/>
											{log.status}
										</span>

										<span
											className="log-timestamp"
											title="Thời gian kiểm tra"
										>
											<Clock size={12} />
											{formatTime(log.checkedAt)}
										</span>
									</div>

									{/* Metrics Row */}
									{(log.latencyMs !== null && log.latencyMs !== undefined) ||
									log.fps ? (
										<div className="log-metrics-row">
											{log.latencyMs !== null &&
												log.latencyMs !== undefined && (
													<span
														className={`log-metric-chip ${getLatencyClass(log.latencyMs)}`}
														title="Thời gian phản hồi kết nối mạng RTSP"
													>
														<Zap size={12} />
														{log.latencyMs} ms
													</span>
												)}

											{log.fps !== null && log.fps !== undefined && (
												<span
													className="log-metric-chip log-metric-chip--fps"
													title="Tốc độ khung hình"
												>
													<Video size={12} />
													{log.fps} FPS
												</span>
											)}
										</div>
									) : null}

									{/* Error Callout */}
									{log.errorMessage && (
										<div
											className="log-error-callout"
											title="Thông tin chi tiết lỗi kết nối"
										>
											<AlertCircle size={14} />
											<span>{log.errorMessage}</span>
										</div>
									)}
								</div>
							);
						})}
					</div>
				)}
			</div>

			{/* Pagination */}
			{totalPages > 1 && (
				<div className="health-logs-pagination">
					<button
						type="button"
						onClick={() => onPageChange && onPageChange(Math.max(0, page - 1))}
						disabled={page === 0 || loading}
						className="pagination-nav-btn"
						title="Trang trước"
					>
						<ChevronLeft size={16} />
					</button>

					<span className="pagination-counter">
						Trang {page + 1} / {totalPages}
					</span>

					<button
						type="button"
						onClick={() =>
							onPageChange && onPageChange(Math.min(totalPages - 1, page + 1))
						}
						disabled={page >= totalPages - 1 || loading}
						className="pagination-nav-btn"
						title="Trang tiếp theo"
					>
						<ChevronRight size={16} />
					</button>
				</div>
			)}
		</div>
	);
}
