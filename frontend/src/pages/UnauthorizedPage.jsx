import { Link } from 'react-router-dom';
import '../styles/DashboardPage.css';

export default function UnauthorizedPage() {
  return (
    <div className="dashboard">
      <div className="dashboard-bg">
        <div className="dashboard-bg-orb dashboard-bg-orb--1" />
        <div className="dashboard-bg-orb dashboard-bg-orb--2" />
      </div>

      <nav className="dashboard-nav">
        <div className="dashboard-nav__brand">
          <span>Campus Security</span>
        </div>
      </nav>

      <main className="dashboard-main" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '60vh' }}>
        <div className="dashboard-card" style={{ maxWidth: '500px', width: '100%', textAlign: 'center', padding: '3rem 2rem' }}>
          <div style={{ fontSize: '4rem', marginBottom: '1rem' }}>🚫</div>
          <h2 style={{ color: '#ff4d4d', marginBottom: '1rem', fontSize: '1.8rem' }}>Không có quyền truy cập</h2>
          <p style={{ color: 'rgba(255,255,255,0.7)', marginBottom: '2rem', lineHeight: '1.6' }}>
            Tài khoản của bạn không được phân quyền để truy cập trang này. Vui lòng liên hệ quản trị viên để biết thêm chi tiết.
          </p>
          <Link to="/" className="dashboard-nav__logout" style={{ textDecoration: 'none', background: 'var(--primary-color, #2563eb)', color: '#fff', padding: '0.75rem 1.5rem', borderRadius: '8px', border: 'none', fontWeight: '600', display: 'inline-block' }}>
            Quay lại trang chủ
          </Link>
        </div>
      </main>
    </div>
  );
}
