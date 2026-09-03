import { Link, useLocation } from 'react-router-dom';
import '../styles/DashboardPage.css';

export default function AdminLayout({ children }) {
  const location = useLocation();

  const isActive = (path) => location.pathname === path;

  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <div className="admin-sidebar__top">
          <div className="admin-sidebar__brand">
            <span className="admin-sidebar__logo">P</span>
            <span>Campus Security</span>
          </div>
        </div>

        <nav className="admin-sidebar__nav" aria-label="Admin navigation">
          <Link
            to="/admin"
            className={`admin-sidebar__item ${isActive('/admin') ? 'admin-sidebar__item--active' : ''}`}
          >
            <span className="admin-sidebar__icon"></span>
            <span>Dashboard Overview</span>
          </Link>
          <button type="button" className="admin-sidebar__item">
            <span className="admin-sidebar__icon"></span>
            <span>Live Monitoring</span>
          </button>
          <button type="button" className="admin-sidebar__item">
            <span className="admin-sidebar__icon"></span>
            <span>Alerts &amp; Incidents</span>
          </button>
          <button type="button" className="admin-sidebar__item">
            <span className="admin-sidebar__icon"></span>
            <span>Restricted Zones</span>
          </button>
          <button type="button" className="admin-sidebar__item">
            <span className="admin-sidebar__icon"></span>
            <span>Camera Fleet</span>
          </button>
          <button type="button" className="admin-sidebar__item">
            <span className="admin-sidebar__icon"></span>
            <span>Operator Control</span>
          </button>
          <Link
            to="/admin/faces"
            className={`admin-sidebar__item ${isActive('/admin/faces') ? 'admin-sidebar__item--active' : ''} admin-sidebar__item--link`}
          >
            <span className="admin-sidebar__icon"></span>
            <span>User Database</span>
          </Link>
        </nav>

        <div className="admin-sidebar__footer">SESSION ACTIVE</div>
      </aside>

      <div className="admin-main-panel">
        {children}
      </div>
    </div>
  );
}
