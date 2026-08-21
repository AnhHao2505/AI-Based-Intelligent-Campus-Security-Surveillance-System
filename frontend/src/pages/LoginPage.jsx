import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Mail, Lock, Eye, EyeOff, ArrowLeft, Loader2 } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import './LoginPage.css';

export default function LoginPage() {
  const { login, loading } = useAuth();
  const navigate = useNavigate();

  // Mode: 'LOGIN' | 'FORGOT' | 'RESET'
  const [viewMode, setViewMode] = useState('LOGIN');

  // Form states
  const [userCode, setUserCode] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [toastMessage, setToastMessage] = useState('');

  // Sub-view states
  const [forgotEmail, setForgotEmail] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  const handleLoginSubmit = async (e) => {
    e.preventDefault();
    setErrorMessage('');
    setToastMessage('');

    try {
      await login(userCode, password, rememberMe);
      navigate('/schedule');
    } catch (err) {
      setErrorMessage(err.message || 'Đăng nhập thất bại. Vui lòng thử lại.');
    }
  };

  const handleForgotSubmit = (e) => {
    e.preventDefault();
    if (!forgotEmail) {
      setErrorMessage('Vui lòng nhập Email đã đăng ký');
      return;
    }
    setErrorMessage('');
    setToastMessage('Chức năng đang phát triển (Cần Backend Email Service). Vui lòng liên hệ Admin!');
  };

  const handleResetSubmit = (e) => {
    e.preventDefault();
    if (!newPassword || !confirmPassword) {
      setErrorMessage('Vui lòng điền đầy đủ thông tin mật khẩu');
      return;
    }
    if (newPassword !== confirmPassword) {
      setErrorMessage('Mật khẩu xác nhận không trùng khớp');
      return;
    }
    setErrorMessage('');
    setToastMessage('Đặt lại mật khẩu thành công (Mock). Vui lòng đăng nhập bằng mật khẩu mới!');
    setTimeout(() => {
      setViewMode('LOGIN');
      setToastMessage('');
    }, 2000);
  };

  return (
    <div className="login-page-wrapper">
      <h1 className="login-page-title">
        {viewMode === 'LOGIN' && 'LOGIN'}
        {viewMode === 'FORGOT' && 'FORGOT PASSWORD'}
        {viewMode === 'RESET' && 'RESET PASSWORD'}
      </h1>

      <div className="login-card">
        {errorMessage && <div className="login-error-banner">{errorMessage}</div>}
        {toastMessage && <div className="login-toast-banner">{toastMessage}</div>}

        {/* 1. LOGIN VIEW */}
        {viewMode === 'LOGIN' && (
          <form onSubmit={handleLoginSubmit}>
            {/* Input Mã định danh (MSSV / MSNV) */}
            <div className="login-field-group">
              <label className="login-label" htmlFor="userCodeInput">
                Mã định danh (MSSV / MSNV)
              </label>
              <div className="login-input-container">
                <div className="login-icon-box">
                  <Mail size={20} />
                </div>
                <input
                  id="userCodeInput"
                  type="text"
                  className="login-input"
                  placeholder="Ví dụ: GUARD01, FM01..."
                  value={userCode}
                  onChange={(e) => setUserCode(e.target.value)}
                  disabled={loading}
                  autoComplete="username"
                  required
                />
              </div>
            </div>

            {/* Input Password */}
            <div className="login-field-group">
              <label className="login-label" htmlFor="passwordInput">
                Password
              </label>
              <div className="login-input-container">
                <div className="login-icon-box">
                  <Lock size={20} />
                </div>
                <input
                  id="passwordInput"
                  type={showPassword ? 'text' : 'password'}
                  className="login-input"
                  placeholder="********************"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={loading}
                  autoComplete="current-password"
                  required
                />
                <button
                  type="button"
                  className="login-eye-btn"
                  onClick={() => setShowPassword(!showPassword)}
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                >
                  {showPassword ? <EyeOff size={20} /> : <Eye size={20} />}
                </button>
              </div>
            </div>

            {/* Remember Me + Forgot Password */}
            <div className="login-options-row">
              <label className="login-remember-me">
                <input
                  type="checkbox"
                  className="login-checkbox"
                  checked={rememberMe}
                  onChange={(e) => setRememberMe(e.target.checked)}
                />
                <span>Remember me</span>
              </label>

              <button
                type="button"
                className="login-forgot-link"
                onClick={() => {
                  setErrorMessage('');
                  setToastMessage('');
                  setViewMode('FORGOT');
                }}
              >
                Forgot password?
              </button>
            </div>

            {/* Submit Button */}
            <button type="submit" className="login-submit-btn" disabled={loading}>
              {loading ? (
                <>
                  <Loader2 size={20} className="animate-spin" />
                  <span>Đang xác thực...</span>
                </>
              ) : (
                'Login'
              )}
            </button>

            {/* Hint Box for Testing Roles */}
            <div className="test-accounts-hint">
              <strong>Tài khoản Test (Mật khẩu: 123456):</strong>
              <div style={{ marginTop: '4px' }}>• <code>GUARD01</code>: Bảo vệ (INTERNAL_GUARD)</div>
              <div>• <code>FM01</code>: Facility Manager</div>
              <div>• <code>ADMIN01</code>: Admin Quản trị viên</div>
            </div>
          </form>
        )}

        {/* 2. FORGOT PASSWORD VIEW */}
        {viewMode === 'FORGOT' && (
          <form onSubmit={handleForgotSubmit}>
            <p className="login-subtitle">
              Enter your registered email to receive recovery mail and accessibility to reset password
            </p>

            <div className="login-field-group">
              <label className="login-label">Email address</label>
              <div className="login-input-container">
                <div className="login-icon-box">
                  <Mail size={20} />
                </div>
                <input
                  type="email"
                  className="login-input"
                  placeholder="example@email.com"
                  value={forgotEmail}
                  onChange={(e) => setForgotEmail(e.target.value)}
                  required
                />
              </div>
            </div>

            <button type="submit" className="login-submit-btn">
              Send Recovery Mail
            </button>

            <div style={{ textAlign: 'center', marginTop: '16px' }}>
              <button
                type="button"
                className="login-back-btn"
                onClick={() => {
                  setErrorMessage('');
                  setToastMessage('');
                  setViewMode('LOGIN');
                }}
              >
                <ArrowLeft size={16} /> Quay lại Đăng nhập
              </button>
            </div>
          </form>
        )}

        {/* 3. RESET PASSWORD VIEW */}
        {viewMode === 'RESET' && (
          <form onSubmit={handleResetSubmit}>
            <div className="login-field-group">
              <label className="login-label">New Password</label>
              <div className="login-input-container">
                <div className="login-icon-box">
                  <Lock size={20} />
                </div>
                <input
                  type="password"
                  className="login-input"
                  placeholder="example password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  required
                />
              </div>
              <div className="login-helper-text">
                Password needs to be a combination of both character (A-Z) and number (0-9) and includes at least one special and uppercase character
              </div>
            </div>

            <div className="login-field-group">
              <label className="login-label">Confirm New Password</label>
              <div className="login-input-container">
                <div className="login-icon-box">
                  <Lock size={20} />
                </div>
                <input
                  type="password"
                  className="login-input"
                  placeholder="example password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  required
                />
              </div>
            </div>

            <button type="submit" className="login-submit-btn">
              Reset
            </button>

            <div style={{ textAlign: 'center', marginTop: '16px' }}>
              <button
                type="button"
                className="login-back-btn"
                onClick={() => {
                  setErrorMessage('');
                  setToastMessage('');
                  setViewMode('LOGIN');
                }}
              >
                <ArrowLeft size={16} /> Quay lại Đăng nhập
              </button>
            </div>
          </form>
        )}

        <hr className="login-divider" />
        <p className="login-footer-text">
          Need access? Contact your system administrator
        </p>
      </div>
    </div>
  );
}
