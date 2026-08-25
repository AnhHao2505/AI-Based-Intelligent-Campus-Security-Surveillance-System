import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { loginWithGoogle, getCurrentUser } from '../services/authService';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    const savedUser = localStorage.getItem('icss_user');
    return savedUser ? JSON.parse(savedUser) : null;
  });

  const [token, setToken] = useState(() => {
    return localStorage.getItem('icss_token') || null;
  });

  const [loading, setLoading] = useState(true);

  const logout = useCallback(() => {
    setUser(null);
    setToken(null);
    localStorage.removeItem('icss_token');
    localStorage.removeItem('icss_user');
  }, []);

  // On mount: if token exists in localStorage, verify token with GET /api/auth/me
  useEffect(() => {
    const initAuth = async () => {
      const storedToken = localStorage.getItem('icss_token');
      if (!storedToken) {
        setLoading(false);
        return;
      }

      try {
        const userData = await getCurrentUser();
        setUser(userData);
        setToken(storedToken);
        localStorage.setItem('icss_user', JSON.stringify(userData));
      } catch (err) {
        console.warn('Session verification failed on mount:', err.message);
        logout();
      } finally {
        setLoading(false);
      }
    };

    initAuth();
  }, [logout]);

  const loginWithGoogleToken = async (idToken) => {
    setLoading(true);
    try {
      const response = await loginWithGoogle(idToken);
      const jwtToken = response.accessToken;
      const userData = response.user;

      localStorage.setItem('icss_token', jwtToken);
      localStorage.setItem('icss_user', JSON.stringify(userData));

      setUser(userData);
      setToken(jwtToken);
      return userData;
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        backgroundColor: '#0f172a',
        color: '#ffffff',
        fontFamily: 'sans-serif'
      }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px' }}>
          <div style={{
            width: '32px',
            height: '32px',
            border: '4px solid #10b981',
            borderTopColor: 'transparent',
            borderRadius: '50%',
            animation: 'spin 1s linear infinite'
          }} />
          <style>{`@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }`}</style>
          <span style={{ fontSize: '14px', color: '#94a3b8' }}>Đang khôi phục phiên đăng nhập...</span>
        </div>
      </div>
    );
  }

  return (
    <AuthContext.Provider value={{ user, token, loading, loginWithGoogleToken, logout, isAuthenticated: !!user }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
