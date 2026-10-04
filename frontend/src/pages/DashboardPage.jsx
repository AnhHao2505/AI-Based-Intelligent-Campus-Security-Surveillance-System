import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { format } from "date-fns";
import { vi } from "date-fns/locale";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import {
	MapPin,
	Camera,
	Users,
	AlertTriangle,
	Activity,
	Bell,
	Calendar,
	Clock,
	HardDrive,
	Cpu,
	Map as MapIcon,
	Shield,
	Info,
} from "lucide-react";
import { useAuth } from "../context/AuthContext";
import { getAreas } from "../services/areaService";
import { DataTable } from "../components/ui/data-table";
import "../styles/DashboardPage.css";

export default function DashboardPage() {
	const { user } = useAuth();
	const isAreaAuthorized =
		user?.role === "ADMIN" || user?.role === "FACILITY_MANAGER";
	const { data: areaResponse, isLoading: areaLoading } = useQuery({
		queryKey: ["areas", { page: 0, size: 100 }],
		queryFn: () => getAreas({ page: 0, size: 100 }),
		enabled: isAreaAuthorized,
	});
	const areas = areaResponse?.content || [];
	const areaCount =
		typeof areaResponse?.totalElements === "number"
			? areaResponse.totalElements
			: isAreaAuthorized
				? areas.length
				: null;

	// Per-card connection flags (FIX 1)
	const [kpiConnection] = useState({
		areas: { isConnected: true },
		cameras: { isConnected: false, reason: "Cần kết nối module Camera" },
		faceProfiles: {
			isConnected: false,
			reason: "Cần kết nối module Nhận diện",
		},
		incidents: {
			isConnected: false,
			reason: "Cần kết nối module Camera và AI",
		},
	});

	// Subsystem statuses (FIX 2)
	const [subsystems] = useState([
		{
			id: "cameraNetwork",
			name: "Camera Network",
			status: "not_connected",
			icon: Camera,
		},
		{
			id: "aiVisionEngine",
			name: "AI Vision Engine",
			status: "not_connected",
			icon: Cpu,
		},
		{
			id: "faceIntelligence",
			name: "Face Intelligence",
			status: "not_connected",
			icon: Shield,
		},
		{
			id: "coreStorageApi",
			name: "Core Storage & API",
			status: "not_connected",
			icon: HardDrive,
		},
	]);

	// Data availability flags for panels (FIX 3)
	const [miniMapData] = useState(null);
	const [attentionEvents] = useState([]);
	const [securityLogs] = useState([]);

	const currentDateStr = format(new Date(), "EEEE, dd/MM/yyyy", { locale: vi });

	// Derive Area data for FIX 4
	const totalAreas = areas.length > 0 ? areas.length : areaCount || 0;
	const publicCount = areas.filter((a) => {
		const lvl = a.areaLevel || a.level;
		return lvl === "PUBLIC" || lvl === 1 || lvl === "1";
	}).length;
	const semiCount = areas.filter((a) => {
		const lvl = a.areaLevel || a.level;
		return lvl === "SEMI_PRIVATE" || lvl === 2 || lvl === "2";
	}).length;
	const privateCount = areas.filter((a) => {
		const lvl = a.areaLevel || a.level;
		return lvl === "PRIVATE" || lvl === 3 || lvl === "3";
	}).length;

	const publicPct = totalAreas > 0 ? (publicCount / totalAreas) * 100 : 0;
	const semiPct = totalAreas > 0 ? (semiCount / totalAreas) * 100 : 0;
	const privatePct = totalAreas > 0 ? (privateCount / totalAreas) * 100 : 0;
	const areaChartData = [
		{ name: "PUBLIC", value: publicCount, color: "#22c55e" },
		{ name: "SEMI_PRIVATE", value: semiCount, color: "#f59e0b" },
		{ name: "PRIVATE", value: privateCount, color: "#ef4444" },
	].filter((item) => item.value > 0);
	const subsystemColumns = [
		{
			accessorKey: "name",
			header: "Phân hệ",
			cell: ({ row }) => (
				<span className="font-medium">{row.original.name}</span>
			),
		},
		{
			accessorKey: "status",
			header: "Trạng thái",
			cell: ({ row }) => (
				<span className="inline-flex items-center gap-2 text-slate-500">
					<span
						className={`h-2 w-2 rounded-full ${row.original.status === "connected" ? "bg-emerald-500" : "bg-slate-300"}`}
					/>
					{row.original.status === "connected" ? "Connected" : "Unavailable"}
				</span>
			),
		},
	];

	const areasWithCoordsCount = areas.filter(
		(a) => a.centerLatitude != null && a.centerLongitude != null,
	).length;

	// Connected subsystems count (FIX 2)
	const connectedSubsystemsCount = subsystems.filter(
		(s) => s.status === "connected",
	).length;
	const isAllSubsystemsDisconnected = connectedSubsystemsCount === 0;

	// Placeholder status for FIX 3
	const hasMiniMapData = Boolean(miniMapData && miniMapData.length > 0);
	const hasAttentionData = Boolean(
		attentionEvents && attentionEvents.length > 0,
	);
	const hasEventsData = Boolean(securityLogs && securityLogs.length > 0);
	const allThreeEmpty = !hasMiniMapData && !hasAttentionData && !hasEventsData;

	// KPI card configs (FIX 1)
	const kpiCards = [
		{
			id: "areas",
			label: "Tổng số khu vực",
			badge: "Khu vực",
			icon: MapPin,
			isConnected: kpiConnection.areas.isConnected,
			value: areaLoading ? "..." : areaCount !== null ? areaCount : "—",
			subtext:
				areaCount !== null ? "Khu vực quản lý an ninh" : "Đang tải dữ liệu...",
			disconnectedReason: "",
		},
		{
			id: "cameras",
			label: "Camera đang hoạt động",
			badge: "Camera",
			icon: Camera,
			isConnected: kpiConnection.cameras.isConnected,
			value: null,
			subtext: "",
			disconnectedReason: kpiConnection.cameras.reason,
		},
		{
			id: "faceProfiles",
			label: "Hồ sơ khuôn mặt",
			badge: "Khuôn mặt",
			icon: Users,
			isConnected: kpiConnection.faceProfiles.isConnected,
			value: null,
			subtext: "",
			disconnectedReason: kpiConnection.faceProfiles.reason,
		},
		{
			id: "incidents",
			label: "Sự cố đang xử lý",
			badge: "Sự cố",
			icon: AlertTriangle,
			isConnected: kpiConnection.incidents.isConnected,
			value: null,
			subtext: "",
			disconnectedReason: kpiConnection.incidents.reason,
		},
	];

	return (
		<div className="dashboard-page">
			<div className="dashboard-container">
				{/* Header Section */}
				<header className="dashboard-header">
					<div className="dashboard-header__left">
						<div className="dashboard-header__badge">
							<span className="dashboard-header__badge-dot" />
							CAMPUS SURVEILLANCE
						</div>
						<h1 className="dashboard-header__title">Dashboard</h1>
						<p className="dashboard-header__subtitle">
							Tổng quan hệ thống an ninh Campus
						</p>
					</div>
					<div className="dashboard-header__right">
						<div className="dashboard-date-badge">
							<Calendar size={14} />
							<span>{currentDateStr}</span>
						</div>
					</div>
				</header>

				{/* 4 KPI Cards (FIX 1) */}
				<section className="dashboard-kpis">
					{kpiCards.map((card) => {
						const IconComponent = card.icon;
						return (
							<article
								key={card.id}
								className={`kpi-card ${!card.isConnected ? "kpi-card--disconnected" : ""}`}
							>
								<div className="kpi-card__top">
									<span className="kpi-card__icon">
										<IconComponent size={20} />
									</span>
									<span className="kpi-card__badge">{card.badge}</span>
								</div>
								<div className="kpi-card__body">
									<span className="kpi-card__label">{card.label}</span>
									{card.isConnected ? (
										<span className="kpi-card__value">{card.value}</span>
									) : (
										<span className="kpi-card__value kpi-card__value--disconnected">
											Chưa kết nối
										</span>
									)}
								</div>
								<div className="kpi-card__footer">
									<span className="kpi-card__subtext">
										{card.isConnected ? card.subtext : card.disconnectedReason}
									</span>
								</div>
							</article>
						);
					})}
				</section>
				<p> Để đây, khi có thêm thông tin rồi, thì thiết kế lại</p>
			</div>
		</div>
	);
}
