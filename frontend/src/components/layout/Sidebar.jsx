import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { NavLink, useLocation } from "react-router-dom";
import {
	LayoutDashboard,
	MapPin,
	Video,
	ShieldAlert,
	KeyRound,
	ClipboardCheck,
	Users,
	Sun,
	Moon,
	LogOut,
	History,
	Bell,
	ChevronDown,
	Pin,
	PinOff,
	CalendarClock,
	CalendarDays,
	Sliders,
	ShieldCheck,
	Tag,
	UserPlus,
	Camera,
	Film,
} from "lucide-react";
import { ROLES, ROLE_SHORT_LABELS } from "../../constants/roles";
import { useTheme } from "../../context/ThemeContext";
import { notificationService } from "../../services/notificationService";
import FptBrandLogo from "../common/FptBrandLogo";
import "../../styles/Sidebar.css";

export default function Sidebar({ user, onLogout, onLayoutChange }) {
	const { theme, toggleTheme } = useTheme();
	const { pathname } = useLocation();

	const [unreadCount, setUnreadCount] = useState(0);
	const [isOpen, setIsOpen] = useState(false);
	const [isPinned, setIsPinned] = useState(false);
	const [isTwoRow, setIsTwoRow] = useState(false);
	const closeTimerRef = useRef(null);
	const headbarRef = useRef(null);
	const navRef = useRef(null);

	const clearCloseTimer = useCallback(() => {
		if (closeTimerRef.current) {
			clearTimeout(closeTimerRef.current);
			closeTimerRef.current = null;
		}
	}, []);

	const openHeadbar = useCallback(() => {
		clearCloseTimer();
		setIsOpen(true);
	}, [clearCloseTimer]);

	const scheduleClose = useCallback(() => {
		if (isPinned) return;
		clearCloseTimer();
		closeTimerRef.current = setTimeout(() => {
			setIsOpen(false);
			closeTimerRef.current = null;
		}, 240);
	}, [isPinned, clearCloseTimer]);

	useEffect(() => {
		return () => clearCloseTimer();
	}, [clearCloseTimer]);

	// Close on touch/narrow navigation when unpinned
	useEffect(() => {
		if (!isPinned && window.matchMedia?.("(hover: none)")?.matches) {
			setIsOpen(false);
		}
	}, [pathname, isPinned]);

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

	const navItems = useMemo(() => {
		if (isNormalUser) {
			return [
				{ to: "/access-requests", label: "Yêu cầu truy cập", Icon: KeyRound },
				{ to: "/access-history", label: "Lịch sử ra vào của tôi", Icon: History },
				{ to: "/guest-visits", label: "Khách của tôi", Icon: UserPlus },
				{ to: "/notifications", label: "Thông báo", Icon: Bell, hasUnread: true },
			];
		}

		const items = [{ to: "/dashboard", label: "Tổng quan", Icon: LayoutDashboard }];

		if (isGuard) {
			items.push(
				{ to: "/guard", label: "Trung tâm Giám sát", Icon: ShieldAlert },
				{ to: "/recordings", label: "Kho lưu trữ ghi hình", Icon: Film },
				{ to: "/notifications", label: "Thông báo", Icon: Bell, hasUnread: true },
			);
		}

		if (isAdmin) {
			items.push(
				{ to: "/cameras", label: "Quản lý camera", Icon: Video },
				{ to: "/recordings", label: "Kho lưu trữ ghi hình", Icon: Film },
			);
		}

		if (isFacilityManager || isAdmin) {
			items.push(
				{ to: "/admin/areas", label: "Quản lý khu vực", Icon: MapPin },
				{ to: "/admin/events", label: "Quản lý sự kiện", Icon: CalendarDays },
				{ to: "/admin/access-history", label: "Lịch sử ra vào", Icon: History },
			);
		}

		if (isFacilityManager) {
			items.push(
				{ to: "/guest-visits", label: "Khách của tôi", Icon: UserPlus },
				{ to: "/notifications", label: "Thông báo", Icon: Bell, hasUnread: true },
				{ to: "/admin/access-requests", label: "Phê duyệt yêu cầu", Icon: ClipboardCheck },
				{ to: "/admin/guard-teams", label: "Quản lý Đội bảo vệ", Icon: CalendarClock },
				{ to: "/fm/access-levels", label: "Cấp truy cập người dùng", Icon: ShieldCheck },
				{ to: "/admin/audit-logs", label: "Nhật ký kiểm toán", Icon: History },
			);
		}

		if (isAdmin) {
			items.push(
				{ to: "/notifications", label: "Thông báo", Icon: Bell, hasUnread: true },
				{ to: "/admin/accounts", label: "Quản lý tài khoản", Icon: Users },
				{ to: "/admin/guest-photos", label: "Ảnh khách", Icon: Camera },
				{ to: "/admin/audit-logs", label: "Nhật ký kiểm toán", Icon: History },
				{ to: "/admin/system-configurations", label: "Cấu hình hệ thống", Icon: Sliders },
				{ to: "/admin/reason-catalogs", label: "Danh mục lý do", Icon: Tag },
			);
		}

		return items;
	}, [isNormalUser, isGuard, isAdmin, isFacilityManager]);

	const visible = isOpen || isPinned;

	// Sync headbar height with AppLayout so content pushes down cleanly when dropped
	useEffect(() => {
		if (!onLayoutChange) return undefined;
		const el = headbarRef.current;
		const notify = () => {
			onLayoutChange(visible, el?.offsetHeight || 0);
		};
		notify();

		if (!el || typeof ResizeObserver === "undefined") return undefined;
		const observer = new ResizeObserver(notify);
		observer.observe(el);
		return () => observer.disconnect();
	}, [visible, onLayoutChange, userRole]);

	// Measure whether all tabs fit comfortably on 1 row; otherwise use balanced 2-row grid
	useEffect(() => {
		const nav = navRef.current;
		if (!nav) return undefined;

		const updateLayout = () => {
			const links = Array.from(nav.querySelectorAll(".headbar__link"));
			if (navItems.length <= 4) {
				setIsTwoRow(false);
			} else {
				const totalLinksWidth = links.reduce((sum, link) => sum + link.scrollWidth, 0);
				const minSpacing = Math.max(0, navItems.length - 1) * 10;
				const needsTwoRows = nav.clientWidth > 0 && totalLinksWidth + minSpacing > nav.clientWidth;
				setIsTwoRow(needsTwoRows);
			}

			requestAnimationFrame(() => {
				const items = Array.from(nav.children);
				items.forEach((item, idx) => {
					const next = items[idx + 1];
					const isRowEnd = Boolean(next && next.offsetTop > item.offsetTop + 4);
					if (isRowEnd) {
						item.setAttribute("data-row-end", "true");
					} else {
						item.removeAttribute("data-row-end");
					}
				});
			});
		};

		updateLayout();
		if (typeof ResizeObserver === "undefined") return undefined;
		const observer = new ResizeObserver(updateLayout);
		observer.observe(nav);
		return () => observer.disconnect();
	}, [navItems, visible, unreadCount]);

	const gridCols = Math.max(1, Math.ceil(navItems.length / 2));

	const linkClass = ({ isActive }) =>
		`headbar__link ${isActive ? "headbar__link--active" : ""}`;

	return (
		<>
			{/* Invisible full-width top edge hover sensor */}
			<div
				className="headbar-hotspot"
				onMouseEnter={openHeadbar}
				aria-hidden="true"
			/>

			{/* Centered sleek peek handle when headbar is tucked away */}
			<button
				type="button"
				className={`headbar-peek ${visible ? "headbar-peek--hidden" : ""}`}
				onMouseEnter={openHeadbar}
				onClick={openHeadbar}
				aria-label="Mở thanh điều hướng"
				title="Di chuột lên để mở thanh điều hướng"
			>
				<span className="headbar-peek__bar" />
				<ChevronDown size={13} className="headbar-peek__icon" />
				<span className="headbar-peek__label">Menu</span>
				{unreadCount > 0 && (
					<span className="headbar-peek__badge">
						{unreadCount > 9 ? "9+" : unreadCount}
					</span>
				)}
			</button>

			{/* Floating Drop-Down Headbar */}
			<header
				ref={headbarRef}
				className={`headbar ${visible ? "headbar--open" : ""}`}
				onMouseEnter={openHeadbar}
				onMouseLeave={scheduleClose}
				onFocus={openHeadbar}
				onBlur={(e) => {
					if (!e.currentTarget.contains(e.relatedTarget)) {
						scheduleClose();
					}
				}}
			>
				<div className="headbar__inner">
					{/* Row 1: Brand & Quick Controls */}
					<div className="headbar__top">
						<NavLink
							to={isNormalUser ? "/access-requests" : "/dashboard"}
							className="headbar__brand"
						>
							<div className="headbar__logo">
								<FptBrandLogo variant="symbol" height={18} />
							</div>
							<div className="headbar__brand-text">
								<span className="headbar__title">FPTU SecureVision</span>
								<span className="headbar__subtitle">Campus Security</span>
							</div>
						</NavLink>

						<div className="headbar__controls">
							<button
								type="button"
								className={`headbar__icon-btn ${isPinned ? "headbar__icon-btn--active" : ""}`}
								onClick={() => {
									setIsPinned((prev) => !prev);
									setIsOpen(true);
								}}
								title={isPinned ? "Tự động ẩn khi rời chuột" : "Ghim thanh điều hướng"}
								aria-label={isPinned ? "Bỏ ghim thanh điều hướng" : "Ghim thanh điều hướng"}
							>
								{isPinned ? <PinOff size={15} /> : <Pin size={15} />}
							</button>

							<button
								type="button"
								className="headbar__theme-toggle"
								onClick={toggleTheme}
								title={
									theme === "light"
										? "Chuyển sang giao diện tối"
										: "Chuyển sang giao diện sáng"
								}
								aria-label={
									theme === "light"
										? "Chuyển sang giao diện tối"
										: "Chuyển sang giao diện sáng"
								}
							>
								{theme === "light" ? (
									<>
										<Moon size={15} />
										<span>Tối</span>
									</>
								) : (
									<>
										<Sun size={15} />
										<span>Sáng</span>
									</>
								)}
							</button>

							<div className="headbar__user">
								<div className="headbar__avatar">{getInitials(user?.fullName)}</div>
								<div className="headbar__user-info">
									<span className="headbar__username" title={user?.fullName || undefined}>
										{user?.fullName || "Người dùng"}
									</span>
									<span className="headbar__userrole">
										{ROLE_SHORT_LABELS[userRole] || "Người dùng"}
									</span>
								</div>
								<button
									type="button"
									className="headbar__logout-btn"
									onClick={onLogout}
									title="Đăng xuất"
									aria-label="Đăng xuất"
								>
									<LogOut size={15} />
								</button>
							</div>
						</div>
					</div>

					{/* Row 2: Evenly Spaced Horizontal Navigation Tabs with Thin Dividers */}
					<nav
						ref={navRef}
						className={`headbar__nav ${isTwoRow ? "headbar__nav--grid" : ""}`}
						style={isTwoRow ? { "--headbar-cols": gridCols } : undefined}
						aria-label="Điều hướng chính"
					>
						{navItems.map(({ to, label, Icon, hasUnread }) => (
							<div key={to} className="headbar__item">
								<NavLink to={to} className={linkClass}>
									<Icon size={14} />
									<span>{label}</span>
									{hasUnread && unreadCount > 0 && (
										<span className="headbar__unread-badge">
											{unreadCount > 9 ? "9+" : unreadCount}
										</span>
									)}
								</NavLink>
							</div>
						))}
					</nav>
				</div>
			</header>
		</>
	);
}
