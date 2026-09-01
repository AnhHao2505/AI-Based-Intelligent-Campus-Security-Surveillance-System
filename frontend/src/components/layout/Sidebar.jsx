import { NavLink } from 'react-router-dom';
import { LayoutDashboard, Video, UserRound, ShieldAlert, LogOut } from 'lucide-react';
import { ROLES, ROLE_LABELS } from '../../constants/roles';
import './Sidebar.css';

export default function Sidebar({ user, onLogout }) {
  const getInitials = (name) => {
    if (!name) return 'U';
    return name
      .split(' ')
      .map((n) => n[0])
      .slice(0, 2)
      .join('')
      .toUpperCase();
  };

  const userRole = user?.role || '';
  const isAdmin = userRole === ROLES.ADMIN;
  const isGuard = userRole === ROLES.INTERNAL_GUARD || userRole === ROLES.OUTSOURCED_GUARD;

  return (
    <aside className="sidebar">
      <div className="sidebar__brand">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
          <path d="M2 17L12 22L22 17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
          <path d="M2 12L12 17L22 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
        <span className="sidebar__brand-text">Campus Security</span>
      </div>

      <nav className="sidebar__nav">
        {isGuard && (
          <NavLink 
            to="/guard" 
            className={({ isActive }) => `sidebar__link ${isActive ? 'sidebar__link--active' : ''}`}
          >
            <ShieldAlert size={20} />
            <span>Giám sát An ninh</span>
          </NavLink>
        )}

        <NavLink 
          to="/dashboard" 
          className={({ isActive }) => `sidebar__link ${isActive ? 'sidebar__link--active' : ''}`}
        >
          <LayoutDashboard size={20} />
          <span>Dashboard</span>
        </NavLink>

        {isAdmin && (
          <>
            <NavLink 
              to="/cameras" 
              className={({ isActive }) => `sidebar__link ${isActive ? 'sidebar__link--active' : ''}`}
            >
              <Video size={20} />
              <span>Quản lý Camera</span>
            </NavLink>

            <NavLink 
              to="/admin/faces" 
              className={({ isActive }) => `sidebar__link ${isActive ? 'sidebar__link--active' : ''}`}
            >
              <UserRound size={20} />
              <span>Quản lý Khuôn mặt</span>
            </NavLink>
          </>
        )}
      </nav>

      <div className="sidebar__footer">
        <div className="sidebar__user">
          <div className="sidebar__avatar">
            {getInitials(user?.fullName)}
          </div>
          <div className="sidebar__user-info">
            <span className="sidebar__username">{user?.fullName}</span>
            <span className="sidebar__userrole">
              {ROLE_LABELS[userRole] || userRole || 'Người dùng'}
            </span>
          </div>
        </div>
        <button className="sidebar__logout" onClick={onLogout} title="Đăng xuất">
          <LogOut size={18} />
          <span>Đăng xuất</span>
        </button>
      </div>
    </aside>
  );
}
