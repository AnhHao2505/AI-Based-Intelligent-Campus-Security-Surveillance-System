import { Outlet } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useUiStore } from '../../store/useUiStore';
import Sidebar from './Sidebar';
import '../../styles/AppLayout.css';

export default function AppLayout({ children }) {
  const { user, logout } = useAuth();
  const sidebarCollapsed = useUiStore((state) => state.sidebarCollapsed);

  return (
    <div className="app-layout">
      <Sidebar user={user} onLogout={logout} />
      <div className={`app-layout__content ${sidebarCollapsed ? 'app-layout__content--collapsed' : ''}`}>
        {children ? children : <Outlet />}
      </div>
    </div>
  );
}
