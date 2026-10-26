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
import {
	DEFAULT_SECURITY_STATS_RANGE,
	SECURITY_STATS_RANGES,
	getSecurityStats,
} from "../services/dashboardService";
import { AREA_LEVEL_OPTIONS } from "../utils/areaHelpers";
import { ACCESS_RESULT_META } from "../utils/accessHistoryHelpers";
import { formatDateTime, formatRange } from "../utils/formatDateTime";
import { TARGET_TYPE_LABELS, getActionLabel, renderSystemActor } from "../utils/auditLabels";
import { EmptyState, ErrorState, LoadingState, PageHeader, StatusTabs } from "../components/ui";
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
	AUTHORIZED_ACCESS: "var(--theme-success)",
	UNAUTHORIZED_ACCESS: "var(--theme-danger)",
	ACCESS_OUT_OF_TIME: "var(--theme-warning)",
	UNKNOWN_PERSON: "var(--theme-text-muted)",
	INTRUSION: "var(--theme-danger-text)",
};
const ACCESS_RESULT_CODES = Object.keys(ACCESS_RESULT_META);
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
						const name = a.areaName ?? "Không xác định khu vực";
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
			title="Lượt ra vào theo ngày"
			subtitle={total > 0 ? `${total} lượt, chia theo kết quả kiểm soát` : "Chia theo kết quả kiểm soát"}
		>
			{total === 0 ? (
				<EmptyState size="sm" icon={ChartColumnStacked} title="Không có lượt ra vào trong khoảng này" />
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
									borderRadius: 8,
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
				label: "Tỉ lệ báo nhầm",
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
							: "Sự cố và lượt ra vào trong khoảng đã chọn"}
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

export default function DashboardPage() {
	const { user } = useAuth();
	const role = user?.role;

	// UI-AUDIT #4: phần nào cũng chỉ hiện khi có API sẵn MÀ role được gọi (theo @PreAuthorize backend); không thì ẩn.
	const isAreaAuthorized = [ROLES.ADMIN, ROLES.FACILITY_MANAGER].includes(role); // GET /api/areas
	const canCountCameras = [ROLES.ADMIN, ROLES.FACILITY_MANAGER, ROLES.GUARD].includes(role); // GET /api/cameras
	const canCountIncidents = [ROLES.ADMIN, ROLES.GUARD].includes(role); // GET /api/incidents/active
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
	// status=ACTIVE là camera được bật trong cấu hình, không phải camera đang có hình
	const cameraQuery = useQuery({
		queryKey: ["dashboard", "active-camera-count"],
		queryFn: () => apiGet("/api/cameras?status=ACTIVE&page=0&size=1"),
		enabled: canCountCameras,
	});
	// Sự cố mới hoặc đã tiếp nhận, chưa xử lý xong
	const incidentQuery = useQuery({
		queryKey: ["dashboard", "active-incidents"],
		queryFn: () => apiGet("/api/incidents/active"),
		enabled: canCountIncidents,
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
			subtext: kpiSubtext(cameraQuery, "Đang bật trong cấu hình, chưa tính tín hiệu kết nối"),
		},
		{
			id: "incidents",
			visible: canCountIncidents,
			label: "Sự cố đang xử lý",
			badge: "Sự cố",
			icon: AlertTriangle,
			value: kpiValue(
				incidentQuery,
				Array.isArray(incidentQuery.data) ? incidentQuery.data.length : null,
			),
			subtext: kpiSubtext(incidentQuery, "Mới hoặc đã tiếp nhận, chưa xử lý xong"),
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

	return (
		<div className="dashboard-page">
			<div className="dashboard-container">
				<PageHeader
					title="Tổng quan"
					description={DASHBOARD_DESCRIPTIONS[role] ?? DEFAULT_DASHBOARD_DESCRIPTION}
					actions={
						<div className="dashboard-date-badge">
							<Calendar size={14} />
							<span>{currentDateStr}</span>
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

				{/* Hàng 2: khu theo loại (trái) + hoạt động gần đây (phải). GUARD không gọi được hai API này nên không có hàng 2 */}
				{(isAreaAuthorized || canSeeAudit) && (
					<div className="dashboard-grid">
						{isAreaAuthorized && <AreaTypePanel queries={areaTypeQueries} />}
						{canSeeAudit && <RecentActivityPanel query={auditQuery} />}
					</div>
				)}

				{/* Hàng 3: thống kê an ninh (FM, ADMIN). GUARD không gọi được API này nên không có hàng 3 */}
				{canSeeSecurityStats && <SecurityStatsSection />}
			</div>
		</div>
	);
}
