import { useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { ThemeProvider } from './context/ThemeContext';
import { AuthProvider } from './context/AuthContext';
import { ROLES } from './constants/roles';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import AdminDashboard from './pages/AdminDashboard';
import UnauthorizedPage from './pages/UnauthorizedPage';
import AreaListPage from './pages/AreaListPage';
import CameraListPage from './pages/cameras/CameraListPage';
import CameraDetailPage from './pages/cameras/CameraDetailPage';
import FaceManagementPage from './pages/faceData/FaceManagementPage';
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
        <BrowserRouter>
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

              {/* Protected Routes */}
              <Route
                path="/dashboard"
                element={
                  <ProtectedRoute>
                    <DashboardPage />
                  </ProtectedRoute>
                }
              />

              {/* Admin Dashboard (Overview Map Mock from NguyenBaoFE) */}
              <Route
                path="/admin"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
                    <AdminDashboard />
                  </ProtectedRoute>
                }
              />

              {/* Area Management (M06 Functional Module with Real Backend APIs) */}
              <Route
                path="/admin/areas"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.ADMIN, ROLES.FACILITY_MANAGER]}>
                    <AreaListPage />
                  </ProtectedRoute>
                }
              />

              {/* Camera Management */}
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

              {/* Face Data Management */}
              <Route
                path="/admin/faces"
                element={
                  <ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
                    <FaceManagementPage />
                  </ProtectedRoute>
                }
              />

              {/* Fallback */}
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
          </AuthProvider>
        </BrowserRouter>
      </ThemeProvider>
    </GoogleOAuthProvider>
  );
}

export default App;
