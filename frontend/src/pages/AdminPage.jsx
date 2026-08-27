import { Link } from 'react-router-dom';
import './DashboardPage.css';

export default function AdminPage() {
  return (
    <div className="dashboard">
      <div className="dashboard-bg">
        <div className="dashboard-bg-orb dashboard-bg-orb--1" />
        <div className="dashboard-bg-orb dashboard-bg-orb--2" />
      </div>

      <nav className="dashboard-nav">
        <div className="dashboard-nav__brand">
          <span>Campus Security - Admin Console</span>
        </div>
        <Link to="/" className="dashboard-nav__logout" style={{ textDecoration: 'none', display: 'flex', alignItems: 'center' }}>
          Quay lại Dashboard
        </Link>
      </nav>

      <main className="dashboard-main">
        <div className="dashboard-welcome">
          <h1>Trang quản trị hệ thống 🔒</h1>
          <p>Trang này chỉ hiển thị với người dùng có vai trò <strong>Quản trị viên (ADMIN)</strong>.</p>
        </div>

        <div className="dashboard-grid">
          <div className="dashboard-card">
            <h3>Bảng điều khiển Admin</h3>
            <p>Tại đây bạn có thể quản lý người dùng, cài đặt hệ thống, và xem log giám sát toàn quyền.</p>
            <div style={{ marginTop: '1.5rem', display: 'flex', gap: '1rem' }}>
              <button className="dashboard-nav__logout" style={{ background: 'rgba(255,255,255,0.1)', cursor: 'not-allowed' }}>
                Quản lý người dùng
              </button>
              <button className="dashboard-nav__logout" style={{ background: 'rgba(255,255,255,0.1)', cursor: 'not-allowed' }}>
                Cấu hình camera
              </button>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
