import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQueries, useQuery } from "@tanstack/react-query";
import { format, formatDistanceToNow, parseISO } from "date-fns";
import { vi } from "date-fns/locale";
import {
	MapPin,
	Camera,
	AlertTriangle,
	Calendar,
	Users,
	Layers,
	History,
	ArrowRight,
	Siren,
	ShieldAlert,
	ShieldCheck,
	ShieldOff,
	Percent,
	ChartColumnStacked,
	MapPinned,
	FileText,
	Film,
	UserCheck,
	DoorOpen,
} from "lucide-react";
import {
	Bar,
	BarChart,
	CartesianGrid,
	Legend,
	ResponsiveContainer,
	Tooltip,
	XAxis,
	YAxis,
} from "recharts";
import { useAuth } from "../context/AuthContext";
import { ROLES } from "../constants/roles";
import { apiGet } from "../api/apiClient";
import { getAreas } from "../services/areaService";
import { getAuditLogs } from "../services/accessControlService";
import { searchAccessHistory } from "../services/accessHistoryService";
import { fetchDiskRecordings } from "../services/recordingService";
import { getGuardsForChat } from "../services/guardChatService";
import {
	DEFAULT_SECURITY_STATS_RANGE,
	SECURITY_STATS_RANGES,
	getSecurityStats,
} from "../services/dashboardService";
import { AREA_LEVEL_OPTIONS } from "../utils/areaHelpers";
import {
	ACCESS_RESULT_META,
	CANONICAL_ACCESS_RESULTS,
	placeholderUserCodeLabel,
} from "../utils/accessHistoryHelpers";
import {
	INCIDENT_EVENT_LABELS,
	INCIDENT_SEVERITY_META,
	INCIDENT_STATUS_META,
	OPERATIONAL_STATUS_META,
	SOC_CLEARED_STORAGE_KEY,
	SOC_CLEARED_UPDATED_EVENT,
	clearSocStoredSets,
	filterOutClearedIncidents,
	isIncidentPendingStatus,
	readSocClearedBefore,
	readSocStoredSet,
	summarizeGuardRoster,
	summarizeIncidents,
	summarizeRecordings,
	writeSocClearedBefore,
} from "../utils/dashboardHelpers";
import { formatDateTime, formatRange } from "../utils/formatDateTime";
import { TARGET_TYPE_LABELS, getActionLabel, renderSystemActor } from "../utils/auditLabels";
import { Button, EmptyState, ErrorState, LoadingState, PageHeader, StatusTabs } from "../components/ui";
import ReportFilterModal from "../components/report/ReportFilterModal";
import ReportPreviewModal from "../components/report/ReportPreviewModal";
import "../styles/DashboardPage.css";

// Phụ đề theo role: nói đúng những phần role đó được xem, không lặp lại tiêu đề "Tổng quan".
// NORMAL_USER không vào trang này (DashboardRoute chuyển sang /access-requests); role khác dùng câu mặc định.
const DASHBOARD_DESCRIPTIONS = {
	[ROLES.ADMIN]: "Khu vực, camera, sự cố, tài khoản và hoạt động quản trị gần đây",
	[ROLES.FACILITY_MANAGER]: "Khu vực, camera, thống kê an ninh và hoạt động quản trị gần đây",
	[ROLES.GUARD]: "Camera và sự cố cần theo dõi trong ca",
};
const DEFAULT_DASHBOARD_DESCRIPTION = "Số liệu an ninh trong phạm vi vai trò của bạn";

// Màu cột theo loại khu vực: token chữ của badge loại khu vực (theme.css), cùng màu với .level-badge--*
const AREA_TYPE_BAR_CLASS = {
	PUBLIC: "area-type-bar__fill--public",
	CONFIDENTIAL: "area-type-bar__fill--confidential",
	HIGHLY_CONFIDENTIAL: "area-type-bar__fill--highly-confidential",
};

const RECENT_ACTIVITY_SIZE = 8;

// Thống kê an ninh (GET /api/dashboard/security-stats). Khoảng nhớ trên URL (?statsRange=...).
const STATS_RANGE_PARAM = "statsRange";
const SECURITY_RANGE_TABS = [
	{ key: "TODAY", label: "Hôm nay" },
	{ key: "LAST_7_DAYS", label: "7 ngày" },
	{ key: "LAST_30_DAYS", label: "30 ngày" },
];

// Nhãn trạng thái sự cố lấy theo app bảo vệ (mobile): "Chờ xử lý" / "Đang xử lý" (huy hiệu danh sách sự cố),
// "Đã xác minh" (dòng kết quả đã giải quyết), "Báo động giả / Nhầm" (lựa chọn kết luận DISMISSED).
const INCIDENT_STATUS_CARDS = [
	{ code: "NEW", label: "Chờ xử lý", icon: Siren, subtext: "Chưa có bảo vệ tiếp nhận" },
	{ code: "CLAIMED", label: "Đang xử lý", icon: ShieldAlert, subtext: "Bảo vệ đã tiếp nhận, chưa kết luận" },
	{ code: "RESOLVED_VERIFIED", label: "Đã xác minh", icon: ShieldCheck, subtext: "Kết luận có sự cố thật" },
	{ code: "RESOLVED_DISMISSED", label: "Báo động giả / Nhầm", icon: ShieldOff, subtext: "Kết luận không có sự cố" },
];

// Màu cột theo họ màu badge của ACCESS_RESULT_META (success / danger / warning / neutral).
// Hai kết quả cùng tông danger: "Sai quyền" dùng --theme-danger, "Ngoài giờ" dùng sắc đậm --theme-danger-text.
const ACCESS_RESULT_FILL = {
	AUTHORIZED_PERSON: "var(--theme-success)",
	UNAUTHORIZED_PERSON: "var(--theme-danger)",
	UNKNOWN_PERSON: "var(--theme-text-muted)",
	PERSON_INTRUSION: "var(--theme-danger-text)",
	SUSPICIOUS_MOVING_OBJECT: "var(--theme-warning)",
};
// Chỉ vẽ 5 nhãn chuẩn (03/11); dữ liệu tên cũ đã được V95 đổi tên
const ACCESS_RESULT_CODES = CANONICAL_ACCESS_RESULTS;
// recharts mặc định sắp legend / tooltip theo tên; giữ thứ tự của ACCESS_RESULT_META
const byAccessResultOrder = (item) => ACCESS_RESULT_CODES.indexOf(item?.dataKey);

const percentFormat = new Intl.NumberFormat("vi-VN", { style: "percent", maximumFractionDigits: 1 });

const totalOf = (page) => (typeof page?.totalElements === "number" ? page.totalElements : null);

function DashboardPanel({ icon: Icon, title, subtitle, action, children }) {
	return (
		<section className="dash-card">
			<header className="dash-card__header">
				<div className="dash-card__title-wrap">
					<span className="dash-card__icon-box" aria-hidden="true">
						<Icon size={18} />
					</span>
					<div>
						<h2 className="dash-card__title">{title}</h2>
						{subtitle && <p className="dash-card__subtitle">{subtitle}</p>}
					</div>
				</div>
				{action}
			</header>
			{children}
		</section>
	);
}

function AreaTypePanel({ queries }) {
	const loading = queries.some((q) => q.isLoading);
	const failed = queries.find((q) => q.isError);
	const rows = AREA_LEVEL_OPTIONS.map((level, i) => ({
		code: level.code,
		name: level.name,
		count: totalOf(queries[i].data) ?? 0,
	}));
	const total = rows.reduce((sum, r) => sum + r.count, 0);
	const ready = !loading && !failed;

	let body;
	if (loading) {
		body = <LoadingState size="sm" text="Đang tải số khu vực..." />;
	} else if (failed) {
		body = (
			<ErrorState
				size="sm"
				title="Không tải được số khu vực theo loại"
				message={failed.error?.message}
				onRetry={() => queries.forEach((q) => q.refetch())}
				retrying={queries.some((q) => q.isFetching)}
			/>
		);
	} else if (total === 0) {
		body = <EmptyState size="sm" icon={MapPin} title="Chưa có khu vực đang hoạt động" />;
	} else {
		body = (
			<ul className="area-type-bars" aria-label="Số khu vực đang hoạt động theo loại">
				{rows.map((r) => {
					const pct = Math.round((r.count / total) * 100);
					return (
						<li key={r.code} className="area-type-bar" title={`${r.name}: ${r.count} khu vực (${pct}%)`}>
							<div className="area-type-bar__meta">
								<span className="area-type-bar__name">{r.name}</span>
								<span className="area-type-bar__value">
									{r.count}
									<span className="area-type-bar__pct"> · {pct}%</span>
								</span>
							</div>
							<div className="area-type-bar__track" aria-hidden="true">
								<div
									className={`area-type-bar__fill ${AREA_TYPE_BAR_CLASS[r.code] ?? ""}`}
									style={{ width: `${(r.count / total) * 100}%` }}
								/>
							</div>
						</li>
					);
				})}
			</ul>
		);
	}

	return (
		<DashboardPanel
			icon={Layers}
			title="Khu vực theo loại"
			subtitle={ready && total > 0 ? `${total} khu vực đang hoạt động` : "Khu vực đang hoạt động"}
		>
			{body}
		</DashboardPanel>
	);
}

function auditActor(log) {
	return log.actorType === "SYSTEM" ? renderSystemActor(log.actorSource) : log.changedByName || "—";
}

function auditObject(log) {
	const type = TARGET_TYPE_LABELS[log.targetType] || log.targetType;
	const name = log.areaName || log.subjectUserName;
	return name ? `${type} · ${name}` : type;
}

function RecentActivityPanel({ query }) {
	const logs = query.data?.content ?? [];

	let body;
	if (query.isLoading) {
		body = <LoadingState size="sm" text="Đang tải hoạt động..." />;
	} else if (query.isError) {
		body = (
			<ErrorState
				size="sm"
				title="Không tải được hoạt động gần đây"
				message={query.error?.message}
				onRetry={() => query.refetch()}
				retrying={query.isFetching}
			/>
		);
	} else if (logs.length === 0) {
		body = <EmptyState size="sm" icon={History} title="Chưa có hoạt động quản trị" />;
	} else {
		body = (
			<ul className="activity-list">
				{logs.map((log) => (
					<li key={log.id} className="activity-item">
						<div className="activity-item__main">
							<span className="activity-item__action">{getActionLabel(log.targetType, log.action)}</span>
							<span className="activity-item__object">{auditObject(log)}</span>
						</div>
						<div className="activity-item__meta">
							<span className="activity-item__actor">{auditActor(log)}</span>
							<time
								className="activity-item__time"
								dateTime={log.changedAt}
								title={formatDateTime(log.changedAt)}
							>
								{formatDistanceToNow(new Date(log.changedAt), { addSuffix: true, locale: vi })}
							</time>
						</div>
					</li>
				))}
			</ul>
		);
	}

	return (
		<DashboardPanel
			icon={History}
			title="Hoạt động quản trị gần đây"
			subtitle={`${RECENT_ACTIVITY_SIZE} thao tác mới nhất`}
			action={
				<Link to="/admin/audit-logs" className="dash-card__link">
					Xem tất cả
					<ArrowRight size={14} aria-hidden="true" />
				</Link>
			}
		>
			{body}
		</DashboardPanel>
	);
}

function TopIncidentAreasPanel({ areas }) {
	const max = areas.reduce((m, a) => Math.max(m, a.count), 0);

	return (
		<DashboardPanel icon={MapPinned} title="Khu vực nhiều sự cố nhất" subtitle="Tối đa 5 khu trong khoảng đã chọn">
			{areas.length === 0 ? (
				<EmptyState size="sm" icon={MapPinned} title="Không có sự cố trong khoảng này" />
			) : (
				<ol className="area-type-bars" aria-label="Số sự cố theo khu vực">
					{areas.map((a) => {
						const name = a.areaName ?? a.cameraName ?? "Không xác định khu vực";
						return (
							<li key={a.areaId ?? "unknown"} className="area-type-bar" title={`${name}: ${a.count} sự cố`}>
								<div className="area-type-bar__meta">
									<span className="area-type-bar__name">{name}</span>
									<span className="area-type-bar__value">{a.count}</span>
								</div>
								<div className="area-type-bar__track" aria-hidden="true">
									<div
										className="area-type-bar__fill area-type-bar__fill--incident"
										style={{ width: `${max > 0 ? (a.count / max) * 100 : 0}%` }}
									/>
								</div>
							</li>
						);
					})}
				</ol>
			)}
		</DashboardPanel>
	);
}

function AccessResultsPanel({ days }) {
	const rows = days.map((d) => ({
		date: d.date,
		label: format(parseISO(d.date), "dd/MM"),
		...d.counts,
	}));
	const total = days.reduce(
		(sum, d) => sum + ACCESS_RESULT_CODES.reduce((s, code) => s + (d.counts?.[code] ?? 0), 0),
		0,
	);

	return (
		<DashboardPanel
			icon={ChartColumnStacked}
			title="Lượt vào khu vực theo ngày"
			subtitle={total > 0 ? `${total} lượt, chia theo kết quả kiểm soát` : "Chia theo kết quả kiểm soát"}
		>
			{total === 0 ? (
				<EmptyState size="sm" icon={ChartColumnStacked} title="Không có lượt vào nào trong khoảng này" />
			) : (
				<div className="dash-stats__chart">
					<ResponsiveContainer width="100%" height="100%">
						<BarChart data={rows} margin={{ top: 8, right: 8, left: -16, bottom: 0 }}>
							<CartesianGrid vertical={false} stroke="var(--theme-border)" />
							<XAxis
								dataKey="label"
								tick={{ fill: "var(--theme-text-muted)", fontSize: 12 }}
								tickLine={false}
								axisLine={{ stroke: "var(--theme-border)" }}
							/>
							<YAxis
								allowDecimals={false}
								tick={{ fill: "var(--theme-text-muted)", fontSize: 12 }}
								tickLine={false}
								axisLine={false}
							/>
							<Tooltip
								cursor={{ fill: "var(--theme-bg-surface-elevated)" }}
								contentStyle={{
									background: "var(--theme-bg-surface)",
									border: "1px solid var(--theme-border)",
									borderRadius: 4,
									color: "var(--theme-text-primary)",
									fontSize: 12,
								}}
								labelStyle={{ color: "var(--theme-text-primary)", fontWeight: 600 }}
								itemSorter={byAccessResultOrder}
								labelFormatter={(_, payload) =>
									payload?.[0]?.payload?.date
										? format(parseISO(payload[0].payload.date), "dd/MM/yyyy")
										: ""
								}
							/>
							<Legend
								wrapperStyle={{ fontSize: 12, color: "var(--theme-text-secondary)" }}
								itemSorter={byAccessResultOrder}
							/>
							{ACCESS_RESULT_CODES.map((code) => (
								<Bar
									key={code}
									dataKey={code}
									name={ACCESS_RESULT_META[code].label}
									stackId="access"
									fill={ACCESS_RESULT_FILL[code]}
									maxBarSize={40}
								/>
							))}
						</BarChart>
					</ResponsiveContainer>
				</div>
			)}
		</DashboardPanel>
	);
}

function SecurityStatsSection() {
	const [searchParams, setSearchParams] = useSearchParams();
	const rawRange = searchParams.get(STATS_RANGE_PARAM);
	const range = SECURITY_STATS_RANGES.includes(rawRange) ? rawRange : DEFAULT_SECURITY_STATS_RANGE;

	const statsQuery = useQuery({
		queryKey: ["dashboard", "security-stats", range],
		queryFn: () => getSecurityStats(range),
	});
	const stats = statsQuery.data;

	const changeRange = (next) => {
		setSearchParams(
			(prev) => {
				const params = new URLSearchParams(prev);
				params.set(STATS_RANGE_PARAM, next);
				return params;
			},
			{ replace: true },
		);
	};

	let body;
	if (statsQuery.isLoading) {
		body = <LoadingState size="sm" text="Đang tải thống kê an ninh..." />;
	} else if (statsQuery.isError) {
		body = (
			<ErrorState
				size="sm"
				title="Không tải được thống kê an ninh"
				message={statsQuery.error?.message}
				onRetry={() => statsQuery.refetch()}
				retrying={statsQuery.isFetching}
			/>
		);
	} else {
		const byStatus = stats?.incidentsByStatus ?? {};
		const rate = stats?.dismissedRate;
		const resolvedCount = stats?.resolvedCount ?? 0;
		const cards = [
			...INCIDENT_STATUS_CARDS.map((c) => ({
				id: c.code,
				label: c.label,
				icon: c.icon,
				value: byStatus[c.code] ?? 0,
				subtext: c.subtext,
			})),
			{
				id: "dismissed-rate",
				label: "Tỉ lệ bác bỏ",
				icon: Percent,
				value: typeof rate === "number" ? percentFormat.format(rate) : "—",
				subtext: `trên ${resolvedCount} sự cố đã kết luận`,
			},
		];
		body = (
			<>
				<div className="dashboard-kpis dash-stats__kpis">
					{cards.map((card) => {
						const IconComponent = card.icon;
						return (
							<article key={card.id} className="kpi-card">
								<div className="kpi-card__top">
									<span className="kpi-card__icon">
										<IconComponent size={20} />
									</span>
									<span className="kpi-card__badge">Sự cố</span>
								</div>
								<div className="kpi-card__body">
									<span className="kpi-card__label">{card.label}</span>
									<span className="kpi-card__value">{card.value}</span>
								</div>
								<div className="kpi-card__footer">
									<span className="kpi-card__subtext">{card.subtext}</span>
								</div>
							</article>
						);
					})}
				</div>
				<div className="dashboard-grid">
					<TopIncidentAreasPanel areas={stats?.topAreas ?? []} />
					<AccessResultsPanel days={stats?.accessResultsByDay ?? []} />
				</div>
			</>
		);
	}

	return (
		<section className="dash-stats" aria-labelledby="dash-stats-title">
			<header className="dash-stats__header">
				<div>
					<h2 id="dash-stats-title" className="dash-stats__title">
						Thống kê an ninh
					</h2>
					<p className="dash-stats__subtitle">
						{stats && !statsQuery.isLoading
							? `Sự cố theo thời điểm phát hiện, ${formatRange(stats.from, stats.to)}`
							: "Sự cố và lượt vào khu vực trong khoảng đã chọn"}
					</p>
				</div>
				<StatusTabs
					items={SECURITY_RANGE_TABS}
					value={range}
					onChange={changeRange}
					ariaLabel="Khoảng thời gian thống kê"
				/>
			</header>
			{body}
		</section>
	);
}

function RecentIncidentsPanel({
	query,
	summary,
	canOpenGuardCenter,
	hasCleared = false,
	onClearResolved,
	onRestoreCleared,
}) {
	const hasVisibleResolved = summary.recent.some((inc) => !isIncidentPendingStatus(inc?.status));
	let body;
	if (query.isLoading) {
		body = <LoadingState size="sm" text="Đang tải danh sách sự cố..." />;
	} else if (query.isError) {
		body = (
			<ErrorState
				size="sm"
				title="Không tải được sự cố gần đây"
				message={query.error?.message}
				onRetry={() => query.refetch()}
				retrying={query.isFetching}
			/>
		);
	} else if (summary.recent.length === 0) {
		body = <EmptyState size="sm" icon={AlertTriangle} title="Chưa có sự cố an ninh nào" />;
	} else {
		body = (
			<>
				{summary.byEventType.length > 0 && (
					<div className="dash-chip-row" aria-label="Phân bổ theo loại sự cố">
						{summary.byEventType.map((item) => (
							<span key={item.code} className="dash-chip">
								<span>{item.label}</span>
								<strong>{item.count}</strong>
							</span>
						))}
					</div>
				)}
				<ul className="activity-list">
					{summary.recent.map((inc) => {
						const sev = INCIDENT_SEVERITY_META[inc.severity] || { label: inc.severity || "—", tone: "neutral" };
						const st = INCIDENT_STATUS_META[inc.status] || { label: inc.status || "—", tone: "neutral" };
						const locationLabel = [inc.areaName, inc.building, inc.cameraName || inc.cameraCode]
							.filter(Boolean)
							.join(" · ");
						return (
							<li key={inc.id} className="activity-item">
								<div className="activity-item__main">
									<span className="activity-item__action">
										{INCIDENT_EVENT_LABELS[inc.eventType] || inc.eventType}
									</span>
									<span className="activity-item__object">{locationLabel || "Chưa rõ vị trí"}</span>
									<div className="dash-row-tags">
										<span className={`dash-pill dash-pill--${sev.tone}`}>{sev.label}</span>
										<span className={`dash-pill dash-pill--${st.tone}`}>{st.label}</span>
									</div>
								</div>
								<div className="activity-item__meta">
									<span className="activity-item__actor">
										{inc.resolvedByName || inc.claimedByName || "Chưa tiếp nhận"}
									</span>
									{inc.detectedAt && (
										<time
											className="activity-item__time"
											dateTime={inc.detectedAt}
											title={formatDateTime(inc.detectedAt)}
										>
											{formatDistanceToNow(new Date(inc.detectedAt), { addSuffix: true, locale: vi })}
										</time>
									)}
								</div>
							</li>
						);
					})}
				</ul>
			</>
		);
	}

	return (
		<DashboardPanel
			icon={Siren}
			title="Sự cố an ninh gần đây"
			subtitle={
				summary.total > 0
					? `${summary.total} sự cố · ${summary.criticalCount} nghiêm trọng · ${summary.pendingCount} chưa xử lý xong`
					: "Theo dõi cảnh báo mới nhất"
			}
			action={
				canOpenGuardCenter ? (
					<div className="dash-card__actions">
						{hasVisibleResolved && onClearResolved && (
							<button
								type="button"
								className="dash-card__action-btn"
								onClick={onClearResolved}
								title="Ẩn các sự cố đã xử lý khỏi bảng trực và Tổng quan"
							>
								Dọn dẹp
							</button>
						)}
						{hasCleared && onRestoreCleared && (
							<button
								type="button"
								className="dash-card__action-btn"
								onClick={onRestoreCleared}
								title="Khôi phục toàn bộ sự cố đã ẩn"
							>
								Khôi phục
							</button>
						)}
						<Link to="/guard" className="dash-card__link">
							Mở trung tâm
							<ArrowRight size={14} aria-hidden="true" />
						</Link>
					</div>
				) : undefined
			}
		>
			{body}
		</DashboardPanel>
	);
}

function CamerasOverviewPanel({ query, manageLink, manageLabel }) {
	const cameras = query.data?.content ?? [];
	const total = totalOf(query.data) ?? cameras.length;

	let body;
	if (query.isLoading) {
		body = <LoadingState size="sm" text="Đang tải danh sách camera..." />;
	} else if (query.isError) {
		body = (
			<ErrorState
				size="sm"
				title="Không tải được camera"
				message={query.error?.message}
				onRetry={() => query.refetch()}
				retrying={query.isFetching}
			/>
		);
	} else if (cameras.length === 0) {
		body = <EmptyState size="sm" icon={Camera} title="Chưa có camera đang bật" />;
	} else {
		body = (
			<ul className="activity-list">
				{cameras.slice(0, 8).map((cam) => {
					const op = OPERATIONAL_STATUS_META[cam.operationalStatus] || {
						label: cam.operationalStatus || "Không rõ",
						tone: "neutral",
					};
					const loc = [cam.areaName, cam.building, cam.floor].filter(Boolean).join(" · ");
					return (
						<li key={cam.id || cam.cameraCode} className="activity-item">
							<div className="activity-item__main">
								<span className="activity-item__action">
									{cam.cameraCode} {cam.name ? `· ${cam.name}` : ""}
								</span>
								<span className="activity-item__object">{loc || "Chưa gắn khu vực"}</span>
							</div>
							<div className="activity-item__meta">
								<span className={`dash-pill dash-pill--${op.tone}`}>{op.label}</span>
							</div>
						</li>
					);
				})}
			</ul>
		);
	}

	return (
		<DashboardPanel
			icon={Camera}
			title="Camera giám sát"
			subtitle={`${total} camera đang bật trong cấu hình`}
			action={
				manageLink ? (
					<Link to={manageLink} className="dash-card__link">
						{manageLabel}
						<ArrowRight size={14} aria-hidden="true" />
					</Link>
				) : undefined
			}
		>
			{body}
		</DashboardPanel>
	);
}

function RecentRecordingsPanel({ query, summary }) {
	let body;
	if (query.isLoading) {
		body = <LoadingState size="sm" text="Đang tải bản ghi video..." />;
	} else if (query.isError) {
		body = (
			<ErrorState
				size="sm"
				title="Không tải được kho ghi hình"
				message={query.error?.message}
				onRetry={() => query.refetch()}
				retrying={query.isFetching}
			/>
		);
	} else if (summary.recent.length === 0) {
		body = <EmptyState size="sm" icon={Film} title="Chưa có video ghi hình" />;
	} else {
		body = (
			<ul className="activity-list">
				{summary.recent.map((rec) => {
					const loc = [rec.cameraName || rec.cameraId, rec.areaName || rec.building].filter(Boolean).join(" · ");
					const metaParts = [rec.duration, rec.fileSize].filter(Boolean).join(" · ");
					const isSecurity = rec.eventType && rec.eventType !== "NORMAL_RECORDING";
					return (
						<li key={rec.id || rec.filename} className="activity-item">
							<div className="activity-item__main">
								<span className="activity-item__action">
									{rec.eventTypeName || INCIDENT_EVENT_LABELS[rec.eventType] || rec.eventType || rec.filename}
								</span>
								<span className="activity-item__object">{loc || rec.filename}</span>
							</div>
							<div className="activity-item__meta">
								<span className={`dash-pill dash-pill--${isSecurity ? "danger" : "neutral"}`}>
									{isSecurity ? "Sự kiện an ninh" : "Định kỳ"}
								</span>
								<span className="activity-item__time">
									{metaParts || (rec.startTime ? formatDateTime(rec.startTime) : "—")}
								</span>
							</div>
						</li>
					);
				})}
			</ul>
		);
	}

	return (
		<DashboardPanel
			icon={Film}
			title="Video ghi hình gần đây"
			subtitle={
				summary.total > 0
					? `${summary.total} video · ${summary.securityCount} sự kiện · ${summary.normalCount} định kỳ`
					: "Kho lưu trữ video ghi hình"
			}
			action={
				<Link to="/recordings" className="dash-card__link">
					Mở kho lưu trữ
					<ArrowRight size={14} aria-hidden="true" />
				</Link>
			}
		>
			{body}
		</DashboardPanel>
	);
}

function RecentAccessHistoryPanel({ query }) {
	const items = query.data?.content ?? [];
	const total = totalOf(query.data) ?? items.length;

	let body;
	if (query.isLoading) {
		body = <LoadingState size="sm" text="Đang tải nhật ký ra vào..." />;
	} else if (query.isError) {
		body = (
			<ErrorState
				size="sm"
				title="Không tải được nhật ký ra vào"
				message={query.error?.message}
				onRetry={() => query.refetch()}
				retrying={query.isFetching}
			/>
		);
	} else if (items.length === 0) {
		body = <EmptyState size="sm" icon={DoorOpen} title="Chưa có lượt ra vào nào" />;
	} else {
		body = (
			<ul className="activity-list">
				{items.map((item) => {
					const meta = ACCESS_RESULT_META[item.accessResult] || { label: item.accessResult || "—", badge: "neutral" };
					const personLabel =
						item.fullName || placeholderUserCodeLabel(item.userCode) || item.userCode || "Không xác định";
					const loc = [item.areaName, item.cameraCode].filter(Boolean).join(" · ");
					return (
						<li key={item.id} className="activity-item">
							<div className="activity-item__main">
								<span className="activity-item__action">{personLabel}</span>
								<span className="activity-item__object">{loc || "—"}</span>
							</div>
							<div className="activity-item__meta">
								<span className={`dash-pill dash-pill--${meta.badge}`}>{meta.label}</span>
								{item.loggedAt && (
									<time
										className="activity-item__time"
										dateTime={item.loggedAt}
										title={formatDateTime(item.loggedAt)}
									>
										{formatDistanceToNow(new Date(item.loggedAt), { addSuffix: true, locale: vi })}
									</time>
								)}
							</div>
						</li>
					);
				})}
			</ul>
		);
	}

	return (
		<DashboardPanel
			icon={DoorOpen}
			title="Nhật ký ra vào gần đây"
			subtitle={`${total} lượt nhận diện được ghi nhận`}
			action={
				<Link to="/admin/access-history" className="dash-card__link">
					Xem tất cả
					<ArrowRight size={14} aria-hidden="true" />
				</Link>
			}
		>
			{body}
		</DashboardPanel>
	);
}

function GuardRosterPanel({ query, summary, canManageTeams }) {
	let body;
	if (query.isLoading) {
		body = <LoadingState size="sm" text="Đang tải danh sách bảo vệ..." />;
	} else if (query.isError) {
		body = (
			<ErrorState
				size="sm"
				title="Không tải được nhân sự bảo vệ"
				message={query.error?.message}
				onRetry={() => query.refetch()}
				retrying={query.isFetching}
			/>
		);
	} else if (summary.guards.length === 0) {
		body = <EmptyState size="sm" icon={UserCheck} title="Chưa có nhân sự bảo vệ" />;
	} else {
		body = (
			<ul className="activity-list">
				{summary.guards.slice(0, 8).map((g) => {
					const details = [g.userCode, g.shiftName, g.buildingName, g.dutyPostCode].filter(Boolean).join(" · ");
					return (
						<li key={g.id} className="activity-item">
							<div className="activity-item__main">
								<span className="activity-item__action">{g.fullName}</span>
								<span className="activity-item__object">{details || g.email || "—"}</span>
							</div>
							<div className="activity-item__meta">
								<span className={`dash-pill dash-pill--${g.onDuty ? "success" : "neutral"}`}>
									{g.onDuty ? "Đang trong ca" : "Ngoài ca"}
								</span>
							</div>
						</li>
					);
				})}
			</ul>
		);
	}

	return (
		<DashboardPanel
			icon={UserCheck}
			title="Nhân sự bảo vệ & Ca trực"
			subtitle={`${summary.onDutyCount} / ${summary.totalGuards} bảo vệ đang trong ca trực`}
			action={
				canManageTeams ? (
					<Link to="/admin/guard-teams" className="dash-card__link">
						Quản lý đội
						<ArrowRight size={14} aria-hidden="true" />
					</Link>
				) : undefined
			}
		>
			{body}
		</DashboardPanel>
	);
}

export default function DashboardPage() {
	const [isReportFilterOpen, setIsReportFilterOpen] = useState(false);
	const [isReportPreviewOpen, setIsReportPreviewOpen] = useState(false);
	const [reportData, setReportData] = useState(null);
	const { user } = useAuth();
	const role = user?.role;

	// UI-AUDIT #4: phần nào cũng chỉ hiện khi có API sẵn MÀ role được gọi (theo @PreAuthorize backend); không thì ẩn.
	const isAreaAuthorized = [ROLES.ADMIN, ROLES.FACILITY_MANAGER].includes(role); // GET /api/areas
	const canCountCameras = [ROLES.ADMIN, ROLES.FACILITY_MANAGER, ROLES.GUARD].includes(role); // GET /api/cameras
	const canCountIncidents = [ROLES.ADMIN, ROLES.GUARD].includes(role); // GET /api/incidents(/active)
	const canSeeRecordings = [ROLES.ADMIN, ROLES.GUARD].includes(role); // GET /api/cameras/recordings
	const canSeeAccessHistory = [ROLES.ADMIN, ROLES.FACILITY_MANAGER].includes(role); // GET /api/access-history
	const canSeeGuardRoster = [ROLES.FACILITY_MANAGER, ROLES.GUARD].includes(role); // GET /api/guard-chat/guards
	const canCountAccounts = role === ROLES.ADMIN; // GET /api/users (FM chỉ được xem bảo vệ nên không hiện thẻ)
	const canSeeAudit = [ROLES.ADMIN, ROLES.FACILITY_MANAGER].includes(role); // GET /api/access-control/audit-logs
	const canSeeSecurityStats = [ROLES.ADMIN, ROLES.FACILITY_MANAGER].includes(role); // GET /api/dashboard/security-stats

	// GET /api/areas mặc định isActive=true (khu chưa bị vô hiệu hoá); trang 1 phần tử, chỉ lấy totalElements
	const areaQuery = useQuery({
		queryKey: ["dashboard", "area-count"],
		queryFn: () => getAreas({ page: 0, size: 1 }),
		enabled: isAreaAuthorized,
	});
	const areaTypeQueries = useQueries({
		queries: AREA_LEVEL_OPTIONS.map((level) => ({
			queryKey: ["dashboard", "area-count", level.code],
			queryFn: () => getAreas({ areaLevel: level.code, page: 0, size: 1 }),
			enabled: isAreaAuthorized,
		})),
	});
	// status=ACTIVE là camera được bật trong cấu hình; tải tối đa 12 camera để hiện trạng thái vận hành trên Dashboard
	const cameraQuery = useQuery({
		queryKey: ["dashboard", "active-camera-count"],
		queryFn: () => apiGet("/api/cameras?status=ACTIVE&page=0&size=12"),
		enabled: canCountCameras,
	});
	// Sự cố mới hoặc đã tiếp nhận, chưa xử lý xong
	const incidentQuery = useQuery({
		queryKey: ["dashboard", "active-incidents"],
		queryFn: () => apiGet("/api/incidents/active"),
		enabled: canCountIncidents,
	});
	// Toàn bộ sự cố ghi nhận để hiển thị danh sách gần đây và phân bổ theo loại/mức độ
	const allIncidentsQuery = useQuery({
		queryKey: ["dashboard", "all-incidents"],
		queryFn: () => apiGet("/api/incidents"),
		enabled: canCountIncidents,
	});
	// Kho bản ghi video trên đĩa
	const recordingsQuery = useQuery({
		queryKey: ["dashboard", "recordings"],
		queryFn: () => fetchDiskRecordings(),
		enabled: canSeeRecordings,
	});
	// Nhật ký ra vào toàn hệ thống
	const accessHistoryQuery = useQuery({
		queryKey: ["dashboard", "access-history"],
		queryFn: () => searchAccessHistory({ page: 0, size: 8 }),
		enabled: canSeeAccessHistory,
	});
	// Nhân sự bảo vệ & trạng thái trực ca
	const guardRosterQuery = useQuery({
		queryKey: ["dashboard", "guard-roster"],
		queryFn: () => getGuardsForChat(),
		enabled: canSeeGuardRoster,
	});
	// isActive chỉ có hiệu lực khi kèm accountType: NORMAL = người dùng, SYSTEM = cán bộ (ADMIN, FM, GUARD)
	const [normalAccountQuery, staffAccountQuery] = useQueries({
		queries: ["NORMAL", "SYSTEM"].map((accountType) => ({
			queryKey: ["dashboard", "active-accounts", accountType],
			queryFn: () => apiGet(`/api/users?accountType=${accountType}&isActive=true&page=0&size=1`),
			enabled: canCountAccounts,
		})),
	});
	const auditQuery = useQuery({
		queryKey: ["dashboard", "recent-audit"],
		queryFn: () => getAuditLogs({ page: 0, size: RECENT_ACTIVITY_SIZE }),
		enabled: canSeeAudit,
	});

	const kpiValue = (query, count) =>
		query.isLoading ? "..." : query.isError || typeof count !== "number" ? "—" : count;
	const kpiSubtext = (query, text) =>
		query.isLoading ? "Đang tải dữ liệu..." : query.isError ? "Không tải được số liệu" : text;

	const accountState = {
		isLoading: normalAccountQuery.isLoading || staffAccountQuery.isLoading,
		isError: normalAccountQuery.isError || staffAccountQuery.isError,
	};
	const normalCount = totalOf(normalAccountQuery.data?.users);
	const staffCount = totalOf(staffAccountQuery.data?.users);
	const accountCount = normalCount !== null && staffCount !== null ? normalCount + staffCount : null;

	const [clearedVersion, setClearedVersion] = useState(0);
	useEffect(() => {
		const bump = () => setClearedVersion((v) => v + 1);
		window.addEventListener(SOC_CLEARED_UPDATED_EVENT, bump);
		window.addEventListener("storage", bump);
		return () => {
			window.removeEventListener(SOC_CLEARED_UPDATED_EVENT, bump);
			window.removeEventListener("storage", bump);
		};
	}, []);

	// Khi bảo vệ bấm "Dọn dẹp" ở trang Giám sát, các sự cố đã ẩn cũng không hiện lại trên Tổng quan của bảo vệ đó
	const clearedIncidentIds =
		role === ROLES.GUARD
			? readSocStoredSet(SOC_CLEARED_STORAGE_KEY, user?.id ?? null)
			: new Set();
	const clearedResolvedBeforeMs =
		role === ROLES.GUARD ? readSocClearedBefore(user?.id ?? null) : null;
	void clearedVersion;

	const visibleActiveIncidents = filterOutClearedIncidents(
		incidentQuery.data,
		clearedIncidentIds,
		clearedResolvedBeforeMs,
	);
	const visibleAllIncidents = filterOutClearedIncidents(
		allIncidentsQuery.data,
		clearedIncidentIds,
		clearedResolvedBeforeMs,
	);

	const incidentSummary = summarizeIncidents(visibleAllIncidents, 8);
	const recordingSummary = summarizeRecordings(recordingsQuery.data, 6);
	const guardSummary = summarizeGuardRoster(guardRosterQuery.data);
	const cameraList = cameraQuery.data?.content ?? [];
	const onlineCameraCount = cameraList.filter((c) =>
		["ONLINE", "LIVE", "CONNECTED"].includes(c?.operationalStatus),
	).length;

	const kpiCards = [
		{
			id: "areas",
			visible: isAreaAuthorized,
			label: "Khu vực đang hoạt động",
			badge: "Khu vực",
			icon: MapPin,
			value: kpiValue(areaQuery, totalOf(areaQuery.data)),
			subtext: kpiSubtext(areaQuery, "Không tính khu đã vô hiệu hoá"),
		},
		{
			id: "cameras",
			visible: canCountCameras,
			label: "Camera đang bật",
			badge: "Camera",
			icon: Camera,
			value: kpiValue(cameraQuery, cameraQuery.data?.totalElements),
			subtext: kpiSubtext(
				cameraQuery,
				cameraList.length > 0
					? `${onlineCameraCount}/${cameraList.length} trực tuyến · ${cameraList.length - onlineCameraCount} ngoại tuyến`
					: "Đang bật trong cấu hình",
			),
		},
		{
			id: "incidents",
			visible: canCountIncidents,
			label: "Sự cố đang xử lý",
			badge: "Sự cố",
			icon: AlertTriangle,
			value: kpiValue(
				incidentQuery,
				Array.isArray(visibleActiveIncidents) ? visibleActiveIncidents.length : null,
			),
			subtext: kpiSubtext(incidentQuery, "Mới hoặc đã tiếp nhận, chưa xử lý xong"),
		},
		{
			id: "total-incidents",
			visible: canCountIncidents,
			label: "Tổng sự cố ghi nhận",
			badge: "Tổng hợp",
			icon: Siren,
			value: kpiValue(
				allIncidentsQuery,
				Array.isArray(visibleAllIncidents) ? incidentSummary.total : null,
			),
			subtext: kpiSubtext(
				allIncidentsQuery,
				`${incidentSummary.criticalCount} nghiêm trọng · ${incidentSummary.pendingCount} đang mở`,
			),
		},
		{
			id: "recordings",
			visible: canSeeRecordings,
			label: "Video ghi hình",
			badge: "Lưu trữ",
			icon: Film,
			value: kpiValue(
				recordingsQuery,
				Array.isArray(recordingsQuery.data) ? recordingSummary.total : null,
			),
			subtext: kpiSubtext(
				recordingsQuery,
				`${recordingSummary.securityCount} sự kiện · ${recordingSummary.normalCount} định kỳ`,
			),
		},
		{
			id: "guard-roster",
			visible: canSeeGuardRoster,
			label: "Bảo vệ trong ca",
			badge: "Nhân sự",
			icon: UserCheck,
			value: kpiValue(
				guardRosterQuery,
				Array.isArray(guardRosterQuery.data) ? guardSummary.onDutyCount : null,
			),
			subtext: kpiSubtext(guardRosterQuery, `Trên tổng số ${guardSummary.totalGuards} bảo vệ đang hoạt động`),
		},
		{
			id: "accounts",
			visible: canCountAccounts,
			label: "Tài khoản đang hoạt động",
			badge: "Tài khoản",
			icon: Users,
			value: kpiValue(accountState, accountCount),
			subtext: kpiSubtext(accountState, `${normalCount ?? "—"} người dùng · ${staffCount ?? "—"} cán bộ`),
		},
	].filter((card) => card.visible);

	const currentDateStr = format(new Date(), "EEEE, dd/MM/yyyy", { locale: vi });
	const cameraManageLink =
		role === ROLES.GUARD ? "/guard" : isAreaAuthorized ? "/admin/areas" : null;
	const cameraManageLabel = role === ROLES.GUARD ? "Xem trực tiếp" : "Quản lý khu vực";

	return (
		<div className="dashboard-page">
			<div className="dashboard-container">
				<PageHeader
					title="Tổng quan"
					description={DASHBOARD_DESCRIPTIONS[role] ?? DEFAULT_DASHBOARD_DESCRIPTION}
					actions={
						<div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
							{role === ROLES.ADMIN && (
								<Button
									variant="primary"
									onClick={() => setIsReportFilterOpen(true)}
									className="inline-flex items-center gap-1.5 shadow-sm text-xs py-1.5 px-3"
								>
									<FileText size={15} />
									<span>Xuất báo cáo</span>
								</Button>
							)}
							<div className="dashboard-date-badge">
								<Calendar size={14} />
								<span>{currentDateStr}</span>
							</div>
						</div>
					}
				/>

				{/* Hàng 1: thẻ số */}
				<section className="dashboard-kpis">
					{kpiCards.map((card) => {
						const IconComponent = card.icon;
						return (
							<article key={card.id} className="kpi-card">
								<div className="kpi-card__top">
									<span className="kpi-card__icon">
										<IconComponent size={20} />
									</span>
									<span className="kpi-card__badge">{card.badge}</span>
								</div>
								<div className="kpi-card__body">
									<span className="kpi-card__label">{card.label}</span>
									<span className="kpi-card__value">{card.value}</span>
								</div>
								<div className="kpi-card__footer">
									<span className="kpi-card__subtext">{card.subtext}</span>
								</div>
							</article>
						);
					})}
				</section>

				{/* Hàng 2: khu theo loại (trái) + hoạt động gần đây (phải) */}
				{(isAreaAuthorized || canSeeAudit) && (
					<div className="dashboard-grid">
						{isAreaAuthorized && <AreaTypePanel queries={areaTypeQueries} />}
						{canSeeAudit && <RecentActivityPanel query={auditQuery} />}
					</div>
				)}

				{/* Hàng 3: sự cố gần đây + camera / nhân sự / nhật ký ra vào / video ghi hình */}
				{(canCountIncidents || canSeeRecordings) && (
					<div className="dashboard-grid dash-grid--equal">
						{canCountIncidents && (
							<RecentIncidentsPanel
								query={allIncidentsQuery}
								summary={incidentSummary}
								canOpenGuardCenter={role === ROLES.GUARD}
								hasCleared={
									role === ROLES.GUARD &&
									(clearedIncidentIds.size > 0 || clearedResolvedBeforeMs !== null)
								}
								onClearResolved={() => writeSocClearedBefore(Date.now(), user?.id ?? null)}
								onRestoreCleared={() => clearSocStoredSets(user?.id ?? null)}
							/>
						)}
						{canSeeRecordings && (
							<RecentRecordingsPanel query={recordingsQuery} summary={recordingSummary} />
						)}
					</div>
				)}

				{(canCountCameras || canSeeAccessHistory || canSeeGuardRoster) && (
					<div className="dashboard-grid dash-grid--equal">
						{canCountCameras && (
							<CamerasOverviewPanel
								query={cameraQuery}
								manageLink={cameraManageLink}
								manageLabel={cameraManageLabel}
							/>
						)}
						{canSeeAccessHistory ? (
							<RecentAccessHistoryPanel query={accessHistoryQuery} />
						) : (
							canSeeGuardRoster && (
								<GuardRosterPanel
									query={guardRosterQuery}
									summary={guardSummary}
									canManageTeams={role === ROLES.FACILITY_MANAGER}
								/>
							)
						)}
					</div>
				)}

				{canSeeAccessHistory && canSeeGuardRoster && (
					<GuardRosterPanel
						query={guardRosterQuery}
						summary={guardSummary}
						canManageTeams={role === ROLES.FACILITY_MANAGER}
					/>
				)}

				{/* Hàng 4: thống kê an ninh (FM, ADMIN) */}
				{canSeeSecurityStats && <SecurityStatsSection />}

				<ReportFilterModal
					isOpen={isReportFilterOpen}
					onClose={() => setIsReportFilterOpen(false)}
					onReportGenerated={(data) => {
						setReportData(data);
						setIsReportPreviewOpen(true);
					}}
				/>

				<ReportPreviewModal
					isOpen={isReportPreviewOpen}
					onClose={() => setIsReportPreviewOpen(false)}
					reportData={reportData}
				/>
			</div>
		</div>
	);
}
