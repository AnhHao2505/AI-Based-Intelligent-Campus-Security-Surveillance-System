import { Outlet } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import Sidebar from './Sidebar';
import '../../styles/AppLayout.css';

export default function AppLayout({ children }) {
  const { user, logout } = useAuth();

  return (
    <div className="app-layout">
      <Sidebar user={user} onLogout={logout} />
      <div className="app-layout__content">
        {children ? children : <Outlet />}
      </div>
    </div>
  );
}
