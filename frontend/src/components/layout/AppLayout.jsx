import Sidebar from './Sidebar';
import './AppLayout.css';

export default function AppLayout({ user, onLogout, children }) {
  return (
    <div className="app-layout">
      <Sidebar user={user} onLogout={onLogout} />
      <div className="app-layout__content">
        {children}
      </div>
    </div>
  );
}
