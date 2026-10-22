import React, { useState, useEffect, useCallback } from "react";
import { History, RotateCcw, Link, X } from "lucide-react";
import { useAuth } from "../../context/AuthContext";
import { getAuditLogs } from "../../services/accessControlService";
import { getAreas } from "../../services/areaService";
import { ROLES } from "../../constants/roles";
import { getLevelConfig } from "../../utils/areaHelpers";
import { formatDate, formatTime, formatDateTime } from "../../utils/formatDateTime";
import { formatLocation } from "../../utils/formatLocation";
import {
	TARGET_TYPE_LABELS,
	getActionLabel,
	renderSystemActor,
} from "../../utils/auditLabels";
import Button from "../../components/ui/Button";
import PageHeader from "../../components/ui/PageHeader";
import { LoadingState, EmptyState, ErrorState, Select } from "../../components/ui";
import UserSearchCombobox from "../../components/user/UserSearchCombobox";
// UserAccessLevelPage.css: badge / ô người dùng / nội dung thay đổi / banner correlation (audit-*) dùng chung với trang cũ
import "../../styles/UserAccessLevelPage.css";
import "../../styles/AuditLogPage.css";

// Nhãn tiếng Việt cho tên trường trong snapshot audit (cột nội dung thay đổi, nhánh mặc định).
// Trường không có nhãn (id, version, ...) không hiện tên kỹ thuật.
const AUDIT_FIELD_LABELS = {
	status: "Trạng thái",
	name: "Tên",
	code: "Mã",
	label: "Tên",
	note: "Ghi chú",
	reason: "Lý do",
	reasonCode: "Lý do",
	reasonLabel: "Lý do",
	rejectionReason: "Lý do từ chối",
	actionType: "Loại thao tác",
	isOther: "Lý do khác",
	isActive: "Trạng thái hoạt động",
	accessLevel: "Cấp truy cập",
	areaAccessLevel: "Cấp truy cập khu vực",
	areaLevel: "Loại khu vực",
	explicitAuthorizationRequired: "Yêu cầu chỉ định đích danh",
	building: "Tòa nhà",
	floor: "Tầng",
	centerLatitude: "Tọa độ",
	centerLongitude: "Tọa độ",
	areaId: "Khu vực",
	areaIds: "Khu vực",
	requestType: "Hình thức",
	requesterId: "Người yêu cầu",
	validFrom: "Hiệu lực từ",
	validTo: "Hiệu lực đến",
	startTime: "Giờ bắt đầu",
	endTime: "Giờ kết thúc",
	startAt: "Giờ bắt đầu",
	endAt: "Giờ kết thúc",
	scheduleId: "Lịch sự kiện",
	title: "Tên sự kiện",
	sessionId: "Phiên sự kiện",
	openToMembers: "Mở cho thành viên",
	openUntil: "Mở đến",
	plannedEnd: "Dự kiến kết thúc",
	hostCode: "Người tiếp đón",
	guestCount: "Số khách",
	guestId: "Khách",
	visitId: "Lượt khách",
	biometricStatus: "Trạng thái sinh trắc",
	consentNoticeVersion: "Phiên bản thông báo đồng ý",
	photoDeletionScheduled: "Lịch xóa ảnh",
	anonymized: "Ẩn danh hóa",
};

const summarizeSnapshotFields = (snap) => {
	const labels = [
		...new Set(Object.keys(snap).map((key) => AUDIT_FIELD_LABELS[key]).filter(Boolean)),
	];
	if (labels.length === 0) return "Xem chi tiết";
	return labels.length > 3 ? `${labels.slice(0, 3).join(", ")}, …` : labels.join(", ");
};

const MODULE_OPTIONS_ADMIN = [
	{ value: "", label: "Tất cả phân hệ" },
	{ value: "AREA", label: "Khu vực" },
	{ value: "ACCESS_CONTROL", label: "Phân quyền" },
	{ value: "ACCESS_REQUEST", label: "Yêu cầu truy cập" },
	{ value: "SYSTEM", label: "Hệ thống" },
	{ value: "GUEST", label: "Khách" },
	// Xem ảnh qua /api/media — chỉ ADMIN (audit_module_roles, V81_1)
	{ value: "SECURITY", label: "An ninh" },
];

const MODULE_OPTIONS_FM = [
	{ value: "", label: "Tất cả phân hệ" },
	{ value: "AREA", label: "Khu vực" },
	{ value: "ACCESS_CONTROL", label: "Phân quyền" },
	{ value: "ACCESS_REQUEST", label: "Yêu cầu truy cập" },
];

// Chỉ các giá trị có trong enum AuditTargetType của backend (giá trị khác -> API trả 400)
const TARGET_TYPE_OPTIONS = [
	{ value: "", label: "Tất cả loại đối tượng" },
	{ value: "USER_ACCESS_LEVEL", label: "Cấp truy cập người dùng" },
	{ value: "AREA_ACCESS_RULES", label: "Quy tắc truy cập khu vực" },
	{ value: "AREA_ASSIGNMENT", label: "Phân công nhân sự khu vực" },
	{ value: "LEVEL_PRESET", label: "Mặc định theo loại khu vực" },
	{ value: "AREA_EVENT_MODE", label: "Chế độ sự kiện" },
	{ value: "AREA_EVENT_SCHEDULE", label: "Lịch sự kiện" },
	{ value: "REASON_CATALOG", label: "Danh mục lý do" },
	{ value: "AREA", label: "Khu vực" },
	{ value: "ACCESS_REQUEST", label: "Yêu cầu truy cập" },
];

// Đối tượng của phân hệ GUEST — chỉ ADMIN được đọc (audit_module_roles, V60)
const TARGET_TYPE_OPTIONS_GUEST = [
	{ value: "GUEST_VISIT", label: "Lượt khách" },
	{ value: "GUEST", label: "Khách" },
];

// Đối tượng của phân hệ SECURITY — chỉ ADMIN được đọc (V81, V81_1)
const TARGET_TYPE_OPTIONS_SECURITY = [
	{ value: "FACE_PROFILE", label: "Ảnh khuôn mặt" },
	{ value: "SECURITY_EVIDENCE", label: "Ảnh bằng chứng sự cố" },
];

// Màu badge "Loại sự kiện" theo bảng màu của thiết kế (category-badge--*)
const TARGET_TYPE_CATEGORY = {
	USER_ACCESS_LEVEL: "purple",
	AREA_ACCESS_RULES: "purple",
	AREA_ASSIGNMENT: "purple",
	LEVEL_PRESET: "purple",
	AREA: "success",
	AREA_GEOMETRY: "success",
	AREA_CAMERAS: "success",
	AREA_EVENT_MODE: "success",
	AREA_EVENT_SCHEDULE: "success",
	ACCESS_REQUEST: "warning",
};

// "yyyy-MM-dd" theo giờ máy, dùng cho ô ngày và nút "Hôm nay"
const todayInputValue = () => {
	const d = new Date();
	const pad = (n) => String(n).padStart(2, "0");
	return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

export default function AuditLogPage() {
	const { user: currentUser } = useAuth();
	const isAdmin = currentUser?.role === ROLES.ADMIN;

	const [logs, setLogs] = useState([]);
	const [loadingLogs, setLoadingLogs] = useState(false);
	const [loadError, setLoadError] = useState(null);
	const [currentPage, setCurrentPage] = useState(0);
	const [totalPages, setTotalPages] = useState(0);
	const [totalElements, setTotalElements] = useState(0);
	const [pageSize] = useState(20);

	// Filters
	const [filterModule, setFilterModule] = useState("");
	const [filterCorrelationId, setFilterCorrelationId] = useState("");
	const [filterTargetType, setFilterTargetType] = useState("");
	const [filterAreaId, setFilterAreaId] = useState("");
	const [filterSubjectUser, setFilterSubjectUser] = useState(null);
	const [filterChangedByUser, setFilterChangedByUser] = useState(null);
	const [filterFrom, setFilterFrom] = useState("");
	const [filterTo, setFilterTo] = useState("");

	const [areasList, setAreasList] = useState([]);

	useEffect(() => {
		const fetchAreas = async () => {
			try {
				const res = await getAreas({ size: 100 });
				setAreasList(res?.content || []);
			} catch (err) {
				console.error("Lỗi tải danh sách khu vực:", err);
			}
		};
		fetchAreas();
	}, []);

	const loadAuditLogs = useCallback(
		async (page = 0, overrideCorrelationId = undefined) => {
			setLoadingLogs(true);
			setLoadError(null);
			try {
				const corrId =
					overrideCorrelationId !== undefined
						? overrideCorrelationId
						: filterCorrelationId;
				const params = {
					page,
					size: pageSize,
				};
				if (filterModule) params.module = filterModule;
				if (corrId) params.correlationId = corrId;
				if (filterTargetType) params.targetType = filterTargetType;
				if (filterAreaId) params.areaId = filterAreaId;
				if (filterSubjectUser?.id) params.subjectUserId = filterSubjectUser.id;
				if (filterChangedByUser?.id) params.changedBy = filterChangedByUser.id;
				// Ngày lọc là ngày theo giờ Việt Nam (UTC+7), không phải UTC
				if (filterFrom) params.from = `${filterFrom}T00:00:00+07:00`;
				if (filterTo) params.to = `${filterTo}T23:59:59+07:00`;

				const res = await getAuditLogs(params);
				setLogs(res?.content || []);
				setTotalPages(res?.totalPages || 0);
				setTotalElements(res?.totalElements || 0);
				setCurrentPage(page);
			} catch (err) {
				console.error("Lỗi tải nhật ký thay đổi:", err);
				const httpStatus = err?.status ?? err?.response?.status;
				if (httpStatus === 403) {
					setLoadError("Bạn không có quyền xem nhật ký của phân hệ này");
				} else {
					setLoadError(err?.message || "Không thể tải nhật ký phân quyền");
				}
				setLogs([]);
			} finally {
				setLoadingLogs(false);
			}
		},
		[
			filterModule,
			filterCorrelationId,
			filterTargetType,
			filterAreaId,
			filterSubjectUser,
			filterChangedByUser,
			filterFrom,
			filterTo,
			pageSize,
		],
	);

	useEffect(() => {
		loadAuditLogs(0);
	}, [loadAuditLogs]);

	const handleFilterByCorrelation = (correlationId) => {
		if (!correlationId) return;
		setFilterCorrelationId(correlationId);
		loadAuditLogs(0, correlationId);
	};

	const handleClearCorrelationFilter = () => {
		setFilterCorrelationId("");
		loadAuditLogs(0, "");
	};

	const hasActiveFilters = Boolean(
		filterModule ||
			filterCorrelationId ||
			filterTargetType ||
			filterAreaId ||
			filterFrom ||
			filterTo ||
			filterSubjectUser ||
			filterChangedByUser,
	);

	const handleResetFilters = () => {
		setFilterModule("");
		setFilterCorrelationId("");
		setFilterTargetType("");
		setFilterAreaId("");
		setFilterFrom("");
		setFilterTo("");
		setFilterSubjectUser(null);
		setFilterChangedByUser(null);
	};

	// Nút "Tất cả / Hôm nay": chỉ đặt khoảng ngày (from / to) gửi lên API
	const today = todayInputValue();
	const isTodayRange = filterFrom === today && filterTo === today;
	const isAllRange = !filterFrom && !filterTo;
	const handleSelectAllTime = () => {
		setFilterFrom("");
		setFilterTo("");
	};
	const handleSelectToday = () => {
		setFilterFrom(today);
		setFilterTo(today);
	};

	const renderCategoryBadge = (targetType) => {
		const variant = TARGET_TYPE_CATEGORY[targetType] || "default";
		return (
			<span className={`category-badge category-badge--${variant}`}>
				{TARGET_TYPE_LABELS[targetType] || targetType}
			</span>
		);
	};

	const getAuditReasonText = (log) => {
		const { targetType, action, oldValue, newValue } = log;
		let label = null;
		if (targetType === "AREA_EVENT_SCHEDULE") {
			const snap = newValue || oldValue || {};
			label =
				action === "CANCEL"
					? snap.cancelReasonLabel || snap.reasonLabel
					: snap.reasonLabel;
		} else if (
			["ENABLE_EVENT_MODE", "DISABLE_EVENT_MODE", "EXTEND_EVENT_MODE"].includes(
				action,
			)
		) {
			label = newValue?.reasonLabel || oldValue?.reasonLabel;
		}
		if (label && log.reason && log.reason !== label)
			return `${label} – ${log.reason}`;
		return log.reason || label || null;
	};

	const renderAuditChange = (log) => {
		const { targetType, action, oldValue, newValue } = log;

		if (action === "EXPIRE_EVENT_MODE") {
			const plannedEnd = newValue?.plannedEnd || oldValue?.plannedEnd;
			return (
				<div className="audit-detail-rules">
					<div className="audit-detail-row">
						<span className="audit-row-label">Kết thúc dự kiến:</span>
						<strong className="audit-val--new">
							{plannedEnd ? formatDateTime(plannedEnd) : "—"}
						</strong>
					</div>
				</div>
			);
		}

		if (targetType === "AREA_EVENT_SCHEDULE") {
			const snap = newValue || oldValue || {};
			const formatRange = (v) =>
				v?.startAt || v?.endAt
					? `${v?.startAt ? formatDateTime(v.startAt) : "—"} → ${v?.endAt ? formatDateTime(v.endAt) : "—"}`
					: "—";
			const oldRange = formatRange(oldValue);
			const newRange = formatRange(newValue);
			const statusLabels = {
				SCHEDULED: "Đã lên lịch",
				STARTED: "Đã bắt đầu",
				COMPLETED: "Đã kết thúc",
				ENDED_EARLY: "Kết thúc sớm",
				CANCELLED: "Đã hủy",
				FAILED: "Thất bại",
			};
			const oldStatus = oldValue?.status;
			const newStatus = newValue?.status;
			// V87: tên sự kiện (audit trước V87 không có khoá title)
			const oldTitle = oldValue?.title;
			const newTitle = newValue?.title;

			return (
				<div className="audit-detail-rules">
					{(oldTitle || newTitle) && (
						<div className="audit-detail-row">
							<span className="audit-row-label">Tên sự kiện:</span>
							{oldTitle && newTitle && oldTitle !== newTitle ? (
								<>
									<span>{oldTitle}</span>
									<span className="audit-arrow">→</span>
									<strong className="audit-val--new">{newTitle}</strong>
								</>
							) : (
								<strong className="audit-val--new">{newTitle || oldTitle}</strong>
							)}
						</div>
					)}
					<div className="audit-detail-row">
						<span className="audit-row-label">Thời gian:</span>
						{oldValue && newValue && oldRange !== newRange ? (
							<>
								<span>{oldRange}</span>
								<span className="audit-arrow">→</span>
								<strong className="audit-val--new">{newRange}</strong>
							</>
						) : (
							<strong className="audit-val--new">
								{newValue ? newRange : oldRange}
							</strong>
						)}
					</div>
					{(oldStatus || newStatus) && (
						<div className="audit-detail-row">
							<span className="audit-row-label">Trạng thái:</span>
							{oldStatus && newStatus && oldStatus !== newStatus ? (
								<>
									<span>{statusLabels[oldStatus] || oldStatus}</span>
									<span className="audit-arrow">→</span>
									<strong className="audit-val--new">
										{statusLabels[newStatus] || newStatus}
									</strong>
								</>
							) : (
								<strong className="audit-val--new">
									{statusLabels[newStatus || oldStatus] ||
										newStatus ||
										oldStatus}
								</strong>
							)}
						</div>
					)}
					{action === "FAIL" && snap.failReason && (
						<div className="audit-detail-row">
							<span className="audit-row-label">Lý do lỗi:</span>
							<span className="audit-val--fail">{snap.failReason}</span>
						</div>
					)}
				</div>
			);
		}

		if (targetType === "USER_ACCESS_LEVEL") {
			const oldLvl = oldValue?.accessLevel;
			const newLvl = newValue?.accessLevel;
			return (
				<div className="audit-detail-rules">
					<div className="audit-detail-row">
						<span className="audit-row-label">Cấp độ:</span>
						{oldLvl !== undefined && newLvl !== undefined ? (
							<>
								<span>Cấp {oldLvl}</span>
								<span className="audit-arrow">→</span>
								<strong className="audit-val--new">Cấp {newLvl}</strong>
							</>
						) : (
							<strong className="audit-val--new">Cấp {newLvl ?? oldLvl}</strong>
						)}
					</div>
				</div>
			);
		}

		if (targetType === "AREA_ACCESS_RULES" || targetType === "LEVEL_PRESET") {
			const oldLvl = oldValue?.areaAccessLevel ?? oldValue?.accessLevel;
			const newLvl = newValue?.areaAccessLevel ?? newValue?.accessLevel;
			const oldExp = oldValue?.explicitAuthorizationRequired;
			const newExp = newValue?.explicitAuthorizationRequired;
			// V83: cờ chỉ định đã bỏ; chỉ bản ghi cũ còn trường này
			const hasExplicit = oldExp !== undefined || newExp !== undefined;
			// Bản ghi gộp loại V83 có areaLevel (tên loại cũ hiển thị "Bảo mật (cũ: ...)")
			const oldType = oldValue?.areaLevel;
			const newType = newValue?.areaLevel;

			return (
				<div className="audit-detail-rules">
					{(oldType || newType) && (
						<div className="audit-detail-row">
							<span className="audit-row-label">Loại khu vực:</span>
							{oldType && newType && oldType !== newType ? (
								<>
									<span>{getLevelConfig(oldType).name}</span>
									<span className="audit-arrow">→</span>
									<strong className="audit-val--new">{getLevelConfig(newType).name}</strong>
								</>
							) : (
								<strong className="audit-val--new">{getLevelConfig(newType || oldType).name}</strong>
							)}
						</div>
					)}
					<div className="audit-detail-row">
						<span className="audit-row-label">Cấp truy cập:</span>
						{oldLvl !== undefined &&
						newLvl !== undefined &&
						oldLvl !== newLvl ? (
							<>
								<span>Cấp {oldLvl}</span>
								<span className="audit-arrow">→</span>
								<strong className="audit-val--new">Cấp {newLvl}</strong>
							</>
						) : (
							<strong className="audit-val--new">
								Cấp {newLvl ?? oldLvl ?? "—"}
							</strong>
						)}
					</div>
					{hasExplicit && (
					<div className="audit-detail-row">
						<span className="audit-row-label">Yêu cầu chỉ định:</span>
						{oldExp !== undefined &&
						newExp !== undefined &&
						oldExp !== newExp ? (
							<>
								<span>{oldExp ? "Bật" : "Tắt"}</span>
								<span className="audit-arrow">→</span>
								<strong className="audit-val--new">
									{newExp ? "Bật" : "Tắt"}
								</strong>
							</>
						) : (
							<strong className="audit-val--new">
								{(newExp ?? oldExp) ? "Bật" : "Tắt"}
							</strong>
						)}
					</div>
					)}
				</div>
			);
		}

		// Default JSON fallback summary
		const snap = newValue || oldValue;
		if (!snap) return null;
		return (
			<div className="audit-detail-rules">
				<span className="audit-val--json-summary">
					{typeof snap === "object" ? summarizeSnapshotFields(snap) : String(snap)}
				</span>
			</div>
		);
	};

	return (
		<div className="audit-log-page">
			<PageHeader
				title="Nhật ký kiểm toán"
				description="Theo dõi toàn bộ lịch sử thay đổi phân quyền, quy tắc khu vực và các thao tác trong hệ thống."
			/>

			{filterCorrelationId && (
				<div className="audit-correlation-banner">
					<div className="audit-correlation-banner__content">
						<Link size={15} />
						<span>Đang lọc theo mã thao tác (Correlation ID):</span>
						<span className="audit-correlation-banner__id">
							{filterCorrelationId}
						</span>
					</div>
					<button
						type="button"
						className="audit-correlation-clear-btn"
						onClick={handleClearCorrelationFilter}
					>
						<X size={13} />
						<span>Xóa lọc</span>
					</button>
				</div>
			)}

			<div className="section-card">
				{/* Filter Bar */}
				<div className="audit-filter-bar">
					<div className="audit-user-filters">
						<div className="audit-user-filter">
							<span className="audit-user-filter__label">Người thực hiện</span>
							<UserSearchCombobox
								selectedUser={filterChangedByUser}
								onSelect={(u) => setFilterChangedByUser(u)}
								onClear={() => setFilterChangedByUser(null)}
								placeholder="Tìm theo tên/mã người thực hiện..."
								ariaLabel="Lọc theo người thực hiện"
							/>
						</div>
						<div className="audit-user-filter">
							<span className="audit-user-filter__label">
								Người bị tác động
							</span>
							<UserSearchCombobox
								selectedUser={filterSubjectUser}
								onSelect={(u) => setFilterSubjectUser(u)}
								onClear={() => setFilterSubjectUser(null)}
								placeholder="Tìm theo tên/mã người bị tác động..."
								ariaLabel="Lọc theo người bị tác động"
							/>
						</div>
					</div>

					<div className="audit-filter-controls">
						<Select
							className="ui-select-group--filter"
							aria-label="Phân hệ"
							value={filterModule}
							onChange={(e) => setFilterModule(e.target.value)}
							options={isAdmin ? MODULE_OPTIONS_ADMIN : MODULE_OPTIONS_FM}
						/>

						<Select
							className="ui-select-group--filter"
							aria-label="Loại đối tượng"
							value={filterTargetType}
							onChange={(e) => setFilterTargetType(e.target.value)}
							options={
								isAdmin
									? [...TARGET_TYPE_OPTIONS, ...TARGET_TYPE_OPTIONS_GUEST, ...TARGET_TYPE_OPTIONS_SECURITY]
									: TARGET_TYPE_OPTIONS
							}
						/>

						<Select
							className="ui-select-group--filter"
							aria-label="Khu vực"
							value={filterAreaId}
							onChange={(e) => setFilterAreaId(e.target.value)}
							options={[
								{ value: "", label: "Tất cả khu vực" },
								...areasList.map((a) => {
									const loc = formatLocation(a.building, a.floor);
									return { value: a.id, label: loc ? `${a.name} (${loc})` : a.name };
								}),
							]}
						/>

						<div className="audit-date-range">
							<label
								className="audit-date-label"
								htmlFor="audit-filter-from"
							>
								Từ
							</label>
							<input
								id="audit-filter-from"
								type="date"
								className="audit-date-input"
								value={filterFrom}
								onChange={(e) => setFilterFrom(e.target.value)}
							/>
							<label
								className="audit-date-label"
								htmlFor="audit-filter-to"
							>
								Đến
							</label>
							<input
								id="audit-filter-to"
								type="date"
								className="audit-date-input"
								value={filterTo}
								onChange={(e) => setFilterTo(e.target.value)}
							/>
						</div>

						<div className="audit-time-toggle">
							<button
								type="button"
								className={`toggle-btn ${isAllRange ? "active" : ""}`}
								onClick={handleSelectAllTime}
							>
								Tất cả
							</button>
							<button
								type="button"
								className={`toggle-btn ${isTodayRange ? "active" : ""}`}
								onClick={handleSelectToday}
							>
								Hôm nay
							</button>
						</div>

						<div className="audit-filter-actions">
							<button
								type="button"
								className="audit-clear-btn"
								onClick={handleResetFilters}
								disabled={!hasActiveFilters}
								title={hasActiveFilters ? "Xóa tất cả bộ lọc đang chọn" : "Chưa có bộ lọc nào"}
							>
								<X size={16} />
								<span>Xóa bộ lọc</span>
							</button>

							<button
								type="button"
								className="audit-refresh-btn"
								aria-label="Làm mới"
								title="Làm mới"
								onClick={() => loadAuditLogs(currentPage)}
								disabled={loadingLogs}
							>
								<RotateCcw
									size={16}
									className={loadingLogs ? "animate-spin" : ""}
								/>
							</button>
						</div>
					</div>
				</div>

				{/* Table */}
				{loadingLogs ? (
					<LoadingState text="Đang tải nhật ký thay đổi..." />
				) : loadError ? (
					<ErrorState
						message={loadError}
						onRetry={() => loadAuditLogs(currentPage)}
					/>
				) : logs.length === 0 ? (
					<EmptyState
						icon={History}
						title="Không tìm thấy bản ghi nhật ký phù hợp"
						description="Thử thay đổi bộ lọc tìm kiếm để xem kết quả khác."
					/>
				) : (
					<>
						<div className="table-wrapper">
							<table className="data-table">
								<thead>
									<tr>
										<th>THỜI GIAN</th>
										<th>NGƯỜI THỰC HIỆN</th>
										<th>LOẠI SỰ KIỆN</th>
										<th>HÀNH ĐỘNG & CHI TIẾT</th>
										<th>ĐỐI TƯỢNG</th>
									</tr>
								</thead>
								<tbody>
									{logs.map((log) => {
										const reasonText = getAuditReasonText(log);
										const changeDetail = renderAuditChange(log);
										const isSystemActor =
											log.actorType === "SYSTEM" || !log.changedByName;
										return (
											<tr key={log.id}>
												<td>
													<div className="flex-col-stack">
														<span className="text-muted">
															{formatDate(log.changedAt)}
														</span>
														<span>{formatTime(log.changedAt)}</span>
													</div>
												</td>
												<td>
													{isSystemActor ? (
														<div className="flex-col-stack">
															<span className="font-semibold audit-system-actor">
																{renderSystemActor(log.actorSource)}
															</span>
														</div>
													) : (
														<div className="flex-col-stack">
															<span className="font-semibold">
																{log.changedByName}
															</span>
															{log.changedByUserCode && (
																<span className="text-muted">
																	{log.changedByUserCode}
																</span>
															)}
														</div>
													)}
												</td>
												<td>{renderCategoryBadge(log.targetType)}</td>
												<td>
													<div className="flex-col-stack">
														<span className="font-semibold">
															{getActionLabel(log.targetType, log.action)}
														</span>
														{changeDetail}
														{reasonText && (
															<span
																className="text-muted-wrap"
																title={reasonText}
															>
																Lý do: {reasonText}
															</span>
														)}
													</div>
												</td>
												<td>
													{log.areaName || log.subjectUserName ? (
														<div className="flex-col-stack">
															{log.areaName && (
																<>
																	<span className="font-semibold">
																		Khu vực:
																	</span>
																	<span className="text-muted-wrap">
																		{log.areaName}
																	</span>
																</>
															)}
															{log.subjectUserName && (
																<>
																	<span className="font-semibold">
																		Người dùng:
																	</span>
																	<span className="text-muted-wrap">
																		{log.subjectUserName}
																		{log.subjectUserCode
																			? ` (${log.subjectUserCode})`
																			: ""}
																	</span>
																</>
															)}
														</div>
													) : (
														<span className="text-muted">—</span>
													)}
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
									Trang {currentPage + 1} / {totalPages} ({totalElements} bản
									ghi)
								</span>
								<div className="pagination-actions">
									<Button
										variant="outline"
										size="sm"
										disabled={currentPage === 0 || loadingLogs}
										onClick={() => loadAuditLogs(currentPage - 1)}
									>
										Trang trước
									</Button>
									<Button
										variant="outline"
										size="sm"
										disabled={currentPage >= totalPages - 1 || loadingLogs}
										onClick={() => loadAuditLogs(currentPage + 1)}
									>
										Trang sau
									</Button>
								</div>
							</div>
						)}
					</>
				)}
			</div>
		</div>
	);
}
