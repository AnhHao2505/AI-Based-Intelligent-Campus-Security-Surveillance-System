import { useState, useEffect } from "react";
import { Routes, Route, Navigate, useLocation } from "react-router-dom";
import { GoogleOAuthProvider } from "@react-oauth/google";
import { ThemeProvider } from "./context/ThemeContext";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { ROLES } from "./constants/roles";
import ProtectedRoute from "./components/ProtectedRoute";
import AppLayout from "./components/layout/AppLayout";
import LoginPage from "./pages/LoginPage";
import DashboardPage from "./pages/DashboardPage";
import UnauthorizedPage from "./pages/UnauthorizedPage";
import AreaListPage from "./pages/areas/AreaListPage";
import CampusMapPage from "./pages/areas/CampusMapPage";
import CameraListPage from "./pages/cameras/CameraListPage";
import CameraDetailPage from "./pages/cameras/CameraDetailPage";
import GuardDashboardPage from "./pages/guard/GuardDashboardPage";
import AccessRequestPage from "./pages/accessRequest/AccessRequestPage";
import AccessRequestReviewPage from "./pages/accessRequest/AccessRequestReviewPage";
import GuestVisitPage from "./pages/guestVisit/GuestVisitPage";
import GuestVisitReviewPage from "./pages/guestVisit/GuestVisitReviewPage";
import GuestPhotoPage from "./pages/guestVisit/GuestPhotoPage";
import AccessHistoryPage from "./pages/accessHistory/AccessHistoryPage";
import NotificationsPage from "./pages/notifications/NotificationsPage";
import ManageAccountPage from "./pages/accounts/ManageAccountPage";
import SystemConfigPage from "./pages/system/SystemConfigPage";
import GuardTeamManagementPage from "./pages/guardTeams/GuardTeamManagementPage";
import ReasonCatalogPage from "./pages/system/ReasonCatalogPage";
import UserAccessLevelPage from "./pages/accessControl/UserAccessLevelPage";
import AdminPresetPage from "./pages/accessControl/AdminPresetPage";
import AuditLogPage from "./pages/accessControl/AuditLogPage";
import DemoModeBanner from "./components/common/DemoModeBanner";

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || "";

const ROUTE_TITLES = {
	"/": "FPTU SecureVision",
	"/dashboard": "Dashboard",
	"/admin/areas": "Quản lý khu vực",
	"/admin/areas/map": "Bản đồ Vùng",
	"/cameras": "Quản lý Camera",
	"/admin/cameras": "Quản lý Camera",
	"/admin/accounts": "Quản lý tài khoản",
	"/admin/guard-teams": "Quản lý Đội bảo vệ",
	"/admin/guard-schedules": "Quản lý Đội bảo vệ",
	"/guard": "Trung tâm Giám sát",
	"/access-requests": "Yêu cầu truy cập",
	"/access-history": "Lịch sử truy cập",
	"/notifications": "Thông báo",
	"/admin/access-requests": "Phê duyệt yêu cầu truy cập",
	"/guest-visits": "Khách của tôi",
	"/admin/guest-visits": "Duyệt lượt khách",
	"/admin/guest-photos": "Ảnh khách",
	"/fm/access-levels": "Cấp truy cập người dùng",
	"/admin/access-levels": "Cấp truy cập người dùng",
	"/admin/level-presets": "Mặc định theo loại khu vực",
	"/admin/audit-logs": "Nhật ký Kiểm toán",
	"/admin/system-configurations": "Cấu hình Hệ thống",
	"/login": "Đăng nhập",
	"/admin/reason-catalogs": "Danh mục lý do",
	"/unauthorized": "Không có quyền truy cập",
};

function getPageTitle(pathname) {
	if (ROUTE_TITLES[pathname]) {
		return ROUTE_TITLES[pathname];
	}
	if (pathname.startsWith("/cameras/")) {
		return "Chi tiết Camera";
	}
	return null;
}

function RootRoute() {
	const { user } = useAuth();
	if (user?.role === ROLES.NORMAL_USER) {
		return (
			<Navigate
				to="/access-requests"
				replace
			/>
		);
	}
	return (
		<Navigate
			to="/dashboard"
			replace
		/>
	);
}

function DashboardRoute() {
	const { user } = useAuth();
	if (user?.role === ROLES.NORMAL_USER) {
		return (
			<Navigate
				to="/access-requests"
				replace
			/>
		);
	}
	return <DashboardPage />;
}

function App() {
	const location = useLocation();

	useEffect(() => {
		const title = getPageTitle(location.pathname);
		if (title) {
			document.title = `${title} — AI Campus Security`;
		} else {
			document.title = "AI Campus Security";
		}
	}, [location.pathname]);

	const [resetToken, setResetToken] = useState(() => {
		const urlParams = new URLSearchParams(window.location.search);
		const token = urlParams.get("token");
		if (token) {
			window.history.replaceState({}, document.title, window.location.pathname);
		}
		return token;
	});

	return (
		<GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
			<ThemeProvider>
				<AuthProvider>
					<DemoModeBanner />
					<Routes>
						{/* Public Routes */}
						<Route
							path="/login"
							element={
								<LoginPage
									initialResetToken={resetToken}
									onResetComplete={() => setResetToken(null)}
								/>
							}
						/>
						<Route
							path="/unauthorized"
							element={<UnauthorizedPage />}
						/>

						{/* Authenticated Management Routes using shared AppLayout */}
						<Route
							element={
								<ProtectedRoute>
									<AppLayout />
								</ProtectedRoute>
							}
						>
							<Route
								path="/"
								element={<RootRoute />}
							/>
							<Route
								path="/dashboard"
								element={<DashboardRoute />}
							/>

							<Route
								path="/admin/map"
								element={
									<ProtectedRoute
										allowedRoles={[ROLES.ADMIN]}
									>
										<CampusMapPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/areas"
								element={
									<ProtectedRoute
										allowedRoles={[ROLES.ADMIN, ROLES.FACILITY_MANAGER]}
									>
										<AreaListPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/areas/map"
								element={
									<Navigate
										to="/admin/map"
										replace
									/>
								}
							/>

							<Route
								path="/cameras"
								element={
									<ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
										<CameraListPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/cameras/:id"
								element={
									<ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
										<CameraDetailPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/cameras"
								element={<Navigate to="/cameras" replace />}
							/>

							<Route
								path="/admin/cameras/:id"
								element={<Navigate to="/cameras" replace />}
							/>

							{/* Account management - Admin & Facility Manager */}
							<Route
								path="/admin/accounts"
								element={
									<ProtectedRoute allowedRoles={[ROLES.ADMIN, ROLES.FACILITY_MANAGER]}>
										<ManageAccountPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/guest-photos"
								element={
									<ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
										<GuestPhotoPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/guard-teams"
								element={
									<ProtectedRoute allowedRoles={[ROLES.FACILITY_MANAGER]}>
										<GuardTeamManagementPage />
									</ProtectedRoute>
								}
							/>
							<Route
								path="/admin/guard-schedules"
								element={
									<ProtectedRoute allowedRoles={[ROLES.FACILITY_MANAGER]}>
										<GuardTeamManagementPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/guard"
								element={
									<ProtectedRoute allowedRoles={[ROLES.GUARD]}>
										<GuardDashboardPage />
									</ProtectedRoute>
								}
							/>

							{/* Access Request Flow */}
							<Route
								path="/access-requests"
								element={
									<ProtectedRoute allowedRoles={[ROLES.NORMAL_USER]}>
										<AccessRequestPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/guest-visits"
								element={
									<ProtectedRoute
										allowedRoles={[
											// B-04: ADMIN không làm host khách (BE GuestVisitController create/my/cancel)
											ROLES.NORMAL_USER,
											ROLES.FACILITY_MANAGER
										]}
									>
										<GuestVisitPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/access-history"
								element={
									<ProtectedRoute allowedRoles={[ROLES.NORMAL_USER]}>
										<AccessHistoryPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/notifications"
								element={
									<ProtectedRoute
										allowedRoles={[
											ROLES.NORMAL_USER,
											ROLES.FACILITY_MANAGER,
											ROLES.GUARD,
											ROLES.ADMIN,
										]}
									>
										<NotificationsPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/access-requests"
								element={
									<ProtectedRoute allowedRoles={[ROLES.FACILITY_MANAGER]}>
										<AccessRequestReviewPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/guest-visits"
								element={
									<ProtectedRoute allowedRoles={[ROLES.FACILITY_MANAGER]}>
										<GuestVisitReviewPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/fm/access-levels"
								element={
									<ProtectedRoute
										allowedRoles={[ROLES.FACILITY_MANAGER]}
									>
										<UserAccessLevelPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/access-levels"
								element={<Navigate to="/admin/level-presets" replace />}
							/>

							<Route
								path="/admin/level-presets"
								element={
									<ProtectedRoute
										allowedRoles={[ROLES.ADMIN]}
									>
										<AdminPresetPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/audit-logs"
								element={
									<ProtectedRoute
										allowedRoles={[ROLES.ADMIN, ROLES.FACILITY_MANAGER]}
									>
										<AuditLogPage />
									</ProtectedRoute>
								}
							/>


							<Route
								path="/admin/system-configurations"
								element={
									<ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
										<SystemConfigPage />
									</ProtectedRoute>
								}
							/>
							<Route
								path="/admin/reason-catalogs"
								element={
									<ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
										<ReasonCatalogPage />
									</ProtectedRoute>
								}
							/>
						</Route>

						{/* Fallback */}
						<Route
							path="*"
							element={<RootRoute />}
						/>
					</Routes>
				</AuthProvider>
			</ThemeProvider>
		</GoogleOAuthProvider>
	);
}

export default App;
