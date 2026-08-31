import { useState } from 'react';
import { Routes, Route, Navigate, Outlet } from 'react-router-dom';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ROLES } from './constants/roles';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import UnauthorizedPage from './pages/UnauthorizedPage';
import CameraListPage from './pages/cameras/CameraListPage';
import CameraDetailPage from './pages/cameras/CameraDetailPage';
import FaceManagementPage from './pages/faceData/FaceManagementPage';
import GuardDashboardPage from './pages/guard/GuardDashboardPage';
import AppLayout from './components/layout/AppLayout';
import './App.css';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';

function AppLayoutWrapper() {
  const { user, logout } = useAuth();
  return (
    <AppLayout user={user} onLogout={logout}>
      <Outlet />
    </AppLayout>
  );
}

function App() {
  const [resetToken, setResetToken] = useState(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token');
    if (token) {
      window.history.replaceState({}, document.title, window.location.pathname);
    }
    return token;
  });

  return (
    <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
      <AuthProvider>
        <Routes>
          {/* Public routes */}
          <Route
            path="/login"
            element={
              <LoginPage
                initialResetToken={resetToken}
                onResetComplete={() => setResetToken(null)}
              />
            }
          />
          <Route path="/unauthorized" element={<UnauthorizedPage />} />

          {/* Protected routes - requires authentication */}
          <Route element={<ProtectedRoute />}>
            {/* Pages with Sidebar Layout */}
            <Route element={<AppLayoutWrapper />}>
              <Route path="/" element={<DashboardPage />} />

              {/* Camera management - Admin only */}
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
                path="/admin/faces"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
                    <FaceManagementPage />
                  </ProtectedRoute>
                }
              />

              {/* Guard live surveillance - Admin and Guard */}
              <Route
                path="/guard"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.ADMIN, ROLES.INTERNAL_GUARD, 'ADMIN', 'INTERNAL_GUARD']}>
                    <GuardDashboardPage />
                  </ProtectedRoute>
                }
              />
            </Route>

          </Route>

          {/* Fallback */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </GoogleOAuthProvider>
  );
}

export default App;
