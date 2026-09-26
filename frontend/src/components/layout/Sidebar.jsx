import { useState, useEffect } from "react";
import { NavLink } from "react-router-dom";
import {
	LayoutDashboard,
	MapPin,
	Video,
	Cpu,
	Network,
	ShieldAlert,
	KeyRound,
	ClipboardCheck,
	Users,
	Sun,
	Moon,
	LogOut,
	History,
	Bell,
	ChevronLeft,
	ChevronRight,
	CalendarClock,
	Sliders,
	ShieldCheck,
	Compass,
	Tag,
} from "lucide-react";
import { ROLES, ROLE_LABELS } from "../../constants/roles";
import { useTheme } from "../../context/ThemeContext";
import { useUiStore } from "../../store/useUiStore";
import { notificationService } from "../../services/notificationService";
import "../../styles/Sidebar.css";

export default function Sidebar({ user, onLogout }) {
	const { theme, toggleTheme } = useTheme();
	const { sidebarCollapsed, toggleSidebar } = useUiStore();

	const [unreadCount, setUnreadCount] = useState(0);

	useEffect(() => {
		let isMounted = true;

		const fetchUnread = async () => {
			try {
				if (!user) return;
				const res = await notificationService.getUnreadCount();
				if (isMounted) {
					setUnreadCount(res?.count ?? 0);
				}
			} catch {
				// Silent poll error
			}
		};

		fetchUnread();
		const intervalId = setInterval(fetchUnread, 60000);

		const handleUpdate = () => fetchUnread();
		window.addEventListener("notification-updated", handleUpdate);

		return () => {
			isMounted = false;
			clearInterval(intervalId);
			window.removeEventListener("notification-updated", handleUpdate);
		};
	}, [user]);

	const getInitials = (name) => {
		if (!name) return "U";
		return name
			.split(" ")
			.map((n) => n[0])
			.slice(0, 2)
			.join("")
			.toUpperCase();
	};

	const userRole = user?.role || user?.role_type || "";
	const isAdmin = userRole === ROLES.ADMIN;
	const isFacilityManager = userRole === ROLES.FACILITY_MANAGER;
	const isNormalUser = userRole === ROLES.NORMAL_USER;
	const isGuard =
		userRole === ROLES.GUARD ||
		userRole === "GUARD" ||
		userRole === "INTERNAL_GUARD" ||
		userRole === "OUTSOURCED_GUARD";

	return (
		<aside
			className={`sidebar ${sidebarCollapsed ? "sidebar--collapsed" : ""}`}
		>
			<div className="sidebar__header">
				<NavLink
					to={isNormalUser ? "/access-requests" : "/dashboard"}
					className="sidebar__brand"
					title={sidebarCollapsed ? "FPTU SecureVision" : undefined}
				>
					<div className="sidebar__logo">
						<svg
							width="22"
							height="22"
							viewBox="0 0 24 24"
							fill="none"
							xmlns="http://www.w3.org/2000/svg"
						>
							<path
								d="M12 2L2 7L12 12L22 7L12 2Z"
								stroke="currentColor"
								strokeWidth="2"
								strokeLinecap="round"
								strokeLinejoin="round"
							/>
							<path
								d="M2 17L12 22L22 17"
								stroke="currentColor"
								strokeWidth="2"
								strokeLinecap="round"
								strokeLinejoin="round"
							/>
							<path
								d="M2 12L12 17L22 12"
								stroke="currentColor"
								strokeWidth="2"
								strokeLinecap="round"
								strokeLinejoin="round"
							/>
						</svg>
					</div>
					<div>
						<div className="sidebar__title">FPTU SecureVision</div>
						<div className="sidebar__subtitle">Campus Security</div>
					</div>
				</NavLink>
				<button
					type="button"
					className="sidebar__collapse-button"
					onClick={toggleSidebar}
					aria-label={
						sidebarCollapsed
							? "Mở rộng thanh điều hướng"
							: "Thu gọn thanh điều hướng"
					}
					title={sidebarCollapsed ? "Mở rộng" : "Thu gọn"}
				>
					{sidebarCollapsed ? (
						<ChevronRight size={16} />
					) : (
						<ChevronLeft size={16} />
					)}
				</button>

				<nav className="sidebar__nav">
					{isNormalUser ? (
						<div className="sidebar__section">
							<NavLink
								to="/access-requests"
								className={({ isActive }) =>
									`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
								}
								title={sidebarCollapsed ? "Yêu cầu truy cập" : undefined}
							>
								<KeyRound size={18} />
								<span>Yêu cầu truy cập</span>
							</NavLink>

							<NavLink
								to="/access-history"
								className={({ isActive }) =>
									`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
								}
								title={sidebarCollapsed ? "Lịch sử truy cập" : undefined}
							>
								<History size={18} />
								<span>Lịch sử truy cập</span>
							</NavLink>

							<NavLink
								to="/notifications"
								className={({ isActive }) =>
									`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
								}
								title={sidebarCollapsed ? "Thông báo" : undefined}
							>
								<Bell size={18} />
								<span>Thông báo</span>
								{unreadCount > 0 && (
									<span className="sidebar__unread-badge">
										{unreadCount > 9 ? "9+" : unreadCount}
									</span>
								)}
							</NavLink>
						</div>
					) : (
						<>
							<div className="sidebar__section">
								<NavLink
									to="/dashboard"
									className={({ isActive }) =>
										`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
									}
									title={sidebarCollapsed ? "Dashboard" : undefined}
								>
									<LayoutDashboard size={18} />
									<span>Dashboard</span>
								</NavLink>

								{isGuard && (
									<NavLink
										to="/guard"
										className={({ isActive }) =>
											`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
										}
										title={sidebarCollapsed ? "Trung tâm Giám sát" : undefined}
									>
										<ShieldAlert size={18} />
										<span>Trung tâm Giám sát</span>
									</NavLink>
								)}

								{isAdmin && (
									<NavLink
										to="/cameras"
										className={({ isActive }) =>
											`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
										}
										title={sidebarCollapsed ? "Quản lý Camera" : undefined}
									>
										<Video size={18} />
										<span>Quản lý camera</span>
									</NavLink>
								)}
							</div>

							{(isFacilityManager || isAdmin) && (
								<div className="sidebar__section">
									<NavLink
										to="/admin/areas"
										className={({ isActive }) =>
											`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
										}
										title={
											sidebarCollapsed
												? isAdmin
													? "Cấu hình vùng"
													: "Quản lý vùng"
												: undefined
										}
									>
										<MapPin size={18} />
										<span>{isAdmin ? "Cấu hình vùng" : "Quản lý vùng"}</span>
									</NavLink>

									{isFacilityManager && (
										<>
											<NavLink
												to="/notifications"
												className={({ isActive }) =>
													`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
												}
												title={sidebarCollapsed ? "Thông báo" : undefined}
											>
												<Bell size={18} />
												<span>Thông báo</span>
												{unreadCount > 0 && (
													<span className="sidebar__unread-badge">
														{unreadCount > 9 ? "9+" : unreadCount}
													</span>
												)}
											</NavLink>
											<NavLink
												to="/admin/access-requests"
												className={({ isActive }) =>
													`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
												}
												title={
													sidebarCollapsed ? "Phê duyệt truy cập" : undefined
												}
											>
												<ClipboardCheck size={18} />
												<span>Phê duyệt truy cập</span>
											</NavLink>
											<NavLink
												to="/admin/guard-schedules"
												className={({ isActive }) =>
													`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
												}
												title={
													sidebarCollapsed ? "Lịch trực Bảo vệ" : undefined
												}
											>
												<CalendarClock size={18} />
												<span>Lịch trực Bảo vệ</span>
											</NavLink>
											<NavLink
												to="/fm/access-levels"
												className={({ isActive }) =>
													`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
												}
												title={
													sidebarCollapsed ? "Phân quyền truy cập" : undefined
												}
											>
												<ShieldCheck size={18} />
												<span>Phân quyền truy cập</span>
											</NavLink>
										</>
									)}

									{isAdmin && (
										<>
											<NavLink
												to="/admin/map"
												className={({ isActive }) =>
													`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
												}
												title={sidebarCollapsed ? "Quản lý bản đồ" : undefined}
											>
												<Compass size={18} />
												<span>Quản lý bản đồ</span>
											</NavLink>
											<NavLink
												to="/admin/accounts"
												className={({ isActive }) =>
													`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
												}
												title={
													sidebarCollapsed ? "Quản lý tài khoản" : undefined
												}
											>
												<Users size={18} />
												<span>Quản lý tài khoản</span>
											</NavLink>

											<div className="sidebar__section">
												<NavLink
													to="/admin/system-configurations"
													className={({ isActive }) =>
														`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
													}
													title={
														sidebarCollapsed ? "Cấu hình hệ thống" : undefined
													}
												>
													<Sliders size={18} />
													<span>Cấu hình hệ thống</span>
												</NavLink>
												<NavLink
													to="/admin/reason-catalogs"
													className={({ isActive }) =>
														`sidebar__link ${isActive ? "sidebar__link--active" : ""}`
													}
													title={
														sidebarCollapsed ? "Danh mục lý do" : undefined
													}
												>
													<Tag size={18} />
													<span>Danh mục lý do</span>
												</NavLink>
											</div>
										</>
									)}
								</div>
							)}
						</>
					)}
				</nav>
			</div>

			<div className="sidebar__bottom">
				{/* Theme Toggle Button */}
				<button
					type="button"
					className="sidebar__theme-toggle"
					onClick={toggleTheme}
					title={
						theme === "light"
							? "Chuyển sang Dark Mode"
							: "Chuyển sang Light Mode"
					}
				>
					{theme === "light" ? (
						<>
							<Moon size={16} />
							<span>Dark Mode</span>
						</>
					) : (
						<>
							<Sun size={16} />
							<span>Light Mode</span>
						</>
					)}
				</button>

				{/* User Profile Footer */}
				<div className="sidebar__footer">
					<div className="sidebar__user">
						<div className="sidebar__avatar">{getInitials(user?.fullName)}</div>
						<div className="sidebar__user-info">
							<span className="sidebar__username">
								{user?.fullName || "User"}
							</span>
							<span className="sidebar__userrole">
								{ROLE_LABELS[userRole] || userRole || "Người dùng"}
							</span>
						</div>
					</div>
					<button
						className="sidebar__logout-btn"
						onClick={onLogout}
						title="Đăng xuất"
					>
						<LogOut size={16} />
					</button>
				</div>
			</div>
		</aside>
	);
}
