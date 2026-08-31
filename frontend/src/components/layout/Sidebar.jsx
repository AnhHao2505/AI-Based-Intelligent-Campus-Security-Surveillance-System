import { NavLink } from 'react-router-dom';
import { 
  LayoutDashboard, 
  MapPin, 
  Video, 
  UserRound, 
  Cpu, 
  Sun, 
  Moon, 
  LogOut 
} from 'lucide-react';
import { useTheme } from '../../context/ThemeContext';
import './Sidebar.css';

export default function Sidebar({ user, onLogout }) {
  const { theme, toggleTheme } = useTheme();

  const getInitials = (name) => {
    if (!name) return 'U';
    return name
      .split(' ')
      .map((n) => n[0])
      .slice(0, 2)
      .join('')
      .toUpperCase();
  };

  const userRole = user?.role || user?.role_type || '';
  const isAdmin = userRole === 'ADMIN';
  const isFacilityManager = userRole === 'FACILITY_MANAGER';

  return (
    <aside className="sidebar">
      <div className="sidebar__header">
        <NavLink to="/dashboard" className="sidebar__brand">
          <div className="sidebar__logo">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M2 17L12 22L22 17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M2 12L12 17L22 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <div>
            <div className="sidebar__title">FPTU SecureVision</div>
            <div className="sidebar__subtitle">Campus Security</div>
          </div>
        </NavLink>

        <nav className="sidebar__nav">
          <NavLink 
            to="/dashboard" 
            className={({ isActive }) => `sidebar__link ${isActive ? 'sidebar__link--active' : ''}`}
          >
            <LayoutDashboard size={18} />
            <span>Dashboard</span>
          </NavLink>

          {(isAdmin || isFacilityManager) && (
            <NavLink 
              to="/admin/areas" 
              className={({ isActive }) => `sidebar__link ${isActive ? 'sidebar__link--active' : ''}`}
            >
              <MapPin size={18} />
              <span>Cấu hình vùng (Zones)</span>
            </NavLink>
          )}

          {isAdmin && (
            <>
              <NavLink 
                to="/cameras" 
                className={({ isActive }) => `sidebar__link ${isActive ? 'sidebar__link--active' : ''}`}
              >
                <Video size={18} />
                <span>Quản lý Camera</span>
              </NavLink>

              <NavLink 
                to="/admin/faces" 
                className={({ isActive }) => `sidebar__link ${isActive ? 'sidebar__link--active' : ''}`}
              >
                <UserRound size={18} />
                <span>Quản lý Khuôn mặt</span>
              </NavLink>
            </>
          )}

          <div className="sidebar__link sidebar__link--disabled">
            <Cpu size={18} />
            <span>Thiết lập AI</span>
            <span className="sidebar__badge-soon">Soon</span>
          </div>
        </nav>
      </div>

      <div className="sidebar__bottom">
        {/* Theme Toggle Button */}
        <button
          type="button"
          className="sidebar__theme-toggle"
          onClick={toggleTheme}
          title={theme === 'light' ? 'Chuyển sang Dark Mode' : 'Chuyển sang Light Mode'}
        >
          {theme === 'light' ? (
            <>
              <Moon size={16} />
              <span>Dark Mode</span>
            </>
          ) : (
            <>
              <Sun size={16} />
              <span>Light Mode</span>
            </>
          )}
        </button>

        {/* User Profile Footer */}
        <div className="sidebar__footer">
          <div className="sidebar__user">
            <div className="sidebar__avatar">
              {getInitials(user?.fullName)}
            </div>
            <div className="sidebar__user-info">
              <span className="sidebar__username">{user?.fullName || 'User'}</span>
              <span className="sidebar__userrole">
                {userRole === 'ADMIN' ? 'Admin' : userRole === 'FACILITY_MANAGER' ? 'Facility Manager' : userRole}
              </span>
            </div>
          </div>
          <button className="sidebar__logout-btn" onClick={onLogout} title="Đăng xuất">
            <LogOut size={16} />
          </button>
        </div>
      </div>
    </aside>
  );
}
