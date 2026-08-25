import { Routes, Route, Navigate } from 'react-router-dom';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { AuthProvider } from './context/AuthContext';
import { ROLES } from './constants/roles';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import AdminPage from './pages/AdminPage';
import GuardPage from './pages/GuardPage';
import UnauthorizedPage from './pages/UnauthorizedPage';
import './App.css';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID;

function App() {
  return (
    <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
      <AuthProvider>
        <Routes>
          {/* Public routes */}
          <Route path="/login" element={<LoginPage />} />
          <Route path="/unauthorized" element={<UnauthorizedPage />} />

          {/* Protected routes - requires authentication */}
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<DashboardPage />} />

            {/* Admin only route */}
            <Route
              path="/admin"
              element={
                <ProtectedRoute allowedRoles={[ROLES.ADMIN]}>
                  <AdminPage />
                </ProtectedRoute>
              }
            />

            {/* Guards and Admin route */}
            <Route
              path="/guard"
              element={
                <ProtectedRoute allowedRoles={[ROLES.ADMIN, ROLES.INTERNAL_GUARD, ROLES.OUTSOURCED_GUARD]}>
                  <GuardPage />
                </ProtectedRoute>
              }
            />
          </Route>

          {/* Fallback */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </GoogleOAuthProvider>
  );
}

export default App;
