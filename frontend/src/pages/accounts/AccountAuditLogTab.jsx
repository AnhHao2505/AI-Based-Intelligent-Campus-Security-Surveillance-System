import { useState, useEffect, useCallback } from "react";
import { History, RotateCcw, X, Layers } from "lucide-react";
import { getAccountAuditLogs } from "../../services/userService";
import { formatDate, formatTime } from "../../utils/formatDateTime";
import {
	ACCOUNT_LOG_GROUPS,
	LOGIN_FAILURE_REASON_LABELS,
	TARGET_TYPE_LABELS,
	getActionLabel,
	renderSystemActor,
} from "../../utils/auditLabels";
import Button from "../../components/ui/Button";
import { LoadingState, EmptyState, ErrorState } from "../../components/ui";
import { ROLE_LABELS } from "../../constants/roles";
// Dùng lại bảng / bộ lọc / banner correlation của màn Nhật ký thay đổi (audit-*, category-badge)
import "../../styles/UserAccessLevelPage.css";
import "../../styles/AuditLogPage.css";
import "../../styles/AccountAuditLogTab.css";

const PAGE_SIZE = 20;

const GROUP_BADGE = { ACCOUNT: "success", AUTH: "warning", SYSTEM_CONFIG: "purple" };
const MODULE_OF_TARGET = {
	USER_ACCOUNT: "ACCOUNT",
	USER_IMPORT_BATCH: "ACCOUNT",
	FACE_REGISTRATION: "ACCOUNT",
	AUTH: "AUTH",
	SYSTEM_CONFIG: "SYSTEM_CONFIG",
};

const yesNo = (v) => (v ? "Có" : "Không");

// audit-login-privacy: luôn hiện e-mail đăng nhập ở dạng đã che (h***@miền). Giá trị đã che từ backend giữ nguyên.
const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const maskEmail = (value) => {
	if (!value) return "—";
	if (!EMAIL_SHAPE.test(value)) return value.includes("*") ? value : "***";
	const at = value.lastIndexOf("@");
	return `${value.charAt(0)}***@${value.slice(at + 1)}`;
};

/**
 * PR-AUDIT (F1–F3): tab "Nhật ký" của màn Quản lý tài khoản (chỉ ADMIN) — tài khoản, đăng nhập, cấu hình hệ thống.
 * @param {{ subjectUser?: {id: string, userCode: string, fullName: string} | null, onClearSubjectUser: () => void,
 *           initialGroups?: string[] }} props
 */
export default function AccountAuditLogTab({ subjectUser, onClearSubjectUser, initialGroups }) {
	const [groups, setGroups] = useState(initialGroups || []);
	const [actions, setActions] = useState([]);
	const [subjectKeyword, setSubjectKeyword] = useState("");
	const [actorKeyword, setActorKeyword] = useState("");
	const [debouncedSubject, setDebouncedSubject] = useState("");
	const [debouncedActor, setDebouncedActor] = useState("");
	const [filterFrom, setFilterFrom] = useState("");
	const [filterTo, setFilterTo] = useState("");
	const [correlation, setCorrelation] = useState(null);

	const [logs, setLogs] = useState([]);
	const [currentPage, setCurrentPage] = useState(0);
	const [totalPages, setTotalPages] = useState(0);
	const [totalElements, setTotalElements] = useState(0);
	const [loading, setLoading] = useState(false);
	const [loadError, setLoadError] = useState(null);

	useEffect(() => {
		const t = setTimeout(() => {
			setDebouncedSubject(subjectKeyword.trim());
			setDebouncedActor(actorKeyword.trim());
		}, 400);
		return () => clearTimeout(t);
	}, [subjectKeyword, actorKeyword]);

	const loadLogs = useCallback(
		async (page = 0) => {
			setLoading(true);
			setLoadError(null);
			try {
				const params = { page, size: PAGE_SIZE, modules: groups, actions };
				if (subjectUser?.id) params.subjectUserId = subjectUser.id;
				if (debouncedSubject) params.subject = debouncedSubject;
				if (debouncedActor) params.actor = debouncedActor;
				if (correlation?.id) params.correlationId = correlation.id;
				// Ngày lọc theo giờ Việt Nam (UTC+7), như màn Nhật ký thay đổi
				if (filterFrom) params.from = `${filterFrom}T00:00:00+07:00`;
				if (filterTo) params.to = `${filterTo}T23:59:59+07:00`;
				const res = await getAccountAuditLogs(params);
				setLogs(res?.content || []);
				setTotalPages(res?.totalPages || 0);
				setTotalElements(res?.totalElements || 0);
				setCurrentPage(page);
			} catch (err) {
				console.error("Lỗi tải nhật ký tài khoản:", err);
				setLoadError(
					err?.status === 403
						? "Bạn không có quyền xem nhật ký tài khoản"
						: err?.message || "Không thể tải nhật ký tài khoản",
				);
				setLogs([]);
			} finally {
				setLoading(false);
			}
		},
		[groups, actions, subjectUser, debouncedSubject, debouncedActor, correlation, filterFrom, filterTo],
	);

	useEffect(() => {
		loadLogs(0);
	}, [loadLogs]);

	const toggleGroup = (module) => {
		setGroups((prev) => {
			const next = prev.includes(module) ? prev.filter((m) => m !== module) : [...prev, module];
			// Bỏ hành động không còn thuộc nhóm nào đang chọn
			const allowed = ACCOUNT_LOG_GROUPS.filter((g) => next.length === 0 || next.includes(g.module)).flatMap(
				(g) => g.actions,
			);
			setActions((acts) => acts.filter((a) => allowed.includes(a)));
			return next;
		});
	};

	const toggleAction = (action) => {
		setActions((prev) => (prev.includes(action) ? prev.filter((a) => a !== action) : [...prev, action]));
	};

	const visibleGroups = ACCOUNT_LOG_GROUPS.filter((g) => groups.length === 0 || groups.includes(g.module));
	const actionOptions = [...new Set(visibleGroups.flatMap((g) => g.actions))];

	const hasActiveFilters =
		groups.length > 0 ||
		actions.length > 0 ||
		subjectKeyword ||
		actorKeyword ||
		filterFrom ||
		filterTo ||
		correlation ||
		subjectUser;

	const handleResetFilters = () => {
		setGroups([]);
		setActions([]);
		setSubjectKeyword("");
		setActorKeyword("");
		setFilterFrom("");
		setFilterTo("");
		setCorrelation(null);
		if (subjectUser) onClearSubjectUser();
	};

	const openBatch = (log) => {
		setCorrelation({ id: log.correlationId, label: log.newValue?.fileName || log.targetId });
	};

	const renderActor = (log) => {
		if (log.actorType === "SYSTEM" || !log.changedByName) {
			return <span className="font-semibold audit-system-actor">{renderSystemActor(log.actorSource)}</span>;
		}
		return (
			<div className="flex-col-stack">
				<span className="font-semibold">{log.changedByName}</span>
				{log.changedByUserCode && <span className="text-muted">{log.changedByUserCode}</span>}
			</div>
		);
	};

	const renderTarget = (log) => {
		const snap = log.newValue || log.oldValue || {};
		switch (log.targetType) {
			case "USER_ACCOUNT":
			case "FACE_REGISTRATION":
				return log.subjectUserName ? (
					<div className="flex-col-stack">
						<span className="font-semibold">{log.subjectUserName}</span>
						<span className="text-muted">{log.subjectUserCode}</span>
					</div>
				) : (
					<span className="font-semibold">{snap.userCode || log.targetId}</span>
				);
			case "USER_IMPORT_BATCH":
				return (
					<div className="flex-col-stack">
						<span className="font-semibold">Lô {log.targetId?.slice(0, 8)}</span>
						{snap.fileName && <span className="text-muted-wrap">{snap.fileName}</span>}
					</div>
				);
			case "AUTH": {
				// target_id "UNKNOWN:<hash>" = chuỗi đã nhập không khớp tài khoản nào (backend chỉ lưu bản che + hash)
				const unknown = typeof log.targetId === "string" && log.targetId.startsWith("UNKNOWN");
				return (
					<div className="flex-col-stack">
						<span className="font-semibold">{maskEmail(snap.email)}</span>
						{log.subjectUserCode && <span className="text-muted">{log.subjectUserCode}</span>}
						{unknown && (
							<span className="text-muted" title={snap.emailHash ? `Mã băm: ${snap.emailHash}` : undefined}>
								Không có tài khoản
							</span>
						)}
					</div>
				);
			}
			case "SYSTEM_CONFIG":
				return <span className="font-semibold aal-mono">{log.targetId}</span>;
			default:
				return <span className="text-muted">{log.targetId}</span>;
		}
	};

	const renderDetail = (log) => {
		const oldV = log.oldValue || {};
		const newV = log.newValue || {};
		const rows = [];
		const row = (label, value, key) =>
			rows.push(
				<div className="audit-detail-row" key={key || label}>
					<span className="audit-row-label">{label}:</span>
					{value}
				</div>,
			);
		const change = (from, to) => (
			<span>
				<span className="audit-val--old">{from}</span>
				<span className="audit-arrow">→</span>
				<strong className="audit-val--new">{to}</strong>
			</span>
		);

		if (log.targetType === "USER_ACCOUNT") {
			if (log.action === "CREATE") {
				row("Vai trò", <strong>{ROLE_LABELS[newV.role] || newV.role}</strong>);
				row("Cấp truy cập", <strong>Cấp {newV.accessLevel}</strong>);
				row("Có khuôn mặt", <strong>{yesNo(newV.hasFaceData)}</strong>);
			} else {
				const status = (v) => (v.deleted ? "Đã xoá" : v.isActive ? "Đang hoạt động" : "Đã vô hiệu hoá");
				row("Trạng thái", change(status(oldV), status(newV)));
				if (newV.cancelledRequests != null) row("Đơn bị huỷ", <strong>{newV.cancelledRequests}</strong>);
				if (newV.revokedAssignments != null)
					row("Nhân sự chỉ định bị thu hồi", <strong>{newV.revokedAssignments}</strong>);
				if (newV.websocketSessionsToClose != null)
					row("Phiên kết nối bị đóng", <strong>{newV.websocketSessionsToClose}</strong>);
			}
		} else if (log.targetType === "USER_IMPORT_BATCH") {
			if (log.action === "IMPORT") {
				row("Luồng", <strong>{newV.flow === "STAFF" ? "Cán bộ / bảo vệ" : "Người dùng thường"}</strong>);
				row(
					"Kết quả",
					<strong>
						{newV.successCount}/{newV.totalRows} thành công, {newV.failureCount} lỗi
					</strong>,
				);
			} else if (log.action === "DELETE") {
				row("Số tài khoản gỡ", <strong>{newV.affectedCount}</strong>);
			} else if (log.action === "RESTORE") {
				row("Khôi phục / bỏ qua", <strong>{newV.affectedCount} / {newV.skippedCount}</strong>);
			}
			if (Array.isArray(newV.userCodes) && newV.userCodes.length > 0) {
				const shown = newV.userCodes.slice(0, 5).join(", ");
				row("Mã", <span title={newV.userCodes.join(", ")}>{shown}{newV.userCodes.length > 5 ? ", …" : ""}</span>);
			}
		} else if (log.targetType === "FACE_REGISTRATION") {
			row("Loại", <strong>{newV.replaced ? "Thay ảnh khuôn mặt" : "Đăng ký lần đầu"}</strong>);
		} else if (log.targetType === "AUTH") {
			if (newV.method) row("Phương thức", <strong>{newV.method === "GOOGLE" ? "Google" : "Mật khẩu"}</strong>);
			if (newV.failureReason)
				row(
					"Lý do",
					<span className="audit-val--fail">
						{LOGIN_FAILURE_REASON_LABELS[newV.failureReason] || newV.failureReason}
					</span>,
				);
			if (newV.lockMinutes != null) row("Thời gian khoá", <strong>{newV.lockMinutes} phút</strong>);
		} else if (log.targetType === "SYSTEM_CONFIG") {
			row("Giá trị", change(oldV.value ?? "—", newV.value ?? "—"));
		}
		if (log.reason) row("Lý do", <span className="text-muted-wrap">{log.reason}</span>, "reason");
		return rows.length > 0 ? <div className="audit-detail-rules">{rows}</div> : null;
	};

	return (
		<div className="aal-container">
			{subjectUser && (
				<div className="audit-correlation-banner">
					<div className="audit-correlation-banner__content">
						<span>
							Đang xem nhật ký của <strong>{subjectUser.fullName}</strong> ({subjectUser.userCode})
						</span>
					</div>
					<button
						type="button"
						className="audit-correlation-clear-btn"
						onClick={onClearSubjectUser}
						aria-label="Bỏ lọc theo tài khoản"
					>
						<X size={14} />
						<span>Bỏ lọc</span>
					</button>
				</div>
			)}
			{correlation && (
				<div className="audit-correlation-banner">
					<div className="audit-correlation-banner__content">
						<Layers size={16} />
						<span>
							Các thao tác của lô <span className="audit-correlation-banner__id">{correlation.label}</span>
						</span>
					</div>
					<button
						type="button"
						className="audit-correlation-clear-btn"
						onClick={() => setCorrelation(null)}
						aria-label="Bỏ lọc theo lô"
					>
						<X size={14} />
						<span>Bỏ lọc</span>
					</button>
				</div>
			)}

			<div className="audit-filter-bar">
				<div className="aal-filter-row">
					<div className="aal-chip-group" role="group" aria-label="Nhóm nhật ký">
						{ACCOUNT_LOG_GROUPS.map((g) => (
							<button
								key={g.module}
								type="button"
								className={`aal-chip ${groups.includes(g.module) ? "aal-chip--active" : ""}`}
								aria-pressed={groups.includes(g.module)}
								onClick={() => toggleGroup(g.module)}
							>
								{g.label}
							</button>
						))}
					</div>
					<input
						type="text"
						className="audit-filter-input aal-input"
						placeholder="Đối tượng: mã tài khoản, e-mail, mã lô, khoá cấu hình"
						aria-label="Lọc theo đối tượng"
						value={subjectKeyword}
						onChange={(e) => setSubjectKeyword(e.target.value)}
					/>
					<input
						type="text"
						className="audit-filter-input aal-input"
						placeholder="Người thực hiện: mã hoặc họ tên"
						aria-label="Lọc theo người thực hiện"
						value={actorKeyword}
						onChange={(e) => setActorKeyword(e.target.value)}
					/>
				</div>

				<div className="aal-chip-group aal-chip-group--actions" role="group" aria-label="Hành động">
					{actionOptions.map((a) => (
						<button
							key={a}
							type="button"
							className={`aal-chip aal-chip--small ${actions.includes(a) ? "aal-chip--active" : ""}`}
							aria-pressed={actions.includes(a)}
							onClick={() => toggleAction(a)}
						>
							{a === "UPDATE" ? "Sửa cấu hình" : getActionLabel(null, a)}
						</button>
					))}
				</div>

				<div className="audit-filter-controls">
					<div className="audit-date-range">
						<label className="audit-date-label" htmlFor="aal-filter-from">
							Từ
						</label>
						<input
							id="aal-filter-from"
							type="date"
							className="audit-date-input"
							value={filterFrom}
							onChange={(e) => setFilterFrom(e.target.value)}
						/>
						<label className="audit-date-label" htmlFor="aal-filter-to">
							Đến
						</label>
						<input
							id="aal-filter-to"
							type="date"
							className="audit-date-input"
							value={filterTo}
							onChange={(e) => setFilterTo(e.target.value)}
						/>
					</div>
					<div className="audit-filter-actions">
						<button
							type="button"
							className="audit-clear-btn"
							onClick={handleResetFilters}
							disabled={!hasActiveFilters}
						>
							<X size={16} />
							<span>Xóa bộ lọc</span>
						</button>
						<button
							type="button"
							className="audit-refresh-btn"
							aria-label="Làm mới"
							title="Làm mới"
							onClick={() => loadLogs(currentPage)}
							disabled={loading}
						>
							<RotateCcw size={16} className={loading ? "animate-spin" : ""} />
						</button>
					</div>
				</div>
			</div>

			{loading ? (
				<LoadingState text="Đang tải nhật ký..." />
			) : loadError ? (
				<ErrorState message={loadError} onRetry={() => loadLogs(currentPage)} />
			) : logs.length === 0 ? (
				<EmptyState
					icon={History}
					title="Không có bản ghi nhật ký phù hợp"
					description="Thử thay đổi bộ lọc để xem kết quả khác."
				/>
			) : (
				<>
					<div className="table-wrapper">
						<table className="data-table">
							<thead>
								<tr>
									<th>THỜI ĐIỂM</th>
									<th>NGƯỜI THỰC HIỆN</th>
									<th>HÀNH ĐỘNG</th>
									<th>ĐỐI TƯỢNG</th>
									<th>CHI TIẾT</th>
									<th>IP</th>
								</tr>
							</thead>
							<tbody>
								{logs.map((log) => {
									const isBatchImport = log.targetType === "USER_IMPORT_BATCH" && log.action === "IMPORT";
									const module = MODULE_OF_TARGET[log.targetType];
									return (
										<tr
											key={log.id}
											className={isBatchImport ? "aal-row--clickable" : undefined}
											onClick={isBatchImport ? () => openBatch(log) : undefined}
											title={isBatchImport ? "Bấm để xem các tài khoản của lô" : undefined}
										>
											<td>
												<div className="flex-col-stack">
													<span className="text-muted">{formatDate(log.changedAt)}</span>
													<span>{formatTime(log.changedAt)}</span>
												</div>
											</td>
											<td>{renderActor(log)}</td>
											<td>
												<div className="flex-col-stack">
													<span
														className={`category-badge category-badge--${GROUP_BADGE[module] || "default"}`}
													>
														{TARGET_TYPE_LABELS[log.targetType] || log.targetType}
													</span>
													<span className="font-semibold">
														{getActionLabel(log.targetType, log.action)}
													</span>
												</div>
											</td>
											<td>{renderTarget(log)}</td>
											<td>
												{renderDetail(log)}
												{isBatchImport && (
													<button
														type="button"
														className="aal-link-btn"
														onClick={(e) => {
															e.stopPropagation();
															openBatch(log);
														}}
													>
														Xem các tài khoản của lô
													</button>
												)}
											</td>
											<td>
												<span className="aal-mono" title={log.userAgent || undefined}>
													{log.ipAddress || "—"}
												</span>
											</td>
										</tr>
									);
								})}
							</tbody>
						</table>
					</div>

					{totalPages > 1 && (
						<div className="pagination-bar">
							<span className="pagination-info">
								Trang {currentPage + 1} / {totalPages} ({totalElements} bản ghi)
							</span>
							<div className="pagination-actions">
								<Button
									variant="outline"
									size="sm"
									disabled={currentPage === 0 || loading}
									onClick={() => loadLogs(currentPage - 1)}
								>
									Trang trước
								</Button>
								<Button
									variant="outline"
									size="sm"
									disabled={currentPage >= totalPages - 1 || loading}
									onClick={() => loadLogs(currentPage + 1)}
								>
									Trang sau
								</Button>
							</div>
						</div>
					)}
				</>
			)}
		</div>
	);
}
