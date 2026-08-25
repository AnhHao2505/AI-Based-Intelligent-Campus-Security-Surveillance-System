import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import './DashboardPage.css';

const ROLE_LABELS = {
  ADMIN: 'Quản trị viên',
  FACILITY_MANAGER: 'Quản lý cơ sở',
  INTERNAL_GUARD: 'Bảo vệ nội bộ',
  OUTSOURCED_GUARD: 'Bảo vệ thuê ngoài',
};

export default function DashboardPage() {
  const { user, logout } = useAuth();

  const isAreaManager = user?.role === 'ADMIN' || user?.role === 'FACILITY_MANAGER';

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
          <h1>Xin chào, {user?.fullName} 👋</h1>
          <p>Chào mừng bạn đến với Hệ thống Giám sát An ninh Thông minh</p>
        </div>

        {isAreaManager && (
          <div style={{ marginBottom: '24px' }}>
            <Link 
              to="/admin/areas" 
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '8px',
                padding: '10px 18px',
                backgroundColor: '#10b981',
                color: '#ffffff',
                borderRadius: '8px',
                textDecoration: 'none',
                fontWeight: '500',
                fontSize: '14px'
              }}
            >
              📍 Quản lý Khu vực (M06)
            </Link>
          </div>
        )}

        <div className="dashboard-grid">
          <div className="dashboard-card dashboard-card--profile">
            <h3>Thông tin cá nhân</h3>
            <div className="dashboard-card__rows">
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Họ tên</span>
                <span className="dashboard-card__value">{user?.fullName}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Email</span>
                <span className="dashboard-card__value">{user?.email}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Mã nhân viên</span>
                <span className="dashboard-card__value">{user?.userCode || user?.staffCode}</span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Vai trò</span>
                <span className="dashboard-card__value dashboard-card__role-badge">
                  {ROLE_LABELS[user?.role] || user?.role}
                </span>
              </div>
            </div>
          </div>

          <div className="dashboard-card dashboard-card--status">
            <h3>Trạng thái hệ thống</h3>
            <div className="dashboard-card__rows">
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Xác thực</span>
                <span className="dashboard-card__status dashboard-card__status--ok">
                  ● Đã đăng nhập
                </span>
              </div>
              <div className="dashboard-card__row">
                <span className="dashboard-card__label">Token</span>
                <span className="dashboard-card__status dashboard-card__status--ok">
                  ● Hợp lệ (24h)
                </span>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
