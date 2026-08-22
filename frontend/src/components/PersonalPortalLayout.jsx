import { useState, useEffect } from 'react';
import { NavLink, useNavigate, useLocation } from 'react-router-dom';
import { Clock, Bell, KeyRound, Shield, LogOut } from 'lucide-react';
import { clearAuth } from '../services/authService';
import { getNotifications } from '../api/mockPersonalApi';
import './PersonalPortalLayout.css';

export default function PersonalPortalLayout({ user, onLogout, children }) {
  const navigate = useNavigate();
  const location = useLocation();

  // Real-time system clock
  const [sysTime, setSysTime] = useState('');

  // Unread notifications count
  const [unreadCount, setUnreadCount] = useState(0);

  useEffect(() => {
    const updateClock = () => {
      const now = new Date();
      const timeStr = now.toTimeString().split(' ')[0]; // HH:MM:SS
      setSysTime(timeStr);
    };

    updateClock();
    const interval = setInterval(updateClock, 1000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    const notifs = getNotifications();
    const count = notifs.filter((n) => n.isUnread).length;
    setUnreadCount(count);
  }, [location.pathname]);

  const handleLogoutClick = () => {
    clearAuth();
    if (onLogout) {
      onLogout();
    } else {
      navigate('/login');
    }
  };

  const getInitials = (name) => {
    if (!name) return 'U';
    const parts = name.trim().split(' ');
    return parts[0][0].toUpperCase();
  };

  return (
    <div className="portal-container">
      {/* Sidebar */}
      <aside className="portal-sidebar">
        <div>
          {/* Logo & Campus Brand */}
          <div className="portal-brand">
            <div className="portal-brand__icon">
              <Shield size={20} />
            </div>
            <div className="portal-brand__text">
              <h2>FPTU SecureVision</h2>
              <span>TÂN UYÊN CAMPUS</span>
            </div>
          </div>

          {/* Navigation Links */}
          <nav className="portal-nav">
            <NavLink
              to="/personal/access-history"
              className={({ isActive }) =>
                `portal-nav__item ${isActive ? 'portal-nav__item--active' : ''}`
              }
            >
              <div className="portal-nav__left">
                <Clock size={18} />
                <span>Access History</span>
              </div>
            </NavLink>

            <NavLink
              to="/personal/notifications"
              className={({ isActive }) =>
                `portal-nav__item ${isActive ? 'portal-nav__item--active' : ''}`
              }
            >
              <div className="portal-nav__left">
                <Bell size={18} />
                <span>Notifications</span>
              </div>
              {unreadCount > 0 && <span className="portal-nav__badge">{unreadCount}</span>}
            </NavLink>

            <NavLink
              to="/personal/request-access"
              className={({ isActive }) =>
                `portal-nav__item ${isActive ? 'portal-nav__item--active' : ''}`
              }
            >
              <div className="portal-nav__left">
                <KeyRound size={18} />
                <span>Request Access</span>
              </div>
            </NavLink>
          </nav>
        </div>

        {/* User Profile Footer */}
        <div className="portal-user-footer">
          <div className="portal-user-info">
            <div className="portal-avatar">{getInitials(user?.fullName || user?.full_name)}</div>
            <div className="portal-user-details">
              <h4>{user?.fullName || user?.full_name || 'User'}</h4>
              <p>User Code: {user?.staffCode || user?.user_code || 'SE160000'}</p>
            </div>
          </div>
          <div className="portal-status-badge">
            <span>● SESSION: ACTIVE</span>
          </div>

          <button className="portal-logout-btn" onClick={handleLogoutClick}>
            <LogOut size={14} />
            <span>Đăng xuất</span>
          </button>
        </div>
      </aside>

      {/* Main Content Right Side */}
      <div className="portal-main-area">
        {/* Top Header */}
        <header className="portal-header">
          <h1 className="portal-header__title">Personal Portal</h1>

          <div className="portal-header__right">
            <div className="portal-sys-time">
              <Clock size={14} />
              <span>SYS TIME: {sysTime || '14:35:00'}</span>
            </div>

            <button
              className="portal-notif-btn"
              onClick={() => navigate('/personal/notifications')}
              title="Notifications"
            >
              <Bell size={20} />
              {unreadCount > 0 && <span className="portal-notif-dot" />}
            </button>
          </div>
        </header>

        {/* Body Pages View */}
        <main className="portal-content">{children}</main>
      </div>
    </div>
  );
}
