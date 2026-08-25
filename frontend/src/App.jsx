import { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { AuthProvider, useAuth } from './context/AuthContext';
import { getUserPortalRoute } from './services/authService';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import PersonalPortalLayout from './components/PersonalPortalLayout';
import AccessHistoryPage from './pages/AccessHistoryPage';
import NotificationsPage from './pages/NotificationsPage';
import RequestAccessPage from './pages/RequestAccessPage';
import ProtectedRoute from './components/ProtectedRoute';
import './App.css';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID;
if (!GOOGLE_CLIENT_ID) {
  throw new Error('Thiếu VITE_GOOGLE_CLIENT_ID. Sao chép frontend/.env.example thành frontend/.env và điền giá trị.');
}

function AppRoutes() {
  const { user, isAuthenticated, logout } = useAuth();
  const navigate = useNavigate();

  // Determine portal route status: 'ADMIN' | 'PERSONAL' | 'UNKNOWN'
  const portalRoute = user ? getUserPortalRoute(user) : 'UNKNOWN';

  // Security Fail-Closed: If user exists but role_type is UNKNOWN (invalid/corrupted session), force logout immediately
  useEffect(() => {
    if (isAuthenticated && user && portalRoute === 'UNKNOWN') {
      console.warn('Security Fail-Closed: Unknown user role_type detected. Clearing session.');
      logout();
    }
  }, [isAuthenticated, user, portalRoute, logout]);

  const handleLoginSuccess = (userData) => {
    const route = getUserPortalRoute(userData);
    const target = route === 'ADMIN' ? '/dashboard' : '/personal/access-history';
    navigate(target, { replace: true });
  };

  const defaultAuthRedirect =
    portalRoute === 'ADMIN'
      ? '/dashboard'
      : portalRoute === 'PERSONAL'
      ? '/personal/access-history'
      : '/login';

  return (
    <Routes>
      {/* Login Route */}
      <Route
        path="/login"
        element={
          isAuthenticated && user && portalRoute !== 'UNKNOWN' ? (
            <Navigate to={defaultAuthRedirect} replace />
          ) : (
            <LoginPage onLoginSuccess={handleLoginSuccess} />
          )
        }
      />

      {/* Admin / Guard / Manager Dashboard Route */}
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute allowedRoles={['ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'OUTSOURCED_GUARD']}>
            <DashboardPage user={user} onLogout={logout} />
          </ProtectedRoute>
        }
      />

      {/* Personal Portal Routes (Student / Teacher / Staff / All Non-Admin Roles) */}
      <Route
        path="/personal/access-history"
        element={
          <ProtectedRoute>
            <PersonalPortalLayout user={user} onLogout={logout}>
              <AccessHistoryPage />
            </PersonalPortalLayout>
          </ProtectedRoute>
        }
      />

      <Route
        path="/personal/notifications"
        element={
          <ProtectedRoute>
            <PersonalPortalLayout user={user} onLogout={logout}>
              <NotificationsPage />
            </PersonalPortalLayout>
          </ProtectedRoute>
        }
      />

      <Route
        path="/personal/request-access"
        element={
          <ProtectedRoute>
            <PersonalPortalLayout user={user} onLogout={logout}>
              <RequestAccessPage />
            </PersonalPortalLayout>
          </ProtectedRoute>
        }
      />

      {/* Default / Fallback Route */}
      <Route
        path="*"
        element={
          isAuthenticated && user && portalRoute !== 'UNKNOWN' ? (
            <Navigate to={defaultAuthRedirect} replace />
          ) : (
            <Navigate to="/login" replace />
          )
        }
      />
    </Routes>
  );
}

function App() {
  return (
    <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
      <BrowserRouter>
        <AuthProvider>
          <AppRoutes />
        </AuthProvider>
      </BrowserRouter>
    </GoogleOAuthProvider>
  );
}

export default App;
