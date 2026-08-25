import { useState } from 'react';
import { GoogleLogin } from '@react-oauth/google';
import { useAuth } from '../context/AuthContext';
import { ROLE_LABELS } from '../services/authService';
import './LoginPage.css';

export default function LoginPage({ onLoginSuccess }) {
  const { loginWithGoogleToken } = useAuth();
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleGoogleSuccess = async (credentialResponse) => {
    setError(null);
    setLoading(true);

    try {
      // credentialResponse.credential là id_token từ Google
      const userData = await loginWithGoogleToken(credentialResponse.credential);
      if (onLoginSuccess) {
        onLoginSuccess(userData);
      }
    } catch (err) {
      console.error('Login failed:', err);
      setError(err.message || 'Đăng nhập thất bại. Vui lòng thử lại.');
    } finally {
      setLoading(false);
    }
  };

  const handleGoogleError = () => {
    setError('Đăng nhập Google thất bại. Vui lòng thử lại.');
  };

  return (
    <div className="login-page">
      {/* Animated background */}
      <div className="login-bg">
        <div className="login-bg-orb login-bg-orb--1" />
        <div className="login-bg-orb login-bg-orb--2" />
        <div className="login-bg-orb login-bg-orb--3" />
      </div>

      <div className="login-card">
        {/* Logo / Icon */}
        <div className="login-card__icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            <path d="M2 17L12 22L22 17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            <path d="M2 12L12 17L22 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
        </div>

        <h1 className="login-card__title">Campus Security</h1>
        <p className="login-card__subtitle">
          Hệ thống Giám sát An ninh Thông minh
        </p>

        <div className="login-card__divider" />

        {/* Google Login Button */}
        <div className="login-card__btn-wrapper">
          {loading ? (
            <div className="login-card__loader">
              <div className="login-card__spinner" />
              <span>Đang xác thực...</span>
            </div>
          ) : (
            <GoogleLogin
              onSuccess={handleGoogleSuccess}
              onError={handleGoogleError}
              theme="outline"
              size="large"
              width="320"
              text="signin_with"
              shape="pill"
              logo_alignment="left"
            />
          )}
        </div>

        {/* Error message */}
        {error && (
          <div className="login-card__error">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2"/>
              <line x1="12" y1="8" x2="12" y2="12" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
              <line x1="12" y1="16" x2="12.01" y2="16" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
            <span>{error}</span>
          </div>
        )}

        <p className="login-card__note">
          Chỉ email được cấp bởi quản trị viên mới có thể đăng nhập.
        </p>

        {/* Role badges */}
        <div className="login-card__roles">
          {Object.entries(ROLE_LABELS).map(([key, label]) => (
            <span key={key} className="login-card__role-badge">
              {label}
            </span>
          ))}
        </div>
      </div>

      <footer className="login-footer">
        FA26SE040 &middot; AI-Based Intelligent Campus Security
      </footer>
    </div>
  );
}
