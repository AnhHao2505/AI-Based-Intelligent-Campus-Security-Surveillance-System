import { useState } from 'react';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { isAuthenticated, getStoredUser, clearAuth } from './services/authService';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import './App.css';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID;

function App() {
  const [loggedIn, setLoggedIn] = useState(isAuthenticated());
  const [user, setUser] = useState(getStoredUser());
  const [resetToken, setResetToken] = useState(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get('token');
    if (token) {
      window.history.replaceState({}, document.title, window.location.pathname);
    }
    return token;
  });

  const handleLoginSuccess = (authResponse) => {
    setUser(authResponse.user);
    setLoggedIn(true);
  };

  const handleLogout = () => {
    clearAuth();
    setUser(null);
    setLoggedIn(false);
  };

  return (
    <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
      {loggedIn && user ? (
        <DashboardPage user={user} onLogout={handleLogout} />
      ) : (
        <LoginPage 
          onLoginSuccess={handleLoginSuccess} 
          initialResetToken={resetToken}
          onResetComplete={() => setResetToken(null)}
        />
      )}
    </GoogleOAuthProvider>
  );
}

export default App;
