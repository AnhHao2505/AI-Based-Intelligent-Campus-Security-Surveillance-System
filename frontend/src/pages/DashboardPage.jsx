import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { ROLE_LABELS, ROLES } from '../constants/roles';
import './DashboardPage.css';

export default function DashboardPage() {
  const { user, logout, hasRole } = useAuth();

  return (
    <div className="dashboard">
      <div className="dashboard-bg">
        <div className="dashboard-bg-orb dashboard-bg-orb--1" />
        <div className="dashboard-bg-orb dashboard-bg-orb--2" />
      </div>

      <nav className="dashboard-nav">
        <div className="dashboard-nav__brand">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            <path d="M2 17L12 22L22 17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            <path d="M2 12L12 17L22 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
          </svg>
          <span>Campus Security</span>
        </div>
        <button className="dashboard-nav__logout" onClick={logout}>
          Đăng xuất
        </button>
      </nav>

      <main className="dashboard-main">
        <div className="dashboard-welcome">
          <h1>Xin chào, {user.fullName} 👋</h1>
          <p>Chào mừng bạn đến với Hệ thống Giám sát An ninh Thông minh</p>
        </div>

        <div className="dashboard-grid">
          <div className="dashboard-card dashboard-card--profile">
            <h3>Thông tin cá nhân</h3>
            <div className="dashboard-card__rows">
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Họ tên</span>
                <span className="dashboard-card__value">{user.fullName}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Email</span>
                <span className="dashboard-card__value">{user.email}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Mã nhân viên</span>
                <span className="dashboard-card__value">{user.userCode}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Vai trò</span>
                <span className="dashboard-card__value dashboard-card__role-badge">
                  {ROLE_LABELS[user.role] || user.role}
                </span>
              </div>
            </div>
          </div>

          <div className="dashboard-card dashboard-card--status">
            <h3>Truy cập nhanh (Phân quyền)</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginTop: '1rem' }}>
              <Link to="/admin" className="dashboard-nav__logout" style={{ textDecoration: 'none', textAlign: 'center', background: hasRole([ROLES.ADMIN]) ? 'rgba(59, 130, 246, 0.2)' : 'rgba(255, 255, 255, 0.05)', color: hasRole([ROLES.ADMIN]) ? '#60a5fa' : 'rgba(255, 255, 255, 0.4)' }}>
                Trang Admin (Chỉ ADMIN)
              </Link>
              <Link to="/admin/faces" className="dashboard-nav__logout" style={{ textDecoration: 'none', textAlign: 'center', background: hasRole([ROLES.ADMIN]) ? 'rgba(37, 99, 235, 0.3)' : 'rgba(255, 255, 255, 0.05)', color: hasRole([ROLES.ADMIN]) ? '#93c5fd' : 'rgba(255, 255, 255, 0.4)' }}>
                Quản lý Dataset Khuôn mặt (ADMIN)
              </Link>
              <Link to="/guard" className="dashboard-nav__logout" style={{ textDecoration: 'none', textAlign: 'center', background: hasRole([ROLES.ADMIN, ROLES.INTERNAL_GUARD, ROLES.OUTSOURCED_GUARD]) ? 'rgba(16, 185, 129, 0.2)' : 'rgba(255, 255, 255, 0.05)', color: hasRole([ROLES.ADMIN, ROLES.INTERNAL_GUARD, ROLES.OUTSOURCED_GUARD]) ? '#34d399' : 'rgba(255, 255, 255, 0.4)' }}>
                Trạm tuần tra (ADMIN, GUARDS)
              </Link>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

