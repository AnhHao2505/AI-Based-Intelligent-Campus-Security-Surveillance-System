import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import * as authService from '../services/authService';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    const savedUser = localStorage.getItem('user');
    return savedUser ? JSON.parse(savedUser) : null;
  });

  const [token, setToken] = useState(() => {
    return localStorage.getItem('accessToken') || null;
  });

  const [loading, setLoading] = useState(true);

  const logout = useCallback(() => {
    setUser(null);
    setToken(null);
    authService.clearAuth();
  }, []);

  // On mount: if accessToken exists in localStorage, verify session with GET /api/auth/me
  useEffect(() => {
    const initAuth = async () => {
      const storedToken = localStorage.getItem('accessToken');
      if (!storedToken) {
        setLoading(false);
        return;
      }

      try {
        const userData = await authService.getCurrentUser();
        setUser(userData);
        setToken(storedToken);
        localStorage.setItem('user', JSON.stringify(userData));
      } catch (err) {
        console.warn('Session verification failed on mount:', err.message);
        // If stored user exists and is valid, keep offline session if not 401
        const storedUser = authService.getStoredUser();
        if (storedUser) {
          setUser(storedUser);
          setToken(storedToken);
        } else {
          logout();
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

  const value = {
    user,
    token,
    loading,
    isAuthenticated: !!user,
    loginWithCredentials,
    loginWithPassword: loginWithCredentials,
    loginWithGoogle,
    loginWithGoogleToken: loginWithGoogle,
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
