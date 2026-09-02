import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { GoogleLogin } from '@react-oauth/google';
import { useAuth } from '../context/AuthContext';
import {
  sendResetLink,
  resetPasswordWithToken
} from '../services/authService';
import './LoginPage.css';

const ROLE_LABELS = {
  ADMIN: 'Quản trị viên',
  FACILITY_MANAGER: 'Quản lý cơ sở',
  INTERNAL_GUARD: 'Bảo vệ nội bộ',
  OUTSOURCED_GUARD: 'Bảo vệ thuê ngoài',
};

export default function LoginPage({ onLoginSuccess, initialResetToken, onResetComplete }) {
  const { loginWithGoogleToken, loginWithPassword } = useAuth();
  const navigate = useNavigate();

  const [mode, setMode] = useState('login'); // 'login' | 'forgot' | 'reset'
  const [activeTab, setActiveTab] = useState('google'); // 'google' | 'credentials'

  // General states
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [success, setSuccess] = useState(false);

  // Form states
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  // Switch to reset mode if token exists in URL
  useEffect(() => {
    if (initialResetToken) {
      setMode('reset');
    }
  }, [initialResetToken]);

  const handleGoogleSuccess = async (credentialResponse) => {
    setError(null);
    setLoading(true);
    try {
      await loginWithGoogleToken(credentialResponse.credential);
      if (onLoginSuccess) onLoginSuccess();
      navigate('/dashboard');
    } catch (err) {
      console.error('Google login failed:', err);
      setError(err.message || 'Đăng nhập Google thất bại. Vui lòng thử lại.');
    } finally {
      setLoading(false);
    }
  };

  const handleGoogleError = () => {
    setError('Đăng nhập Google thất bại. Vui lòng thử lại.');
  };

  const handleCredentialLoginSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await loginWithPassword(email, password);
      if (onLoginSuccess) onLoginSuccess();
      navigate('/dashboard');
    } catch (err) {
      console.error('Credentials login failed:', err);
      setError(err.message || 'Đăng nhập thất bại. Vui lòng kiểm tra lại thông tin.');
    } finally {
      setLoading(false);
    }
  };

  const handleForgotSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await sendResetLink(email);
      setSuccess(true);
    } catch (err) {
      console.error('Forgot password link request failed:', err);
      setError(err.message || 'Có lỗi xảy ra khi gửi yêu cầu. Vui lòng thử lại.');
    } finally {
      setLoading(false);
    }
  };

  const handleResetSubmit = async (e) => {
    e.preventDefault();
    if (password !== confirmPassword) {
      setError('Mật khẩu nhập lại không khớp');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      await resetPasswordWithToken(initialResetToken, password);
      setSuccess(true);
      setTimeout(() => {
        if (onResetComplete) onResetComplete();
        setMode('login');
        setSuccess(false);
        setPassword('');
        setConfirmPassword('');
      }, 3000);
    } catch (err) {
      console.error('Password reset failed:', err);
      setError(err.message || 'Đổi mật khẩu thất bại. Token có thể đã hết hạn hoặc không hợp lệ.');
    } finally {
      setLoading(false);
    }
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
            <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M2 17L12 22L22 17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M2 12L12 17L22 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </div>

        <h1 className="login-card__title">Campus Security</h1>
        <p className="login-card__subtitle">
          {mode === 'login' && 'Hệ thống Giám sát An ninh Thông minh'}
          {mode === 'forgot' && 'Khôi phục mật khẩu tài khoản'}
          {mode === 'reset' && 'Đặt lại mật khẩu mới'}
        </p>

        <div className="login-card__divider" />

        {/* ERROR BOX */}
        {error && (
          <div className="login-card__error">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
              <line x1="12" y1="8" x2="12" y2="12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
              <line x1="12" y1="16" x2="12.01" y2="16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
            <span>{error}</span>
          </div>
        )}

        {/* ==================== LOGIN SCREEN ==================== */}
        {mode === 'login' && (
          <>
            <div className="login-card__tabs">
              <button
                type="button"
                className={`login-card__tab ${activeTab === 'google' ? 'login-card__tab--active' : ''}`}
                onClick={() => { setActiveTab('google'); setError(null); }}
              >
                Google Mail
              </button>
              <button
                type="button"
                className={`login-card__tab ${activeTab === 'credentials' ? 'login-card__tab--active' : ''}`}
                onClick={() => { setActiveTab('credentials'); setError(null); }}
              >
                Tài khoản cấp
              </button>
            </div>

            {activeTab === 'google' && (
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
            )}

            {activeTab === 'credentials' && (
              <form className="login-card__form" onSubmit={handleCredentialLoginSubmit}>
                <div className="login-card__input-group">
                  <label className="login-card__input-label">Email</label>
                  <input
                    type="email"
                    required
                    placeholder="name@campus.edu.vn"
                    className="login-card__input"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </div>
                <div className="login-card__input-group">
                  <label className="login-card__input-label">Mật khẩu</label>
                  <input
                    type="password"
                    required
                    placeholder="••••••••"
                    className="login-card__input"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                </div>
                <span
                  className="login-card__forgot-link"
                  onClick={() => { setMode('forgot'); setError(null); }}
                >
                  Quên mật khẩu?
                </span>
                <button
                  type="submit"
                  className="login-card__submit-btn"
                  disabled={loading}
                >
                  {loading ? 'Đang đăng nhập...' : 'Đăng nhập'}
                </button>
              </form>
            )}
          </>
        )}

        {/* ==================== FORGOT PASSWORD SCREEN ==================== */}
        {mode === 'forgot' && (
          <>
            {success ? (
              <div className="login-card__success">
                <svg className="login-card__success-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
                  <path d="M9 12L11 14L15 10" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
                <span>Yêu cầu khôi phục mật khẩu đã được gửi đi! Vui lòng kiểm tra hòm thư của bạn để lấy đường dẫn xác nhận.</span>
              </div>
            ) : (
              <form className="login-card__form" onSubmit={handleForgotSubmit}>
                <p style={{ fontSize: '0.85rem', color: '#94a3b8', textAlign: 'left', margin: '0 0 10px 4px', lineHeight: 1.4 }}>
                  Vui lòng cung cấp email tài khoản của bạn. Hệ thống sẽ gửi một liên kết xác nhận thay đổi mật khẩu mới.
                </p>
                <div className="login-card__input-group">
                  <label className="login-card__input-label">Email tài khoản</label>
                  <input
                    type="email"
                    required
                    placeholder="name@campus.edu.vn"
                    className="login-card__input"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </div>
                <button
                  type="submit"
                  className="login-card__submit-btn"
                  disabled={loading}
                >
                  {loading ? 'Đang gửi...' : 'Gửi link khôi phục'}
                </button>
              </form>
            )}
            <span
              className="login-card__back-link"
              onClick={() => { setMode('login'); setError(null); setSuccess(false); }}
            >
              Quay lại đăng nhập
            </span>
          </>
        )}

        {/* ==================== RESET PASSWORD SCREEN ==================== */}
        {mode === 'reset' && (
          <>
            {success ? (
              <div className="login-card__success">
                <svg className="login-card__success-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
                  <path d="M9 12L11 14L15 10" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
                <span>Đổi mật khẩu thành công! Đang chuyển hướng bạn quay lại trang đăng nhập...</span>
              </div>
            ) : (
              <form className="login-card__form" onSubmit={handleResetSubmit}>
                <div className="login-card__input-group">
                  <label className="login-card__input-label">Mật khẩu mới</label>
                  <input
                    type="password"
                    required
                    placeholder="Tối thiểu 6 ký tự"
                    className="login-card__input"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                </div>
                <div className="login-card__input-group">
                  <label className="login-card__input-label">Nhập lại mật khẩu mới</label>
                  <input
                    type="password"
                    required
                    placeholder="Nhập lại mật khẩu mới"
                    className="login-card__input"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                  />
                </div>
                <button
                  type="submit"
                  className="login-card__submit-btn"
                  disabled={loading}
                >
                  {loading ? 'Đang cập nhật...' : 'Xác nhận đổi mật khẩu'}
                </button>
              </form>
            )}
            {!success && (
              <span
                className="login-card__back-link"
                onClick={() => { setMode('login'); if (onResetComplete) onResetComplete(); setError(null); }}
              >
                Hủy bỏ và quay lại
              </span>
            )}
          </>
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
