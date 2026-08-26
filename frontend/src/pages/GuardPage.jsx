import { Link } from 'react-router-dom';
import './DashboardPage.css';

export default function GuardPage() {
  return (
    <div className="dashboard">
      <div className="dashboard-bg">
        <div className="dashboard-bg-orb dashboard-bg-orb--1" />
        <div className="dashboard-bg-orb dashboard-bg-orb--2" />
      </div>

      <nav className="dashboard-nav">
        <div className="dashboard-nav__brand">
          <span>Campus Security - Guard Terminal</span>
        </div>
        <Link to="/" className="dashboard-nav__logout" style={{ textDecoration: 'none', display: 'flex', alignItems: 'center' }}>
          Quay lại Dashboard
        </Link>
      </nav>

      <main className="dashboard-main">
        <div className="dashboard-welcome">
          <h1>Trạm tuần tra & Giám sát 🛡️</h1>
          <p>Trang này hiển thị với <strong>Bảo vệ nội bộ, Bảo vệ thuê ngoài</strong> và <strong>Admin</strong>.</p>
        </div>

        <div className="dashboard-grid">
          <div className="dashboard-card">
            <h3>Danh sách sự cố & Tuần tra</h3>
            <p>Trạng thái trực ban: Hoạt động bình thường. Không phát hiện đột nhập.</p>
            <div style={{ marginTop: '1.5rem' }}>
              <span className="dashboard-card__status dashboard-card__status--ok">
                ● Hệ thống tuần tra trực tuyến
              </span>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
