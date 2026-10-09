import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import * as authService from '../services/authService';
import { DEMO_LOGIN_ENABLED } from '../config/demoConfig';
import { getStoredUser, setStoredUser, clearStoredAuth } from '../utils/authStorage';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => getStoredUser());

  const [token, setToken] = useState(() => {
    return authService.getAccessToken() || null;
  });

  const [loading, setLoading] = useState(true);

  const logout = useCallback(() => {
    setUser(null);
    setToken(null);
    clearStoredAuth();
  }, []);

  // On mount: if accessToken exists in localStorage, verify session with GET /api/auth/me
  useEffect(() => {
    const initAuth = async () => {
      const storedToken = authService.getAccessToken();
      if (!storedToken) {
        setLoading(false);
        return;
      }

      if (storedToken.startsWith('frontend-demo-') && !DEMO_LOGIN_ENABLED) {
        logout();
        setLoading(false);
        return;
      }

      try {
        const userData = await authService.getCurrentUser();
        if (!userData || typeof userData !== 'object' || Object.keys(userData).length === 0) {
          clearStoredAuth();
          setUser(null);
          setToken(null);
          if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
            window.location.href = '/login';
          }
          return;
        }
        setUser(userData);
        setToken(storedToken);
        setStoredUser(userData);
      } catch (err) {
        console.warn('Session verification failed on mount:', err.message);
        // If stored user exists and is valid, keep offline session if not 401
        const storedUser = getStoredUser();
        if (storedUser) {
          setUser(storedUser);
          setToken(storedToken);
        } else {
          logout();
          if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
            window.location.href = '/login';
          }
        }
      } finally {
        setLoading(false);
      }
    };

    initAuth();
  }, [logout]);

  const loginWithCredentials = async (email, password) => {
    setLoading(true);
    try {
      const response = await authService.loginWithCredentials(email, password);
      authService.saveAuth(response);
      setUser(response.user);
      setToken(response.accessToken);
      return response;
    } finally {
      setLoading(false);
    }
  };

  const loginWithGoogle = async (idToken) => {
    setLoading(true);
    try {
      const response = await authService.loginWithGoogle(idToken);
      authService.saveAuth(response);
      setUser(response.user);
      setToken(response.accessToken);
      return response;
    } finally {
      setLoading(false);
    }
  };

  const loginAsDemoRole = (role) => {
    if (!DEMO_LOGIN_ENABLED) {
      throw new Error('Chế độ đăng nhập demo đang bị tắt.');
    }
    const response = authService.createDemoAuth(role);
    authService.saveAuth(response);
    setUser(response.user);
    setToken(response.accessToken);
    return response;
  };

  const hasRole = useCallback((allowedRoles) => {
    if (!user) return false;
    if (!allowedRoles || allowedRoles.length === 0) return true;
    if (typeof allowedRoles === 'string') return user.role === allowedRoles;
    return allowedRoles.includes(user.role);
  }, [user]);

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

  const isDemoMode = DEMO_LOGIN_ENABLED && Boolean(token?.startsWith('frontend-demo-'));

  const value = {
    user,
    token,
    loading,
    isDemoMode,
    isAuthenticated: !!user,
    loginWithCredentials,
    loginWithPassword: loginWithCredentials,
    loginWithGoogle,
    loginWithGoogleToken: loginWithGoogle,
    loginAsDemoRole,
    logout,
    hasRole,
  };

  return (
    <AuthContext.Provider value={value}>
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
