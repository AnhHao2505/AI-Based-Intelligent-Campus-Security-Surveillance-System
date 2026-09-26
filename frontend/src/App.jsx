import { useState } from "react";
import { Routes, Route, Navigate } from "react-router-dom";
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
import AccessHistoryPage from "./pages/accessHistory/AccessHistoryPage";
import NotificationsPage from "./pages/notifications/NotificationsPage";
import ManageAccountPage from "./pages/accounts/ManageAccountPage";
import SystemConfigPage from "./pages/system/SystemConfigPage";
import ReasonCatalogPage from "./pages/system/ReasonCatalogPage";
import UiKitPage from "./pages/_devPreview/UiKitPage";
import GuardScheduleManagementPage from "./pages/admin/GuardScheduleManagementPage";
import UserAccessLevelPage from "./pages/accessControl/UserAccessLevelPage";
import DemoModeBanner from "./components/common/DemoModeBanner";

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || "";

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
						<Route
							path="/dev/ui-kit"
							element={<UiKitPage />}
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

							{/* Account management - Admin only */}
							<Route
								path="/admin/accounts"
								element={
									<ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
										<ManageAccountPage />
									</ProtectedRoute>
								}
							/>

							<Route
								path="/admin/guard-schedules"
								element={
									<ProtectedRoute allowedRoles={[ROLES.FACILITY_MANAGER]}>
										<GuardScheduleManagementPage />
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
											ROLES.FACILITY_MANAGER
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
								element={<Navigate to="/fm/access-levels" replace />}
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
