import { createContext, useContext, useState, useEffect } from 'react';
import { mockLoginApi } from '../api/mockAuth';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    const savedUser = localStorage.getItem('sep_user');
    return savedUser ? JSON.parse(savedUser) : null;
  });

  const [token, setToken] = useState(() => {
    return localStorage.getItem('sep_token') || null;
  });

  const [loading, setLoading] = useState(false);

  // TODO: Switch to httpOnly cookie before official demo for enhanced security
  const login = async (userCode, password, rememberMe) => {
    setLoading(true);
    try {
      const response = await mockLoginApi(userCode, password);
      setUser(response.user);
      setToken(response.token);

      if (rememberMe) {
        localStorage.setItem('sep_token', response.token);
        localStorage.setItem('sep_user', JSON.stringify(response.user));
      } else {
        sessionStorage.setItem('sep_token', response.token);
        sessionStorage.setItem('sep_user', JSON.stringify(response.user));
      }
      return response.user;
    } finally {
      setLoading(false);
    }
  };

  const logout = () => {
    setUser(null);
    setToken(null);
    localStorage.removeItem('sep_token');
    localStorage.removeItem('sep_user');
    sessionStorage.removeItem('sep_token');
    sessionStorage.removeItem('sep_user');
  };

  return (
    <AuthContext.Provider value={{ user, token, loading, login, logout, isAuthenticated: !!user }}>
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
