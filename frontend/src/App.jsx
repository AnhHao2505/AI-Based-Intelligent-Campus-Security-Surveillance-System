import { useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { isAuthenticated, getStoredUser, isPersonalPortalUser } from './services/authService';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import PersonalPortalLayout from './components/PersonalPortalLayout';
import AccessHistoryPage from './pages/AccessHistoryPage';
import NotificationsPage from './pages/NotificationsPage';
import RequestAccessPage from './pages/RequestAccessPage';
import './App.css';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID || 'mock-client-id';

function App() {
  const [loggedIn, setLoggedIn] = useState(isAuthenticated());
  const [user, setUser] = useState(getStoredUser());

  const handleLoginSuccess = (authResponse) => {
    setUser(authResponse.user);
    setLoggedIn(true);
  };

  const handleLogout = () => {
    setUser(null);
    setLoggedIn(false);
  };

  // Determine redirect target based on role exclusion
  const isPersonal = user ? isPersonalPortalUser(user) : true;
  const defaultAuthRedirect = isPersonal ? '/personal/access-history' : '/dashboard';

  return (
    <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
      <BrowserRouter>
        <Routes>
          {/* Login Route */}
          <Route
            path="/login"
            element={
              loggedIn && user ? (
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
              loggedIn && user ? (
                <DashboardPage user={user} onLogout={handleLogout} />
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />

          {/* Personal Portal Routes (Student / Teacher / Staff / All Non-Admin Roles) */}
          <Route
            path="/personal/access-history"
            element={
              loggedIn && user ? (
                <PersonalPortalLayout user={user} onLogout={handleLogout}>
                  <AccessHistoryPage />
                </PersonalPortalLayout>
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />

          <Route
            path="/personal/notifications"
            element={
              loggedIn && user ? (
                <PersonalPortalLayout user={user} onLogout={handleLogout}>
                  <NotificationsPage />
                </PersonalPortalLayout>
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />

          <Route
            path="/personal/request-access"
            element={
              loggedIn && user ? (
                <PersonalPortalLayout user={user} onLogout={handleLogout}>
                  <RequestAccessPage />
                </PersonalPortalLayout>
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />

          {/* Default / Fallback Route */}
          <Route
            path="*"
            element={
              loggedIn && user ? (
                <Navigate to={defaultAuthRedirect} replace />
              ) : (
                <Navigate to="/login" replace />
              )
            }
          />
        </Routes>
      </BrowserRouter>
    </GoogleOAuthProvider>
  );
}

export default App;
