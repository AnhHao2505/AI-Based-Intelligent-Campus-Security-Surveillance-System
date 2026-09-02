import { useState } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { ThemeProvider } from './context/ThemeContext';
import { AuthProvider } from './context/AuthContext';
import { ROLES } from './constants/roles';
import ProtectedRoute from './components/ProtectedRoute';
import AppLayout from './components/layout/AppLayout';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import UnauthorizedPage from './pages/UnauthorizedPage';
import AreaListPage from './pages/areas/AreaListPage';
import AreaMapPage from './pages/areas/AreaMapPage';
import CameraListPage from './pages/cameras/CameraListPage';
import CameraDetailPage from './pages/cameras/CameraDetailPage';
import FaceManagementPage from './pages/faceData/FaceManagementPage';
import GuardDashboardPage from './pages/guard/GuardDashboardPage';
import './App.css';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';

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
      <ThemeProvider>
        <AuthProvider>
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
            <Route path="/unauthorized" element={<UnauthorizedPage />} />

            {/* Authenticated Management Routes using shared AppLayout */}
            <Route
              element={
                <ProtectedRoute>
                  <AppLayout />
                </ProtectedRoute>
              }
            >
              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              <Route path="/dashboard" element={<DashboardPage />} />

              

              <Route
                path="/admin/areas"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.ADMIN, ROLES.FACILITY_MANAGER]}>
                    <AreaListPage />
                  </ProtectedRoute>
                }
              />

              <Route
                path="/admin/areas/map"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.ADMIN, ROLES.FACILITY_MANAGER]}>
                    <AreaMapPage />
                  </ProtectedRoute>
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

              {/* Face management - Admin only */}
              <Route
                path="/admin/faces"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
                    <FaceManagementPage />
                  </ProtectedRoute>
                }
              />

              <Route
                path="/guard"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.INTERNAL_GUARD, ROLES.OUTSOURCED_GUARD]}>
                    <GuardDashboardPage />
                  </ProtectedRoute>
                }
              />
            </Route>

            {/* Fallback */}
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </AuthProvider>
      </ThemeProvider>
    </GoogleOAuthProvider>
  );
}

export default App;
